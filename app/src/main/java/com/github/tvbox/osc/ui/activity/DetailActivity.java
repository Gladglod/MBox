package com.github.tvbox.osc.ui.activity;

import android.annotation.SuppressLint;
import android.app.ActivityOptions;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.text.Html;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.content.pm.ActivityInfo;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.content.ClipboardManager;
import android.content.ClipData;

import androidx.fragment.app.FragmentContainerView;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.player.FloatPlayWindow;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.ui.adapter.SeriesAdapter;
import com.github.tvbox.osc.ui.adapter.SeriesFlagAdapter;
import com.github.tvbox.osc.ui.dialog.DownloadSelectDialog;
import com.github.tvbox.osc.ui.dialog.SeriesPickDialog;
import com.github.tvbox.osc.ui.dialog.SourceSpeedDialog;
import com.github.tvbox.osc.ui.fragment.PlayFragment;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.GlassTip;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.MotionCompat;
import com.github.tvbox.osc.util.SearchHelper;
import com.github.tvbox.osc.util.SourceSpeedTester;
import com.github.tvbox.osc.util.SubtitleHelper;
import com.github.tvbox.osc.util.ThemeUtil;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.lzy.okgo.OkGo;
import com.orhanobut.hawk.Hawk;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.jessyan.autosize.utils.AutoSizeUtils;

import android.graphics.Paint;

/**
 * @author pj567
 * @date :2020/12/22
 * @description:
 */

public class DetailActivity extends BaseActivity {
    private static final String STATE_FULL_WINDOWS = "detail_full_windows";
    private static final String DETAIL_FALLBACK_SEARCH_TAG = "detail_fallback_search";
    private static final String SWITCH_SOURCE_SEARCH_TAG = "switch_source_search";
    private static final String PANEL_SOURCE_SEARCH_TAG = "panel_source_search";
    public static final String EXTRA_DETAIL_FALLBACK_CANDIDATES = "detailFallbackCandidates";
    private static final int DETAIL_FALLBACK_MAX_SEARCH = 20;
    private static final long DETAIL_FALLBACK_SEARCH_TIMEOUT_MS = 8000L;
    private static final long DETAIL_FALLBACK_DETAIL_TIMEOUT_MS = 6000L;
    private static final long SWITCH_SOURCE_SEARCH_TIMEOUT_MS = 10000L;
    private static final long PANEL_SOURCE_SEARCH_TIMEOUT_MS = 15000L;
    private View llLayout;
    private androidx.core.widget.NestedScrollView scrollView;
    private FragmentContainerView llPlayerFragmentContainer;
    private View llPlayerFragmentContainerBlock;
    private View llPlayerPlace;
    private int playerHeightPx = 0;
    private PlayFragment playFragment = null;
    private View thumbContainer;
    private ImageView ivThumb;
    private TextView tvName;
    private TextView tvYear;
    private TextView tvSite;
    private TextView tvArea;
    private TextView tvLang;
    private TextView tvActor;
    private TextView tvDirector;
    private TextView tvPlayUrl;
    private TextView tvDes;
    /** 简介折叠状态与开关行控件（tvDes 是被折叠的那棵 TextView） */
    private LinearLayout desToggleRow;
    private TextView tvDesToggle;
    private TextView desArrowIcon;
    private boolean desExpanded = false;
    /** 底部「其他信息」折叠：来源/年份常驻，地区/语言/演员/导演/播放链接收进 infoMoreBlock */
    private LinearLayout detailInfoBlock;
    private LinearLayout infoMoreBlock;
    private LinearLayout infoToggleRow;
    private TextView tvInfoToggle;
    private TextView infoArrowIcon;
    private boolean infoExpanded = false;
//    private TextView tvSort;
    private TextView tvSeriesSort;
    /** 展开全部选集（列表/表格） */
    private TextView tvSeriesExpand;
    private TextView tvChangeSource;
    /** 手动换源测速面板与单源搜索状态 */
    private SourceSpeedDialog sourceSpeedDialog;
    private ExecutorService switchSourceSearchExecutor;
    private java.util.concurrent.Future<?> switchSourceSearchFuture;
    private boolean switchSourceSearching = false;
    private String switchSourceToken = "";
    private String switchSourceTargetKey = "";
    private String switchSourceTargetName = "";
    private String switchSourceTitle = "";
    private String manualSwitchSourceName = "";
    private int switchSourceRequestIndex = 0;
    private final Runnable switchSourceSearchTimeout = new Runnable() {
        @Override
        public void run() {
            if (!switchSourceSearching) {
                return;
            }
            switchSourceSearching = false;
            cancelSwitchSourceSearchTask();
            OkGo.getInstance().cancelTag(SWITCH_SOURCE_SEARCH_TAG);
            if (sourceSpeedDialog != null && sourceSpeedDialog.isShowing()) {
                sourceSpeedDialog.setRowSearching(switchSourceTargetKey, false);
            }
            GlassTip.show(DetailActivity.this, "搜索片源超时，请稍后再试");
        }
    };
    /** 换源面板后台预搜索：面板打开时并发搜全部源，标记「有片源/无片源」 */
    private ExecutorService panelSearchExecutor;
    private boolean panelSearchActive = false;
    private String panelSearchBaseToken = "";
    private String panelSearchTitle = "";
    private int panelSearchRequestIndex = 0;
    private final Runnable panelSearchTimeout = new Runnable() {
        @Override
        public void run() {
            if (!panelSearchActive) {
                return;
            }
            panelSearchActive = false;
            stopPanelSearchExecutor();
            OkGo.getInstance().cancelTag(PANEL_SOURCE_SEARCH_TAG);
            if (sourceSpeedDialog != null && sourceSpeedDialog.isShowing()) {
                sourceSpeedDialog.finishSearchPhase();
            }
        }
    };
    /** 收藏星标：★ = 已收藏，☆ = 未收藏 */
    private TextView tvCollect;
    /** 缓存入口：弹出「缓存选集」弹窗 */
    private TextView tvCache;
    private RecyclerView mGridViewFlag;
    private RecyclerView mGridViewQuality;
    private RecyclerView mGridView;
    private RecyclerView mSeriesGroupView;
    private LinearLayout mEmptyPlayList;
    private LinearLayout tvSeriesGroup;
    private SourceViewModel sourceViewModel;
    private Movie.Video mVideo;
    private VodInfo vodInfo;
    private SeriesFlagAdapter seriesFlagAdapter;
    private BaseQuickAdapter<String, BaseViewHolder> qualityAdapter;
    private BaseQuickAdapter<String, BaseViewHolder> seriesGroupAdapter;
    private SeriesAdapter seriesAdapter;
    public String vodId;
    public String sourceKey;
    public String firstsourceKey;
    private boolean fromCollect;
    boolean seriesSelect = false;
    private View seriesFlagFocus = null;
    private boolean isReverse;
    private String preFlag="";
    private VodInfo.VodSeries routeSwitchSeries;
    private boolean firstReverse;
    private GridLayoutManager mGridViewLayoutMgr = null;
    private HashMap<String, String> mCheckSources = null;
    private final ArrayList<String> seriesGroupOptions = new ArrayList<>();
    private final ArrayList<String> qualityOptions = new ArrayList<>();
    private View currentSeriesGroupView;
    private int selectedSeriesGroupPosition;
    private int GroupCount;
    private int qualityPosition;
    boolean showPreview = Hawk.get(HawkConfig.SHOW_PREVIEW, true);; // true 开启 false 关闭

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            fullWindows = savedInstanceState.getBoolean(STATE_FULL_WINDOWS, false);
        }
        super.onCreate(savedInstanceState);
        // singleTask 任务根：本实例存活期间，点应用图标应直接回到播放页（见 sDetailSessionAlive）
        sDetailSessionAlive = true;
        setupPipKeepClearStrip();
    }

    /* ============================ 系统 PiP 落位（上侧） ============================ */

    /**
     * PiP 落位避让条（Android 13+ 生效）：系统 PiP 的落位由 WM Shell 决定（默认屏幕
     * 底部），App 没有直接指定位置的 API；官方提供的合法杠杆是
     * {@link View#setPreferKeepClear}——标记「不可遮挡区域」后，落位算法
     * （PipKeepClearAlgorithm.findUnoccludedPosition）会把 PiP 推离该区域。
     * 在窗口底部常驻一条透明、不可交互的避让区（透明 + 无点击，触摸穿透到页面），
     * PiP 每次进入都被推到屏幕上侧。Android &lt; 13 无此 API，静默跳过（走系统默认落位）。
     */
    private void setupPipKeepClearStrip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        try {
            ViewGroup content = findViewById(android.R.id.content);
            if (content == null || pipKeepClearStrip != null) return;
            View strip = new View(this);
            strip.setBackgroundColor(Color.TRANSPARENT);
            strip.setClickable(false);
            strip.setFocusable(false);
            strip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            strip.setPreferKeepClear(true);
            content.addView(strip, keepClearStripLp());
            pipKeepClearStrip = strip;
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /** 避让条布局：贴底、宽满屏、高约屏高 45%（旋转时随 onConfigurationChanged 更新） */
    private FrameLayout.LayoutParams keepClearStripLp() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(getResources().getDisplayMetrics().heightPixels * 0.45f));
        lp.gravity = Gravity.BOTTOM;
        return lp;
    }

    /** 跨任务返回首页的自定义转场：与应用内返回一致的共享轴反向动画（无动画设置时纯淡入淡出） */
    private Bundle buildBackToHomeOptions() {
        try {
            boolean reduced = MotionCompat.reducedMotion(this);
            ActivityOptions options = ActivityOptions.makeCustomAnimation(this,
                    reduced ? R.anim.anim_fade_in : R.anim.anim_shared_axis_in_reverse,
                    reduced ? R.anim.anim_fade_out : R.anim.anim_shared_axis_out_reverse);
            return options.toBundle();
        } catch (Throwable th) {
            return null;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // singleTask：再次打开播放页（搜索/收藏/首页跳转、图标路由）复用本实例。
        // 带 extras 的新意图 = 换片，按旧标准启动路径重新加载；不带 extras 的路由
        // 意图（HomeActivity 图标回跳）不触碰当前播放状态
        if (intent == null || intent.getExtras() == null) return;
        setIntent(intent);
        initData();
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_detail;
    }

    /* ============================ 简介折叠 ============================ */

    /** 绑定「详情」折叠开关：内容不足 3 行时整行隐藏，展开后箭头旋转 180° */
    private void setupDesToggle() {
        updateDesCollapseUi();
        desToggleRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                desExpanded = !desExpanded;
                tvDes.setMaxLines(desExpanded ? Integer.MAX_VALUE : 3);
                updateDesCollapseUi();
            }
        });
        // 内容长度随源变化：不足 3 行（没有省略号）时收起「详情」开关，免得多余的一行
        tvDes.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                try {
                    if (desToggleRow == null || tvDes == null) return;
                    if (desExpanded) {
                        desToggleRow.setVisibility(View.VISIBLE);
                    } else if (isDesFullyVisibleAtCollapse()) {
                        desToggleRow.setVisibility(View.GONE);
                    } else {
                        desToggleRow.setVisibility(View.VISIBLE);
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** 收起态下简介是否本来就没超过 3 行（无省略 = 不需要折叠开关） */
    private boolean isDesFullyVisibleAtCollapse() {
        android.text.Layout layout = tvDes.getLayout();
        if (layout == null) return true;
        int last = layout.getLineCount() - 1;
        if (last < 0) return true;
        return layout.getEllipsisCount(last) <= 0;
    }

    /** 折叠 UI 状态：文案 + 箭头旋转（尊重系统「移除动画」设置） */
    private void updateDesCollapseUi() {
        if (tvDesToggle == null || desArrowIcon == null) return;
        tvDesToggle.setText(desExpanded ? "收起" : "详情");
        float rotation = desExpanded ? 180f : 0f;
        if (MotionCompat.reducedMotion(this)) {
            desArrowIcon.setRotation(rotation);
        } else {
            desArrowIcon.animate().rotation(rotation).setDuration(180)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
    }

    /** 收藏星标状态：★ 已收藏（品牌色）/ ☆ 未收藏（弱化灰） */
    private void updateCollectStar(boolean fav) {
        if (tvCollect == null) return;
        tvCollect.setText(fav ? "★" : "☆");
        tvCollect.setTextColor(getResources().getColor(
                fav ? R.color.ui_accent : R.color.ui_muted));
        tvCollect.setContentDescription(fav ? "取消收藏" : "加入收藏");
    }

    /** 点亮小脉冲：星标放大 1.25 再弹回 */
    private void popCollectStar() {
        if (tvCollect == null || MotionCompat.reducedMotion(this)) return;
        tvCollect.animate().cancel();
        tvCollect.setScaleX(1f);
        tvCollect.setScaleY(1f);
        tvCollect.animate().scaleX(1.25f).scaleY(1.25f).setDuration(90)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        tvCollect.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                    }
                }).start();
    }

    /** 打开「缓存选集」弹窗：把当前线路的分集加入离线缓存队列 */
    private void showDownloadDialog() {
        if (vodInfo == null || vodInfo.seriesMap == null
                || vodInfo.seriesMap.get(vodInfo.playFlag) == null
                || vodInfo.seriesMap.get(vodInfo.playFlag).isEmpty()) {
            GlassTip.show(DetailActivity.this, "暂无可缓存的选集");
            return;
        }
        new DownloadSelectDialog(DetailActivity.this, sourceKey, vodInfo).show();
    }

    /** 绑定「其他信息」折叠开关：默认收起，只露出来源/年份，展开后显示全部 */
    private void setupInfoToggle() {
        infoExpanded = false;
        applyInfoCollapse();
        infoToggleRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                infoExpanded = !infoExpanded;
                applyInfoCollapse();
                updateInfoCollapseUi();
            }
        });
    }

    /** 应用折叠态：infoMoreBlock（地区/语言/演员/导演/播放链接）整体显隐 */
    private void applyInfoCollapse() {
        if (infoMoreBlock == null) return;
        infoMoreBlock.setVisibility(infoExpanded ? View.VISIBLE : View.GONE);
    }

    /** 「其他信息」开关行 UI：文案 + 箭头旋转（尊重系统「移除动画」设置） */
    private void updateInfoCollapseUi() {
        if (tvInfoToggle == null || infoArrowIcon == null) return;
        tvInfoToggle.setText(infoExpanded ? "收起" : "详情");
        float rotation = infoExpanded ? 180f : 0f;
        if (MotionCompat.reducedMotion(this)) {
            infoArrowIcon.setRotation(rotation);
        } else {
            infoArrowIcon.animate().rotation(rotation).setDuration(180)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
    }

    @Override
    protected void init() {
        EventBus.getDefault().register(this);
        initView();
        initViewModel();
        initData();
    }

    private void initView() {
        llLayout = findViewById(R.id.llLayout);
        scrollView = findViewById(R.id.llLayout);
        llPlayerPlace = findViewById(R.id.previewPlayerPlace);
        llPlayerFragmentContainer = findViewById(R.id.previewPlayer);
        llPlayerFragmentContainerBlock = findViewById(R.id.previewPlayerBlock);
        applyPlayerSize();
        setupPlayerScrollSync();
        applyPreviewRoundCorners();
        thumbContainer = findViewById(R.id.thumbContainer);
        ivThumb = findViewById(R.id.ivThumb);
        applyThumbPreviewStyle();
        tvName = findViewById(R.id.tvName);
        tvYear = findViewById(R.id.tvYear);
        tvSite = findViewById(R.id.tvSite);
        tvArea = findViewById(R.id.tvArea);
        tvLang = findViewById(R.id.tvLang);
        tvActor = findViewById(R.id.tvActor);
        tvDirector = findViewById(R.id.tvDirector);
        tvPlayUrl = findViewById(R.id.tvPlayUrl);
        tvDes = findViewById(R.id.tvDes);
        desToggleRow = findViewById(R.id.desToggleRow);
        tvDesToggle = findViewById(R.id.tvDesToggle);
        desArrowIcon = findViewById(R.id.desArrowIcon);
        setupDesToggle();
        detailInfoBlock = findViewById(R.id.detailInfoBlock);
        infoMoreBlock = findViewById(R.id.infoMoreBlock);
        infoToggleRow = findViewById(R.id.infoToggleRow);
        tvInfoToggle = findViewById(R.id.tvInfoToggle);
        infoArrowIcon = findViewById(R.id.infoArrowIcon);
        setupInfoToggle();
//        tvSort = findViewById(R.id.tvSort);
        tvSeriesSort = findViewById(R.id.mSeriesSortTv);
        tvSeriesExpand = findViewById(R.id.tvSeriesExpand);
        tvSeriesExpand.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                showSeriesPickDialog();
            }
        });
        tvCollect = findViewById(R.id.tvCollect);
        tvChangeSource = findViewById(R.id.tvChangeSource);
        tvCache = findViewById(R.id.tvCache);
        mEmptyPlayList = findViewById(R.id.mEmptyPlaylist);
        mGridView = findViewById(R.id.mGridView);
        mGridView.setHasFixedSize(false);
        // 手机竖屏：集数改为「单行横向滚动」，spanCount 恒为 1
        this.mGridViewLayoutMgr = new GridLayoutManager(this.mContext, 1,
                androidx.recyclerview.widget.RecyclerView.HORIZONTAL, false);
        mGridView.setLayoutManager(this.mGridViewLayoutMgr);

        seriesAdapter = new SeriesAdapter(this.mGridViewLayoutMgr);
        mGridView.setAdapter(seriesAdapter);
        mGridViewFlag = findViewById(R.id.mGridViewFlag);
        mGridViewFlag.setHasFixedSize(true);
        mGridViewFlag.setLayoutManager(new LinearLayoutManager(this.mContext, LinearLayoutManager.HORIZONTAL, false));
        seriesFlagAdapter = new SeriesFlagAdapter();
        mGridViewFlag.setAdapter(seriesFlagAdapter);
        mGridViewQuality = findViewById(R.id.mGridViewQuality);
        mGridViewQuality.setHasFixedSize(true);
        mGridViewQuality.setLayoutManager(new LinearLayoutManager(this.mContext, LinearLayoutManager.HORIZONTAL, false));
        qualityAdapter = new BaseQuickAdapter<String, BaseViewHolder>(R.layout.item_series_flag, qualityOptions) {
            @Override
            protected void convert(BaseViewHolder helper, String item) {
                helper.setText(R.id.tvSeriesFlag, item);
                helper.getView(R.id.tvSeriesFlagSelect).setVisibility(helper.getLayoutPosition() == qualityPosition ? View.VISIBLE : View.GONE);
                helper.itemView.setNextFocusUpId(tvSeriesGroup.getVisibility() == View.VISIBLE ? R.id.mSeriesSortTv : R.id.mGridViewFlag);
                helper.itemView.setNextFocusDownId(R.id.mGridView);
            }
        };
        mGridViewQuality.setAdapter(qualityAdapter);
        qualityAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                if (playFragment == null) return;
                if (position == qualityPosition) {
                    if (showPreview && !fullWindows && playFragment.getPlayer().isPlaying()) enterFullPreview();
                    return;
                }
                if (playFragment.selectQuality(position)) {
                    qualityPosition = position;
                    qualityAdapter.notifyDataSetChanged();
                }
            }
        });
        isReverse = false;
        firstReverse = false;
        preFlag = "";
        if (showPreview) {
            // 播放器初始化（inflate 控制条、弹幕层、解码器）开销较大，
            // 延后到详情页首帧渲染之后再创建，避免点卡片进入详情时明显卡顿
            llPlayerFragmentContainer.post(new Runnable() {
                @Override
                public void run() {
                    if (!isFinishing()) ensurePlayFragment();
                }
            });
        }
        llPlayerFragmentContainerBlock.setFocusable(showPreview);

        mSeriesGroupView = findViewById(R.id.mSeriesGroupView);
        tvSeriesGroup = findViewById(R.id.mSeriesGroupTv);
        mSeriesGroupView.setHasFixedSize(true);
        mSeriesGroupView.setLayoutManager(new LinearLayoutManager(this.mContext, LinearLayoutManager.HORIZONTAL, false));
        seriesGroupAdapter = new BaseQuickAdapter<String, BaseViewHolder>(R.layout.item_series_flag, seriesGroupOptions) {
            @Override
            protected void convert(BaseViewHolder helper, String item) {
                TextView tvSeries = helper.getView(R.id.tvSeriesFlag);
                tvSeries.setText(item);
                helper.getView(R.id.tvSeriesFlagSelect).setVisibility(helper.getLayoutPosition() == selectedSeriesGroupPosition ? View.VISIBLE : View.GONE);
                helper.itemView.setNextFocusUpId(R.id.mGridViewFlag);
                if (helper.getLayoutPosition() == getData().size() - 1) {
                    helper.itemView.setId(View.generateViewId());
                    helper.itemView.setNextFocusRightId(helper.itemView.getId());
                }else {
                    helper.itemView.setNextFocusRightId(View.NO_ID);
                }
            }
        };
        mSeriesGroupView.setAdapter(seriesGroupAdapter);

        llPlayerFragmentContainerBlock.setOnClickListener(v -> {
            if (firstReverse) {
                // 首次点击：拉起播放，同时把播放组件带出来
                jumpToPlay();
                firstReverse = false;
                llPlayerFragmentContainer.post(() -> {
                    if (playFragment != null) playFragment.showControls();
                });
                return;
            }
            // 非全屏状态下点击播放区域：只显示播放组件，不再自动进入全屏
            if (playFragment != null) {
                playFragment.toggleControls();
            }
        });

        tvChangeSource.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                showSourceSpeedDialog();
            }
        });
        tvCache.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                showDownloadDialog();
            }
        });
        tvCollect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                if (vodInfo == null) return;
                boolean fav = RoomDataManger.isVodCollect(sourceKey, vodId);
                if (fav) {
                    RoomDataManger.deleteVodCollect(sourceKey, vodInfo);
                    GlassTip.show(DetailActivity.this, "已移除收藏夹");
                    updateCollectStar(false);
                } else {
                    RoomDataManger.insertVodCollect(sourceKey, vodInfo);
                    GlassTip.show(DetailActivity.this, "已加入收藏夹");
                    updateCollectStar(true);
                }
                // 星标的「点亮」小脉冲，给收藏操作一个明确反馈
                popCollectStar();
            }
        });
        tvPlayUrl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //获取剪切板管理器
                ClipboardManager cm = (ClipboardManager)getSystemService(mContext.CLIPBOARD_SERVICE);
                //设置内容到剪切板
                cm.setPrimaryClip(ClipData.newPlainText(null, tvPlayUrl.getText().toString().replace("播放地址：","")));
                GlassTip.show(DetailActivity.this, "已复制");
            }
        });


        tvSeriesSort.setOnClickListener(new View.OnClickListener() {
            @SuppressLint("NotifyDataSetChanged")
            @Override
            public void onClick(View v) {
                if (vodInfo != null && vodInfo.seriesMap.size() > 0) {
                    vodInfo.reverseSort = !vodInfo.reverseSort;
                    isReverse = !isReverse;
                    tvSeriesSort.setText(isReverse?"倒序":"正序");
                    vodInfo.reverse();
                    if (vodInfo.playIndex >= 0) {
                        vodInfo.playIndex=(vodInfo.seriesMap.get(vodInfo.playFlag).size()-1)-vodInfo.playIndex;
                    }
                    firstReverse = !firstReverse;
                    setSeriesGroupOptions();
                    seriesAdapter.notifyDataSetChanged();

                    if (vodInfo.playIndex >= 0) customSeriesScrollPos(vodInfo.playIndex);
                    if(currentSeriesGroupView != null) {
                        TextView txtView = currentSeriesGroupView.findViewById(R.id.tvSeriesFlag);
                        txtView.setTextColor(androidx.core.content.ContextCompat.getColor(mContext, R.color.ui_text_primary));
                    }
                }
            }
        });
        seriesFlagAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                View itemView = view;
                String newFlag = seriesFlagAdapter.getData().get(position).name;
                if (vodInfo != null && !vodInfo.playFlag.equals(newFlag)) {
                    String oldFlag = vodInfo.playFlag;
                    int oldIndex = vodInfo.playIndex;
                    VodInfo.VodSeries currentSeries = getPlayingSeries(previewVodInfo, previewVodInfo == null ? null : previewVodInfo.playFlag);
                    List<VodInfo.VodSeries> oldSeriesList = vodInfo.seriesMap.get(oldFlag);
                    if (currentSeries == null && previewVodInfo == null && oldIndex >= 0 && oldSeriesList != null && !oldSeriesList.isEmpty()) {
                        int safeOldIndex = Math.max(0, Math.min(oldIndex, oldSeriesList.size() - 1));
                        currentSeries = oldSeriesList.get(safeOldIndex);
                    }
                    if (currentSeries == null) {
                        currentSeries = routeSwitchSeries;
                    }
                    for (int i = 0; i < vodInfo.seriesFlags.size(); i++) {
                        VodInfo.VodSeriesFlag flag = vodInfo.seriesFlags.get(i);
                        if (flag.name.equals(oldFlag)) {
                            flag.selected = false;
                            View oldItemView = mGridViewFlag.getLayoutManager().findViewByPosition(i);
                            if (oldItemView != null) oldItemView.findViewById(R.id.tvSeriesFlagSelect).setVisibility(View.GONE);
                            break;
                        }
                    }
                    VodInfo.VodSeriesFlag flag = vodInfo.seriesFlags.get(position);
                    flag.selected = true;
                    itemView.findViewById(R.id.tvSeriesFlagSelect).setVisibility(View.VISIBLE);
                    // clean pre flag select status
                    if (oldSeriesList != null && oldIndex >= 0 && oldSeriesList.size() > oldIndex) {
                        oldSeriesList.get(oldIndex).selected = false;
                    }
                    vodInfo.playFlag = newFlag;
                    List<VodInfo.VodSeries> newSeriesList = vodInfo.seriesMap.get(newFlag);
                    if (newSeriesList != null && !newSeriesList.isEmpty()) {
                        vodInfo.playIndex = findMatchingEpisodeIndex(currentSeries, newSeriesList);
                        for (VodInfo.VodSeries series : newSeriesList) {
                            series.selected = false;
                        }
                        if (vodInfo.playIndex >= 0) {
                            newSeriesList.get(vodInfo.playIndex).selected = true;
                            routeSwitchSeries = newSeriesList.get(vodInfo.playIndex);
                        } else if (currentSeries != null) {
                            routeSwitchSeries = currentSeries;
                        }
                    }
                    refreshList(true);
                }
                seriesFlagFocus = itemView;
            }
        });
        seriesAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                selectSeries(position);
            }
        });

        seriesGroupAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                selectSeriesGroup(view, position);
                if (vodInfo != null && vodInfo.seriesMap.get(vodInfo.playFlag).size() > 0) {
                    // 该分页第一集在列表中的下标（0 开始）
                    int targetPos = position * GroupCount;

                    customSeriesScrollPos(targetPos);
                }
                if(currentSeriesGroupView != null) {
                    TextView txtView = currentSeriesGroupView.findViewById(R.id.tvSeriesFlag);
                    txtView.setTextColor(androidx.core.content.ContextCompat.getColor(mContext, R.color.ui_text_primary));
                }
                currentSeriesGroupView = view;
                currentSeriesGroupView.isSelected();
            }
        });

        setLoadSir(llLayout);
        // 进入详情页停在页面最顶部，焦点落在最上方的预览区域
        if (scrollView != null) {
            scrollView.post(new Runnable() {
                @Override
                public void run() {
                    if (scrollView != null) scrollView.scrollTo(0, 0);
                    if (showPreview) llPlayerFragmentContainerBlock.requestFocus();
                }
            });
        }
        if (fullWindows) {
            setFullPreview(true);
        }
    }

    //解决类似海贼王的超长动漫 焦点滚动失败的问题
    private void selectSeriesGroup(View selectedView, int position) {
        if (selectedSeriesGroupPosition == position) return;
        View previousView = mSeriesGroupView.getLayoutManager().findViewByPosition(selectedSeriesGroupPosition);
        if (previousView != null) previousView.findViewById(R.id.tvSeriesFlagSelect).setVisibility(View.GONE);
        selectedSeriesGroupPosition = position;
        selectedView.findViewById(R.id.tvSeriesFlagSelect).setVisibility(View.VISIBLE);
    }

    /**
     * 集数改为单行横滑后：横向滚到目标集，并把选集区域滚进视野。
     */
    void customSeriesScrollPos(int targetPos) {
        if (targetPos < 0) return;
        mGridView.postDelayed(new Runnable() {
            @Override
            public void run() {
                RecyclerView.LayoutManager manager = mGridView.getLayoutManager();
                if (manager instanceof LinearLayoutManager) {
                    int itemW = resolveSeriesItemWidth();
                    ((LinearLayoutManager) manager).scrollToPositionWithOffset(targetPos, Math.max(0, itemW));
                }
                scrollSeriesIntoView();
            }
        }, 60);
    }

    /** 让选集区域滚动到屏幕可见位置 */
    private void scrollSeriesIntoView() {
        if (scrollView == null || mGridView == null) return;
        int[] gridLoc = new int[2];
        int[] svLoc = new int[2];
        mGridView.getLocationInWindow(gridLoc);
        scrollView.getLocationInWindow(svLoc);
        int gridTop = gridLoc[1] - svLoc[1] + scrollView.getScrollY();
        int offset = Math.round(72 * getResources().getDisplayMetrics().density);
        scrollView.smoothScrollTo(0, Math.max(0, gridTop - offset));
    }

    /** 单个集数按钮的宽度，用于横滑定位时左侧留白 */
    private int resolveSeriesItemWidth() {
        if (mGridView != null && mGridView.getChildCount() > 0) {
            View child = mGridView.getChildAt(0);
            if (child != null && child.getWidth() > 0) return child.getWidth();
        }
        return Math.round(64 * getResources().getDisplayMetrics().density);
    }

    private void initCheckedSourcesForSearch() {
        mCheckSources = SearchHelper.getSourcesForSearch();
    }

    private List<Runnable> pauseRunnable = null;

    private void jumpToPlay() {
        if (vodInfo != null && vodInfo.seriesMap.get(vodInfo.playFlag).size() > 0) {
            preFlag = vodInfo.playFlag;
            //更新播放地址
            setTextShow(tvPlayUrl, "播放地址：", vodInfo.seriesMap.get(vodInfo.playFlag).get(vodInfo.playIndex).url);
            Bundle bundle = new Bundle();
            //保存历史
            insertVod(firstsourceKey, vodInfo);
        //   insertVod(sourceKey, vodInfo);
            bundle.putString("sourceKey", sourceKey);
//            bundle.putSerializable("VodInfo", vodInfo);
            App.getInstance().setVodInfo(vodInfo);
            if (showPreview) {
                ensurePlayFragment();
                updatePreviewVodInfo();
                App.getInstance().setVodInfo(previewVodInfo);
                if (playFragment != null) playFragment.setData(bundle);
            } else {
                ensurePlayFragment();
                if (playFragment != null) playFragment.setData(bundle);
                enterFullPreview();
            }
        }
    }

    private void updatePreviewVodInfo() {
        if (previewVodInfo == null) {
            previewVodInfo = new VodInfo();
        }
        previewVodInfo.id = vodInfo.id;
        previewVodInfo.name = vodInfo.name;
        previewVodInfo.pic = vodInfo.pic;
        previewVodInfo.sourceKey = vodInfo.sourceKey;
        previewVodInfo.playNote = vodInfo.playNote;
        previewVodInfo.seriesFlags = vodInfo.seriesFlags;
        previewVodInfo.seriesMap = vodInfo.seriesMap;
        previewVodInfo.playerCfg = vodInfo.playerCfg;
        previewVodInfo.playFlag = vodInfo.playFlag;
        previewVodInfo.playIndex = vodInfo.playIndex;
    }

    @SuppressLint("NotifyDataSetChanged")
    void refreshList() {
        refreshList(false);
    }

    /**
     * @param scrollToSeries 是否把页面滚动到当前集所在位置。
     *                       首次进入详情页传 false，保持页面停在顶部。
     */
    @SuppressLint("NotifyDataSetChanged")
    void refreshList(boolean scrollToSeries) {
        if (vodInfo.seriesMap.get(vodInfo.playFlag).size() <= vodInfo.playIndex) {
            vodInfo.playIndex = 0;
        }

        if (vodInfo.seriesMap.get(vodInfo.playFlag) != null) {
            boolean canSelect = true;
            for (int j = 0; j < vodInfo.seriesMap.get(vodInfo.playFlag).size(); j++) {
                if(vodInfo.seriesMap.get(vodInfo.playFlag).get(j).selected){
                    canSelect = false;
                    break;
                }
            }
            if(canSelect && vodInfo.playIndex >= 0 && vodInfo.playIndex < vodInfo.seriesMap.get(vodInfo.playFlag).size()) {
                vodInfo.seriesMap.get(vodInfo.playFlag).get(vodInfo.playIndex).selected = true;
            }
        }

        Paint pFont = new Paint();
//        pFont.setTypeface(Typeface.DEFAULT );
        Rect rect = new Rect();

        List<VodInfo.VodSeries> list = vodInfo.seriesMap.get(vodInfo.playFlag);
        int listSize = list.size();
        int w = 1;
        for(int i =0; i < listSize; ++i){
            String name = list.get(i).name;
            pFont.getTextBounds(name, 0, name.length(), rect);
            if(w < rect.width()){
                w = rect.width();
            }
        }
        w += 32;
        // 单行横向滚动：恒为 1 列，宽度由每个集数按钮自身内容决定
        mGridViewLayoutMgr.setSpanCount(1);
        seriesAdapter.setNewData(vodInfo.seriesMap.get(vodInfo.playFlag));

        setSeriesGroupOptions();

        if (scrollToSeries) {
            mGridView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (vodInfo.playIndex >= 0) customSeriesScrollPos(vodInfo.playIndex);
                }
            }, 100);
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private void setSeriesGroupOptions(){
        List<VodInfo.VodSeries> list = vodInfo.seriesMap.get(vodInfo.playFlag);
        int listSize = list.size();
        // 选集标题右侧的「正序 / 倒序」切换按钮：只有一集时无意义
        if (tvSeriesSort != null) {
            tvSeriesSort.setVisibility(listSize > 1 ? View.VISIBLE : View.GONE);
            tvSeriesSort.setText(vodInfo.reverseSort ? "倒序" : "正序");
        }
        // 「列表」展开按钮：只有一集时也不需要
        if (tvSeriesExpand != null) {
            tvSeriesExpand.setVisibility(listSize > 1 ? View.VISIBLE : View.GONE);
        }
        int offset = mGridViewLayoutMgr.getSpanCount();
        seriesGroupOptions.clear();
        GroupCount=(offset==3 || offset==6)?30:20;
        if(listSize>100 && listSize<=400)GroupCount=60;
        if(listSize>400)GroupCount=120;
        if(listSize > 1) {
            // 手机竖屏用单行横滑，不再需要分页
            tvSeriesGroup.setVisibility(isBaseOnWidth() ? View.GONE : View.VISIBLE);
            int remainedOptionSize = listSize % GroupCount;
            int optionSize = listSize / GroupCount;

            for(int i = 0; i < optionSize; i++) {
                if(vodInfo.reverseSort)
//                    seriesGroupOptions.add(String.format("%d - %d", i * GroupCount + GroupCount, i * GroupCount + 1));
                    seriesGroupOptions.add(String.format("%d - %d", listSize - (i * GroupCount + 1)+1, listSize - (i * GroupCount + GroupCount)+1));
                else
                    seriesGroupOptions.add(String.format("%d - %d", i * GroupCount + 1, i * GroupCount + GroupCount));
            }
            if(remainedOptionSize > 0) {
                if(vodInfo.reverseSort)
//                    seriesGroupOptions.add(String.format("%d - %d", optionSize * GroupCount + remainedOptionSize, optionSize * GroupCount + 1));
                    seriesGroupOptions.add(String.format("%d - %d", listSize - (optionSize * GroupCount + 1)+1, listSize - (optionSize * GroupCount + remainedOptionSize)+1));
                else
                    seriesGroupOptions.add(String.format("%d - %d", optionSize * GroupCount + 1, optionSize * GroupCount + remainedOptionSize));
            }
//            if(vodInfo.reverseSort) Collections.reverse(seriesGroupOptions);

            selectedSeriesGroupPosition = Math.max(0, Math.min(vodInfo.playIndex / GroupCount, seriesGroupOptions.size() - 1));
            seriesGroupAdapter.notifyDataSetChanged();
        }else {
            tvSeriesGroup.setVisibility(View.GONE);
        }
        if (!mGridViewFlag.hasFocus()) seriesFlagAdapter.notifyDataSetChanged();
        mGridViewQuality.setNextFocusUpId(tvSeriesGroup.getVisibility() == View.VISIBLE ? R.id.mSeriesSortTv : R.id.mGridViewFlag);
    }

    private void updateQualityOptions(JSONObject result) {
        ArrayList<String> options = new ArrayList<>();
        try {
            Object value = result == null ? null : result.opt("url");
            JSONArray urls = value instanceof JSONArray ? (JSONArray) value : value instanceof String ? new JSONArray((String) value) : null;
            if (urls != null) for (int i = 0; i + 1 < urls.length(); i += 2) options.add(urls.optString(i));
        } catch (Throwable th) {
        }
        if (qualityOptions.equals(options)) {
            if (qualityPosition == 0) return;
            qualityPosition = 0;
            qualityAdapter.notifyDataSetChanged();
            return;
        }
        qualityOptions.clear();
        qualityOptions.addAll(options);
        qualityPosition = 0;
        boolean visible = showPreview && options.size() > 1;
        mGridViewQuality.setVisibility(visible ? View.VISIBLE : View.GONE);
        seriesFlagAdapter.notifyDataSetChanged();
        seriesAdapter.notifyDataSetChanged();
        qualityAdapter.setNewData(new ArrayList<>(qualityOptions));
        int up = tvSeriesGroup.getVisibility() == View.VISIBLE ? R.id.mSeriesSortTv : R.id.mGridViewFlag;
        int down = visible ? R.id.mGridViewQuality : R.id.mGridView;
        mGridViewQuality.setNextFocusUpId(up);
        mGridViewQuality.setNextFocusDownId(R.id.mGridView);
        tvSeriesSort.setNextFocusDownId(down);
        mSeriesGroupView.setNextFocusDownId(down);
    }

    private void setTextShow(TextView view, String tag, String info) {
        if (info == null || info.trim().isEmpty()) {
            view.setVisibility(View.GONE);
            return;
        }
        view.setVisibility(View.VISIBLE);
        view.setText(Html.fromHtml(getHtml(tag, info)));
    }

    private String removeHtmlTag(String info) {
        if (TextUtils.isEmpty(info))
            return "";
        String text = info.replaceAll("\\[a=cr:(?:\\{.*?\\}|\\[.*?\\])\\/](.*?)\\[\\/a]", "$1");
        text = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                ? Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString()
                : Html.fromHtml(text).toString();
        return text.replaceAll("\\s", "");
    }

    /**
     * 手机竖屏：播放器独占顶部一排，按 16:9 计算高度（全屏时另算）
     */
    private void applyPlayerSize() {
        int w = getResources().getDisplayMetrics().widthPixels;
        playerHeightPx = Math.round(w * 9f / 16f);
        setViewHeight(llPlayerPlace, playerHeightPx);
        setViewHeight(llPlayerFragmentContainer, playerHeightPx);
        setViewHeight(llPlayerFragmentContainerBlock, playerHeightPx);
    }

    private void setViewHeight(View view, int height) {
        if (view == null || height <= 0) return;
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp == null) return;
        lp.height = height;
        view.setLayoutParams(lp);
    }

    /**
     * 播放器是 FrameLayout 的直接子 View（保证全屏可以铺满），
     * 这里让它跟随内容滚动一起上移，视觉上等同于「播放器在页面顶部」。
     */
    private void setupPlayerScrollSync() {
        if (scrollView == null) return;
        scrollView.setOnScrollChangeListener(new androidx.core.widget.NestedScrollView.OnScrollChangeListener() {
            @Override
            public void onScrollChange(androidx.core.widget.NestedScrollView v, int scrollX, int scrollY, int oldScrollX, int oldScrollY) {
                syncPlayerTranslation(scrollY);
            }
        });
    }

    private void syncPlayerTranslation(int scrollY) {
        float translation = 0f;
        if (!fullWindows && showPreview) {
            translation = -Math.min(Math.max(scrollY, 0), playerHeightPx);
        }
        if (llPlayerFragmentContainer != null) llPlayerFragmentContainer.setTranslationY(translation);
        if (llPlayerFragmentContainerBlock != null) llPlayerFragmentContainerBlock.setTranslationY(translation);
    }

    private void applyPreviewRoundCorners() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        final float radius = getResources().getDimension(R.dimen.preview_player_radius);
        ViewOutlineProvider provider = new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        };
        llPlayerFragmentContainer.setClipToOutline(true);
        llPlayerFragmentContainer.setOutlineProvider(provider);
        llPlayerFragmentContainerBlock.setClipToOutline(true);
        llPlayerFragmentContainerBlock.setOutlineProvider(provider);
    }

    private void applyThumbPreviewStyle() {
        thumbContainer.setVisibility(showPreview ? View.GONE : View.VISIBLE);
        llPlayerPlace.setVisibility(showPreview ? View.VISIBLE : View.GONE);
        ivThumb.setVisibility(!showPreview ? View.VISIBLE : View.GONE);
        thumbContainer.setBackgroundResource(showPreview ? R.drawable.shape_detail_thumb_bg : R.drawable.shape_detail_thumb_idle_bg);
    }

    private void setPreviewRoundClip(boolean enable) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        llPlayerFragmentContainer.setClipToOutline(enable);
        llPlayerFragmentContainerBlock.setClipToOutline(enable);
        llPlayerFragmentContainer.setBackgroundResource(enable ? R.drawable.preview_player_round : android.R.color.black);
    }


    private void initViewModel() {
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        // 手动换源：换源面板点选源后的单源搜索结果
        sourceViewModel.switchSourceSearchResult.observe(this, new Observer<AbsXml>() {
            @Override
            public void onChanged(AbsXml absXml) {
                if (!switchSourceSearching || absXml == null
                        || !TextUtils.equals(absXml.searchToken, switchSourceToken)
                        || !TextUtils.equals(absXml.sourceKey, switchSourceTargetKey)) {
                    return;
                }
                llLayout.removeCallbacks(switchSourceSearchTimeout);
                switchSourceSearching = false;
                Movie.Video matched = null;
                if (absXml.movie != null && absXml.movie.videoList != null) {
                    for (Movie.Video video : absXml.movie.videoList) {
                        if (video == null || TextUtils.isEmpty(video.id)) {
                            continue;
                        }
                        // 只认片名完全一致的条目，与自动换源口径相同
                        if (TextUtils.equals(switchSourceTitle, video.name == null ? "" : video.name.trim())) {
                            matched = video;
                            break;
                        }
                    }
                }
                if (matched == null) {
                    if (sourceSpeedDialog != null && sourceSpeedDialog.isShowing()) {
                        sourceSpeedDialog.setRowSearching(switchSourceTargetKey, false);
                    }
                    GlassTip.show(DetailActivity.this, "「" + switchSourceTargetName + "」未找到同名片源");
                    return;
                }
                switchToMatchedVideo(matched, switchSourceTargetName);
            }
        });
        // 换源面板预搜索：逐源回包，标记「有片源/无片源」，命中的带上候选影片供直接切换
        sourceViewModel.panelSourceSearchResult.observe(this, new Observer<AbsXml>() {
            @Override
            public void onChanged(AbsXml absXml) {
                if (!panelSearchActive || absXml == null
                        || sourceSpeedDialog == null || !sourceSpeedDialog.isShowing()) {
                    return;
                }
                String token = absXml.searchToken;
                String prefix = panelSearchBaseToken + "|";
                if (TextUtils.isEmpty(token) || !token.startsWith(prefix)) {
                    return;
                }
                if (!TextUtils.equals(absXml.sourceKey, token.substring(prefix.length()))) {
                    return;
                }
                Movie.Video matched = findExactTitleVideo(absXml, panelSearchTitle);
                sourceSpeedDialog.markSearchResult(absXml.sourceKey, matched != null, matched);
            }
        });
        sourceViewModel.detailResult.observe(this, new Observer<AbsXml>() {
            @Override
            public void onChanged(AbsXml absXml) {
                if (detailFallbackActive && !detailFallbackLoadingCandidate) {
                    return;
                }
                if (absXml != null && !TextUtils.isEmpty(absXml.sourceKey)
                        && !TextUtils.equals(absXml.sourceKey, sourceKey)
                        && !("push_fallback".equals(absXml.sourceKey) && "push_agent".equals(sourceKey))) {
                    return;
                }
                if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                    boolean fallbackResult = detailFallbackLoadingCandidate;
                    if (detailFallbackLoadingCandidate) {
                        detailFallbackLoadingCandidate = false;
                        llLayout.removeCallbacks(detailFallbackDetailTimeout);
                    }
                    if (fallbackResult) {
                        SourceBean fallbackSource = ApiConfig.get().getSource(sourceKey);
                        String fallbackName = fallbackSource == null ? sourceKey : fallbackSource.getName();
                        GlassTip.show(DetailActivity.this, "站点切换至" + fallbackName);
                    } else if (!TextUtils.isEmpty(manualSwitchSourceName)) {
                        GlassTip.show(DetailActivity.this, "已切换至" + manualSwitchSourceName);
                        manualSwitchSourceName = "";
                    }
                    showSuccess();
                    if(!TextUtils.isEmpty(absXml.msg) && !absXml.msg.equals("数据列表")){
                        resetDetailFallback();
                        GlassTip.show(DetailActivity.this, absXml.msg);
                        showEmpty();
                        return;
                    }
                    mVideo = absXml.movie.videoList.get(0);
                    mVideo.id = vodId;
                    if (TextUtils.isEmpty(mVideo.name))mVideo.name = vod_name;
                    if (TextUtils.isEmpty(mVideo.name))mVideo.name = "TVBox";
                    vodInfo = new VodInfo();
                    routeSwitchSeries = null;
                    if((mVideo.pic==null || mVideo.pic.isEmpty()) && !vod_picture.isEmpty()){
                        mVideo.pic=vod_picture;
                    }
                    vodInfo.setVideo(mVideo);
                    vodInfo.sourceKey = mVideo.sourceKey;
                    sourceKey = mVideo.sourceKey;

                    tvName.setText(mVideo.name);
                    SourceBean displaySource = ApiConfig.get().getSource(firstsourceKey);
                    if (displaySource == null) {
                        displaySource = ApiConfig.get().getSource(sourceKey);
                    }
                    setTextShow(tvSite, "来源：", displaySource == null ? "" : displaySource.getName());
                    setTextShow(tvYear, "年份：", mVideo.year == 0 ? "" : String.valueOf(mVideo.year));
                    setTextShow(tvArea, "地区：", mVideo.area);
                    setTextShow(tvLang, "语言：", mVideo.lang);
                    setTextShow(tvActor, "演员：", removeHtmlTag(mVideo.actor));
                    setTextShow(tvDirector, "导演：", removeHtmlTag(mVideo.director));
                    setTextShow(tvDes, "内容简介：", removeHtmlTag(mVideo.des));
                    // 换源 / 重新加载后把简介和其他信息都收回折叠态
                    desExpanded = false;
                    tvDes.setMaxLines(3);
                    updateDesCollapseUi();
                    infoExpanded = false;
                    applyInfoCollapse();
                    if (!TextUtils.isEmpty(mVideo.pic)) {
                        // thumbContainer 是 108dp×162dp（约 324×486px），圆角由 clipToOutline 负责。
                        // 原来传 300dp×400dp 的设计稿尺寸，经 AutoSize 放大后是 900×1200，
                        // 解码量和内存都多 7 倍，进详情页那一下的卡顿主要来自这里。
                        com.github.tvbox.osc.util.ImgUtil.load(DefaultConfig.checkReplaceProxy(mVideo.pic), ivThumb, 0, 0, 0, mVideo.name);
                    } else {
                        ivThumb.setImageDrawable(com.github.tvbox.osc.util.ImgUtil.createTextDrawable(mVideo.name));
                    }

                    if (vodInfo.seriesMap != null && vodInfo.seriesMap.size() > 0) {
                        mGridViewFlag.setVisibility(View.VISIBLE);
                        mGridView.setVisibility(View.VISIBLE);
                        mEmptyPlayList.setVisibility(View.GONE);

                        VodInfo vodInfoRecord = RoomDataManger.getVodInfo(sourceKey, vodId);
                        // 读取历史记录
                        if (vodInfoRecord != null) {
                            vodInfo.playIndex = Math.max(vodInfoRecord.playIndex, 0);
                            vodInfo.playFlag = vodInfoRecord.playFlag;
                            vodInfo.playerCfg = vodInfoRecord.playerCfg;
                            vodInfo.reverseSort = vodInfoRecord.reverseSort;
                        } else {
                            vodInfo.playIndex = 0;
                            vodInfo.playFlag = null;
                            vodInfo.playerCfg = "";
                            vodInfo.reverseSort = false;
                        }

                        if (vodInfo.reverseSort) {
                            vodInfo.reverse();
                        }

                        if (vodInfo.playFlag == null || !vodInfo.seriesMap.containsKey(vodInfo.playFlag))
                            vodInfo.playFlag = (String) vodInfo.seriesMap.keySet().toArray()[0];

                        restoreDetailFallbackEpisode();
                        resetDetailFallback();
                        List<VodInfo.VodSeries> playingSeriesList = vodInfo.seriesMap.get(vodInfo.playFlag);
                        vodInfo.playIndex = Math.max(0, Math.min(vodInfo.playIndex, playingSeriesList.size() - 1));

                        int flagScrollTo = 0;
                        for (int j = 0; j < vodInfo.seriesFlags.size(); j++) {
                            VodInfo.VodSeriesFlag flag = vodInfo.seriesFlags.get(j);
                            if (flag.name.equals(vodInfo.playFlag)) {
                                flagScrollTo = j;
                                flag.selected = true;
                            } else
                                flag.selected = false;
                        }
                        //设置播放地址
                        setTextShow(tvPlayUrl, "播放地址：", playingSeriesList.get(vodInfo.playIndex).url);
                        seriesFlagAdapter.setNewData(vodInfo.seriesFlags);
                        mGridViewFlag.scrollToPosition(flagScrollTo);

                        refreshList();
                        if (showPreview) {
                            jumpToPlay();
                            llPlayerFragmentContainer.setVisibility(View.VISIBLE);
                            llPlayerFragmentContainerBlock.setVisibility(View.VISIBLE);
                            toggleSubtitleTextSize();
                        }
                        // startQuickSearch();
                    } else {
                        mGridViewFlag.setVisibility(View.GONE);
                        mGridView.setVisibility(View.GONE);
                        tvSeriesGroup.setVisibility(View.GONE);
                        mEmptyPlayList.setVisibility(View.VISIBLE);
                        handleNoPlayableDetail();
                    }
                } else {
                    if (detailFallbackLoadingCandidate) {
                        detailFallbackLoadingCandidate = false;
                        detailFallbackDetailTimedOut = true;
                        llLayout.removeCallbacks(detailFallbackDetailTimeout);
                        loadNextDetailFallbackSource();
                        return;
                    }
                    handleEmptyDetail(absXml);
                }
            }
        });
        sourceViewModel.detailFallbackSearchResult.observe(this, new Observer<AbsXml>() {
            @Override
            public void onChanged(AbsXml absXml) {
                onDetailFallbackSearchResult(absXml);
            }
        });
    }

    private String getHtml(String label, String content) {
        if (content == null) {
            content = "";
        }
        // 内容（数据值）改用主题感知色：日间近黑、夜间白色，避免在浅色背景上出现白字。
        int contentColor = androidx.core.content.ContextCompat.getColor(this, R.color.ui_text_primary);
        return label + "<font color=\"#" + String.format(Locale.US, "%08x", contentColor) + "\">" + content + "</font>";
    }

    private String  vod_picture="";
    private String  vod_name="";
    private void initData() {
        Intent intent = getIntent();
        if (intent != null && intent.getExtras() != null) {
            Bundle bundle = intent.getExtras();
            vod_name=bundle.getString("title", "");
            vod_picture=bundle.getString("picture", "");
            fromCollect = bundle.getBoolean("collect", false);
            Object fallbackCandidates = bundle.getSerializable(EXTRA_DETAIL_FALLBACK_CANDIDATES);
            if (fallbackCandidates instanceof ArrayList) {
                cacheDetailFallbackCandidates(vod_name, (ArrayList<Movie.Video>) fallbackCandidates);
            }
            loadDetail(bundle.getString("id", null), bundle.getString("sourceKey", ""));
        }
    }

    private void loadDetail(String vid, String key) {
        loadDetail(vid, key, false);
    }

    private void loadDetail(String vid, String key, boolean fallback) {
        if (!fallback) {
            resetDetailFallback();
        }
        vodId = vid;
        sourceKey = key;
        firstsourceKey = key;
        if (TextUtils.isEmpty(vid) || vid.startsWith("msearch:") || ApiConfig.get().getSource(sourceKey) == null) {
            handleNoPlayableDetail();
            return;
        }
        if (!fallback) {
            showLoading();
        }
        if (fallback && detailFallbackActive) {
            llLayout.removeCallbacks(detailFallbackDetailTimeout);
            llLayout.postDelayed(detailFallbackDetailTimeout, DETAIL_FALLBACK_DETAIL_TIMEOUT_MS);
        }
        // fallback=true 时 getDetail 用 6s 短超时；手动换源（detailFallbackActive=false）同样受益
        sourceViewModel.getDetail(sourceKey, vodId, fallback);
        boolean isVodCollect = RoomDataManger.isVodCollect(sourceKey, vodId);
        updateCollectStar(isVodCollect);
    }

    private void handleEmptyDetail(AbsXml data) {
        boolean shouldFinish = data != null && !TextUtils.isEmpty(data.msg);
        if (shouldFinish || fromCollect) {
            resetDetailFallback();
            if (shouldFinish) {
                GlassTip.show(this, data.msg);
            }
            finish();
            return;
        }
        handleNoPlayableDetail();
    }

    private void handleNoPlayableDetail() {
        if (detailFallbackActive) {
            detailFallbackLoadingCandidate = false;
            loadNextDetailFallbackSource();
            return;
        }
        startDetailFallback();
    }

    public boolean startDetailFallbackAfterLinesExhausted() {
        return startDetailFallback(false);
    }

    /** 选中某一集（选集网格与「选集列表」抽屉共用）：更新高亮并跳转播放。 */
    private void selectSeries(int position) {
        if (vodInfo == null || vodInfo.seriesMap == null || TextUtils.isEmpty(vodInfo.playFlag)) {
            return;
        }
        List<VodInfo.VodSeries> currentList = vodInfo.seriesMap.get(vodInfo.playFlag);
        if (currentList == null || currentList.isEmpty()
                || position < 0 || position >= currentList.size()) {
            return;
        }
        boolean reload = false;
        for (int j = 0; j < currentList.size(); j++) {
            seriesAdapter.getData().get(j).selected = false;
            seriesAdapter.notifyItemChanged(j);
        }
        //解决倒叙不刷新
        if (vodInfo.playIndex != position) {
            seriesAdapter.getData().get(position).selected = true;
            seriesAdapter.notifyItemChanged(position);
            vodInfo.playIndex = position;
            routeSwitchSeries = seriesAdapter.getData().get(position);

            reload = true;
        }
        //解决当前集不刷新的BUG
        if (!preFlag.isEmpty() && !vodInfo.playFlag.equals(preFlag)) {
            reload = true;
        }
        boolean isCurrentPlaying = !showPreview || isCurrentPreviewPlaying(position);
        if (showPreview && !isCurrentPlaying) {
            reload = true;
        }

        seriesAdapter.getData().get(vodInfo.playIndex).selected = true;
        seriesAdapter.notifyItemChanged(vodInfo.playIndex);
        //选集全屏 想选集不全屏的注释下面一行
        if (showPreview && !fullWindows && previewVodInfo != null && TextUtils.equals(vodInfo.playFlag, previewVodInfo.playFlag) && (playFragment.getPlayer().isPlaying() || isCurrentPlaying)) enterFullPreview();
        if (!showPreview || reload) {
            jumpToPlay();
            firstReverse=false;
        }
    }

    /** 选集列表抽屉：把当前线路全部选集以表格展开，方便长剧集查找选集。 */
    private void showSeriesPickDialog() {
        if (vodInfo == null || vodInfo.seriesMap == null || TextUtils.isEmpty(vodInfo.playFlag)) {
            GlassTip.show(this, "暂无选集信息");
            return;
        }
        List<VodInfo.VodSeries> list = vodInfo.seriesMap.get(vodInfo.playFlag);
        if (list == null || list.isEmpty()) {
            GlassTip.show(this, "暂无选集信息");
            return;
        }
        String title = !TextUtils.isEmpty(vod_name) ? vod_name
                : (mVideo != null && !TextUtils.isEmpty(mVideo.name) ? mVideo.name : "");
        new SeriesPickDialog(this, title, vodInfo.playFlag, list, vodInfo.playIndex,
                new SeriesPickDialog.OnSeriesPickListener() {
                    @Override
                    public void onSeriesPick(int position) {
                        selectSeries(position);
                    }
                }).show();
    }

    /** 换源测速面板：列出可切换的源并测速，用户自行点选要切换到的源。 */
    private void showSourceSpeedDialog() {
        if (isFinishing() || switchSourceSearching) {
            return;
        }
        SourceBean currentSource = ApiConfig.get().getSource(sourceKey);
        if (currentSource == null || !currentSource.isChangeable()) {
            GlassTip.show(this, "当前站点不支持换源");
            return;
        }
        String title = mVideo != null && !TextUtils.isEmpty(mVideo.name) ? mVideo.name : vod_name;
        if (TextUtils.isEmpty(title)) {
            GlassTip.show(this, "暂无片名信息，无法换源");
            return;
        }
        List<SourceBean> sources = new ArrayList<>();
        for (SourceBean bean : ApiConfig.get().getSwitchSourceBeanList()) {
            if (bean.isSearchable() && bean.isChangeable() && !TextUtils.equals(bean.getKey(), sourceKey)) {
                sources.add(bean);
            }
        }
        if (sources.isEmpty()) {
            GlassTip.show(this, "暂无可切换的片源");
            return;
        }
        sourceSpeedDialog = new SourceSpeedDialog(this, sources, title.trim(), new SourceSpeedDialog.OnSourcePickListener() {
            @Override
            public void onSourcePick(SourceBean source, Movie.Video matchedVideo) {
                onSourcePicked(source, matchedVideo);
            }
        });
        sourceSpeedDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                releaseSourceSpeedDialog();
            }
        });
        sourceSpeedDialog.show();
        // 面板打开的同时后台预搜索全部源，标记「有片源/无片源」
        startPanelSourceSearch(sources, title.trim());
    }

    /** 预搜索命中候选的源直接切换；否则先在该源里搜同名片源。 */
    private void onSourcePicked(SourceBean source, Movie.Video matchedVideo) {
        if (switchSourceSearching) {
            GlassTip.show(this, "正在搜索片源，请稍候");
            return;
        }
        if (matchedVideo != null) {
            switchToMatchedVideo(matchedVideo, source.getName());
            return;
        }
        String title = mVideo != null && !TextUtils.isEmpty(mVideo.name) ? mVideo.name : vod_name;
        if (TextUtils.isEmpty(title)) {
            return;
        }
        switchSourceSearching = true;
        switchSourceTargetKey = source.getKey();
        switchSourceTargetName = source.getName();
        switchSourceTitle = title.trim();
        switchSourceToken = "switch_source_" + (++switchSourceRequestIndex);
        if (sourceSpeedDialog != null && sourceSpeedDialog.isShowing()) {
            sourceSpeedDialog.setRowSearching(switchSourceTargetKey, true);
        }
        llLayout.removeCallbacks(switchSourceSearchTimeout);
        llLayout.postDelayed(switchSourceSearchTimeout, SWITCH_SOURCE_SEARCH_TIMEOUT_MS);
        if (switchSourceSearchExecutor == null || switchSourceSearchExecutor.isShutdown()) {
            switchSourceSearchExecutor = Executors.newSingleThreadExecutor();
        }
        final String searchKey = switchSourceTargetKey;
        final String searchTitle = switchSourceTitle;
        final String searchToken = switchSourceToken;
        switchSourceSearchFuture = switchSourceSearchExecutor.submit(new Runnable() {
            @Override
            public void run() {
                sourceViewModel.getSwitchSourceSearch(searchKey, searchTitle, searchToken);
            }
        });
    }

    /** 用预搜索/单源搜索命中的候选影片直接切换过去（点选搜索成功时也走这里）。 */
    private void switchToMatchedVideo(Movie.Video video, String sourceName) {
        if (video == null || TextUtils.isEmpty(video.id)) {
            return;
        }
        // 先清掉可能仍在进行的自动换源状态：否则 detailResult 会被自动换源的
        // 「detailFallbackActive && !detailFallbackLoadingCandidate」守卫拦掉，表现为点了没反应。
        resetDetailFallback();
        // 记入换源历史，后续自动换源（线路耗尽兜底）不会再走这个源
        detailFallbackTitle = switchSourceTitle != null && !TextUtils.isEmpty(switchSourceTitle)
                ? switchSourceTitle : (vod_name == null ? "" : vod_name.trim());
        switchSourceTitle = detailFallbackTitle;
        detailFallbackTriedKeys.add(getDetailFallbackKey(video.sourceKey, video.id));
        addDetailFallbackUsedSource(video.sourceKey);
        manualSwitchSourceName = sourceName == null ? "" : sourceName;
        vod_name = video.name == null ? "" : video.name;
        vod_picture = video.pic == null ? "" : video.pic;
        if (sourceSpeedDialog != null && sourceSpeedDialog.isShowing()) {
            sourceSpeedDialog.dismiss();
        }
        showLoading();
        loadDetail(video.id, video.sourceKey, true);
    }

    private void cancelSwitchSourceSearchTask() {
        if (switchSourceSearchFuture != null) {
            switchSourceSearchFuture.cancel(true);
            switchSourceSearchFuture = null;
        }
    }

    private void stopSwitchSourceSearchExecutor() {
        cancelSwitchSourceSearchTask();
        if (switchSourceSearchExecutor != null) {
            try {
                switchSourceSearchExecutor.shutdownNow();
            } catch (Throwable th) {
                th.printStackTrace();
            }
            switchSourceSearchExecutor = null;
        }
    }

    /** 面板预搜索：并发搜全部可换源，结果经 panelSourceSearchResult 回来逐源标记。 */
    private void startPanelSourceSearch(List<SourceBean> sources, String title) {
        stopPanelSearchExecutor();
        panelSearchActive = true;
        panelSearchTitle = title;
        panelSearchBaseToken = "panel_source_" + (++panelSearchRequestIndex);
        panelSearchExecutor = Executors.newFixedThreadPool(8);
        for (SourceBean bean : sources) {
            final String searchKey = bean.getKey();
            final String searchToken = panelSearchBaseToken + "|" + searchKey;
            panelSearchExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    sourceViewModel.getPanelSourceSearch(searchKey, panelSearchTitle, searchToken);
                }
            });
        }
        llLayout.removeCallbacks(panelSearchTimeout);
        llLayout.postDelayed(panelSearchTimeout, PANEL_SOURCE_SEARCH_TIMEOUT_MS);
    }

    private void stopPanelSearchExecutor() {
        if (panelSearchExecutor != null) {
            try {
                panelSearchExecutor.shutdownNow();
            } catch (Throwable th) {
                th.printStackTrace();
            }
            panelSearchExecutor = null;
        }
    }

    /** 预搜索整体收尾：停任务、超时回调、剩余未回包的源按「无片源」收尾。 */
    private void stopPanelSourceSearch() {
        llLayout.removeCallbacks(panelSearchTimeout);
        panelSearchActive = false;
        stopPanelSearchExecutor();
        OkGo.getInstance().cancelTag(PANEL_SOURCE_SEARCH_TAG);
        if (sourceSpeedDialog != null && sourceSpeedDialog.isShowing()) {
            sourceSpeedDialog.finishSearchPhase();
        }
    }

    /** 从搜索结果里找片名完全一致的条目（与自动换源口径相同）。 */
    private Movie.Video findExactTitleVideo(AbsXml absXml, String title) {
        if (absXml == null || absXml.movie == null || absXml.movie.videoList == null || TextUtils.isEmpty(title)) {
            return null;
        }
        for (Movie.Video video : absXml.movie.videoList) {
            if (video == null || TextUtils.isEmpty(video.id)) {
                continue;
            }
            if (TextUtils.equals(title, video.name == null ? "" : video.name.trim())) {
                return video;
            }
        }
        return null;
    }

    /** 面板关闭（用户关闭 / 切换成功 / 页面销毁）后的清理。 */
    private void releaseSourceSpeedDialog() {
        sourceSpeedDialog = null;
        SourceSpeedTester.cancelAll();
        stopPanelSourceSearch();
        if (switchSourceSearching) {
            llLayout.removeCallbacks(switchSourceSearchTimeout);
            switchSourceSearching = false;
            cancelSwitchSourceSearchTask();
            OkGo.getInstance().cancelTag(SWITCH_SOURCE_SEARCH_TAG);
        }
    }

    private boolean startDetailFallback(boolean manual) {
        SourceBean currentSource = ApiConfig.get().getSource(sourceKey);
        if (isFinishing() || currentSource == null || !currentSource.isChangeable()) {
            return false;
        }
        if (detailFallbackActive) {
            return true;
        }
        detailFallbackKeepCurrentDetail = mVideo != null && vodInfo != null
                && vodInfo.seriesMap != null && !vodInfo.seriesMap.isEmpty();
        llLayout.removeCallbacks(detailFallbackDetailTimeout);
        captureDetailFallbackEpisode();
        if (mVideo != null && !TextUtils.isEmpty(mVideo.name)) {
            vod_name = mVideo.name;
        }
        if (TextUtils.isEmpty(vod_name)) {
            return false;
        }
        detailFallbackExcludedSourceKey = sourceKey;
        detailFallbackTitle = vod_name.trim();
        addDetailFallbackUsedSource(sourceKey);
        if (loadDetailFallbackCache()) {
            return true;
        }
        LOG.i("echo-detail fallback " + (manual ? "manual" : "after lines exhausted") + ": " + vod_name);
        startDetailFallback();
        return detailFallbackActive;
    }

    private void startDetailFallback() {
        detailFallbackTitle = vod_name == null ? "" : vod_name.trim();
        if (TextUtils.isEmpty(detailFallbackTitle)) {
            showDetailEmpty();
            return;
        }
        detailFallbackKeepCurrentDetail = mVideo != null && vodInfo != null
                && vodInfo.seriesMap != null && !vodInfo.seriesMap.isEmpty();

        for (SourceBean bean : ApiConfig.get().getSourceBeanList()) {
            if (bean.isSearchable() && bean.isChangeable() && !TextUtils.equals(bean.getKey(), detailFallbackExcludedSourceKey) && !isDetailFallbackSourceUsed(bean.getKey())) {
                detailFallbackSourceOrder.add(bean.getKey());
            }
        }
        if (detailFallbackSourceOrder.isEmpty()) {
            if (!detailFallbackKeepCurrentDetail) {
                showDetailEmpty();
            }
            return;
        }

        detailFallbackActive = true;
        detailFallbackSearching = true;
        detailFallbackSearchCollecting = true;
        detailFallbackSearchTimedOut = false;
        detailFallbackDetailTimedOut = false;
        detailFallbackSearchTimeoutScheduled = false;
        detailFallbackLoadingCandidate = false;
        detailFallbackBatchIndex = 0;
        detailFallbackNextSourceIndex = 0;
        detailFallbackToken = "detail_fallback_" + (++detailFallbackRequestIndex);
        detailFallbackTriedKeys.add(getDetailFallbackKey(sourceKey, vodId));
        LOG.i("echo-detail fallback search: " + detailFallbackTitle + ", sources=" + detailFallbackSourceOrder.size());
        scheduleDetailFallbackSearch();
    }

    private void captureDetailFallbackEpisode() {
        detailFallbackEpisode = null;
        detailFallbackEpisodeIndex = -1;
        if (vodInfo == null || vodInfo.seriesMap == null || TextUtils.isEmpty(vodInfo.playFlag)) {
            return;
        }
        List<VodInfo.VodSeries> seriesList = vodInfo.seriesMap.get(vodInfo.playFlag);
        if (seriesList == null || seriesList.isEmpty()) {
            return;
        }
        detailFallbackEpisodeIndex = Math.max(0, Math.min(vodInfo.playIndex, seriesList.size() - 1));
        detailFallbackEpisode = seriesList.get(detailFallbackEpisodeIndex);
    }

    private void restoreDetailFallbackEpisode() {
        if (detailFallbackEpisode == null || detailFallbackEpisodeIndex < 0 || vodInfo == null || vodInfo.seriesMap == null) {
            return;
        }
        String preferredFlag = vodInfo.playFlag;
        List<VodInfo.VodSeries> preferredList = vodInfo.seriesMap.get(preferredFlag);
        int matchedIndex = findMatchingEpisodeIndex(detailFallbackEpisode, preferredList);
        if (matchedIndex >= 0) {
            vodInfo.playIndex = matchedIndex;
            return;
        }
        if (vodInfo.seriesFlags != null) {
            for (VodInfo.VodSeriesFlag seriesFlag : vodInfo.seriesFlags) {
                if (seriesFlag == null || TextUtils.isEmpty(seriesFlag.name) || TextUtils.equals(preferredFlag, seriesFlag.name)) {
                    continue;
                }
                List<VodInfo.VodSeries> seriesList = vodInfo.seriesMap.get(seriesFlag.name);
                matchedIndex = findMatchingEpisodeIndex(detailFallbackEpisode, seriesList);
                if (matchedIndex >= 0) {
                    vodInfo.playFlag = seriesFlag.name;
                    vodInfo.playIndex = matchedIndex;
                    return;
                }
            }
        }
        for (String flag : vodInfo.seriesMap.keySet()) {
            if (TextUtils.equals(preferredFlag, flag) || containsSeriesFlag(flag)) {
                continue;
            }
            List<VodInfo.VodSeries> seriesList = vodInfo.seriesMap.get(flag);
            matchedIndex = findMatchingEpisodeIndex(detailFallbackEpisode, seriesList);
            if (matchedIndex >= 0) {
                vodInfo.playFlag = flag;
                vodInfo.playIndex = matchedIndex;
                return;
            }
        }
        if (preferredList != null && !preferredList.isEmpty()) {
            vodInfo.playIndex = Math.max(0, Math.min(detailFallbackEpisodeIndex, preferredList.size() - 1));
        }
    }

    private boolean containsSeriesFlag(String name) {
        if (vodInfo == null || vodInfo.seriesFlags == null) {
            return false;
        }
        for (VodInfo.VodSeriesFlag flag : vodInfo.seriesFlags) {
            if (flag != null && TextUtils.equals(flag.name, name)) {
                return true;
            }
        }
        return false;
    }

    private void onDetailFallbackSearchResult(AbsXml data) {
        if (!detailFallbackActive || !detailFallbackSearchCollecting || data == null || !detailFallbackBatchToken.equals(data.searchToken)) {
            return;
        }
        detailFallbackPendingSources.remove(data.sourceKey);
        if (data.movie != null && data.movie.videoList != null) {
            for (Movie.Video video : data.movie.videoList) {
                if (video == null || TextUtils.isEmpty(video.id) || isDetailFallbackSourceUsed(video.sourceKey) || !detailFallbackTitle.equals(video.name == null ? "" : video.name.trim())) {
                    continue;
                }
                String candidateKey = getDetailFallbackKey(video.sourceKey, video.id);
                if (!detailFallbackTriedKeys.contains(candidateKey) && detailFallbackCandidateKeys.add(candidateKey)) {
                    detailFallbackCandidates.add(video);
                    cacheDetailFallbackCandidate(video);
                }
            }
        }
        if (!detailFallbackLoadingCandidate && !detailFallbackCandidates.isEmpty()
                && (!detailFallbackSearchTimedOut || detailFallbackDetailTimedOut)) {
            LOG.i("echo-detail fallback candidates: " + detailFallbackCandidates.size());
            loadNextDetailFallbackSource();
        }
        if (!detailFallbackLoadingCandidate) {
            if (detailFallbackSearching) {
                scheduleDetailFallbackSearch();
            } else if (detailFallbackPendingSources.isEmpty()) {
                finishDetailFallbackSearchCollection();
            }
        }
    }

    private void scheduleDetailFallbackSearch() {
        if (!detailFallbackActive || !detailFallbackSearching) {
            return;
        }
        if (!detailFallbackPendingSources.isEmpty() || detailFallbackLoadingCandidate) {
            return;
        }
        if (detailFallbackNextSourceIndex >= detailFallbackSourceOrder.size()) {
            detailFallbackSearching = false;
            llLayout.removeCallbacks(detailFallbackTimeout);
            stopDetailFallbackSearchExecutor();
            LOG.i("echo-detail fallback candidates: " + detailFallbackCandidates.size());
            loadNextDetailFallbackSource();
            return;
        }

        stopDetailFallbackSearchExecutor();
        detailFallbackSearchExecutor = Executors.newFixedThreadPool(DETAIL_FALLBACK_MAX_SEARCH);
        detailFallbackBatchToken = detailFallbackToken + "_batch_" + (++detailFallbackBatchIndex);
        int batchEnd = Math.min(detailFallbackNextSourceIndex + DETAIL_FALLBACK_MAX_SEARCH, detailFallbackSourceOrder.size());
        while (detailFallbackNextSourceIndex < batchEnd) {
            final String searchKey = detailFallbackSourceOrder.get(detailFallbackNextSourceIndex++);
            final String searchTitle = detailFallbackTitle;
            final String searchToken = detailFallbackBatchToken;
            detailFallbackPendingSources.add(searchKey);
            detailFallbackSearchExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    sourceViewModel.getDetailFallbackSearch(searchKey, searchTitle, searchToken);
                }
            });
        }
        if (!detailFallbackSearchTimeoutScheduled) {
            detailFallbackSearchTimeoutScheduled = true;
            llLayout.postDelayed(detailFallbackTimeout, DETAIL_FALLBACK_SEARCH_TIMEOUT_MS);
        }
    }

    private void finishDetailFallbackSearchOnTimeout() {
        if (!detailFallbackActive || !detailFallbackSearching) {
            return;
        }
        detailFallbackSearchTimeoutScheduled = false;
        LOG.i("echo-detail fallback search timeout: " + detailFallbackBatchToken);
        // Keep the current 20 searches alive so late results can be used by the next fallback source.
        detailFallbackSearching = false;
        detailFallbackSearchTimedOut = true;
        detailFallbackNextSourceIndex = detailFallbackSourceOrder.size();
        if (!detailFallbackLoadingCandidate) {
            LOG.i("echo-detail fallback candidates: " + detailFallbackCandidates.size());
            if (!detailFallbackCandidates.isEmpty()) {
                loadNextDetailFallbackSource();
            } else {
                showDetailFallbackEmptyIfNeeded();
            }
        }
    }

    private void loadNextDetailFallbackSource() {
        while (!detailFallbackCandidates.isEmpty()) {
            Movie.Video video = detailFallbackCandidates.remove(0);
            String candidateKey = getDetailFallbackKey(video.sourceKey, video.id);
            if (isDetailFallbackSourceUsed(video.sourceKey) || !detailFallbackTriedKeys.add(candidateKey)) {
                continue;
            }
            LOG.i("echo-detail fallback source: " + video.sourceKey + ", id=" + video.id);
            detailFallbackLoadingCandidate = true;
            detailFallbackDetailTimedOut = false;
            addDetailFallbackUsedSource(video.sourceKey);
            vod_name = video.name == null ? "" : video.name;
            vod_picture = video.pic == null ? "" : video.pic;
            loadDetail(video.id, video.sourceKey, true);
            return;
        }
        if (detailFallbackSearching || detailFallbackSearchCollecting) {
            if (detailFallbackPendingSources.isEmpty()) {
                if (detailFallbackSearching) {
                    scheduleDetailFallbackSearch();
                } else {
                    finishDetailFallbackSearchCollection();
                }
            }
            return;
        }
        finishDetailFallbackWithoutResult();
    }

    private void finishDetailFallbackSearchCollection() {
        if (!detailFallbackSearchCollecting) {
            return;
        }
        detailFallbackSearchCollecting = false;
        stopDetailFallbackSearchExecutor();
        OkGo.getInstance().cancelTag(DETAIL_FALLBACK_SEARCH_TAG);
        if (!detailFallbackLoadingCandidate) {
            finishDetailFallbackWithoutResult();
        }
    }

    private void showDetailFallbackEmptyIfNeeded() {
        if (!detailFallbackKeepCurrentDetail) {
            showDetailEmpty();
        }
    }

    private void finishDetailFallbackWithoutResult() {
        boolean keepCurrentDetail = detailFallbackKeepCurrentDetail;
        resetDetailFallback();
        if (!keepCurrentDetail) {
            showDetailEmpty();
        }
    }

    private void finishDetailFallbackDetailOnTimeout() {
        if (!detailFallbackActive || !detailFallbackLoadingCandidate) {
            return;
        }
        LOG.i("echo-detail fallback detail timeout: " + sourceKey);
        detailFallbackLoadingCandidate = false;
        detailFallbackDetailTimedOut = true;
        OkGo.getInstance().cancelTag("detail");
        loadNextDetailFallbackSource();
    }

    private String getDetailFallbackKey(String key, String id) {
        return (key == null ? "" : key) + "|" + (id == null ? "" : id);
    }

    private boolean loadDetailFallbackCache() {
        List<Movie.Video> cachedCandidates = detailFallbackCache.get(detailFallbackTitle);
        if (cachedCandidates == null || cachedCandidates.isEmpty()) {
            return false;
        }
        for (Movie.Video video : cachedCandidates) {
            SourceBean source = video == null ? null : ApiConfig.get().getSource(video.sourceKey);
            if (video == null || source == null || !source.isChangeable()
                    || TextUtils.equals(video.sourceKey, detailFallbackExcludedSourceKey) || isDetailFallbackSourceUsed(video.sourceKey)) {
                continue;
            }
            String candidateKey = getDetailFallbackKey(video.sourceKey, video.id);
            if (detailFallbackCandidateKeys.add(candidateKey)) {
                detailFallbackCandidates.add(video);
            }
        }
        if (detailFallbackCandidates.isEmpty()) {
            return false;
        }
        detailFallbackActive = true;
        detailFallbackLoadingCandidate = false;
        detailFallbackTriedKeys.add(getDetailFallbackKey(sourceKey, vodId));
        LOG.i("echo-detail fallback cache: " + detailFallbackTitle + ", candidates=" + detailFallbackCandidates.size());
        loadNextDetailFallbackSource();
        return true;
    }

    private void cacheDetailFallbackCandidates(String title, List<Movie.Video> candidates) {
        title = title == null ? "" : title.trim();
        if (TextUtils.isEmpty(title) || candidates == null || candidates.isEmpty()) {
            return;
        }
        List<Movie.Video> cachedCandidates = detailFallbackCache.get(title);
        if (cachedCandidates == null) {
            cachedCandidates = new ArrayList<>();
            detailFallbackCache.put(title, cachedCandidates);
        }
        for (Movie.Video video : candidates) {
            if (cachedCandidates.size() >= DETAIL_FALLBACK_MAX_SEARCH || video == null
                    || TextUtils.isEmpty(video.id) || !TextUtils.equals(title, video.name == null ? "" : video.name.trim())) {
                continue;
            }
            String candidateKey = getDetailFallbackKey(video.sourceKey, video.id);
            boolean exists = false;
            for (Movie.Video cachedVideo : cachedCandidates) {
                if (candidateKey.equals(getDetailFallbackKey(cachedVideo.sourceKey, cachedVideo.id))) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                cachedCandidates.add(video);
            }
        }
    }

    private void cacheDetailFallbackCandidate(Movie.Video video) {
        List<Movie.Video> cachedCandidates = detailFallbackCache.get(detailFallbackTitle);
        if (cachedCandidates == null) {
            cachedCandidates = new ArrayList<>();
            detailFallbackCache.put(detailFallbackTitle, cachedCandidates);
        }
        String candidateKey = getDetailFallbackKey(video.sourceKey, video.id);
        for (Movie.Video cachedVideo : cachedCandidates) {
            if (candidateKey.equals(getDetailFallbackKey(cachedVideo.sourceKey, cachedVideo.id))) {
                return;
            }
        }
        cachedCandidates.add(video);
    }

    private void addDetailFallbackUsedSource(String sourceKey) {
        if (TextUtils.isEmpty(detailFallbackTitle) || TextUtils.isEmpty(sourceKey)) {
            return;
        }
        Set<String> usedSources = detailFallbackUsedSourceKeys.get(detailFallbackTitle);
        if (usedSources == null) {
            usedSources = new HashSet<>();
            detailFallbackUsedSourceKeys.put(detailFallbackTitle, usedSources);
        }
        usedSources.add(sourceKey);
    }

    private boolean isDetailFallbackSourceUsed(String sourceKey) {
        Set<String> usedSources = detailFallbackUsedSourceKeys.get(detailFallbackTitle);
        return usedSources != null && usedSources.contains(sourceKey);
    }

    private void showDetailEmpty() {
        showEmpty();
        llPlayerFragmentContainer.setVisibility(View.GONE);
        llPlayerFragmentContainerBlock.setVisibility(View.GONE);
    }

    private void resetDetailFallback() {
        detailFallbackActive = false;
        detailFallbackSearching = false;
        detailFallbackSearchCollecting = false;
        detailFallbackSearchTimedOut = false;
        detailFallbackDetailTimedOut = false;
        detailFallbackSearchTimeoutScheduled = false;
        detailFallbackKeepCurrentDetail = false;
        detailFallbackLoadingCandidate = false;
        detailFallbackBatchIndex = 0;
        detailFallbackNextSourceIndex = 0;
        detailFallbackToken = "";
        detailFallbackBatchToken = "";
        detailFallbackTitle = "";
        detailFallbackExcludedSourceKey = "";
        detailFallbackSourceOrder.clear();
        detailFallbackCandidates.clear();
        detailFallbackPendingSources.clear();
        detailFallbackCandidateKeys.clear();
        detailFallbackTriedKeys.clear();
        detailFallbackEpisode = null;
        detailFallbackEpisodeIndex = -1;
        switchSourceSearching = false;
        manualSwitchSourceName = "";
        panelSearchActive = false;
        if (llLayout != null) {
            llLayout.removeCallbacks(detailFallbackTimeout);
            llLayout.removeCallbacks(detailFallbackDetailTimeout);
            llLayout.removeCallbacks(switchSourceSearchTimeout);
            llLayout.removeCallbacks(panelSearchTimeout);
        }
        stopSwitchSourceSearchExecutor();
        stopPanelSearchExecutor();
        if (sourceSpeedDialog != null && sourceSpeedDialog.isShowing()) {
            sourceSpeedDialog.dismiss();
        }
        OkGo.getInstance().cancelTag(DETAIL_FALLBACK_SEARCH_TAG);
        OkGo.getInstance().cancelTag(PANEL_SOURCE_SEARCH_TAG);
    }

    private void stopDetailFallbackSearchExecutor() {
        if (detailFallbackSearchExecutor != null) {
            detailFallbackSearchExecutor.shutdownNow();
            detailFallbackSearchExecutor = null;
        }
    }

    private boolean isFirstLoad = true;
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_REFRESH) {
            if (event.obj != null) {
                if (event.obj instanceof VodInfo) {
                    syncPlayingVodInfo((VodInfo) event.obj);
                } else if (event.obj instanceof Integer) {
                    int index = (int) event.obj;
                    for (int j = 0; j < Objects.requireNonNull(vodInfo.seriesMap.get(vodInfo.playFlag)).size(); j++) {
                        seriesAdapter.getData().get(j).selected = false;
                        seriesAdapter.notifyItemChanged(j);
                    }
                    seriesAdapter.getData().get(index).selected = true;
                    seriesAdapter.notifyItemChanged(index);
                    if(!isFirstLoad)mGridView.scrollToPosition(index);
                    vodInfo.playIndex = index;
                    routeSwitchSeries = seriesAdapter.getData().get(index);
                    //保存历史
                    insertVod(firstsourceKey, vodInfo);
                    isFirstLoad = false;
                } else if (event.obj instanceof JSONObject) {
                    vodInfo.playerCfg = event.obj.toString();
                    //保存历史
                    insertVod(firstsourceKey, vodInfo);
                } else if (event.obj instanceof String) {
                    String url = event.obj.toString();
                    //设置更新播放地址
                    setTvPlayUrl(url);
                }

            }
        } else if (event.type == RefreshEvent.TYPE_PLAY_QUALITY) {
            updateQualityOptions(event.obj instanceof JSONObject ? (JSONObject) event.obj : null);
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_SELECT) {
            if (event.obj != null) {
                Movie.Video video = (Movie.Video) event.obj;
                vod_name = video.name;
                vod_picture = video.pic;
                loadDetail(video.id, video.sourceKey);
            }
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_WORD_CHANGE) {
            if (event.obj != null) {
                String word = (String) event.obj;
                switchSearchWord(word);
            }
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_RESULT) {
            try {
                searchData(event.obj == null ? null : (AbsXml) event.obj);
            } catch (Exception e) {
                searchData(null);
            }
        }
    }

    private String searchTitle = "";
    private boolean hadQuickStart = false;
    private final List<Movie.Video> quickSearchData = new ArrayList<>();
    private final List<String> quickSearchWord = new ArrayList<>();
    private ExecutorService searchExecutorService = null;
    private ExecutorService detailFallbackSearchExecutor;
    private final List<String> detailFallbackSourceOrder = new ArrayList<>();
    private final List<Movie.Video> detailFallbackCandidates = new ArrayList<>();
    private final HashMap<String, List<Movie.Video>> detailFallbackCache = new HashMap<>();
    private final HashMap<String, Set<String>> detailFallbackUsedSourceKeys = new HashMap<>();
    private final Set<String> detailFallbackPendingSources = new HashSet<>();
    private final Set<String> detailFallbackCandidateKeys = new HashSet<>();
    private final Set<String> detailFallbackTriedKeys = new HashSet<>();
    private VodInfo.VodSeries detailFallbackEpisode;
    private int detailFallbackEpisodeIndex = -1;
    private boolean detailFallbackActive;
    private boolean detailFallbackSearching;
    private boolean detailFallbackSearchCollecting;
    private boolean detailFallbackSearchTimedOut;
    private boolean detailFallbackDetailTimedOut;
    private boolean detailFallbackSearchTimeoutScheduled;
    private boolean detailFallbackKeepCurrentDetail;
    private boolean detailFallbackLoadingCandidate;
    private int detailFallbackRequestIndex;
    private int detailFallbackBatchIndex;
    private int detailFallbackNextSourceIndex;
    private String detailFallbackToken = "";
    private String detailFallbackBatchToken = "";
    private String detailFallbackTitle = "";
    private String detailFallbackExcludedSourceKey = "";
    private final Runnable detailFallbackTimeout = new Runnable() {
        @Override
        public void run() {
            finishDetailFallbackSearchOnTimeout();
        }
    };
    private final Runnable detailFallbackDetailTimeout = new Runnable() {
        @Override
        public void run() {
            finishDetailFallbackDetailOnTimeout();
        }
    };

    private void switchSearchWord(String word) {
        OkGo.getInstance().cancelTag("quick_search");
        quickSearchData.clear();
        searchTitle = word;
        searchResult();
    }

    private void startQuickSearch() {
        initCheckedSourcesForSearch();
        if (hadQuickStart)
            return;
        hadQuickStart = true;
        OkGo.getInstance().cancelTag("quick_search");
        quickSearchWord.clear();
        searchTitle = mVideo.name;
        quickSearchData.clear();
        quickSearchWord.addAll(SearchHelper.splitWords(searchTitle));
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
//                        try {
//                            for (JsonElement je : new Gson().fromJson(json, JsonArray.class)) {
//                                quickSearchWord.add(je.getAsJsonObject().get("t").getAsString());
//                            }
//                        } catch (Throwable th) {
//                            th.printStackTrace();
//                        }
//                        List<String> words = new ArrayList<>(new HashSet<>(quickSearchWord));
//                        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_WORD, words));
//                    }
//
//                    @Override
//                    public void onError(Response<String> response) {super.onError(response);}
//                });

        searchResult();
    }

    private void searchResult() {
        try {
            if (searchExecutorService != null) {
                searchExecutorService.shutdownNow();
                searchExecutorService = null;
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        searchExecutorService = Executors.newFixedThreadPool(5);
        List<SourceBean> searchRequestList = new ArrayList<>();
        searchRequestList.addAll(ApiConfig.get().getSourceBeanList());
        SourceBean home = ApiConfig.get().getHomeSourceBean();
        searchRequestList.remove(home);
        searchRequestList.add(0, home);

        ArrayList<String> siteKey = new ArrayList<>();
        for (SourceBean bean : searchRequestList) {
            if (!bean.isSearchable() || !bean.isQuickSearch()) {
                continue;
            }
            if (mCheckSources != null && !mCheckSources.containsKey(bean.getKey())) {
                continue;
            }
            siteKey.add(bean.getKey());
        }
        for (String key : siteKey) {
            searchExecutorService.execute(new Runnable() {
                @Override
                public void run() {
                    sourceViewModel.getQuickSearch(key, searchTitle);
                }
            });
        }
    }

    private void searchData(AbsXml absXml) {
        if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
            List<Movie.Video> data = new ArrayList<>();
            for (Movie.Video video : absXml.movie.videoList) {
                // 去除当前相同的影片
                if (video.sourceKey.equals(sourceKey) && video.id.equals(vodId))
                    continue;
                data.add(video);
            }
            quickSearchData.addAll(data);
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH, data));
        }
    }

    private void syncPlayingVodInfo(VodInfo playingVodInfo) {
        if (playingVodInfo == null || vodInfo == null || vodInfo.seriesMap == null) {
            return;
        }
        String newFlag = playingVodInfo.playFlag;
        if (TextUtils.isEmpty(newFlag) || !vodInfo.seriesMap.containsKey(newFlag)) {
            return;
        }
        List<VodInfo.VodSeries> newSeriesList = vodInfo.seriesMap.get(newFlag);
        if (newSeriesList == null || newSeriesList.isEmpty()) {
            return;
        }

        String oldFlag = vodInfo.playFlag;
        int oldIndex = vodInfo.playIndex;
        boolean sameFlag = TextUtils.equals(oldFlag, newFlag);
        VodInfo.VodSeries playingSeries = getPlayingSeries(playingVodInfo, newFlag);
        int newIndex = findMatchingEpisodeIndex(playingSeries, newSeriesList);
        vodInfo.playFlag = newFlag;
        vodInfo.playIndex = newIndex;
        if (playingVodInfo.playerCfg != null) {
            vodInfo.playerCfg = playingVodInfo.playerCfg;
        }

        for (VodInfo.VodSeriesFlag flag : vodInfo.seriesFlags) {
            flag.selected = flag.name.equals(newFlag);
        }
        for (List<VodInfo.VodSeries> seriesList : vodInfo.seriesMap.values()) {
            if (seriesList == null) {
                continue;
            }
            for (VodInfo.VodSeries series : seriesList) {
                series.selected = false;
            }
        }
        if (newIndex >= 0) {
            newSeriesList.get(newIndex).selected = true;
            routeSwitchSeries = newSeriesList.get(newIndex);
        }

        seriesFlagAdapter.notifyDataSetChanged();
        if (sameFlag && newIndex >= 0 && oldIndex >= 0 && oldIndex < newSeriesList.size()) {
            if (oldIndex != newIndex) {
                seriesAdapter.notifyItemChanged(oldIndex);
                seriesAdapter.notifyItemChanged(newIndex);
            }
        } else {
            refreshList();
        }
        if (newIndex >= 0) {
            setTvPlayUrl(newSeriesList.get(newIndex).url);
        }

        int flagIndex = -1;
        for (int i = 0; i < vodInfo.seriesFlags.size(); i++) {
            if (vodInfo.seriesFlags.get(i).name.equals(newFlag)) {
                flagIndex = i;
                break;
            }
        }
        if (flagIndex >= 0) {
            mGridViewFlag.scrollToPosition(flagIndex);
        }
        if (!isFirstLoad && newIndex >= 0) {
            mGridView.scrollToPosition(newIndex);
        }

        insertVod(firstsourceKey, vodInfo);
        isFirstLoad = false;
    }

    private VodInfo.VodSeries getPlayingSeries(VodInfo playingVodInfo, String flag) {
        if (playingVodInfo == null || playingVodInfo.seriesMap == null || TextUtils.isEmpty(flag)) {
            return null;
        }
        List<VodInfo.VodSeries> playingList = playingVodInfo.seriesMap.get(flag);
        if (playingList == null || playingList.isEmpty()) {
            return null;
        }
        int safeIndex = Math.max(0, Math.min(playingVodInfo.playIndex, playingList.size() - 1));
        return playingList.get(safeIndex);
    }

    private boolean isCurrentPreviewPlaying(int position) {
        if (!showPreview || previewVodInfo == null || vodInfo == null || vodInfo.seriesMap == null || TextUtils.isEmpty(vodInfo.playFlag)) {
            return false;
        }
        if (!TextUtils.equals(vodInfo.playFlag, previewVodInfo.playFlag) || previewVodInfo.playIndex != position) {
            return false;
        }
        List<VodInfo.VodSeries> currentList = vodInfo.seriesMap.get(vodInfo.playFlag);
        if (currentList == null || position < 0 || position >= currentList.size()) {
            return false;
        }
        VodInfo.VodSeries currentSeries = currentList.get(position);
        VodInfo.VodSeries previewSeries = getPlayingSeries(previewVodInfo, previewVodInfo.playFlag);
        return currentSeries != null && previewSeries != null && TextUtils.equals(currentSeries.url, previewSeries.url);
    }

    private int findSameEpisodeIndex(VodInfo.VodSeries currentSeries, List<VodInfo.VodSeries> targetList, int fallbackIndex) {
        if (targetList == null || targetList.isEmpty()) {
            return 0;
        }
        int matchedIndex = findMatchingEpisodeIndex(currentSeries, targetList);
        return matchedIndex >= 0 ? matchedIndex : Math.max(0, Math.min(fallbackIndex, targetList.size() - 1));
    }

    private int findMatchingEpisodeIndex(VodInfo.VodSeries currentSeries, List<VodInfo.VodSeries> targetList) {
        if (targetList == null || targetList.isEmpty()) {
            return -1;
        }
        if (targetList.size() == 1) {
            return 0;
        }
        if (currentSeries == null || TextUtils.isEmpty(currentSeries.name)) {
            return -1;
        }
        int currentEpisode = extractEpisodeNumber(currentSeries.name);
        int matchedIndex = -1;
        int bestScore = 0;
        for (int i = 0; i < targetList.size(); i++) {
            VodInfo.VodSeries targetSeries = targetList.get(i);
            int score = getEpisodeMatchScore(currentSeries.name, currentEpisode, targetSeries == null ? null : targetSeries.name);
            if (score > bestScore) {
                bestScore = score;
                matchedIndex = i;
            }
        }
        return matchedIndex;
    }

    private int getEpisodeMatchScore(String currentName, int currentEpisode, String targetName) {
        if (TextUtils.isEmpty(currentName) || TextUtils.isEmpty(targetName)) {
            return 0;
        }
        if (targetName.equalsIgnoreCase(currentName)) {
            return 100;
        }
        if (currentEpisode >= 0 && extractEpisodeNumber(targetName) == currentEpisode) {
            return 80;
        }
        String currentLower = currentName.toLowerCase(Locale.ROOT);
        String targetLower = targetName.toLowerCase(Locale.ROOT);
        if (currentEpisode < 0 && currentName.length() >= 2 && targetLower.contains(currentLower)) {
            return 70;
        }
        if (currentEpisode < 0 && targetName.length() >= 2 && currentLower.contains(targetLower)) {
            return 60;
        }
        return 0;
    }

    private int extractEpisodeNumber(String name) {
        if (TextUtils.isEmpty(name)) {
            return -1;
        }
        try {
            String text = name.replaceAll("\\[.*?\\]|\\(.*?\\)", "");
            text = text.replaceAll("\\b(19|20)\\d{2}\\b", "");
            text = text.toLowerCase(Locale.ROOT).replaceAll("2160p|1080p|720p|480p|4k|h26[45]|x26[45]|mp4", "");
            Matcher matcher = Pattern.compile("(?i)(?:ep|\\u7b2c|e|[\\-\\.\\s])\\s?(\\d{1,4})").matcher(text);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
            String number = text.replaceAll("\\D+", "");
            if (!TextUtils.isEmpty(number)) {
                return Integer.parseInt(number);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private void insertVod(String sourceKey, VodInfo vodInfo) {
        try {
            vodInfo.playNote = vodInfo.seriesMap.get(vodInfo.playFlag).get(vodInfo.playIndex).name;
        } catch (Throwable th) {
            vodInfo.playNote = "";
        }
        RoomDataManger.insertVodRecord(sourceKey, vodInfo);
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH));
    }

    @Override
    protected void onDestroy() {
        sDetailSessionAlive = false;
        resetDetailFallback();
        super.onDestroy();
        try {
            if (searchExecutorService != null) {
                searchExecutorService.shutdownNow();
                searchExecutorService = null;
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        OkGo.getInstance().cancelTag("fenci");
        OkGo.getInstance().cancelTag("detail");
        OkGo.getInstance().cancelTag("quick_search");
        OkGo.getInstance().cancelTag(SWITCH_SOURCE_SEARCH_TAG);
        OkGo.getInstance().cancelTag(PANEL_SOURCE_SEARCH_TAG);
        releasePlayFragment();
        EventBus.getDefault().unregister(this);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_FULL_WINDOWS, fullWindows);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        // singleTask 任务根：播放页自成任务，PiP 期间整任务转 pinned、退出后无残留。
        // 从桌面展开 PiP 回到播放页时，本任务下面直接是桌面（原主任务不在紧邻位置），
        // 返回键会退到桌面——这里把主任务拉回前台再结束本页，恢复「播放页 → 首页 → 桌面」
        // 的返回链。正常浏览（未进过 PiP）时本任务下面就是主任务，走自然返回不受影响。
        if (everEnteredPipTask && isTaskRoot()) {
            try {
                Intent home = new Intent(this, HomeActivity.class);
                home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                // 跨任务启动默认是「应用切换」动画（缩放/上滑拉任务），与页面内返回的
                // 观感割裂。这里用与应用内返回一致的共享轴反向转场覆盖，
                // 「移除动画」开启时降级为纯淡入淡出
                startActivity(home, buildBackToHomeOptions());
                LOG.i("pip task back repair: bring main task to front");
                finish();
                return;
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
        if (fullWindows) {
            if (playFragment.onBackPressed())
                return;
            exitFullPreview();
            return;
        }
        if (mGridView != null && mGridView.hasFocus()
                && mGridViewFlag != null && mGridViewFlag.getVisibility() == View.VISIBLE) {
            try {
                if (seriesFlagFocus != null && seriesFlagFocus.isShown()
                        && seriesFlagFocus.requestFocus()) {
                    return;
                }
                if (mGridViewFlag.requestFocus()) {
                    return;
                }
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
        if(showPreview && playFragment!=null){
            try {
                playFragment.setPlayTitle(false);
                playFragment.setExitingPreview(true);
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
        super.onBackPressed();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && playFragment != null && fullWindows) {
            if (playFragment.dispatchKeyEvent(event)) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event != null && playFragment != null && fullWindows) {
            if (playFragment.onKeyDown(keyCode,event)) {
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (event != null && playFragment != null && fullWindows) {
            if (playFragment.onKeyUp(keyCode,event)) {
                return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    // preview
    VodInfo previewVodInfo = null;
    boolean fullWindows = false;
    /** 本 Activity 进过系统 PiP（singleTask 任务根：PiP 退出后按返回键需拉回主任务） */
    boolean everEnteredPipTask = false;
    /** 预览（未全屏）进 PiP 时把播放器布局临时提升为全屏，PiP 窗口内才是干净视频 */
    boolean pipAutoPromotedFull = false;
    /** PiP 退出后的去向待判定：展开（回前台）保持全屏；被划掉（不可见）静默还原预览 */
    boolean pendingPipDemote = false;

    /**
     * 当前是否处于系统 PiP（带版本守卫：{@code isInPictureInPictureMode()} 是 API 26+，
     * minSdk 19/21 的老设备直接调用会 NoSuchMethodError）。
     */
    private boolean inPipNow() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode();
    }

    /**
     * 预提升兜底：没有真正进入 PiP（系统拒绝 / 只是内部跳转）时，
     * 延时静默把临时全屏还原回预览，避免页面停在错误布局。
     */
    private final Runnable pipPromoteAbortCheck = new Runnable() {
        @Override
        public void run() {
            if (!pipAutoPromotedFull || pendingPipDemote) return;
            if (inPipNow()) return;
            pipAutoPromotedFull = false;
            try {
                setFullPreview(false);
            } catch (Throwable ignored) {
            }
            LOG.i("pip enter transition: promote aborted, restore preview");
        }
    };

    /**
     * 预提升（PiP 转场开始前）：预览（未全屏）状态下把播放器布局临时提升为全屏。
     *
     * <p>没有 API 31 时能“转场前”感知 PiP 的方式——系统只在 API 31+ 提供
     * {@code onPictureInPictureUiStateChanged}（该回调的参数类型是 API 31 才有的类，
     * 写进 Activity 签名会让低版本 ROM 在类校验时闪退，已弃用）。因此改由「已知的
     * 进 PiP 时机」主动调用：手动点小窗按钮、Android 11 及以下自动画中画、
     * Android 12+ 系统自动进入 PiP 前的 {@code onUserLeaveHint}。
     * 常规兜底仍是 {@link #onPictureInPictureModeChanged} 里的提升。</p>
     */
    public void preparePipPromotion() {
        if (fullWindows || pipAutoPromotedFull) return;
        pipAutoPromotedFull = true;
        try {
            setFullPreview(true);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        postPipPromoteAbortCheck();
        LOG.i("pip enter transition: pre-promote full window");
    }

    /** 预提升后确认没有进入 PiP（例如系统拒绝）：立即还原预览，不等兜底定时器 */
    public void cancelPipPromotion() {
        if (!pipAutoPromotedFull || pendingPipDemote) return;
        if (inPipNow()) return;
        pipAutoPromotedFull = false;
        removePipPromoteAbortCheck();
        try {
            setFullPreview(false);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        LOG.i("pip enter transition: promote cancelled");
    }

    private void postPipPromoteAbortCheck() {
        try {
            View decor = getWindow() == null ? null : getWindow().getDecorView();
            if (decor != null) {
                decor.removeCallbacks(pipPromoteAbortCheck);
                decor.postDelayed(pipPromoteAbortCheck, 1500);
            }
        } catch (Throwable ignored) {
        }
    }

    private void removePipPromoteAbortCheck() {
        try {
            View decor = getWindow() == null ? null : getWindow().getDecorView();
            if (decor != null) decor.removeCallbacks(pipPromoteAbortCheck);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 播放页会话存活标记（singleTask 任务根，同进程内至多一个实例）。
     * HomeActivity 收到 launcher 重新进入时据此把用户直接送回播放页
     * （对齐其他播放软件「点应用图标回到看视频时的页面」的行为）。
     */
    private static boolean sDetailSessionAlive = false;

    /** 是否存在存活的播放页（供首页图标路由判定） */
    public static boolean isDetailSessionAlive() {
        return sDetailSessionAlive;
    }

    /** PiP 落位避让条：贴底透明区，系统落位时避开该区域 → PiP 漂浮在屏幕上侧（API 33+） */
    private View pipKeepClearStrip;
    private int previewOrientation;
    private boolean previewOrientationChanged;
    ViewGroup.LayoutParams windowsPreview = null;
    ViewGroup.LayoutParams windowsFull = null;

    void toggleFullPreview() {
        setFullPreview(!fullWindows);
    }

    public void enterFullPreview() {
        setFullPreview(true);
    }

    public void exitFullPreview() {
        boolean needRefreshSeries = previewOrientationChanged;
        setFullPreview(false);
        // 全屏播放时可能已被切成横屏，退出全屏要回到竖屏详情页，
        // 否则会出现"返回后停在横屏"的问题。
        if (getResources().getConfiguration().orientation != Configuration.ORIENTATION_PORTRAIT) {
            needRefreshSeries = true;
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        }
        previewOrientationChanged = false;
        if (needRefreshSeries) {
            refreshSeriesAfterFullPreview();
        } else {
            syncSeriesSelectionAfterFullPreview();
        }
    }

    /**
     * 预览区域（previewPlayerBlock）点击热区的显隐。
     * 它盖在播放器之上，控制条显示时必须让开，否则控制条上的按钮点不到。
     */
    public void setPreviewBlockVisible(boolean visible) {
        if (llPlayerFragmentContainerBlock == null) return;
        llPlayerFragmentContainerBlock.setVisibility(
                (!fullWindows && showPreview && visible) ? View.VISIBLE : View.GONE);
    }

    private void refreshSeriesAfterFullPreview() {        if (seriesAdapter == null || vodInfo == null || vodInfo.seriesMap == null || TextUtils.isEmpty(vodInfo.playFlag)) return;
        if (vodInfo.seriesMap.get(vodInfo.playFlag) == null) return;
        mGridView.post(new Runnable() {
            @Override
            public void run() {
                mGridView.getRecycledViewPool().clear();
                mGridView.setAdapter(seriesAdapter);
                refreshList(true);
            }
        });
    }

    private void syncSeriesSelectionAfterFullPreview() {
        if (seriesAdapter == null || vodInfo == null || vodInfo.seriesMap == null || TextUtils.isEmpty(vodInfo.playFlag)) return;
        List<VodInfo.VodSeries> list = vodInfo.seriesMap.get(vodInfo.playFlag);
        if (list == null || list.isEmpty()) return;
        if (vodInfo.playIndex >= list.size()) {
            vodInfo.playIndex = list.size() - 1;
        }
        setSeriesGroupOptions();
        mGridView.post(new Runnable() {
            @Override
            public void run() {
                int firstVisible = mGridViewLayoutMgr.findFirstVisibleItemPosition();
                int lastVisible = mGridViewLayoutMgr.findLastVisibleItemPosition();
                if (vodInfo.playIndex >= 0 && (vodInfo.playIndex < firstVisible || vodInfo.playIndex > lastVisible)) {
                    customSeriesScrollPos(vodInfo.playIndex);
                }
            }
        });
        mSeriesGroupView.post(new Runnable() {
            @Override
            public void run() {
                if (mSeriesGroupView.getVisibility() == View.VISIBLE) {
                    mSeriesGroupView.scrollToPosition(selectedSeriesGroupPosition);
                }
            }
        });
    }

    void setFullPreview(boolean full) {
        if (windowsPreview == null) {
            windowsPreview = llPlayerFragmentContainer.getLayoutParams();
        }
        if (windowsFull == null) {
            windowsFull = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        if (full) {
            previewOrientation = getResources().getConfiguration().orientation;
            previewOrientationChanged = false;
        }
        fullWindows = full;
        applyImmersive(full);
        if (playFragment != null) {
            playFragment.setAutoSwitchLineEnabled(!fullWindows);
            playFragment.setPreviewMode(!fullWindows);
        }
        llPlayerFragmentContainer.setVisibility(fullWindows || showPreview ? View.VISIBLE : View.GONE);
        llPlayerFragmentContainer.setLayoutParams(fullWindows ? windowsFull : windowsPreview);
        syncPlayerTranslation(scrollView == null ? 0 : scrollView.getScrollY());
        setPreviewRoundClip(!fullWindows);
        // 控制条显示时热区必须让开，否则控制条上的按钮点不到
        setPreviewBlockVisible(!(playFragment != null && playFragment.isControlsVisible()));
        mGridView.setVisibility(fullWindows ? View.GONE : View.VISIBLE);
        mGridViewFlag.setVisibility(fullWindows ? View.GONE : View.VISIBLE);
        if (fullWindows) {
            tvSeriesGroup.setVisibility(View.GONE);
        } else {
            List<VodInfo.VodSeries> list = vodInfo == null || vodInfo.seriesMap == null || TextUtils.isEmpty(vodInfo.playFlag) ? null : vodInfo.seriesMap.get(vodInfo.playFlag);
            tvSeriesGroup.setVisibility(list != null && list.size() > 1 ? View.VISIBLE : View.GONE);
            seriesFlagAdapter.notifyDataSetChanged();
            if (showPreview) mGridView.requestFocus();
            else {
                if (playFragment != null) playFragment.pauseForHidden();
                mGridView.requestFocus();
            }
        }
        toggleSubtitleTextSize();
    }

    /**
     * 全屏播放时隐藏状态栏和导航栏（沉浸式），退出全屏恢复。
     * BaseActivity.hideSysBar() 在手机端是 no-op，所以这里单独实现。
     * 只用 SYSTEM_UI_FLAG_* 一套：targetSdk 28 下在 Android 11+ 依然有效，
     * 也避免低版本设备加载 API 30 的类时出现 VerifyError。
     */
    private void applyImmersive(boolean immersive) {
        Window window = getWindow();
        if (window == null) return;
        View decor = window.getDecorView();
        if (decor == null) return;
        // 根布局是 fitsSystemWindows="true"，系统会为状态栏加 paddingTop。
        // 全屏时必须关掉它并清掉已应用的 padding，否则播放器上方会留出一条状态栏高度的黑边。
        View root = findViewById(R.id.detail_root);
        if (root != null) {
            root.setFitsSystemWindows(!immersive);
            if (immersive) {
                root.setPadding(0, 0, 0, 0);
            } else {
                root.requestApplyInsets();
            }
        }
        if (immersive) {
            // 沉浸标志整组重设；日间主题要保留「浅色栏（深色图标）」标志——
            // 否则弹层打开时部分 ROM 会把状态栏放出来，白底 + 白字完全看不清
            int visible = ThemeUtil.isNight(this) ? 0
                    : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | visible);
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            // 退出沉浸式时整组标志是重设的，主题给的「浅色底 = 深色图标」
            // （SYSTEM_UI_FLAG_LIGHT_STATUS_BAR/NAVIGATION_BAR）也被一并清掉了，
            // 状态栏文字会变白。这里按当前主题重新上一次系统栏样式。
            ThemeUtil.applySystemBars(this);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 全屏时如果用户下拉唤出了状态栏，焦点回来后重新收起
        if (hasFocus && fullWindows) {
            applyImmersive(true);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 旋转后同步 PiP 避让条高度（跟随屏幕高度）
        if (pipKeepClearStrip != null) {
            try {
                pipKeepClearStrip.setLayoutParams(keepClearStripLp());
            } catch (Throwable ignored) {
            }
        }
        if (fullWindows && newConfig.orientation != previewOrientation) {
            previewOrientation = newConfig.orientation;
            previewOrientationChanged = true;
        }
        if (fullWindows) {
            applyImmersive(true);
        }
    }

    void ensurePlayFragment() {
        if (playFragment != null) return;
        playFragment = new PlayFragment();
        getSupportFragmentManager().beginTransaction().add(R.id.previewPlayer, playFragment).commitNowAllowingStateLoss();
        playFragment.setPreviewMode(!fullWindows);
        playFragment.setAutoSwitchLineEnabled(!fullWindows);
    }

    /** 系统 PiP 模式切换回调（Android 8.0+ 才会被调用）：转发给播放 Fragment 收敛 / 恢复播放器 UI */
    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        try {
            super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        } catch (Throwable ignored) {
        }
        if (isInPictureInPictureMode) {
            everEnteredPipTask = true;
            // 预览（未全屏）进 PiP：把播放器布局提升为全屏——PiP 窗口渲染的是整个
            // Activity 窗口，只有播放器铺满窗口，窗口里才是干净的视频画面。
            // 已由 preparePipPromotion() 转场前提升过则跳过（常见路径）；
            // 系统自行进入 PiP 等未预判到的情况在这里兜底提升。
            // 提升不强制旋转；退出 PiP 时按去向还原（展开保持全屏 / 划掉还原预览）
            removePipPromoteAbortCheck();
            if (!fullWindows && !pipAutoPromotedFull) {
                pipAutoPromotedFull = true;
                setFullPreview(true);
            }
        } else if (pipAutoPromotedFull) {
            // 退出 PiP 的去向由接下来的生命周期判定：onResume=展开回前台（保持全屏）；
            // onStop=被划掉/关闭（此时窗口不可见，静默还原预览布局）
            pendingPipDemote = true;
        }
        if (playFragment != null) {
            playFragment.setPipModeChanged(isInPictureInPictureMode);
        }
        LOG.i("pip mode changed -> " + isInPictureInPictureMode);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // PiP 展开回到前台：保持全屏布局（清除自动提升标记，返回键走正常全屏逻辑）
        if (pendingPipDemote) {
            pendingPipDemote = false;
            pipAutoPromotedFull = false;
            removePipPromoteAbortCheck();
        } else if (pipAutoPromotedFull && !inPipNow()) {
            // 预提升后并没有进入 PiP（内部跳转 / 系统拒绝）：页面已回到前台，
            // 立刻还原预览布局，避免用户看到「莫名全屏」
            pipAutoPromotedFull = false;
            removePipPromoteAbortCheck();
            try {
                setFullPreview(false);
            } catch (Throwable ignored) {
            }
            LOG.i("pip enter transition: promote reverted on resume");
        }
        // 自定义小窗（overlay）随页面回前台自动收回：页面已经回到播放界面，悬浮小窗
        // 没有继续存在的必要（点应用图标 / 最近任务回到本页都会走到这里）。
        // recall(false) 把播放器视图挂回本页并继续播放，不释放播放器实例。
        if (playFragment != null && FloatPlayWindow.get().isOwner(playFragment)) {
            try {
                FloatPlayWindow.get().recall(false);
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // PiP 被划掉/关闭（而非展开）：Activity 已不可见，静默还原预览布局不闪屏
        if (pendingPipDemote) {
            pendingPipDemote = false;
            pipAutoPromotedFull = false;
            removePipPromoteAbortCheck();
            try {
                setFullPreview(false);
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
    }

    /**
     * 「自动画中画」触发点（播放设定→后台播放→自动画中画）：用户按 Home / 切换到
     * 其他应用时系统回调，转发给播放 Fragment 按画中画模式分发（详见
     * {@link PlayFragment#autoEnterOnUserLeave()}）。App 内部跳转也会触发本回调，
     * 因此仅在「正在播放视频」时动作，开关默认关闭（按需开启）。
     */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (playFragment != null) {
            playFragment.autoEnterOnUserLeave();
        }
    }

    void releasePlayFragment() {
        if (playFragment == null) return;
        getSupportFragmentManager().beginTransaction().remove(playFragment).commitNowAllowingStateLoss();
        playFragment = null;
    }

    void toggleSubtitleTextSize() {
        int subtitleTextSize  = SubtitleHelper.getTextSize(this);
        if (!fullWindows) {
            subtitleTextSize *= 0.6;
        }
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SUBTITLE_SIZE_CHANGE, subtitleTextSize));
    }

    public PlayFragment getPlayFragment() {
        return playFragment;
    }

    private void setTvPlayUrl(String url)
    {
        setTextShow(tvPlayUrl, "播放地址：", url);
    }
}
