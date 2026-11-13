package com.vloader.app;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * 自定义下载目录的路径规范化（纯 Java，便于单元测试）。
 *
 * 背景：原实现直接 new File(userInput)。当用户输入不带前导斜杠的名字（如 m3u8downloaderx），
 * 它会被当成相对路径，getAbsolutePath() 得到 "/m3u8downloaderx"（工作目录是根目录 /），
 * 于是 mkdirs() 去写根目录，必然失败 —— 这正是截图中「无法创建目录：/m3u8downloaderx」的原因。
 *
 * 规则：
 *  1) 空输入，落到 Download 目录；
 *  2) 以共享存储根（/storage/emulated/0 或 /sdcard 别名）开头，视为合法绝对路径，仅做规范化；
 *  3) 其它任何形式（相对名、/foo 这种根级名字）一律视为「目录名」，补到 Download 下，
 *     绝不写到根目录或系统分区；
 *  4) 消除 . / ..，若规范化后越出共享存储根则拒绝；
 *  5) 系统敏感目录一律不写入；原始输入以系统分区开头时统一归入 Download，
 *     不再截取末段，避免用户误以为自己指定了系统分区。
 */
public final class PathUtil {

    private static final String[] FORBIDDEN = {
            "/system", "/vendor", "/proc", "/dev", "/sys", "/data", "/boot",
            "/apex", "/product", "/odm", "/bin", "/sbin", "/etc", "/root"
    };

    /** 把用户输入规范化为可安全写入的绝对目录路径。 */
    public static String normalize(String input, String externalRoot, String downloadDir) {
        String root = trimSlash(externalRoot);
        String dl = trimSlash(downloadDir);
        String s = input == null ? "" : input.trim();
        if (s.isEmpty()) {
            return dl;
        }
        s = s.replace('\\', '/');
        // 去掉末尾斜杠
        while (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }

        // 原始输入以系统分区开头时统一归入 Download。
        // 不做「取最后一段」的处理，否则 /system/app 会被改写成 Download/app，
        // 虽然不危险，但会让用户误以为自己真的指定了 /system 下的目录。
        String lowerInput = s.toLowerCase(Locale.US);
        for (String f : FORBIDDEN) {
            if (lowerInput.equals(f) || lowerInput.startsWith(f + "/")) {
                return dl;
            }
        }

        String alias = "/sdcard";
        boolean absolute = false;
        if (s.startsWith(root + "/") || s.equals(root)) {
            absolute = true;
        } else if (s.startsWith(alias + "/") || s.equals(alias)) {
            // /sdcard/... 是 /storage/emulated/0 的软链别名，映射回去
            s = root + s.substring(alias.length());
            absolute = true;
        } else if (s.startsWith("/mnt/sdcard")) {
            s = root + s.substring("/mnt/sdcard".length());
            absolute = true;
        }

        String candidate;
        if (absolute) {
            candidate = s;
        } else {
            // 取最后一段作为目录名，避免把 "/" 或 "/tmp/x" 之类的输入直接落到根
            String name = s;
            int slash = name.lastIndexOf('/');
            if (slash >= 0) {
                name = name.substring(slash + 1);
            }
            name = name.replace("..", "").trim();
            while (name.startsWith(".")) {
                name = name.substring(1);
            }
            if (name.isEmpty()) {
                return dl;
            }
            candidate = dl + "/" + name;
        }

        // 规范化 . / ..
        String canonical;
        try {
            canonical = new File(candidate).getCanonicalPath();
        } catch (IOException e) {
            canonical = new File(candidate).getAbsolutePath();
        }
        // 必须仍位于共享存储根内
        if (!(canonical.equals(root) || canonical.startsWith(root + "/"))) {
            return dl;
        }
        // 系统敏感目录一律归入 Download
        String lower = canonical.toLowerCase(Locale.US);
        for (String f : FORBIDDEN) {
            if (lower.equals(f) || lower.startsWith(f + "/")) {
                return dl;
            }
        }
        if (canonical.equals("/") || canonical.equals(root)) {
            return dl;   // 不允许直接把整个根目录当下载目录
        }
        return canonical;
    }

    /** 该绝对路径是否落在共享存储内（用于判断是否需要所有文件访问权限）。 */
    public static boolean insideSharedStorage(String path, String externalRoot) {
        String root = trimSlash(externalRoot);
        return path != null && (path.equals(root) || path.startsWith(root + "/"));
    }

    private static String trimSlash(String s) {
        if (s == null) {
            return "/storage/emulated/0";
        }
        String r = s.trim();
        while (r.length() > 1 && r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r.isEmpty() ? "/storage/emulated/0" : r;
    }

    private PathUtil() {
    }
}
