package com.vloader.app;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * 界面风格：Material You（MD3）/ MIUI 两套主题 × 浅色 / 深色 / 跟随系统。
 */
public final class UiTheme {

    public final String style;
    public final boolean dark;
    public final boolean miui;
    public final int bg;
    public final int surface;
    public final int field;
    public final int primary;
    public final int onPrimary;
    public final int soft;
    public final int text;
    public final int sub;
    public final int divider;
    public final int stroke;
    public final int cardRadius;
    public final int btnRadius;
    public final int fieldRadius;
    public final int pad;
    public final int titleSize;
    /** 字号倍率：所有文字统一乘它，设置页可切换小/标准/大/超大。 */
    public final float fontScale;

    private UiTheme(String style, boolean dark, int bg, int surface, int field,
                    int primary, int onPrimary, int soft, int text, int sub, int divider,
                    int stroke, int cardRadius, int btnRadius, int fieldRadius, int pad,
                    int titleSize, float fontScale) {
        this.style = style;
        this.dark = dark;
        this.miui = Prefs.STYLE_MIUI.equals(style);
        this.bg = bg;
        this.surface = surface;
        this.field = field;
        this.primary = primary;
        this.onPrimary = onPrimary;
        this.soft = soft;
        this.text = text;
        this.sub = sub;
        this.divider = divider;
        this.stroke = stroke;
        this.cardRadius = cardRadius;
        this.btnRadius = btnRadius;
        this.fieldRadius = fieldRadius;
        this.pad = pad;
        this.titleSize = titleSize;
        this.fontScale = fontScale;
    }

    public static UiTheme of(Context ctx, String style, int darkMode) {
        final float fs = Prefs.fontScale(ctx);
        boolean dark;
        if (darkMode == Prefs.MODE_LIGHT) {
            dark = false;
        } else if (darkMode == Prefs.MODE_DARK) {
            dark = true;
        } else {
            int m = ctx.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            dark = m == Configuration.UI_MODE_NIGHT_YES;
        }

        if (Prefs.STYLE_MIUI.equals(style)) {
            if (dark) {
                return new UiTheme(style, dark,
                        0xFF101012, 0xFF1C1C1E, 0xFF2C2C2E, 0xFF0A84FF, 0xFFFFFFFF,
                        0xFF163A5C, 0xFFF2F2F7, 0xFF8E8E93, 0xFF2E2E30, 0x00000000,
                        18, 12, 10, 16, 20, fs);
            }
            return new UiTheme(style, dark,
                    0xFFF5F5F7, 0xFFFFFFFF, 0xFFF0F0F3, 0xFF0A7AFF, 0xFFFFFFFF,
                    0xFFE4F0FF, 0xFF1A1A1A, 0xFF8A8A8E, 0xFFEDEDF0, 0x00000000,
                    18, 12, 10, 16, 20, fs);
        }

        // Material You：Android 12+ 读取系统动态配色
        int primary = 0xFF6750A4;
        int soft = 0xFFEADDFF;
        int bg = 0xFFFFFBFE;
        int surface = 0xFFF7F2FA;
        int field = 0xFFEFE9F2;
        int text = 0xFF1D1B20;
        int sub = 0xFF625B71;
        if (Build.VERSION.SDK_INT >= 31) {
            if (dark) {
                primary = sysColor(ctx, "system_accent1_200", 0xFFD0BCFF);
                soft = sysColor(ctx, "system_accent1_700", 0xFF4A4458);
                bg = sysColor(ctx, "system_neutral1_900", 0xFF131318);
                surface = sysColor(ctx, "system_neutral2_800", 0xFF211F26);
                field = sysColor(ctx, "system_neutral2_700", 0xFF2B2933);
                text = sysColor(ctx, "system_neutral1_100", 0xFFE6E0E9);
                sub = sysColor(ctx, "system_neutral2_300", 0xFFCAC4D0);
            } else {
                primary = sysColor(ctx, "system_accent1_600", primary);
                soft = sysColor(ctx, "system_accent1_100", soft);
                bg = sysColor(ctx, "system_neutral1_10", bg);
                surface = sysColor(ctx, "system_neutral2_50", surface);
                field = sysColor(ctx, "system_neutral2_100", field);
                text = sysColor(ctx, "system_neutral1_900", text);
                sub = sysColor(ctx, "system_neutral2_500", sub);
            }
        } else if (dark) {
            primary = 0xFFD0BCFF;
            soft = 0xFF4A4458;
            bg = 0xFF131318;
            surface = 0xFF211F26;
            field = 0xFF2B2933;
            text = 0xFFE6E0E9;
            sub = 0xFFCAC4D0;
        }
        int onPrimary = dark ? 0xFF2B1B4B : 0xFFFFFFFF;
        return new UiTheme(style, dark, bg, surface, field, primary, onPrimary,
                soft, text, sub, dark ? 0xFF3B3841 : 0xFFE7E0EC, 0x00000000,
                24, 999, 16, 20, 22, fs);
    }

    private static int sysColor(Context ctx, String name, int fallback) {
        try {
            int id = ctx.getResources().getIdentifier(name, "color", "android");
            if (id != 0) {
                return ctx.getColor(id);
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    public String label() {
        return miui ? "MIUI" : "Material You";
    }

    /**
     *  设置页左侧选项图标与返回箭头的颜色：MIUI 主题按参考图用黑色，
     * 深色模式退化为文字色以保证可读；Material 主题沿用主色。
     */
    public int settingIcon() {
        return miui ? (dark ? text : 0xFF111111) : primary;
    }

    public int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    /** 自定义壁纸：铺满内容层并叠一层遮罩保证对比度；未设置时使用纯色背景。 */
    public void applyRootBackground(View root, Context c) {
        String path = Prefs.wallpaper(c);
        if (path != null && !path.isEmpty()) {
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, bounds);
                int sw = c.getResources().getDisplayMetrics().widthPixels;
                int sh = c.getResources().getDisplayMetrics().heightPixels;
                int sample = 1;
                while (bounds.outWidth / sample > sw * 2
                        || bounds.outHeight / sample > sh * 2) {
                    sample *= 2;
                }
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = Math.max(1, sample);
                Bitmap bmp = BitmapFactory.decodeFile(path, opts);
                if (bmp != null) {
                    BitmapDrawable pic = new BitmapDrawable(c.getResources(), bmp);
                    pic.setGravity(Gravity.FILL);
                    Drawable veil = new ColorDrawable(dark ? 0x77000000 : 0x4DFFFFFF);
                    root.setBackground(new LayerDrawable(new Drawable[]{pic, veil}));
                    return;
                }
            } catch (Throwable ignored) { }
        }
        root.setBackgroundColor(bg);
    }

    public GradientDrawable card(Context c) {
        GradientDrawable d = shape(surface, dp(c, cardRadius));
        if (miui && !dark) {
            d.setStroke(Math.max(1, dp(c, 1)), 0xFFF0F0F2);
        }
        return d;
    }

    /** 悬浮底栏的玻璃底板：半透明白 + 高光描边。 */
    public GradientDrawable glassBar(Context c) {
        int base = dark ? 0x33FFFFFF : 0xCCFFFFFF;
        GradientDrawable d = shape(base, dp(c, 30));
        d.setStroke(Math.max(1, dp(c, 1)), dark ? 0x55FFFFFF : 0xFFFFFFFF);
        return d;
    }

    /** 液态玻璃折射光晕：彩色渐变 + API31 真实模糊，用作底栏滑动高光块的拖尾。 */
    public View glowLayer(Context c) {
        View v = new View(c);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x66A0C8FF, 0x55C8A6FF, 0x44FFD9E8, 0x00000000});
        g.setCornerRadius(dp(c, 120));
        v.setBackground(g);
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                v.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                        24f, 24f, Shader.TileMode.CLAMP));
            } catch (Throwable ignored) {
            }
        }
        return v;
    }

    public GradientDrawable fieldBg(Context c) {
        GradientDrawable d = shape(field, dp(c, fieldRadius));
        if (miui) {
            d.setStroke(Math.max(1, dp(c, 1)), 0xFFE3E4E7);
        }
        return d;
    }

    public void stylePrimary(Button b, Context c) {
        b.setBackground(shape(primary, dp(c, btnRadius)));
        b.setTextColor(onPrimary);
        b.setTextSize(15 * fontScale);
        b.setAllCaps(false);
        b.setTypeface(miui ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        b.setPadding(dp(c, 8), dp(c, 13), dp(c, 8), dp(c, 13));
        b.setStateListAnimator(null);
    }

    public void styleSecondary(Button b, Context c) {
        b.setBackground(shape(soft, dp(c, btnRadius)));
        b.setTextColor(primary);
        b.setTextSize(15 * fontScale);
        b.setAllCaps(false);
        b.setPadding(dp(c, 8), dp(c, 13), dp(c, 8), dp(c, 13));
        b.setStateListAnimator(null);
    }

    public TextView title(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(titleSize * fontScale);
        t.setTextColor(text);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public TextView section(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(13 * fontScale);
        t.setTextColor(miui ? sub : primary);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(c, 4), 0, 0, 0);
        return t;
    }

    public TextView body(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(15 * fontScale);
        t.setTextColor(text);
        return t;
    }

    public TextView caption(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(12 * fontScale);
        t.setTextColor(sub);
        return t;
    }

    public EditText input(Context c) {
        EditText e = new EditText(c);
        e.setTextSize(14 * fontScale);
        e.setTextColor(text);
        e.setHintTextColor(sub);
        e.setBackground(fieldBg(c));
        e.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        return e;
    }

    /** 系统设置式列表行：左侧线性矢量图标 + 主标题 + 副标题 + 右侧控件。 */
    public LinearLayout settingRow(Context c, int iconId, String titleText,
                                   String subText, View right) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(c, 10), 0, dp(c, 10));
        row.setMinimumHeight(dp(c, 58));

        //  MIUI 主题下选项图标用黑色（深色模式退化为文字色），Material 沿用主色
        View icon = Icons.of(c, iconId, settingIcon(), 22);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(c, 22), dp(c, 22));
        row.addView(icon, ilp);

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(c, 14);
        tlp.rightMargin = dp(c, 8);
        TextView t = body(c, titleText);
        t.setTextSize(16 * fontScale);
        texts.addView(t);
        if (subText != null && !subText.isEmpty()) {
            TextView s = caption(c, subText);
            s.setPadding(0, dp(c, 3), 0, 0);
            texts.addView(s);
        }
        row.addView(texts, tlp);
        if (right != null) {
            row.addView(right);
        }
        return row;
    }

    /** 右侧进入箭头（线性矢量，替代文字字符）。 */
    public View arrow(Context c) {
        return Icons.of(c, Icons.CHEVRON_RIGHT, sub, 20);
    }

    /** 选中态蓝色对勾（线性矢量）。 */
    public View check(Context c, boolean selected) {
        return selected ? Icons.of(c, Icons.CHECK, primary, 22) : new View(c);
    }

    /** 自绘 MD3 / MIUI 开关。 */
    public AppSwitch styledSwitch(Context c) {
        return new AppSwitch(c, this);
    }

    public void styleProgress(ProgressBar p) {
        p.setProgressTintList(android.content.res.ColorStateList.valueOf(primary));
        p.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(soft));
    }

    public View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(divider);
        return v;
    }

    public LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    public LinearLayout cardContainer(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(card(c));
        int p = dp(c, miui ? 14 : 18);
        l.setPadding(p, dp(c, 6), p, dp(c, 6));
        return l;
    }

    public View space(Context c, int dpValue) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, dpValue)));
        return v;
    }

    public static int disabled(int color) {
        int a = Color.alpha(color);
        return Color.argb((int) (a * 0.45f), Color.red(color), Color.green(color),
                Color.blue(color));
    }
}
