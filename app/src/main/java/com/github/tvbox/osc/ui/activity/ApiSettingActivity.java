package com.github.tvbox.osc.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.ui.dialog.ConfirmClearDialog;
import com.github.tvbox.osc.ui.tv.QRCodeGen;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HistoryHelper;
import com.github.tvbox.osc.util.ShortDramaConfig;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;
import com.orhanobut.hawk.Hawk;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 「数据源地址」页面：编辑点播 / 直播地址、扫码导入、本地配置文件导入，以及历史配置列表。
 * 历史配置记录在 {@link HawkConfig#API_HISTORY} 与 {@link HawkConfig#LIVE_API_HISTORY}，
 * 最新的排在最前面，可直接点选切换。
 */
public class ApiSettingActivity extends BaseActivity {

    private static final int REQUEST_LOCAL_CONFIG = 3001;

    private EditText inputApi;
    private EditText inputLive;
    private LinearLayout historyContainer;
    private TextView tvDramaSourceValue;
    private boolean selectLocalLive;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_api_setting;
    }

    @Override
    protected boolean shouldRefreshAutoSize() {
        return true;
    }

    @Override
    protected void init() {
        inputApi = findViewById(R.id.inputApi);
        inputLive = findViewById(R.id.inputLive);
        historyContainer = findViewById(R.id.historyContainer);
        findViewById(R.id.ivBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                onBackPressed();
            }
        });
        findViewById(R.id.tvSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                save();
            }
        });
        findViewById(R.id.btnPickApi).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                openLocalConfig(false);
            }
        });
        findViewById(R.id.btnPickLive).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                openLocalConfig(true);
            }
        });
        findViewById(R.id.btnPermission).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                requestStoragePermission();
            }
        });
        findViewById(R.id.tvClearHistory).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                clearHistory();
            }
        });

        inputApi.setText(Hawk.get(HawkConfig.API_URL, ""));
        inputLive.setText(Hawk.get(HawkConfig.LIVE_API_URL, ""));

        tvDramaSourceValue = findViewById(R.id.tvDramaSourceValue);
        findViewById(R.id.rowDramaSource).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                jumpActivity(ShortDramaSourceActivity.class);
            }
        });
        refreshDramaSourceValue();

        refreshQRCode();
        buildHistory();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从「短剧源」页回来时数量可能变了
        refreshDramaSourceValue();
    }

    private void refreshDramaSourceValue() {
        if (tvDramaSourceValue == null) return;
        int count = ShortDramaConfig.enabledCount();
        tvDramaSourceValue.setText(count > 0
                ? getString(R.string.short_drama_entry_value, count)
                : getString(R.string.short_drama_entry_none));
    }

    /* ============================ 保存 / 切换 ============================ */

    private void save() {
        String api = inputApi.getText().toString().trim();
        String live = inputLive.getText().toString().trim();
        if (TextUtils.isEmpty(api)) {
            Toast.makeText(this, "点播地址不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        applyApi(api, live, true);
    }

    /**
     * @param record 是否写入历史配置
     */
    private void applyApi(final String api, String live, boolean record) {
        String oldApi = Hawk.get(HawkConfig.API_URL, "");
        if (!HistoryHelper.isApiLineHistory(api)) {
            HistoryHelper.clearApiLineList();
        }
        Hawk.put(HawkConfig.API_URL, api);
        Hawk.put(HawkConfig.LIVE_API_URL, TextUtils.isEmpty(live) ? api : live);
        if (record) {
            HistoryHelper.setApiHistory(api);
            if (!TextUtils.isEmpty(live)) {
                HistoryHelper.setLiveApiHistory(live);
            }
        }
        inputApi.setText(api);
        inputLive.setText(Hawk.get(HawkConfig.LIVE_API_URL, ""));
        buildHistory();
        if (!oldApi.equals(api)) {
            restartAppAfterConfigChanged();
        } else {
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        }
    }

    private void restartAppAfterConfigChanged() {
        Toast.makeText(this, "配置已切换，即将重新加载", Toast.LENGTH_SHORT).show();
        try {
            SourceViewModel.clearRuntimeCache();
        } catch (Throwable ignore) {
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
                    if (intent == null) return;
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                } catch (Throwable ignore) {
                }
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }, 1200);
    }

    /* ============================ 历史配置 ============================ */

    private void buildHistory() {
        if (historyContainer == null) return;
        historyContainer.removeAllViews();
        ArrayList<String> history = Hawk.get(HawkConfig.API_HISTORY, new ArrayList<String>());
        String current = Hawk.get(HawkConfig.API_URL, "");
        if (history == null) history = new ArrayList<>();
        if (history.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有历史配置，保存一次后会自动记录");
            empty.setTextColor(getResources().getColor(R.color.ui_muted));
            empty.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimensionPixelSize(R.dimen.ts_14));
            int pad = dp(12);
            empty.setPadding(pad, pad, pad, pad);
            historyContainer.addView(empty);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < history.size(); i++) {
            final String api = history.get(i);
            if (i > 0) historyContainer.addView(makeDivider());
            View row = inflater.inflate(R.layout.item_api_history_row, historyContainer, false);
            ((TextView) row.findViewById(R.id.tvApi)).setText(api);
            row.findViewById(R.id.tvCurrent).setVisibility(api.equals(current) ? View.VISIBLE : View.GONE);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FastClickCheckUtil.check(v);
                    applyApi(api, "", true);
                }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    removeHistory(api);
                    return true;
                }
            });
            row.findViewById(R.id.ivDel).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FastClickCheckUtil.check(v);
                    removeHistory(api);
                }
            });
            historyContainer.addView(row);
        }
    }

    private void removeHistory(String api) {
        ArrayList<String> history = Hawk.get(HawkConfig.API_HISTORY, new ArrayList<String>());
        if (history != null && history.remove(api)) {
            Hawk.put(HawkConfig.API_HISTORY, history);
        }
        buildHistory();
    }

    private void clearHistory() {
        ArrayList<String> history = Hawk.get(HawkConfig.API_HISTORY, new ArrayList<String>());
        if (history == null || history.isEmpty()) {
            Toast.makeText(this, "历史配置为空", Toast.LENGTH_SHORT).show();
            return;
        }
        ConfirmClearDialog dialog = new ConfirmClearDialog(mContext, "ApiHistory");
        dialog.setMessage("确定清空历史配置 ?");
        dialog.setOnConfirm(new Runnable() {
            @Override
            public void run() {
                Hawk.put(HawkConfig.API_HISTORY, new ArrayList<String>());
                buildHistory();
            }
        });
        dialog.show();
    }

    private View makeDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(getResources().getColor(R.color.ui_divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f)));
        lp.leftMargin = dp(12);
        lp.rightMargin = dp(12);
        divider.setLayoutParams(lp);
        return divider;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /* ============================ 扫码 / 本地文件 ============================ */

    private void refreshQRCode() {
        ImageView ivQRCode = findViewById(R.id.ivQRCode);
        TextView tvAddress = findViewById(R.id.tvAddress);
        try {
            String address = ControlManager.get().getAddress(false);
            tvAddress.setText(String.format("手机或电脑浏览器访问\n%s", address));
            ivQRCode.setImageBitmap(QRCodeGen.generateBitmap(address + "api.html", dp(96), dp(96)));
        } catch (Throwable th) {
            tvAddress.setText("服务未启动，无法扫码导入");
            ivQRCode.setImageDrawable(null);
        }
    }

    private void requestStoragePermission() {
        if (XXPermissions.isGranted(this, Permission.Group.STORAGE)) {
            Toast.makeText(this, "已获得存储权限", Toast.LENGTH_SHORT).show();
            return;
        }
        XXPermissions.with(this)
                .permission(Permission.Group.STORAGE)
                .request(new OnPermissionCallback() {
                    @Override
                    public void onGranted(List<String> permissions, boolean all) {
                        Toast.makeText(ApiSettingActivity.this, "已获得存储权限", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onDenied(List<String> permissions, boolean never) {
                        if (never) {
                            Toast.makeText(ApiSettingActivity.this, "获取存储权限失败,请在系统设置中开启", Toast.LENGTH_SHORT).show();
                            XXPermissions.startPermissionActivity(ApiSettingActivity.this, permissions);
                        } else {
                            Toast.makeText(ApiSettingActivity.this, "获取存储权限失败", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    private void openLocalConfig(boolean live) {
        selectLocalLive = live;
        if (!XXPermissions.isGranted(this, Permission.Group.STORAGE)) {
            Toast.makeText(this, "请选择文件前需要先授予存储权限", Toast.LENGTH_SHORT).show();
            XXPermissions.with(this)
                    .permission(Permission.Group.STORAGE)
                    .request(new OnPermissionCallback() {
                        @Override
                        public void onGranted(List<String> permissions, boolean all) {
                            if (all) openLocalFileActivity(selectLocalLive);
                        }

                        @Override
                        public void onDenied(List<String> permissions, boolean never) {
                            if (never) {
                                Toast.makeText(ApiSettingActivity.this, "获取存储权限失败,请在系统设置中开启", Toast.LENGTH_SHORT).show();
                                XXPermissions.startPermissionActivity(ApiSettingActivity.this, permissions);
                            } else {
                                Toast.makeText(ApiSettingActivity.this, "获取存储权限失败", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
            return;
        }
        openLocalFileActivity(live);
    }

    private void openLocalFileActivity(boolean live) {
        Intent intent = new Intent(mContext, LocalFileActivity.class);
        intent.putExtra(LocalFileActivity.EXTRA_LIVE, live);
        startActivityForResult(intent, REQUEST_LOCAL_CONFIG);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_LOCAL_CONFIG || resultCode != Activity.RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        String api = localConfigToApi(data.getData());
        if (TextUtils.isEmpty(api)) {
            Toast.makeText(this, "读取本地配置失败", Toast.LENGTH_SHORT).show();
            return;
        }
        if (selectLocalLive) {
            inputLive.setText(api);
        } else {
            inputApi.setText(api);
        }
    }

    private String localConfigToApi(Uri uri) {
        String path = getPathFromUri(uri);
        if (TextUtils.isEmpty(path)) {
            path = copyUriToLocalConfig(uri);
        }
        if (TextUtils.isEmpty(path)) return "";
        String storageRoot = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (path.startsWith(storageRoot)) {
            return "clan://localhost/" + path.substring(storageRoot.length()).replaceFirst("^/+", "");
        }
        path = copyUriToLocalConfig(uri);
        if (path != null && path.startsWith(storageRoot)) {
            return "clan://localhost/" + path.substring(storageRoot.length()).replaceFirst("^/+", "");
        }
        return "";
    }

    private String getPathFromUri(Uri uri) {
        try {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                return uri.getPath();
            }
            if (DocumentsContract.isDocumentUri(this, uri)) {
                String docId = DocumentsContract.getDocumentId(uri);
                if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
                    String[] split = docId.split(":");
                    if (split.length > 1 && "primary".equalsIgnoreCase(split[0])) {
                        return Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + split[1];
                    }
                }
                if ("com.android.providers.downloads.documents".equals(uri.getAuthority()) && docId.startsWith("raw:")) {
                    return docId.substring(4);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private String copyUriToLocalConfig(Uri uri) {
        InputStream input = null;
        FileOutputStream output = null;
        try {
            input = getContentResolver().openInputStream(uri);
            if (input == null) return "";
            File dir = new File(FileUtils.getExternalCachePath(), "config");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, getDisplayName(uri));
            output = new FileOutputStream(file);
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
            }
            return file.getAbsolutePath();
        } catch (Throwable th) {
            return "";
        } finally {
            try {
                if (output != null) output.close();
            } catch (Throwable ignored) {
            }
            try {
                if (input != null) input.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private String getDisplayName(Uri uri) {
        String name = "local_config.json";
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String displayName = cursor.getString(index);
                    if (!TextUtils.isEmpty(displayName)) name = displayName;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return name;
    }
}
