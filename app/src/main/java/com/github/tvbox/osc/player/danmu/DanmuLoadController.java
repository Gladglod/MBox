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
    private int startedSeq = -1;
    private boolean pendingPrepare;
    private boolean temporarilyClosed;
    private long lastPrepareAt;
    private LoadCallback loadCallback;
    private volatile int seekGeneration;
    private volatile long pendingSeekPosition = -1;

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
        DanmakuApi.cancel();
        loadCallback = callback;
        seekGeneration++;
        pendingSeekPosition = -1;
        temporarilyClosed = false;
        danmuText = TextUtils.isEmpty(danmu) ? "" : danmu.trim();
        danmuTitle = TextUtils.isEmpty(title) ? "" : title;
        danmuEpisode = TextUtils.isEmpty(episode) ? "" : episode;
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
        pendingPrepare = false;
        loadCallback = null;
        loadSeq.incrementAndGet();
        startedSeq = -1;
        if (controller != null) controller.setHasDanmu(false);
        releaseView();
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
