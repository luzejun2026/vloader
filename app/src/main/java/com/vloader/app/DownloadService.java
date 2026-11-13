package com.vloader.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 前台下载服务：切出 App 后下载继续，通知栏展示进度、速度、剩余时间与
 * 暂停 / 继续 / 放弃；通过 WakeLock 支持锁屏后继续下载，并在完成时推送系统通知。
 *
 * 状态分发采用常驻静态分发器 DISPATCH + 多个 UI 订阅者，
 * 引擎回调一旦设置即常驻；Service 销毁仅注销自身通知回调。
 */
public final class DownloadService extends Service {

    // 通知渠道创建后 setShowBadge 不可再改，渠道 ID 固定以保持角标行为一致。
    private static final String CHANNEL = "m3u8dl_download_v2";
    private static final String CHANNEL_DONE = "m3u8dl_finish_v2";
    private static final int NOTIF_ID = 1001;
    private static final int NOTIF_DONE_ID = 1002;

    public static final String ACTION_START = "com.vloader.app.START";
    public static final String ACTION_PAUSE = "com.vloader.app.PAUSE";
    public static final String ACTION_RESUME = "com.vloader.app.RESUME";
    public static final String ACTION_CANCEL = "com.vloader.app.CANCEL";

    private static DownloadEngine sEngine;
    private static volatile DownloadEngine.Config sPendingConfig;
    private static volatile DownloadEngine.State sLastState;
    private static volatile DownloadEngine.Callback sServiceCallback;
    private static final List<DownloadEngine.Callback> sUiCallbacks = new ArrayList<>();

    /** 常驻分发器：引擎回调只设置这一次，Service 生命周期不再影响状态推送。 */
    private static final DownloadEngine.Callback DISPATCH = new DownloadEngine.Callback() {
        @Override public void onState(DownloadEngine.State s) {
            sLastState = s;
            DownloadEngine.Callback svc = sServiceCallback;
            if (svc != null) {
                try { svc.onState(s); } catch (Throwable ignored) { }
            }
            synchronized (sUiCallbacks) {
                for (DownloadEngine.Callback cb : sUiCallbacks) {
                    try { cb.onState(s); } catch (Throwable ignored) { }
                }
            }
        }
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private NotificationManager nm;
    private boolean running;
    private PowerManager.WakeLock wakeLock;
    private boolean finishNotified;

    public static DownloadEngine engine(Context c) {
        if (sEngine == null) {
            sEngine = new DownloadEngine(c.getApplicationContext());
            sEngine.setCallback(DISPATCH);
        }
        return sEngine;
    }

    /** 多播注册；注册时补发一次当前状态，页面打开就能看到已有队列。 */
    public static void registerUiCallback(DownloadEngine.Callback cb) {
        if (cb == null) { return; }
        synchronized (sUiCallbacks) {
            if (!sUiCallbacks.contains(cb)) { sUiCallbacks.add(cb); }
        }
        DownloadEngine.State s = sLastState;
        if (s != null) {
            try { cb.onState(s); } catch (Throwable ignored) { }
        }
    }

    public static void unregisterUiCallback(DownloadEngine.Callback cb) {
        if (cb == null) { return; }
        synchronized (sUiCallbacks) { sUiCallbacks.remove(cb); }
    }

    /** 兼容旧调用：替换为唯一订阅者。 */
    public static void setUiCallback(DownloadEngine.Callback cb) {
        synchronized (sUiCallbacks) { sUiCallbacks.clear(); }
        if (cb != null) { registerUiCallback(cb); }
    }

    public static DownloadEngine.State lastState() {
        return sLastState;
    }

    public static void start(Context c, DownloadEngine.Config cfg) {
        sPendingConfig = cfg;
        // 同步入队并立即 emit：MainAct... startActivity 之前状态就已就绪，
        // 任务在 start() 中同步入队，UI 与通知读取同一份状态。
        engine(c).start(cfg);
        Intent i = new Intent(c, DownloadService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    public static void pause(Context c) {
        c.startService(new Intent(c, DownloadService.class).setAction(ACTION_PAUSE));
    }

    public static void resume(Context c) {
        c.startService(new Intent(c, DownloadService.class).setAction(ACTION_RESUME));
    }

    public static void cancel(Context c) {
        c.startService(new Intent(c, DownloadService.class).setAction(ACTION_CANCEL));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
        DownloadEngine.State s = sLastState;
        startForegroundSafe(buildNotification(s != null ? s : bootstrapState()));
        engine(this);                    // 保证引擎与 DISPATCH 已建立
        sServiceCallback = forwardCallback;
        DownloadEngine.State cur = sLastState;
        if (cur != null) { forwardCallback.onState(cur); }
    }

    private final DownloadEngine.Callback forwardCallback = new DownloadEngine.Callback() {
        @Override public void onState(final DownloadEngine.State s) {
            handler.post(new Runnable() {
                @Override public void run() {
                    if (running) { nm.notify(NOTIF_ID, buildNotification(s)); }
                    if (s.phase == DownloadEngine.PHASE_ALL_DONE) {
                        showFinishNotification(s);
                        finishForeground(s);
                    } else if (s.phase == DownloadEngine.PHASE_CANCELLED) {
                        finishForeground(s);
                    }
                }
            });
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        DownloadEngine e = engine(this);
        if (ACTION_PAUSE.equals(action)) {
            e.pause();
        } else if (ACTION_RESUME.equals(action)) {
            e.resume();
        } else if (ACTION_CANCEL.equals(action)) {
            e.cancel(true);
        } else if (ACTION_START.equals(action)) {
            // 任务已在 start() 里同步入队；这里只负责前台服务与 WakeLock，
            // 新任务追加进队列，保留当前状态快照。
            running = true;
            finishNotified = false;
            acquireWakeLock();
            DownloadEngine.State s = sLastState;
            if (s != null) { nm.notify(NOTIF_ID, buildNotification(s)); }
        }
        return START_NOT_STICKY;
    }

    /** 锁屏后继续下载：需用户在设置中开启，并关闭电池优化。 */
    private void acquireWakeLock() {
        releaseWakeLock();
        if (!Prefs.keepAlive(this)) { return; }
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "m3u8dl:download");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(6 * 60 * 60 * 1000L);
            }
        } catch (Exception ignored) { }
    }

    private void releaseWakeLock() {
        if (wakeLock != null) {
            try { if (wakeLock.isHeld()) { wakeLock.release(); } }
            catch (Exception ignored) { }
            wakeLock = null;
        }
    }

    /** 下载完成系统通知，可在「设置 → 下载」中关闭。 */
    private void showFinishNotification(DownloadEngine.State s) {
        if (finishNotified || !Prefs.finishNotify(this)) { return; }
        finishNotified = true;
        try {
            Intent open = new Intent(this, HistoryActivity.class)
                    .putExtra(HistoryActivity.EXTRA_TAB, HistoryActivity.TAB_DONE)
                    .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 1, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            String title = getString(R.string.notif_finish_title);
            String text = getString(R.string.notif_finish_text, s.successCount, s.failCount);
            Notification n = new Notification.Builder(this, CHANNEL_DONE)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(NOTIF_DONE_ID, n);
        } catch (Exception ignored) { }
    }

    private DownloadEngine.State bootstrapState() {
        DownloadEngine.State s = new DownloadEngine.State();
        s.phase = DownloadEngine.PHASE_RUNNING;
        s.status = "准备开始…";
        return s;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            // 清理历史通知渠道，保证角标行为一致
            try { nm.deleteNotificationChannel("m3u8dl_download"); }
            catch (Throwable ignored) { }
            try { nm.deleteNotificationChannel("m3u8dl_finish"); }
            catch (Throwable ignored) { }
            NotificationChannel ch = new NotificationChannel(CHANNEL, "下载进度",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("显示 M3U8 下载进度与速度");
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setShowBadge(false);   // 通知右上角不显示应用角标
            nm.createNotificationChannel(ch);
            NotificationChannel done = new NotificationChannel(CHANNEL_DONE, "下载完成",
                    NotificationManager.IMPORTANCE_DEFAULT);
            done.setDescription("全部文件下载完成后提醒");
            done.setShowBadge(false);
            nm.createNotificationChannel(done);
        }
    }

    private void startForegroundSafe(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            startForeground(NOTIF_ID, n);
        }
    }

    private void finishForeground(DownloadEngine.State s) {
        running = false;
        releaseWakeLock();
        try {
            Notification n = buildNotification(s);
            nm.notify(NOTIF_ID, n);
            stopForeground(false);
        } catch (Exception ignored) { }
        stopSelf();
    }

    private Notification buildNotification(DownloadEngine.State s) {
        boolean detailed = Prefs.notifyEnabled(this);
        String title;
        if (s.phase == DownloadEngine.PHASE_ALL_DONE) {
            title = "下载已结束：成功 " + s.successCount + " 个，失败 " + s.failCount + " 个";
        } else if (s.phase == DownloadEngine.PHASE_CANCELLED) {
            title = "下载已取消";
        } else if (s.phase == DownloadEngine.PHASE_PAUSED) {
            title = "下载已暂停";
        } else {
            title = "M3U8 下载中";
        }
        StringBuilder text = new StringBuilder();
        int active = 0, doneCount = 0;
        if (s.files != null) {
            for (DownloadEngine.FileState f : s.files) {
                if (f.phase == DownloadEngine.PHASE_RUNNING
                        || f.phase == DownloadEngine.PHASE_PAUSED
                        || f.phase == DownloadEngine.PHASE_QUEUED) { active++; }
                if (f.phase == DownloadEngine.PHASE_DONE) { doneCount++; }
            }
        }
        if (active > 0) {
            text.append("进行中 ").append(active).append(" 个");
            if (doneCount > 0) { text.append("  ·  已完成 ").append(doneCount).append(" 个"); }
        } else {
            text.append(s.status == null ? "" : stripPrefix(s.status));
        }
        if (detailed && active == 1 && s.files != null) {
            for (DownloadEngine.FileState f : s.files) {
                if (f.phase == DownloadEngine.PHASE_RUNNING
                        || f.phase == DownloadEngine.PHASE_PAUSED) {
                    text.append("  ·  ").append(f.percent).append("%");
                    if (f.speed > 0) {
                        text.append("  ·  ").append(DownloadEngine.formatSpeed(f.speed));
                    }
                    if (f.etaMs > 0) {
                        text.append("  ·  剩余 ").append(DownloadEngine.formatEta(f.etaMs));
                    }
                    break;
                }
            }
        }

        Intent open = new Intent(this, HistoryActivity.class)
                .putExtra(HistoryActivity.EXTRA_TAB, HistoryActivity.TAB_ACTIVE)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text.toString())
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setAutoCancel(s.phase == DownloadEngine.PHASE_ALL_DONE
                        || s.phase == DownloadEngine.PHASE_CANCELLED);

        boolean ongoing = s.phase == DownloadEngine.PHASE_RUNNING
                || s.phase == DownloadEngine.PHASE_PAUSED;
        if (ongoing) {
            b.setOngoing(true);
            if (detailed && s.files != null && !s.files.isEmpty()) {
                int sum = 0;
                for (DownloadEngine.FileState f : s.files) { sum += f.percent; }
                b.setProgress(100 * s.files.size(), sum, false);
            }
            if (s.phase == DownloadEngine.PHASE_PAUSED) {
                b.addAction(android.R.drawable.ic_media_play, "继续", actionPi(ACTION_RESUME, 2));
            } else {
                b.addAction(android.R.drawable.ic_media_pause, "暂停", actionPi(ACTION_PAUSE, 1));
            }
            b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "放弃",
                    actionPi(ACTION_CANCEL, 3));
        }
        return b.build();
    }

    private PendingIntent actionPi(String action, int req) {
        return PendingIntent.getService(this, req,
                new Intent(this, DownloadService.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static String stripPrefix(String s) {
        if (s == null) { return ""; }
        return s.replaceFirst("^\\[\\d+/\\d+]\\s*", "");
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        // 只注销自己的通知回调；绝不清空引擎回调，否则后续任务状态会全部丢失。
        if (sServiceCallback == forwardCallback) { sServiceCallback = null; }
        super.onDestroy();
    }
}
