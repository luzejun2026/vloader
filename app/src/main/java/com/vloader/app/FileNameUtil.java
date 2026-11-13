package com.vloader.app;

import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 单次文件名工具：文件名属于「每一次下载」，不是全局设置。
 * 批量模式下链接一行一条、文件名一行一个，按行号一一对应。
 */
public final class FileNameUtil {

    /** 默认文件名：video_时间戳；批量时自动加序号。 */
    public static String defaultName(int index, int total, long timeMillis) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date(timeMillis));
        String s = "video_" + stamp;
        if (total > 1) {
            s = s + "_" + index;
        }
        return s;
    }

    /** 把用户在主页输入的本次文件名规范化，追加真实扩展名。 */
    public static String build(String userInput, String url, int index, int total,
                               String ext, long timeMillis) {
        String s = userInput == null ? "" : userInput.trim();
        if (s.isEmpty()) {
            s = defaultName(index, total, timeMillis);
        } else {
            // 支持 {time}/{date}/{index}/{total}/{host} 占位符，方便批量命名
            Date d = new Date(timeMillis);
            s = s.replace("{time}", new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(d))
                    .replace("{date}", new SimpleDateFormat("yyyyMMdd", Locale.US).format(d))
                    .replace("{index}", String.valueOf(index))
                    .replace("{total}", String.valueOf(total))
                    .replace("{host}", hostOf(url));
            // 批量且没写序号时自动补，避免互相覆盖
            if (total > 1 && !s.contains("{index}") && !userInput.trim().contains("{index}")) {
                s = s + "_" + index;
            }
        }
        s = stripExtension(s);
        s = sanitize(s);
        if (s.isEmpty()) {
            s = defaultName(index, total, timeMillis);
        }
        if (s.length() > 110) {
            s = s.substring(0, 110);
        }
        return s + ext;
    }

    /**
     * 把链接文本与文件名文本装配成任务队列。
     * 也支持在链接行内用「|」直接写文件名： 链接|文件名
     */
    public static List<Models.Task> parseTasks(String linkText, String nameText, boolean batch) {
        List<Models.Task> out = new ArrayList<>();
        if (linkText == null) {
            return out;
        }
        List<String> links = new ArrayList<>();
        List<String> inlineNames = new ArrayList<>();
        if (!batch) {
            String s = linkText.trim();
            if (!s.isEmpty()) {
                links.add(s);
                inlineNames.add(null);
            }
        } else {
            for (String line : linkText.split("\\r?\\n")) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) {
                    continue;
                }
                int bar = s.indexOf('|');
                if (bar > 0) {
                    links.add(s.substring(0, bar).trim());
                    inlineNames.add(s.substring(bar + 1).trim());
                } else {
                    links.add(s);
                    inlineNames.add(null);
                }
            }
        }
        List<String> names = new ArrayList<>();
        if (nameText != null) {
            for (String line : nameText.split("\\r?\\n")) {
                names.add(line.trim());
            }
        }
        int total = links.size();
        for (int i = 0; i < total; i++) {
            String inline = inlineNames.get(i);
            String byLine = i < names.size() ? names.get(i) : "";
            String chosen = inline != null && !inline.isEmpty() ? inline : byLine;
            out.add(new Models.Task(links.get(i), chosen, i + 1, total));
        }
        return out;
    }

    private static String hostOf(String url) {
        try {
            String h = URI.create(url).getHost();
            if (h != null && !h.isEmpty()) {
                return sanitize(h.replaceAll("^www\\.", ""));
            }
        } catch (Exception ignored) {
        }
        return "video";
    }

    private static String stripExtension(String s) {
        String lower = s.toLowerCase(Locale.US);
        String[] exts = {".mp4", ".ts", ".m4v", ".mkv", ".mov", ".m3u8"};
        for (String e : exts) {
            if (lower.endsWith(e)) {
                return s.substring(0, s.length() - e.length());
            }
        }
        return s;
    }

    public static String sanitize(String s) {
        if (s == null) {
            return "";
        }
        String r = s.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        while (r.startsWith(".")) {
            r = r.substring(1);
        }
        return r;
    }

    /** Download 下的子目录：禁止绝对路径与 .. 越级。 */
    public static String safeSubdir(String s) {
        if (s == null) {
            return Prefs.DEFAULT_SUBDIR;
        }
        String r = sanitize(s).replace("..", "");
        r = r.replaceAll("^/+", "").replaceAll("/+$", "");
        return r.isEmpty() ? Prefs.DEFAULT_SUBDIR : r;
    }

    private FileNameUtil() {
    }
}
