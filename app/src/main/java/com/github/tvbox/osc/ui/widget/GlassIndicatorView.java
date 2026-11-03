package com.github.tvbox.osc.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.github.tvbox.osc.R;

/**
 * Liquid Glass 的「选中指示器」。
 *
 * <p>它不是一张会跟着选中项瞬移的背景图，而是一枚独立存在的玻璃透镜：
 * 位置和宽度各由一条弹簧驱动，切换 Tab 时它从旧位置滑到新位置。
 *
 * <p>为什么不直接给一个 View 做 translationX + 宽度动画：
 * 宽度变化没法用属性动画平滑表达（要么改 LayoutParams 每帧 requestLayout，
 * 要么用 scaleX 把圆角拉扁）。这里改成「自己画一个圆角矩形」，
 * 只让 left/right 两个浮点数变化，圆角永远是正圆，也没有布局抖动。
 *
 * <p>为什么用两条弹簧而不是一条：
 * 胶囊的左右两端需要能独立移动 —— 从窄 Tab 跳到宽 Tab 时，如果两端用同一个进度插值，
 * 中途会先「整体平移再变宽」，看起来像两个动作。两条弹簧同时出发，
 * 视觉上是一个物体在伸展。
 */
public class GlassIndicatorView extends View {

    /** 横向滑动（底部导航） */
    public static final int HORIZONTAL = 0;
    /** 纵向滑动（右上角数据源下拉菜单） */
    public static final int VERTICAL = 1;

    /**
     * 弹簧参数。
     *
     * <p>dampingRatio 0.75 —— 有一点回弹、但不会来回晃，是「果冻感」和「稳」的分界点；
     * stiffness 400 —— 单次切换大约 220ms 落定。再软就跟不上用户连续点 Tab 的节奏，
     * 再硬就变成线性动画，失去弹性。
     */
    private static final float DAMPING_RATIO = 0.75f;
    private static final float STIFFNESS = 400f;

    private static final FloatPropertyCompat<GlassIndicatorView> PROP_START =
            new FloatPropertyCompat<GlassIndicatorView>("glassIndicatorStart") {
                @Override
                public float getValue(GlassIndicatorView view) {
                    return view.spanStart;
                }

                @Override
                public void setValue(GlassIndicatorView view, float value) {
                    view.spanStart = value;
                    view.invalidate();
                }
            };

    private static final FloatPropertyCompat<GlassIndicatorView> PROP_END =
            new FloatPropertyCompat<GlassIndicatorView>("glassIndicatorEnd") {
                @Override
                public float getValue(GlassIndicatorView view) {
                    return view.spanEnd;
                }

                @Override
                public void setValue(GlassIndicatorView view, float value) {
                    view.spanEnd = value;
                    view.invalidate();
                }
            };

    private final RectF rect = new RectF();
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int orientation = HORIZONTAL;
    /** 主轴起点：横向是 left，纵向是 top */
    private float spanStart;
    /** 主轴终点：横向是 right，纵向是 bottom */
    private float spanEnd;
    /** 交叉轴内缩，让胶囊不要贴着容器边缘 */
    private float crossInset;
    /** > 0 时用固定圆角，<= 0 时取半高/半宽做完整胶囊 */
    private float cornerRadius;
    private int highlightColor;

    private SpringAnimation startAnim;
    private SpringAnimation endAnim;

    private LinearGradient glossShader;
    private float shaderTop = Float.NaN;
    private float shaderBottom = Float.NaN;

    public GlassIndicatorView(Context context) {
        this(context, null);
    }

    public GlassIndicatorView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public GlassIndicatorView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setWillNotDraw(false);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(ContextCompat.getColor(context, R.color.glass_indicator_fill));
        strokePaint.setStyle(Paint.Style.STROKE);
        // 1.2dp：填充只有 8%，边缘必须画得实一点，否则整个透镜会"消失"
        strokePaint.setStrokeWidth(context.getResources().getDisplayMetrics().density * 1.2f);
        strokePaint.setColor(ContextCompat.getColor(context, R.color.glass_indicator_stroke));
        highlightColor = ContextCompat.getColor(context, R.color.glass_indicator_highlight);
    }

    /** 横向 = 底部导航，纵向 = 下拉菜单 */
    public void setOrientation(int orientation) {
        if (this.orientation == orientation) return;
        this.orientation = orientation;
        invalidate();
    }

    /** 交叉轴内缩（px）：横向时作用于上下，纵向时作用于左右 */
    public void setCrossInset(float px) {
        if (this.crossInset == px) return;
        this.crossInset = px;
        invalidate();
    }

    /** 圆角（px）。传 <= 0 表示按短边做完整胶囊 */
    public void setCornerRadius(float px) {
        if (this.cornerRadius == px) return;
        this.cornerRadius = px;
        invalidate();
    }

    public void setFillColor(int color) {
        fillPaint.setColor(color);
        invalidate();
    }

    public void setStrokeColor(int color) {
        strokePaint.setColor(color);
        invalidate();
    }

    /**
     * 把指示器移到 [start, end] 这一段（主轴坐标，相对本 View）。
     *
     * @param animate false 用于首次布局/尺寸变化时的瞬间对位；
     *                true 用于用户切换，会走弹簧
     */
    public void setSpan(float start, float end, boolean animate) {
        ensureSprings();
        if (!animate || !isAttachedToWindow()) {
            startAnim.cancel();
            endAnim.cancel();
            spanStart = start;
            spanEnd = end;
            invalidate();
            return;
        }
        if (Math.abs(spanStart - start) < 0.5f && Math.abs(spanEnd - end) < 0.5f) {
            return;
        }
        // 关键：animateToFinalPosition 而不是 cancel + 重新 start。
        // 正在滑动时再调用它，弹簧只会「改变目标位置」，
        // 当前速度和当前位置都保留 —— 所以连续快速点 Tab 时指示器不会停顿、不会排队，
        // 而是带着当前速度拐向新目标。这正是要求里的第 3 点。
        startAnim.animateToFinalPosition(start);
        endAnim.animateToFinalPosition(end);
    }

    public boolean isAnimating() {
        return (startAnim != null && startAnim.isRunning())
                || (endAnim != null && endAnim.isRunning());
    }

    private void ensureSprings() {
        if (startAnim != null) return;
        startAnim = new SpringAnimation(this, PROP_START);
        startAnim.setSpring(new SpringForce().setDampingRatio(DAMPING_RATIO).setStiffness(STIFFNESS));
        endAnim = new SpringAnimation(this, PROP_END);
        endAnim.setSpring(new SpringForce().setDampingRatio(DAMPING_RATIO).setStiffness(STIFFNESS));
    }

    @Override
    protected void onDetachedFromWindow() {
        if (startAnim != null) startAnim.cancel();
        if (endAnim != null) endAnim.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left, top, right, bottom;
        if (orientation == HORIZONTAL) {
            left = spanStart;
            right = spanEnd;
            top = crossInset;
            bottom = getHeight() - crossInset;
        } else {
            left = crossInset;
            right = getWidth() - crossInset;
            top = spanStart;
            bottom = spanEnd;
        }
        if (right - left < 1f || bottom - top < 1f) {
            return;
        }
        rect.set(left, top, right, bottom);
        float radius = cornerRadius > 0
                ? cornerRadius
                : Math.min(rect.width(), rect.height()) / 2f;

        canvas.drawRoundRect(rect, radius, radius, fillPaint);

        if (highlightColor != 0) {
            if (glossShader == null || shaderTop != rect.top || shaderBottom != rect.bottom) {
                glossShader = new LinearGradient(0, rect.top, 0, rect.top + rect.height() * 0.8f,
                        highlightColor, 0x00FFFFFF, Shader.TileMode.CLAMP);
                shaderTop = rect.top;
                shaderBottom = rect.bottom;
                glossPaint.setShader(glossShader);
            }
            canvas.drawRoundRect(rect, radius, radius, glossPaint);
        }

        canvas.drawRoundRect(rect, radius, radius, strokePaint);
    }
}
