package com.vloader.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一网络层：自定义请求头、超时、重试、取消断连、实时字节统计。
 *
 * 暂停模型采用「一个共享停止标志 + 每轮检查 + interrupt」：
 *  1) 进程级 volatile STOP 标志，暂停即置位，所有读取循环每读一块都检查它；
 *  2) CancelHandle.isCancelled() 同步反映 STOP，保证句柄换代后判定一致；
 *  3) 在途工作线程登记到 ACTIVE，取消时一并 interrupt()，
 *     打断 latch.await / sleepBeforeRetry / gate.acquire 等可中断等待；
 *  4) 读超时设为 10 秒，使阻塞读取及时返回并被 STOP 标志拦下；
 *  5) close() / disconnect() 配合加速读取返回。
 */
public final class HttpUtil {

    public interface CancelCheck {
        boolean isCancelled();
    }

    /** 进程级共享停止标志：暂停即置位，继续 / 新任务即清除。绝不与句柄换代耦合。 */
    private static volatile boolean STOP = false;

    /** 暂停（或放弃）时调用：置位共享标志 + 关闭所有在途 I/O + 中断所有下载线程。 */
    public static void requestStop() {
        STOP = true;
        cancelAll();
    }

    /** 继续 / 发起新一批任务时调用：复位共享标志。 */
    public static void clearStop() {
        STOP = false;
    }

    public static boolean stopRequested() {
        return STOP;
    }

    /**
     * 单个文件的取消句柄，用于精确断连。
     * isCancelled() 同时反映全局 STOP，因此句柄换代/脱钩都不会让暂停失效。
     */
    public static final class CancelHandle implements CancelCheck {
        private volatile boolean cancelled;

        @Override public boolean isCancelled() {
            return cancelled || STOP;
        }

        /** 立即置位并关闭本句柄名下的所有在途 I/O，让阻塞的 read() 立即返回。 */
        public void cancel() {
            cancelled = true;
            cancelFor(this);
        }
    }

    /** 每读到一块数据就上报，用于计算实时下载速度。 */
    public interface ByteSink {
        void onBytes(int n);
    }

    public static final class Headers {
        private final Map<String, String> map = new LinkedHashMap<>();

        public Headers(String userText) {
            map.put("User-Agent", "Mozilla/5.0 (Linux; Android) m3u8dl/"
                    + Prefs.VERSION_NAME);
            if (userText != null) {
                for (String line : userText.split("\\r?\\n")) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        String k = line.substring(0, colon).trim();
                        String v = line.substring(colon + 1).trim();
                        if (!k.isEmpty() && !v.isEmpty()) {
                            map.put(k, v);
                        }
                    }
                }
            }
        }

        public Map<String, String> map() {
            return map;
        }
    }

    /** 在途 I/O：连接、正在读的流、以及正在工作的线程都登记，值为该文件自己的取消句柄。 */
    private static final Map<Object, CancelCheck> ACTIVE = new ConcurrentHashMap<>();

    private static void track(Object io, CancelCheck cancel) {
        if (io != null) {
            ACTIVE.put(io, cancel);
        }
    }

    private static void untrack(Object io) {
        if (io != null) {
            ACTIVE.remove(io);
        }
    }

    /** 断开连接 / 关闭流 / 中断线程：三者合力让阻塞的 read() 与等待立刻结束。 */
    private static void abort(Object io) {
        try {
            if (io instanceof HttpURLConnection) {
                ((HttpURLConnection) io).disconnect();
            } else if (io instanceof InputStream) {
                ((InputStream) io).close();
            } else if (io instanceof Thread) {
                ((Thread) io).interrupt();   // 直接中断下载线程
            }
        } catch (Exception ignored) { }
    }

    /** 全局取消：暂停 / 放弃全部 / 清空列表时使用。 */
    public static void cancelAll() {
        for (Object io : ACTIVE.keySet()) {
            abort(io);
        }
    }

    /** 精确取消：只中断绑定了该句柄的请求，暂停单文件不影响其它并发文件。 */
    public static void cancelFor(CancelCheck cancel) {
        if (cancel == null) { return; }
        for (Map.Entry<Object, CancelCheck> e : ACTIVE.entrySet()) {
            if (e.getValue() == cancel) {
                abort(e.getKey());
            }
        }
    }

    /** 统一的「是否该停」判断：共享标志优先，其次是本请求的句柄。 */
    private static boolean isStopped(CancelCheck cancel) {
        return STOP || (cancel != null && cancel.isCancelled());
    }

    private static HttpURLConnection open(String url, Headers headers, int timeout,
                                          CancelCheck cancel) throws IOException {
        if (isStopped(cancel)) {
            throw new IOException("已暂停");
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        track(c, cancel);
        c.setConnectTimeout(timeout);
        // 读超时压到 10 秒：即使 close() 没能打断阻塞的 read()，也不会让用户等 30 秒
        c.setReadTimeout(Math.min(timeout, 10000));
        c.setInstanceFollowRedirects(true);
        for (Map.Entry<String, String> e : headers.map().entrySet()) {
            c.setRequestProperty(e.getKey(), e.getValue());
        }
        return c;
    }

    public static byte[] fetchBytes(String url, Headers headers, int timeout, int retries,
                                    CancelCheck cancel) throws IOException {
        IOException last = null;
        Thread self = Thread.currentThread();
        for (int attempt = 0; attempt <= retries; attempt++) {
            HttpURLConnection c = null;
            InputStream in = null;
            try {
                track(self, cancel);   // 登记工作线程，取消时 interrupt()
                c = open(url, headers, timeout, cancel);
                int code = c.getResponseCode();
                if (code >= 400) {
                    throw new IOException("HTTP " + code);
                }
                in = c.getInputStream();
                track(in, cancel);
                try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (isStopped(cancel)) {
                            throw new IOException("已暂停");
                        }
                        out.write(buf, 0, n);
                    }
                    return out.toByteArray();
                }
            } catch (IOException e) {
                last = e;
                if (isStopped(cancel)) {
                    break;
                }
                if (attempt < retries) {
                    sleepBeforeRetry(attempt, cancel);
                }
            } finally {
                if (in != null) {
                    untrack(in);
                    try { in.close(); } catch (Exception ignored) { }
                }
                if (c != null) {
                    untrack(c);
                    c.disconnect();
                }
                untrack(self);
                // 停止与否由共享 STOP / 句柄决定，清掉中断标志避免污染线程池复用
                Thread.interrupted();
            }
        }
        throw last != null ? last : new IOException("请求失败");
    }

    public static String fetchText(String url, Headers headers, int timeout, int retries,
                                   CancelCheck cancel) throws IOException {
        String s = new String(fetchBytes(url, headers, timeout, retries, cancel),
                StandardCharsets.UTF_8);
        if (!s.isEmpty() && s.charAt(0) == '\ufeff') {
            s = s.substring(1);
        }
        return s;
    }

    /** 下载到 .part，成功后原子重命名为完整片段，重跑时可直接跳过（断点续传）。 */
    public static void downloadToFile(String url, File dest, Headers headers, int timeout,
                                      int retries, CancelCheck cancel, ByteSink sink)
            throws IOException {
        IOException last = null;
        File part = new File(dest.getAbsolutePath() + ".part");
        Thread self = Thread.currentThread();
        for (int attempt = 0; attempt <= retries; attempt++) {
            HttpURLConnection c = null;
            InputStream in = null;
            try {
                if (isStopped(cancel)) {
                    throw new IOException("已暂停");
                }
                if (part.exists()) {
                    part.delete();
                }
                track(self, cancel);   // 登记工作线程，取消时 interrupt()
                c = open(url, headers, timeout, cancel);
                int code = c.getResponseCode();
                if (code >= 400) {
                    throw new IOException("HTTP " + code);
                }
                File parent = dest.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                in = c.getInputStream();
                track(in, cancel);
                try (java.io.OutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        // 每读一块都查共享停止标志：暂停后立即停止写盘、不再继续下载
                        if (isStopped(cancel)) {
                            throw new IOException("已暂停");
                        }
                        out.write(buf, 0, n);
                        // 全局限速：整批下载共享同一份令牌桶，限制总带宽
                        SpeedLimiter.consume(n);
                        if (sink != null) {
                            sink.onBytes(n);
                        }
                    }
                    out.flush();
                }
                if (isStopped(cancel)) {
                    throw new IOException("已暂停");
                }
                if (part.length() == 0) {
                    throw new IOException("空文件");
                }
                if (dest.exists()) {
                    dest.delete();
                }
                if (!part.renameTo(dest)) {
                    throw new IOException("无法保存片段");
                }
                return;
            } catch (IOException e) {
                last = e;
                if (part.exists()) {
                    part.delete();
                }
                if (isStopped(cancel)) {
                    break;   // 暂停/取消：不再重试
                }
                if (attempt < retries) {
                    sleepBeforeRetry(attempt, cancel);
                }
            } finally {
                if (in != null) {
                    untrack(in);
                    try { in.close(); } catch (Exception ignored) { }
                }
                if (c != null) {
                    untrack(c);
                    c.disconnect();
                }
                untrack(self);
                // 停止与否由共享 STOP / 句柄决定，清掉中断标志避免污染线程池复用
                Thread.interrupted();
            }
        }
        throw last != null ? last : new IOException("下载失败");
    }

    private static void sleepBeforeRetry(int attempt, CancelCheck cancel) {
        try {
            Thread.sleep(300L * (attempt + 1));
        } catch (InterruptedException e) {
            // 暂停中断了退避等待：置回共享标志由读取循环处理，这里直接返回
            Thread.currentThread().interrupt();
        }
    }

    private HttpUtil() {
    }
}
