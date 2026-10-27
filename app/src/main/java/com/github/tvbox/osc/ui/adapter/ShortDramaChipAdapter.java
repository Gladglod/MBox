package com.github.tvbox.osc.ui.adapter;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.ShortDramaSource;

import java.util.ArrayList;

/**
 * 短剧页顶部的源胶囊。
 *
 * <p>选中态用 {@code state_selected} 驱动 drawable 与文字色两个选择器，
 * 而不是在代码里改颜色 —— 这样日间 / 夜间切换时不用重新渲染一遍列表。</p>
 */
public class ShortDramaChipAdapter extends BaseQuickAdapter<ShortDramaSource, BaseViewHolder> {

    private String selectedId = "";

    public ShortDramaChipAdapter() {
        super(R.layout.item_short_drama_chip, new ArrayList<ShortDramaSource>());
    }

    public void setSelectedId(String id) {
        this.selectedId = id == null ? "" : id;
        notifyDataSetChanged();
    }

    @Override
    protected void convert(BaseViewHolder helper, ShortDramaSource item) {
        String label = item == null ? "" : item.label;
        helper.setText(R.id.tvChip, label);
        helper.getView(R.id.tvChip).setSelected(item != null && selectedId.equals(item.id()));
    }
}
