package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.FastClickCheckUtil;

/**
 * 首页长按弹出的底部快捷菜单。
 *
 * <p>使用整屏透明窗口 + 贴底面板，进出场由 View 级动画完成：
 * 蒙层淡入淡出，面板 <code>translationY</code> 从屏幕底边滑入 / 沉回去。
 * 不用 {@code windowAnimations}，因为 MATCH_PARENT 窗口的 window 动画
 * 会把蒙层和面板整体平移，观感是错的（和 {@code DownloadSelectDialog} 同一套处理）。</p>
 */
public class HomeMenuSheetDialog extends BaseDialog {

    public interface OnMenuClickListener {
        void onDiscoverClick();

        void onHomeTabsClick();
    }

    private static final long PANEL_IN_DURATION = 260L;
    private static final long PANEL_OUT_DURATION = 180L;
    private static final long SCRIM_IN_DURATION = 200L;

    private final OnMenuClickListener listener;
    private View scrim;
    private View sheetPanel;
    private boolean dismissing = false;

    public HomeMenuSheetDialog(@NonNull Context context, OnMenuClickListener listener) {
        super(context, R.style.CustomDialogStyleDim);
        this.listener = listener;
        setContentView(R.layout.dialog_home_menu);
        setCanceledOnTouchOutside(true);
        setupBottomWindow();

        this.scrim = findViewById(R.id.scrim);
        this.sheetPanel = findViewById(R.id.sheetPanel);

        if (scrim != null) {
            scrim.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                }
            });
        }

        View rowDiscover = findViewById(R.id.rowDiscover);
        if (rowDiscover != null) {
            rowDiscover.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FastClickCheckUtil.check(v);
                    if (HomeMenuSheetDialog.this.listener == null) {
                        dismiss();
                        return;
                    }
                    dismiss();
                    HomeMenuSheetDialog.this.listener.onDiscoverClick();
                }
            });
        }

        View rowTabs = findViewById(R.id.rowTabs);
        if (rowTabs != null) {
            rowTabs.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FastClickCheckUtil.check(v);
                    if (HomeMenuSheetDialog.this.listener == null) {
                        dismiss();
                        return;
                    }
                    dismiss();
                    HomeMenuSheetDialog.this.listener.onHomeTabsClick();
                }
            });
        }

        View btnCancel = findViewById(R.id.btnCancel);
        if (btnCancel != null) {
            btnCancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                }
            });
        }
    }

    private void setupBottomWindow() {
        Window window = getWindow();
        if (window == null) return;
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.gravity = Gravity.BOTTOM;
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.MATCH_PARENT;
        window.setAttributes(lp);
    }

    @Override
    public void show() {
        // 先摆好初始态：面板沉到屏幕外、蒙层透明，避免首帧闪出完整面板
        if (scrim != null) scrim.setAlpha(0f);
        final int screenHeight = screenHeight();
        if (sheetPanel != null) sheetPanel.setTranslationY(screenHeight);
        super.show();
        if (sheetPanel == null) return;
        sheetPanel.post(new Runnable() {
            @Override
            public void run() {
                if (dismissing) return;
                float height = sheetPanel.getHeight();
                if (height <= 0) height = screenHeight;
                sheetPanel.setTranslationY(height);
                sheetPanel.animate()
                        .translationY(0f)
                        .setDuration(PANEL_IN_DURATION)
                        .setInterpolator(new OvershootInterpolator(1.0f))
                        .start();
                if (scrim != null) {
                    scrim.animate().alpha(1f).setDuration(SCRIM_IN_DURATION).start();
                }
            }
        });
    }

    @Override
    public void dismiss() {
        if (dismissing) return;
        dismissing = true;
        playExitAnim();
    }

    @Override
    public void cancel() {
        if (dismissing) return;
        dismissing = true;
        playExitAnim();
    }

    private void playExitAnim() {
        if (sheetPanel == null || sheetPanel.getHeight() <= 0) {
            HomeMenuSheetDialog.super.dismiss();
            return;
        }
        sheetPanel.animate()
                .translationY(sheetPanel.getHeight())
                .setDuration(PANEL_OUT_DURATION)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        HomeMenuSheetDialog.super.dismiss();
                    }
                })
                .start();
        if (scrim != null) {
            scrim.animate().alpha(0f).setDuration(PANEL_OUT_DURATION).start();
        }
    }

    private int screenHeight() {
        DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
        return dm == null ? 0 : dm.heightPixels;
    }
}
