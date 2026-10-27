package com.github.tvbox.osc.util;

import android.text.TextUtils;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.ShortDramaSource;
import com.github.tvbox.osc.bean.SourceBean;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「短剧源」的识别规则与持久化。
 *
 * <p>存两条：
 * <ul>
 *     <li>{@link HawkConfig#SHORT_DRAMA_SOURCES}：用户可见的短剧源清单（含启用状态、是否手动添加）；</li>
 *     <li>{@link HawkConfig#SHORT_DRAMA_SCANNED}：已经联网探测过的站点，用于增量识别。</li>
 * </ul>
 * 分开存的原因是这两件事的生命周期不一样：清单会被用户改（勾选 / 删除），
 * 而「探测过哪些站点」是纯粹的技术状态，用户不应该看到它。</p>
 */
public class ShortDramaConfig {

    /**
     * 判定关键词。只放真正指向短剧的词：
     * 「剧场」「微电影」这类在不同站点含义差别太大，放进来只会把整个站点误判成短剧。
     */
    private static final String[] KEYWORDS = {"短剧", "竖屏", "迷你剧"};

    /** 设置页改过配置后置位，短剧页 onResume 时据此决定要不要重建 */
    private static boolean changed = false;

    public static void markChanged() {
        changed = true;
    }

    public static boolean consumeChanged() {
        boolean value = changed;
        changed = false;
        return value;
    }

    /** 名字（站点名 / 分类名）看起来像不像短剧 */
    public static boolean matches(String name) {
        if (TextUtils.isEmpty(name)) return false;
        for (String keyword : KEYWORDS) {
            if (name.contains(keyword)) return true;
        }
        return false;
    }

    /* ============================ 清单读写 ============================ */

    public static List<ShortDramaSource> loadAll() {
        List<ShortDramaSource> result = new ArrayList<>();
        String raw = Hawk.get(HawkConfig.SHORT_DRAMA_SOURCES, "");
        if (TextUtils.isEmpty(raw)) return result;
        for (String line : raw.split("\n")) {
            if (TextUtils.isEmpty(line)) continue;
            String[] parts = line.split("\t", -1);
            if (parts.length < 5) continue;
            ShortDramaSource item = new ShortDramaSource(parts[2], "", parts[3], parts[4]);
            item.enabled = !"0".equals(parts[0]);
            item.manual = "1".equals(parts[1]);
            if (!item.isValid()) continue;
            result.add(item);
        }
        resolveNames(result);
        return result;
    }

    /** 只取启用且站点还在的，短剧页用 */
    public static List<ShortDramaSource> loadEnabled() {
        List<ShortDramaSource> result = new ArrayList<>();
        for (ShortDramaSource item : loadAll()) {
            if (item.enabled && item.alive) result.add(item);
        }
        return result;
    }

    public static int enabledCount() {
        return loadEnabled().size();
    }

    public static void saveAll(List<ShortDramaSource> list) {
        StringBuilder sb = new StringBuilder();
        if (list != null) {
            for (ShortDramaSource item : list) {
                if (item == null || !item.isValid()) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(item.enabled ? '1' : '0').append('\t')
                        .append(item.manual ? '1' : '0').append('\t')
                        .append(clean(item.sourceKey)).append('\t')
                        .append(clean(item.categoryId)).append('\t')
                        .append(clean(item.categoryName));
            }
        }
        Hawk.put(HawkConfig.SHORT_DRAMA_SOURCES, sb.toString());
        markChanged();
    }

    /**
     * 把识别结果并进清单：已存在的保持用户原有设置（启用状态、是否手动），
     * 新出现的追加到末尾并默认启用。这样「重新识别」不会把用户手动关掉的项又打开。
     */
    public static int mergeDetected(List<ShortDramaSource> detected) {
        if (detected == null || detected.isEmpty()) return 0;
        List<ShortDramaSource> current = loadAll();
        Map<String, ShortDramaSource> index = new LinkedHashMap<>();
        for (ShortDramaSource item : current) {
            index.put(item.id(), item);
        }
        int added = 0;
        for (ShortDramaSource item : detected) {
            if (item == null || !item.isValid()) continue;
            ShortDramaSource exist = index.get(item.id());
            if (exist != null) {
                exist.alive = true;
                if (TextUtils.isEmpty(exist.categoryName)) exist.categoryName = item.categoryName;
                if (TextUtils.isEmpty(exist.sourceName)) exist.sourceName = item.sourceName;
                continue;
            }
            item.enabled = true;
            item.manual = false;
            index.put(item.id(), item);
            added++;
        }
        // 即使没有新增也回写一次：站点名 / 分类名可能已经变了
        saveAll(new ArrayList<>(index.values()));
        return added;
    }

    /** 手动添加：重复返回 false */
    public static boolean addManual(String sourceKey, String categoryId, String categoryName) {
        List<ShortDramaSource> current = loadAll();
        for (ShortDramaSource item : current) {
            if (item.sourceKey.equals(sourceKey) && item.categoryId.equals(categoryId)) {
                if (!item.enabled) {
                    item.enabled = true;
                    saveAll(current);
                }
                return false;
            }
        }
        ShortDramaSource item = new ShortDramaSource(sourceKey, "", categoryId, categoryName);
        item.manual = true;
        item.enabled = true;
        current.add(item);
        saveAll(current);
        return true;
    }

    public static void remove(ShortDramaSource target) {
        if (target == null) return;
        List<ShortDramaSource> current = loadAll();
        for (int i = 0; i < current.size(); i++) {
            if (current.get(i).id().equals(target.id())) {
                current.remove(i);
                break;
            }
        }
        saveAll(current);
    }

    /* ============================ 已探测站点 ============================ */

    public static Set<String> loadScanned() {
        Set<String> set = new HashSet<>();
        String raw = Hawk.get(HawkConfig.SHORT_DRAMA_SCANNED, "");
        if (TextUtils.isEmpty(raw)) return set;
        for (String line : raw.split("\n")) {
            if (!TextUtils.isEmpty(line)) set.add(line);
        }
        return set;
    }

    public static void addScanned(String sourceKey) {
        if (TextUtils.isEmpty(sourceKey)) return;
        Set<String> set = loadScanned();
        if (!set.add(sourceKey)) return;
        StringBuilder sb = new StringBuilder();
        for (String key : set) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(key);
        }
        Hawk.put(HawkConfig.SHORT_DRAMA_SCANNED, sb.toString());
    }

    public static void clearScanned() {
        Hawk.put(HawkConfig.SHORT_DRAMA_SCANNED, "");
    }

    /* ============================ 选中项 ============================ */

    public static String getSelectedId() {
        return Hawk.get(HawkConfig.SHORT_DRAMA_SELECTED, "");
    }

    public static void setSelectedId(String id) {
        Hawk.put(HawkConfig.SHORT_DRAMA_SELECTED, id == null ? "" : id);
    }

    /* ============================ 内部 ============================ */

    /** 用当前配置里的站点名补全 sourceName，并标出站点是否还在 */
    private static void resolveNames(List<ShortDramaSource> list) {
        for (ShortDramaSource item : list) {
            SourceBean bean = ApiConfig.get().getSource(item.sourceKey);
            if (bean == null) {
                item.alive = false;
                if (TextUtils.isEmpty(item.sourceName)) item.sourceName = item.sourceKey;
            } else {
                item.alive = true;
                item.sourceName = bean.getName();
            }
        }
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace("\t", " ").replace("\n", " ").trim();
    }
}
