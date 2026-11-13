package com.vloader.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

/**
 * 全自绘对话框：标题 / 正文 / 单选列表（蓝色对勾）/ 普通列表 / 自定义输入 / 文字按钮。
 * 不使用系统 AlertDialog.Builder —— 它会沿用 Activity 的 Holo/MD2 主题，出现青绿文字按钮、
 * 空心单选圈、系统强调色，与自绘的 Material You / MIUI 界面不一致。风格完全由 UiTheme 决定。
 *
 * 「默认打开方式」列表底层采用系统 ListView，滚动与裁剪由容器统一处理：
 * （作为普通 View 放进自绘卡片里，不用系统 AlertDialog）。之前两版用自绘滚动容器 +
 * 高度测算，在部分 ROM 上仍表现为「十项只显示五项、无法上滑」。
 * ListView 自带测量、滚动与 fling，只要有确定的高度就一定能上下滑动查看全部条目。
 * 外观仍然跟随当前主题（圆角卡片 + 深/浅色），应用自身提供的 label 文本原样显示。
 */
public final class AppDialog {

    public interface OnItemListener {
        void onItem(int which);
    }

    public interface OnButtonListener {
        void onClick(Dialog dialog);
    }

    private final Activity a;
    private final UiTheme th;
    private String title;
    private String message;
    private View customView;
    private String[] items;
    private int checked = -1;
    private OnItemListener itemListener;
    private String posLabel, negLabel, neuLabel;
    private OnButtonListener posCb, negCb, neuCb;

    private AppDialog(Activity a, UiTheme th) {
        this.a = a;
        this.th = th;
    }

    public static AppDialog with(Activity a, UiTheme th) {
        return new AppDialog(a, th);
    }

    public AppDialog title(String s) {
        title = s;
        return this;
    }

    public AppDialog message(String s) {
        message = s;
        return this;
    }

    public AppDialog view(View v) {
        customView = v;
        return this;
    }

    /** 单选列表：选中项为蓝色文字 + 右侧蓝色对勾。 */
    public AppDialog singleChoice(String[] labels, int checkedIndex, OnItemListener l) {
        items = labels;
        checked = checkedIndex;
        itemListener = l;
        return this;
    }

    /** 普通操作列表（无对勾）。 */
    public AppDialog items(String[] labels, OnItemListener l) {
        items = labels;
        checked = -1;
        itemListener = l;
        return this;
    }

    public AppDialog positive(String label, OnButtonListener cb) {
        posLabel = label;
        posCb = cb;
        return this;
    }

    public AppDialog negative(String label, OnButtonListener cb) {
        negLabel = label;
        negCb = cb;
        return this;
    }

    public AppDialog neutral(String label, OnButtonListener cb) {
        neuLabel = label;
        neuCb = cb;
        return this;
    }

    public Dialog show() {
        final Dialog dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            win.setDimAmount(0.42f);
        }

        int side = th.dp(a, 24);
        LinearLayout outer = new LinearLayout(a);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(side, 0, side, 0);
        outer.setGravity(Gravity.CENTER);

        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = th.shape(th.dark ? th.surface : 0xFFFFFFFF, th.dp(a, 26));
        card.setBackground(bg);
        card.setElevation(th.dp(a, 12));
        int pad = th.dp(a, 22);
        card.setPadding(pad, th.dp(a, 22), pad, th.dp(a, 8));

        if (title != null) {
            TextView t = new TextView(a);
            t.setText(title);
            t.setTextSize(19);
            t.setTextColor(th.text);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            card.addView(t, th.matchWidth());
            card.addView(th.space(a, 8));
        }
        if (message != null && !message.isEmpty()) {
            TextView m = th.body(a, message);
            m.setTextSize(14);
            m.setTextColor(th.sub);
            card.addView(m, th.matchWidth());
            card.addView(th.space(a, 12));
        }
        if (customView != null) {
            card.addView(customView, th.matchWidth());
            card.addView(th.space(a, 8));
        }
        if (items != null) {
            // 列表高度按条目数估算、再用屏幕可用高度截断，得到一个确定的 EXACTLY 像素高度。
            // 系统 ListView 在这个高度内自带滚动，条目再多也一定能上下滑动查看全部。
            int screenH = a.getResources().getDisplayMetrics().heightPixels;
            int natural = items.length * th.dp(a, 46) + th.dp(a, 8);
            int maxListH = Math.max(th.dp(a, 160), screenH - th.dp(a, 210));
            final int listH = Math.min(natural, maxListH);

            final String[] labels = items;
            final int checkedIndex = checked;
            ListView lv = new ListView(a);
            lv.setDivider(null);
            lv.setDividerHeight(0);
            lv.setSelector(new ColorDrawable(Color.TRANSPARENT));
            lv.setCacheColorHint(Color.TRANSPARENT);
            lv.setVerticalScrollBarEnabled(true);
            lv.setScrollbarFadingEnabled(false);   // 滚动条常显，提示「可以上滑」
            lv.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
            lv.setAdapter(new BaseAdapter() {
                @Override public int getCount() { return labels.length; }
                @Override public Object getItem(int p) { return labels[p]; }
                @Override public long getItemId(int p) { return p; }

                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    boolean selected = position == checkedIndex;
                    LinearLayout row = new LinearLayout(a);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(th.dp(a, 4), th.dp(a, 12), th.dp(a, 4), th.dp(a, 12));
                    row.setMinimumHeight(th.dp(a, 44));
                    TextView label = new TextView(a);
                    label.setText(labels[position]);
                    label.setTextSize(16);
                    label.setTextColor(selected ? th.primary : th.text);
                    label.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                    row.addView(label, new LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                    if (selected) {
                        row.addView(Icons.of(a, Icons.CHECK, th.primary, 22));
                    }
                    return row;
                }
            });
            lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override public void onItemClick(AdapterView<?> parent, View view,
                                                 int position, long id) {
                    if (itemListener != null) {
                        itemListener.onItem(position);
                    }
                    dialog.dismiss();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, listH);
            card.addView(lv, lp);
        }

        LinearLayout buttons = new LinearLayout(a);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, th.dp(a, 8), 0, th.dp(a, 6));
        if (neuLabel != null) {
            buttons.addView(textButton(neuLabel, neuCb, dialog, false));
        }
        if (negLabel != null) {
            buttons.addView(textButton(negLabel, negCb, dialog, false));
        }
        if (posLabel != null) {
            buttons.addView(textButton(posLabel, posCb, dialog, true));
        }
        card.addView(buttons, th.matchWidth());

        outer.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(outer);
        if (win != null) {
            // 全屏透明窗口：根布局拿到确定高度、卡片居中，列表不再被 WRAP_CONTENT 窗口一起裁掉。
            win.setLayout(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT);
        }
        dialog.show();
        return dialog;
    }

    private TextView textButton(String label, final OnButtonListener cb,
                                final Dialog dialog, boolean strong) {
        TextView b = new TextView(a);
        b.setText(label);
        b.setTextSize(14);
        b.setTextColor(th.primary);
        b.setTypeface(strong ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(th.dp(a, 16), th.dp(a, 10), th.dp(a, 16), th.dp(a, 10));
        b.setBackground(th.shape(Color.TRANSPARENT, th.dp(a, 12)));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (cb != null) {
                    cb.onClick(dialog);
                }
                dialog.dismiss();
            }
        });
        return b;
    }
}
