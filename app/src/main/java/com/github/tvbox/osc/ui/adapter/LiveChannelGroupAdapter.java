package com.github.tvbox.osc.ui.adapter;

import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.LiveChannelGroup;

import java.util.ArrayList;


/**
 * 直播分类（横向 chip）。
 *
 * <p>以前是左侧竖排的白色文字列表，只有深色播放器上看得见；现在频道面板铺在
 * 白色卡片上，文字改成语义色，选中态用强调色 + 胶囊描边，和「选集」一致。</p>
 */
public class LiveChannelGroupAdapter extends BaseQuickAdapter<LiveChannelGroup, BaseViewHolder> {
    private int selectedGroupIndex = -1;
    private int focusedGroupIndex = -1;

    public LiveChannelGroupAdapter() {
        super(R.layout.item_live_group_chip, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder holder, LiveChannelGroup item) {
        TextView tvGroupName = holder.getView(R.id.tvChannelGroupName);
        tvGroupName.setText(item.getGroupName());
        int groupIndex = item.getGroupIndex();
        boolean selected = groupIndex == selectedGroupIndex;
        holder.itemView.setSelected(selected);
        tvGroupName.setTextColor(mContext.getResources().getColor(
                selected ? R.color.ui_accent : R.color.ui_text_primary));
    }

    public void setSelectedGroupIndex(int selectedGroupIndex) {
        if (selectedGroupIndex == this.selectedGroupIndex) return;
        int preSelectedGroupIndex = this.selectedGroupIndex;
        this.selectedGroupIndex = selectedGroupIndex;
        notifyGroupChanged(preSelectedGroupIndex);
        notifyGroupChanged(this.selectedGroupIndex);
    }

    public int getSelectedGroupIndex() {
        return selectedGroupIndex;
    }

    public void setFocusedGroupIndex(int focusedGroupIndex) {
        this.focusedGroupIndex = focusedGroupIndex;
        if (this.focusedGroupIndex != -1)
            notifyGroupChanged(this.focusedGroupIndex);
        else if (this.selectedGroupIndex != -1)
            notifyGroupChanged(this.selectedGroupIndex);
    }

    public void clearGroupState() {
        selectedGroupIndex = -1;
        focusedGroupIndex = -1;
    }

    private void notifyGroupChanged(int position) {
        if (position >= 0 && position < getItemCount()) {
            notifyItemChanged(position);
        }
    }
}
