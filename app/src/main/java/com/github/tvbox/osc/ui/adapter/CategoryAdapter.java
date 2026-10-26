package com.github.tvbox.osc.ui.adapter;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.MovieSort;

import java.util.ArrayList;

/**
 * 分类页：分类名称卡片适配器
 */
public class CategoryAdapter extends BaseQuickAdapter<MovieSort.SortData, BaseViewHolder> {

    public CategoryAdapter() {
        super(R.layout.item_category, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder helper, MovieSort.SortData item) {
        helper.setText(R.id.tvCategoryName, item == null ? "" : item.name);
    }
}
