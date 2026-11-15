package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.ui.adapter.SourceSpeedAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.SourceSpeedTester;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 换源抽屉：iOS 玻璃质感半屏底部抽屉（与「缓存选集」抽屉同一套设计）。
 * 列出所有可切换的源，一行一条；后台测速显示延迟（带缓存，10 分钟内直接复用），
 * 同时后台全源预搜索本片，标记每个源「有片源/无片源」，命中的源点选可直接切换。
 */
public class SourceSpeedDialog extends BaseDialog {

    public interface OnSourcePickListener {
        void onSourcePick(SourceBean source, Movie.Video matchedVideo);
    }

    /** 抽屉面板高度占屏幕比例（半屏，留出上半屏播放预览） */
    private static final float PANEL_HEIGHT_RATIO = 0.45f;
    /** 抽屉入场：蒙层淡入时长 */
    private static final long SCRIM_IN_DURATION = 220L;
    /** 抽屉入场：面板自底部滑入时长（带轻微回弹） */
    private static final long PANEL_IN_DURATION = 300L;
    /** 抽屉出场：面板下沉时长 */
    private static final long PANEL_OUT_DURATION = 260L;

    private RecyclerView mRecyclerView;
    private SourceSpeedAdapter speedAdapter;
    private final List<SourceSpeedAdapter.Item> items = new java.util.ArrayList<>();
    private OnSourcePickListener pickListener;
    private boolean released = false;
    /** 两个后台阶段都结束后做一次「有片源优先 + 延迟升序」的最终排序 */
    private boolean latencyDone = false;
    private boolean searchDone = false;
    private int resolvedSearchCount = 0;

    private View scrim;
    private View drawerPanel;
    /** 防重复 dismiss：出场动画只播一次 */
    private boolean dismissing = false;

    public SourceSpeedDialog(@NonNull @NotNull Context context, List<SourceBean> sources, String title, OnSourcePickListener listener) {
        super(context, R.style.CustomDialogStyleDim);
        if (context instanceof Activity) {
            setOwnerActivity((Activity) context);
        }
        setCanceledOnTouchOutside(true);
        setContentView(R.layout.dialog_source_speed);
        setupBottomWindow();
        for (SourceBean source : sources) {
            SourceSpeedAdapter.Item item = new SourceSpeedAdapter.Item(source);
            Integer cached = SourceSpeedTester.getCachedLatency(source.getKey());
            if (cached != null) {
                item.latency = cached;
            }
            items.add(item);
        }
        initView(title);
        sortInitialByLatency();
        startSpeedTests();
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
        if (scrim != null) {
            scrim.setAlpha(0f);
        }
        if (drawerPanel != null) {
            DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
            drawerPanel.setTranslationY(dm.heightPixels * PANEL_HEIGHT_RATIO);
        }
        released = false;
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
    }

    /** 出场：面板下沉 + 蒙层淡出，动画结束后真正 dismiss */
    @Override
    public void dismiss() {
        if (dismissing) {
            return;
        }
        dismissing = true;
        released = true;
        SourceSpeedTester.cancelAll();
        playExitAnim();
    }

    @Override
    public void cancel() {
        // 点蒙层 / 按返回键走 Dialog.cancel()，同样先播出场动画
        if (dismissing) {
            return;
        }
        dismissing = true;
        released = true;
        SourceSpeedTester.cancelAll();
        playExitAnim();
    }

    private void playExitAnim() {
        if (drawerPanel == null || drawerPanel.getHeight() <= 0) {
            SourceSpeedDialog.super.dismiss();
            return;
        }
        drawerPanel.animate()
                .translationY(drawerPanel.getHeight())
                .setDuration(PANEL_OUT_DURATION)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        SourceSpeedDialog.super.dismiss();
                    }
                })
                .start();
        if (scrim != null) {
            scrim.animate().alpha(0f).setDuration(PANEL_OUT_DURATION).start();
        }
    }

    private void initView(String title) {
        TextView tvTitle = findViewById(R.id.tvTitle);
        tvTitle.setText("换源");
        TextView tvSubtitle = findViewById(R.id.tvSourceSpeedSubtitle);
        tvSubtitle.setText(title + " · 共 " + items.size() + " 个源");

        TextView btnCancel = findViewById(R.id.btnCancel);
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                dismiss();
            }
        });

        mRecyclerView = findViewById(R.id.mRecyclerView);
        mRecyclerView.setHasFixedSize(true);
        // 一行一条
        mRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        speedAdapter = new SourceSpeedAdapter();
        speedAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                SourceSpeedAdapter.Item item = speedAdapter.getItem(position);
                if (item == null) {
                    return;
                }
                if (pickListener != null) {
                    pickListener.onSourcePick(item.source, item.matchedVideo);
                }
            }
        });
        mRecyclerView.setAdapter(speedAdapter);
        speedAdapter.setNewData(items);

        // 蒙层点击 = 关闭抽屉
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
    }

    /** 打开时的初始排序：有缓存延迟的按延迟升序排前面，没有的保持原顺序排后面。 */
    private void sortInitialByLatency() {
        boolean anyCached = false;
        for (SourceSpeedAdapter.Item item : items) {
            if (item.latency > 0) {
                anyCached = true;
                break;
            }
        }
        if (!anyCached) {
            return;
        }
        Collections.sort(items, new Comparator<SourceSpeedAdapter.Item>() {
            @Override
            public int compare(SourceSpeedAdapter.Item a, SourceSpeedAdapter.Item b) {
                int la = a.latency <= 0 ? Integer.MAX_VALUE : a.latency;
                int lb = b.latency <= 0 ? Integer.MAX_VALUE : b.latency;
                return Integer.compare(la, lb);
            }
        });
        speedAdapter.notifyDataSetChanged();
    }

    private void startSpeedTests() {
        for (final SourceSpeedAdapter.Item item : items) {
            if (item.latency != 0) {
                continue; // 缓存命中，无需重测
            }
            SourceSpeedTester.test(item.source, new SourceSpeedTester.SpeedCallback() {
                @Override
                public void onResult(String sourceKey, int latencyMs) {
                    if (released) {
                        return;
                    }
                    speedAdapter.updateLatency(sourceKey, latencyMs);
                    checkLatencyDone();
                }
            });
        }
        checkLatencyDone(); // 全部走缓存的情况
    }

    private void checkLatencyDone() {
        for (SourceSpeedAdapter.Item item : items) {
            if (item.latency == 0) {
                return;
            }
        }
        latencyDone = true;
        maybeFinalSort();
    }

    /**
     * 面板预搜索：某源的搜索结果回来了。
     * Activity 保证每个源恰好回调一次（成功或失败），全部到齐后做最终排序。
     */
    public void markSearchResult(String sourceKey, boolean hasMovie, Movie.Video matchedVideo) {
        speedAdapter.markSearchResult(sourceKey, hasMovie, matchedVideo);
        resolvedSearchCount++;
        if (resolvedSearchCount >= items.size()) {
            finishSearchPhase();
        }
    }

    /** 预搜索阶段结束（全部源已回包或整体超时），未回包的源按「无片源」收尾。 */
    public void finishSearchPhase() {
        if (searchDone || released) {
            return;
        }
        searchDone = true;
        for (SourceSpeedAdapter.Item item : items) {
            if (item.searching) {
                item.searching = false;
                item.hasMovie = false;
            }
        }
        speedAdapter.notifyDataSetChanged();
        maybeFinalSort();
    }

    /** 最终排序：有片源的源优先（内部按延迟升序），无片源/未知的沉底。 */
    private void maybeFinalSort() {
        if (!latencyDone || !searchDone) {
            return;
        }
        Collections.sort(items, new Comparator<SourceSpeedAdapter.Item>() {
            @Override
            public int compare(SourceSpeedAdapter.Item a, SourceSpeedAdapter.Item b) {
                int ra = a.hasMovie == null ? 1 : Boolean.TRUE.equals(a.hasMovie) ? 0 : 2;
                int rb = b.hasMovie == null ? 1 : Boolean.TRUE.equals(b.hasMovie) ? 0 : 2;
                if (ra != rb) {
                    return Integer.compare(ra, rb);
                }
                int la = a.latency <= 0 ? Integer.MAX_VALUE : a.latency;
                int lb = b.latency <= 0 ? Integer.MAX_VALUE : b.latency;
                if (la != lb) {
                    return Integer.compare(la, lb);
                }
                return a.source.getName().compareTo(b.source.getName());
            }
        });
        speedAdapter.notifyDataSetChanged();
    }

    /** 页面在点选的源里搜索片源时，把该行标记为搜索中。 */
    public void setRowSearching(String sourceKey, boolean searching) {
        speedAdapter.updateSearching(sourceKey, searching);
    }
}
