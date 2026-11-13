package com.vloader.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 内置文件管理器：纯 java.io.File 遍历目录、选择下载位置，不调用系统 SAF / 文件管理 App。
 * 起始目录为共享存储根 /storage/emulated/0，可进入子目录、返回上级、新建文件夹，
 * 点「选择此目录」把绝对路径回传给设置页。
 *
 * 系统返回键逐级返回上一级目录，到根目录后再交给 BaseActivity 处理；
 * 到了共享存储根目录再按返回才退出。左上角另有一个返回键，点击直接退出。
 */
public final class FilePickerActivity extends BaseActivity {

    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_START = "start";
    /** true 时列出图片文件，点图片直接把文件绝对路径回传（用于自定义壁纸）。 */
    public static final String EXTRA_IMAGE_ONLY = "image_only";

    private File current;
    private String rootPath;
    private LinearLayout list;
    private TextView pathText;
    private TextView permissionHint;
    private boolean imageOnly;
    /** 选文件模式（图片）：点文件直接回传，不显示「选择此目录」。 */
    private boolean fileMode;

    /** 文件管理器是子页，返回应回到设置页而非直接退出应用。 */
    @Override
    protected boolean exitOnBack() {
        return false;
    }

    /**
     * 系统返回键：逐级返回上一级目录，到根目录后再交给 BaseActivity 退出。
     * 左上角返回键不受此约束，点击直接 finish()。
     */
    @Override
    protected boolean onHandleBack() {
        return goUp();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        th = currentTheme();
        imageOnly = getIntent().getBooleanExtra(EXTRA_IMAGE_ONLY, false);
        fileMode = imageOnly;
        String start = getIntent().getStringExtra(EXTRA_START);
        File root = Environment.getExternalStorageDirectory();
        rootPath = root.getAbsolutePath();
        current = new File(start == null || start.isEmpty()
                ? rootPath : start);
        if (!current.exists() || !current.isDirectory()) {
            current = root;
        }
        buildUi();
    }

    private void buildUi() {
        FrameLayout rootView = new FrameLayout(this);
        th.applyRootBackground(rootView, this);
        setContentView(rootView);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(th.dp(this, 16), th.dp(this, 8),
                th.dp(this, 16), th.dp(this, 16));
        EdgeInset.applyTop(this, page, 8, 10);

        // 标题行：左上角返回键直接退出文件管理器，标题居中
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(th.dp(this, 44));
        //  返回键改为黑色（跟随文字色：浅色=黑、深色=白），不再用主题蓝
        View exitBtn = Icons.of(this, Icons.ARROW_LEFT, th.text, 24);
        exitBtn.setPadding(th.dp(this, 4), th.dp(this, 8),
                th.dp(this, 12), th.dp(this, 8));
        exitBtn.setContentDescription(getString(R.string.picker_exit));
        exitBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        header.addView(exitBtn);
        TextView title = new TextView(this);
        title.setText(imageOnly ? R.string.picker_file_title : R.string.picker_title);
        title.setTextSize(20);
        title.setTextColor(th.text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        View spacer = new View(this);
        header.addView(spacer, new LinearLayout.LayoutParams(th.dp(this, 40), 1));
        page.addView(header, th.matchWidth());
        page.addView(th.space(this, 4));
        TextView sub = th.caption(this, getString(imageOnly
                ? R.string.picker_image_only : R.string.picker_hint));
        page.addView(sub, th.matchWidth());
        page.addView(th.space(this, 12));

        // 当前路径卡片
        LinearLayout pathCard = th.cardContainer(this);
        pathText = th.body(this, current.getAbsolutePath());
        pathText.setTextSize(13);
        pathText.setTextColor(th.primary);
        pathCard.addView(pathText, th.matchWidth());
        page.addView(pathCard, th.matchWidth());
        page.addView(th.space(this, 8));

        permissionHint = th.caption(this, "");
        page.addView(permissionHint, th.matchWidth());
        page.addView(th.space(this, 8));

        // 目录列表
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(list, th.matchWidth());
        page.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        page.addView(th.space(this, 10));

        // 底部操作
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.Button up = actionButton(R.string.picker_up, false);
        up.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { goUp(); }
        });
        actions.addView(up, weighted(1));
        android.widget.Button newDir = actionButton(R.string.picker_new_folder, false);
        newDir.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptNewFolder();
            }
        });
        if (!fileMode) {
            actions.addView(newDir, weighted(1));
        }
        android.widget.Button ok = actionButton(R.string.picker_select, true);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent data = new Intent();
                data.putExtra(EXTRA_PATH, current.getAbsolutePath());
                setResult(Activity.RESULT_OK, data);
                finish();
            }
        });
        if (!fileMode) {
            actions.addView(ok, weighted(1.4f));
        }
        page.addView(actions, th.matchWidth());

        rootView.addView(page, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        refresh();
    }

    /** 是否已在最上层根目录：此时系统返回键才允许退出文件管理器。 */
    private boolean atRoot() {
        if (current == null || rootPath == null) { return true; }
        String path = current.getAbsolutePath();
        if (path.equals(rootPath)) { return true; }
        File parent = current.getParentFile();
        return parent == null || path.equals(parent.getAbsolutePath());
    }

    /** 返回上一级目录；已在根目录时不再越界往上翻。 */
    private boolean goUp() {
        if (atRoot()) { return false; }
        File parent = current.getParentFile();
        if (parent == null || !parent.exists() || !parent.isDirectory()) { return false; }
        current = parent;
        refresh();
        return true;
    }

    private android.widget.Button actionButton(int res, boolean primary) {
        android.widget.Button b = new android.widget.Button(this);
        b.setText(res);
        b.setAllCaps(false);
        b.setTextSize(13);
        if (primary) {
            th.stylePrimary(b, this);
        } else {
            th.styleSecondary(b, this);
        }
        return b;
    }

    private LinearLayout.LayoutParams weighted(double w) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, (float) w);
        lp.rightMargin = th.dp(this, 6);
        return lp;
    }

    private static boolean isImage(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) { return false; }
        String e = name.substring(dot + 1).toLowerCase(java.util.Locale.US);
        return e.equals("jpg") || e.equals("jpeg") || e.equals("png")
                || e.equals("webp") || e.equals("bmp");
    }

    /** 选文件（图片）模式下，该文件是否为目标类型。 */
    private boolean isTargetFile(String name) {
        return isImage(name);
    }

    private void refresh() {
        pathText.setText(current.getAbsolutePath());
        boolean granted = StorageUtil.hasAllFilesAccess(this);
        permissionHint.setText(getString(granted
                ? R.string.picker_granted : R.string.picker_need_permission));
        list.removeAllViews();

        File[] children = current.listFiles();
        if (children == null) {
            list.addView(th.caption(this, getString(R.string.picker_no_access)),
                    th.matchWidth());
            return;
        }
        List<File> dirs = new ArrayList<>();
        for (File f : children) {
            if (f.isDirectory() && !f.isHidden()) {
                dirs.add(f);
            }
        }
        Collections.sort(dirs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        final List<File> files = new ArrayList<>();
        if (fileMode) {
            for (File f : children) {
                if (f.isFile() && !f.isHidden() && isTargetFile(f.getName())) {
                    files.add(f);
                }
            }
            Collections.sort(files, new Comparator<File>() {
                @Override public int compare(File a, File b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
        }
        if (dirs.isEmpty() && files.isEmpty()) {
            list.addView(th.caption(this, getString(R.string.picker_empty)),
                    th.matchWidth());
            return;
        }
        // 选文件模式：点目标文件直接回传绝对路径（图片作壁纸 / M3U8 交内置播放器）
        for (final File img : files) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(th.dp(this, 6), th.dp(this, 12), th.dp(this, 6), th.dp(this, 12));
            row.addView(Icons.of(this, Icons.FILE, th.primary, 24));
            TextView nm = th.body(this, img.getName());
            nm.setTextSize(15);
            LinearLayout.LayoutParams nlp2 = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            nlp2.leftMargin = th.dp(this, 12);
            nm.setLayoutParams(nlp2);
            row.addView(nm);
            row.setBackground(th.shape(ColorTransparent(), th.dp(this, 12)));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Intent data = new Intent();
                    data.putExtra(EXTRA_PATH, img.getAbsolutePath());
                    setResult(Activity.RESULT_OK, data);
                    finish();
                }
            });
            list.addView(row, th.matchWidth());
            list.addView(th.divider(this), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, th.dp(this, 1))));
        }
        for (final File d : dirs) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(th.dp(this, 6), th.dp(this, 12), th.dp(this, 6), th.dp(this, 12));
            row.addView(Icons.of(this, Icons.FOLDER, th.primary, 24));
            TextView name = th.body(this, d.getName());
            name.setTextSize(15);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            nlp.leftMargin = th.dp(this, 12);
            name.setLayoutParams(nlp);
            row.addView(name);
            row.addView(Icons.of(this, Icons.CHEVRON_RIGHT, th.sub, 18));
            row.setBackground(th.shape(ColorTransparent(), th.dp(this, 12)));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    current = d;
                    refresh();
                }
            });
            list.addView(row, th.matchWidth());
            list.addView(th.divider(this), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, th.dp(this, 1))));
        }
    }

    private static int ColorTransparent() {
        return 0x00000000;
    }

    private void promptNewFolder() {
        final EditText input = th.input(this);
        input.setHint(R.string.picker_folder_name);
        input.setSingleLine(true);
        AppDialog.with(this, th)
                .title(getString(R.string.picker_new_folder))
                .view(input)
                .negative(getString(R.string.btn_cancel), null)
                .positive(getString(R.string.btn_ok), new AppDialog.OnButtonListener() {
                    @Override public void onClick(android.app.Dialog dialog) {
                        String name = FileNameUtil.sanitize(input.getText().toString());
                        if (name.isEmpty()) {
                            toast(getString(R.string.picker_name_empty));
                            return;
                        }
                        File nd = new File(current, name);
                        if (nd.exists()) {
                            toast(getString(R.string.picker_exists));
                            return;
                        }
                        if (nd.mkdirs()) {
                            current = nd;
                            refresh();
                        } else {
                            toast(getString(R.string.picker_create_failed));
                        }
                    }
                })
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
