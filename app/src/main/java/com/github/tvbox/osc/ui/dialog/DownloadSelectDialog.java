package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.DownloadRecord;
import com.github.tvbox.osc.download.DownloadManager;
import com.github.tvbox.osc.util.GlassTip;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 缓存选集弹窗：在详情页点「缓存」弹出，列出当前线路的全部选集，
 * 勾选（支持全选）后加入离线缓存队列；已缓存过的集置灰不可再选。
 *
 * @author MBox
 */
public class DownloadSelectDialog extends BaseDialog {

    private final VodInfo vodInfo;
    private final String sourceKey;
    private final List<VodInfo.VodSeries> episodes;
    private final boolean[] selected;
    private final boolean[] cached;
    private RecyclerView list;
    private TextView tvSubtitle;
    private EpisodeAdapter adapter;

    public DownloadSelectDialog(@NonNull @NotNull Context context, String sourceKey, VodInfo vodInfo) {
        super(context);
        setContentView(R.layout.dialog_download_select);
        setCanceledOnTouchOutside(true);
        this.sourceKey = sourceKey;
        this.vodInfo = vodInfo;
        List<VodInfo.VodSeries> list0 = vodInfo == null || vodInfo.seriesMap == null
                ? null : vodInfo.seriesMap.get(vodInfo.playFlag);
        this.episodes = list0 == null ? new ArrayList<VodInfo.VodSeries>() : list0;
        this.selected = new boolean[episodes.size()];
        this.cached = new boolean[episodes.size()];
        DownloadManager manager = DownloadManager.get();
        for (int i = 0; i < episodes.size(); i++) {
            cached[i] = manager.hasTask(sourceKey, vodInfo.id, vodInfo.playFlag, i);
            selected[i] = cached[i];
        }
        initView();
    }

    private void initView() {
        list = findViewById(R.id.mList);
        tvSubtitle = findViewById(R.id.tvSubtitle);
        TextView tvTitle = findViewById(R.id.tvTitle);
        String flagName = vodInfo.playFlag == null ? "" : vodInfo.playFlag;
        tvTitle.setText("缓存选集 · " + flagName);
        tvSubtitle.setText((vodInfo.name == null ? "" : vodInfo.name)
                + " · 共 " + episodes.size() + " 集");

        list.setLayoutManager(new LinearLayoutManager(getContext(), LinearLayoutManager.VERTICAL, false));
        adapter = new EpisodeAdapter();
        list.setAdapter(adapter);

        findViewById(R.id.btnCancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        findViewById(R.id.btnAll).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean allSelected = true;
                for (int i = 0; i < episodes.size(); i++) {
                    if (!cached[i] && !selected[i]) {
                        allSelected = false;
                        break;
                    }
                }
                for (int i = 0; i < episodes.size(); i++) {
                    if (!cached[i]) selected[i] = !allSelected;
                }
                adapter.notifyDataSetChanged();
            }
        });
        findViewById(R.id.btnConfirm).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirm();
            }
        });
    }

    private void confirm() {
        int count = 0;
        for (int i = 0; i < episodes.size(); i++) {
            if (cached[i] || !selected[i]) continue;
            VodInfo.VodSeries series = episodes.get(i);
            DownloadRecord record = new DownloadRecord();
            record.sourceKey = sourceKey;
            record.vodId = vodInfo.id;
            record.vodName = vodInfo.name;
            record.vodPic = vodInfo.pic;
            record.playFlag = vodInfo.playFlag;
            record.playIndex = i;
            record.episodeName = series.name;
            record.url = series.url;
            if (DownloadManager.get().addTask(record)) count++;
        }
        if (count > 0) {
            GlassTip.show(getContext(), "已加入缓存队列 " + count + " 集");
        } else {
            GlassTip.show(getContext(), "没有可缓存的新选集");
        }
        dismiss();
    }

    private class EpisodeAdapter extends BaseQuickAdapter<VodInfo.VodSeries, BaseViewHolder> {

        EpisodeAdapter() {
            super(R.layout.item_download_select, episodes);
        }

        @Override
        protected void convert(final BaseViewHolder helper, final VodInfo.VodSeries item) {
            final int position = helper.getLayoutPosition();
            TextView tvName = helper.getView(R.id.tvName);
            TextView tvCached = helper.getView(R.id.tvCached);
            ImageView ivCheck = helper.getView(R.id.ivCheck);
            View contentRoot = helper.getView(R.id.contentRoot);

            tvName.setText(item.name == null ? "第" + (position + 1) + "集" : item.name);
            tvCached.setVisibility(cached[position] ? View.VISIBLE : View.GONE);
            tvName.setAlpha(cached[position] ? 0.5f : 1f);
            ivCheck.setVisibility(cached[position] ? View.GONE : View.VISIBLE);
            ivCheck.setSelected(selected[position]);

            contentRoot.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (cached[position]) return;
                    selected[position] = !selected[position];
                    ivCheck.setSelected(selected[position]);
                }
            });
        }
    }
}
