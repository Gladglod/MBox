package com.github.tvbox.osc.ui.activity;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.ViewPager;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.ui.adapter.HomePageAdapter;
import com.github.tvbox.osc.ui.activity.SearchActivity;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.adapter.SortAdapter;
import com.github.tvbox.osc.ui.dialog.SelectDialog;
import com.github.tvbox.osc.ui.dialog.TipDialog;
import com.github.tvbox.osc.ui.fragment.CategoryFragment;
import com.github.tvbox.osc.ui.fragment.GridFragment;
import com.github.tvbox.osc.ui.fragment.HotRecommendFragment;
import com.github.tvbox.osc.ui.fragment.UserFragment;
import com.github.tvbox.osc.ui.tv.widget.DefaultTransformer;
import com.github.tvbox.osc.ui.tv.widget.FixedSpeedScroller;
import com.github.tvbox.osc.util.AppManager;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HomeTabConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.google.android.material.tabs.TabLayout;
import com.orhanobut.hawk.Hawk;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class HomeActivity extends BaseActivity implements CategoryFragment.OnCategorySelectListener {
    private LinearLayout topLayout;
    private TextView tvDate;
    private TextView tvName;
    private TextView tvMenu;
    private TabLayout mTabLayout;
    private ViewPager mViewPager;
    private View homeContent;
    private View flFragment;
    private ImageView ivNavHome, ivNavCategory, ivNavLive, ivNavMine;
    private TextView tvNavHome, tvNavCategory, tvNavLive, tvNavMine;
    private int currentBottomTab = 0;
    private List<Movie.Video> lastRecList;
    private SourceViewModel sourceViewModel;
    private SortAdapter sortAdapter;
    private HomePageAdapter pageAdapter;
    /** 首页实际渲染的分类页（不含最左侧「热门推荐」），顺序即页序 - 1 */
    private final List<MovieSort.SortData> pageSorts = new ArrayList<>();
    private AbsSortXml lastAbsXml;
    private int currentSelected = 0;
    private SourceBean previousHomeSource;
    private String loadingSourceKey;
    private String previousHomeName;
    private boolean homeSortLoading = false;
    private final Handler mHandler = new Handler();
    private long mExitTime = 0;
    private boolean eventBusRegistered = false;
    private final Runnable mRunnable = new Runnable() {
        @SuppressLint("SetTextI18n")
        @Override
        public void run() {
            Date date = new Date();
            SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy/MM/dd  E  HH:mm", Locale.CHINA);
            tvDate.setText(timeFormat.format(date));
            mHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_home;
    }

    @Override
    protected boolean shouldRefreshAutoSize() {
        return true;
    }

    boolean useCacheConfig = false;

    @Override
    protected void init() {
        EventBus.getDefault().register(this);
        eventBusRegistered = true;
        ControlManager.get().startServer();
        initView();
        initViewModel();
        useCacheConfig = false;
        Intent intent = getIntent();
        if (intent != null && intent.getExtras() != null) {
            Bundle bundle = intent.getExtras();
            useCacheConfig = bundle.getBoolean("useCache", false);
        }
        initData();
    }

    private void initView() {
        this.topLayout = findViewById(R.id.topLayout);
        this.tvDate = findViewById(R.id.tvDate);
        this.tvName = findViewById(R.id.tvName);
        this.tvMenu = findViewById(R.id.tvMenu);
        this.mTabLayout = findViewById(R.id.mTabLayout);
        this.mViewPager = findViewById(R.id.mViewPager);
        this.sortAdapter = new SortAdapter();

        // 左上角站点名称仅作展示，不再响应点击（设置入口统一收归「我的」）
        tvName.setOnClickListener(null);
        tvName.setClickable(false);
        tvName.setFocusable(false);
        tvMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                showSiteSwitch();
            }
        });
        ImageView ivSearch = findViewById(R.id.ivSearch);
        ivSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                startActivity(new Intent(HomeActivity.this, SearchActivity.class));
            }
        });

        this.mTabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                selectPage(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                // 再次点击当前页签 = 平滑回到该页顶部
                scrollPageToTop(tab.getPosition());
            }
        });

        // 左右滑动切换页面时，让顶部页签跟着走
        this.mViewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                if (mTabLayout == null) return;
                TabLayout.Tab tab = mTabLayout.getTabAt(position);
                if (tab != null && !tab.isSelected()) {
                    tab.select();
                }
            }
        });

        initBottomNav();
        setLoadSir(this.topLayout);
    }

    private void selectPage(int position) {
        if (pageAdapter == null || position < 0 || position >= pageAdapter.getCount()) return;
        if (currentSelected == position) return;
        currentSelected = position;
        mViewPager.setCurrentItem(position, false);
        BaseLazyFragment baseLazyFragment = pageAdapter.getFragment(position);
        if (baseLazyFragment instanceof GridFragment && ((GridFragment) baseLazyFragment).shouldReloadOnSelect()) {
            ((GridFragment) baseLazyFragment).forceRefresh();
        }
    }

    private void setupTabs() {
        if (mTabLayout == null || sortAdapter == null) return;
        mTabLayout.removeAllTabs();
        // 第 0 页固定是「热门推荐」，其后按用户配置的分类顺序排列
        mTabLayout.addTab(mTabLayout.newTab().setText(HomeTabConfig.HOT_NAME));
        for (MovieSort.SortData sortData : getVisibleSortData()) {
            mTabLayout.addTab(mTabLayout.newTab().setText(sortData.name));
        }
    }

    /** 首页要显示的分类（已按用户配置过滤 + 排序），不含「热门推荐」 */
    private List<MovieSort.SortData> getVisibleSortData() {
        return HomeTabConfig.visibleSortData(sortAdapter == null ? null : sortAdapter.getData());
    }

    private void initBottomNav() {
        this.homeContent = findViewById(R.id.homeContent);
        this.flFragment = findViewById(R.id.flFragment);
        this.ivNavHome = findViewById(R.id.ivNavHome);
        this.ivNavCategory = findViewById(R.id.ivNavCategory);
        this.ivNavLive = findViewById(R.id.ivNavLive);
        this.ivNavMine = findViewById(R.id.ivNavMine);
        this.tvNavHome = findViewById(R.id.tvNavHome);
        this.tvNavCategory = findViewById(R.id.tvNavCategory);
        this.tvNavLive = findViewById(R.id.tvNavLive);
        this.tvNavMine = findViewById(R.id.tvNavMine);

        findViewById(R.id.navHome).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                selectBottomTab(0);
            }
        });
        findViewById(R.id.navCategory).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                selectBottomTab(1);
            }
        });
        findViewById(R.id.navLive).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                selectBottomTab(2);
            }
        });
        findViewById(R.id.navMine).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                selectBottomTab(3);
            }
        });
        updateBottomNav(0);
    }

    /**
     * 底部四栏切换：0 首页 / 1 分类 / 2 直播 / 3 我的
     */
    private void selectBottomTab(int tab) {
        if (tab == 2) {
            // 直播：直接进入直播播放页
            startActivity(new Intent(HomeActivity.this, LivePlayActivity.class));
            return;
        }
        // 已经在首页时再点「首页」= 平滑回到当前分类页顶部
        boolean alreadyHome = tab == 0 && currentBottomTab == 0
                && homeContent != null && homeContent.getVisibility() == View.VISIBLE;
        currentBottomTab = tab;
        updateBottomNav(tab);
        if (tab == 0) {
            showHomeContent();
            if (alreadyHome) {
                scrollPageToTop(currentSelected);
            }
        } else if (tab == 1) {
            showSubFragment(CategoryFragment.newInstance(getCategoryList()));
        } else if (tab == 3) {
            showSubFragment(UserFragment.newInstance(lastRecList));
        }
    }

    /** 把首页第 position 页平滑滚回顶部 */
    private boolean scrollPageToTop(int position) {
        if (pageAdapter == null) return false;
        BaseLazyFragment fragment = pageAdapter.getFragment(position);
        if (fragment instanceof GridFragment) {
            return ((GridFragment) fragment).smoothScrollToTop();
        }
        if (fragment instanceof HotRecommendFragment) {
            return ((HotRecommendFragment) fragment).scrollToTop();
        }
        return false;
    }

    private void showHomeContent() {
        if (flFragment != null) {
            flFragment.setVisibility(View.GONE);
        }
        if (homeContent != null) {
            homeContent.setVisibility(View.VISIBLE);
        }
        Fragment existing = getSupportFragmentManager().findFragmentById(R.id.flFragment);
        if (existing != null) {
            getSupportFragmentManager().beginTransaction().remove(existing).commitAllowingStateLoss();
        }
    }

    private void showSubFragment(Fragment fragment) {
        if (homeContent != null) {
            homeContent.setVisibility(View.GONE);
        }
        if (flFragment != null) {
            flFragment.setVisibility(View.VISIBLE);
        }
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.flFragment, fragment)
                .commitAllowingStateLoss();
    }

    private void updateBottomNav(int selected) {
        // 用语义色：日间是深青 + 半透明黑，夜间是亮青 + 半透明白，否则日间模式下图标会白到看不见
        int on = getResources().getColor(R.color.ui_accent);
        int off = getResources().getColor(R.color.ui_text_secondary);
        applyNavState(ivNavHome, tvNavHome, selected == 0, on, off);
        applyNavState(ivNavCategory, tvNavCategory, selected == 1, on, off);
        applyNavState(ivNavLive, tvNavLive, selected == 2, on, off);
        applyNavState(ivNavMine, tvNavMine, selected == 3, on, off);
    }

    private void applyNavState(ImageView icon, TextView label, boolean selected, int on, int off) {
        if (icon == null || label == null) {
            return;
        }
        int color = selected ? on : off;
        icon.setColorFilter(color);
        label.setTextColor(color);
    }

    private ArrayList<MovieSort.SortData> getCategoryList() {
        return new ArrayList<>(getVisibleSortData());
    }

    @Override
    public void onCategorySelect(MovieSort.SortData sortData) {
        if (sortData == null) {
            return;
        }
        // 页 0 是「热门推荐」，分类从第 1 页开始
        int index = -1;
        for (int i = 0; i < pageSorts.size(); i++) {
            MovieSort.SortData data = pageSorts.get(i);
            if (data != null && sortData.id != null && sortData.id.equals(data.id)) {
                index = i + 1;
                break;
            }
        }
        selectBottomTab(0);
        if (mTabLayout != null && index >= 0) {
            TabLayout.Tab tab = mTabLayout.getTabAt(index);
            if (tab != null) {
                tab.select();
            }
        }
    }

    private void initViewModel() {
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.sortResult.observe(this, new Observer<AbsSortXml>() {
            @Override
            public void onChanged(AbsSortXml absXml) {
                if (loadingSourceKey == null) {
                    return;
                }
                if (absXml != null && absXml.sourceKey != null && loadingSourceKey != null && !loadingSourceKey.equals(absXml.sourceKey)) {
                    return;
                }
                lastRecList = absXml != null ? absXml.videoList : null;
                lastAbsXml = absXml;
                SourceBean home = ApiConfig.get().getHomeSourceBean();
                showSuccess();
                clearHomePages();
                List<MovieSort.SortData> newSortData;
                if (absXml != null && absXml.classes != null && absXml.classes.sortList != null) {
                    newSortData = DefaultConfig.adjustSort(ApiConfig.get().getHomeSourceBean().getKey(), absXml.classes.sortList, false);
                } else {
                    newSortData = DefaultConfig.adjustSort(ApiConfig.get().getHomeSourceBean().getKey(), new ArrayList<>(), false);
                }
                updateSortData(newSortData);
                initViewPager(absXml);
                if (home != null && home.getName() != null && !home.getName().isEmpty()) tvName.setText(home.getName());
                tvName.clearAnimation();
                homeSortLoading = false;
                loadingSourceKey = null;
                previousHomeName = null;
                previousHomeSource = null;
            }
        });
    }

    private boolean dataInitOk = false;
    private boolean jarInitOk = false;
    private boolean searchSpiderWarmStarted = false;
    private TipDialog mConfigErrorDialog;

    private void initData() {
        if (dataInitOk && jarInitOk) {
            loadHomeSort(false);
            if (hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                LOG.e("有");
            } else {
                LOG.e("无");
            }
            if (!useCacheConfig && Hawk.get(HawkConfig.DEFAULT_LOAD_LIVE, false)) {
                jumpActivity(LivePlayActivity.class);
            }
            if (!useCacheConfig) warmSearchSpidersOnce();
            return;
        }
        tvNameAnimation();
        showLoading();
        if (dataInitOk && !jarInitOk) {
            if (!ApiConfig.get().getSpider().isEmpty()) {
                ApiConfig.get().loadJar(useCacheConfig, ApiConfig.get().getSpider(), new ApiConfig.LoadConfigCallback() {
                    @Override
                    public void success() {
                        jarInitOk = true;
                        mHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                initData();
                            }
                        }, 50);
                    }

                    @Override
                    public void notice(String msg) {
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(HomeActivity.this, msg, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }

                    @Override
                    public void error(String msg) {
                        jarInitOk = true;
                        dataInitOk = true;
                        mHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(HomeActivity.this, msg + " jar load err", Toast.LENGTH_SHORT).show();
                                initData();
                            }
                        }, 50);
                    }
                });
            }
            return;
        }
        ApiConfig.get().loadConfig(useCacheConfig, new ApiConfig.LoadConfigCallback() {
            @Override
            public void notice(String msg) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(HomeActivity.this, msg, Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void success() {
                dataInitOk = true;
                if (ApiConfig.get().getSpider().isEmpty()) {
                    jarInitOk = true;
                }
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        initData();
                    }
                }, 50);
            }

            @Override
            public void error(String msg) {
                if (msg.equalsIgnoreCase("-1")) {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            dataInitOk = true;
                            jarInitOk = true;
                            initData();
                        }
                    });
                    return;
                }
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isActivityUnavailable()) {
                            return;
                        }
                        if (mConfigErrorDialog == null)
                            mConfigErrorDialog = new TipDialog(HomeActivity.this, msg, "重试", "取消", new TipDialog.OnListener() {
                                @Override
                                public void left() {
                                    mHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            dismissConfigErrorDialog();
                                            initData();
                                        }
                                    });
                                }

                                @Override
                                public void right() {
                                    dataInitOk = true;
                                    jarInitOk = true;
                                    mHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            dismissConfigErrorDialog();
                                            initData();
                                        }
                                    });
                                }

                                @Override
                                public void cancel() {
                                    dataInitOk = true;
                                    jarInitOk = true;
                                    mHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            dismissConfigErrorDialog();
                                            initData();
                                        }
                                    });
                                }
                            });
                        if (!mConfigErrorDialog.isShowing())
                            mConfigErrorDialog.show();
                    }
                });
            }
        }, this);
    }

    private void warmSearchSpidersOnce() {
        if (searchSpiderWarmStarted) return;
        searchSpiderWarmStarted = true;
        ApiConfig.get().warmSearchSpiders();
    }

    private void loadHomeSort(boolean keepCurrentContent) {
        SourceBean home = ApiConfig.get().getHomeSourceBean();
        homeSortLoading = keepCurrentContent;
        if (keepCurrentContent && home != null && home.getName() != null && !home.getName().isEmpty()) {
            previousHomeName = tvName.getText() == null ? null : tvName.getText().toString();
            tvName.setText(home.getName());
        }
        tvNameAnimation();
        if (home == null) {
            loadingSourceKey = null;
            if (!keepCurrentContent) showLoading();
            sourceViewModel.getSort(null);
            return;
        }
        loadingSourceKey = home.getKey();
        if (!keepCurrentContent) {
            showLoading();
        }
        sourceViewModel.getSort(loadingSourceKey);
    }

    private void initViewPager(AbsSortXml absXml) {
        if (sortAdapter == null) return;
        // 交给「热门推荐」页的数据：站点推荐 / 历史记录
        HotRecommendFragment.setData(resolveHotList(absXml));

        pageSorts.clear();
        pageSorts.addAll(getVisibleSortData());
        final List<MovieSort.SortData> sorts = new ArrayList<>(pageSorts);

        // 懒加载：这里只给出「第几页是什么」，Fragment 实例等 ViewPager 真正需要时才创建
        pageAdapter = new HomePageAdapter(getSupportFragmentManager(), sorts.size() + 1,
                new HomePageAdapter.PageProvider() {
                    @Override
                    public BaseLazyFragment create(int position) {
                        if (position <= 0) {
                            return HotRecommendFragment.newInstance();
                        }
                        return GridFragment.newInstance(sorts.get(position - 1));
                    }
                });
        try {
            java.lang.reflect.Field field = ViewPager.class.getDeclaredField("mScroller");
            field.setAccessible(true);
            FixedSpeedScroller scroller = new FixedSpeedScroller(mContext, new android.view.animation.AccelerateInterpolator());
            field.set(mViewPager, scroller);
            scroller.setmDuration(300);
        } catch (Exception e) {
        }
        mViewPager.setPageTransformer(true, new DefaultTransformer());
        // 只保留当前页左右各一页；滑出范围的页面会被 detach，视图树和海报一起释放
        mViewPager.setOffscreenPageLimit(1);
        mViewPager.setAdapter(pageAdapter);
        if (currentSelected < 0 || currentSelected >= pageAdapter.getCount()) {
            currentSelected = 0;
        }
        mViewPager.setCurrentItem(currentSelected, false);
    }

    /** 「热门推荐」页的数据源：0 豆瓣热播 / 1 站点推荐走接口列表，2 走本地历史 */
    private List<Movie.Video> resolveHotList(AbsSortXml absXml) {
        if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 2) {
            return buildHistoryList();
        }
        List<Movie.Video> rec = absXml != null ? absXml.videoList : null;
        // 「热门推荐」是首页默认落地页，空着很难看。
        // 不少数据源的 homeContent 并不返回推荐列表，这种情况下退回到本地观看历史，
        // 至少让首屏有内容可点；历史也为空时才显示「暂无推荐内容」。
        if (rec == null || rec.isEmpty()) {
            return buildHistoryList();
        }
        return rec;
    }

    /** 本地观看历史 → 首页卡片。也用作「热门推荐」无推荐内容时的兜底 */
    private List<Movie.Video> buildHistoryList() {
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

    private void clearHomePages() {
        mHandler.removeCallbacks(mDataRunnable);
        currentSelected = 0;
        pageSorts.clear();
        if (pageAdapter != null) {
            mViewPager.setAdapter(null);
            pageAdapter.removeAll();
            pageAdapter = null;
        }
    }

    private void updateSortData(List<MovieSort.SortData> newSortData) {
        if (newSortData == null) {
            newSortData = new ArrayList<>();
        }
        sortSnapshot = new ArrayList<>(newSortData);
        sortAdapter.setNewData(newSortData);
        setupTabs();
    }

    /** 首页分类快照，供「首页标签」设置页读取（分类里带 filters，不适合走 Intent 序列化） */
    private static List<MovieSort.SortData> sortSnapshot = new ArrayList<>();

    public static List<MovieSort.SortData> getSortSnapshot() {
        return sortSnapshot;
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public void onBackPressed() {
        if (homeSortLoading) {
            cancelHomeSortLoading();
            return;
        }
        if (isLoading()) {
            refreshEmpty();
            return;
        }
        // 分类/我的 页面：返回键先回到首页
        if (currentBottomTab != 0) {
            selectBottomTab(0);
            return;
        }
        if (pageAdapter == null || this.currentSelected >= pageAdapter.getCount() || this.currentSelected < 0) {
            doExit();
            return;
        }

        BaseLazyFragment baseLazyFragment = pageAdapter.getFragment(this.currentSelected);
        if (baseLazyFragment instanceof GridFragment) {
            GridFragment grid = (GridFragment) baseLazyFragment;
            if (grid.restoreView()) {
                return;
            } else if (this.currentSelected != 0) {
                selectPage(0);
            } else {
                doExit();
            }
        } else if (baseLazyFragment instanceof HotRecommendFragment
                && ((HotRecommendFragment) baseLazyFragment).scrollToTop()) {
            // 热门推荐页已经上滑，先回到顶部
        } else {
            doExit();
        }
    }

    private void doExit() {
        if (System.currentTimeMillis() - mExitTime < 2000) {
            AppManager.getInstance().finishAllActivity();
            unregisterEventBus();
            ControlManager.get().stopServer();
            finish();
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        } else {
            mExitTime = System.currentTimeMillis();
            Toast.makeText(mContext, "再按一次返回键退出应用", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshTopInfoTextSize();
        mHandler.post(mRunnable);
        applyHomeTabChange();
    }

    /** 从「界面偏好 → 首页标签」返回后，按新配置重建页签与页面 */
    private void applyHomeTabChange() {
        // 首页数据还没回来时先不动，等下一次 onResume 或首页加载完成后再应用
        if (sortAdapter == null || sortAdapter.getData().isEmpty()) return;
        if (!HomeTabConfig.consumeChanged()) return;
        clearHomePages();
        setupTabs();
        initViewPager(lastAbsXml);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mRunnable);
    }

    private void refreshTopInfoTextSize() {
        if (tvName == null || tvDate == null) {
            return;
        }
        tvName.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.ts_20));
        tvDate.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.ts_14));
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_PUSH_URL) {
            if (ApiConfig.get().getSource("push_agent") != null) {
                Intent newIntent = new Intent(mContext, DetailActivity.class);
                newIntent.putExtra("id", (String) event.obj);
                newIntent.putExtra("sourceKey", "push_agent");
                newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                HomeActivity.this.startActivity(newIntent);
            }
        } else if (event.type == RefreshEvent.TYPE_HOME_SOURCE_CHANGE) {
            refreshHome(false);
        }
    }

    private final Runnable mDataRunnable = new Runnable() {
        @Override
        public void run() {
        }
    };

    @Override
    protected void onDestroy() {
        dismissHomeDialogs();
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
        unregisterEventBus();
        if (isFinishing()) {
            ControlManager.get().stopServer();
        }
    }

    private void unregisterEventBus() {
        if (eventBusRegistered) {
            EventBus.getDefault().unregister(this);
            eventBusRegistered = false;
        }
    }

    private SelectDialog<SourceBean> mSiteSwitchDialog;

    void showSiteSwitch() {
        if (isActivityUnavailable()) return;
        List<SourceBean> sites = ApiConfig.get().getSwitchSourceBeanList();
        if (sites.isEmpty()) return;
        int select = sites.indexOf(ApiConfig.get().getHomeSourceBean());
        if (select < 0 || select >= sites.size()) select = 0;
        if (mSiteSwitchDialog == null) {
            mSiteSwitchDialog = new SelectDialog<>(HomeActivity.this);
            RecyclerView recyclerView = mSiteSwitchDialog.findViewById(R.id.list);
            ConstraintLayout cl_root = mSiteSwitchDialog.findViewById(R.id.cl_root);
            if (cl_root != null) {
                ViewGroup.LayoutParams clp = cl_root.getLayoutParams();
                if (clp != null) {
                    clp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.85f);
                }
            }
        }
        mSiteSwitchDialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<SourceBean>() {
            @Override
            public void click(SourceBean value, int pos) {
                dismissSiteSwitchDialog();
                previousHomeSource = ApiConfig.get().getHomeSourceBean();
                ApiConfig.get().setSourceBean(value);
                refreshHome(false);
            }

            @Override
            public String getDisplay(SourceBean val) {
                return val.getName();
            }
        }, new DiffUtil.ItemCallback<SourceBean>() {
            @Override
            public boolean areItemsTheSame(@NonNull SourceBean oldItem, @NonNull SourceBean newItem) {
                return oldItem == newItem;
            }

            @Override
            public boolean areContentsTheSame(@NonNull SourceBean oldItem, @NonNull SourceBean newItem) {
                return oldItem.getKey().equals(newItem.getKey());
            }
        }, sites, select);
        if (!mSiteSwitchDialog.isShowing())
            mSiteSwitchDialog.show();
    }

    private void refreshHome() {
        refreshHome(true);
    }

    private void refreshHomeSort() {
        SourceBean home = ApiConfig.get().getHomeSourceBean();
        if (home != null) {
            SourceViewModel.clearSortCache(home.getKey());
        }
        refreshHome(false);
    }

    private void refreshHome(final boolean restart) {
        if (Thread.currentThread() != android.os.Looper.getMainLooper().getThread()) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    refreshHome(restart);
                }
            });
            return;
        }
        if (isActivityUnavailable()) {
            return;
        }
        dismissHomeDialogs();
        if (!restart) {
            loadHomeSort(true);
            return;
        }
        Intent intent = new Intent(getApplicationContext(), HomeActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Bundle bundle = new Bundle();
        bundle.putBoolean("useCache", true);
        intent.putExtras(bundle);
        HomeActivity.this.startActivity(intent);
    }

    private boolean isActivityUnavailable() {
        return isFinishing() || (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed());
    }

    private void dismissHomeDialogs() {
        dismissConfigErrorDialog();
        dismissSiteSwitchDialog();
    }

    private void dismissConfigErrorDialog() {
        if (mConfigErrorDialog != null) {
            if (mConfigErrorDialog.isShowing()) {
                mConfigErrorDialog.dismiss();
            }
            mConfigErrorDialog = null;
        }
    }

    private void dismissSiteSwitchDialog() {
        if (mSiteSwitchDialog != null) {
            if (mSiteSwitchDialog.isShowing()) {
                mSiteSwitchDialog.dismiss();
            }
            mSiteSwitchDialog = null;
        }
    }

    private void refreshEmpty() {
        loadingSourceKey = null;
        showSuccess();
        cancelHomeSortLoading();
        clearHomePages();
        sortAdapter.setNewData(DefaultConfig.adjustSort(ApiConfig.get().getHomeSourceBean().getKey(), new ArrayList<>(), false));
        setupTabs();
        initViewPager(null);
        tvName.clearAnimation();
    }

    private void cancelHomeSortLoading() {
        homeSortLoading = false;
        loadingSourceKey = null;
        tvName.clearAnimation();
        if (previousHomeSource != null) {
            ApiConfig.get().setSourceBean(previousHomeSource);
        }
        if (!TextUtils.isEmpty(previousHomeName)) {
            tvName.setText(previousHomeName);
        }
        previousHomeSource = null;
        previousHomeName = null;
    }

    private void tvNameAnimation() {
        tvName.clearAnimation();
        AlphaAnimation blinkAnimation = new AlphaAnimation(0.0f, 1.0f);
        blinkAnimation.setDuration(500);
        blinkAnimation.setStartOffset(20);
        blinkAnimation.setRepeatMode(Animation.REVERSE);
        blinkAnimation.setRepeatCount(Animation.INFINITE);
        tvName.startAnimation(blinkAnimation);
    }
}
