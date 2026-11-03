package com.github.tvbox.osc.util;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import java.util.ArrayList;
import java.util.List;

/**
 * 「我的」体系统一的温和弹簧动效工具。
 *
 * <ul>
 *   <li>弹簧参数温和：dampingRatio 0.85 / stiffness 300（入场），不做夸张回弹；</li>
 *   <li>行按压反馈：整行轻微缩放到 0.98，释放弹簧回弹，替代默认的大面积灰色 ripple；</li>
 *   <li>入场：分组卡片轻微上浮 + 淡入，按序错开 40ms；</li>
 *   <li>尊重系统「移除动画」（三个时长缩放都为 0）——开启后弹簧动效改为直接置位，
 *       转场改为淡入淡出（见 {@link #reducedMotion}）。</li>
 * </ul>
 */
public final class MotionCompat {

    /** 入场弹簧：温和，不 bouncy */
    public static final float SPRING_DAMPING = 0.85f;
    public static final float SPRING_STIFFNESS = 300f;
    /** 按压弹簧：比入场快一档才有「跟手」的反馈，但参数仍可预测 */
    private static final float PRESS_DAMPING = 0.85f;
    private static final float PRESS_STIFFNESS = 500f;
    /** 行按压时整行的目标缩放 */
    private static final float PRESS_SCALE = 0.98f;
    /** 入场错开间隔 */
    private static final long STAGGER_MS = 40;

    private MotionCompat() {
    }

    /**
     * 系统开了「移除动画」（无障碍 → 移除动画，会把动画时长缩放设为 0）时返回 true。
     * 调用方应当把滑动/缩放类动效换成纯淡入淡出或直接置位。
     */
    public static boolean reducedMotion(Context context) {
        if (context == null) return false;
        try {
            ContentResolver cr = context.getContentResolver();
            float animatorScale = Settings.Global.getFloat(
                    cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            float windowScale = Settings.Global.getFloat(
                    cr, Settings.Global.WINDOW_ANIMATION_SCALE, 1f);
            return animatorScale <= 0f && windowScale <= 0f;
        } catch (Throwable th) {
            return false;
        }
    }

    /** 建一个温和的弹簧参数 */
    public static SpringForce spring() {
        return new SpringForce()
                .setStiffness(SPRING_STIFFNESS)
                .setDampingRatio(SPRING_DAMPING);
    }

    /**
     * 行按压反馈：按下整行轻微缩放 0.98，抬起/取消弹簧回弹。
     * 不消费任何事件，点击逻辑照常走。开启了「移除动画」时不生效。
     */
    public static void attachPressScale(final View row) {
        if (row == null || reducedMotion(row.getContext())) return;
        final SpringAnimation scaleX = new SpringAnimation(row, DynamicAnimation.SCALE_X);
        final SpringAnimation scaleY = new SpringAnimation(row, DynamicAnimation.SCALE_Y);
        SpringForce spring = new SpringForce()
                .setStiffness(PRESS_STIFFNESS)
                .setDampingRatio(PRESS_DAMPING);
        scaleFor(scaleX, spring);
        scaleFor(scaleY, spring);
        row.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        scaleX.animateToFinalPosition(PRESS_SCALE);
                        scaleY.animateToFinalPosition(PRESS_SCALE);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        scaleX.animateToFinalPosition(1f);
                        scaleY.animateToFinalPosition(1f);
                        break;
                }
                return false; // 只做反馈，不拦截事件
            }
        });
    }

    private static void scaleFor(SpringAnimation anim, SpringForce spring) {
        anim.setSpring(spring);
        anim.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE);
    }

    /**
     * 分组卡片入场：轻微上浮 + 淡入，逐个错开 {@link #STAGGER_MS}。
     * 只在页面首次出现时调用一次；开启「移除动画」时直接置位（无动画）。
     */
    public static void staggerEnter(final ViewGroup container) {
        if (container == null || container.getChildCount() == 0) return;
        List<View> children = new ArrayList<>();
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE) children.add(child);
        }
        if (children.isEmpty()) return;

        if (reducedMotion(container.getContext())) {
            for (View child : children) {
                child.setAlpha(1f);
                child.setTranslationY(0f);
            }
            return;
        }

        // 上浮距离约 14dp，几张卡片加头部一起错开，形成「浮上来」的层次
        int from = (int) (children.get(0).getContext().getResources()
                .getDisplayMetrics().density * 14f);
        for (int i = 0; i < children.size(); i++) {
            final View child = children.get(i);
            child.setAlpha(0f);
            child.setTranslationY(from);

            // dynamicanimation 1.0.0 没有 setStartDelay/MIN_VISIBLE_CHANGE_TRANSLATION，
            // 错开交给 postDelayed 手动调度：
            // 弹簧不用 (target) 构造器（内置参数不可控且 setSpring 替换后丢 final position，
            // start() 会抛异常），统一先建后 animateToFinalPosition。
            final long delay = i * STAGGER_MS;
            container.postDelayed(new Runnable() {
                @Override
                public void run() {
                    SpringAnimation alpha = new SpringAnimation(child, DynamicAnimation.ALPHA);
                    alpha.setSpring(spring());
                    alpha.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_ALPHA);
                    alpha.animateToFinalPosition(1f);

                    SpringAnimation rise = new SpringAnimation(child, DynamicAnimation.TRANSLATION_Y);
                    rise.setSpring(spring());
                    rise.setMinimumVisibleChange(1f); // 位移收敛阈值 1px
                    rise.animateToFinalPosition(0f);
                }
            }, delay);
        }
    }
}
