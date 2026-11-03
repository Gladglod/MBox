package com.github.tvbox.osc.ui.widget;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.MotionCompat;

/**
 * 「我的」设置行用的 iOS 风格开关：
 *
 * <ul>
 *   <li>选中态轨道用品牌色（ui_accent），轨道颜色随进度渐变；</li>
 *   <li>滑块位置用温和弹簧（damping 0.7 / stiffness 420，轻微过冲不夸张回弹）；</li>
 *   <li>切换瞬间整个开关做一个 1.0 → ~1.05 → 1.0 的小脉冲反馈；</li>
 *   <li>点击交给所在设置行处理（开关本身不可点），行里点哪儿都有效；</li>
 *   <li>系统「移除动画」开启时直接置位，不做动画。</li>
 * </ul>
 */
public class SpringSwitch extends View {

    private static final int TRACK_WIDTH_DP = 44;
    private static final int TRACK_HEIGHT_DP = 26;
    private static final int THUMB_MARGIN_DP = 2;

    private static final float THUMB_SPRING_DAMPING = 0.7f;
    private static final float THUMB_SPRING_STIFFNESS = 420f;
    /** 切换瞬间的缩放脉冲：1.0 → 1.05 → 1.0，正弦曲线，不夸张回弹 */
    private static final long POP_DURATION_MS = 220;
    private static final float POP_SCALE = 0.05f;

    private boolean checked = false;
    /** 滑块进度：0 = 关，1 = 开（由弹簧驱动） */
    private float thumbProgress = 0f;
    private boolean drewOnce = false;

    private final int colorTrackOn;
    private final int colorTrackOff;
    private final int colorThumbFill;
    private final int colorThumbStroke;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private SpringAnimation thumbAnim;
    private ValueAnimator popAnimator;

    private static final ArgbEvaluator EVALUATOR = new ArgbEvaluator();

    public SpringSwitch(Context context) {
        this(context, null);
    }

    public SpringSwitch(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SpringSwitch(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        colorTrackOn = resolveColor(R.color.ui_accent);
        colorTrackOff = resolveColor(R.color.switch_track_off);
        colorThumbFill = resolveColor(R.color.ui_surface);
        colorThumbStroke = resolveColor(R.color.switch_thumb_stroke);
        // 点击交给所在设置行整行处理，开关自己不拦截
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private int resolveColor(int resId) {
        return getResources().getColor(resId);
    }

    public boolean isChecked() {
        return checked;
    }

    /** 默认带动画切换 */
    public void setChecked(boolean checked) {
        setChecked(checked, true);
    }

    /**
     * @param checked 目标状态
     * @param animate false 表示初始化回填，不做动画
     */
    public void setChecked(boolean checked, boolean animate) {
        if (this.checked == checked && drewOnce) return;
        this.checked = checked;
        drewOnce = true;
        float target = checked ? 1f : 0f;
        if (!animate || MotionCompat.reducedMotion(getContext())) {
            cancelAll();
            thumbProgress = target;
            scheduleInvalidate();
            return;
        }
        ensureAnimations();
        thumbAnim.cancel();
        thumbAnim.animateToFinalPosition(target);
        playPop();
        scheduleInvalidate();
    }

    private void scheduleInvalidate() {
        if (drawn()) invalidate();
    }

    private boolean drawn() {
        return getWidth() > 0;
    }

    private void cancelAll() {
        if (thumbAnim != null) thumbAnim.cancel();
        if (popAnimator != null) popAnimator.cancel();
        setScaleX(1f);
        setScaleY(1f);
    }

    private void playPop() {
        if (popAnimator == null) {
            popAnimator = ValueAnimator.ofFloat(0f, 1f);
            popAnimator.setDuration(POP_DURATION_MS);
            popAnimator.setInterpolator(null); // 线性取样，正弦曲线自己管起伏
            popAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    float t = (Float) animation.getAnimatedValue();
                    float scale = 1f + POP_SCALE * (float) Math.sin(Math.PI * t);
                    SpringSwitch.this.setScaleX(scale);
                    SpringSwitch.this.setScaleY(scale);
                }
            });
        }
        popAnimator.cancel();
        popAnimator.start();
    }

    private void ensureAnimations() {
        if (thumbAnim == null) {
            thumbAnim = new SpringAnimation(new FloatValueHolder(thumbProgress));
            thumbAnim.setSpring(new SpringForce()
                    .setDampingRatio(THUMB_SPRING_DAMPING)
                    .setStiffness(THUMB_SPRING_STIFFNESS));
            thumbAnim.addUpdateListener(new DynamicAnimation.OnAnimationUpdateListener() {
                @Override
                public void onAnimationUpdate(DynamicAnimation animation, float value, float velocity) {
                    thumbProgress = value;
                    invalidate();
                }
            });
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAll();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = dpToPx(TRACK_WIDTH_DP);
        int h = dpToPx(TRACK_HEIGHT_DP);
        setMeasuredDimension(
                resolveSize(w, widthMeasureSpec),
                resolveSize(h, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float half = h / 2f;
        float radius = Math.min(half, dpToPx(TRACK_HEIGHT_DP) / 2f);

        // 轨道：关闭灰 → 品牌色，随滑块进度渐变
        trackPaint.setColor(mixColor(colorTrackOff, colorTrackOn, thumbProgress));
        canvas.drawRoundRect(0, 0, w, h, radius, radius, trackPaint);

        // 滑块：两端各留 2dp
        float margin = dpToPx(THUMB_MARGIN_DP);
        float thumbRadius = half - margin;
        float from = margin + thumbRadius;
        float to = w - margin - thumbRadius;
        float cx = from + (to - from) * thumbProgress;
        float cy = half;

        thumbPaint.setColor(colorThumbFill);
        canvas.drawCircle(cx, cy, thumbRadius, thumbPaint);
        thumbStrokePaint.setColor(colorThumbStroke);
        thumbStrokePaint.setStyle(Paint.Style.STROKE);
        thumbStrokePaint.setStrokeWidth(dpToPx(1));
        canvas.drawCircle(cx, cy, thumbRadius - dpToPx(0.5f), thumbStrokePaint);
    }

    private int mixColor(int from, int to, float progress) {
        return (Integer) EVALUATOR.evaluate(Math.max(0f, Math.min(1f, progress)), from, to);
    }

    private int dpToPx(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
