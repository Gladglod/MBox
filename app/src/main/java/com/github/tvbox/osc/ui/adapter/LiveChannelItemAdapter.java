package com.github.tvbox.osc.ui.adapter;

import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.LiveChannelItem;

import java.util.ArrayList;

/**
 * 直播频道（网格 chip，按「选集」的观感做）。
 *
 * <p>频道号以前占一格、名字占另一格，竖排窄列表里还行，铺成网格之后纯属浪费宽度，
 * 现在只在 chip 里显示频道名；频道号在切台提示和底部信息条里仍然看得到。</p>
 */
public class LiveChannelItemAdapter extends BaseQuickAdapter<LiveChannelItem, BaseViewHolder> {
    private int selectedChannelIndex = -1;
    private int focusedChannelIndex = -1;

    public LiveChannelItemAdapter() {
        super(R.layout.item_live_channel_chip, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder holder, LiveChannelItem item) {
        TextView tvChannel = holder.getView(R.id.tvChannelName);
        tvChannel.setText(item.getChannelName());
        int channelIndex = item.getChannelIndex();
        boolean selected = channelIndex == selectedChannelIndex;
        holder.itemView.setSelected(selected);
        tvChannel.setTextColor(mContext.getResources().getColor(
                selected ? R.color.ui_accent : R.color.ui_text_primary));
    }

    public void setSelectedChannelIndex(int selectedChannelIndex) {
        if (selectedChannelIndex == this.selectedChannelIndex) return;
        int preSelectedChannelIndex = this.selectedChannelIndex;
        this.selectedChannelIndex = selectedChannelIndex;
        if (preSelectedChannelIndex != -1)
            notifyItemChanged(preSelectedChannelIndex);
        if (this.selectedChannelIndex != -1)
            notifyItemChanged(this.selectedChannelIndex);
    }

    public void setFocusedChannelIndex(int focusedChannelIndex) {
        int preFocusedChannelIndex = this.focusedChannelIndex;
        this.focusedChannelIndex = focusedChannelIndex;
        if (preFocusedChannelIndex != -1)
            notifyItemChanged(preFocusedChannelIndex);
        if (this.focusedChannelIndex != -1)
            notifyItemChanged(this.focusedChannelIndex);
        else if (this.selectedChannelIndex != -1)
            notifyItemChanged(this.selectedChannelIndex);
    }
}
