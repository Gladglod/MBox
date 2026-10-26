package com.github.tvbox.osc.ui.fragment;

import android.content.Context;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.ui.adapter.CategoryAdapter;

import java.util.ArrayList;

/**
 * 底部导航「分类」页：展示当前站点所有分类，点击后回到首页并定位到该分类。
 */
public class CategoryFragment extends BaseLazyFragment {

    public interface OnCategorySelectListener {
        void onCategorySelect(MovieSort.SortData sortData);
    }

    private OnCategorySelectListener listener;
    private CategoryAdapter adapter;

    public static CategoryFragment newInstance(ArrayList<MovieSort.SortData> list) {
        CategoryFragment fragment = new CategoryFragment();
        Bundle bundle = new Bundle();
        bundle.putSerializable("categories", list);
        fragment.setArguments(bundle);
        return fragment;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof OnCategorySelectListener) {
            listener = (OnCategorySelectListener) context;
        }
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_category;
    }

    @Override
    protected void init() {
        RecyclerView recyclerView = findViewById(R.id.categoryList);
        if (recyclerView == null) {
            return;
        }
        recyclerView.setLayoutManager(new GridLayoutManager(mContext, 3));
        recyclerView.setHasFixedSize(true);
        adapter = new CategoryAdapter();
        recyclerView.setAdapter(adapter);

        Bundle bundle = getArguments();
        if (bundle != null) {
            @SuppressWarnings("unchecked")
            ArrayList<MovieSort.SortData> list = (ArrayList<MovieSort.SortData>) bundle.getSerializable("categories");
            if (list != null) {
                adapter.setNewData(list);
            }
        }

        adapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter baseAdapter, View view, int position) {
                MovieSort.SortData item = (MovieSort.SortData) baseAdapter.getItem(position);
                if (item != null && listener != null) {
                    listener.onCategorySelect(item);
                }
            }
        });
    }
}
