package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

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

    @Override
    public void show() {
        if (isContextInvalid()) {
            return;
        }
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        super.show();
        inheritImmersiveUi();
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

    private void hideSysBar() {
        // 手机端：不再强制沉浸式全屏（会把系统栏顶掉、弹窗高度异常），交给系统默认处理
    }

    /**
     * 弹窗是一个独立窗口：抢到焦点后，系统栏会按「弹窗窗口」的系统 UI 标志重新渲染，
     * 于是播放器全屏沉浸时随便弹个窗状态栏就冒出来。
     * 这里把宿主 Activity 的沉浸标志同步到弹窗自己的 decor 上；
     * 宿主不在沉浸式全屏时什么都不做，保持系统默认表现。
     */
    private void inheritImmersiveUi() {
        try {
            if (!(getContext() instanceof Activity)) {
                return;
            }
            Activity activity = (Activity) getContext();
            Window host = activity.getWindow();
            Window mine = getWindow();
            if (host == null || mine == null) return;
            // 系统栏颜色跟宿主走，不然浅色主题里弹窗后状态栏还是默认深色，观感割裂
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mine.setStatusBarColor(host.getStatusBarColor());
                    mine.setNavigationBarColor(host.getNavigationBarColor());
                }
            } catch (Throwable ignore) {
            }
            View hostDecor = host.getDecorView();
            int hostFlags = hostDecor.getSystemUiVisibility();
            int hidden = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            if ((hostFlags & hidden) != hidden) {
                return;
            }
            mine.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | hidden
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } catch (Throwable ignore) {
            // 拿不到宿主 / 标志不兼容时静默降级，不影响弹窗本身
        }
    }
}
