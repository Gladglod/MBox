package com.github.tvbox.osc.util;

import android.text.TextUtils;

import com.github.tvbox.osc.bean.MovieSort;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 首页标签（分类页签）配置。
 *
 * <p>存储格式：{@link HawkConfig#HOME_TABS} 里每行一条 {@code 可见(1/0)\t分类id}，行序即显示顺序。
 * 没有存储过、或者数据源新增了分类时，新增的分类会追加在末尾并默认显示，这样换数据源后不会
 * 出现「首页一个标签都没有」的情况。</p>
 */
public class HomeTabConfig {

    /** 首页第 0 页固定是「热门推荐」，它不属于数据源分类，用这个保留 id 参与配置 */
    public static final String HOT_ID = "__hot__";
    public static final String HOT_NAME = "热门推荐";

    public static class Tab {
        public String id;
        public String name;
        public boolean visible = true;

        public Tab(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** 配置是否被改过，供首页 onResume 时决定要不要重建页面 */
    private static boolean changed = false;

    public static void markChanged() {
        changed = true;
    }

    public static boolean consumeChanged() {
        boolean c = changed;
        changed = false;
        return c;
    }

    /**
     * 把已保存的配置与当前数据源的分类合并成一个可编辑列表（供设置页使用）。
     * 顺序：先按用户保存的顺序，数据源新增的分类追加在末尾。
     */
    public static List<Tab> build(List<MovieSort.SortData> data) {
        List<Tab> result = new ArrayList<>();
        Map<String, MovieSort.SortData> rest = indexOf(data);
        Map<String, Boolean> saved = loadRaw();
        for (Map.Entry<String, Boolean> entry : saved.entrySet()) {
            MovieSort.SortData sortData = rest.remove(entry.getKey());
            if (sortData == null) continue;
            Tab tab = new Tab(sortData.id, sortData.name);
            tab.visible = entry.getValue();
            result.add(tab);
        }
        for (MovieSort.SortData sortData : rest.values()) {
            result.add(new Tab(sortData.id, sortData.name));
        }
        return result;
    }

    /**
     * 首页实际要渲染的分类页（已按用户顺序排序、已过滤掉隐藏项）。
     * 不含「热门推荐」，它由首页自己插到第 0 位。
     */
    public static List<MovieSort.SortData> visibleSortData(List<MovieSort.SortData> data) {
        List<MovieSort.SortData> result = new ArrayList<>();
        Map<String, MovieSort.SortData> rest = indexOf(data);
        Map<String, Boolean> saved = loadRaw();
        for (Map.Entry<String, Boolean> entry : saved.entrySet()) {
            MovieSort.SortData sortData = rest.remove(entry.getKey());
            if (sortData == null) continue;
            if (entry.getValue()) result.add(sortData);
        }
        // 未出现在配置里的（数据源新增的分类）默认显示
        result.addAll(rest.values());
        return result;
    }

    public static void save(List<Tab> tabs) {
        StringBuilder sb = new StringBuilder();
        if (tabs != null) {
            for (Tab tab : tabs) {
                if (tab == null || TextUtils.isEmpty(tab.id)) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(tab.visible ? '1' : '0').append('\t').append(tab.id);
            }
        }
        Hawk.put(HawkConfig.HOME_TABS, sb.toString());
        markChanged();
    }

    /** 恢复默认：全部显示、按数据源顺序 */
    public static void reset() {
        Hawk.put(HawkConfig.HOME_TABS, "");
        markChanged();
    }

    public static int visibleCount(List<Tab> tabs) {
        int count = 0;
        if (tabs == null) return 0;
        for (Tab tab : tabs) {
            if (tab != null && tab.visible) count++;
        }
        return count;
    }

    private static Map<String, MovieSort.SortData> indexOf(List<MovieSort.SortData> data) {
        Map<String, MovieSort.SortData> map = new LinkedHashMap<>();
        if (data == null) return map;
        for (MovieSort.SortData sortData : data) {
            if (sortData == null || TextUtils.isEmpty(sortData.id)) continue;
            // 「主页」这个历史遗留分类已经由底部「我的」替代，不参与首页标签
            if ("my0".equals(sortData.id)) continue;
            map.put(sortData.id, sortData);
        }
        return map;
    }

    private static Map<String, Boolean> loadRaw() {
        Map<String, Boolean> map = new LinkedHashMap<>();
        String raw = Hawk.get(HawkConfig.HOME_TABS, "");
        if (TextUtils.isEmpty(raw)) return map;
        String[] lines = raw.split("\n");
        for (String line : lines) {
            if (TextUtils.isEmpty(line)) continue;
            int split = line.indexOf('\t');
            if (split <= 0 || split >= line.length() - 1) continue;
            String id = line.substring(split + 1);
            if (TextUtils.isEmpty(id)) continue;
            map.put(id, line.charAt(0) == '1');
        }
        return map;
    }
}
