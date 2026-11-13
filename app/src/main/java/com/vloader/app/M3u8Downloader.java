package com.vloader.app;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 并发片段下载 + 顺序合并（含 AES-128 解密）。
 * 线程数、重试次数由设置传入；0 线程表示「无限制」，按片段数自由并发。
 * 任一片段失败即整体失败，保证产出的完整性。
 *
 * 停止模型统一由「每文件取消句柄 + 进程级共享停止标志」驱动：
 *  1) 片段线程的 CancelCheck 绑定取消句柄，读取循环逐块检查共享标志；
 *  2) HttpUtil 中断所有在途工作线程，打断 latch.await / 退避等待；
 *  3) 等待 latch 采用短轮询，检测到停止立即 shutdownNow() 并退出；
 *  4) 派发与合并循环逐次检查停止状态，停止后不提交新片段、不再写盘。
 */
public final class M3u8Downloader {

    public interface ProgressListener {
        void onProgress(int percent);

        void onStatus(String message);

        /** bytes=累计下载字节，done=已处理片段数，total=总片段数。 */
        void onDetail(long bytes, int done, int total, long elapsedMillis);
    }

    public static final class Result {
        public final boolean ok;
        public final boolean paused;
        public final int failed;
        public final int total;
        public final String error;

        Result(boolean ok, boolean paused, int failed, int total, String error) {
            this.ok = ok;
            this.paused = paused;
            this.failed = failed;
            this.total = total;
            this.error = error;
        }
    }

    private static final int TIMEOUT = 30000;
    private static final int MERGE_MAX = 99;   // 留 1% 给 remux / 写盘
    /** 无限制模式下同时在途请求的上限，防止超大播放列表瞬间开上千线程导致 OOM。 */
    private static final int FREE_CONCURRENCY_CAP = 128;

    private final ProgressListener listener;
    private final HttpUtil.Headers headers;
    private final Map<String, byte[]> keys;
    private final int threadCfg;
    private final int retryCfg;
    /** 外部取消句柄（每个文件一个）；暂停/放弃时由引擎置位。可为 null（兼容旧调用）。 */
    private final HttpUtil.CancelCheck owner;
    private volatile boolean cancelled;
    private volatile boolean paused;
    private final AtomicLong bytes = new AtomicLong(0);
    private long startAt;
    private ExecutorService pool;

    public M3u8Downloader(ProgressListener listener, HttpUtil.Headers headers,
                          Map<String, byte[]> keys, int threads, int retries) {
        this(listener, headers, keys, threads, retries, null);
    }

    public M3u8Downloader(ProgressListener listener, HttpUtil.Headers headers,
                          Map<String, byte[]> keys, int threads, int retries,
                          HttpUtil.CancelCheck owner) {
        this.listener = listener;
        this.headers = headers;
        this.keys = keys;
        this.threadCfg = threads;
        this.retryCfg = retries;
        this.owner = owner;
    }

    /** 统一的停止判断：自身取消 / 所属文件被暂停放弃 / 全局共享停止标志，都算停止。 */
    private boolean stopped() {
        return cancelled || (owner != null && owner.isCancelled())
                || HttpUtil.stopRequested();
    }

    /**
     * 片段请求的取消句柄：直接返回所属文件的句柄本身（而非包装类），
     * 这样 HttpUtil.track() 登记的 key 就是该句柄，暂停时 f.cancel.cancel()
     * 才能精确关闭本文件所有在途流。cancelled 状态由 cancel() 里同步置到 owner 上。
     */
    private HttpUtil.CancelCheck segmentCancel() {
        if (owner != null) {
            return owner;
        }
        return new HttpUtil.CancelCheck() {
            @Override public boolean isCancelled() {
                return cancelled;
            }
        };
    }

    /** 暂停：断连但保留已完整落盘的片段，继续时复用。 */
    public void pause() {
        paused = true;
        // 精确取消：owner.cancel() 关闭本文件在途 I/O 并 interrupt 本文件片段线程。
        // 全局急停标志由引擎在「全局暂停」时统一置位，这里不污染其它并发文件。
        cancel();
    }

    public boolean isPaused() {
        return paused;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void cancel() {
        cancelled = true;
        // 精确取消：只关闭本文件在途 I/O；owner 为空（旧调用）时退回全局取消
        if (owner instanceof HttpUtil.CancelHandle) {
            ((HttpUtil.CancelHandle) owner).cancel();
        } else if (owner == null) {
            HttpUtil.cancelAll();
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private void status(String s) {
        if (listener != null) {
            listener.onStatus(s);
        }
    }

    private void progress(int p) {
        if (listener != null) {
            listener.onProgress(Math.max(0, Math.min(100, p)));
        }
    }

    private void detail(int done, int total) {
        if (listener != null) {
            listener.onDetail(bytes.get(), done, total,
                    System.currentTimeMillis() - startAt);
        }
    }

    public Result run(List<M3u8Parser.Segment> segments, File workDir, File merged) {
        final int total = segments.size();
        if (total == 0) {
            return new Result(false, false, 0, 0, "播放列表为空");
        }
        if (!workDir.exists() && !workDir.mkdirs()) {
            return new Result(false, false, 0, total, "无法创建缓存目录");
        }
        if (stopped()) {
            return new Result(false, paused, 0, total, paused ? "已暂停" : "已取消");
        }
        startAt = System.currentTimeMillis();
        final boolean unlimited = threadCfg == Prefs.THREAD_UNLIMITED;
        final int threads = unlimited ? Math.min(total, FREE_CONCURRENCY_CAP)
                : Math.min(Math.max(1, threadCfg), total);
        pool = Executors.newFixedThreadPool(threads);
        // 无限制模式：线程数按片段规模放大，用信号量控制在途请求，避免瞬时建连风暴
        final Semaphore gate = unlimited ? new Semaphore(FREE_CONCURRENCY_CAP) : null;

        final File[] tmp = new File[total];
        final String[] errors = new String[total];
        final AtomicInteger done = new AtomicInteger(0);
        final AtomicInteger failed = new AtomicInteger(0);
        final CountDownLatch latch = new CountDownLatch(total);

        status("开始下载 " + total + " 个片段（并发 "
                + (unlimited ? "无限制" : threads + " 线程")
                + "，失败重试 " + retryCfg + " 次）…");
        for (int i = 0; i < total; i++) {
            // 取消后不再提交剩余片段；已提交但排在队列里的会被 shutdownNow 丢弃
            if (stopped()) {
                break;
            }
            final int idx = i;
            final M3u8Parser.Segment seg = segments.get(i);
            final File f = new File(workDir, String.format(Locale.US, "seg_%05d.ts", i));
            tmp[i] = f;
            try {
                pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        boolean acquired = false;
                        try {
                            if (stopped()) {
                                return;
                            }
                            if (gate != null) {
                                gate.acquire();
                                acquired = true;
                            }
                            if (stopped()) {
                                return;
                            }
                            if (!(f.exists() && f.length() > 0)) {
                                HttpUtil.downloadToFile(seg.url, f, headers, TIMEOUT, retryCfg,
                                        segmentCancel(),
                                        new HttpUtil.ByteSink() {
                                            @Override
                                            public void onBytes(int n) {
                                                bytes.addAndGet(n);
                                            }
                                        });
                            } else {
                                bytes.addAndGet(f.length()); // 续传复用也计入吞吐
                            }
                            done.incrementAndGet();
                        } catch (Exception e) {
                            errors[idx] = e.getMessage() == null ? e.toString() : e.getMessage();
                            failed.incrementAndGet();
                        } finally {
                            if (acquired) {
                                gate.release();
                            }
                            latch.countDown();
                            int finished = done.get() + failed.get();
                            progress((int) ((long) finished * MERGE_MAX / total));
                            detail(finished, total);
                        }
                    }
                });
            } catch (RejectedExecutionException ignored) {
                latch.countDown();
            }
        }
        // 短轮询等待：取消后立即退出，不再因队列任务被丢弃而卡满 180 秒
        try {
            long deadline = System.currentTimeMillis() + 180000L;
            while (latch.getCount() > 0) {
                if (stopped()) {
                    pool.shutdownNow();
                    break;
                }
                if (latch.await(200, TimeUnit.MILLISECONDS)) {
                    break;
                }
                if (System.currentTimeMillis() > deadline) {
                    status("等待下载线程结束超时，请检查网络后继续");
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pool.shutdown();

        if (stopped()) {
            return new Result(false, paused, failed.get(), total,
                    paused ? "已暂停" : "已取消");
        }
        if (failed.get() > 0) {
            return new Result(false, false, failed.get(), total,
                    failed.get() + "/" + total + " 个片段下载失败：" + firstError(errors)
                            + "（已下载片段已保留，点继续可续传）");
        }

        status("正在合并 " + total + " 个片段…");
        try {
            if (merged.exists()) {
                merged.delete();
            }
            merge(segments, tmp, merged);
        } catch (Exception e) {
            return new Result(false, false, 0, total, "合并失败：" + e.getMessage());
        }
        if (stopped()) {
            return new Result(false, paused, 0, total, paused ? "已暂停" : "已取消");
        }
        if (merged.length() == 0) {
            return new Result(false, false, 0, total, "合并结果为空");
        }
        for (File f : tmp) {
            if (f != null) {
                f.delete();
            }
        }
        progress(MERGE_MAX);
        detail(total, total);
        return new Result(true, false, 0, total, null);
    }

    private void merge(List<M3u8Parser.Segment> segments, File[] tmp, File merged)
            throws Exception {
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(merged), 1 << 20)) {
            byte[] buf = new byte[1 << 20];
            for (int i = 0; i < tmp.length; i++) {
                if (stopped()) {
                    throw new IOException("已取消");
                }
                File f = tmp[i];
                if (f == null || !f.exists() || f.length() == 0) {
                    throw new IOException("第 " + (i + 1) + " 个片段缺失");
                }
                InputStream in = new FileInputStream(f);
                M3u8Parser.Key key = segments.get(i).key;
                if (key != null) {
                    if (!"AES-128".equalsIgnoreCase(key.method)) {
                        throw new IOException("不支持的加密方式：" + key.method);
                    }
                    byte[] keyBytes = keys.get(key.uri);
                    if (keyBytes == null || keyBytes.length < 16) {
                        throw new IOException("无法获取解密密钥：" + key.uri);
                    }
                    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                    cipher.init(Cipher.DECRYPT_MODE,
                            new SecretKeySpec(keyBytes, 0, 16, "AES"),
                            new IvParameterSpec(ivFor(key, segments.get(i).sequence)));
                    in = new CipherInputStream(in, cipher);
                }
                try {
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                } finally {
                    in.close();
                }
            }
            out.flush();
        }
    }

    /** HLS 规范：未显式指定 IV 时，用媒体序列号的大端 128 位表示。 */
    private static byte[] ivFor(M3u8Parser.Key key, int sequence) {
        byte[] iv = new byte[16];
        if (key.ivHex != null) {
            String h = key.ivHex.toLowerCase(Locale.US).replaceFirst("^0x", "");
            h = String.format(Locale.US, "%32s", h).replace(' ', '0');
            if (h.length() == 32) {
                for (int i = 0; i < 16; i++) {
                    iv[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
                }
                return iv;
            }
        }
        long v = sequence & 0xffffffffL;
        for (int i = 15; i >= 0 && v != 0; i--) {
            iv[i] = (byte) (v & 0xff);
            v >>>= 8;
        }
        return iv;
    }

    private static String firstError(String[] errors) {
        for (String e : errors) {
            if (e != null) {
                return e;
            }
        }
        return "未知错误";
    }
}
