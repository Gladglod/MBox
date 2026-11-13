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

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.ui.adapter.SourceSpeedAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.SourceSpeedTester;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 换源面板：列出所有可切换的源，后台测速显示延迟，用户点选某个源后
 * 由页面去该源搜索同名片源并切换。
 */
public class SourceSpeedDialog extends BaseDialog {

    public interface OnSourcePickListener {
        void onSourcePick(SourceBean source);
    }

    private RecyclerView mRecyclerView;
    private SourceSpeedAdapter speedAdapter;
    private final List<SourceSpeedAdapter.Item> items = new java.util.ArrayList<>();
    private OnSourcePickListener pickListener;
    private boolean released = false;

    public SourceSpeedDialog(@NonNull @NotNull Context context, List<SourceBean> sources, String title, OnSourcePickListener listener) {
        super(context);
        if (context instanceof Activity) {
            setOwnerActivity((Activity) context);
        }
        setCanceledOnTouchOutside(true);
        setCancelable(true);
        pickListener = listener;
        for (SourceBean source : sources) {
            items.add(new SourceSpeedAdapter.Item(source));
        }
        setContentView(R.layout.dialog_source_speed);
        initView(title);
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
        speedAdapter.setOnItemClickListener(new com.chad.library.adapter.base.BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(com.chad.library.adapter.base.BaseQuickAdapter adapter, View view, int position) {
                SourceSpeedAdapter.Item item = speedAdapter.getItem(position);
                if (item == null || item.searching) {
                    return;
                }
                if (pickListener != null) {
                    pickListener.onSourcePick(item.source);
                }
            }
        });
        mRecyclerView.setAdapter(speedAdapter);
        speedAdapter.setNewData(items);

        resizePanel();
    }

    private void startSpeedTests() {
        for (final SourceSpeedAdapter.Item item : items) {
            SourceSpeedTester.test(item.source, new SourceSpeedTester.SpeedCallback() {
                @Override
                public void onResult(String sourceKey, int latencyMs) {
                    if (released) {
                        return;
                    }
                    speedAdapter.updateLatency(sourceKey, latencyMs);
                    checkAllTested();
                }
            });
        }
    }

    /** 全部测完后按延迟升序重排，超时/失败的沉底。 */
    private void checkAllTested() {
        for (SourceSpeedAdapter.Item item : items) {
            if (item.latency == 0) {
                return;
            }
        }
        Collections.sort(items, new Comparator<SourceSpeedAdapter.Item>() {
            @Override
            public int compare(SourceSpeedAdapter.Item a, SourceSpeedAdapter.Item b) {
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
