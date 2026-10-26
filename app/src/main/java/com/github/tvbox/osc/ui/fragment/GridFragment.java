package com.github.tvbox.osc.ui.fragment;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;

import android.widget.TextView;
import android.widget.Toast;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.FastSearchActivity;
import com.github.tvbox.osc.ui.activity.SearchActivity;
import com.github.tvbox.osc.ui.adapter.GridAdapter;
import com.github.tvbox.osc.ui.adapter.GridFilterKVAdapter;
import com.github.tvbox.osc.ui.dialog.GridFilterDialog;
import com.github.tvbox.osc.ui.tv.widget.LoadMoreView;
import com.github.tvbox.osc.ui.widget.SmoothTopScroller;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.ImgUtil;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

import org.greenrobot.eventbus.EventBus;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * @author pj567
 * @date :2020/12/21
 * @description:
 */
public class GridFragment extends BaseLazyFragment {
    private MovieSort.SortData sortData = null;
    private RecyclerView mGridView;
    private SourceViewModel sourceViewModel;
    private GridFilterDialog gridFilterDialog;
    private GridAdapter gridAdapter;
    private int page = 1;
    private int maxPage = 1;
    private boolean isLoad = false;
    private boolean isRequesting = false;
    private boolean hasActionItems = false;
    private boolean isTop = true;
    private float pullRefreshStartX;
    private float pullRefreshStartY;
    private boolean pullRefreshStartAtTop = false;
    private boolean pullRefreshReady = false;
    private int pullRefreshThreshold = 0;
    private View loadSirView = null;

    private static class GridInfo {
        public String sortID = "";
        public RecyclerView mGridView;
        public GridAdapter gridAdapter;
        public int page = 1;
        public int maxPage = 1;
        public boolean isLoad = false;
        public boolean hasActionItems = false;
    }
    Stack<GridInfo> mGrids = new Stack<GridInfo>(); //ui栈

    public static GridFragment newInstance(MovieSort.SortData sortData) {
        return new GridFragment().setArguments(sortData);
    }

    public GridFragment setArguments(MovieSort.SortData sortData) {
        this.sortData = sortData;
        return this;
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_grid;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
    }

    @Override
    protected void init() {
        initView();
        initViewModel();
        initData();
    }

    /**
     * 首页每个分类都是一个 GridFragment，数量多。ViewPager 把页面滑走后会 detach，
     * 这里把视图树和列表数据一起释放，避免多页 RecyclerView + 海报 bitmap 常驻导致 OOM。
     */
    @Override
    protected boolean isKeepViewOnDestroy() {
        return false;
    }

    @Override
    protected void onFragmentViewDestroyed() {
        if (gridFilterDialog != null) {
            try {
                gridFilterDialog.dismiss();
            } catch (Throwable ignore) {
            }
            gridFilterDialog = null;
        }
        mGridView = null;
        gridAdapter = null;
        loadSirView = null;
        mGrids.clear();
        isLoad = false;
        isRequesting = false;
        page = 1;
        maxPage = 1;
        hasActionItems = false;
        isTop = true;
    }

    @Override
    protected void onFragmentResume() {
        super.onFragmentResume();
        // 视图被释放过（从别的 tab 滑回来），需要重新建视图并拉数据
        if (mGridView == null && rootView != null && sortData != null) {
            init();
        }
    }

    private void changeView(String id, Boolean isFolder) {
        if (isFolder) {
            this.sortData.flag = style == null ? "1" : "2";
        } else {
            this.sortData.flag = "2";
        }
        initView();
        this.sortData.id = id;
        initViewModel();
        initData();
    }

    public boolean isFolederMode() { return (getUITag() == '1'); }

    public char getUITag() {
        return (sortData == null || sortData.flag == null || sortData.flag.length() == 0 || style != null) ? '0' : sortData.flag.charAt(0);
    }

    public boolean enableFastSearch() { return sortData.flag == null || sortData.flag.length() < 2 || (sortData.flag.charAt(1) == '1'); }

    private void saveCurrentView() {
        if (this.mGridView == null) return;
        GridInfo info = new GridInfo();
        info.sortID = this.sortData.id;
        info.mGridView = this.mGridView;
        info.gridAdapter = this.gridAdapter;
        info.page = this.page;
        info.maxPage = this.maxPage;
        info.isLoad = this.isLoad;
        info.hasActionItems = this.hasActionItems;
        this.mGrids.push(info);
    }

    public boolean restoreView() {
        if (mGrids.empty()) return false;
        this.showSuccess();
        if (mGridView != null && mGridView.getParent() instanceof ViewGroup) {
            ((ViewGroup) mGridView.getParent()).removeView(this.mGridView);
        }
        GridInfo info = mGrids.pop();
        this.sortData.id = info.sortID;
        this.mGridView = info.mGridView;
        this.gridAdapter = info.gridAdapter;
        this.page = info.page;
        this.maxPage = info.maxPage;
        this.isLoad = info.isLoad;
        this.hasActionItems = info.hasActionItems;
        this.mGridView.setVisibility(View.VISIBLE);
        return true;
    }

    private ImgUtil.Style style;

    private void createView() {
        this.saveCurrentView();
        if (mGridView == null) {
            mGridView = findViewById(R.id.mGridView);
        } else {
            RecyclerView v3 = new RecyclerView(this.mContext);
            v3.setLayoutParams(mGridView.getLayoutParams());
            v3.setPadding(mGridView.getPaddingLeft(), mGridView.getPaddingTop(), mGridView.getPaddingRight(), mGridView.getPaddingBottom());
            v3.setClipToPadding(mGridView.getClipToPadding());
            if (mGridView.getParent() instanceof ViewGroup) {
                ((ViewGroup) mGridView.getParent()).addView(v3);
            }
            mGridView.setVisibility(View.GONE);
            mGridView = v3;
            mGridView.setVisibility(View.VISIBLE);
        }
        mGridView.setHasFixedSize(true);
        // 滑动时多留几屏 item，滑回来不用重新 bind（默认只有 2 个）
        mGridView.setItemViewCacheSize(8);
        // 关闭增删动画：一次 setNewData 几十项时，动画会让首帧明显掉帧
        mGridView.setItemAnimator(null);
        // 复用池放大：默认只有 5 个，而一屏 3×4=12 个 item，滑一屏就要丢掉一半再 inflate
        mGridView.getRecycledViewPool().setMaxRecycledViews(0, 12);
        // 注意：这里以前挂了「滑动时 Glide.pauseRequests()、停下再 resume」的监听。
        // 那是错的——pause 会让滑动过程中新出现的 item 永远拿不到图（表现就是「图片一直加载不出来」），
        // 停下瞬间几十个请求同时恢复又反过来抢占主线程。Glide 本身会在 View 复用时
        // 自动取消旧请求，不需要手动暂停。
        style = ImgUtil.initStyle();
        gridAdapter = new GridAdapter(isFolederMode(), style);
        this.page = 1;
        this.maxPage = 1;
        this.isLoad = false;
    }

    private void initView() {
        this.createView();
        if (isFolederMode()) {
            mGridView.setLayoutManager(new LinearLayoutManager(this.mContext, LinearLayoutManager.VERTICAL, false));
        } else {
            int spanCount = isBaseOnWidth() ? 3 : 4;
            if (style != null) {
                spanCount = ImgUtil.spanCountByStyle(style, spanCount);
            }
            if (spanCount <= 1) {
                mGridView.setLayoutManager(new LinearLayoutManager(this.mContext, LinearLayoutManager.VERTICAL, false));
            } else {
                mGridView.setLayoutManager(new GridLayoutManager(this.mContext, spanCount));
            }
        }
        mGridView.addItemDecoration(new SpacesItemDecoration((int) getResources().getDimension(R.dimen.vs_8)));
        mGridView.setAdapter(gridAdapter);

        gridAdapter.setOnLoadMoreListener(new BaseQuickAdapter.RequestLoadMoreListener() {
            @Override
            public void onLoadMoreRequested() {
                // 防抖：上一次请求还没回来、已经到底、或没有更多了，都不要重复发请求。
                // 否则列表不足一屏时会被连续触发，一次性把几十页都拉下来。
                if (isRequesting || !isLoad || (maxPage > 0 && page > maxPage)) {
                    gridAdapter.loadMoreComplete();
                    if (maxPage > 0 && page > maxPage) gridAdapter.setEnableLoadMore(false);
                    return;
                }
                isRequesting = true;
                sourceViewModel.getList(sortData, page);
            }
        }, mGridView);
        gridAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Movie.Video video = gridAdapter.getData().get(position);
                if (video != null) {
                    if (video.action != null) {
                        sourceViewModel.action(video.sourceKey, video.action);
                        return;
                    }
                    Bundle bundle = new Bundle();
                    bundle.putString("id", video.id);
                    bundle.putString("sourceKey", video.sourceKey);
                    bundle.putString("title", video.name);
                    if (video.tag != null && (video.tag.equals("folder") || video.tag.equals("cover"))) {
                        if (("12".indexOf(getUITag()) != -1)) {
                            changeView(video.id, video.tag.equals("folder"));
                        } else {
                            changeView(video.id, false);
                        }
                    } else {
                        if (video.id != null && video.id.startsWith("msearch:")) {
                            if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true) && enableFastSearch()) {
                                jumpActivity(FastSearchActivity.class, bundle);
                            } else {
                                jumpActivity(SearchActivity.class, bundle);
                            }
                        } else {
                            bundle.putString("picture", video.pic);
                            jumpActivity(DetailActivity.class, bundle);
                        }
                    }
                }
            }
        });
        gridAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Movie.Video video = gridAdapter.getData().get(position);
                if (video != null) {
                    Bundle bundle = new Bundle();
                    bundle.putString("id", video.id);
                    bundle.putString("sourceKey", video.sourceKey);
                    bundle.putString("title", video.name);
                    if (video.id != null && video.id.startsWith("msearch:")) {
                        bundle.putString("picture", video.pic);
                        jumpActivity(DetailActivity.class, bundle);
                    } else {
                        jumpActivity(FastSearchActivity.class, bundle);
                    }
                }
                return true;
            }
        });
        gridAdapter.setLoadMoreView(new LoadMoreView());
        setLoadSir2(mGridView);
        initPullRefresh();
    }

    private static class SpacesItemDecoration extends RecyclerView.ItemDecoration {
        private final int space;

        SpacesItemDecoration(int space) {
            this.space = space;
        }

        @Override
        public void getItemOffsets(@NonNull android.graphics.Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            outRect.left = space;
            outRect.right = space;
            outRect.top = space;
            outRect.bottom = space;
        }
    }

    private void initPullRefresh() {
        pullRefreshThreshold = ViewConfiguration.get(mContext).getScaledTouchSlop() * 6;
        loadSirView = (View) mGridView.getParent();
        if (loadSirView != null) {
            loadSirView.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    return handlePullRefreshTouch(v, event);
                }
            });
        }
    }

    private boolean handlePullRefreshTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pullRefreshStartX = event.getX();
                pullRefreshStartY = event.getY();
                pullRefreshStartAtTop = !mGridView.canScrollVertically(-1);
                pullRefreshReady = false;
                break;
            case MotionEvent.ACTION_MOVE:
                float diffX = Math.abs(event.getX() - pullRefreshStartX);
                float diffY = event.getY() - pullRefreshStartY;
                pullRefreshReady = pullRefreshStartAtTop && diffY > pullRefreshThreshold && diffY > diffX;
                break;
            case MotionEvent.ACTION_UP:
                if (pullRefreshReady) {
                    pullRefreshReady = false;
                    forceRefresh();
                    return true;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                pullRefreshReady = false;
                break;
        }
        return false;
    }

    @Override
    protected void showEmpty() {
        super.showEmpty();
    }

    private void initViewModel() {
        if (sourceViewModel != null) { return; }
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.listResult.observe(this, new Observer<AbsXml>() {
            @Override
            public void onChanged(AbsXml absXml) {
                isRequesting = false;
                // 视图可能已被释放（滑到别的 tab），此时不能再操作 adapter
                if (gridAdapter == null || mGridView == null) {
                    return;
                }
                if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                    if (page == 1) {
                        showSuccess();
                        isLoad = true;
                        hasActionItems = hasActionVideo(absXml.movie.videoList);
                        gridAdapter.setNewData(absXml.movie.videoList);
                    } else {
                        hasActionItems = hasActionItems || hasActionVideo(absXml.movie.videoList);
                        gridAdapter.addData(absXml.movie.videoList);
                    }
                    page++;
                    maxPage = absXml.movie.pagecount;
                    if (maxPage > 0 && page > maxPage) {
                        gridAdapter.loadMoreEnd();
                        gridAdapter.setEnableLoadMore(false);
                        if (page > 2) Toast.makeText(getContext(), "没有更多了", Toast.LENGTH_SHORT).show();
                    } else {
                        gridAdapter.loadMoreComplete();
                        gridAdapter.setEnableLoadMore(true);
                    }
                } else {
                    if (page == 1) {
                        hasActionItems = false;
                        showEmpty();
                    } else if (page > 2) {
                        Toast.makeText(getContext(), "没有更多了", Toast.LENGTH_SHORT).show();
                    }
                    gridAdapter.loadMoreEnd();
                    gridAdapter.setEnableLoadMore(false);
                }
            }
        });
        sourceViewModel.actionResult.observe(this, new Observer<JSONObject>() {
            @Override
            public void onChanged(JSONObject jsonObject) {
                if (jsonObject == null) return;
                String msg = jsonObject.optString("msg");
                if (!msg.isEmpty()) {
                    Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
                    forceRefresh();
                }
            }
        });
    }

    public boolean isLoad() {
        return isLoad || !mGrids.empty();
    }

    public boolean shouldReloadOnSelect() {
        return !isRequesting && mGrids.empty() && (hasActionItems || !isLoad);
    }

    private void initData() {
        showLoading();
        isRequesting = true;
        isLoad = false;
        hasActionItems = false;
        scrollTop();
        toggleFilterColor();
        sourceViewModel.getList(sortData, page);
    }

    private boolean hasActionVideo(List<Movie.Video> videos) {
        if (videos == null) return false;
        for (Movie.Video video : videos) {
            if (video != null && video.action != null) return true;
        }
        return false;
    }

    private void toggleFilterColor() {
        if (sortData != null && sortData.filters != null && !sortData.filters.isEmpty()) {
            int count = sortData.filterSelectCount();
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_FILTER_CHANGE, count));
        }
    }

    public boolean isTop() {
        return isTop;
    }

    /** 刷新数据时用：瞬间回到顶部，不带滚动动画 */
    public void scrollTop() {
        isTop = true;
        if (mGridView == null) return;
        mGridView.scrollToPosition(0);
    }

    /**
     * 用户主动「回到顶部」时用：平滑滚动，0 号 item 半露时也能精确收到顶部。
     *
     * @return true 表示确实滚动了（已经在顶部返回 false）
     */
    public boolean smoothScrollToTop() {
        return SmoothTopScroller.scrollToTop(mGridView);
    }

    public void showFilter() {
        if (!sortData.filters.isEmpty() && gridFilterDialog == null) {
            gridFilterDialog = new GridFilterDialog(mContext);
            setFilterDialogData();
        }
        if (gridFilterDialog != null)
            gridFilterDialog.show();
    }

    public void setFilterDialogData() {
        Context context = getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        assert context != null;
        final int defaultColor = ContextCompat.getColor(context, R.color.color_FFFFFF);
        final int selectedColor = ContextCompat.getColor(context, R.color.color_02F8E1);
        for (MovieSort.SortFilter filter : sortData.filters) {
            View line = inflater.inflate(R.layout.item_grid_filter, gridFilterDialog.filterRoot, false);
            TextView filterNameTv = line.findViewById(R.id.filterName);
            filterNameTv.setText(filter.name);
            RecyclerView gridView = line.findViewById(R.id.mFilterKv);
            gridView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
            GridFilterKVAdapter adapter = new GridFilterKVAdapter();
            gridView.setAdapter(adapter);
            final String key = filter.key;
            final ArrayList<String> values = new ArrayList<>(filter.values.keySet());
            final ArrayList<String> keys = new ArrayList<>(filter.values.values());
            adapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
                View previousSelectedView = null;

                @Override
                public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                    String currentSelection = sortData.filterSelect.get(key);
                    String newSelection = keys.get(position);
                    if (currentSelection == null || !currentSelection.equals(newSelection)) {
                        sortData.filterSelect.put(key, newSelection);
                        updateViewStyle(view, selectedColor, true);
                        if (previousSelectedView != null) {
                            updateViewStyle(previousSelectedView, defaultColor, false);
                        }
                        previousSelectedView = view;
                    } else {
                        sortData.filterSelect.remove(key);
                        if (previousSelectedView != null) {
                            updateViewStyle(previousSelectedView, defaultColor, false);
                        }
                        previousSelectedView = null;
                    }
                    forceRefresh();
                }

                private void updateViewStyle(View view, int color, boolean isBold) {
                    TextView valueTv = view.findViewById(R.id.filterValue);
                    valueTv.getPaint().setFakeBoldText(isBold);
                    valueTv.setTextColor(color);
                }
            });
            adapter.setNewData(values);
            gridFilterDialog.filterRoot.addView(line);
        }
    }

    public void forceRefresh() {
        if (mGridView == null || gridAdapter == null || sourceViewModel == null) return;
        if (isRequesting) return;
        page = 1;
        initData();
    }
}
