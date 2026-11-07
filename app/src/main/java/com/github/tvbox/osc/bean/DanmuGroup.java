package com.github.tvbox.osc.bean;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索弹幕结果的一层分组。
 * 常规形态：一级剧名（接口的 animeTitle），点进去看每集；
 * 同名多部形态：剧名去掉来源后缀后一致的（例如同剧的 from iqiyi / from youku），
 * 一级显示合成的剧名，点进二级才是各部的完整剧名，再点进三级看每集。
 */
public class DanmuGroup {
    private final String showName;
    private final String source;
    private final List<DanmuSearchResult> items = new ArrayList<>();
    /** 同名多来源时挂在下面的二级分组；非空时本组自己不带剧集 */
    private final List<DanmuGroup> children = new ArrayList<>();

    public DanmuGroup(String showName, String source) {
        this.showName = showName == null ? "" : showName;
        this.source = source == null ? "" : source;
    }

    public void add(DanmuSearchResult item) {
        if (item != null) items.add(item);
    }

    public void addChild(DanmuGroup child) {
        if (child != null) children.add(child);
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

    public List<DanmuGroup> getChildren() {
        return children;
    }

    public boolean hasChildren() {
        return !children.isEmpty();
    }

    /** 这一层下的剧集总数（含二级里的） */
    public int size() {
        int total = items.size();
        for (DanmuGroup child : children) {
            total += child.size();
        }
        return total;
    }

    /** 这一层下是否有指定集数（用于「疑似此集」定位，含二级） */
    public boolean containsEpisode(int episode) {
        if (episode <= 0) return false;
        for (DanmuSearchResult item : items) {
            if (item.getEpisode() == episode) return true;
        }
        for (DanmuGroup child : children) {
            if (child.containsEpisode(episode)) return true;
        }
        return false;
    }
}
