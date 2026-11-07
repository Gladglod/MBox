package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.DanmakuApi;
import com.github.tvbox.osc.bean.DanmuGroup;
import com.github.tvbox.osc.bean.DanmuSearchResult;
import com.github.tvbox.osc.ui.adapter.DanmuGroupAdapter;
import com.github.tvbox.osc.ui.adapter.SearchDanmuAdapter;
import com.github.tvbox.osc.util.DanmuTitleUtil;
import com.github.tvbox.osc.util.FastClickCheckUtil;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索弹幕：结果最多分三层。
 * 第一层是合并后的剧名（去掉 from 站点后缀），点击后：
 * 如果同剧有多个站点/多部的，先进第二层看各部完整剧名，再点进第三层看每一集；
 * 只有一部的直接进每集列表。与当前播放集数一致的那一条会标「疑似此集」，自动滚到它。
 * 选中某一集后留在窗内显示「正在加载弹幕：xxx」，可取消；成功后自动关窗并挂载。
 */
public class SearchDanmuDialog extends BaseDialog {
    /** 层级：0 一级剧名分组 / 1 二级同名各部 / 2 每集列表 */
    private static final int LEVEL_GROUP = 0;
    private static final int LEVEL_CHILDREN = 1;
    private static final int LEVEL_EPISODES = 2;

    /** 状态条右侧按钮的动作：无 / 取消正在加载的弹幕请求 / 卸载已挂载的弹幕 */
    private static final int ACTION_NONE = 0;
    private static final int ACTION_CANCEL_LOAD = 1;
    private static final int ACTION_UNLOAD = 2;

    private RecyclerView listView;
    private DanmuGroupAdapter groupAdapter;
    private SearchDanmuAdapter episodeAdapter;
    private EditText searchInput;
    private LinearLayout statusBar;
    private TextView statusText;
    private TextView statusCancel;
    private int statusAction = ACTION_NONE;
    private DanmuProvider danmuProvider;
    private ImageView backBtn;
    private TextView titleView;
    private DanmuLoader danmuLoader;
    private String episode = "";
    /** 当前搜索用的剧集名，用于过滤与标题清洗 */
    private String keyword = "";
    /** 当前播放的集数，0 表示解析不出来 */
    private int currentEpisode = 0;
    private int level = LEVEL_GROUP;
    /** 当前打开的一级分组与二级分组（用于回退） */
    private DanmuGroup currentGroup = null;
    private DanmuGroup currentChild = null;
    private List<DanmuGroup> groups = new ArrayList<>();

    public SearchDanmuDialog(@NonNull @NotNull Context context) {
        super(context);
        if (context instanceof Activity) {
            setOwnerActivity((Activity) context);
        }
        setContentView(R.layout.dialog_search_danmu);
        initView();
    }

    private void initView() {
        listView = findViewById(R.id.mGridView);
        searchInput = findViewById(R.id.input);
        titleView = findViewById(R.id.dialog_title);
        backBtn = findViewById(R.id.iv_back);
        statusBar = findViewById(R.id.statusBar);
        statusText = findViewById(R.id.statusText);
        statusCancel = findViewById(R.id.statusCancel);
        TextView searchButton = findViewById(R.id.inputSubmit);
        ImageView closeBtn = findViewById(R.id.iv_close);
        groupAdapter = new DanmuGroupAdapter();
        episodeAdapter = new SearchDanmuAdapter();

        listView.setHasFixedSize(true);
        listView.setLayoutManager(new LinearLayoutManager(getContext(), LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(groupAdapter);

        // 第一层 / 中间层共用 groupAdapter，点谁由 level 决定
        groupAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                openGroupAt(position);
            }
        });
        // item 内部的 groupItem 自己 clickable，会吃掉 item 点击，这里必须再兜一层，
        // 否则第一层「点不进去」
        groupAdapter.setOnItemChildClickListener(new BaseQuickAdapter.OnItemChildClickListener() {
            @Override
            public void onItemChildClick(BaseQuickAdapter adapter, View view, int position) {
                if (view.getId() != R.id.groupItem) return;
                FastClickCheckUtil.check(view);
                openGroupAt(position);
            }
        });
        // 集列表：点某一集 → 状态条显示正在加载哪一条，可取消
        episodeAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                if (position >= 0 && position < episodeAdapter.getData().size()) {
                    loadDanmu(episodeAdapter.getData().get(position));
                }
            }
        });
        // item 内部 danmuItem 自己注册了子点击，这里也要接住，否则点了没反应
        episodeAdapter.setOnItemChildClickListener(new BaseQuickAdapter.OnItemChildClickListener() {
            @Override
            public void onItemChildClick(BaseQuickAdapter adapter, View view, int position) {
                if (view.getId() != R.id.danmuItem) return;
                FastClickCheckUtil.check(view);
                if (position >= 0 && position < episodeAdapter.getData().size()) {
                    loadDanmu(episodeAdapter.getData().get(position));
                }
            }
        });

        if (backBtn != null) {
            backBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    backToParent();
                }
            });
        }
        if (searchButton != null) {
            searchButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    FastClickCheckUtil.check(view);
                    search(searchInput.getText().toString().trim());
                }
            });
        }
        if (statusCancel != null) {
            statusCancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FastClickCheckUtil.check(v);
                    if (statusAction == ACTION_UNLOAD) {
                        // 卸载已挂载的弹幕（清掉播放器上的弹幕层）
                        if (danmuProvider != null) danmuProvider.unloadDanmu();
                        Toast.makeText(getContext(), "已卸载当前弹幕", Toast.LENGTH_SHORT).show();
                    } else {
                        // 取消正在拉取的弹幕请求
                        DanmakuApi.cancel();
                        Toast.makeText(getContext(), "已取消加载弹幕", Toast.LENGTH_SHORT).show();
                    }
                    statusAction = ACTION_NONE;
                    updateIdleStatus();
                }
            });
        }
        if (closeBtn != null) {
            closeBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    DanmakuApi.cancel();
                    dismiss();
                }
            });
        }
        // 点卡片外空白直接关窗（卡片自身 clickable，不会误触发）
        View root = findViewById(R.id.dialog_root);
        if (root != null) {
            root.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                }
            });
        }
        applyResponsiveSize();
    }

    /** 按当前层级把 groupAdapter 的点击分发到对应的数据集 */
    private void openGroupAt(int position) {
        if (level == LEVEL_GROUP) {
            if (position >= 0 && position < groups.size()) {
                openGroup(groups.get(position));
            }
        } else if (level == LEVEL_CHILDREN && currentGroup != null) {
            List<DanmuGroup> children = currentGroup.getChildren();
            if (position >= 0 && position < children.size()) {
                openEpisodes(currentGroup, children.get(position));
            }
        }
    }

    /** 卡片贴右边显示（不居中），尺寸按屏幕比例收窄 */
    private void applyResponsiveSize() {
        try {
            android.util.DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
            View card = findViewById(R.id.dialog_card);
            View list = findViewById(R.id.list_container);
            if (card == null) return;
            int width = Math.min((int) (dm.widthPixels * 0.66f), dp(380));
            width = Math.max(width, dp(250));
            int height = Math.min((int) (dm.heightPixels * 0.72f), dp(520));
            ViewGroup.LayoutParams lp = card.getLayoutParams();
            lp.width = width;
            lp.height = height;
            if (lp instanceof LinearLayout.LayoutParams) {
                ((LinearLayout.LayoutParams) lp).gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            }
            card.setLayoutParams(lp);

            if (list != null && list.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                // 列表占满卡片剩余高度
                LinearLayout.LayoutParams listLp = (LinearLayout.LayoutParams) list.getLayoutParams();
                listLp.height = 0;
                listLp.weight = 1;
                list.setLayoutParams(listLp);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private int dp(float value) {
        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }

    @Override
    public void show() {
        super.show();
        // 每次打开都刷新「当前弹幕」状态条，能实际看到挂载情况
        updateIdleStatus();
        // Theme.Dialog 默认窗口是 wrap_content，根布局的 match_parent 会被压成内容宽度，
        // 面板就没法靠右贴边。这里把窗口撑满屏幕，靠右由布局里的 gravity 决定。
        try {
            android.view.Window window = getWindow();
            if (window != null) {
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    public void setEpisode(String episode) {
        this.episode = episode == null ? "" : episode;
    }

    public void setSearchWord(String word) {
        String searchWord = word == null ? "" : word.trim();
        searchInput.setText(searchWord);
        searchInput.setSelection(searchWord.length());
        searchInput.requestFocus();
        search(searchWord);
    }

    public void setDanmuLoader(DanmuLoader danmuLoader) {
        this.danmuLoader = danmuLoader;
    }

    public void setDanmuProvider(DanmuProvider provider) {
        this.danmuProvider = provider;
        updateIdleStatus();
    }

    private void search(String word) {
        groups = new ArrayList<>();
        currentGroup = null;
        currentChild = null;
        level = LEVEL_GROUP;
        groupAdapter.setNewData(new ArrayList<DanmuGroup>());
        episodeAdapter.setNewData(new ArrayList<DanmuSearchResult>());
        if (TextUtils.isEmpty(word)) {
            Toast.makeText(getContext(), "输入内容不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        keyword = word;
        currentEpisode = DanmuTitleUtil.episodeOf(episode, "");
        showLoading();
        DanmakuApi.searchList(word, episode, new DanmakuApi.SearchListCallback() {
            @Override
            public void onSuccess(List<DanmuSearchResult> results) {
                showResults(results);
            }

            @Override
            public void onError(String message) {
                showResults(new ArrayList<DanmuSearchResult>());
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 选中某一集：不关窗，状态条显示「正在加载弹幕：xxx」，失败/取消可回去继续挑 */
    private void loadDanmu(DanmuSearchResult result) {
        if (result == null) return;
        showDanmuLoading(result);
        DanmakuApi.loadSearchResult(result, new DanmakuApi.SearchResultCallback() {
            @Override
            public void onSuccess(String danmu) {
                if (danmuLoader != null) danmuLoader.loadDanmu(danmu, result.getName());
                dismiss();
                Toast.makeText(getContext(), "弹幕已加载", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                // 回到列表让用户换一条再试，同时恢复「当前弹幕」显示
                updateIdleStatus();
                Toast.makeText(getContext(), "弹幕加载失败：" + message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 空闲态状态条：有已挂载弹幕就显示「当前弹幕：xxx」+「卸载」，没有则整条隐藏。
     * 重新打开搜索框时也会走到这里，能看到实际挂载的弹幕。
     */
    private void updateIdleStatus() {
        String name = danmuProvider == null ? "" : danmuProvider.getLoadedDanmuName();
        if (TextUtils.isEmpty(name)) {
            if (statusBar != null) {
                statusAction = ACTION_NONE;
                if (statusCancel != null) statusCancel.setVisibility(View.GONE);
                statusBar.setVisibility(View.GONE);
            }
            if (listView != null) listView.setVisibility(View.VISIBLE);
            return;
        }
        statusAction = ACTION_UNLOAD;
        if (statusText != null) statusText.setText("当前弹幕：" + name);
        if (statusCancel != null) {
            statusCancel.setText("卸载");
            statusCancel.setVisibility(View.VISIBLE);
        }
        if (statusBar != null) statusBar.setVisibility(View.VISIBLE);
        if (listView != null) listView.setVisibility(View.VISIBLE);
    }

    /** 状态条通用展示：action 为 true 时右侧按钮按 statusText 的用途（加载取消/卸载）复用 */
    private void showStatus(String text, boolean action) {
        if (statusBar == null) return;
        if (statusText != null) statusText.setText(text);
        if (statusCancel != null) {
            statusCancel.setVisibility(action ? View.VISIBLE : View.GONE);
        }
        statusBar.setVisibility(View.VISIBLE);
    }

    private void showDanmuLoading(DanmuSearchResult result) {
        if (statusText != null) statusText.setText("正在加载弹幕：" + result.getName());
        statusAction = ACTION_CANCEL_LOAD;
        if (statusCancel != null) {
            statusCancel.setText("取消");
            statusCancel.setVisibility(View.VISIBLE);
        }
        if (statusBar != null) statusBar.setVisibility(View.VISIBLE);
        if (listView != null) listView.setVisibility(View.GONE);
    }

    private void showLoading() {
        showStatus("正在搜索弹幕…", false);
        statusAction = ACTION_NONE;
        // 新一轮搜索：恢复到第一层展示，避免停留在上次的层级
        currentGroup = null;
        currentChild = null;
        level = LEVEL_GROUP;
        if (listView != null) listView.setVisibility(View.GONE);
    }

    private void showResults(List<DanmuSearchResult> results) {
        if (results == null) results = new ArrayList<>();
        // 只做去重与「集数 / 来源」旁路解析，标题原样保留；带剧名的按剧聚合并同名并组，点进去看每一集
        groups = DanmuTitleUtil.group(DanmuTitleUtil.refine(results, keyword), keyword);
        showGroupList();
        // 搜索结束后恢复「当前弹幕：xxx」的挂载状态显示
        updateIdleStatus();
        if (groups.isEmpty()) {
            Toast.makeText(getContext(), "未查询到匹配弹幕", Toast.LENGTH_SHORT).show();
        }
    }

    /** 第一层：合并后的剧名列表 */
    private void showGroupList() {
        level = LEVEL_GROUP;
        currentGroup = null;
        currentChild = null;
        if (titleView != null) titleView.setText("搜索弹幕");
        if (backBtn != null) backBtn.setVisibility(View.GONE);
        groupAdapter.setCurrentEpisode(currentEpisode);
        listView.setAdapter(groupAdapter);
        groupAdapter.setNewData(groups);
        scrollToGuessGroup();
    }

    private void openGroup(DanmuGroup group) {
        if (group == null) return;
        // 同名多站的：先看中部层列出的各部完整剧名；只有一部分的直接进剧集
        if (group.hasChildren()) {
            openChildrenList(group);
        } else {
            openEpisodes(group, null);
        }
    }

    /** 中部层：同名剧下的各部（from iqiyi / from youku…） */
    private void openChildrenList(DanmuGroup group) {
        currentGroup = group;
        currentChild = null;
        level = LEVEL_CHILDREN;
        if (titleView != null) titleView.setText(group.getShowName());
        if (backBtn != null) backBtn.setVisibility(View.VISIBLE);
        groupAdapter.setCurrentEpisode(currentEpisode);
        listView.setAdapter(groupAdapter);
        groupAdapter.setNewData(group.getChildren());
        scrollToGuessChildren(group);
    }

    /** 集列表：child 为空表示一级分组自己就是整部剧 */
    private void openEpisodes(DanmuGroup group, DanmuGroup child) {
        currentGroup = group;
        currentChild = child;
        level = LEVEL_EPISODES;
        if (titleView != null) {
            String name = child != null ? child.getShowName() : group.getShowName();
            titleView.setText(TextUtils.isEmpty(name) ? "弹幕列表" : name);
        }
        if (backBtn != null) backBtn.setVisibility(View.VISIBLE);
        episodeAdapter.setCurrentEpisode(currentEpisode);
        listView.setAdapter(episodeAdapter);
        episodeAdapter.setNewData(child != null ? child.getItems() : group.getItems());
        scrollToGuessEpisode(child != null ? child : group);
    }

    /** 按返回键 / 返回箭头逐层回退 */
    private void backToParent() {
        if (level == LEVEL_EPISODES) {
            DanmuGroup group = currentGroup;
            if (group != null && group.hasChildren()) {
                openChildrenList(group);
            } else {
                showGroupList();
            }
            return;
        }
        showGroupList();
    }

    /** 搜索完成后自动滚到「含当前集」的分组 */
    private void scrollToGuessGroup() {
        if (currentEpisode <= 0) return;
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).containsEpisode(currentEpisode)) {
                scrollTo(i);
                return;
            }
        }
    }

    /** 同名各部列表中滚到「含当前集」的那一部 */
    private void scrollToGuessChildren(DanmuGroup group) {
        if (currentEpisode <= 0) return;
        List<DanmuGroup> children = group.getChildren();
        for (int i = 0; i < children.size(); i++) {
            if (children.get(i).containsEpisode(currentEpisode)) {
                scrollTo(i);
                return;
            }
        }
    }

    /** 进入剧集列表后自动滚到「疑似此集」那一行 */
    private void scrollToGuessEpisode(DanmuGroup group) {
        if (group == null || currentEpisode <= 0) return;
        List<DanmuSearchResult> items = group.getItems();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getEpisode() == currentEpisode) {
                scrollTo(i);
                return;
            }
        }
    }

    private void scrollTo(final int index) {
        listView.post(new Runnable() {
            @Override
            public void run() {
                listView.scrollToPosition(index);
            }
        });
    }

    @Override
    public void onBackPressed() {
        // 分层时先逐层回退，回到第一层再按才关闭
        if (level != LEVEL_GROUP) {
            backToParent();
            return;
        }
        DanmakuApi.cancel();
        dismiss();
    }

    public interface DanmuLoader {
        /** danmu = 弹幕内容/地址；name = 搜索选中的条目名，供「当前弹幕：xxx」显示 */
        void loadDanmu(String danmu, String name);
    }

    /** 提供「当前已挂载弹幕」的状态与卸载能力（由播放页实现） */
    public interface DanmuProvider {
        /** 当前已挂载弹幕的显示名，空 = 没有挂载弹幕 */
        String getLoadedDanmuName();

        /** 卸载当前弹幕 */
        void unloadDanmu();
    }
}
