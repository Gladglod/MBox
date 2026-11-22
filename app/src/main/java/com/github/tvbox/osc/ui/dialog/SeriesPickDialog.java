package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
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
import com.github.tvbox.osc.util.FastClickCheckUtil;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 选集列表抽屉：iOS 玻璃质感半屏底部抽屉（与「缓存选集」抽屉同一套设计）。
 * 在详情页点「列表」弹出，把当前线路的全部选集以一行 5 个的表格展开，
 * 长剧集也能快速查找并选集播放；当前集高亮。
 */
public class SeriesPickDialog extends BaseDialog {

    public interface OnSeriesPickListener {
        void onSeriesPick(int position);
    }

    /** 抽屉面板高度占屏幕比例（表格需要更高一些，方便长剧集查找） */
    private static final float PANEL_HEIGHT_RATIO = 0.60f;
    /** 选集表格：一行 5 个 */
    private static final int GRID_SPAN = 5;

    private static final long SCRIM_IN_DURATION = 220L;
    private static final long PANEL_IN_DURATION = 300L;
    private static final long PANEL_OUT_DURATION = 260L;

    private final List<VodInfo.VodSeries> episodes;
    private final int selectedPosition;
    private final OnSeriesPickListener listener;

    private View scrim;
    private View drawerPanel;
    private RecyclerView list;
    private GridLayoutManager layoutManager;
    private EpisodeAdapter adapter;
    /** 防重复 dismiss：出场动画只播一次 */
    private boolean dismissing = false;

    public SeriesPickDialog(@NonNull @NotNull Context context, String title, String flagName,
                            List<VodInfo.VodSeries> episodes, int selectedPosition,
                            OnSeriesPickListener listener) {
        super(context, R.style.CustomDialogStyleDim);
        if (context instanceof Activity) {
            setOwnerActivity((Activity) context);
        }
        this.episodes = episodes == null ? new ArrayList<VodInfo.VodSeries>() : new ArrayList<>(episodes);
        this.selectedPosition = Math.max(0, Math.min(selectedPosition, Math.max(0, this.episodes.size() - 1)));
        this.listener = listener;
        setContentView(R.layout.dialog_series_pick);
        setCanceledOnTouchOutside(true);
        setupBottomWindow();
        initView(title, flagName);
    }

    /** 底部抽屉窗口：贴底铺宽 + 半屏面板，进出动效由 View 级动画负责（见 show/dismiss） */
    private void setupBottomWindow() {
        Window window = getWindow();
        if (window == null) {
            return;
        }
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.gravity = Gravity.BOTTOM;
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.MATCH_PARENT;
        window.setAttributes(lp);
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
        if (scrim != null) {
            scrim.setAlpha(0f);
        }
        if (drawerPanel != null) {
            DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
            drawerPanel.setTranslationY(dm.heightPixels * PANEL_HEIGHT_RATIO);
        }
        super.show();
        if (drawerPanel == null || scrim == null) {
            return;
        }
        drawerPanel.post(new Runnable() {
            @Override
            public void run() {
                if (dismissing) {
                    return;
                }
                float height = drawerPanel.getHeight();
                if (height <= 0) {
                    height = drawerPanel.getTranslationY();
                }
                drawerPanel.setTranslationY(height);
                drawerPanel.animate()
                        .translationY(0f)
                        .setDuration(PANEL_IN_DURATION)
                        .setInterpolator(new OvershootInterpolator(1.0f))
                        .start();
                scrim.animate().alpha(1f).setDuration(SCRIM_IN_DURATION).start();
            }
        });
        // 滚到当前集并让焦点落在当前集上（遥控器可直接上下左右找集）
        if (list != null && layoutManager != null) {
            list.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (dismissing || list == null) {
                        return;
                    }
                    scrollToSelectedPosition();
                    list.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            View selectedView = layoutManager == null ? null
                                    : layoutManager.findViewByPosition(selectedPosition);
                            if (selectedView != null) {
                                selectedView.requestFocus();
                            }
                        }
                    }, 60);
                }
            }, PANEL_IN_DURATION);
        }
    }

    /** 出场：面板下沉 + 蒙层淡出，动画结束后真正 dismiss */
    @Override
    public void dismiss() {
        if (dismissing) {
            return;
        }
        dismissing = true;
        playExitAnim();
    }

    @Override
    public void cancel() {
        // 点蒙层 / 按返回键走 Dialog.cancel()，同样先播出场动画
        if (dismissing) {
            return;
        }
        dismissing = true;
        playExitAnim();
    }

    private void playExitAnim() {
        if (drawerPanel == null || drawerPanel.getHeight() <= 0) {
            SeriesPickDialog.super.dismiss();
            return;
        }
        drawerPanel.animate()
                .translationY(drawerPanel.getHeight())
                .setDuration(PANEL_OUT_DURATION)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        SeriesPickDialog.super.dismiss();
                    }
                })
                .start();
        if (scrim != null) {
            scrim.animate().alpha(0f).setDuration(PANEL_OUT_DURATION).start();
        }
    }

    private void initView(String title, String flagName) {
        TextView tvTitle = findViewById(R.id.tvTitle);
        tvTitle.setText("选集 · " + (TextUtils.isEmpty(flagName) ? "" : flagName));
        TextView tvSubtitle = findViewById(R.id.tvSubtitle);
        tvSubtitle.setText((title == null ? "" : title) + " · 共 " + episodes.size() + " 集");

        TextView btnCancel = findViewById(R.id.btnCancel);
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                dismiss();
            }
        });
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });

        list = findViewById(R.id.mList);
        list.setHasFixedSize(true);
        layoutManager = new GridLayoutManager(getContext(), GRID_SPAN, GridLayoutManager.VERTICAL, false);
        list.setLayoutManager(layoutManager);
        adapter = new EpisodeAdapter();
        list.setAdapter(adapter);
        adapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter baseAdapter, View view, int position) {
                FastClickCheckUtil.check(view);
                dismiss();
                if (listener != null) {
                    listener.onSeriesPick(position);
                }
            }
        });
        adapter.setNewData(episodes);
        scrollToSelectedPosition();
    }

    private void scrollToSelectedPosition() {
        if (layoutManager == null) {
            return;
        }
        // 当前集上方预留两排，避免贴在顶部时遥控上键找不到可移动的焦点项
        int rowsBeforeSelected = 2;
        int startPosition = Math.max(0, selectedPosition - layoutManager.getSpanCount() * rowsBeforeSelected);
        layoutManager.scrollToPositionWithOffset(startPosition, 0);
    }

    private class EpisodeAdapter extends BaseQuickAdapter<VodInfo.VodSeries, BaseViewHolder> {

        EpisodeAdapter() {
            super(R.layout.item_series_pick, episodes);
        }

        @Override
        protected void convert(BaseViewHolder helper, VodInfo.VodSeries item) {
            TextView tvSeries = helper.getView(R.id.tvSeriesName);
            tvSeries.setText(item == null || TextUtils.isEmpty(item.name) ? "" : item.name);
            android.content.res.Resources res = helper.itemView.getContext().getResources();
            boolean selected = helper.getLayoutPosition() == selectedPosition;
            // 背景由 item 布局的 selector 按 selected/focused 切换，这里只同步选中态与文字色
            helper.itemView.setSelected(selected);
            tvSeries.setTextColor(res.getColor(selected ? R.color.ui_accent : R.color.dialog_text_primary));
        }
    }
}
