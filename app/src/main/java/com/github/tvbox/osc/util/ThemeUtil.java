package com.github.tvbox.osc.util;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.view.View;
import android.view.Window;

import androidx.appcompat.app.AppCompatDelegate;

import com.github.tvbox.osc.R;
import com.orhanobut.hawk.Hawk;

/**
 * 界面明暗模式（日间 / 夜间 / 跟随系统）。
 *
 * <p>实现方式是 {@link AppCompatDelegate#setDefaultNightMode(int)} + {@code values-night} 资源覆盖：
 * 调用后 AppCompat 会改写 Resources 的 uiMode，从而让 {@code res/values-night/colors.xml}
 * 里的同名语义色自动生效，所有引用 {@code @color/ui_*} 的布局会一起变色。</p>
 *
 * <p>所以新增界面时**不要**再写死 {@code #0E0E12}、{@code @android:color/white} 这类字面量，
 * 一律用 {@code @color/ui_*}，否则那一块在切换主题时不会跟着变。</p>
 */
public final class ThemeUtil {

    private ThemeUtil() {
    }

    public static int getMode() {
        Object value = Hawk.get(HawkConfig.UI_MODE, HawkConfig.DEFAULT_UI_MODE);
        int mode = value instanceof Integer ? (Integer) value : HawkConfig.DEFAULT_UI_MODE;
        if (mode < HawkConfig.UI_MODE_DAY || mode > HawkConfig.UI_MODE_SYSTEM) {
            mode = HawkConfig.DEFAULT_UI_MODE;
        }
        return mode;
    }

    public static void setMode(int mode) {
        if (mode < HawkConfig.UI_MODE_DAY || mode > HawkConfig.UI_MODE_SYSTEM) {
            mode = HawkConfig.DEFAULT_UI_MODE;
        }
        Hawk.put(HawkConfig.UI_MODE, mode);
        applyMode();
    }

    public static String getModeName(int mode) {
        switch (mode) {
            case HawkConfig.UI_MODE_NIGHT:
                return "夜间";
            case HawkConfig.UI_MODE_SYSTEM:
                return "跟随系统";
            case HawkConfig.UI_MODE_DAY:
            default:
                return "日间";
        }
    }

    /** 把配置里的模式应用到 AppCompatDelegate。切换时会自动重建已启动的 Activity。 */
    public static void applyMode() {
        AppCompatDelegate.setDefaultNightMode(toNightMode(getMode()));
    }

    public static int toNightMode(int mode) {
        switch (mode) {
            case HawkConfig.UI_MODE_NIGHT:
                return AppCompatDelegate.MODE_NIGHT_YES;
            case HawkConfig.UI_MODE_SYSTEM:
                return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            case HawkConfig.UI_MODE_DAY:
            default:
                return AppCompatDelegate.MODE_NIGHT_NO;
        }
    }

    /** 当前是否处于夜间（读的是 Resources 的 uiMode，跟随系统时会随系统变化） */
    public static boolean isNight(Context context) {
        if (context == null) return false;
        int uiMode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return uiMode == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * 状态栏 / 导航栏跟着主题走：日间用浅色底 + 深色图标，夜间用深色底 + 浅色图标。
     * 主题切换后 Activity 会重建，这里在 onCreate 里调一次即可。
     */
    public static void applySystemBars(Activity activity) {
        if (activity == null) return;
        Window window = activity.getWindow();
        if (window == null) return;
        boolean night = isNight(activity);
        // ui_bg 在 values-night 里有同名覆盖，这里取到的已经是当前主题对应的底色
        int barColor = activity.getResources().getColor(R.color.ui_bg);
        window.setStatusBarColor(barColor);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.setNavigationBarColor(barColor);
        }
        View decor = window.getDecorView();
        if (decor == null) return;
        int flags = decor.getSystemUiVisibility();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (night) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (night) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        decor.setSystemUiVisibility(flags);
    }

    /** 方便布局里用：当前主题的页面底色 */
    public static int bgColor(Context context) {
        return context.getResources().getColor(R.color.ui_bg);
    }
}
