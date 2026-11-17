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
 * 文本输入弹窗，复用 {@link NumberInputDialog} 的视觉样式（同一套玻璃布局），
 * 用于自定义弹幕接口、片头片尾数据库接口这类地址输入。
 */
public class TextInputDialog extends BaseDialog {

    public interface OnConfirm {
        void onConfirm(String value);
    }

    private final EditText input;
    private OnConfirm onConfirm;

    public TextInputDialog(@NonNull @NotNull Context context, String title, String tip,
                           String value, String hint) {
        super(context);
        setContentView(R.layout.dialog_number_input);
        ((TextView) findViewById(R.id.tvTitle)).setText(title);
        TextView tvTip = findViewById(R.id.tvTip);
        if (TextUtils.isEmpty(tip)) {
            tvTip.setVisibility(TextView.GONE);
        } else {
            tvTip.setText(tip);
        }
        input = findViewById(R.id.input);
        // 布局按数值输入配置（number + maxLength 3），文本模式放开
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2048)});
        input.setHint(hint == null ? "" : hint);
        input.setText(value == null ? "" : value);
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
        findViewById(R.id.btnCancel).setOnClickListener(v -> {
            if (!allowGlassClick(v)) return;
            dismiss();
        });
        findViewById(R.id.btnConfirm).setOnClickListener(v -> {
            if (!allowGlassClick(v)) return;
            submit();
        });
        bindGlassPressEffect(findViewById(R.id.btnCancel));
        bindGlassPressEffect(findViewById(R.id.btnConfirm));
    }

    private void submit() {
        if (onConfirm != null) onConfirm.onConfirm(input.getText().toString().trim());
        dismiss();
    }

    public void setOnConfirm(OnConfirm onConfirm) {
        this.onConfirm = onConfirm;
    }
}
