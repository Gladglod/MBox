package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.DanmakuApi;
import com.github.tvbox.osc.bean.DanmuSearchResult;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public class DanmuApiDialog extends BaseDialog {
    private EditText input;
    private TextView testResult;
    private OnListener listener;

    public DanmuApiDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_danmu_api);
        setCanceledOnTouchOutside(false);
        input = findViewById(R.id.input);
        input.setText(Hawk.get(HawkConfig.DANMU_API, ""));
        input.setHint(getDefaultApi());
        testResult = findViewById(R.id.tvTestResult);
        findViewById(R.id.inputDefault).setOnClickListener(v -> saveDefault());
        findViewById(R.id.inputTest).setOnClickListener(v -> test(input.getText().toString().trim()));
        findViewById(R.id.inputSubmit).setOnClickListener(v -> save(input.getText().toString().trim()));
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    save(input.getText().toString().trim());
                    return true;
                }
                return false;
            }
        });
    }

    private String getDefaultApi() {
        String api = DanmakuApi.getDisplayApiUrl();
        return api.isEmpty() ? "请输入弹幕搜索地址" : api;
    }

    /**
     * 使用输入框中的地址（未填则用内置默认）真实发起一次弹幕搜索，验证接口是否可用。
     * 测试通过后地址立即生效，无需再点确定。
     */
    private void test(String api) {
        if (testResult == null) return;
        if (api.isEmpty()) {
            DanmakuApi.setUseDefault(true);
        } else {
            DanmakuApi.setCustomApi(api);
        }
        testResult.setVisibility(TextView.VISIBLE);
        testResult.setText("正在测试…");
        if (listener != null) listener.onChange(api);
        DanmakuApi.searchList("庆余年", "1", new DanmakuApi.SearchListCallback() {
            @Override
            public void onSuccess(List<DanmuSearchResult> results) {
                int count = results == null ? 0 : results.size();
                if (count > 0) {
                    testResult.setText("✔ 接口正常，匹配到 " + count + " 条弹幕");
                } else {
                    testResult.setText("接口可访问，但没有匹配到弹幕");
                }
            }

            @Override
            public void onError(String message) {
                testResult.setText("✘ 接口异常：" + message);
            }
        });
    }

    private void save(String api) {
        DanmakuApi.setCustomApi(api);
        if (listener != null) listener.onChange(api);
        dismiss();
    }

    private void saveDefault() {
        DanmakuApi.setUseDefault(true);
        if (listener != null) listener.onChange("");
        dismiss();
    }

    public void setOnListener(OnListener listener) {
        this.listener = listener;
    }

    public interface OnListener {
        void onChange(String api);
    }
}
