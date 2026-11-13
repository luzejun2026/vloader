package com.vloader.app;

import android.app.Dialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 下载历史：任务进度统一在此呈现。
 * 顶部「已下载 / 正在下载」两个 Tab；正在下载按「每个文件一张卡片」展示，
 * 卡片自带暂停/继续键与放弃叉号；两个 Tab 分别有「清空全部」。
 * 打开文件时把 file:// 通过 AppFileProvider 转成 content://，避免
 * targetSdk 36 下的 FileUriExposedException（表现为「文件已下载但无法打开」）。
 */
public final class HistoryActivity extends BaseActivity implements DownloadEngine.Callback {

    public static final String EXTRA_TAB = "tab";
    public static final int TAB_DONE = 0;
    public static final int TAB_ACTIVE = 1;

    private static final int REQ_DIR = 71;
    /** 完成卡片在「正在下载」页的停留时长：让用户看到 100% 后自动移除。 */
    private static final long DONE_HOLD_MS = 2500L;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoRefresh = new Runnable() {
        @Override public void run() {
            if (tab == TAB_ACTIVE) { reload(); }
        }
    };

    private LinearLayout list;
    private TextView tabDone;
    private TextView tabActive;
    private Button clearBtn;
    private Button deleteAllBtn;
    private View navBar;
    private int tab = TAB_DONE;

    private long pendingId;
    private boolean pendingMove;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tab = getIntent().getIntExtra(EXTRA_TAB, TAB_DONE);
        buildUi();
    }

    /**
     * 底栏用 CLEAR_TOP | SINGLE_TOP 切页时本 Activity 会被复用，
     * 走 onNewIntent 而不重新创建：这里同步新的 Tab 与滑块位置。
     */
    @Override
    protected void onNewIntent(Intent it) {
        super.onNewIntent(it);
        setIntent(it);
        int t = it.getIntExtra(EXTRA_TAB, tab);
        if (t != tab) { setTab(t); }
        NavBar.syncCurrent(navBar, NavBar.PAGE_HISTORY);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从设置切回下载历史时强制把滑块归位到「下载历史」
        NavBar.syncCurrent(navBar, NavBar.PAGE_HISTORY);
        DownloadService.registerUiCallback(this);
        DownloadEngine.State s = DownloadService.lastState();
        if (s != null) { onState(s); }
        else { reload(); }
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(autoRefresh);
        DownloadService.unregisterUiCallback(this);
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacks(autoRefresh);
        DownloadService.unregisterUiCallback(this);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 界面

    private void buildUi() {
        th = currentTheme();
        FrameLayout root = new FrameLayout(this);
        th.applyRootBackground(root, this);
        setContentView(root);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(th.dp(this, 16), th.dp(this, 8),
                th.dp(this, 16), th.dp(this, 96));
        EdgeInset.applyTop(this, page, 8, 14);

        TextView title = new TextView(this);
        title.setText(R.string.history_title);
        title.setTextSize(22 * th.fontScale);
        title.setTextColor(th.text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        page.addView(title, th.matchWidth());
        page.addView(th.space(this, 14));

        // 双 Tab
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackground(th.shape(th.field, th.dp(this, 14)));
        tabs.setPadding(th.dp(this, 4), th.dp(this, 4), th.dp(this, 4), th.dp(this, 4));
        tabDone = tabButton(getString(R.string.history_tab_done));
        tabActive = tabButton(getString(R.string.history_tab_active));
        tabs.addView(tabDone, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tabs.addView(tabActive, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tabDone.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setTab(TAB_DONE); }
        });
        tabActive.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setTab(TAB_ACTIVE); }
        });
        page.addView(tabs, th.matchWidth());
        page.addView(th.space(this, 10));

        clearBtn = new Button(this);
        th.styleSecondary(clearBtn, this);
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (tab == TAB_DONE) { confirmClearDone(); }
                else { confirmClearActive(); }
            }
        });
        page.addView(clearBtn, th.matchWidth());
        page.addView(th.space(this, 6));

        // 已下载页专属：删除所有文件（连同本地文件），与「清空记录」区分
        deleteAllBtn = new Button(this);
        th.styleSecondary(deleteAllBtn, this);
        deleteAllBtn.setText(R.string.history_delete_all_files);
        deleteAllBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmDeleteAllFiles(); }
        });
        page.addView(deleteAllBtn, th.matchWidth());
        page.addView(th.space(this, 6));

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list, th.matchWidth());
        page.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(page, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        navBar = NavBar.attach(this, root, th, NavBar.PAGE_HISTORY, true, null);
        setTab(tab);
    }

    private TextView tabButton(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14 * th.fontScale);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(th.dp(this, 8), th.dp(this, 10),
                th.dp(this, 8), th.dp(this, 10));
        return t;
    }

    private void setTab(int t) {
        tab = t;
        boolean done = t == TAB_DONE;
        tabDone.setBackground(th.shape(done ? th.surface : 0x00000000, th.dp(this, 11)));
        tabDone.setTextColor(done ? th.primary : th.sub);
        tabActive.setBackground(th.shape(done ? 0x00000000 : th.surface, th.dp(this, 11)));
        tabActive.setTextColor(done ? th.sub : th.primary);
        clearBtn.setText(done ? R.string.history_clear_done : R.string.history_clear_active);
        deleteAllBtn.setVisibility(done ? View.VISIBLE : View.GONE);
        reload();
    }

    // ---------------------------------------------------------------- 列表

    private void reload() {
        if (list == null) { return; }
        list.removeAllViews();
        if (tab == TAB_ACTIVE) {
            renderActive();
        } else {
            renderDone();
        }
    }

    private void renderDone() {
        List<Models.Record> records = HistoryStore.all(this);
        if (records.isEmpty()) {
            list.addView(th.caption(this, getString(R.string.history_done_empty)),
                    th.matchWidth());
            return;
        }
        for (Models.Record r : records) {
            list.addView(recordCard(r));
            list.addView(th.space(this, 10));
        }
    }

    private void renderActive() {
        uiHandler.removeCallbacks(autoRefresh);
        DownloadEngine.State s = DownloadService.lastState();
        if (s == null || s.files == null || s.files.isEmpty()) {
            list.addView(th.caption(this, getString(R.string.history_active_empty)),
                    th.matchWidth());
            return;
        }
        long now = System.currentTimeMillis();
        int active = 0;                          // 进行中 / 暂停 / 排队
        long nextRemoveDelay = Long.MAX_VALUE;  // 最近的完成卡片到期时间
        java.util.List<DownloadEngine.FileState> shown =
                new java.util.ArrayList<>();
        for (DownloadEngine.FileState f : s.files) {
            boolean running = f.phase == DownloadEngine.PHASE_RUNNING
                    || f.phase == DownloadEngine.PHASE_PAUSED
                    || f.phase == DownloadEngine.PHASE_QUEUED;
            if (running) {
                active++;
                shown.add(f);
                continue;
            }
            if (f.phase == DownloadEngine.PHASE_DONE) {
                // 完成后停留 DONE_HOLD_MS 让用户看到 100%，之后自动从「正在下载」移除
                long elapsed = f.doneAt > 0 ? now - f.doneAt : 0;
                long remain = DONE_HOLD_MS - elapsed;
                if (remain > 0) {
                    shown.add(f);
                    if (remain < nextRemoveDelay) { nextRemoveDelay = remain; }
                }
                continue;   // 到期：不再渲染（完成记录仍保留在「已下载」页）
            }
            // 失败 / 放弃：保留卡片让用户看到原因
            shown.add(f);
        }
        if (shown.isEmpty()) {
            list.addView(th.caption(this, getString(R.string.history_active_empty)),
                    th.matchWidth());
            return;
        }
        TextView count = th.caption(this, getString(R.string.history_active_count, active));
        count.setPadding(0, 0, 0, th.dp(this, 8));
        list.addView(count, th.matchWidth());
        for (DownloadEngine.FileState f : shown) {
            list.addView(activeCard(f));
            list.addView(th.space(this, 10));
        }
        // 还有停留中的完成卡片：到点自动刷新把它们移除（完成后引擎不再推送状态）
        if (nextRemoveDelay != Long.MAX_VALUE) {
            uiHandler.postDelayed(autoRefresh, Math.max(200L, nextRemoveDelay));
        }
    }

    /** 每个文件一张卡片：进度、速度、ETA + 暂停/继续键 + 放弃叉号。 */
    private View activeCard(final DownloadEngine.FileState f) {
        LinearLayout card = th.cardContainer(this);
        card.setPadding(th.dp(this, 14), th.dp(this, 12),
                th.dp(this, 14), th.dp(this, 12));

        TextView name = th.body(this, f.name);
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setMaxLines(2);
        card.addView(name, th.matchWidth());

        String stateText;
        switch (f.phase) {
            case DownloadEngine.PHASE_PAUSED:
                stateText = getString(R.string.status_paused); break;
            case DownloadEngine.PHASE_DONE:
                stateText = getString(R.string.status_done); break;
            case DownloadEngine.PHASE_FAILED:
                stateText = getString(R.string.status_failed); break;
            case DownloadEngine.PHASE_CANCELLED:
                stateText = getString(R.string.status_cancelled_item); break;
            case DownloadEngine.PHASE_QUEUED:
                stateText = getString(R.string.status_queued); break;
            default:
                stateText = f.status.isEmpty()
                        ? getString(R.string.status_running) : f.status;
        }
        TextView st = th.caption(this, stateText);
        st.setPadding(0, th.dp(this, 4), 0, 0);
        st.setMaxLines(2);
        card.addView(st, th.matchWidth());

        LinearLayout pRow = new LinearLayout(this);
        pRow.setOrientation(LinearLayout.HORIZONTAL);
        pRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView pct = new TextView(this);
        pct.setText(f.percent + "%");
        pct.setTextSize(22 * th.fontScale);
        pct.setTextColor(th.primary);
        pct.setTypeface(Typeface.DEFAULT_BOLD);
        pRow.addView(pct);
        ProgressBar pb = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setProgress(f.percent);
        th.styleProgress(pb);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        plp.leftMargin = th.dp(this, 12);
        pRow.addView(pb, plp);
        card.addView(pRow, th.matchWidth());

        StringBuilder d = new StringBuilder();
        if (f.total > 0) { d.append(getString(R.string.detail_segments, f.done, f.total)); }
        if (f.speed > 0) {
            if (d.length() > 0) { d.append("  ·  "); }
            d.append(DownloadEngine.formatSpeed(f.speed));
        }
        if (f.etaMs > 0) {
            if (d.length() > 0) { d.append("  ·  "); }
            d.append(getString(R.string.detail_eta, DownloadEngine.formatEta(f.etaMs)));
        }
        if (f.bytes > 0) {
            if (d.length() > 0) { d.append("  ·  "); }
            d.append(DownloadEngine.formatBytes(f.bytes));
        }
        if (d.length() == 0) { d.append(getString(R.string.detail_idle)); }
        TextView detail = th.caption(this, d.toString());
        detail.setPadding(0, th.dp(this, 6), 0, 0);
        card.addView(detail, th.matchWidth());

        boolean ended = f.phase == DownloadEngine.PHASE_DONE
                || f.phase == DownloadEngine.PHASE_FAILED
                || f.phase == DownloadEngine.PHASE_CANCELLED;
        if (!ended) {
            LinearLayout buttons = new LinearLayout(this);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.setPadding(0, th.dp(this, 10), 0, 0);
            Button toggle = new Button(this);
            boolean paused = f.phase == DownloadEngine.PHASE_PAUSED;
            toggle.setText(paused ? R.string.btn_resume : R.string.btn_pause);
            th.stylePrimary(toggle, this);
            toggle.setPadding(th.dp(this, 16), th.dp(this, 8),
                    th.dp(this, 16), th.dp(this, 8));
            toggle.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    DownloadEngine e = DownloadService.engine(HistoryActivity.this);
                    if (f.phase == DownloadEngine.PHASE_PAUSED) { e.resumeFile(f.id); }
                    else { e.pauseFile(f.id); }
                }
            });
            buttons.addView(toggle, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            buttons.addView(th.space(this, 8));
            Button abandon = new Button(this);
            abandon.setText(R.string.btn_abandon);
            th.styleSecondary(abandon, this);
            abandon.setPadding(th.dp(this, 16), th.dp(this, 8),
                    th.dp(this, 16), th.dp(this, 8));
            abandon.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    // 放弃即自动清理本任务的临时片段缓存（引擎内静默完成），
                    // 不弹确认框、不弹 toast 提示；刷新列表让卡片消失即可。
                    DownloadService.engine(HistoryActivity.this).cancelFile(f.id);
                    reload();
                }
            });
            buttons.addView(abandon, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            card.addView(buttons, th.matchWidth());
        }
        return card;
    }

    private View recordCard(final Models.Record r) {
        LinearLayout card = th.cardContainer(this);
        card.setPadding(th.dp(this, 14), th.dp(this, 12),
                th.dp(this, 14), th.dp(this, 12));

        TextView name = th.body(this, r.name);
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(name, th.matchWidth());

        String meta = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(new Date(r.time)) + "   ·   "
                + DownloadEngine.formatBytes(r.size) + "   ·   " + r.status;
        TextView m = th.caption(this, meta);
        m.setPadding(0, th.dp(this, 4), 0, 0);
        card.addView(m, th.matchWidth());

        TextView path = th.caption(this, getString(R.string.history_location,
                r.path.isEmpty() ? r.uri : r.path));
        path.setPadding(0, th.dp(this, 3), 0, 0);
        path.setMaxLines(2);
        card.addView(path, th.matchWidth());

        TextView link = th.caption(this, getString(R.string.history_source, r.url));
        link.setPadding(0, th.dp(this, 3), 0, 0);
        link.setMaxLines(1);
        card.addView(link, th.matchWidth());

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, th.dp(this, 8), 0, 0);
        Button open = new Button(this);
        open.setText(R.string.history_open);
        th.stylePrimary(open, this);
        open.setPadding(th.dp(this, 14), th.dp(this, 8),
                th.dp(this, 14), th.dp(this, 8));
        open.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openWith(r, false); }
        });
        buttons.addView(open);
        buttons.addView(th.space(this, 8));
        Button more = new Button(this);
        more.setText(R.string.history_manage);
        th.styleSecondary(more, this);
        more.setPadding(th.dp(this, 14), th.dp(this, 8),
                th.dp(this, 14), th.dp(this, 8));
        more.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showActions(r); }
        });
        buttons.addView(more);
        card.addView(buttons, th.matchWidth());
        return card;
    }

    // ---------------------------------------------------------------- 操作

    private void showActions(final Models.Record r) {
        final String[] items = {
                getString(R.string.history_open),
                getString(R.string.history_choose_opener),
                getString(R.string.history_rename),
                getString(R.string.history_copy),
                getString(R.string.history_move),
                getString(R.string.history_delete_file),
                getString(R.string.history_remove_record),
                getString(R.string.history_copy_link)};
        AppDialog.with(this, th)
                .title(r.name)
                .items(items, new AppDialog.OnItemListener() {
                    @Override public void onItem(int which) {
                        switch (which) {
                            case 0: openWith(r, false); break;
                            case 1: openWith(r, true); break;
                            case 2: promptRename(r); break;
                            case 3: pickDir(r.id, false); break;
                            case 4: pickDir(r.id, true); break;
                            case 5: confirmDelete(r); break;
                            case 6:
                                HistoryStore.remove(HistoryActivity.this, r.id);
                                toast(getString(R.string.history_removed));
                                reload();
                                break;
                            case 7: copyLink(r.url); break;
                            default: break;
                        }
                    }
                })
                .show();
    }

    private void copyLink(String url) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("m3u8", url));
        toast(getString(R.string.history_link_copied));
    }

    private void promptRename(final Models.Record r) {
        final EditText input = th.input(this);
        input.setText(r.name);
        input.setSingleLine(true);
        input.setSelection(r.name.length());
        AppDialog.with(this, th)
                .title(getString(R.string.history_rename))
                .message(getString(R.string.history_rename_msg))
                .view(input)
                .negative(getString(R.string.btn_cancel), null)
                .positive(getString(R.string.btn_ok), new AppDialog.OnButtonListener() {
                    @Override public void onClick(Dialog dialog) {
                        String s = FileNameUtil.sanitize(input.getText().toString());
                        if (s.isEmpty()) {
                            toast(getString(R.string.history_name_empty));
                            return;
                        }
                        if (StorageUtil.rename(HistoryActivity.this, r, s)) {
                            r.name = s;
                            r.path = r.path.replaceAll("[^/]+$", s);
                            HistoryStore.update(HistoryActivity.this, r);
                            toast(getString(R.string.history_renamed));
                            reload();
                        } else {
                            toast(getString(R.string.history_rename_failed));
                        }
                    }
                })
                .show();
    }

    private void confirmDelete(final Models.Record r) {
        AppDialog.with(this, th)
                .title(getString(R.string.history_delete))
                .message(getString(R.string.history_delete_msg))
                .negative(getString(R.string.btn_cancel), null)
                .positive(getString(R.string.history_delete),
                        new AppDialog.OnButtonListener() {
                            @Override public void onClick(Dialog dialog) {
                                boolean ok = StorageUtil.deleteFile(HistoryActivity.this, r);
                                HistoryStore.remove(HistoryActivity.this, r.id);
                                toast(getString(ok ? R.string.history_deleted
                                        : R.string.history_deleted_no_file));
                                reload();
                            }
                        })
                .show();
    }

    private void confirmClearDone() {
        AppDialog.with(this, th)
                .title(getString(R.string.history_clear_done))
                .message(getString(R.string.history_clear_done_msg))
                .negative(getString(R.string.btn_cancel), null)
                .positive(getString(R.string.btn_clear), new AppDialog.OnButtonListener() {
                    @Override public void onClick(Dialog dialog) {
                        HistoryStore.clear(HistoryActivity.this);
                        reload();
                        toast(getString(R.string.history_cleared));
                    }
                })
                .show();
    }

    /** 删除所有已下载文件：连本地文件一起删，再清空历史记录。 */
    private void confirmDeleteAllFiles() {
        final List<Models.Record> records = HistoryStore.all(this);
        if (records.isEmpty()) {
            toast(getString(R.string.history_done_empty));
            return;
        }
        AppDialog.with(this, th)
                .title(getString(R.string.history_delete_all_files))
                .message(getString(R.string.history_delete_all_msg, records.size()))
                .negative(getString(R.string.btn_cancel), null)
                .positive(getString(R.string.history_delete), new AppDialog.OnButtonListener() {
                    @Override public void onClick(Dialog dialog) {
                        int deleted = 0;
                        for (Models.Record r : records) {
                            if (StorageUtil.deleteFile(HistoryActivity.this, r)) { deleted++; }
                        }
                        HistoryStore.clear(HistoryActivity.this);
                        reload();
                        toast(getString(R.string.history_delete_all_done, deleted));
                    }
                })
                .show();
    }

    private void confirmClearActive() {
        AppDialog.with(this, th)
                .title(getString(R.string.history_clear_active))
                .message(getString(R.string.history_clear_active_msg))
                .negative(getString(R.string.btn_cancel), null)
                .positive(getString(R.string.btn_clear), new AppDialog.OnButtonListener() {
                    @Override public void onClick(Dialog dialog) {
                        DownloadService.engine(HistoryActivity.this).clearActive();
                        reload();
                        toast(getString(R.string.history_cleared));
                    }
                })
                .show();
    }

    private void pickDir(long id, boolean move) {
        pendingId = id;
        pendingMove = move;
        Intent it = new Intent(this, FilePickerActivity.class);
        startActivityForResult(it, REQ_DIR);
        NavBar.slidePage(this, true);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_DIR || resultCode != RESULT_OK || data == null) { return; }
        final String dir = data.getStringExtra(FilePickerActivity.EXTRA_PATH);
        if (dir == null || dir.isEmpty()) { return; }
        final Models.Record r = HistoryStore.find(this, pendingId);
        if (r == null) { return; }
        final boolean move = pendingMove;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final String newUri = StorageUtil.copyToDir(HistoryActivity.this,
                            r, dir, move);
                    final String newPath = Uri.parse(newUri).getPath();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (move) {
                                r.uri = newUri;
                                r.path = newPath;
                                HistoryStore.update(HistoryActivity.this, r);
                            } else {
                                Models.Record copy = Models.Record.decode(r.encode());
                                if (copy != null) {
                                    copy.id = System.currentTimeMillis();
                                    copy.time = System.currentTimeMillis();
                                    copy.uri = newUri;
                                    copy.path = newPath;
                                    copy.status = getString(R.string.history_copy_status);
                                    HistoryStore.add(HistoryActivity.this, copy);
                                }
                            }
                            toast(getString(move ? R.string.history_moved
                                    : R.string.history_copied));
                            reload();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            toast(getString(R.string.history_op_failed,
                                    e.getMessage() == null ? "" : e.getMessage()));
                        }
                    });
                }
            }
        }, "m3u8dl-copy").start();
    }

    /**
     *  打开文件：一律用【本应用 AppFileProvider】的 content:// URI 共享，
     * 绝不把 MediaStore 的 content://media/... 直接丢给外部播放器——本应用不是它的
     * owner，无法授权，会报 "UID xxx does not have permission to content://media/..."。
     * 另外：文件可能已被删除但记录还在，打开前先校验真实文件存在，不存在则友好提示
     * 并提供「移除失效记录」，而不是抛权限异常。
     */
    private void openWith(final Models.Record r, boolean choose) {
        String path = AppFileProvider.localPath(r);
        File file = path == null ? null : new File(path);
        if (file == null || !file.isFile()) {
            showMissingFile(r);
            return;
        }
        Uri uri = AppFileProvider.uriFor(r);
        if (uri == null) {
            toast(getString(R.string.err_open_failed, "文件地址无效"));
            return;
        }
        String pkg = Prefs.defaultOpener(this);
        String mime = ".ts".equalsIgnoreCase(r.ext) ? "video/mp2t" : "video/mp4";
        try {
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setDataAndType(uri, mime);
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            // 某些系统下 createChooser 会丢失 data URI 的授权，设置 ClipData 可确保
            // 选中的目标应用拿到临时读权限（本应用是 owner，授权才有效）。
            it.setClipData(ClipData.newUri(getContentResolver(), "video", uri));
            if (!choose && !pkg.isEmpty()) {
                it.setPackage(pkg);
                grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(it);
                return;
            }
            //  「每次询问」时也用 MATCH_ALL，保证能列出全部可处理的应用
            List<ResolveInfo> apps = getPackageManager()
                    .queryIntentActivities(it, PackageManager.MATCH_ALL);
            if (apps.isEmpty()) {
                toast(getString(R.string.err_no_opener));
                return;
            }
            for (ResolveInfo ri : apps) {
                try {
                    grantUriPermission(ri.activityInfo.packageName, uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) { }
            }
            Intent chooser = Intent.createChooser(it, getString(R.string.opener_title));
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(chooser);
        } catch (Exception e) {
            toast(getString(R.string.err_open_failed,
                    e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    /**  文件已被删除但记录仍在：给出明确提示，并可一键移除失效记录。 */
    private void showMissingFile(final Models.Record r) {
        AppDialog.with(this, th)
                .title(getString(R.string.history_file_missing_title))
                .message(getString(R.string.err_file_missing))
                .negative(getString(R.string.btn_cancel), null)
                .positive(getString(R.string.history_remove_record),
                        new AppDialog.OnButtonListener() {
                            @Override public void onClick(Dialog dialog) {
                                HistoryStore.remove(HistoryActivity.this, r.id);
                                toast(getString(R.string.history_removed));
                                reload();
                            }
                        })
                .show();
    }

    @Override
    public void onState(final DownloadEngine.State s) {
        if (s == null) { return; }
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (tab == TAB_ACTIVE) { reload(); }
            }
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
