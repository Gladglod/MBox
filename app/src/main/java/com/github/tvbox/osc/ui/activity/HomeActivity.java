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
import com.github.tvbox.osc.ui.fragment.UserFragment;
import com.github.tvbox.osc.ui.tv.widget.DefaultTransformer;
import com.github.tvbox.osc.ui.tv.widget.FixedSpeedScroller;
import com.github.tvbox.osc.util.AppManager;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
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
    private final List<BaseLazyFragment> fragments = new ArrayList<>();
    private int currentSelected = 0;
    private SourceBean previousHomeSource;
    private String loadingSourceKey;
    private String previousHomeName;
    private boolean homeSortLoading = false;
    private boolean refreshHomeRec = false;
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

        tvName.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                jumpActivity(SettingActivity.class);
            }
        });
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
            }
        });

        initBottomNav();
        setLoadSir(this.topLayout);
    }

    private void selectPage(int position) {
        if (position < 0 || position >= fragments.size()) return;
        if (currentSelected == position) return;
        currentSelected = position;
        mViewPager.setCurrentItem(position, false);
        BaseLazyFragment baseLazyFragment = fragments.get(position);
        if (baseLazyFragment instanceof GridFragment && ((GridFragment) baseLazyFragment).shouldReloadOnSelect()) {
            ((GridFragment) baseLazyFragment).forceRefresh();
        }
    }

    private void setupTabs() {
        if (mTabLayout == null || sortAdapter == null) return;
        mTabLayout.removeAllTabs();
        List<MovieSort.SortData> data = sortAdapter.getData();
        for (MovieSort.SortData sortData : data) {
            mTabLayout.addTab(mTabLayout.newTab().setText(sortData.name));
        }
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
        currentBottomTab = tab;
        updateBottomNav(tab);
        if (tab == 0) {
            showHomeContent();
        } else if (tab == 1) {
            showSubFragment(CategoryFragment.newInstance(getCategoryList()));
        } else if (tab == 3) {
            showSubFragment(UserFragment.newInstance(lastRecList));
        }
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
        int on = getResources().getColor(R.color.color_02F8E1);
        int off = getResources().getColor(R.color.color_BBFFFFFF);
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
        ArrayList<MovieSort.SortData> list = new ArrayList<>();
        if (sortAdapter != null) {
            for (MovieSort.SortData data : sortAdapter.getData()) {
                if (data != null && !"my0".equals(data.id)) {
                    list.add(data);
                }
            }
        }
        return list;
    }

    @Override
    public void onCategorySelect(MovieSort.SortData sortData) {
        if (sortData == null) {
            return;
        }
        int index = -1;
        List<MovieSort.SortData> data = sortAdapter.getData();
        for (int i = 0; i < data.size(); i++) {
            if (data.get(i) != null && sortData.id != null && sortData.id.equals(data.get(i).id)) {
                index = i;
                break;
            }
        }
        if (mViewPager != null && index >= 0) {
            mViewPager.setCurrentItem(index, false);
        }
        selectBottomTab(0);
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
                updateHomeRec(absXml);
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
        if (sortAdapter.getData().size() > 0) {
            for (MovieSort.SortData data : sortAdapter.getData()) {
                if (data.id.equals("my0")) {
                    if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1 && absXml != null && absXml.videoList != null && absXml.videoList.size() > 0) {
                        fragments.add(UserFragment.newInstance(absXml.videoList));
                    } else {
                        fragments.add(UserFragment.newInstance(null));
                    }
                } else {
                    fragments.add(GridFragment.newInstance(data));
                }
            }
            pageAdapter = new HomePageAdapter(getSupportFragmentManager(), fragments);
            try {
                java.lang.reflect.Field field = ViewPager.class.getDeclaredField("mScroller");
                field.setAccessible(true);
                FixedSpeedScroller scroller = new FixedSpeedScroller(mContext, new android.view.animation.AccelerateInterpolator());
                field.set(mViewPager, scroller);
                scroller.setmDuration(300);
            } catch (Exception e) {
            }
            mViewPager.setPageTransformer(true, new DefaultTransformer());
            mViewPager.setAdapter(pageAdapter);
            mViewPager.setCurrentItem(currentSelected, false);
        }
    }

    private void clearHomePages() {
        mHandler.removeCallbacks(mDataRunnable);
        currentSelected = 0;
        if (pageAdapter != null) {
            mViewPager.setAdapter(null);
            pageAdapter.removeAll();
            pageAdapter = null;
        } else if (!fragments.isEmpty()) {
            fragments.clear();
        }
    }

    private void updateSortData(List<MovieSort.SortData> newSortData) {
        if (newSortData == null) {
            newSortData = new ArrayList<>();
        }
        List<MovieSort.SortData> oldSortData = sortAdapter.getData();
        if (oldSortData.isEmpty()
                || newSortData.isEmpty()
                || oldSortData.get(0) == null
                || newSortData.get(0) == null
                || !"my0".equals(oldSortData.get(0).id)
                || !"my0".equals(newSortData.get(0).id)) {
            sortAdapter.setNewData(newSortData);
            setupTabs();
            return;
        }
        int oldTailCount = oldSortData.size() - 1;
        if (oldTailCount > 0) {
            oldSortData.subList(1, oldSortData.size()).clear();
            sortAdapter.notifyItemRangeRemoved(1, oldTailCount);
        }
        if (newSortData.size() > 1) {
            oldSortData.addAll(newSortData.subList(1, newSortData.size()));
            sortAdapter.notifyItemRangeInserted(1, newSortData.size() - 1);
        }
        setupTabs();
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
        if (HawkConfig.hotVodDelete) {
            HawkConfig.hotVodDelete = false;
            UserFragment.homeHotVodAdapter.notifyDataSetChanged();
            return;
        }

        if (this.fragments.size() <= 0 || this.currentSelected >= this.fragments.size() || this.currentSelected < 0) {
            doExit();
            return;
        }

        BaseLazyFragment baseLazyFragment = this.fragments.get(this.currentSelected);
        if (baseLazyFragment instanceof GridFragment) {
            GridFragment grid = (GridFragment) baseLazyFragment;
            if (grid.restoreView()) {
                return;
            } else if (this.currentSelected != 0) {
                mViewPager.setCurrentItem(0, false);
            } else {
                doExit();
            }
        } else if (baseLazyFragment instanceof UserFragment && UserFragment.tvHotList.canScrollVertically(-1)) {
            UserFragment.tvHotList.scrollToPosition(0);
            mViewPager.setCurrentItem(0, false);
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
        refreshHomeRec = true;
        if (UserFragment.homeHotVodAdapter != null) {
            UserFragment.homeHotVodAdapter.setNewData(new ArrayList<Movie.Video>());
        }
        SourceBean home = ApiConfig.get().getHomeSourceBean();
        if (home != null) {
            SourceViewModel.clearSortCache(home.getKey());
        }
        refreshHome(false);
    }

    private void updateHomeRec(AbsSortXml absXml) {
        if (!refreshHomeRec) return;
        refreshHomeRec = false;
        if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) != 1) return;
        if (absXml == null || absXml.videoList == null || UserFragment.homeHotVodAdapter == null) return;
        UserFragment.homeHotVodAdapter.setNewData(absXml.videoList);
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
