package com.github.tvbox.osc.util;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.github.tvbox.osc.api.IntroDbApi;
import com.github.tvbox.osc.bean.VodInfo;
import com.orhanobut.hawk.Hawk;

import org.json.JSONObject;

import xyz.doikki.videoplayer.player.VideoView;

/**
 * 片头片尾自动跳过模块。
 *
 * <p>流程：换集时 {@link #onEpisodeStart(String, int, boolean, int, int, boolean)} 带
 * 入片名 / 年份 / 季集 → 先查本地缓存，没有则 IMDb 建议接口把片名解析成 tt 号 →
 * 按用户选择的数据库查询片头片尾区间 → 播放进入区间后自动 seek。</p>
 *
 * <ul>
 *     <li>片头：位置落在片头区间内（还没到片尾结束点）时跳到片头结束点，每集只触发一次；</li>
 *     <li>片尾：进入片尾后 {@code 延迟秒数} 触发——片尾有已知结束点就 seek 过去，
 *         否则交给播放页走「下一集」（与手动设的 et 逻辑一致）。</li>
 * </ul>
 *
 * <p>设置开关见 {@link HawkConfig#INTRO_SKIP_OPEN} / {@link HawkConfig#INTRO_SKIP_CREDITS}；
 * 结果按「片名|年份|季|集」缓存 7 天（Hawk），避免每次换集都联网。</p>
 */
public class IntroSkipHelper {

    public interface Listener {
        /** 片尾没有已知结束点（一直到片尾结束）时回调，由播放页决定进下一集 */
        void onSkipCreditsToEnd();

        /**
         * 片头跳过后回调，用于轻提示与撤销。
         *
         * @param fromPos 跳过前的播放进度（毫秒），撤销时 seek 回这里
         */
        void onIntroSkipped(long fromPos);
    }

    /**
     * 播放器配置挂钩：解析到片头 / 片尾节点后，把它们写进播放器本身的
     * 片头片尾配置（st 片头秒数 / et 片尾倒数秒数）——抽屉里的「片头 / 片尾」
     * 按钮随即显示这两个节点，用户可在此基础上手动微调；片尾跳过也交回
     * {@code VodController} 原有的 et 机制执行。
     */
    public interface ConfigHook {
        /**
         * @param introSec             片头结束点（秒），0 表示没有片头数据
         * @param creditsCountdownSec  片尾倒数秒数（触发点=片尾开始后延迟秒），0 表示不写
         * @return 最终生效的 et 值（&gt;0 时 helper 不再自己触发片尾跳过）
         */
        int applyIntroOutro(int introSec, int creditsCountdownSec);
    }

    /** 轮询间隔：进度判断用 500ms 足够 */
    private static final long TICK_MS = 500;
    /** 进度未就绪（duration<=0）时最多轮询等待的次数，防止空转 */
    private static final int MAX_EMPTY_TICKS = 60;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private VideoView videoView;
    private Listener listener;
    private ConfigHook configHook;

    /** 当前集的上下文 */
    private String episodeKey;
    private IntroDbApi.Segments segments;
    private boolean manualCredits;      // 换集时已手动设过本集 et：片尾交给原有逻辑，避免双重触发
    private boolean introDone;
    private boolean creditsDone;
    private boolean hookApplied;        // 节点已写入播放器 st/et 配置（每集一次）
    private int configEt;               // 写入后生效的 et（>0 时片尾跳过由播放器 et 机制执行）
    private int emptyTicks;
    private boolean tickLogged;         // 本集首个有效 tick 的状态日志只打一次

    /** 查询序号：换集后旧请求的结果直接丢弃 */
    private int seq;
    private boolean tickRunning;
    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!tickRunning) return;
            tick();
            if (tickRunning) handler.postDelayed(this, TICK_MS);
        }
    };

    public IntroSkipHelper() {
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setConfigHook(ConfigHook configHook) {
        this.configHook = configHook;
    }

    /* ============================ 生命周期 ============================ */

    /** 播放页初始化时挂上播放器：自带播放状态监听，播放中才轮询进度 */
    public void attach(VideoView videoView) {
        this.videoView = videoView;
        if (videoView == null) {
            LOG.i("echo-intro attach: videoView=null，跳过模块不工作");
            return;
        }
        videoView.addOnStateChangeListener(new VideoView.SimpleOnStateChangeListener() {
            @Override
            public void onPlayStateChanged(int playState) {
                if (playState == VideoView.STATE_PLAYING) {
                    LOG.i("echo-intro 播放状态 STATE_PLAYING → startTick");
                    startTick();
                } else if (playState == VideoView.STATE_ERROR
                        || playState == VideoView.STATE_PLAYBACK_COMPLETED) {
                    LOG.i("echo-intro 播放状态 " + playState + "（错误/播完）→ stopTick");
                    stopTick();
                }
            }
        });
    }

    public void release() {
        stopTick();
        videoView = null;
        listener = null;
        seq++;
        segments = null;
        episodeKey = null;
    }

    /* ============================ 换集 ============================ */

    /**
     * 换集时调用：解析并预取本集的片头片尾数据。
     *
     * @param name          片名（可能带「第二季」等季数尾巴）
     * @param year          年份（0 未知），用于 IMDb 结果消歧
     * @param isMovie       电影（不带季集）
     * @param season        季（电影传 0）
     * @param episode       集（电影传 0）
     * @param manualCredits 播放页已手动设置本集片尾跳过（et &gt; 0）时为 true
     */
    public void onEpisodeStart(String name, int year, boolean isMovie, int season, int episode,
                               boolean manualCredits) {
        this.manualCredits = manualCredits;
        introDone = false;
        creditsDone = false;
        hookApplied = false;
        configEt = 0;
        emptyTicks = 0;
        tickLogged = false;
        segments = null;
        LOG.i("echo-intro onEpisodeStart: name=" + name + ", year=" + year
                + ", isMovie=" + isMovie + ", season=" + season + ", episode=" + episode
                + ", manualEt=" + manualCredits);
        if (!enabled()) {
            LOG.i("echo-intro skip: 未开启（INTRO_SKIP_OPEN/INTRO_SKIP_CREDITS 均关，或接口未配置）");
            return;
        }
        final int mySeq = ++seq;
        final String cacheKey = "intro_skip|" + DanmuTitleUtil.compact(name) + "|" + year
                + "|" + (isMovie ? 0 : season) + "|" + (isMovie ? 0 : episode);
        episodeKey = cacheKey;
        IntroDbApi.Segments cached = readCache(cacheKey);
        if (cached != null) {
            LOG.i("echo-intro 命中缓存: key=" + cacheKey
                    + (cached.hasIntro() ? ", intro=[" + cached.introStartMs + "," + cached.introEndMs + "]" : ", intro=无")
                    + (cached.hasCredits() ? ", credits=[" + cached.creditsStartMs + "," + cached.creditsEndMs + "]" : ", credits=无"));
            applySegments(mySeq, cacheKey, cached);
            return;
        }
        LOG.i("echo-intro 无缓存，开始解析 IMDb ID");
        // 先从缓存拿 IMDb ID（含负缓存，避免没收录的片名反复打接口）
        final String idCacheKey = "intro_skip_id|" + DanmuTitleUtil.compact(name) + "|" + year
                + "|" + (isMovie ? "movie" : "tv");
        String imdbId = Hawk.get(idCacheKey, (String) null);
        if (imdbId == null) {
            final boolean tv = !isMovie;
            IntroDbApi.resolveImdbId(name, year, tv, new IntroDbApi.IdCallback() {
                @Override
                public void onResult(String id) {
                    if (mySeq != seq) return;
                    LOG.i("echo-intro IMDb 解析结果: id=" + (TextUtils.isEmpty(id) ? "未命中" : id));
                    Hawk.put(idCacheKey, id == null ? "" : id);
                    if (TextUtils.isEmpty(id)) return;
                    fetchSegments(mySeq, cacheKey, id, season, episode, isMovie);
                }
            });
        } else if (!TextUtils.isEmpty(imdbId)) {
            LOG.i("echo-intro IMDb ID 命中缓存: " + imdbId);
            fetchSegments(mySeq, cacheKey, imdbId, season, episode, isMovie);
        } else {
            LOG.i("echo-intro IMDb ID 为负缓存（之前没解析到），本集放弃联网查询");
        }
    }

    private void fetchSegments(final int mySeq, final String cacheKey, final String imdbId,
                               final int season, final int episode, final boolean isMovie) {
        LOG.i("echo-intro 查询区间: imdb=" + imdbId + ", season=" + season
                + ", episode=" + episode + ", isMovie=" + isMovie);
        IntroDbApi.querySegments(imdbId, season, episode, isMovie, new IntroDbApi.SegmentsCallback() {
            @Override
            public void onResult(IntroDbApi.Segments result) {
                if (mySeq != seq) {
                    LOG.i("echo-intro 区间结果已过期（换集后丢弃），mySeq=" + mySeq + ", seq=" + seq);
                    return;
                }
                if (result != null) writeCache(cacheKey, result);
                applySegments(mySeq, cacheKey, result);
            }
        });
    }

    private void applySegments(int mySeq, String key, IntroDbApi.Segments result) {
        if (mySeq != seq || key == null || !key.equals(episodeKey)) return;
        segments = result;
        if (segments == null) {
            LOG.i("echo-intro 本集没有可用区间数据（接口无数据或查询失败），自动跳过不会触发");
            return;
        }
        LOG.i("echo-intro 区间就绪:"
                + (segments.hasIntro() ? " intro=[" + segments.introStartMs + "," + segments.introEndMs + "]ms" : " intro=无")
                + (segments.hasCredits() ? " credits=[" + segments.creditsStartMs + "," + segments.creditsEndMs + "]ms" : " credits=无"));
        // 断点续播已经在片头之后：片头无需再触发
        long pos = position();
        if (pos > 0 && segments.hasIntro() && pos >= segments.introEndMs) {
            introDone = true;
            LOG.i("echo-intro 起播位置 " + pos + "ms 已在片头(" + segments.introEndMs + "ms)之后，片头不触发");
        }
        // 数据先于播放器就绪时也尝试写入一次 st/et
        tryApplyHook();
        startTick();
    }

    /**
     * 把解析到的节点写入播放器的片头片尾配置（st / et）：
     * 播放器抽屉里的「片头 / 片尾」按钮显示并保存这两个节点，用户可继续手动微调；
     * 片尾跳过随之交回 {@code VodController} 的 et 机制，helper 只做兜底。
     */
    private void tryApplyHook() {
        if (hookApplied || configHook == null || videoView == null) return;
        IntroDbApi.Segments current = segments;
        if (current == null) return;
        long duration = videoView.getDuration();
        if (duration <= 0) return;
        hookApplied = true;
        int introSec = 0;
        if (Hawk.get(HawkConfig.INTRO_SKIP_OPEN, true) && current.hasIntro()) {
            introSec = Math.max(1, (int) Math.round(current.introEndMs / 1000.0));
        }
        int creditsSec = 0;
        if (!manualCredits
                && Hawk.get(HawkConfig.INTRO_SKIP_CREDITS, false)
                && current.hasCredits()) {
            int delaySec = Hawk.get(HawkConfig.INTRO_SKIP_CREDITS_DELAY, 3);
            long remaining = duration - (current.creditsStartMs + delaySec * 1000L);
            // et 是「倒数多少秒进下一集」，最少留 1 秒
            creditsSec = (int) Math.max(1, Math.round(remaining / 1000.0));
        }
        LOG.i("echo-intro 写入播放器配置: st=" + introSec + "s, et=" + creditsSec
                + "s（duration=" + duration + "ms，手动Et=" + manualCredits + "）");
        if (introSec > 0 || creditsSec > 0) {
            try {
                configEt = configHook.applyIntroOutro(introSec, creditsSec);
                LOG.i("echo-intro 播放器配置写入完成: 生效 et=" + configEt);
            } catch (Throwable th) {
                configEt = 0;
                LOG.e("echo-intro 播放器配置写入失败: " + th.getMessage());
            }
        }
    }

    /* ============================ 进度轮询 ============================ */

    private void startTick() {
        if (tickRunning || videoView == null) return;
        // 没开启任何跳过、也没拿到数据时不用轮询
        if (!enabled()) {
            LOG.i("echo-intro startTick 跳过: 未开启跳过或接口未配置（enabled()=false）");
            return;
        }
        tickRunning = true;
        handler.postDelayed(tickRunnable, TICK_MS);
    }

    private void stopTick() {
        tickRunning = false;
        handler.removeCallbacks(tickRunnable);
    }

    private void tick() {
        if (videoView == null) return;
        long duration = videoView.getDuration();
        long pos = position();
        if (duration <= 0 || pos <= 0) {
            if (++emptyTicks > MAX_EMPTY_TICKS) {
                LOG.i("echo-intro 停止轮询: 播放器迟迟无进度（emptyTicks=" + emptyTicks + "）");
                stopTick();
            }
            return;
        }
        if (!tickLogged) {
            tickLogged = true;
            IntroDbApi.Segments cur = segments;
            LOG.i("echo-intro 首个有效 tick: pos=" + pos + "ms, duration=" + duration + "ms"
                    + (cur == null ? ", segments=null（还没拿到数据）"
                    : (cur.hasIntro()
                    ? ", intro=[" + (cur.introStartMs == null ? 0 : cur.introStartMs) + "," + cur.introEndMs + "]"
                    : ", intro=无")
                    + (cur.hasCredits()
                    ? ", credits=[" + cur.creditsStartMs + "," + cur.creditsEndMs + "]"
                    : ", credits=无")));
        }
        // 拿到时长后把节点写进播放器 st/et 配置（每集一次）
        tryApplyHook();
        IntroDbApi.Segments current = segments;
        if (current == null) {
            LOG.i("echo-intro 停止轮询: 本集始终没有区间数据");
            stopTick(); // 本集没有可用数据，停掉轮询
            return;
        }
        if (Hawk.get(HawkConfig.INTRO_SKIP_OPEN, true)
                && current.hasIntro() && !introDone) {
            long introStart = current.introStartMs == null ? 0 : current.introStartMs;
            long introEnd = current.introEndMs;
            if (pos >= introStart && pos < introEnd - 800) {
                introDone = true;
                LOG.i("echo-intro ★触发片头跳过: pos=" + pos + "ms → seekTo " + (introEnd + 300) + "ms");
                videoView.seekTo(introEnd + 300);
                if (listener != null) listener.onIntroSkipped(pos);
                return;
            } else if (pos >= introEnd) {
                introDone = true; // 起播已过片头，不再触发
                LOG.i("echo-intro 片头不触发: 起播 pos=" + pos + "ms 已在片头结束点 " + introEnd + "ms 之后");
            }
        }
        if (Hawk.get(HawkConfig.INTRO_SKIP_CREDITS, false)
                && current.hasCredits() && !creditsDone
                && !manualCredits && configEt <= 0) {
            // configEt>0 或手动设过 et 时，片尾跳过交给播放器原有的 et 机制
            int delaySec = Hawk.get(HawkConfig.INTRO_SKIP_CREDITS_DELAY, 3);
            long trigger = current.creditsStartMs + delaySec * 1000L;
            if (pos >= trigger) {
                creditsDone = true;
                Long end = current.creditsEndMs;
                if (end != null && end > 0 && end < duration - 3000) {
                    // 片尾有明确结束点（后面还有内容，如彩蛋）：直接跳过去
                    LOG.i("echo-intro ★触发片尾跳过: seekTo 片尾结束点 " + (end + 300) + "ms");
                    videoView.seekTo(end + 300);
                } else if (listener != null) {
                    LOG.i("echo-intro ★触发片尾跳过: 无结束点 → 回调播放页进下一集");
                    listener.onSkipCreditsToEnd();
                } else {
                    LOG.i("echo-intro ★触发片尾跳过: 无结束点无监听 → seekTo 片尾前 " + (duration - 500) + "ms");
                    videoView.seekTo(Math.max(0, duration - 500));
                }
            }
        }
    }

    /**
     * 撤销本次片头跳过：seek 回跳过前的进度。
     * 本集 {@code introDone} 已置位，不会再次触发片头跳过。
     */
    public void undoIntroSkip(long fromPos) {
        if (videoView == null || fromPos <= 0) return;
        LOG.i("echo-intro 撤销片头跳过: seekTo " + fromPos + "ms");
        videoView.seekTo(fromPos);
    }

    /* ============================ 缓存 ============================ */
    private IntroDbApi.Segments readCache(String key) {
        try {
            String json = Hawk.get(key, (String) null);
            if (TextUtils.isEmpty(json)) return null;
            JSONObject obj = new JSONObject(json);
            long time = obj.optLong("t", 0);
            if (time <= 0 || System.currentTimeMillis() - time > HawkConfig.INTRO_SKIP_CACHE_TTL) {
                return null;
            }
            IntroDbApi.Segments result = new IntroDbApi.Segments();
            if (obj.optBoolean("hi", false)) {
                result.introStartMs = obj.optLong("is", 0);
                result.introEndMs = obj.optLong("ie", 0);
            }
            if (obj.optBoolean("hc", false)) {
                result.creditsStartMs = obj.optLong("cs", 0);
                if (obj.optBoolean("hk", false)) {
                    result.creditsEndMs = obj.optLong("ce", 0);
                }
            }
            return result;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void writeCache(String key, IntroDbApi.Segments segments) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("t", System.currentTimeMillis());
            if (segments.hasIntro()) {
                obj.put("hi", true);
                obj.put("is", segments.introStartMs == null ? 0 : segments.introStartMs);
                obj.put("ie", segments.introEndMs);
            }
            if (segments.hasCredits()) {
                obj.put("hc", true);
                obj.put("cs", segments.creditsStartMs);
                if (segments.creditsEndMs != null) {
                    obj.put("hk", true);
                    obj.put("ce", segments.creditsEndMs);
                }
            }
            Hawk.put(key, obj.toString());
        } catch (Throwable ignored) {
        }
    }

    /* ============================ 辅助 ============================ */

    private boolean enabled() {
        // 只看跳过开关：来源一个都没启用时，查询会回落到公共服务默认配置
        return Hawk.get(HawkConfig.INTRO_SKIP_OPEN, true)
                || Hawk.get(HawkConfig.INTRO_SKIP_CREDITS, false);
    }

    private long position() {
        if (videoView == null) return 0;
        try {
            return videoView.getCurrentPosition();
        } catch (Throwable th) {
            return 0;
        }
    }

    /**
     * 从 VodInfo 推断是否电影：只有一个播放线且只有一集按电影处理。
     * 单集剧集会走电影查询，但季集号不传，通常也能查到（数据按电视收录时 404 而已）。
     */
    public static boolean guessMovie(VodInfo vodInfo) {
        if (vodInfo == null || vodInfo.seriesMap == null || vodInfo.seriesMap.isEmpty()) return true;
        int flags = 0;
        int episodes = 0;
        for (java.util.List<VodInfo.VodSeries> series : vodInfo.seriesMap.values()) {
            if (series == null || series.isEmpty()) continue;
            flags++;
            episodes += series.size();
        }
        return flags <= 1 && episodes <= 1;
    }

    /** 从剧名 / 播放线名里解析季数（「庆余年 第二季」→ 2） */
    public static int guessSeason(VodInfo vodInfo) {
        if (vodInfo == null) return 0;
        int season = DanmuTitleUtil.seasonOf(vodInfo.name);
        if (season > 0) return season;
        if (vodInfo.seriesMap != null && !TextUtils.isEmpty(vodInfo.playFlag)
                && vodInfo.seriesMap.containsKey(vodInfo.playFlag)) {
            java.util.List<VodInfo.VodSeries> series = vodInfo.seriesMap.get(vodInfo.playFlag);
            if (series != null && !series.isEmpty()) {
                season = DanmuTitleUtil.seasonOf(series.get(0).name);
            }
        }
        return Math.max(0, season);
    }

    /** 从单集标题里解析集数（「第01集」「EP03」「天下 03」），解析不出退回索引 */
    public static int guessEpisode(String seriesName, int playIndex) {
        int episode = DanmuTitleUtil.episodeOf(seriesName == null ? "" : seriesName,
                seriesName == null ? "" : seriesName);
        if (episode <= 0) episode = playIndex + 1;
        return Math.max(1, episode);
    }
}
