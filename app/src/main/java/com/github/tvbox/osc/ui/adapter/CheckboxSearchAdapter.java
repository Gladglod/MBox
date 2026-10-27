package com.github.tvbox.osc.ui.adapter;


import android.annotation.SuppressLint;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.SearchHelper;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class CheckboxSearchAdapter extends ListAdapter<SourceBean, CheckboxSearchAdapter.ViewHolder> {

    public CheckboxSearchAdapter(DiffUtil.ItemCallback<SourceBean> diffCallback) {
        super(diffCallback);
    }

    @Override
    public ViewHolder onCreateViewHolder(@NonNull @NotNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_dialog_checkbox_search, parent, false));
    }

    private void setCheckedSource(HashMap<String, String> checkedSources) {
        mCheckedSources = checkedSources;
    }

    private ArrayList<SourceBean> data = new ArrayList<>();
    public HashMap<String, String> mCheckedSources = new HashMap<>();

    @SuppressLint("NotifyDataSetChanged")
    public void setData(List<SourceBean> newData, HashMap<String, String> checkedSources) {
        data.clear();
        data.addAll(newData);
        setCheckedSource(checkedSources);
        notifyDataSetChanged();
    }

    public void setMCheckedSources() {
        SearchHelper.putCheckedSources(mCheckedSources, data.size() == mCheckedSources.size());
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        int pos = holder.getAdapterPosition();
        if (pos < 0 || pos >= data.size()) return;
        SourceBean sourceBean = data.get(pos);
        holder.tvSourceName.setText(sourceBean.getName());
        boolean checked = mCheckedSources != null && mCheckedSources.containsKey(sourceBean.getKey());
        applyCheckedStyle(holder, checked);

        holder.itemRoot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int adapterPosition = holder.getAdapterPosition();
                if (adapterPosition < 0 || adapterPosition >= data.size()) return;
                SourceBean bean = data.get(adapterPosition);
                if (mCheckedSources == null) mCheckedSources = new HashMap<>();
                if (mCheckedSources.containsKey(bean.getKey())) {
                    mCheckedSources.remove(bean.getKey());
                } else {
                    mCheckedSources.put(bean.getKey(), "1");
                }
                notifyItemChanged(adapterPosition);
            }
        });
    }

    private void applyCheckedStyle(ViewHolder holder, boolean checked) {
        holder.itemRoot.setBackgroundResource(checked ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
        holder.tvSourceName.setTextColor(checked
                ? holder.itemRoot.getContext().getResources().getColor(R.color.ui_accent)
                : holder.itemRoot.getContext().getResources().getColor(R.color.dialog_text_primary));
        holder.tvSourceCheck.setVisibility(checked ? View.VISIBLE : View.INVISIBLE);
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        public View itemRoot;
        public TextView tvSourceName;
        public TextView tvSourceCheck;

        public ViewHolder(View view) {
            super(view);
            itemRoot = view.findViewById(R.id.itemRoot);
            tvSourceName = (TextView) view.findViewById(R.id.tvSourceName);
            tvSourceCheck = (TextView) view.findViewById(R.id.tvSourceCheck);
        }
    }

}
