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
import com.github.tvbox.osc.util.ImgUtil;

import java.util.ArrayList;
import java.util.Locale;

/**
 * 离线缓存「条状」列表项：缩略图 + 剧名 / 集数 + 进度 + 右侧操作按钮。
 * 点击 = 播放（已完成）/ 暂停继续（未完成）；长按 = 删除（含文件）。
 *
 * @author MBox
 */
public class DownloadBarAdapter extends BaseQuickAdapter<DownloadRecord, BaseViewHolder> {

    public interface OnItemActionListener {
        /** 主操作：已完成 → 播放；未完成 → 暂停/继续 */
        void onAction(DownloadRecord item);

        void onItemLongClick(DownloadRecord item);
    }

    private OnItemActionListener actionListener;
    private int radiusPx;

    public DownloadBarAdapter() {
        super(R.layout.item_download_bar, new ArrayList<DownloadRecord>());
    }

    public void setOnItemActionListener(OnItemActionListener listener) {
        this.actionListener = listener;
    }

    @Override
    protected void convert(BaseViewHolder helper, final DownloadRecord item) {
        if (radiusPx == 0) {
            radiusPx = (int) (10 * helper.itemView.getContext().getResources().getDisplayMetrics().density);
        }
        TextView tvName = helper.getView(R.id.tvName);
        TextView tvInfo = helper.getView(R.id.tvInfo);
        TextView tvState = helper.getView(R.id.tvState);
        TextView btnAction = helper.getView(R.id.btnAction);
        ProgressBar pbProgress = helper.getView(R.id.pbProgress);
        ImageView ivThumb = helper.getView(R.id.ivThumb);

        tvName.setText(TextUtils.isEmpty(item.vodName) ? "未知" : item.vodName);
        SourceBean bean = ApiConfig.get().getSource(item.sourceKey);
        String sourceName = bean != null ? bean.getName() : (item.sourceKey == null ? "" : item.sourceKey);
        String info = (item.episodeName == null ? "EP" + (item.playIndex + 1) : item.episodeName)
                + " · " + sourceName;
        if (item.isDone() && !TextUtils.isEmpty(item.filePath)) {
            info += " · " + String.format(Locale.US, "%.1fMB", item.currentLen / 1048576f);
        }
        tvInfo.setText(info);

        int progress = Math.max(0, Math.min(100, item.progress));
        boolean showBar = item.isActive();
        pbProgress.setVisibility(showBar ? View.VISIBLE : View.GONE);
        pbProgress.setProgress(progress);
        tvState.setText(stateText(item));
        if (item.state == DownloadRecord.STATE_ERROR) {
            tvState.setTextColor(helper.itemView.getResources().getColor(R.color.ui_danger));
        } else {
            tvState.setTextColor(helper.itemView.getResources().getColor(R.color.ui_text_secondary));
        }

        // 右侧按钮：已完成=播放；等待/下载中=暂停；暂停/失败=继续
        int actionColor;
        String actionLabel;
        switch (item.state) {
            case DownloadRecord.STATE_DONE:
                actionLabel = "播放";
                actionColor = R.color.ui_accent;
                break;
            case DownloadRecord.STATE_WAIT:
            case DownloadRecord.STATE_RUNNING:
                actionLabel = "暂停";
                actionColor = R.color.ui_text_secondary;
                break;
            default:
                actionLabel = "继续";
                actionColor = R.color.ui_accent;
                break;
        }
        btnAction.setText(actionLabel);
        btnAction.setTextColor(helper.itemView.getResources().getColor(actionColor));

        btnAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (actionListener != null) actionListener.onAction(item);
            }
        });

        View contentRoot = helper.getView(R.id.contentRoot);
        contentRoot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (actionListener != null) actionListener.onAction(item);
            }
        });
        contentRoot.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (actionListener != null) {
                    actionListener.onItemLongClick(item);
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
                return "排队等待中";
            case DownloadRecord.STATE_RUNNING:
                return String.format(Locale.US, "下载中 %d%%", Math.max(0, Math.min(100, item.progress)));
            case DownloadRecord.STATE_PAUSE:
                return "已暂停 · " + String.format(Locale.US, "%d%%", Math.max(0, Math.min(100, item.progress)));
            case DownloadRecord.STATE_DONE:
                return "已缓存";
            default:
                return item.errorMsg == null ? "下载失败" : "失败：" + item.errorMsg;
        }
    }
}
