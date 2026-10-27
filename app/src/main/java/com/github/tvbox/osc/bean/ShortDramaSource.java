package com.github.tvbox.osc.bean;

import android.text.TextUtils;

import java.io.Serializable;

/**
 * 一个「短剧源」= 某个站点的某个分类。
 *
 * <p>为什么不是「站点」：绝大多数配置里并没有独立的短剧站点，短剧是综合站点里的一个分类。
 * 所以识别的粒度必须落到「站点 + 分类」，否则拿到站点也定位不到内容。</p>
 */
public class ShortDramaSource implements Serializable {

    /** 站点 key（{@link SourceBean#getKey()}） */
    public String sourceKey = "";
    /** 站点名，只用于展示；站点被删掉后仍保留旧值 */
    public String sourceName = "";
    /** 分类 id，请求列表时用 */
    public String categoryId = "";
    /** 分类名，只用于展示 */
    public String categoryName = "";
    /** true = 用户手动添加；false = 自动识别 */
    public boolean manual = false;
    /** 是否出现在首页「短剧」页 */
    public boolean enabled = true;
    /** 站点是否还在当前配置里（运行时计算，不持久化） */
    public boolean alive = true;
    /** 胶囊上的文案（运行时计算，不持久化） */
    public String label = "";

    public ShortDramaSource() {
    }

    public ShortDramaSource(String sourceKey, String sourceName, String categoryId, String categoryName) {
        this.sourceKey = safe(sourceKey);
        this.sourceName = safe(sourceName);
        this.categoryId = safe(categoryId);
        this.categoryName = safe(categoryName);
    }

    /** 唯一标识：站点 + 分类 */
    public String id() {
        return sourceKey + "|" + categoryId;
    }

    public boolean isValid() {
        return !TextUtils.isEmpty(sourceKey) && !TextUtils.isEmpty(categoryId);
    }

    public String displayName() {
        if (!TextUtils.isEmpty(categoryName)) return categoryName;
        if (!TextUtils.isEmpty(sourceName)) return sourceName;
        return "未命名分类";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
