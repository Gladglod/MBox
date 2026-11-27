package com.github.tvbox.osc.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 壁纸 / 纯色背景下的「玻璃化」统一处理。
 *
 * <p>旧做法只把 3 张 drawable 的 ConstantState 改成 72% 不透明的固定白/深灰，
 * 其余卡片、胶囊、搜索框、玻璃导航条仍是实色；而且纯色背景选到 #FFFFFF 时
 * 白玻璃和白底完全糊在一起，看起来就是「壁纸没生效」。</p>
 *
 * <p>现在改成两步：
 * <ol>
 *   <li>按当前背景（配图取平均亮度，纯色取色值亮度）和明暗主题算出一组
 *       <b>自适应玻璃色</b>：目标是让表面在背景上保持足够对比度，
 *       但只用最小必要的不透明度，背景色能透出来；</li>
 *   <li>把这组颜色写到所有 UI 表面 drawable 的共享 ConstantState 上，
 *       并递归改页面里直接 setBackgroundColor 的控件。Resources 的
 *       ConstantState 是进程共享的，所以之后新 inflate 的列表项也会自动是玻璃。</li>
 * </ol>
 * </p>
 *
 * <p>没有壁纸时全部按快照还原，页面外观和以前完全一致。</p>
 */
public final class WallpaperGlass {

    /** 需要玻璃化的 UI 表面。播放器 / 海报 / 进度条等深色浮层不在此列。 */
    private static final int[] SURFACE_DRAWABLES = {
            R.drawable.bg_appbar,
            R.drawable.bg_bottom_nav,
            R.drawable.bg_phone_card,
            R.drawable.bg_chip,
            R.drawable.bg_chip_selected,
            R.drawable.bg_chip_live,
            R.drawable.bg_series_pick_chip,
            R.drawable.bg_search_field,
            R.drawable.bg_search_field_normal,
            R.drawable.bg_search_field_pressed,
            R.drawable.bg_input_field,
            R.drawable.bg_input_field_normal,
            R.drawable.bg_input_field_focused,
            R.drawable.bg_history_card,
            R.drawable.bg_history_thumb,
            R.drawable.bg_panel_item,
            R.drawable.bg_panel_item_focused,
            R.drawable.bg_glass_chip,
            R.drawable.bg_glass_chip_selected,
            R.drawable.bg_glass_card_chip,
            R.drawable.bg_glass_card_pill,
            R.drawable.bg_glass_card_circle,
            R.drawable.bg_glass_group,
            R.drawable.bg_glass_tabbar,
            R.drawable.bg_glass_bottom_bar,
            R.drawable.bg_glass_menu,
            R.drawable.bg_dialog_sheet,
            R.drawable.bg_live_setting_panel,
            R.drawable.bg_discover_sheet
    };

    /** 明暗主题各存一份原始颜色快照，切换主题后也能正确还原 */
    @SuppressWarnings("unchecked")
    private static final SparseArray<List<Object>>[] ORIGINAL = new SparseArray[]{new SparseArray(), new SparseArray()};
    @SuppressWarnings("unchecked")
    private static final SparseArray<Boolean>[] APPLIED = new SparseArray[]{new SparseArray(), new SparseArray()};

    /** 当前主题下的 solid -> glass 颜色映射 */
    private static final SparseIntArray GLASS_MAP = new SparseIntArray();

    private static Context sContext;

    private WallpaperGlass() {
    }

    /**
     * 根据当前背景重新计算玻璃色。
     *
     * @param wallpaper  配图壁纸，可为空
     * @param solidColor 纯色背景，0 表示没有
     */
    public static void configure(Context context, Bitmap wallpaper, int solidColor) {
        sContext = context.getApplicationContext();
        GLASS_MAP.clear();

        float backgroundLuminance;
        if (wallpaper != null && !wallpaper.isRecycled()) {
            backgroundLuminance = bitmapLuminance(wallpaper);
        } else if (solidColor != 0) {
            backgroundLuminance = luminance(solidColor);
        } else {
            backgroundLuminance = -1f;
        }

        boolean night = ThemeUtil.isNight(context);
        // 目标亮度：日间表面保持浅但不纯白，夜间保持深但不纯黑。
        // 与背景亮度差越大 -> 需要的遮罩越浓；差越小 -> 越透明，壁纸越明显。
        int surface = blend(backgroundLuminance, night ? 0.14f : 0.88f);
        int bar = blend(backgroundLuminance, night ? 0.11f : 0.91f);
        int chip = blend(backgroundLuminance, night ? 0.19f : 0.83f);
        int panel = blend(backgroundLuminance, night ? 0.08f : 0.93f);
        int row = blend(backgroundLuminance, night ? 0.16f : 0.86f);

        put(R.color.ui_surface, surface);
        put(R.color.ui_surface_alt, chip);
        put(R.color.ui_surface_pressed, chip);
        put(R.color.ui_poster_placeholder, chip);
        put(R.color.glass_card_fill, surface);
        put(R.color.ui_bar_bg, bar);
        put(R.color.ui_material, bar);
        put(R.color.glass_bar_bg, bar);
        put(R.color.ui_chip_bg, chip);
        put(R.color.ui_accent_soft, row);
        put(R.color.glass_row_fill, row);
        put(R.color.dialog_control_bg, row);
        put(R.color.dialog_input_bg, row);
        put(R.color.glass_menu_bg, panel);
        put(R.color.dialog_panel_bg, panel);

        // 少数 drawable 里直接写了白色，没有走语义色
        GLASS_MAP.put(0xFFFFFFFF, surface);
        GLASS_MAP.put(0xFFF8F9FB, panel);
        GLASS_MAP.put(0xFFF0F1F5, chip);
        GLASS_MAP.put(0xFFE4E6EC, chip);
        GLASS_MAP.put(0xF5FFFFFF, bar);
        GLASS_MAP.put(0xF2FFFFFF, panel);
        GLASS_MAP.put(0xD9FFFFFF, surface);
        GLASS_MAP.put(0xA6FFFFFF, row);
    }

    private static void put(int colorRes, int glassColor) {
        GLASS_MAP.put(color(sContext, colorRes), glassColor);
    }

    /** 当前主题下，surface drawable 的 ConstantState 整体打开 / 关闭玻璃化 */
    public static void applyToResources(Context context, boolean glass) {
        if (sContext == null) configure(context, null, 0);
        int theme = ThemeUtil.isNight(context) ? 1 : 0;
        SparseArray<List<Object>> snapshots = ORIGINAL[theme];
        SparseArray<Boolean> states = APPLIED[theme];

        for (int resId : SURFACE_DRAWABLES) {
            if (Boolean.valueOf(glass).equals(states.get(resId))) continue;
            Drawable drawable;
            try {
                drawable = ContextCompat.getDrawable(context, resId);
            } catch (Throwable ignored) {
                continue;
            }
            if (drawable == null) continue;

            List<Object> snapshot = snapshots.get(resId);
            if (snapshot == null) {
                snapshot = new ArrayList<Object>();
                snapshotDrawable(drawable, snapshot);
                snapshots.put(resId, snapshot);
            }
            if (glass) {
                applyDrawable(drawable, snapshot, true);
            } else {
                applyDrawable(drawable, snapshot, false);
            }
            states.put(resId, glass);
        }
    }

    /** 递归处理页面里直接写 @color/ui_surface 这类纯色的控件 */
    public static void applyToViewTree(View view, boolean glass) {
        if (view == null) return;
        Drawable bg = view.getBackground();
        if (bg instanceof ColorDrawable) {
            ColorDrawable cd = (ColorDrawable) bg;
            int original = cd.getColor();
            if (glass) {
                int glassColor = glassFor(original);
                if (glassColor != original) {
                    view.setTag(R.id.tag_glass_original, original);
                    cd.setColor(glassColor);
                }
            } else {
                Object tag = view.getTag(R.id.tag_glass_original);
                if (tag instanceof Integer) {
                    cd.setColor((Integer) tag);
                    view.setTag(R.id.tag_glass_original, null);
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyToViewTree(group.getChildAt(i), glass);
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* drawable 递归：ColorDrawable / GradientDrawable / selector / layer    */
    /* ------------------------------------------------------------------ */

    private static void snapshotDrawable(Drawable drawable, List<Object> out) {
        if (drawable instanceof ColorDrawable) {
            out.add(((ColorDrawable) drawable).getColor());
        } else if (drawable instanceof GradientDrawable) {
            // getStrokeColor()/getStrokeWidth() 是隐藏 API，描边保持原样
            out.add(((GradientDrawable) drawable).getColor());
        } else if (drawable instanceof LayerDrawable) {
            LayerDrawable ld = (LayerDrawable) drawable;
            for (int i = 0; i < ld.getNumberOfLayers(); i++) {
                snapshotDrawable(ld.getDrawable(i), out);
            }
        }
        // selector 的子项不在这里递归：它们引用的都是独立 drawable 资源，
        // 那些资源本身在 SURFACE_DRAWABLES 里，会被单独转换。
    }

    private static void applyDrawable(Drawable drawable, List<Object> snapshot, boolean glass) {
        int[] cursor = new int[]{0};
        applyDrawableRecursive(drawable, snapshot, glass, cursor);
    }

    private static void applyDrawableRecursive(Drawable drawable, List<Object> snapshot,
                                               boolean glass, int[] cursor) {
        if (drawable == null) return;
        if (drawable instanceof ColorDrawable) {
            if (cursor[0] >= snapshot.size()) return;
            Object original = snapshot.get(cursor[0]++);
            if (original instanceof Integer) {
                ((ColorDrawable) drawable).setColor(glass
                        ? glassFor((Integer) original) : (Integer) original);
            }
        } else if (drawable instanceof GradientDrawable) {
            GradientDrawable gd = (GradientDrawable) drawable;
            if (cursor[0] < snapshot.size()) {
                Object fill = snapshot.get(cursor[0]++);
                if (fill instanceof ColorStateList) {
                    gd.setColor(glass ? convert((ColorStateList) fill) : (ColorStateList) fill);
                }
            }
        } else if (drawable instanceof LayerDrawable) {
            LayerDrawable ld = (LayerDrawable) drawable;
            for (int i = 0; i < ld.getNumberOfLayers(); i++) {
                applyDrawableRecursive(ld.getDrawable(i), snapshot, glass, cursor);
            }
        }
    }

    /** getColors()/getStates() 都是隐藏 API，用一组常见状态逐个取值即可（填充色通常各状态相同） */
    private static final int[][] COMMON_STATES = {
            new int[0],
            new int[]{android.R.attr.state_pressed},
            new int[]{android.R.attr.state_focused},
            new int[]{android.R.attr.state_selected},
            new int[]{android.R.attr.state_checked},
            new int[]{-android.R.attr.state_enabled}
    };

    private static ColorStateList convert(ColorStateList source) {
        if (source == null) return null;
        int defaultColor = source.getDefaultColor();
        int[] converted = new int[COMMON_STATES.length];
        for (int i = 0; i < COMMON_STATES.length; i++) {
            converted[i] = glassFor(source.getColorForState(COMMON_STATES[i], defaultColor));
        }
        return new ColorStateList(COMMON_STATES, converted);
    }

    private static int glassFor(int color) {
        int alpha = Color.alpha(color);
        // 高光、描边、透明占位不动，否则玻璃的层次会一起消失
        if (alpha < 128) return color;
        return GLASS_MAP.get(color, color);
    }

    /* ------------------------------------------------------------------ */
    /* 亮度计算                                                             */
    /* ------------------------------------------------------------------ */

    private static float luminance(int color) {
        return (0.299f * Color.red(color) + 0.587f * Color.green(color)
                + 0.114f * Color.blue(color)) / 255f;
    }

    private static float bitmapLuminance(Bitmap bitmap) {
        try {
            Bitmap small = Bitmap.createScaledBitmap(bitmap, 24, 24, true);
            long sum = 0;
            int count = 0;
            for (int y = 0; y < small.getHeight(); y += 2) {
                for (int x = 0; x < small.getWidth(); x += 2) {
                    sum += Math.round(luminance(small.getPixel(x, y)) * 1000);
                    count++;
                }
            }
            if (small != bitmap) small.recycle();
            return count == 0 ? 0.5f : sum / (float) count / 1000f;
        } catch (Throwable ignored) {
            return 0.5f;
        }
    }

    /**
     * 生成刚好把背景拉到 targetLum 的半透明遮罩。
     * 背景本来就近目标色时只加一层 6% 的反向罩子，避免表面完全消失。
     */
    private static int blend(float backgroundLuminance, float targetLum) {
        if (backgroundLuminance < 0f) return Color.TRANSPARENT;
        float alpha;
        int tint;
        if (backgroundLuminance > targetLum + 0.02f) {
            alpha = (backgroundLuminance - targetLum) / Math.max(backgroundLuminance, 0.01f);
            tint = Color.BLACK;
        } else if (backgroundLuminance < targetLum - 0.02f) {
            alpha = (targetLum - backgroundLuminance) / Math.max(1f - backgroundLuminance, 0.01f);
            tint = Color.WHITE;
        } else {
            alpha = 0.06f;
            tint = ThemeUtil.isNight(sContext) ? Color.WHITE : Color.BLACK;
        }
        alpha = Math.max(0.06f, Math.min(0.94f, alpha));
        return Color.argb(Math.round(alpha * 255f), Color.red(tint), Color.green(tint), Color.blue(tint));
    }

    private static int color(Context context, int resId) {
        return ContextCompat.getColor(context, resId);
    }
}
