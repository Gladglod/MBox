package com.github.tvbox.osc.util;

/**
 * @author pj567
 * @date :2020/12/23
 * @description:
 */
public class HawkConfig {
    public static final String API_URL = "api_url";
    public static final String EPG_URL = "epg_url";
    public static final String SHOW_PREVIEW = "show_preview";
    public static final String API_HISTORY = "api_history";
    public static final String API_LINE_LIST = "api_line_list";
    public static final String API_LINE_SOURCE = "api_line_source";
    public static final String LIVE_API_HISTORY = "live_api_history";
    public static final String EPG_HISTORY = "epg_history";
    public static final String HOME_API = "home_api";
    public static final String DEFAULT_PARSE = "parse_default";
    public static final String DEBUG_OPEN = "debug_open";
    public static final String PARSE_WEBVIEW = "parse_webview"; // true 系统 false xwalk
    public static final String IJK_CODEC = "ijk_codec";
    public static final String PLAY_TYPE = "play_type";//1 ijk 2 exo 10 MXPlayer
    public static final String LIVE_PLAY_TYPE = "live_play_type";//1 ijk 2 exo 10 MXPlayer
    public static final String PLAY_RENDER = "play_render"; //0 texture 2
    public static final String PLAY_SCALE = "play_scale"; //0 texture 2
    public static final String LIVE_PLAY_SCALE = "live_play_scale";
    public static final String PLAY_TIME_STEP = "play_time_step"; //0 texture 2
    public static final String DOH_URL = "doh_url";
    public static final int DEFAULT_HOME_REC = 1;
    public static final String HOME_REC = "home_rec"; // 0 豆瓣热播 1 数据源推荐 2 历史
    public static final String HISTORY_NUM = "history_num";
    public static final String HISTORY_MERGE = "history_merge";
    public static final String SEARCH_VIEW = "search_view"; // 0 列表 1 缩略图
    public static final String LIVE_CHANNEL = "last_live_channel_name";
    public static final String LIVE_CHANNEL_REVERSE = "live_channel_reverse";
    public static final String LIVE_CROSS_GROUP = "live_cross_group";
    public static final String LIVE_CONNECT_TIMEOUT = "live_connect_timeout";
    public static final String LIVE_SHOW_NET_SPEED = "live_show_net_speed";
    public static final String LIVE_SHOW_RESOLUTION = "live_show_resolution";
    public static final String LIVE_SHOW_TIME = "live_show_time";
    public static final String FAST_SEARCH_MODE = "fast_search_mode";
    public static final String SUBTITLE_TEXT_SIZE = "subtitle_text_size";
    public static final String SUBTITLE_TIME_DELAY = "subtitle_time_delay";
    public static final String SUBTITLE_EXO_SCALE = "subtitle_exo_scale";
    public static final String SUBTITLE_EXO_POSITION = "subtitle_exo_position";
    public static final String SOURCES_FOR_SEARCH = "checked_sources_for_search";
    public static final String HOME_REC_STYLE = "home_rec_style";
    public static final String NOW_DATE = "now_date"; //当前日期
    public static final String REMOTE_TVBOX = "remote_tvbox_host";
    public static final String IJK_CACHE_PLAY = "ijk_cache_play";
    public static final String PLAYER_IS_LIVE = "player_is_live";
    public static final String DOH_JSON = "doh_json";
    public static final String LIVE_GROUP_INDEX = "live_group_index";
    public static final String LIVE_GROUP_LIST = "live_group_list";
    public static final String LIVE_API_URL = "live_api_url";
    public static final String M3U8_PURIFY = "m3u8_purify";
    public static final String AUTO_SWITCH_LINE = "auto_switch_line";
    public static final String SCREEN_DISPLAY = "screen_display";
    public static final String LIVE_WEB_HEADER = "live_web_header";
    public static final String DEFAULT_LOAD_LIVE = "DEFAULT_LOAD_LIVE";
    public static final String SEARCH_HISTORY = "search_history";
    public static final String DANMU_OPEN = "danmu_open";
    public static final String DANMU_MAX_LINE = "danmu_max_line";
    public static final String DANMU_SPEED = "danmu_speed";
    public static final String DANMU_ALPHA = "danmu_alpha";
    public static final String DANMU_SIZE_SCALE = "danmu_size_scale";
    public static final String DANMU_RANDOM_COLOR = "danmu_random_color";
    public static final String DANMU_API = "danmu_api";

    /** 纯色背景（ARGB，0 表示未设置） */
    public static final String WALLPAPER_COLOR = "wallpaper_color";

    /**
     * 首页标签配置：每行一条 {@code 可见(1/0)\t分类id}，行序即显示顺序。
     * 空字符串表示「默认」——全部显示，按数据源给出的顺序。
     */
    public static final String HOME_TABS = "home_tabs";

    /* ===== 外观主题 ===== */
    /**
     * 界面明暗模式：0 日间 / 1 夜间 / 2 跟随系统。默认 0（日间，白色为主）。
     * 具体映射到 {@code AppCompatDelegate.setDefaultNightMode()} 见 {@code ThemeUtil}。
     */
    public static final String UI_MODE = "ui_mode";
    public static final int UI_MODE_DAY = 0;
    public static final int UI_MODE_NIGHT = 1;
    public static final int UI_MODE_SYSTEM = 2;
    // 默认跟随系统：手机开了深色就该是深色，不该让用户自己来设置里找
    public static final int DEFAULT_UI_MODE = UI_MODE_SYSTEM;

    /* ===== 播放手势 ===== */
    /** 长按屏幕进入变速 */
    public static final String GESTURE_SPEED_BOOST = "gesture_speed_boost";
    /** 长按加速的最高倍速 */
    public static final String GESTURE_MAX_SPEED = "gesture_max_speed";
    /** 双击中间区域播放 / 暂停 */
    public static final String GESTURE_DOUBLE_TAP_PLAY = "gesture_double_tap_play";
    /** 双击左右两侧快进 / 快退的毫秒数 */
    public static final String GESTURE_DOUBLE_TAP_SEEK = "gesture_double_tap_seek";
    /** 竖屏上下滑动切换上一集 / 下一集 */
    public static final String GESTURE_PORTRAIT_SWIPE = "gesture_portrait_swipe";

    public static boolean hotVodDelete;
}
