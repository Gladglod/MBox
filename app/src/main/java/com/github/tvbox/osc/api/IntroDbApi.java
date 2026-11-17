package com.github.tvbox.osc.api;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.util.DanmuTitleUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.OkGoHelper;
import com.orhanobut.hawk.Hawk;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 片头片尾数据库远程接口。
 *
 * <p>数据库来源是一个可管理的列表（见 {@link #getSources()}）：两个内置接口属于
 * 「公共服务」，可按需添加（地址可改成镜像/代理），也可添加自定义接口；
 * 查询格式分两种——</p>
 *
 * <ul>
 *     <li><b>TheIntroDB v4</b> {@code GET {url}/media?imdb_id=tt…&season=&episode=}
 *         —— 返回 {@code intro / recap / credits / preview} 四类区间数组，
 *         {@code start_ms} 或 {@code end_ms} 可为 null（片头从 0 开始 / 片尾直到片尾结束），
 *         404 + {@code code: no_submissions} 表示该集还没有人提交过；</li>
 *     <li><b>IntroDB</b> {@code GET {url}/segments?imdb_id=tt…&season=&episode=}
 *         —— 电影带 {@code is_movie=true}；
 *         返回单个对象 {@code intro / outro}，缺失为 null，
 *         {@code no_intro} / {@code no_outro} 表示社区确认没有该区间。</li>
 * </ul>
 *
 * <p>两个库都只能按 IMDb tt 号查询，不支持按片名搜索，所以这里额外提供
 * IMDb 官方建议接口（免密钥）做「片名 → tt 号」解析。</p>
 *
 * <p>查询采用<b>多来源顺序兜底</b>：按来源的添加顺序依次查询，
 * 某个来源查不到（失败 / 无数据）就换下一个；一个都没结果才放弃。
 * 一个来源都没启用时，默认回落到两个公共服务。</p>
 */
public class IntroDbApi {

    /** 查询格式：TheIntroDB v4 兼容（{url}/media） */
    public static final int FORMAT_THEINTRODB = 0;
    /** 查询格式：IntroDB 兼容（{url}/segments） */
    public static final int FORMAT_INTRODB = 1;

    private static final String DEFAULT_URL_THEINTRODB = "https://api.theintrodb.org/v4";
    private static final String DEFAULT_URL_INTRODB = "https://api.introdb.app";
    /** 公共服务（内置默认接口）的固定 id，用于判断是否已添加 */
    public static final String ID_PUBLIC_THEINTRODB = "builtin_theintrodb";
    public static final String ID_PUBLIC_INTRODB = "builtin_introdb";
    private static final String ID_BUILTIN_THEINTRODB = ID_PUBLIC_THEINTRODB;
    private static final String ID_BUILTIN_INTRODB = ID_PUBLIC_INTRODB;

    /** IMDb 官方片名建议接口（公开、免密钥），用于把片名解析成 tt 号 */
    private static final String IMDB_SUGGEST_API = "https://v2.sg.media-imdb.com/suggestion";

    private static final Handler handler = new Handler(Looper.getMainLooper());

    /* ============================ 数据库来源 ============================ */

    /** 一个数据库接口来源：列表顺序 = 查询顺序；可同时启用多个 */
    public static class DbSource {
        public String id;
        public String name;
        public String url;
        /** 查询格式：{@link #FORMAT_THEINTRODB} / {@link #FORMAT_INTRODB} */
        public int format;
        /** true = 公共服务（两个内置接口），可删除后从「公共服务」重新添加 */
        public boolean builtIn;
        /** 是否参与查询（列表里的开关状态） */
        public boolean enabled;
    }

    public static String getDefaultUrl(int format) {
        return format == FORMAT_INTRODB ? DEFAULT_URL_INTRODB : DEFAULT_URL_THEINTRODB;
    }

    public static String getFormatName(int format) {
        return format == FORMAT_INTRODB ? "IntroDB" : "TheIntroDB v4";
    }

    /** 全部数据库来源（首次调用会生成默认列表并迁移旧版单选配置） */
    public static List<DbSource> getSources() {
        List<DbSource> list = parseSources(Hawk.get(HawkConfig.INTRO_DB_SOURCES, ""));
        if (list == null) {
            list = new ArrayList<>();
            list.add(buildPublicService(ID_BUILTIN_THEINTRODB, "TheIntroDB",
                    DEFAULT_URL_THEINTRODB, FORMAT_THEINTRODB));
            list.add(buildPublicService(ID_BUILTIN_INTRODB, "IntroDB",
                    DEFAULT_URL_INTRODB, FORMAT_INTRODB));
            migrateLegacy(list);
            saveSources(list);
        }
        return list;
    }

    /** 造一个公共服务来源（默认地址 + 启用） */
    private static DbSource buildPublicService(String id, String name, String url, int format) {
        DbSource source = new DbSource();
        source.id = id;
        source.name = name;
        source.url = url;
        source.format = format;
        source.builtIn = true;
        source.enabled = true;
        return source;
    }

    public static void saveSources(List<DbSource> sources) {
        JSONArray array = new JSONArray();
        if (sources != null) {
            for (DbSource source : sources) {
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("id", source.id);
                    obj.put("name", source.name);
                    obj.put("url", source.url);
                    obj.put("format", source.format);
                    obj.put("builtIn", source.builtIn);
                    obj.put("enabled", source.enabled);
                    array.put(obj);
                } catch (Throwable ignored) {
                }
            }
        }
        Hawk.put(HawkConfig.INTRO_DB_SOURCES, array.toString());
    }

    /** 按 id 找来源，找不到返回 null */
    public static DbSource findSource(String id) {
        if (TextUtils.isEmpty(id)) return null;
        for (DbSource source : getSources()) {
            if (id.equals(source.id)) return source;
        }
        return null;
    }

    /** 参与查询的来源（enabled 且地址非空），顺序 = 添加顺序 */
    public static List<DbSource> getEnabledSources() {
        List<DbSource> result = new ArrayList<>();
        for (DbSource source : getSources()) {
            if (source.enabled && !TextUtils.isEmpty(source.url)) result.add(source);
        }
        return result;
    }

    /** 当前是否有任何可用的来源（一个都没有时查询回落到公共服务默认地址） */
    public static boolean isConfigured() {
        return !getEnabledSources().isEmpty();
    }

    public static boolean setSourceEnabled(String id, boolean enabled) {
        List<DbSource> sources = getSources();
        for (DbSource source : sources) {
            if (source.id.equals(id)) {
                source.enabled = enabled;
                saveSources(sources);
                return true;
            }
        }
        return false;
    }

    public static DbSource addCustomSource(String name, String url, int format) {
        DbSource source = new DbSource();
        source.id = UUID.randomUUID().toString();
        source.name = TextUtils.isEmpty(name) ? "自定义接口" : name.trim();
        source.url = url == null ? "" : url.trim();
        source.format = format;
        source.builtIn = false;
        source.enabled = true;
        List<DbSource> sources = getSources();
        sources.add(source);
        saveSources(sources);
        return source;
    }

    /**
     * 添加公共服务（两个内置接口的默认配置），追加到列表末位 = 查询顺序末位；
     * 已添加过返回 false。
     */
    public static boolean addPublicService(int format) {
        String id = format == FORMAT_INTRODB ? ID_BUILTIN_INTRODB : ID_BUILTIN_THEINTRODB;
        if (findSource(id) != null) return false;
        DbSource source = buildPublicService(id,
                format == FORMAT_INTRODB ? "IntroDB" : "TheIntroDB",
                getDefaultUrl(format), format);
        List<DbSource> sources = getSources();
        sources.add(source);
        saveSources(sources);
        return true;
    }

    /** 公共服务是否已添加 */
    public static boolean isPublicServiceAdded(int format) {
        return findSource(format == FORMAT_INTRODB ? ID_BUILTIN_INTRODB : ID_BUILTIN_THEINTRODB) != null;
    }

    /** 编辑地址；来源不存在返回 false */
    public static boolean updateSourceUrl(String id, String url) {
        List<DbSource> sources = getSources();
        for (DbSource source : sources) {
            if (source.id.equals(id)) {
                source.url = url == null ? "" : url.trim();
                saveSources(sources);
                return true;
            }
        }
        return false;
    }

    public static boolean setSourceFormat(String id, int format) {
        List<DbSource> sources = getSources();
        for (DbSource source : sources) {
            if (source.id.equals(id)) {
                source.format = format;
                saveSources(sources);
                return true;
            }
        }
        return false;
    }

    /** 内置来源的地址恢复默认 */
    public static boolean resetSourceUrl(String id) {
        List<DbSource> sources = getSources();
        for (DbSource source : sources) {
            if (source.id.equals(id) && source.builtIn) {
                source.url = getDefaultUrl(source.format);
                saveSources(sources);
                return true;
            }
        }
        return false;
    }

    /** 删除来源（公共服务也可删，之后可从「公共服务」重新添加） */
    public static boolean removeSource(String id) {
        List<DbSource> sources = getSources();
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).id.equals(id)) {
                sources.remove(i);
                saveSources(sources);
                return true;
            }
        }
        return false;
    }

    /** 序列化来源列表；内容损坏返回 null（触发重建默认） */
    private static List<DbSource> parseSources(String json) {
        if (TextUtils.isEmpty(json)) return null;
        try {
            JSONArray array = new JSONArray(json);
            if (array.length() == 0) return null;
            List<DbSource> list = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) continue;
                DbSource source = new DbSource();
                source.id = obj.optString("id", "");
                source.name = obj.optString("name", "");
                source.url = obj.optString("url", "");
                source.format = obj.optInt("format", FORMAT_THEINTRODB);
                source.builtIn = obj.optBoolean("builtIn", false);
                // 旧版数据没有 enabled 字段：公共服务默认启用，其余默认停用
                source.enabled = obj.optBoolean("enabled", source.builtIn);
                if (TextUtils.isEmpty(source.id) || TextUtils.isEmpty(source.name)) continue;
                list.add(source);
            }
            return list.isEmpty() ? null : list;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 旧版单选配置（INTRO_DB_SOURCE + INTRO_DB_CUSTOM_URL）迁移进来源列表 */
    private static void migrateLegacy(List<DbSource> list) {
        int old = Hawk.get(HawkConfig.INTRO_DB_SOURCE, -1);
        if (old == 2) {
            String customUrl = Hawk.get(HawkConfig.INTRO_DB_CUSTOM_URL, "");
            if (!TextUtils.isEmpty(customUrl)) {
                DbSource custom = new DbSource();
                custom.id = UUID.randomUUID().toString();
                custom.name = "自定义";
                custom.url = customUrl;
                custom.format = FORMAT_THEINTRODB;
                custom.builtIn = false;
                custom.enabled = true;
                list.add(custom);
            }
        }
        // 旧版是单选启用，新版两个公共服务默认都启用，不再单独迁移 active
    }

    /* ============================ 区间查询 ============================ */

    /** 一次查询解析出的片头 / 片尾区间（毫秒），全部 null 表示该集没有数据 */
    public static class Segments {
        public Long introStartMs;
        public Long introEndMs;
        public Long creditsStartMs;
        /** null 表示片尾一直到视频结束 */
        public Long creditsEndMs;

        public boolean hasIntro() {
            return introEndMs != null && introEndMs > (introStartMs == null ? 0 : introStartMs);
        }

        public boolean hasCredits() {
            return creditsStartMs != null && creditsStartMs > 0;
        }
    }

    public interface SegmentsCallback {
        void onResult(Segments segments);
    }

    public interface IdCallback {
        /** id 为空串表示没解析到（也用于负缓存） */
        void onResult(String imdbId);
    }

    /**
     * 查询某一集的片头 / 片尾区间：按启用来源的添加顺序逐个尝试，
     * 某个来源失败或查不到数据就换下一个；全部落空才回调 null。
     * 一个来源都没启用时，回落到两个公共服务的默认地址。
     *
     * @param season  季（电影传 0）
     * @param episode 集（电影传 0）
     * @param isMovie 电影查询时免带季集
     */
    public static void querySegments(String imdbId, int season, int episode, boolean isMovie,
                                     final SegmentsCallback callback) {
        if (TextUtils.isEmpty(imdbId) || callback == null) return;
        List<DbSource> chain = getEnabledSources();
        if (chain.isEmpty()) {
            // 用户没有启用/添加任何数据库：默认采用公共服务的默认配置
            chain = new ArrayList<>();
            chain.add(buildPublicService(ID_BUILTIN_THEINTRODB, "TheIntroDB",
                    DEFAULT_URL_THEINTRODB, FORMAT_THEINTRODB));
            chain.add(buildPublicService(ID_BUILTIN_INTRODB, "IntroDB",
                    DEFAULT_URL_INTRODB, FORMAT_INTRODB));
            LOG.i("echo-intro 未启用任何数据库来源，回落到公共服务默认配置");
        }
        queryNext(chain, 0, imdbId, season, episode, isMovie, callback);
    }

    /** 逐个尝试来源链：index 处的来源查完没有可用数据就继续下一个 */
    private static void queryNext(final List<DbSource> chain, final int index,
                                  final String imdbId, final int season, final int episode,
                                  final boolean isMovie, final SegmentsCallback callback) {
        if (index >= chain.size()) {
            LOG.i("echo-intro query 所有来源均无数据（共 " + chain.size() + " 个）");
            postResult(callback, null);
            return;
        }
        final DbSource source = chain.get(index);
        if (source == null || TextUtils.isEmpty(source.url)) {
            queryNext(chain, index + 1, imdbId, season, episode, isMovie, callback);
            return;
        }
        String url;
        final boolean theIntroDbStyle;
        try {
            String encoded = URLEncoder.encode(imdbId, "UTF-8");
            if (source.format == FORMAT_INTRODB) {
                url = normalizeBase(source.url) + "/segments?imdb_id=" + encoded;
                if (isMovie) {
                    url += "&is_movie=true";
                } else {
                    url += "&season=" + Math.max(1, season) + "&episode=" + 1;
                }
                theIntroDbStyle = false;
            } else {
                url = normalizeBase(source.url) + "/media?imdb_id=" + encoded;
                if (!isMovie) url += "&season=" + Math.max(1, season) + "&episode=" + 1;
                theIntroDbStyle = true;
            }
        } catch (Throwable th) {
            LOG.e("echo-intro query 拼接 URL 失败: " + th.getMessage());
            queryNext(chain, index + 1, imdbId, season, episode, isMovie, callback);
            return;
        }
        LOG.i("echo-intro query[" + (index + 1) + "/" + chain.size() + "]: url=" + url
                + "（来源=" + source.name + ", format=" + getFormatName(source.format) + "）");
        final String urlFinal = url;
        Request request = new Request.Builder().url(url).get().build();
        httpClient().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull final IOException e) {
                LOG.e("echo-intro query 请求失败: " + e.getMessage() + ", url=" + urlFinal
                        + "，换下一个来源");
                queryNext(chain, index + 1, imdbId, season, episode, isMovie, callback);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                Segments segments = null;
                int code = response.code();
                try {
                    // 404 在这两个数据库里都表示「该集没有数据」而不是查询失败
                    if (code == 200) {
                        String body = response.body() == null ? "" : response.body().string();
                        if (theIntroDbStyle) {
                            segments = parseTheIntroDb(body);
                        } else {
                            segments = parseIntroDb(body);
                        }
                        LOG.i("echo-intro query 响应 200: "
                                + (segments == null || (!segments.hasIntro() && !segments.hasCredits())
                                ? "无可用区间"
                                : (segments.hasIntro()
                                ? "intro=[" + (segments.introStartMs == null ? 0 : segments.introStartMs)
                                + "," + segments.introEndMs + "]ms" : "intro=无")
                                + (segments.hasCredits()
                                ? ", credits=[" + segments.creditsStartMs + "," + segments.creditsEndMs + "]ms"
                                : ", credits=无"))
                                + "，body=" + (body.length() > 500 ? body.substring(0, 500) + "…" : body));
                    } else {
                        LOG.i("echo-intro query 响应 " + code
                                + (code == 404 ? "（该集无数据）" : "（非 200，视为查询失败）"));
                    }
                } catch (Throwable th) {
                    LOG.e("echo-intro query 解析响应失败: " + th.getMessage());
                } finally {
                    try {
                        if (response.body() != null) response.close();
                    } catch (Throwable ignored) {
                    }
                }
                boolean usable = segments != null && (segments.hasIntro() || segments.hasCredits());
                if (usable) {
                    postResult(callback, segments);
                } else {
                    // 这个来源没查到：换下一个来源
                    queryNext(chain, index + 1, imdbId, season, episode, isMovie, callback);
                }
            }
        });
    }

    /** TheIntroDB v4：intro / credits 是区间数组，start 或 end 可为 null */
    private static Segments parseTheIntroDb(String body) {
        Segments segments = new Segments();
        try {
            JSONObject json = new JSONObject(body);
            JSONArray intro = json.optJSONArray("intro");
            if (intro != null && intro.length() > 0) {
                JSONObject item = intro.optJSONObject(0);
                if (item != null && item.optLong("end_ms", 0) > 0) {
                    segments.introStartMs = item.isNull("start_ms") ? 0L : item.optLong("start_ms", 0);
                    segments.introEndMs = item.optLong("end_ms", 0);
                }
            }
            JSONArray credits = json.optJSONArray("credits");
            if (credits != null && credits.length() > 0) {
                JSONObject item = credits.optJSONObject(0);
                if (item != null && item.optLong("start_ms", 0) > 0) {
                    segments.creditsStartMs = item.optLong("start_ms", 0);
                    segments.creditsEndMs = item.isNull("end_ms") ? null : item.optLong("end_ms", 0);
                    if (segments.creditsEndMs != null && segments.creditsEndMs <= 0) segments.creditsEndMs = null;
                }
            }
        } catch (Throwable ignored) {
        }
        return segments;
    }

    /** IntroDB：intro / outro 是单个对象（或 null） */
    private static Segments parseIntroDb(String body) {
        Segments segments = new Segments();
        try {
            JSONObject json = new JSONObject(body);
            JSONObject intro = json.optJSONObject("intro");
            if (intro != null) {
                long start = intro.optLong("start_ms", 0);
                long end = intro.optLong("end_ms", 0);
                if (end > start) {
                    segments.introStartMs = start;
                    segments.introEndMs = end;
                }
            }
            JSONObject outro = json.optJSONObject("outro");
            if (outro != null) {
                long start = outro.optLong("start_ms", 0);
                long end = outro.optLong("end_ms", 0);
                if (start > 0) {
                    segments.creditsStartMs = start;
                    segments.creditsEndMs = end > start ? end : null;
                }
            }
        } catch (Throwable ignored) {
        }
        return segments;
    }

    /* ============================ IMDb ID 解析 ============================ */

    /**
     * 片名 → IMDb ID（免密钥）。
     * <p>IMDb 建议接口返回的标题多为英文，中文片名按「年份 + 类型 + 相关度」挑最合适的；
     * 解析不到回调空串。</p>
     */
    public static void resolveImdbId(String name, int year, boolean isTv, final IdCallback callback) {
        if (callback == null || TextUtils.isEmpty(name)) return;
        String query = cleanQuery(name);
        if (TextUtils.isEmpty(query)) {
            postResult(callback, "");
            return;
        }
        String url;
        try {
            String slug = query.toLowerCase().replaceAll("\\s+", "_");
            char pathChar = 'a';
            for (int i = 0; i < query.length(); i++) {
                char ch = Character.toLowerCase(query.charAt(i));
                if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                    pathChar = ch;
                    break;
                }
            }
            url = IMDB_SUGGEST_API + "/" + pathChar + "/" + URLEncoder.encode(slug, "UTF-8") + ".json";
        } catch (Throwable th) {
            LOG.e("echo-intro resolve 拼接 URL 失败: " + th.getMessage());
            postResult(callback, "");
            return;
        }
        LOG.i("echo-intro resolve: query=\"" + query + "\", year=" + year + ", isTv=" + isTv + ", url=" + url);
        Request request = new Request.Builder().url(url).get().build();
        httpClient().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                LOG.e("echo-intro resolve 请求失败: " + e.getMessage() + ", url=" + url);
                postResult(callback, "");
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String imdbId = "";
                try {
                    if (response.code() == 200) {
                        String body = response.body() == null ? "" : response.body().string();
                        JSONArray list = new JSONObject(body).optJSONArray("d");
                        imdbId = pickBestMatch(list, query, year, isTv);
                        LOG.i("echo-intro resolve 候选 " + (list == null ? 0 : list.length())
                                + " 条 → 选中 " + (TextUtils.isEmpty(imdbId) ? "无（全被过滤/得分过低）" : imdbId));
                    } else {
                        LOG.i("echo-intro resolve 响应 " + response.code() + "（非 200）");
                    }
                } catch (Throwable th) {
                    LOG.e("echo-intro resolve 解析响应失败: " + th.getMessage());
                } finally {
                    try {
                        if (response.body() != null) response.close();
                    } catch (Throwable ignored) {
                    }
                }
                postResult(callback, imdbId);
            }
        });
    }

    /** 去掉片名里的季数尾巴，数据库里同一部剧按不带季的片名收录 */
    private static String cleanQuery(String name) {
        String text = DanmuTitleUtil.normalize(name);
        // 「庆余年 第二季」「权力的游戏 S03」→ 留下主片名
        text = text.replaceAll("(?i)第\\s*[0-9一二三四五六七八九十]{1,3}\\s*[季部篇]", " ");
        text = text.replaceAll("(?i)\\bS\\s*[0-9]{1,3}\\b", " ");
        text = text.replaceAll("(?i)\\bSEASON\\s*[0-9]{1,3}\\b", " ");
        text = text.replaceAll("\\s{2,}", " ").trim();
        // 再兜一层「尾部季数」：「天下3」→「天下」
        String base = DanmuTitleUtil.keywordBase(text);
        return TextUtils.isEmpty(base) ? text : base;
    }

    /**
     * 从建议结果里挑最合适的条目：
     * 标题完全一致 &gt; 标题互相包含 &gt; 年份一致 &gt; 类型一致 &gt; 接口排序（相关度）。
     */
    private static String pickBestMatch(JSONArray list, String query, int year, boolean isTv) {
        if (list == null || list.length() == 0) return "";
        String bestId = "";
        int bestScore = -1;
        String compactQuery = DanmuTitleUtil.compact(query);
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id", "");
            if (id == null || !id.matches("tt[0-9]{7,8}")) continue;
            String qid = item.optString("qid", "");
            boolean itemIsTv = qid.startsWith("tv");
            if (itemIsTv != isTv) continue;

            int score = 0;
            String title = DanmuTitleUtil.compact(item.optString("l", ""));
            if (!TextUtils.isEmpty(title) && !TextUtils.isEmpty(compactQuery)) {
                if (title.equals(compactQuery)) {
                    score += 50;
                } else if (title.contains(compactQuery) || compactQuery.contains(title)) {
                    score += 15;
                }
            }
            if (year > 0) {
                int itemYear = item.optInt("y", 0);
                if (itemYear == year) {
                    score += 25;
                } else {
                    String yearRange = item.optString("yr", "");
                    Matcher range = Pattern.compile("(\\d{4})-?(\\d{0,4})").matcher(yearRange);
                    if (range.matches()) {
                        int from = Integer.parseInt(range.group(1));
                        String to = range.group(2);
                        int toYear = TextUtils.isEmpty(to) ? 2999 : Integer.parseInt(to);
                        if (toYear < 1000) toYear = Integer.parseInt(from / 100 + to);
                        if (year >= from && year <= toYear) score += 15;
                    }
                }
            }
            // 排序兜底：越靠前相关度越高
            score += Math.max(0, 5 - i);
            if (score > bestScore) {
                bestScore = score;
                bestId = id;
            }
        }
        return bestScore > 0 ? bestId : "";
    }

    /* ============================ 通用 ============================ */

    private static OkHttpClient httpClient() {
        // 单次查询给足 10s，避免弱网下一直挂着
        return OkGoHelper.getDefaultClient().newBuilder()
                .callTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    private static <T> void postResult(final T callback, final Object value) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (callback instanceof SegmentsCallback) {
                        ((SegmentsCallback) callback).onResult((Segments) value);
                    } else if (callback instanceof IdCallback) {
                        ((IdCallback) callback).onResult((String) value);
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /**
     * 规范化来源地址：补协议、去尾部斜杠；用户直接粘贴完整端点
     * （…/v4/media 或 …/segments）时把端点尾巴去掉，由调用处统一拼接。
     */
    private static String normalizeBase(String base) {
        String url = base == null ? "" : base.trim();
        if (TextUtils.isEmpty(url)) return "";
        if (!url.startsWith("http")) url = "https://" + url;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.endsWith("/media") || url.endsWith("/segments")) {
            url = url.substring(0, url.lastIndexOf('/'));
        }
        return url;
    }
}
