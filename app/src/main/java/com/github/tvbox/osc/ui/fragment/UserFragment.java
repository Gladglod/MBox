package com.github.tvbox.osc.ui.fragment;

import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.ui.activity.ApiSettingActivity;
import com.github.tvbox.osc.ui.activity.CollectActivity;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.HistoryActivity;
import com.github.tvbox.osc.ui.activity.MineSettingActivity;
import com.github.tvbox.osc.ui.adapter.ContinueSquareAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import java.util.List;

/**
 * 「我的」页面：条状菜单。
 * 「播放历史」这一行下面直接挂一排「继续观看」小方块（横向滑动），相当于它的子模块。
 */
public class UserFragment extends BaseLazyFragment {

    private static final int ACTION_SOURCE = 0;   // 数据源地址
    private static final int ACTION_SECTION = 1;  // 打开 MineSettingActivity 的某个分组
    private static final int ACTION_HISTORY = 2;  // 播放历史
    private static final int ACTION_COLLECT = 3;  // 收藏

    private static final class MenuEntry {
        final String title;
        final int icon;
        final int action;
        final String section;

        MenuEntry(String title, int icon, int action, String section) {
            this.title = title;
            this.icon = icon;
            this.action = action;
            this.section = section;
        }
    }

    private static final MenuEntry E_SOURCE = new MenuEntry("数据源地址", R.drawable.ic_mine_source, ACTION_SOURCE, null);
    private static final MenuEntry E_THEME = new MenuEntry("外观主题", R.drawable.ic_mine_theme, ACTION_SECTION, MineSettingActivity.SECTION_APPEARANCE);
    private static final MenuEntry E_HISTORY = new MenuEntry("播放历史", R.drawable.ic_mine_history, ACTION_HISTORY, null);
    private static final MenuEntry E_COLLECT = new MenuEntry("收藏", R.drawable.icon_collect, ACTION_COLLECT, null);
    private static final MenuEntry E_PLAY = new MenuEntry("播放设定", R.drawable.ic_mine_play, ACTION_SECTION, MineSettingActivity.SECTION_PLAY);
    private static final MenuEntry E_UI = new MenuEntry("界面偏好", R.drawable.ic_mine_ui, ACTION_SECTION, MineSettingActivity.SECTION_UI);
    private static final MenuEntry E_GESTURE = new MenuEntry("播放手势", R.drawable.ic_mine_gesture, ACTION_SECTION, MineSettingActivity.SECTION_GESTURE);
    private static final MenuEntry E_DANMU = new MenuEntry("弹幕设定", R.drawable.ic_mine_danmu, ACTION_SECTION, MineSettingActivity.SECTION_DANMU);
    private static final MenuEntry E_ABOUT = new MenuEntry("关于", R.drawable.ic_mine_about, ACTION_SECTION, MineSettingActivity.SECTION_ABOUT);

    /**
     * 分组（每组一张卡片，组间留白）。分组标题为 null 表示该组不显示组头。
     * 采用 Apple Settings 的「分组卡片 + 组头」结构，信息层级更清晰。
     */
    private static final MenuEntry[][] MENU_GROUPS = {
            {E_SOURCE, E_THEME},
            {E_HISTORY, E_COLLECT},
            {E_PLAY, E_UI, E_GESTURE, E_DANMU},
            {E_ABOUT}
    };
    private static final String[] GROUP_TITLES = {null, "媒体库", "播放设置", null};

    private static final int CONTINUE_LIMIT = 12;

    private TextView tvSourceValue;
    private NestedScrollView userScroll;
    private RecyclerView continueStrip;
    private ContinueSquareAdapter continueAdapter;

    public static UserFragment newInstance() {
        return new UserFragment();
    }

    /**
     * 兼容旧调用（首页「站点推荐」列表已不再使用，参数忽略）。
     */
    public static UserFragment newInstance(List<Movie.Video> recVod) {
        return new UserFragment();
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_user;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
    }

    @Override
    protected void onFragmentResume() {
        super.onFragmentResume();
        refreshSourceValue();
        loadContinueList();
    }

    @Override
    protected void init() {
        userScroll = findViewById(R.id.userScroll);
        buildMenu();
        loadContinueList();
    }

    /* ======================== 条状菜单 ======================== */

    private void buildMenu() {
        LinearLayout container = findViewById(R.id.menuCard);
        if (container == null) return;
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(mContext);
        for (int g = 0; g < MENU_GROUPS.length; g++) {
            boolean hasHeader = GROUP_TITLES[g] != null;
            if (hasHeader) {
                container.addView(inflater.inflate(R.layout.item_group_header, container, false));
            }
            LinearLayout card = new LinearLayout(mContext);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.bg_phone_card);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                card.setClipToOutline(true); // 行内水波纹跟随卡片圆角
            }
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (g > 0 && !hasHeader) {
                clp.topMargin = dp(18); // 无组头时用上间距区分分组
            }
            card.setLayoutParams(clp);

            MenuEntry[] group = MENU_GROUPS[g];
            for (int i = 0; i < group.length; i++) {
                if (i > 0) card.addView(makeDivider());
                final MenuEntry entry = group[i];
                View row = inflater.inflate(R.layout.item_user_menu, card, false);
                ImageView menuIcon = row.findViewById(R.id.ivMenuIcon);
                menuIcon.setImageResource(entry.icon);
                // 图标是白色矢量图，日间模式下必须换成主题色，否则白底白图看不见
                menuIcon.setColorFilter(getResources().getColor(R.color.ui_icon));
                ((TextView) row.findViewById(R.id.tvMenuName)).setText(entry.title);
                TextView value = row.findViewById(R.id.tvMenuValue);
                if (entry.action == ACTION_SOURCE) {
                    tvSourceValue = value;
                    refreshSourceValue();
                } else {
                    value.setVisibility(View.GONE);
                }
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        FastClickCheckUtil.check(v);
                        openMenu(entry);
                    }
                });
                card.addView(row);
                // 「播放历史」下面挂「继续观看」小方块
                if (entry.action == ACTION_HISTORY) {
                    card.addView(makeContinueStrip());
                }
            }
            container.addView(card);
        }
    }

    /** 继续观看：横向滑动的小方块列表 */
    private View makeContinueStrip() {
        LinearLayout wrap = new LinearLayout(mContext);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wrap.setLayoutParams(wlp);
        wrap.setPadding(0, 0, 0, dp(4));

        TextView tip = new TextView(mContext);
        tip.setText("继续观看");
        tip.setTextColor(getResources().getColor(R.color.ui_muted));
        tip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.ts_14));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(14);
        tlp.topMargin = dp(2);
        tlp.bottomMargin = dp(6);
        wrap.addView(tip, tlp);

        continueStrip = new RecyclerView(mContext);
        continueStrip.setLayoutManager(new LinearLayoutManager(mContext, LinearLayoutManager.HORIZONTAL, false));
        continueStrip.setOverScrollMode(View.OVER_SCROLL_NEVER);
        continueStrip.setClipToPadding(false);
        continueStrip.setPadding(dp(14), 0, dp(14), 0);
        continueAdapter = new ContinueSquareAdapter();
        continueStrip.setAdapter(continueAdapter);
        continueAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                if (position < 0 || position >= continueAdapter.getData().size()) return;
                VodInfo vodInfo = continueAdapter.getData().get(position);
                if (vodInfo == null) return;
                Bundle bundle = new Bundle();
                bundle.putString("id", vodInfo.id);
                bundle.putString("sourceKey", vodInfo.sourceKey);
                bundle.putString("title", vodInfo.name);
                bundle.putString("picture", vodInfo.pic);
                jumpActivity(DetailActivity.class, bundle);
            }
        });
        wrap.addView(continueStrip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.setVisibility(View.GONE);
        continueWrap = wrap;
        return wrap;
    }

    private View continueWrap;

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private View makeDivider() {
        View divider = new View(mContext);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f)));
        // 左侧缩进与标题文字对齐（padding 10 + icon 20 + gap 8），右侧留白：Apple 分组列表的分隔线细节
        lp.leftMargin = dp(38);
        lp.rightMargin = dp(10);
        // 用语义色，日间为浅黑细线、夜间为浅白细线（原来写死 0x14FFFFFF，日间白卡上完全看不见）
        divider.setBackgroundColor(getResources().getColor(R.color.ui_divider));
        divider.setLayoutParams(lp);
        return divider;
    }

    private void openMenu(MenuEntry entry) {
        switch (entry.action) {
            case ACTION_SOURCE:
                jumpActivity(ApiSettingActivity.class);
                break;
            case ACTION_HISTORY:
                jumpActivity(HistoryActivity.class);
                break;
            case ACTION_COLLECT:
                jumpActivity(CollectActivity.class);
                break;
            case ACTION_SECTION:
            default:
                Bundle bundle = new Bundle();
                bundle.putString(MineSettingActivity.EXTRA_SECTION, entry.section);
                jumpActivity(MineSettingActivity.class, bundle);
                break;
        }
    }

    /* ======================== 数据源地址 ======================== */

    private void refreshSourceValue() {
        if (tvSourceValue == null) return;
        String api = Hawk.get(HawkConfig.API_URL, "");
        tvSourceValue.setText(TextUtils.isEmpty(api) ? "未配置" : api);
    }

    /* ======================== 继续观看 ======================== */

    private void loadContinueList() {
        if (continueAdapter == null || continueWrap == null) return;
        List<VodInfo> records = RoomDataManger.getAllVodRecord(CONTINUE_LIMIT);
        if (records == null || records.isEmpty()) {
            continueWrap.setVisibility(View.GONE);
            continueAdapter.setNewData(new ArrayList<VodInfo>());
            return;
        }
        continueWrap.setVisibility(View.VISIBLE);
        continueAdapter.setNewData(records);
    }

    /* ======================== 返回键回顶 ======================== */

    /**
     * 供 HomeActivity 的返回键逻辑调用：列表已上滑时回到顶部并消费事件。
     */
    public boolean scrollToTop() {
        if (userScroll == null) return false;
        if (userScroll.getScrollY() > 0) {
            userScroll.scrollTo(0, 0);
            return true;
        }
        return false;
    }
}
