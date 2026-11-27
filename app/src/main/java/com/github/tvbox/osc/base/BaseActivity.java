package com.github.tvbox.osc.base;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.PermissionChecker;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.callback.EmptyCallback;
import com.github.tvbox.osc.callback.LoadingCallback;
import com.github.tvbox.osc.util.AppManager;
import com.kingja.loadsir.callback.Callback;
import com.kingja.loadsir.core.LoadService;
import com.kingja.loadsir.core.LoadSir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import me.jessyan.autosize.AutoSizeConfig;
import me.jessyan.autosize.AutoSizeCompat;
import me.jessyan.autosize.internal.CustomAdapt;
import xyz.doikki.videoplayer.util.CutoutUtil;

/**
 * @author pj567
 * @date :2020/12/17
 * @description:
 */
public abstract class BaseActivity extends AppCompatActivity implements CustomAdapt {
    protected Context mContext;
    private LoadService mLoadService;

    private static float screenRatio = -100.0f;
    private final Runnable refreshAutoSizeRunnable = new Runnable() {
        @Override
        public void run() {
            if (shouldRefreshAutoSize()) {
                refreshAutoSize();
            }
        }
    };
    private final Runnable hideSysBarRunnable = new Runnable() {
        @Override
        public void run() {
            hideSysBar();
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        try {
            if (screenRatio < 0) {
                DisplayMetrics dm = new DisplayMetrics();
                getWindowManager().getDefaultDisplay().getMetrics(dm);
                updateScreenRatio(dm);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        super.onCreate(savedInstanceState);
        updateBaseSizeInDp();
        setContentView(getLayoutResID());
        mContext = this;
        com.github.tvbox.osc.util.ThemeUtil.applySystemBars(this);
        initSystemUiListener();
        CutoutUtil.adaptCutoutAboveAndroidP(mContext, true);//设置刘海
        AppManager.getInstance().addActivity(this);
        init();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSysBar();
        if (shouldRefreshAutoSize()) {
            refreshAutoSize();
            scheduleRefreshAutoSize();
        }
    }

    /**
     * 手机端：不再使用 TV 的沉浸式隐藏（会遮挡手势导航区）。
     * 播放器等需要全屏的场景请单独实现，不要复用此方法。
     */
    public void hideSysBar() {
        // no-op on phone
    }

    private void initSystemUiListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            final View decorView = getWindow().getDecorView();
            decorView.setOnSystemUiVisibilityChangeListener(new View.OnSystemUiVisibilityChangeListener() {
                @Override
                public void onSystemUiVisibilityChange(int visibility) {
                    int hiddenBars = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN;
                    if ((visibility & hiddenBars) != hiddenBars) {
                        decorView.removeCallbacks(hideSysBarRunnable);
                        decorView.postDelayed(hideSysBarRunnable, 300);
                    }
                }
            });
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        getWindow().getDecorView().removeCallbacks(refreshAutoSizeRunnable);
        getWindow().getDecorView().removeCallbacks(hideSysBarRunnable);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSysBar();
            if (shouldRefreshAutoSize()) {
                scheduleRefreshAutoSize();
            }
        }
    }

    protected boolean shouldRefreshAutoSize() {
        return false;
    }

    private void scheduleRefreshAutoSize() {
        View decorView = getWindow().getDecorView();
        decorView.removeCallbacks(refreshAutoSizeRunnable);
        decorView.postDelayed(refreshAutoSizeRunnable, 300);
    }

    private void refreshAutoSize() {
        try {
            DisplayMetrics dm = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(dm);
            if (dm.widthPixels <= 0 || dm.heightPixels <= 0) {
                return;
            }
            updateScreenRatio(dm);
            updateBaseSizeInDp();
            AutoSizeConfig.getInstance()
                    .setScreenWidth(dm.widthPixels)
                    .setScreenHeight(dm.heightPixels);
            AutoSizeCompat.autoConvertDensityOfCustomAdapt(super.getResources(), this);
            getWindow().getDecorView().requestLayout();
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 横竖屏切换后重新折算设计宽度，否则横屏 density 会被放大
        updateBaseSizeInDp();
        AutoSizeCompat.autoConvertDensityOfCustomAdapt(super.getResources(), this);
    }

    private void updateScreenRatio(DisplayMetrics dm) {
        int screenWidth = dm.widthPixels;
        int screenHeight = dm.heightPixels;
        int min = Math.min(screenWidth, screenHeight);
        if (min > 0) {
            screenRatio = (float) Math.max(screenWidth, screenHeight) / (float) min;
        }
    }

    @Override
    public Resources getResources() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            AutoSizeCompat.autoConvertDensityOfCustomAdapt(super.getResources(), this);
        }
        return super.getResources();
    }

    public boolean hasPermission(String permission) {
        boolean has = true;
        try {
            has = PermissionChecker.checkSelfPermission(this, permission) == PermissionChecker.PERMISSION_GRANTED;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return has;
    }

    protected abstract int getLayoutResID();

    protected abstract void init();

    protected void setLoadSir(View view) {
        if (mLoadService == null) {
            mLoadService = LoadSir.getDefault().register(view, new Callback.OnReloadListener() {
                @Override
                public void onReload(View v) {
                }
            });
        }
    }

    protected void showLoading() {
        showLoading(LoadingCallback.class);
    }

    /** 允许子类换用更贴合页面内容的加载占位（例如海报骨架） */
    protected void showLoading(Class<? extends Callback> callbackClass) {
        if (mLoadService != null) {
            mLoadService.showCallback(callbackClass);
        }
    }

    protected boolean isLoading() {
        return isShowingCallback(LoadingCallback.class);
    }

    protected boolean isShowingCallback(Class<? extends Callback> callbackClass) {
        if (mLoadService != null && mLoadService.getCurrentCallback() != null) {
            return mLoadService.getCurrentCallback().equals(callbackClass);
        }
        return false;
    }

    protected void showEmpty() {
        if (null != mLoadService) {
            mLoadService.showCallback(EmptyCallback.class);
        }
    }

    protected void showSuccess() {
        if (null != mLoadService) {
            mLoadService.showSuccess();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AppManager.getInstance().finishActivity(this);
    }

    public void jumpActivity(Class<? extends BaseActivity> clazz) {
        Intent intent = new Intent(mContext, clazz);
        startActivity(intent);
    }

    /**
     * 「我的」层级的共享轴（X 轴）返回转场。
     * 正向动效由 UserFragment 在 startActivity 的 ActivityOptions 里指定，
     * 从「我的」页进入的二级页只在 finish() 里调用本方法换上反向的一组。
     */
    protected void applyMineBackTransition() {
        super.finish();
        boolean reduced = com.github.tvbox.osc.util.MotionCompat.reducedMotion(mContext);
        overridePendingTransition(
                reduced ? R.anim.anim_fade_in : R.anim.anim_shared_axis_in_reverse,
                reduced ? R.anim.anim_fade_out : R.anim.anim_shared_axis_out_reverse);
    }

    public void jumpActivity(Class<? extends BaseActivity> clazz, Bundle bundle) {
        Intent intent = new Intent(mContext, clazz);
        intent.putExtras(bundle);
        startActivity(intent);
    }

    protected String getAssetText(String fileName) {
        StringBuilder stringBuilder = new StringBuilder();
        try {
            AssetManager assets = getAssets();
            BufferedReader bf = new BufferedReader(new InputStreamReader(assets.open(fileName)));
            String line;
            while ((line = bf.readLine()) != null) {
                stringBuilder.append(line);
            }
            return stringBuilder.toString();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "";
    }

    /**
     * AutoSize 设计宽度基准。
     *
     * 原来恒为 360：竖屏没问题，但横屏时 widthPixels 变大，AutoSize 会按
     * "屏幕宽 / 360" 重算 density，于是横屏下所有 dp/sp 都被放大 2 倍多
     * （播放器抽屉、下拉框、弹幕设置全部巨大且文字截断）。
     *
     * 现在横屏时按竖屏宽高比折算设计宽度，保证横屏的 density 与竖屏一致，
     * 控件在横竖屏下视觉大小相同。
     */
    @Override
    public float getSizeInDp() {
        return baseSizeInDp;
    }

    private float baseSizeInDp = DESIGN_WIDTH_DP;

    private static final float DESIGN_WIDTH_DP = 360f;

    private void updateBaseSizeInDp() {
        try {
            DisplayMetrics dm = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(dm);
            int w = dm.widthPixels;
            int h = dm.heightPixels;
            if (w > 0 && h > 0 && w > h) {
                // 横屏：设计宽度 = 360 * (宽 / 高)，density 与竖屏保持一致
                baseSizeInDp = DESIGN_WIDTH_DP * w / (float) h;
            } else {
                baseSizeInDp = DESIGN_WIDTH_DP;
            }
        } catch (Throwable th) {
            baseSizeInDp = DESIGN_WIDTH_DP;
        }
    }

    @Override
    public boolean isBaseOnWidth() {
        return true;
    }
}
