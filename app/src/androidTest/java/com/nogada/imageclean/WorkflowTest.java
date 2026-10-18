package com.nogada.imageclean;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.provider.MediaStore;
import android.test.InstrumentationTestCase;
import java.io.OutputStream;
import java.util.ArrayList;

public final class WorkflowTest extends InstrumentationTestCase {
    public void testBatchPublishesCleanCopiesAndPreservesInputs() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        CleanApp app = (CleanApp) context.getApplicationContext();
        ArrayList<Uri> inputs = new ArrayList<>();
        ArrayList<Uri> outputs = new ArrayList<>();
        Activity activity = getInstrumentation().startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            app.clear();
            for (int index = 0; index < 3; index++) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "workflow_fixture_" + index + ".png");
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ImageCleanTests");
                Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                assertNotNull(uri);
                inputs.add(uri);
                Bitmap bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.rgb(index * 60, 100, 150));
                try (OutputStream stream = context.getContentResolver().openOutputStream(uri)) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
                bitmap.recycle();
                app.add(uri, "테스트 이미지 " + (index + 1));
            }
            getInstrumentation().runOnMainSync(() -> context.startForegroundService(new Intent(context, CleanService.class)));
            long deadline = System.currentTimeMillis() + 45000;
            while (System.currentTimeMillis() < deadline) {
                int finished = 0;
                for (CleanApp.Item item : app.snapshot()) if ("done".equals(item.state) || "error".equals(item.state)) finished++;
                if (finished == 3 && !app.running) break;
                Thread.sleep(100);
            }
            assertFalse("service finished", app.running);
            assertEquals(3, app.snapshot().size());
            for (CleanApp.Item item : app.snapshot()) {
                assertEquals(item.details, "done", item.state);
                Uri output = Uri.parse(item.output);
                outputs.add(output);
                assertFalse(inputs.contains(output));
                try (java.io.InputStream stream = context.getContentResolver().openInputStream(output)) { assertTrue(stream.read() >= 0); }
                try (java.io.InputStream stream = context.getContentResolver().openInputStream(Uri.parse(item.input))) { assertTrue(stream.read() >= 0); }
            }
            app.clear();
            assertEquals(0, app.snapshot().size());
            for (Uri output : outputs) try (java.io.InputStream stream = context.getContentResolver().openInputStream(output)) { assertTrue(stream.read() >= 0); }
        } finally {
            getInstrumentation().runOnMainSync(activity::finish);
            app.clear();
            for (Uri uri : inputs) context.getContentResolver().delete(uri, null, null);
            for (Uri uri : outputs) context.getContentResolver().delete(uri, null, null);
        }
    }
}
