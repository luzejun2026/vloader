package com.vloader.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置：单 Activity 两级结构，主列表为「外观 / 下载 / 保存位置 / 其他 / 关于」5 个入口。
 * 子设置页左上角有返回键、隐藏悬浮底栏；页面切换无动效，直接替换。
 * 当前子页写入 Intent + savedInstanceState，主题刷新 recreate() 后仍停留在该子页。
 * 所有弹窗用自绘 AppDialog、下拉用 Dropdown、开关用 AppSwitch，不出现系统 MD2 风格。
 */
public final class SettingsActivity extends BaseActivity {

    public static final String EXTRA_PAGE = "page";

    private static final int PAGE_MAIN = 0;
    private static final int PAGE_APPEARANCE = 1;
    private static final int PAGE_DOWNLOAD = 2;
    private static final int PAGE_LOCATION = 3;
    private static final int PAGE_OTHER = 4;
    private static final int PAGE_ABOUT = 5;
    private static final int PAGE_LOG = 6;

    private static final int REQ_DIR = 81;
    private static final int REQ_WALLPAPER = 82;
    private static final int[] THREAD_PRESETS = {1, 2, 4, 8, 16, 32, 64, 0};
    private static final int[] FILE_PRESETS = {1, 2, 3, 4, 5, 8, 10, 0};
    /** KB/s；-1 表示自定义输入。 */
    private static final int[] SPEED_PRESETS = {0, 1024, 2048, 5120, 10240, -1};

    private int page = PAGE_MAIN;
    /**  设置页导航历史栈：返回键/返回箭头弹栈回到上一级子页，而非直接退到设置主页。 */
    private final java.util.ArrayDeque<Integer> pageHistory = new java.util.ArrayDeque<>();
    private FrameLayout pageHost;
    private TextView titleView;
    private View backBtn;
    private View navBar;
    private LinearLayout content;

    private int draftMode = Prefs.LOC_PUBLIC;
    private String draftValue = Prefs.DEFAULT_SUBDIR;
    private EditText locInput;
    private TextView locCurrent;
    private TextView grantText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 主题/字号变化会触发 BaseActivity.recreate()，这里优先从保存状态恢复子页，
        // 避免「选完一个选项就弹回设置主列表」。
        if (savedInstanceState != null) {
            page = savedInstanceState.getInt("cur_page", PAGE_MAIN);
            draftMode = savedInstanceState.getInt("draft_mode", Prefs.locMode(this));
            draftValue = savedInstanceState.getString("draft_value", Prefs.locValue(this));
            //  主题/字号切换会 recreate()，必须一并恢复导航历史栈，
            // 否则重建后栈为空，按返回键会穿透到 finishAffinity() 直接退桌面。
            int[] hist = savedInstanceState.getIntArray("page_history");
            if (hist != null) {
                // 保存顺序为 head→tail（最近父页在前），按序 addLast 还原同一顺序
                for (int h : hist) { pageHistory.addLast(h); }
            }
        } else {
            page = getIntent().getIntExtra(EXTRA_PAGE, PAGE_MAIN);
            draftMode = Prefs.locMode(this);
            draftValue = Prefs.locValue(this);
        }
        buildUi();
        setPage(page, false);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("cur_page", page);
        out.putInt("draft_mode", draftMode);
        out.putString("draft_value", draftValue);
        // 保存导航栈，供 recreate() 后逐级返回
        int[] hist = new int[pageHistory.size()];
        int i = 0;
        for (int h : pageHistory) { hist[i++] = h; }
        out.putIntArray("page_history", hist);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从其它主页面切回设置时，Activity 可能被 CLEAR_TOP|SINGLE_TOP 复用，
        // 不再重新 attach 底栏；这里强制把滑块同步到「设置」。
        NavBar.syncCurrent(navBar, NavBar.PAGE_SETTINGS);
    }

    @Override
    protected boolean onHandleBack() {
        return navigateBack();
    }

    /**
     * / 返回：弹栈回到上一级子页（一级子页则回设置主页）。
     * 若历史栈为空但当前仍在子页（例如主题 recreate 后状态未恢复），
     * 也先回设置主页，绝不返回 false 让 BaseActivity 直接 finishAffinity() 退桌面。
     */
    private boolean navigateBack() {
        if (!pageHistory.isEmpty()) {
            setPage(pageHistory.pop(), false);
            return true;
        }
        if (page != PAGE_MAIN) {
            setPage(PAGE_MAIN, false);
            return true;
        }
        return false;   // 已在设置主页：交给底栏/BaseActivity 处理退出
    }

    // ---------------------------------------------------------------- 骨架

    private void buildUi() {
        th = currentTheme();
        FrameLayout root = new FrameLayout(this);
        th.applyRootBackground(root, this);
        setContentView(root);

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(th.dp(this, 16), th.dp(this, 8),
                th.dp(this, 16), th.dp(this, 96));
        EdgeInset.applyTop(this, shell, 8, 10);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(th.dp(this, 44));

        //  MIUI 主题下返回箭头按参考图用黑色（深色退化为文字色），Material 沿用主色
        backBtn = Icons.of(this, Icons.ARROW_LEFT, th.settingIcon(), 24);
        backBtn.setPadding(th.dp(this, 4), th.dp(this, 8),
                th.dp(this, 12), th.dp(this, 8));
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { navigateBack(); }
        });
        header.addView(backBtn);

        titleView = new TextView(this);
        titleView.setTextSize(20 * th.fontScale);
        titleView.setTextColor(th.text);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setGravity(Gravity.CENTER);
        header.addView(titleView, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        View spacer = new View(this);
        header.addView(spacer, new LinearLayout.LayoutParams(th.dp(this, 40), 1));
        shell.addView(header, th.matchWidth());
        shell.addView(th.space(this, 12));

        pageHost = new FrameLayout(this);
        shell.addView(pageHost, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(shell, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        navBar = NavBar.attach(this, root, th, NavBar.PAGE_SETTINGS, true, null);
    }

    /** 页面切换无动效：直接替换内容层，不做滑动/淡化转场。 */
    private void setPage(int p, boolean forward) {
        if (forward && p != page) {
            pageHistory.push(page);   // 记下当前页，供返回时逐级返回
        }
        page = p;
        getIntent().putExtra(EXTRA_PAGE, p);
        pageHost.removeAllViews();
        pageHost.addView(buildPage(p), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        titleView.setText(pageTitle(p));
        backBtn.setVisibility(p == PAGE_MAIN ? View.GONE : View.VISIBLE);
        NavBar.setVisible(navBar, p == PAGE_MAIN);
    }

    private View buildPage(int p) {
        ScrollView sv = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        sv.addView(content, th.matchWidth());
        switch (p) {
            case PAGE_APPEARANCE: buildAppearance(); break;
            case PAGE_DOWNLOAD: buildDownload(); break;
            case PAGE_LOCATION: buildLocation(); break;
            case PAGE_OTHER: buildOther(); break;
            case PAGE_ABOUT: buildAbout(); break;
            case PAGE_LOG: buildLog(); break;
            default: buildMain(); break;
        }
        return sv;
    }

    private int pageTitle(int p) {
        switch (p) {
            case PAGE_APPEARANCE: return R.string.cat_appearance;
            case PAGE_DOWNLOAD: return R.string.cat_download;
            case PAGE_LOCATION: return R.string.cat_location;
            case PAGE_OTHER: return R.string.cat_other;
            case PAGE_ABOUT: return R.string.cat_about;
            case PAGE_LOG: return R.string.log_title;
            default: return R.string.settings_title;
        }
    }

    // ---------------------------------------------------------------- 通用控件

    private LinearLayout group(String section) {
        if (section != null && !section.isEmpty()) {
            content.addView(th.section(this, section), th.matchWidth());
            content.addView(th.space(this, 8));
        }
        LinearLayout card = th.cardContainer(this);
        content.addView(card, th.matchWidth());
        content.addView(th.space(this, 20));
        return card;
    }

    private void divider(LinearLayout card) {
        //  MIUI 主题按参考图：设置项之间不画横杠线；Material 主题保留分隔线
        if (th.miui) { return; }
        View v = th.divider(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, th.dp(this, 1))));
        card.addView(v);
    }

    private void row(LinearLayout card, int icon, String title, String sub,
                     View right, View.OnClickListener click) {
        LinearLayout r = th.settingRow(this, icon, title, sub, right);
        if (click != null) { r.setOnClickListener(click); r.setClickable(true); }
        card.addView(r, th.matchWidth());
    }

    private AppSwitch toggle(boolean initial,
                             final AppSwitch.OnCheckedChangeListener l) {
        AppSwitch s = th.styledSwitch(this);
        s.setChecked(initial, false);
        s.setOnCheckedChangeListener(l);
        return s;
    }

    private View valueBox(String text) {
        return Dropdown.valueBox(this, th, text);
    }

    private void pick(String[] labels, int checked, View anchor,
                      final Dropdown.OnPick cb) {
        Dropdown.show(this, anchor, th, labels, checked, cb);
    }

    // ---------------------------------------------------------------- 主列表

    private void buildMain() {
        LinearLayout card = group("");
        row(card, Icons.APPEARANCE, getString(R.string.cat_appearance), null,
                th.arrow(this), new View.OnClickListener() {
                    @Override public void onClick(View v) { setPage(PAGE_APPEARANCE, true); }
                });
        divider(card);
        row(card, Icons.DOWNLOAD, getString(R.string.cat_download), null,
                th.arrow(this), new View.OnClickListener() {
                    @Override public void onClick(View v) { setPage(PAGE_DOWNLOAD, true); }
                });
        divider(card);
        row(card, Icons.FOLDER, getString(R.string.cat_location), null,
                th.arrow(this), new View.OnClickListener() {
                    @Override public void onClick(View v) { setPage(PAGE_LOCATION, true); }
                });
        divider(card);
        row(card, Icons.TUNE, getString(R.string.cat_other), null,
                th.arrow(this), new View.OnClickListener() {
                    @Override public void onClick(View v) { setPage(PAGE_OTHER, true); }
                });
        divider(card);
        row(card, Icons.INFO, getString(R.string.cat_about), null,
                th.arrow(this), new View.OnClickListener() {
                    @Override public void onClick(View v) { setPage(PAGE_ABOUT, true); }
                });
    }

    // ---------------------------------------------------------------- 外观

    private void buildAppearance() {
        LinearLayout card = group("");
        final View uiBox = valueBox(Prefs.STYLE_MIUI.equals(Prefs.style(this))
                ? getString(R.string.ui_miui) : getString(R.string.ui_material));
        row(card, Icons.PALETTE, getString(R.string.row_ui_style), null, uiBox,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        String[] l = {getString(R.string.ui_material),
                                getString(R.string.ui_miui)};
                        int c = Prefs.STYLE_MIUI.equals(
                                Prefs.style(SettingsActivity.this)) ? 1 : 0;
                        pick(l, c, uiBox, new Dropdown.OnPick() {
                            @Override public void onPick(int which) {
                                Prefs.setStyle(SettingsActivity.this,
                                        which == 1 ? Prefs.STYLE_MIUI : Prefs.STYLE_MATERIAL);
                                recreate();
                            }
                        });
                    }
                });
        divider(card);
        final View darkBox = valueBox(darkLabel());
        row(card, Icons.MOON, getString(R.string.row_dark_mode), null, darkBox,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        String[] l = {getString(R.string.mode_light),
                                getString(R.string.mode_dark),
                                getString(R.string.mode_system)};
                        pick(l, Prefs.darkMode(SettingsActivity.this), darkBox,
                                new Dropdown.OnPick() {
                                    @Override public void onPick(int which) {
                                        Prefs.setDarkMode(SettingsActivity.this, which);
                                        recreate();
                                    }
                                });
                    }
                });
        divider(card);
        final View fontBox = valueBox(fontLabel());
        row(card, Icons.FILE, getString(R.string.row_font_size), null, fontBox,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        String[] l = {getString(R.string.font_small),
                                getString(R.string.font_normal),
                                getString(R.string.font_large),
                                getString(R.string.font_xlarge)};
                        pick(l, Prefs.fontLevel(SettingsActivity.this), fontBox,
                                new Dropdown.OnPick() {
                                    @Override public void onPick(int which) {
                                        Prefs.setFontLevel(SettingsActivity.this, which);
                                        recreate();
                                    }
                                });
                    }
                });
        divider(card);
        final View wallBox = valueBox(Prefs.wallpaper(this).isEmpty()
                ? getString(R.string.wallpaper_none) : getString(R.string.wallpaper_pick));
        row(card, Icons.FILM, getString(R.string.row_wallpaper), null, wallBox,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { chooseWallpaper(); }
                });
        //  底栏拆成两个独立开关：形态（悬浮 / 固定）+ 材质（液态玻璃 / 磨砂）。
        // 与参考软件 Scrcpy for Android 一致：关闭悬浮后走贴底全宽的经典固定底栏，
        // 此时「液态玻璃」材质开关无意义，直接隐藏该行。
        divider(card);
        row(card, Icons.APPEARANCE, getString(R.string.row_floating_nav),
                getString(R.string.row_floating_nav_hint),
                toggle(Prefs.floatingNav(this), new AppSwitch.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(AppSwitch v, boolean on) {
                        Prefs.setFloatingNav(SettingsActivity.this, on);
                        // 形态与材质行的显隐都依赖它，重建当前页让底栏与设置立即生效
                        recreate();
                    }
                }), null);
        if (Prefs.floatingNav(this)) {
            divider(card);
            row(card, Icons.LIQUID, getString(R.string.row_liquid_nav),
                    getString(R.string.row_liquid_nav_hint),
                    toggle(Prefs.liquidNav(this), new AppSwitch.OnCheckedChangeListener() {
                        @Override public void onCheckedChanged(AppSwitch v, boolean on) {
                            Prefs.setLiquidNav(SettingsActivity.this, on);
                            // 底栏在 attach 时读取该开关，重建让光学材质立即切换
                            recreate();
                        }
                    }), null);
        }
    }

    private String darkLabel() {
        switch (Prefs.darkMode(this)) {
            case Prefs.MODE_LIGHT: return getString(R.string.mode_light);
            case Prefs.MODE_DARK: return getString(R.string.mode_dark);
            default: return getString(R.string.mode_system);
        }
    }

    private String fontLabel() {
        switch (Prefs.fontLevel(this)) {
            case Prefs.FONT_SMALL: return getString(R.string.font_small);
            case Prefs.FONT_LARGE: return getString(R.string.font_large);
            case Prefs.FONT_XLARGE: return getString(R.string.font_xlarge);
            default: return getString(R.string.font_normal);
        }
    }

    private void chooseWallpaper() {
        final String[] items = {getString(R.string.wallpaper_pick),
                getString(R.string.wallpaper_clear)};
        AppDialog.with(this, th)
                .title(getString(R.string.row_wallpaper))
                .items(items, new AppDialog.OnItemListener() {
                    @Override public void onItem(int which) {
                        if (which == 1) {
                            Prefs.setWallpaper(SettingsActivity.this, "");
                            recreate();
                            return;
                        }
                        Intent it = new Intent(SettingsActivity.this,
                                FilePickerActivity.class);
                        it.putExtra(FilePickerActivity.EXTRA_IMAGE_ONLY, true);
                        startActivityForResult(it, REQ_WALLPAPER);
                        NavBar.slidePage(SettingsActivity.this, true);
                    }
                })
                .negative(getString(R.string.btn_cancel), null)
                .show();
    }

    // ---------------------------------------------------------------- 下载

    private void buildDownload() {
        LinearLayout card = group("");

        final View threadBox = valueBox(threadsLabel());
        row(card, Icons.THREADS, getString(R.string.row_threads), null, threadBox,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { chooseThreads(threadBox); }
                });
        divider(card);

        final View retryBox = valueBox(
                getString(R.string.row_retries_value, Prefs.retries(this)));
        row(card, Icons.RETRY, getString(R.string.row_retries), null, retryBox,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        String[] l = new String[11];
                        for (int i = 0; i <= 10; i++) {
                            l[i] = getString(R.string.retries_item, i);
                        }
                        pick(l, Prefs.retries(SettingsActivity.this), retryBox,
                                new Dropdown.OnPick() {
                                    @Override public void onPick(int which) {
                                        Prefs.setRetries(SettingsActivity.this, which);
                                        Dropdown.setValue(retryBox,
                                                getString(R.string.row_retries_value, which));
                                    }
                                });
                    }
                });
        divider(card);

        final View fileBox = valueBox(fileCountLabel(Prefs.filesPerRun(this)));
        row(card, Icons.FILM, getString(R.string.row_files_per_run), null, fileBox,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { chooseFileCount(fileBox); }
                });
        divider(card);

        final View speedBox = valueBox(speedLabel());
        row(card, Icons.DOWNLOAD, getString(R.string.row_speed_limit), null, speedBox,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { chooseSpeed(speedBox); }
                });
        divider(card);

        row(card, Icons.HEADERS, getString(R.string.row_headers), null,
                th.arrow(this), new View.OnClickListener() {
                    @Override public void onClick(View v) { editHeaders(); }
                });
        divider(card);

        row(card, Icons.BELL, getString(R.string.row_notify), null,
                toggle(Prefs.notifyEnabled(this), new AppSwitch.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(AppSwitch v, boolean on) {
                        Prefs.setNotify(SettingsActivity.this, on);
                    }
                }), null);
        divider(card);

        row(card, Icons.CHECK, getString(R.string.row_finish_notify), null,
                toggle(Prefs.finishNotify(this), new AppSwitch.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(AppSwitch v, boolean on) {
                        Prefs.setFinishNotify(SettingsActivity.this, on);
                    }
                }), null);
        divider(card);

        row(card, Icons.PLAY, getString(R.string.row_keep_alive), null,
                toggle(Prefs.keepAlive(this), new AppSwitch.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(AppSwitch v, boolean on) {
                        Prefs.setKeepAlive(SettingsActivity.this, on);
                        if (on) { showBatteryGuide(); }
                    }
                }), null);
        divider(card);

        // 执行下载任务后是否自动跳转至下载任务界面，默认关闭
        row(card, Icons.CHEVRON_RIGHT, getString(R.string.row_auto_jump), null,
                toggle(Prefs.autoJump(this), new AppSwitch.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(AppSwitch v, boolean on) {
                        Prefs.setAutoJump(SettingsActivity.this, on);
                    }
                }), null);
    }

    private String threadsLabel() {
        int t = Prefs.threads(this);
        return t == 0 ? getString(R.string.threads_unlimited)
                : getString(R.string.threads_value, t);
    }

    private String fileCountLabel(int n) {
        return n <= 0 ? getString(R.string.files_per_run_unlimited)
                : getString(R.string.files_per_run_value, n);
    }

    private String speedLabel() {
        int k = Prefs.speedLimitKbps(this);
        return k <= 0 ? getString(R.string.speed_value_unlimited)
                : getString(R.string.speed_value_limited, String.valueOf(k));
    }

    private void chooseThreads(final View box) {
        String[] l = new String[THREAD_PRESETS.length + 1];
        int checked = 0;
        for (int i = 0; i < THREAD_PRESETS.length; i++) {
            int t = THREAD_PRESETS[i];
            l[i] = t == 0 ? getString(R.string.threads_unlimited)
                    : getString(R.string.threads_value, t);
            if (t == Prefs.threads(this)) { checked = i; }
        }
        l[THREAD_PRESETS.length] = getString(R.string.threads_custom);
        final int presetCount = THREAD_PRESETS.length;
        pick(l, checked, box, new Dropdown.OnPick() {
            @Override public void onPick(int which) {
                if (which >= presetCount) { promptNumber(box, 1); return; }
                Prefs.setThreads(SettingsActivity.this, THREAD_PRESETS[which]);
                Dropdown.setValue(box, threadsLabel());
            }
        });
    }

    private void promptNumber(final View box, final int kind) {
        final EditText input = th.input(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(kind == 1 ? R.string.threads_hint : R.string.speed_custom_hint);
        AppDialog.with(this, th)
                .title(getString(kind == 1 ? R.string.row_threads : R.string.speed_title))
                .view(input)
                .negative(getString(R.string.btn_cancel), null)
                .positive(getString(R.string.btn_ok), new AppDialog.OnButtonListener() {
                    @Override public void onClick(Dialog dialog) {
                        try {
                            int n = Integer.parseInt(input.getText().toString().trim());
                            if (n < 0) { throw new NumberFormatException(); }
                            if (kind == 1) {
                                Prefs.setThreads(SettingsActivity.this, n);
                                Dropdown.setValue(box, threadsLabel());
                            } else {
                                Prefs.setSpeedLimitKbps(SettingsActivity.this, n);
                                SpeedLimiter.setLimitKbps(n);
                                Dropdown.setValue(box, speedLabel());
                            }
                        } catch (Exception e) {
                            toast(getString(R.string.err_number));
                        }
                    }
                })
                .show();
    }

    private void chooseFileCount(final View box) {
        String[] l = new String[FILE_PRESETS.length];
        int checked = 0;
        for (int i = 0; i < FILE_PRESETS.length; i++) {
            l[i] = fileCountLabel(FILE_PRESETS[i]);
            if (FILE_PRESETS[i] == Prefs.filesPerRun(this)) { checked = i; }
        }
        pick(l, checked, box, new Dropdown.OnPick() {
            @Override public void onPick(int which) {
                Prefs.setFilesPerRun(SettingsActivity.this, FILE_PRESETS[which]);
                Dropdown.setValue(box, fileCountLabel(FILE_PRESETS[which]));
            }
        });
    }

    /** 图四样式：单选列表 + 自定义 KB/s 输入框 + 取消/确定。全部自绘，不用系统单选按钮。 */
    private void chooseSpeed(final View box) {
        final String[] labels = {
                getString(R.string.speed_unlimited),
                getString(R.string.speed_1),
                getString(R.string.speed_2),
                getString(R.string.speed_5),
                getString(R.string.speed_10),
                getString(R.string.speed_custom)};
        int cur = Prefs.speedLimitKbps(this);
        final int[] checked = {labels.length - 1};
        for (int i = 0; i < SPEED_PRESETS.length; i++) {
            if (SPEED_PRESETS[i] == cur) { checked[0] = i; }
        }

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        final TextView[] rows = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(labels[i]);
            t.setTextSize(16 * th.fontScale);
            t.setTextColor(i == checked[0] ? th.primary : th.text);
            t.setTypeface(i == checked[0] ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            t.setPadding(th.dp(this, 6), th.dp(this, 11), th.dp(this, 6), th.dp(this, 11));
            t.setBackground(th.shape(0x00000000, th.dp(this, 10)));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    checked[0] = idx;
                    for (int j = 0; j < rows.length; j++) {
                        rows[j].setTextColor(j == idx ? th.primary : th.text);
                        rows[j].setTypeface(j == idx
                                ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                    }
                }
            });
            rows[i] = t;
            wrap.addView(t, th.matchWidth());
        }
        final EditText custom = th.input(this);
        custom.setInputType(InputType.TYPE_CLASS_NUMBER);
        custom.setHint(R.string.speed_custom_hint);
        LinearLayout.LayoutParams clp = th.matchWidth();
        clp.topMargin = th.dp(this, 8);
        wrap.addView(custom, clp);

        AppDialog.with(this, th)
                .title(getString(R.string.speed_title))
                .view(wrap)
                .negative(getString(R.string.btn_cancel), null)
                .positive(getString(R.string.btn_ok), new AppDialog.OnButtonListener() {
                    @Override public void onClick(Dialog dialog) {
                        int kbps;
                        if (checked[0] == SPEED_PRESETS.length - 1) {
                            String s = custom.getText().toString().trim();
                            if (s.isEmpty()) { toast(getString(R.string.speed_invalid)); return; }
                            try { kbps = Integer.parseInt(s); }
                            catch (Exception e) { toast(getString(R.string.speed_invalid)); return; }
                            if (kbps <= 0) { toast(getString(R.string.speed_invalid)); return; }
                        } else {
                            kbps = SPEED_PRESETS[checked[0]];
                        }
                        Prefs.setSpeedLimitKbps(SettingsActivity.this, kbps);
                        SpeedLimiter.setLimitKbps(kbps);   // 当前任务即时生效
                        Dropdown.setValue(box, speedLabel());
                    }
                })
                .show();
    }

    private void editHeaders() {
        final EditText input = th.input(this);
        input.setText(Prefs.headers(this));
        input.setHint(R.string.hint_headers);
        input.setMinLines(3);
        input.setGravity(Gravity.TOP);
        AppDialog.with(this, th)
                .title(getString(R.string.row_headers))
                .view(input)
                .negative(getString(R.string.btn_cancel), null)
                .positive(getString(R.string.btn_ok), new AppDialog.OnButtonListener() {
                    @Override public void onClick(Dialog dialog) {
                        Prefs.setHeaders(SettingsActivity.this, input.getText().toString());
                        toast(getString(R.string.toast_applied));
                    }
                })
                .show();
    }

    private void showBatteryGuide() {
        AppDialog.with(this, th)
                .title(getString(R.string.row_keep_alive))
                .message(getString(R.string.keep_alive_need))
                .negative(getString(R.string.btn_cancel), null)
                .positive(getString(R.string.btn_battery_settings),
                        new AppDialog.OnButtonListener() {
                            @Override public void onClick(Dialog dialog) {
                                openBatterySettings();
                            }
                        })
                .show();
    }

    private void openBatterySettings() {
        try {
            startActivity(new Intent(
                    Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception e) {
            toast(getString(R.string.err_no_browser));
        }
    }

    // ---------------------------------------------------------------- 保存位置

    private void buildLocation() {
        LinearLayout card = group("");
        final View modeBox = valueBox(locModeLabel(draftMode));
        row(card, Icons.FOLDER, getString(R.string.loc_mode_title), null, modeBox,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        String[] l = {getString(R.string.loc_public),
                                getString(R.string.loc_subdir),
                                getString(R.string.loc_absolute)};
                        pick(l, draftMode, modeBox, new Dropdown.OnPick() {
                            @Override public void onPick(int which) {
                                draftMode = which;
                                Dropdown.setValue(modeBox, locModeLabel(draftMode));
                                updateLocUi();
                            }
                        });
                    }
                });
        divider(card);

        locInput = th.input(this);
        locInput.setText(draftValue);
        card.addView(locInput, th.matchWidth());
        card.addView(th.space(this, 10));

        row(card, Icons.NEW_FOLDER, getString(R.string.btn_browse_dir), null,
                th.arrow(this), new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Intent it = new Intent(SettingsActivity.this,
                                FilePickerActivity.class);
                        it.putExtra(FilePickerActivity.EXTRA_START,
                                StorageUtil.normalizeForDisplay(SettingsActivity.this,
                                        draftValue));
                        startActivityForResult(it, REQ_DIR);
                        NavBar.slidePage(SettingsActivity.this, true);
                    }
                });
        divider(card);

        grantText = th.caption(this, "");
        card.addView(grantText, th.matchWidth());
        card.addView(th.space(this, 8));
        row(card, Icons.CHECK, getString(R.string.btn_grant_all_files), null,
                th.arrow(this), new View.OnClickListener() {
                    @Override public void onClick(View v) { requestAllFiles(); }
                });
        divider(card);

        locCurrent = th.caption(this, "");
        card.addView(locCurrent, th.matchWidth());
        card.addView(th.space(this, 10));

        android.widget.Button save = new android.widget.Button(this);
        save.setText(R.string.btn_save_location);
        th.stylePrimary(save, this);
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveLocation(); }
        });
        card.addView(save, th.matchWidth());
        updateLocUi();
    }

    private String locModeLabel(int m) {
        switch (m) {
            case Prefs.LOC_SUBDIR: return getString(R.string.loc_subdir);
            case Prefs.LOC_ABSOLUTE: return getString(R.string.loc_absolute);
            default: return getString(R.string.loc_public);
        }
    }

    private void updateLocUi() {
        if (locInput == null) { return; }
        locInput.setEnabled(draftMode != Prefs.LOC_PUBLIC);
        locInput.setHint(draftMode == Prefs.LOC_ABSOLUTE
                ? R.string.hint_abs_path : R.string.hint_subdir);
        boolean granted = StorageUtil.hasAllFilesAccess(this);
        if (grantText != null) {
            grantText.setText(draftMode == Prefs.LOC_ABSOLUTE
                    ? getString(granted ? R.string.all_files_granted
                    : R.string.all_files_not_granted)
                    : getString(R.string.location_no_permission_needed));
        }
        if (locCurrent != null) {
            locCurrent.setText(getString(R.string.location_current,
                    StorageUtil.normalizeForDisplay(this,
                            draftMode == Prefs.LOC_PUBLIC
                                    ? getString(R.string.loc_public) : draftValue)));
        }
    }

    private void saveLocation() {
        String v = locInput == null ? "" : locInput.getText().toString().trim();
        if (draftMode != Prefs.LOC_PUBLIC && v.isEmpty()) {
            toast(getString(R.string.err_empty_path));
            return;
        }
        v = StorageUtil.normalizeForDisplay(this, v);
        if (locInput != null) { locInput.setText(v); }
        draftValue = v;
        Prefs.saveLocation(this, draftMode, draftValue);
        updateLocUi();
        toast(getString(R.string.toast_location_saved));
    }

    private void requestAllFiles() {
        try {
            Intent it = new Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(it);
        } catch (Exception e) {
            toast(getString(R.string.err_need_all_files));
        }
    }

    // ---------------------------------------------------------------- 其他

    private void buildOther() {
        LinearLayout card = group("");
        row(card, Icons.OPENER, getString(R.string.row_default_opener), null,
                th.arrow(this), new View.OnClickListener() {
                    @Override public void onClick(View v) { chooseOpener(); }
                });
        divider(card);
        row(card, Icons.TRASH, getString(R.string.row_clear_cache), null,
                th.arrow(this), new View.OnClickListener() {
                    @Override public void onClick(View v) { confirmClearCache(); }
                });
    }

    private void chooseOpener() {
        // 打开方式识别：
        //  - Android 11+ 包可见性由 <queries> 与 QUERY_ALL_PACKAGES 决定（见 Manifest），
        //    MATCH_ALL 只负责不被「默认/首选应用」标记过滤，二者缺一不可；
        //  - 单一 setType("video/*")（无 data）匹配不到只声明了具体 scheme 的播放器，
        //    故用多种 data(scheme) + MIME 组合分别探测，取并集；
        //  - 全部用 MATCH_ALL，按包名去重，应用自身 label 原样保留。
        PackageManager pm = getPackageManager();
        String self = getPackageName();
        String[] dataUris = {
                "content://" + self + ".fileprovider/sample.mp4",
                "file:///sdcard/Download/sample.mp4",
                "http://example.com/sample.mp4"
        };
        String[] mimes = {"video/mp4", "video/*"};
        // LinkedHashMap：按包名去重且保留首次出现顺序
        final java.util.LinkedHashMap<String, CharSequence> found =
                new java.util.LinkedHashMap<>();
        for (String u : dataUris) {
            for (String mime : mimes) {
                Intent probe = new Intent(Intent.ACTION_VIEW);
                probe.setDataAndType(Uri.parse(u), mime);
                try {
                    List<ResolveInfo> res =
                            pm.queryIntentActivities(probe, PackageManager.MATCH_ALL);
                    for (ResolveInfo ri : res) {
                        if (ri == null || ri.activityInfo == null) { continue; }
                        String p = ri.activityInfo.packageName;
                        if (p == null || p.equals(self) || found.containsKey(p)) { continue; }
                        found.put(p, ri.loadLabel(pm));
                    }
                } catch (Exception ignored) { }
            }
        }
        // 再补一次无 data 的纯 MIME 探测，覆盖只按 mimeType 声明的应用
        Intent mimeOnly = new Intent(Intent.ACTION_VIEW);
        mimeOnly.setType("video/*");
        try {
            for (ResolveInfo ri : pm.queryIntentActivities(mimeOnly, PackageManager.MATCH_ALL)) {
                if (ri == null || ri.activityInfo == null) { continue; }
                String p = ri.activityInfo.packageName;
                if (p == null || p.equals(self) || found.containsKey(p)) { continue; }
                found.put(p, ri.loadLabel(pm));
            }
        } catch (Exception ignored) { }

        final List<String> labels = new ArrayList<>();
        final List<String> pkgs = new ArrayList<>();
        labels.add(getString(R.string.opener_ask));
        pkgs.add("");
        for (java.util.Map.Entry<String, CharSequence> e : found.entrySet()) {
            CharSequence lb = e.getValue();
            labels.add(lb == null ? e.getKey() : lb.toString());
            pkgs.add(e.getKey());
        }
        // 当前默认项高亮，打开列表时一眼能看到已选的是哪个
        String cur = Prefs.defaultOpener(this);
        int checked = 0;
        for (int i = 0; i < pkgs.size(); i++) {
            if (pkgs.get(i).equals(cur)) { checked = i; break; }
        }
        // 打开方式沿用系统默认交互：
        // 不再用自绘 AppDialog 卡片，直接上系统原生 AlertDialog.setSingleChoiceItems。
        // 系统单选列表天然可上下滑动、能显示全部应用，应用自身 label 原样保留。
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.row_default_opener))
                .setSingleChoiceItems(labels.toArray(new String[0]), checked,
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                Prefs.setDefaultOpener(SettingsActivity.this,
                                        pkgs.get(which));
                                toast(getString(R.string.toast_applied));
                                d.dismiss();
                            }
                        })
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show();
    }

    private void confirmClearCache() {
        String size = DownloadEngine.formatBytes(StorageUtil.cacheSize(this));
        AppDialog.with(this, th)
                .title(getString(R.string.row_clear_cache))
                .message(getString(R.string.cache_size, size))
                .negative(getString(R.string.btn_cancel), null)
                .positive(getString(R.string.btn_clear), new AppDialog.OnButtonListener() {
                    @Override public void onClick(Dialog dialog) {
                        long freed = StorageUtil.clearCache(SettingsActivity.this);
                        toast(getString(R.string.clear_cache_done,
                                DownloadEngine.formatBytes(freed)));
                    }
                })
                .show();
    }

    // ---------------------------------------------------------------- 关于 / 日志

    private void buildAbout() {
        //  顶部：应用图标 + 名称 + 版本 + 反盗版声明
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setGravity(Gravity.CENTER_HORIZONTAL);
        brand.setPadding(th.dp(this, 8), th.dp(this, 12), th.dp(this, 8), th.dp(this, 6));
        ImageView logo = new ImageView(this);
        // 关于页图标与启动图标一致：直接引用 mipmap 资源，
        // 避免部分 ROM 对 getApplicationIcon() 做自适应裁剪导致图形被切。
        logo.setImageResource(R.mipmap.ic_launcher);
        int iconPx = th.dp(this, 64);
        brand.addView(logo, new LinearLayout.LayoutParams(iconPx, iconPx));
        TextView brandName = new TextView(this);
        brandName.setText(R.string.about_brand);
        brandName.setTextSize(19 * th.fontScale);
        brandName.setTextColor(th.text);
        brandName.setTypeface(Typeface.DEFAULT_BOLD);
        brandName.setGravity(Gravity.CENTER);
        brandName.setPadding(0, th.dp(this, 8), 0, 0);
        brand.addView(brandName, th.matchWidth());
        TextView brandVer = new TextView(this);
        brandVer.setText(getString(R.string.about_version_label, Prefs.VERSION_NAME));
        brandVer.setTextSize(12 * th.fontScale);
        brandVer.setTextColor(th.sub);
        brandVer.setGravity(Gravity.CENTER);
        brand.addView(brandVer, th.matchWidth());
        TextView antipiracy = new TextView(this);
        antipiracy.setText(R.string.about_antipiracy);
        antipiracy.setTextSize(12 * th.fontScale);
        antipiracy.setTextColor(th.sub);
        antipiracy.setGravity(Gravity.CENTER);
        antipiracy.setPadding(th.dp(this, 8), th.dp(this, 10), th.dp(this, 8), 0);
        brand.addView(antipiracy, th.matchWidth());
        content.addView(brand, th.matchWidth());
        content.addView(th.space(this, 12));

        LinearLayout card = group("");
        row(card, Icons.VERSION, getString(R.string.about_version), null,
                versionValue(), null);
        divider(card);
        row(card, Icons.QQ, getString(R.string.contact_qq), Prefs.CONTACT_QQ,
                th.arrow(this), new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        final String[] acts = {getString(R.string.btn_open_qq),
                                getString(R.string.btn_copy_qq)};
                        AppDialog.with(SettingsActivity.this, th)
                                .title(getString(R.string.contact_qq))
                                .items(acts, new AppDialog.OnItemListener() {
                                    @Override public void onItem(int which) {
                                        if (which == 0) {
                                            openLink(Prefs.CONTACT_QQ_URL);
                                        } else {
                                            copyText(Prefs.CONTACT_QQ);
                                        }
                                    }
                                })
                                .negative(getString(R.string.btn_cancel), null)
                                .show();
                    }
                });
        divider(card);
        // B 站昵称与跳转入口
        row(card, Icons.BILI, getString(R.string.contact_bili),
                Prefs.CONTACT_BILI_NAME + getString(R.string.about_bili_tail), th.arrow(this),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openLink(Prefs.CONTACT_BILI_URL);
                    }
                });
        divider(card);
        //  关于页可再次查看用户协议
        row(card, Icons.FILE, getString(R.string.eula_view), null,
                th.arrow(this), new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        EulaDialog.show(SettingsActivity.this, false, null);
                    }
                });
        divider(card);
        row(card, Icons.FILE, getString(R.string.row_view_log), null,
                th.arrow(this), new View.OnClickListener() {
                    @Override public void onClick(View v) { setPage(PAGE_LOG, true); }
                });
        divider(card);
        row(card, Icons.DOWNLOAD, getString(R.string.row_export_log), null,
                th.arrow(this), new View.OnClickListener() {
                    @Override public void onClick(View v) { exportLog(); }
                });
        divider(card);
        row(card, Icons.TRASH, getString(R.string.btn_clear_log), null,
                th.arrow(this), new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        LogStore.clear(SettingsActivity.this);
                        toast(getString(R.string.toast_applied));
                    }
                });
    }

    /** 版本号点击事件：短时间内连续多次点击后跳转。 */
    private int versionClicks;
    private long versionFirstAt;

    private TextView versionValue() {
        TextView t = new TextView(this);
        t.setText(Prefs.VERSION_NAME);
        t.setTextSize(14 * th.fontScale);
        t.setTextColor(th.primary);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - versionFirstAt > 2000L) {
                    versionFirstAt = now;
                    versionClicks = 0;
                }
                versionClicks++;
                if (versionClicks >= 5) {
                    versionClicks = 0;
                    toast(getString(R.string.about_easter_toast));
                    openLink(Prefs.EASTER_EGG_URL);
                }
            }
        });
        return t;
    }

    private void buildLog() {
        String text = LogStore.read(this);
        LinearLayout card = group("");
        TextView log = th.body(this, text == null || text.isEmpty()
                ? getString(R.string.log_empty) : text);
        log.setTextSize(12 * th.fontScale);
        log.setTextIsSelectable(true);
        card.addView(log, th.matchWidth());
    }

    private void exportLog() {
        try {
            String name = LogStore.export(this);
            toast(getString(R.string.log_exported, name));
        } catch (Exception e) {
            toast(getString(R.string.log_failed,
                    e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    private void copyText(String s) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("m3u8", s));
        toast(getString(R.string.toast_copied));
    }

    private void openLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast(getString(R.string.err_no_browser));
        }
    }

    // ---------------------------------------------------------------- 结果

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null) { return; }
        String path = data.getStringExtra(FilePickerActivity.EXTRA_PATH);
        if (path == null || path.isEmpty()) { return; }
        if (requestCode == REQ_WALLPAPER) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) {
                toast(getString(R.string.wallpaper_invalid));
                return;
            }
            Prefs.setWallpaper(this, path);
            toast(getString(R.string.toast_wallpaper_set));
            recreate();
        } else if (requestCode == REQ_DIR) {
            draftMode = Prefs.LOC_ABSOLUTE;
            draftValue = StorageUtil.normalizeForDisplay(this, path);
            if (locInput != null) { locInput.setText(draftValue); }
            updateLocUi();
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
