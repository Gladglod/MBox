package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;

import org.jetbrains.annotations.NotNull;

/**
 * 一个轻量的数值输入弹窗，与 {@link DanmuApiDialog} 同一套视觉，
 * 避免设置页里混进系统风格弹窗。
 *
 * <ul>
 *     <li>int 构造：整数模式（自定义秒数这类设置项，保持旧行为）；</li>
 *     <li>double 构造：浮数模式，最多两位小数（自定义播放倍速这类场景）。</li>
 * </ul>
 */
public class NumberInputDialog extends BaseDialog {

    public interface OnConfirm {
        void onConfirm(int value);
    }

    /** 浮数模式回调：结果已四舍五入到两位小数 */
    public interface OnConfirmFloat {
        void onConfirm(double value);
    }

    private EditText input;
    private final boolean floatMode;
    private final int min;
    private final int max;
    private final double fMin;
    private final double fMax;
    private OnConfirm onConfirm;
    private OnConfirmFloat onConfirmFloat;
    public NumberInputDialog(@NonNull @NotNull Context context, String title, String tip,
                             int value, int min, int max) {
        super(context);
        this.floatMode = false;
        this.min = min;
        this.max = max;
        this.fMin = 0;
        this.fMax = 0;
        setContentView(R.layout.dialog_number_input);
        init(title, tip);
        input.setText(String.valueOf(value));
        input.setSelection(input.getText().length());
    }

    /** 浮数模式：支持最多两位小数（例如自定义播放倍速） */
    public NumberInputDialog(@NonNull @NotNull Context context, String title, String tip,
                             double value, double min, double max) {
        super(context);
        this.floatMode = true;
        this.min = 0;
        this.max = 0;
        this.fMin = min;
        this.fMax = max;
        setContentView(R.layout.dialog_number_input);
        init(title, tip);
        input.setText(formatFloat(Math.max(min, Math.min(max, value))));
        input.setSelection(input.getText().length());
    }

    private void init(String title, String tip) {
        ((TextView) findViewById(R.id.tvTitle)).setText(title);
        TextView tvTip = findViewById(R.id.tvTip);
        if (TextUtils.isEmpty(tip)) {
            tvTip.setVisibility(TextView.GONE);
        } else {
            tvTip.setText(tip);
        }

        input = findViewById(R.id.input);
        if (floatMode) {
            // 布局默认按整数配置（inputType=number、maxLength=3），浮数模式放开到 5 位并允许小数点
            input.setInputType(InputType.TYPE_CLASS_NUMBER
                    | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(5)});
        }
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
        if (floatMode) {
            double value;
            try {
                value = Double.parseDouble(input.getText().toString().trim());
            } catch (Exception e) {
                value = fMin;
            }
            if (value < fMin) value = fMin;
            if (value > fMax) value = fMax;
            value = Math.round(value * 100) / 100.0;
            if (onConfirmFloat != null) onConfirmFloat.onConfirm(value);
            dismiss();
            return;
        }
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

    private static String formatFloat(double value) {
        return String.valueOf(Math.round(value * 100) / 100.0);
    }

    public void setOnConfirm(OnConfirm onConfirm) {
        this.onConfirm = onConfirm;
    }

    public void setOnConfirm(OnConfirmFloat onConfirmFloat) {
        this.onConfirmFloat = onConfirmFloat;
    }
}
