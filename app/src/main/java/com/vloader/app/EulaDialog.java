package com.vloader.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 用户协议弹窗：首次启动强制阅读并勾选后才能进入软件；「关于」页可再次查看全文。
 *
 * 用系统 AlertDialog + ScrollView 承载长文本，保证协议全文一定能上下滑动看完整。
 * 独立成类，主页 / 设置页只负责调用，不重复实现。
 */
public final class EulaDialog {

    /**
     * @param firstRun true=首次启动（不可取消、需勾选、不同意即退出）；false=关于页查看（可关闭）
     * @param onAgree  首次启动时勾选并同意后的回调（写 Prefs）
     */
    public static void show(final Activity a, final boolean firstRun,
                           final Runnable onAgree) {
        int pad = dp(a, 20);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, dp(a, 12), pad, 0);

        if (firstRun) {
            TextView hint = new TextView(a);
            hint.setText(a.getString(R.string.eula_hint));
            hint.setTextSize(13);
            hint.setTextColor(0xFFE24B4B);
            hint.setPadding(0, 0, 0, dp(a, 10));
            box.addView(hint);
        }

        ScrollView sv = new ScrollView(a);
        TextView body = new TextView(a);
        body.setText(Eula.TEXT);
        body.setTextSize(13);
        body.setTextColor(0xFF333333);
        body.setLineSpacing(dp(a, 3), 1f);
        sv.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        // 限定高度，长文本在弹窗内滚动而不是把按钮挤出屏幕
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        box.addView(sv, slp);

        final CheckBox cb = new CheckBox(a);
        if (firstRun) {
            cb.setText(a.getString(R.string.eula_checkbox));
            cb.setTextSize(14);
            cb.setChecked(false);   // 默认未勾选：未完成勾选不得进入软件
            cb.setPadding(0, dp(a, 10), 0, dp(a, 6));
            box.addView(cb);
        }

        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(a.getString(R.string.eula_title))
                .setView(box)
                .setCancelable(!firstRun)
                .setPositiveButton(a.getString(firstRun
                        ? R.string.eula_agree : R.string.eula_close), null);
        if (firstRun) {
            b.setNegativeButton(a.getString(R.string.eula_decline),
                    new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            a.finishAffinity();   // 不同意：退出软件
                        }
                    });
        }
        final AlertDialog dialog = b.create();
        if (firstRun) {
            // 覆盖系统按钮的默认「点击即关闭」，未勾选时不让进入
            dialog.setOnShowListener(new DialogInterface.OnShowListener() {
                @Override public void onShow(DialogInterface d) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                            .setOnClickListener(new android.view.View.OnClickListener() {
                                @Override public void onClick(android.view.View v) {
                                    if (!cb.isChecked()) {
                                        Toast.makeText(a,
                                                a.getString(R.string.eula_need_check),
                                                Toast.LENGTH_SHORT).show();
                                        return;
                                    }
                                    if (onAgree != null) { onAgree.run(); }
                                    dialog.dismiss();
                                }
                            });
                }
            });
        }
        dialog.show();
        if (dialog.getWindow() != null) {
            int h = a.getResources().getDisplayMetrics().heightPixels * 3 / 4;
            dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT, h);
        }
    }

    private static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }

    private EulaDialog() { }
}
