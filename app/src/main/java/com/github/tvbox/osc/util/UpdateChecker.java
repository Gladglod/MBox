package com.github.tvbox.osc.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import com.github.tvbox.osc.BuildConfig;
import com.github.tvbox.osc.bean.GithubRelease;
import com.github.tvbox.osc.ui.dialog.UpdateDialog;
import com.google.gson.Gson;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.FileCallback;
import com.lzy.okgo.callback.StringCallback;
import com.lzy.okgo.model.HttpHeaders;
import com.lzy.okgo.model.Progress;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 基于 GitHub Releases 的版本更新检查 / 下载 / 安装。
 *
 * <p>网络策略：GitHub 在部分网络环境下被墙，检查与下载都是双通道 ——
 * 直连失败自动改走 {@link #GH_PROXY_PREFIX} 加速前缀；检查阶段走通的通道会被记住，
 * 下载时优先用它，避免被墙用户每次先白等一次直连超时。</p>
 *
 * <p>约定（发版时必须遵守）：</p>
 * <ul>
 *   <li>tag 用纯版本号，如 {@code 2.0.2}（可带前缀 v，比较时会忽略）；</li>
 *   <li>{@code app/build.gradle} 的 {@code versionName} 与 tag 保持一致 —— 判断「是否有新版」
 *       就是拿 tag 和本地 versionName 做逐段数字比较，两者不同步会误判；</li>
 *   <li>每个 flavor 上传一个同名包：{@code MBox_release-java.apk / -java32 / -java64 / -python ...}，
 *       运行时按 {@link BuildConfig#FLAVOR} 挑对应的那个。</li>
 * </ul>
 */
public final class UpdateChecker {

    public static final String REPO = "Gladglod/MBox";
    public static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases";

    private static final String API_LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String TAG_DOWNLOAD = "app_update_download";
    /** GitHub 被墙时的加速通道：在原 GitHub 链接前拼上这个前缀即可（release 包、API 都支持） */
    public static final String GH_PROXY_PREFIX = "https://gh-proxy.org/";
    /** 上次检查走通的通道：直连成功记 false，代理成功记 true。
     *  下载时用它决定先试哪个通道，被墙用户就不用每次先白等一次直连超时 */
    private static volatile boolean lastCheckViaProxy = false;
    /** 用户主动取消下载的标记：取消会以 onError 的形式回到回调里，别再傻乎乎切通道重试 */
    private static volatile boolean downloadCanceled = false;
    /** 自动检查的最小间隔：一天 */
    private static final long CHECK_INTERVAL = 24L * 60 * 60 * 1000;
    /** 启动后延迟再查，别和首页首屏抢带宽 */
    private static final long LAUNCH_DELAY = 3000L;

    private UpdateChecker() {
    }

    /* ============================ 检查 ============================ */

    public interface CheckCallback {
        /** 有新版（已确定要提示的版本） */
        void onNewVersion(GithubRelease release, boolean manual);

        /** 已经是最新 */
        void onLatest(boolean manual);

        /** 检查失败，msg 是给用户看的一句话 */
        void onError(String msg, boolean manual);
    }

    /** 手动检查：结果无论有没有新版都回调，由调用方决定怎么提示。
     *  GitHub API 被墙时自动改走 gh-proxy.org 加速通道再试一次 */
    public static void check(final boolean manual, final CheckCallback callback) {
        attemptCheck(0, manual, callback);
    }

    /** 先直连，失败（超时 / 被墙重置 / 限流 / 5xx）换加速通道；attempt 从 0 开始 */
    private static void attemptCheck(final int attempt, final boolean manual, final CheckCallback callback) {
        final String url = attempt == 0 ? API_LATEST : proxied(API_LATEST);
        HttpHeaders headers = new HttpHeaders();
        // GitHub 要求带 UA，否则 403
        headers.put("User-Agent", "MBox-Android/" + BuildConfig.VERSION_NAME);
        headers.put("Accept", "application/vnd.github+json");
        OkGo.<String>get(url)
                .headers(headers)
                .tag("app_update_check")
                .execute(new StringCallback() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        GithubRelease release = parse(response.body());
                        if (release == null || TextUtils.isEmpty(release.tagName)) {
                            if (callback != null) callback.onError("返回数据无法解析", manual);
                            return;
                        }
                        // 记下这次走通的通道，下载时优先用它
                        lastCheckViaProxy = attempt > 0;
                        if (isNewer(release.tagName, BuildConfig.VERSION_NAME)) {
                            if (callback != null) callback.onNewVersion(release, manual);
                        } else {
                            if (callback != null) callback.onLatest(manual);
                        }
                    }

                    @Override
                    public void onError(Response<String> response) {
                        int code = response == null ? 0 : response.code();
                        LOG.e("UpdateChecker check failed: url=" + url + " code=" + code
                                + " msg=" + (response == null ? "" : response.message()));
                        if (callback == null) return;
                        // 404 是 GitHub 的明确答复（仓库还没有发布版本），换通道也没用；
                        // 其余失败换一条通道还有救，比如被墙换加速、限流换个出口 IP
                        if (attempt == 0 && code != 404) {
                            attemptCheck(1, manual, callback);
                            return;
                        }
                        if (code == 404) {
                            callback.onError("仓库还没有发布版本", manual);
                        } else if (code == 403) {
                            callback.onError("请求太频繁，请稍后再试", manual);
                        } else {
                            callback.onError("无法连接 GitHub，请检查网络", manual);
                        }
                    }
                });
    }

    /**
     * 启动时自动检查：受「自动检查」开关与 24 小时节流控制，
     * 只有真的发现新版（且没被忽略）才弹窗，其余情况完全静默。
     */
    public static void autoCheckOnLaunch(final Activity activity) {
        if (activity == null) return;
        if (!Hawk.get(HawkConfig.UPDATE_AUTO_CHECK, true)) return;
        long last = Hawk.get(HawkConfig.UPDATE_LAST_CHECK, 0L);
        if (System.currentTimeMillis() - last < CHECK_INTERVAL) return;
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isAlive(activity)) return;
                check(false, new CheckCallback() {
                    @Override
                    public void onNewVersion(GithubRelease release, boolean manual) {
                        Hawk.put(HawkConfig.UPDATE_LAST_CHECK, System.currentTimeMillis());
                        String ignored = Hawk.get(HawkConfig.UPDATE_IGNORE_TAG, "");
                        if (TextUtils.equals(ignored, release.tagName)) return;
                        if (!isAlive(activity)) return;
                        new UpdateDialog(activity, release, false).show();
                    }

                    @Override
                    public void onLatest(boolean manual) {
                        Hawk.put(HawkConfig.UPDATE_LAST_CHECK, System.currentTimeMillis());
                    }

                    @Override
                    public void onError(String msg, boolean manual) {
                        // 失败也记一次时间，避免网络不通时每次启动都白等一次超时
                        Hawk.put(HawkConfig.UPDATE_LAST_CHECK, System.currentTimeMillis());
                    }
                });
            }
        }, LAUNCH_DELAY);
    }

    /** 异步回调回来时页面可能已经被销毁（换主题会重建 Activity），别往死页面上弹窗 */
    private static boolean isAlive(Activity activity) {
        if (activity == null || activity.isFinishing()) return false;
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !activity.isDestroyed();
    }

    private static GithubRelease parse(String json) {        if (TextUtils.isEmpty(json)) return null;
        try {
            return new Gson().fromJson(json, GithubRelease.class);
        } catch (Throwable th) {
            return null;
        }
    }

    /* ============================ 版本比较 ============================ */

    /** remote 比 local 新返回 true；相等或更旧返回 false */
    public static boolean isNewer(String remote, String local) {
        return compareVersion(remote, local) > 0;
    }

    /**
     * 逐段数字比较，返回 1/0/-1。非数字分隔符（点、横线、加号）都当作分段，
     * 所以 {@code v2.0.1-beta} 与 {@code 2.0.1} 视为同一版本。
     */
    public static int compareVersion(String v1, String v2) {
        int[] a = versionParts(v1);
        int[] b = versionParts(v2);
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y ? 1 : -1;
        }
        return 0;
    }

    private static int[] versionParts(String version) {
        if (TextUtils.isEmpty(version)) return new int[0];
        String v = version.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        String[] raw = v.split("[^0-9]+");
        int count = 0;
        int[] tmp = new int[raw.length];
        for (String s : raw) {
            if (TextUtils.isEmpty(s)) continue;
            try {
                tmp[count++] = Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                // 超长数字段直接忽略
            }
        }
        int[] out = new int[count];
        System.arraycopy(tmp, 0, out, 0, count);
        return out;
    }

    /* ============================ 选包 ============================ */

    /**
     * 从 release 里挑出与当前构建匹配的 apk：
     * 先精确匹配 flavor（{@code -java64.apk}），再按 ABI 猜，最后退到任意 apk。
     * 没有 apk 时返回 null。
     */
    public static GithubRelease.Asset pickAsset(GithubRelease release) {
        if (release == null || release.assets == null || release.assets.isEmpty()) return null;
        List<GithubRelease.Asset> assets = release.assets;

        String flavor = BuildConfig.FLAVOR == null ? "" : BuildConfig.FLAVOR.toLowerCase(Locale.ROOT);
        if (!TextUtils.isEmpty(flavor)) {
            GithubRelease.Asset exact = findApk(assets, flavor);
            if (exact != null) return exact;
        }
        // flavor 对不上（比如 python 包没传）：按设备 ABI 退一步
        if (isArm64()) {
            GithubRelease.Asset a64 = findApk(assets, "java64");
            if (a64 != null) return a64;
        }
        if (isArm32()) {
            GithubRelease.Asset a32 = findApk(assets, "java32");
            if (a32 != null) return a32;
        }
        GithubRelease.Asset plain = findApk(assets, "java");
        if (plain != null) return plain;
        for (GithubRelease.Asset asset : assets) {
            if (asset != null && asset.name != null && asset.name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                return asset;
            }
        }
        return null;
    }

    private static GithubRelease.Asset findApk(List<GithubRelease.Asset> assets, String flavor) {
        String suffix = "-" + flavor + ".apk";
        for (GithubRelease.Asset asset : assets) {
            if (asset == null || asset.name == null || TextUtils.isEmpty(asset.downloadUrl)) continue;
            if (asset.name.toLowerCase(Locale.ROOT).endsWith(suffix)) return asset;
        }
        return null;
    }

    private static boolean isArm64() {
        if (Build.SUPPORTED_ABIS == null) return false;
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }

    private static boolean isArm32() {
        if (Build.SUPPORTED_ABIS == null) return false;
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("armeabi-v7a".equals(abi) || "armeabi".equals(abi)) return true;
        }
        return false;
    }

    /* ============================ 下载 ============================ */

    public interface DownloadCallback {
        void onProgress(float fraction, long current, long total);

        void onSuccess(File apk);

        void onError(String msg);

        /** 当前通道失败、切换到另一条通道（直连 <-> gh-proxy 加速）时回调，hint 是给用户看的一句话 */
        default void onChannelFallback(String hint) {
        }
    }

    /** 下载目录：优先外部缓存（不占应用数据配额），没有就退到内部缓存 */
    public static File downloadDir(Context context) {
        File base = context.getExternalCacheDir();
        if (base == null) base = context.getCacheDir();
        File dir = new File(base, "update");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /**
     * 找出已经完整下载过的安装包（大小与 API 返回一致），没有则返回 null。
     * 弹窗用它决定是否直接把主按钮显示成「立即安装」。
     */
    public static File findDownloadedApk(Context context, GithubRelease release, GithubRelease.Asset asset) {
        if (context == null || release == null || asset == null) return null;
        File target = new File(downloadDir(context), fileNameOf(release, asset));
        if (!target.exists() || target.length() <= 0) return null;
        if (asset.size > 0 && target.length() != asset.size) return null;
        return target;
    }

    /**
     * 双通道下载：直连 GitHub 与 gh-proxy.org 加速各试一次，先后顺序按检查阶段走通的通道来，
     * 失败的通道会删掉半截文件再换下一条，全部失败才报错。
     */
    public static void download(Context context, GithubRelease release, GithubRelease.Asset asset,
                                final DownloadCallback callback) {
        if (asset == null || TextUtils.isEmpty(asset.downloadUrl)) {
            if (callback != null) callback.onError("该版本没有可用的安装包");
            return;
        }
        final File dir = downloadDir(context);
        final File target = new File(dir, fileNameOf(release, asset));

        // 之前已经完整下过就直接复用，省一次 30MB 流量
        if (target.exists() && asset.size > 0 && target.length() == asset.size) {
            if (callback != null) callback.onSuccess(target);
            return;
        }
        if (target.exists()) target.delete();

        downloadCanceled = false;
        List<String> urls = new ArrayList<>();
        if (lastCheckViaProxy) {
            // 检查阶段直连 GitHub API 都不通，说明多半被墙了，先走加速通道
            urls.add(proxied(asset.downloadUrl));
            urls.add(asset.downloadUrl);
        } else {
            urls.add(asset.downloadUrl);
            urls.add(proxied(asset.downloadUrl));
        }
        attemptDownload(0, urls, dir, target.getName(), asset.size, callback);
    }

    /** 按顺序尝试各条通道；当前这条废了就删掉半截文件，通知 UI 后换下一条 */
    private static void attemptDownload(final int index, final List<String> urls, final File dir,
                                        final String name, final long expectSize, final DownloadCallback callback) {
        if (callback == null) return;
        if (index >= urls.size()) {
            callback.onError("下载失败：直连与加速通道均不可用，请检查网络后重试");
            return;
        }
        final String url = urls.get(index);
        OkGo.<File>get(url)
                .tag(TAG_DOWNLOAD)
                .execute(new FileCallback(dir.getAbsolutePath(), name) {
                    @Override
                    public void onSuccess(Response<File> response) {
                        File file = response.body();
                        if (file == null || !file.exists() || file.length() <= 0) {
                            nextChannel("下载的文件无效");
                            return;
                        }
                        // 加速通道偶发会回错误页而不是安装包，用 API 里的包大小做完整性校验
                        if (expectSize > 0 && file.length() != expectSize) {
                            file.delete();
                            nextChannel("下载的安装包不完整，请稍后重试");
                            return;
                        }
                        callback.onSuccess(file);
                    }

                    @Override
                    public void onError(Response<File> response) {
                        LOG.e("UpdateChecker download failed: url=" + url
                                + " code=" + (response == null ? 0 : response.code()));
                        // 是用户主动取消（关弹窗）的话，别再切通道偷偷接着下
                        if (downloadCanceled) return;
                        nextChannel(null);
                    }

                    @Override
                    public void downloadProgress(Progress progress) {
                        callback.onProgress(progress.fraction, progress.currentSize, progress.totalSize);
                    }

                    private void nextChannel(String reason) {
                        File partial = new File(dir, name);
                        if (partial.exists()) partial.delete();
                        if (index + 1 < urls.size()) {
                            String next = urls.get(index + 1);
                            String failed = url.startsWith(GH_PROXY_PREFIX) ? "加速通道失败" : "直连下载失败";
                            String switching = next.startsWith(GH_PROXY_PREFIX) ? "改用加速通道下载" : "尝试直连下载";
                            callback.onChannelFallback(failed + "，正在" + switching + "…");
                            attemptDownload(index + 1, urls, dir, name, expectSize, callback);
                        } else {
                            callback.onError(reason == null ? "下载失败，请检查网络后重试" : reason);
                        }
                    }
                });
    }

    /** 在 GitHub 链接（release 包、API）前拼接 gh-proxy.org 加速前缀 */
    private static String proxied(String url) {
        if (TextUtils.isEmpty(url)) return url;
        return GH_PROXY_PREFIX + url;
    }

    public static void cancelDownload() {
        downloadCanceled = true;
        OkGo.getInstance().cancelTag(TAG_DOWNLOAD);
    }

    private static String fileNameOf(GithubRelease release, GithubRelease.Asset asset) {
        // 统一命名，避免不同版本互相覆盖；版本号里的非法字符直接换成下划线
        String tag = TextUtils.isEmpty(release.tagName) ? "latest" : release.tagName.replaceAll("[^0-9A-Za-z._-]", "_");
        return "MBox-" + tag + "-" + asset.name;
    }

    /* ============================ 安装 ============================ */

    /**
     * 拉起系统安装器。返回 false 表示没装上：
     * 已跳转「安装未知应用」授权页，或调用失败，调用方给个提示即可。
     */
    public static boolean installApk(Context context, File apk) {
        if (context == null || apk == null || !apk.exists()) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !context.getPackageManager().canRequestPackageInstalls()) {
            openUnknownSourceSetting(context);
            return false;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Uri uri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                uri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".fileprovider", apk);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                uri = Uri.fromFile(apk);
            }
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            context.startActivity(intent);
            return true;
        } catch (Throwable th) {
            return false;
        }
    }

    private static void openUnknownSourceSetting(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    /** 在浏览器里打开 Releases 页（API 被墙 / 没有匹配包时的兜底） */
    public static void openReleasePage(Context context, String url) {
        if (context == null) return;
        String target = TextUtils.isEmpty(url) ? RELEASES_PAGE : url;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(target));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    /* ============================ 小工具 ============================ */

    public static String formatSize(long bytes) {
        if (bytes <= 0) return "未知大小";
        if (bytes < 1024 * 1024) return String.format(Locale.CHINA, "%d KB", bytes / 1024);
        return String.format(Locale.CHINA, "%.1f MB", bytes / 1024f / 1024f);
    }
}
