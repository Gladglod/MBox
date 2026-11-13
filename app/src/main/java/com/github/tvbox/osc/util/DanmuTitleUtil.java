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
 * 弹幕搜索结果整理：
 * <ol>
 *     <li><b>不改写</b>：接口返回的标题一律原样显示，不做替换、裁剪、删字，也不按关键词筛掉结果；</li>
 *     <li><b>旁路解析</b>：只额外读出「集数 / 来源」两个字段，供列表标注与分组使用；</li>
 *     <li><b>分组</b>：按剧（去掉 from 站点后缀）归并成一级，同名多站的整部剧嵌到二级，没有重名的
 *         一级就是整部剧；接口没带剧名的退回按弹幕来源分组。</li>
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
    /** 日期（综艺单集标题常带录制/播出日期）：20220128 / 2022-01-28 / 2022.1.28 / 2022年1月28日 */
    private static final Pattern P_DATE = Pattern.compile(
            "(?<![0-9])20[0-9]{2}[-/.年]\\s*[0-9]{1,2}[-/.月]\\s*[0-9]{1,2}日?(?![0-9])"
                    + "|(?<![0-9])20[0-9]{6}(?![0-9])");
    /** 标题头部成段的括号标注：【综艺】 / 【qq】 / （高清） 这类 */
    private static final Pattern P_BRACKET_HEAD =
            Pattern.compile("^[\\(（\\[【][^\\)）\\]】]*[\\)）\\]】]");
    /** 标题头部的来源标注：from 360 / From qq 这类 */
    private static final Pattern P_FROM_HEAD = Pattern.compile("(?i)^[\\s]*from\\s+\\S+");

    private DanmuTitleUtil() {
    }

    /**
     * 去重 + 旁路解析集数与来源。
     * <p>
     * 标题本身原样保留（接口给什么就显示什么），这里只是额外算一个集数用于「疑似此集」，
     * 以及一个来源用于分组。
     *
     * @param source  接口返回的原始结果
     * @param keyword 用户搜索用的剧集名，仅用于从标题尾部推断集数
     */
    public static List<DanmuSearchResult> refine(List<DanmuSearchResult> source, String keyword) {
        List<DanmuSearchResult> result = new ArrayList<>();
        if (source == null || source.isEmpty()) return result;

        Set<String> seen = new HashSet<>();
        for (DanmuSearchResult item : source) {
            if (item == null) continue;
            String raw = item.getName();
            if (TextUtils.isEmpty(raw)) continue;
            if (!seen.add(item.getUrl() + "|" + raw)) continue;
            result.add(new DanmuSearchResult(
                    raw,
                    item.getUrl(),
                    item.isBuiltIn(),
                    episodeOf(raw, keyword),
                    sourceOf(item.getUrl(), item.isBuiltIn()),
                    item.getAnime()));
        }
        return result;
    }

    /**
     * 分层整理：
     * <ol>
     *     <li>接口带了 animeTitle 的，先按整部剧聚合；</li>
     *     <li>剧名去掉「from 某站点」尾巴后一致的（同剧不同站），把整部剧的条目再并进同一个一级分组，
     *         一级显示合成的剧名，各部完整剧名挂在二级，减少一级列表的长度；没有重名的保持一级就是整部剧；</li>
     *     <li>接口没带剧名的（自定义源），退回按弹幕来源分组、一级沿用搜索词做标题。</li>
     * </ol>
     * 组内保持接口返回的顺序，能读出集数的按集数升序排。
     */
    public static List<DanmuGroup> group(List<DanmuSearchResult> refined, String keyword) {
        List<DanmuGroup> groups = new ArrayList<>();
        if (refined == null || refined.isEmpty()) return groups;
        String title = keyword == null ? "" : keyword.trim();

        // 第一轮：按 animeTitle 整部聚合；没带剧名的按来源各成一部
        Map<String, DanmuGroup> animeIndex = new LinkedHashMap<>();
        for (DanmuSearchResult item : refined) {
            if (item == null) continue;
            String anime = item.getAnime();
            boolean byAnime = !TextUtils.isEmpty(anime);
            String key = byAnime ? "anime|" + compact(anime) : "source|" + item.getSource();
            DanmuGroup entry = animeIndex.get(key);
            if (entry == null) {
                String showName = byAnime ? anime : (TextUtils.isEmpty(title) ? item.getSource() : title);
                entry = new DanmuGroup(showName, item.getSource());
                animeIndex.put(key, entry);
            }
            entry.add(item);
        }

        // 第二轮：剧名去掉来源后缀（from xxx）后一致的并成一组，原剧名下挂为二级
        Map<String, DanmuGroup> merged = new LinkedHashMap<>();
        int solo = 0;
        for (Map.Entry<String, DanmuGroup> e : animeIndex.entrySet()) {
            DanmuGroup entry = e.getValue();
            sortEpisodes(entry.getItems());
            if (e.getKey().startsWith("source|")) {
                // 自定义源按来源归的组，保持原样不参与剧名合并
                merged.put("solo|" + (solo++), entry);
                continue;
            }
            String base = stripSourceSuffix(entry.getShowName());
            String key = "base|" + compact(base);
            DanmuGroup group = merged.get(key);
            if (group == null) {
                group = new DanmuGroup(base, entry.getSource());
                merged.put(key, group);
            }
            group.addChild(entry);
        }

        // 收尾：并入后只有一部的不多套一层，直接把整部剧的完整标题提到一级
        for (DanmuGroup group : merged.values()) {
            if (group.hasChildren() && group.getChildren().size() == 1) {
                groups.add(group.getChildren().get(0));
            } else {
                groups.add(group);
            }
        }
        return groups;
    }

    /** 组内按能识别出的集数升序排；识别不出的（0）靠后，其余保持接口返回顺序 */
    private static void sortEpisodes(List<DanmuSearchResult> items) {
        Collections.sort(items, new Comparator<DanmuSearchResult>() {
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

    /** 去掉剧名尾巴上的来源标注：「少年神探狄仁杰(2014)…from iqiyi」→「少年神探狄仁杰(2014)…」 */
    private static String stripSourceSuffix(String title) {
        String text = normalize(title);
        return text.replaceAll("(?i)\\s*from\\s+\\S+\\s*$", "").trim();
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

    /**
     * 综艺等节目单集标题的显示整理（只改显示，不改加载用的原始标题）：
     * <ol>
     *     <li>去掉头部成段的来源标注：「【综艺】from 360 【qq】 第1期…」→「第1期…」；</li>
     *     <li>标题里的日期提到最前面（「第1期 … 2023-08-13期」→「20230813 第1期 …」），按日期找期数更直观。</li>
     * </ol>
     * 没有日期也没有头部标注的标题原样返回。
     */
    public static String displayTitle(String raw) {
        String text = stripSourcePrefix(normalize(raw));
        if (TextUtils.isEmpty(text)) return text;
        Matcher matcher = P_DATE.matcher(text);
        if (!matcher.find()) return text;
        String digits = compactDate(matcher.group());
        if (TextUtils.isEmpty(digits) || text.startsWith(digits)) return text;
        String head = text.substring(0, matcher.start()).trim();
        String tail = text.substring(matcher.end()).trim();
        // 日期后面如果只剩「期 / 集」标记词，是日期自带的「…期」尾巴，一并去掉
        if (tail.matches("^[期集][\\s:：，,·.．-]*$")) tail = "";
        StringBuilder display = new StringBuilder(digits);
        if (!TextUtils.isEmpty(head)) display.append(' ').append(head);
        if (!TextUtils.isEmpty(tail)) display.append(' ').append(tail);
        return display.toString().replaceAll("\\s{2,}", " ").trim();
    }

    /**
     * 去掉标题头部成段的来源标注：「【综艺】from 360 【qq】 第1期…」→「第1期…」。
     * 只从头部连续地摘，遇到真正的标题内容就停，避免误伤正文里的括号段。
     */
    private static String stripSourcePrefix(String text) {
        String result = text;
        while (true) {
            String trimmed = result.replaceFirst("^[\\s\\-–—:：|｜]+", "");
            Matcher bracket = P_BRACKET_HEAD.matcher(trimmed);
            if (bracket.find()) {
                result = trimmed.substring(bracket.end());
                continue;
            }
            Matcher from = P_FROM_HEAD.matcher(trimmed);
            if (from.find()) {
                result = trimmed.substring(from.end());
                continue;
            }
            return trimmed;
        }
    }

    /** 日期统一成 20220128 的 8 位形式，解析不出返回空 */
    private static String compactDate(String matched) {
        String digits = matched.replaceAll("[^0-9]", "");
        if (digits.length() == matched.length()) {
            // 纯数字形式，8 位才算日期
            return digits.length() == 8 ? digits : "";
        }
        // 带分隔符的形式：2022-01-28 / 2022年1月28日 → 补零成 8 位
        String[] parts = matched.split("[-/.年月日\\s]+");
        if (parts.length != 3) return "";
        try {
            return String.format(java.util.Locale.US, "%04d%02d%02d",
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()));
        } catch (Throwable ignored) {
            return "";
        }
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
