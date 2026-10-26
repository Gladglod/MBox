package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
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
import com.github.tvbox.osc.bean.DanmuSearchResult;
import com.github.tvbox.osc.ui.adapter.SearchDanmuAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class SearchDanmuDialog extends BaseDialog {
    private RecyclerView gridView;
    private SearchDanmuAdapter searchAdapter;
    private EditText searchInput;
    private ProgressBar loadingBar;
    private DanmuLoader danmuLoader;
    private String episode = "";

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
        gridView = findViewById(R.id.mGridView);
        searchInput = findViewById(R.id.input);
        TextView searchButton = findViewById(R.id.inputSubmit);
        ImageView closeBtn = findViewById(R.id.iv_close);
        searchAdapter = new SearchDanmuAdapter();

        gridView.setHasFixedSize(true);
        gridView.setLayoutManager(new LinearLayoutManager(getContext(), LinearLayoutManager.VERTICAL, false));
        gridView.setAdapter(searchAdapter);

        // 手机端是触摸操作，直接用 item 点击事件即可
        searchAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                if (position >= 0 && position < searchAdapter.getData().size()) {
                    loadDanmu(searchAdapter.getData().get(position));
                }
            }
        });
        // item 内部 danmuItem 自己注册了子点击，这里也要接住，否则点了没反应
        searchAdapter.setOnItemChildClickListener(new BaseQuickAdapter.OnItemChildClickListener() {
            @Override
            public void onItemChildClick(BaseQuickAdapter adapter, View view, int position) {
                if (view.getId() != R.id.danmuItem) return;
                FastClickCheckUtil.check(view);
                if (position >= 0 && position < searchAdapter.getData().size()) {
                    loadDanmu(searchAdapter.getData().get(position));
                }
            }
        });

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
        searchAdapter.setNewData(new ArrayList<DanmuSearchResult>());
        applyResponsiveSize();
    }

    /** 按屏幕尺寸限制卡片大小，避免横屏时超出屏幕导致内容看不全 */
    private void applyResponsiveSize() {
        try {
            android.util.DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
            View card = findViewById(R.id.dialog_card);
            View list = findViewById(R.id.list_container);
            if (card == null) return;
            int width = Math.min((int) (dm.widthPixels * 0.90f), dp(430));
            int height = Math.min((int) (dm.heightPixels * 0.86f), dp(560));
            ViewGroup.LayoutParams lp = card.getLayoutParams();
            lp.width = width;
            lp.height = height;
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
        searchAdapter.setNewData(new ArrayList<DanmuSearchResult>());
        if (TextUtils.isEmpty(word)) {
            Toast.makeText(getContext(), "输入内容不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
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
        showLoading();
        DanmakuApi.loadSearchResult(result, new DanmakuApi.SearchResultCallback() {
            @Override
            public void onSuccess(String danmu) {
                if (danmuLoader != null) danmuLoader.loadDanmu(danmu);
                dismiss();
            }

            @Override
            public void onError(String message) {
                loadingBar.setVisibility(View.GONE);
                gridView.setVisibility(View.VISIBLE);
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showLoading() {
        loadingBar.setVisibility(View.VISIBLE);
        gridView.setVisibility(View.GONE);
    }

    private void showResults(List<DanmuSearchResult> results) {
        if (results == null) results = new ArrayList<>();
        loadingBar.setVisibility(View.GONE);
        gridView.setVisibility(View.VISIBLE);
        searchAdapter.setNewData(results);
        if (results.isEmpty()) {
            Toast.makeText(getContext(), "未查询到匹配弹幕", Toast.LENGTH_SHORT).show();
            return;
        }
        gridView.scrollToPosition(0);
    }

    @Override
    public void onBackPressed() {
        DanmakuApi.cancel();
        dismiss();
    }

    public interface DanmuLoader {
        void loadDanmu(String danmu);
    }
}
