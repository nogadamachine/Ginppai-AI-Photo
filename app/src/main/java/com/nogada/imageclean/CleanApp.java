package com.nogada.imageclean;

import android.app.Application;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CleanApp extends Application {
    public static final class Item {
        public String id;
        public String input;
        public String name;
        public String state = "ready";
        public String output = "";
        public String details = "";
        public String mime = "image/png";
        public long outputBytes;
        public boolean outputOwned;

        Item copy() {
            Item copied = new Item();
            copied.id = id;
            copied.input = input;
            copied.name = name;
            copied.state = state;
            copied.output = output;
            copied.details = details;
            copied.mime = mime;
            copied.outputBytes = outputBytes;
            copied.outputOwned = outputOwned;
            return copied;
        }

        void restore(Item saved) {
            id = saved.id;
            input = saved.input;
            name = saved.name;
            state = saved.state;
            output = saved.output;
            details = saved.details;
            mime = saved.mime;
            outputBytes = saved.outputBytes;
            outputOwned = saved.outputOwned;
        }
    }

    private enum PublishedState { COMPLETE, INCOMPLETE }

    private final ArrayList<Item> items = new ArrayList<>();
    private final CopyOnWriteArrayList<Runnable> observers = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService recovery = Executors.newSingleThreadExecutor();
    public volatile boolean running;
    public final java.util.concurrent.atomic.AtomicInteger importsInFlight = new java.util.concurrent.atomic.AtomicInteger();
    public volatile boolean stopRequested;
    public volatile String storageError = "";

    @Override public void onCreate() {
        super.onCreate();
        removeRetiredOptions();
        boolean loaded = load();
        cleanupStartupFiles();
        if (!loaded) return;
        recoverInterruptedAndPublishing();
        validateDoneOutputsAsync();
    }

    void removeRetiredOptions() {
        getSharedPreferences("options", MODE_PRIVATE).edit()
                .remove("fingerprint").remove("reprocess").remove("strength").apply();
    }

    @Override public void onTerminate() {
        recovery.shutdownNow();
        super.onTerminate();
    }

    private boolean load() {
        File base = stateFile().getBaseFile();
        if (!base.exists()) return true;
        try {
            JSONArray saved = new JSONArray(new String(stateFile().readFully(), StandardCharsets.UTF_8));
            for (int index = 0; index < saved.length(); index++) {
                JSONObject object = saved.getJSONObject(index);
                Item item = new Item();
                item.id = object.getString("id");
                item.input = object.getString("input");
                item.name = object.getString("name");
                item.state = object.optString("state", "ready");
                item.output = object.optString("output");
                item.details = object.optString("details");
                item.mime = object.optString("mime", "image/png");
                item.outputBytes = object.optLong("outputBytes", 0L);
                item.outputOwned = object.optBoolean("outputOwned", false);
                items.add(item);
            }
            return true;
        } catch (Exception exception) {
            items.clear();
            storageError = "이전 작업 목록을 불러오지 못했습니다. 사진을 다시 선택해 주세요.";
            return false;
        }
    }

    private void cleanupStartupFiles() {
        HashSet<String> referenced = new HashSet<>();
        synchronized (this) {
            for (Item item : items) {
                Uri uri = parseUri(item.input);
                File file = ownedIncomingFile(uri);
                if (file != null) referenced.add(file.getPath());
            }
        }
        File incoming = new File(getFilesDir(), "incoming");
        File[] staged = incoming.listFiles();
        if (staged != null) {
            for (File file : staged) {
                try {
                    File canonical = file.getCanonicalFile();
                    if (canonical.isFile() && (canonical.getName().endsWith(".part") || !referenced.contains(canonical.getPath()))) {
                        canonical.delete();
                    }
                } catch (Exception ignored) { }
            }
        }
        File[] cached = getCacheDir().listFiles();
        if (cached != null) {
            for (File file : cached) {
                String name = file.getName();
                if (file.isFile() && (name.startsWith("imageclean_") || name.startsWith("clean_"))) {
                    file.delete();
                }
            }
        }
    }

    private void recoverInterruptedAndPublishing() {
        ArrayList<Item> before = new ArrayList<>();
        ArrayList<Item> completed = new ArrayList<>();
        boolean changed = false;
        synchronized (this) {
            for (Item item : items) {
                if ("working".equals(item.state)) {
                    before.add(item.copy());
                    item.state = "ready";
                    item.details = "중단된 작업입니다. 다시 시작해 주세요.";
                    changed = true;
                } else if ("publishing".equals(item.state)) {
                    before.add(item.copy());
                    Uri output = parseUri(item.output);
                    if (publishedState(output, item.outputBytes) == PublishedState.COMPLETE) {
                        item.state = "done";
                        completed.add(item.copy());
                    } else {
                        deleteOwnedOutput(item);
                        item.state = "ready";
                        item.output = "";
                        item.outputBytes = 0L;
                        item.outputOwned = false;
                        item.details = "중단된 저장을 정리했습니다. 다시 시작해 주세요.";
                    }
                    changed = true;
                }
            }
            if (changed && !save()) {
                for (Item saved : before) {
                    Item current = find(saved.id);
                    if (current != null) current.restore(saved);
                }
                completed.clear();
            }
        }
        for (Item item : completed) releaseInputIfUnused(item.input, item.id);
        if (changed) notifyChanged();
    }

    private void validateDoneOutputsAsync() {
        ArrayList<Item> done = new ArrayList<>();
        synchronized (this) {
            for (Item item : items) if ("done".equals(item.state)) done.add(item.copy());
        }
        if (done.isEmpty()) return;
        recovery.execute(() -> {
            for (Item saved : done) {
                if (canReadOutput(saved.output)) {
                    releaseInputIfUnused(saved.input, saved.id);
                    continue;
                }
                synchronized (CleanApp.this) {
                    Item current = find(saved.id);
                    if (current == null || !"done".equals(current.state) || !current.output.equals(saved.output)) continue;
                    Item before = current.copy();
                    current.state = "missing";
                    current.details = "갤러리에서 저장된 사본을 찾을 수 없습니다. 목록에서 제외한 뒤 다시 정리해 주세요.";
                    if (!save()) current.restore(before);
                }
                notifyChanged();
            }
        });
    }

    private AtomicFile stateFile() {
        return new AtomicFile(new File(getFilesDir(), "queue.json"));
    }

    public synchronized ArrayList<Item> snapshot() {
        ArrayList<Item> result = new ArrayList<>();
        for (Item item : items) result.add(item.copy());
        return result;
    }

    public synchronized boolean add(Uri uri, String name) {
        for (Item item : items) if (item.input.equals(uri.toString()) && !"done".equals(item.state) && !"missing".equals(item.state)) return true;
        Item item = new Item();
        item.id = UUID.randomUUID().toString();
        item.input = uri.toString();
        item.name = name;
        items.add(item);
        boolean saved = save();
        if (!saved) items.remove(item);
        notifyChanged();
        return saved;
    }

    public synchronized Item next() {
        for (Item item : items) if ("ready".equals(item.state)) return item.copy();
        return null;
    }

    public synchronized boolean beginPublish(String id, String outputUri, String mime, String details, long expectedBytes) {
        Item item = find(id);
        if (item == null || outputUri == null || outputUri.isEmpty() || expectedBytes <= 0) return false;
        Item before = item.copy();
        item.state = "publishing";
        item.output = outputUri;
        item.mime = mime == null ? item.mime : mime;
        item.details = details == null ? "갤러리에 저장 중" : details;
        item.outputBytes = expectedBytes;
        item.outputOwned = isMediaStoreImageUri(parseUri(outputUri));
        boolean saved = save();
        if (!saved) item.restore(before);
        notifyChanged();
        return saved;
    }

    public synchronized boolean update(String id, String state, String output, String mime, String details) {
        Item item = find(id);
        if (item == null) return false;
        Item before = item.copy();
        item.state = state;
        if (output != null) item.output = output;
        if (mime != null) item.mime = mime;
        item.details = details == null ? "" : details;
        if ("done".equals(state) && item.outputBytes <= 0 && item.output != null && !item.output.isEmpty()) {
            item.outputBytes = querySize(parseUri(item.output));
        }
        boolean saved = save();
        if (!saved) item.restore(before);
        notifyChanged();
        if (saved && "done".equals(state)) releaseInputIfUnused(item.input, item.id);
        return saved;
    }

    public synchronized void retryFailures() {
        ArrayList<Item> changed = new ArrayList<>();
        for (Item item : items) {
            if ("error".equals(item.state)) {
                changed.add(item.copy());
                item.state = "ready";
            }
        }
        if (!changed.isEmpty() && !save()) {
            for (Item saved : changed) {
                Item current = find(saved.id);
                if (current != null) current.restore(saved);
            }
        }
        notifyChanged();
    }

    public synchronized void remove(String id) {
        if (running) return;
        int index = indexOf(id);
        if (index < 0) return;
        Item removed = items.remove(index);
        if (!save()) {
            items.add(index, removed);
        } else {
            if ("publishing".equals(removed.state)) deleteOwnedOutput(removed);
            releaseInputIfUnused(removed.input, removed.id);
        }
        notifyChanged();
    }

    public synchronized void clear() {
        if (running) return;
        ArrayList<Item> removed = new ArrayList<>(items);
        items.clear();
        if (!save()) {
            items.addAll(removed);
        } else {
            HashSet<String> released = new HashSet<>();
            for (Item item : removed) {
                if ("publishing".equals(item.state)) deleteOwnedOutput(item);
                if (released.add(item.input)) releaseInputIfUnused(item.input, item.id);
            }
        }
        notifyChanged();
    }

    private Item find(String id) {
        for (Item item : items) if (item.id.equals(id)) return item;
        return null;
    }

    private int indexOf(String id) {
        for (int index = 0; index < items.size(); index++) if (items.get(index).id.equals(id)) return index;
        return -1;
    }

    private boolean save() {
        FileOutputStream stream = null;
        AtomicFile file = stateFile();
        try {
            JSONArray data = new JSONArray();
            for (Item item : items) {
                JSONObject object = new JSONObject();
                object.put("id", item.id).put("input", item.input).put("name", item.name)
                        .put("state", item.state).put("output", item.output).put("details", item.details).put("mime", item.mime)
                        .put("outputBytes", item.outputBytes).put("outputOwned", item.outputOwned);
                data.put(object);
            }
            stream = file.startWrite();
            stream.write(data.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(stream);
            storageError = "";
            return true;
        } catch (Exception exception) {
            if (stream != null) {
                try { file.failWrite(stream); }
                catch (Exception ignored) { }
            }
            storageError = "작업 목록 저장에 실패했습니다. 기기의 저장 공간을 확인해 주세요.";
            return false;
        }
    }

    private PublishedState publishedState(Uri uri, long expectedBytes) {
        if (uri == null || expectedBytes <= 0) return PublishedState.INCOMPLETE;
        try (Cursor cursor = getContentResolver().query(uri,
                new String[] {MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return PublishedState.INCOMPLETE;
            int pending = cursor.isNull(0) ? 1 : cursor.getInt(0);
            long size = cursor.isNull(1) ? 0L : cursor.getLong(1);
            return pending == 0 && size == expectedBytes ? PublishedState.COMPLETE : PublishedState.INCOMPLETE;
        } catch (Exception exception) {
            return PublishedState.INCOMPLETE;
        }
    }

    private long querySize(Uri uri) {
        if (uri == null) return 0L;
        try (Cursor cursor = getContentResolver().query(uri, new String[] {MediaStore.MediaColumns.SIZE}, null, null, null)) {
            return cursor != null && cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getLong(0) : 0L;
        } catch (Exception exception) {
            return 0L;
        }
    }

    private boolean canReadOutput(String value) {
        Uri uri = parseUri(value);
        if (uri == null) return false;
        try (android.content.res.AssetFileDescriptor descriptor = getContentResolver().openAssetFileDescriptor(uri, "r")) {
            return descriptor != null;
        } catch (Exception exception) {
            return false;
        }
    }

    private void deleteOwnedOutput(Item item) {
        Uri uri = parseUri(item.output);
        if (!item.outputOwned || !isMediaStoreImageUri(uri)) return;
        try { getContentResolver().delete(uri, null, null); }
        catch (Exception ignored) { }
    }

    private boolean isMediaStoreImageUri(Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme()) || !MediaStore.AUTHORITY.equals(uri.getAuthority())) return false;
        String path = uri.getPath();
        return path != null && path.contains("/images/");
    }

    private Uri parseUri(String value) {
        if (value == null || value.isEmpty()) return null;
        try { return Uri.parse(value); }
        catch (RuntimeException exception) { return null; }
    }

    private void releaseInputIfUnused(String input, String excludedId) {
        if (input == null || input.isEmpty()) return;
        synchronized (this) {
            for (Item other : items) {
                if (!other.id.equals(excludedId) && input.equals(other.input) && needsInput(other.state)) return;
            }
        }
        Uri uri = parseUri(input);
        if (deleteOwnedIncoming(uri)) return;
        if (uri == null || !"content".equals(uri.getScheme())) return;
        try { getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException | IllegalArgumentException ignored) { }
    }

    private boolean needsInput(String state) {
        return !"done".equals(state) && !"missing".equals(state);
    }

    private boolean deleteOwnedIncoming(Uri uri) {
        File candidate = ownedIncomingFile(uri);
        if (candidate == null) return false;
        try {
            return !candidate.exists() || candidate.isFile() && candidate.delete();
        } catch (Exception exception) {
            return false;
        }
    }

    private File ownedIncomingFile(Uri uri) {
        if (uri == null || !"file".equals(uri.getScheme()) || uri.getPath() == null) return null;
        try {
            File incoming = new File(getFilesDir(), "incoming").getCanonicalFile();
            File candidate = new File(uri.getPath()).getCanonicalFile();
            String root = incoming.getPath() + File.separator;
            return candidate.getPath().startsWith(root) ? candidate : null;
        } catch (Exception exception) {
            return null;
        }
    }

    public void observe(Runnable observer) { observers.addIfAbsent(observer); }
    public void unobserve(Runnable observer) { observers.remove(observer); }
    public void notifyChanged() { handler.post(() -> { for (Runnable observer : observers) observer.run(); }); }
}
