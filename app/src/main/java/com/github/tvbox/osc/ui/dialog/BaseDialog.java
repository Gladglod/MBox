package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;

import xyz.doikki.videoplayer.util.CutoutUtil;

public class BaseDialog extends Dialog {
    public BaseDialog(@NonNull Context context) {
        super(context, R.style.CustomDialogStyle);
    }

    public BaseDialog(Context context, int customDialogStyle) {
        super(context, customDialogStyle);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        CutoutUtil.adaptCutoutAboveAndroidP(this, true);//设置刘海
        super.onCreate(savedInstanceState);
    }

    /* ==================== 玻璃按钮通用动效（与播放页/各页面同一套） ==================== */

    /**
     * 玻璃按钮统一按压效果：按下缩至 0.86（90ms）、松开平滑回弹 1.0（180ms 减速曲线）。
     * 只动观感不改点击逻辑，各功能对话框的按钮统一挂这一套。
     */
    protected void bindGlassPressEffect(final View view) {
        if (view == null) return;
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.animate().cancel();
                        v.animate().scaleX(0.86f).scaleY(0.86f).setDuration(90).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.animate().cancel();
                        v.animate().scaleX(1f).scaleY(1f).setDuration(180)
                                .setInterpolator(new DecelerateInterpolator()).start();
                        break;
                }
                return false;
            }
        });
    }

    private long mLastGlassClickAt = 0L;
    private int mLastGlassClickId = 0;

    /** @return true 放行本次点击；false 拦截（同一按钮 0.45s 内的连点） */
    protected boolean allowGlassClick(View v) {
        if (v == null) return false;
        long now = SystemClock.uptimeMillis();
        int id = v.getId();
        if (id == mLastGlassClickId && now - mLastGlassClickAt < 450) {
            return false;
        }
        mLastGlassClickAt = now;
        mLastGlassClickId = id;
        return true;
    }

    @Override
    public void show() {
        if (isContextInvalid()) {
            return;
        }
        // show 之前先把弹窗窗口配置成与沉浸式宿主一致：系统栏透明 + NO_LIMITS +
        // 复制宿主的系统栏标志。窗口带着这套配置 attach，第一帧就不会把状态栏
        // 渲染成白色 / 不透明（这是之前只在 show 之后补标志一直压不住闪白的原因）
        prepareImmersiveWindow();
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        super.show();
        inheritImmersiveUi();
        reAssertImmersiveDecor();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
    }

    private boolean isContextInvalid() {
        Context context = getContext();
        if (!(context instanceof Activity)) {
            return false;
        }
        Activity activity = (Activity) context;
        return activity.isFinishing()
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed());
    }

    /** 找到弹窗的宿主 Activity（context 可能是 ContextWrapper 包了一层） */
    private Activity hostActivity() {
        Context base = getContext();
        while (base instanceof android.content.ContextWrapper) {
            if (base instanceof Activity) {
                return (Activity) base;
            }
            base = ((android.content.ContextWrapper) base).getBaseContext();
        }
        return base instanceof Activity ? (Activity) base : null;
    }

    /**
     * show 之前的窗口预配置：仅当宿主处于沉浸式全屏（播放页）时生效。
     * 系统栏颜色置透明 + FLAG_LAYOUT_NO_LIMITS（窗口允许伸到系统栏背后），
     * 并把宿主 decor 的系统栏标志复制到弹窗 decor 上（pre-attach 一份）。
     */
    private void prepareImmersiveWindow() {
        try {
            Activity activity = hostActivity();
            Window host = activity == null ? null : activity.getWindow();
            Window mine = getWindow();
            if (host == null || mine == null) return;
            int hostFlags = host.getDecorView().getSystemUiVisibility();
            int hidden = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            if ((hostFlags & hidden) != hidden) return; // 非沉浸宿主保持系统默认表现
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mine.setStatusBarColor(Color.TRANSPARENT);
                mine.setNavigationBarColor(Color.TRANSPARENT);
            }
            mine.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            mine.getDecorView().setSystemUiVisibility(immersiveDecorFlags());
        } catch (Throwable ignore) {
        }
    }

    /** 沉浸式弹窗 decor 用的系统栏标志（与播放页浮层同一套） */
    private int immersiveDecorFlags() {
        return View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
    }

    /**
     * attach 之后再把 decor 标志补挂一遍（部分 ROM 会在 attach 时丢掉 view 级标志），
     * 并 post 一次兜底（与播放条浮层的 reApplyImmersiveToPopup 同一思路）。
     */
    private void reAssertImmersiveDecor() {
        try {
            final Window mine = getWindow();
            Activity activity = hostActivity();
            Window host = activity == null ? null : activity.getWindow();
            if (mine == null || host == null) return;
            int hostFlags = host.getDecorView().getSystemUiVisibility();
            int hidden = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            if ((hostFlags & hidden) != hidden) return; // 非沉浸宿主不动
            final View decor = mine.getDecorView();
            decor.setSystemUiVisibility(immersiveDecorFlags());
            decor.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        decor.setSystemUiVisibility(immersiveDecorFlags());
                    } catch (Throwable ignore) {
                    }
                }
            });
        } catch (Throwable ignore) {
        }
    }

    private void hideSysBar() {
        // 手机端：不再强制沉浸式全屏（会把系统栏顶掉、弹窗高度异常），交给系统默认处理
    }

    /**
     * 弹窗是一个独立窗口：抢到焦点后，系统栏会按「弹窗窗口」的系统 UI 标志重新渲染，
     * 于是播放器全屏沉浸时随便弹个窗状态栏就冒出来。
     * show 前的 prepareImmersiveWindow 已把窗口配好，这里只做兜底：
     * 宿主不在沉浸式全屏时什么都不做，保持系统默认表现。
     */
    private void inheritImmersiveUi() {
        try {
            Activity activity = hostActivity();
            Window host = activity == null ? null : activity.getWindow();
            Window mine = getWindow();
            if (host == null || mine == null) return;
            // 非沉浸宿主：系统栏颜色跟宿主走，观感统一
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    int hostFlags = host.getDecorView().getSystemUiVisibility();
                    int hidden = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
                    if ((hostFlags & hidden) != hidden) {
                        mine.setStatusBarColor(host.getStatusBarColor());
                        mine.setNavigationBarColor(host.getNavigationBarColor());
                    }
                }
            } catch (Throwable ignore) {
            }
            View hostDecor = host.getDecorView();
            int hostFlags = hostDecor.getSystemUiVisibility();
            int hidden = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            if ((hostFlags & hidden) != hidden) {
                return;
            }
            mine.getDecorView().setSystemUiVisibility(immersiveDecorFlags());
        } catch (Throwable ignore) {
            // 拿不到宿主 / 标志不兼容时静默降级，不影响弹窗本身
        }
    }
}
