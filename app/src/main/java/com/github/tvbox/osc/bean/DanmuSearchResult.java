package com.github.tvbox.osc.bean;

import android.text.TextUtils;

public class DanmuSearchResult {
    private final String name;
    private final String url;
    private final boolean builtIn;
    /** 集数，0 表示解析不出来 */
    private final int episode;
    /** 来源标识（一般是接口域名），用于分组展示 */
    private final String source;

    public DanmuSearchResult(String name, String url, boolean builtIn) {
        this(name, url, builtIn, 0, "");
    }

    public DanmuSearchResult(String name, String url, boolean builtIn, int episode, String source) {
        this.name = name;
        this.url = url;
        this.builtIn = builtIn;
        this.episode = episode;
        this.source = source == null ? "" : source;
    }

    public String getName() {
        return TextUtils.isEmpty(name) ? getUrl() : name;
    }

    public String getUrl() {
        return url == null ? "" : url;
    }

    public boolean isBuiltIn() {
        return builtIn;
    }

    public int getEpisode() {
        return episode;
    }

    public String getSource() {
        return source;
    }
}
