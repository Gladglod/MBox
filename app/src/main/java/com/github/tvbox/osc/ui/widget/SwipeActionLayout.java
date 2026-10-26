package com.github.tvbox.osc.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * 左滑露出操作按钮的容器。
 * <p>
 * XML 里第一个子 View 是「操作区」（靠右显示），第二个子 View 是「内容区」。
 * 内容区被手指左滑后整体左移，露出下面的操作区按钮；同一时刻只允许一个打开。
 */
public class SwipeActionLayout extends FrameLayout {

    private static SwipeActionLayout currentOpen;

    private View contentView;
    private View actionsView;
    private int actionsWidth;
    private int touchSlop;

    private float downX;
    private float downY;
    private boolean dragging;
    private boolean opened;
    private boolean swipeEnabled = true;

    public SwipeActionLayout(Context context) {
        super(context);
        init(context);
    }

    public SwipeActionLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public SwipeActionLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (getChildCount() >= 2) {
            actionsView = getChildAt(0);
            contentView = getChildAt(1);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (actionsView != null) {
            actionsWidth = actionsView.getWidth();
        }
        if (contentView != null && !opened) {
            contentView.setTranslationX(0f);
        }
    }

    public void setSwipeEnabled(boolean enabled) {
        swipeEnabled = enabled;
        if (!enabled) close();
    }

    public boolean isOpened() {
        return opened;
    }

    public void close() {
        settle(false);
    }

    /** 列表复用时立刻复位，不走动画 */
    public void resetNow() {
        if (contentView != null) contentView.setTranslationX(0f);
        opened = false;
        if (currentOpen == this) currentOpen = null;
    }

    private void settle(boolean open) {
        if (contentView == null) return;
        if (open && currentOpen != null && currentOpen != this) {
            currentOpen.close();
        }
        opened = open;
        if (open) {
            currentOpen = this;
            contentView.animate().translationX(-actionsWidth).setDuration(180).start();
        } else {
            if (currentOpen == this) currentOpen = null;
            contentView.animate().translationX(0f).setDuration(180).start();
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (contentView == null || actionsWidth <= 0) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                // 已经展开时，点内容区先收起（不触发条目点击）
                if (opened && ev.getX() < getWidth() - actionsWidth) {
                    close();
                    return true;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (!swipeEnabled) return false;
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    return true;
                }
                break;
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (contentView == null || actionsWidth <= 0) return super.onTouchEvent(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = false;
                // 交给 View 自己处理，保证点击能正常触发 performClick
                return super.onTouchEvent(ev);
            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getX() - downX;
                if (!dragging) {
                    if (Math.abs(dx) <= touchSlop) return super.onTouchEvent(ev);
                    dragging = true;
                }
                float base = opened ? -actionsWidth : 0f;
                float target = base + dx;
                if (target > 0f) target = 0f;
                if (target < -actionsWidth) target = -actionsWidth;
                contentView.setTranslationX(target);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (dragging) {
                    dragging = false;
                    float current = contentView.getTranslationX();
                    settle(current < -actionsWidth / 2f);
                    return true;
                }
                return super.onTouchEvent(ev);
            }
            default:
                return super.onTouchEvent(ev);
        }
    }
}
