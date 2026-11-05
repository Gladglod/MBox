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
import android.widget.ProgressBar;
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
 * 搜索弹幕：结果分两层。
 * 第一层是「剧集名 + 来源 + 共几集」，第二层才是每一集；
 * 与当前播放集数一致的那一集会标「疑似此集」，搜索完成后自动滚到它。
 */
public class SearchDanmuDialog extends BaseDialog {
    private RecyclerView listView;
    private DanmuGroupAdapter groupAdapter;
    private SearchDanmuAdapter episodeAdapter;
    private EditText searchInput;
    private ProgressBar loadingBar;
    private ImageView backBtn;
    private TextView titleView;
    private DanmuLoader danmuLoader;
    private String episode = "";
    /** 当前搜索用的剧集名，用于过滤与标题清洗 */
    private String keyword = "";
    /** 当前播放的集数，0 表示解析不出来 */
    private int currentEpisode = 0;
    private boolean showingEpisodes = false;
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
        loadingBar = findViewById(R.id.loadingBar);
        listView = findViewById(R.id.mGridView);
        searchInput = findViewById(R.id.input);
        titleView = findViewById(R.id.dialog_title);
        backBtn = findViewById(R.id.iv_back);
        TextView searchButton = findViewById(R.id.inputSubmit);
        ImageView closeBtn = findViewById(R.id.iv_close);
        groupAdapter = new DanmuGroupAdapter();
        episodeAdapter = new SearchDanmuAdapter();

        listView.setHasFixedSize(true);
        listView.setLayoutManager(new LinearLayoutManager(getContext(), LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(groupAdapter);

        // 第一层：点分组 → 看这一组的每一集
        groupAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                if (position >= 0 && position < groups.size()) {
                    openGroup(groups.get(position));
                }
            }
        });
        // item 内部的 groupItem 自己 clickable，会吃掉 item 点击，这里必须再兜一层，
        // 否则第一层「点不进去」
        groupAdapter.setOnItemChildClickListener(new BaseQuickAdapter.OnItemChildClickListener() {
            @Override
            public void onItemChildClick(BaseQuickAdapter adapter, View view, int position) {
                if (view.getId() != R.id.groupItem) return;
                FastClickCheckUtil.check(view);
                if (position >= 0 && position < groups.size()) {
                    openGroup(groups.get(position));
                }
            }
        });
        // 第二层：点某一集 → 加载弹幕
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
                    showGroupList();
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
        if (closeBtn != null) {
            closeBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    DanmakuApi.cancel();
                    dismiss();
                }
            });
        }
        applyResponsiveSize();
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

    private void search(String word) {
        groups = new ArrayList<>();
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

    private void loadDanmu(DanmuSearchResult result) {
        // 选择即关窗：加载交给 DanmakuApi 在后台继续，弹幕就绪后由播放器自动挂载，
        // 不再让搜索窗口在前台一直占着转圈
        dismiss();
        Toast.makeText(getContext(), "已选择弹幕，后台加载中", Toast.LENGTH_SHORT).show();
        DanmakuApi.loadSearchResult(result, new DanmakuApi.SearchResultCallback() {
            @Override
            public void onSuccess(String danmu) {
                if (danmuLoader != null) danmuLoader.loadDanmu(danmu);
            }

            @Override
            public void onError(String message) {
                // 窗口已关，失败只能以轻提示告知
                Toast.makeText(getContext(), "弹幕加载失败：" + message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showLoading() {
        loadingBar.setVisibility(View.VISIBLE);
        listView.setVisibility(View.GONE);
    }

    private void showResults(List<DanmuSearchResult> results) {
        if (results == null) results = new ArrayList<>();
        // 过滤掉别的季 + 拆出「来源 / 集数」+ 按「剧集名 + 来源」分组
        groups = DanmuTitleUtil.group(DanmuTitleUtil.refine(results, keyword));
        loadingBar.setVisibility(View.GONE);
        listView.setVisibility(View.VISIBLE);
        showGroupList();
        if (groups.isEmpty()) {
            Toast.makeText(getContext(), "未查询到匹配弹幕", Toast.LENGTH_SHORT).show();
        }
    }

    /** 回到第一层：剧集分组列表 */
    private void showGroupList() {
        showingEpisodes = false;
        if (titleView != null) titleView.setText("搜索弹幕");
        if (backBtn != null) backBtn.setVisibility(View.GONE);
        groupAdapter.setCurrentEpisode(currentEpisode);
        listView.setAdapter(groupAdapter);
        groupAdapter.setNewData(groups);
        scrollToGuessGroup();
    }

    /** 进入第二层：某一部剧的每一集 */
    private void openGroup(DanmuGroup group) {
        if (group == null) return;
        showingEpisodes = true;
        if (titleView != null) titleView.setText(group.getShowName());
        if (backBtn != null) backBtn.setVisibility(View.VISIBLE);
        episodeAdapter.setCurrentEpisode(currentEpisode);
        listView.setAdapter(episodeAdapter);
        episodeAdapter.setNewData(group.getItems());
        scrollToGuessEpisode(group);
    }

    /** 搜索完成后自动滚到「含当前集」的分组 */
    private void scrollToGuessGroup() {
        if (currentEpisode <= 0) return;
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).containsEpisode(currentEpisode)) {
                final int index = i;
                listView.post(new Runnable() {
                    @Override
                    public void run() {
                        listView.scrollToPosition(index);
                    }
                });
                return;
            }
        }
    }

    /** 进入分组后自动滚到「疑似此集」那一行 */
    private void scrollToGuessEpisode(DanmuGroup group) {
        if (currentEpisode <= 0) return;
        List<DanmuSearchResult> items = group.getItems();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getEpisode() == currentEpisode) {
                final int index = i;
                listView.post(new Runnable() {
                    @Override
                    public void run() {
                        listView.scrollToPosition(index);
                    }
                });
                return;
            }
        }
    }

    @Override
    public void onBackPressed() {
        // 第二层时先退回分组列表，再按一次才关闭
        if (showingEpisodes) {
            showGroupList();
            return;
        }
        DanmakuApi.cancel();
        dismiss();
    }

    public interface DanmuLoader {
        void loadDanmu(String danmu);
    }
}
