package com.vloader.app;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 下载前的在线预览：不写入任何文件；批量时可切换上一个/下一个。
 *
 * 播放器 UI 全部自绘，不依赖系统播放器控件：
 *  - 画面用 TextureView + MediaPlayer，进度条/按钮/时间/提示全部自己画；
 *  - 控制条：播放暂停、后退/前进、自绘进度（可拖动 seek）、倍速、全屏；
 *  - 倍速：点击「倍速」在 0.5/1.0/1.5/2.0/3.0x 间切换；长按画面临时 3.0x，松手恢复；
 *  - 全屏：隐藏其它 UI 与系统栏，让画面铺满整屏；返回键或按钮退出全屏。
 */
public final class PreviewActivity extends BaseActivity {

    public static final String EXTRA_URLS = "urls";
    public static final String EXTRA_INDEX = "index";
    public static final String EXTRA_URL = "url"; // 兼容旧调用

    private static final float[] SPEEDS = {0.5f, 1.0f, 1.5f, 2.0f, 3.0f};
    private static final long BOOST_DELAY = 300L;

    private ArrayList<String> urls = new ArrayList<>();
    private int index;

    private LinearLayout mainUi;
    private FrameLayout stageHolder;
    private FrameLayout stage;
    private RatioTextureView texture;
    private Surface surface;
    private boolean surfaceReady;
    /**  记录视频原始宽高比，全屏时按比例 fitCenter，绝不拉伸画面。 */
    private float videoRatio = 0f;

    private TextView status;
    private TextView counter;
    private TextView curTime;
    private TextView totalTime;
    private TextView speedBtn;
    private TextView boostHint;
    private TextView buffering;
    private Button prev;
    private Button next;
    private FrameLayout playBox;
    private FrameLayout fsBox;
    private View playIcon;
    private View pauseIcon;
    private View fsIcon;
    private View fsExitIcon;
    private Track track;

    private MediaPlayer mp;
    private boolean prepared;
    private boolean fullscreen;
    private boolean boostActive;
    private int speedIdx = 1;                 // 默认 1.0x
    private float boostSavedSpeed = 1f;
    private String pendingUrl;
    private int stageNormalH;
    private final int[] padSave = new int[4];

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updateProgress();
            ui.postDelayed(this, 500L);
        }
    };
    private final Runnable boostRun = new Runnable() {
        @Override public void run() {
            boostActive = true;
            boostSavedSpeed = SPEEDS[speedIdx];
            applySpeed(3.0f);
            //  长按时不显示「3.0X」字样：功能保留，只把提示隐藏，不出现倍速数字
            boostHint.setVisibility(View.GONE);
        }
    };
    /** / 松手后短暂显示「已恢复原速」，随后隐藏（不再恢复「长按加速」字样）。 */
    private final Runnable resumeHint = new Runnable() {
        @Override public void run() {
            boostHint.setVisibility(View.GONE);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ArrayList<String> list = getIntent().getStringArrayListExtra(EXTRA_URLS);
        if (list != null && !list.isEmpty()) {
            urls = list;
        } else {
            String single = getIntent().getStringExtra(EXTRA_URL);
            if (single != null && !single.isEmpty()) {
                urls.add(single);
            }
        }
        index = getIntent().getIntExtra(EXTRA_INDEX, 0);
        if (index < 0 || index >= urls.size()) {
            index = 0;
        }
        buildUi();
        playCurrent();
    }

    /** 预览是子页，返回应回到主页而非直接退出应用。 */
    @Override
    protected boolean exitOnBack() {
        return false;
    }

    /** 全屏时按系统返回键先退出全屏，而不是直接离开预览。 */
    @Override
    protected boolean onHandleBack() {
        if (fullscreen) {
            setFullscreen(false);
            return true;
        }
        return super.onHandleBack();
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        mainUi = new LinearLayout(this);
        mainUi.setOrientation(LinearLayout.VERTICAL);
        mainUi.setPadding(th.dp(this, 16), th.dp(this, 8),
                th.dp(this, 16), th.dp(this, 16));
        EdgeInset.applyTop(this, mainUi, 8, 10);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        //  返回键跟随文字色（浅色=黑、深色=白），不再用主题蓝
        View back = Icons.of(this, Icons.ARROW_LEFT, th.text, 24);
        back.setPadding(th.dp(this, 4), th.dp(this, 8),
                th.dp(this, 12), th.dp(this, 8));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        header.addView(back);
        TextView title = new TextView(this);
        title.setText(R.string.preview_title);
        title.setTextSize(18);
        title.setTextColor(th.text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        title.setGravity(Gravity.CENTER);
        header.addView(title);
        counter = th.caption(this, "");
        header.addView(counter);
        mainUi.addView(header, th.matchWidth());
        mainUi.addView(th.space(this, 14));

        // 视频舞台：全屏时只放大这个容器，TextureView 不被 detach，Surface 不重建
        stageNormalH = th.dp(this, 230);
        stageHolder = new FrameLayout(this);
        stageHolder.setBackground(th.shape(0xFF000000, th.dp(this, th.cardRadius)));
        stage = new FrameLayout(this);

        //  画面按视频原始宽高比 fitCenter 显示，全屏/横屏都不拉伸
        texture = new RatioTextureView(this);
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                surface = new Surface(st);
                surfaceReady = true;
                if (pendingUrl != null) {
                    startPlayback(pendingUrl);
                }
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                surfaceReady = false;
                if (surface != null) { surface.release(); surface = null; }
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
        FrameLayout.LayoutParams viewLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER);
        stage.addView(texture, viewLp);

        // 手势层：长按画面临时 3 倍速；短按切换播放/暂停
        View touch = new View(this);
        touch.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        ui.removeCallbacks(boostRun);
                        ui.postDelayed(boostRun, BOOST_DELAY);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        ui.removeCallbacks(boostRun);
                        if (boostActive) {
                            boostActive = false;
                            applySpeed(boostSavedSpeed);
                            //  松手显示「已恢复原速」，稍后再恢复常驻提示
                            boostHint.setText(getString(R.string.preview_boost_resume));
                            boostHint.setTextColor(0xFFFFFFFF);
                            boostHint.setVisibility(View.VISIBLE);
                            ui.removeCallbacks(resumeHint);
                            ui.postDelayed(resumeHint, 900L);
                        } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                            togglePlayPause();
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });
        stage.addView(touch, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // 自绘进度条与底部控制条
        track = new Track(this, th.primary, Math.max(2, th.dp(this, 4)));
        track.setOnSeek(new Track.OnSeek() {
            @Override public void seek(float frac) {
                if (mp != null && prepared) {
                    int d = mp.getDuration();
                    if (d > 0) { mp.seekTo((int) (d * frac)); }
                }
            }
        });
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setBackgroundColor(0x99000000);
        controls.setPadding(th.dp(this, 8), th.dp(this, 6), th.dp(this, 8), th.dp(this, 6));

        playBox = iconToggle(Icons.PLAY, Icons.PAUSE, 26);
        playIcon = playBox.getChildAt(0);
        pauseIcon = playBox.getChildAt(1);
        playBox.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { togglePlayPause(); }
        });
        controls.addView(playBox, new LinearLayout.LayoutParams(
                th.dp(this, 34), th.dp(this, 34)));

        curTime = smallTime(getString(R.string.preview_time_zero));
        controls.addView(curTime);

        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                th.dp(this, 34), 1f);
        tlp.leftMargin = th.dp(this, 6);
        tlp.rightMargin = th.dp(this, 6);
        controls.addView(track, tlp);

        totalTime = smallTime(getString(R.string.preview_time_zero));
        controls.addView(totalTime);

        speedBtn = new TextView(this);
        speedBtn.setText(speedLabel());
        speedBtn.setTextColor(0xFFFFFFFF);
        speedBtn.setTextSize(13);
        speedBtn.setPadding(th.dp(this, 8), th.dp(this, 4),
                th.dp(this, 8), th.dp(this, 4));
        speedBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { chooseSpeed(); }
        });
        controls.addView(speedBtn);

        fsBox = iconToggle(Icons.FULLSCREEN, Icons.FULLSCREEN_EXIT, 24);
        fsIcon = fsBox.getChildAt(0);
        fsExitIcon = fsBox.getChildAt(1);
        fsExitIcon.setVisibility(View.GONE);   // 初始非全屏：只显示「进入全屏」
        fsBox.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setFullscreen(!fullscreen); }
        });
        controls.addView(fsBox, new LinearLayout.LayoutParams(
                th.dp(this, 32), th.dp(this, 32)));

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        stage.addView(controls, clp);

        buffering = hintText(getString(R.string.preview_buffering));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        stage.addView(buffering, blp);
        buffering.setVisibility(View.GONE);

        //  不再显示初始「长按加速」提示字样（长按倍速 / 松手恢复功能照常保留）；
        // 仅在松手时短暂提示「已恢复原速」（见 release 分支），默认隐藏。
        boostHint = hintText(getString(R.string.preview_boost_resume));
        boostHint.setTextColor(0xCCFFFFFF);
        boostHint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hlp.topMargin = th.dp(this, 8);
        stage.addView(boostHint, hlp);

        stageHolder.addView(stage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        mainUi.addView(stageHolder, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, stageNormalH));
        mainUi.addView(th.space(this, 14));

        status = th.body(this, getString(R.string.preview_connecting));
        status.setTextSize(14);
        mainUi.addView(status, th.matchWidth());
        mainUi.addView(th.space(this, 14));

        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        prev = new Button(this);
        prev.setText(R.string.preview_prev);
        th.styleSecondary(prev, this);
        prev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchTo(index - 1); }
        });
        switchRow.addView(prev, weighted(1));
        next = new Button(this);
        next.setText(R.string.preview_next);
        th.styleSecondary(next, this);
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchTo(index + 1); }
        });
        switchRow.addView(next, weighted(1));
        mainUi.addView(switchRow, th.matchWidth());
        mainUi.addView(th.space(this, 8));

        Button external = new Button(this);
        external.setText(R.string.preview_external);
        th.styleSecondary(external, this);
        external.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openExternal(); }
        });
        mainUi.addView(external, th.matchWidth());

        // 二级页不挂悬浮底栏（Navigation bar 仅用于 3 个平级主页面）
        setContentView(mainUi);
        padSave[0] = mainUi.getPaddingLeft();
        padSave[1] = mainUi.getPaddingTop();
        padSave[2] = mainUi.getPaddingRight();
        padSave[3] = mainUi.getPaddingBottom();
        updatePlayBtn();
    }

    /** 两个图标叠在一起、按需切换可见性的按钮盒。 */
    private FrameLayout iconToggle(int idA, int idB, int dp) {
        FrameLayout box = new FrameLayout(this);
        int px = th.dp(this, dp);
        View a = Icons.of(this, idA, 0xFFFFFFFF, dp);
        a.setLayoutParams(new FrameLayout.LayoutParams(px, px, Gravity.CENTER));
        View b = Icons.of(this, idB, 0xFFFFFFFF, dp);
        b.setLayoutParams(new FrameLayout.LayoutParams(px, px, Gravity.CENTER));
        box.addView(a);
        box.addView(b);
        return box;
    }

    private TextView smallTime(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(12);
        t.setSingleLine(true);
        return t;
    }

    private TextView hintText(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xCCFFFFFF);
        t.setTextSize(12);
        t.setPadding(th.dp(this, 10), th.dp(this, 5),
                th.dp(this, 10), th.dp(this, 5));
        t.setBackground(th.shape(0x66000000, th.dp(this, 14)));
        return t;
    }

    private LinearLayout.LayoutParams weighted(int w) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, w);
        lp.rightMargin = th.dp(this, 6);
        return lp;
    }

    // ---------------------------------------------------------------- 播放

    private void switchTo(int i) {
        if (i < 0 || i >= urls.size()) {
            toast(getString(R.string.preview_no_more));
            return;
        }
        index = i;
        playCurrent();
    }

    private void playCurrent() {
        int total = urls.size();
        boolean multi = total > 1;
        counter.setText(multi ? getString(R.string.preview_counter, index + 1, total) : "");
        prev.setEnabled(index > 0);
        next.setEnabled(index < total - 1);
        prev.setAlpha(index > 0 ? 1f : 0.45f);
        next.setAlpha(index < total - 1 ? 1f : 0.45f);
        prev.setVisibility(multi ? View.VISIBLE : View.GONE);
        next.setVisibility(multi ? View.VISIBLE : View.GONE);
        if (total == 0) {
            status.setText(getString(R.string.preview_empty));
            return;
        }
        status.setText(getString(R.string.preview_connecting));
        pendingUrl = urls.get(index);
        if (surfaceReady) {
            startPlayback(pendingUrl);
        }
    }

    private void startPlayback(String url) {
        buffering.setVisibility(View.VISIBLE);
        ui.removeCallbacks(resumeHint);
        //  切集/重播时隐藏提示字样，不显示「长按加速」
        boostHint.setVisibility(View.GONE);
        videoRatio = 0f;   // 新源先复位，避免沿用上一个视频的宽高比
        if (texture != null) { texture.setRatio(0f); }
        try {
            if (mp == null) {
                mp = new MediaPlayer();
            } else {
                mp.reset();
            }
            prepared = false;
            if (surface != null) { mp.setSurface(surface); }
            mp.setDataSource(this, Uri.parse(url));
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer p) {
                    prepared = true;
                    buffering.setVisibility(View.GONE);
                    status.setText(getString(R.string.preview_playing));
                    //  拿到视频真实宽高，按比例 fitCenter；全屏/横屏都不拉伸
                    int vw = p.getVideoWidth(), vh = p.getVideoHeight();
                    if (vw > 0 && vh > 0) {
                        videoRatio = vw / (float) vh;
                        if (texture != null) { texture.setRatio(videoRatio); }
                    }
                    p.start();
                    applySpeed(SPEEDS[speedIdx]);
                    updatePlayBtn();
                    updateProgress();
                    ui.removeCallbacks(ticker);
                    ui.post(ticker);
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer p, int what, int extra) {
                    buffering.setVisibility(View.GONE);
                    status.setText(getString(R.string.preview_error, what));
                    return true;
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer p) { updatePlayBtn(); }
            });
            mp.prepareAsync();
        } catch (Exception e) {
            buffering.setVisibility(View.GONE);
            status.setText(getString(R.string.preview_error, 0));
        }
    }

    private void togglePlayPause() {
        if (mp == null || !prepared) { return; }
        try {
            if (mp.isPlaying()) {
                mp.pause();
            } else {
                mp.start();
                applySpeed(boostActive ? 3.0f : SPEEDS[speedIdx]);
            }
        } catch (Exception ignored) { }
        updatePlayBtn();
    }

    private void updatePlayBtn() {
        boolean playing = mp != null && prepared && isPlayingSafe();
        if (playIcon != null) { playIcon.setVisibility(playing ? View.GONE : View.VISIBLE); }
        if (pauseIcon != null) { pauseIcon.setVisibility(playing ? View.VISIBLE : View.GONE); }
    }

    private boolean isPlayingSafe() {
        try { return mp != null && mp.isPlaying(); }
        catch (Exception e) { return false; }
    }

    private void updateProgress() {
        if (mp == null || !prepared) { return; }
        try {
            int dur = mp.getDuration();
            int pos = mp.getCurrentPosition();
            if (dur > 0) { track.setFrac(pos / (float) dur); }
            curTime.setText(fmtTime(pos));
            totalTime.setText(dur > 0 ? fmtTime(dur)
                    : getString(R.string.preview_time_zero));
        } catch (Exception ignored) { }
    }

    private static String fmtTime(int ms) {
        if (ms < 0) { ms = 0; }
        int s = ms / 1000;
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    // ---------------------------------------------------------------- 倍速

    private String speedLabel() {
        return getString(R.string.preview_speed_item, String.valueOf(SPEEDS[speedIdx]));
    }

    private void chooseSpeed() {
        String[] labels = new String[SPEEDS.length];
        for (int i = 0; i < SPEEDS.length; i++) {
            labels[i] = getString(R.string.preview_speed_item, String.valueOf(SPEEDS[i]));
        }
        AppDialog.with(this, th)
                .title(getString(R.string.preview_speed_title))
                .singleChoice(labels, speedIdx, new AppDialog.OnItemListener() {
                    @Override public void onItem(int which) {
                        speedIdx = which;
                        boostSavedSpeed = SPEEDS[which];
                        if (!boostActive) { applySpeed(SPEEDS[which]); }
                        speedBtn.setText(speedLabel());
                    }
                })
                .negative(getString(R.string.btn_cancel), null)
                .show();
    }

    /** 用 MediaPlayer.setPlaybackParams 设置倍速，并对设备差异做兼容处理。 */
    private void applySpeed(float s) {
        if (mp == null || !prepared) { return; }
        try {
            PlaybackParams pp = new PlaybackParams();
            pp.setSpeed(s);
            pp.setPitch(1.0f);
            mp.setPlaybackParams(pp);
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- 全屏

    private void setFullscreen(boolean on) {
        fullscreen = on;
        for (int i = 0; i < mainUi.getChildCount(); i++) {
            View c = mainUi.getChildAt(i);
            if (c != stageHolder) {
                c.setVisibility(on ? View.GONE : View.VISIBLE);
            }
        }
        if (on) {
            stageHolder.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            mainUi.setPadding(0, 0, 0, 0);
        } else {
            stageHolder.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, stageNormalH));
            mainUi.setPadding(padSave[0], padSave[1], padSave[2], padSave[3]);
        }
        fsIcon.setVisibility(on ? View.GONE : View.VISIBLE);
        fsExitIcon.setVisibility(on ? View.VISIBLE : View.GONE);
        applyFullscreenDecor(on);
        //  横屏视频在全屏时自动旋转为横屏；退出全屏恢复跟随系统。
        // 清单已声明 configChanges=orientation，旋转不重建 Activity，播放不中断。
        if (on && videoRatio > 1f) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
    }

    private void applyFullscreenDecor(boolean on) {
        View d = getWindow().getDecorView();
        if (on) {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        } else {
            d.setSystemUiVisibility(0);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
    }

    // ---------------------------------------------------------------- 其它

    private void openExternal() {
        if (urls.isEmpty()) { return; }
        try {
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setDataAndType(Uri.parse(urls.get(index)), "video/*");
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(it, getString(R.string.preview_external)));
        } catch (Exception e) {
            toast(getString(R.string.err_no_browser));
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacks(ticker);
        ui.removeCallbacks(boostRun);
        ui.removeCallbacks(resumeHint);
        if (boostActive) {
            boostActive = false;
            applySpeed(boostSavedSpeed);
        }
        if (mp != null && isPlayingSafe()) {
            try { mp.pause(); } catch (Exception ignored) { }
        }
        updatePlayBtn();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(ticker);
        ui.removeCallbacks(boostRun);
        ui.removeCallbacks(resumeHint);
        if (mp != null) {
            try { mp.reset(); } catch (Exception ignored) { }
            try { mp.release(); } catch (Exception ignored) { }
            mp = null;
        }
        if (surface != null) { surface.release(); surface = null; }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- 比例画面

    /**
     *  按视频原始宽高比 fitCenter 的 TextureView：全屏或旋转后都保持比例、
     * 只做等比缩放并居中，四周留黑边，绝不无视比例把画面拉长。
     */
    private static final class RatioTextureView extends TextureView {
        private float ratio = 0f;   // 宽 / 高；<=0 表示未知，按容器测量

        RatioTextureView(android.content.Context c) {
            super(c);
        }

        void setRatio(float r) {
            if (ratio != r) {
                ratio = r;
                requestLayout();
            }
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int w = View.MeasureSpec.getSize(widthSpec);
            int h = View.MeasureSpec.getSize(heightSpec);
            if (ratio > 0f && w > 0 && h > 0) {
                // 等比缩放，使整个画面刚好放进容器（letterbox）
                if (w / (float) h > ratio) {
                    w = Math.round(h * ratio);      // 容器偏宽：按高撑满，两侧留黑
                } else {
                    h = Math.round(w / ratio);      // 容器偏高：按宽撑满，上下留黑
                }
                setMeasuredDimension(
                        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            } else {
                super.onMeasure(widthSpec, heightSpec);
            }
        }
    }

    // ---------------------------------------------------------------- 自绘进度条

    /** 完全自绘的播放进度条：轨道 + 已播放段 + 圆点，支持按下/拖动 seek。 */
    private static final class Track extends View {
        interface OnSeek {
            void seek(float frac);
        }

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int accent;
        private final int barPx;
        private float frac;
        private OnSeek cb;

        Track(android.content.Context c, int accent, int barPx) {
            super(c);
            this.accent = accent;
            this.barPx = barPx;
            setClickable(true);
        }

        void setOnSeek(OnSeek s) { this.cb = s; }

        void setFrac(float f) {
            frac = Math.max(0f, Math.min(1f, f));
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            super.onDraw(cv);
            int w = getWidth(), h = getHeight();
            float cy = h / 2f;
            float half = barPx / 2f;
            p.setColor(0x55FFFFFF);
            cv.drawRoundRect(0, cy - half, w, cy + half, half, half, p);
            p.setColor(accent);
            float x = w * frac;
            cv.drawRoundRect(0, cy - half, x, cy + half, half, half, p);
            p.setColor(Color.WHITE);
            cv.drawCircle(x, cy, half * 1.6f, p);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            int act = e.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_MOVE) {
                int w = getWidth();
                float f = w > 0 ? e.getX() / w : 0f;
                f = Math.max(0f, Math.min(1f, f));
                setFrac(f);
                if (cb != null) { cb.seek(f); }
                return true;
            }
            if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                return true;
            }
            return super.onTouchEvent(e);
        }
    }
}
