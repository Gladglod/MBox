package com.github.tvbox.osc.ui.widget;

import android.content.Context;
import android.util.DisplayMetrics;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 「平滑回到列表顶部」专用 Scroller。
 *
 * <p>为什么不直接用 {@code recyclerView.smoothScrollToPosition(0)}：</p>
 * <ul>
 *     <li>当 0 号 item 已经在屏幕上（哪怕只露出一半）时，它判定「目标可见」就直接不动了；</li>
 *     <li>速度由框架按固定公式推算，没法调，短列表一闪而过、长列表又慢得难受。</li>
 * </ul>
 *
 * <p>{@link LinearSmoothScroller} 的 {@code SNAP_TO_START} 会强制把 0 号 item 的顶部对齐到
 * 列表内边距的顶部，所以「半露」的情况也能精确收尾；速度则由
 * {@link #calculateSpeedPerPixel(DisplayMetrics)} 按「每英寸毫秒数」换算，可以自由调节。</p>
 *
 * <p>另外做了一个实用取舍：距离顶部超过 {@link #MAX_SMOOTH_DISTANCE} 条时直接
 * {@code scrollToPosition(0)} 跳过去。因为平滑滚过几十屏会把中间所有海报都加载一遍，
 * 反而更卡——用户在那个距离上期待的也是「跳回去」。</p>
 */
public class SmoothTopScroller extends LinearSmoothScroller {

    /** 每英寸耗时（毫秒），越大越慢。默认 28ms/inch，比框架的 25ms 略慢一点，收尾更从容 */
    public static final float DEFAULT_MILLIS_PER_INCH = 28f;

    /** 离顶部超过这么多条就不再平滑，直接跳 */
    public static final int MAX_SMOOTH_DISTANCE = 12;

    private final float millisPerInch;

    public SmoothTopScroller(Context context) {
        this(context, DEFAULT_MILLIS_PER_INCH);
    }

    public SmoothTopScroller(Context context, float millisPerInch) {
        super(context);
        this.millisPerInch = millisPerInch <= 0 ? DEFAULT_MILLIS_PER_INCH : millisPerInch;
    }

    @Override
    protected float calculateSpeedPerPixel(DisplayMetrics displayMetrics) {
        return millisPerInch / displayMetrics.densityDpi;
    }

    @Override
    protected int getVerticalSnapPreference() {
        // 关键：让 0 号 item 精确贴到顶部，而不是「可见就停」
        return SNAP_TO_START;
    }

    @Override
    protected int getHorizontalSnapPreference() {
        return SNAP_TO_START;
    }

    /**
     * 平滑回到顶部。
     *
     * @return true 表示确实触发了滚动；已经在顶部（或列表为空）时返回 false
     */
    public static boolean scrollToTop(RecyclerView recyclerView) {
        return scrollToTop(recyclerView, DEFAULT_MILLIS_PER_INCH);
    }

    /**
     * 平滑回到顶部，并指定速度。
     *
     * @param millisPerInch 每英寸毫秒数，越大越慢；传 <= 0 用默认值
     * @return true 表示确实触发了滚动
     */
    public static boolean scrollToTop(RecyclerView recyclerView, float millisPerInch) {
        if (recyclerView == null) return false;
        RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
        if (layoutManager == null) return false;
        RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
        if (adapter == null || adapter.getItemCount() == 0) return false;
        // 已经没有可向上滚动的余量（0 号 item 完整贴在顶部）
        if (!recyclerView.canScrollVertically(-1)) return false;

        if (layoutManager instanceof LinearLayoutManager) {
            int firstVisible = ((LinearLayoutManager) layoutManager).findFirstVisibleItemPosition();
            if (firstVisible > MAX_SMOOTH_DISTANCE) {
                recyclerView.scrollToPosition(0);
                return true;
            }
        }
        // startSmoothScroll 要求 LayoutManager 实现 ScrollVectorProvider，否则会抛异常
        if (!(layoutManager instanceof RecyclerView.SmoothScroller.ScrollVectorProvider)) {
            recyclerView.scrollToPosition(0);
            return true;
        }
        SmoothTopScroller scroller = new SmoothTopScroller(recyclerView.getContext(), millisPerInch);
        scroller.setTargetPosition(0);
        layoutManager.startSmoothScroll(scroller);
        return true;
    }
}
