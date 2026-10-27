package com.github.tvbox.osc.base;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.PermissionChecker;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.callback.EmptyCallback;
import com.github.tvbox.osc.callback.LoadingCallback;
import com.github.tvbox.osc.util.AppManager;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;
import com.kingja.loadsir.callback.Callback;
import com.kingja.loadsir.core.LoadService;
import com.kingja.loadsir.core.LoadSir;

import java.io.BufferedReader;
import java.io.File;
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
        changeWallpaper(false);
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

    protected static BitmapDrawable globalWp = null;

    public void changeWallpaper(boolean force) {
        if (!force && globalWp != null) {
            getWindow().setBackgroundDrawable(globalWp);
            releaseContentBackground(true);
            applyWallpaperChrome(true);
            return;
        }
        try {
            File wp = new File(getFilesDir().getAbsolutePath() + "/wp");
            if (wp.exists()) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(wp.getAbsolutePath(), opts);
                // 从Options中获取图片的分辨率
                int imageHeight = opts.outHeight;
                int imageWidth = opts.outWidth;
                int picHeight = 720;
                int picWidth = 1080;
                int scaleX = imageWidth / picWidth;
                int scaleY = imageHeight / picHeight;
                int scale = 1;
                if (scaleX > scaleY && scaleY >= 1) {
                    scale = scaleX;
                }
                if (scaleX < scaleY && scaleX >= 1) {
                    scale = scaleY;
                }
                opts.inJustDecodeBounds = false;
                // 采样率
                opts.inSampleSize = scale;
                globalWp = new BitmapDrawable(BitmapFactory.decodeFile(wp.getAbsolutePath(), opts));
            } else {
                globalWp = null;
            }
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            globalWp = null;
        }
        if (globalWp != null) {
            getWindow().setBackgroundDrawable(globalWp);
            releaseContentBackground(true);
            applyWallpaperChrome(true);
        } else if (Hawk.get(HawkConfig.WALLPAPER_COLOR, 0) != 0) {
            getWindow().setBackgroundDrawable(new ColorDrawable(Hawk.get(HawkConfig.WALLPAPER_COLOR, 0)));
            releaseContentBackground(true);
            applyWallpaperChrome(true);
        } else {
            // 没有配图也没有纯色：底色交给主题（日间白 / 夜间深），
            // 不再固定用 app_bg.jpg —— 那张是深色图，日间模式下会和白色文字打架。
            getWindow().setBackgroundDrawable(new ColorDrawable(
                    getResources().getColor(R.color.ui_bg)));
            releaseContentBackground(false);
            applyWallpaperChrome(false);
        }
    }

    /**
     * 让窗口背景（配图 / 纯色）能真正透出来。
     *
     * <p>页面根布局自带 {@code @color/ui_bg} 底色，会把 window background 整个盖住——
     * 这正是之前「设置了配图/纯色，全局却不生效」的原因。
     * 用户设了自定义背景时把整棵树上所有「页面底色」抹成透明，没设时还原
     * （保持不透明可以让 GPU 少画一层全屏底色）。</p>
     */
    private void releaseContentBackground(boolean transparent) {
        View content = findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) content;
        for (int i = 0; i < group.getChildCount(); i++) {
            applyPageBackground(group.getChildAt(i), transparent, true);
        }
    }

    /**
     * 递归处理页面底色。
     *
     * <p>只动「颜色正好等于 ui_bg」的 ColorDrawable —— Fragment 页、ViewPager 里的
     * 列表容器都各自铺了一层 ui_bg，不递归的话配图只能从顶栏、底栏那两条缝里露出来。</p>
     */
    private void applyPageBackground(View view, boolean transparent, boolean root) {
        if (view == null) return;
        int uiBg = getResources().getColor(R.color.ui_bg);
        Drawable bg = view.getBackground();
        if (bg instanceof ColorDrawable) {
            ColorDrawable colorDrawable = (ColorDrawable) bg;
            if (transparent) {
                if (colorDrawable.getColor() == uiBg) {
                    colorDrawable.setColor(Color.TRANSPARENT);
                    view.setTag(R.id.tag_page_bg_transparent, Boolean.TRUE);
                }
            } else if (Boolean.TRUE.equals(view.getTag(R.id.tag_page_bg_transparent))) {
                colorDrawable.setColor(uiBg);
                view.setTag(R.id.tag_page_bg_transparent, null);
            }
        } else if (root && bg == null) {
            view.setBackgroundColor(transparent ? Color.TRANSPARENT : uiBg);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyPageBackground(group.getChildAt(i), transparent, false);
            }
        }
    }

    /**
     * 壁纸模式下把顶栏、底栏、卡片换成半透明，让配图真的透出来。
     *
     * <p>做法是直接改这几张底图的共享 ConstantState：Resources 里同一张 drawable 的
     * ConstantState 是全进程共享的，改一次之后新 inflate 出来的列表 item 也会自动是半透明的，
     * 不用挨个页面去 findViewById。恢复时再按语义色写回不透明版本。</p>
     */
    private void applyWallpaperChrome(boolean glass) {
        int[] resIds = {R.drawable.bg_appbar, R.drawable.bg_bottom_nav, R.drawable.bg_phone_card};
        int[] solidColors = {R.color.ui_surface, R.color.ui_material, R.color.ui_surface};
        int[] glassColors = {R.color.ui_surface_glass, R.color.ui_material_glass, R.color.ui_surface_glass};
        for (int i = 0; i < resIds.length; i++) {
            try {
                Drawable drawable = loadThemedDrawable(resIds[i]);
                if (drawable instanceof GradientDrawable) {
                    ((GradientDrawable) drawable).setColor(getResources().getColor(
                            glass ? glassColors[i] : solidColors[i]));
                }
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
        // 直接写 @color/ui_surface / @color/ui_bar_bg 的控件（首页页签条、收藏/历史底部操作条）
        View content = findViewById(android.R.id.content);
        if (content instanceof ViewGroup) {
            tintColorSurfaces((ViewGroup) content, glass);
        }
    }

    private void tintColorSurfaces(ViewGroup parent, boolean glass) {
        int[] solidColors = {R.color.ui_surface, R.color.ui_bar_bg, R.color.ui_material};
        int[] glassColors = {R.color.ui_surface_glass, R.color.ui_bar_glass, R.color.ui_material_glass};
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child == null) continue;
            Drawable bg = child.getBackground();
            if (bg instanceof ColorDrawable) {
                ColorDrawable colorDrawable = (ColorDrawable) bg;
                int color = colorDrawable.getColor();
                for (int k = 0; k < solidColors.length; k++) {
                    int solid = getResources().getColor(solidColors[k]);
                    int glassColor = getResources().getColor(glassColors[k]);
                    if (glass && color == solid) {
                        colorDrawable.setColor(glassColor);
                        break;
                    } else if (!glass && color == glassColor) {
                        colorDrawable.setColor(solid);
                        break;
                    }
                }
            }
            if (child instanceof ViewGroup) {
                tintColorSurfaces((ViewGroup) child, glass);
            }
        }
    }

    private Drawable loadThemedDrawable(int resId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return getResources().getDrawable(resId, getTheme());
        }
        return getResources().getDrawable(resId);
    }

    /** 立即刷新背景（设置页里切换纯色后即时预览用） */
    public void applyWallpaperNow() {
        globalWp = null;
        changeWallpaper(true);
    }
}