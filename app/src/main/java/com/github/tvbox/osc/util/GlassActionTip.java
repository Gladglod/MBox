package com.github.tvbox.osc.util;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.github.tvbox.osc.R;

/**
 * iOS 玻璃质感的动作胶囊：常驻在内容右下角偏上处，持续数秒自动消失，
 * 点按立即执行附带动作。用于「已自动跳过片头 · 点按撤销」这类
 * 需要用户来得及做选择的临时按钮（与 {@link GlassTip} 同一套玻璃画法）。
 */
public final class GlassActionTip {

    /** 胶囊停留时长：3 秒左右 */
    private static final long DURATION_MS = 3000;

    /** 提示 active 期间的全局引用，用完置空 */
    private static View sActiveTip;
    private static Runnable sHideRun;

    private GlassActionTip() {
    }

    /**
     * 在右下角偏上处显示一枚玻璃动作胶囊。
     *
     * @param text     提示文案（同时也是点按目标）
     * @param onAction 点按胶囊后执行的动作（撤销等）
     */
    public static void show(Context context, CharSequence text, final Runnable onAction) {
        Activity activity = findActivity(context);
        if (activity == null || text == null) return;
        final ViewGroup content = (ViewGroup) activity.findViewById(android.R.id.content);
        if (content == null) return;

        // 同一时刻只留一枚：直接撤掉旧的（连同其延迟隐藏回调）
        dismiss();

        final TextView tip = (TextView) LayoutInflater.from(activity)
                .inflate(R.layout.view_glass_tip, content, false);
        tip.setText(text);
        // 压住内容、可点按
        tip.setClickable(true);
        tip.setFocusable(true);
        try {
            // 玻璃胶囊自带一层白色按压高光
            Drawable bg = tip.getBackground();
            tip.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(0x33FFFFFF), bg, bg));
        } catch (Throwable ignored) {
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        // 右下角偏上：避开控制器进度条区域
        lp.bottomMargin = (int) dp(activity, 132);
        lp.rightMargin = (int) dp(activity, 24);
        content.addView(tip, lp);

        tip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                if (onAction != null) onAction.run();
            }
        });

        // 入场：自右侧轻滑入 + 玻璃胶囊弹出
        tip.setAlpha(0f);
        tip.setTranslationX(dp(tip.getContext(), 24));
        tip.setScaleX(0.9f);
        tip.setScaleY(0.9f);
        tip.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
                .setDuration(220).setInterpolator(new OvershootInterpolator(1.4f)).start();

        // 延迟淡出后移除
        sHideRun = new Runnable() {
            @Override
            public void run() {
                if (tip.getParent() == null) return;
                tip.animate().cancel();
                tip.animate().alpha(0f).translationX(dp(tip.getContext(), 24)).setDuration(180)
                        .setInterpolator(new DecelerateInterpolator())
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                ViewGroup parent = (ViewGroup) tip.getParent();
                                if (parent != null) parent.removeView(tip);
                                if (sActiveTip == tip) {
                                    sActiveTip = null;
                                    sHideRun = null;
                                }
                            }
                        }).start();
            }
        };
        sActiveTip = tip;
        tip.postDelayed(sHideRun, DURATION_MS);
    }

    /** 撤掉当前展示中的动作胶囊（连同延迟隐藏回调） */
    public static void dismiss() {
        if (sActiveTip != null) {
            if (sHideRun != null) sActiveTip.removeCallbacks(sHideRun);
            sActiveTip.animate().cancel();
            ViewGroup oldParent = (ViewGroup) sActiveTip.getParent();
            if (oldParent != null) oldParent.removeView(sActiveTip);
            sActiveTip = null;
            sHideRun = null;
        }
    }

    /** Context / ContextThemeWrapper / ContextWrapper 层层解包，找到宿主 Activity */
    private static Activity findActivity(Context context) {
        Context ctx = context;
        while (ctx != null) {
            if (ctx instanceof Activity) return (Activity) ctx;
            if (ctx instanceof ContextWrapper) {
                ctx = ((ContextWrapper) ctx).getBaseContext();
            } else {
                ctx = null;
            }
        }
        return null;
    }

    private static float dp(Context context, float value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics());
    }
}
