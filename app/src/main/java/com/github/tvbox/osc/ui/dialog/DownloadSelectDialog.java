package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.DownloadRecord;
import com.github.tvbox.osc.download.DownloadManager;
import com.github.tvbox.osc.util.GlassTip;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 缓存选集弹窗：iOS 玻璃质感半屏底部抽屉（自下而上浮起，不遮挡上半屏播放预览区）。
 * 在详情页点「缓存」弹出，列出当前线路的全部选集，
 * 勾选（支持全选）后加入离线缓存队列；已缓存过的集置灰不可再选。
 *
 * @author MBox
 */
public class DownloadSelectDialog extends BaseDialog {

    /** 抽屉面板高度占屏幕比例（半屏，留出上半屏播放预览） */
    private static final float PANEL_HEIGHT_RATIO = 0.45f;

    /** 选集网格：一行小方块数量 */
    private static final int GRID_SPAN = 5;

    /** 抽屉入场：蒙层淡入时长 */
    private static final long SCRIM_IN_DURATION = 220L;
    /** 抽屉入场：面板自底部滑入时长（带轻微回弹） */
    private static final long PANEL_IN_DURATION = 300L;
    /** 抽屉出场：面板下沉时长 */
    private static final long PANEL_OUT_DURATION = 260L;

    private final VodInfo vodInfo;
    private final String sourceKey;
    private final List<VodInfo.VodSeries> episodes;
    private final boolean[] selected;
    private final boolean[] cached;
    private RecyclerView list;
    private TextView tvSubtitle;
    private View scrim;
    private View drawerPanel;
    /** 网格小方块边长（由列表宽度换算，convert 时同步撑成正方形） */
    private int cellSize;
    /** 防重复 dismiss：出场动画只播一次 */
    private boolean dismissing = false;
    private EpisodeAdapter adapter;

    public DownloadSelectDialog(@NonNull @NotNull Context context, String sourceKey, VodInfo vodInfo) {
        super(context, R.style.CustomDialogStyleDim);
        setContentView(R.layout.dialog_download_select);
        setCanceledOnTouchOutside(true);
        setupBottomWindow();
        this.sourceKey = sourceKey;
        this.vodInfo = vodInfo;
        List<VodInfo.VodSeries> list0 = vodInfo == null || vodInfo.seriesMap == null
                ? null : vodInfo.seriesMap.get(vodInfo.playFlag);
        this.episodes = list0 == null ? new ArrayList<VodInfo.VodSeries>() : list0;
        this.selected = new boolean[episodes.size()];
        this.cached = new boolean[episodes.size()];
        DownloadManager manager = DownloadManager.get();
        for (int i = 0; i < episodes.size(); i++) {
            cached[i] = manager.hasTask(sourceKey, vodInfo.id, vodInfo.playFlag, i);
            selected[i] = cached[i];
        }
        initView();
    }

    /** 底部抽屉窗口：贴底铺宽 + 半屏面板，进出动效由 View 级动画负责（见 show/dismiss） */
    private void setupBottomWindow() {
        Window window = getWindow();
        if (window == null) return;
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.gravity = Gravity.BOTTOM;
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.MATCH_PARENT;
        // 不用 windowAnimations：MATCH_PARENT 窗口的 window 动画会把蒙层和面板整体平移，观感错误；
        // 改为蒙层淡入 + 面板 translationY 自底部滑入的 View 级动效。
        window.setAttributes(lp);
        // 面板高度 = 屏幕 45%，上半屏保持可见
        DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
        drawerPanel = findViewById(R.id.drawerPanel);
        scrim = findViewById(R.id.scrim);
        if (drawerPanel != null) {
            drawerPanel.getLayoutParams().height = (int) (dm.heightPixels * PANEL_HEIGHT_RATIO);
        }
    }

    /** 入场：蒙层淡入 + 面板自底部滑入（轻微回弹） */
    @Override
    public void show() {
        // 先摆好初始态（面板沉到屏幕外），再展示窗口，避免首帧闪现完整抽屉
        if (scrim != null) scrim.setAlpha(0f);
        if (drawerPanel != null) {
            DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
            drawerPanel.setTranslationY(dm.heightPixels * PANEL_HEIGHT_RATIO);
        }
        super.show();
        if (drawerPanel == null || scrim == null) return;
        drawerPanel.post(new Runnable() {
            @Override
            public void run() {
                if (dismissing) return;
                float height = drawerPanel.getHeight();
                if (height <= 0) height = drawerPanel.getTranslationY();
                drawerPanel.setTranslationY(height);
                drawerPanel.animate()
                        .translationY(0f)
                        .setDuration(PANEL_IN_DURATION)
                        .setInterpolator(new OvershootInterpolator(1.0f))
                        .start();
                scrim.animate().alpha(1f).setDuration(SCRIM_IN_DURATION).start();
            }
        });
    }

    /** 出场：面板下沉 + 蒙层淡出，动画结束后真正 dismiss */
    @Override
    public void dismiss() {
        if (dismissing) return;
        dismissing = true;
        playExitAnim();
    }

    @Override
    public void cancel() {
        // 点蒙层 / 按返回键走 Dialog.cancel()，同样先播出场动画
        if (dismissing) return;
        dismissing = true;
        playExitAnim();
    }

    private void playExitAnim() {
        if (drawerPanel == null || drawerPanel.getHeight() <= 0) {
            DownloadSelectDialog.super.dismiss();
            return;
        }
        drawerPanel.animate()
                .translationY(drawerPanel.getHeight())
                .setDuration(PANEL_OUT_DURATION)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        DownloadSelectDialog.super.dismiss();
                    }
                })
                .start();
        if (scrim != null) {
            scrim.animate().alpha(0f).setDuration(PANEL_OUT_DURATION).start();
        }
    }

    private void initView() {
        list = findViewById(R.id.mList);
        tvSubtitle = findViewById(R.id.tvSubtitle);
        TextView tvTitle = findViewById(R.id.tvTitle);
        String flagName = vodInfo.playFlag == null ? "" : vodInfo.playFlag;
        tvTitle.setText("缓存选集 · " + flagName);
        tvSubtitle.setText((vodInfo.name == null ? "" : vodInfo.name)
                + " · 共 " + episodes.size() + " 集");

        list.setLayoutManager(new GridLayoutManager(getContext(), GRID_SPAN, GridLayoutManager.VERTICAL, false));
        adapter = new EpisodeAdapter();
        list.setAdapter(adapter);
        // 列表有宽度后换算小方块边长，让所有方块首帧即为正方形
        list.post(new Runnable() {
            @Override
            public void run() {
                int width = list.getWidth();
                if (width <= 0) return;
                float density = getContext().getResources().getDisplayMetrics().density;
                int size = width / GRID_SPAN - (int) (6 * density);
                if (size > 0 && size != cellSize) {
                    cellSize = size;
                    adapter.notifyDataSetChanged();
                }
            }
        });

        // 蒙层点击 = 关闭抽屉
        View scrim = findViewById(R.id.scrim);
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        findViewById(R.id.btnCancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        findViewById(R.id.btnAll).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean allSelected = true;
                for (int i = 0; i < episodes.size(); i++) {
                    if (!cached[i] && !selected[i]) {
                        allSelected = false;
                        break;
                    }
                }
                for (int i = 0; i < episodes.size(); i++) {
                    if (!cached[i]) selected[i] = !allSelected;
                }
                adapter.notifyDataSetChanged();
            }
        });
        findViewById(R.id.btnConfirm).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirm();
            }
        });
    }

    private void confirm() {
        int count = 0;
        for (int i = 0; i < episodes.size(); i++) {
            if (cached[i] || !selected[i]) continue;
            VodInfo.VodSeries series = episodes.get(i);
            DownloadRecord record = new DownloadRecord();
            record.sourceKey = sourceKey;
            record.vodId = vodInfo.id;
            record.vodName = vodInfo.name;
            record.vodPic = vodInfo.pic;
            record.playFlag = vodInfo.playFlag;
            record.playIndex = i;
            record.episodeName = series.name;
            record.url = series.url;
            if (DownloadManager.get().addTask(record)) count++;
        }
        if (count > 0) {
            GlassTip.show(getContext(), "已加入缓存队列 " + count + " 集");
        } else {
            GlassTip.show(getContext(), "没有可缓存的新选集");
        }
        dismiss();
    }

    private class EpisodeAdapter extends BaseQuickAdapter<VodInfo.VodSeries, BaseViewHolder> {

        EpisodeAdapter() {
            super(R.layout.item_download_select, episodes);
        }

        @Override
        protected void convert(final BaseViewHolder helper, final VodInfo.VodSeries item) {
            final int position = helper.getLayoutPosition();
            TextView tvIndex = helper.getView(R.id.tvIndex);
            TextView tvCachedTag = helper.getView(R.id.tvCachedTag);
            final View cell = helper.itemView;

            tvIndex.setText(String.valueOf(position + 1));
            if (cached[position]) {
                // 已缓存：置灰 + 角标勾，不可再选
                tvIndex.setBackgroundResource(R.drawable.bg_glass_chip);
                tvIndex.setAlpha(0.45f);
                tvCachedTag.setVisibility(View.VISIBLE);
            } else if (selected[position]) {
                tvIndex.setBackgroundResource(R.drawable.bg_chip_selected);
                tvIndex.setAlpha(1f);
                tvCachedTag.setVisibility(View.GONE);
            } else {
                tvIndex.setBackgroundResource(R.drawable.bg_glass_chip);
                tvIndex.setAlpha(1f);
                tvCachedTag.setVisibility(View.GONE);
            }

            // 小方块：优先用换算好的边长同步成正方形，post 仅作兜底微调
            if (cellSize > 0) {
                ViewGroup.LayoutParams lp = cell.getLayoutParams();
                if (lp == null) {
                    lp = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cellSize);
                    cell.setLayoutParams(lp);
                } else if (lp.height != cellSize) {
                    lp.height = cellSize;
                    cell.setLayoutParams(lp);
                }
            }
            cell.post(new Runnable() {
                @Override
                public void run() {
                    int width = cell.getWidth();
                    if (width > 0) {
                        ViewGroup.LayoutParams lp = cell.getLayoutParams();
                        if (lp == null || lp.height != width) {
                            if (lp == null) {
                                lp = new ViewGroup.LayoutParams(width, width);
                            } else {
                                lp.height = width;
                            }
                            cell.setLayoutParams(lp);
                        }
                    }
                }
            });

            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (cached[position]) return;
                    selected[position] = !selected[position];
                    tvIndex.setBackgroundResource(selected[position]
                            ? R.drawable.bg_chip_selected
                            : R.drawable.bg_glass_chip);
                }
            });
        }
    }
}
