package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.ui.widget.SwipeActionLayout;
import com.github.tvbox.osc.util.ImgUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 历史记录「条状」列表项：左侧缩略图，右侧标题 + 来源 + 观看进度。
 * 支持左滑露出「置顶 / 删除」，以及编辑模式下的多选。
 */
public class HistoryBarAdapter extends BaseQuickAdapter<VodInfo, BaseViewHolder> {

    public interface OnActionListener {
        void onPin(VodInfo item);

        void onDelete(VodInfo item);
    }

    public interface OnItemActionListener {
        void onItemClick(VodInfo item);

        void onItemLongClick(VodInfo item);
    }

    private int thumbWidthPx;
    private int thumbHeightPx;
    private int radiusPx;

    private boolean editMode = false;
    private final Set<String> selected = new LinkedHashSet<>();
    private OnActionListener actionListener;
    private OnItemActionListener itemActionListener;

    public HistoryBarAdapter() {
        super(R.layout.item_history_bar, new ArrayList<VodInfo>());
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

    public void clearSelection() {
        selected.clear();
        notifyDataSetChanged();
    }

    public void selectAll() {
        for (VodInfo item : getData()) {
            selected.add(keyOf(item));
        }
        notifyDataSetChanged();
    }

    /** 编辑模式下点条目：勾选 / 取消勾选 */
    public void toggleSelection(VodInfo item) {
        if (item == null) return;
        String key = keyOf(item);
        if (!selected.remove(key)) {
            selected.add(key);
        }
        notifyDataSetChanged();
    }

    public int getSelectedCount() {
        return selected.size();
    }

    public List<VodInfo> getSelectedItems() {
        List<VodInfo> list = new ArrayList<>();
        for (VodInfo item : getData()) {
            if (selected.contains(keyOf(item))) list.add(item);
        }
        return list;
    }

    private String keyOf(VodInfo item) {
        return (item == null ? "" : item.sourceKey) + "|" + (item == null ? "" : item.id);
    }

    /** 首次 bind 时按当前页面的 density（AutoSize 已按屏宽改写）计算缩略图尺寸 */
    private void ensureSizes(android.content.Context context) {
        if (thumbWidthPx > 0) return;
        float density = context.getResources().getDisplayMetrics().density;
        thumbWidthPx = (int) (104 * density);
        thumbHeightPx = (int) (60 * density);
        radiusPx = (int) (4 * density);
    }

    @Override
    protected void convert(BaseViewHolder helper, final VodInfo item) {
        ensureSizes(helper.itemView.getContext());
        TextView tvName = helper.getView(R.id.tvName);
        TextView tvSource = helper.getView(R.id.tvSource);
        TextView tvProgress = helper.getView(R.id.tvProgress);
        TextView tvBadge = helper.getView(R.id.tvBadge);
        ProgressBar pbProgress = helper.getView(R.id.pbProgress);
        ImageView ivThumb = helper.getView(R.id.ivThumb);
        ImageView ivCheck = helper.getView(R.id.ivCheck);
        View btnPin = helper.getView(R.id.btnPin);
        View btnDelete = helper.getView(R.id.btnDelete);
        SwipeActionLayout swipe = (SwipeActionLayout) helper.itemView;

        swipe.resetNow();
        swipe.setSwipeEnabled(!editMode);

        ivCheck.setVisibility(editMode ? View.VISIBLE : View.GONE);
        ivCheck.setSelected(selected.contains(keyOf(item)));

        tvName.setText(TextUtils.isEmpty(item.name) ? "未知" : item.name);

        // 观看进度：集数维度
        int total = seriesCount(item);
        int index = item.playIndex + 1;
        boolean hasSeries = total > 1;

        SourceBean bean = ApiConfig.get().getSource(item.sourceKey);
        String sourceName = bean != null ? bean.getName() : "聚合搜索";
        if (!TextUtils.isEmpty(item.playNote)) {
            // 有多集信息时进度行已经说明了位置，这里只补充具体集名，避免文案重复
            sourceName += hasSeries ? " · " + item.playNote : " · 上次看到 " + item.playNote;
        }
        tvSource.setText(sourceName);

        if (hasSeries) {
            if (index > total) index = total;
            if (index < 0) index = 0;
            int percent = (int) (index * 100f / total);
            pbProgress.setVisibility(View.VISIBLE);
            pbProgress.setProgress(percent);
            tvBadge.setVisibility(View.VISIBLE);
            tvBadge.setText(index + "/" + total);
            tvProgress.setVisibility(View.VISIBLE);
            tvProgress.setText("已看 " + index + " / " + total + " 集 · " + percent + "%");
        } else {
            pbProgress.setVisibility(View.GONE);
            tvBadge.setVisibility(View.GONE);
            tvProgress.setVisibility(View.GONE);
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

    private int seriesCount(VodInfo item) {
        if (item.seriesCount > 0) return item.seriesCount;
        try {
            if (item.seriesMap == null || item.seriesMap.isEmpty()) return 0;
            String flag = item.playFlag;
            if (flag == null || !item.seriesMap.containsKey(flag)) {
                flag = item.seriesMap.keySet().iterator().next();
            }
            List<VodInfo.VodSeries> series = item.seriesMap.get(flag);
            return series == null ? 0 : series.size();
        } catch (Throwable th) {
            return 0;
        }
    }
}
