package com.vloader.app;

import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** 落盘、重命名、删除、复制/移动到内置文件管理器所选目录；历史记录保存的是返回的 uri。 */
public final class StorageUtil {

    public static final class Saved {
        public String uri;
        public String path;
        public long size;
    }

    public static File workDir(Context ctx, String key) {
        File base = ctx.getExternalCacheDir();
        if (base == null) {
            base = ctx.getCacheDir();
        }
        File dir = new File(new File(base, "m3u8downloader"), key);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /** 下载缓存根目录（用于统计与清理，优化占用空间）。 */
    public static File cacheRoot(Context ctx) {
        File base = ctx.getExternalCacheDir();
        if (base == null) {
            base = ctx.getCacheDir();
        }
        return new File(base, "m3u8downloader");
    }

    public static long cacheSize(Context ctx) {
        return sizeOf(cacheRoot(ctx));
    }

    private static long sizeOf(File f) {
        if (f == null || !f.exists()) {
            return 0;
        }
        if (f.isFile()) {
            return f.length();
        }
        long sum = 0;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                sum += sizeOf(c);
            }
        }
        return sum;
    }

    /** 清空所有下载中间缓存；返回释放的字节数。旧文件不会残留占空间。 */
    public static long clearCache(Context ctx) {
        long size = cacheSize(ctx);
        File root = cacheRoot(ctx);
        clean(root);
        // 同时清理应用内部 cache 下的同名目录
        clean(new File(ctx.getCacheDir(), "m3u8downloader"));
        return size;
    }

    public static Saved save(Context ctx, File src, String displayName, String mime,
                             int mode, String value) throws IOException {
        if (mode == Prefs.LOC_ABSOLUTE) {
            return saveAbsolute(ctx, src, displayName, value);
        }
        String sub = mode == Prefs.LOC_SUBDIR
                ? FileNameUtil.safeSubdir(value) : Prefs.DEFAULT_SUBDIR;
        return saveRelative(ctx, src, displayName, mime, sub);
    }

    private static Saved saveRelative(Context ctx, File src, String displayName,
                                      String mime, String sub) throws IOException {
        String rel = Environment.DIRECTORY_DOWNLOADS + "/" + sub;
        Saved s = new Saved();
        s.size = src.length();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.RELATIVE_PATH, rel);
            v.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = ctx.getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) {
                throw new IOException("无法创建下载文件");
            }
            OutputStream out = ctx.getContentResolver().openOutputStream(uri);
            if (out == null) {
                throw new IOException("无法写入下载文件");
            }
            try (InputStream in = new FileInputStream(src)) {
                copy(in, out);
            } finally {
                out.close();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            ctx.getContentResolver().update(uri, done, null, null);
            s.uri = uri.toString();
            s.path = rel + "/" + displayName;
            return s;
        }
        File dir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                sub);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建下载目录");
        }
        File dest = uniqueFile(dir, displayName);
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dest)) {
            copy(in, out);
        }
        s.uri = Uri.fromFile(dest).toString();
        s.path = dest.getAbsolutePath();
        return s;
    }

    /**
     * 自定义绝对路径落盘：先用 PathUtil 把用户输入规范化到共享存储内，
     * 避免 new File(相对名) 落到根目录 / 导致 mkdirs 失败。
     */
    private static Saved saveAbsolute(Context ctx, File src, String displayName, String value)
            throws IOException {
        String externalRoot = Environment.getExternalStorageDirectory().getAbsolutePath();
        String download = new File(externalRoot, Environment.DIRECTORY_DOWNLOADS)
                .getAbsolutePath();
        String dirPath = PathUtil.normalize(value, externalRoot, download);
        if (!PathUtil.insideSharedStorage(dirPath, externalRoot)
                || !hasAllFilesAccess(ctx)) {
            throw new IOException("未获得「所有文件访问」权限，请在设置中授权或改用公共下载目录");
        }
        File dir = new File(dirPath);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建目录：" + dir.getAbsolutePath()
                    + "（请确认路径位于共享存储内且已授权）");
        }
        if (!dir.isDirectory() || !dir.canWrite()) {
            throw new IOException("目录不可写：" + dir.getAbsolutePath());
        }
        File dest = uniqueFile(dir, displayName);
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dest)) {
            copy(in, out);
        }
        Saved s = new Saved();
        s.uri = Uri.fromFile(dest).toString();
        s.path = dest.getAbsolutePath();
        s.size = dest.length();
        return s;
    }

    /** 设置页展示用的规范化路径（与真正落盘用的是同一套规则）。 */
    public static String normalizeForDisplay(Context ctx, String value) {
        String externalRoot = Environment.getExternalStorageDirectory().getAbsolutePath();
        String download = new File(externalRoot, Environment.DIRECTORY_DOWNLOADS)
                .getAbsolutePath();
        return PathUtil.normalize(value, externalRoot, download);
    }

    public static boolean hasAllFilesAccess(Context ctx) {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        if (Build.VERSION.SDK_INT == 29) {
            return false;
        }
        return ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static InputStream open(Context ctx, Models.Record r) throws IOException {
        if (r == null || r.uri == null || r.uri.isEmpty()) {
            throw new IOException("文件地址为空");
        }
        Uri u = Uri.parse(r.uri);
        InputStream in = "file".equals(u.getScheme())
                ? new FileInputStream(new File(u.getPath()))
                : ctx.getContentResolver().openInputStream(u);
        if (in == null) {
            throw new IOException("无法打开文件");
        }
        return in;
    }

    public static boolean rename(Context ctx, Models.Record r, String newName) {
        if (r == null || r.uri == null || newName == null || newName.trim().isEmpty()) {
            return false;
        }
        Uri u = Uri.parse(r.uri);
        try {
            if ("content".equals(u.getScheme())) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, newName);
                return ctx.getContentResolver().update(u, v, null, null) > 0;
            }
            File f = new File(u.getPath());
            File dest = new File(f.getParentFile(), newName);
            if (dest.exists()) {
                return false;
            }
            if (!f.renameTo(dest)) {
                return false;
            }
            r.uri = Uri.fromFile(dest).toString();
            r.path = dest.getAbsolutePath();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean deleteFile(Context ctx, Models.Record r) {
        if (r == null || r.uri == null || r.uri.isEmpty()) {
            return true;
        }
        Uri u = Uri.parse(r.uri);
        try {
            if ("content".equals(u.getScheme())) {
                return ctx.getContentResolver().delete(u, null, null) > 0;
            }
            File f = new File(u.getPath());
            return !f.exists() || f.delete();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 复制/移动历史文件到内置文件管理器所选的绝对目录（纯 java.io.File，不调用系统 SAF）。
     * 目录经 PathUtil 规范化，绝不写到根目录或系统分区；无「所有文件访问」权限时给出明确提示。
     */
    public static String copyToDir(Context ctx, Models.Record r, String dirValue,
                                    boolean deleteSource) throws IOException {
        if (!hasAllFilesAccess(ctx)) {
            throw new IOException("需要「所有文件访问」权限，请到「设置 → 保存位置」授权");
        }
        String externalRoot = Environment.getExternalStorageDirectory().getAbsolutePath();
        String download = new File(externalRoot, Environment.DIRECTORY_DOWNLOADS)
                .getAbsolutePath();
        String dirPath = PathUtil.normalize(dirValue, externalRoot, download);
        if (!PathUtil.insideSharedStorage(dirPath, externalRoot)) {
            throw new IOException("目标目录不在共享存储内：" + dirPath);
        }
        File dir = new File(dirPath);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建目录：" + dir.getAbsolutePath());
        }
        if (!dir.isDirectory() || !dir.canWrite()) {
            throw new IOException("目录不可写：" + dir.getAbsolutePath());
        }
        File dest = uniqueFile(dir, r.name);
        try (InputStream in = open(ctx, r);
             OutputStream out = new FileOutputStream(dest)) {
            copy(in, out);
        }
        if (deleteSource) {
            deleteFile(ctx, r);
        }
        return Uri.fromFile(dest).toString();
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) {
            return f;
        }
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            File c = new File(dir, stem + "_" + i + ext);
            if (!c.exists()) {
                return c;
            }
        }
        return f;
    }

    public static void clean(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    clean(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 20];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        out.flush();
    }

    private StorageUtil() {
    }
}
