package com.vloader.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

/**
 * 下拉选项框：列表行右侧显示「当前值 + 上下箭头」，点击弹出白色圆角浮层，
 * 选中项为蓝色文字 + 蓝色对勾，未选中为深色常规文字。完全自绘，不用系统 Spinner/AlertDialog。
 */
public final class Dropdown {

    public interface OnPick {
        void onPick(int which);
    }

    private static final String TAG_VALUE = "dropdown_value";

    /** 右侧「当前值 + 上下箭头」组合控件。 */
    public static View valueBox(Activity a, UiTheme th, String current) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        TextView v = new TextView(a);
        v.setTag(TAG_VALUE);
        v.setText(current);
        v.setTextSize(14);
        v.setTextColor(th.sub);
        v.setMaxWidth(th.dp(a, 150));
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        box.addView(v);
        View arrow = Icons.of(a, Icons.UPDOWN, th.sub, 18);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                th.dp(a, 18), th.dp(a, 18));
        lp.leftMargin = th.dp(a, 6);
        box.addView(arrow, lp);
        return box;
    }

    /** 更新已创建 valueBox 里显示的当前值。 */
    public static void setValue(View valueBox, String current) {
        if (valueBox instanceof LinearLayout) {
            View v = ((LinearLayout) valueBox).findViewWithTag(TAG_VALUE);
            if (v instanceof TextView) {
                ((TextView) v).setText(current);
            }
        }
    }

    /**
     * 在锚点附近弹出下拉浮层。浮层贴近右侧、带阴影、白色圆角，覆盖在列表之上。
     */
    public static void show(Activity a, View anchor, UiTheme th, String[] labels,
                            int checked, final OnPick cb) {
        if (a.isFinishing()) {
            return;
        }
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = th.shape(th.dark ? th.surface : 0xFFFFFFFF, th.dp(a, 22));
        card.setBackground(bg);
        int pad = th.dp(a, 10);
        card.setPadding(pad, pad, pad, pad);

        final PopupWindow pw = new PopupWindow(card,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, true);
        pw.setOutsideTouchable(true);
        pw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        pw.setElevation(th.dp(a, 10));

        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            final boolean selected = i == checked;
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumWidth(th.dp(a, 168));
            row.setPadding(th.dp(a, 16), th.dp(a, 13), th.dp(a, 16), th.dp(a, 13));
            // 选中项不再铺浅蓝色高亮底：保持与浮层一致的白色/表面底色，
            // 仅用蓝色文字 + 蓝色对勾标识（对照图为白底蓝字）。
            TextView t = new TextView(a);
            t.setText(labels[i]);
            t.setTextSize(16);
            t.setTextColor(selected ? th.primary : th.text);
            t.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            row.addView(t, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            if (selected) {
                View check = Icons.of(a, Icons.CHECK, th.primary, 22);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        th.dp(a, 22), th.dp(a, 22));
                clp.leftMargin = th.dp(a, 22);
                row.addView(check, clp);
            }
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pw.dismiss();
                    if (cb != null) {
                        cb.onPick(idx);
                    }
                }
            });
            card.addView(row, th.matchWidth());
        }

        // 贴近锚点右侧弹出，覆盖在列表上
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        pw.showAsDropDown(anchor, 0, -th.dp(a, 8));
    }

    private static int blend(int from, int to, float f) {
        return Color.argb(
                (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f),
                (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * f),
                (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * f),
                (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f));
    }

    private Dropdown() {
    }
}
