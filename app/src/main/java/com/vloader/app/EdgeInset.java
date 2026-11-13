package com.vloader.app;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

/**
 * 顶部安全区内边距：targetSdk 36 在 Android 15/16 强制 edge-to-edge，内容会延伸到状态栏下，
 * 灵动岛 / 挖孔 / 刘海会遮住标题。这里按系统栏 + 挖孔的 inset 动态给内容页补顶部 padding。
 */
public final class EdgeInset {

    /** 在原有 padding 基础上叠加状态栏/挖孔高度，并额外留出呼吸空间。 */
    public static void applyTop(final Activity a, final View content, final int baseDp,
                                final int extraDp) {
        final UiTheme th = UiTheme.of(a, Prefs.style(a), Prefs.darkMode(a));
        final int base = th.dp(a, baseDp);
        final int extra = th.dp(a, extraDp);
        final int left = content.getPaddingLeft();
        final int right = content.getPaddingRight();
        final int bottom = content.getPaddingBottom();
        content.setPadding(left, base + extra, right, bottom);
        content.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top;
                if (Build.VERSION.SDK_INT >= 30) {
                    top = insets.getInsets(
                            WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout()).top;
                } else {
                    top = insets.getSystemWindowInsetTop();
                }
                v.setPadding(left, base + extra + top, right, bottom);
                return insets;
            }
        });
        content.requestApplyInsets();
    }

    private EdgeInset() {
    }
}
