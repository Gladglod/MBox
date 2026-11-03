package com.github.tvbox.osc.util;

import android.net.Uri;
import android.text.TextUtils;

import com.github.tvbox.osc.bean.DanmuGroup;
import com.github.tvbox.osc.bean.DanmuSearchResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 弹幕搜索结果清洗：
 * <ol>
 *     <li><b>过滤</b>：剧集名为「天下3」时只保留第三部（或同标题、无季数信息）的条目，把第一、二部等噪音挡掉；</li>
 *     <li><b>拆字段</b>：把「来源 / 集数」从又长又乱的标题里解析出来，标题只留剧集名；</li>
 *     <li><b>分组</b>：按「剧集名 + 来源」聚成一层，界面上先看剧集，点进去再看每一集。</li>
 * </ol>
 */
public final class DanmuTitleUtil {

    /** 第3集 / 第03话 / 第十二集 */
    private static final Pattern P_EP_CN =
            Pattern.compile("第\\s*([0-9]{1,4}|[一二三四五六七八九十]{1,3})\\s*[集话話回期章篇]");
    /** 03集 / 03话 */
    private static final Pattern P_EP_SUFFIX = Pattern.compile("([0-9]{1,4})\\s*[集话話回期章篇]");
    /** EP03 / E03 */
    private static final Pattern P_EP_EN = Pattern.compile("(?i)\\bEP?\\.?\\s*([0-9]{1,4})\\b");
    /** 第3季 / 第三部 / 第3篇 */
    private static final Pattern P_SEASON_CN =
            Pattern.compile("第\\s*([0-9]{1,3}|[一二三四五六七八九十]{1,3})\\s*[季部篇]");
    /** Season 3 / S03 */
    private static final Pattern P_SEASON_EN = Pattern.compile("(?i)\\bSEASON\\s*([0-9]{1,3})\\b|\\bS([0-9]{1,3})\\b");
    /** 裸数字（「天下 03」这种没有「第…集」字样的标题）；不匹配长数字片段，避免把 1080P、年份当集数 */
    private static final Pattern P_BARE_NUMBER = Pattern.compile("(?<![0-9])[0-9]{1,3}(?![0-9])");
    /** 尾部「分隔符 + 数字」，用于从分组标题里剪掉没写「第…集」的集数 */
    private static final Pattern P_TAIL_NUMBER = Pattern.compile("[\\s\\-_·|,，.。:：;；/]+([0-9]{1,3})\\s*$");

    private DanmuTitleUtil() {
    }

    /**
     * 过滤 + 拆字段，一次完成。返回的条目里 name 已是干净的剧集名，
     * 集数与来源单独放在 {@code episode} / {@code source} 字段上。
     *
     * @param source  接口返回的原始结果
     * @param keyword 用户搜索用的剧集名（如「天下3」）
     */
    public static List<DanmuSearchResult> refine(List<DanmuSearchResult> source, String keyword) {
        List<DanmuSearchResult> result = new ArrayList<>();
        if (source == null || source.isEmpty()) return result;

        String base = keywordBase(keyword);
        int season = keywordSeason(keyword);

        List<DanmuSearchResult> exact = new ArrayList<>();
        List<DanmuSearchResult> loose = new ArrayList<>();
        for (DanmuSearchResult item : source) {
            if (item == null) continue;
            String raw = item.getName();
            if (TextUtils.isEmpty(raw)) continue;
            // 名字本身就是一坨地址的（自定义接口常见），无法判断，先归到 loose
            if (isUrlLike(raw)) {
                loose.add(item);
                continue;
            }
            if (!base.isEmpty() && !compact(raw).contains(base)) continue;
            int itemSeason = seasonOf(raw);
            if (season <= 0 || itemSeason == season || itemSeason <= 0) {
                exact.add(item);
            } else {
                loose.add(item);
            }
        }

        // 优先用「季数对得上」的结果；一个都没有时退回同标题的结果，再没有就原样放回，避免列表空白
        List<DanmuSearchResult> picked;
        if (!exact.isEmpty()) picked = exact;
        else if (!loose.isEmpty()) picked = loose;
        else picked = source;

        Set<String> seen = new HashSet<>();
        for (DanmuSearchResult item : picked) {
            if (item == null) continue;
            String raw = item.getName();
            if (TextUtils.isEmpty(raw)) continue;
            if (!seen.add(item.getUrl() + "|" + raw)) continue;
            result.add(new DanmuSearchResult(
                    groupTitle(raw, keyword),
                    item.getUrl(),
                    item.isBuiltIn(),
                    episodeOf(raw, keyword),
                    sourceOf(item.getUrl(), item.isBuiltIn())));
        }
        return result;
    }

    /** 把已清洗的条目按「剧集名 + 来源」聚成一层，组内按集数升序 */
    public static List<DanmuGroup> group(List<DanmuSearchResult> refined) {
        List<DanmuGroup> groups = new ArrayList<>();
        if (refined == null || refined.isEmpty()) return groups;
        Map<String, DanmuGroup> index = new LinkedHashMap<>();
        for (DanmuSearchResult item : refined) {
            if (item == null) continue;
            String key = item.getName() + "@" + item.getSource();
            DanmuGroup group = index.get(key);
            if (group == null) {
                group = new DanmuGroup(item.getName(), item.getSource());
                index.put(key, group);
                groups.add(group);
            }
            group.add(item);
        }
        for (DanmuGroup group : groups) {
            Collections.sort(group.getItems(), new Comparator<DanmuSearchResult>() {
                @Override
                public int compare(DanmuSearchResult left, DanmuSearchResult right) {
                    int a = left.getEpisode();
                    int b = right.getEpisode();
                    if (a == b) return 0;
                    if (a <= 0) return 1;
                    if (b <= 0) return -1;
                    return a - b;
                }
            });
        }
        return groups;
    }

    /** 展示用的剧集名：「天下 第三季」/「天下3(2023)」都收敛成「天下3」 */
    public static String showName(String keyword) {
        String base = keywordBase(keyword);
        int season = keywordSeason(keyword);
        if (!TextUtils.isEmpty(base) && season > 0) return base + season;
        if (!TextUtils.isEmpty(base)) return base;
        return compact(keyword);
    }

    /**
     * 第一层分组标题。
     * <p>
     * 已经分了二级层次，第一层不用再把「第几集」挤进去，所以这里只把集数片段抠掉，
     * 来源、季数、清晰度、括注等原始信息**尽量留着**——这样同一部剧的不同版本 / 不同来源
     * 在列表里能一眼区分，也不会把「天下第一」错并进「天下3」。
     */
    public static String groupTitle(String raw, String keyword) {
        if (TextUtils.isEmpty(raw)) return showName(keyword);
        if (isUrlLike(raw)) {
            String name = showName(keyword);
            return TextUtils.isEmpty(name) ? sourceOf(raw, false) : name;
        }
        String text = normalize(raw);
        text = P_EP_CN.matcher(text).replaceAll(" ");
        text = P_EP_SUFFIX.matcher(text).replaceAll(" ");
        text = P_EP_EN.matcher(text).replaceAll(" ");
        text = text.replaceAll("\\s+", " ").trim();
        // 「天下 03」这种集数没写「第…集」的：把尾部孤立数字去掉。
        // 前面必须有分隔符，所以「天下3」这种剧名结尾的数字不会被误删
        Matcher tailNumber = P_TAIL_NUMBER.matcher(text);
        if (tailNumber.find()) {
            text = text.substring(0, tailNumber.start()).trim();
        }
        // 抠掉集数后可能留下「-」「·」之类挂在首尾的分隔符
        text = text.replaceAll("^[\\s\\-_·|,，.。:：;；/]+", "");
        text = text.replaceAll("[\\s\\-_·|,，.。:：;；/]+$", "").trim();
        if (TextUtils.isEmpty(text)) text = showName(keyword);
        if (text.length() > 24) text = text.substring(0, 24);
        return text;
    }

    /** 来源标识：取接口域名，取不到时按内置/自定义兜底 */
    public static String sourceOf(String url, boolean builtIn) {
        if (!TextUtils.isEmpty(url)) {
            try {
                String host = Uri.parse(url).getHost();
                if (!TextUtils.isEmpty(host)) return host;
            } catch (Throwable ignored) {
            }
        }
        return builtIn ? "内置弹幕" : "自定义源";
    }

    /** 关键字里的剧集名部分：「天下3」→「天下」，「天下 第三季」→「天下」 */
    public static String keywordBase(String keyword) {
        String text = compact(keyword);
        if (TextUtils.isEmpty(text)) return "";
        Matcher matcher = P_SEASON_CN.matcher(text);
        if (matcher.find()) return text.substring(0, matcher.start());
        int end = text.length();
        while (end > 0 && Character.isDigit(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }

    /** 关键字里的季数：「天下3」→ 3，「天下第三季」→ 3，没有则 0 */
    public static int keywordSeason(String keyword) {
        String text = compact(keyword);
        if (TextUtils.isEmpty(text)) return 0;
        Matcher matcher = P_SEASON_CN.matcher(text);
        if (matcher.find()) return toNumber(matcher.group(1));
        int end = text.length();
        int start = end;
        while (start > 0 && Character.isDigit(text.charAt(start - 1))) start--;
        if (start < end) {
            try {
                int value = Integer.parseInt(text.substring(start, end));
                // 季数不会太大，尾部是年份之类的数字就不当季数用
                return value > 0 && value <= 20 ? value : 0;
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    /** 从一条结果标题里读出季数，读不到返回 0 */
    public static int seasonOf(String raw) {
        String text = normalize(raw);
        Matcher matcher = P_SEASON_CN.matcher(text);
        if (matcher.find()) return toNumber(matcher.group(1));
        matcher = P_SEASON_EN.matcher(text);
        if (matcher.find()) {
            String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            return toNumber(value);
        }
        return 0;
    }

    /** 从一条结果标题里读出集数，读不到返回 0 */
    public static int episodeOf(String raw, String keyword) {
        String text = normalize(raw);
        Matcher matcher = P_EP_CN.matcher(text);
        if (matcher.find()) return toNumber(matcher.group(1));
        matcher = P_EP_SUFFIX.matcher(text);
        if (matcher.find()) return toNumber(matcher.group(1));
        matcher = P_EP_EN.matcher(text);
        if (matcher.find()) return toNumber(matcher.group(1));
        // 「天下 03」「S01E03」这种没有标记词的：取剧集名之后最后一个数字当集数
        String tail = text;
        String base = keywordBase(keyword);
        if (!TextUtils.isEmpty(base)) {
            int from = text.indexOf(base);
            if (from >= 0) tail = text.substring(from + base.length());
        }
        Matcher numbers = P_BARE_NUMBER.matcher(tail);
        int last = 0;
        while (numbers.find()) {
            int value = toNumber(numbers.group());
            if (value > 0) last = value;
        }
        return last;
    }

    /** 全角转半角 + 去首尾空白 */
    public static String normalize(String text) {
        if (text == null) return "";
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= 0xFF01 && ch <= 0xFF5E) builder.append((char) (ch - 0xFEE0));
            else if (ch == 0x3000) builder.append(' ');
            else builder.append(ch);
        }
        return builder.toString().trim();
    }

    /** 去掉空白、括号段（含内容）与常见分隔符，便于做包含判断 */
    public static String compact(String text) {
        String normalized = normalize(text);
        // 「天下3(2023)」「【高清】狂飙」这类括注直接整段丢掉
        normalized = normalized.replaceAll("[\\(（\\[【][^\\)）\\]】]*[\\)）\\]】]", "");
        StringBuilder builder = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (Character.isWhitespace(ch)) continue;
            if ("-_·|,，.。:：;；/\\()（）[]【】<>《》\"'“”‘’".indexOf(ch) >= 0) continue;
            builder.append(ch);
        }
        return builder.toString();
    }

    private static boolean isUrlLike(String text) {
        if (TextUtils.isEmpty(text)) return false;
        String trimmed = text.trim().toLowerCase();
        return trimmed.startsWith("http://") || trimmed.startsWith("https://");
    }

    private static int toNumber(String value) {
        if (TextUtils.isEmpty(value)) return 0;
        try {
            return Integer.parseInt(value);
        } catch (Throwable ignored) {
            return chineseToNumber(value);
        }
    }

    /** 只处理 1~99 的中文数字，够用 */
    private static int chineseToNumber(String value) {
        if (TextUtils.isEmpty(value)) return 0;
        final String digits = "零一二三四五六七八九";
        int result = 0;
        int current = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '十') {
                if (current == 0) current = 1;
                result += current * 10;
                current = 0;
            } else {
                int digit = digits.indexOf(ch);
                if (digit < 0) return 0;
                current = digit;
            }
        }
        return result + current;
    }
}
