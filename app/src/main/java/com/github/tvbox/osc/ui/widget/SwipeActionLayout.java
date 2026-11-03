package com.github.tvbox.osc.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.github.tvbox.osc.util.MotionCompat;

/**
 * 左滑露出操作按钮的容器（iOS 风格）。
 * <p>
 * XML 里第一个子 View 是「操作区」（靠右显示），第二个子 View 是「内容区」。
 * <p>
 * 动效设计：
 * <ul>
 *   <li>跟手：手指拖动实时移动内容区，两端越界时施加橡皮筋阻力（越拉越沉）；
 *   <li>收摊：松手后用弹簧（stiffness 360 / damping 0.9）回弹到开/关位，可被快速滑动“甩”开；
 *   <li>级联：操作按钮随露出进度从右缘淡入 + 右漂进场，逐个错开，像 iOS 邮件的抽屉；
 *   <li>按压：按钮自带品牌色玻璃按压态（bg_swipe_pin / bg_swipe_delete），无默认灰 ripple；
 *   <li>降级：系统开启「移除动画」时，收摊直接置位，仅保留纯状态变化。
 * </ul>
 * 同一时刻只允许一个打开。
 */
public class SwipeActionLayout extends FrameLayout {

    private static SwipeActionLayout currentOpen;

    /** 越出两端时的橡皮筋阻力系数（越界部分只按 25% 跟手） */
    private static final float OVER_DRAG_RESIST = 0.25f;
    /** 正向（往右推）的最大弹性余量 */
    private static final float MAX_OVER_PULL_DP = 40f;
    /** 收摊阈值：露出到一半多一点就算打开 */
    private static final float SETTLE_THRESHOLD = 0.55f;
    /** 快速滑动“甩开”的最小速度（px/s） */
    private static final float FLING_VELOCITY = 1800f;
    /** 收摊弹簧参数：比入场弹簧略硬一档，收摊干脆且有一点点过冲 */
    private static final float SETTLE_STIFFNESS = 360f;
    private static final float SETTLE_DAMPING = 0.9f;

    private View contentView;
    private View actionsView;
    private int actionsWidth;
    private int touchSlop;

    private float downX;
    private float downY;
    private boolean dragging;
    private boolean opened;
    private boolean swipeEnabled = true;

    /** 手势过程中的速度估计（px/s，EMA 平滑），用于快滑“甩开” */
    private float lastX;
    private long lastT;
    private float velocity;
    private SpringAnimation settleAnim;

    public SwipeActionLayout(Context context) {
        super(context);
        init(context);
    }

    public SwipeActionLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public SwipeActionLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (getChildCount() >= 2) {
            actionsView = getChildAt(0);
            contentView = getChildAt(1);
            // 复位到「全部隐藏」，防复用时旧状态闪现
            updateActionsProgress(0f);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (actionsView != null) {
            actionsWidth = actionsView.getWidth();
        }
        if (contentView != null && !opened) {
            contentView.setTranslationX(0f);
        }
    }

    public void setSwipeEnabled(boolean enabled) {
        swipeEnabled = enabled;
        if (!enabled) close();
    }

    public boolean isOpened() {
        return opened;
    }

    public void close() {
        settle(false);
    }

    /** 列表复用时立刻复位，不走动画 */
    public void resetNow() {
        cancelSettle();
        if (contentView != null) contentView.setTranslationX(0f);
        updateActionsProgress(0f);
        opened = false;
        if (currentOpen == this) currentOpen = null;
    }

    private void cancelSettle() {
        if (settleAnim == null) return;
        try {
            settleAnim.cancel();
        } catch (Throwable ignored) {
        }
        settleAnim = null;
    }

    /** 弹簧收摊：目标位要么完全露出操作区，要么归零 */
    private void settle(boolean open) {
        if (contentView == null) return;
        if (open && currentOpen != null && currentOpen != this) {
            currentOpen.close();
        }
        opened = open;
        if (open) {
            currentOpen = this;
        } else if (currentOpen == this) {
            currentOpen = null;
        }
        final float target = open ? -actionsWidth : 0f;

        if (MotionCompat.reducedMotion(getContext())) {
            cancelSettle();
            contentView.setTranslationX(target);
            updateActionsProgress(target);
            return;
        }

        cancelSettle();
        settleAnim = new SpringAnimation(contentView, DynamicAnimation.TRANSLATION_X, target);
        settleAnim.setSpring(new SpringForce()
                .setFinalPosition(target)
                .setStiffness(SETTLE_STIFFNESS)
                .setDampingRatio(SETTLE_DAMPING));
        settleAnim.addUpdateListener(new DynamicAnimation.OnAnimationUpdateListener() {
            @Override
            public void onAnimationUpdate(DynamicAnimation animation, float value, float velocity) {
                // 按钮的淡入/位移随同一根弹簧同步推进
                updateActionsProgress(value);
            }
        });
        settleAnim.start();
    }

    /** 露出进度 → 按钮级联淡入 + 右漂（进度 0 = 全隐藏，1 = 全露出） */
    private void updateActionsProgress(float translationX) {
        if (actionsWidth <= 0 || !(actionsView instanceof ViewGroup)) return;
        ViewGroup buttons = (ViewGroup) actionsView;
        int count = buttons.getChildCount();
        if (count == 0) return;
        float revealed = -translationX;
        if (revealed < 0f) revealed = 0f;
        if (revealed > actionsWidth) revealed = actionsWidth;
        float progress = revealed / actionsWidth;
        float density = getResources().getDisplayMetrics().density;
        // 逐个错开：越靠右（越后操作）的按钮越晚进场
        for (int i = 0; i < count; i++) {
            View button = buttons.getChildAt(i);
            float finished = clamp((progress - i * 0.15f) / 0.85f, 0f, 1f);
            button.setAlpha(finished);
            button.setTranslationX((1f - finished) * 18f * density);
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (contentView == null || actionsWidth <= 0) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                // 已经展开时，点内容区先收起（不触发条目点击）
                if (opened && ev.getX() < getWidth() - actionsWidth) {
                    close();
                    return true;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (!swipeEnabled) return false;
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    return true;
                }
                break;
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (contentView == null || actionsWidth <= 0) return super.onTouchEvent(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = false;
                velocity = 0f;
                lastX = ev.getX();
                lastT = System.nanoTime();
                // 交给 View 自己处理，保证点击能正常触发 performClick
                return super.onTouchEvent(ev);
            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getX() - downX;
                if (!dragging) {
                    if (Math.abs(dx) <= touchSlop) return super.onTouchEvent(ev);
                    dragging = true;
                    cancelSettle();
                }
                // EMA 估计滑动速度，供松手时判断“甩开”
                long now = System.nanoTime();
                float dt = (now - lastT) / 1_000_000_000f;
                if (dt > 0.0001f) {
                    float v = (ev.getX() - lastX) / dt;
                    velocity = velocity == 0f ? v : velocity * 0.7f + v * 0.3f;
                }
                lastX = ev.getX();
                lastT = now;

                float base = opened ? -actionsWidth : 0f;
                float target = base + dx;
                // 橡皮筋：两端只允许少量越界，且越界部分放大阻力
                if (target > 0f) {
                    float overMax = 40f * getResources().getDisplayMetrics().density;
                    float pulled = Math.min(target * OVER_DRAG_RESIST, overMax);
                    target = pulled;
                } else if (target < -actionsWidth) {
                    target = -actionsWidth + (target + actionsWidth) * OVER_DRAG_RESIST;
                }
                contentView.setTranslationX(target);
                updateActionsProgress(target);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (dragging) {
                    dragging = false;
                    float current = contentView.getTranslationX();
                    boolean fastLeft = current < -touchSlop && velocity < -FLING_VELOCITY;
                    boolean fastRight = current > -touchSlop && velocity > FLING_VELOCITY;
                    boolean open;
                    if (fastRight) open = false;
                    else if (fastLeft) open = true;
                    else open = current < -actionsWidth * SETTLE_THRESHOLD;
                    settle(open);
                    return true;
                }
                return super.onTouchEvent(ev);
            }
            default:
                return super.onTouchEvent(ev);
        }
    }
}
