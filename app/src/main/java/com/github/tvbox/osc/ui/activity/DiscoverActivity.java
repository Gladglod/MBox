package com.github.tvbox.osc.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.TmdbApi;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.ui.adapter.DiscoverAdapter;
import com.github.tvbox.osc.ui.dialog.ChoiceListDialog;
import com.github.tvbox.osc.ui.dialog.ConfirmClearDialog;
import com.github.tvbox.osc.ui.dialog.DiscoverDetailDialog;
import com.github.tvbox.osc.ui.dialog.TextInputDialog;
import com.github.tvbox.osc.ui.widget.GridSpacingItemDecoration;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.GlassTip;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 发现页：用 TMDB 的公开接口浏览热门 / 电影 / 剧集 / 高分，并搜索影片。
 *
 * <p>TMDB 只提供影视资料（标题、海报、简介、演职员），不提供播放地址。
 * 点开海报后可以选择「搜索片源」，把片名交给首页搜索，
 * 用用户自己配置的数据源去找可播放线路。</p>
 *
 * <p>API Key 由用户自行填写，只保存在本机 Hawk；没有内置任何 Key。</p>
 */
public class DiscoverActivity extends BaseActivity {

    private static final int TAB_HOT = 0;
    private static final int TAB_MOVIE = 1;
    private static final int TAB_TV = 2;
    private static final int TAB_TOP = 3;

    private static final int SPAN_COUNT = 3;

    private RecyclerView discoverGrid;
    private DiscoverAdapter adapter;
    private LinearLayout mediaTabs;
    private LinearLayout genreChips;
    private HorizontalScrollView genreScroll;
    private TextView tvScope;
    private View stateLoading;
    private ProgressBar loadingMore;
    private View statePanel;
    private TextView tvStateTitle;
    private TextView tvStateDesc;
    private TextView tvStateAction;

    private final List<TextView> mediaTabViews = new ArrayList<>();
    private Runnable stateAction;

    private int currentTab = TAB_HOT;
    private int selectedGenreId = 0;
    private int currentPage = 1;
    private int totalPages = 1;
    private int loadVersion = 0;
    private boolean loading = false;

    private String searchKeyword = "";

    private List<TmdbApi.Genre> movieGenres;
    private List<TmdbApi.Genre> tvGenres;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_discover;
    }

    @Override
    protected void init() {
        discoverGrid = findViewById(R.id.discoverGrid);
        mediaTabs = findViewById(R.id.mediaTabs);
        genreChips = findViewById(R.id.genreChips);
        genreScroll = findViewById(R.id.genreScroll);
        tvScope = findViewById(R.id.tvScope);
        stateLoading = findViewById(R.id.stateLoading);
        loadingMore = findViewById(R.id.loadingMore);
        statePanel = findViewById(R.id.statePanel);
        tvStateTitle = findViewById(R.id.tvStateTitle);
        tvStateDesc = findViewById(R.id.tvStateDesc);
        tvStateAction = findViewById(R.id.tvStateAction);

        findViewById(R.id.ivBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.ivSearch).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                showSearchDialog();
            }
        });
        findViewById(R.id.ivSettings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                showSettingsDialog();
            }
        });
        tvScope.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exitSearchMode();
            }
        });
        tvStateAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (stateAction != null) stateAction.run();
            }
        });

        initGrid();
        buildMediaTabs();
        // 「热门」不展示分类胶囊：trending/all 没有类型筛选参数
        genreScroll.setVisibility(currentTab == TAB_HOT ? View.GONE : View.VISIBLE);

        if (!TmdbApi.hasApiKey()) {
            showKeyMissing();
            return;
        }
        loadFirstPage();
    }

    private void initGrid() {
        adapter = new DiscoverAdapter();
        GridLayoutManager layoutManager = new GridLayoutManager(this, SPAN_COUNT);
        discoverGrid.setLayoutManager(layoutManager);
        discoverGrid.setItemAnimator(null);
        discoverGrid.setHasFixedSize(true);
        discoverGrid.addItemDecoration(
                new GridSpacingItemDecoration(getResources().getDimensionPixelSize(R.dimen.vs_8)));
        discoverGrid.setAdapter(adapter);
        adapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter baseAdapter, View view, int position) {
                FastClickCheckUtil.check(view);
                if (position < 0 || position >= adapter.getData().size()) return;
                showDetail(adapter.getData().get(position));
            }
        });
        discoverGrid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy <= 0 || loading) return;
                if (currentPage >= totalPages) return;
                if (adapter.getItemCount() == 0) return;
                GridLayoutManager lm = (GridLayoutManager) recyclerView.getLayoutManager();
                if (lm == null) return;
                int last = lm.findLastVisibleItemPosition();
                if (last >= adapter.getItemCount() - 6) {
                    loadPage(currentPage + 1, true, loadVersion);
                }
            }
        });
    }

    /* ============================ 顶部页签 ============================ */

    private void buildMediaTabs() {
        String[] titles = {
                getString(R.string.discover_tab_hot),
                getString(R.string.discover_tab_movie),
                getString(R.string.discover_tab_tv),
                getString(R.string.discover_tab_top)
        };
        for (int i = 0; i < titles.length; i++) {
            final int tab = i;
            TextView chip = createChip(titles[i]);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FastClickCheckUtil.check(v);
                    selectTab(tab);
                }
            });
            mediaTabs.addView(chip, chipParams());
            mediaTabViews.add(chip);
        }
        updateMediaTabs();
    }

    private void updateMediaTabs() {
        for (int i = 0; i < mediaTabViews.size(); i++) {
            applyChipState(mediaTabViews.get(i), i == currentTab);
        }
    }

    private void selectTab(int tab) {
        currentTab = tab;
        searchKeyword = "";
        selectedGenreId = 0;
        tvScope.setVisibility(View.GONE);
        updateMediaTabs();
        if (tab == TAB_HOT) {
            genreScroll.setVisibility(View.GONE);
            genreChips.removeAllViews();
            loadFirstPage();
        } else {
            // 换页签时分类列表会重建，滚动位置要回到最左，避免停在上一类的中段
            genreScroll.scrollTo(0, 0);
            genreScroll.setVisibility(View.VISIBLE);
            ensureGenres();
            loadFirstPage();
        }
    }

    private void exitSearchMode() {
        selectTab(currentTab);
    }

    /* ============================ 分类胶囊 ============================ */

    private void ensureGenres() {
        final boolean tv = currentTab == TAB_TV;
        List<TmdbApi.Genre> cached = tv ? tvGenres : movieGenres;
        if (cached != null) {
            bindGenreChips(cached);
            return;
        }
        // 拉取期间先只显示「全部」，避免旧分类的选中态串到新页签
        bindGenreChips(new ArrayList<TmdbApi.Genre>());
        TmdbApi.fetchGenres(tv ? TmdbApi.MEDIA_TV : TmdbApi.MEDIA_MOVIE, new TmdbApi.GenreCallback() {
            @Override
            public void onSuccess(List<TmdbApi.Genre> genres) {
                if (isFinishing()) return;
                if (tv) {
                    tvGenres = genres;
                } else {
                    movieGenres = genres;
                }
                if (tv == (currentTab == TAB_TV)) {
                    bindGenreChips(genres);
                }
            }

            @Override
            public void onError(String message) {
                // 分类拉不到不影响主列表：保留「全部」，用户仍可浏览
            }
        });
    }

    private void bindGenreChips(List<TmdbApi.Genre> genres) {
        // 重建胶囊会把横向滚动位置带回最左，选右侧分类时会突然跳一下；
        // 先记下来，重建后再恢复。
        final int scrollX = genreScroll.getScrollX();
        genreChips.removeAllViews();
        boolean hasSelected = false;
        for (TmdbApi.Genre genre : genres) {
            if (genre.id == selectedGenreId) hasSelected = true;
        }
        if (!hasSelected) selectedGenreId = 0;

        TextView all = createChip(getString(R.string.discover_genre_all));
        applyChipState(all, selectedGenreId == 0);
        all.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                if (selectedGenreId == 0) return;
                selectedGenreId = 0;
                loadFirstPage();
                bindGenreChips(currentGenreList());
            }
        });
        genreChips.addView(all, chipParams());

        for (final TmdbApi.Genre genre : genres) {
            TextView chip = createChip(genre.name);
            applyChipState(chip, selectedGenreId == genre.id);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FastClickCheckUtil.check(v);
                    if (selectedGenreId == genre.id) return;
                    selectedGenreId = genre.id;
                    loadFirstPage();
                    bindGenreChips(currentGenreList());
                }
            });
            genreChips.addView(chip, chipParams());
        }

        genreScroll.post(new Runnable() {
            @Override
            public void run() {
                genreScroll.scrollTo(scrollX, 0);
            }
        });
    }

    private List<TmdbApi.Genre> currentGenreList() {
        List<TmdbApi.Genre> list = currentTab == TAB_TV ? tvGenres : movieGenres;
        return list == null ? new ArrayList<TmdbApi.Genre>() : list;
    }

    private TextView createChip(String text) {
        TextView chip = (TextView) LayoutInflater.from(this)
                .inflate(R.layout.item_discover_chip, genreChips, false);
        chip.setText(text);
        return chip;
    }

    private LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = getResources().getDimensionPixelSize(R.dimen.vs_6);
        return lp;
    }

    private void applyChipState(TextView chip, boolean selected) {
        chip.setBackgroundResource(selected ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
        chip.setTextColor(ContextCompat.getColor(this,
                selected ? R.color.ui_accent : R.color.ui_text_secondary));
        chip.setTypeface(null, selected ? android.graphics.Typeface.BOLD
                : android.graphics.Typeface.NORMAL);
    }

    /* ============================ 数据加载 ============================ */

    private void loadFirstPage() {
        if (!TmdbApi.hasApiKey()) {
            showKeyMissing();
            return;
        }
        loadVersion++;
        currentPage = 1;
        totalPages = 1;
        loading = false;
        loadingMore.setVisibility(View.GONE);
        adapter.setNewData(new ArrayList<TmdbApi.Item>());
        showInitialLoading();
        loadPage(1, false, loadVersion);
    }

    private void loadPage(final int page, final boolean append, final int version) {
        if (loading) return;
        if (!TmdbApi.hasApiKey()) {
            showKeyMissing();
            return;
        }
        loading = true;
        if (append) {
            loadingMore.setVisibility(View.VISIBLE);
        } else {
            showInitialLoading();
        }

        TmdbApi.PageCallback callback = new TmdbApi.PageCallback() {
            @Override
            public void onSuccess(TmdbApi.Page result) {
                if (version != loadVersion || isFinishing()) return;
                loading = false;
                loadingMore.setVisibility(View.GONE);
                currentPage = result.page;
                totalPages = result.totalPages;

                if (append) {
                    adapter.addData(result.items);
                    if (result.items.isEmpty()) {
                        totalPages = currentPage;
                    }
                } else {
                    adapter.setNewData(result.items);
                }
                if (adapter.getItemCount() == 0) {
                    showEmptyState();
                } else {
                    showContent();
                }
            }

            @Override
            public void onError(String message) {
                if (version != loadVersion || isFinishing()) return;
                loading = false;
                loadingMore.setVisibility(View.GONE);
                if (append) {
                    GlassTip.show(DiscoverActivity.this, message);
                } else {
                    showError(message);
                }
            }
        };

        if (!TextUtils.isEmpty(searchKeyword)) {
            TmdbApi.searchMulti(searchKeyword, page, callback);
            return;
        }

        switch (currentTab) {
            case TAB_MOVIE:
                TmdbApi.fetchDiscover(TmdbApi.MEDIA_MOVIE, page, genreQuery(),
                        "popularity.desc", 0, callback);
                break;
            case TAB_TV:
                TmdbApi.fetchDiscover(TmdbApi.MEDIA_TV, page, genreQuery(),
                        "popularity.desc", 0, callback);
                break;
            case TAB_TOP:
                TmdbApi.fetchDiscover(TmdbApi.MEDIA_MOVIE, page, genreQuery(),
                        "vote_average.desc", 300, callback);
                break;
            case TAB_HOT:
            default:
                TmdbApi.fetchTrending("all", "week", page, callback);
                break;
        }
    }

    private String genreQuery() {
        return selectedGenreId > 0 ? String.valueOf(selectedGenreId) : "";
    }

    /* ============================ 搜索 ============================ */

    private void showSearchDialog() {
        TextInputDialog dialog = new TextInputDialog(this,
                getString(R.string.discover_search_title),
                getString(R.string.discover_search_tip),
                searchKeyword,
                getString(R.string.discover_search_hint));
        dialog.setOnConfirm(new TextInputDialog.OnConfirm() {
            @Override
            public void onConfirm(String value) {
                String keyword = value == null ? "" : value.trim();
                if (TextUtils.isEmpty(keyword)) {
                    GlassTip.show(DiscoverActivity.this,
                            getString(R.string.discover_search_empty));
                    return;
                }
                startSearch(keyword);
            }
        });
        dialog.show();
    }

    private void startSearch(String keyword) {
        if (!TmdbApi.hasApiKey()) {
            showKeyMissing();
            return;
        }
        searchKeyword = keyword;
        selectedGenreId = 0;
        tvScope.setText(getString(R.string.discover_search_scope, keyword));
        tvScope.setVisibility(View.VISIBLE);
        genreScroll.setVisibility(View.GONE);
        loadFirstPage();
    }

    /* ============================ 设置 ============================ */

    private void showSettingsDialog() {
        List<String> items = Arrays.asList(
                getString(R.string.discover_settings_key),
                getString(R.string.discover_settings_api),
                getString(R.string.discover_settings_image),
                getString(R.string.discover_settings_clear_key));
        new ChoiceListDialog(this, getString(R.string.discover_settings_title), items, -1,
                new ChoiceListDialog.OnPick() {
                    @Override
                    public void onPick(int position, String value) {
                        switch (position) {
                            case 0:
                                showKeyDialog();
                                break;
                            case 1:
                                showAddressDialog(true);
                                break;
                            case 2:
                                showAddressDialog(false);
                                break;
                            case 3:
                                confirmClearKey();
                                break;
                            default:
                                break;
                        }
                    }
                }).show();
    }

    private void showKeyDialog() {
        TextInputDialog dialog = new TextInputDialog(this,
                getString(R.string.discover_key_title_input),
                getString(R.string.discover_key_tip),
                TmdbApi.getApiKey(),
                getString(R.string.discover_key_hint));
        dialog.setOnConfirm(new TextInputDialog.OnConfirm() {
            @Override
            public void onConfirm(String value) {
                if (TextUtils.isEmpty(value)) {
                    GlassTip.show(DiscoverActivity.this, getString(R.string.discover_key_empty));
                    return;
                }
                TmdbApi.setApiKey(value);
                GlassTip.show(DiscoverActivity.this, getString(R.string.discover_key_saved));
                selectTab(currentTab);
            }
        });
        dialog.show();
    }

    private void confirmClearKey() {
        ConfirmClearDialog dialog = new ConfirmClearDialog(this, "TMDB");
        dialog.setMessage(getString(R.string.discover_settings_clear_confirm));
        dialog.setOnConfirm(new Runnable() {
            @Override
            public void run() {
                clearKey();
            }
        });
        dialog.show();
    }

    private void clearKey() {
        loadVersion++;
        loading = false;
        TmdbApi.clearCredentials();
        adapter.setNewData(new ArrayList<TmdbApi.Item>());
        GlassTip.show(this, getString(R.string.discover_settings_cleared));
        showKeyMissing();
    }

    private void showAddressDialog(final boolean apiAddress) {
        String title = getString(apiAddress
                ? R.string.discover_settings_api : R.string.discover_settings_image);
        String tip = getString(apiAddress
                ? R.string.discover_api_address_tip : R.string.discover_image_address_tip);
        String value = apiAddress ? TmdbApi.getBaseUrl() : TmdbApi.getImageBase();
        TextInputDialog dialog = new TextInputDialog(this, title, tip, value, "https://");
        dialog.setOnConfirm(new TextInputDialog.OnConfirm() {
            @Override
            public void onConfirm(String text) {
                String address = text == null ? "" : text.trim();
                if (!address.startsWith("http://") && !address.startsWith("https://")) {
                    GlassTip.show(DiscoverActivity.this,
                            getString(R.string.discover_address_invalid));
                    return;
                }
                if (apiAddress) {
                    TmdbApi.setBaseUrl(address);
                } else {
                    TmdbApi.setImageBase(address);
                }
                GlassTip.show(DiscoverActivity.this, getString(R.string.discover_address_saved));
                if (apiAddress) {
                    // 分类字典跟着接口地址走：旧地址下可能已经缓存失败/缓存了别的语言，
                    // 换地址后必须重新拉一次，否则类型胶囊会一直停在「全部」。
                    movieGenres = null;
                    tvGenres = null;
                    if (TextUtils.isEmpty(searchKeyword)) {
                        selectTab(currentTab);
                    } else {
                        loadFirstPage();
                    }
                } else {
                    adapter.notifyDataSetChanged();
                }
            }
        });
        dialog.show();
    }

    /* ============================ 详情 ============================ */

    private void showDetail(TmdbApi.Item item) {
        if (item == null) return;
        new DiscoverDetailDialog(this, item, new DiscoverDetailDialog.OnSearchClickListener() {
            @Override
            public void onSearchClick(TmdbApi.Item data) {
                searchResource(data);
            }
        }).show();
    }

    /** 把 TMDB 的片名交给首页搜索，由用户自己的数据源去找可播放线路 */
    private void searchResource(TmdbApi.Item item) {
        if (item == null || TextUtils.isEmpty(item.displayTitle())) return;
        Bundle bundle = new Bundle();
        bundle.putString("title", item.displayTitle());
        if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)) {
            jumpActivity(FastSearchActivity.class, bundle);
        } else {
            jumpActivity(SearchActivity.class, bundle);
        }
    }

    /* ============================ 状态视图 ============================ */

    private void showInitialLoading() {
        stateLoading.setVisibility(View.VISIBLE);
        statePanel.setVisibility(View.GONE);
        discoverGrid.setVisibility(View.INVISIBLE);
    }

    private void showContent() {
        stateLoading.setVisibility(View.GONE);
        statePanel.setVisibility(View.GONE);
        discoverGrid.setVisibility(View.VISIBLE);
    }

    private void showEmptyState() {
        stateLoading.setVisibility(View.GONE);
        discoverGrid.setVisibility(View.INVISIBLE);
        setState(getString(R.string.discover_state_empty_title),
                getString(R.string.discover_state_empty_desc),
                getString(R.string.discover_state_retry),
                new Runnable() {
                    @Override
                    public void run() {
                        loadFirstPage();
                    }
                });
    }

    private void showError(String message) {
        stateLoading.setVisibility(View.GONE);
        discoverGrid.setVisibility(View.INVISIBLE);
        setState(getString(R.string.discover_state_error_title),
                TextUtils.isEmpty(message)
                        ? getString(R.string.discover_state_empty_desc) : message,
                getString(R.string.discover_state_retry),
                new Runnable() {
                    @Override
                    public void run() {
                        loadFirstPage();
                    }
                });
    }

    private void showKeyMissing() {
        loading = false;
        loadingMore.setVisibility(View.GONE);
        stateLoading.setVisibility(View.GONE);
        discoverGrid.setVisibility(View.INVISIBLE);
        setState(getString(R.string.discover_key_title),
                getString(R.string.discover_key_desc),
                getString(R.string.discover_key_action),
                new Runnable() {
                    @Override
                    public void run() {
                        showKeyDialog();
                    }
                });
    }

    private void setState(String title, String desc, String action, Runnable runnable) {
        stateAction = runnable;
        tvStateTitle.setText(title);
        tvStateDesc.setText(desc);
        if (TextUtils.isEmpty(action) || runnable == null) {
            tvStateAction.setVisibility(View.GONE);
        } else {
            tvStateAction.setText(action);
            tvStateAction.setVisibility(View.VISIBLE);
        }
        statePanel.setVisibility(View.VISIBLE);
    }

    /* ============================ 生命周期 ============================ */

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && !TextUtils.isEmpty(searchKeyword)) {
            exitSearchMode();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        TmdbApi.cancelAll();
        super.onDestroy();
    }
}
