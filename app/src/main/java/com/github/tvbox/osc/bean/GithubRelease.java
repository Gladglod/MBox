package com.github.tvbox.osc.bean;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * GitHub Releases API（{@code /repos/{owner}/{repo}/releases/latest}）返回的单个发布版本。
 * 只声明用得到的字段，其余字段 Gson 会自动忽略。
 */
public class GithubRelease {

    /** 版本 tag，例如 {@code 2.0.1} 或 {@code v2.0.1} */
    @SerializedName("tag_name")
    public String tagName;

    /** 发布标题，可能为空 */
    @SerializedName("name")
    public String name;

    /** 更新说明（Markdown 原文） */
    @SerializedName("body")
    public String body;

    /** 网页地址，用于「在浏览器中打开」 */
    @SerializedName("html_url")
    public String htmlUrl;

    @SerializedName("published_at")
    public String publishedAt;

    @SerializedName("prerelease")
    public boolean prerelease;

    @SerializedName("draft")
    public boolean draft;

    @SerializedName("assets")
    public List<Asset> assets;

    /** 发布包（每个 flavor 一个 apk） */
    public static class Asset {
        public String name;

        @SerializedName("browser_download_url")
        public String downloadUrl;

        /** 字节数，用于断点复用与下载完成校验 */
        public long size;
    }
}
