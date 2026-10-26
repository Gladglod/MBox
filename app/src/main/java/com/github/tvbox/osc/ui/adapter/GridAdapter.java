package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.util.ImgUtil;

import java.util.ArrayList;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * 首页 / 分类页的海报网格。
 *
 * <p><b>关于图片尺寸</b>：这里刻意<b>不</b>给 Glide 传 override 尺寸，交给它按 ImageView
 * 的实测宽高解码。原因：</p>
 * <ul>
 *     <li>item_grid 的 posterFrame 高度是写死的 162dp、宽度 match_parent，尺寸由
 *         GridLayoutManager 的列数决定，adapter 拿不到可靠数值；</li>
 *     <li>原来传的是 {@code ImgUtil.defaultWidth(244dp) × defaultHeight(320dp)}，
 *         这是 TV 版 4 列布局的设计稿尺寸。手机竖屏是 3 列，海报实际只有约 97dp 宽，
 *         再经 AutoSize 改写后的 density(3.0) 换算，会算成 732×960，
 *         而真正显示只有约 292×486 —— 解码量和内存都多了 5 倍，
 *         内存缓存只够存十几张，滑两屏就全部被淘汰，表现就是「图老是加载不出来」。</li>
 *     <li>不传 override 时 Glide 的 {@code ViewTarget.SizeDeterminer} 会等 View 测量完
 *         再按实际宽高解码，永远和显示尺寸一致。</li>
 * </ul>
 */
public class GridAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {
    private boolean mShowList;
    public ImgUtil.Style style;
    // dp→px 转换每帧都算一遍太浪费，这里缓存一次
    private int radiusPx = -1;

    public GridAdapter(boolean showList, ImgUtil.Style style) {
        super(showList ? R.layout.item_list : R.layout.item_grid, new ArrayList<>());
        this.mShowList = showList;
        if (style != null) {
            if (style.type.equals("list")) this.mShowList = true;
        }
        this.style = style;
    }

    private void ensureRadius() {
        if (radiusPx > 0) return;
        radiusPx = AutoSizeUtils.mm2px(mContext, 10);
    }

    @Override
    protected void convert(BaseViewHolder helper, Movie.Video item) {
        if (this.mShowList) {
            ensureRadius();
            helper.setText(R.id.tvNote, item.note);
            helper.setText(R.id.tvName, item.name);
            ImageView ivThumb = helper.getView(R.id.ivThumb);
            bindImage(ivThumb, item);
            return;
        }

        TextView tvYear = helper.getView(R.id.tvYear);
        if (item.year <= 0) {
            if (tvYear.getVisibility() != View.GONE) tvYear.setVisibility(View.GONE);
        } else {
            tvYear.setText(String.valueOf(item.year));
            if (tvYear.getVisibility() != View.VISIBLE) tvYear.setVisibility(View.VISIBLE);
        }
        if (TextUtils.isEmpty(item.note)) {
            helper.setVisible(R.id.tvNote, false);
        } else {
            helper.setVisible(R.id.tvNote, true);
            helper.setText(R.id.tvNote, item.note);
        }
        helper.setText(R.id.tvName, item.name);
        // tvActor 在 item_grid 里是 GONE 的，setText 会白白触发一次 requestLayout，
        // 一次 bind 十几项时这笔开销并不小，直接不设置。
        ImageView ivThumb = helper.getView(R.id.ivThumb);
        bindImage(ivThumb, item);
        applyStyleToImage(ivThumb);
    }

    /**
     * 绑定海报。用 tag 记住当前图，复用到同一个 item 时不再重复发 Glide 请求——
     * 滚动过程中大量 bind 都是"图没变"，重复请求是掉帧的主要来源之一。
     */
    private void bindImage(ImageView ivThumb, Movie.Video item) {
        String pic = item.pic == null ? "" : item.pic.trim();
        if (pic.equals(ivThumb.getTag())) return;
        ivThumb.setTag(pic);
        if (TextUtils.isEmpty(pic)) {
            ivThumb.setImageDrawable(ImgUtil.createTextDrawable(item.name));
        } else if (ImgUtil.isBase64Image(pic)) {
            ivThumb.setImageBitmap(ImgUtil.decodeBase64ToBitmap(pic));
        } else {
            // 网格模式的圆角由 posterFrame 的 clipToOutline 负责，不需要 Glide 再复制一张
            // 全尺寸 bitmap 去切圆角；列表模式（item_list）没有 outline，仍交给 Glide 裁。
            // 宽高传 0：让 Glide 按 ImageView 实际尺寸解码，见类注释。
            int radius = mShowList ? radiusPx : 0;
            ImgUtil.load(pic, ivThumb, radius, 0, 0, item.name);
        }
    }

    /**
     * ViewHolder 进复用池后，ImageView 仍然持有上一张海报的 bitmap 引用，
     * 会让 Glide 的内存缓存无法真正淘汰这些图（池子一大就白白囤几百 KB × N）。
     * 这里主动断开引用；tag 也要一起清，否则下次 bind 到同一张图会被误判成「已加载」而留白。
     */
    @Override
    public void onViewRecycled(@NonNull BaseViewHolder holder) {
        super.onViewRecycled(holder);
        ImageView ivThumb = holder.getView(R.id.ivThumb);
        if (ivThumb != null) {
            ivThumb.setImageDrawable(null);
            ivThumb.setTag(null);
        }
    }

    /**
     * 数据源自定义了海报比例（style）时，按<b>实际列宽</b>换算高度。
     *
     * <p>原来是用 {@code mm2px(getStyleDefaultWidth(style))} 算绝对宽度（280dp → 840px），
     * 比手机 3 列的列宽（约 292px）大得多，posterFrame 会被父容器裁掉一大截。
     * 现在只改高度、宽度继续跟随列宽，既不会溢出也不会破坏网格。</p>
     */
    private void applyStyleToImage(ImageView ivThumb) {
        if (style == null || style.ratio <= 0) return;
        ViewGroup container = (ViewGroup) ivThumb.getParent();
        if (container == null) return;
        int width = container.getWidth();
        if (width <= 0) return; // 还没测量完，等下一次 bind 再调整
        int height = Math.max(1, (int) (width / style.ratio));
        ViewGroup.LayoutParams lp = container.getLayoutParams();
        if (lp == null || lp.height == height) return;
        lp.height = height;
        container.setLayoutParams(lp);
    }
}
