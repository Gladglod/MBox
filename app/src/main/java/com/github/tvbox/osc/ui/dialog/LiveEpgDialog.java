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
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import org.jetbrains.annotations.NotNull;

/**
 * 直播设置「EPG源 → 自定义地址」输入弹窗。
 * 复用 {@link NumberInputDialog} 的 dialog_number_input 布局（标题 / 输入 / 提示 / 取消+确定），
 * 保证与设置页其他弹窗同一套视觉，字号、配色随主题自动适配。
 * 地址支持 {name}/{date} 模板（diyp JSON 或 .xml 全量节目单均可），留空或取消则不改动现有配置。
 */
public class LiveEpgDialog extends BaseDialog {

    private EditText input;
    private OnListener listener;

    /** onChange 携带确认后的地址；取消/返回则回调 onCancel */
    public interface OnListener {
        void onChange(String address);

        void onCancel();
    }

    public LiveEpgDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_number_input);
        ((TextView) findViewById(R.id.tvTitle)).setText("自定义 EPG 地址");
        TextView tvTip = findViewById(R.id.tvTip);
        tvTip.setText("带 {name}/{date} 参数的 diyp JSON 接口，或直接填 .xml 全量节目单地址");
        input = findViewById(R.id.input);
        // 布局默认按数字配置（inputType=number、maxLength=3），地址输入要放开
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1024)});
        input.setHint("https://host/epg.html?ch={name}&date={date}");
        // 预填已保存的自定义地址
        String saved = Hawk.get(HawkConfig.EPG_URL, "");
        input.setText(saved == null ? "" : saved.trim());
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
            if (listener != null) listener.onCancel();
            dismiss();
        });
        findViewById(R.id.btnConfirm).setOnClickListener(v -> submit());
    }

    private void submit() {
        String address = input.getText() == null ? "" : input.getText().toString().trim();
        if (TextUtils.isEmpty(address) || listener == null) {
            dismiss();
            return;
        }
        listener.onChange(address);
        dismiss();
    }

    public void setOnListener(OnListener listener) {
        this.listener = listener;
    }
}
