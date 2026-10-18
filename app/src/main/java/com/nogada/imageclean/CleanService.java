package com.nogada.imageclean;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.MediaStore;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class CleanService extends Service {
    private static final String CHANNEL = "image_processing";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private PowerManager.WakeLock wakeLock;
    private CleanApp app;
    private Future<?> task;

    @Override public void onCreate() {
        super.onCreate();
        app = (CleanApp) getApplication();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "이미지 처리", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent pause = PendingIntent.getService(this, 1, new Intent(this, CleanService.class).setAction("pause"), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name)).setContentText(text).setContentIntent(open)
                .setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "현재 사진 후 중지", pause).build()).build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "pause".equals(intent.getAction())) {
            app.stopRequested = true;
            app.notifyChanged();
            if (!app.running) stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(1, notification("사진을 준비하고 있습니다"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        if (app.running) return START_NOT_STICKY;
        app.running = true;
        app.stopRequested = false;
        boolean fingerprint = intent != null && intent.getBooleanExtra("fingerprint", false);
        boolean jpeg = intent != null && intent.getBooleanExtra("jpeg", false);
        boolean reprocess = intent != null && intent.getBooleanExtra("reprocess", false);
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":processing");
        wakeLock.acquire(6 * 60 * 60 * 1000L);
        app.notifyChanged();
        task = worker.submit(() -> process(fingerprint, jpeg, reprocess));
        return START_NOT_STICKY;
    }

    private void process(boolean fingerprint, boolean jpeg, boolean reprocess) {
        try {
            CleanApp.Item item;
            int count = 0;
            while (!app.stopRequested && !Thread.currentThread().isInterrupted() && (item = app.next()) != null) {
                if (!app.update(item.id, "working", null, null, "메타데이터 정리 중")) break;
                getSystemService(NotificationManager.class).notify(1, notification((count + 1) + "번째 사진 처리 중"));
                ImageProcessor.Result result = null;
                try {
                    result = ImageProcessor.process(this, Uri.parse(item.input), getCacheDir(), fingerprint, jpeg, reprocess);
                    checkInterrupted();
                    Uri output = publish(item.id, result);
                    app.update(item.id, "done", output.toString(), result.mimeType,
                            result.width + " x " + result.height + " / " + size(result.cleanedBytes) + "\n" + result.details);
                } catch (java.io.InterruptedIOException exception) {
                    app.update(item.id, "ready", "", null, "처리가 중단되었습니다. 다시 시작할 수 있습니다.");
                    break;
                } catch (OutOfMemoryError error) {
                    app.update(item.id, "error", null, null, "이 사진을 처리할 메모리가 부족합니다. 더 작은 해상도의 사진을 선택해 주세요.");
                } catch (Exception exception) {
                    String message = exception instanceof SecurityException ? "사진 읽기 권한이 만료되었습니다. 목록을 비우고 사진을 다시 선택해 주세요." : exception.getMessage();
                    app.update(item.id, "error", null, null, message == null ? "처리하지 못했습니다. 파일 형식과 저장 공간을 확인해 주세요." : message);
                } finally {
                    if (result != null) result.file.delete();
                }
                count++;
            }
        } finally {
            app.running = false;
            app.stopRequested = false;
            app.notifyChanged();
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private Uri publish(String itemId, ImageProcessor.Result result) throws IOException {
        checkInterrupted();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, result.file.getName());
        values.put(MediaStore.Images.Media.MIME_TYPE, result.mimeType);
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Ginppai-AI-Photo");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("갤러리에 저장할 수 없습니다. 저장 공간을 확인해 주세요.");
        boolean committed = false;
        try {
            String details = result.width + " x " + result.height + " / " + size(result.cleanedBytes) + "\n" + result.details;
            if (!app.beginPublish(itemId, uri.toString(), result.mimeType, details, result.cleanedBytes)) throw new IOException("저장 기록을 쓰지 못했습니다. 저장 공간을 확인해 주세요.");
            try (FileInputStream input = new FileInputStream(result.file);
                 OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
                if (output == null) throw new IOException("저장할 파일을 열 수 없습니다.");
                byte[] buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    checkInterrupted();
                    output.write(buffer, 0, read);
                }
            }
            checkInterrupted();
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            if (getContentResolver().update(uri, values, null, null) != 1) throw new IOException("갤러리 저장을 완료하지 못했습니다.");
            committed = true;
            return uri;
        } finally {
            if (!committed) getContentResolver().delete(uri, null, null);
        }
    }

    private void checkInterrupted() throws java.io.InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("처리가 중단되었습니다.");
    }

    public static String size(long bytes) {
        return bytes >= 1024 * 1024 ? String.format(java.util.Locale.KOREA, "%.1f MB", bytes / 1048576.0) : Math.max(1, bytes / 1024) + " KB";
    }

    @Override public void onTimeout(int startId, int foregroundServiceType) {
        app.stopRequested = true;
        if (task != null) task.cancel(true);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        app.stopRequested = true;
        if (app.running && task != null) task.cancel(true);
        worker.shutdownNow();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
