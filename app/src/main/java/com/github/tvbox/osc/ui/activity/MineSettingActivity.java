package com.github.tvbox.osc.ui.activity;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.github.tvbox.osc.BuildConfig;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.api.DanmakuApi;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.DanmuSearchResult;
import com.github.tvbox.osc.bean.IJKCode;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.dialog.DanmuApiDialog;
import com.github.tvbox.osc.ui.dialog.SelectDialog;
import com.github.tvbox.osc.ui.dialog.XWalkInitDialog;
import com.github.tvbox.osc.util.DanmuHelper;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HistoryHelper;
import com.github.tvbox.osc.util.PlayerHelper;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.FileCallback;
import com.lzy.okgo.model.Progress;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;

import org.greenrobot.eventbus.EventBus;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「我的」页面各个横排菜单项的二级设置页。
 * 通过 {@link #EXTRA_SECTION} 决定展示哪一组设置，全部使用统一的手机端设置行样式。
 */
public class MineSettingActivity extends BaseActivity {

    public static final String EXTRA_SECTION = "mine_section";

    public static final String SECTION_APPEARANCE = "appearance";
    public static final String SECTION_PLAY = "play";
    public static final String SECTION_UI = "ui";
    public static final String SECTION_GESTURE = "gesture";
    public static final String SECTION_DANMU = "danmu";
    public static final String SECTION_ABOUT = "about";

    private static final int REQUEST_PICK_WALLPAPER = 4001;

    private String section;
    private LinearLayout contentContainer;
    private LinearLayout currentCard;
    private final List<Runnable> refreshers = new ArrayList<>();

    private interface Action {
        void run();
    }

    private interface TextProvider {
        String get();
    }

    private interface IntProvider {
        int get();
    }

    private interface IntAction {
        void run(int value);
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_mine_setting;
    }

    @Override
    protected boolean shouldRefreshAutoSize() {
        return true;
    }

    @Override
    protected void init() {
        section = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_SECTION);
        if (section == null) section = SECTION_APPEARANCE;
        contentContainer = findViewById(R.id.contentContainer);
        findViewById(R.id.ivBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                onBackPressed();
            }
        });
        ((TextView) findViewById(R.id.tvTitle)).setText(titleOf(section));
        buildSection();
        refreshRows();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshRows();
        if (SECTION_APPEARANCE.equals(section)) updateSwatches();
    }

    private String titleOf(String section) {
        switch (section) {
            case SECTION_PLAY:
                return "播放设定";
            case SECTION_UI:
                return "界面偏好";
            case SECTION_GESTURE:
                return "播放手势";
            case SECTION_DANMU:
                return "弹幕设定";
            case SECTION_ABOUT:
                return "关于";
            case SECTION_APPEARANCE:
            default:
                return "外观主题";
        }
    }

    private void buildSection() {
        switch (section) {
            case SECTION_PLAY:
                buildPlay();
                break;
            case SECTION_UI:
                buildUi();
                break;
            case SECTION_GESTURE:
                buildGesture();
                break;
            case SECTION_DANMU:
                buildDanmu();
                break;
            case SECTION_ABOUT:
                buildAbout();
                break;
            case SECTION_APPEARANCE:
            default:
                buildAppearance();
                break;
        }
    }

    /* ============================ 外观主题 ============================ */

    private void buildAppearance() {
        header("明暗模式");
        card();
        addSelectRow("主题模式", "日间以白色为主；跟随系统会随手机深色模式自动切换", "请选择主题模式",
                new String[]{"日间", "夜间", "跟随系统"},
                new IntProvider() {
                    @Override
                    public int get() {
                        return com.github.tvbox.osc.util.ThemeUtil.getMode();
                    }
                },
                new IntAction() {
                    @Override
                    public void run(final int value) {
                        if (value == com.github.tvbox.osc.util.ThemeUtil.getMode()) return;
                        // 延后一拍再切：此刻选择弹窗还没 dismiss，直接切会导致 Activity 在弹窗显示期间被重建
                        contentContainer.post(new Runnable() {
                            @Override
                            public void run() {
                                com.github.tvbox.osc.util.ThemeUtil.setMode(value);
                            }
                        });
                    }
                });

        header("壁纸");
        card();
        addActionRow("自定义配图", "从相册选一张图片作为应用背景", new Action() {
            @Override
            public void run() {
                pickCustomWallpaper();
            }
        });
        addActionRow("数据源壁纸", "下载当前数据源配置的壁纸", new Action() {
            @Override
            public void run() {
                changeWallpaperFromApi();
            }
        });
        addActionRow("重置背景", "清除自定义配图与纯色，恢复默认背景", new Action() {
            @Override
            public void run() {
                resetWallpaper();
            }
        });

        header("纯色背景");
        card();
        addColorPicker();

        header("首页与列表");
        card();
        addSwitchRow("首页多行", "热门推荐使用多行网格展示", HawkConfig.HOME_REC_STYLE, false, null);
        addSelectRow("搜索展示", "搜索结果列表样式", "请选择搜索视图",
                new String[]{"列表", "缩略图"},
                new IntProvider() {
                    @Override
                    public int get() {
                        return Hawk.get(HawkConfig.SEARCH_VIEW, 0);
                    }
                },
                new IntAction() {
                    @Override
                    public void run(int value) {
                        Hawk.put(HawkConfig.SEARCH_VIEW, value);
                    }
                });
        addSelectRow("下次进入", "启动应用后默认打开的页面", "请选择启动页面",
                new String[]{"点播", "直播"},
                new IntProvider() {
                    @Override
                    public int get() {
                        return Hawk.get(HawkConfig.DEFAULT_LOAD_LIVE, false) ? 1 : 0;
                    }
                },
                new IntAction() {
                    @Override
                    public void run(int value) {
                        Hawk.put(HawkConfig.DEFAULT_LOAD_LIVE, value == 1);
                    }
                });
    }

    private void changeWallpaperFromApi() {
        String wallpaper = ApiConfig.get().wallpaper;
        if (TextUtils.isEmpty(wallpaper)) {
            Toast.makeText(this, "当前数据源没有提供壁纸", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "正在下载壁纸…", Toast.LENGTH_SHORT).show();
        OkGo.<File>get(wallpaper).execute(new FileCallback(getFilesDir().getAbsolutePath(), "wp") {
            @Override
            public void onSuccess(Response<File> response) {
                changeWallpaper(true);
            }

            @Override
            public void onError(Response<File> response) {
                super.onError(response);
                Toast.makeText(MineSettingActivity.this, "壁纸下载失败", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void downloadProgress(Progress progress) {
                super.downloadProgress(progress);
            }
        });
    }

    private void resetWallpaper() {
        File wp = new File(getFilesDir().getAbsolutePath() + "/wp");
        if (wp.exists() && !wp.delete()) {
            Toast.makeText(this, "壁纸重置失败", Toast.LENGTH_SHORT).show();
            return;
        }
        Hawk.put(HawkConfig.WALLPAPER_COLOR, 0);
        applyWallpaperNow();
        updateSwatches();
        Toast.makeText(this, "已恢复默认背景", Toast.LENGTH_SHORT).show();
    }

    /* ------------------------- 纯色背景 ------------------------- */

    /** 日间纯色：浅色系，配深色文字 */
    private static final int[] WALLPAPER_COLORS_DAY = {
            0xFFF3F4F7, 0xFFFFFFFF, 0xFFEFF4FA, 0xFFF4F0FA,
            0xFFFBF1F0, 0xFFEFF7F1, 0xFFFAF5EA, 0xFFEDF3FB,
            0xFFF5F5F5, 0xFFEDF6F7, 0xFFF7F0F8, 0xFFEDF7F5
    };

    /** 夜间纯色：深色系，避免与白色文字冲突 */
    private static final int[] WALLPAPER_COLORS_NIGHT = {
            0xFF0E0E12, 0xFF1B2430, 0xFF22303C, 0xFF2C2A3A,
            0xFF33262A, 0xFF1E2B26, 0xFF3A2F1E, 0xFF101B2E,
            0xFF2B2B2B, 0xFF14202A, 0xFF26172E, 0xFF00212B
    };

    /** 色板跟着当前明暗模式走 */
    private int[] wallpaperColors() {
        return com.github.tvbox.osc.util.ThemeUtil.isNight(this)
                ? WALLPAPER_COLORS_NIGHT : WALLPAPER_COLORS_DAY;
    }

    private final List<View> colorSwatches = new ArrayList<>();

    private void addColorPicker() {
        if (currentCard == null) card();
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(12), dp(10), dp(12), dp(10));
        currentCard.addView(wrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(6);
        wrap.addView(grid);

        int size = dp(38);
        int margin = dp(6);
        colorSwatches.clear();
        for (final int color : wallpaperColors()) {
            View swatch = new View(this);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = size;
            lp.height = size;
            lp.setMargins(margin, margin, margin, margin);
            swatch.setLayoutParams(lp);
            swatch.setTag(color);
            swatch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FastClickCheckUtil.check(v);
                    applyPureColor(color);
                }
            });
            colorSwatches.add(swatch);
            grid.addView(swatch);
        }
        updateSwatches();
    }

    private void applyPureColor(int color) {
        // 纯色与自定义配图互斥：选了纯色就把配图删掉
        File wp = new File(getFilesDir().getAbsolutePath() + "/wp");
        if (wp.exists()) wp.delete();
        Hawk.put(HawkConfig.WALLPAPER_COLOR, color);
        applyWallpaperNow();
        updateSwatches();
    }

    private void updateSwatches() {
        int current = Hawk.get(HawkConfig.WALLPAPER_COLOR, 0);
        boolean hasImage = new File(getFilesDir().getAbsolutePath() + "/wp").exists();
        for (View swatch : colorSwatches) {
            Object tag = swatch.getTag();
            if (!(tag instanceof Integer)) continue;
            int color = (Integer) tag;
            GradientDrawable drawable = new GradientDrawable();
            drawable.setShape(GradientDrawable.OVAL);
            drawable.setColor(color);
            boolean selected = !hasImage && color == current;
            drawable.setStroke(dp(selected ? 2 : 1),
                    getResources().getColor(selected ? R.color.ui_text_primary : R.color.ui_text_tertiary));
            swatch.setBackground(drawable);
        }
    }

    /* ------------------------- 自定义配图 ------------------------- */

    private void pickCustomWallpaper() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "选择背景图片"), REQUEST_PICK_WALLPAPER);
        } catch (Throwable th) {
            Toast.makeText(this, "没有找到可用的图片选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_WALLPAPER || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        saveWallpaperFromUri(data.getData());
    }

    /** 把选中的图片压缩后写入 files/wp，作为全局背景 */
    private void saveWallpaperFromUri(Uri uri) {
        InputStream input = null;
        FileOutputStream output = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            input = getContentResolver().openInputStream(uri);
            if (input == null) {
                Toast.makeText(this, "读取图片失败", Toast.LENGTH_SHORT).show();
                return;
            }
            BitmapFactory.decodeStream(input, null, bounds);
            input.close();
            input = null;

            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (longest / sample > 1440) {
                sample *= 2;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            input = getContentResolver().openInputStream(uri);
            Bitmap bitmap = BitmapFactory.decodeStream(input, null, opts);
            if (bitmap == null) {
                Toast.makeText(this, "图片解码失败", Toast.LENGTH_SHORT).show();
                return;
            }

            File wp = new File(getFilesDir().getAbsolutePath() + "/wp");
            output = new FileOutputStream(wp);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output);
            output.flush();
            bitmap.recycle();

            Hawk.put(HawkConfig.WALLPAPER_COLOR, 0);
            applyWallpaperNow();
            updateSwatches();
            Toast.makeText(this, "背景已更新", Toast.LENGTH_SHORT).show();
        } catch (Throwable th) {
            Toast.makeText(this, "设置背景失败：" + th.getMessage(), Toast.LENGTH_SHORT).show();
        } finally {
            try {
                if (output != null) output.close();
            } catch (Throwable ignored) {
            }
            try {
                if (input != null) input.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /* ============================ 播放设定 ============================ */

    private void buildPlay() {
        header("播放器");
        card();
        addSelectRow("播放器", "默认使用的播放内核", "请选择默认播放器",
                playerNames(),
                new IntProvider() {
                    @Override
                    public int get() {
                        int type = Hawk.get(HawkConfig.PLAY_TYPE, 2);
                        return Math.max(0, PlayerHelper.getExistPlayerTypes().indexOf(type));
                    }
                },
                new IntAction() {
                    @Override
                    public void run(int value) {
                        ArrayList<Integer> types = PlayerHelper.getExistPlayerTypes();
                        if (value < 0 || value >= types.size()) return;
                        Hawk.put(HawkConfig.PLAY_TYPE, types.get(value));
                        PlayerHelper.init();
                    }
                });
        addSelectRow("渲染方式", "SurfaceView 兼容性更好", "请选择默认渲染方式",
                new String[]{PlayerHelper.getRenderName(0), PlayerHelper.getRenderName(1)},
                new IntProvider() {
                    @Override
                    public int get() {
                        return Hawk.get(HawkConfig.PLAY_RENDER, 0) == 1 ? 1 : 0;
                    }
                },
                new IntAction() {
                    @Override
                    public void run(int value) {
                        Hawk.put(HawkConfig.PLAY_RENDER, value);
                        PlayerHelper.init();
                    }
                });
        addSelectRow("画面缩放", "默认画面显示比例", "请选择默认画面缩放",
                scaleNames(),
                new IntProvider() {
                    @Override
                    public int get() {
                        int scale = Hawk.get(HawkConfig.PLAY_SCALE, 0);
                        return (scale < 0 || scale >= scaleNames().length) ? 0 : scale;
                    }
                },
                new IntAction() {
                    @Override
                    public void run(int value) {
                        Hawk.put(HawkConfig.PLAY_SCALE, value);
                    }
                });
        addSelectRow("IJK解码方式", "硬解码兼容性最好", "请选择IJK解码",
                ijkNames(),
                new IntProvider() {
                    @Override
                    public int get() {
                        List<IJKCode> codes = ApiConfig.get().getIjkCodes();
                        String cur = Hawk.get(HawkConfig.IJK_CODEC, "硬解码");
                        if (codes != null) {
                            for (int i = 0; i < codes.size(); i++) {
                                if (cur.equals(codes.get(i).getName())) return i;
                            }
                        }
                        return 0;
                    }
                },
                new IntAction() {
                    @Override
                    public void run(int value) {
                        List<IJKCode> codes = ApiConfig.get().getIjkCodes();
                        if (codes == null || value < 0 || value >= codes.size()) return;
                        Hawk.put(HawkConfig.IJK_CODEC, codes.get(value).getName());
                    }
                });

        header("播放行为");
        card();
        addSwitchRow("IJK缓存", "开启后可减少卡顿，占用更多内存", HawkConfig.IJK_CACHE_PLAY, false, null);
        addSwitchRow("自动换线", "播放失败时自动切换线路", HawkConfig.AUTO_SWITCH_LINE, true, null);
        addSwitchRow("M3U8去广告", "自动过滤 M3U8 中的广告分片", HawkConfig.M3U8_PURIFY, false, null);
        addSwitchRow("窗口预览", "详情页顶部显示小窗预览", HawkConfig.SHOW_PREVIEW, true, null);
    }

    private String[] playerNames() {
        ArrayList<Integer> types = PlayerHelper.getExistPlayerTypes();
        String[] names = new String[types.size()];
        for (int i = 0; i < types.size(); i++) {
            names[i] = PlayerHelper.getPlayerName(types.get(i));
        }
        if (names.length == 0) return new String[]{"IJK"};
        return names;
    }

    private String[] scaleNames() {
        String[] names = new String[6];
        for (int i = 0; i < names.length; i++) {
            names[i] = PlayerHelper.getScaleName(i);
        }
        return names;
    }

    private String[] ijkNames() {
        List<IJKCode> codes = ApiConfig.get().getIjkCodes();
        if (codes == null || codes.isEmpty()) return new String[]{"硬解码"};
        String[] names = new String[codes.size()];
        for (int i = 0; i < codes.size(); i++) {
            names[i] = codes.get(i).getName();
        }
        return names;
    }

    /* ============================ 界面偏好 ============================ */

    private void buildUi() {
        header("首页");
        card();
        addActionRow("首页标签", "选择首页显示哪些分类、长按拖动调整顺序", new Action() {
            @Override
            public void run() {
                jumpActivity(HomeTabSettingActivity.class);
            }
        });
        addSelectRow("首页推荐", "最左侧「热门推荐」页的数据来源", "请选择首页列表数据",
                new String[]{"豆瓣热播", "站点推荐", "历史记录"},
                new IntProvider() {
                    @Override
                    public int get() {
                        int rec = Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC);
                        return (rec < 0 || rec > 2) ? HawkConfig.DEFAULT_HOME_REC : rec;
                    }
                },
                new IntAction() {
                    @Override
                    public void run(int value) {
                        Hawk.put(HawkConfig.HOME_REC, value);
                    }
                });
        addSwitchRow("聚合搜索", "搜索时同时查询全部站源", HawkConfig.FAST_SEARCH_MODE, true, null);

        header("历史与搜索");
        card();
        addSwitchRow("历史合并", "同一影片合并为一条记录", HawkConfig.HISTORY_MERGE, false, null);
        addSelectRow("历史记录数量", "本地保留的历史条数", "保留历史记录数量",
                new String[]{HistoryHelper.getHistoryNumName(0), HistoryHelper.getHistoryNumName(1), HistoryHelper.getHistoryNumName(2)},
                new IntProvider() {
                    @Override
                    public int get() {
                        int num = Hawk.get(HawkConfig.HISTORY_NUM, 0);
                        return (num < 0 || num > 2) ? 0 : num;
                    }
                },
                new IntAction() {
                    @Override
                    public void run(int value) {
                        Hawk.put(HawkConfig.HISTORY_NUM, value);
                    }
                });
        addSwitchRow("嗅探 WebView", "开启使用系统 WebView，关闭使用 XWalk", HawkConfig.PARSE_WEBVIEW, true,
                new Runnable() {
                    @Override
                    public void run() {
                        if (!Hawk.get(HawkConfig.PARSE_WEBVIEW, true)) {
                            Toast.makeText(MineSettingActivity.this,
                                    "XWalkView 仅适用于部分低版本系统", Toast.LENGTH_LONG).show();
                            XWalkInitDialog dialog = new XWalkInitDialog(mContext);
                            dialog.setOnListener(new XWalkInitDialog.OnListener() {
                                @Override
                                public void onchange() {
                                }
                            });
                            dialog.show();
                        }
                    }
                });

        header("调试");
        card();
        addSwitchRow("调试模式", "显示播放器调试信息", HawkConfig.DEBUG_OPEN, false, null);
    }

    /* ============================ 播放手势 ============================ */

    private void buildGesture() {
        header("长按加速");
        card();
        addSwitchRow("长按加速", "播放时长按屏幕进入变速，上下滑动调速", HawkConfig.GESTURE_SPEED_BOOST, true, null);
        addSelectRow("最高倍速", "长按加速可达到的最高倍速", "请选择最高倍速",
                new String[]{"2.0X", "3.0X", "4.0X"},
                new IntProvider() {
                    @Override
                    public int get() {
                        float max = Hawk.get(HawkConfig.GESTURE_MAX_SPEED, 3.0f);
                        if (max <= 2.0f) return 0;
                        if (max >= 4.0f) return 2;
                        return 1;
                    }
                },
                new IntAction() {
                    @Override
                    public void run(int value) {
                        Hawk.put(HawkConfig.GESTURE_MAX_SPEED, value == 0 ? 2.0f : (value == 2 ? 4.0f : 3.0f));
                    }
                });

        header("双击");
        card();
        addSwitchRow("双击中间暂停", "关闭后中间区域双击不再暂停", HawkConfig.GESTURE_DOUBLE_TAP_PLAY, true, null);
        addSelectRow("双击快进/快退", "左右两侧双击的快进快退秒数", "请选择快进快退秒数",
                new String[]{"3 秒", "5 秒", "10 秒"},
                new IntProvider() {
                    @Override
                    public int get() {
                        int ms = Hawk.get(HawkConfig.GESTURE_DOUBLE_TAP_SEEK, 3000);
                        if (ms <= 3000) return 0;
                        if (ms >= 10000) return 2;
                        return 1;
                    }
                },
                new IntAction() {
                    @Override
                    public void run(int value) {
                        Hawk.put(HawkConfig.GESTURE_DOUBLE_TAP_SEEK, value == 0 ? 3000 : (value == 2 ? 10000 : 5000));
                    }
                });

        header("滑动");
        card();
        addSwitchRow("竖屏上下滑切集", "竖屏播放时上下滑动切换上一集/下一集",
                HawkConfig.GESTURE_PORTRAIT_SWIPE, true, null);
    }

    /* ============================ 弹幕设定 ============================ */

    private void buildDanmu() {
        header("弹幕");
        card();
        addSwitchRow("弹幕开关", "关闭后不再加载与显示弹幕", HawkConfig.DANMU_OPEN, true, new Runnable() {
            @Override
            public void run() {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SET_DANMU_SETTINGS,
                        DanmuHelper.isOpen()));
            }
        });
        addValueRow("弹幕接口", "支持自定义弹幕搜索接口", new TextProvider() {
            @Override
            public String get() {
                String api = DanmakuApi.getDisplayApiUrl();
                return TextUtils.isEmpty(api) ? "内置默认" : api;
            }
        }, new Action() {
            @Override
            public void run() {
                DanmuApiDialog dialog = new DanmuApiDialog(mContext);
                dialog.setOnListener(new DanmuApiDialog.OnListener() {
                    @Override
                    public void onChange(String api) {
                        refreshRows();
                    }
                });
                dialog.show();
            }
        });
        addActionRow("测试接口", "使用当前接口搜索一次示例影片", new Action() {
            @Override
            public void run() {
                testDanmuApi();
            }
        });

        header("显示效果");
        card();
        addSelectRow("最大行数", "同屏最多显示的弹幕行数", "请选择弹幕最大行数",
                lineNames(),
                new IntProvider() {
                    @Override
                    public int get() {
                        return Math.max(0, DanmuHelper.getMaxLine() - 1);
                    }
                },
                new IntAction() {
                    @Override
                    public void run(int value) {
                        DanmuHelper.setMaxLine(value + 1);
                        postDanmuSettings();
                    }
                });
        addSelectRow("弹幕速度", "弹幕滚动速度", "请选择弹幕速度",
                new String[]{"超慢", "慢", "适中", "快"},
                new IntProvider() {
                    @Override
                    public int get() {
                        float speed = DanmuHelper.getSpeed();
                        if (speed >= 2.4f) return 0;
                        if (speed >= 1.8f) return 1;
                        if (speed >= 1.5f) return 2;
                        return 3;
                    }
                },
                new IntAction() {
                    @Override
                    public void run(int value) {
                        float[] speeds = {2.4f, 1.8f, 1.5f, 1.0f};
                        DanmuHelper.setSpeed(speeds[Math.max(0, Math.min(value, speeds.length - 1))]);
                        postDanmuSettings();
                    }
                });
        addSelectRow("透明度", "弹幕文字透明度", "请选择弹幕透明度",
                alphaNames(),
                new IntProvider() {
                    @Override
                    public int get() {
                        int percent = Math.round(DanmuHelper.getAlpha() * 100);
                        int idx = Math.round((percent - 10) / 10f);
                        return Math.max(0, Math.min(idx, 9));
                    }
                },
                new IntAction() {
                    @Override
                    public void run(int value) {
                        DanmuHelper.setAlpha((value * 10 + 10) / 100f);
                        postDanmuSettings();
                    }
                });
        addSelectRow("字号", "弹幕文字缩放比例", "请选择弹幕字号",
                sizeNames(),
                new IntProvider() {
                    @Override
                    public int get() {
                        int scale = Math.round(DanmuHelper.getSizeScale() * 10);
                        return Math.max(0, Math.min(scale - 6, 14));
                    }
                },
                new IntAction() {
                    @Override
                    public void run(int value) {
                        DanmuHelper.setSizeScale((value + 6) / 10f);
                        postDanmuSettings();
                    }
                });
        addSwitchRow("随机颜色", "关闭后弹幕统一使用白色", HawkConfig.DANMU_RANDOM_COLOR, false, new Runnable() {
            @Override
            public void run() {
                postDanmuSettings();
            }
        });
    }

    private String[] lineNames() {
        String[] names = new String[15];
        for (int i = 0; i < names.length; i++) {
            names[i] = (i + 1) + " 行";
        }
        return names;
    }

    private String[] alphaNames() {
        String[] names = new String[10];
        for (int i = 0; i < names.length; i++) {
            names[i] = (i * 10 + 10) + "%";
        }
        return names;
    }

    private String[] sizeNames() {
        String[] names = new String[15];
        for (int i = 0; i < names.length; i++) {
            names[i] = String.format(Locale.getDefault(), "%.1f 倍", (i + 6) / 10f);
        }
        return names;
    }

    private void postDanmuSettings() {
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SET_DANMU_SETTINGS, false));
    }

    private void testDanmuApi() {
        // 未自定义时 getApiUrl() 会回退到内置接口，这里不需要额外校验
        Toast.makeText(this, "正在测试弹幕接口…", Toast.LENGTH_SHORT).show();
        DanmakuApi.searchList("庆余年", "1", new DanmakuApi.SearchListCallback() {
            @Override
            public void onSuccess(List<DanmuSearchResult> results) {
                int count = results == null ? 0 : results.size();
                if (count > 0) {
                    Toast.makeText(mContext, "接口正常，匹配到 " + count + " 条弹幕", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(mContext, "接口可访问，但没有匹配到弹幕", Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onError(String message) {
                Toast.makeText(mContext, "接口异常：" + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /* ============================ 关于 ============================ */

    private void buildAbout() {
        header("应用信息");
        card();
        addInfoRow("应用名称", new TextProvider() {
            @Override
            public String get() {
                return "TVBoxOS（手机竖屏版）";
            }
        });
        addInfoRow("版本号", new TextProvider() {
            @Override
            public String get() {
                return BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")";
            }
        });
        addInfoRow("包名", new TextProvider() {
            @Override
            public String get() {
                return getPackageName();
            }
        });
        addInfoRow("构建类型", new TextProvider() {
            @Override
            public String get() {
                return BuildConfig.BUILD_TYPE;
            }
        });

        header("数据来源");
        card();
        addInfoRow("首页数据源", new TextProvider() {
            @Override
            public String get() {
                SourceBean bean = ApiConfig.get().getHomeSourceBean();
                return bean == null ? "未加载" : bean.getName();
            }
        });
        addInfoRow("配置地址", new TextProvider() {
            @Override
            public String get() {
                String api = Hawk.get(HawkConfig.API_URL, "");
                return TextUtils.isEmpty(api) ? "未配置" : api;
            }
        });
        addInfoRow("直播配置", new TextProvider() {
            @Override
            public String get() {
                String api = Hawk.get(HawkConfig.LIVE_API_URL, "");
                return TextUtils.isEmpty(api) ? "未配置" : api;
            }
        });
        addInfoRow("弹幕接口", new TextProvider() {
            @Override
            public String get() {
                String api = DanmakuApi.getDisplayApiUrl();
                return TextUtils.isEmpty(api) ? "内置默认" : api;
            }
        });

        header("运行环境");
        card();
        addInfoRow("设备", new TextProvider() {
            @Override
            public String get() {
                return Build.MANUFACTURER + " " + Build.MODEL;
            }
        });
        addInfoRow("系统版本", new TextProvider() {
            @Override
            public String get() {
                return "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
            }
        });
        addInfoRow("项目来源", new TextProvider() {
            @Override
            public String get() {
                return "基于 TVBoxOS 二次开发，针对手机竖屏重做详情页与播放器交互";
            }
        });
    }

    /* ============================ 通用 UI 构建 ============================ */

    private void header(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(getResources().getColor(R.color.ui_muted));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.ts_16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(4);
        lp.topMargin = dp(contentContainer.getChildCount() == 0 ? 6 : 18);
        lp.bottomMargin = dp(2);
        contentContainer.addView(tv, lp);
        currentCard = null;
    }

    private void card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_phone_card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        contentContainer.addView(card, lp);
        currentCard = card;
    }

    private View makeRow(String title, String sub) {
        if (currentCard == null) card();
        View row = LayoutInflater.from(this).inflate(R.layout.item_mine_setting_row, currentCard, false);
        ((TextView) row.findViewById(R.id.tvRowTitle)).setText(title);
        TextView tvSub = row.findViewById(R.id.tvRowSub);
        if (TextUtils.isEmpty(sub)) {
            tvSub.setVisibility(View.GONE);
        } else {
            tvSub.setVisibility(View.VISIBLE);
            tvSub.setText(sub);
        }
        return row;
    }

    private void attachRow(View row) {
        if (currentCard.getChildCount() > 0) {
            View divider = new View(this);
            divider.setBackgroundColor(getResources().getColor(R.color.ui_divider));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f)));
            dlp.leftMargin = dp(12);
            dlp.rightMargin = dp(12);
            currentCard.addView(divider, dlp);
        }
        currentCard.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addActionRow(String title, String sub, final Action action) {
        View row = makeRow(title, sub);
        row.findViewById(R.id.tvRowValue).setVisibility(View.GONE);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                action.run();
                contentContainer.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        refreshRows();
                    }
                }, 150);
            }
        });
        attachRow(row);
    }

    private void addValueRow(String title, String sub, final TextProvider provider, final Action onClick) {
        View row = makeRow(title, sub);
        final TextView tvValue = row.findViewById(R.id.tvRowValue);
        Runnable refresher = new Runnable() {
            @Override
            public void run() {
                String value = provider.get();
                tvValue.setText(value == null ? "" : value);
            }
        };
        refreshers.add(refresher);
        refresher.run();
        if (onClick == null) {
            row.setClickable(false);
            row.findViewById(R.id.tvRowArrow).setVisibility(View.GONE);
        } else {
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FastClickCheckUtil.check(v);
                    onClick.run();
                    contentContainer.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            refreshRows();
                        }
                    }, 150);
                }
            });
        }
        attachRow(row);
    }

    /** 只读信息行：值放在标题下方的副标题位置，长文本可换行显示 */
    private void addInfoRow(String title, final TextProvider provider) {
        View row = makeRow(title, null);
        final TextView tvSub = row.findViewById(R.id.tvRowSub);
        row.findViewById(R.id.tvRowValue).setVisibility(View.GONE);
        row.findViewById(R.id.tvRowArrow).setVisibility(View.GONE);
        row.setClickable(false);
        Runnable refresher = new Runnable() {
            @Override
            public void run() {
                String value = provider.get();
                tvSub.setVisibility(View.VISIBLE);
                tvSub.setText(value == null ? "" : value);
            }
        };
        refreshers.add(refresher);
        refresher.run();
        attachRow(row);
    }

    private void addSwitchRow(String title, String sub, final String key, final boolean def, final Runnable after) {
        View row = makeRow(title, sub);
        final TextView tvValue = row.findViewById(R.id.tvRowValue);
        row.findViewById(R.id.tvRowArrow).setVisibility(View.GONE);
        Runnable refresher = new Runnable() {
            @Override
            public void run() {
                Object value = Hawk.get(key, def);
                boolean on = value instanceof Boolean ? (Boolean) value : def;
                tvValue.setText(on ? "开启" : "关闭");
            }
        };
        refreshers.add(refresher);
        refresher.run();
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                Object value = Hawk.get(key, def);
                boolean on = value instanceof Boolean ? (Boolean) value : def;
                Hawk.put(key, !on);
                refresher.run();
                if (after != null) after.run();
            }
        });
        attachRow(row);
    }

    private void addSelectRow(String title, String sub, final String tip, final String[] items,
                              final IntProvider current, final IntAction onPick) {
        addValueRow(title, sub, new TextProvider() {
            @Override
            public String get() {
                int idx = current.get();
                if (idx < 0 || idx >= items.length) return items.length > 0 ? items[0] : "";
                return items[idx];
            }
        }, new Action() {
            @Override
            public void run() {
                showPick(tip, items, current.get(), onPick);
            }
        });
    }

    private void showPick(String tip, String[] items, int selected, final IntAction onPick) {
        List<String> data = new ArrayList<>();
        for (String item : items) data.add(item);
        if (data.isEmpty()) return;
        if (selected < 0 || selected >= data.size()) selected = 0;
        SelectDialog<String> dialog = new SelectDialog<>(this);
        dialog.setTip(tip);
        dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<String>() {
            @Override
            public void click(String value, int pos) {
                onPick.run(pos);
                // 选项在弹窗里被点选，此刻才拿到新值，必须在这里回刷一次
                refreshRows();
                dialog.dismiss();
            }

            @Override
            public String getDisplay(String val) {
                return val;
            }
        }, new DiffUtil.ItemCallback<String>() {
            @Override
            public boolean areItemsTheSame(@NonNull @NotNull String oldItem, @NonNull @NotNull String newItem) {
                return TextUtils.equals(oldItem, newItem);
            }

            @Override
            public boolean areContentsTheSame(@NonNull @NotNull String oldItem, @NonNull @NotNull String newItem) {
                return TextUtils.equals(oldItem, newItem);
            }
        }, data, selected);
        dialog.show();
    }

    private void refreshRows() {
        for (Runnable refresher : refreshers) {
            refresher.run();
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
