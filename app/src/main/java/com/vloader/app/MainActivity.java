package com.vloader.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 主页：链接与文件名输入、在线预览、单次下载文件数。
 * 下载进度、暂停/继续/放弃、完成弹窗全部归并到「下载历史 → 正在下载」页，
 * 主页只负责发起任务，点开始后隐藏按钮并直接翻页到任务列表。
 */
public final class MainActivity extends BaseActivity implements DownloadEngine.Callback {

    private static final int REQ_NOTIFICATION = 91;

    private static boolean sCacheSwept = false;

    private EditText urlInput;
    private EditText nameInput;
    /** 承接初始焦点的根容器：让输入框默认不抢焦点，返回主页时也不会自动弹键盘。 */
    private View focusAnchor;
    private TextView clipHint;
    private Button startBtn;
    private View navBar;
    private String clipCandidate = "";
    private String lastClip = "";
    private boolean busy;
    private boolean resumed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DownloadService.engine(this);
        buildUi();
        requestNotificationPermission();
        sweepCacheOnce();
        //  主题切换 / 重建后恢复已输入的链接与文件名
        if (savedInstanceState != null) {
            urlInput.setText(savedInstanceState.getString("draft_url", ""));
            nameInput.setText(savedInstanceState.getString("draft_name", ""));
        } else {
            urlInput.setText(Prefs.draftUrl(this));
            nameInput.setText(Prefs.draftName(this));
        }
        //  首次启动强制弹出用户协议
        if (!Prefs.eulaAccepted(this)) {
            EulaDialog.show(this, true, new Runnable() {
                @Override public void run() {
                    Prefs.setEulaAccepted(MainActivity.this, true);
                }
            });
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (urlInput != null) { out.putString("draft_url", urlInput.getText().toString()); }
        if (nameInput != null) { out.putString("draft_name", nameInput.getText().toString()); }
    }

    private void sweepCacheOnce() {
        if (sCacheSwept) { return; }
        sCacheSwept = true;
        DownloadEngine.State s = DownloadService.lastState();
        boolean active = s != null && (s.phase == DownloadEngine.PHASE_RUNNING
                || s.phase == DownloadEngine.PHASE_PAUSED);
        if (active) { return; }
        new Thread(new Runnable() {
            @Override public void run() {
                try { StorageUtil.clearCache(MainActivity.this); }
                catch (Exception ignored) { }
            }
        }, "m3u8dl-sweep").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        // 从下载历史 / 设置切回主页时，Activity 可能被复用而不重新 attach 底栏，
        // 这里强制把滑块同步到「主页」。
        NavBar.syncCurrent(navBar, NavBar.PAGE_HOME);
        DownloadService.registerUiCallback(this);
        DownloadEngine.State s = DownloadService.lastState();
        if (s != null) { onState(s); }
        else { setBusy(false); }
        checkClipboard();
        // 从下载历史 / 设置返回主页时，输入框残留的光标会让系统自动拉起软键盘。
        // 这里把焦点交还给根容器并主动收起键盘；用户之后主动点输入框仍可正常输入。
        dismissKeyboardOnReturn();
    }

    /** 返回主页时清掉输入框焦点、收起软键盘，避免键盘自动弹出（不改动输入框可聚焦性）。 */
    private void dismissKeyboardOnReturn() {
        if (urlInput != null) { urlInput.clearFocus(); }
        if (nameInput != null) { nameInput.clearFocus(); }
        if (focusAnchor != null) { focusAnchor.requestFocus(); }
        try {
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            View decor = getWindow().getDecorView();
            if (imm != null && decor != null && decor.getWindowToken() != null) {
                imm.hideSoftInputFromWindow(decor.getWindowToken(), 0);
            }
        } catch (Exception ignored) { }
    }

    @Override
    protected void onPause() {
        //  双保险：跨页面/进程被回收时也能保住输入草稿
        if (urlInput != null) {
            Prefs.saveDraft(this, urlInput.getText().toString(),
                    nameInput != null ? nameInput.getText().toString() : "");
        }
        super.onPause();
        resumed = false;
        DownloadService.unregisterUiCallback(this);
    }

    @Override
    protected void onDestroy() {
        DownloadService.unregisterUiCallback(this);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 界面

    private void buildUi() {
        th = currentTheme();
        FrameLayout root = new FrameLayout(this);
        th.applyRootBackground(root, this);
        // 根容器优先接管焦点：两个输入框默认不再自动获得焦点，
        // 这样首次进入与从历史/设置返回主页时都不会自动弹出软键盘。
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        root.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        focusAnchor = root;
        setContentView(root);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(th.dp(this, 16), th.dp(this, 8),
                th.dp(this, 16), th.dp(this, 96));
        EdgeInset.applyTop(this, page, 8, 14);

        TextView title = new TextView(this);
        title.setText(R.string.app_title);
        title.setTextSize(22 * th.fontScale);
        title.setTextColor(th.text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        page.addView(title, th.matchWidth());
        page.addView(th.space(this, 16));

        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        sv.addView(body, th.matchWidth());
        page.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        clipHint = new TextView(this);
        clipHint.setTextSize(13 * th.fontScale);
        clipHint.setTextColor(th.onPrimary);
        clipHint.setBackground(th.shape(th.primary, th.dp(this, 12)));
        clipHint.setPadding(th.dp(this, 14), th.dp(this, 10),
                th.dp(this, 14), th.dp(this, 10));
        clipHint.setVisibility(View.GONE);
        clipHint.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!clipCandidate.isEmpty()) {
                    urlInput.setText(clipCandidate);
                    urlInput.setSelection(clipCandidate.length());
                    clipHint.setVisibility(View.GONE);
                    toast(getString(R.string.toast_clip_filled));
                }
            }
        });
        body.addView(clipHint, th.matchWidth());
        body.addView(th.space(this, 10));

        LinearLayout linkCard = th.cardContainer(this);
        linkCard.addView(th.section(this, getString(R.string.section_links)),
                th.matchWidth());
        linkCard.addView(th.space(this, 8));
        urlInput = th.input(this);
        urlInput.setHint(R.string.hint_url_multi);
        urlInput.setGravity(Gravity.TOP);
        urlInput.setMinLines(4);
        urlInput.setMaxLines(12);
        linkCard.addView(urlInput, th.matchWidth());
        body.addView(linkCard, th.matchWidth());
        body.addView(th.space(this, 12));

        LinearLayout nameCard = th.cardContainer(this);
        nameCard.addView(th.section(this, getString(R.string.section_filename_once)),
                th.matchWidth());
        nameCard.addView(th.space(this, 8));
        nameInput = th.input(this);
        nameInput.setHint(R.string.hint_filename_batch);
        nameInput.setGravity(Gravity.TOP);
        nameInput.setMinLines(2);
        nameInput.setMaxLines(12);
        nameCard.addView(nameInput, th.matchWidth());
        body.addView(nameCard, th.matchWidth());
        body.addView(th.space(this, 12));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button preview = new Button(this);
        preview.setText(R.string.btn_preview);
        th.styleSecondary(preview, this);
        preview.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openPreview(); }
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        plp.rightMargin = th.dp(this, 8);
        actions.addView(preview, plp);
        startBtn = new Button(this);
        startBtn.setText(R.string.btn_start);
        th.stylePrimary(startBtn, this);
        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startDownload(); }
        });
        actions.addView(startBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 2f));
        body.addView(actions, th.matchWidth());

        root.addView(page, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        navBar = NavBar.attach(this, root, th, NavBar.PAGE_HOME, true, null);
        setBusy(busy);
    }

    // ---------------------------------------------------------------- 动作

    private void openPreview() {
        List<String> urls = parseUrls(urlInput.getText().toString());
        if (urls.isEmpty()) {
            toast(getString(R.string.err_empty_url));
            return;
        }
        Intent it = new Intent(this, PreviewActivity.class);
        it.putStringArrayListExtra(PreviewActivity.EXTRA_URLS, new ArrayList<>(urls));
        it.putExtra(PreviewActivity.EXTRA_INDEX, 0);
        startActivity(it);
        NavBar.slidePage(this, true);
    }

    private List<String> parseUrls(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) { return out; }
        for (String line : text.split("\\r?\\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) { continue; }
            int bar = s.indexOf('|');
            String u = bar > 0 ? s.substring(0, bar).trim() : s;
            if (!u.isEmpty()) { out.add(u); }
        }
        return out;
    }

    /**  链接形态快检：真正的 M3U8 播放列表链接，而非普通文件下载直链。 */
    private boolean looksLikeM3u8(String url) {
        if (url == null) { return false; }
        String s = url.trim().toLowerCase(Locale.US);
        if (!s.startsWith("http://") && !s.startsWith("https://")) { return false; }
        String path = s;
        int q = path.indexOf('?');
        if (q >= 0) { path = path.substring(0, q); }   // 去掉 query 再判断后缀
        if (path.contains(".m3u8")) { return true; }
        // 明显是其它文件的直链：直接拒绝
        String[] bad = {".mp4", ".mkv", ".mov", ".avi", ".flv", ".ts", ".zip", ".rar",
                ".7z", ".apk", ".exe", ".pdf", ".jpg", ".jpeg", ".png", ".mp3", ".m4a",
                ".txt", ".html", ".htm"};
        for (String e : bad) {
            if (path.endsWith(e)) { return false; }
        }
        // 无明显后缀（带路径的动态地址等）放行，交给引擎做内容层校验
        return true;
    }

    private void startDownload() {
        if (busy) {
            toast(getString(R.string.toast_already_running));
            return;
        }
        final DownloadEngine.Config cfg = new DownloadEngine.Config();
        // 单次 / 批量统一按多行解析，一行链接对应一行文件名
        cfg.tasks = FileNameUtil.parseTasks(
                urlInput.getText().toString(), nameInput.getText().toString(), true);
        if (cfg.tasks.isEmpty()) {
            toast(getString(R.string.err_empty_url));
            return;
        }
        //  形态快检：拒绝普通文件下载直链（.mp4/.zip/.apk 等），只放行 M3U8 播放列表
        for (Models.Task t : cfg.tasks) {
            if (!looksLikeM3u8(t.url)) {
                toast(getString(R.string.err_not_m3u8, t.url));
                return;
            }
        }
        cfg.headersText = Prefs.headers(this);
        cfg.threads = Prefs.threads(this);
        cfg.retries = Prefs.retries(this);
        cfg.locMode = Prefs.locMode(this);
        cfg.locValue = Prefs.locValue(this);
        cfg.filesPerRun = Prefs.filesPerRun(this);
        cfg.speedLimitKbps = Prefs.speedLimitKbps(this);
        setBusy(true);
        toast(getString(R.string.toast_task_added, cfg.tasks.size()));
        DownloadService.start(this, cfg);
        // 默认不跳转；仅当「设置 → 下载」里开启自动跳转时才切到下载任务界面
        if (Prefs.autoJump(this)) {
            Intent it = new Intent(this, HistoryActivity.class)
                    .putExtra(HistoryActivity.EXTRA_TAB, HistoryActivity.TAB_ACTIVE);
            startActivity(it);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                            new String[]{Manifest.permission.POST_NOTIFICATIONS},
                            REQ_NOTIFICATION);
                }
            } catch (Throwable ignored) { }
        }
    }

    // ---------------------------------------------------------------- 状态

    @Override
    public void onState(final DownloadEngine.State s) {
        if (s == null) { return; }
        runOnUiThread(new Runnable() {
            @Override public void run() {
                //  只要有活动文件（排队/下载/暂停）就隐藏开始按钮。
                // 旧逻辑只看顶层 phase，start() 后 emit 的 QUEUED 态会把按钮显示回来。
                boolean active = s.phase == DownloadEngine.PHASE_RUNNING
                        || s.phase == DownloadEngine.PHASE_PAUSED;
                if (s.files != null) {
                    for (DownloadEngine.FileState f : s.files) {
                        if (f.phase == DownloadEngine.PHASE_QUEUED
                                || f.phase == DownloadEngine.PHASE_RUNNING
                                || f.phase == DownloadEngine.PHASE_PAUSED) {
                            active = true;
                            break;
                        }
                    }
                }
                setBusy(active);
            }
        });
    }

    private void setBusy(boolean isBusy) {
        busy = isBusy;
        //  下载进行中「开始下载」按钮保持常驻可见（用户要求看得到）；
        // busy 仅用于防止重复发起下载，不再隐藏按钮、不再禁用输入框。
    }

    // ---------------------------------------------------------------- 剪贴板

    private void checkClipboard() {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()
                    || cm.getPrimaryClip() == null
                    || cm.getPrimaryClip().getItemCount() == 0) {
                return;
            }
            CharSequence cs = cm.getPrimaryClip().getItemAt(0).getText();
            if (cs == null) { return; }
            String text = cs.toString().trim();
            if (text.isEmpty() || text.equals(lastClip)) { return; }
            lastClip = text;
            boolean looksM3u8 = text.toLowerCase().contains(".m3u8")
                    || (text.startsWith("http") && text.contains("m3u8"));
            if (!looksM3u8 || busy) { return; }
            String current = urlInput.getText().toString().trim();
            if (text.equals(current) || current.contains(text)) { return; }
            clipCandidate = text;
            String shown = text.length() > 60 ? text.substring(0, 60) + "…" : text;
            clipHint.setText(getString(R.string.clip_hint, shown));
            clipHint.setVisibility(View.VISIBLE);
        } catch (Exception ignored) { }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
