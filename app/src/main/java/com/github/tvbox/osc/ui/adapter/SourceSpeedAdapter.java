package com.github.tvbox.osc.ui.adapter;

import android.content.res.Resources;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.SourceSpeedTester;

import java.util.ArrayList;
import java.util.List;

/**
 * 换源测速面板的源列表：源名 + 片源状态 + 延迟。
 * latency 语义：0 = 测速中，FAIL(-1) = 超时/失败，>0 = 实测延迟 ms（可为缓存值）。
 * hasMovie 语义：null = 预搜索未出结果，true = 该源搜到同名片源，false = 没有。
 */
public class SourceSpeedAdapter extends BaseQuickAdapter<SourceSpeedAdapter.Item, BaseViewHolder> {

    public static class Item {
        public final SourceBean source;
        public int latency = 0;
        public boolean searching = true;
        public Boolean hasMovie = null;
        public Movie.Video matchedVideo = null;

        public Item(SourceBean source) {
            this.source = source;
        }
    }

    public SourceSpeedAdapter() {
        super(R.layout.item_source_speed, new ArrayList<Item>());
    }

    @Override
    protected void convert(BaseViewHolder helper, Item item) {
        helper.setText(R.id.tvSourceName, item.source.getName());
        Resources res = helper.itemView.getContext().getResources();

        TextView tvStatus = helper.getView(R.id.tvSourceStatus);
        if (item.searching) {
            tvStatus.setText("搜索中…");
            tvStatus.setTextColor(res.getColor(R.color.ui_muted));
        } else if (Boolean.TRUE.equals(item.hasMovie)) {
            tvStatus.setText("有片源");
            tvStatus.setTextColor(res.getColor(R.color.ui_speed_good));
        } else if (Boolean.FALSE.equals(item.hasMovie)) {
            tvStatus.setText("无片源");
            tvStatus.setTextColor(res.getColor(R.color.ui_muted));
        } else {
            tvStatus.setText("—");
            tvStatus.setTextColor(res.getColor(R.color.ui_muted));
        }

        TextView tvLatency = helper.getView(R.id.tvSourceLatency);
        if (item.latency == 0) {
            tvLatency.setText("测速中…");
            tvLatency.setTextColor(res.getColor(R.color.ui_muted));
        } else if (item.latency == SourceSpeedTester.FAIL) {
            tvLatency.setText("超时");
            tvLatency.setTextColor(res.getColor(R.color.ui_speed_bad));
        } else {
            tvLatency.setText(item.latency + "ms");
            tvLatency.setTextColor(res.getColor(
                    item.latency <= 300 ? R.color.ui_speed_good
                            : item.latency <= 1000 ? R.color.ui_speed_mid
                            : R.color.ui_speed_bad));
        }
        helper.itemView.setAlpha(item.latency == SourceSpeedTester.FAIL
                || Boolean.FALSE.equals(item.hasMovie) ? 0.55f : 1f);
    }

    /** 原地更新某个源的测速结果，不改变列表顺序（仅测速中才覆盖，缓存预置的值不被冲掉）。 */
    public void updateLatency(String sourceKey, int latency) {
        List<Item> data = getData();
        for (int i = 0; i < data.size(); i++) {
            Item item = data.get(i);
            if (item.source.getKey().equals(sourceKey) && item.latency == 0) {
                item.latency = latency;
                notifyItemChanged(i);
                return;
            }
        }
    }

    /** 标记某个源正在搜索片源 / 结束搜索状态。 */
    public void updateSearching(String sourceKey, boolean searching) {
        List<Item> data = getData();
        for (int i = 0; i < data.size(); i++) {
            Item item = data.get(i);
            if (item.source.getKey().equals(sourceKey) && item.searching != searching) {
                item.searching = searching;
                notifyItemChanged(i);
                return;
            }
        }
    }

    /** 面板预搜索：标记某源的片源结果，并把命中的候选影片带上（点选可直接切换）。 */
    public void markSearchResult(String sourceKey, boolean hasMovie, Movie.Video matchedVideo) {
        List<Item> data = getData();
        for (int i = 0; i < data.size(); i++) {
            Item item = data.get(i);
            if (item.source.getKey().equals(sourceKey)) {
                item.searching = false;
                item.hasMovie = hasMovie;
                item.matchedVideo = matchedVideo;
                notifyItemChanged(i);
                return;
            }
        }
    }
}
