package com.vloader.app;

import android.app.Activity;
import android.os.Bundle;

/**
 * 统一处理：主题即时刷新与返回键语义。
 *
 * 返回键：底栏是三个平级页面，按系统返回键应「直接退出应用」，而不是退回主页。
 * 因此这里用 finishAffinity() 结束整个任务栈。子页面（预览 / 文件管理）覆盖 exitOnBack()。
 * 设置子页通过 onHandleBack() 拦截返回，先回到设置主列表。
 *
 * 清单中通过 android:enableOnBackInvokedCallback="false" 保持统一的返回键语义。
 */
public abstract class BaseActivity extends Activity {

    protected UiTheme th;
    private String themeKey;

    protected UiTheme currentTheme() {
        return UiTheme.of(this, Prefs.style(this), Prefs.darkMode(this));
    }

    private String key() {
        return Prefs.style(this) + "/" + Prefs.darkMode(this)
                + "/" + Prefs.floatingNav(this)
                + "/" + Prefs.liquidNav(this)
                + "/" + Prefs.fontLevel(this) + "/" + Prefs.wallpaper(this);
    }

    /** 该页面按返回键是否直接退出整个应用；预览/文件管理等子页返回 false。 */
    protected boolean exitOnBack() {
        return true;
    }

    /** 子类拦截返回：返回 true 表示已消费（例如设置子页回到设置主列表）。 */
    protected boolean onHandleBack() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        th = currentTheme();
        themeKey = key();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!key().equals(themeKey)) {
            recreate();
            return;
        }
    }

    /** 真正执行返回动作。 */
    private void handleBack() {
        if (onHandleBack()) {
            return;
        }
        if (exitOnBack()) {
            finishAffinity();   // 直接退出应用，不退回主页
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }
}
