package com.vloader.app;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 下载任务与历史记录的数据模型。 */
public final class Models {

    /** 一次下载任务：链接 + 本次单独指定的文件名（不含扩展名，可为空）。 */
    public static final class Task {
        public String url;
        public String name;
        public int index;
        public int total;

        public Task(String url, String name, int index, int total) {
            this.url = url;
            this.name = name;
            this.index = index;
            this.total = total;
        }
    }

    /**
     * 历史记录存的是「这条下载记录 + 文件地址」，不扫描下载目录。
     * 因此中途更换保存位置也不会丢失历史。
     */
    public static final class Record {
        public long id;
        public long time;
        public long size;
        public String url = "";
        public String name = "";
        /** content:// (MediaStore) 或 file:// (绝对路径)。 */
        public String uri = "";
        public String path = "";
        public String ext = ".mp4";
        public String status = "完成";

        public String encode() {
            return join(id, time, size, enc(url), enc(name), enc(uri), enc(path),
                    enc(ext), enc(status));
        }

        public static Record decode(String line) {
            try {
                String[] f = line.split("\\|", -1);
                if (f.length < 9) {
                    return null;
                }
                Record r = new Record();
                r.id = Long.parseLong(f[0]);
                r.time = Long.parseLong(f[1]);
                r.size = Long.parseLong(f[2]);
                r.url = dec(f[3]);
                r.name = dec(f[4]);
                r.uri = dec(f[5]);
                r.path = dec(f[6]);
                r.ext = dec(f[7]);
                r.status = dec(f[8]);
                return r;
            } catch (Exception e) {
                return null;
            }
        }

        private static String enc(String s) {
            try {
                return URLEncoder.encode(s == null ? "" : s, "UTF-8");
            } catch (Exception e) {
                return "";
            }
        }

        private static String dec(String s) {
            try {
                return URLDecoder.decode(s, "UTF-8");
            } catch (Exception e) {
                return s;
            }
        }

        private static String join(Object... parts) {
            StringBuilder sb = new StringBuilder();
            for (Object p : parts) {
                if (sb.length() > 0) {
                    sb.append('|');
                }
                sb.append(p);
            }
            return sb.toString();
        }
    }

    public static List<Record> clone(List<Record> src) {
        return new ArrayList<>(src);
    }

    private Models() {
    }
}
