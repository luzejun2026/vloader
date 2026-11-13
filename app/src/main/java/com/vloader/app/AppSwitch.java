package com.vloader.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Checkable;

/**
 * 自绘开关，替代系统 Switch（系统 Switch 会带出 MD2/Holo 视觉）。
 * 规格按参考图：
 *  - MD3 开：实心主色胶囊轨道 + 白色大圆滑块在右；关：浅灰底 + 灰色描边 + 灰色小滑块在左。
 *  - MIUI 开：亮蓝实心胶囊轨道 + 白色圆滑块在右；关：浅灰实心胶囊 + 白圆滑块在左。
 * 用插值动画在开/关之间平滑过渡（轨道颜色、描边、滑块大小与位置）。
 */
public final class AppSwitch extends View implements Checkable {

    public interface OnCheckedChangeListener {
        void onCheckedChanged(AppSwitch v, boolean checked);
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();

    private final boolean miui;
    private final boolean dark;
    private final int primary;
    private final int trackOn;
    private final int trackOff;
    private final int strokeOff;
    private final int thumbOn;
    private final int thumbOff;
    private final float w, h, radius;

    private boolean checked;
    private float fraction;               // 0=关, 1=开
    private OnCheckedChangeListener listener;
    private ValueAnimator animator;

    public AppSwitch(Context c, UiTheme th) {
        super(c);
        this.miui = th.miui;
        this.dark = th.dark;
        this.primary = th.primary;
        // MD3 用主题主色（靛蓝），MIUI 用固定亮蓝。
        //  深色 + Material Design 3 开关「打开态」按参考图改配色：
        // 浅蓝轨道 #9ACEEC + 深蓝圆点 #133444（仅 dark && !miui 生效，其余主题不变）。
        if (dark && !miui) {
            this.trackOn = 0xFF9ACEEC;
            this.thumbOn = 0xFF133444;
        } else {
            this.trackOn = miui ? 0xFF5B8CFF : th.primary;
            this.thumbOn = 0xFFFFFFFF;
        }
        this.trackOff = dark ? 0xFF3A3A40 : (miui ? 0xFFE3E3E8 : 0xFFE6E1E7);
        this.strokeOff = dark ? 0xFF8A8A90 : 0xFF79747E;
        this.thumbOff = miui ? 0xFFFFFFFF : (dark ? 0xFFB9B9C0 : 0xFF79747E);

        float density = c.getResources().getDisplayMetrics().density;
        this.w = 52 * density;
        this.h = 32 * density;
        this.radius = h / 2f;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2 * density);
        setClickable(true);
        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                toggle();
            }
        });
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension((int) w, (int) h);
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        float f = fraction;
        track.set(0, 0, w, h);

        // 轨道颜色：关→开插值
        fill.setColor(blend(trackOff, trackOn, f));
        cv.drawRoundRect(track, radius, radius, fill);

        // MD3 关闭态的灰色描边，随打开淡出
        if (!miui) {
            int a = (int) (Color.alpha(strokeOff) * (1f - f));
            stroke.setColor(Color.argb(a, Color.red(strokeOff),
                    Color.green(strokeOff), Color.blue(strokeOff)));
            float inset = stroke.getStrokeWidth() / 2f;
            RectF inner = new RectF(inset, inset, w - inset, h - inset);
            cv.drawRoundRect(inner, radius - inset, radius - inset, stroke);
        }

        // 滑块：MD3 关小开大；MIUI 恒定大圆
        float density = getResources().getDisplayMetrics().density;
        float smallR = 8 * density;    // 直径 16dp
        float bigR = miui ? 13 * density : 12 * density; // MIUI 26dp / MD3 24dp
        float r = miui ? bigR : (smallR + (bigR - smallR) * f);

        float pad = 4 * density;
        float leftX = pad + smallR + (h / 2f - pad - smallR) * 0f + (bigR - smallR) * 0f;
        // 用统一中心：关闭时靠左、打开时靠右
        float minCx = h / 2f;
        float maxCx = w - h / 2f;
        float cx = minCx + (maxCx - minCx) * f;
        float cy = h / 2f;
        thumbPaint.setColor(blend(thumbOff, thumbOn, f));
        cv.drawCircle(cx, cy, r, thumbPaint);
    }

    private static int blend(int from, int to, float f) {
        f = Math.max(0f, Math.min(1f, f));
        return Color.argb(
                (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f),
                (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * f),
                (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * f),
                (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f));
    }

    @Override
    public void setChecked(boolean b) {
        setChecked(b, true);
    }

    public void setChecked(boolean b, boolean animate) {
        boolean changed = checked != b;
        this.checked = b;
        float target = b ? 1f : 0f;
        if (animator != null) {
            animator.cancel();
        }
        if (!animate || !isAttachedToWindow()) {
            fraction = target;
            invalidate();
        } else {
            animator = ValueAnimator.ofFloat(fraction, target);
            animator.setDuration(200);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    fraction = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            animator.start();
        }
        if (changed && listener != null) {
            listener.onCheckedChanged(this, b);
        }
    }

    @Override
    public boolean isChecked() {
        return checked;
    }

    @Override
    public void toggle() {
        setChecked(!checked);
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener l) {
        this.listener = l;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        fraction = checked ? 1f : 0f;
        invalidate();
    }
}
