package com.nogada.imageclean;

import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;
import android.test.ApplicationTestCase;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.UUID;

public final class QueueRecoveryTest extends ApplicationTestCase<CleanApp> {
    private CleanApp app;
    private Uri output;

    public QueueRecoveryTest() {
        super(CleanApp.class);
    }

    @Override protected void setUp() throws Exception {
        super.setUp();
        createApplication();
        app = getApplication();
        app.clear();
    }

    @Override protected void tearDown() throws Exception {
        if (output != null) {
            try { app.getContentResolver().delete(output, null, null); }
            catch (Exception ignored) { }
        }
        app.clear();
        if (app != getApplication()) app.onTerminate();
        terminateApplication();
        super.tearDown();
    }

    public void testVisibleCompletePublishRecoversDone() throws Exception {
        File input = createIncoming();
        CleanApp.Item item = add(input);
        byte[] bytes = new byte[] {1, 2, 3, 4, 5};
        output = insertPending();
        assertTrue(app.beginPublish(item.id, output.toString(), "image/png", "갤러리에 저장 중", bytes.length));
        write(output, bytes);
        commit(output);

        restartApplication();

        CleanApp.Item recovered = onlyItem();
        assertEquals("done", recovered.state);
        assertEquals(bytes.length, recovered.outputBytes);
        assertFalse(input.exists());
    }

    public void testPendingIncompletePublishIsDeletedAndRetried() throws Exception {
        File input = createIncoming();
        CleanApp.Item item = add(input);
        output = insertPending();
        assertTrue(app.beginPublish(item.id, output.toString(), "image/png", "갤러리에 저장 중", 8L));
        write(output, new byte[] {1, 2, 3});

        restartApplication();

        CleanApp.Item recovered = onlyItem();
        assertEquals("ready", recovered.state);
        assertEquals("", recovered.output);
        assertEquals(0L, recovered.outputBytes);
        assertTrue(input.exists());
        assertFalse(mediaRowExists(output));
    }

    public void testDeletedDoneOutputBecomesMissing() throws Exception {
        File input = createIncoming();
        CleanApp.Item item = add(input);
        byte[] bytes = new byte[] {9, 8, 7, 6};
        output = insertPending();
        assertTrue(app.beginPublish(item.id, output.toString(), "image/png", "갤러리에 저장 중", bytes.length));
        write(output, bytes);
        commit(output);
        assertTrue(app.update(item.id, "done", output.toString(), "image/png", "저장 완료"));
        assertEquals(1, app.getContentResolver().delete(output, null, null));

        restartApplication();

        CleanApp.Item recovered = waitForState("missing");
        assertTrue(recovered.details.contains("저장된 사본을 찾을 수 없습니다"));
        output = null;
    }

    public void testInterruptedWorkingReturnsReady() throws Exception {
        File input = createIncoming();
        CleanApp.Item item = add(input);
        assertTrue(app.update(item.id, "working", null, null, "처리 중"));

        restartApplication();

        CleanApp.Item recovered = onlyItem();
        assertEquals("ready", recovered.state);
        assertTrue(recovered.details.contains("중단된 작업"));
    }

    public void testStartupDeletesOnlyUnreferencedIncomingAndProcessingCache() throws Exception {
        File referenced = createIncoming();
        add(referenced);
        File orphan = createIncoming();
        File unfinished = writeFile(new File(referenced.getParentFile(), UUID.randomUUID() + ".part"));
        File imageCache = writeFile(new File(app.getCacheDir(), "imageclean_" + UUID.randomUUID()));
        File cleanCache = writeFile(new File(app.getCacheDir(), "clean_" + UUID.randomUUID() + ".png"));
        File unrelatedCache = writeFile(new File(app.getCacheDir(), "unrelated_" + UUID.randomUUID()));

        restartApplication();

        assertTrue(referenced.exists());
        assertFalse(orphan.exists());
        assertFalse(unfinished.exists());
        assertFalse(imageCache.exists());
        assertFalse(cleanCache.exists());
        assertTrue(unrelatedCache.exists());
        assertTrue(unrelatedCache.delete());
    }

    private File createIncoming() throws Exception {
        File directory = new File(app.getFilesDir(), "incoming");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File file = new File(directory, UUID.randomUUID() + ".img");
        try (FileOutputStream stream = new FileOutputStream(file)) {
            stream.write(new byte[] {4, 3, 2, 1});
        }
        return file;
    }

    private CleanApp.Item add(File input) {
        assertTrue(app.add(Uri.fromFile(input), "입력 이미지"));
        return onlyItem();
    }

    private File writeFile(File file) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(file)) {
            stream.write(new byte[] {5, 6, 7});
        }
        return file;
    }

    private Uri insertPending() {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "queue-recovery-" + UUID.randomUUID() + ".png");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ImageCleanTest");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = app.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        assertNotNull(uri);
        return uri;
    }

    private void write(Uri uri, byte[] bytes) throws Exception {
        try (OutputStream stream = app.getContentResolver().openOutputStream(uri, "w")) {
            assertNotNull(stream);
            stream.write(bytes);
        }
    }

    private void commit(Uri uri) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.IS_PENDING, 0);
        assertEquals(1, app.getContentResolver().update(uri, values, null, null));
    }

    private boolean mediaRowExists(Uri uri) {
        try (android.database.Cursor cursor = app.getContentResolver().query(uri,
                new String[] {MediaStore.MediaColumns._ID}, null, null, null)) {
            return cursor != null && cursor.moveToFirst();
        }
    }

    private void restartApplication() throws Exception {
        Context base = app.getBaseContext();
        app.onTerminate();
        app = (CleanApp) Instrumentation.newApplication(CleanApp.class, base);
        app.onCreate();
    }

    private CleanApp.Item onlyItem() {
        ArrayList<CleanApp.Item> items = app.snapshot();
        assertEquals(1, items.size());
        return items.get(0);
    }

    private CleanApp.Item waitForState(String state) throws Exception {
        long deadline = System.currentTimeMillis() + 5000L;
        CleanApp.Item item = onlyItem();
        while (!state.equals(item.state) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
            item = onlyItem();
        }
        assertEquals(state, item.state);
        return item;
    }
}
