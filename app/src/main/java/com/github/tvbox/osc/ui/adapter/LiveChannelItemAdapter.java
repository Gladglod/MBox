package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.LiveChannelItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 直播频道（单列玻璃行卡片，像播放历史 / 选集列表）。
 *
 * <p>以前是 3 列网格 chip，只有频道名，看不出来这一台在放什么；
 * 现在每行下面加一条「正在直播」的节目（EPG 由页面排队预取后回填），
 * EPG 没到的行第二行自动隐藏，出问题也不影响频道本身的选择。</p>
 */
public class LiveChannelItemAdapter extends BaseQuickAdapter<LiveChannelItem, BaseViewHolder> {
    /** 频道列表整体换过一批数据后回调，页面据此排队预取每个频道的「正在直播」节目 */
    public interface ChannelProgramLoader {
        void onChannelDataChanged(List<LiveChannelItem> channels);
    }

    private int selectedChannelIndex = -1;
    private int focusedChannelIndex = -1;
    /** 频道名 → 「正在直播」节目文案 */
    private final Map<String, String> programMap = new HashMap<>();
    private ChannelProgramLoader programLoader;

    public LiveChannelItemAdapter() {
        super(R.layout.item_live_channel_row, new ArrayList<LiveChannelItem>());
    }

    public void setProgramLoader(ChannelProgramLoader loader) {
        programLoader = loader;
    }

    @Override
    public void setNewData(@Nullable List<LiveChannelItem> data) {
        // 先清掉上一批频道的节目文案，避免换分类后串台；
        // 转换过程中地图是空的，各行第二行先隐藏，等预取结果回来再逐条点亮
        programMap.clear();
        super.setNewData(data);
        if (programLoader != null) {
            programLoader.onChannelDataChanged(data == null ? new ArrayList<LiveChannelItem>() : data);
        }
    }

    @Override
    protected void convert(BaseViewHolder holder, LiveChannelItem item) {
        TextView tvChannel = holder.getView(R.id.tvChannelName);
        TextView tvProgram = holder.getView(R.id.tvChannelProgram);
        tvChannel.setText(item.getChannelName());
        int channelIndex = item.getChannelIndex();
        boolean selected = channelIndex == selectedChannelIndex;
        holder.itemView.setSelected(selected);
        int nameColor = selected ? R.color.ui_accent : R.color.ui_text_primary;
        String program = programMap.get(item.getChannelName());
        if (TextUtils.isEmpty(program)) {
            tvProgram.setVisibility(View.GONE);
            tvProgram.setText("");
        } else {
            tvProgram.setVisibility(View.VISIBLE);
            tvProgram.setText(program);
            if (selected) nameColor = R.color.ui_accent;
        }
        tvChannel.setTextColor(mContext.getResources().getColor(nameColor));
        tvProgram.setTextColor(mContext.getResources().getColor(
                selected ? R.color.ui_accent : R.color.ui_text_secondary));
    }

    /** 预取到某个频道的节目后回填：只刷新命中频道名的那些行 */
    public void setProgramText(String channelName, String program) {
        if (channelName == null || program == null) return;
        programMap.put(channelName, program);
        List<LiveChannelItem> data = getData();
        if (data == null) return;
        for (int i = 0; i < data.size(); i++) {
            LiveChannelItem item = data.get(i);
            if (item != null && channelName.equals(item.getChannelName())) {
                notifyItemChanged(i);
            }
        }
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
