package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.cache.VodCollect;
import com.github.tvbox.osc.ui.widget.SwipeActionLayout;
import com.github.tvbox.osc.util.ImgUtil;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 收藏列表项：与播放历史保持一致的条状样式（缩略图 + 标题 + 来源 + 收藏时间），
 * 支持左滑「置顶 / 删除」与编辑模式多选。
 */
public class CollectBarAdapter extends BaseQuickAdapter<VodCollect, BaseViewHolder> {

    public interface OnActionListener {
        void onPin(VodCollect item);

        void onDelete(VodCollect item);
    }

    public interface OnItemActionListener {
        void onItemClick(VodCollect item);

        void onItemLongClick(VodCollect item);
    }

    private int thumbWidthPx;
    private int thumbHeightPx;
    private int radiusPx;

    private boolean editMode = false;
    private final Set<Integer> selected = new LinkedHashSet<>();
    private OnActionListener actionListener;
    private OnItemActionListener itemActionListener;

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
    /**
     * 「收藏于 xxxx-xx-xx xx:xx」按条目 id 缓存。
     * 收藏时间是固定的，没必要每次 bind 都 new Date + format 一遍；
     * 这是典型的「格式化应该在数据准备阶段做完，而不是放在 onBindViewHolder 里」。
     */
    private final android.util.SparseArray<String> timeTextCache = new android.util.SparseArray<>();

    public CollectBarAdapter() {
        super(R.layout.item_collect_bar, new ArrayList<VodCollect>());
    }

    @Override
    public void setNewData(@Nullable List<VodCollect> data) {
        timeTextCache.clear();
        super.setNewData(data);
    }

    private String timeTextOf(VodCollect item) {
        int id = item.getId();
        String cached = timeTextCache.get(id);
        if (cached != null) return cached;
        String text = "收藏于 " + dateFormat.format(new Date(item.updateTime));
        timeTextCache.put(id, text);
        return text;
    }

    public void setOnActionListener(OnActionListener listener) {
        this.actionListener = listener;
    }

    public void setOnItemActionListener(OnItemActionListener listener) {
        this.itemActionListener = listener;
    }

    public boolean isEditMode() {
        return editMode;
    }

    public void setEditMode(boolean edit) {
        this.editMode = edit;
        if (!edit) selected.clear();
        notifyDataSetChanged();
    }

    public void selectAll() {
        for (VodCollect item : getData()) {
            selected.add(item.getId());
        }
        notifyDataSetChanged();
    }

    public void toggleSelection(VodCollect item) {
        if (item == null) return;
        if (!selected.remove(item.getId())) {
            selected.add(item.getId());
        }
        notifyDataSetChanged();
    }

    public int getSelectedCount() {
        return selected.size();
    }

    public List<VodCollect> getSelectedItems() {
        List<VodCollect> list = new ArrayList<>();
        for (VodCollect item : getData()) {
            if (selected.contains(item.getId())) list.add(item);
        }
        return list;
    }

    private void ensureSizes(android.content.Context context) {
        if (thumbWidthPx > 0) return;
        float density = context.getResources().getDisplayMetrics().density;
        thumbWidthPx = (int) (104 * density);
        thumbHeightPx = (int) (60 * density);
        radiusPx = (int) (4 * density);
    }

    @Override
    protected void convert(BaseViewHolder helper, final VodCollect item) {
        ensureSizes(helper.itemView.getContext());
        TextView tvName = helper.getView(R.id.tvName);
        TextView tvSource = helper.getView(R.id.tvSource);
        TextView tvTime = helper.getView(R.id.tvTime);
        ImageView ivThumb = helper.getView(R.id.ivThumb);
        ImageView ivCheck = helper.getView(R.id.ivCheck);
        View btnPin = helper.getView(R.id.btnPin);
        View btnDelete = helper.getView(R.id.btnDelete);
        SwipeActionLayout swipe = (SwipeActionLayout) helper.itemView;

        swipe.resetNow();
        swipe.setSwipeEnabled(!editMode);

        ivCheck.setVisibility(editMode ? View.VISIBLE : View.GONE);
        ivCheck.setSelected(selected.contains(item.getId()));

        tvName.setText(TextUtils.isEmpty(item.name) ? "未知" : item.name);

        SourceBean bean = ApiConfig.get().getSource(item.sourceKey);
        tvSource.setText(bean != null ? bean.getName() : "聚合搜索");

        if (item.updateTime > 0) {
            tvTime.setVisibility(View.VISIBLE);
            tvTime.setText(timeTextOf(item));
        } else {
            tvTime.setVisibility(View.GONE);
        }

        btnPin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (actionListener != null) actionListener.onPin(item);
            }
        });
        btnDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (actionListener != null) actionListener.onDelete(item);
            }
        });

        View contentRoot = helper.getView(R.id.contentRoot);
        contentRoot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (itemActionListener != null) itemActionListener.onItemClick(item);
            }
        });
        contentRoot.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (itemActionListener != null) {
                    itemActionListener.onItemLongClick(item);
                    return true;
                }
                return false;
            }
        });

        if (!TextUtils.isEmpty(item.pic)) {
            ImgUtil.load(item.pic, ivThumb, radiusPx, thumbWidthPx, thumbHeightPx, item.name);
        } else {
            ivThumb.setImageDrawable(ImgUtil.createTextDrawable(item.name));
        }
    }
}
