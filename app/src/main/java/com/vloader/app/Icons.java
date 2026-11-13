package com.vloader.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.widget.LinearLayout;

/**
 * 简洁现代的线性矢量图标：全部用 Canvas 路径绘制，不使用 emoji、不使用文字字形。
 * 统一 24x24 坐标系，1.7 线宽、圆角线帽，随主题主色着色。
 */
public final class Icons {

    public static final int APPEARANCE = 0;
    public static final int DOWNLOAD = 1;
    public static final int FOLDER = 2;
    public static final int TUNE = 3;
    public static final int INFO = 4;
    public static final int THREADS = 5;
    public static final int RETRY = 6;
    public static final int HEADERS = 7;
    public static final int BELL = 8;
    public static final int BACK_GESTURE = 9;
    public static final int LIQUID = 10;
    public static final int QQ = 11;
    public static final int BILI = 12;
    public static final int OPENER = 13;
    public static final int TRASH = 14;
    public static final int FILE = 15;
    public static final int CHECK = 16;
    public static final int UPDOWN = 17;
    public static final int NEW_FOLDER = 18;
    public static final int HOME = 19;
    public static final int CLOCK = 20;
    public static final int GEAR = 21;
    public static final int MOON = 22;
    public static final int PLAY = 23;
    public static final int FILM = 24;
    public static final int ARROW_LEFT = 25;
    public static final int PLUS = 26;
    public static final int VERSION = 27;
    public static final int CHEVRON_RIGHT = 28;
    public static final int PALETTE = 29;
    public static final int PAUSE = 30;            // 自绘播放器：暂停（两条竖线）
    public static final int FULLSCREEN = 31;       // 自绘播放器：进入全屏（四角外扩）
    public static final int FULLSCREEN_EXIT = 32;  // 自绘播放器：退出全屏（四角内收）

    /** 返回一个已按 dp 设好尺寸的图标 View（供 LinearLayout 行使用）。 */
    public static View of(Context c, int id, int color, int dp) {
        int px = (int) (dp * c.getResources().getDisplayMetrics().density + 0.5f);
        IconView v = new IconView(c, id, color);
        v.setLayoutParams(new LinearLayout.LayoutParams(px, px));
        return v;
    }

    /** 运行时改色：液态玻璃底栏按下方画面亮度做前景自适应反色时用。 */
    public static void tint(View v, int color) {
        if (v instanceof IconView) {
            IconView iv = (IconView) v;
            iv.p.setColor(color);
            iv.fill.setColor(color);
            iv.invalidate();
        }
    }

    static final class IconView extends View {
        final int id;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        final RectF oval = new RectF();

        IconView(Context c, int id, int color) {
            super(c);
            this.id = id;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(color);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(color);
        }

        @Override
        protected void onDraw(Canvas cv) {
            super.onDraw(cv);
            int w = getWidth(), h = getHeight();
            float s = Math.min(w, h) / 24f;
            cv.save();
            cv.translate((w - 24 * s) / 2f, (h - 24 * s) / 2f);
            cv.scale(s, s);
            p.setStrokeWidth(1.7f);
            render(cv);
            cv.restore();
        }

        private void line(Canvas cv, float x1, float y1, float x2, float y2) {
            cv.drawLine(x1, y1, x2, y2, p);
        }

        private void circle(Canvas cv, float x, float y, float r) {
            cv.drawCircle(x, y, r, p);
        }

        private void dot(Canvas cv, float x, float y, float r) {
            cv.drawCircle(x, y, r, fill);
        }

        private void round(Canvas cv, float l, float t, float r, float b, float rad) {
            cv.drawRoundRect(l, t, r, b, rad, rad, p);
        }

        private void render(Canvas cv) {
            switch (id) {
                case APPEARANCE:
                case PALETTE: {
                    path.reset();
                    path.moveTo(12, 3);
                    path.cubicTo(7, 3, 3, 6.6f, 3, 11.5f);
                    path.cubicTo(3, 16.5f, 7, 20, 11, 20);
                    path.cubicTo(12.6f, 20, 13.6f, 18.9f, 13.6f, 17.5f);
                    path.cubicTo(13.6f, 16.2f, 14.7f, 15.2f, 16.2f, 15.2f);
                    path.cubicTo(19, 15.2f, 21, 13.4f, 21, 10.6f);
                    path.cubicTo(21, 6.2f, 17, 3, 12, 3);
                    path.close();
                    cv.drawPath(path, p);
                    dot(cv, 7.6f, 11, 1.25f);
                    dot(cv, 10f, 7.2f, 1.25f);
                    dot(cv, 15f, 7.6f, 1.25f);
                    break;
                }
                case DOWNLOAD:
                    line(cv, 12, 3, 12, 14);
                    line(cv, 8, 11, 12, 15);
                    line(cv, 16, 11, 12, 15);
                    line(cv, 4, 19.5f, 20, 19.5f);
                    break;
                case FOLDER:
                case NEW_FOLDER: {
                    path.reset();
                    path.moveTo(3, 8);
                    path.lineTo(9, 8);
                    path.lineTo(11, 10);
                    path.lineTo(21, 10);
                    path.lineTo(21, 19);
                    path.lineTo(3, 19);
                    path.close();
                    cv.drawPath(path, p);
                    if (id == NEW_FOLDER) {
                        line(cv, 12, 12.5f, 12, 17.5f);
                        line(cv, 9.5f, 15, 14.5f, 15);
                    }
                    break;
                }
                case TUNE:
                    line(cv, 3, 7, 21, 7);
                    line(cv, 3, 12, 21, 12);
                    line(cv, 3, 17, 21, 17);
                    dot(cv, 9, 7, 1.9f);
                    dot(cv, 16, 12, 1.9f);
                    dot(cv, 8, 17, 1.9f);
                    break;
                case INFO:
                case VERSION:
                    circle(cv, 12, 12, 9);
                    if (id == VERSION) {
                        line(cv, 8.5f, 9, 12, 16);
                        line(cv, 12, 16, 15.5f, 9);
                    } else {
                        line(cv, 12, 11, 12, 16.5f);
                        dot(cv, 12, 7.6f, 1.1f);
                    }
                    break;
                case THREADS:
                    line(cv, 3, 7, 21, 7);
                    line(cv, 3, 12, 21, 12);
                    line(cv, 3, 17, 21, 17);
                    break;
                case RETRY:
                    oval.set(4, 4, 20, 20);
                    cv.drawArc(oval, 30, 285, false, p);
                    line(cv, 4, 8, 4, 3.5f);
                    line(cv, 4, 3.5f, 8.5f, 3.5f);
                    break;
                case HEADERS:
                    line(cv, 9, 7, 4, 12);
                    line(cv, 4, 12, 9, 17);
                    line(cv, 15, 7, 20, 12);
                    line(cv, 20, 12, 15, 17);
                    break;
                case BELL: {
                    path.reset();
                    path.moveTo(6, 17);
                    path.cubicTo(6, 17, 5, 15, 5, 11.5f);
                    path.cubicTo(5, 7.5f, 8, 5, 12, 5);
                    path.cubicTo(16, 5, 19, 7.5f, 19, 11.5f);
                    path.cubicTo(19, 15, 18, 17, 18, 17);
                    path.close();
                    cv.drawPath(path, p);
                    line(cv, 10, 20, 14, 20);
                    dot(cv, 12, 3.6f, 1f);
                    break;
                }
                case BACK_GESTURE:
                    line(cv, 9, 7, 4, 12);
                    line(cv, 4, 12, 9, 17);
                    line(cv, 4, 12, 13, 12);
                    oval.set(11, 5, 21, 19);
                    cv.drawArc(oval, -70, 140, false, p);
                    break;
                case LIQUID: {
                    path.reset();
                    path.moveTo(12, 3);
                    path.cubicTo(12, 3, 5, 11, 5, 15);
                    path.cubicTo(5, 19, 8, 21, 12, 21);
                    path.cubicTo(16, 21, 19, 19, 19, 15);
                    path.cubicTo(19, 11, 12, 3, 12, 3);
                    path.close();
                    cv.drawPath(path, p);
                    break;
                }
                case QQ:
                    round(cv, 3, 5, 21, 17, 5);
                    line(cv, 7, 17, 6, 20.5f);
                    line(cv, 6, 20.5f, 10, 17.5f);
                    dot(cv, 9, 11, 1.1f);
                    dot(cv, 15, 11, 1.1f);
                    break;
                case BILI:
                    round(cv, 4, 8, 20, 18, 3);
                    line(cv, 8, 8, 6.5f, 4);
                    line(cv, 16, 8, 17.5f, 4);
                    dot(cv, 9, 12.6f, 1.05f);
                    dot(cv, 15, 12.6f, 1.05f);
                    break;
                case OPENER:
                    line(cv, 13, 11, 20, 4);
                    line(cv, 14.5f, 4, 20, 4);
                    line(cv, 20, 4, 20, 9.5f);
                    path.reset();
                    path.moveTo(19, 14);
                    path.lineTo(19, 19);
                    path.lineTo(5, 19);
                    path.lineTo(5, 7);
                    path.lineTo(10, 7);
                    cv.drawPath(path, p);
                    break;
                case TRASH:
                    line(cv, 4, 7, 20, 7);
                    line(cv, 9, 7, 9, 4.5f);
                    line(cv, 15, 7, 15, 4.5f);
                    line(cv, 9, 4.5f, 15, 4.5f);
                    path.reset();
                    path.moveTo(6, 7);
                    path.lineTo(7, 20);
                    path.lineTo(17, 20);
                    path.lineTo(18, 7);
                    cv.drawPath(path, p);
                    line(cv, 10, 10.5f, 10, 17);
                    line(cv, 14, 10.5f, 14, 17);
                    break;
                case FILE: {
                    path.reset();
                    path.moveTo(6, 3);
                    path.lineTo(14, 3);
                    path.lineTo(18, 7);
                    path.lineTo(18, 21);
                    path.lineTo(6, 21);
                    path.close();
                    cv.drawPath(path, p);
                    line(cv, 14, 3, 14, 7);
                    line(cv, 14, 7, 18, 7);
                    line(cv, 9, 12, 15, 12);
                    line(cv, 9, 16, 15, 16);
                    break;
                }
                case CHECK:
                    path.reset();
                    path.moveTo(5, 12.5f);
                    path.lineTo(10, 17);
                    path.lineTo(19, 6);
                    cv.drawPath(path, p);
                    break;
                case UPDOWN:
                    line(cv, 8, 10, 12, 6);
                    line(cv, 12, 6, 16, 10);
                    line(cv, 8, 14, 12, 18);
                    line(cv, 12, 18, 16, 14);
                    break;
                case HOME: {
                    path.reset();
                    path.moveTo(3, 11);
                    path.lineTo(12, 4);
                    path.lineTo(21, 11);
                    cv.drawPath(path, p);
                    path.reset();
                    path.moveTo(5.5f, 10);
                    path.lineTo(5.5f, 20);
                    path.lineTo(18.5f, 20);
                    path.lineTo(18.5f, 10);
                    cv.drawPath(path, p);
                    break;
                }
                case CLOCK:
                    circle(cv, 12, 12, 9);
                    line(cv, 12, 7, 12, 12);
                    line(cv, 12, 12, 16, 14.5f);
                    break;
                case GEAR:
                    circle(cv, 12, 12, 3.4f);
                    for (int i = 0; i < 8; i++) {
                        double a = i * Math.PI / 4;
                        float c = (float) Math.cos(a), s2 = (float) Math.sin(a);
                        line(cv, 12 + 6.2f * c, 12 + 6.2f * s2,
                                12 + 8.6f * c, 12 + 8.6f * s2);
                    }
                    break;
                case MOON: {
                    path.reset();
                    path.moveTo(15.5f, 3);
                    path.cubicTo(9.5f, 4, 6, 8, 6, 12);
                    path.cubicTo(6, 17, 10, 21, 15.5f, 21);
                    path.cubicTo(11.5f, 19, 9.5f, 16, 9.5f, 12);
                    path.cubicTo(9.5f, 8, 11.5f, 5, 15.5f, 3);
                    path.close();
                    cv.drawPath(path, p);
                    break;
                }
                case PLAY: {
                    path.reset();
                    path.moveTo(8, 5);
                    path.lineTo(8, 19);
                    path.lineTo(19, 12);
                    path.close();
                    cv.drawPath(path, p);
                    break;
                }
                case FILM:
                    round(cv, 3, 5, 21, 19, 2.5f);
                    line(cv, 8, 5, 8, 19);
                    line(cv, 16, 5, 16, 19);
                    dot(cv, 5.5f, 8, 0.9f);
                    dot(cv, 5.5f, 12, 0.9f);
                    dot(cv, 5.5f, 16, 0.9f);
                    break;
                case ARROW_LEFT:
                    line(cv, 11, 6, 4, 12);
                    line(cv, 4, 12, 11, 18);
                    line(cv, 4, 12, 20, 12);
                    break;
                case PLUS:
                    line(cv, 12, 5, 12, 19);
                    line(cv, 5, 12, 19, 12);
                    break;
                case CHEVRON_RIGHT:
                    line(cv, 9.5f, 5, 16, 12);
                    line(cv, 16, 12, 9.5f, 19);
                    break;
                case PAUSE:
                    line(cv, 9, 5, 9, 19);
                    line(cv, 15, 5, 15, 19);
                    break;
                case FULLSCREEN:
                    line(cv, 4, 9, 4, 4);
                    line(cv, 4, 4, 9, 4);
                    line(cv, 20, 9, 20, 4);
                    line(cv, 20, 4, 15, 4);
                    line(cv, 4, 15, 4, 20);
                    line(cv, 4, 20, 9, 20);
                    line(cv, 20, 15, 20, 20);
                    line(cv, 20, 20, 15, 20);
                    break;
                case FULLSCREEN_EXIT:
                    line(cv, 9, 4, 4, 4);
                    line(cv, 9, 4, 9, 9);
                    line(cv, 15, 4, 20, 4);
                    line(cv, 15, 4, 15, 9);
                    line(cv, 9, 20, 4, 20);
                    line(cv, 9, 20, 9, 15);
                    line(cv, 15, 20, 20, 20);
                    line(cv, 15, 20, 15, 15);
                    break;
                default:
                    circle(cv, 12, 12, 8);
                    break;
            }
        }
    }

    private Icons() {
    }
}
