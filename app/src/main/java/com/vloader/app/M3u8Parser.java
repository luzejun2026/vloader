package com.vloader.app;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HLS 播放列表解析：主列表 / 子流、BANDWIDTH 与 RESOLUTION 属性、
 * #EXT-X-KEY(AES-128)、#EXT-X-MEDIA-SEQUENCE、#EXT-X-DISCONTINUITY、
 * #EXT-X-ENDLIST 等标签，以及相对 URL、BOM 与 URI 百分号编码。
 */
public final class M3u8Parser {

    public static final class Key {
        public final String method;
        public final String uri;
        public final String ivHex;

        Key(String method, String uri, String ivHex) {
            this.method = method;
            this.uri = uri;
            this.ivHex = ivHex;
        }
    }

    public static final class Segment {
        public final String url;
        public final Key key;
        public final int sequence;

        Segment(String url, Key key, int sequence) {
            this.url = url;
            this.key = key;
            this.sequence = sequence;
        }
    }

    public static final class Variant {
        public final String url;
        public final int bandwidth;
        public final String resolution;

        Variant(String url, int bandwidth, String resolution) {
            this.url = url;
            this.bandwidth = bandwidth;
            this.resolution = resolution;
        }
    }

    public static final class Playlist {
        public final List<Segment> segments = new ArrayList<>();
        public final List<Variant> variants = new ArrayList<>();
        public boolean hasEndList;
        public boolean hasMap;
        public boolean hasDiscontinuity;
        public int mediaSequence;

        public boolean isMaster() {
            return !variants.isEmpty();
        }
    }

    private static final Pattern ATTR =
            Pattern.compile("([A-Za-z0-9\\-]+)=(\"([^\"]*)\"|[^,]*)");

    public static Playlist parse(String text, String baseUrl) {
        Playlist p = new Playlist();
        if (text == null) {
            return p;
        }
        if (!text.isEmpty() && text.charAt(0) == '\ufeff') {
            text = text.substring(1);
        }
        Key currentKey = null;
        boolean pendingVariant = false;
        int pendingBandwidth = 0;
        String pendingResolution = null;

        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.charAt(0) == '#') {
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    Map<String, String> a = attributes(line);
                    pendingVariant = true;
                    pendingBandwidth = parseInt(a.get("BANDWIDTH"), 0);
                    pendingResolution = a.get("RESOLUTION");
                } else if (line.startsWith("#EXT-X-KEY")) {
                    Map<String, String> a = attributes(line);
                    String method = a.get("METHOD");
                    if (method != null && !"NONE".equalsIgnoreCase(method)) {
                        String uri = a.get("URI");
                        currentKey = new Key(method,
                                uri == null ? null : resolve(baseUrl, uri), a.get("IV"));
                    } else {
                        currentKey = null;
                    }
                } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE")) {
                    p.mediaSequence = parseInt(valueAfterColon(line), 0);
                } else if (line.startsWith("#EXT-X-MAP")) {
                    p.hasMap = true;
                } else if (line.startsWith("#EXT-X-DISCONTINUITY")) {
                    p.hasDiscontinuity = true;
                } else if (line.startsWith("#EXT-X-ENDLIST")) {
                    p.hasEndList = true;
                }
                continue;
            }

            if (pendingVariant) {
                p.variants.add(new Variant(resolve(baseUrl, line), pendingBandwidth, pendingResolution));
                pendingVariant = false;
                pendingBandwidth = 0;
                pendingResolution = null;
                continue;
            }
            // 没有 STREAM-INF 属性但指向 .m3u8 的行，视为主列表子流
            String path = line.toLowerCase(Locale.US).split("\\?")[0];
            if (path.endsWith(".m3u8")) {
                p.variants.add(new Variant(resolve(baseUrl, line), 0, null));
                continue;
            }
            p.segments.add(new Segment(resolve(baseUrl, line), currentKey,
                    p.mediaSequence + p.segments.size()));
        }
        return p;
    }

    /** 选择带宽最高的子流；无带宽信息时选择第一个。 */
    public static Variant bestVariant(Playlist p) {
        Variant best = p.variants.get(0);
        for (Variant v : p.variants) {
            if (v.bandwidth > best.bandwidth) {
                best = v;
            }
        }
        return best;
    }

    private static Map<String, String> attributes(String line) {
        Map<String, String> m = new LinkedHashMap<>();
        int colon = line.indexOf(':');
        String body = colon >= 0 ? line.substring(colon + 1) : "";
        Matcher mt = ATTR.matcher(body);
        while (mt.find()) {
            String v = mt.group(3) != null ? mt.group(3) : mt.group(2).trim();
            m.put(mt.group(1).toUpperCase(Locale.US), v);
        }
        return m;
    }

    private static String valueAfterColon(String line) {
        int colon = line.indexOf(':');
        return colon >= 0 ? line.substring(colon + 1).trim() : "";
    }

    private static int parseInt(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 相对 URL 用 URI.resolve；对空格、中文等字符做百分号编码，保证 URI 合法。 */
    public static String resolve(String base, String ref) {
        if (ref == null || ref.isEmpty()) {
            return ref;
        }
        String r = ref.trim();
        if (r.startsWith("http://") || r.startsWith("https://")) {
            return encodeUri(r);
        }
        if (base == null || base.isEmpty()) {
            return encodeUri(r);
        }
        try {
            return URI.create(encodeUri(base)).resolve(encodeUri(r)).toString();
        } catch (IllegalArgumentException e) {
            int q = base.indexOf('?');
            String b = q >= 0 ? base.substring(0, q) : base;
            int slash = b.lastIndexOf('/');
            String dir = slash >= 0 ? b.substring(0, slash + 1) : b + "/";
            return encodeUri(dir + r);
        }
    }

    public static String encodeUri(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (byte bb : s.getBytes(StandardCharsets.UTF_8)) {
            int c = bb & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || "-._~:/?#[]@!$&'()*+,;=%".indexOf(c) >= 0) {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format(Locale.US, "%02X", c));
            }
        }
        return sb.toString();
    }

    private M3u8Parser() {
    }
}
