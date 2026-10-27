package com.github.tvbox.osc.ui.adapter;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 单选列表对话框的行：只负责文字 + 选中态。
 * 选中态复用 {@code drama_chip_text} 颜色选择器，和短剧源胶囊保持一致。
 */
public class ChoiceAdapter extends BaseQuickAdapter<String, BaseViewHolder> {

    private int selected = -1;

    public ChoiceAdapter() {
        super(R.layout.item_choice_row, new ArrayList<String>());
    }

    public void setItems(List<String> items, int selected) {
        this.selected = selected;
        setNewData(items == null ? new ArrayList<String>() : new ArrayList<>(items));
    }

    @Override
    protected void convert(BaseViewHolder helper, String item) {
        helper.setText(R.id.tvChoice, item == null ? "" : item);
        helper.getView(R.id.tvChoice).setSelected(helper.getLayoutPosition() == selected);
    }
}
