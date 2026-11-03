package com.github.tvbox.osc.ui.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;

import androidx.annotation.Nullable;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

/**
 * 「我的」页分组容器 —— 玻璃材质的设置卡片。
 *
 * <ul>
 *   <li>半透明白/深灰填充 + 1dp 高光描边 + 26dp 大圆角（{@link R.drawable#bg_glass_group}）；</li>
 *   <li>Android 12（API 31）+ 且窗口背景是壁纸配图时，在填充下面垫一层
 *       「预模糊壁纸」backdrop：先把壁纸降采样到约 96px 再线性放大，
 *       相当于一次廉价的霜化模糊，所有卡片复用同一张小图，滚动时只做位图裁切；</li>
 *   <li>纯色背景、无壁纸或 Android 12 以下自动退化 —— 半透明实色 + 细边框，
 *       不画任何额外内容（<code>draw()</code> 直接走原生路径）。</li>
 * </ul>
 *
 * <p>原则：「玻璃容器 + 实色行」——玻璃只负责容器材质，行永远铺在
 * 85% 左右不透明度的填充之上，复杂背景下标题文字不会发虚。
 * 虚化只出现在填充底下的壁纸透出部分。</p>
 */
public class GlassGroupView extends android.widget.LinearLayout {

    /** 圆角必须和 bg_glass_group.xml 里的 26dp 对齐（裁剪、backdrop 收边都用它） */
    private static final int CORNER_RADIUS_DP = 26;

    /** 预模糊小图的“最长边”像素量级：太小出锯齿，太大失去雾化感 */
    private static final float BLUR_MAX_EDGE = 96f;

    /** 全局共享的预模糊壁纸：所有玻璃卡片复用一张，壁纸更换时按位图身份重建 */
    private static Bitmap sBlurredWallpaper;
    private static Bitmap sBlurredSource;

    private final float cornerRadius;
    private final Paint backdropPaint;

    private final ViewOutlineProvider outlineProvider = new ViewOutlineProvider() {
        @Override
        public void getOutline(View view, Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius);
        }
    };

    public GlassGroupView(Context context) {
        this(context, null);
    }

    public GlassGroupView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public GlassGroupView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        cornerRadius = CORNER_RADIUS_DP * context.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        // 玻璃材质：半透明填充 + 高光描边 + 顶部反光
        setBackgroundResource(R.drawable.bg_glass_group);
        setOutlineProvider(outlineProvider);
        setClipToOutline(true); // 展开动画伸缩时，超出部分裁掉
        setClipChildren(true);

        backdropPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    }

    /**
     * 玻璃容器的绘制顺序：
     * 1) backdrop（预模糊壁纸，可选） —— 最底层；
     * 2) bg drawable —— 半透明填充压在模糊之上做「玻璃染色」，描边/顶高光给出边缘；
     * 3) dispatchDraw —— 实色行内容。
     */
    @Override
    public void draw(Canvas canvas) {
        // blur 属于锦上添花，任何位图/坐标异常都不能影响页面渲染，失败时退化为纯玻璃
        try {
            drawBackdropIfNeeded(canvas);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        super.draw(canvas);
    }

    private void drawBackdropIfNeeded(Canvas canvas) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        if (canvas == null || getWidth() <= 0 || getHeight() <= 0) return;
        // 纯色背景：blur 没有意义，保持半透明实色降级
        try {
            if (Hawk.get(HawkConfig.WALLPAPER_COLOR, 0) != 0) return;
        } catch (Throwable th) {
            return;
        }
        Bitmap source = BaseActivity.currentWallpaperBitmap();
        if (source == null || source.isRecycled()) return;
        Bitmap blurred = ensureBlurred(source);
        if (blurred == null) return;

        // 壁纸铺满整个窗口（BitmapDrawable 默认 FILL 铺满 DecorView），
        // 把这个玻璃卡片在窗口里的偏移映射到模糊小图上：滚动改变卡片在窗口中的
        // 位置，每帧 draw 时重映射，保证模糊区域和壁纸后面那块始终对齐。
        View root = this;
        while (root.getParent() instanceof View) {
            root = (View) root.getParent();
        }
        int rootW = root.getWidth(), rootH = root.getHeight();
        if (rootW <= 0 || rootH <= 0) return;

        int[] rootPos = new int[2];
        root.getLocationOnScreen(rootPos);
        int[] selfPos = new int[2];
        getLocationOnScreen(selfPos);
        float offsetX = selfPos[0] - rootPos[0]; // 卡片相对窗口左上角的偏移
        float offsetY = selfPos[1] - rootPos[1];

        Matrix m = new Matrix();
        // 模糊小图 → 窗口坐标（非均匀拉伸，和窗口背景的实际铺法一致）
        m.setScale(rootW / (float) blurred.getWidth(),
                rootH / (float) blurred.getHeight());
        m.postTranslate(-offsetX, -offsetY); // 窗口坐标 → 卡片局部坐标

        canvas.save();
        canvas.concat(m);
        canvas.drawBitmap(blurred, 0, 0, backdropPaint);
        canvas.restore();
    }

    private Bitmap ensureBlurred(Bitmap source) {
        if (sBlurredSource == source && sBlurredWallpaper != null
                && !sBlurredWallpaper.isRecycled()) {
            return sBlurredWallpaper;
        }
        int w = source.getWidth(), h = source.getHeight();
        if (w <= 0 || h <= 0) return null;
        float scale = Math.max(w, h) / BLUR_MAX_EDGE;
        if (scale < 1f) return null; // 壁纸本来就不大，直接透出去即可
        int tw = Math.max(4, Math.round(w / scale));
        int th = Math.max(4, Math.round(h / scale));
        Bitmap out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        Matrix m = new Matrix();
        m.setScale(tw / (float) w, th / (float) h);
        c.drawBitmap(source, m, p);
        sBlurredSource = source;
        sBlurredWallpaper = out;
        return out;
    }
}
