package com.github.tvbox.osc.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * 筛选选项的流式排布容器。
 *
 * <p>分类数据里每个筛选维度的选项数量不固定，横向 RecyclerView 会把后面的选项藏起来，
 * GridView 又要求等宽。这里只需要按宽度换行，自己量一次就够了，不必为它引入额外依赖。</p>
 */
public class FilterChipGroup extends ViewGroup {
    private final int verticalGap;

    public FilterChipGroup(Context context) {
        this(context, null);
    }

    public FilterChipGroup(Context context, AttributeSet attrs) {
        super(context, attrs);
        verticalGap = dp(8);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    protected boolean checkLayoutParams(LayoutParams p) {
        return p instanceof MarginLayoutParams;
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new MarginLayoutParams(getContext(), attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        int maxWidth = widthMode == MeasureSpec.UNSPECIFIED ? Integer.MAX_VALUE : widthSize;
        int rightBound = maxWidth - getPaddingRight();

        int left = getPaddingLeft();
        int usedWidth = left;
        int lineHeight = 0;
        int totalHeight = getPaddingTop() + getPaddingBottom();

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
            MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            int childWidth = child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            int childHeight = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
            if (left > getPaddingLeft() && left + childWidth > rightBound) {
                totalHeight += lineHeight + verticalGap;
                left = getPaddingLeft();
                lineHeight = 0;
            }
            left += childWidth;
            usedWidth = Math.max(usedWidth, left + getPaddingRight());
            lineHeight = Math.max(lineHeight, childHeight);
        }
        totalHeight += lineHeight;

        int measuredWidth = widthMode == MeasureSpec.EXACTLY ? widthSize : Math.min(usedWidth, maxWidth);
        int measuredHeight = heightMode == MeasureSpec.EXACTLY ? heightSize : totalHeight;
        setMeasuredDimension(measuredWidth, measuredHeight);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int left = getPaddingLeft();
        int top = getPaddingTop();
        int lineHeight = 0;
        int rightBound = getWidth() - getPaddingRight();

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            int childWidth = child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            int childHeight = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
            if (left > getPaddingLeft() && left + childWidth > rightBound) {
                top += lineHeight + verticalGap;
                left = getPaddingLeft();
                lineHeight = 0;
            }
            int childLeft = left + lp.leftMargin;
            int childTop = top + lp.topMargin;
            child.layout(childLeft, childTop,
                    childLeft + child.getMeasuredWidth(), childTop + child.getMeasuredHeight());
            left += childWidth;
            lineHeight = Math.max(lineHeight, childHeight);
        }
    }
}
