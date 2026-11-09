package com.github.tvbox.osc.util;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
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
 * iOS 玻璃质感的轻提示（Toast 替身）。
 *
 * <p>玻璃胶囊从顶部滑入、停留约 1.6s、淡出后自移除；同一时刻只保留一枚，
 * 后一次提示会顶掉前一次。用于收藏操作这类「需要一点反馈」的轻交互，
 * 避免系统 Toast 在各厂商 ROM 上样式不受控、看不见玻璃质感。</p>
 */
public final class GlassTip {

    /** 提示 active 期间的全局引用，用完置空 */
    private static View sActiveTip;
    private static Runnable sHideRun;

    private GlassTip() {
    }

    /** 显示一条玻璃轻提示；@param text 提示文案 */
    public static void show(Context context, CharSequence text) {
        Activity activity = findActivity(context);
        if (activity == null || text == null) return;
        final ViewGroup content = (ViewGroup) activity.findViewById(android.R.id.content);
        if (content == null) return;

        // 同一时刻只留一枚：直接撤掉旧的（连同其延迟隐藏回调）
        if (sActiveTip != null) {
            if (sHideRun != null) sActiveTip.removeCallbacks(sHideRun);
            sActiveTip.animate().cancel();
            ViewGroup oldParent = (ViewGroup) sActiveTip.getParent();
            if (oldParent != null) oldParent.removeView(sActiveTip);
            sActiveTip = null;
            sHideRun = null;
        }

        final TextView tip = (TextView) LayoutInflater.from(activity)
                .inflate(R.layout.view_glass_tip, content, false);
        tip.setText(text);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = (int) dp(activity, 88);
        content.addView(tip, lp);

        // 入场：自上方轻微下落 + 玻璃胶囊弹出
        tip.setAlpha(0f);
        tip.setTranslationY(dp(activity, -14));
        tip.setScaleX(0.9f);
        tip.setScaleY(0.9f);
        tip.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(220).setInterpolator(new OvershootInterpolator(1.4f)).start();

        // 延迟淡出后移除
        sHideRun = new Runnable() {
            @Override
            public void run() {
                if (tip.getParent() == null) return;
                tip.animate().cancel();
                tip.animate().alpha(0f).translationY(dp(tip.getContext(), -8)).setDuration(160)
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
        tip.postDelayed(sHideRun, 1600);
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
