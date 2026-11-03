package com.github.tvbox.osc.ui.widget;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.SourceBean;

import java.util.ArrayList;
import java.util.List;

/**
 * 右上角数据源切换的下拉菜单。
 *
 * <p>原本是一个居中的 {@code SelectDialog}：全屏压暗 + 中间一块面板，成本很高
 * （视线要离开顶栏、页面上下文被遮住），信息量却只有「选一个站点」。
 * 换成贴着胶囊往下展开的菜单之后，用户的目光不用离开右上角。
 *
 * <p>动效沿用底部导航那一套：纵向的 {@link GlassIndicatorView} 在选中行背后滑动，
 * 弹簧驱动、可被连续点击打断；面板本身从右上角原点弹出（Overshoot），
 * 收起时快速缩回。
 */
public class SourceSwitchPopup {

    public interface OnSourceSelectedListener {
        void onSourceSelected(SourceBean bean);
    }

    /** 最多同时显示几行，超过就滚动。7 行 ≈ 308dp，加上顶栏仍在半屏以内 */
    private static final int MAX_VISIBLE_ROWS = 7;

    private final Context context;
    private final LayoutInflater inflater;

    private PopupWindow popup;
    private FrameLayout panel;
    private GlassIndicatorView indicator;
    private ScrollView scroll;
    private LinearLayout list;

    private final List<SourceBean> data = new ArrayList<>();
    private int selectedIndex;
    private OnSourceSelectedListener listener;
    private int rowHeight;
    private boolean dismissing;

    public SourceSwitchPopup(Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.rowHeight = context.getResources().getDimensionPixelSize(R.dimen.vs_108);
    }

    public boolean isShowing() {
        return popup != null && popup.isShowing();
    }

    /**
     * 在 anchor 正下方展开，右边缘与 anchor 对齐。
     *
     * @param sites    可切换的站点
     * @param selected 当前站点下标
     */
    public void show(View anchor, List<SourceBean> sites, int selected, OnSourceSelectedListener l) {
        if (anchor == null || sites == null || sites.isEmpty()) return;
        if (isShowing()) return;
        this.listener = l;
        this.selectedIndex = Math.max(0, Math.min(selected, sites.size() - 1));
        this.data.clear();
        this.data.addAll(sites);
        this.dismissing = false;

        ensureContentView();
        bindRows();

        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(dp(260), (int) (screenWidth * 0.72f));
        int visibleRows = Math.min(data.size(), MAX_VISIBLE_ROWS);
        int height = visibleRows * rowHeight + dp(12);

        popup.setWidth(width);
        popup.setHeight(height);

        // showAsDropDown 的 xoff 相对 anchor 左边；右对齐 = 让弹窗右边贴住 anchor 右边
        int xoff = anchor.getWidth() - width;
        popup.showAsDropDown(anchor, xoff, dp(6));
        playEnterAnimation(width);
    }

    public void dismiss() {
        if (popup == null || !popup.isShowing() || dismissing) return;
        dismissing = true;
        panel.animate()
                .scaleX(0.92f)
                .scaleY(0.92f)
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

    /** 页面销毁等场景：立刻收，不播动画 */
    public void dismissImmediately() {
        dismissing = true;
        if (popup != null && popup.isShowing()) {
            popup.dismiss();
        }
    }

    private void ensureContentView() {
        if (popup != null) return;
        View content = inflater.inflate(R.layout.popup_source_menu, null);
        panel = content.findViewById(R.id.menuPanel);
        indicator = content.findViewById(R.id.menuIndicator);
        scroll = content.findViewById(R.id.menuScroll);
        list = content.findViewById(R.id.menuList);

        indicator.setOrientation(GlassIndicatorView.VERTICAL);
        indicator.setCrossInset(dp(6));
        // 菜单是列表不是胶囊，圆角取 10dp，和行高 44dp 配起来是个圆角矩形
        indicator.setCornerRadius(dp(10));

        popup = new PopupWindow(content,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true);
        // 透明背景 + outsideTouchable：点外面才收得掉；不加这行 setOutsideTouchable 不生效
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setFocusable(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            popup.setElevation(dp(12));
        }
        popup.setOnDismissListener(new PopupWindow.OnDismissListener() {
            @Override
            public void onDismiss() {
                // 面板被系统收起（返回键 / 点外部）后，复位缩放，下次展开才不会是 0 尺寸
                if (panel != null) {
                    panel.setAlpha(1f);
                    panel.setScaleX(1f);
                    panel.setScaleY(1f);
                }
            }
        });

        // 滚动时指示器要跟着行一起走，否则选中项被滚出视野后透镜会停在错误位置
        scroll.getViewTreeObserver().addOnScrollChangedListener(
                new ViewTreeObserver.OnScrollChangedListener() {
                    @Override
                    public void onScrollChanged() {
                        moveIndicator(false);
                    }
                });
    }

    private void bindRows() {
        list.removeAllViews();
        for (int i = 0; i < data.size(); i++) {
            final int index = i;
            TextView row = (TextView) inflater.inflate(R.layout.item_source_menu_row, list, false);
            SourceBean bean = data.get(i);
            row.setText(bean.getName() == null ? "" : bean.getName());
            row.setTextColor(ContextCompat.getColor(context,
                    index == selectedIndex ? R.color.ui_accent : R.color.ui_text_primary));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (dismissing) return;
                    if (listener != null) {
                        listener.onSourceSelected(data.get(index));
                    }
                    dismiss();
                }
            });
            list.addView(row);
        }
        // 首次展开不播弹簧：指示器应该"本来就在"选中项后面
        list.post(new Runnable() {
            @Override
            public void run() {
                moveIndicator(false);
                scrollToSelected();
            }
        });
    }

    /** 选中项在列表下方时先滚过去，别让用户展开后还要自己找 */
    private void scrollToSelected() {
        if (scroll == null || list.getChildCount() == 0) return;
        View row = list.getChildAt(selectedIndex);
        if (row == null) return;
        int maxScroll = Math.max(0, list.getHeight() - scroll.getHeight());
        int target = row.getTop() - (scroll.getHeight() - row.getHeight()) / 2;
        scroll.scrollTo(0, Math.max(0, Math.min(target, maxScroll)));
    }

    private void moveIndicator(boolean animate) {
        if (indicator == null || list == null || list.getChildCount() == 0) return;
        if (selectedIndex < 0 || selectedIndex >= list.getChildCount()) return;
        View row = list.getChildAt(selectedIndex);
        if (row == null) return;
        int scrollY = scroll == null ? 0 : scroll.getScrollY();
        indicator.setSpan(row.getTop() - scrollY, row.getBottom() - scrollY, animate);
    }

    /**
     * 从锚点方向弹出：原点定在右上角，先小一号再 Overshoot 回原尺寸。
     * 不用纯 alpha 淡入 —— 淡入没有"从哪里来"的信息，缩放才有。
     */
    private void playEnterAnimation(final int width) {
        panel.post(new Runnable() {
            @Override
            public void run() {
                if (!isShowing()) return;
                panel.setPivotX(width);
                panel.setPivotY(0f);
                panel.setScaleX(0.86f);
                panel.setScaleY(0.86f);
                panel.setAlpha(0f);
                panel.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(240)
                        .setInterpolator(new OvershootInterpolator(0.8f))
                        .start();
            }
        });
    }

    private int dp(float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
