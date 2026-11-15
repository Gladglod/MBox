package com.github.tvbox.osc.ui.activity;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.BounceInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.catvod.crawler.JsLoader;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.event.ServerEvent;
import com.github.tvbox.osc.ui.adapter.FastListAdapter;
import com.github.tvbox.osc.ui.adapter.FastSearchAdapter;
import com.github.tvbox.osc.ui.adapter.SearchWordAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.GlassTip;
import com.github.tvbox.osc.util.HistoryHelper;
import com.github.tvbox.osc.util.SearchHelper;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.lzy.okgo.OkGo;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author pj567
 * @date :2020/12/23
 * @description:
 */
public class FastSearchActivity extends BaseActivity {
    private static final int SEARCH_THREAD_COUNT = 6;
    private static final int SEARCH_MAX_THREAD_COUNT = Build.VERSION.SDK_INT >= 35 ? 24 : Build.VERSION.SDK_INT >= 30 ? 18 : 12;
    private static final int SEARCH_PUMP_SECONDS = 2;
    private static final int SEARCH_NEXT_BATCH_SECONDS = 3;
    private static final int SEARCH_SITE_TIMEOUT_SECONDS = 15;
    private static final long POSTER_FOCUS_ANIM_DURATION = 300L;
    private static final float POSTER_FOCUS_SCALE = 1.05f;
    private static final String SEARCH_ALL_NAME = "\u5168\u90e8";
    private View llLayout;
    private TextView mSearchTitle;
    private RecyclerView mGridView;
    private RecyclerView mGridViewFilter;
    private RecyclerView mGridViewWord;
    private RecyclerView mGridViewWordFenci;
    SourceViewModel sourceViewModel;

    private SearchWordAdapter searchWordAdapter;
    private FastSearchAdapter searchAdapter;
    private FastSearchAdapter searchAdapterFilter;
    private FastListAdapter spListAdapter;
    private String searchTitle = "";
    private HashMap<String, String> spNames;
    private boolean isFilterMode = false;
    private String searchFilterKey = "";    // 过滤的key
    private HashMap<String, ArrayList<Movie.Video>> resultVods; // 搜索结果
    private final List<Movie.Video> highMatchVods = new ArrayList<>();
    private boolean showHighMatchResults = false;
    private final List<String> quickSearchWord = new ArrayList<>();
    private final Set<String> wordListNames = new HashSet<>();
    private int wordListVersion = 0;
    private String selectedWordName = "";
    private HashMap<String, String> mCheckSources = null;

    // 顶部玻璃钮：返回 / 筛选
    private ImageView ivFastSearchBack;
    private ImageView ivFastSearchFilter;
    /** 筛选菜单浮层（挂在 android.R.id.content 的玻璃浮层，不用 PopupWindow） */
    private View filterMenuScrim;
    private View filterMenuPanel;
    /** 网盘源显示开关：false 时隐藏 tag==folder 的网盘条目 */
    private boolean showPanSource = true;
    /** 名字带网盘特征的源（夸父/嘟嘟等），判定结果按 sourceKey 缓存 */
    private final Set<String> panSourceKeys = new HashSet<>();
    /** 主结果列表的未过滤镜像：切网盘开关时按它重筛，顺序不变 */
    private final List<Movie.Video> mainShownVods = new ArrayList<>();
    /** 按搜索发起顺序排列的源名（spNames 是 HashMap 无序，菜单需要有序表） */
    private final List<String> orderedSourceNames = new ArrayList<>();
    private long mLastGlassClickAt = 0;
    private int mLastGlassClickId = 0;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_fast_search;
    }

    @Override
    protected void init() {
        spNames = new HashMap<String, String>();
        resultVods = new HashMap<String, ArrayList<Movie.Video>>();
        initView();
        initViewModel();
        initData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (searchPaused) {
            resumePausedSearches();
        }
    }

    private void initView() {
        EventBus.getDefault().register(this);
        llLayout = findViewById(R.id.llLayout);
        mSearchTitle = findViewById(R.id.mSearchTitle);
        mGridView = findViewById(R.id.mGridView);
        mGridViewWord = findViewById(R.id.mGridViewWord);
        mGridViewFilter = findViewById(R.id.mGridViewFilter);

        // 顶部玻璃钮：返回 / 筛选（按压缩小回弹 + 时间戳防连点，与播放器玻璃钮同一套手感）
        ivFastSearchBack = findViewById(R.id.ivFastSearchBack);
        ivFastSearchFilter = findViewById(R.id.ivFastSearchFilter);
        bindFastGlassPressEffect(ivFastSearchBack);
        bindFastGlassPressEffect(ivFastSearchFilter);
        ivFastSearchBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!allowFastGlassClick(v)) return;
                dismissFilterMenu(true);
                onBackPressed();
            }
        });
        ivFastSearchFilter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!allowFastGlassClick(v)) return;
                if (filterMenuPanel != null) dismissFilterMenu(true);
                else showFilterMenu();
            }
        });

        mGridViewWord.setHasFixedSize(true);
        mGridViewWord.setLayoutManager(new LinearLayoutManager(this.mContext, LinearLayoutManager.HORIZONTAL, false));
        spListAdapter = new FastListAdapter();
        mGridViewWord.setAdapter(spListAdapter);

        spListAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                selectWord(spListAdapter.getItem(position));
            }
        });

        mGridView.setHasFixedSize(true);
        mGridView.setLayoutManager(new GridLayoutManager(this.mContext, 3));

        searchAdapter = new FastSearchAdapter();
        mGridView.setAdapter(searchAdapter);

        searchAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Movie.Video video = searchAdapter.getData().get(position);
                if (video != null) {
                    openSearchVideo(video, false);
                }
            }
        });


        mGridViewFilter.setLayoutManager(new GridLayoutManager(this.mContext, 3));
        searchAdapterFilter = new FastSearchAdapter();
        mGridViewFilter.setAdapter(searchAdapterFilter);
        searchAdapterFilter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Movie.Video video = searchAdapterFilter.getData().get(position);
                if (video != null) {
                    openSearchVideo(video, true);
                }
            }
        });

        setLoadSir(llLayout);

        // 分词
        searchWordAdapter = new SearchWordAdapter();
        mGridViewWordFenci = findViewById(R.id.mGridViewWordFenci);
        mGridViewWordFenci.setAdapter(searchWordAdapter);
        mGridViewWordFenci.setLayoutManager(new LinearLayoutManager(this.mContext, LinearLayoutManager.HORIZONTAL, false));
        searchWordAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                String str = searchWordAdapter.getData().get(position);
                search(str);
            }
        });
        searchWordAdapter.setNewData(new ArrayList<>());
    }

    // ===== 顶部玻璃钮手感（与 VodController 同一套：按压缩小、松开平滑回弹、时间戳防连点） =====

    private void bindFastGlassPressEffect(final View view) {
        if (view == null) return;
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.animate().cancel();
                        v.animate().scaleX(0.86f).scaleY(0.86f).setDuration(90).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.animate().cancel();
                        v.animate().scaleX(1f).scaleY(1f).setDuration(180)
                                .setInterpolator(new DecelerateInterpolator()).start();
                        break;
                }
                return false;
            }
        });
    }

    /** @return true 放行本次点击；false 忽略（同一钮 0.45s 内的连击） */
    private boolean allowFastGlassClick(View v) {
        if (v == null) return false;
        long now = SystemClock.uptimeMillis();
        int id = v.getId();
        if (id == mLastGlassClickId && now - mLastGlassClickAt < 450) {
            return false;
        }
        mLastGlassClickAt = now;
        mLastGlassClickId = id;
        return true;
    }

    private int dp2px(float value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    // ===== 筛选玻璃菜单：竖排「全部 + 各源」+ 网盘源开关 =====

    /**
     * 从筛选键右上角下拉出的玻璃菜单（进 android.R.id.content 的浮层，
     * 主题玻璃面板 bg_glass_menu，日浅玻璃/夜深玻璃；不用 PopupWindow——
     * 独立窗口会抢焦点并按弹窗自己的标志重渲状态栏）。
     */
    private void showFilterMenu() {
        if (filterMenuPanel != null || isFinishing() || ivFastSearchFilter == null) return;
        final ViewGroup content = (ViewGroup) findViewById(android.R.id.content);
        if (content == null) return;

        final int pad = dp2px(5);
        final int rowH = dp2px(34);
        final int panelWidth = dp2px(232);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        // ---- 「全部」行：主题玻璃行（日浅玻璃/夜深玻璃），选中走强调色 ----
        list.addView(newFilterMenuRow(SEARCH_ALL_NAME, TextUtils.isEmpty(selectedWordName),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (!allowFastGlassClick(v)) return;
                        dismissFilterMenu(true);
                        selectWord(SEARCH_ALL_NAME);
                    }
                }));

        // ---- 网盘源开关行：紧跟「全部」便于选择，右侧对勾实时反映当前状态 ----
        LinearLayout panRow = new LinearLayout(this);
        panRow.setOrientation(LinearLayout.HORIZONTAL);
        panRow.setGravity(Gravity.CENTER_VERTICAL);
        panRow.setBackgroundResource(R.drawable.bg_glass_chip);
        panRow.setPadding(dp2px(12), 0, dp2px(12), 0);
        TextView panLabel = new TextView(this);
        panLabel.setText("\u5305\u542b\u7f51\u76d8\u6e90");
        panLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        panLabel.setTextColor(getResources().getColor(R.color.ui_text_primary));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        panLabel.setLayoutParams(plp);
        panRow.addView(panLabel);
        ImageView panIcon = new ImageView(this);
        panIcon.setBackgroundResource(showPanSource
                ? R.drawable.bg_check_circle_on : R.drawable.bg_check_circle_off);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp2px(18), dp2px(18));
        ilp.leftMargin = dp2px(8);
        panIcon.setLayoutParams(ilp);
        panRow.addView(panIcon);
        LinearLayout.LayoutParams panRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, rowH);
        panRowLp.topMargin = dp2px(3);
        panRow.setLayoutParams(panRowLp);
        panRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!allowFastGlassClick(v)) return;
                boolean shown = !showPanSource;
                panIcon.setBackgroundResource(shown
                        ? R.drawable.bg_check_circle_on : R.drawable.bg_check_circle_off);
                setPanSourceShown(shown);
                GlassTip.show(FastSearchActivity.this,
                        shown ? "\u5df2\u663e\u793a\u7f51\u76d8\u6e90" : "\u5df2\u9690\u85cf\u7f51\u76d8\u6e90");
            }
        });
        list.addView(panRow);

        // ---- 分隔线（主题色） ----
        View divider = new View(this);
        divider.setBackgroundColor(getResources().getColor(R.color.ui_divider));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp2px(1));
        dlp.topMargin = dp2px(6);
        dlp.bottomMargin = dp2px(6);
        divider.setLayoutParams(dlp);
        list.addView(divider);

        // ---- 各源行：与底部筛选条同一套数据与选中态 ----
        for (int i = 0; i < orderedSourceNames.size(); i++) {
            final String name = orderedSourceNames.get(i);
            boolean checked = TextUtils.equals(selectedWordName, name);
            list.addView(newFilterMenuRow(name, checked, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!allowFastGlassClick(v)) return;
                    dismissFilterMenu(true);
                    selectWord(name);
                }
            }));
        }

        // ---- 面板：竖排主题玻璃菜单（glass_menu_* 日夜自动），条目多时限高内部滚动 ----
        final ScrollView panel = new ScrollView(this);
        panel.setBackgroundResource(R.drawable.bg_glass_menu);
        panel.setPadding(pad, pad, pad, pad);
        panel.setVerticalScrollBarEnabled(false);
        panel.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.measure(
                View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int panelHeight = Math.min(panel.getMeasuredHeight(),
                (int) (content.getHeight() * 0.62f));

        // ---- 点空白收起的遮罩 ----
        final View scrim = new View(this);
        scrim.setBackgroundColor(0x59000000);
        scrim.setClickable(true);
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissFilterMenu(true);
            }
        });
        content.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(panel, new FrameLayout.LayoutParams(panelWidth, panelHeight));

        // 定位：右缘与筛选键对齐，挂在键的正下方
        int[] anchorLoc = new int[2];
        ivFastSearchFilter.getLocationOnScreen(anchorLoc);
        int[] contentLoc = new int[2];
        content.getLocationOnScreen(contentLoc);
        int left = anchorLoc[0] - contentLoc[0] + ivFastSearchFilter.getWidth() - panelWidth;
        left = Math.max(dp2px(8), left);
        int top = anchorLoc[1] - contentLoc[1] + ivFastSearchFilter.getHeight() + dp2px(6);
        FrameLayout.LayoutParams posLp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        posLp.leftMargin = left;
        posLp.topMargin = top;
        panel.setLayoutParams(posLp);

        filterMenuScrim = scrim;
        filterMenuPanel = panel;

        // 入场：以右上角为原点轻缩放淡入（iOS 菜单手感）
        panel.setPivotX(panelWidth);
        panel.setPivotY(0f);
        panel.setAlpha(0f);
        panel.setScaleX(0.92f);
        panel.setScaleY(0.92f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200)
                .setInterpolator(new OvershootInterpolator(1.1f)).start();
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(200).start();
    }

    /** 主题玻璃菜单行：未选中 = 浅玻璃行 + 主题字色；选中 = 强调色描边 + 强调色字 */
    private TextView newFilterMenuRow(String name, boolean checked, View.OnClickListener listener) {
        TextView tv = new TextView(this);
        tv.setText(name);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(getResources().getColor(checked
                ? R.color.ui_accent : R.color.ui_text_primary));
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setPadding(dp2px(12), 0, dp2px(12), 0);
        tv.setBackgroundResource(checked
                ? R.drawable.bg_glass_chip_selected : R.drawable.bg_glass_chip);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp2px(34)));
        tv.setOnClickListener(listener);
        return tv;
    }

    /** @param animate true 走淡出收场；false 直接撤（返回键等即时场景） */
    private void dismissFilterMenu(final boolean animate) {        final View panel = filterMenuPanel;
        final View scrim = filterMenuScrim;
        if (panel == null && scrim == null) return;
        filterMenuPanel = null;
        filterMenuScrim = null;
        if (panel == null || scrim == null || !animate || panel.getWidth() <= 0) {
            removeFilterOverlay(panel, scrim);
            return;
        }
        panel.animate().cancel();
        scrim.animate().cancel();
        panel.setPivotX(panel.getWidth());
        panel.setPivotY(0f);
        panel.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(150)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        removeFilterOverlay(panel, scrim);
                    }
                }).start();
        scrim.animate().alpha(0f).setDuration(150).start();
    }

    private void removeFilterOverlay(View panel, View scrim) {
        if (panel != null) {
            ViewGroup parent = (ViewGroup) panel.getParent();
            if (parent != null) parent.removeView(panel);
        }
        if (scrim != null) {
            ViewGroup parent = (ViewGroup) scrim.getParent();
            if (parent != null) parent.removeView(scrim);
        }
    }

    // ===== 网盘源过滤（tag == "folder" 的条目即网盘目录） =====

    /** 网盘源开关过滤：false 时隐藏网盘条目（tag==folder 或源名带夸父/嘟嘟），其余原样保序 */
    private List<Movie.Video> applyPanFilter(List<Movie.Video> src) {
        if (src == null || src.isEmpty() || showPanSource) return src;
        List<Movie.Video> out = new ArrayList<>();
        for (Movie.Video video : src) {
            if (video == null) continue;
            boolean isPan = TextUtils.equals("folder", video.tag)
                    || isPanSourceKey(video.sourceKey);
            if (isPan) continue;
            out.add(video);
        }
        return out;
    }

    /**
     * 该源是否为网盘源：tag==folder 之外的补充判定——源名含「夸父 / 嘟嘟」
     * 这类网盘聚合站也按网盘源对待；结果按 key 缓存避免反复解析源配置。
     */
    private boolean isPanSourceKey(String sourceKey) {
        if (TextUtils.isEmpty(sourceKey)) return false;
        if (panSourceKeys.contains(sourceKey)) return true;
        SourceBean bean = ApiConfig.get().getSource(sourceKey);
        if (bean != null && !TextUtils.isEmpty(bean.getName())) {
            String name = bean.getName();
            if (name.contains("\u5938\u7236") || name.contains("\u561f\u561f")) {
                panSourceKeys.add(sourceKey);
                return true;
            }
        }
        return false;
    }

    /** 切换后重筛当前显示中的那个网格，不碰加载/空态 */
    private void setPanSourceShown(boolean shown) {
        if (showPanSource == shown) return;
        showPanSource = shown;
        // 正在浏览网盘目录（folder）时不重筛：目录列表不属于主结果链路，交给原逻辑
        if (!folderHistory.isEmpty() || folderLoading) return;
        if (mGridViewFilter.getVisibility() == View.VISIBLE
                && mGridView.getVisibility() != View.VISIBLE) {
            ArrayList<Movie.Video> list = TextUtils.isEmpty(searchFilterKey)
                    ? null : resultVods.get(searchFilterKey);
            searchAdapterFilter.setNewData(applyPanFilter(
                    list == null ? new ArrayList<Movie.Video>() : list));
        } else {
            searchAdapter.setNewData(applyPanFilter(new ArrayList<>(mainShownVods)));
        }
    }

    private void setPosterFocusScale(View itemView, boolean focused) {
        if (itemView == null) return;
        if (focused) {
            itemView.bringToFront();
        }
        float scale = focused ? POSTER_FOCUS_SCALE : 1.0f;
        itemView.animate()
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(POSTER_FOCUS_ANIM_DURATION)
                .setInterpolator(new BounceInterpolator())
                .start();
    }

    private void initViewModel() {
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.listResult.observe(this, new androidx.lifecycle.Observer<AbsXml>() {
            @Override
            public void onChanged(AbsXml data) {
                if (!folderLoading) return;
                folderLoading = false;
                if (data == null || data.movie == null || data.movie.videoList == null) {
                    showEmpty();
                    return;
                }
                showSuccess();
                if (folderFilterMode) {
                    mGridView.setVisibility(View.GONE);
                    mGridViewFilter.setVisibility(View.VISIBLE);
                    searchAdapterFilter.setNewData(data.movie.videoList);
                } else {
                    mGridViewFilter.setVisibility(View.GONE);
                    mGridView.setVisibility(View.VISIBLE);
                    searchAdapter.setNewData(data.movie.videoList);
                }
            }
        });
    }

    private void openSearchVideo(Movie.Video video, boolean filterMode) {
        pauseSearchTasks();
        if (TextUtils.equals("folder", video.tag)) {
            folderHistory.add(new ArrayList<>(filterMode ? searchAdapterFilter.getData() : searchAdapter.getData()));
            folderHistoryFilter.add(filterMode);
            folderHistorySiteKeys.add(getSelectedSearchSiteKey());
            folderFilterMode = filterMode;
            folderLoading = true;
            showLoading();
            sourceViewModel.getList(video.sourceKey, video.id);
            return;
        }
        Bundle bundle = new Bundle();
        bundle.putString("id", video.id);
        bundle.putString("sourceKey", video.sourceKey);
        bundle.putString("title", video.name);
        bundle.putString("picture", video.pic);
        putDetailFallbackCandidates(bundle, video);
        jumpActivity(DetailActivity.class, bundle);
    }

    @Override
    public void onBackPressed() {
        // 筛选菜单开着时，返回键只收菜单，不退出页面
        if (filterMenuPanel != null) {
            dismissFilterMenu(true);
            return;
        }
        if (!folderHistory.isEmpty()) {
            String currentSiteKey = getSelectedSearchSiteKey();
            String folderSiteKey = folderHistorySiteKeys.get(folderHistorySiteKeys.size() - 1);
            if (!TextUtils.equals(currentSiteKey, folderSiteKey)) {
                folderLoading = false;
                folderHistory.clear();
                folderHistoryFilter.clear();
                folderHistorySiteKeys.clear();
                super.onBackPressed();
                return;
            }
            folderLoading = false;
            List<Movie.Video> previous = folderHistory.remove(folderHistory.size() - 1);
            boolean filterMode = folderHistoryFilter.remove(folderHistoryFilter.size() - 1);
            folderHistorySiteKeys.remove(folderHistorySiteKeys.size() - 1);
            folderFilterMode = filterMode;
            showSuccess();
            if (filterMode) {
                mGridView.setVisibility(View.GONE);
                mGridViewFilter.setVisibility(View.VISIBLE);
                searchAdapterFilter.setNewData(previous);
            } else {
                mGridViewFilter.setVisibility(View.GONE);
                mGridView.setVisibility(View.VISIBLE);
                searchAdapter.setNewData(previous);
            }
            return;
        }
        super.onBackPressed();
    }

    private String getSelectedSearchSiteKey() {
        if (TextUtils.isEmpty(selectedWordName) || TextUtils.equals(selectedWordName, SEARCH_ALL_NAME)) {
            return SEARCH_ALL_NAME;
        }
        String key = spNames.get(selectedWordName);
        return TextUtils.isEmpty(key) ? selectedWordName : key;
    }

    private void filterResult(String spName) {
        if (TextUtils.isEmpty(spName)) return;
        selectedWordName = spName;
        setSelectedWordName(spName);
        if (TextUtils.equals(spName, SEARCH_ALL_NAME)) {
            mGridView.setVisibility(View.VISIBLE);
            mGridViewFilter.setVisibility(View.GONE);
            return;
        }
        mGridView.setVisibility(View.GONE);
        mGridViewFilter.setVisibility(View.VISIBLE);
        String key = spNames.get(spName);
        if (TextUtils.isEmpty(key)) return;

        if (TextUtils.equals(searchFilterKey, key)) return;
        searchFilterKey = key;

        List<Movie.Video> list = resultVods.get(key);
        if (list == null) {
            list = new ArrayList<>();
        }
        searchAdapterFilter.setNewData(applyPanFilter(list));
    }

    private void selectWord(String spName) {
        if (TextUtils.isEmpty(spName) || TextUtils.equals(selectedWordName, spName)) return;
        filterResult(spName);
    }

    private void updateWordListWhenIdle(final Runnable action) {
        if (action == null) return;
        if (mGridViewWord == null) {
            action.run();
            return;
        }
        if (mGridViewWord.isComputingLayout()) {
            mGridViewWord.post(new Runnable() {
                @Override
                public void run() {
                    updateWordListWhenIdle(action);
                }
            });
            return;
        }
        action.run();
    }

    private void setSelectedWordName(final String spName) {
        updateWordListWhenIdle(new Runnable() {
            @Override
            public void run() {
                spListAdapter.setSelectedName(spName);
                spListAdapter.refreshVisibleSelection(mGridViewWord);
            }
        });
    }

    private void setWordListData(List<String> data) {
        final List<String> wordList = new ArrayList<>(data);
        final int version = ++wordListVersion;
        wordListNames.clear();
        wordListNames.addAll(wordList);
        updateWordListWhenIdle(new Runnable() {
            @Override
            public void run() {
                if (version != wordListVersion) return;
                spListAdapter.setNewData(wordList);
                if (wordList.size() > 0 && TextUtils.equals(wordList.get(0), SEARCH_ALL_NAME)) {
                    mGridViewWord.scrollToPosition(0);
                }
            }
        });
    }

    private void requestWordListFirstFocus() {
        if (mGridViewWord == null) return;
        mGridViewWord.post(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing() && mGridViewWord != null) {
                    mGridViewWord.scrollToPosition(0);
                }
            }
        });
    }

    private void addWordListDataIfAbsent(final String name) {
        if (TextUtils.isEmpty(name) || !wordListNames.add(name)) return;
        final int version = wordListVersion;
        updateWordListWhenIdle(new Runnable() {
            @Override
            public void run() {
                if (version != wordListVersion) return;
                List<String> names = spListAdapter.getData();
                for (int i = 0; i < names.size(); ++i) {
                    if (TextUtils.equals(name, names.get(i))) {
                        return;
                    }
                }
                spListAdapter.addData(name);
            }
        });
    }

    private void fenci() {
        if (!quickSearchWord.isEmpty()) return; // 如果经有分词了，不再进行二次分词
        quickSearchWord.addAll(SearchHelper.splitWords(searchTitle));
        List<String> words = new ArrayList<>(new LinkedHashSet<>(quickSearchWord));
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_WORD, words));
        // 分词
//        OkGo.<String>get("http://api.pullword.com/get.php?source=" + URLEncoder.encode(searchTitle) + "&param1=0&param2=0&json=1")
//                .tag("fenci")
//                .execute(new AbsCallback<String>() {
//                    @Override
//                    public String convertResponse(okhttp3.Response response) throws Throwable {
//                        if (response.body() != null) {
//                            return response.body().string();
//                        } else {
//                            throw new IllegalStateException("网络请求错误");
//                        }
//                    }
//
//                    @Override
//                    public void onSuccess(Response<String> response) {
//                        String json = response.body();
//                        quickSearchWord.clear();
//                        try {
//                            for (JsonElement je : new Gson().fromJson(json, JsonArray.class)) {
//                                quickSearchWord.add(je.getAsJsonObject().get("t").getAsString());
//                            }
//                        } catch (Throwable th) {
//                            th.printStackTrace();
//                        }
//                        quickSearchWord.addAll(SearchHelper.splitWords(searchTitle));
//                        List<String> words = new ArrayList<>(new HashSet<>(quickSearchWord));
//                        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_WORD, words));
//                    }
//
//                    @Override
//                    public void onError(Response<String> response) {
//                        super.onError(response);
//                    }
//                });
    }

    private void initData() {
        initCheckedSourcesForSearch();
        Intent intent = getIntent();
        if (intent != null && intent.hasExtra("title")) {
            String title = intent.getStringExtra("title");
            showLoading();
            search(title);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void server(ServerEvent event) {
        if (event.type == ServerEvent.SERVER_SEARCH) {
            String title = (String) event.obj;
            showLoading();
            search(title);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_SEARCH_RESULT) {
            try {
                searchData(event.obj == null ? null : (AbsXml) event.obj);
            } catch (Exception e) {
                searchData(null);
            }
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_WORD) {
            if (event.obj != null) {
                List<String> data = (List<String>) event.obj;
                searchWordAdapter.setNewData(data);
            }
        }
        updateSearchStatus();
    }

    private void initCheckedSourcesForSearch() {
        mCheckSources = SearchHelper.getSourcesForSearch();
    }

    private void search(String title) {
        cancel();
        showLoading();
        this.searchTitle = title;
        fenci();
        mGridView.setVisibility(View.INVISIBLE);
        mGridViewFilter.setVisibility(View.GONE);
        searchAdapter.setNewData(new ArrayList<>());
        searchAdapterFilter.setNewData(new ArrayList<>());

        selectedWordName = "";
        filterResult(SEARCH_ALL_NAME);
        resultVods.clear();
        highMatchVods.clear();
        showHighMatchResults = false;
        searchFilterKey = "";
        isFilterMode = false;
        spNames.clear();
        orderedSourceNames.clear();
        mainShownVods.clear();
        panSourceKeys.clear();
        totalSearchCount.set(0);
        timedOutSearchCount.set(0);
        updateSearchStatus();

        //写入历史记录
        HistoryHelper.setSearchHistory(title);

        searchResult();
    }

    private ExecutorService searchExecutorService = null;
    private ScheduledExecutorService searchTimeoutExecutor = null;
    private final AtomicInteger allRunCount = new AtomicInteger(0);
    private final Set<String> pendingSearchKeys = Collections.synchronizedSet(new HashSet<String>());
    private final List<SearchTask> waitingSearchTasks = Collections.synchronizedList(new ArrayList<SearchTask>());
    private final Set<String> startedSearchKeys = Collections.synchronizedSet(new HashSet<String>());
    private final Set<String> releasedSearchKeys = Collections.synchronizedSet(new HashSet<String>());
    private int startedSearchCountOffset = 0;
    private final AtomicInteger searchTokenSeq = new AtomicInteger(0);
    private final AtomicInteger totalSearchCount = new AtomicInteger(0);
    private final AtomicInteger timedOutSearchCount = new AtomicInteger(0);
    private String currentSearchToken = "";
    private boolean searchPaused = false;
    private final List<Movie.Video> detailFallbackSearchResults = new ArrayList<>();
    private boolean folderFilterMode;
    private final List<List<Movie.Video>> folderHistory = new ArrayList<>();
    private final List<Boolean> folderHistoryFilter = new ArrayList<>();
    private final List<String> folderHistorySiteKeys = new ArrayList<>();
    private boolean folderLoading;

    private void searchResult() {
        try {
            if (searchExecutorService != null) {
                searchExecutorService.shutdownNow();
                searchExecutorService = null;
                JsLoader.stopAll();
            }
            if (searchTimeoutExecutor != null) {
                searchTimeoutExecutor.shutdownNow();
                searchTimeoutExecutor = null;
            }
        } catch (Throwable th) {
            th.printStackTrace();
        } finally {
            searchAdapter.setNewData(new ArrayList<>());
            searchAdapterFilter.setNewData(new ArrayList<>());
            allRunCount.set(0);
            pendingSearchKeys.clear();
            waitingSearchTasks.clear();
            startedSearchKeys.clear();
            releasedSearchKeys.clear();
            startedSearchCountOffset = 0;
            currentSearchToken = String.valueOf(searchTokenSeq.incrementAndGet());
            searchPaused = false;
            totalSearchCount.set(0);
            timedOutSearchCount.set(0);
            detailFallbackSearchResults.clear();
            folderHistory.clear();
            folderHistoryFilter.clear();
            folderHistorySiteKeys.clear();
            updateSearchStatus();
        }
        List<SourceBean> searchRequestList = new ArrayList<>();
        searchRequestList.addAll(ApiConfig.get().getSourceBeanList());
        SourceBean home = ApiConfig.get().getHomeSourceBean();
        searchRequestList.remove(home);
        searchRequestList.add(0, home);


        ArrayList<SearchTask> fastSearchTasks = new ArrayList<>();
        ArrayList<SearchTask> blockingSearchTasks = new ArrayList<>();
        ArrayList<String> hots = new ArrayList<>();
        hots.add(SEARCH_ALL_NAME);

        setWordListData(hots);
        for (SourceBean bean : searchRequestList) {
            if (!bean.isSearchable()) {
                continue;
            }
            if (mCheckSources != null && !mCheckSources.containsKey(bean.getKey())) {
                continue;
            }
            SearchTask task = new SearchTask(bean.getKey(), searchTitle, currentSearchToken, isBlockingSearchSource(bean));
            if (task.blocking) {
                blockingSearchTasks.add(task);
            } else {
                fastSearchTasks.add(task);
            }
            this.spNames.put(bean.getName(), bean.getKey());
            orderedSourceNames.add(bean.getName());
        }
        ArrayList<SearchTask> searchTasks = new ArrayList<>();
        searchTasks.addAll(fastSearchTasks);
        searchTasks.addAll(blockingSearchTasks);

        if (searchTasks.size() <= 0) {
            showEmpty();
            setSearchStatusText("\u65e0\u641c\u7d22\u6e90", "\u7ed3\u679c 0");
            return;
        }
        for (SearchTask task : searchTasks) {
            pendingSearchKeys.add(task.sourceKey);
        }
        allRunCount.set(searchTasks.size());
        totalSearchCount.set(searchTasks.size());
        updateSearchStatus();
        searchExecutorService = createSearchExecutor();
        searchTimeoutExecutor = Executors.newSingleThreadScheduledExecutor();
        waitingSearchTasks.addAll(searchTasks);
        startNextSearchBatch(currentSearchToken);
        startSearchPump(currentSearchToken);
        updateSearchStatus();
    }

    // 向过滤栏添加有结果的spname
    private String addWordAdapterIfNeed(String key) {
        try {
            String name = "";
            for (String n : spNames.keySet()) {
                if (TextUtils.equals(spNames.get(n), key)) {
                    name = n;
                }
            }
            if (TextUtils.isEmpty(name)) return key;

            addWordListDataIfAbsent(name);
            return key;
        } catch (Exception e) {
            return key;
        }
    }

    private boolean matchSearchResult(String name, String searchTitle) {
        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(searchTitle)) return false;
        searchTitle = searchTitle.trim();
        String[] arr = searchTitle.split("\\s+");
        int matchNum = 0;
        for(String one : arr) {
            if (name.contains(one)) matchNum++;
        }
        return matchNum == arr.length;
    }

    private boolean isExactSearchResult(Movie.Video video) {
        return video != null && !TextUtils.isEmpty(video.name) && !TextUtils.isEmpty(searchTitle)
                && TextUtils.equals(video.name.trim(), searchTitle.trim());
    }

    private boolean isHighMatchSearchResult(Movie.Video video) {
        return video != null && !TextUtils.isEmpty(video.name) && !TextUtils.isEmpty(searchTitle)
                && video.name.replaceAll("\\s+", "").startsWith(searchTitle.replaceAll("\\s+", ""));
    }

    private boolean shouldShowHighMatchResults() {
        if (showHighMatchResults || searchAdapter.getData().size() > 0) return false;
        int total = totalSearchCount.get();
        int threshold = Math.min(SEARCH_THREAD_COUNT, total);
        return threshold > 0 && total - allRunCount.get() >= threshold;
    }

    private void addMainSearchResults(List<Movie.Video> data) {
        if (data == null || data.isEmpty()) return;
        // 镜像进未过滤主列表（切网盘开关时按它重筛、顺序不变），显示层再过一遍网盘开关
        mainShownVods.addAll(data);
        List<Movie.Video> shown = applyPanFilter(data);
        if (shown.isEmpty()) return;
        if (searchAdapter.getData().size() > 0) {
            searchAdapter.addData(shown);
        } else {
            showSuccess();
            if (!isFilterMode) mGridView.setVisibility(View.VISIBLE);
            searchAdapter.setNewData(shown);
        }
    }

    private void searchData(AbsXml absXml) {
        if (!isCurrentSearchResult(absXml)) {
            return;
        }
        String sourceKey = absXml == null ? "" : absXml.sourceKey;
        if (!markSearchFinished(sourceKey, absXml.searchToken)) {
            return;
        }
        releaseSearchSlotAndStartNext(sourceKey, absXml.searchToken);
        String lastSourceKey = "";
        List<Movie.Video> exactData = new ArrayList<>();
        List<Movie.Video> highData = new ArrayList<>();

        if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
            for (Movie.Video video : absXml.movie.videoList) {
                if (!matchSearchResult(video.name, searchTitle)) continue;
                detailFallbackSearchResults.add(video);
                if (!resultVods.containsKey(video.sourceKey)) {
                    resultVods.put(video.sourceKey, new ArrayList<Movie.Video>());
                }
                resultVods.get(video.sourceKey).add(video);
                if (isHighMatchSearchResult(video)) {
                    highMatchVods.add(video);
                    highData.add(video);
                }
                if (isExactSearchResult(video)) {
                    exactData.add(video);
                }
                if (!TextUtils.equals(video.sourceKey, lastSourceKey)) {
                    lastSourceKey = this.addWordAdapterIfNeed(video.sourceKey);
                }
            }
        }

        if (showHighMatchResults) {
            addMainSearchResults(highData);
        } else if (!exactData.isEmpty()) {
            addMainSearchResults(exactData);
        } else if (shouldShowHighMatchResults()) {
            showHighMatchResults = true;
            addMainSearchResults(new ArrayList<>(highMatchVods));
        }

        finishSearchIfDone();
    }

    private void putDetailFallbackCandidates(Bundle bundle, Movie.Video selectedVideo) {
        if (bundle == null || selectedVideo == null || TextUtils.isEmpty(selectedVideo.name)) {
            return;
        }
        String title = selectedVideo.name.trim();
        ArrayList<Movie.Video> candidates = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (Movie.Video video : detailFallbackSearchResults) {
            if (video == null || TextUtils.isEmpty(video.id)
                    || !TextUtils.equals(title, video.name == null ? "" : video.name.trim())) {
                continue;
            }
            String key = (video.sourceKey == null ? "" : video.sourceKey) + "|" + video.id;
            if (keys.add(key)) {
                candidates.add(video);
                if (candidates.size() >= 20) {
                    break;
                }
            }
        }
        if (!candidates.isEmpty()) {
            bundle.putSerializable(DetailActivity.EXTRA_DETAIL_FALLBACK_CANDIDATES, candidates);
        }
    }

    private void scheduleSearchAdvance(final String sourceKey, final String searchToken) {
        if (searchTimeoutExecutor == null) return;
        searchTimeoutExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                if (!isCurrentSearchToken(searchToken)) return;
                if (isSearchPending(sourceKey, searchToken) && releaseSearchSlot(sourceKey, searchToken)) {
                    startNextSearchTask(searchToken);
                }
            }
        }, SEARCH_NEXT_BATCH_SECONDS, TimeUnit.SECONDS);
    }

    private void startSearchPump(final String searchToken) {
        if (searchTimeoutExecutor == null) return;
        searchTimeoutExecutor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!isCurrentSearchToken(searchToken) || allRunCount.get() <= 0) return;
                    if (getWaitingSearchCount() > 0) {
                        startNextSearchBatch(searchToken);
                        updateSearchStatusOnUiThread();
                    }
                } catch (Throwable th) {
                    th.printStackTrace();
                }
            }
        }, SEARCH_PUMP_SECONDS, SEARCH_PUMP_SECONDS, TimeUnit.SECONDS);
    }

    private void updateSearchStatusOnUiThread() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updateSearchStatus();
            }
        });
    }

    private void scheduleSearchTimeout(final String sourceKey, final String searchToken) {
        if (searchTimeoutExecutor == null) return;
        searchTimeoutExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                if (!isCurrentSearchToken(searchToken)) return;
                if (markSearchFinished(sourceKey, searchToken)) {
                    timedOutSearchCount.incrementAndGet();
                    releaseSearchSlotAndStartNext(sourceKey, searchToken);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updateSearchStatus();
                            finishSearchIfDone();
                        }
                    });
                }
            }
        }, SEARCH_SITE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private boolean submitSearchTask(SearchTask task) {
        if (!isSearchPending(task.sourceKey, task.searchToken)) return false;
        if (searchExecutorService == null || searchExecutorService.isShutdown()) return false;
        try {
            searchExecutorService.execute(task);
        } catch (RejectedExecutionException e) {
            return false;
        }
        scheduleSearchAdvance(task.sourceKey, task.searchToken);
        scheduleSearchTimeout(task.sourceKey, task.searchToken);
        updateSearchStatusOnUiThread();
        return true;
    }

    private ExecutorService createSearchExecutor() {
        return new ThreadPoolExecutor(0, SEARCH_MAX_THREAD_COUNT, 30L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>());
    }

    private void startNextSearchBatch(String searchToken) {
        for (int i = 0; i < SEARCH_THREAD_COUNT; i++) {
            if (!startNextSearchTask(searchToken)) {
                return;
            }
        }
    }

    private boolean startNextSearchTask(String searchToken) {
        if (!isCurrentSearchToken(searchToken)) return false;
        SearchTask task = takeNextSearchTask(searchToken);
        if (task == null) {
            return false;
        }
        if (!submitSearchTask(task)) {
            startedSearchKeys.remove(task.sourceKey);
            synchronized (waitingSearchTasks) {
                waitingSearchTasks.add(0, task);
            }
            return false;
        }
        return true;
    }

    private SearchTask takeNextSearchTask(String searchToken) {
        synchronized (waitingSearchTasks) {
            while (!waitingSearchTasks.isEmpty()) {
                SearchTask task = waitingSearchTasks.remove(0);
                if (!isSearchPending(task.sourceKey, searchToken) || !startedSearchKeys.add(task.sourceKey)) {
                    continue;
                }
                return task;
            }
        }
        return null;
    }

    private void resumePausedSearches() {
        if (!searchPaused) {
            return;
        }
        searchPaused = false;
        List<String> sourceKeys = getPendingSearchKeys();
        if (sourceKeys.isEmpty()) {
            finishSearchIfDone();
            return;
        }
        currentSearchToken = String.valueOf(searchTokenSeq.incrementAndGet());
        waitingSearchTasks.clear();
        startedSearchCountOffset = Math.max(0, totalSearchCount.get() - allRunCount.get());
        startedSearchKeys.clear();
        releasedSearchKeys.clear();
        for (String sourceKey : sourceKeys) {
            SourceBean bean = ApiConfig.get().getSource(sourceKey);
            waitingSearchTasks.add(new SearchTask(sourceKey, searchTitle, currentSearchToken, isBlockingSearchSource(bean)));
        }
        if (searchExecutorService == null || searchExecutorService.isShutdown()) {
            searchExecutorService = createSearchExecutor();
        }
        if (searchTimeoutExecutor == null || searchTimeoutExecutor.isShutdown()) {
            searchTimeoutExecutor = Executors.newSingleThreadScheduledExecutor();
        }
        startNextSearchBatch(currentSearchToken);
        startSearchPump(currentSearchToken);
        updateSearchStatus();
    }

    private void pauseSearchTasks() {
        try {
            if (searchExecutorService != null) {
                searchExecutorService.shutdownNow();
                searchExecutorService = null;
                JsLoader.stopAll();
            }
            if (searchTimeoutExecutor != null) {
                searchTimeoutExecutor.shutdownNow();
                searchTimeoutExecutor = null;
            }
            searchPaused = allRunCount.get() > 0;
            if (searchPaused) {
                cancel();
                currentSearchToken = "";
            }
            updateSearchStatus();
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private boolean isCurrentSearchResult(AbsXml absXml) {
        return absXml != null && isCurrentSearchToken(absXml.searchToken);
    }

    private boolean isCurrentSearchToken(String searchToken) {
        return !TextUtils.isEmpty(searchToken) && searchToken.equals(currentSearchToken);
    }

    private boolean markSearchFinished(String sourceKey, String searchToken) {
        if (!isCurrentSearchToken(searchToken)) return false;
        synchronized (pendingSearchKeys) {
            if (TextUtils.isEmpty(sourceKey)) {
                return false;
            }
            if (!pendingSearchKeys.remove(sourceKey)) {
                return false;
            }
            allRunCount.set(pendingSearchKeys.size());
            return true;
        }
    }

    private boolean releaseSearchSlot(String sourceKey, String searchToken) {
        if (!isCurrentSearchToken(searchToken) || TextUtils.isEmpty(sourceKey)) return false;
        return releasedSearchKeys.add(sourceKey);
    }

    private void releaseSearchSlotAndStartNext(String sourceKey, String searchToken) {
        if (releaseSearchSlot(sourceKey, searchToken)) {
            startNextSearchTask(searchToken);
        }
    }

    private boolean isSearchPending(String sourceKey, String searchToken) {
        if (!isCurrentSearchToken(searchToken) || TextUtils.isEmpty(sourceKey)) return false;
        synchronized (pendingSearchKeys) {
            return pendingSearchKeys.contains(sourceKey);
        }
    }

    private boolean isBlockingSearchSource(SourceBean bean) {
        return bean == null || bean.getType() == 3;
    }

    private List<String> getPendingSearchKeys() {
        synchronized (pendingSearchKeys) {
            return new ArrayList<>(pendingSearchKeys);
        }
    }

    private int getWaitingSearchCount() {
        synchronized (waitingSearchTasks) {
            return waitingSearchTasks.size();
        }
    }

    private void finishSearchIfDone() {
        if (allRunCount.get() > 0) return;
        searchPaused = false;
        updateSearchStatus();
        if (searchAdapter.getData().size() == 0 && resultVods.isEmpty()) {
            showEmpty();
        }
        cancel();
        if (searchTimeoutExecutor != null) {
            searchTimeoutExecutor.shutdownNow();
            searchTimeoutExecutor = null;
        }
    }

    private int getStartedSearchCount() {
        synchronized (startedSearchKeys) {
            return startedSearchCountOffset + startedSearchKeys.size();
        }
    }

    private int getResultCount() {
        return searchAdapter == null ? 0 : searchAdapter.getData().size();
    }

    private void updateSearchStatus() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            updateSearchStatusOnUiThread();
            return;
        }
        int total = totalSearchCount.get();
        int pending = allRunCount.get();
        int finished = Math.max(0, total - pending);
        int started = Math.min(total, getStartedSearchCount());
        int results = getResultCount();
        int timeouts = timedOutSearchCount.get();

        String firstLine;
        String secondLine;
        if (total <= 0) {
            firstLine = "\u51c6\u5907\u641c\u7d22";
            secondLine = "\u7ed3\u679c 0";
        } else if (searchPaused && pending > 0) {
            firstLine = "\u5df2\u6682\u505c " + finished + "/" + total;
            secondLine = "\u7ed3\u679c " + results + " \u00b7 \u5f85 " + pending;
        } else if (pending <= 0) {
            firstLine = "\u641c\u7d22\u5b8c\u6210 " + results;
            secondLine = "\u6e90 " + total + "/" + total;
            if (timeouts > 0) {
                secondLine += " \u00b7 \u8d85\u65f6 " + timeouts;
            }
        } else if (started >= total) {
            firstLine = finished > 0 ? "\u7b49\u6162\u6e90 " + pending : "\u7b49\u5f85\u8fd4\u56de " + pending;
            secondLine = "\u7ed3\u679c " + results + " \u00b7 \u5b8c\u6210 " + finished + "/" + total;
        } else {
            firstLine = "\u641c\u6e90 " + started + "/" + total;
            secondLine = "\u7ed3\u679c " + results + " \u00b7 \u5f85 " + pending;
        }
        setSearchStatusText(firstLine, secondLine);
    }

    private void setSearchStatusText(String firstLine, String secondLine) {
        if (mSearchTitle == null) return;
        String text = firstLine + "\n" + secondLine;
        SpannableString span = new SpannableString(text);
        int split = firstLine.length();
        span.setSpan(new StyleSpan(Typeface.BOLD), 0, split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new RelativeSizeSpan(1.05f), 0, split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new RelativeSizeSpan(0.78f), split + 1, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        // 第二行弱化字色也走主题色：写死白色在日间主题的浅玻璃底上会看不见
        span.setSpan(new ForegroundColorSpan(
                mContext != null ? mContext.getResources().getColor(R.color.ui_text_secondary)
                        : getResources().getColor(R.color.ui_text_secondary)),
                split + 1, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        mSearchTitle.setText(span);
    }

    private class SearchTask implements Runnable {
        private final String sourceKey;
        private final String title;
        private final String searchToken;
        private final boolean blocking;

        private SearchTask(String sourceKey, String title, String searchToken, boolean blocking) {
            this.sourceKey = sourceKey;
            this.title = title;
            this.searchToken = searchToken;
            this.blocking = blocking;
        }

        @Override
        public void run() {
            if (!isSearchPending(sourceKey, searchToken)) return;
            try {
                sourceViewModel.getSearch(sourceKey, title, searchToken);
            } catch (Throwable th) {
                th.printStackTrace();
                if (markSearchFinished(sourceKey, searchToken)) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updateSearchStatus();
                            finishSearchIfDone();
                        }
                    });
                }
            }
        }
    }

    private void cancel() {
        OkGo.getInstance().cancelTag("search");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancel();
        try {
            if (searchExecutorService != null) {
                searchExecutorService.shutdownNow();
                searchExecutorService = null;
                JsLoader.stopAll();
            }
            if (searchTimeoutExecutor != null) {
                searchTimeoutExecutor.shutdownNow();
                searchTimeoutExecutor = null;
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        EventBus.getDefault().unregister(this);
    }
}
