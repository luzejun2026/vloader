package com.vloader.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 下载队列引擎：以「每个文件一个独立状态」驱动 UI 与通知。
 *
 * 每个 FileState 持有终态闸门，进入终态后忽略残留回调，保证状态一致。
 * start() 采用「追加到队列」语义，新任务即时写入 state.files 并同步 emit()，
 * UI 打开任务页即可读到完整队列；常驻调度线程按「单次下载文件数」的并发上限
 * 逐个派发 QUEUED 文件，后加入的任务由调度器自动捡起。
 *
 * 暂停即线程退出、文件回到 PAUSED；继续即重新入队 QUEUED，由调度器派发全新
 * 线程续跑，已下载的片段通过断点续传复用。
 */
public final class DownloadEngine {

    public static final int PHASE_IDLE = 0;
    public static final int PHASE_RUNNING = 1;
    public static final int PHASE_PAUSED = 2;
    public static final int PHASE_DONE = 3;
    public static final int PHASE_FAILED = 4;
    public static final int PHASE_CANCELLED = 5;
    public static final int PHASE_ALL_DONE = 6;
    public static final int PHASE_QUEUED = 7;

    /** 单个文件的状态；下载历史「正在下载」页每个文件渲染一张卡片。 */
    public static final class FileState {
        public long id;
        public String name = "";
        public String url = "";
        public int phase = PHASE_QUEUED;
        public int percent;
        public long bytes;
        public int done;
        public int total;
        public long speed;          // B/s
        public long etaMs = -1;
        public String status = "";
        public String error = "";
        public Models.Record record;
        /** 该文件所属批次的配置，追加多个批次时各自生效。 */
        public Config cfg;

        /** 终态闸门：置位后忽略该文件的一切残留回调，直到 resume 重新打开。 */
        boolean terminal;
        boolean paused;
        boolean cancelled;
        /** 是否已有线程在执行该文件的下载循环，避免同一文件被重复派发。 */
        boolean dispatched;
        /**
         * 该文件专属的取消句柄。暂停/放弃时调用 cancel() 立即关闭它名下所有在途
         * 连接与流；「继续」时换一个新句柄，让上一轮已取消的旧线程保持停止、不会复活。
         */
        HttpUtil.CancelHandle cancel = new HttpUtil.CancelHandle();
        /** 完成时间戳（0 表示未完成）；「正在下载」页据此在完成后停留数秒自动移除卡片。 */
        long doneAt;

        public FileState copy() {
            FileState f = new FileState();
            f.id = id;
            f.name = name;
            f.url = url;
            f.phase = phase;
            f.percent = percent;
            f.bytes = bytes;
            f.done = done;
            f.total = total;
            f.speed = speed;
            f.etaMs = etaMs;
            f.status = status;
            f.error = error;
            f.record = record;
            f.cfg = cfg;
            f.terminal = terminal;
            f.paused = paused;
            f.cancelled = cancelled;
            f.dispatched = dispatched;
            f.doneAt = doneAt;
            // cancel 句柄不共享：副本仅供 UI 展示，取消只作用于引擎内的真实文件
            return f;
        }
    }

    /** 全局状态：兼容通知栏与主页，同时携带逐文件列表。 */
    public static final class State {
        public int phase = PHASE_IDLE;
        public String status = "";
        public int percent;
        public long bytes;
        public int done;
        public int total;
        public long speed;
        public long etaMs = -1;
        public int queueIndex;
        public int queueTotal;
        public String currentName = "";
        public Models.Record record;
        public int successCount;
        public int failCount;
        public String error = "";
        public long runId;
        public List<FileState> files = new ArrayList<>();

        public State copy() {
            State s = new State();
            s.phase = phase;
            s.status = status;
            s.percent = percent;
            s.bytes = bytes;
            s.done = done;
            s.total = total;
            s.speed = speed;
            s.etaMs = etaMs;
            s.queueIndex = queueIndex;
            s.queueTotal = queueTotal;
            s.currentName = currentName;
            s.record = record;
            s.successCount = successCount;
            s.failCount = failCount;
            s.error = error;
            s.runId = runId;
            s.files = new ArrayList<>();
            for (FileState f : files) {
                s.files.add(f.copy());
            }
            return s;
        }
    }

    public static final class Config {
        public List<Models.Task> tasks;
        public String headersText = "";
        public int threads = Prefs.DEFAULT_THREADS;
        public int retries = 2;
        public int locMode = Prefs.LOC_PUBLIC;
        public String locValue = "";
        /** 同时下载的文件数；0 表示不限（全部并行）。 */
        public int filesPerRun = 1;
        /** 全局限速 KB/s；0 表示不限速。 */
        public int speedLimitKbps = 0;
    }

    public interface Callback {
        void onState(State s);
    }

    private static final int TIMEOUT = 30000;
    private static final int MAX_VARIANT_DEPTH = 3;
    private static long sRunCounter = 0;
    private static long sFileCounter = 0;

    private final Context ctx;
    private final Object lock = new Object();
    private final State state = new State();
    private final Map<Long, M3u8Downloader> downloaders = new HashMap<>();

    private Callback callback;
    private Thread scheduler;
    private volatile boolean schedAlive;
    private Config cfg;
    private volatile ExecutorService filePool;
    private volatile boolean globalStop;

    public DownloadEngine(Context c) {
        this.ctx = c.getApplicationContext();
    }

    public void setCallback(Callback cb) {
        this.callback = cb;
    }

    /** 是否有活动中的文件（进行中 / 暂停 / 排队）。 */
    public boolean isBusy() {
        synchronized (lock) {
            for (FileState f : state.files) {
                if (f.phase == PHASE_RUNNING || f.phase == PHASE_PAUSED
                        || f.phase == PHASE_QUEUED) { return true; }
            }
        }
        return false;
    }

    public int phase() {
        return state.phase;
    }

    public State snapshot() {
        synchronized (lock) {
            return state.copy();
        }
    }

    /** 汇总所有文件状态，生成一次 UI 推送。 */
    private void emit() {
        State s;
        synchronized (lock) {
            int running = 0, paused = 0, pending = 0, done = 0, failed = 0;
            long bytes = 0, speed = 0;
            for (FileState f : state.files) {
                if (f.phase == PHASE_RUNNING) { running++; }
                else if (f.phase == PHASE_PAUSED) { paused++; }
                else if (f.phase == PHASE_QUEUED) { pending++; }
                else if (f.phase == PHASE_DONE) { done++; }
                else if (f.phase == PHASE_FAILED) { failed++; }
                bytes += f.bytes;
                speed += f.speed;
            }
            state.successCount = done;
            state.failCount = failed;
            state.bytes = bytes;
            state.speed = speed;
            state.queueTotal = state.files.size();
            state.queueIndex = done + failed;
            if (globalStop) {
                state.phase = PHASE_CANCELLED;
            } else if (running > 0) {
                state.phase = PHASE_RUNNING;
            } else if (paused > 0) {
                state.phase = PHASE_PAUSED;
            } else if (pending > 0) {
                state.phase = PHASE_RUNNING;
            } else {
                state.phase = PHASE_ALL_DONE;
                state.status = "全部完成，成功 " + done + " 个，失败 " + failed + " 个";
            }
            s = state.copy();
        }
        Callback cb = callback;
        if (cb != null) { cb.onState(s); }
    }

    /**
     * 单文件进度回调。终态闸门在此生效：暂停/取消/完成/失败后，
     * 底层线程残留的 onProgress/onDetail 一律被丢弃，绝不把 phase 改回去。
     */
    private void publishFile(FileState f, int phase, String status) {
        synchronized (lock) {
            if (f.terminal || f.cancelled || globalStop) { return; }
            if (f.paused && phase == PHASE_RUNNING) { return; }
            if (phase == PHASE_RUNNING && f.phase == PHASE_PAUSED) { return; }
            f.phase = phase;
            if (status != null) { f.status = status; }
            state.currentName = f.name;
        }
        emit();
    }

    /**
     * 追加到下载队列。无论引擎空闲还是正忙都生效；同步 emit 一次，
     * 保证「已加入下载队列」提示出现时队列里立刻就有文件。
     */
    public synchronized void start(Config c) {
        if (c == null || c.tasks == null || c.tasks.isEmpty()) { return; }
        // 新一批任务：复位上一轮暂停/放弃留下的共享停止标志，否则新任务会秒停
        HttpUtil.clearStop();
        this.cfg = c;
        SpeedLimiter.setLimitKbps(c.speedLimitKbps);
        boolean fresh = true;
        synchronized (lock) {
            for (FileState f : state.files) {
                if (f.phase == PHASE_RUNNING || f.phase == PHASE_PAUSED
                        || f.phase == PHASE_QUEUED) { fresh = false; break; }
            }
            globalStop = false;
            if (fresh) {
                // 上一批已全部结束：清掉旧卡片，开启新一轮
                state.files.clear();
                state.runId = ++sRunCounter;
                state.successCount = 0;
                state.failCount = 0;
                state.error = "";
                state.record = null;
                state.percent = 0;
                state.speed = 0;
                state.etaMs = -1;
                state.status = "等待下载…";
            }
            for (Models.Task t : c.tasks) {
                FileState f = new FileState();
                f.id = ++sFileCounter;      // 全局唯一，跨批次不会重复
                f.cfg = c;
                f.url = t.url;
                f.name = t.name == null || t.name.isEmpty()
                        ? FileNameUtil.defaultName(t.index, t.total, System.currentTimeMillis())
                        : t.name;
                f.phase = PHASE_QUEUED;
                f.status = "等待中";
                state.files.add(f);
            }
        }
        LogStore.log(ctx, "engine", "加入队列 " + c.tasks.size()
                + " 个文件，并发 " + c.filesPerRun + "，线程 " + c.threads
                + "，限速 " + (c.speedLimitKbps <= 0 ? "不限" : c.speedLimitKbps + "KB/s"));
        // 关键：同步推送，UI 打开「正在下载」页时立刻看到完整队列
        emit();
        ensureScheduler();
    }

    private synchronized ExecutorService pool() {
        if (filePool == null || filePool.isShutdown() || filePool.isTerminated()) {
            filePool = Executors.newCachedThreadPool();
        }
        return filePool;
    }

    /** 调度线程可重启：空闲时它会自行退出，继续下载时能再拉起一个。 */
    private synchronized void ensureScheduler() {
        if (schedAlive && scheduler != null && scheduler.isAlive()) {
            wakeScheduler();   // 唤醒正在 wait(200) 的调度线程，立即捡起新入队文件
            return;
        }
        schedAlive = true;
        scheduler = new Thread(new Runnable() {
            @Override public void run() {
                try { scheduleLoop(); }
                finally { schedAlive = false; }
            }
        }, "m3u8dl-sched");
        scheduler.start();
    }

    /** 唤醒调度线程；暂停 / 继续 / 文件跑完都要用它。 */
    private void wakeScheduler() {
        synchronized (lock) { lock.notifyAll(); }
    }

    /** 常驻调度：按并发上限把 QUEUED 文件派发出去，后追加的任务也会自动被捡起。 */
    private void scheduleLoop() {
        if (!hasNetwork()) {
            synchronized (lock) {
                for (FileState f : state.files) {
                    if (f.phase == PHASE_QUEUED) {
                        f.phase = PHASE_FAILED;
                        f.terminal = true;
                        f.error = "当前无网络连接";
                        f.status = "失败：当前无网络连接";
                    }
                }
                state.error = "当前无网络连接";
            }
            LogStore.log(ctx, "engine", "失败：当前无网络连接");
            emit();
            return;
        }
        while (true) {
            final List<FileState> todo = new ArrayList<>();
            synchronized (lock) {
                if (globalStop) { return; }
                int active = 0;
                for (FileState f : state.files) {
                    // 只统计真正在跑的文件：PAUSED 不占并发额度，
                    // 否则暂停一个文件就会把「继续 / 新任务」永久堵在队列里。
                    if (f.phase == PHASE_RUNNING && f.dispatched) { active++; }
                }
                int cap = cfg == null ? 1
                        : (cfg.filesPerRun <= 0 ? Integer.MAX_VALUE : cfg.filesPerRun);
                for (FileState f : state.files) {
                    if (f.phase == PHASE_QUEUED && active < cap) {
                        if (f.dispatched) { continue; }   // 已有线程在跑，不重复派发
                        f.phase = PHASE_RUNNING;   // 先标记已派发，避免下一轮重复提交
                        f.dispatched = true;
                        f.status = "准备中…";
                        todo.add(f);
                        active++;
                    }
                }
                if (todo.isEmpty() && active == 0) { return; }
            }
            for (final FileState f : todo) {
                pool().execute(new Runnable() {
                    @Override public void run() { runFileLoop(f); }
                });
            }
            synchronized (lock) {
                if (globalStop) { return; }
                try { lock.wait(200); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    /**
     * 单个文件执行一次下载循环。暂停后线程直接退出、文件回到 PAUSED；
     * 「继续」由 resume / resumeFile 重新置为 QUEUED，调度器派发全新线程续跑。
     * 暂停与继续由调度器统一派发 —— 每项任务始终由独立的全新线程执行，
     * 这正是「暂停后再继续进度条一直不动」的根因。
     */
    private void runFileLoop(final FileState f) {
        try {
            int outcome = runTask(f);
            // 放弃 / 取消：下载线程退出写盘后清理临时片段，保证目录干净。
            // 与 cancelFile() 里的即时清理形成双保险（幂等，重复调用无副作用）。
            if (outcome == 3) { cleanWorkDirOf(f.url); }
            synchronized (lock) {
                if (outcome == 1 && !f.cancelled && !globalStop) {
                    if (f.paused) {
                        // 暂停：保留已下载片段，卡片停在 PAUSED 等用户点「继续」
                        f.phase = PHASE_PAUSED;
                        f.status = "已暂停，已下载片段已保留";
                        f.speed = 0;
                    } else {
                        // 暂停与继续几乎同时发生：立刻重新入队，让调度器再跑一遍
                        f.phase = PHASE_QUEUED;
                        f.status = "正在继续下载…";
                    }
                }
            }
        } finally {
            synchronized (lock) {
                f.dispatched = false;
                lock.notifyAll();
            }
            emit();
            wakeScheduler();   // 让调度器立刻捡起后续 QUEUED 文件
        }
    }

    /** 返回 0=成功，1=暂停，2=失败，3=取消。 */
    private int runTask(final FileState f) {
        final String baseUrl = f.url;
        final Config c = f.cfg != null ? f.cfg : cfg;
        try {
            if (c == null) { throw new Exception("任务配置丢失"); }
            if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                throw new Exception("链接需以 http:// 或 https:// 开头");
            }
            final HttpUtil.Headers h = new HttpUtil.Headers(c.headersText);
            publishFile(f, PHASE_RUNNING, "正在解析播放列表…");
            String playlistUrl = M3u8Parser.encodeUri(baseUrl);
            M3u8Parser.Playlist p = null;
            for (int depth = 0; depth < MAX_VARIANT_DEPTH; depth++) {
                String text = HttpUtil.fetchText(playlistUrl, h, TIMEOUT, c.retries,
                        cancelCheck(f));
                //  内容校验：返回内容必须是真正的 M3U8 播放列表，
                // 普通文件下载直链 / 网页 / 二进制都不含 #EXTM3U 或 #EXT-X- 标签，直接拒绝。
                if (text == null || (!text.contains("#EXTM3U")
                        && !text.contains("#EXT-X-"))) {
                    throw new Exception(ctx.getString(R.string.err_not_m3u8_content));
                }
                p = M3u8Parser.parse(text, playlistUrl);
                if (!p.isMaster()) { break; }
                M3u8Parser.Variant v = M3u8Parser.bestVariant(p);
                publishFile(f, PHASE_RUNNING, "检测到 " + p.variants.size()
                        + " 个子流，选用 " + v.bandwidth + " kbps…");
                playlistUrl = v.url;
                p = null;
            }
            if (p == null || p.isMaster()) { throw new Exception("子流层级过深，无法解析"); }
            if (p.hasMap) { throw new Exception("不支持 fMP4 片段封装（#EXT-X-MAP）"); }
            if (p.segments.isEmpty()) { throw new Exception("未在播放列表中找到 ts 片段"); }

            final Map<String, byte[]> keys = new HashMap<>();
            for (M3u8Parser.Segment seg : p.segments) {
                M3u8Parser.Key k = seg.key;
                if (k != null && k.uri != null && !keys.containsKey(k.uri)) {
                    if (!"AES-128".equalsIgnoreCase(k.method)) {
                        throw new Exception("不支持的加密方式：" + k.method);
                    }
                    publishFile(f, PHASE_RUNNING, "正在获取解密密钥…");
                    byte[] kb = HttpUtil.fetchBytes(k.uri, h, TIMEOUT, c.retries,
                            cancelCheck(f));
                    if (kb.length < 16) { throw new Exception("解密密钥长度无效"); }
                    keys.put(k.uri, kb);
                }
            }

            String key = Integer.toHexString(baseUrl.hashCode());
            File work = StorageUtil.workDir(ctx, key);
            File merged = new File(work, "merged.ts");

            final long[] lastBytes = {0};
            final long[] lastSampleAt = {System.currentTimeMillis()};
            // 每一轮真实派发都用「本轮」的取消句柄：暂停时精确取消这一轮的在途 I/O，
            // 继续时由 resume/resumeFile 换成新句柄，保证线程与句柄一一对应。
            final HttpUtil.CancelHandle runCancel = f.cancel;
            M3u8Downloader d = new M3u8Downloader(new M3u8Downloader.ProgressListener() {
                @Override public void onProgress(int percent) {
                    synchronized (lock) {
                        if (f.terminal || f.paused || f.cancelled) { return; }
                        f.percent = percent;
                    }
                    publishFile(f, PHASE_RUNNING, null);
                }
                @Override public void onStatus(String message) {
                    publishFile(f, PHASE_RUNNING, message);
                }
                @Override public void onDetail(long bytes, int done, int total, long elapsed) {
                    long now = System.currentTimeMillis();
                    long dt = now - lastSampleAt[0];
                    long instant = 0;
                    if (dt >= 500) {
                        instant = Math.max(0, (bytes - lastBytes[0]) * 1000 / dt);
                        lastBytes[0] = bytes;
                        lastSampleAt[0] = now;
                    }
                    synchronized (lock) {
                        if (f.terminal || f.paused || f.cancelled) { return; }
                        f.bytes = bytes;
                        f.done = done;
                        f.total = total;
                        if (instant > 0) { f.speed = instant; }
                        f.etaMs = done > 0 && elapsed > 0
                                ? elapsed * (total - done) / done : -1;
                    }
                    publishFile(f, PHASE_RUNNING, null);
                }
            }, h, keys, c.threads, c.retries, runCancel);
            synchronized (lock) { downloaders.put(f.id, d); }

            M3u8Downloader.Result r = d.run(p.segments, work, merged);
            synchronized (lock) { downloaders.remove(f.id); }
            if (f.cancelled || globalStop) { return 3; }
            if (r.paused || f.paused) { return 1; }
            if (!r.ok) { throw new Exception(r.error == null ? "下载失败" : r.error); }

            publishFile(f, PHASE_RUNNING, "正在转换为 MP4…");
            String ext = ".mp4";
            String mime = "video/mp4";
            File finalFile = merged;
            File mp4 = new File(work, "video.mp4");
            if (Mp4Remuxer.remux(merged, mp4)) {
                finalFile = mp4;
            } else {
                ext = ".ts";
                mime = "video/mp2t";
                publishFile(f, PHASE_RUNNING, "MP4 转换失败，改为保存 TS");
            }

            long now = System.currentTimeMillis();
            final String displayName = FileNameUtil.build(
                    f.name, baseUrl, 1, 1, ext, now);
            publishFile(f, PHASE_RUNNING, "正在保存…");
            StorageUtil.Saved saved = StorageUtil.save(ctx, finalFile, displayName,
                    mime, c.locMode, c.locValue);

            Models.Record rec = new Models.Record();
            rec.id = now * 100 + (f.id % 100);
            rec.time = now;
            rec.size = saved.size;
            rec.url = baseUrl;
            rec.name = displayName;
            rec.uri = saved.uri;
            rec.path = saved.path;
            rec.ext = ext;
            rec.status = "完成";
            HistoryStore.add(ctx, rec);

            StorageUtil.clean(work);
            synchronized (lock) {
                f.record = rec;
                f.percent = 100;
                f.speed = 0;
                f.etaMs = 0;
                f.paused = false;      // 防止暂停竞态残留，完成后再次点继续不会重跑
                f.phase = PHASE_DONE;
                f.terminal = true;
                f.doneAt = System.currentTimeMillis();   // 「正在下载」页据此停留数秒后移除卡片
                f.status = "完成：" + saved.path;
            }
            LogStore.log(ctx, "done", displayName + " → " + saved.path);
            emit();
            return 0;
        } catch (Exception e) {
            synchronized (lock) {
                if (f.cancelled || globalStop) { return 3; }
                if (f.paused) { return 1; }
            }
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            try {
                StorageUtil.clean(StorageUtil.workDir(ctx,
                        Integer.toHexString(baseUrl.hashCode())));
            } catch (Exception ignored) { }
            synchronized (lock) {
                if (f.cancelled || globalStop) { return 3; }
                f.error = msg;
                f.percent = 0;
                f.speed = 0;
                f.etaMs = -1;
                f.phase = PHASE_FAILED;
                f.terminal = true;
                f.status = "失败：" + msg;
                state.error = msg;
            }
            LogStore.log(ctx, "fail", f.name + "：" + msg);
            emit();
            return 2;
        }
    }

    private HttpUtil.CancelCheck cancelCheck(final FileState f) {
        // 直接返回本文件的取消句柄：暂停/放弃时 f.cancel.cancel() 会精确关闭
        // 绑定到它的一切在途连接与流，playlist 解析阶段也能被立即打断。
        return f.cancel;
    }

    // ------------------------------------------------------------ 控制

    /** 全局暂停：所有进行中的文件。 */
    public void pause() {
        HttpUtil.requestStop();   // 置位共享停止标志并中断所有下载线程，立即停
        synchronized (lock) {
            for (FileState f : state.files) {
                if (f.terminal || f.cancelled) { continue; }
                f.paused = true;
                if (f.phase == PHASE_RUNNING || f.phase == PHASE_QUEUED) {
                    f.phase = PHASE_PAUSED;
                }
                f.status = "已暂停，已下载片段已保留";
                f.speed = 0;
                f.terminal = true;   // 闸门关闭，残留回调一律丢弃
                f.cancel.cancel();   // 立即关闭本文件所有在途流，read() 立刻返回
            }
            for (M3u8Downloader d : downloaders.values()) { d.pause(); }
            lock.notifyAll();
        }
        emit();
    }

    /** 全局继续：把暂停文件重新入队，由调度器派发全新线程，进度立即恢复刷新。 */
    public void resume() {
        HttpUtil.clearStop();   // 复位共享停止标志，让新一轮线程能正常发起请求
        synchronized (lock) {
            globalStop = false;
            for (FileState f : state.files) {
                if (!f.paused || f.cancelled) { continue; }
                f.paused = false;
                f.terminal = false;   // 闸门重新打开，进度恢复刷新
                f.phase = PHASE_QUEUED;
                f.status = "正在继续下载…";
                // 换一个全新句柄：上一轮已取消的旧线程继续持有旧句柄、保持停止，
                // 新一轮由调度器派发、用新句柄，互不干扰，旧线程不会复活。
                f.cancel = new HttpUtil.CancelHandle();
            }
            lock.notifyAll();
        }
        emit();
        ensureScheduler();
    }

    /** 放弃全部：清空进度、断连、清理临时片段。 */
    public void cancel(boolean clearCache) {
        synchronized (lock) {
            globalStop = true;
            for (FileState f : state.files) {
                f.cancelled = true;
                f.paused = false;
                f.terminal = true;
                f.dispatched = false;
                f.cancel.cancel();   // 关闭本文件在途 I/O
                f.phase = PHASE_CANCELLED;
                f.percent = 0;
                f.bytes = 0;
                f.done = 0;
                f.total = 0;
                f.speed = 0;
                f.etaMs = -1;
                f.status = "已放弃";
            }
            for (M3u8Downloader d : downloaders.values()) { d.cancel(); }
            lock.notifyAll();
        }
        HttpUtil.cancelAll();
        ExecutorService p = filePool;
        if (p != null) { p.shutdownNow(); }
        if (clearCache) {
            try { StorageUtil.clearCache(ctx); } catch (Exception ignored) { }
        }
        emit();
    }

    /** 单文件暂停（下载历史「正在下载」卡片的暂停键）。 */
    public void pauseFile(long id) {
        // 只用本文件精确句柄取消，不置全局急停，避免影响其它并发文件。
        // f.cancel.cancel() 会精确关闭该文件在途流并 interrupt 它的片段线程。
        synchronized (lock) {
            FileState f = find(id);
            if (f == null || f.cancelled || f.phase == PHASE_DONE
                    || f.phase == PHASE_FAILED || f.phase == PHASE_CANCELLED) { return; }
            f.paused = true;
            f.terminal = true;
            f.phase = PHASE_PAUSED;
            f.status = "已暂停，已下载片段已保留";
            f.speed = 0;
            f.cancel.cancel();   // 立即打断本文件所有片段请求（含阻塞中的 read）
            M3u8Downloader d = downloaders.get(id);
            if (d != null) { d.pause(); }
            lock.notifyAll();   // 唤醒该文件线程与调度线程，暂停立即生效
        }
        emit();
    }

    /** 单文件继续：重新打开终态闸门并重新入队，进度立即恢复刷新。 */
    public void resumeFile(long id) {
        // 若此前是全局暂停（共享急停标志仍在），单文件继续时也要复位，
        // 否则新一轮请求会被 STOP 判为已暂停而立刻退出。
        HttpUtil.clearStop();
        synchronized (lock) {
            FileState f = find(id);
            if (f == null || f.cancelled || !f.paused) { return; }
            globalStop = false;
            f.paused = false;
            f.terminal = false;
            f.phase = PHASE_QUEUED;
            f.status = "正在继续下载…";
            f.cancel = new HttpUtil.CancelHandle();   // 换新句柄，旧线程保持停止
            lock.notifyAll();
        }
        emit();
        ensureScheduler();
    }

    /**
     * 放弃时清理该文件专属的临时片段目录：只删本任务，
     * 不影响其它并发下载任务保留的续传片段，也不弹任何提示。
     */
    private void cleanWorkDirOf(String url) {
        if (url == null || url.isEmpty()) { return; }
        try {
            String key = Integer.toHexString(url.hashCode());
            StorageUtil.clean(new File(StorageUtil.cacheRoot(ctx), key));
            StorageUtil.clean(new File(new File(ctx.getCacheDir(), "m3u8downloader"), key));
        } catch (Exception ignored) { }
    }

    /** 单文件放弃：叉号键。终态后卡片从列表移除、进度归零，并静默清理本任务缓存。 */
    public void cancelFile(long id) {
        String url = null;
        synchronized (lock) {
            FileState f = find(id);
            if (f == null) { return; }
            url = f.url;
            f.cancelled = true;
            f.paused = false;
            f.terminal = true;
            f.dispatched = false;
            f.cancel.cancel();   // 关闭本文件在途 I/O
            f.phase = PHASE_CANCELLED;
            f.percent = 0;
            f.bytes = 0;
            f.done = 0;
            f.total = 0;
            f.speed = 0;
            f.etaMs = -1;
            M3u8Downloader d = downloaders.remove(id);
            if (d != null) { d.cancel(); }
            state.files.remove(f);
            lock.notifyAll();
        }
        // 放弃即自动清理该任务的临时片段，静默执行、不弹提示；
        // 正在写盘的线程退出后由 runFileLoop 再清一次，形成双保险。
        cleanWorkDirOf(url);
        emit();
    }

    /** 「正在下载」页清空全部：取消所有活动中文件并清空列表。 */
    public void clearActive() {
        synchronized (lock) {
            globalStop = true;   // 让汇总状态落到「已取消」而非「全部完成」
            for (FileState f : state.files) {
                f.cancelled = true;
                f.terminal = true;
                f.dispatched = false;
                f.cancel.cancel();   // 关闭每个文件的在途 I/O
            }
            for (M3u8Downloader d : downloaders.values()) { d.cancel(); }
            downloaders.clear();
            state.files.clear();
            lock.notifyAll();
        }
        HttpUtil.cancelAll();
        ExecutorService p = filePool;
        if (p != null) { p.shutdownNow(); }
        // 清空正在下载 = 放弃全部任务，同样静默清理临时片段，不弹提示
        try { StorageUtil.clearCache(ctx); } catch (Exception ignored) { }
        emit();
    }

    /** 移除已结束（完成 / 失败 / 放弃）的卡片，保留活动项。 */
    public void clearFinished() {
        synchronized (lock) {
            List<FileState> keep = new ArrayList<>();
            for (FileState f : state.files) {
                if (f.phase == PHASE_RUNNING || f.phase == PHASE_PAUSED
                        || f.phase == PHASE_QUEUED) { keep.add(f); }
            }
            state.files = keep;
        }
        emit();
    }

    private FileState find(long id) {
        for (FileState f : state.files) {
            if (f.id == id) { return f; }
        }
        return null;
    }

    private boolean hasNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) { return true; }
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info == null || info.isConnected();
        } catch (Exception e) {
            return true;
        }
    }

    public static String formatSpeed(long bytesPerSec) {
        if (bytesPerSec <= 0) { return "—"; }
        if (bytesPerSec < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024f);
        }
        return String.format(Locale.US, "%.2f MB/s", bytesPerSec / (1024f * 1024f));
    }

    public static String formatEta(long millis) {
        if (millis < 0) { return "计算中…"; }
        long s = millis / 1000;
        if (s < 60) { return s + " 秒"; }
        if (s < 3600) { return String.format(Locale.US, "%d 分 %02d 秒", s / 60, s % 60); }
        return String.format(Locale.US, "%d 小时 %02d 分", s / 3600, (s % 3600) / 60);
    }

    public static String formatBytes(long b) {
        if (b < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", b / 1024f);
        }
        return String.format(Locale.US, "%.2f MB", b / (1024f * 1024f));
    }
}
