package com.nogada.imageclean;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_IMAGES = 14;
    private final ExecutorService importer = Executors.newSingleThreadExecutor();
    private final ExecutorService previews = Executors.newSingleThreadExecutor();
    private final android.util.LruCache<String, Bitmap> thumbnails = new android.util.LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private final Runnable observer = this::refresh;
    private CleanApp app;
    private SharedPreferences preferences;
    private ArrayList<CleanApp.Item> rows = new ArrayList<>();
    private final ArrayList<CleanApp.Item> visibleRows = new ArrayList<>();
    private final ArrayList<Button> filters = new ArrayList<>();
    private ImageAdapter adapter;
    private Button primary;
    private ImageButton add;
    private ImageButton settings;
    private TextView options;
    private TextView status;
    private TextView emptyTitle;
    private TextView emptyBody;
    private View emptyState;
    private View filterBar;
    private ProgressBar progress;
    private int filter;
    private boolean sharing;
    private Dialog activeSheet;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        app = (CleanApp) getApplication();
        preferences = getSharedPreferences("options", MODE_PRIVATE);
        if (state != null) filter = state.getInt("filter", 0);
        buildScreen();
        if (state == null) acceptShare(getIntent());
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private int color(int resource) { return getColor(resource); }
    private boolean compact() { return getResources().getConfiguration().screenHeightDp < 480; }

    private TextView text(String value, int size, int colorResource, boolean bold) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color(colorResource));
        if (bold) label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setIncludeFontPadding(false);
        label.setLineSpacing(dp(3), 1);
        return label;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout.LayoutParams block(int top) {
        LinearLayout.LayoutParams parameters = new LinearLayout.LayoutParams(-1, -2);
        parameters.topMargin = dp(top);
        return parameters;
    }

    private Button button(String label, boolean filled) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setStateListAnimator(null);
        button.setMinHeight(dp(52));
        button.setMinimumHeight(dp(52));
        button.setPadding(dp(16), dp(12), dp(16), dp(12));
        button.setTextColor(new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                new int[] {color(R.color.secondary), color(filled ? R.color.on_accent : R.color.accent)}));
        button.setBackground(Ui.ripple(this, filled ? R.color.accent : R.color.tint, 12));
        return button;
    }

    private ImageButton iconButton(String icon, String label) {
        ImageButton button = new ImageButton(this);
        button.setImageDrawable(new Ui.Icon(icon, color(R.color.text)));
        button.setContentDescription(label);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setBackground(Ui.ripple(this, R.color.background, 16));
        return button;
    }

    private void buildScreen() {
        LinearLayout root = vertical();
        root.setBackgroundColor(color(R.color.background));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        setContentView(root);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(24), dp(8), dp(12), dp(8));
        LinearLayout branding = vertical();
        TextView title = text("긴빠이", 26, R.color.text, true);
        title.setAccessibilityHeading(true);
        branding.addView(title);
        if (!compact()) {
            TextView wordmark = text(getString(R.string.app_name), 11, R.color.secondary, false);
            wordmark.setTypeface(Typeface.MONOSPACE);
            branding.addView(wordmark, block(5));
        }
        toolbar.addView(branding, new LinearLayout.LayoutParams(0, -2, 1));
        settings = iconButton("settings", "저장 설정");
        settings.setId(R.id.settings_action);
        settings.setOnClickListener(view -> showSettings());
        toolbar.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(48)));
        ImageButton more = iconButton("more", "더 보기");
        more.setOnClickListener(this::showMenu);
        toolbar.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(toolbar);

        LinearLayout optionRow = new LinearLayout(this);
        optionRow.setId(R.id.options_summary);
        optionRow.setGravity(Gravity.CENTER_VERTICAL);
        optionRow.setPadding(dp(16), dp(12), dp(12), dp(12));
        optionRow.setMinimumHeight(dp(48));
        optionRow.setBackground(Ui.ripple(this, R.color.tint, 14));
        optionRow.setFocusable(true);
        optionRow.setOnClickListener(view -> showSettings());
        options = text("", 13, R.color.accent, true);
        optionRow.addView(options, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView chevron = new ImageView(this);
        chevron.setImageDrawable(new Ui.Icon("chevron", color(R.color.accent)));
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        optionRow.addView(chevron, new LinearLayout.LayoutParams(dp(18), dp(18)));
        LinearLayout.LayoutParams optionPosition = block(4);
        optionPosition.setMargins(dp(24), dp(4), dp(24), dp(8));
        root.addView(optionRow, optionPosition);
        optionRow.setVisibility(compact() ? View.GONE : View.VISIBLE);

        HorizontalScrollView filterScroll = new HorizontalScrollView(this);
        filterScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout filterRow = new LinearLayout(this);
        filterRow.setPadding(dp(24), 0, dp(16), dp(4));
        int[] filterIds = {R.id.filter_all, R.id.filter_pending, R.id.filter_done, R.id.filter_error};
        for (int index = 0; index < filterIds.length; index++) {
            final int selected = index;
            Button chip = button("", false);
            chip.setId(filterIds[index]);
            chip.setMinHeight(dp(48));
            chip.setMinimumHeight(dp(48));
            chip.setTextSize(13);
            chip.setPadding(dp(12), dp(8), dp(12), dp(8));
            chip.setOnClickListener(view -> { filter = selected; refresh(); });
            LinearLayout.LayoutParams placement = new LinearLayout.LayoutParams(-2, -2);
            placement.setMarginEnd(dp(8));
            filterRow.addView(chip, placement);
            filters.add(chip);
        }
        filterScroll.addView(filterRow);
        filterBar = filterScroll;
        root.addView(filterScroll);

        FrameLayout content = new FrameLayout(this);
        ListView list = new ListView(this);
        list.setId(R.id.queue_list);
        list.setDivider(null);
        list.setItemsCanFocus(true);
        list.setClipToPadding(false);
        list.setPadding(dp(24), dp(4), dp(16), dp(12));
        list.setSelector(Ui.ripple(this, R.color.background, 16));
        adapter = new ImageAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> openItem(visibleRows.get(position)));
        content.addView(list, new FrameLayout.LayoutParams(-1, -1));

        ScrollView emptyScroll = new ScrollView(this);
        emptyScroll.setId(R.id.empty_state);
        emptyScroll.setFillViewport(true);
        LinearLayout empty = vertical();
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(32), dp(20), dp(32), dp(20));
        ImageView illustration = new ImageView(this);
        illustration.setImageResource(R.drawable.ic_loot);
        illustration.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (!compact()) empty.addView(illustration, new LinearLayout.LayoutParams(dp(144), dp(144)));
        emptyTitle = text("", compact() ? 25 : 31, R.color.text, true);
        emptyTitle.setId(R.id.empty_title);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyTitle.setAccessibilityHeading(true);
        empty.addView(emptyTitle, block(compact() ? 0 : 28));
        emptyBody = text("", 15, R.color.secondary, false);
        emptyBody.setGravity(Gravity.CENTER);
        empty.addView(emptyBody, block(12));
        emptyScroll.addView(empty);
        emptyState = emptyScroll;
        content.addView(emptyScroll, new FrameLayout.LayoutParams(-1, -1));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout footer = vertical();
        footer.setPadding(dp(24), dp(12), dp(24), dp(compact() ? 8 : 16));
        footer.setBackgroundColor(color(R.color.surface));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        footer.addView(progress, new LinearLayout.LayoutParams(-1, dp(4)));
        status = text("", 12, R.color.secondary, false);
        status.setId(R.id.status_label);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        footer.addView(status, block(4));
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        add = iconButton("plus", "사진 더 추가");
        add.setId(R.id.add_photos);
        add.setBackground(Ui.ripple(this, R.color.tint, 16));
        add.setOnClickListener(view -> pick());
        LinearLayout.LayoutParams addPosition = new LinearLayout.LayoutParams(dp(52), dp(52));
        addPosition.setMarginEnd(dp(12));
        actions.addView(add, addPosition);
        primary = button("사진 가져오기", true);
        primary.setId(R.id.primary_action);
        primary.setOnClickListener(view -> startOrPause());
        actions.addView(primary, new LinearLayout.LayoutParams(0, -2, 1));
        footer.addView(actions, block(12));
        root.addView(footer);
    }

    private Dialog sheet(String title, LinearLayout content) {
        if (activeSheet != null) activeSheet.dismiss();
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout body = vertical();
        body.setPadding(dp(24), dp(24), dp(24), dp(24));
        body.setBackground(Ui.background(this, R.color.surface, 24));
        TextView heading = text(title, 23, R.color.text, true);
        heading.setAccessibilityHeading(true);
        body.addView(heading);
        body.addView(content, block(20));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        dialog.setContentView(scroll);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.32f);
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels, dp(560)), -2);
        }
        activeSheet = dialog;
        dialog.show();
        return dialog;
    }

    private void showSettings() {
        if (app.running) { toast("처리가 끝난 뒤 설정을 바꿀 수 있어요."); return; }
        LinearLayout content = vertical();
        content.addView(text("저장 형식", 14, R.color.secondary, true));
        RadioGroup formats = new RadioGroup(this);
        RadioButton png = new RadioButton(this);
        png.setId(R.id.option_png);
        png.setText("PNG   선명하게");
        png.setTextSize(17);
        png.setTextColor(color(R.color.text));
        png.setMinHeight(dp(52));
        formats.addView(png);
        RadioButton jpeg = new RadioButton(this);
        jpeg.setId(R.id.option_jpeg);
        jpeg.setText("JPEG   작은 용량으로");
        jpeg.setTextSize(17);
        jpeg.setTextColor(color(R.color.text));
        jpeg.setMinHeight(dp(52));
        formats.addView(jpeg);
        formats.check(preferences.getBoolean("jpeg", false) ? R.id.option_jpeg : R.id.option_png);
        content.addView(formats, block(8));
        TextView formatHint = text("", 13, R.color.secondary, false);
        Runnable updateHint = () -> formatHint.setText(jpeg.isChecked()
                ? "화질 95로 압축합니다. 투명 배경은 흰색으로 바뀝니다."
                : "추가 손실 압축 없이 저장합니다. 투명 배경을 유지하며 파일이 커질 수 있습니다.");
        formats.setOnCheckedChangeListener((group, selected) -> updateHint.run());
        updateHint.run();
        content.addView(formatHint, block(4));
        Button apply = button("설정 적용", true);
        apply.setId(R.id.apply_settings);
        content.addView(apply, block(24));
        Dialog dialog = sheet("저장 설정", content);
        apply.setOnClickListener(view -> {
            preferences.edit().putBoolean("jpeg", jpeg.isChecked()).apply();
            dialog.dismiss();
            refresh();
        });
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        boolean hasDone = false;
        for (CleanApp.Item item : rows) if ("done".equals(item.state)) hasDone = true;
        menu.getMenu().add(0, 1, 0, "완료한 사진 모두 공유").setEnabled(hasDone && !sharing);
        menu.getMenu().add(0, 2, 1, "작업 목록 비우기").setEnabled(!rows.isEmpty() && !app.running && app.importsInFlight.get() == 0);
        menu.getMenu().add(0, 3, 2, "사용 안내");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) shareResults(null);
            else if (item.getItemId() == 2) new AlertDialog.Builder(this).setTitle("작업 목록을 비울까요?")
                    .setMessage("원본과 갤러리에 저장한 사본은 삭제하지 않습니다.").setNegativeButton("취소", null)
                    .setPositiveButton("목록 비우기", (dialog, which) -> { filter = 0; app.clear(); }).show();
            else showHelp();
            return true;
        });
        menu.show();
    }

    private void showHelp() {
        LinearLayout content = vertical();
        content.addView(text("사진만 쏙. 원본은 그대로.\n\n1. 사진 가져오기\n여러 장을 고르거나 갤러리의 공유 메뉴에서 긴빠이로 보내세요.\n\n2. 긴빠이하기\n위치, 촬영 정보, 파일 안의 생성 기록을 덜어낸 새 사본을 갤러리에 저장합니다.\n\n3. 챙긴 사본 공유\n완료한 사진을 누르면 미리보기와 개별 공유가 가능합니다.", 15, R.color.text, false));
        content.addView(text("저장 위치\nPictures/Ginppai-AI-Photo\n\n인터넷 연결, 계정, 광고, 사용 횟수 제한이 없습니다. 한 파일은 256 MB 이하이며 처리 가능 크기는 기기 메모리에 따라 달라집니다.\n\n동영상과 애니메이션은 지원하지 않습니다. 일부 HEIC/AVIF와 RAW는 기기 디코더에 따라 열리지 않을 수 있습니다.\n\n버전 " + BuildConfig.VERSION_NAME, 13, R.color.secondary, false), block(24));
        sheet("사용 안내", content);
    }

    private void pick() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, PICK_IMAGES); }
        catch (android.content.ActivityNotFoundException exception) { toast("파일 선택 앱을 찾을 수 없습니다."); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_IMAGES || resultCode != RESULT_OK || data == null) return;
        ArrayList<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int index = 0; index < data.getClipData().getItemCount(); index++) uris.add(data.getClipData().getItemAt(index).getUri());
        } else if (data.getData() != null) uris.add(data.getData());
        importUris(uris, true);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        acceptShare(intent);
    }

    @SuppressWarnings("deprecation")
    private void acceptShare(Intent intent) {
        ArrayList<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) uris.add(uri);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> shared = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (shared != null) uris.addAll(shared);
        }
        if (!uris.isEmpty()) importUris(uris, false);
    }

    private void importUris(ArrayList<Uri> uris, boolean persist) {
        filter = 0;
        app.importsInFlight.incrementAndGet();
        app.notifyChanged();
        refresh();
        importer.execute(() -> {
            int rejected = 0;
            String lastError = "";
            for (Uri uri : uris) {
                if (uri == null || !"content".equals(uri.getScheme())) { rejected++; continue; }
                boolean permissionTaken = false;
                if (persist) {
                    try {
                        getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        permissionTaken = true;
                    }
                    catch (SecurityException ignored) { }
                }
                String name = "이미지";
                try (Cursor cursor = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
                } catch (Exception ignored) { }
                java.io.File staged = null;
                try {
                    staged = stageInput(uri);
                    if (!app.add(Uri.fromFile(staged), name == null ? "이미지" : name)) throw new java.io.IOException("작업 목록을 저장할 공간이 부족합니다.");
                    staged = null;
                } catch (Exception exception) {
                    rejected++;
                    lastError = exception instanceof java.io.IOException ? exception.getMessage() : "사진 읽기 권한을 확인해 주세요.";
                } finally {
                    if (staged != null) staged.delete();
                    if (permissionTaken) {
                        try { getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                        catch (SecurityException ignored) { }
                    }
                }
            }
            final int failed = rejected;
            final String reason = lastError;
            runOnUiThread(() -> {
                app.importsInFlight.decrementAndGet();
                app.notifyChanged();
                refresh();
                if (failed > 0 && !isDestroyed()) new AlertDialog.Builder(this).setTitle(failed + "장을 추가하지 못했습니다")
                        .setMessage(reason.isEmpty() ? "사진 선택으로 다시 열어 주세요." : reason).setPositiveButton("확인", null).show();
            });
        });
    }

    private java.io.File stageInput(Uri uri) throws java.io.IOException {
        java.io.File directory = new java.io.File(getFilesDir(), "incoming");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("사진을 준비할 저장 공간이 없습니다.");
        java.io.File pending = java.io.File.createTempFile("import_", ".part", directory);
        java.io.File destination = new java.io.File(directory, java.util.UUID.randomUUID() + ".image");
        boolean completed = false;
        try {
            try (java.io.InputStream input = getContentResolver().openInputStream(uri);
                 java.io.OutputStream output = new java.io.FileOutputStream(pending)) {
                if (input == null) throw new java.io.IOException("선택한 사진을 읽을 수 없습니다.");
                byte[] buffer = new byte[65536];
                long total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > 256L * 1024 * 1024) throw new java.io.IOException("한 파일은 256 MB 이하만 처리할 수 있습니다.");
                    output.write(buffer, 0, read);
                }
                if (total == 0) throw new java.io.IOException("내용이 없는 파일입니다.");
            }
            if (!pending.renameTo(destination)) throw new java.io.IOException("사진을 준비하지 못했습니다. 저장 공간을 확인해 주세요.");
            completed = true;
            return destination;
        } finally {
            if (!completed) pending.delete();
        }
    }


    private void startOrPause() {
        if (app.running) {
            app.stopRequested = true;
            refresh();
            return;
        }
        int pending = 0;
        int done = 0;
        for (CleanApp.Item item : rows) {
            if ("ready".equals(item.state) || "error".equals(item.state)) pending++;
            if ("done".equals(item.state)) done++;
        }
        if (pending == 0) {
            if (done > 0) shareResults(null);
            else pick();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !preferences.getBoolean("notificationAsked", false)) {
            preferences.edit().putBoolean("notificationAsked", true).apply();
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 17);
        }
        app.retryFailures();
        Intent intent = new Intent(this, CleanService.class)
                .putExtra("jpeg", preferences.getBoolean("jpeg", false));
        try { startForegroundService(intent); }
        catch (RuntimeException exception) { toast("처리를 시작하지 못했습니다. 앱을 열어 둔 상태에서 다시 시도해 주세요."); }
    }

    private boolean failed(CleanApp.Item item) { return "error".equals(item.state) || "missing".equals(item.state); }

    private String stateLabel(CleanApp.Item item) {
        if ("done".equals(item.state)) return "저장 완료";
        if ("error".equals(item.state)) return "처리 실패";
        if ("missing".equals(item.state)) return "사본을 찾을 수 없음";
        if ("working".equals(item.state) || "publishing".equals(item.state)) return "사본 챙기는 중";
        return "대기 중";
    }

    private void removeItem(CleanApp.Item item) {
        new AlertDialog.Builder(this).setTitle("목록에서 제외할까요?")
                .setMessage(item.name + "\n원본과 저장된 사본은 삭제하지 않습니다.")
                .setNegativeButton("취소", null).setPositiveButton("제외", (dialog, which) -> app.remove(item.id)).show();
    }

    private void itemMenu(View anchor, CleanApp.Item item) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "미리보기 및 상세 정보");
        menu.getMenu().add(0, 2, 1, "이 사진 공유").setEnabled("done".equals(item.state) && !sharing);
        menu.getMenu().add(0, 3, 2, "목록에서 제외").setEnabled(!app.running && app.importsInFlight.get() == 0);
        menu.setOnMenuItemClickListener(action -> {
            if (action.getItemId() == 1) openItem(item);
            else if (action.getItemId() == 2) shareResults(item);
            else removeItem(item);
            return true;
        });
        menu.show();
    }

    private void openItem(CleanApp.Item item) {
        LinearLayout content = vertical();
        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackground(Ui.background(this, R.color.background, 16));
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        content.addView(preview, new LinearLayout.LayoutParams(-1, dp(compact() ? 120 : 220)));
        loadPreview(preview, item.output.isEmpty() ? item.input : item.output, 480);
        content.addView(text(item.name, 16, R.color.text, true), block(16));
        String details = "done".equals(item.state)
                ? ("image/jpeg".equals(item.mime) ? "JPEG 사본" : "PNG 사본")
                : item.details.isEmpty() ? "홈의 긴빠이하기 버튼을 누르면 새 사본을 저장합니다." : item.details;
        if ("done".equals(item.state) && item.outputBytes > 0) {
            details += " / " + android.text.format.Formatter.formatShortFileSize(this, item.outputBytes);
        }
        content.addView(text(details, 14, R.color.secondary, false), block(8));
        if ("done".equals(item.state)) {
            content.addView(text("갤러리에 저장된 사본입니다. 원본은 수정하지 않았습니다.", 13, R.color.secondary, false), block(12));
            Button share = button("이 사진 공유", true);
            share.setOnClickListener(view -> { if (activeSheet != null) activeSheet.dismiss(); shareResults(item); });
            content.addView(share, block(20));
            Button open = button("다른 앱에서 열기", false);
            open.setOnClickListener(view -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(item.output), item.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                } catch (RuntimeException exception) { toast("이미지를 열 수 없습니다. 갤러리에서 사본을 확인해 주세요."); }
            });
            content.addView(open, block(8));
        }
        sheet(stateLabel(item), content);
    }
    private void shareResults(CleanApp.Item single) {
        if (sharing) return;
        sharing = true;
        refresh();
        ArrayList<CleanApp.Item> candidates = new ArrayList<>(rows);
        importer.execute(() -> {
            ArrayList<Uri> readable = new ArrayList<>();
            for (CleanApp.Item item : candidates) {
                if (!"done".equals(item.state) || (single != null && !single.id.equals(item.id))) continue;
                try (java.io.InputStream input = getContentResolver().openInputStream(Uri.parse(item.output))) {
                    if (input == null || input.read() < 0) throw new java.io.IOException("empty");
                    readable.add(Uri.parse(item.output));
                } catch (Exception exception) {
                    app.update(item.id, "missing", null, null, "갤러리 사본이 삭제되었거나 읽을 수 없습니다. 원본을 다시 선택해 주세요.");
                }
            }
            runOnUiThread(() -> {
                sharing = false;
                refresh();
                if (!isDestroyed() && !readable.isEmpty()) launchShare(readable);
                else if (!isDestroyed()) toast("공유할 사본이 없습니다. 원본을 다시 선택해 주세요.");
            });
        });
    }

    private void launchShare(ArrayList<Uri> uris) {
        Intent intent = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE).setType("image/*");
        if (uris.size() == 1) intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        ClipData clips = ClipData.newUri(getContentResolver(), "정리한 이미지", uris.get(0));
        for (int index = 1; index < uris.size(); index++) clips.addItem(new ClipData.Item(uris.get(index)));
        intent.setClipData(clips);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(intent, "정리한 이미지 공유")); }
        catch (RuntimeException exception) { toast("공유하지 못했습니다. 갤러리에서 이미지를 나누어 선택해 주세요."); }
    }


    private void refresh() {
        if (adapter == null || isDestroyed()) return;
        rows = app.snapshot();
        int done = 0;
        int errors = 0;
        int pending = 0;
        int retry = 0;
        visibleRows.clear();
        for (CleanApp.Item item : rows) {
            boolean completed = "done".equals(item.state);
            boolean error = failed(item);
            if (completed) done++;
            else if (error) errors++;
            else pending++;
            if ("error".equals(item.state)) retry++;
            if (filter == 0 || filter == 1 && !completed && !error || filter == 2 && completed || filter == 3 && error) visibleRows.add(item);
        }
        int[] counts = {rows.size(), pending, done, errors};
        String[] names = {"전체", "대기", "완료", "실패"};
        for (int index = 0; index < filters.size(); index++) {
            Button chip = filters.get(index);
            chip.setText(names[index] + " " + counts[index]);
            chip.setSelected(index == filter);
            chip.setBackground(Ui.ripple(this, index == filter ? R.color.text : R.color.background, 24));
            chip.setTextColor(color(index == filter ? R.color.surface : R.color.secondary));
        }
        filterBar.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        emptyState.setVisibility(visibleRows.isEmpty() ? View.VISIBLE : View.GONE);
        emptyTitle.setText(rows.isEmpty() ? "사진만 쏙.\n기록은 두고." : names[filter] + " 사진이 없습니다");
        emptyBody.setText(rows.isEmpty() ? "사진에 딸려온 위치와 생성 기록은 빼고,\n새 사본만 내 갤러리로 챙기세요."
                : "다른 목록을 선택하거나 사진을 더 추가하세요.");
        options.setText(preferences.getBoolean("jpeg", false) ? "JPEG / 작은 용량" : "PNG / 원본 해상도");
        boolean importing = app.importsInFlight.get() > 0;
        add.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        add.setEnabled(!importing && !app.running);
        add.setAlpha(add.isEnabled() ? 1f : 0.4f);
        settings.setEnabled(!app.running);
        settings.setAlpha(settings.isEnabled() ? 1f : 0.4f);
        primary.setEnabled(!importing && !sharing && !(app.running && app.stopRequested));
        primary.setText(importing ? "사진 준비 중..." : sharing ? "공유 준비 중..." : app.running ? (app.stopRequested ? "현재 사진을 마치는 중..." : "현재 사진 후 중지")
                : pending + retry > 0 ? (pending + retry) + "장 긴빠이하기" : done > 0 ? "챙긴 " + done + "장 공유" : "사진 가져오기");
        primary.setContentDescription(primary.getText() + (pending + retry > 0 && !app.running ? ", 메타데이터를 정리한 사본 저장" : ""));
        progress.setVisibility(app.running ? View.VISIBLE : View.GONE);
        progress.setMax(Math.max(1, rows.size()));
        progress.setProgress(done + errors);
        if (!app.storageError.isEmpty()) status.setText(app.storageError);
        else if (importing) status.setText("선택한 사진을 안전하게 준비하고 있어요.");
        else if (app.running) status.setText("완료 " + done + " / " + rows.size() + "장" + (errors > 0 ? " / 실패 " + errors + "장" : ""));
        else if (errors > 0) status.setText("실패 " + errors + "장 / 사진을 눌러 원인을 확인하세요.");
        else if (done > 0) status.setText("사본 " + done + "장 챙겼어요 / 갤러리 저장 완료");
        else status.setText("원본 보존 / 오프라인 처리 / 횟수 제한 없음");
        status.setTextColor(color(errors > 0 || !app.storageError.isEmpty() ? R.color.error : R.color.secondary));
        adapter.notifyDataSetChanged();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("filter", filter);
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() { super.onResume(); app.observe(observer); refresh(); }
    @Override protected void onPause() { app.unobserve(observer); super.onPause(); }
    @Override protected void onDestroy() {
        if (activeSheet != null) activeSheet.dismiss();
        importer.shutdown();
        previews.shutdownNow();
        super.onDestroy();
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

    private void loadPreview(ImageView preview, String uri, int size) {
        String key = uri + "#" + size;
        preview.setTag(key);
        Bitmap cached = thumbnails.get(key);
        if (cached == null) preview.setImageDrawable(new Ui.Icon("photo", color(R.color.line)));
        else preview.setImageBitmap(cached);
        if (cached != null || previews.isShutdown() || uri.isEmpty()) return;
        previews.execute(() -> {
            try {
                Bitmap queued = thumbnails.get(key);
                if (queued != null) {
                    runOnUiThread(() -> { if (!isDestroyed() && key.equals(preview.getTag())) preview.setImageBitmap(queued); });
                    return;
                }
                Bitmap bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), Uri.parse(uri)), (decoder, info, source) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    int width = info.getSize().getWidth();
                    int height = info.getSize().getHeight();
                    float scale = Math.min(1f, dp(size) / (float) Math.max(width, height));
                    decoder.setTargetSize(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)));
                });
                thumbnails.put(key, bitmap);
                runOnUiThread(() -> { if (!isDestroyed() && key.equals(preview.getTag())) preview.setImageBitmap(bitmap); });
            } catch (Exception | OutOfMemoryError ignored) { }
        });
    }

    private final class ImageAdapter extends BaseAdapter {
        @Override public int getCount() { return visibleRows.size(); }
        @Override public Object getItem(int position) { return visibleRows.get(position); }
        @Override public long getItemId(int position) { return visibleRows.get(position).id.hashCode(); }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row;
            if (recycled instanceof LinearLayout) row = (LinearLayout) recycled;
            else {
                row = new LinearLayout(MainActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(12), 0, dp(12));
                ImageView preview = new ImageView(MainActivity.this);
                preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
                preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                preview.setBackground(Ui.background(MainActivity.this, R.color.tint, 14));
                preview.setClipToOutline(true);
                row.addView(preview, new LinearLayout.LayoutParams(dp(72), dp(72)));
                LinearLayout labels = vertical();
                labels.setPadding(dp(16), 0, dp(4), 0);
                TextView name = text("", 15, R.color.text, true);
                name.setMaxLines(2);
                name.setEllipsize(TextUtils.TruncateAt.END);
                labels.addView(name);
                labels.addView(text("", 13, R.color.secondary, false), block(6));
                row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                ImageButton more = iconButton("more", "사진 메뉴");
                row.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
            }
            CleanApp.Item item = visibleRows.get(position);
            row.setFocusable(true);
            row.setBackground(Ui.ripple(MainActivity.this, R.color.background, 16));
            row.setOnClickListener(view -> openItem(item));
            ImageView preview = (ImageView) row.getChildAt(0);
            LinearLayout labels = (LinearLayout) row.getChildAt(1);
            ((TextView) labels.getChildAt(0)).setText(item.name);
            TextView detail = (TextView) labels.getChildAt(1);
            String size = "done".equals(item.state) && item.outputBytes > 0
                    ? " / " + android.text.format.Formatter.formatShortFileSize(MainActivity.this, item.outputBytes) : "";
            detail.setText(stateLabel(item) + size);
            detail.setTextColor(color(failed(item) ? R.color.error : "done".equals(item.state) ? R.color.success : R.color.secondary));
            ImageButton more = (ImageButton) row.getChildAt(2);
            more.setContentDescription(item.name + " 메뉴");
            more.setOnClickListener(view -> itemMenu(view, item));
            loadPreview(preview, item.output.isEmpty() ? item.input : item.output, 88);
            return row;
        }
    }
}
