package com.github.tvbox.osc.ui.widget;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.MovieSort;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 首页分类标签旁的细分筛选下拉面板。
 *
 * <p>数据源在 {@link MovieSort.SortData#filters} 里给出若干个筛选维度，每个维度是一组
 * 「显示名 -&gt; 请求值」。面板贴着标签展开，每个维度单选（再点一次取消），
 * 关闭时把选择写回 {@link MovieSort.SortData#filterSelect}，由调用方决定是否刷新列表。</p>
 */
public class CategoryFilterPopup {

    public interface OnFilterClosedListener {
        /** @param changed 本次打开期间选择是否发生变化 */
        void onClosed(boolean changed);
    }

    private final Context context;
    private final LayoutInflater inflater;

    private PopupWindow popup;
    private View panel;
    private LinearLayout filterList;
    private TextView filterTitle;

    private MovieSort.SortData sortData;
    private OnFilterClosedListener listener;
    /** 面板里的临时选择，关闭时才提交 */
    private final Map<String, String> pending = new HashMap<>();
    /** 每个维度当前选中的 chip，便于单选切换 */
    private final Map<String, View> selectedChips = new HashMap<>();

    private boolean dismissing;
    private boolean applied;

    public CategoryFilterPopup(Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
    }

    public boolean isShowing() {
        return popup != null && popup.isShowing();
    }

    public void show(View anchor, MovieSort.SortData data, OnFilterClosedListener l) {
        if (anchor == null || data == null || data.filters == null || data.filters.isEmpty()) return;
        this.sortData = data;
        this.listener = l;
        this.pending.clear();
        if (data.filterSelect != null) {
            this.pending.putAll(data.filterSelect);
        }
        this.dismissing = false;
        this.applied = false;

        ensurePopup();
        bindFilters();

        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        int screenHeight = context.getResources().getDisplayMetrics().heightPixels;
        int width = Math.min(dp(360), screenWidth - dp(24));

        // 先量一次，给 ScrollView 一个确定的最大高度，避免选项很多时撑满整屏
        View content = popup.getContentView();
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int measuredHeight = content.getMeasuredHeight();
        int maxHeight = (int) (screenHeight * 0.62f);
        popup.setWidth(width);
        popup.setHeight(measuredHeight > 0 ? Math.min(measuredHeight, maxHeight)
                : ViewGroup.LayoutParams.WRAP_CONTENT);

        // showAsDropDown 的 xoff 相对 anchor 左边；贴着标签，但不越过屏幕两侧
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        int x = Math.max(dp(12), Math.min(location[0] - dp(12), screenWidth - width - dp(12)));
        popup.showAsDropDown(anchor, x - location[0], dp(8));
        playEnterAnimation();
    }

    /** 页面销毁 / 切源：立刻收起，不提交、不触发刷新 */
    public void dismissImmediately() {
        applied = true;
        dismissing = true;
        if (popup != null && popup.isShowing()) {
            popup.dismiss();
        }
    }

    /** 用户点「完成」或点面板外部：收起并按需提交 */
    public void dismiss() {
        if (popup == null || !popup.isShowing() || dismissing) return;
        dismissing = true;
        panel.animate()
                .scaleX(0.94f)
                .scaleY(0.94f)
                .alpha(0f)
                .setDuration(120)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (popup != null && popup.isShowing()) {
                            popup.dismiss();
                        }
                    }
                })
                .start();
    }

    private void ensurePopup() {
        if (popup != null) return;
        View content = inflater.inflate(R.layout.popup_category_filter, null);
        panel = content.findViewById(R.id.filterPanel);
        filterList = content.findViewById(R.id.filterList);
        filterTitle = content.findViewById(R.id.filterTitle);
        TextView reset = content.findViewById(R.id.filterReset);
        TextView done = content.findViewById(R.id.filterDone);

        popup = new PopupWindow(content,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true);
        // 透明背景 + outsideTouchable：点面板外才能收起
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setFocusable(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            popup.setElevation(dp(12));
        }
        popup.setOnDismissListener(new PopupWindow.OnDismissListener() {
            @Override
            public void onDismiss() {
                if (panel != null) {
                    panel.setAlpha(1f);
                    panel.setScaleX(1f);
                    panel.setScaleY(1f);
                }
                commitIfChanged();
            }
        });

        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (View chip : selectedChips.values()) {
                    chip.setSelected(false);
                }
                selectedChips.clear();
                pending.clear();
            }
        });
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
    }

    private void commitIfChanged() {
        if (applied) return;
        applied = true;
        boolean changed = sortData != null && !sameSelection(pending, sortData.filterSelect);
        if (changed) {
            if (sortData.filterSelect == null) {
                sortData.filterSelect = new HashMap<>();
            }
            sortData.filterSelect.clear();
            sortData.filterSelect.putAll(pending);
        }
        if (listener != null) {
            listener.onClosed(changed);
        }
    }

    private static boolean sameSelection(Map<String, String> a, Map<String, String> b) {
        if (a == null || b == null) return a == b;
        if (a.size() != b.size()) return false;
        for (Map.Entry<String, String> entry : a.entrySet()) {
            if (!Objects.equals(entry.getValue(), b.get(entry.getKey()))) return false;
        }
        return true;
    }

    private void bindFilters() {
        filterList.removeAllViews();
        selectedChips.clear();
        filterTitle.setText(context.getString(R.string.home_filter_for, sortData.name));

        for (final MovieSort.SortFilter filter : sortData.filters) {
            if (filter == null || filter.values == null || filter.values.isEmpty()) continue;
            final String key = filter.key;
            View row = inflater.inflate(R.layout.popup_category_filter_row, filterList, false);
            TextView name = row.findViewById(R.id.filterName);
            name.setText(filter.name);
            FilterChipGroup chips = row.findViewById(R.id.filterChips);

            String current = pending.get(key);
            for (final Map.Entry<String, String> option : filter.values.entrySet()) {
                TextView chip = (TextView) inflater.inflate(R.layout.item_category_filter_chip, chips, false);
                chip.setText(option.getKey());
                if (option.getValue() != null && option.getValue().equals(current)) {
                    chip.setSelected(true);
                    selectedChips.put(key, chip);
                }
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        View previous = selectedChips.get(key);
                        if (previous == v) {
                            v.setSelected(false);
                            selectedChips.remove(key);
                            pending.remove(key);
                        } else {
                            if (previous != null) previous.setSelected(false);
                            v.setSelected(true);
                            selectedChips.put(key, v);
                            pending.put(key, option.getValue());
                        }
                    }
                });
                chips.addView(chip);
            }
            filterList.addView(row);
        }
    }

    /** 从标签方向弹出：原点在左上角，先小一号再 Overshoot 回原尺寸 */
    private void playEnterAnimation() {
        panel.post(new Runnable() {
            @Override
            public void run() {
                if (!isShowing()) return;
                panel.setPivotX(0f);
                panel.setPivotY(0f);
                panel.setScaleX(0.88f);
                panel.setScaleY(0.88f);
                panel.setAlpha(0f);
                panel.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(220)
                        .setInterpolator(new OvershootInterpolator(0.8f))
                        .start();
            }
        });
    }

    private int dp(float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
