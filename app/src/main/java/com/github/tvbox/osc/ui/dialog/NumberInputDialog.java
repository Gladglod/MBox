package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;

import org.jetbrains.annotations.NotNull;

/**
 * 一个只输入整数的轻量弹窗，用于「自定义秒数」这类需要自由取值的设置项。
 * 与 {@link DanmuApiDialog} 同一套视觉，避免设置页里混进系统风格弹窗。
 */
public class NumberInputDialog extends BaseDialog {

    public interface OnConfirm {
        void onConfirm(int value);
    }

    private final EditText input;
    private final int min;
    private final int max;
    private OnConfirm onConfirm;

    public NumberInputDialog(@NonNull @NotNull Context context, String title, String tip,
                             int value, int min, int max) {
        super(context);
        setContentView(R.layout.dialog_number_input);
        setCanceledOnTouchOutside(true);
        this.min = min;
        this.max = max;

        ((TextView) findViewById(R.id.tvTitle)).setText(title);
        TextView tvTip = findViewById(R.id.tvTip);
        if (TextUtils.isEmpty(tip)) {
            tvTip.setVisibility(TextView.GONE);
        } else {
            tvTip.setText(tip);
        }

        input = findViewById(R.id.input);
        input.setText(String.valueOf(value));
        input.setSelection(input.getText().length());
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    submit();
                    return true;
                }
                return false;
            }
        });
        findViewById(R.id.btnCancel).setOnClickListener(v -> dismiss());
        findViewById(R.id.btnConfirm).setOnClickListener(v -> submit());
    }

    private void submit() {
        int value;
        try {
            value = Integer.parseInt(input.getText().toString().trim());
        } catch (Exception e) {
            value = min;
        }
        if (value < min) value = min;
        if (value > max) value = max;
        if (onConfirm != null) onConfirm.onConfirm(value);
        dismiss();
    }

    public void setOnConfirm(OnConfirm onConfirm) {
        this.onConfirm = onConfirm;
    }
}
