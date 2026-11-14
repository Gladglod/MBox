package com.github.tvbox.osc.util;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.OkGoHelper;
import com.orhanobut.hawk.Hawk;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 源接口测速：给「换源」面板提供每个源的参考延迟。
 * <p>
 * type 0/1/4：HTTP GET 到源 api，取收到响应头的耗时（不读 body，拿到头即断开）；
 * type 3（Spider）：源 api 不是 URL，限时调用 homeContent(false) 探测，取总耗时。
 * 测不出延迟（网络错误 / 超时 / 中断）返回 {@link #FAIL}。
 */
public class SourceSpeedTester {

    public static final int FAIL = -1;

    /** 测速结果缓存有效期：10 分钟 */
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;
    /** 内存缓存：sourceKey -> [latencyMs, timestamp] */
    private static final java.util.concurrent.ConcurrentHashMap<String, long[]> CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static boolean cacheLoaded = false;

    public interface SpeedCallback {
        void onResult(String sourceKey, int latencyMs);
    }

    private static final long HTTP_TIMEOUT_MS = 5000L;
    private static final long SPIDER_TIMEOUT_MS = 6000L;
    private static final int THREADS = 6;

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(THREADS);
    private static final List<Future<?>> RUNNING = new ArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static OkHttpClient speedClient = null;

    public static void test(final SourceBean bean, final SpeedCallback callback) {
        if (bean == null || TextUtils.isEmpty(bean.getKey()) || callback == null) {
            return;
        }
        Future<?> future;
        synchronized (RUNNING) {
            future = EXECUTOR.submit(new Runnable() {
                @Override
                public void run() {
                    int latency = doTest(bean);
                    cacheLatency(bean.getKey(), latency);
                    postResult(bean.getKey(), latency, callback);
                }
            });
            RUNNING.add(future);
        }
    }

    /** 取消所有进行中的测速（面板关闭时调用），未完成的回调仍会以 FAIL 回来。 */
    public static void cancelAll() {
        synchronized (RUNNING) {
            for (Future<?> future : RUNNING) {
                future.cancel(true);
            }
            RUNNING.clear();
        }
    }

    private static int doTest(SourceBean bean) {
        if (bean.getType() == 3) {
            return testSpider(bean);
        }
        return testHttp(bean);
    }

    /**
     * 取缓存的测速延迟（TTL 内有效），无缓存或已过期返回 null。
     */
    public static Integer getCachedLatency(String sourceKey) {
        ensureCacheLoaded();
        long[] entry = CACHE.get(sourceKey);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() - entry[1] > CACHE_TTL_MS) {
            CACHE.remove(sourceKey);
            return null;
        }
        return (int) entry[0];
    }

    private static void cacheLatency(String sourceKey, int latencyMs) {
        if (latencyMs <= 0) {
            return; // 超时/失败不缓存，下次重新测
        }
        ensureCacheLoaded();
        CACHE.put(sourceKey, new long[]{latencyMs, System.currentTimeMillis()});
        saveCache();
    }

    private static void ensureCacheLoaded() {
        if (cacheLoaded) {
            return;
        }
        cacheLoaded = true;
        try {
            String json = Hawk.get(HawkConfig.SOURCE_SPEED_CACHE, "");
            if (!TextUtils.isEmpty(json)) {
                JSONObject root = new JSONObject(json);
                java.util.Iterator<String> keys = root.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    JSONArray arr = root.optJSONArray(key);
                    if (arr != null && arr.length() >= 2) {
                        CACHE.put(key, new long[]{arr.optLong(0), arr.optLong(1)});
                    }
                }
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private static void saveCache() {
        try {
            JSONObject root = new JSONObject();
            long now = System.currentTimeMillis();
            for (java.util.Map.Entry<String, long[]> entry : CACHE.entrySet()) {
                if (now - entry.getValue()[1] > CACHE_TTL_MS) {
                    continue;
                }
                JSONArray arr = new JSONArray();
                arr.put(entry.getValue()[0]);
                arr.put(entry.getValue()[1]);
                root.put(entry.getKey(), arr);
            }
            Hawk.put(HawkConfig.SOURCE_SPEED_CACHE, root.toString());
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private static void postResult(final String sourceKey, final int latency, final SpeedCallback callback) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    callback.onResult(sourceKey, latency);
                } catch (Throwable th) {
                    th.printStackTrace();
                }
            }
        });
    }

    private static int testHttp(SourceBean bean) {
        String url = bean.getApi();
        if (TextUtils.isEmpty(url) || !url.startsWith("http")) {
            return FAIL;
        }
        if (speedClient == null) {
            OkHttpClient base = OkGoHelper.getDefaultClient();
            speedClient = (base == null ? new OkHttpClient() : base).newBuilder()
                    .connectTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .readTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .build();
        }
        Request request = new Request.Builder().url(url).get().build();
        long start = System.currentTimeMillis();
        Response response = null;
        try {
            // execute 返回即代表响应头已到达，耗时即为接口连通延迟；body 不读，直接关闭
            response = speedClient.newCall(request).execute();
            return (int) (System.currentTimeMillis() - start);
        } catch (Throwable th) {
            return FAIL;
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private static int testSpider(final SourceBean bean) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        long start = System.currentTimeMillis();
        try {
            Future<String> future = executor.submit(new Callable<String>() {
                @Override
                public String call() {
                    try {
                        Spider sp = ApiConfig.get().getCSP(bean);
                        return sp.homeContent(false);
                    } catch (Throwable th) {
                        return "";
                    }
                }
            });
            future.get(SPIDER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return (int) (System.currentTimeMillis() - start);
        } catch (Throwable th) {
            return FAIL;
        } finally {
            executor.shutdownNow();
        }
    }
}
