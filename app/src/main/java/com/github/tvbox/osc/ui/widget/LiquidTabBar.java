package com.github.tvbox.osc.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

/**
 * 底部导航胶囊的容器。本身不画任何东西，只负责一件事：
 * 在用户横向拖动时把触摸从 Tab 手里"抢"过来，交给指示器跟随。
 *
 * <p>为什么需要抢：Tab 是 clickable 的，会消费掉所有触摸事件，
 * 父容器上的 OnTouchListener 根本收不到 MOVE。只有在
 * {@link #onInterceptTouchEvent} 里判断"这是一次横向拖拽"之后提前返回 true，
 * 才能既保留点击、又支持拖拽 —— 这正是 ScrollView / ViewPager 处理
 * "点击还是滑动"的标准做法。
 *
 * <p>拖动阈值用系统的 touchSlop，并且要求横向位移明显大于纵向，
 * 否则手指稍微斜着点一下就会误判成拖拽，Tab 就点不动了。
 */
public class LiquidTabBar extends FrameLayout {

    public interface OnDragListener {
        /** 刚越过 touchSlop，此时应记录起始位置 */
        void onDragStart();

        /** 相对按下点的横向位移（px） */
        void onDrag(float dx);

        /** 松手，参数为最终位移，用于判断落在哪个 Tab */
        void onDragEnd(float dx);
    }

    private final int touchSlop;
    private float downX;
    private float downY;
    private boolean dragging;
    private OnDragListener dragListener;

    public LiquidTabBar(Context context) {
        this(context, null);
    }

    public LiquidTabBar(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LiquidTabBar(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setOnDragListener(OnDragListener listener) {
        this.dragListener = listener;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) {
                    float dx = Math.abs(ev.getX() - downX);
                    float dy = Math.abs(ev.getY() - downY);
                    // 横向位移要同时超过阈值、并且明显大于纵向，才算拖拽
                    if (dx > touchSlop && dx > dy * 1.2f) {
                        dragging = true;
                        if (dragListener != null) dragListener.onDragStart();
                        return true;
                    }
                }
                break;
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!dragging) {
            return super.onTouchEvent(ev);
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (dragListener != null) dragListener.onDrag(ev.getX() - downX);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (dragListener != null) dragListener.onDragEnd(ev.getX() - downX);
                return true;
            default:
                return true;
        }
    }
}
