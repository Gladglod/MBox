package com.github.tvbox.osc.bean;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索弹幕结果的一层分组：同一部剧 + 同一个弹幕来源。
 * 列表第一层展示它（剧集名 / 来源 / 共几集），点进去才看每一集。
 */
public class DanmuGroup {
    private final String showName;
    private final String source;
    private final List<DanmuSearchResult> items = new ArrayList<>();

    public DanmuGroup(String showName, String source) {
        this.showName = showName == null ? "" : showName;
        this.source = source == null ? "" : source;
    }

    public void add(DanmuSearchResult item) {
        if (item != null) items.add(item);
    }

    public String getShowName() {
        return showName;
    }

    public String getSource() {
        return source;
    }

    public List<DanmuSearchResult> getItems() {
        return items;
    }

    public int size() {
        return items.size();
    }

    /** 这一组里是否有指定集数（用于「疑似此集」定位） */
    public boolean containsEpisode(int episode) {
        if (episode <= 0) return false;
        for (DanmuSearchResult item : items) {
            if (item.getEpisode() == episode) return true;
        }
        return false;
    }
}
