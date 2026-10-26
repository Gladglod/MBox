package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.util.ImgUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 「继续观看」小方块：横向滑动，缩略图 + 剧名 + 底部观看进度条。
 */
public class ContinueSquareAdapter extends BaseQuickAdapter<VodInfo, BaseViewHolder> {

    private int sizePx;
    private int radiusPx;

    public ContinueSquareAdapter() {
        super(R.layout.item_continue_square, new ArrayList<VodInfo>());
    }

    private void ensureSizes(android.content.Context context) {
        if (sizePx > 0) return;
        float density = context.getResources().getDisplayMetrics().density;
        sizePx = (int) (84 * density);
        radiusPx = (int) (6 * density);
    }

    @Override
    protected void convert(BaseViewHolder helper, VodInfo item) {
        ensureSizes(helper.itemView.getContext());
        TextView tvName = helper.getView(R.id.tvName);
        ProgressBar pbProgress = helper.getView(R.id.pbProgress);
        ImageView ivThumb = helper.getView(R.id.ivThumb);

        tvName.setText(TextUtils.isEmpty(item.name) ? "未知" : item.name);

        int total = seriesCount(item);
        int index = Math.max(0, Math.min(item.playIndex + 1, total));
        if (total > 1) {
            pbProgress.setVisibility(View.VISIBLE);
            pbProgress.setProgress((int) (index * 100f / total));
        } else {
            pbProgress.setVisibility(View.GONE);
        }

        if (!TextUtils.isEmpty(item.pic)) {
            ImgUtil.load(item.pic, ivThumb, radiusPx, sizePx, sizePx, item.name);
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
