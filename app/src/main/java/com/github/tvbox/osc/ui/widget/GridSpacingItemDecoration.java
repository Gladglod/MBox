package com.github.tvbox.osc.ui.widget;

import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 网格等距间隔。
 *
 * <p>首页「热门推荐」与分类页共用同一套网格，间距必须一致——
 * 以前只有分类页加了间距，落地页是 0dp，两页来回切会明显感到"呼吸感"不同。</p>
 */
public class GridSpacingItemDecoration extends RecyclerView.ItemDecoration {

    private final int space;

    public GridSpacingItemDecoration(int space) {
        this.space = space;
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                               @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        outRect.left = space;
        outRect.right = space;
        outRect.top = space;
        outRect.bottom = space;
    }
}
