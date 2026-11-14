package com.github.tvbox.osc.download;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.cache.DownloadRecord;
import com.github.tvbox.osc.cache.DownloadRecordDao;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.event.DownloadEvent;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.M3u8;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.OkGoHelper;
import com.google.android.exoplayer2.util.UriUtil;
import com.google.gson.Gson;
import com.orhanobut.hawk.Hawk;

import org.greenrobot.eventbus.EventBus;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * 离线缓存下载引擎。
 * <p>
 * 能力：
 * 1. 自动把详情页里保存的「选集页面地址」解析成真实媒体地址（复用各数据源的 spider / 接口）；
 * 2. 直链文件下载（Range 断点续传）；
 * 3. m3u8 / HLS 下载：多级播放列表、AES-128 解密、分段并发下载、失败重试、
 *    分段文件落盘支持断点续传，全部完成后合并成单个 .ts 文件；
 * 4. 任务队列（最多同时 2 个任务）、暂停 / 继续 / 删除（连文件一起清理）；
 * 5. 状态与进度写入 Room，UI 通过 EventBus {@link DownloadEvent} 实时刷新。
 *
 * @author MBox
 */
public class DownloadManager {

    private static final String TAG = "DownloadManager";
    /** 同时下载的任务数上限 */
    private static final int MAX_CONCURRENT = 2;
    /** 单个分段下载的并发数 */
    private static final int SEGMENT_CONCURRENT = 3;
    /** 分段请求失败重试次数 */
    private static final int SEGMENT_RETRY = 3;
    /** 进度事件节流间隔 ms */
    private static final long EVENT_THROTTLE = 500;
    /** spider 解析超时 */
    private static final long RESOLVE_TIMEOUT = 20;

    private static volatile DownloadManager instance;

    public static DownloadManager get() {
        if (instance == null) {
            synchronized (DownloadManager.class) {
                if (instance == null) {
                    instance = new DownloadManager();
                }
            }
        }
        return instance;
    }

    private final Gson gson = new Gson();
    private final ExecutorService taskExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT);
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    /** 任务队列（等待中的任务，先进先出） */
    private final List<DownloadRecord> queue = new ArrayList<>();
    /** 正在执行的任务 id */
    private final Set<Integer> runningIds = new HashSet<>();
    /** 请求暂停的任务 id（由工作线程轮询生效） */
    private final Set<Integer> pauseIds = new HashSet<>();
    /** 请求删除的任务 id */
    private final Set<Integer> deleteIds = new HashSet<>();

    private DownloadManager() {
        recoverRunningTasks();
    }

    /**
     * 进程被杀后重启：把残留的「下载中」任务改为「已暂停」，避免状态错乱；
     * 已下载的分段文件都在，用户手动点「继续」即可续传。
     */
    private void recoverRunningTasks() {
        try {
            List<DownloadRecord> all = getAll();
            if (all == null) return;
            for (DownloadRecord record : all) {
                if (record.state == DownloadRecord.STATE_RUNNING) {
                    record.state = DownloadRecord.STATE_PAUSE;
                    persist(record);
                }
            }
        } catch (Throwable th) {
            LOG.e(TAG + " recover:" + th.getMessage());
        }
    }

    /* ============================ 对外 API ============================ */

    public List<DownloadRecord> getAll() {
        return AppDataManager.get().getDownloadRecordDao().getAll();
    }

    public DownloadRecordDao dao() {
        return AppDataManager.get().getDownloadRecordDao();
    }

    /**
     * 同一剧同一线路同一集是否已有任务（无论完成与否），用于「缓存选集」弹窗去重。
     */
    public boolean hasTask(String sourceKey, String vodId, String playFlag, int playIndex) {
        try {
            return dao().get(sourceKey, vodId, playFlag, playIndex) != null;
        } catch (Throwable th) {
            return false;
        }
    }

    /**
     * 新增缓存任务。已存在同位置任务时返回 false。
     */
    public boolean addTask(DownloadRecord record) {
        try {
            if (record == null) return false;
            if (hasTask(record.sourceKey, record.vodId, record.playFlag, record.playIndex)) return false;
            long now = System.currentTimeMillis();
            record.createTime = now;
            record.updateTime = now;
            record.state = DownloadRecord.STATE_WAIT;
            // 存储位置：应用专属外部目录，无需存储权限
            File dir = new File(getDownloadRoot(), vodDirName(record));
            record.saveDir = dir.getAbsolutePath();
            record.filePath = new File(dir, fileName(record)).getAbsolutePath();
            record.type = isM3u8Url(record.url) ? DownloadRecord.TYPE_M3U8 : DownloadRecord.TYPE_DIRECT;
            long id = dao().insert(record);
            record.setId((int) id);
            post(DownloadEvent.TYPE_ADD, record);
            synchronized (this) {
                queue.add(record);
                scheduleLocked();
            }
            ensureService();
            return true;
        } catch (Throwable th) {
            LOG.e(TAG + " addTask:" + th.getMessage());
            return false;
        }
    }

    /** 暂停任务 */
    public void pause(DownloadRecord record) {
        if (record == null) return;
        if (record.isActive() && record.state != DownloadRecord.STATE_PAUSE) {
            synchronized (this) {
                boolean inQueue = queue.remove(record);
                if (inQueue) {
                    record.state = DownloadRecord.STATE_PAUSE;
                    persist(record);
                    post(DownloadEvent.TYPE_UPDATE, record);
                } else if (runningIds.contains(record.getId())) {
                    pauseIds.add(record.getId()); // 工作线程自行退出并更新状态
                }
            }
        }
    }

    /** 继续任务 */
    public void resume(DownloadRecord record) {
        if (record == null || record.isDone()) return;
        pauseIds.remove(record.getId());
        deleteIds.remove(record.getId());
        if (record.state == DownloadRecord.STATE_ERROR || record.state == DownloadRecord.STATE_PAUSE) {
            record.state = DownloadRecord.STATE_WAIT;
            record.errorMsg = null;
            persist(record);
            post(DownloadEvent.TYPE_UPDATE, record);
        }
        synchronized (this) {
            if (!queue.contains(record)) queue.add(record);
            scheduleLocked();
        }
        ensureService();
    }

    /** 删除任务（连同已下载的文件 / 临时分段一起删除） */
    public void delete(final DownloadRecord record) {
        if (record == null) return;
        synchronized (this) {
            queue.remove(record);
            deleteIds.add(record.getId());
        }
        // 等工作线程感知删除后再动文件
        if (runningIds.contains(record.getId())) {
            // 删除请求由工作线程在下一个检查点自己结束，文件清理放后台线程兜底执行
            taskExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    waitTaskExit(record.getId());
                    cleanupFiles(record);
                    dao().delete(record);
                    post(DownloadEvent.TYPE_REMOVE, record);
                }
            });
        } else {
            cleanupFiles(record);
            dao().delete(record);
            post(DownloadEvent.TYPE_REMOVE, record);
        }
    }

    /** 清空已完成 / 失败的任务（保留未完成任务），deleteFiles 是否连文件一起删 */
    public void clearFinished(boolean deleteFiles) {
        List<DownloadRecord> all = getAll();
        if (all == null) return;
        for (DownloadRecord record : all) {
            if (record.state == DownloadRecord.STATE_DONE || record.state == DownloadRecord.STATE_ERROR) {
                if (deleteFiles) delete(record);
                else {
                    dao().delete(record);
                    post(DownloadEvent.TYPE_REMOVE, record);
                }
            }
        }
    }

    /** 当前是否有任务在下载 / 排队 */
    public boolean hasActiveTasks() {
        try {
            return dao().getCountByState(DownloadRecord.STATE_WAIT) > 0
                    || dao().getCountByState(DownloadRecord.STATE_RUNNING) > 0;
        } catch (Throwable th) {
            return false;
        }
    }

    /** 离线缓存根目录：/sdcard/Android/data/{pkg}/files/download */
    public static File getDownloadRoot() {
        File base = App.getInstance().getExternalFilesDir(null);
        if (base == null) base = App.getInstance().getFilesDir();
        File dir = new File(base, "download");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /* ============================ 任务调度 ============================ */

    private synchronized void scheduleLocked() {
        Iterator<DownloadRecord> it = queue.iterator();
        while (it.hasNext() && runningIds.size() < MAX_CONCURRENT) {
            DownloadRecord next = it.next();
            it.remove();
            if (pauseIds.contains(next.getId()) || deleteIds.contains(next.getId())) continue;
            runningIds.add(next.getId());
            final DownloadRecord task = next;
            taskExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    runTask(task);
                }
            });
        }
    }

    private void runTask(final DownloadRecord record) {
        boolean success = false;
        String error = null;
        try {
            // 1. 解析真实播放地址
            PlayInfo info = resolvePlayUrl(record);
            if (info == null || TextUtils.isEmpty(info.url)) {
                throw new Exception("未获取到可下载的视频地址");
            }
            record.headersJson = gson.toJson(info.headers == null ? new HashMap<String, String>() : info.headers);
            // 2. 探测真实类型（m3u8 还是直链），直链按真实扩展名命名
            String ext = probeExt(info.url, info.headers);
            boolean m3u8 = "m3u8".equals(ext);
            record.type = m3u8 ? DownloadRecord.TYPE_M3U8 : DownloadRecord.TYPE_DIRECT;
            // 3. 分发到对应的下载器
            if (m3u8) {
                // m3u8 的最终扩展名（fMP4 → mp4 / TS → ts）在解析完播放列表后确定
                downloadM3u8(record, info);
            } else {
                renameTarget(record, ext);
                downloadDirect(record, info);
            }
            success = true;
        } catch (TaskAbort abort) {
            // 暂停 / 删除：保持调用方设置的状态
        } catch (Throwable th) {
            LOG.e(TAG + " task:" + th.getMessage());
            error = th.getMessage() == null ? "下载失败" : th.getMessage();
        } finally {
            synchronized (this) {
                runningIds.remove(record.getId());
            }
            if (deleteIds.remove(record.getId())) {
                // 已被删除：不写状态，交给删除逻辑收尾
            } else if (pauseIds.remove(record.getId())) {
                record.state = DownloadRecord.STATE_PAUSE;
                persist(record);
                post(DownloadEvent.TYPE_UPDATE, record);
            } else if (!success) {
                record.state = DownloadRecord.STATE_ERROR;
                record.errorMsg = error;
                record.updateTime = System.currentTimeMillis();
                persist(record);
                post(DownloadEvent.TYPE_UPDATE, record);
            }
            checkService();
            synchronized (this) {
                scheduleLocked();
            }
        }
    }

    private void waitTaskExit(int id) {
        long deadline = System.currentTimeMillis() + 15000;
        while (runningIds.contains(id) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }
        }
        deleteIds.remove(id);
    }

    /* ============================ 播放地址解析 ============================ */

    private static class PlayInfo {
        String url;
        HashMap<String, String> headers = new HashMap<>();
    }

    /**
     * 把详情页保存的「选集地址」解析成真实媒体地址，逻辑与 SourceViewModel.getPlay 一致：
     * type 3 → spider playerContent；type 0/1 → 直链或 JSON 解析接口；type 4 → 远程接口。
     * parse=1 且地址不是直链时，尝试用户配置的 type=1 JSON 解析接口（与在线播放的 initParse 对齐）；
     * 只有必须网页嗅探（type=0 解析）的集数不支持离线缓存。
     */
    private PlayInfo resolvePlayUrl(DownloadRecord record) throws Exception {
        SourceBean sourceBean = ApiConfig.get().getSource(record.sourceKey);
        if (sourceBean == null) throw new Exception("数据源不存在");
        int type = sourceBean.getType();
        String resultUrl = null;
        String playUrl = "";
        HashMap<String, String> headers = new HashMap<>();
        if (type == 3) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<String> future = executor.submit(new java.util.concurrent.Callable<String>() {
                    @Override
                    public String call() {
                        try {
                            Spider sp = ApiConfig.get().getCSP(sourceBean);
                            return sp.playerContent(record.playFlag, record.url, ApiConfig.get().getVipParseFlags());
                        } catch (Throwable th) {
                            return "";
                        }
                    }
                });
                String json = future.get(RESOLVE_TIMEOUT, TimeUnit.SECONDS);
                if (TextUtils.isEmpty(json)) throw new Exception("解析播放地址失败");
                JSONObject result = new JSONObject(json);
                resultUrl = pickQualityUrl(result.optString("url", ""));
                playUrl = result.optString("playUrl", "").trim();
                // video:// 前缀：地址本身是真实媒体地址（可能需附带 header），剥离后直接下载
                if (resultUrl.startsWith("video://")) {
                    resultUrl = resultUrl.substring(8);
                }
                parseHeaders(result.opt("header"), headers);
                parseHeaders(result.opt("headers"), headers);
                if (TextUtils.isEmpty(resultUrl)) throw new Exception("解析播放地址失败");
                boolean parse = result.optInt("parse", 1) == 1;
                boolean directUrl = resultUrl.startsWith("http")
                        && (isM3u8Url(resultUrl) || DefaultConfig.isVideoFormat(resultUrl));
                if (directUrl) {
                    // 直链：无论 parse 标记如何都可以离线下载
                } else if (playUrl.startsWith("json:") || playUrl.startsWith("parse:")) {
                    // 线路指定了 JSON 解析接口
                    return parseViaParseBean(record, playUrl);
                } else if (parse) {
                    // 需要二次解析：尝试用户配置的 JSON 解析接口；没有则只能网页嗅探，不支持
                    return parseViaParseBean(record, "");
                } else {
                    throw new Exception("未获取到可下载的视频地址");
                }
            } catch (TimeoutException e) {
                throw new Exception("解析播放地址超时");
            } finally {
                executor.shutdown();
            }
        } else if (type == 0 || type == 1) {
            String sitePlayUrl = sourceBean.getPlayerUrl() == null ? "" : sourceBean.getPlayerUrl().trim();
            if (sitePlayUrl.startsWith("json:") || sitePlayUrl.startsWith("parse:")) {
                return parseViaParseBean(record, sitePlayUrl);
            }
            if (!sitePlayUrl.isEmpty()) throw new Exception("该集需要网页嗅探，暂不支持离线缓存");
            if (!DefaultConfig.isVideoFormat(record.url)) {
                // 非直链（多为网页地址）：尝试用户配置的 JSON 解析接口
                return parseViaParseBean(record, "");
            }
            resultUrl = record.url;
        } else if (type == 4) {
            // 远程采集接口：GET api?play=xxx&flag=xxx&extend=xxx
            okhttp3.HttpUrl api = okhttp3.HttpUrl.parse(sourceBean.getApi());
            if (api == null) throw new Exception("采集接口地址无效");
            okhttp3.HttpUrl.Builder builder = api.newBuilder()
                    .addQueryParameter("play", record.url)
                    .addQueryParameter("flag", record.playFlag);
            String extend = sourceBean.getExt();
            if (!TextUtils.isEmpty(extend)) builder.addQueryParameter("extend", extend);
            Response resp = client().newCall(new Request.Builder().url(builder.build()).build()).execute();
            try {
                String json = resp.body() == null ? "" : resp.body().string();
                if (TextUtils.isEmpty(json)) throw new Exception("解析播放地址失败");
                JSONObject result = new JSONObject(json);
                resultUrl = pickQualityUrl(result.optString("url", ""));
                if (resultUrl.startsWith("video://")) resultUrl = resultUrl.substring(8);
                parseHeaders(result.opt("header"), headers);
                parseHeaders(result.opt("headers"), headers);
                if (resultUrl.startsWith("http") && (isM3u8Url(resultUrl) || DefaultConfig.isVideoFormat(resultUrl))) {
                    // 直链
                } else {
                    return parseViaParseBean(record, result.optString("playUrl", "").trim());
                }
            } finally {
                closeQuietly(resp);
            }
        } else {
            throw new Exception("该数据源类型暂不支持离线缓存");
        }
        PlayInfo info = new PlayInfo();
        info.url = resultUrl;
        info.headers = headers;
        return info;
    }

    /**
     * 多清晰度数组（["名字", "地址", ...]）取第一个地址；普通地址原样返回。
     */
    private String pickQualityUrl(String url) {
        try {
            if (url != null && url.startsWith("[") && url.endsWith("]")) {
                JSONArray array = new JSONArray(url);
                for (int i = 0; i < array.length(); i++) {
                    Object item = array.get(i);
                    if (item instanceof String) {
                        String str = (String) item;
                        if (str.startsWith("proxy://")) continue;
                        return str;
                    }
                }
                return "";
            }
        } catch (Throwable ignored) {
        }
        return url;
    }

    /**
     * 仿 PlayFragment.initParse/jsonParse：用配置的 type=1 JSON 解析接口把网页地址转成真实媒体地址。
     * playUrl 支持 "json:接口地址" / "parse:解析接口名"；为空时遍历全部 type=1 解析接口。
     */
    private PlayInfo parseViaParseBean(DownloadRecord record, String playUrl) throws Exception {
        List<ParseBean> beans = new ArrayList<>();
        if (playUrl.startsWith("json:")) {
            ParseBean pb = new ParseBean();
            pb.setType(1);
            pb.setUrl(playUrl.substring(5));
            beans.add(pb);
        } else if (playUrl.startsWith("parse:")) {
            String name = playUrl.substring(6);
            for (ParseBean pb : ApiConfig.get().getParseBeanList()) {
                if (name.equals(pb.getName())) {
                    if (pb.getType() != 1) throw new Exception("该集需要网页嗅探，暂不支持离线缓存");
                    beans.add(pb);
                    break;
                }
            }
            if (beans.isEmpty()) throw new Exception("未找到可用的解析接口");
        } else {
            for (ParseBean pb : ApiConfig.get().getParseBeanList()) {
                if (pb.getType() == 1) beans.add(pb);
            }
        }
        if (beans.isEmpty()) throw new Exception("该集需要网页嗅探，暂不支持离线缓存");
        Exception lastError = null;
        for (ParseBean pb : beans) {
            try {
                HashMap<String, String> reqHeaders = parseExtHeaders(pb);
                String resp = httpGetString(pb.getUrl() + URLEncoder.encode(record.url, "UTF-8"), reqHeaders);
                PlayInfo info = jsonParseResult(resp);
                if (info == null) {
                    lastError = new Exception("解析接口未返回有效地址");
                    continue;
                }
                return info;
            } catch (Exception e) {
                lastError = e;
            }
        }
        throw lastError != null ? lastError : new Exception("该集需要网页嗅探，暂不支持离线缓存");
    }

    /** 解析接口 ext 里配置的请求头 */
    private HashMap<String, String> parseExtHeaders(ParseBean pb) {
        HashMap<String, String> headers = new HashMap<>();
        try {
            if (pb.getExt() != null) {
                JSONObject ext = new JSONObject(pb.getExt());
                parseHeaders(ext.opt("header"), headers);
                parseHeaders(ext.opt("headers"), headers);
            }
        } catch (Throwable ignored) {
        }
        return headers;
    }

    /**
     * 仿 PlayFragment.jsonParse：从解析接口返回的 JSON 里提取真实媒体地址与请求头。
     * 返回 null 表示接口要求网页嗅探（parse=1），不支持离线缓存。
     */
    private PlayInfo jsonParseResult(String json) throws Exception {
        JSONObject jsonPlayData = new JSONObject(json);
        JSONObject playData = jsonPlayData.optJSONObject("data");
        if (playData == null) playData = jsonPlayData;
        String url = playData.optString("url", jsonPlayData.optString("url", ""));
        if (url.startsWith("//")) url = "http:" + url;
        if (url.startsWith("video://")) url = url.substring(8);
        url = DefaultConfig.checkReplaceProxy(url);
        if (!url.startsWith("http")) throw new Exception("解析接口未返回有效地址");
        int parse = playData.optInt("parse", jsonPlayData.optInt("parse", 0));
        if (parse == 1) return null;
        HashMap<String, String> headers = new HashMap<>();
        parseHeaders(playData.opt("header"), headers);
        parseHeaders(playData.opt("headers"), headers);
        parseHeaders(jsonPlayData.opt("header"), headers);
        parseHeaders(jsonPlayData.opt("headers"), headers);
        String ua = playData.optString("user-agent", jsonPlayData.optString("user-agent", ""));
        if (ua.trim().length() > 0) headers.put("User-Agent", ua.trim());
        String referer = playData.optString("referer", jsonPlayData.optString("referer", ""));
        if (referer.trim().length() > 0) headers.put("Referer", referer.trim());
        PlayInfo info = new PlayInfo();
        info.url = url;
        info.headers = headers;
        return info;
    }

    /**
     * 下载前探测真实类型：
     * 响应开头是 #EXTM3U 或 Content-Type 为 mpegurl → m3u8；
     * 否则按 Content-Type / 地址后缀推断直链扩展名（mp4 / mkv / flv ...）。
     */
    private String probeExt(String url, Map<String, String> headers) throws Exception {
        Response resp = client().newCall(request(url, headers).build()).execute();
        try {
            if (!resp.isSuccessful() || resp.body() == null) throw new Exception("服务器返回错误：" + resp.code());
            ResponseBody body = resp.body();
            String contentType = resp.header("Content-Type", "");
            String lowerType = contentType == null ? "" : contentType.toLowerCase(Locale.US);
            if (lowerType.contains("mpegurl") || lowerType.contains("m3u8")) return "m3u8";
            BufferedSource source = body.source();
            String head = "";
            try {
                head = source.peek().readUtf8(Math.min(16, body.contentLength() > 0 ? body.contentLength() : 16));
            } catch (Throwable ignored) {
            }
            if (head.startsWith("#EXTM3U")) return "m3u8";
            if (lowerType.contains("video/")) {
                if (lowerType.contains("mp4") || lowerType.contains("m4v")) return "mp4";
                if (lowerType.contains("matroska")) return "mkv";
                if (lowerType.contains("webm")) return "webm";
                if (lowerType.contains("flv")) return "flv";
                if (lowerType.contains("avi")) return "avi";
                if (lowerType.contains("quicktime")) return "mov";
                if (lowerType.contains("mp2t")) return "ts";
                return extFromUrl(url, "mp4");
            }
            return extFromUrl(url, "mp4");
        } finally {
            closeQuietly(resp);
        }
    }

    /** 直链扩展名：优先取地址里的真实后缀，取不到用默认值 */
    public static String extFromUrl(String url, String def) {
        if (url == null) return def;
        try {
            String path = url.toLowerCase(Locale.US);
            int query = path.indexOf('?');
            if (query >= 0) path = path.substring(0, query);
            int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            if (slash >= 0) path = path.substring(slash + 1);
            int dot = path.lastIndexOf('.');
            if (dot >= 0) {
                String ext = path.substring(dot + 1).trim();
                if (ext.length() > 0 && ext.length() <= 5) return ext;
            }
        } catch (Throwable ignored) {
        }
        return def;
    }

    /**
     * 按探测到的扩展名重命名目标文件（原来一律 .ts，直链 mp4 会被错误命名）。
     * 已有的断点临时文件跟随改名，保证续传不丢。
     */
    private void renameTarget(DownloadRecord record, String ext) {
        try {
            File dir = new File(record.saveDir);
            String newPath = new File(dir, fileName(record, ext)).getAbsolutePath();
            if (newPath.equals(record.filePath)) return;
            File oldPart = new File(record.filePath + ".part");
            File newPart = new File(newPath + ".part");
            if (oldPart.exists() && !newPart.exists()) oldPart.renameTo(newPart);
            record.filePath = newPath;
            record.totalLen = 0;
            record.currentLen = 0;
            persist(record);
            post(DownloadEvent.TYPE_UPDATE, record);
        } catch (Throwable th) {
            LOG.e(TAG + " renameTarget:" + th.getMessage());
        }
    }

    private void parseHeaders(JSONObject json, HashMap<String, String> out) {
        if (json == null) return;
        try {
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = json.optString(key, "");
                if (!TextUtils.isEmpty(key) && !TextUtils.isEmpty(value)) out.put(key, value);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 解析头信息：兼容 JSONObject 与 JSON 字符串两种形式（与 PlayFragment.appendHeaders 一致）。
     */
    private void parseHeaders(Object raw, HashMap<String, String> out) {
        if (raw == null || raw == JSONObject.NULL) return;
        try {
            JSONObject json = null;
            if (raw instanceof JSONObject) {
                json = (JSONObject) raw;
            } else if (raw instanceof String) {
                String text = ((String) raw).trim();
                if (!TextUtils.isEmpty(text)) json = new JSONObject(text);
            }
            if (json == null) return;
            parseHeaders(json, out);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isM3u8Url(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.US);
        int query = lower.indexOf('?');
        if (query >= 0) lower = lower.substring(0, query);
        return lower.endsWith(".m3u8") || lower.contains(".m3u8");
    }

    /* ============================ 直链下载 ============================ */

    private void downloadDirect(DownloadRecord record, PlayInfo info) throws Exception {
        checkAbort(record);
        File tmp = new File(record.filePath + ".part");
        File dir = tmp.getParentFile();
        if (!dir.exists()) dir.mkdirs();
        long offset = tmp.exists() ? tmp.length() : 0;
        Request.Builder rb = new Request.Builder().url(info.url);
        applyHeaders(rb, info.headers);
        if (offset > 0) rb.header("Range", "bytes=" + offset + "-");
        Response resp = client().newCall(rb.build()).execute();
        try {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new Exception("服务器返回错误：" + resp.code());
            }
            boolean append = resp.code() == 206 && offset > 0;
            if (resp.code() == 200 && offset > 0) {
                // 服务器不支持 Range，从头下载
                offset = 0;
                tmp.delete();
            }
            long contentLength = resp.body().contentLength();
            record.totalLen = contentLength > 0 ? contentLength + offset : 0;
            record.currentLen = offset;
            InputStream in = resp.body().byteStream();
            OutputStream out = new FileOutputStream(tmp, append);
            byte[] buf = new byte[8192];
            try {
                int len;
                long lastEvent = 0;
                while ((len = in.read(buf)) != -1) {
                    checkAbort(record);
                    out.write(buf, 0, len);
                    record.currentLen += len;
                    if (record.totalLen > 0) {
                        record.progress = (int) Math.min(99, record.currentLen * 100 / record.totalLen);
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastEvent >= EVENT_THROTTLE) {
                        lastEvent = now;
                        notifyProgress(record);
                    }
                }
            } finally {
                out.close();
                closeQuietly(in);
            }
        } finally {
            closeQuietly(resp);
        }
        // 完成
        File target = new File(record.filePath);
        if (target.exists()) target.delete();
        if (!tmp.renameTo(target)) throw new Exception("文件重命名失败");
        record.progress = 100;
        record.state = DownloadRecord.STATE_DONE;
        record.errorMsg = null;
        record.updateTime = System.currentTimeMillis();
        persist(record);
        post(DownloadEvent.TYPE_UPDATE, record);
    }

    /* ============================ m3u8 下载 ============================ */

    private void downloadM3u8(DownloadRecord record, PlayInfo info) throws Exception {
        checkAbort(record);
        File dir = new File(record.saveDir);
        if (!dir.exists()) dir.mkdirs();
        File tmpDir = new File(dir, "tmp_" + record.getId());
        if (!tmpDir.exists()) tmpDir.mkdirs();

        // 1. 拉取播放列表（处理多级 master playlist）
        String content = httpGetString(info.url, info.headers);
        String baseUrl = info.url;
        for (int level = 0; level < 3 && isMasterPlaylist(content); level++) {
            String variant = findBestVariant(baseUrl, content);
            if (TextUtils.isEmpty(variant)) break;
            baseUrl = variant;
            content = httpGetString(baseUrl, info.headers);
        }
        if (TextUtils.isEmpty(content) || !content.contains("#EXTINF")) {
            throw new Exception("m3u8 播放列表无效");
        }
        // 直播流（没有结束标记）无法完整离线缓存
        if (!content.contains("#EXT-X-ENDLIST")) {
            throw new Exception("直播流暂不支持离线缓存");
        }
        // SAMPLE-AES 加密暂不支持
        if (content.contains("METHOD=SAMPLE-AES") || content.contains("METHOD=FAIR-PLAY")) {
            throw new Exception("该流的加密方式暂不支持离线缓存");
        }
        // 可选：m3u8 去广告（与在线播放保持一致，开关 + 大站豁免）
        if (Hawk.get(HawkConfig.M3U8_PURIFY, false) && !DefaultConfig.noAd(record.playFlag)) {
            String purified = M3u8.purify(baseUrl, content);
            if (!TextUtils.isEmpty(purified) && purified.contains("#EXTINF")) {
                content = purified;
            }
        }

        // 2. 解析出分段列表（含 EXT-X-KEY / EXT-X-MAP / EXT-X-BYTERANGE）
        List<Segment> segments = new ArrayList<>();
        parseSegments(baseUrl, content, segments);
        if (segments.isEmpty()) throw new Exception("m3u8 中没有可下载的分段");
        Segment init = parseInitSegment(baseUrl, content);
        long mediaSequence = parseMediaSequence(content);

        // 3. 并发下载分段（分段文件已存在则跳过 → 天然支持断点续传）
        record.totalLen = 0;
        record.currentLen = 0;
        ExecutorService segPool = Executors.newFixedThreadPool(SEGMENT_CONCURRENT);
        final Exception[] segError = {null};
        try {
            int index = 0;
            int completed = 0;
            for (final Segment seg : segments) {
                final int segIndex = index++;
                final Segment segFinal = seg;
                segPool.submit(new Runnable() {
                    @Override
                    public void run() {
                        if (segError[0] != null) return;
                        try {
                            downloadSegment(tmpDir, segFinal, info, mediaSequence + segIndex);
                        } catch (Throwable th) {
                            if (segError[0] == null) segError[0] = th instanceof Exception ? (Exception) th : new Exception(th.getMessage());
                        }
                    }
                });
                // 每 SEGMENT_CONCURRENT 个分段等一批完成，保证顺序落盘 + 可控并发
                if ((segIndex + 1) % SEGMENT_CONCURRENT == 0 || segIndex == segments.size() - 1) {
                    while (true) {
                        checkAbort(record);
                        if (segError[0] != null) break;
                        long done = 0;
                        for (Segment s : segments) {
                            File f = segFile(tmpDir, s);
                            if (f.exists() && f.length() > 0) done++;
                        }
                        if (done >= segIndex + 1) {
                            if (completed < done) {
                                completed = (int) done;
                                record.progress = (int) (done * 100 / segments.size());
                                notifyProgress(record);
                            }
                            break;
                        }
                        Thread.sleep(150);
                    }
                    if (segError[0] != null) break;
                }
            }
        } finally {
            segPool.shutdown();
        }
        if (segError[0] != null) throw segError[0];

        // 4. 按真实封装格式命名（fMP4 → mp4，纯 TS → ts）
        renameTarget(record, init != null ? "mp4" : "ts");

        // 5. 合并分段成单个文件
        File target = new File(record.filePath);
        File targetTmp = new File(record.filePath + ".merge");
        if (targetTmp.exists()) targetTmp.delete();
        OutputStream out = new FileOutputStream(targetTmp);
        try {
            if (init != null) {
                byte[] initBytes = readFile(segFile(tmpDir, init));
                if (initBytes != null) out.write(initBytes);
            }
            for (Segment seg : segments) {
                checkAbort(record);
                byte[] bytes = readFile(segFile(tmpDir, seg));
                if (bytes == null || bytes.length == 0) throw new Exception("分段文件缺失，无法合并");
                out.write(bytes);
                record.currentLen += bytes.length;
            }
        } finally {
            out.close();
        }
        if (target.exists()) target.delete();
        if (!targetTmp.renameTo(target)) throw new Exception("文件重命名失败");
        deleteDir(tmpDir);
        record.progress = 100;
        record.state = DownloadRecord.STATE_DONE;
        record.errorMsg = null;
        record.updateTime = System.currentTimeMillis();
        persist(record);
        post(DownloadEvent.TYPE_UPDATE, record);
    }

    private File segFile(File tmpDir, Segment seg) {
        return new File(tmpDir, String.format(Locale.US, "%05d.ts", seg.index));
    }

    /** 下载单个分段（加密分段解密后以明文保存；已存在且非空则跳过） */
    private void downloadSegment(File tmpDir, Segment seg, PlayInfo info, long sequence) throws Exception {
        File f = segFile(tmpDir, seg);
        if (f.exists() && f.length() > 0) return;
        byte[] data = null;
        Exception lastError = null;
        for (int retry = 0; retry < SEGMENT_RETRY && data == null; retry++) {
            try {
                if (retry > 0) Thread.sleep(500L * retry);
                if (seg.byteLen >= 0) {
                    data = httpGetBytesRange(seg.url, info.headers, seg.byteOffset, seg.byteLen);
                } else {
                    data = httpGetBytes(seg.url, info.headers);
                }
            } catch (Exception e) {
                lastError = e;
            }
        }
        if (data == null) {
            throw lastError != null ? lastError : new Exception("分段下载失败");
        }
        if (seg.encrypted) {
            data = decryptAes(data, seg.keyUrl, seg.iv, sequence, info);
        }
        File tmp = new File(f.getAbsolutePath() + ".dl");
        FileOutputStream fos = new FileOutputStream(tmp);
        try {
            fos.write(data);
        } finally {
            fos.close();
        }
        if (!tmp.renameTo(f)) {
            // 目标已存在（并发重复下载）：丢弃临时文件即可
            tmp.delete();
        }
    }

    /** AES-128 解密（HLS 标准：AES/CBC/PKCS7；无填充的流降级为 NoPadding 截断处理） */
    private byte[] decryptAes(byte[] data, String keyUrl, String ivHex, long sequence, PlayInfo info) throws Exception {
        byte[] keyBytes = keyCacheGet(keyUrl, info);
        byte[] ivBytes;
        if (!TextUtils.isEmpty(ivHex)) {
            ivBytes = parseIv(ivHex);
        } else {
            ivBytes = new byte[16];
            for (int i = 0; i < 8; i++) ivBytes[15 - i] = (byte) ((sequence >> (8 * i)) & 0xFF);
        }
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(ivBytes));
        try {
            return cipher.doFinal(data);
        } catch (Exception e) {
            // 部分 CDN 不做填充，退回 NoPadding 并去掉末尾不完整的块
            cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(ivBytes));
            byte[] plain = cipher.doFinal(data, 0, data.length - (data.length % 16));
            return plain;
        }
    }

    private final Map<String, byte[]> keyCache = new HashMap<>();

    private byte[] keyCacheGet(String keyUrl, PlayInfo info) throws Exception {
        synchronized (keyCache) {
            byte[] cached = keyCache.get(keyUrl);
            if (cached != null) return cached;
        }
        Response resp = client().newCall(request(keyUrl, info.headers).build()).execute();
        byte[] key;
        try {
            if (!resp.isSuccessful() || resp.body() == null) throw new Exception("获取解密密钥失败");
            key = resp.body().bytes();
        } finally {
            closeQuietly(resp);
        }
        synchronized (keyCache) {
            keyCache.put(keyUrl, key);
        }
        return key;
    }

    private static byte[] parseIv(String iv) {
        String hex = iv.trim();
        if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2);
        byte[] out = new byte[16];
        int len = Math.min(hex.length(), 32);
        for (int i = 0; i < len / 2; i++) {
            out[16 - len / 2 + i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /* ============================ m3u8 解析 ============================ */

    private static class Segment {
        int index;
        String url;
        boolean encrypted;
        String keyUrl;
        String iv;
        /** EXT-X-BYTERANGE：子范围下载（-1 表示整个分段） */
        long byteOffset = -1;
        long byteLen = -1;
    }

    private boolean isMasterPlaylist(String content) {
        return content != null && content.contains("#EXT-X-STREAM-INF");
    }

    /** 找出码率最高的子播放列表地址 */
    private String findBestVariant(String baseUrl, String content) {
        String best = null;
        long bestBandwidth = -1;
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                long bandwidth = 0;
                int idx = line.indexOf("BANDWIDTH=");
                if (idx < 0) idx = line.indexOf("bandwidth=");
                if (idx >= 0) {
                    StringBuilder num = new StringBuilder();
                    for (int j = idx + 10; j < line.length(); j++) {
                        char c = line.charAt(j);
                        if (c >= '0' && c <= '9') num.append(c);
                        else if (num.length() > 0) break;
                    }
                    try {
                        bandwidth = Long.parseLong(num.toString());
                    } catch (Exception ignored) {
                    }
                }
                String url = nextMediaLine(lines, i);
                if (url != null && bandwidth >= bestBandwidth) {
                    bestBandwidth = bandwidth;
                    best = UriUtil.resolve(baseUrl, url);
                }
            }
        }
        return best;
    }

    private String nextMediaLine(String[] lines, int from) {
        for (int i = from + 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0) continue;
            if (line.startsWith("#")) continue;
            return line;
        }
        return null;
    }

    private void parseSegments(String baseUrl, String content, List<Segment> out) {
        String pendingKeyUrl = null;
        String pendingIv = null;
        String pendingKeyMethod = "NONE";
        // EXT-X-BYTERANGE：<长度>[@<起始偏移>]；不写偏移时接着上一分段末尾
        long pendingRangeLen = -1;
        long pendingRangeOffset = -1;
        long runningOffset = 0;
        int index = 0;
        String[] lines = content.split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-KEY")) {
                String method = attr(line, "METHOD");
                pendingKeyMethod = method == null ? "NONE" : method;
                String uri = attr(line, "URI");
                pendingKeyUrl = TextUtils.isEmpty(uri) ? null : UriUtil.resolve(baseUrl, uri);
                pendingIv = attr(line, "IV");
                continue;
            }
            if (line.startsWith("#EXT-X-BYTERANGE")) {
                try {
                    String spec = line.substring(line.indexOf(':') + 1).trim();
                    int at = spec.indexOf('@');
                    pendingRangeLen = Long.parseLong(at >= 0 ? spec.substring(0, at) : spec);
                    pendingRangeOffset = at >= 0 ? Long.parseLong(spec.substring(at + 1)) : -1;
                } catch (Throwable th) {
                    pendingRangeLen = -1;
                    pendingRangeOffset = -1;
                }
                continue;
            }
            if (line.startsWith("#") || line.length() == 0) continue;
            Segment seg = new Segment();
            seg.index = index;
            seg.url = UriUtil.resolve(baseUrl, line);
            if (pendingKeyMethod != null && pendingKeyMethod.toUpperCase(Locale.US).startsWith("AES") && pendingKeyUrl != null) {
                seg.encrypted = true;
                seg.keyUrl = pendingKeyUrl;
                seg.iv = pendingIv;
            }
            if (pendingRangeLen >= 0) {
                long offset = pendingRangeOffset >= 0 ? pendingRangeOffset : runningOffset;
                seg.byteOffset = offset;
                seg.byteLen = pendingRangeLen;
                runningOffset = offset + pendingRangeLen;
                pendingRangeLen = -1;
                pendingRangeOffset = -1;
            }
            out.add(seg);
            index++;
        }
    }

    /** EXT-X-MAP（fMP4 init 段），有则合并到文件头 */
    private Segment parseInitSegment(String baseUrl, String content) {
        String[] lines = content.split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-MAP")) {
                String uri = attr(line, "URI");
                if (!TextUtils.isEmpty(uri)) {
                    Segment seg = new Segment();
                    seg.index = -1;
                    seg.url = UriUtil.resolve(baseUrl, uri);
                    return seg;
                }
            }
        }
        return null;
    }

    private long parseMediaSequence(String content) {
        String[] lines = content.split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE")) {
                try {
                    return Long.parseLong(line.substring(line.indexOf(':') + 1).trim());
                } catch (Exception ignored) {
                }
            }
        }
        return 0;
    }

    private String attr(String tagLine, String name) {
        int idx = tagLine.indexOf(name + "=");
        if (idx < 0) return null;
        int start = tagLine.indexOf('"', idx);
        if (start >= 0) {
            int end = tagLine.indexOf('"', start + 1);
            if (end > start) return tagLine.substring(start + 1, end);
        }
        // 无引号的值
        int valueStart = idx + name.length() + 1;
        int end = tagLine.indexOf(',', valueStart);
        return end < 0 ? tagLine.substring(valueStart).trim() : tagLine.substring(valueStart, end).trim();
    }

    /* ============================ HTTP ============================ */

    private OkHttpClient client() {
        OkHttpClient client = OkGoHelper.getDefaultClient();
        if (client != null) return client;
        return new OkHttpClient();
    }

    private Request.Builder request(String url, Map<String, String> headers) {
        Request.Builder rb = new Request.Builder().url(url);
        applyHeaders(rb, headers);
        return rb;
    }

    private void applyHeaders(Request.Builder rb, Map<String, String> headers) {
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                try {
                    rb.header(entry.getKey(), entry.getValue());
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private String httpGetString(String url, Map<String, String> headers) throws Exception {
        Response resp = client().newCall(request(url, headers).build()).execute();
        try {
            if (!resp.isSuccessful() || resp.body() == null) throw new Exception("请求失败：" + resp.code());
            return resp.body().string();
        } finally {
            closeQuietly(resp);
        }
    }

    private byte[] httpGetBytes(String url, Map<String, String> headers) throws Exception {
        Response resp = client().newCall(request(url, headers).build()).execute();
        try {
            if (!resp.isSuccessful() || resp.body() == null) throw new Exception("请求失败：" + resp.code());
            return resp.body().bytes();
        } finally {
            closeQuietly(resp);
        }
    }

    /** 带 Range 的分段请求（EXT-X-BYTERANGE） */
    private byte[] httpGetBytesRange(String url, Map<String, String> headers, long offset, long length) throws Exception {
        Request.Builder rb = request(url, headers);
        rb.header("Range", "bytes=" + offset + "-" + (offset + length - 1));
        Response resp = client().newCall(rb.build()).execute();
        try {
            if (!resp.isSuccessful() || resp.body() == null) throw new Exception("请求失败：" + resp.code());
            return resp.body().bytes();
        } finally {
            closeQuietly(resp);
        }
    }

    /* ============================ 工具 ============================ */

    private static class TaskAbort extends RuntimeException {
    }

    private void checkAbort(DownloadRecord record) {
        if (pauseIds.contains(record.getId())) throw new TaskAbort();
        if (deleteIds.contains(record.getId())) throw new TaskAbort();
    }

    private void notifyProgress(DownloadRecord record) {
        long now = System.currentTimeMillis();
        record.updateTime = now;
        long last = lastNotify.get(record.getId()) == null ? 0 : lastNotify.get(record.getId());
        if (now - last >= EVENT_THROTTLE) {
            lastNotify.put(record.getId(), now);
            persist(record);
            post(DownloadEvent.TYPE_UPDATE, record);
        }
    }

    private final Map<Integer, Long> lastNotify = new HashMap<>();

    private void persist(DownloadRecord record) {
        try {
            dao().update(record);
        } catch (Throwable th) {
            LOG.e(TAG + " persist:" + th.getMessage());
        }
    }

    private void post(int type, DownloadRecord record) {
        final DownloadEvent event = new DownloadEvent(type, record);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                EventBus.getDefault().post(event);
            }
        });
    }

    private void ensureService() {
        try {
            DownloadService.ensureStarted(App.getInstance());
        } catch (Throwable ignored) {
        }
    }

    private void checkService() {
        try {
            DownloadService.ensureStopped(App.getInstance());
        } catch (Throwable ignored) {
        }
    }

    public static String vodDirName(DownloadRecord record) {
        String name = sanitize(record.vodName == null ? "video" : record.vodName);
        String hash = MD5.string2MD5(record.sourceKey + "_" + record.vodId).substring(0, 8);
        return name + "-" + hash;
    }

    public static String fileName(DownloadRecord record) {
        return fileName(record, "ts");
    }

    /** 按真实扩展名命名（直链 mp4 / fMP4 的 m3u8 → mp4，纯 TS 的 m3u8 → ts） */
    public static String fileName(DownloadRecord record, String ext) {
        String name = record.episodeName == null || record.episodeName.trim().isEmpty()
                ? "EP" + (record.playIndex + 1)
                : record.episodeName;
        return sanitize(name) + "_" + (record.playIndex + 1) + "." + ext;
    }

    private static String sanitize(String name) {
        String clean = name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", " ").trim();
        if (clean.length() > 50) clean = clean.substring(0, 50);
        return clean.isEmpty() ? "video" : clean;
    }

    private void cleanupFiles(DownloadRecord record) {
        try {
            if (!TextUtils.isEmpty(record.saveDir)) {
                File dir = new File(record.saveDir);
                if (dir.exists() && TextUtils.isEmpty(record.filePath)) {
                    deleteDir(dir);
                    return;
                }
                // 删除本集文件 + 本集临时目录；目录里没有其他文件时把剧目录也清掉
                if (!TextUtils.isEmpty(record.filePath)) new File(record.filePath).delete();
                File tmp = new File(dir, "tmp_" + record.getId());
                if (tmp.exists()) deleteDir(tmp);
                File[] remains = dir.listFiles();
                if (remains != null && remains.length == 0) dir.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDir(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    private byte[] readFile(File file) {
        if (!file.exists()) return null;
        try {
            FileInputStream in = new FileInputStream(file);
            try {
                byte[] out = new byte[(int) file.length()];
                int read = 0;
                while (read < out.length) {
                    int len = in.read(out, read, out.length - read);
                    if (len < 0) break;
                    read += len;
                }
                return out;
            } finally {
                closeQuietly(in);
            }
        } catch (Throwable th) {
            return null;
        }
    }

    private void closeQuietly(Object closeable) {
        try {
            if (closeable instanceof Response) {
                ((Response) closeable).close();
            } else if (closeable instanceof InputStream) {
                ((InputStream) closeable).close();
            } else if (closeable instanceof OutputStream) {
                ((OutputStream) closeable).close();
            }
        } catch (Throwable ignored) {
        }
    }
}
