package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.adapter.ChoiceAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;

import java.util.List;

/**
 * 单选列表对话框。
 *
 * <p>选站点、选分类都是「从一堆名字里挑一个」，所以抽成一个通用控件，
 * 而不是每处各写一个 AlertDialog —— 后者在这套自定义主题下样式会跑偏。</p>
 */
public class ChoiceListDialog extends BaseDialog {

    public interface OnPick {
        void onPick(int position, String value);
    }

    public ChoiceListDialog(@NonNull Context context, String title, List<String> items,
                            int selected, final OnPick listener) {
        super(context, R.style.CustomDialogStyleDim);
        setContentView(R.layout.dialog_choice_list);
        setCanceledOnTouchOutside(true);

        ((TextView) findViewById(R.id.title)).setText(title);

        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(context));
        ChoiceAdapter adapter = new ChoiceAdapter();
        list.setAdapter(adapter);
        adapter.setItems(items, selected);
        adapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter baseAdapter, View view, int position) {
                FastClickCheckUtil.check(view);
                if (listener != null) {
                    listener.onPick(position, (String) baseAdapter.getItem(position));
                }
                dismiss();
            }
        });

        findViewById(R.id.btnCancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                dismiss();
            }
        });
    }
}
