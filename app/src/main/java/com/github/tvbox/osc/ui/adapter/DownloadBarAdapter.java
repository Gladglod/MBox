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
import com.github.tvbox.osc.cache.DownloadRecord;
import com.github.tvbox.osc.ui.widget.SwipeActionLayout;
import com.github.tvbox.osc.util.ImgUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 离线缓存「条状」列表项：iOS 玻璃卡片（缩略图 + 剧名/集数 + 进度 + 大小/速度）。
 * 点击 = 播放（已完成）/ 暂停·继续（未完成）；长按 = 进入批量编辑并勾选该条；
 * 左滑 = 删除（含文件）；编辑模式下点条目 = 勾选 / 取消勾选。
 *
 * @author MBox
 */
public class DownloadBarAdapter extends BaseQuickAdapter<DownloadRecord, BaseViewHolder> {

    public interface OnItemActionListener {
        /** 主操作：编辑模式下 = 勾选；正常模式：已完成 → 播放；未完成 → 暂停/继续 */
        void onItemClick(DownloadRecord item);

        void onItemLongClick(DownloadRecord item);
    }

    public interface OnItemDeleteListener {
        /** 左滑删除单个任务（含缓存文件） */
        void onDelete(DownloadRecord item);
    }

    private OnItemActionListener itemListener;
    private OnItemDeleteListener deleteListener;
    private int radiusPx;

    private boolean editMode = false;
    private final Set<Integer> selected = new LinkedHashSet<>();

    public DownloadBarAdapter() {
        super(R.layout.item_download_bar, new ArrayList<DownloadRecord>());
    }

    public void setOnItemActionListener(OnItemActionListener listener) {
        this.itemListener = listener;
    }

    public void setOnItemDeleteListener(OnItemDeleteListener listener) {
        this.deleteListener = listener;
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
        for (DownloadRecord item : getData()) {
            selected.add(keyOf(item));
        }
        notifyDataSetChanged();
    }

    /** 编辑模式下点条目：勾选 / 取消勾选 */
    public void toggleSelection(DownloadRecord item) {
        if (item == null) return;
        int key = keyOf(item);
        if (!selected.remove(key)) {
            selected.add(key);
        }
        notifyDataSetChanged();
    }

    public int getSelectedCount() {
        return selected.size();
    }

    public List<DownloadRecord> getSelectedItems() {
        List<DownloadRecord> list = new ArrayList<>();
        for (DownloadRecord item : getData()) {
            if (selected.contains(keyOf(item))) list.add(item);
        }
        return list;
    }

    private int keyOf(DownloadRecord item) {
        return item == null ? -1 : item.getId();
    }

    @Override
    protected void convert(BaseViewHolder helper, final DownloadRecord item) {
        if (radiusPx == 0) {
            radiusPx = (int) (10 * helper.itemView.getContext().getResources().getDisplayMetrics().density);
        }
        TextView tvName = helper.getView(R.id.tvName);
        TextView tvInfo = helper.getView(R.id.tvInfo);
        TextView tvState = helper.getView(R.id.tvState);
        ProgressBar pbProgress = helper.getView(R.id.pbProgress);
        ImageView ivThumb = helper.getView(R.id.ivThumb);
        ImageView ivCheck = helper.getView(R.id.ivCheck);
        View btnDelete = helper.getView(R.id.btnDelete);
        SwipeActionLayout swipe = (SwipeActionLayout) helper.itemView;

        swipe.resetNow();
        swipe.setSwipeEnabled(!editMode);

        ivCheck.setVisibility(editMode ? View.VISIBLE : View.GONE);
        ivCheck.setSelected(selected.contains(keyOf(item)));

        tvName.setText(TextUtils.isEmpty(item.vodName) ? "未知" : item.vodName);
        SourceBean bean = ApiConfig.get().getSource(item.sourceKey);
        String sourceName = bean != null ? bean.getName() : (item.sourceKey == null ? "" : item.sourceKey);
        String info = (item.episodeName == null ? "EP" + (item.playIndex + 1) : item.episodeName)
                + " · " + sourceName;
        tvInfo.setText(info);

        int progress = Math.max(0, Math.min(100, item.progress));
        pbProgress.setProgress(progress);
        pbProgress.setVisibility(item.isActive() ? View.VISIBLE : View.GONE);
        tvState.setText(stateText(item));
        if (item.state == DownloadRecord.STATE_ERROR) {
            tvState.setTextColor(helper.itemView.getResources().getColor(R.color.ui_danger));
        } else {
            tvState.setTextColor(helper.itemView.getResources().getColor(R.color.ui_text_secondary));
        }

        btnDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (deleteListener != null) deleteListener.onDelete(item);
            }
        });

        View contentRoot = helper.getView(R.id.contentRoot);
        contentRoot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (itemListener != null) itemListener.onItemClick(item);
            }
        });
        contentRoot.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (itemListener != null) {
                    itemListener.onItemLongClick(item);
                    return true;
                }
                return false;
            }
        });

        if (!TextUtils.isEmpty(item.vodPic)) {
            ImgUtil.load(item.vodPic, ivThumb, radiusPx, 0, 0, item.vodName);
        } else {
            ivThumb.setImageDrawable(ImgUtil.createTextDrawable(item.vodName));
        }
    }

    private String stateText(DownloadRecord item) {
        switch (item.state) {
            case DownloadRecord.STATE_WAIT:
                return "排队等待中 · " + formatSize(item.currentLen);
            case DownloadRecord.STATE_RUNNING:
                return String.format(Locale.US, "下载中 %d%% · %s · %s",
                        Math.max(0, Math.min(100, item.progress)),
                        formatProgress(item),
                        formatSpeed(item.speed));
            case DownloadRecord.STATE_PAUSE:
                return String.format(Locale.US, "已暂停 · %d%% · %s",
                        Math.max(0, Math.min(100, item.progress)),
                        formatProgress(item));
            case DownloadRecord.STATE_DONE:
                return "已缓存 · " + formatSize(item.currentLen);
            default:
                return item.errorMsg == null ? "下载失败" : "失败：" + item.errorMsg;
        }
    }

    /** 已下载 / 总大小（总大小未知时只显示已下载） */
    private String formatProgress(DownloadRecord item) {
        if (item.totalLen > 0) {
            return formatSize(item.currentLen) + " / " + formatSize(item.totalLen);
        }
        return formatSize(item.currentLen);
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) return "0MB";
        double mb = bytes / 1048576d;
        if (mb >= 1024) {
            return String.format(Locale.US, "%.2fGB", mb / 1024d);
        }
        return String.format(Locale.US, "%.1fMB", mb);
    }

    private static String formatSpeed(long bytesPerSecond) {
        if (bytesPerSecond <= 0) return "0KB/s";
        double kb = bytesPerSecond / 1024d;
        if (kb >= 1024) {
            return String.format(Locale.US, "%.1fMB/s", kb / 1024d);
        }
        return String.format(Locale.US, "%.0fKB/s", kb);
    }
}
