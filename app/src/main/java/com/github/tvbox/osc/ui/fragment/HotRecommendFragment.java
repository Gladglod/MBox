package com.github.tvbox.osc.ui.fragment;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.FastSearchActivity;
import com.github.tvbox.osc.ui.activity.SearchActivity;
import com.github.tvbox.osc.ui.adapter.GridAdapter;
import com.github.tvbox.osc.ui.widget.SmoothTopScroller;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.ImgUtil;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.orhanobut.hawk.Hawk;

import org.json.JSONObject;

import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页第 0 页「热门推荐」。
 *
 * <p>数据来自首页接口返回的推荐列表（{@code AbsSortXml.videoList}），当「首页推荐」被设置为
 * 「历史记录」时改读本地播放记录。列表按批追加（首批 12 条），滑到底再补下一批，
 * 避免一进首页就把几十张海报一次性丢给 Glide。</p>
 */
public class HotRecommendFragment extends BaseLazyFragment {

    private static final int PAGE_SIZE = 12;

    /** 首页把推荐数据放在这里交给本 Fragment（Fragment 参数不适合塞大列表） */
    private static List<Movie.Video> pendingData;

    public static void setData(List<Movie.Video> list) {
        pendingData = list;
    }

    public static HotRecommendFragment newInstance() {
        return new HotRecommendFragment();
    }

    private RecyclerView mGridView;
    private TextView tvEmpty;
    private GridAdapter gridAdapter;
    private SourceViewModel sourceViewModel;
    private ImgUtil.Style style;

    private final List<Movie.Video> allData = new ArrayList<>();
    private int shown = 0;

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_hot_recommend;
    }

    /** 和分类页一样：被 ViewPager detach 时释放视图树，避免海报 bitmap 常驻 */
    @Override
    protected boolean isKeepViewOnDestroy() {
        return false;
    }

    @Override
    protected void onFragmentViewDestroyed() {
        mGridView = null;
        tvEmpty = null;
        gridAdapter = null;
    }

    @Override
    protected void onFragmentResume() {
        super.onFragmentResume();
        if (mGridView == null && rootView != null) {
            init();
        }
    }

    @Override
    protected void init() {
        mGridView = findViewById(R.id.hotGrid);
        tvEmpty = findViewById(R.id.tvHotEmpty);
        if (mGridView == null) return;

        mGridView.setHasFixedSize(true);
        mGridView.setItemViewCacheSize(8);
        mGridView.setItemAnimator(null);
        mGridView.getRecycledViewPool().setMaxRecycledViews(0, 12);
        style = ImgUtil.initStyle();
        int spanCount = isBaseOnWidth() ? 3 : 4;
        if (style != null) {
            spanCount = ImgUtil.spanCountByStyle(style, spanCount);
        }
        if (spanCount <= 1) {
            mGridView.setLayoutManager(new LinearLayoutManager(mContext, LinearLayoutManager.VERTICAL, false));
        } else {
            mGridView.setLayoutManager(new GridLayoutManager(mContext, spanCount));
        }

        gridAdapter = new GridAdapter(false, style);
        mGridView.setAdapter(gridAdapter);
        gridAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                if (position < 0 || position >= gridAdapter.getData().size()) return;
                openVideo(gridAdapter.getData().get(position));
            }
        });
        mGridView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (dy <= 0) return;
                if (shown >= allData.size()) return;
                RecyclerView.LayoutManager lm = recyclerView.getLayoutManager();
                int last = 0;
                if (lm instanceof GridLayoutManager) {
                    last = ((GridLayoutManager) lm).findLastVisibleItemPosition();
                } else if (lm instanceof LinearLayoutManager) {
                    last = ((LinearLayoutManager) lm).findLastVisibleItemPosition();
                }
                if (last >= gridAdapter.getItemCount() - 6) {
                    appendBatch();
                }
            }
        });

        initViewModel();
        loadData();
    }

    private void initViewModel() {
        if (sourceViewModel != null) return;
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.actionResult.observe(this, new Observer<JSONObject>() {
            @Override
            public void onChanged(JSONObject jsonObject) {
                if (jsonObject == null) return;
                String msg = jsonObject.optString("msg");
                if (!msg.isEmpty() && getContext() != null) {
                    Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void loadData() {
        allData.clear();
        shown = 0;
        if (gridAdapter != null) gridAdapter.setNewData(new ArrayList<Movie.Video>());
        List<Movie.Video> list = resolveData();
        if (list != null && !list.isEmpty()) {
            allData.addAll(list);
        }
        appendBatch();
        updateEmpty();
    }

    private List<Movie.Video> resolveData() {
        if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 2) {
            List<VodInfo> records = RoomDataManger.getAllVodRecord(30);
            List<Movie.Video> list = new ArrayList<>();
            if (records != null) {
                for (VodInfo info : records) {
                    if (info == null) continue;
                    Movie.Video video = new Movie.Video();
                    video.id = info.id;
                    video.sourceKey = info.sourceKey;
                    video.name = info.name;
                    video.pic = info.pic;
                    if (info.playNote != null && !info.playNote.isEmpty()) {
                        video.note = "上次看到" + info.playNote;
                    }
                    list.add(video);
                }
            }
            return list;
        }
        return pendingData;
    }

    /** 追加一批，首批 12 条，后续每次再补 12 条 */
    private void appendBatch() {
        if (gridAdapter == null) return;
        if (shown >= allData.size()) return;
        int end = Math.min(shown + PAGE_SIZE, allData.size());
        List<Movie.Video> batch = new ArrayList<>(allData.subList(shown, end));
        shown = end;
        gridAdapter.addData(batch);
        updateEmpty();
    }

    private void updateEmpty() {
        if (tvEmpty == null) return;
        tvEmpty.setVisibility(gridAdapter == null || gridAdapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private void openVideo(Movie.Video video) {
        if (video == null) return;
        if (video.action != null && sourceViewModel != null) {
            sourceViewModel.action(video.sourceKey, video.action);
            return;
        }
        Bundle bundle = new Bundle();
        bundle.putString("id", video.id);
        bundle.putString("sourceKey", video.sourceKey);
        bundle.putString("title", video.name);
        bundle.putString("picture", video.pic);
        if (video.id != null && video.id.startsWith("msearch:")) {
            if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)) {
                jumpActivity(FastSearchActivity.class, bundle);
            } else {
                jumpActivity(SearchActivity.class, bundle);
            }
            return;
        }
        jumpActivity(DetailActivity.class, bundle);
    }

    /** 供 HomeActivity 返回键使用：已上滑时平滑回到顶部并消费事件 */
    public boolean scrollToTop() {
        return SmoothTopScroller.scrollToTop(mGridView);
    }

    @NonNull
    @Override
    public String toString() {
        return "HotRecommendFragment";
    }
}
