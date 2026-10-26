package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.ui.adapter.CheckboxSearchAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;

import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;

public class SearchCheckboxDialog extends BaseDialog {

    private static final int SPAN_COUNT = 3;

    private RecyclerView mGridView;
    private CheckboxSearchAdapter checkboxSearchAdapter;
    public List<SourceBean> mSourceList;
    TextView checkAll;
    TextView clearAll;
    TextView tvDone;

    public HashMap<String, String> mCheckSourcees;

    public SearchCheckboxDialog(@NonNull @NotNull Context context, List<SourceBean> sourceList, HashMap<String, String> checkedSources) {
        super(context);
        if (context instanceof Activity) {
            setOwnerActivity((Activity) context);
        }
        setCanceledOnTouchOutside(true);
        setCancelable(true);
        mSourceList = sourceList;
        mCheckSourcees = checkedSources;
        setContentView(R.layout.dialog_checkbox_search);
        initView(context);
    }

    @Override
    public void dismiss() {
        if (checkboxSearchAdapter != null) {
            checkboxSearchAdapter.setMCheckedSources();
        }
        super.dismiss();
    }

    protected void initView(Context context) {
        mGridView = findViewById(R.id.mGridView);
        checkAll = findViewById(R.id.checkAll);
        clearAll = findViewById(R.id.clearAll);
        tvDone = findViewById(R.id.tvDone);

        checkboxSearchAdapter = new CheckboxSearchAdapter(new DiffUtil.ItemCallback<SourceBean>() {
            @Override
            public boolean areItemsTheSame(@NonNull SourceBean oldItem, @NonNull SourceBean newItem) {
                return oldItem.getKey().equals(newItem.getKey());
            }

            @Override
            public boolean areContentsTheSame(@NonNull SourceBean oldItem, @NonNull SourceBean newItem) {
                return oldItem.getName().equals(newItem.getName());
            }
        });
        mGridView.setHasFixedSize(true);
        mGridView.setLayoutManager(new GridLayoutManager(getContext(), SPAN_COUNT));
        mGridView.setAdapter(checkboxSearchAdapter);

        resizePanel();

        checkboxSearchAdapter.setData(mSourceList, mCheckSourcees);

        checkAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                mCheckSourcees = new HashMap<>();
                for (SourceBean sourceBean : mSourceList) {
                    mCheckSourcees.put(sourceBean.getKey(), "1");
                }
                checkboxSearchAdapter.setData(mSourceList, mCheckSourcees);
            }
        });
        clearAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                mCheckSourcees = new HashMap<>();
                checkboxSearchAdapter.setData(mSourceList, mCheckSourcees);
            }
        });
        if (tvDone != null) {
            tvDone.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    FastClickCheckUtil.check(view);
                    dismiss();
                }
            });
        }
    }

    /**
     * 手机竖屏：面板宽度铺满（左右留白），高度按数据量自适应，最多占屏幕 70%。
     */
    private void resizePanel() {
        View root = findViewById(R.id.root);
        if (root == null) return;
        DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
        int rowHeight = Math.round(48 * dm.density);      // item 40dp + 上下 padding 4dp
        int headerHeight = Math.round(112 * dm.density);   // 标题行 + 全选行 + 间距
        int rows = Math.max(1, (int) Math.ceil(mSourceList.size() / (double) SPAN_COUNT));
        int contentHeight = rows * rowHeight + Math.round(10 * dm.density);
        int maxHeight = Math.round(dm.heightPixels * 0.70f);
        int panelHeight = Math.min(headerHeight + contentHeight, maxHeight);

        ViewGroup.LayoutParams lp = root.getLayoutParams();
        lp.height = panelHeight;
        root.setLayoutParams(lp);
    }

    public void setMSourceList(List<SourceBean> SourceBeanList) {
        mSourceList = SourceBeanList;
        checkboxSearchAdapter.setData(mSourceList, mCheckSourcees);
        resizePanel();
    }
}
