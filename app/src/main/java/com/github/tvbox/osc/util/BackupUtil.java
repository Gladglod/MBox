package com.github.tvbox.osc.util;

import com.orhanobut.hawk.Hawk;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 「备份与同步」的配置快照：把设置页里用户可能关心的配置项导出为一份 JSON 文本，
 * 在另一台设备（或重装后）读回来即完成同步。
 *
 * <p>覆盖范围：数据源配置（API_URL/API_HISTORY 及直播、EPG、DOH）、外观主题、
 * 播放/字幕、直播、弹幕、手势、短剧源等。刻意不包含会话性质的状态
 * （正在播放的频道、上次时间日期、搜索历史等）。</p>
 *
 * <p>格式：
 * <pre>{@code
 * {
 *   "format": 1,
 *   "app": "tvboxos",
 *   "time": 1700000000000,
 *   "entries": [ {"k":"key","t":"string","v":"..."} ]
 * }
 * }</pre></p>
 */
public final class BackupUtil {

    public static final int FORMAT_VERSION = 1;

    /** 恢复结果：成功条数 + 需要即时生效的几个大类 */
    public static final class RestoreResult {
        public int count = 0;
        public boolean uiModeChanged = false;      // 外观主题（即时应用）
        public boolean playerChanged = false;      // 播放内核相关（需 PlayerHelper.init）
        public boolean danmuChanged = false;       // 弹幕（发 Settings 事件）
        public boolean wallpaperChanged = false;   // 纯色背景（需 reapply）
    }

    /** 备份覆盖的配置 key（不在此列表里的 key 不导出也不导入，规避会话状态与自定义对象） */
    private static final String[] KEYS = {
            // 数据源 / 直播 / EPG / DOH
            HawkConfig.API_URL, HawkConfig.API_HISTORY, HawkConfig.LIVE_API_URL,
            HawkConfig.LIVE_API_HISTORY, HawkConfig.EPG_URL, HawkConfig.DOH_URL,
            HawkConfig.REMOTE_TVBOX,
            // 外观主题
            HawkConfig.UI_MODE, HawkConfig.WALLPAPER_COLOR, HawkConfig.HOME_TABS,
            HawkConfig.HOME_REC, HawkConfig.HOME_REC_STYLE, HawkConfig.SEARCH_VIEW,
            HawkConfig.DEFAULT_LOAD_LIVE,
            // 历史与搜索
            HawkConfig.HISTORY_NUM, HawkConfig.HISTORY_MERGE, HawkConfig.FAST_SEARCH_MODE,
            // 播放与默认嗅探
            HawkConfig.PLAY_TYPE, HawkConfig.PLAY_RENDER, HawkConfig.PLAY_SCALE,
            HawkConfig.IJK_CODEC, HawkConfig.PLAY_TIME_STEP, HawkConfig.IJK_CACHE_PLAY,
            HawkConfig.AUTO_SWITCH_LINE, HawkConfig.M3U8_PURIFY, HawkConfig.SHOW_PREVIEW,
            HawkConfig.DEFAULT_PARSE, HawkConfig.PARSE_WEBVIEW, HawkConfig.SCREEN_DISPLAY,
            // 字幕
            HawkConfig.SUBTITLE_TEXT_SIZE, HawkConfig.SUBTITLE_TIME_DELAY,
            HawkConfig.SUBTITLE_EXO_SCALE, HawkConfig.SUBTITLE_EXO_POSITION,
            // 直播偏好
            HawkConfig.LIVE_PLAY_TYPE, HawkConfig.LIVE_PLAY_SCALE,
            HawkConfig.LIVE_CONNECT_TIMEOUT, HawkConfig.LIVE_SHOW_NET_SPEED,
            HawkConfig.LIVE_SHOW_RESOLUTION, HawkConfig.LIVE_SHOW_TIME,
            HawkConfig.LIVE_CHANNEL_REVERSE, HawkConfig.LIVE_CROSS_GROUP,
            // 弹幕
            HawkConfig.DANMU_OPEN, HawkConfig.DANMU_MAX_LINE, HawkConfig.DANMU_SPEED,
            HawkConfig.DANMU_ALPHA, HawkConfig.DANMU_SIZE_SCALE,
            HawkConfig.DANMU_RANDOM_COLOR, HawkConfig.DANMU_API,
            // 播放手势
            HawkConfig.GESTURE_SPEED_BOOST, HawkConfig.GESTURE_MAX_SPEED,
            HawkConfig.GESTURE_SPEED_BOOST_LOCKED, HawkConfig.GESTURE_DOUBLE_TAP_PLAY,
            HawkConfig.GESTURE_DOUBLE_TAP_SEEK, HawkConfig.GESTURE_PORTRAIT_SWIPE,
            // 短剧
            HawkConfig.SHORT_DRAMA_SOURCES, HawkConfig.SHORT_DRAMA_SELECTED,
    };

    private BackupUtil() {
    }

    /** 把当前配置快照成一个 JSON 文本；不支持的值类型（自定义对象等）会被跳过 */
    public static String buildJson() {
        JSONObject root = new JSONObject();
        JSONArray entries = new JSONArray();
        for (String key : KEYS) {
            Object value;
            try {
                value = Hawk.get(key); // 未配置的 key 返回 null，直接跳过
            } catch (Throwable th) {
                continue;
            }
            String type = typeName(value);
            if (type == null) continue;
            try {
                JSONObject entry = new JSONObject();
                entry.put("k", key);
                entry.put("t", type);
                entry.put("v", value);
                entries.put(entry);
            } catch (Throwable ignored) {
            }
        }
        try {
            root.put("format", FORMAT_VERSION);
            root.put("app", "tvboxos");
            root.put("time", System.currentTimeMillis());
            root.put("entries", entries);
        } catch (Throwable ignored) {
        }
        return root.toString();
    }

    /**
     * 从 JSON 文本恢复配置。只认白名单里的 key，未知 key 一律忽略，避免脏数据灌入。
     *
     * @return 恢复条目数；格式不对抛 {@link org.json.JSONException}
     */
    public static RestoreResult restore(String json) throws org.json.JSONException {
        RestoreResult result = new RestoreResult();
        if (json == null || json.trim().isEmpty()) {
            throw new org.json.JSONException("empty backup");
        }
        JSONObject root = new JSONObject(json);
        JSONArray entries = root.optJSONArray("entries");
        if (entries == null) {
            throw new org.json.JSONException("no entries");
        }
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            String key = entry.optString("k", "");
            String type = entry.optString("t", "");
            Object raw = entry.opt("v");
            if (!isBackupKey(key) || raw == null) continue;
            try {
                if (!restoreEntry(key, type, raw, result)) continue;
                result.count++;
            } catch (Throwable ignored) {
                // 单条坏了不拖垮整份备份
            }
        }
        return result;
    }

    private static boolean restoreEntry(String key, String type, Object raw, RestoreResult result) {
        if ("bool".equals(type) && raw instanceof Boolean) {
            Hawk.put(key, (Boolean) raw);
        } else if ("int".equals(type) && raw instanceof Number) {
            Hawk.put(key, (Object) ((Number) raw).intValue());
            // Integer 泛型消歧：显式转型避免 Hawk.put(String, Object) 重载歧义
        } else if ("long".equals(type) && raw instanceof Number) {
            Hawk.put(key, (Object) ((Number) raw).longValue());
        } else if ("float".equals(type) && raw instanceof Number) {
            Hawk.put(key, (Object) ((Number) raw).floatValue());
        } else if ("string".equals(type) && raw instanceof String) {
            Hawk.put(key, (String) raw);
        } else {
            return false;
        }
        markSideEffects(key, result);
        return true;
    }

    private static boolean isBackupKey(String key) {
        for (String k : KEYS) {
            if (k.equals(key)) return true;
        }
        return false;
    }

    /** 恢复完成后再按 key 触发即时生效逻辑 */
    private static void markSideEffects(String key, RestoreResult result) {
        if (HawkConfig.UI_MODE.equals(key)) {
            result.uiModeChanged = true;
        } else if (HawkConfig.PLAY_TYPE.equals(key) || HawkConfig.PLAY_RENDER.equals(key)) {
            result.playerChanged = true;
        } else if (key.startsWith("danmu_")) {
            result.danmuChanged = true;
        } else if (HawkConfig.WALLPAPER_COLOR.equals(key)) {
            result.wallpaperChanged = true;
        }
    }

    private static String typeName(Object value) {
        if (value instanceof Boolean) return "bool";
        if (value instanceof Integer) return "int";
        if (value instanceof Long) return "long";
        if (value instanceof Float) return "float";
        if (value instanceof String) return "string";
        return null;
    }
}
