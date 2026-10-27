package com.github.tvbox.osc.util;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.lifecycle.Observer;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.ShortDramaSource;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.viewmodel.SourceViewModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 短剧源识别。
 *
 * <p>识别分两步：</p>
 * <ol>
 *     <li><b>本地命中</b>：站点名或配置里声明的分类名带「短剧」，这种不用请求就能判断，
 *     但仍然要拉一次该站点的分类列表，因为请求列表需要分类 id，而配置里只有分类名。</li>
 *     <li><b>联网探测</b>：其余站点逐个拉首页分类，命中关键词的分类收下。</li>
 * </ol>
 *
 * <p>为什么是「渐进式」而不是一次扫完：一个配置动辄几十上百个站点，每个站点一次请求，
 * 串行全扫要几分钟，用户打开「短剧」页不可能等这么久。所以：</p>
 * <ul>
 *     <li>扫过的站点记进 {@link ShortDramaConfig#addScanned}，下次只补扫没扫过的；</li>
 *     <li>单站点 10 秒超时、整体 25 秒预算，到点就带着已有结果收工，剩下的下次接着扫；</li>
 *     <li>每扫完一个站点就落盘，进程被杀也不丢。</li>
 * </ul>
 *
 * <p>整个流程跑在主线程上（网络请求本身在 {@link SourceViewModel} 里是异步的），
 * 只有这样才能安全地操作 LiveData 的 observeForever。</p>
 */
public class ShortDramaScanner {

    /** 单个站点最多等这么久 */
    private static final long SITE_TIMEOUT_MS = 10_000L;
    /** 一轮扫描的总预算 */
    private static final long TOTAL_BUDGET_MS = 25_000L;
    /** 候选站点上限，防止超大配置把电池扫干 */
    private static final int MAX_SITES = 80;

    public interface Listener {
        /** 开始，total = 本轮要扫的站点数 */
        void onStart(int total);

        /** 每扫完一个站点回调一次；found 是累计找到的数量 */
        void onProgress(int done, int total, int found);

        /** 结束。complete = false 表示预算用完、还有站点没扫 */
        void onFinish(int found, boolean complete);
    }

    public interface CategoriesCallback {
        void onResult(List<MovieSort.SortData> list);
    }

    private interface FetchCallback {
        void onResult(List<MovieSort.SortData> list);
    }

    /** 同一时刻只允许一轮扫描，避免两处同时发请求 */
    private static ShortDramaScanner running;

    public static boolean isScanning() {
        return running != null;
    }

    /**
     * 启动一轮识别。
     *
     * @param force true = 忽略「已扫过」记录，全部重扫
     * @return false 表示已经有一轮在跑，本次忽略
     */
    public static boolean scan(boolean force, Listener listener) {
        if (running != null) return false;
        running = new ShortDramaScanner(listener);
        running.start(force);
        return true;
    }

    /** 页面销毁时中止，已经扫到的结果保留 */
    public static void cancel() {
        ShortDramaScanner scanner = running;
        if (scanner == null) return;
        running = null;
        scanner.stop();
    }

    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<SourceBean> queue = new ArrayList<>();
    private int index = 0;
    private int found = 0;
    private long deadline = 0;
    private SourceViewModel viewModel;
    private Observer<AbsSortXml> observer;
    private FetchCallback pending;
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            finishCurrent(null);
        }
    };

    private ShortDramaScanner(Listener listener) {
        this.listener = listener;
    }

    private void start(boolean force) {
        if (force) ShortDramaConfig.clearScanned();
        Set<String> scanned = ShortDramaConfig.loadScanned();
        queue.clear();
        queue.addAll(candidates(scanned));
        if (queue.isEmpty()) {
            running = null;
            if (listener != null) listener.onFinish(0, true);
            return;
        }
        index = 0;
        found = 0;
        deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS;
        viewModel = new SourceViewModel();
        if (listener != null) listener.onStart(queue.size());
        step();
    }

    /**
     * 候选站点：能列出内容的点播站点，跳过已扫过的。
     * 配置里声明了「短剧」分类的排前面 —— 它们几乎必然命中，先扫能让页面更快有东西。
     */
    private List<SourceBean> candidates(Set<String> scanned) {
        List<SourceBean> result = new ArrayList<>();
        for (SourceBean bean : ApiConfig.get().getSourceBeanList()) {
            if (bean == null) continue;
            String key = bean.getKey();
            if (TextUtils.isEmpty(key)) continue;
            if ("push_agent".equals(key)) continue;
            if (scanned.contains(key)) continue;
            // 只有搜索能力的站点（名字以「搜」结尾）没有分类可列
            String name = bean.getName();
            if (!TextUtils.isEmpty(name) && name.length() <= 3 && name.endsWith("搜")) continue;
            int type = bean.getType();
            if (type != 0 && type != 1 && type != 3 && type != 4) continue;
            result.add(bean);
        }
        Collections.sort(result, new Comparator<SourceBean>() {
            @Override
            public int compare(SourceBean left, SourceBean right) {
                return hint(right) - hint(left);
            }

            private int hint(SourceBean bean) {
                if (ShortDramaConfig.matches(bean.getName())) return 2;
                List<String> categories = bean.getCategories();
                if (categories != null) {
                    for (String category : categories) {
                        if (ShortDramaConfig.matches(category)) return 1;
                    }
                }
                return 0;
            }
        });
        if (result.size() > MAX_SITES) {
            return new ArrayList<>(result.subList(0, MAX_SITES));
        }
        return result;
    }

    private void step() {
        if (running != this) return;
        if (index >= queue.size() || System.currentTimeMillis() > deadline) {
            finish();
            return;
        }
        final SourceBean bean = queue.get(index++);
        fetch(bean, new FetchCallback() {
            @Override
            public void onResult(List<MovieSort.SortData> list) {
                int before = found;
                collect(bean, list);
                ShortDramaConfig.addScanned(bean.getKey());
                if (listener != null) listener.onProgress(index, queue.size(), found);
                if (found == before) {
                    // 没命中也要留出一点间隔，别把几十个请求在同一帧里全发出去
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            step();
                        }
                    }, 30);
                } else {
                    step();
                }
            }
        });
    }

    private void collect(SourceBean bean, List<MovieSort.SortData> list) {
        if (list == null || list.isEmpty()) return;
        List<ShortDramaSource> hits = new ArrayList<>();
        for (MovieSort.SortData sort : list) {
            if (sort == null || TextUtils.isEmpty(sort.id)) continue;
            if (!ShortDramaConfig.matches(sort.name)) continue;
            hits.add(new ShortDramaSource(bean.getKey(), bean.getName(), sort.id, sort.name));
        }
        // 站点本身就是短剧站（名字带「短剧」），但分类名不含关键词（「全部」「精选」这种）：
        // 这种站点第一个分类就是我们要的内容
        if (hits.isEmpty() && ShortDramaConfig.matches(bean.getName())) {
            MovieSort.SortData first = list.get(0);
            if (first != null && !TextUtils.isEmpty(first.id)) {
                hits.add(new ShortDramaSource(bean.getKey(), bean.getName(), first.id, first.name));
            }
        }
        if (hits.isEmpty()) return;
        ShortDramaConfig.mergeDetected(hits);
        found += hits.size();
    }

    /* ============================ 单个站点拉分类 ============================ */

    private void fetch(SourceBean bean, FetchCallback callback) {
        pending = callback;
        removeObserver();
        final String key = bean.getKey();
        observer = new Observer<AbsSortXml>() {
            @Override
            public void onChanged(AbsSortXml xml) {
                if (xml == null || !key.equals(xml.sourceKey)) return;
                finishCurrent(xml.classes == null ? null : xml.classes.sortList);
            }
        };
        viewModel.sortResult.observeForever(observer);
        handler.postDelayed(watchdog, SITE_TIMEOUT_MS);
        viewModel.getSort(key);
    }

    private void finishCurrent(List<MovieSort.SortData> list) {
        if (pending == null) return;
        FetchCallback callback = pending;
        pending = null;
        handler.removeCallbacks(watchdog);
        removeObserver();
        callback.onResult(list);
    }

    private void removeObserver() {
        if (observer != null && viewModel != null) {
            try {
                viewModel.sortResult.removeObserver(observer);
            } catch (Throwable ignore) {
            }
        }
        observer = null;
    }

    private void finish() {
        boolean complete = index >= queue.size();
        if (listener != null) listener.onFinish(found, complete);
        stop();
    }

    private void stop() {
        handler.removeCallbacks(watchdog);
        removeObserver();
        pending = null;
        if (running == this) running = null;
    }

    /* ============================ 手动添加用：取某站点的分类 ============================ */

    /**
     * 拉一个站点的分类列表，供「手动添加短剧源」时选择。
     * 与扫描互不影响（各用各的 ViewModel 实例），但共用同一个超时策略。
     */
    public static void fetchCategories(String sourceKey, final CategoriesCallback callback) {
        if (callback == null) return;
        if (TextUtils.isEmpty(sourceKey) || ApiConfig.get().getSource(sourceKey) == null) {
            callback.onResult(null);
            return;
        }
        final Handler handler = new Handler(Looper.getMainLooper());
        final SourceViewModel viewModel = new SourceViewModel();
        final boolean[] done = {false};
        final Observer<AbsSortXml>[] holder = new Observer[]{null};
        final Runnable timeout = new Runnable() {
            @Override
            public void run() {
                if (done[0]) return;
                done[0] = true;
                if (holder[0] != null) viewModel.sortResult.removeObserver(holder[0]);
                callback.onResult(null);
            }
        };
        holder[0] = new Observer<AbsSortXml>() {
            @Override
            public void onChanged(AbsSortXml xml) {
                if (xml == null || !sourceKey.equals(xml.sourceKey) || done[0]) return;
                done[0] = true;
                handler.removeCallbacks(timeout);
                viewModel.sortResult.removeObserver(holder[0]);
                callback.onResult(xml.classes == null ? null : xml.classes.sortList);
            }
        };
        viewModel.sortResult.observeForever(holder[0]);
        handler.postDelayed(timeout, SITE_TIMEOUT_MS);
        viewModel.getSort(sourceKey);
    }

    /** 供设置页显示：本轮扫描要跳过的已扫站点数量 */
    public static int scannedCount() {
        return ShortDramaConfig.loadScanned().size();
    }

    /** 给「重新识别」用：清空记录（force 扫描内部也会清，这里只是显式一点） */
    public static void resetScanned() {
        ShortDramaConfig.clearScanned();
    }
}
