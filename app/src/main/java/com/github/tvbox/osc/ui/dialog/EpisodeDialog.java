package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class EpisodeDialog extends BaseDialog {
    public interface EpisodeSelectListener {
        void selectEpisode(int position);
    }

    private final List<VodInfo.VodSeries> episodes;
    private final int selectedPosition;
    private final EpisodeSelectListener listener;

    public EpisodeDialog(@NonNull @NotNull Context context, String title, List<VodInfo.VodSeries> episodes, int selectedPosition, EpisodeSelectListener listener) {
        super(context);
        if (context instanceof Activity) setOwnerActivity((Activity) context);
        this.episodes = episodes == null ? new ArrayList<>() : new ArrayList<>(episodes);
        this.selectedPosition = Math.max(0, Math.min(selectedPosition, Math.max(0, this.episodes.size() - 1)));
        this.listener = listener;
        setContentView(R.layout.dialog_episode);
        init(title);
    }

    private void init(String title) {
        TextView episodeTitle = findViewById(R.id.episode_title);
        episodeTitle.setText(title);
        findViewById(R.id.episode_dismiss).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dismiss();
            }
        });

        TvRecyclerView episodeList = findViewById(R.id.episode_list);
        EpisodeAdapter adapter = new EpisodeAdapter(selectedPosition);
        episodeList.setHasFixedSize(true);
        // 一排固定 5 个（需求约定），纵向滚动
        V7GridLayoutManager layoutManager = new V7GridLayoutManager(getContext(), 5);
        episodeList.setLayoutManager(layoutManager);
        episodeList.setAdapter(adapter);
        adapter.setNewData(episodes);
        scrollToSelectedPosition(layoutManager);
        episodeList.setSelectedPosition(selectedPosition);
        adapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                dismiss();
                if (listener != null) listener.selectEpisode(position);
            }
        });
        episodeList.post(new Runnable() {
            @Override
            public void run() {
                episodeList.setSelectedPosition(selectedPosition);
                requestSelectedEpisodeFocus(episodeList, layoutManager, 0);
            }
        });
    }

    private void scrollToSelectedPosition(V7GridLayoutManager layoutManager) {
        // 当前集上方预留两排，避免贴在顶部时遥控上键找不到可移动的焦点项。
        int rowsBeforeSelected = 2;
        int startPosition = Math.max(0, selectedPosition - layoutManager.getSpanCount() * rowsBeforeSelected);
        layoutManager.scrollToPositionWithOffset(startPosition, 0);
    }

    private void requestSelectedEpisodeFocus(final TvRecyclerView episodeList,
                                             final V7GridLayoutManager layoutManager,
                                             final int retryCount) {
        episodeList.postDelayed(new Runnable() {
            @Override
            public void run() {
                View selectedView = layoutManager.findViewByPosition(selectedPosition);
                if (selectedView != null) {
                    selectedView.requestFocus();
                } else if (retryCount < 3) {
                    scrollToSelectedPosition(layoutManager);
                    requestSelectedEpisodeFocus(episodeList, layoutManager, retryCount + 1);
                }
            }
        }, 50);
    }

    @Override
    public void show() {
        super.show();
        if (!isShowing()) return;
        Window window = getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }
    }

    private static class EpisodeAdapter extends BaseQuickAdapter<VodInfo.VodSeries, BaseViewHolder> {
        private final int selectedPosition;

        EpisodeAdapter(int selectedPosition) {
            // 面板是自适应玻璃底，条目用同一套玻璃行（日 / 夜间跟随主题变色）
            super(R.layout.item_series_compact, new ArrayList<VodInfo.VodSeries>());
            this.selectedPosition = selectedPosition;
        }

        @Override
        protected void convert(BaseViewHolder helper, VodInfo.VodSeries item) {
            TextView series = helper.getView(R.id.tvSeries);
            series.setText(item == null ? "" : item.name);
            if (helper.getLayoutPosition() == selectedPosition) {
                series.setTextColor(mContext.getResources().getColor(R.color.ui_accent));
                helper.itemView.setBackgroundResource(R.drawable.bg_chip_selected);
            } else {
                series.setTextColor(mContext.getResources().getColor(R.color.ui_text_primary));
                helper.itemView.setBackgroundResource(R.drawable.bg_glass_card_chip);
            }
        }
    }
}
