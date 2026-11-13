package com.github.tvbox.osc.player.danmu;

import android.text.TextUtils;
import android.view.View;

import com.github.tvbox.osc.api.DanmakuApi;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.player.controller.VodController;
import com.github.tvbox.osc.util.DanmuHelper;
import com.github.tvbox.osc.util.LOG;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import master.flame.danmaku.danmaku.model.BaseDanmaku;
import master.flame.danmaku.danmaku.model.IDisplayer;
import master.flame.danmaku.danmaku.model.android.DanmakuContext;
import master.flame.danmaku.ui.widget.DanmakuView;
import xyz.doikki.videoplayer.player.VideoView;

public class DanmuLoadController {
    public interface LoadCallback {
        void onFailed();
    }

    private final MyVideoView videoView;
    private final VodController controller;
    private final DanmakuView danmuView;
    private final DanmakuContext danmakuContext;
    private final AtomicInteger loadSeq = new AtomicInteger();
    private ExecutorService executor;
    private String danmuText = "";
    private String danmuTitle = "";
    private String danmuEpisode = "";
    /** 展示用：正在使用的弹幕来源名（搜索框状态条「当前弹幕：xxx」），空表示用 Title/Episode 兜底 */
    private String danmuDisplayName = "";
    /** 弹幕是否真正解析挂载成功（下载/解析失败、被释放、被卸载时为 false），状态条以此为准 */
    private boolean danmuLoaded;
    private int startedSeq = -1;
    private boolean pendingPrepare;
    private boolean temporarilyClosed;
    private long lastPrepareAt;
    private LoadCallback loadCallback;
    private volatile int seekGeneration;
    private volatile long pendingSeekPosition = -1;
    /** seek 复用路径的重同步待执行标记（与 resyncIfReady 配对，新 seek 会重新置位） */
    private volatile boolean resyncPending;

    public DanmuLoadController(MyVideoView videoView, VodController controller, DanmakuView danmuView) {
        this.videoView = videoView;
        this.controller = controller;
        this.danmuView = danmuView;
        this.danmakuContext = DanmakuContext.create();
        if (this.videoView != null) {
            this.videoView.setDanmuView(this.danmuView);
            this.videoView.setDanmuSeekListener(this::onDanmuSeek);
        }
        applySettings(false);
    }

    public void applySettings(boolean reload) {
        if (danmuView == null || danmakuContext == null) return;
        if (!DanmuHelper.isOpen()) {
            releaseView();
            if (controller != null) controller.setHasDanmu(!TextUtils.isEmpty(danmuText));
            return;
        }
        HashMap<Integer, Integer> maxLines = new HashMap<>();
        int maxLine = DanmuHelper.getMaxLine();
        maxLines.put(BaseDanmaku.TYPE_FIX_TOP, maxLine);
        maxLines.put(BaseDanmaku.TYPE_SCROLL_RL, maxLine);
        maxLines.put(BaseDanmaku.TYPE_SCROLL_LR, maxLine);
        maxLines.put(BaseDanmaku.TYPE_FIX_BOTTOM, maxLine);
        danmakuContext.setMaximumLines(maxLines)
                .setScrollSpeedFactor(DanmuHelper.getSpeed())
                .setDanmakuTransparency(DanmuHelper.getAlpha())
                .setScaleTextSize(DanmuHelper.getSizeScale());
        danmakuContext.setDanmakuStyle(IDisplayer.DANMAKU_STYLE_STROKEN, 3)
                .setDanmakuMargin(8);
        if (reload && !TextUtils.isEmpty(danmuText) && DanmuHelper.isOpen()) {
            prepare(danmuText);
        }
    }

    public void check(String danmu) {
        check(danmu, "", "");
    }

    public void check(String danmu, String title, String episode) {
        check(danmu, title, episode, null);
    }

    public void check(String danmu, String title, String episode, LoadCallback callback) {
        check(danmu, title, episode, "", callback);
    }

    public void check(String danmu, String title, String episode, String displayName, LoadCallback callback) {
        DanmakuApi.cancel();
        loadCallback = callback;
        seekGeneration++;
        pendingSeekPosition = -1;
        temporarilyClosed = false;
        danmuText = TextUtils.isEmpty(danmu) ? "" : danmu.trim();
        danmuTitle = TextUtils.isEmpty(title) ? "" : title;
        danmuEpisode = TextUtils.isEmpty(episode) ? "" : episode;
        // 展示名只来自本次调用（搜索选中项等），每次都要覆盖，避免串到别的集
        danmuDisplayName = displayName == null ? "" : displayName.trim();
        releaseView();
        boolean hasDanmu = !TextUtils.isEmpty(danmuText);
        if (controller != null) controller.setHasDanmu(hasDanmu);
        if (!hasDanmu || !DanmuHelper.isOpen()) {
            if (danmuView != null) danmuView.setVisibility(View.GONE);
            return;
        }
        if (danmuView != null) danmuView.setVisibility(View.VISIBLE);
        if (!isVideoReady()) {
            pendingPrepare = true;
            return;
        }
        prepare(danmuText);
    }

    public void startIfReady() {
        if (pendingPrepare && !TextUtils.isEmpty(danmuText) && DanmuHelper.isOpen()
                && videoView != null && videoView.isPlaying()) {
            pendingPrepare = false;
            prepare(danmuText);
            return;
        }
        // seek 复用路径：恢复播放等时机下优先把弹幕时间轴按视频实际进度重新拉起
        if (resyncPending) {
            resyncIfReady();
            if (resyncPending) return;
        }
        startIfReady(loadSeq.get());
    }

    /**
     * 弹幕层尺寸变化时调用（由 View 的 layout 回调触发）。
     * 尺寸一直是 0 时 prepare 会被放弃，等真正拿到尺寸后这里补一次，否则弹幕永远不出来。
     */
    public void onViewSizeChanged() {
        if (danmuView == null || !DanmuHelper.isOpen() || TextUtils.isEmpty(danmuText)) return;
        if (danmuView.getWidth() <= 0 || danmuView.getHeight() <= 0) return;
        if (danmuView.isPrepared()) {
            startIfReady();
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastPrepareAt < 1000) return;
        lastPrepareAt = now;
        prepare(danmuText);
    }

    public void reset() {
        DanmakuApi.cancel();
        seekGeneration++;
        pendingSeekPosition = -1;
        temporarilyClosed = false;
        danmuText = "";
        danmuTitle = "";
        danmuEpisode = "";
        danmuDisplayName = "";
        pendingPrepare = false;
        loadCallback = null;
        loadSeq.incrementAndGet();
        startedSeq = -1;
        if (controller != null) controller.setHasDanmu(false);
        releaseView();
    }

    /**
     * 弹幕面板「当前弹幕」的展示名：
     * 优先用搜索选中的条目名，否则用自动加载时的 剧名+集名，没有弹幕时返回空。
     */
    public String getDanmuDisplayName() {
        // 只有真正挂载成功的弹幕才对外显示，下载/解析失败的不算
        if (!danmuLoaded || TextUtils.isEmpty(danmuText)) return "";
        if (!TextUtils.isEmpty(danmuDisplayName)) return danmuDisplayName;
        String name = (danmuTitle + " " + danmuEpisode).trim();
        return name;
    }

    public void close() {
        DanmakuApi.cancel();
        loadSeq.incrementAndGet();
        seekGeneration++;
        pendingSeekPosition = -1;
        startedSeq = -1;
        pendingPrepare = false;
        releaseView();
    }

    public boolean toggle() {
        if (temporarilyClosed) {
            temporarilyClosed = false;
            reloadForPlayback();
            startIfReady();
            return true;
        }
        temporarilyClosed = true;
        close();
        return false;
    }

    public void reloadForPlayback() {
        temporarilyClosed = false;
        loadSeq.incrementAndGet();
        seekGeneration++;
        pendingSeekPosition = -1;
        startedSeq = -1;
        releaseView();
        pendingPrepare = !TextUtils.isEmpty(danmuText) && DanmuHelper.isOpen();
    }

    public void destroy() {
        reset();
        if (videoView != null) videoView.setDanmuSeekListener(null);
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void prepare(String danmu) {
        if (TextUtils.isEmpty(danmu)) return;
        pendingPrepare = false;
        int seq = loadSeq.incrementAndGet();
        startedSeq = -1;
        final int currentSeekGeneration = seekGeneration;
        LOG.i("echo-danmu load title: " + safeLog(danmuTitle) + ", episode: " + safeLog(danmuEpisode) + ", source: " + getSourceSummary(danmu));
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor();
        }
        final long initialPosition;
        if (pendingSeekPosition >= 0) {
            initialPosition = pendingSeekPosition;
        } else if (videoView == null) {
            initialPosition = 0;
        } else {
            long currentPosition = videoView.getCurrentPosition();
            initialPosition = currentPosition > 0 ? currentPosition : videoView.getPlaybackPosition();
        }
        LOG.i("echo-danmu parse start at: " + initialPosition);
        executor.execute(() -> {
            Parser currentParser = new Parser(danmu, () -> seq != loadSeq.get(), initialPosition);
            if (seq != loadSeq.get()) return;
            int danmuCount = currentParser.getDanmuCount();
            LOG.i("echo-danmu parsed count: " + danmuCount);
            if (danmuView == null) return;
            final Parser preparedParser = currentParser;
            danmuView.post(() -> applyPrepared(seq, preparedParser, danmuCount, currentSeekGeneration, 0));
        });
    }

    /**
     * 真正把解析结果挂到弹幕层上。
     * 必须先确认 DanmakuView 已经有实际尺寸：如果此时 width/height 还是 0，
     * DanmakuView 内部会把所有弹幕判定为"超出屏幕"，结果一条都不显示
     * （高分辨率/慢设备上很容易在 layout 完成前就走到这里）。
     */
    private void applyPrepared(int seq, Parser parser, int danmuCount, int generation, int retry) {
        if (seq != loadSeq.get() || danmakuContext == null || danmuView == null) return;
        if (danmuView.getWidth() <= 0 || danmuView.getHeight() <= 0) {
            if (retry < 25) {
                danmuView.postDelayed(() -> applyPrepared(seq, parser, danmuCount, generation, retry + 1), 100);
            } else {
                LOG.e("echo-danmu view size still 0, skip prepare");
            }
            return;
        }
        try {
            danmuView.release();
            if (videoView != null) videoView.setDanmuView(danmuView);
            if (danmuCount <= 0) {
                LOG.e("echo-danmu empty after parse");
                danmuView.setVisibility(View.GONE);
                notifyLoadFailed(seq);
                return;
            }
            danmuView.prepare(parser, danmakuContext);
            // 到这里才算真正挂载成功，状态条的「当前弹幕：xxx」以此为准
            danmuLoaded = true;
            clearLoadCallback(seq);
            danmuView.setVisibility(DanmuHelper.isOpen() ? View.VISIBLE : View.GONE);
            startIfReady(seq);
            danmuView.postDelayed(() -> startIfReady(seq), 300);
            danmuView.postDelayed(() -> startIfReady(seq), 1000);
            // 真机上视频可能还在缓冲，多给几次机会，否则弹幕会一直停在未启动状态
            danmuView.postDelayed(() -> startIfReady(seq), 2000);
            danmuView.postDelayed(() -> startIfReady(seq), 3500);
            appendRemaining(parser, seq, generation);
        } catch (Throwable th) {
            LOG.e("echo-danmu prepare error: " + th.getMessage());
            danmuView.setVisibility(View.GONE);
            notifyLoadFailed(seq);
        }
    }

    private void appendRemaining(Parser parser, int seq, int generation) {
        appendRemaining(parser, seq, generation, false);
    }

    private void appendRemaining(Parser parser, int seq, int generation, boolean seekFirstBatch) {
        if (seq != loadSeq.get() || generation != seekGeneration || !DanmuHelper.isOpen() || !parser.hasMoreDanmaku()) return;
        if (danmuView == null) return;
        if (!danmuView.isPrepared()) {
            danmuView.postDelayed(() -> appendRemaining(parser, seq, generation, seekFirstBatch), 100);
            return;
        }
        if (executor == null || executor.isShutdown()) return;
        executor.execute(() -> {
            if (seq != loadSeq.get() || generation != seekGeneration || !DanmuHelper.isOpen() || danmuView == null) return;
            List<BaseDanmaku> batch = parser.parseNextBatch();
            if (batch.isEmpty()) return;
            for (BaseDanmaku item : batch) {
                if (seq != loadSeq.get() || generation != seekGeneration || !DanmuHelper.isOpen() || danmuView == null) return;
                danmuView.addDanmaku(item);
            }
            LOG.i("echo-danmu appended count: " + batch.size());
            if (seq == loadSeq.get() && generation == seekGeneration && DanmuHelper.isOpen() && parser.hasMoreDanmaku()) {
                long delay = seekFirstBatch ? 1000 : 50;
                danmuView.postDelayed(() -> appendRemaining(parser, seq, generation), delay);
            }
        });
    }

    private void onDanmuSeek(long position) {
        long targetPosition = Math.max(0, position);
        // 快进/快退等普通 seek：弹幕已经解析挂载好的话，直接把弹幕时间轴同步过去即可，
        // 不再整份重新解析（否则每次 seek 都要重新下载/解析，并重复触发一轮加载流程）
        if (DanmuHelper.isOpen() && !TextUtils.isEmpty(danmuText)
                && danmuView != null && danmuView.isPrepared()
                && startedSeq == loadSeq.get()) {
            danmuSeekResync();
            return;
        }
        // 兜底：弹幕还没挂载好（首次加载/被释放/切换集数中）才走重新解析的老路径。
        pendingSeekPosition = targetPosition;
        seekGeneration++;
        loadSeq.incrementAndGet();
        startedSeq = -1;
        if (DanmuHelper.isOpen() && !TextUtils.isEmpty(danmuText)) {
            pendingPrepare = true;
            if (danmuView != null) {
                danmuView.setVisibility(View.GONE);
                // 拖动进度后必须自己再拉一次：原来只把弹幕层藏起来等 prepared() 回调，
                // 但普通 seek 不会触发 prepared()，结果弹幕就一直是隐藏状态（真机续播进度时尤其明显）。
                danmuView.postDelayed(this::startIfReady, 400);
                danmuView.postDelayed(this::startIfReady, 1200);
                danmuView.postDelayed(this::startIfReady, 2500);
            }
        }
    }

    /** seek 复用路径：视频 seek 可能还在缓冲，用视频实际进度把弹幕时间轴同步过去，多补几次 */
    private void danmuSeekResync() {
        resyncPending = true;
        danmuView.setVisibility(View.GONE);
        danmuView.postDelayed(this::resyncIfReady, 400);
        danmuView.postDelayed(this::resyncIfReady, 1200);
        danmuView.postDelayed(this::resyncIfReady, 2500);
    }

    private void resyncIfReady() {
        if (!resyncPending) return;
        // 弹幕被卸载/重新加载/关闭后，旧的重同步任务直接作废
        if (!DanmuHelper.isOpen() || TextUtils.isEmpty(danmuText)
                || danmuView == null || !danmuView.isPrepared()
                || startedSeq != loadSeq.get()
                || videoView == null || !videoView.isPlaying()) {
            return;
        }
        long position = videoView.getCurrentPosition();
        // 进度还没落地时用记录的播放位置兜底，仍未拿到就留给最后一次重试
        if (position <= 0) position = videoView.getPlaybackPosition();
        if (position <= 0) return;
        resyncPending = false;
        danmuView.setVisibility(View.VISIBLE);
        danmuView.seekTo(position);
        danmuView.start(position);
        LOG.i("echo-danmu resync at: " + position);
    }

    private void clearLoadCallback(int seq) {
        if (seq == loadSeq.get()) loadCallback = null;
    }

    private void notifyLoadFailed(int seq) {
        if (seq != loadSeq.get() || loadCallback == null) return;
        LoadCallback callback = loadCallback;
        loadCallback = null;
        callback.onFailed();
    }

    private void startIfReady(int seq) {
        if (seq != loadSeq.get()
                || seq == startedSeq
                || videoView == null
                || !videoView.isPlaying()
                || danmuView == null
                || !danmuView.isPrepared()
                || !DanmuHelper.isOpen()) {
            return;
        }
        long position = videoView.getCurrentPosition();
        danmuView.setVisibility(View.VISIBLE);
        danmuView.seekTo(position);
        danmuView.start(position);
        startedSeq = seq;
        LOG.i("echo-danmu start at: " + position);
    }

    private boolean isVideoReady() {
        if (videoView == null) return false;
        int state = videoView.getCurrentPlayState();
        return state == VideoView.STATE_PREPARED
                || state == VideoView.STATE_BUFFERED
                || state == VideoView.STATE_PLAYING;
    }

    private void releaseView() {
        if (danmuView == null) return;
        try {
            danmuView.release();
        } catch (Throwable ignored) {
        }
        danmuView.setVisibility(View.GONE);
        // 弹幕层被释放后就不能再说「已挂载」了，否则搜索框状态条会显示一个实际不存在的弹幕
        danmuLoaded = false;
    }

    private String getSourceSummary(String danmu) {
        if (TextUtils.isEmpty(danmu)) return "";
        if (danmu.startsWith("http") || danmu.startsWith("file")) return danmu;
        return "inline xml length=" + danmu.length();
    }

    private String safeLog(String text) {
        return TextUtils.isEmpty(text) ? "" : text;
    }
}
