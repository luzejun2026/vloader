package com.vloader.app;

/**
 * 全局下载限速（令牌桶）。单位字节/秒，0 表示不限速。
 *
 * 由 DownloadEngine 在每批开始时按设置写入，HttpUtil 每读到一块字节就 consume 一次，
 * 多线程共享同一份令牌，因此限速是「整批下载的总带宽」而非单连接带宽。
 */
public final class SpeedLimiter {

    private static volatile long limitBps = 0;
    private static final Object LOCK = new Object();
    private static double tokens = 0;
    private static long lastNanos = System.nanoTime();

    private SpeedLimiter() {
    }

    /** KB/s，0 表示不限速。 */
    public static void setLimitKbps(int kbps) {
        synchronized (LOCK) {
            limitBps = kbps <= 0 ? 0 : (long) kbps * 1024L;
            tokens = 0;
            lastNanos = System.nanoTime();
        }
    }

    public static long limitBps() {
        return limitBps;
    }

    public static void consume(int bytes) {
        final long limit = limitBps;
        if (limit <= 0 || bytes <= 0) {
            return;
        }
        synchronized (LOCK) {
            long now = System.nanoTime();
            double elapsed = (now - lastNanos) / 1_000_000_000.0;
            lastNanos = now;
            tokens = Math.min(limit, tokens + elapsed * limit);
            if (tokens < bytes) {
                double deficit = bytes - tokens;
                long sleepMs = (long) Math.ceil(deficit * 1000.0 / limit);
                if (sleepMs > 0) {
                    try {
                        Thread.sleep(Math.min(sleepMs, 1000L));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                lastNanos = System.nanoTime();
                tokens = 0;
            } else {
                tokens -= bytes;
            }
        }
    }
}
