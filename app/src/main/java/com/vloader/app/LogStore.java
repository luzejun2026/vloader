package com.vloader.app;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** 运行日志：写入 App 私有文件，支持查看与导出到公共下载目录（无需权限）。 */
public final class LogStore {

    private static final int MAX_LINES = 400;

    private static File file(Context c) {
        File dir = new File(c.getFilesDir(), "logs");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, "m3u8dl.log");
    }

    public static synchronized void log(Context c, String tag, String msg) {
        if (c == null) {
            return;
        }
        try {
            File f = file(c);
            if (f.length() > 512 * 1024) {
                trim(f);
            }
            String ts = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                    .format(new Date());
            FileOutputStream out = new FileOutputStream(f, true);
            out.write((ts + "  [" + tag + "]  " + (msg == null ? "" : msg) + "\n")
                    .getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {
        }
    }

    private static void trim(File f) throws IOException {
        ArrayDeque<String> q = new ArrayDeque<>();
        BufferedReader br = new BufferedReader(new FileReader(f));
        String line;
        while ((line = br.readLine()) != null) {
            q.addLast(line);
            if (q.size() > MAX_LINES) {
                q.removeFirst();
            }
        }
        br.close();
        StringBuilder sb = new StringBuilder();
        for (String s : q) {
            sb.append(s).append('\n');
        }
        FileOutputStream out = new FileOutputStream(f, false);
        out.write(sb.toString().getBytes("UTF-8"));
        out.close();
    }

    public static synchronized String read(Context c) {
        File f = file(c);
        if (!f.exists()) {
            return "";
        }
        ArrayDeque<String> q = new ArrayDeque<>();
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                q.addLast(line);
                if (q.size() > MAX_LINES) {
                    q.removeFirst();
                }
            }
            br.close();
        } catch (Exception ignored) {
        }
        StringBuilder sb = new StringBuilder();
        for (String s : q) {
            sb.append(s).append('\n');
        }
        return sb.toString();
    }

    public static synchronized void clear(Context c) {
        File f = file(c);
        if (f.exists()) {
            f.delete();
        }
    }

    /** 导出到公共下载目录，返回展示用的文件名；失败抛异常。 */
    public static synchronized String export(Context c) throws IOException {
        File f = file(c);
        String name = "m3u8dl_log_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                + ".txt";
        Uri uri = null;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/m3u8downloader");
            uri = c.getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        }
        OutputStream out = null;
        InputStream in = null;
        try {
            if (uri != null) {
                out = c.getContentResolver().openOutputStream(uri);
            } else {
                File dl = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                File dest = new File(dl, name);
                out = new FileOutputStream(dest);
            }
            if (out == null) {
                throw new IOException("无法创建导出文件");
            }
            if (f.exists()) {
                in = new FileInputStream(f);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            } else {
                out.write("（暂无日志）\n".getBytes("UTF-8"));
            }
            out.flush();
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
            if (out != null) {
                try { out.close(); } catch (Exception ignored) {}
            }
        }
        return name;
    }

    private LogStore() {
    }
}
