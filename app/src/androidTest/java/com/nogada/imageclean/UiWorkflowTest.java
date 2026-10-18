package com.nogada.imageclean;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

public final class UiWorkflowTest extends InstrumentationTestCase {
    private Context context;
    private CleanApp app;
    private SharedPreferences preferences;
    private ArrayList<CleanApp.Item> originalItems;
    private boolean hadJpegPreference;
    private boolean originalJpegPreference;
    private boolean hadFingerprintPreference;
    private boolean originalFingerprintPreference;
    private boolean hadReprocessPreference;
    private boolean originalReprocessPreference;
    private String originalStorageError;
    private File fixtureDirectory;
    private Activity activity;

    @Override protected void setUp() throws Exception {
        super.setUp();
        context = getInstrumentation().getTargetContext();
        app = (CleanApp) context.getApplicationContext();
        assertFalse("UI 테스트는 실행 중인 정리 작업이 없을 때 수행해야 합니다.", app.running);
        assertEquals("UI 테스트는 사진 준비 작업이 없을 때 수행해야 합니다.", 0, app.importsInFlight.get());
        originalItems = app.snapshot();
        originalStorageError = app.storageError;
        preferences = context.getSharedPreferences("options", Context.MODE_PRIVATE);
        hadJpegPreference = preferences.contains("jpeg");
        originalJpegPreference = preferences.getBoolean("jpeg", false);
        hadFingerprintPreference = preferences.contains("fingerprint");
        originalFingerprintPreference = preferences.getBoolean("fingerprint", false);
        hadReprocessPreference = preferences.contains("reprocess");
        originalReprocessPreference = preferences.getBoolean("reprocess", false);
        fixtureDirectory = new File(context.getCacheDir(), "ui_workflow_" + System.nanoTime());
        assertTrue(fixtureDirectory.mkdirs());
        replaceItems(new ArrayList<>());
        setPreferences(false, false, false);
        app.storageError = "";
    }

    @Override protected void tearDown() throws Exception {
        try {
            if (activity != null) {
                getInstrumentation().runOnMainSync(activity::finish);
                getInstrumentation().waitForIdleSync();
                activity = null;
            }
            replaceItems(originalItems);
            restorePreferences();
            app.storageError = originalStorageError;
            deleteTree(fixtureDirectory);
        } finally {
            super.tearDown();
        }
    }

    public void testEmptyScreenShowsPhotoAddPrimaryAction() throws Exception {
        launchActivity();

        Button primary = activity.findViewById(R.id.primary_action);
        View emptyState = activity.findViewById(R.id.empty_state);
        Button filterAll = activity.findViewById(R.id.filter_all);
        TextView status = activity.findViewById(R.id.status_label);
        View filterScroll = (View) filterAll.getParent().getParent();

        assertEquals("사진 추가", primary.getText().toString());
        assertTrue(primary.isEnabled());
        assertEquals(View.VISIBLE, emptyState.getVisibility());
        assertEquals(View.GONE, filterScroll.getVisibility());
        assertEquals("원본 보존 / 오프라인 처리 / 횟수 제한 없음", status.getText().toString());
    }

    public void testAllPendingDoneAndErrorFiltersShowMatchingRows() throws Exception {
        addQueueItem("대기 사진", "ready");
        addQueueItem("작업 중 사진", "working");
        addQueueItem("완료 사진", "done");
        addQueueItem("실패 사진", "error");
        launchActivity();

        Button filterAll = activity.findViewById(R.id.filter_all);
        Button filterPending = activity.findViewById(R.id.filter_pending);
        Button filterDone = activity.findViewById(R.id.filter_done);
        Button filterError = activity.findViewById(R.id.filter_error);
        ListView queue = activity.findViewById(R.id.queue_list);

        assertEquals("전체 4", filterAll.getText().toString());
        assertEquals("대기 2", filterPending.getText().toString());
        assertEquals("완료 1", filterDone.getText().toString());
        assertEquals("실패 1", filterError.getText().toString());
        assertTrue(filterAll.isSelected());
        assertEquals(4, queue.getAdapter().getCount());

        click(filterPending);
        assertTrue(filterPending.isSelected());
        assertEquals(2, queue.getAdapter().getCount());

        click(filterDone);
        assertTrue(filterDone.isSelected());
        assertEquals(1, queue.getAdapter().getCount());

        click(filterError);
        assertTrue(filterError.isSelected());
        assertEquals(1, queue.getAdapter().getCount());

        click(filterAll);
        assertTrue(filterAll.isSelected());
        assertEquals(4, queue.getAdapter().getCount());
    }

    public void testSettingsApplyAndCancelPreserveCommittedValues() throws Exception {
        launchActivity();
        View settings = activity.findViewById(R.id.settings_action);

        click(settings);
        Dialog firstSheet = activeSheet();
        RadioButton jpeg = firstSheet.findViewById(R.id.option_jpeg);
        Switch fingerprint = firstSheet.findViewById(R.id.option_fingerprint);
        Switch reprocess = firstSheet.findViewById(R.id.option_reprocess);
        Button apply = firstSheet.findViewById(R.id.apply_settings);
        getInstrumentation().runOnMainSync(() -> {
            jpeg.setChecked(true);
            fingerprint.setChecked(true);
            reprocess.setChecked(true);
            apply.performClick();
        });
        getInstrumentation().waitForIdleSync();

        assertTrue(preferences.getBoolean("jpeg", false));
        assertTrue(preferences.getBoolean("fingerprint", false));
        assertTrue(preferences.getBoolean("reprocess", false));
        assertEquals(
                "JPEG / 작은 용량 / 실험 모드 켜짐",
                optionsSummary().getText().toString()
        );

        click(settings);
        Dialog cancelledSheet = activeSheet();
        RadioButton pngBeforeCancel = cancelledSheet.findViewById(R.id.option_png);
        Switch fingerprintBeforeCancel = cancelledSheet.findViewById(R.id.option_fingerprint);
        Switch reprocessBeforeCancel = cancelledSheet.findViewById(R.id.option_reprocess);
        getInstrumentation().runOnMainSync(() -> {
            pngBeforeCancel.setChecked(true);
            fingerprintBeforeCancel.setChecked(false);
            reprocessBeforeCancel.setChecked(false);
            cancelledSheet.cancel();
        });
        getInstrumentation().waitForIdleSync();

        assertTrue(preferences.getBoolean("jpeg", false));
        assertTrue(preferences.getBoolean("fingerprint", false));
        assertTrue(preferences.getBoolean("reprocess", false));
        assertEquals(
                "JPEG / 작은 용량 / 실험 모드 켜짐",
                optionsSummary().getText().toString()
        );

        click(settings);
        Dialog finalSheet = activeSheet();
        RadioButton png = finalSheet.findViewById(R.id.option_png);
        Switch fingerprintOff = finalSheet.findViewById(R.id.option_fingerprint);
        Switch reprocessOff = finalSheet.findViewById(R.id.option_reprocess);
        Button applyPng = finalSheet.findViewById(R.id.apply_settings);
        getInstrumentation().runOnMainSync(() -> {
            png.setChecked(true);
            fingerprintOff.setChecked(false);
            reprocessOff.setChecked(false);
            applyPng.performClick();
        });
        getInstrumentation().waitForIdleSync();

        assertFalse(preferences.getBoolean("jpeg", true));
        assertFalse(preferences.getBoolean("fingerprint", true));
        assertFalse(preferences.getBoolean("reprocess", true));
        assertEquals(
                "PNG / 원본 해상도 / 픽셀 변화 꺼짐",
                optionsSummary().getText().toString()
        );
    }

    private void launchActivity() {
        Intent intent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = getInstrumentation().startActivitySync(intent);
        getInstrumentation().waitForIdleSync();
    }

    private void click(View view) {
        getInstrumentation().runOnMainSync(() -> assertTrue(view.performClick()));
        getInstrumentation().waitForIdleSync();
    }

    private Dialog activeSheet() throws Exception {
        Field field = MainActivity.class.getDeclaredField("activeSheet");
        field.setAccessible(true);
        Dialog dialog = (Dialog) field.get(activity);
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        return dialog;
    }

    private TextView optionsSummary() {
        ViewGroup summary = activity.findViewById(R.id.options_summary);
        assertNotNull(summary);
        assertTrue(summary.getChildAt(0) instanceof TextView);
        return (TextView) summary.getChildAt(0);
    }

    private void addQueueItem(String name, String state) throws Exception {
        File source = new File(fixtureDirectory, name + ".png");
        Bitmap bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.rgb(70, 130, 190));
        try (OutputStream output = new FileOutputStream(source)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            bitmap.recycle();
        }
        Uri sourceUri = Uri.fromFile(source);
        assertTrue(app.add(sourceUri, name));
        CleanApp.Item added = findByName(name);
        if (!"ready".equals(state)) {
            String output = "done".equals(state) ? sourceUri.toString() : null;
            assertTrue(app.update(added.id, state, output, "image/png", state + " 테스트"));
        }
    }

    private CleanApp.Item findByName(String name) {
        for (CleanApp.Item item : app.snapshot()) {
            if (name.equals(item.name)) {
                return item;
            }
        }
        fail("추가한 테스트 항목을 찾을 수 없습니다.");
        return null;
    }

    @SuppressWarnings("unchecked")
    private void replaceItems(ArrayList<CleanApp.Item> source) throws Exception {
        Field itemsField = CleanApp.class.getDeclaredField("items");
        itemsField.setAccessible(true);
        Method saveMethod = CleanApp.class.getDeclaredMethod("save");
        saveMethod.setAccessible(true);
        synchronized (app) {
            ArrayList<CleanApp.Item> items = (ArrayList<CleanApp.Item>) itemsField.get(app);
            items.clear();
            for (CleanApp.Item item : source) {
                items.add(item.copy());
            }
            assertTrue((Boolean) saveMethod.invoke(app));
        }
        app.notifyChanged();
    }

    private void setPreferences(boolean jpeg, boolean fingerprint, boolean reprocess) {
        assertTrue(preferences.edit()
                .putBoolean("jpeg", jpeg)
                .putBoolean("fingerprint", fingerprint)
                .putBoolean("reprocess", reprocess)
                .commit());
    }

    private void restorePreferences() {
        SharedPreferences.Editor editor = preferences.edit();
        if (hadJpegPreference) {
            editor.putBoolean("jpeg", originalJpegPreference);
        } else {
            editor.remove("jpeg");
        }
        if (hadFingerprintPreference) {
            editor.putBoolean("fingerprint", originalFingerprintPreference);
        } else {
            editor.remove("fingerprint");
        }
        if (hadReprocessPreference) {
            editor.putBoolean("reprocess", originalReprocessPreference);
        } else {
            editor.remove("reprocess");
        }
        assertTrue(editor.commit());
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        file.delete();
    }
}
