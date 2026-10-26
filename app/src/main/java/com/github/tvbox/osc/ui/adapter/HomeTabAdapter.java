package com.github.tvbox.osc.ui.adapter;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.HomeTabConfig;

import java.util.ArrayList;

/**
 * 「首页标签」编辑列表：一行一个分类，左侧拖动柄、右侧勾选框。
 */
public class HomeTabAdapter extends BaseQuickAdapter<HomeTabConfig.Tab, BaseViewHolder> {

    public HomeTabAdapter() {
        super(R.layout.item_home_tab_row, new ArrayList<HomeTabConfig.Tab>());
    }

    @Override
    protected void convert(BaseViewHolder helper, HomeTabConfig.Tab item) {
        helper.setText(R.id.tvName, item == null ? "" : item.name);
        helper.getView(R.id.ivCheck).setSelected(item != null && item.visible);
    }
}
