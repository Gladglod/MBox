package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.ShortDramaSource;

import java.util.ArrayList;

/**
 * 「短剧源」设置页的行：分类名 + 站点名 + 勾选 + 删除。
 *
 * <p>删除按钮只在「手动添加」和「站点已经不在配置里」两种情况下出现：
 * 自动识别出来的项取消勾选就够了，给删除反而会让用户以为能永久清掉，
 * 而下次强制重新识别它又会回来。</p>
 */
public class ShortDramaSourceAdapter extends BaseQuickAdapter<ShortDramaSource, BaseViewHolder> {

    public ShortDramaSourceAdapter() {
        super(R.layout.item_short_drama_source_row, new ArrayList<ShortDramaSource>());
    }

    @Override
    protected void convert(BaseViewHolder helper, ShortDramaSource item) {
        if (item == null) return;
        helper.setText(R.id.tvName, item.displayName());
        helper.setText(R.id.tvSource, buildSubtitle(item));
        helper.getView(R.id.ivCheck).setSelected(item.enabled && item.alive);
        helper.getView(R.id.ivCheck).setAlpha(item.alive ? 1f : 0.4f);
        helper.getView(R.id.ivDel).setVisibility(item.manual || !item.alive
                ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private String buildSubtitle(ShortDramaSource item) {
        StringBuilder sb = new StringBuilder();
        sb.append(TextUtils.isEmpty(item.sourceName) ? item.sourceKey : item.sourceName);
        if (item.manual) {
            sb.append(" · ").append(mContext.getString(R.string.short_drama_source_manual_tag));
        }
        if (!item.alive) {
            sb.append(" · ").append(mContext.getString(R.string.short_drama_source_dead_tag));
        }
        return sb.toString();
    }
}
