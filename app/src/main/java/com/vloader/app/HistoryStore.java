package com.vloader.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 下载历史：只持久化「每一条下载记录 + 该文件的地址」，不扫描下载目录。
 * 更换保存位置、文件被移动后，历史依然完整。
 */
public final class HistoryStore {

    private static final String FILE = "m3u8dl_history";
    private static final String KEY = "records";
    private static final int MAX = 500;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static List<Models.Record> all(Context c) {
        String raw = sp(c).getString(KEY, "");
        List<Models.Record> out = new ArrayList<>();
        if (raw.isEmpty()) {
            return out;
        }
        for (String line : raw.split("\n")) {
            Models.Record r = Models.Record.decode(line);
            if (r != null) {
                out.add(r);
            }
        }
        Collections.sort(out, new Comparator<Models.Record>() {
            @Override
            public int compare(Models.Record a, Models.Record b) {
                return Long.compare(b.time, a.time);
            }
        });
        return out;
    }

    public static Models.Record find(Context c, long id) {
        for (Models.Record r : all(c)) {
            if (r.id == id) {
                return r;
            }
        }
        return null;
    }

    public static void add(Context c, Models.Record r) {
        List<Models.Record> list = all(c);
        list.add(0, r);
        save(c, list);
    }

    public static void update(Context c, Models.Record r) {
        List<Models.Record> list = all(c);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id == r.id) {
                list.set(i, r);
                break;
            }
        }
        save(c, list);
    }

    public static void remove(Context c, long id) {
        List<Models.Record> list = all(c);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id == id) {
                list.remove(i);
                break;
            }
        }
        save(c, list);
    }

    public static void clear(Context c) {
        sp(c).edit().putString(KEY, "").apply();
    }

    private static void save(Context c, List<Models.Record> list) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(list.size(), MAX);
        for (int i = 0; i < n; i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(list.get(i).encode());
        }
        sp(c).edit().putString(KEY, sb.toString()).apply();
    }

    private HistoryStore() {
    }
}
