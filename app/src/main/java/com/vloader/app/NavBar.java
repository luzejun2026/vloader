package com.vloader.app;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 液态玻璃 Navigation bar（3 个平级主页面）。
 *
 * 分层（从下到上），严格按设计规范：
 *   1) 内容层：各页面 root 的第 0 个子 View，铺满整窗、clipToPadding=false，
 *      内容滚动时能从玻璃正下方穿过 —— 不再给胶囊预留纯色空白。
 *   2) 玻璃层 BackdropView：实时把内容层重绘到自己的 Canvas 上，再用
 *      RenderEffect.createBlurEffect 对「这份下层画面」做背景模糊（不是模糊玻璃自身、
 *      不是预渲染截图），并 clip 到胶囊圆角。API<31 / 减少动效时使用不透明实底。
 *   3) 玻璃表面 GlassSurface：自适应 tint、上沿亮边、下沿暗边、Fresnel 边缘高光、
 *      缓慢移动的表面流光、按压点光晕。
 *   4) 活动指示器 IndicatorView：覆盖整个导航项的全圆角玻璃胶囊，弹簧物理滑动
 *      （可打断、带拉伸与回弹），支持手拖 scrub 吸附；高光随其位置流动。
 *   5) 前景层：图标（选中实心+主色）+ 文字，不参与模糊。不再绘制任何角标。
 *
 * 边缘高光与流光随指示器位置流动，表达材质对运动的响应。
 */
public final class NavBar {

    public static final int PAGE_HOME = 0;
    public static final int PAGE_HISTORY = 1;
    public static final int PAGE_SETTINGS = 2;

    private static final int COUNT = 3;
    private static final float SPRING_K = 260f;      // 刚度
    private static final float SPRING_D = 30f;       // 阻尼（略欠阻尼，带回弹）
    //  参考软件 Scrcpy for Android 的 FloatingBottomBar 规格
    private static final int BAR_H_DP = 64;          // height(64.dp)
    private static final int BAR_PAD_DP = 4;         // padding(4.dp)
    private static final int TAB_MIN_W_DP = 76;      // tab defaultMinSize(minWidth = 76.dp)
    private static final int BOTTOM_GAP_DP = 12;     // padding(bottom = 12.dp)
    private static final int SHADOW_RADIUS_DP = 10;  // dropShadow(radius = 10.dp)
    private static final float BLUR_RADIUS = 20f;    // backdrop 模糊半径
    private static final int CLASSIC_BAR_H_DP = 64;  // 经典固定底栏内容高度

    /** 不透明实底胶囊：API&lt;31 或减少动效时使用。 */
    private static final int MODE_SOLID = 0;
    /**  悬浮胶囊 + 普通磨砂（液态玻璃关闭）：backdrop 模糊 + 半透明实底。 */
    private static final int MODE_FROSTED = 1;
    /**  悬浮胶囊 + 全套液态玻璃：vibrancy / 折射近似 / 色散 / 内阴影 / 双光源高光。 */
    private static final int MODE_LIQUID = 2;

    private static int sLastPage = -1;

    public interface NavigateHook {
        /** 返回 true 表示已消费，不再启动 Activity。 */
        boolean onNavigate(Activity a, int target);
    }

    // ---------------------------------------------------------------- attach

    public static View attach(final Activity a, final FrameLayout root, final UiTheme th,
                              final int current, boolean liquid, final NavigateHook hook) {
        //  形态分流：悬浮底栏关闭 → 贴底全宽的经典固定 NavigationBar
        // （与参考软件 Scrcpy for Android 关闭「悬浮底栏」时的形态一致）
        if (!Prefs.floatingNav(a)) {
            return attachClassic(a, root, th, current, hook);
        }
        return attachFloating(a, root, th, current, liquid, hook);
    }

    /**
     *  悬浮胶囊底栏。形态规格对齐参考软件 FloatingBottomBar.kt：
     * 64dp 高、居中窄胶囊（每 tab ≥76dp）、底部 12dp 悬浮、10dp 柔投影、56dp 全高指示器。
     * 材质由「液态玻璃」开关决定：
     *   开 → MODE_LIQUID：全套折射近似 / 色散描边 / 内阴影 / 双光源高光 / 流光 / 按压缩放
     *   关 → MODE_FROSTED：普通磨砂（backdrop 模糊 + 半透明实底），无折射与动效
     * 纯 Java Canvas 绘制，配合 RenderEffect 背景模糊呈现玻璃质感。
     */
    private static View attachFloating(final Activity a, final FrameLayout root, final UiTheme th,
                                       final int current, boolean liquid, final NavigateHook hook) {
        final boolean reduce = reduceMotion(a);
        final boolean blurOk = liquid && !reduce && Build.VERSION.SDK_INT >= 31;
        // 低版本 / 减少动效时使用不透明实底
        final int mode = !blurOk ? MODE_SOLID
                : (Prefs.liquidNav(a) ? MODE_LIQUID : MODE_FROSTED);
        final boolean glass = mode == MODE_LIQUID;
        final boolean animate = glass && sLastPage >= 0 && sLastPage != current;
        final int from = sLastPage;
        sLastPage = current;

        // ---- 1) 内容层：铺到玻璃正下方，不给胶囊预留纯色占位 ----
        final View content = root.getChildCount() > 0 ? root.getChildAt(0) : null;
        if (content != null) {
            // 让可滚动内容绘制到 padding 区域（Android 版 extendBody）
            enableClipToPaddingFalse(content);
            // 极淡纹理画在 BackdropView 内，避免往 root 插入额外层级影响采样
        }

        final int barH = th.dp(a, BAR_H_DP);
        final int pad = th.dp(a, BAR_PAD_DP);
        final int screenW = a.getResources().getDisplayMetrics().widthPixels;
        // 参考：width(IntrinsicSize.Min)，胶囊宽度由 tab 内容决定，居中而非撑满屏幕
        final int barW = Math.min(screenW - th.dp(a, 24),
                th.dp(a, TAB_MIN_W_DP) * COUNT + pad * 2);
        final int itemW = (barW - pad * 2) / COUNT;
        final int itemH = barH - pad * 2;
        // 指示器铺满整个导航项、56dp 全高（参考 .height(56.dp)）
        final int indW = itemW;
        final int indH = itemH;

        final GlassBar bar = new GlassBar(a, th, mode, reduce, a, current, hook, pad, itemW);
        bar.setClickable(true);
        // 圆角≈半高，并把所有子层裁到胶囊里
        final int radius = barH / 2;
        bar.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        bar.setClipToOutline(true);
        if (Build.VERSION.SDK_INT >= 21) {
            bar.setElevation(th.dp(a, SHADOW_RADIUS_DP));
        }

        // ---- 2) 玻璃层：对下方实时画面做背景模糊 ----
        // mode 决定材质：LIQUID 走全套（vibrancy + 轻模糊 + 折射近似），FROSTED 只做普通磨砂
        final BackdropView backdrop = new BackdropView(a, th, mode);
        backdrop.content = content;
        backdrop.setWillNotDraw(false);
        bar.addView(backdrop, new FrameLayout.LayoutParams(barW, barH));

        // ---- 3) 玻璃表面：自适应 tint / 亮暗边 / 双光源高光 / 色散 / 流光 / 按压光晕 ----
        final GlassSurface surface = new GlassSurface(a, th, radius, mode, reduce);
        bar.addView(surface, new FrameLayout.LayoutParams(barW, barH));

        // ---- 4) 活动指示器：56dp 全高胶囊，带内阴影与色散描边 ----
        final IndicatorView indicator = new IndicatorView(a, th, indH / 2f, mode, reduce);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(indW, indH);
        ilp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        ilp.leftMargin = pad + (itemW - indW) / 2;
        bar.addView(indicator, ilp);

        // ---- 5) 前景层：底层深色全绘 + 上层蓝色「染色透镜」（无角标）----
        //  滑块局部染色：底层所有图标/文字都用未选中深色绘制；上层再叠一份
        // 位置像素级对齐的蓝色图标/文字，但按滑块（花块）当前覆盖的圆角矩形裁剪，
        // 只有被花块盖住的像素才显示蓝色 —— 于是同一个字可以左半深、右半蓝。
        final String[] labels = {"主页", "下载历史", "设置"};
        final int[] icons = {Icons.HOME, Icons.CLOCK, Icons.GEAR};
        final TextView[] labelViews = new TextView[COUNT];
        final View[] iconViews = new View[COUNT];

        final LinearLayout items = buildItems(a, th, labels, icons, itemW, itemH,
                pad, th.sub, true, current, hook, labelViews, iconViews);
        bar.addView(items, new FrameLayout.LayoutParams(barW, barH));

        final LinearLayout blueItems = buildItems(a, th, labels, icons, itemW, itemH,
                pad, th.primary, false, current, hook, null, null);
        final TintLayer tintLayer = new TintLayer(a, indicator, indH / 2f);
        tintLayer.setClickable(false);   // 点击穿透到底层 items 的导航项
        tintLayer.addView(blueItems, new FrameLayout.LayoutParams(barW, barH));
        bar.addView(tintLayer, new FrameLayout.LayoutParams(barW, barH));

        // ---- 弹簧：状态驱动、可打断、带拉伸回弹 ----
        final Spring spring = new Spring(a, th, indicator, itemW, indW, animate, reduce);
        spring.setTintLayer(tintLayer);   // #43 滑块移动时逐帧刷新蓝色透镜
        spring.setCurrent(current, from);
        bar.setSpring(spring);
        surface.setSpring(spring);
        indicator.setSpring(spring);

        // ---- 表面流光（缓慢移动，非静态贴图）----
        if (glass && !reduce) {
            final ValueAnimator flow = ValueAnimator.ofFloat(0f, 1f);
            flow.setDuration(7000);
            flow.setRepeatCount(ValueAnimator.INFINITE);
            flow.setInterpolator(null);
            flow.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator animation) {
                    surface.flow = (Float) animation.getAnimatedValue();
                    surface.invalidate();
                }
            });
            flow.start();
            bar.setTag(R.id.nav_flow_tag, flow);
        }

        // ---- 自适应反色：玻璃采集下方亮度后回调，底层文字随之深浅切换 ----
        //  蓝色不再按「选中项整项」给，而是交给上层 TintLayer 按滑块矩形裁剪。
        // 底层三项统一用未选中深色（亮背景下近黑），因此这里不再跳过 current 项。
        backdrop.setLumaListener(new BackdropView.LumaListener() {
            @Override public void onLuma(float luminance) {
                boolean lightBg = luminance > 0.55f;
                int onGlass = lightBg ? 0xFF1B1B1F : 0xFFF2F2F7;
                for (int i = 0; i < COUNT; i++) {
                    labelViews[i].setTextColor(onGlass);
                    Icons.tint(iconViews[i], onGlass);
                }
                surface.setLuma(luminance);
                tintLayer.invalidate();   // 同步刷新蓝色透镜，避免底色变了蓝层没跟上
            }
        });

        // ---- 悬浮位置：居中窄胶囊，底部 12dp；用 WindowInsets 动态避让系统手势条 ----
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(barW, barH);
        flp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        flp.bottomMargin = th.dp(a, BOTTOM_GAP_DP);
        bar.setLayoutParams(flp);
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    int nav = insets.getInsets(WindowInsets.Type.navigationBars()
                            | WindowInsets.Type.mandatorySystemGestures()).bottom;
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bar.getLayoutParams();
                    int want = Math.max(th.dp(a, BOTTOM_GAP_DP), nav + th.dp(a, 8));
                    if (lp.bottomMargin != want) {
                        lp.bottomMargin = want;
                        bar.setLayoutParams(lp);
                    }
                    return insets;
                }
            });
            bar.requestApplyInsets();
        }

        // 滚动时实时刷新玻璃采样（下层内容从玻璃里穿过）
        attachScrollRefresh(content, backdrop);
        root.addView(bar);
        return bar;
    }

    /**
     *  经典固定底栏：贴底全宽的 Material NavigationBar 形态。
     * 参考软件关闭「悬浮底栏」时走 Scaffold.bottomBar = NavigationBar。
     *
     * 特征：MATCH_PARENT 全宽、贴底（bottomMargin=0）、实色 surface 背景、
     * 顶部分割线、选中项药丸指示器 + 主色图标文字、手势条 inset 由底栏自身吸收。
     * 不做 backdrop 采样 / 模糊 / 流光 / 弹簧拖拽，内容层沿用页面已有的 96dp 底部留白。
     */
    private static View attachClassic(final Activity a, final FrameLayout root, final UiTheme th,
                                      final int current, final NavigateHook hook) {
        sLastPage = current;
        final ClassicBar bar = new ClassicBar(a, th, current, a, hook);
        bar.setClickable(true);

        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                th.dp(a, CLASSIC_BAR_H_DP));
        flp.gravity = Gravity.BOTTOM;
        bar.setLayoutParams(flp);
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    int nav = insets.getInsets(WindowInsets.Type.navigationBars()
                            | WindowInsets.Type.mandatorySystemGestures()).bottom;
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bar.getLayoutParams();
                    int want = th.dp(a, CLASSIC_BAR_H_DP) + nav;
                    if (lp.height != want) {
                        lp.height = want;
                        bar.setBottomInset(nav);
                        bar.setLayoutParams(lp);
                    }
                    return insets;
                }
            });
            bar.requestApplyInsets();
        }
        root.addView(bar);
        return bar;
    }

    /**
     *  经典固定底栏本体：自绘背景 + 顶部分割线，内部三个等宽导航项。
     * 选中项：图标上方 64x32 圆角药丸指示器（soft 色）+ 主色图标与文字。
     */
    static final class ClassicBar extends LinearLayout {
        private final UiTheme th;
        private final int currentPage;
        private final Activity activity;
        private final NavigateHook hook;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final View[] indicators = new View[COUNT];
        private final View[] iconViews = new View[COUNT];
        private final TextView[] labelViews = new TextView[COUNT];
        private int bottomInset;

        ClassicBar(Context c, UiTheme th, int current, Activity a, NavigateHook hook) {
            super(c);
            this.th = th;
            this.currentPage = current;
            this.activity = a;
            this.hook = hook;
            setOrientation(LinearLayout.HORIZONTAL);
            setWillNotDraw(false);
            setBackgroundColor(th.surface);

            final String[] labels = {"主页", "下载历史", "设置"};
            final int[] icons = {Icons.HOME, Icons.CLOCK, Icons.GEAR};
            for (int i = 0; i < COUNT; i++) {
                final int page = i;
                FrameLayout slot = new FrameLayout(c);
                slot.setLayoutParams(new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.MATCH_PARENT, 1f));

                // 选中项药丸指示器：图标后方的 64x32 圆角块（Material 3 active indicator）
                View ind = new View(c);
                GradientDrawable g = new GradientDrawable();
                g.setColor(th.soft);
                g.setCornerRadius(th.dp(c, 16));
                ind.setBackground(g);
                FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                        th.dp(c, 64), th.dp(c, 32));
                ilp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
                ilp.topMargin = th.dp(c, 12);
                ind.setVisibility(i == current ? VISIBLE : INVISIBLE);
                slot.addView(ind, ilp);
                indicators[i] = ind;

                LinearLayout col = new LinearLayout(c);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setGravity(Gravity.CENTER);
                col.setLayoutParams(new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
                int fg = i == current ? th.primary : th.sub;
                View icon = Icons.of(c, icons[i], fg, 24);
                LinearLayout.LayoutParams iclp = new LinearLayout.LayoutParams(
                        th.dp(c, 24), th.dp(c, 24));
                iclp.gravity = Gravity.CENTER_HORIZONTAL;
                col.addView(icon, iclp);
                iconViews[i] = icon;

                TextView label = new TextView(c);
                label.setText(labels[i]);
                label.setTextSize(12 * th.fontScale);
                label.setTextColor(fg);
                label.setTypeface(Typeface.DEFAULT);
                label.setGravity(Gravity.CENTER);
                label.setSingleLine(true);
                label.setPadding(0, th.dp(c, 3), 0, 0);
                col.addView(label);
                labelViews[i] = label;
                slot.addView(col);

                slot.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (hook != null && hook.onNavigate(activity, page)) { return; }
                        if (page == currentPage) { return; }
                        Class<?> target = page == PAGE_HOME ? MainActivity.class
                                : page == PAGE_HISTORY ? HistoryActivity.class
                                : SettingsActivity.class;
                        Intent it = new Intent(activity, target);
                        it.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        activity.startActivity(it);
                        slidePage(activity, page > currentPage);
                    }
                });
                addView(slot);
            }
        }

        void setBottomInset(int v) {
            bottomInset = v;
            setPadding(getPaddingLeft(), getPaddingTop(), getPaddingRight(), v);
        }

        @Override protected void onDraw(Canvas cv) {
            super.onDraw(cv);
            // 顶部分割线：经典固定底栏靠它与内容区分，而非悬浮胶囊的阴影
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(th.divider);
            cv.drawRect(0, 0, getWidth(), Math.max(1, th.dp(getContext(), 1)), paint);
        }
    }

    /**
     *  构建一层导航前景（图标 + 文字）。两层用完全相同的布局参数，
     * 保证像素级对齐：底层深色全绘，上层蓝色全绘后被滑块圆角矩形裁剪。
     * color 为该层图标/文字颜色；withClick 仅底层为 true（挂导航点击）。
     * out 数组可选，用于把底层控件交给 luma 自适应反色回调重新着色。
     */
    private static LinearLayout buildItems(final Activity a, final UiTheme th,
                                            final String[] labels, final int[] icons,
                                            final int itemW, final int itemH, final int pad,
                                            final int color, final boolean withClick,
                                            final int current, final NavigateHook hook,
                                            final TextView[] labelOut, final View[] iconOut) {
        LinearLayout items = new LinearLayout(a);
        items.setOrientation(LinearLayout.HORIZONTAL);
        items.setPadding(pad, pad, pad, pad);
        for (int i = 0; i < COUNT; i++) {
            final int page = i;
            FrameLayout slot = new FrameLayout(a);
            slot.setLayoutParams(new LinearLayout.LayoutParams(itemW, itemH));

            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            col.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

            View icon = Icons.of(a, icons[i], color, 24);
            if (iconOut != null) { iconOut[i] = icon; }
            LinearLayout.LayoutParams iclp = new LinearLayout.LayoutParams(
                    th.dp(a, 24), th.dp(a, 24));
            iclp.gravity = Gravity.CENTER_HORIZONTAL;
            col.addView(icon, iclp);

            TextView label = new TextView(a);
            label.setText(labels[i]);
            label.setTextSize(11 * th.fontScale);
            label.setTextColor(color);
            label.setTypeface(Typeface.DEFAULT);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setPadding(0, th.dp(a, 3), 0, 0);
            if (labelOut != null) { labelOut[i] = label; }
            col.addView(label);
            slot.addView(col);

            if (withClick) {
                slot.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (hook != null && hook.onNavigate(a, page)) { return; }
                        if (page == current) { return; }
                        Class<?> target = page == PAGE_HOME ? MainActivity.class
                                : page == PAGE_HISTORY ? HistoryActivity.class
                                : SettingsActivity.class;
                        Intent it = new Intent(a, target);
                        it.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        a.startActivity(it);
                        slidePage(a, page > current);
                    }
                });
            }
            items.addView(slot);
        }
        return items;
    }

    // ---------------------------------------------------------------- 兼容 API

    /** 不锁定导航项；保留签名兼容既有调用。 */
    public static void setLocked(View bar, boolean locked) { }

    /** 页面切换无动效：直接切，不做滑动/淡化转场。 */
    public static void slidePage(Activity a, boolean forward) {
        a.overridePendingTransition(0, 0);
    }

    /** 兼容旧调用；同样无动效。 */
    public static void crossFade(Activity a) {
        a.overridePendingTransition(0, 0);
    }

    /** 进入二级页面时隐藏整条悬浮底栏；退回主页面恢复。 */
    public static void setVisible(View bar, boolean visible) {
        if (bar == null) { return; }
        bar.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (bar instanceof GlassBar) {
            GlassBar g = (GlassBar) bar;
            if (!visible) { g.stopFlow(); }
            else { g.resumeFlow(); }
        }
    }

    /**
     * 页面回到前台时强制把滑块与静态页码同步到当前页。
     *
     * 页面切换时底栏选中项跟随当前页面：底栏用
     * FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP 切页，目标 Activity 会被复用、
     * 走 onNewIntent 而不再重新 attach()，于是滑块可能停留在上一页的位置，
     * 或仍被上一次没跑完的弹簧帧回调拉向旧目标。这里撤销残留动画并强制归位。
     */
    public static void syncCurrent(View bar, int page) {
        sLastPage = page;
        if (!(bar instanceof GlassBar)) { return; }
        Spring s = ((GlassBar) bar).getSpring();
        if (s != null) { s.forceTo(page); }
    }

    // ---------------------------------------------------------------- 工具

    private static boolean reduceMotion(Context c) {
        try {
            float s = Settings.Global.getFloat(c.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            if (s == 0f) { return true; }
        } catch (Exception ignored) { }
        try {
            if (Settings.Global.getInt(c.getContentResolver(), "reduce_motion", 0) != 0) {
                return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private static void enableClipToPaddingFalse(View v) {
        if (v instanceof ScrollView) {
            ((ScrollView) v).setClipToPadding(false);
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                enableClipToPaddingFalse(g.getChildAt(i));
            }
        }
    }

    private static void attachScrollRefresh(View v, final BackdropView backdrop) {
        if (Build.VERSION.SDK_INT >= 23 && v instanceof ScrollView) {
            ((ScrollView) v).setOnScrollChangeListener(new View.OnScrollChangeListener() {
                @Override public void onScrollChange(View vv, int x, int y, int ox, int oy) {
                    backdrop.invalidate();
                }
            });
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                attachScrollRefresh(g.getChildAt(i), backdrop);
            }
        }
    }

    // ---------------------------------------------------------------- 内部类

    /** 自定义 FrameLayout：拦截水平拖动做指示器 scrub，并处理按压弹性缩放。 */
    static final class GlassBar extends FrameLayout {
        private final UiTheme th;
        private final int mode;
        private final boolean reduce;
        private final Activity activity;
        private final int currentPage;
        private final NavigateHook hook;
        private final int padPx;
        private final int itemW;
        private Spring spring;
        private float downX, downY;
        private boolean dragging;
        private ValueAnimator pressAnim;

        GlassBar(Context c, UiTheme th, int mode, boolean reduce, Activity activity,
                 int currentPage, NavigateHook hook, int padPx, int itemW) {
            super(c);
            this.th = th;
            this.mode = mode;
            this.reduce = reduce;
            this.activity = activity;
            this.currentPage = currentPage;
            this.hook = hook;
            this.padPx = padPx;
            this.itemW = itemW;
        }

        void setSpring(Spring s) { this.spring = s; }
        Spring getSpring() { return spring; }

        void stopFlow() {
            Object t = getTag(R.id.nav_flow_tag);
            if (t instanceof ValueAnimator) { ((ValueAnimator) t).cancel(); }
        }

        void resumeFlow() {
            Object t = getTag(R.id.nav_flow_tag);
            if (t instanceof ValueAnimator && !reduce) { ((ValueAnimator) t).start(); }
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (reduce || spring == null) { return false; }
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    downY = ev.getY();
                    dragging = false;
                    press(true, ev.getX(), ev.getY());
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(ev.getX() - downX) > th.dp(getContext(), 8)
                            && Math.abs(ev.getX() - downX)
                            > Math.abs(ev.getY() - downY)) {
                        dragging = true;
                        press(false, 0, 0);
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    press(false, 0, 0);
                    return false;
                default:
                    return false;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            if (reduce || spring == null) { return super.onTouchEvent(ev); }
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (dragging) {
                        spring.dragTo(ev.getX() - padPx - itemW / 2f);
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        int page = spring.snap();
                        dragging = false;
                        navigateTo(page);
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) { spring.snap(); dragging = false; }
                    return true;
                default:
                    return super.onTouchEvent(ev);
            }
        }

        /** scrub 松手后切到吸附到的主页面。 */
        private void navigateTo(int page) {
            if (hook != null && hook.onNavigate(activity, page)) { return; }
            if (page == currentPage) { return; }
            Class<?> target = page == PAGE_HOME ? MainActivity.class
                    : page == PAGE_HISTORY ? HistoryActivity.class
                    : SettingsActivity.class;
            Intent it = new Intent(activity, target);
            it.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(it);
            slidePage(activity, page > currentPage);
        }

        /**
         *  按压缩放，对齐参考 FloatingBottomBar.kt：
         * 整条胶囊按下时轻微撑大（layerBlock scale = 1 + 16dp/宽度），
         * 指示器放大到 78/56≈1.39、tab 内容放大到 1.2（由 Spring.pressProgress 驱动）；
         * 松手回弹到 1。磨砂/实底模式不做缩放，只保留触点光晕。
         */
        private void press(boolean down, float x, float y) {
            if (reduce) { return; }
            if (pressAnim != null) { pressAnim.cancel(); }
            float grow = 1f + (float) th.dp(getContext(), 16) / Math.max(1, getWidth());
            float to = (down && mode == MODE_LIQUID) ? grow : 1f;
            pressAnim = ValueAnimator.ofFloat(getScaleX(), to);
            pressAnim.setDuration(down ? 110 : 220);
            pressAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    float v = (Float) a.getAnimatedValue();
                    setScaleX(v);
                    setScaleY(v);
                }
            });
            pressAnim.start();
            if (spring != null) { spring.setPress(down && mode == MODE_LIQUID); }
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c instanceof GlassSurface) {
                    ((GlassSurface) c).setPress(down, x, y);
                }
            }
        }
    }

    /**
     *  局部染色透镜：内部是一整层「蓝色图标 + 蓝色文字」，与底层 items 像素级对齐，
     * 但 dispatchDraw 时把画布裁剪到滑块（IndicatorView）当前覆盖的圆角矩形内。
     * 于是只有被花块盖住的像素才显示蓝色，未盖住处透出底层深色，
     * 同一个字可以左半深、右半蓝；滑块每帧移动由 Spring 触发 invalidate 重算裁剪区。
     *
     * indicator 与本层同为 GlassBar 的子 View、且本层铺满整个 bar（left/top=0），
     * 坐标系一致，因此 indicator 的 getX()/getY()/缩放可直接作为本层裁剪矩形。
     */
    static final class TintLayer extends FrameLayout {
        private final View indicator;
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private final float radius;

        TintLayer(Context c, View indicator, float radius) {
            super(c);
            this.indicator = indicator;
            this.radius = radius;
            setWillNotDraw(false);
        }

        /** 蓝色层不参与任何触摸命中，点击照常落到底层 items。 */
        @Override public boolean dispatchTouchEvent(MotionEvent ev) {
            return false;
        }

        @Override protected void dispatchDraw(Canvas canvas) {
            float cx = indicator.getX() + indicator.getWidth() / 2f;
            float cy = indicator.getY() + indicator.getHeight() / 2f;
            float hw = indicator.getWidth() * indicator.getScaleX() / 2f;
            float hh = indicator.getHeight() * indicator.getScaleY() / 2f;
            if (hw <= 0 || hh <= 0) { return; }
            rect.set(cx - hw, cy - hh, cx + hw, cy + hh);
            path.reset();
            path.addRoundRect(rect, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(path);
            super.dispatchDraw(canvas);   // 蓝色子 View 只画在滑块圆角矩形内
            canvas.restore();
        }
    }

    /** 玻璃层：实时重绘下层内容并做背景模糊（非自身模糊、非静态截图）。 */
    static final class BackdropView extends View {
        interface LumaListener { void onLuma(float luminance); }

        View content;
        private final int mode;
        /** 非实底形态下对下层采样并模糊（磨砂与液态玻璃都需要）。 */
        private final boolean blur;
        private final int rootBg;
        private final int texColor;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] loc = new int[2];
        private Bitmap probe;
        private Canvas probeCanvas;
        private LumaListener lumaListener;
        private int frame;

        BackdropView(Context c, UiTheme th, int mode) {
            super(c);
            this.mode = mode;
            this.blur = mode != MODE_SOLID;
            this.rootBg = th.bg;
            this.texColor = th.dark ? 0x0CFFFFFF : 0x0D000000;
            if (blur && Build.VERSION.SDK_INT >= 31) {
                try {
                    //  参考 effects：vibrancy()（提饱和度）+ blur(4dp)。
                    // 纯 Java 用 RenderEffect 链近似：模糊 → 色彩增强；
                    // 磨砂态只做模糊，不叠 vibrancy（关闭液态玻璃 = 普通高斯模糊）。
                    RenderEffect re = RenderEffect.createBlurEffect(
                            BLUR_RADIUS, BLUR_RADIUS, Shader.TileMode.CLAMP);
                    if (mode == MODE_LIQUID) {
                        ColorMatrix cm = new ColorMatrix();
                        cm.setSaturation(1.35f);          // vibrancy 近似
                        RenderEffect vib = RenderEffect.createColorFilterEffect(
                                new ColorMatrixColorFilter(cm));
                        re = RenderEffect.createChainEffect(vib, re);
                    }
                    setRenderEffect(re);
                } catch (Throwable ignored) { }
            }
        }

        void setLumaListener(LumaListener l) { this.lumaListener = l; }

        @Override protected void onDraw(Canvas cv) {
            if (!blur || content == null || getWidth() <= 0) { return; }
            getLocationInWindow(loc);
            // 把「整窗下层内容」按窗口坐标重绘到本 View 的 Canvas，随后由 RenderEffect 模糊
            cv.drawColor(rootBg);
            // 极淡对角线纹理：静态页面下玻璃也有明暗层次可透，而不是一片纯色
            LinearGradient tex = new LinearGradient(0, 0, getWidth(), getHeight(),
                    Color.TRANSPARENT, texColor, Shader.TileMode.CLAMP);
            paint.setShader(tex);
            cv.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setShader(null);
            cv.save();
            cv.translate(-loc[0], -loc[1]);
            content.draw(cv);
            cv.restore();
            if ((frame++ & 3) == 0) { sampleLuma(); }
        }

        /** 低分辨率采集下层画面平均亮度，供自适应 tint 与前景反色。 */
        private void sampleLuma() {
            try {
                if (probe == null) {
                    probe = Bitmap.createBitmap(12, 6, Bitmap.Config.ARGB_8888);
                    probeCanvas = new Canvas(probe);
                }
                probeCanvas.drawColor(rootBg);
                probeCanvas.save();
                probeCanvas.scale(12f / getWidth(), 6f / getHeight());
                probeCanvas.translate(-loc[0], -loc[1]);
                content.draw(probeCanvas);
                probeCanvas.restore();
                long sum = 0;
                int n = 0;
                for (int y = 0; y < 6; y++) {
                    for (int x = 0; x < 12; x++) {
                        int c = probe.getPixel(x, y);
                        sum += (Color.red(c) * 299 + Color.green(c) * 587
                                + Color.blue(c) * 114) / 1000;
                        n++;
                    }
                }
                if (lumaListener != null) { lumaListener.onLuma(sum / (255f * n)); }
            } catch (Throwable ignored) { }
        }
    }

    /**
     * 玻璃表面：自适应 tint、上沿亮边、下沿暗边、Fresnel 高光、流光、按压光晕。
     *  液态玻璃态额外叠加：双光源镜面高光（BloomStroke 近似）、边缘色散描边、
     * 折射亮环（lens 的 SDF 位移用「内缩亮环 + 外扩暗环」近似）。
     */
    static final class GlassSurface extends View {
        private final UiTheme th;
        private final float radius;
        private final int mode;
        private final boolean glass;
        private final boolean reduce;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF rect = new RectF();
        private float luma = 0.8f;
        float flow;
        private Spring spring;
        private boolean pressed;
        private float pressX, pressY, pressAlpha;

        GlassSurface(Context c, UiTheme th, float radius, int mode, boolean reduce) {
            super(c);
            this.th = th;
            this.radius = radius;
            this.mode = mode;
            this.glass = mode != MODE_SOLID;
            this.reduce = reduce;
            setWillNotDraw(false);
        }

        void setSpring(Spring s) { this.spring = s; }
        void setLuma(float v) { this.luma = v; invalidate(); }
        void setPress(boolean down, float x, float y) {
            this.pressed = down;
            this.pressX = x;
            this.pressY = y;
            this.pressAlpha = down ? 1f : 0f;
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) { return; }
            rect.set(0, 0, w, h);
            clip.reset();
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
            cv.save();
            cv.clipPath(clip);

            // 自适应着色：亮底→极淡白，暗底→极淡黑；实底形态用不透明表面色
            //  参考 containerColor = surfaceContainer.copy(0.4f)：液态玻璃态用更透的 0x66
            if (!glass) {
                cv.drawColor(th.surface);
            } else if (mode == MODE_FROSTED) {
                // 普通磨砂：参考 textureBlur 的 surfaceContainer.copy(0.6f) 半透明实底
                cv.drawColor(withAlpha(th.surface, 0x99));
            } else {
                int tint = luma > 0.55f ? 0x38FFFFFF : 0x50000000;
                cv.drawColor(tint);
                cv.drawColor(withAlpha(th.surface, 0x40));  // 参考 0.4f 容器色
            }

            // 上沿亮边 + 下沿淡暗边（表达厚度，不靠厚重阴影）
            LinearGradient top = new LinearGradient(0, 0, 0, th.dp(getContext(), 10),
                    glass ? 0x66FFFFFF : 0x22FFFFFF, Color.TRANSPARENT, Shader.TileMode.CLAMP);
            paint.setShader(top);
            paint.setStyle(Paint.Style.FILL);
            cv.drawRect(0, 0, w, th.dp(getContext(), 10), paint);
            LinearGradient bottom = new LinearGradient(0, h - th.dp(getContext(), 8), 0, h,
                    Color.TRANSPARENT, 0x2A000000, Shader.TileMode.CLAMP);
            paint.setShader(bottom);
            cv.drawRect(0, h - th.dp(getContext(), 8), w, h, paint);

            // Fresnel 边缘高光：亮度集中在活动指示器附近，随滑块位置流动
            float hx = spring == null ? w * 0.5f : spring.indicatorCenterX();
            float norm = Math.max(0.001f, Math.min(0.999f, hx / w));
            LinearGradient edge = new LinearGradient(hx - w * 0.45f, 0, hx + w * 0.45f, 0,
                    new int[]{Color.TRANSPARENT, 0x4DFFFFFF, Color.TRANSPARENT},
                    new float[]{0f, norm, 1f}, Shader.TileMode.CLAMP);
            paint.setShader(edge);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.5f, th.dp(getContext(), 1)));
            cv.drawRoundRect(rect, radius, radius, paint);

            //  双光源镜面高光：参考 iOS specular 的 primaryLight(上) + secondaryLight(下,0.4)。
            // 用两条极细的白色描边近似 BloomStroke 的双峰，不假称实现了着色器。
            if (mode == MODE_LIQUID) {
                paint.setShader(null);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, th.dp(getContext(), 1)));
                paint.setColor(0x1FFFFFFF);          // 主光：上沿
                cv.drawRoundRect(rect, radius, radius, paint);
                paint.setColor(0x0DFFFFFF);          // 次光：下沿（约 0.4 强度）
                RectF inner = new RectF(0, th.dp(getContext(), 2),
                        w, h - th.dp(getContext(), 2));
                cv.drawRoundRect(inner, radius, radius, paint);

                //  色散描边：参考 chromaticAberration=0.5，用左右两条极淡互补色边近似。
                // 纯 Canvas 无法做逐像素 RGB 位移，这里只在边缘表达色散观感。
                paint.setStrokeWidth(Math.max(1f, th.dp(getContext(), 1)));
                paint.setColor(0x1F66E0FF);          // 冷色偏折
                RectF cold = new RectF(th.dp(getContext(), 1), 0, w, h);
                cv.drawRoundRect(cold, radius, radius, paint);
                paint.setColor(0x1FFF8080);          // 暖色偏折
                RectF warm = new RectF(0, 0, w - th.dp(getContext(), 1), h);
                cv.drawRoundRect(warm, radius, radius, paint);
            }

            // 表面流光：几乎看不见的斜向高光带，缓慢移动（仅液态玻璃态）
            if (mode == MODE_LIQUID && !reduce) {
                float band = (flow * 2f - 0.5f) * w;
                LinearGradient sheen = new LinearGradient(band - w * 0.18f, 0,
                        band + w * 0.18f, h,
                        new int[]{Color.TRANSPARENT, 0x14FFFFFF, Color.TRANSPARENT},
                        new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
                paint.setShader(sheen);
                paint.setStyle(Paint.Style.FILL);
                cv.drawRect(rect, paint);
            }

            // 按压点光源：触点向外扩散一圈很淡的光晕
            if (pressed && !reduce) {
                RadialGradient glow = new RadialGradient(pressX, pressY,
                        th.dp(getContext(), 46), 0x33FFFFFF, Color.TRANSPARENT,
                        Shader.TileMode.CLAMP);
                paint.setShader(glow);
                paint.setAlpha((int) (255 * pressAlpha));
                cv.drawRect(rect, paint);
                paint.setAlpha(255);
            }
            paint.setShader(null);
            cv.restore();
        }
    }

    /**
     * 活动指示器：半透明玻璃胶囊，弹簧滑动、可打断、带拉伸回弹。
     *  液态玻璃态叠加：参考 innerShadow(radius=8dp, black 0.15) 的内阴影、
     * lens 折射亮环（depthEffect 近似）、随按压增强的色散边。
     */
    static final class IndicatorView extends View {
        private final UiTheme th;
        private final float radius;
        private final int mode;
        private final boolean glass;
        private final boolean reduce;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private Spring spring;

        IndicatorView(Context c, UiTheme th, float radius, int mode, boolean reduce) {
            super(c);
            this.th = th;
            this.radius = radius;
            this.mode = mode;
            this.glass = mode != MODE_SOLID;
            this.reduce = reduce;
            setWillNotDraw(false);
        }

        void setSpring(Spring s) { this.spring = s; }

        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            rect.set(0, 0, w, h);
            // 参考 onDrawSurface：亮主题 Black 10% / 暗主题 White 10%；磨砂态用纯色药丸
            int base;
            if (mode == MODE_LIQUID) {
                base = th.dark ? 0x1AFFFFFF : 0x1A000000;
            } else if (mode == MODE_FROSTED) {
                base = withAlpha(th.primary, 0x26);   // 参考 accentColor.copy(0.15f)
            } else {
                base = th.soft;
            }
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(base);
            cv.drawRoundRect(rect, radius, radius, paint);
            if (mode == MODE_LIQUID) {
                cv.drawColor(withAlpha(th.surface, 0x33));
            }
            // 与主胶囊同套材质语言：极细亮边 + 顶部内高光
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, th.dp(getContext(), 1)));
            paint.setColor(glass ? 0x66FFFFFF : th.primary);
            cv.drawRoundRect(rect, radius, radius, paint);
            if (glass) {
                LinearGradient g = new LinearGradient(0, 0, 0, h * 0.5f,
                        0x3DFFFFFF, Color.TRANSPARENT, Shader.TileMode.CLAMP);
                paint.setShader(g);
                paint.setStyle(Paint.Style.FILL);
                cv.drawRoundRect(rect, radius, radius, paint);
                paint.setShader(null);
            }

            //  内阴影：参考 innerShadow(radius=8dp, black 0.15)，沿内缘一圈暗渐变。
            // 同时用一圈内缩亮环近似 lens 的 depthEffect（折射让边缘内容被挤压）。
            if (mode == MODE_LIQUID) {
                float inset = Math.max(1f, th.dp(getContext(), 8));
                RectF inner = new RectF(inset, inset, w - inset, h - inset);
                LinearGradient sh = new LinearGradient(0, 0, 0, inset,
                        0x26000000, Color.TRANSPARENT, Shader.TileMode.CLAMP);
                paint.setShader(sh);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(inset);
                cv.drawRoundRect(inner, radius - inset, radius - inset, paint);
                paint.setShader(null);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, th.dp(getContext(), 1)));
                paint.setColor(0x2BFFFFFF);
                cv.drawRoundRect(inner, radius - inset, radius - inset, paint);
            }
        }
    }

    /** 把主题色改成指定 alpha，用于参考规格里的 copy(0.4f)/copy(0.6f) 半透明容器色。 */
    private static int withAlpha(int argb, int alpha) {
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    /** 阻尼弹簧：状态驱动、可打断，移动中按速度拉伸，到位回弹。 */
    static final class Spring implements Choreographer.FrameCallback {
        private final Choreographer ch = Choreographer.getInstance();
        private final UiTheme th;
        private final IndicatorView indicator;
        private final int itemW;
        private final int startOffset;
        private final boolean reduce;
        private float x, v, target;
        private long last;
        private boolean running;
        private int current;
        private View tintLayer;   // #43 蓝色透镜层，滑块移动时逐帧重算裁剪区
        /**  按压进度 0→1：指示器放大到 78/56、整条胶囊按 4dp 上限跟随拖动。 */
        private float pressProgress;
        private ValueAnimator pressAnim;

        Spring(Context c, UiTheme th, IndicatorView indicator, int itemW, int indW,
               boolean animateIn, boolean reduce) {
            this.th = th;
            this.indicator = indicator;
            this.itemW = itemW;
            this.startOffset = 0;
            this.reduce = reduce;
        }

        void setTintLayer(View v) { this.tintLayer = v; }

        /**  参考 DampedDragAnimation.pressProgress：按下时指示器与 tab 一起放大。 */
        void setPress(boolean down) {
            if (reduce) { return; }
            if (pressAnim != null) { pressAnim.cancel(); }
            pressAnim = ValueAnimator.ofFloat(pressProgress, down ? 1f : 0f);
            pressAnim.setDuration(down ? 120 : 240);
            pressAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    pressProgress = (Float) a.getAnimatedValue();
                    apply();
                }
            });
            pressAnim.start();
        }

        /**
         * 页面打开时滑块直接落在当前项，不做入场动画。
         * 滑块以当前页面位置为基准，随页面切换平滑移动，
         * 但 View 尚未附着时回调不执行，滑块会永远停在中间那一项。
         */
        void setCurrent(int page, int from) {
            this.current = page;
            this.x = page * itemW;
            this.target = x;
            this.v = 0;
            // 撤销上一次可能仍在跑的帧回调，否则它会把滑块重新拉回旧页
            running = false;
            ch.removeFrameCallback(this);
            apply();
        }

        /** 页面回到前台时强制归位（无动画、清除残留动画）。 */
        void forceTo(int page) {
            setCurrent(page, page);
        }

        /** 只改目标，不重置位置/速度 —— 因此可被打断并带速度感。 */
        void setTarget(int page) {
            this.current = page;
            this.target = page * itemW;
            start();
        }

        void dragTo(float position) {
            this.x = Math.max(0f, Math.min((COUNT - 1) * itemW, position));
            this.v = 0;
            apply();
        }

        /** 松手吸附到最近一项并轻微回弹。 */
        int snap() {
            int page = Math.round(x / itemW);
            page = Math.max(0, Math.min(COUNT - 1, page));
            current = page;
            target = page * itemW;
            start();
            return page;
        }

        float indicatorCenterX() {
            return x + itemW / 2f;
        }

        private void start() {
            if (running || reduce) {
                if (reduce) { x = target; apply(); }
                return;
            }
            running = true;
            last = 0;
            ch.postFrameCallback(this);
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) { return; }
            float dt = last == 0 ? 0.016f
                    : Math.min(0.032f, (frameTimeNanos - last) / 1_000_000_000f);
            last = frameTimeNanos;
            float a = SPRING_K * (target - x) - SPRING_D * v;
            v += a * dt;
            x += v * dt;
            apply();
            // 玻璃表面随滑块位置流动
            ViewParent p = indicator.getParent();
            if (p instanceof GlassBar) {
                GlassBar bar = (GlassBar) p;
                for (int i = 0; i < bar.getChildCount(); i++) {
                    View c = bar.getChildAt(i);
                    if (c instanceof GlassSurface) { c.invalidate(); }
                }
            }
            if (Math.abs(target - x) < 0.6f && Math.abs(v) < 3f) {
                x = target;
                v = 0;
                apply();
                running = false;
                return;
            }
            ch.postFrameCallback(this);
        }

        private void apply() {
            indicator.setTranslationX(x);
            // 移动中按速度轻微拉伸，停下回弹；叠加按压放大
            float stretch = 1f + Math.min(0.34f, Math.abs(v) / (itemW * 26f));
            float press = 1f + (78f / 56f - 1f) * pressProgress;
            indicator.setScaleX(stretch * press);
            indicator.setScaleY((1f - (stretch - 1f) * 0.35f) * press);
            //  参考 panelOffset：拖动时整条胶囊按位移方向最多跟随 4dp（EaseOut 近似）
            ViewParent p = indicator.getParent();
            if (p instanceof GlassBar) {
                float frac = Math.max(-1f, Math.min(1f, (x - target) / itemW));
                float eased = (float) (1 - Math.pow(1 - Math.abs(frac), 3));
                ((GlassBar) p).setTranslationX(
                        Math.signum(frac) * eased * th.dp(((GlassBar) p).getContext(), 4));
            }
            //  滑块位置/形变每帧变化，蓝色透镜的裁剪区必须同步刷新，
            // 否则会停在上一帧的位置，出现「字已经滑走蓝色还留在原处」。
            if (tintLayer != null) {
                tintLayer.invalidate();
            }
        }
    }

    private NavBar() { }
}
