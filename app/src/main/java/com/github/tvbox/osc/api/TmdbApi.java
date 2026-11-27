package com.github.tvbox.osc.api;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.OkGoHelper;
import com.orhanobut.hawk.Hawk;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * TMDB（The Movie Database）接口封装。
 *
 * <p>官方文档：<a href="https://developer.themoviedb.org/docs">developer.themoviedb.org</a>。
 * 发现页只用到公开的 v3 REST 接口：</p>
 *
 * <ul>
 *     <li>{@code /3/trending/{movie|tv|all}/{day|week}} 趋势榜</li>
 *     <li>{@code /3/discover/movie}、{@code /3/discover/tv} 按类型 / 评分 / 年份筛选</li>
 *     <li>{@code /3/genre/movie/list}、{@code /3/genre/tv/list} 类型字典</li>
 *     <li>{@code /3/search/multi} 多类型搜索</li>
 *     <li>{@code /3/movie/{id}}、{@code /3/tv/{id}} 详情（含演职员）</li>
 * </ul>
 *
 * <p>鉴权同时兼容两种方式：</p>
 * <ul>
 *     <li><b>v3 API Key</b>（32 位字符串）—— 作为 {@code api_key} 查询参数；</li>
 *     <li><b>v4 Read Access Token</b>（JWT，以 {@code eyJ} 开头）—— 作为
 *         {@code Authorization: Bearer …} 请求头。</li>
 * </ul>
 *
 * <p>Key、API 地址与图片地址都保存在本机 Hawk 中，方便无法直连官方域名的网络环境
 * 使用反向代理。所有回调都切回主线程。</p>
 */
public final class TmdbApi {

    public static final int MEDIA_MOVIE = 0;
    public static final int MEDIA_TV = 1;
    public static final int MEDIA_ALL = 2;

    public static final String DEFAULT_BASE_URL = "https://api.themoviedb.org";
    public static final String DEFAULT_IMAGE_BASE = "https://image.tmdb.org/t/p/";
    public static final String DEFAULT_LANGUAGE = "zh-CN";
    public static final String DEFAULT_REGION = "CN";

    /** TMDB 官方图片尺寸档位（https://developer.themoviedb.org/docs/image-basics） */
    public static final String POSTER_SIZE = "w342";
    public static final String POSTER_SIZE_BIG = "w500";
    public static final String BACKDROP_SIZE = "w780";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<Call> ACTIVE_CALLS =
            Collections.synchronizedSet(new HashSet<Call>());

    private TmdbApi() {
    }

    /* ============================ 配置 ============================ */

    public static String getApiKey() {
        return normalizeKey(Hawk.get(HawkConfig.TMDB_API_KEY, ""));
    }

    public static void setApiKey(String key) {
        Hawk.put(HawkConfig.TMDB_API_KEY, normalizeKey(key));
    }

    public static boolean hasApiKey() {
        return !TextUtils.isEmpty(getApiKey());
    }

    public static String getBaseUrl() {
        return normalizeBase(Hawk.get(HawkConfig.TMDB_BASE_URL, DEFAULT_BASE_URL));
    }

    public static void setBaseUrl(String url) {
        Hawk.put(HawkConfig.TMDB_BASE_URL, TextUtils.isEmpty(trim(url)) ? DEFAULT_BASE_URL : trim(url));
    }

    public static String getImageBase() {
        return normalizeImageBase(Hawk.get(HawkConfig.TMDB_IMAGE_BASE, DEFAULT_IMAGE_BASE));
    }

    public static void setImageBase(String url) {
        Hawk.put(HawkConfig.TMDB_IMAGE_BASE,
                TextUtils.isEmpty(trim(url)) ? DEFAULT_IMAGE_BASE : trim(url));
    }

    public static void clearCredentials() {
        Hawk.delete(HawkConfig.TMDB_API_KEY);
    }

    /** 拼出海报 / 剧照完整地址；path 为空时返回空串 */
    public static String imageUrl(String path, String size) {
        if (TextUtils.isEmpty(path)) return "";
        String p = path.startsWith("/") ? path : "/" + path;
        String s = TextUtils.isEmpty(size) ? POSTER_SIZE : size;
        return getImageBase() + s + p;
    }

    /** 影片在 TMDB 网站的详情页 */
    public static String webUrl(Item item) {
        if (item == null || item.id <= 0) return "";
        String type = "tv".equals(item.mediaType) ? "tv" : "movie";
        return "https://www.themoviedb.org/" + type + "/" + item.id;
    }

    /** 取消所有在途请求（页面销毁时调用） */
    public static void cancelAll() {
        synchronized (ACTIVE_CALLS) {
            for (Call call : ACTIVE_CALLS) {
                try {
                    call.cancel();
                } catch (Throwable ignored) {
                }
            }
            ACTIVE_CALLS.clear();
        }
    }

    /* ============================ 数据模型 ============================ */

    /** 列表 / 搜索结果里的一条影视条目 */
    public static class Item {
        public int id;
        /** movie / tv */
        public String mediaType = "movie";
        public String title = "";
        public String originalTitle = "";
        public String overview = "";
        public String posterPath = "";
        public String backdropPath = "";
        public String date = "";
        public String originalLanguage = "";
        public double rating;
        public int voteCount;
        public List<Integer> genreIds = new ArrayList<>();

        public String displayTitle() {
            if (!TextUtils.isEmpty(title)) return title;
            if (!TextUtils.isEmpty(originalTitle)) return originalTitle;
            return "未命名";
        }

        public String year() {
            if (!TextUtils.isEmpty(date) && date.length() >= 4) return date.substring(0, 4);
            return "";
        }

        public boolean isTv() {
            return "tv".equals(mediaType);
        }

        public String mediaLabel() {
            return isTv() ? "剧集" : "电影";
        }

        public String posterUrl() {
            return posterUrl(POSTER_SIZE);
        }

        public String posterUrl(String size) {
            return imageUrl(posterPath, size);
        }

        public String backdropUrl() {
            return imageUrl(backdropPath, BACKDROP_SIZE);
        }

        public String uniqueKey() {
            return mediaType + ":" + id;
        }

        static Item fromJson(JSONObject json, String fallbackMediaType) {
            if (json == null) return null;
            Item item = new Item();
            item.id = json.optInt("id", 0);
            if (item.id <= 0) return null;

            String mediaType = str(json, "media_type");
            if (TextUtils.isEmpty(mediaType)) mediaType = fallbackMediaType;
            item.mediaType = "tv".equalsIgnoreCase(mediaType) ? "tv" : "movie";

            item.title = firstNonEmpty(str(json, "title"), str(json, "name"));
            item.originalTitle = firstNonEmpty(
                    str(json, "original_title"), str(json, "original_name"));
            item.overview = str(json, "overview");
            item.posterPath = str(json, "poster_path");
            item.backdropPath = str(json, "backdrop_path");
            item.date = firstNonEmpty(
                    str(json, "release_date"), str(json, "first_air_date"));
            item.originalLanguage = str(json, "original_language");
            item.rating = json.optDouble("vote_average", 0d);
            item.voteCount = json.optInt("vote_count", 0);
            item.genreIds = intArray(json.optJSONArray("genre_ids"));
            if (TextUtils.isEmpty(item.title)) item.title = item.originalTitle;
            if (TextUtils.isEmpty(item.title)) return null;
            return item;
        }
    }

    /** 分页结果 */
    public static class Page {
        public int page = 1;
        public int totalPages = 1;
        public int totalResults;
        public final List<Item> items = new ArrayList<>();

        static Page fromJson(JSONObject json, String fallbackMediaType) {
            Page page = new Page();
            if (json == null) return page;
            page.page = Math.max(1, json.optInt("page", 1));
            page.totalPages = Math.max(1, json.optInt("total_pages", 1));
            page.totalResults = json.optInt("total_results", 0);
            JSONArray results = json.optJSONArray("results");
            if (results != null) {
                for (int i = 0; i < results.length(); i++) {
                    JSONObject obj = results.optJSONObject(i);
                    if (obj == null) continue;
                    // 搜索接口会混入人物结果，发现页只消费影视
                    if ("person".equalsIgnoreCase(str(obj, "media_type"))) continue;
                    Item item = Item.fromJson(obj, fallbackMediaType);
                    if (item != null) page.items.add(item);
                }
            }
            return page;
        }
    }

    /** 类型字典 */
    public static class Genre {
        public int id;
        public String name = "";

        public Genre() {
        }

        public Genre(int id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** 详情（含演职员） */
    public static class Detail {
        public Item item;
        /** 电影：128 分钟；剧集：3 季 · 24 集 */
        public String runtimeText = "";
        public String status = "";
        public String tagline = "";
        public String homepage = "";
        public String imdbId = "";
        public String director = "";
        public final List<String> genres = new ArrayList<>();
        public final List<String> cast = new ArrayList<>();
    }

    /* ============================ 回调 ============================ */

    public interface PageCallback {
        void onSuccess(Page page);

        /** message 已本地化，可直接展示给用户 */
        void onError(String message);
    }

    public interface GenreCallback {
        void onSuccess(List<Genre> genres);

        void onError(String message);
    }

    public interface DetailCallback {
        void onSuccess(Detail detail);

        void onError(String message);
    }

    /* ============================ 接口 ============================ */

    /** 趋势榜。mediaType 取 movie / tv / all；timeWindow 取 day / week */
    public static void fetchTrending(String mediaType, String timeWindow, int page,
                                     final PageCallback callback) {
        if (callback == null) return;
        String type = TextUtils.isEmpty(mediaType) ? "all" : mediaType;
        String window = "day".equals(timeWindow) ? "day" : "week";
        Map<String, String> query = new LinkedHashMap<>();
        query.put("language", DEFAULT_LANGUAGE);
        query.put("page", String.valueOf(Math.max(1, page)));
        getJson("/3/trending/" + type + "/" + window, query, new JsonCallback() {
            @Override
            public void onSuccess(JSONObject json) {
                postPage(callback, Page.fromJson(json, "movie"));
            }

            @Override
            public void onError(String message, int code) {
                postPageError(callback, message);
            }
        });
    }

    /**
     * 发现 / 筛选。
     *
     * @param media      {@link #MEDIA_MOVIE} 或 {@link #MEDIA_TV}
     * @param genreIds   TMDB 类型 id，逗号分隔；空串表示全部
     * @param sortBy     popularity.desc / vote_average.desc / primary_release_date.desc …
     * @param minVotes   最低评分人数，0 表示不限制
     */
    public static void fetchDiscover(int media, int page, String genreIds,
                                     String sortBy, int minVotes, final PageCallback callback) {
        if (callback == null) return;
        final boolean tv = media == MEDIA_TV;
        String type = tv ? "tv" : "movie";
        Map<String, String> query = new LinkedHashMap<>();
        query.put("language", DEFAULT_LANGUAGE);
        query.put("page", String.valueOf(Math.max(1, page)));
        query.put("include_adult", "false");
        query.put("sort_by", TextUtils.isEmpty(sortBy) ? "popularity.desc" : sortBy);
        if (!TextUtils.isEmpty(genreIds)) query.put("with_genres", genreIds);
        if (minVotes > 0) query.put("vote_count.gte", String.valueOf(minVotes));
        if (!tv) query.put("region", DEFAULT_REGION);
        getJson("/3/discover/" + type, query, new JsonCallback() {
            @Override
            public void onSuccess(JSONObject json) {
                postPage(callback, Page.fromJson(json, tv ? "tv" : "movie"));
            }

            @Override
            public void onError(String message, int code) {
                postPageError(callback, message);
            }
        });
    }

    /** 类型字典，media 取 MEDIA_MOVIE / MEDIA_TV */
    public static void fetchGenres(int media, final GenreCallback callback) {
        if (callback == null) return;
        String type = media == MEDIA_TV ? "tv" : "movie";
        Map<String, String> query = new LinkedHashMap<>();
        query.put("language", DEFAULT_LANGUAGE);
        getJson("/3/genre/" + type + "/list", query, new JsonCallback() {
            @Override
            public void onSuccess(JSONObject json) {
                final List<Genre> genres = new ArrayList<>();
                JSONArray array = json.optJSONArray("genres");
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject obj = array.optJSONObject(i);
                        if (obj == null) continue;
                        Genre genre = new Genre(obj.optInt("id", 0), str(obj, "name"));
                        if (genre.id > 0 && !TextUtils.isEmpty(genre.name)) genres.add(genre);
                    }
                }
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onSuccess(genres);
                    }
                });
            }

            @Override
            public void onError(String message, int code) {
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onError(message);
                    }
                });
            }
        });
    }

    /** 多类型搜索（电影 / 剧集 / 人物，人物结果会被过滤） */
    public static void searchMulti(String keyword, int page, final PageCallback callback) {
        if (callback == null) return;
        if (TextUtils.isEmpty(trim(keyword))) {
            postPageError(callback, "请输入要搜索的片名");
            return;
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("query", trim(keyword));
        query.put("language", DEFAULT_LANGUAGE);
        query.put("page", String.valueOf(Math.max(1, page)));
        query.put("include_adult", "false");
        getJson("/3/search/multi", query, new JsonCallback() {
            @Override
            public void onSuccess(JSONObject json) {
                postPage(callback, Page.fromJson(json, "movie"));
            }

            @Override
            public void onError(String message, int code) {
                postPageError(callback, message);
            }
        });
    }

    /** 详情（movie / tv），附带 credits 演职员 */
    public static void fetchDetail(String mediaType, int id, final DetailCallback callback) {
        if (callback == null) return;
        if (id <= 0) {
            postDetailError(callback, "影片 id 无效");
            return;
        }
        final boolean tv = "tv".equalsIgnoreCase(mediaType);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("language", DEFAULT_LANGUAGE);
        query.put("append_to_response", "credits");
        getJson("/3/" + (tv ? "tv" : "movie") + "/" + id, query, new JsonCallback() {
            @Override
            public void onSuccess(JSONObject json) {
                postDetail(callback, parseDetail(json, tv));
            }

            @Override
            public void onError(String message, int code) {
                postDetailError(callback, message);
            }
        });
    }

    /* ============================ 解析 ============================ */

    private static Detail parseDetail(JSONObject json, boolean tv) {
        Detail detail = new Detail();
        detail.item = Item.fromJson(json, tv ? "tv" : "movie");
        if (detail.item == null) detail.item = new Item();
        detail.item.mediaType = tv ? "tv" : "movie";
        if (json == null) return detail;

        detail.item.overview = str(json, "overview");
        detail.item.posterPath = str(json, "poster_path");
        detail.item.backdropPath = str(json, "backdrop_path");
        detail.item.voteCount = json.optInt("vote_count", 0);
        detail.item.rating = json.optDouble("vote_average", 0d);
        detail.status = localizeStatus(str(json, "status"));
        detail.tagline = str(json, "tagline");
        detail.homepage = str(json, "homepage");
        detail.imdbId = str(json, "imdb_id");

        JSONArray genres = json.optJSONArray("genres");
        if (genres != null) {
            for (int i = 0; i < genres.length(); i++) {
                JSONObject obj = genres.optJSONObject(i);
                if (obj != null) {
                    String name = str(obj, "name");
                    if (!TextUtils.isEmpty(name)) detail.genres.add(name);
                }
            }
        }

        if (tv) {
            int seasons = json.optInt("number_of_seasons", 0);
            int episodes = json.optInt("number_of_episodes", 0);
            if (seasons > 0) {
                detail.runtimeText = seasons + " 季" + (episodes > 0 ? " · " + episodes + " 集" : "");
            } else {
                JSONArray runtimes = json.optJSONArray("episode_run_time");
                if (runtimes != null && runtimes.length() > 0) {
                    detail.runtimeText = runtimes.optInt(0, 0) + " 分钟/集";
                }
            }
            JSONArray createdBy = json.optJSONArray("created_by");
            if (createdBy != null) {
                StringBuilder builder = new StringBuilder();
                for (int i = 0; i < createdBy.length(); i++) {
                    JSONObject creator = createdBy.optJSONObject(i);
                    if (creator == null) continue;
                    String name = str(creator, "name");
                    if (TextUtils.isEmpty(name)) continue;
                    if (builder.length() > 0) builder.append("、");
                    builder.append(name);
                }
                detail.director = builder.toString();
            }
        } else {
            int runtime = json.optInt("runtime", 0);
            if (runtime > 0) detail.runtimeText = runtime + " 分钟";
        }

        JSONObject credits = json.optJSONObject("credits");
        if (credits != null) {
            JSONArray cast = credits.optJSONArray("cast");
            if (cast != null) {
                for (int i = 0; i < cast.length() && detail.cast.size() < 8; i++) {
                    JSONObject person = cast.optJSONObject(i);
                    if (person == null) continue;
                    String name = str(person, "name");
                    if (!TextUtils.isEmpty(name)) detail.cast.add(name);
                }
            }
            if (!tv && TextUtils.isEmpty(detail.director)) {
                JSONArray crew = credits.optJSONArray("crew");
                if (crew != null) {
                    for (int i = 0; i < crew.length(); i++) {
                        JSONObject person = crew.optJSONObject(i);
                        if (person == null) continue;
                        if (!"Director".equalsIgnoreCase(str(person, "job"))) continue;
                        String name = str(person, "name");
                        if (!TextUtils.isEmpty(name)) {
                            detail.director = name;
                            break;
                        }
                    }
                }
            }
        }
        return detail;
    }

    /* ============================ HTTP ============================ */

    private interface JsonCallback {
        void onSuccess(JSONObject json);

        void onError(String message, int code);
    }

    private static void getJson(String path, Map<String, String> query, final JsonCallback callback) {
        final String key = getApiKey();
        if (TextUtils.isEmpty(key)) {
            callback.onError("请先设置 TMDB API Key", 401);
            return;
        }
        HttpUrl parsed = HttpUrl.parse(getBaseUrl() + path);
        if (parsed == null) {
            callback.onError("TMDB 接口地址无效，请检查设置", 0);
            return;
        }
        HttpUrl.Builder url = parsed.newBuilder();
        if (query != null) {
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                url.addQueryParameter(entry.getKey(), entry.getValue());
            }
        }

        Request.Builder builder = new Request.Builder()
                .url(url.build())
                .get()
                .header("Accept", "application/json");
        if (isBearerToken(key)) {
            // v4 Read Access Token
            builder.header("Authorization", "Bearer " + key);
        } else {
            // v3 API Key
            url.addQueryParameter("api_key", key);
            builder.url(url.build());
        }

        final Request request = builder.build();
        final Call call = client().newCall(request);
        ACTIVE_CALLS.add(call);
        LOG.i("echo-tmdb request: " + request.url().toString().replace(key, "***"));
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                ACTIVE_CALLS.remove(call);
                if (call.isCanceled()) return;
                LOG.e("echo-tmdb network error: " + e.getMessage());
                callback.onError("网络连接失败，请检查网络或接口地址", 0);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                ACTIVE_CALLS.remove(call);
                if (call.isCanceled()) {
                    closeQuietly(response);
                    return;
                }
                int code = response.code();
                try {
                    String body = response.body() == null ? "" : response.body().string();
                    if (!response.isSuccessful()) {
                        LOG.e("echo-tmdb http " + code + ": " + body);
                        callback.onError(httpMessage(code), code);
                        return;
                    }
                    JSONObject json = TextUtils.isEmpty(body) ? new JSONObject() : new JSONObject(body);
                    callback.onSuccess(json);
                } catch (Throwable th) {
                    LOG.e("echo-tmdb parse error: " + th.getMessage());
                    callback.onError("TMDB 返回数据解析失败", code);
                } finally {
                    closeQuietly(response);
                }
            }
        });
    }

    private static OkHttpClient client() {
        OkHttpClient base = OkGoHelper.getDefaultClient();
        return base == null ? new OkHttpClient() : base;
    }

    private static void closeQuietly(Response response) {
        try {
            if (response != null && response.body() != null) response.close();
        } catch (Throwable ignored) {
        }
    }

    private static void postPage(final PageCallback callback, final Page page) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                callback.onSuccess(page);
            }
        });
    }

    private static void postPageError(final PageCallback callback, final String message) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                callback.onError(message);
            }
        });
    }

    private static void postDetail(final DetailCallback callback, final Detail detail) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                callback.onSuccess(detail);
            }
        });
    }

    private static void postDetailError(final DetailCallback callback, final String message) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                callback.onError(message);
            }
        });
    }

    private static String httpMessage(int code) {
        switch (code) {
            case 401:
            case 403:
                return "TMDB API Key 无效或没有权限，请重新填写";
            case 404:
                return "TMDB 上找不到这条内容";
            case 429:
                return "请求过于频繁，请稍后再试";
            default:
                if (code >= 500) return "TMDB 服务暂时不可用（HTTP " + code + "）";
                return "请求失败（HTTP " + code + "）";
        }
    }

    /* ============================ 工具 ============================ */

    /**
     * TMDB 的 status 是英文枚举，直接显示在中文界面里很突兀。
     * 只翻译常见值；不认识的一律返回空串，界面据此隐藏这一行。
     */
    private static String localizeStatus(String status) {
        if (TextUtils.isEmpty(status)) return "";
        switch (status) {
            case "Released":
                return "已上映";
            case "Returning Series":
                return "连载中";
            case "Ended":
                return "已完结";
            case "Canceled":
            case "Cancelled":
                return "已取消";
            case "In Production":
                return "制作中";
            case "Post Production":
                return "后期制作";
            case "Planned":
                return "计划中";
            case "Rumored":
                return "传闻中";
            case "Pilot":
                return "试播集";
            default:
                return "";
        }
    }

    /** v4 Token 是 JWT，长度远大于 v3 的 32 位 Key */
    private static boolean isBearerToken(String key) {
        return key != null && (key.startsWith("eyJ") || key.length() > 40);
    }

    private static String normalizeKey(String key) {
        String value = trim(key);
        if (value.toLowerCase(Locale.US).startsWith("bearer ")) {
            value = value.substring(7).trim();
        }
        if ((value.startsWith("\"") && value.endsWith("\"")) && value.length() > 1) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private static String normalizeBase(String base) {
        String url = trim(base);
        if (TextUtils.isEmpty(url)) return DEFAULT_BASE_URL;
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.endsWith("/3")) url = url.substring(0, url.length() - 2);
        return url;
    }

    private static String normalizeImageBase(String base) {
        String url = trim(base);
        if (TextUtils.isEmpty(url)) return DEFAULT_IMAGE_BASE;
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        // 用户如果直接粘了 …/t/p/w500，把尺寸段去掉，由调用处统一拼接
        String[] parts = url.split("/");
        if (parts.length > 0) {
            String last = parts[parts.length - 1].toLowerCase(Locale.US);
            if ("original".equals(last) || last.matches("w[0-9]{2,4}|h[0-9]{2,4}")) {
                url = url.substring(0, url.lastIndexOf('/'));
            }
        }
        return url + "/";
    }

    private static List<Integer> intArray(JSONArray array) {
        List<Integer> list = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                int id = array.optInt(i, 0);
                if (id > 0) list.add(id);
            }
        }
        return list;
    }

    private static String str(JSONObject json, String key) {
        if (json == null) return "";
        String value = json.optString(key, "");
        if (value == null || "null".equalsIgnoreCase(value)) return "";
        return value.trim();
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) return value;
        }
        return "";
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
