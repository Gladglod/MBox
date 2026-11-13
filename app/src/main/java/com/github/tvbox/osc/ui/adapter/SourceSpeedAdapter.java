package com.github.tvbox.osc.ui.adapter;

import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.SourceSpeedTester;

import java.util.ArrayList;
import java.util.List;

/**
 * 换源测速面板的源列表：源名 + 延迟状态。
 * latency 语义：0 = 测速中，FAIL(-1) = 超时/失败，>0 = 实测延迟 ms。
 */
public class SourceSpeedAdapter extends BaseQuickAdapter<SourceSpeedAdapter.Item, BaseViewHolder> {

    public static class Item {
        public final SourceBean source;
        public int latency = 0;
        public boolean searching = false;

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
        TextView tvLatency = helper.getView(R.id.tvSourceLatency);
        android.content.res.Resources res = helper.itemView.getContext().getResources();
        if (item.searching) {
            tvLatency.setText("搜索片源中…");
            tvLatency.setTextColor(res.getColor(R.color.ui_accent));
        } else if (item.latency == 0) {
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
        helper.itemView.setAlpha(item.latency == SourceSpeedTester.FAIL ? 0.5f : 1f);
    }

    /** 原地更新某个源的测速结果，不改变列表顺序。 */
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
}
