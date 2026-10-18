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
import android.view.KeyEvent;
import android.graphics.Rect;
import android.widget.Button;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.ScrollView;
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
    private boolean hadStrengthPreference;
    private int originalStrengthPreference;
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
        hadStrengthPreference = preferences.contains("strength");
        originalStrengthPreference = preferences.getInt("strength", 0);
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

        assertEquals("사진 가져오기", primary.getText().toString());
        assertEquals("사진만 쏙.\n기록은 두고.", ((TextView) activity.findViewById(R.id.empty_title)).getText().toString());
        assertTrue(primary.isEnabled());
        assertEquals(View.VISIBLE, emptyState.getVisibility());
        assertEquals(View.GONE, filterScroll.getVisibility());
        assertEquals("원본 보존 / 오프라인 처리 / 횟수 제한 없음", status.getText().toString());
        capture("home.png");
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
        Button primary = activity.findViewById(R.id.primary_action);
        assertEquals("3장 긴빠이하기", primary.getText().toString());
        assertTrue(primary.getContentDescription().toString().contains("사본 저장"));

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
        capture("queue.png");
    }

    public void testSettingsApplyAndCancelPreserveCommittedValues() throws Exception {
        launchActivity();
        View settings = activity.findViewById(R.id.settings_action);
        click(settings);
        Dialog firstSheet = activeSheet();
        getInstrumentation().runOnMainSync(() -> ((RadioButton) firstSheet.findViewById(R.id.option_jpeg)).setChecked(true));
        click(firstSheet.findViewById(R.id.apply_settings));
        assertTrue(preferences.getBoolean("jpeg", false));
        assertEquals("JPEG / 작은 용량", optionsSummary().getText().toString());
        click(settings);
        Dialog cancelledSheet = activeSheet();
        getInstrumentation().runOnMainSync(() -> {
            ((RadioButton) cancelledSheet.findViewById(R.id.option_png)).setChecked(true);
            cancelledSheet.cancel();
        });
        getInstrumentation().waitForIdleSync();
        assertTrue(preferences.getBoolean("jpeg", false));
        assertEquals("JPEG / 작은 용량", optionsSummary().getText().toString());
        click(settings);
        Dialog finalSheet = activeSheet();
        getInstrumentation().runOnMainSync(() -> ((RadioButton) finalSheet.findViewById(R.id.option_png)).setChecked(true));
        click(finalSheet.findViewById(R.id.apply_settings));
        assertFalse(preferences.getBoolean("jpeg", true));
        assertEquals("PNG / 원본 해상도", optionsSummary().getText().toString());
    }

    public void testCompletedPhotoShowsSavedCopyActions() throws Exception {
        addQueueItem("완료 사진", "done");
        CleanApp.Item saved = findByName("완료 사진");
        assertTrue(app.update(saved.id, saved.state, saved.output, saved.mime, "이전 버전의 처리 안내"));
        launchActivity();
        ListView queue = activity.findViewById(R.id.queue_list);
        getInstrumentation().runOnMainSync(() -> {
            View row = queue.getAdapter().getView(0, null, queue);
            assertTrue(row.performClick());
        });
        getInstrumentation().waitForIdleSync();
        View content = activeSheet().getWindow().getDecorView();
        assertTrue(containsText(content, "갤러리에 저장된 사본입니다. 원본은 수정하지 않았습니다."));
        assertTrue(containsText(content, "이 사진 공유"));
        assertTrue(containsText(content, "다른 앱에서 열기"));
        assertTrue(containsText(content, "PNG 사본"));
        assertFalse(containsText(content, "이전 버전의 처리 안내"));
        capture("completed.png");
        assertEquals("챙긴 1장 공유", ((Button) activity.findViewById(R.id.primary_action)).getText().toString());
    }

    public void testSettingsScrollAndKeyboardEntry() throws Exception {
        launchActivity();
        View settings = activity.findViewById(R.id.settings_action);
        getInstrumentation().runOnMainSync(() -> {
            settings.setFocusableInTouchMode(true);
            assertTrue(settings.requestFocus());
        });
        getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER);
        getInstrumentation().waitForIdleSync();
        Dialog dialog = activeSheet();
        assertTrue(dialog.isShowing());
        RadioButton png = dialog.findViewById(R.id.option_png);
        assertTrue(png.isChecked());
        capture("settings-top.png");
        View current = png;
        while (!(current instanceof ScrollView)) current = (View) current.getParent();
        ScrollView scroll = (ScrollView) current;
        getInstrumentation().runOnMainSync(() -> scroll.scrollTo(0, scroll.getChildAt(0).getHeight()));
        getInstrumentation().waitForIdleSync();
        Button apply = dialog.findViewById(R.id.apply_settings);
        Rect visible = new Rect();
        assertTrue(apply.getGlobalVisibleRect(visible));
        assertEquals(apply.getHeight(), visible.height());
        assertTrue(containsText(dialog.getWindow().getDecorView(), "추가 손실 압축 없이 저장합니다."));
        capture("settings-bottom.png");
        click(apply);
        assertFalse(dialog.isShowing());
    }

    public void testRetiredPreferencesAreRemovedWithoutChangingQueueOrFormat() throws Exception {
        addQueueItem("보존할 대기 사진", "ready");
        addQueueItem("보존할 완료 사진", "done");
        ArrayList<CleanApp.Item> before = app.snapshot();
        preferences.edit().putBoolean("jpeg", true).putBoolean("fingerprint", true)
                .putBoolean("reprocess", true).putInt("strength", 5).commit();
        getInstrumentation().runOnMainSync(app::removeRetiredOptions);
        getInstrumentation().runOnMainSync(app::removeRetiredOptions);
        assertFalse(preferences.contains("fingerprint"));
        assertFalse(preferences.contains("reprocess"));
        assertFalse(preferences.contains("strength"));
        assertTrue(preferences.getBoolean("jpeg", false));
        ArrayList<CleanApp.Item> after = app.snapshot();
        assertEquals(before.size(), after.size());
        for (int index = 0; index < before.size(); index++) {
            assertEquals(before.get(index).id, after.get(index).id);
            assertEquals(before.get(index).input, after.get(index).input);
            assertEquals(before.get(index).output, after.get(index).output);
            assertEquals(before.get(index).state, after.get(index).state);
            assertTrue(new File(Uri.parse(after.get(index).input).getPath()).isFile());
        }
        launchActivity();
        click(activity.findViewById(R.id.settings_action));
        View content = activeSheet().getWindow().getDecorView();
        assertTrue(containsText(content, "저장 설정"));
        assertTrue(containsText(content, "화질 95로 압축합니다."));
    }

    public void testHelpDescribesLocalPhotoWorkflow() throws Exception {
        launchActivity();
        Method help = MainActivity.class.getDeclaredMethod("showHelp");
        help.setAccessible(true);
        getInstrumentation().runOnMainSync(() -> {
            try { help.invoke(activity); }
            catch (Exception exception) { throw new AssertionError(exception); }
        });
        getInstrumentation().waitForIdleSync();
        View content = activeSheet().getWindow().getDecorView();
        assertTrue(containsText(content, "사진만 쏙. 원본은 그대로."));
        assertTrue(containsText(content, "Pictures/Ginppai-AI-Photo"));
        assertTrue(containsText(content, "버전 " + BuildConfig.VERSION_NAME));
        capture("help.png");
    }

    private boolean containsText(View view, String expected) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(expected)) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                if (containsText(group.getChildAt(index), expected)) return true;
            }
        }
        return false;
    }

    private void capture(String filename) throws Exception {
        android.os.SystemClock.sleep(300);
        Bitmap screenshot = getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        File directory = new File(context.getExternalFilesDir(null), "ui-review");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream stream = new FileOutputStream(new File(directory, filename))) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally {
            screenshot.recycle();
        }
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
                .putInt("strength", 0)
                .commit());
    }

    private void restorePreferences() {
        SharedPreferences.Editor editor = preferences.edit();
        if (hadStrengthPreference) editor.putInt("strength", originalStrengthPreference);
        else editor.remove("strength");
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
