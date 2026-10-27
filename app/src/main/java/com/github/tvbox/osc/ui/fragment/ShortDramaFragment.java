package com.github.tvbox.osc.ui.fragment;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.ShortDramaSource;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.ShortDramaSourceActivity;
import com.github.tvbox.osc.ui.adapter.GridAdapter;
import com.github.tvbox.osc.ui.adapter.ShortDramaChipAdapter;
import com.github.tvbox.osc.ui.tv.widget.LoadMoreView;
import com.github.tvbox.osc.ui.widget.GridSpacingItemDecoration;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.ShortDramaConfig;
import com.github.tvbox.osc.util.ShortDramaScanner;
import com.github.tvbox.osc.viewmodel.SourceViewModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 底部导航「短剧」页。
 *
 * <p>短剧不是一个独立的数据源，而是散落在配置里各站点的一个分类。所以这一页做三件事：</p>
 * <ol>
 *     <li>顶部一排源胶囊，告诉用户「这些短剧分别来自哪个站点」，可切换；</li>
 *     <li>下面是被选中那个源的短剧列表，走 {@link SourceViewModel#getList(String, MovieSort.SortData, int)}
 *     直接读那个站点的分类，不切换首页数据源；</li>
 *     <li>首次进入自动识别短剧源（渐进式，扫不完就下次接着扫），识别不到时给出「去添加」。</li>
 * </ol>
 */
public class ShortDramaFragment extends BaseLazyFragment {

    private RecyclerView chipList;
    private RecyclerView mGridView;
    private TextView tvStatus;
    private TextView tvEmptyTitle;
    private TextView tvEmptyDesc;
    private TextView btnEmptyPrimary;
    private TextView btnEmptySecondary;
    private LinearLayout emptyState;
    private ProgressBar progressBar;

    private ShortDramaChipAdapter chipAdapter;
    private GridAdapter gridAdapter;
    private SourceViewModel sourceViewModel;

    private final List<ShortDramaSource> sources = new ArrayList<>();
    private ShortDramaSource selected;

    private int page = 1;
    private int maxPage = 1;
    private boolean isRequesting = false;
    private boolean canLoadMore = false;
    private int lastFound = 0;
    private int scanDone = 0;
    private int scanTotal = 0;
    private boolean scanStartedHere = false;

    public static ShortDramaFragment newInstance() {
        return new ShortDramaFragment();
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_short_drama;
    }

    @Override
    protected void init() {
        chipList = findViewById(R.id.chipList);
        mGridView = findViewById(R.id.mGridView);
        tvStatus = findViewById(R.id.tvStatus);
        emptyState = findViewById(R.id.emptyState);
        tvEmptyTitle = findViewById(R.id.tvEmptyTitle);
        tvEmptyDesc = findViewById(R.id.tvEmptyDesc);
        btnEmptyPrimary = findViewById(R.id.btnEmptyPrimary);
        btnEmptySecondary = findViewById(R.id.btnEmptySecondary);
        progressBar = findViewById(R.id.progressBar);

        findViewById(R.id.tvManage).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                jumpActivity(ShortDramaSourceActivity.class);
            }
        });

        initChipList();
        initGrid();
        initViewModel();
        reloadSources();
        startScan(false);
    }

    @Override
    protected void onFragmentResume() {
        super.onFragmentResume();
        // 从「短剧源」设置页回来：用户可能改了勾选或手动加了源
        if (ShortDramaConfig.consumeChanged()) {
            reloadSources();
        }
    }

    @Override
    protected void onFragmentViewDestroyed() {
        if (scanStartedHere) {
            ShortDramaScanner.cancel();
            scanStartedHere = false;
        }
    }

    /* ============================ 源胶囊 ============================ */

    private void initChipList() {
        chipAdapter = new ShortDramaChipAdapter();
        chipList.setLayoutManager(new LinearLayoutManager(mContext, LinearLayoutManager.HORIZONTAL, false));
        chipList.setAdapter(chipAdapter);
        chipAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                if (position < 0 || position >= sources.size()) return;
                selectSource(sources.get(position), true);
            }
        });
    }

    /** 重新读取配置里的短剧源，尽量保住当前选中项 */
    private void reloadSources() {
        List<ShortDramaSource> list = ShortDramaConfig.loadEnabled();
        sources.clear();
        sources.addAll(list);
        buildLabels(sources);

        if (sources.isEmpty()) {
            selected = null;
            chipList.setVisibility(View.GONE);
            chipAdapter.setNewData(new ArrayList<ShortDramaSource>());
            if (gridAdapter != null) gridAdapter.setNewData(new ArrayList<Movie.Video>());
            showNoSourceState();
            updateStatus();
            return;
        }

        chipList.setVisibility(View.VISIBLE);
        chipAdapter.setNewData(new ArrayList<>(sources));

        ShortDramaSource target = null;
        String saved = ShortDramaConfig.getSelectedId();
        if (selected != null) {
            for (ShortDramaSource item : sources) {
                if (item.id().equals(selected.id())) {
                    target = item;
                    break;
                }
            }
        }
        if (target == null && !TextUtils.isEmpty(saved)) {
            for (ShortDramaSource item : sources) {
                if (item.id().equals(saved)) {
                    target = item;
                    break;
                }
            }
        }
        if (target == null) target = sources.get(0);

        boolean sameAsBefore = selected != null && selected.id().equals(target.id())
                && gridAdapter != null && !gridAdapter.getData().isEmpty();
        selectSource(target, !sameAsBefore);
        updateStatus();
    }

    /**
     * 胶囊文案：一个站点只有一个短剧分类时只显示站点名；
     * 有多个分类、或两个站点重名时补上分类名，否则用户没法区分。
     */
    private void buildLabels(List<ShortDramaSource> list) {
        Map<String, Integer> byKey = new HashMap<>();
        Map<String, Integer> byName = new HashMap<>();
        for (ShortDramaSource item : list) {
            byKey.put(item.sourceKey, byKey.containsKey(item.sourceKey) ? byKey.get(item.sourceKey) + 1 : 1);
            String name = TextUtils.isEmpty(item.sourceName) ? item.sourceKey : item.sourceName;
            byName.put(name, byName.containsKey(name) ? byName.get(name) + 1 : 1);
        }
        for (ShortDramaSource item : list) {
            String name = TextUtils.isEmpty(item.sourceName) ? item.sourceKey : item.sourceName;
            Integer count = byKey.get(item.sourceKey);
            Integer nameCount = byName.get(name);
            boolean needCategory = (count != null && count > 1) || (nameCount != null && nameCount > 1);
            item.label = needCategory ? name + " · " + item.displayName() : name;
        }
    }

    private void selectSource(ShortDramaSource source, boolean load) {
        if (source == null) return;
        selected = source;
        ShortDramaConfig.setSelectedId(source.id());
        chipAdapter.setSelectedId(source.id());
        if (load) loadContent();
    }

    /* ============================ 列表 ============================ */

    private void initGrid() {
        gridAdapter = new GridAdapter(false, null);
        mGridView.setLayoutManager(new GridLayoutManager(mContext, 3));
        mGridView.addItemDecoration(new GridSpacingItemDecoration((int) getResources().getDimension(R.dimen.vs_8)));
        mGridView.setItemAnimator(null);
        mGridView.setHasFixedSize(true);
        mGridView.setAdapter(gridAdapter);
        gridAdapter.setLoadMoreView(new LoadMoreView());
        gridAdapter.setEnableLoadMore(false);
        gridAdapter.setOnLoadMoreListener(new BaseQuickAdapter.RequestLoadMoreListener() {
            @Override
            public void onLoadMoreRequested() {
                if (isRequesting || !canLoadMore || selected == null) {
                    gridAdapter.loadMoreComplete();
                    return;
                }
                if (maxPage > 0 && page > maxPage) {
                    gridAdapter.loadMoreEnd();
                    return;
                }
                requestPage(page);
            }
        }, mGridView);
        gridAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                if (position < 0 || position >= gridAdapter.getData().size()) return;
                Movie.Video video = gridAdapter.getData().get(position);
                if (video == null) return;
                Bundle bundle = new Bundle();
                bundle.putString("id", video.id);
                bundle.putString("sourceKey", video.sourceKey);
                bundle.putString("title", video.name);
                bundle.putString("picture", video.pic);
                jumpActivity(DetailActivity.class, bundle);
            }
        });
    }

    /** 网格固定 3 列：数据源的 style 属于它自己的首页，套过来只会让海报比例错乱 */
    private void initViewModel() {
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.listResult.observe(this, new Observer<AbsXml>() {
            @Override
            public void onChanged(AbsXml absXml) {
                isRequesting = false;
                showProgress(false);
                if (gridAdapter == null || selected == null) return;
                // 切源太快时会有上一轮的迟到响应，按 sourceKey 丢掉
                if (absXml != null && !TextUtils.isEmpty(absXml.sourceKey)
                        && !absXml.sourceKey.equals(selected.sourceKey)) {
                    return;
                }
                boolean hasData = absXml != null && absXml.movie != null
                        && absXml.movie.videoList != null && !absXml.movie.videoList.isEmpty();
                if (hasData) {
                    showEmptyState(false);
                    if (page == 1) {
                        gridAdapter.setNewData(absXml.movie.videoList);
                    } else {
                        gridAdapter.addData(absXml.movie.videoList);
                    }
                    page++;
                    maxPage = absXml.movie.pagecount;
                    canLoadMore = maxPage <= 0 || page <= maxPage;
                    if (canLoadMore) {
                        gridAdapter.loadMoreComplete();
                        gridAdapter.setEnableLoadMore(true);
                    } else {
                        gridAdapter.loadMoreEnd();
                        gridAdapter.setEnableLoadMore(false);
                    }
                } else {
                    if (page == 1) {
                        gridAdapter.setNewData(new ArrayList<Movie.Video>());
                        showContentEmptyState();
                    } else {
                        canLoadMore = false;
                        gridAdapter.loadMoreEnd();
                        gridAdapter.setEnableLoadMore(false);
                        if (getContext() != null) {
                            Toast.makeText(getContext(), R.string.short_drama_load_more_end, Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            }
        });
    }

    private void loadContent() {
        if (selected == null) return;
        page = 1;
        maxPage = 1;
        canLoadMore = false;
        if (gridAdapter != null) {
            gridAdapter.setNewData(new ArrayList<Movie.Video>());
            gridAdapter.setEnableLoadMore(false);
        }
        showEmptyState(false);
        requestPage(1);
    }

    private void requestPage(int target) {
        isRequesting = true;
        if (gridAdapter.getData().isEmpty()) showProgress(true);
        MovieSort.SortData sortData = new MovieSort.SortData(selected.categoryId, selected.categoryName);
        sourceViewModel.getList(selected.sourceKey, sortData, target);
    }

    /* ============================ 识别 ============================ */

    private void startScan(boolean force) {
        if (!ShortDramaScanner.scan(force, new ShortDramaScanner.Listener() {
            @Override
            public void onStart(int total) {
                if (!isAlive()) return;
                scanDone = 0;
                scanTotal = total;
                updateStatus();
            }

            @Override
            public void onProgress(int done, int total, int found) {
                if (!isAlive()) return;
                scanDone = done;
                scanTotal = total;
                updateStatus();
                // 第一次识别出结果时把页面点亮，不用等整轮扫完
                if (found > lastFound) {
                    lastFound = found;
                    reloadSources();
                }
            }

            @Override
            public void onFinish(int found, boolean complete) {
                if (!isAlive()) return;
                scanStartedHere = false;
                lastFound = found;
                if (found > 0) {
                    reloadSources();
                } else {
                    updateStatus();
                    if (sources.isEmpty()) showNoSourceState();
                }
            }
        })) {
            return;
        }
        scanStartedHere = true;
        updateStatus();
    }

    private boolean isAlive() {
        return isAdded() && rootView != null && gridAdapter != null;
    }

    private void updateStatus() {
        if (tvStatus == null) return;
        if (ShortDramaScanner.isScanning()) {
            tvStatus.setVisibility(View.VISIBLE);
            tvStatus.setText(getString(R.string.short_drama_scanning, scanDone, scanTotal));
            return;
        }
        if (sources.isEmpty()) {
            tvStatus.setVisibility(View.GONE);
            return;
        }
        tvStatus.setVisibility(View.VISIBLE);
        tvStatus.setText(getString(R.string.short_drama_status, sources.size()));
    }

    /* ============================ 空状态 ============================ */

    private void showNoSourceState() {
        showEmptyState(true);
        boolean scanning = ShortDramaScanner.isScanning();
        // 首次进页面时识别还在跑，别说「还没有」——那会让用户以为已经找完了
        tvEmptyTitle.setText(scanning
                ? R.string.short_drama_scanning_title
                : R.string.short_drama_empty_title);
        tvEmptyDesc.setText(scanning
                ? R.string.short_drama_scanning_desc
                : R.string.short_drama_empty_desc);
        tvEmptyDesc.setVisibility(View.VISIBLE);
        btnEmptyPrimary.setText(R.string.short_drama_empty_add);
        btnEmptyPrimary.setVisibility(View.VISIBLE);
        btnEmptyPrimary.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                jumpActivity(ShortDramaSourceActivity.class);
            }
        });
        btnEmptySecondary.setText(R.string.short_drama_empty_rescan);
        btnEmptySecondary.setVisibility(View.VISIBLE);
        btnEmptySecondary.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                startScan(true);
            }
        });
    }

    private void showContentEmptyState() {
        showEmptyState(true);
        tvEmptyTitle.setText(R.string.short_drama_content_empty);
        tvEmptyDesc.setVisibility(View.GONE);
        if (sources.size() > 1) {
            btnEmptyPrimary.setText(R.string.short_drama_content_empty_action);
            btnEmptyPrimary.setVisibility(View.VISIBLE);
            btnEmptyPrimary.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FastClickCheckUtil.check(v);
                    selectNextSource();
                }
            });
        } else {
            btnEmptyPrimary.setVisibility(View.GONE);
        }
        btnEmptySecondary.setVisibility(View.GONE);
    }

    private void selectNextSource() {
        if (sources.size() < 2 || selected == null) return;
        int index = 0;
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).id().equals(selected.id())) {
                index = i;
                break;
            }
        }
        selectSource(sources.get((index + 1) % sources.size()), true);
    }

    private void showEmptyState(boolean show) {
        if (emptyState == null) return;
        emptyState.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) showProgress(false);
    }

    private void showProgress(boolean show) {
        if (progressBar == null) return;
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }
}
