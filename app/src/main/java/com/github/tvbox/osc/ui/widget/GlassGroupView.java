package com.github.tvbox.osc.ui.widget;

import android.content.Context;
import android.graphics.Outline;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;

import androidx.annotation.Nullable;

import com.github.tvbox.osc.R;

/**
 * 「我的」页分组容器 —— 玻璃材质的设置卡片。
 *
 * <p>半透明白/深灰填充 + 1dp 高光描边 + 26dp 大圆角（{@link R.drawable#bg_glass_group}），
 * 颜色随日间 / 夜间主题自动切换。行内容铺在容器之上，保持 titles 可读。</p>
 */
public class GlassGroupView extends android.widget.LinearLayout {

    /** 圆角必须和 bg_glass_group.xml 里的 26dp 对齐 */
    private static final int CORNER_RADIUS_DP = 26;

    private final float cornerRadius;

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
        setBackgroundResource(R.drawable.bg_glass_group);
        setOutlineProvider(outlineProvider);
        setClipToOutline(true); // 展开动画伸缩时，超出部分裁掉
        setClipChildren(true);
    }
}
