package com.vloader.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * 轻量文件共享 Provider（纯 framework，零第三方依赖）。
 *
 * targetSdk 36 下把 file:// URI 放进 Intent 交给外部播放器会抛
 * FileUriExposedException（「exposed beyond app through Intent.getData()」）。
 * 因此把历史里的 file:// 地址转换成 content:// 再共享，并由调用方授予读权限。
 */
public final class AppFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.vloader.app.fileprovider";

    /**
     * 解析历史记录对应的真实本地文件绝对路径。
     * - file:// 直接取 path；
     * - r.path 以 / 开头即绝对路径；
     * - r.path 为相对路径（如 "Download/m3u8downloader/x.mp4"，MediaStore 落盘时保存的）→
     *   拼到外部存储根，得到 /storage/emulated/0/Download/... 的真实路径；
     * - content://media/... 这种媒体库地址本身无法被本应用授权给第三方，故绝不返回它。
     */
    public static String localPath(Models.Record r) {
        if (r == null) {
            return null;
        }
        if (r.uri != null && r.uri.startsWith("file://")) {
            String p = Uri.parse(r.uri).getPath();
            if (p != null && !p.isEmpty()) {
                return p;
            }
        }
        if (r.path != null && r.path.startsWith("/")) {
            return r.path;
        }
        if (r.path != null && !r.path.isEmpty()) {
            String root = Environment.getExternalStorageDirectory().getAbsolutePath();
            return new File(root, r.path).getAbsolutePath();
        }
        return null;
    }

    /**
     *  把记录转成【本应用自己】的 content:// URI。
     * 关键：早期版本保存的是 MediaStore 的 content://media/...，本应用不是该 URI 的
     * owner，无法把读权限 grant 给外部播放器，点「打开」会报
     * "UID xxx does not have permission to content://media/..."。
     * 现在一律改用本应用 AppFileProvider 的 URI（由真实文件路径构造），
     * 本应用即 owner，grantUriPermission / FLAG_GRANT_READ_URI_PERMISSION 才真正生效。
     */
    public static Uri uriFor(Models.Record r) {
        String path = localPath(r);
        if (path == null || path.isEmpty()) {
            return null;
        }
        // 逐段 appendPath，避免整串绝对路径里的 '/' 被编码成 %2F，
        // 保证 openFile/query/getType 里 getPath()、getLastPathSegment() 都还原准确。
        Uri.Builder b = new Uri.Builder().scheme("content").authority(AUTHORITY);
        for (String seg : path.split("/")) {
            if (!seg.isEmpty()) { b.appendPath(seg); }
        }
        return b.build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    /** 只允许共享存储与 App 私有目录，阻断系统分区与 .. 穿越。 */
    private File fileFor(Uri uri) throws FileNotFoundException {
        String p = uri.getPath();
        if (p == null || p.isEmpty()) {
            throw new FileNotFoundException("路径为空");
        }
        String abs = p.startsWith("/") ? p : "/" + p;
        File f;
        try {
            f = new File(abs).getCanonicalFile();
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
        String s = f.getAbsolutePath();
        File ext = getContext().getExternalFilesDir(null);
        boolean allowed = s.startsWith("/storage/") || s.startsWith("/sdcard/")
                || s.startsWith(getContext().getFilesDir().getAbsolutePath())
                || (ext != null && s.startsWith(ext.getAbsolutePath()));
        if (!allowed || s.contains("/../") || s.startsWith("/system")
                || s.startsWith("/data/") || s.startsWith("/proc/")) {
            throw new FileNotFoundException("不允许访问：" + s);
        }
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileFor(uri);
        if (!f.exists() || !f.isFile()) {
            throw new FileNotFoundException("文件不存在：" + f.getAbsolutePath());
        }
        int m = ParcelFileDescriptor.MODE_READ_ONLY;
        if (mode != null && mode.contains("w")) {
            m = ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE;
        }
        return ParcelFileDescriptor.open(f, m);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = fileFor(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;
        MatrixCursor c = new MatrixCursor(cols, 1);
        MatrixCursor.RowBuilder row = c.newRow();
        for (String col : cols) {
            if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                row.add(f.getName());
            } else if (OpenableColumns.SIZE.equals(col)) {
                row.add(f.length());
            } else {
                row.add(null);
            }
        }
        return c;
    }

    @Override
    public String getType(Uri uri) {
        String last = uri.getLastPathSegment();
        String n = last == null ? "" : last.toLowerCase();
        if (n.endsWith(".ts")) {
            return "video/mp2t";
        }
        if (n.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (n.endsWith(".png")) {
            return "image/png";
        }
        if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".webp")) {
            return "image/jpeg";
        }
        if (n.endsWith(".txt") || n.endsWith(".log")) {
            return "text/plain";
        }
        return "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0;
    }
}
