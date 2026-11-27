package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.TmdbApi;
import com.github.tvbox.osc.util.ImgUtil;

import java.util.ArrayList;
import java.util.Locale;

/**
 * 发现页（TMDB）海报网格。
 *
 * <p>海报比例固定 2:3，由 {@code item_discover_card} 的 ConstraintLayout 控制，
 * adapter 只负责文字和图片，不参与排版。图片不给 Glide 指定 override 尺寸，
 * 交给它按 ImageView 实测宽高解码（和 {@link GridAdapter} 同样的理由：
 * AutoSize 下自己算出来的像素数往往比实际显示尺寸大好几倍）。</p>
 */
public class DiscoverAdapter extends BaseQuickAdapter<TmdbApi.Item, BaseViewHolder> {

    public DiscoverAdapter() {
        super(R.layout.item_discover_card, new ArrayList<TmdbApi.Item>());
    }

    @Override
    protected void convert(BaseViewHolder helper, TmdbApi.Item item) {
        if (item == null) return;

        helper.setText(R.id.tvTitle, item.displayTitle());

        TextView tvRating = helper.getView(R.id.tvRating);
        if (item.rating > 0) {
            tvRating.setText(String.format(Locale.US, "★ %.1f", item.rating));
            if (tvRating.getVisibility() != View.VISIBLE) tvRating.setVisibility(View.VISIBLE);
        } else if (tvRating.getVisibility() != View.GONE) {
            tvRating.setVisibility(View.GONE);
        }

        TextView tvMediaType = helper.getView(R.id.tvMediaType);
        tvMediaType.setText(item.mediaLabel());

        // 年份 / 评分人数，两者都没有时退回 TMDB 的原始语言，保证第二行不空
        StringBuilder meta = new StringBuilder();
        String year = item.year();
        if (!TextUtils.isEmpty(year)) meta.append(year);
        if (item.voteCount > 0) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(item.voteCount).append(" 人评分");
        }
        if (meta.length() == 0 && !TextUtils.isEmpty(item.originalLanguage)) {
            meta.append(item.originalLanguage.toUpperCase(Locale.US));
        }
        helper.setText(R.id.tvMeta, meta.toString());

        ImageView ivPoster = helper.getView(R.id.ivPoster);
        String url = item.posterUrl();
        if (url.equals(ivPoster.getTag())) return;
        ivPoster.setTag(url);
        // 圆角由 posterFrame 的 clipToOutline 负责，这里 radius 传 0，避免 Glide 再复制一张位图
        ImgUtil.load(url, ivPoster, 0, 0, 0, item.displayTitle());
    }

    /**
     * 回收时断开 ImageView 对 bitmap 的引用，否则复用池里会一直囤着海报，
     * Glide 的内存缓存也没法真正淘汰它们。
     */
    @Override
    public void onViewRecycled(@NonNull BaseViewHolder holder) {
        super.onViewRecycled(holder);
        ImageView ivPoster = holder.getView(R.id.ivPoster);
        if (ivPoster != null) {
            ivPoster.setImageDrawable(null);
            ivPoster.setTag(null);
        }
    }
}
