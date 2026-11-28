package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.content.DialogInterface;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.BuildConfig;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.GithubRelease;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.GlassTip;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.MotionCompat;
import com.github.tvbox.osc.util.UpdateChecker;
import com.orhanobut.hawk.Hawk;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Locale;

/**
 * 检查更新弹窗：展示新版本号与更新说明，把「怎么更新」交给用户选。
 *
 * <p>初始状态并排两个明确入口：</p>
 * <ul>
 *   <li>浏览器下载 —— 打开 GitHub Releases 页面，由用户自行下载；</li>
 *   <li>软件内下载 —— 就地下载并拉起系统安装器。</li>
 * </ul>
 *
 * <p>软件内下载时弹窗原地切到进度态（百分比 + 已下载/总大小 + 取消下载），
 * 下载完成后主按钮变「立即安装」，失败或取消后恢复两个入口。</p>
 */
public class UpdateDialog extends BaseDialog {

    private final GithubRelease release;
    private final GithubRelease.Asset asset;
    /** 手动检查进来的弹窗，「稍后」换成「关闭」更贴切（不会再有自动提示） */
    private final boolean manual;

    private TextView tvTitle;
    private TextView tvSubtitle;
    private TextView tvVersionTag;
    private TextView tvPublishedAt;
    private View notesBox;
    private TextView tvNotes;
    private TextView tvProgressTitle;
    private TextView tvProgressPercent;
    private TextView tvProgress;
    private TextView tvActionHint;
    private TextView btnLater;
    private TextView btnBrowser;
    private TextView btnUpdate;
    private TextView btnIgnore;
    private View progressBox;
    private ProgressBar progressBar;

    private boolean downloading;
    private File downloadedApk;
    /**
     * 下载会话号：取消 / 关闭 / 重新下载时自增，
     * 旧会话迟到的进度或成功回调直接丢弃，避免进度条被上一轮下载回写。
     */
    private int downloadToken;

    public UpdateDialog(@NonNull @NotNull Context context, GithubRelease release, boolean manual) {
        super(context);
        this.release = release;
        this.manual = manual;
        this.asset = UpdateChecker.pickAsset(release);
        this.downloadedApk = UpdateChecker.findDownloadedApk(context, release, asset);
        setContentView(R.layout.dialog_update);
        // 窗口铺满全屏：蒙层才能盖住整个页面（floating 窗口默认按内容收缩，会左右露空隙）
        Window window = getWindow();
        if (window != null) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.MATCH_PARENT;
            window.setAttributes(lp);
        }
        // 面板是屏幕中央的一块，点空白处不应该误关（尤其下载中）
        setCanceledOnTouchOutside(false);
        bindViews();
        fillContent();
        setOnDismissListener(new OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                // 关掉弹窗就别在后台继续下 30MB 了
                if (downloading) {
                    downloading = false;
                    downloadToken++;
                    UpdateChecker.cancelDownload();
                }
            }
        });
    }

    private void bindViews() {
        tvTitle = findViewById(R.id.tvTitle);
        tvSubtitle = findViewById(R.id.tvSubtitle);
        tvVersionTag = findViewById(R.id.tvVersionTag);
        tvPublishedAt = findViewById(R.id.tvPublishedAt);
        notesBox = findViewById(R.id.notesBox);
        tvNotes = findViewById(R.id.tvNotes);
        tvProgressTitle = findViewById(R.id.tvProgressTitle);
        tvProgressPercent = findViewById(R.id.tvProgressPercent);
        tvProgress = findViewById(R.id.tvProgress);
        tvActionHint = findViewById(R.id.tvActionHint);
        btnLater = findViewById(R.id.btnLater);
        btnBrowser = findViewById(R.id.btnBrowser);
        btnUpdate = findViewById(R.id.btnUpdate);
        btnIgnore = findViewById(R.id.btnIgnore);
        progressBox = findViewById(R.id.progressBox);
        progressBar = findViewById(R.id.progressBar);

        // 更新说明可能很长：给个固定高度上限 + 内部滚动，别把弹窗撑出屏幕
        tvNotes.setMovementMethod(ScrollingMovementMethod.getInstance());

        MotionCompat.attachPressScale(btnLater);
        MotionCompat.attachPressScale(btnBrowser);
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
        btnBrowser.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                // 下载中这个按钮是「取消下载」，点完留在弹窗里让用户重新选
                if (downloading) {
                    cancelDownload();
                } else {
                    openInBrowser();
                }
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
                if (downloading) return;
                Hawk.put(HawkConfig.UPDATE_IGNORE_TAG,
                        TextUtils.isEmpty(release.tagName) ? "" : release.tagName);
                GlassTip.show(getContext(), "已忽略 " + release.tagName + "，自动检查不再提示");
                dismiss();
            }
        });
    }

    private String laterLabel() {
        return manual ? "关闭" : "稍后再说";
    }

    private void fillContent() {
        String tag = TextUtils.isEmpty(release.tagName) ? "" : release.tagName;
        tvTitle.setText("发现新版本");
        if (TextUtils.isEmpty(tag)) {
            tvVersionTag.setVisibility(View.GONE);
        } else {
            tvVersionTag.setText(tag);
            tvVersionTag.setVisibility(View.VISIBLE);
        }

        StringBuilder sub = new StringBuilder();
        sub.append("当前 ").append(BuildConfig.VERSION_NAME);
        if (asset != null && asset.size > 0) {
            sub.append(" · 安装包 ").append(UpdateChecker.formatSize(asset.size));
        }
        sub.append(" · ").append(flavorLabel());
        tvSubtitle.setText(sub.toString());

        String published = shortDate(release.publishedAt);
        if (TextUtils.isEmpty(published)) {
            tvPublishedAt.setVisibility(View.GONE);
        } else {
            tvPublishedAt.setText(published);
            tvPublishedAt.setVisibility(View.VISIBLE);
        }

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
            btnBrowser.setVisibility(View.GONE);
            btnUpdate.setText("浏览器打开");
            tvActionHint.setText("该版本没有提供与当前设备匹配的安装包，请前往浏览器下载。");
        } else {
            btnBrowser.setVisibility(View.VISIBLE);
            btnBrowser.setText("浏览器下载");
            if (downloadedApk != null && downloadedApk.exists()) {
                btnUpdate.setText("立即安装");
                tvActionHint.setText("安装包已下载完成，可直接安装；也可改用浏览器重新下载。");
            } else {
                btnUpdate.setText("软件内下载");
                tvActionHint.setText("软件内下载可直接安装；浏览器下载可自行保存安装包。");
            }
        }
    }

    private String shortDate(String iso) {
        if (TextUtils.isEmpty(iso)) return "";
        // GitHub 返回 2026-11-23T08:00:00Z，弹窗里只展示日期就够
        String date = iso.length() >= 10 ? iso.substring(0, 10) : iso;
        return date + " 发布";
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
            openInBrowser();
            return;
        }
        if (downloading) return;
        if (downloadedApk != null && downloadedApk.exists()) {
            install();
            return;
        }
        startDownload();
    }

    /** 浏览器自行下载：打开 Releases 页面，由用户挑包下载 */
    private void openInBrowser() {
        UpdateChecker.openReleasePage(getContext(), release.htmlUrl);
    }

    private void startDownload() {
        downloading = true;
        downloadedApk = null;
        final int token = ++downloadToken;

        notesBox.setVisibility(View.GONE);
        progressBox.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        tvProgressTitle.setText("正在下载");
        tvProgressPercent.setVisibility(View.VISIBLE);
        tvProgressPercent.setText("0%");
        tvProgress.setText("正在连接下载通道…");

        btnUpdate.setEnabled(false);
        btnUpdate.setText("下载中");
        btnBrowser.setVisibility(View.VISIBLE);
        btnBrowser.setText("取消下载");
        btnBrowser.setEnabled(true);
        btnLater.setText("关闭");
        btnIgnore.setVisibility(View.GONE);
        tvActionHint.setText("下载中可随时取消；速度慢时可改用浏览器下载。");

        UpdateChecker.download(getContext(), release, asset, new UpdateChecker.DownloadCallback() {
            @Override
            public void onProgress(float fraction, long current, long total) {
                if (token != downloadToken || !isShowing()) return;
                int percent = Math.max(0, Math.min(100, Math.round(fraction * 100)));
                progressBar.setProgress(percent);
                tvProgressTitle.setText("正在下载");
                tvProgressPercent.setText(percent + "%");
                tvProgress.setText(String.format(Locale.CHINA, "已下载 %s / %s",
                        UpdateChecker.formatSize(current), UpdateChecker.formatSize(total)));
            }

            @Override
            public void onSuccess(File apk) {
                if (token != downloadToken) return;
                downloading = false;
                downloadedApk = apk;
                if (!isShowing()) return;

                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(100);
                tvProgressTitle.setText("下载完成");
                tvProgressPercent.setText("100%");
                tvProgress.setText("安装包已就绪，正在打开系统安装器…");
                btnBrowser.setVisibility(View.GONE);
                btnUpdate.setEnabled(true);
                btnUpdate.setText("立即安装");
                btnLater.setText(laterLabel());
                btnIgnore.setVisibility(View.GONE);
                tvActionHint.setText("如果安装器没有自动弹出，可点「立即安装」重试。");
                install();
            }

            @Override
            public void onChannelFallback(String hint) {
                // 换了下载通道：进度归零，把原因告诉用户，别让他以为进度条抽风
                if (token != downloadToken || !isShowing()) return;
                progressBar.setProgress(0);
                tvProgressTitle.setText("正在切换下载通道");
                tvProgressPercent.setText("0%");
                tvProgress.setText(hint);
            }

            @Override
            public void onError(String msg) {
                if (token != downloadToken) return;
                downloading = false;
                if (!isShowing()) return;
                restoreChoiceUi("重试下载");
                GlassTip.show(getContext(), msg);
            }
        });
    }

    private void cancelDownload() {
        if (!downloading) return;
        downloading = false;
        downloadToken++;
        UpdateChecker.cancelDownload();
        restoreChoiceUi("软件内下载");
        GlassTip.show(getContext(), "已取消下载");
    }

    /** 取消 / 失败后回到「浏览器下载 + 软件内下载」的双入口状态 */
    private void restoreChoiceUi(String primaryLabel) {
        notesBox.setVisibility(View.VISIBLE);
        progressBox.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);
        tvProgressPercent.setVisibility(View.VISIBLE);
        btnBrowser.setVisibility(View.VISIBLE);
        btnBrowser.setText("浏览器下载");
        btnBrowser.setEnabled(true);
        btnUpdate.setEnabled(true);
        btnUpdate.setText(primaryLabel);
        btnLater.setText(laterLabel());
        btnIgnore.setVisibility(View.VISIBLE);
        tvActionHint.setText("软件内下载可直接安装；浏览器下载可自行保存安装包。");
    }

    private void install() {
        if (downloadedApk == null || !downloadedApk.exists()) {
            GlassTip.show(getContext(), "安装包不存在，请重新下载");
            restoreChoiceUi("软件内下载");
            return;
        }
        boolean launched = UpdateChecker.installApk(getContext(), downloadedApk);
        if (launched) {
            // 安装器已经拉起，弹窗留着也没意义
            dismiss();
        } else {
            // 多半是被系统「安装未知应用」权限拦下了，已经跳到授权页
            progressBox.setVisibility(View.VISIBLE);
            progressBar.setVisibility(View.GONE);
            tvProgressTitle.setText("需要安装权限");
            tvProgressPercent.setVisibility(View.GONE);
            tvProgress.setText("请在系统设置里允许本应用「安装未知应用」，返回后点「立即安装」重试。");
            btnBrowser.setVisibility(View.VISIBLE);
            btnBrowser.setText("浏览器下载");
            btnBrowser.setEnabled(true);
            btnUpdate.setText("立即安装");
            btnUpdate.setEnabled(true);
            tvActionHint.setText("如果仍然无法安装，可改用浏览器下载安装包。");
        }
    }
}
