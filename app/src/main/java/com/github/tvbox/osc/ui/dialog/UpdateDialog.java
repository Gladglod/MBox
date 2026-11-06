package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.content.DialogInterface;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.BuildConfig;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.GithubRelease;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.MotionCompat;
import com.github.tvbox.osc.util.UpdateChecker;
import com.orhanobut.hawk.Hawk;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Locale;

/**
 * 检查更新弹窗：展示新版本号与更新说明，就地下载并拉起系统安装器。
 *
 * <p>三种状态：</p>
 * <ol>
 *   <li>待更新 —— 显示「稍后 / 立即更新」；</li>
 *   <li>下载中 —— 显示进度条，「稍后」变成「取消」；</li>
 *   <li>已下载 —— 主按钮变「安装」，再次点击可重试安装（比如刚从授权页回来）。</li>
 * </ol>
 */
public class UpdateDialog extends BaseDialog {

    private final GithubRelease release;
    private final GithubRelease.Asset asset;
    /** 手动检查进来的弹窗，「稍后」换成「关闭」更贴切（不会再有自动提示） */
    private final boolean manual;

    private TextView tvTitle;
    private TextView tvSubtitle;
    private TextView tvNotes;
    private TextView tvProgress;
    private TextView btnLater;
    private TextView btnUpdate;
    private TextView btnIgnore;
    private View progressBox;
    private ProgressBar progressBar;

    private boolean downloading;
    private File downloadedApk;

    public UpdateDialog(@NonNull @NotNull Context context, GithubRelease release, boolean manual) {
        super(context);
        this.release = release;
        this.manual = manual;
        this.asset = UpdateChecker.pickAsset(release);
        setContentView(R.layout.dialog_update);
        // 面板是屏幕中央的一块，点空白处不应该误关（尤其下载中）
        setCanceledOnTouchOutside(false);
        bindViews();
        fillContent();
        setOnDismissListener(new OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                // 关掉弹窗就别在后台继续下 30MB 了
                if (downloading) UpdateChecker.cancelDownload();
            }
        });
    }

    private void bindViews() {
        tvTitle = findViewById(R.id.tvTitle);
        tvSubtitle = findViewById(R.id.tvSubtitle);
        tvNotes = findViewById(R.id.tvNotes);
        tvProgress = findViewById(R.id.tvProgress);
        btnLater = findViewById(R.id.btnLater);
        btnUpdate = findViewById(R.id.btnUpdate);
        btnIgnore = findViewById(R.id.btnIgnore);
        progressBox = findViewById(R.id.progressBox);
        progressBar = findViewById(R.id.progressBar);

        // 更新说明可能很长：给个固定行数上限 + 内部滚动，别把弹窗撑出屏幕
        tvNotes.setMovementMethod(ScrollingMovementMethod.getInstance());

        MotionCompat.attachPressScale(btnLater);
        MotionCompat.attachPressScale(btnUpdate);
        MotionCompat.attachPressScale(btnIgnore);
        btnLater.setText(laterLabel());

        btnLater.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                dismiss();
            }
        });
        btnUpdate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                onPrimaryClick();
            }
        });
        btnIgnore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                Hawk.put(HawkConfig.UPDATE_IGNORE_TAG,
                        TextUtils.isEmpty(release.tagName) ? "" : release.tagName);
                Toast.makeText(getContext(),
                        "已忽略 " + release.tagName + "，自动检查不再提示", Toast.LENGTH_SHORT).show();
                dismiss();
            }
        });
    }

    private String laterLabel() {
        return manual ? "关闭" : "稍后";
    }

    private void fillContent() {
        String tag = TextUtils.isEmpty(release.tagName) ? "" : release.tagName;
        tvTitle.setText(TextUtils.isEmpty(tag) ? "发现新版本" : "发现新版本 " + tag);

        StringBuilder sub = new StringBuilder();
        sub.append("当前 ").append(BuildConfig.VERSION_NAME);
        if (asset != null && asset.size > 0) {
            sub.append(" · 安装包 ").append(UpdateChecker.formatSize(asset.size));
        }
        sub.append(" · ").append(flavorLabel());
        tvSubtitle.setText(sub.toString());

        String body = release.body == null ? "" : release.body.trim();
        if (TextUtils.isEmpty(body)) {
            tvNotes.setText("这个版本没有填写更新说明。");
        } else {
            // Releases 里的 Markdown 在这里按纯文本展示，去掉标题井号和多余空行即可
            tvNotes.setText(body.replaceAll("(?m)^#{1,6}\\s*", "")
                    .replaceAll("\\n{3,}", "\n\n"));
        }

        if (asset == null) {
            // 没有和本机 flavor 匹配的包（比如只传了 java 版却装的是 python 版）
            tvProgress.setVisibility(View.VISIBLE);
            tvProgress.setText("该版本没有提供与当前设备匹配的安装包，可前往 GitHub 手动下载。");
            btnUpdate.setText("浏览器打开");
        }
    }

    private String flavorLabel() {
        String flavor = BuildConfig.FLAVOR;
        if (TextUtils.isEmpty(flavor)) return "通用版";
        switch (flavor) {
            case "java64":
                return "java64 版";
            case "java32":
                return "java32 版";
            case "java":
                return "java 版";
            case "python64":
                return "python64 版";
            case "python32":
                return "python32 版";
            case "python":
                return "python 版";
            default:
                return flavor;
        }
    }

    private void onPrimaryClick() {
        if (asset == null) {
            UpdateChecker.openReleasePage(getContext(), release.htmlUrl);
            return;
        }
        if (downloading) return;
        if (downloadedApk != null && downloadedApk.exists()) {
            install();
            return;
        }
        startDownload();
    }

    private void startDownload() {
        downloading = true;
        progressBox.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        tvProgress.setText("正在准备下载…");
        btnUpdate.setEnabled(false);
        btnUpdate.setText("下载中");
        btnLater.setText("取消");

        UpdateChecker.download(getContext(), release, asset, new UpdateChecker.DownloadCallback() {
            @Override
            public void onProgress(float fraction, long current, long total) {
                if (!isShowing()) return;
                int percent = Math.max(0, Math.min(100, Math.round(fraction * 100)));
                progressBar.setProgress(percent);
                tvProgress.setText(String.format(Locale.CHINA, "下载中 %s / %s（%d%%）",
                        UpdateChecker.formatSize(current), UpdateChecker.formatSize(total), percent));
            }

            @Override
            public void onSuccess(File apk) {
                downloading = false;
                downloadedApk = apk;
                if (!isShowing()) return;
                progressBar.setProgress(100);
                tvProgress.setText("下载完成，正在安装…");
                btnLater.setText(laterLabel());
                btnUpdate.setEnabled(true);
                btnUpdate.setText("安装");
                install();
            }

            @Override
            public void onError(String msg) {
                downloading = false;
                if (!isShowing()) return;
                progressBox.setVisibility(View.GONE);
                btnLater.setText(laterLabel());
                btnUpdate.setEnabled(true);
                btnUpdate.setText("重新下载");
                Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void install() {
        if (downloadedApk == null || !downloadedApk.exists()) {
            Toast.makeText(getContext(), "安装包不存在，请重新下载", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean launched = UpdateChecker.installApk(getContext(), downloadedApk);
        if (launched) {
            // 安装器已经拉起，弹窗留着也没意义
            dismiss();
        } else {
            // 多半是被系统「安装未知应用」权限拦下了，已经跳到授权页
            tvProgress.setVisibility(View.VISIBLE);
            tvProgress.setText("请在系统设置里允许本应用「安装未知应用」，返回后点「安装」重试。");
        }
    }
}
