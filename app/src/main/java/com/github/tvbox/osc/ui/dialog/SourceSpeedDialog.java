package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
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
 * 换源面板：列出所有可切换的源，后台测速显示延迟（带缓存，10 分钟内直接复用），
 * 同时后台全源预搜索本片，标记每个源「有片源/无片源」，命中的源点选可直接切换。
 */
public class SourceSpeedDialog extends BaseDialog {

    public interface OnSourcePickListener {
        void onSourcePick(SourceBean source, Movie.Video matchedVideo);
    }

    private RecyclerView mRecyclerView;
    private SourceSpeedAdapter speedAdapter;
    private final List<SourceSpeedAdapter.Item> items = new java.util.ArrayList<>();
    private OnSourcePickListener pickListener;
    private boolean released = false;
    /** 两个后台阶段都结束后做一次「有片源优先 + 延迟升序」的最终排序 */
    private boolean latencyDone = false;
    private boolean searchDone = false;
    private int resolvedSearchCount = 0;

    public SourceSpeedDialog(@NonNull @NotNull Context context, List<SourceBean> sources, String title, OnSourcePickListener listener) {
        super(context);
        if (context instanceof Activity) {
            setOwnerActivity((Activity) context);
        }
        setCanceledOnTouchOutside(true);
        setCancelable(true);
        pickListener = listener;
        for (SourceBean source : sources) {
            SourceSpeedAdapter.Item item = new SourceSpeedAdapter.Item(source);
            Integer cached = SourceSpeedTester.getCachedLatency(source.getKey());
            if (cached != null) {
                item.latency = cached;
            }
            items.add(item);
        }
        setContentView(R.layout.dialog_source_speed);
        initView(title);
        sortInitialByLatency();
        startSpeedTests();
    }

    private void initView(String title) {
        TextView tvSubtitle = findViewById(R.id.tvSourceSpeedSubtitle);
        tvSubtitle.setText(title);

        TextView tvClose = findViewById(R.id.tvSourceSpeedClose);
        tvClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                dismiss();
            }
        });

        mRecyclerView = findViewById(R.id.mRecyclerView);
        mRecyclerView.setHasFixedSize(true);
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

        resizePanel();
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
                if (la != lb) {
                    return Integer.compare(la, lb);
                }
                return 0;
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

    /**
     * 手机竖屏：面板贴底，宽度铺满（左右留白），高度按源数量自适应，最多占屏幕 70%。
     */
    private void resizePanel() {
        View root = findViewById(R.id.root);
        if (root == null) {
            return;
        }
        DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
        int rowHeight = Math.round(48 * dm.density);
        int headerHeight = Math.round(86 * dm.density);
        int contentHeight = items.size() * rowHeight + Math.round(10 * dm.density);
        int maxHeight = Math.round(dm.heightPixels * 0.70f);
        int panelHeight = Math.min(headerHeight + contentHeight, maxHeight);

        ViewGroup.LayoutParams lp = root.getLayoutParams();
        lp.height = panelHeight;
        root.setLayoutParams(lp);
    }

    @Override
    public void dismiss() {
        released = true;
        SourceSpeedTester.cancelAll();
        super.dismiss();
    }
}
