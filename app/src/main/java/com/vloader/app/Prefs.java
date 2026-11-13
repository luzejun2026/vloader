package com.vloader.app;

import android.content.Context;
import android.content.SharedPreferences;

/** 所有配置集中读写；除下载位置外，任何修改都即时生效。 */
public final class Prefs {

    public static final String VERSION_NAME = "1.1";

    public static final String STYLE_MATERIAL = "material_you";
    public static final String STYLE_MIUI = "miui";
    /** 主题样式常量。 */
    @Deprecated
    public static final String STYLE_GLASS = "liquid_glass";

    public static final int MODE_LIGHT = 0;
    public static final int MODE_DARK = 1;
    public static final int MODE_SYSTEM = 2;

    public static final int LOC_PUBLIC = 0;
    public static final int LOC_SUBDIR = 1;
    public static final int LOC_ABSOLUTE = 2;

    public static final int THREAD_UNLIMITED = 0;
    /** 默认并发线程数。 */
    public static final int DEFAULT_THREADS = 32;

    public static final int FONT_SMALL = 0;
    public static final int FONT_NORMAL = 1;
    public static final int FONT_LARGE = 2;
    public static final int FONT_XLARGE = 3;

    public static final String DEFAULT_SUBDIR = "m3u8downloader";

    public static final String CONTACT_QQ = "1741131705";
    public static final String CONTACT_QQ_URL = "https://qm.qq.com/q/EnYKvgEESG";
    public static final String CONTACT_BILI_NAME = "@Bilibili：失声雨下";
    public static final String CONTACT_BILI_UID = "3546388084295962";
    public static final String CONTACT_BILI_URL = "https://b23.tv/WubywYq";

    /** 关于页链接地址。 */
    public static final String EASTER_EGG_URL =
            "https://m.bilibili.com/video/BV12SoeYdEH8";

    private static final String FILE = "vloader_settings";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static void put(Context c, String k, Object v) {
        SharedPreferences.Editor e = sp(c).edit();
        if (v instanceof String) {
            e.putString(k, (String) v);
        } else if (v instanceof Boolean) {
            e.putBoolean(k, (Boolean) v);
        } else if (v instanceof Integer) {
            e.putInt(k, (Integer) v);
        }
        e.apply();
    }

    /** 读取当前主题样式：Material You / MIUI。 */
    public static String style(Context c) {
        String s = sp(c).getString("style", STYLE_MATERIAL);
        return STYLE_MIUI.equals(s) ? s : STYLE_MATERIAL;
    }

    public static void setStyle(Context c, String s) {
        put(c, "style", STYLE_MIUI.equals(s) ? STYLE_MIUI : STYLE_MATERIAL);
    }

    public static int darkMode(Context c) {
        int m = sp(c).getInt("dark_mode", MODE_SYSTEM);
        return m == MODE_LIGHT || m == MODE_DARK ? m : MODE_SYSTEM;
    }

    public static void setDarkMode(Context c, int m) {
        put(c, "dark_mode", m);
    }

    /** 执行下载任务后自动跳转至下载任务界面（「正在下载」Tab），默认开启。 */
    public static boolean autoJump(Context c) {
        return sp(c).getBoolean("auto_jump", true);
    }

    public static void setAutoJump(Context c, boolean b) {
        put(c, "auto_jump", b);
    }

    /**
     *  底栏形态开关：开启为「悬浮胶囊底栏」（参考 Scrcpy for Android 的 FloatingBottomBar），
     * 关闭为贴底全宽的经典固定 NavigationBar。默认开启。
     *
     * 与 {@link #liquidNav(Context)} 相互独立，可组合出 3 种形态：
     *   悬浮关           → 经典固定底栏（液态玻璃开关隐藏，不生效）
     *   悬浮开 + 玻璃关  → 悬浮胶囊 + 普通磨砂（backdrop 模糊，无折射/流光/动效）
     *   悬浮开 + 玻璃开  → 悬浮胶囊 + 全套液态玻璃（默认）
     */
    public static boolean floatingNav(Context c) {
        return sp(c).getBoolean("floating_nav", true);
    }

    public static void setFloatingNav(Context c, boolean b) {
        put(c, "floating_nav", b);
    }

    /** 悬浮胶囊的液态玻璃光学材质，默认开启；仅在 floatingNav 开启时有意义。 */
    public static boolean liquidNav(Context c) {
        return sp(c).getBoolean("liquid_nav", true);
    }

    public static void setLiquidNav(Context c, boolean b) {
        put(c, "liquid_nav", b);
    }

    /** 0=无限制；默认 32。 */
    public static int threads(Context c) {
        return Math.max(0, sp(c).getInt("threads", DEFAULT_THREADS));
    }

    public static void setThreads(Context c, int n) {
        put(c, "threads", Math.max(0, n));
    }

    public static int retries(Context c) {
        return Math.max(0, Math.min(10, sp(c).getInt("retries", 2)));
    }

    public static void setRetries(Context c, int n) {
        put(c, "retries", Math.max(0, Math.min(10, n)));
    }

    /** 一次最多同时下载的文件数；0 表示不限（全部并行）。默认 1。 */
    public static int filesPerRun(Context c) {
        return Math.max(0, sp(c).getInt("files_per_run", 1));
    }

    public static void setFilesPerRun(Context c, int n) {
        put(c, "files_per_run", Math.max(0, n));
    }

    /** 下载速度上限，单位 KB/s；0 表示不限速。 */
    public static int speedLimitKbps(Context c) {
        return Math.max(0, sp(c).getInt("speed_limit", 0));
    }

    public static void setSpeedLimitKbps(Context c, int n) {
        put(c, "speed_limit", Math.max(0, n));
    }

    public static String headers(Context c) {
        return sp(c).getString("headers", "");
    }

    public static void setHeaders(Context c, String s) {
        put(c, "headers", s);
    }

    public static int locMode(Context c) {
        int m = sp(c).getInt("loc_mode", LOC_PUBLIC);
        return m == LOC_SUBDIR || m == LOC_ABSOLUTE ? m : LOC_PUBLIC;
    }

    public static String locValue(Context c) {
        return sp(c).getString("loc_value", DEFAULT_SUBDIR);
    }

    /** 设置页里唯一需要点「保存位置」才提交的项。 */
    public static void saveLocation(Context c, int mode, String value) {
        sp(c).edit().putInt("loc_mode", mode).putString("loc_value", value).apply();
    }

    /** 单次 / 批量统一入口。 */
    public static boolean batch(Context c) {
        return true;
    }

    public static boolean notifyEnabled(Context c) {
        return sp(c).getBoolean("notify", true);
    }

    public static void setNotify(Context c, boolean b) {
        put(c, "notify", b);
    }

    /** 下载完成后弹出系统通知，默认开启。 */
    public static boolean finishNotify(Context c) {
        return sp(c).getBoolean("finish_notify", true);
    }

    public static void setFinishNotify(Context c, boolean b) {
        put(c, "finish_notify", b);
    }

    /** 锁屏后继续下载（需关闭电池优化），默认关闭。 */
    public static boolean keepAlive(Context c) {
        return sp(c).getBoolean("keep_alive", false);
    }

    public static void setKeepAlive(Context c, boolean b) {
        put(c, "keep_alive", b);
    }

    public static int fontLevel(Context c) {
        int v = sp(c).getInt("font_level", FONT_NORMAL);
        return v < FONT_SMALL || v > FONT_XLARGE ? FONT_NORMAL : v;
    }

    public static void setFontLevel(Context c, int v) {
        put(c, "font_level", v);
    }

    public static float fontScale(Context c) {
        switch (fontLevel(c)) {
            case FONT_SMALL:
                return 0.88f;
            case FONT_LARGE:
                return 1.16f;
            case FONT_XLARGE:
                return 1.32f;
            default:
                return 1.0f;
        }
    }

    /** 自定义壁纸图片的绝对路径；空串表示不使用。 */
    public static String wallpaper(Context c) {
        return sp(c).getString("wallpaper", "");
    }

    public static void setWallpaper(Context c, String path) {
        put(c, "wallpaper", path == null ? "" : path);
    }

    /** 空串表示每次询问；否则为默认打开方式的包名。 */
    public static String defaultOpener(Context c) {
        return sp(c).getString("default_opener", "");
    }

    public static void setDefaultOpener(Context c, String pkg) {
        put(c, "default_opener", pkg == null ? "" : pkg);
    }

    /** 用户协议是否已同意；false 时首次启动强制弹出全文。 */
    public static boolean eulaAccepted(Context c) {
        return sp(c).getBoolean("eula_accepted", false);
    }

    public static void setEulaAccepted(Context c, boolean v) {
        put(c, "eula_accepted", v);
    }

    /**  主页输入草稿：主题切换/重建 Activity 时保留已输入的链接与文件名。 */
    public static String draftUrl(Context c) {
        return sp(c).getString("draft_url", "");
    }

    public static String draftName(Context c) {
        return sp(c).getString("draft_name", "");
    }

    public static void saveDraft(Context c, String url, String name) {
        sp(c).edit()
                .putString("draft_url", url == null ? "" : url)
                .putString("draft_name", name == null ? "" : name)
                .apply();
    }

    private Prefs() {
    }
}
