package com.github.tvbox.osc.ui.activity;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.github.tvbox.osc.BuildConfig;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.api.DanmakuApi;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.DanmuSearchResult;
import com.github.tvbox.osc.bean.GithubRelease;
import com.github.tvbox.osc.bean.IJKCode;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.ui.dialog.DanmuApiDialog;
import com.github.tvbox.osc.ui.dialog.NumberInputDialog;
import com.github.tvbox.osc.ui.dialog.TipDialog;
import com.github.tvbox.osc.ui.dialog.UpdateDialog;
import com.github.tvbox.osc.ui.dialog.XWalkInitDialog;
import com.github.tvbox.osc.ui.widget.GlassGroupView;
import com.github.tvbox.osc.ui.widget.SpringSwitch;
import com.github.tvbox.osc.util.BackupUtil;
import com.github.tvbox.osc.util.DanmuHelper;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HistoryHelper;
import com.github.tvbox.osc.util.MotionCompat;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.ThemeUtil;
import com.github.tvbox.osc.util.UpdateChecker;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.FileCallback;
import com.lzy.okgo.model.Progress;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;

import org.greenrobot.eventbus.EventBus;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
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
    public static final String SECTION_BACKUP = "backup";
    public static final String SECTION_ABOUT = "about";

    private static final int REQUEST_PICK_WALLPAPER = 4001;
    private static final int REQUEST_BACKUP_EXPORT = 4002;
    private static final int REQUEST_BACKUP_IMPORT = 4003;

    private String section;
    private LinearLayout contentContainer;
    private LinearLayout currentCard;
    private final List<Runnable> refreshers = new ArrayList<>();

    /** 「关于 → 检查更新」那一行右侧的状态文字：空表示还没查过 */
    private String updateStatus = "";
    private boolean checkingUpdate;
    private TipDialog updateErrorTip;

    private interface Action {
        void run();
    }

    /** 带「来源行」的 Action：选择类交互需要知道是从哪一行下拉的 */
    private interface ViewAction {
        void run(View row);
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
        // 分组卡片入场：轻微上浮 + 淡入，错开 40ms（尊重系统「移除动画」）
        if (!sceneIn) {
            sceneIn = true;
            MotionCompat.staggerEnter(contentContainer);
        }
    }

    private boolean sceneIn;

    @Override
    public void finish() {
        // 共享轴返回转场：从二级页回「我的」，反向滑动
        applyMineBackTransition();
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
            case SECTION_BACKUP:
                return "备份与同步";
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
            case SECTION_BACKUP:
                buildBackup();
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
        }, true);

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
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        switch (requestCode) {
            case REQUEST_PICK_WALLPAPER:
                saveWallpaperFromUri(uri);
                break;
            case REQUEST_BACKUP_EXPORT:
                writeBackupToUri(uri);
                break;
            case REQUEST_BACKUP_IMPORT:
                restoreBackupFromUri(uri);
                break;
            default:
                break;
        }
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
        addSelectRow("画中画模式", "系统：稳定省电；自定义：可自由调整尺寸（需悬浮窗权限）", "请选择画中画模式",
                new String[]{
                        PlayerHelper.getPipModeName(HawkConfig.PIP_MODE_SYSTEM),
                        PlayerHelper.getPipModeName(HawkConfig.PIP_MODE_OVERLAY),
                        PlayerHelper.getPipModeName(HawkConfig.PIP_MODE_OFF)},
                new IntProvider() {
                    @Override
                    public int get() {
                        return Hawk.get(HawkConfig.PIP_MODE, HawkConfig.PIP_MODE_SYSTEM);
                    }
                },
                new IntAction() {
                    @Override
                    public void run(int value) {
                        Hawk.put(HawkConfig.PIP_MODE, value);
                    }
                });
        addSelectRow("小窗比例锁", "锁住自定义小窗的宽高比，避免画面拉伸", "请选择小窗比例锁定",
                new String[]{
                        PlayerHelper.getPipRatioLockName(HawkConfig.PIP_RATIO_FREE),
                        PlayerHelper.getPipRatioLockName(HawkConfig.PIP_RATIO_16_9),
                        PlayerHelper.getPipRatioLockName(HawkConfig.PIP_RATIO_VIDEO)},
                new IntProvider() {
                    @Override
                    public int get() {
                        return Hawk.get(HawkConfig.PIP_RATIO_LOCK, HawkConfig.PIP_RATIO_FREE);
                    }
                },
                new IntAction() {
                    @Override
                    public void run(int value) {
                        Hawk.put(HawkConfig.PIP_RATIO_LOCK, value);
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

        header("高级");
        card();
        buildAdvancedCard();
    }

    private LinearLayout advancedPanel;
    private TextView advancedChevron;
    private SpringAnimation advancedHeightAnim;

    /** 「高级」分组里一个默认收起的抽屉：调试开关这类的低频选项藏进去，点行弹簧展开 */
    private void buildAdvancedCard() {
        if (currentCard == null) card();
        boolean open = Hawk.get(HawkConfig.UI_ADVANCED, false);

        View expander = makeRow("高级选项", "调试开关、嗅探内核等低频选项");
        advancedChevron = (TextView) expander.findViewById(R.id.tvRowArrow);
        advancedChevron.setRotation(open ? 90f : 0f);
        expander.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                toggleAdvanced();
            }
        });
        MotionCompat.attachPressScale(expander);
        attachRow(expander);

        // 抽屉容器放在展开行的下一格里，默认 0 高度；展开/收起时对高度做弹簧动画，
        // 行内容随容器淡入淡出。玻璃卡片 clipToOutline 保证不越出 26dp 圆角。
        advancedPanel = new LinearLayout(this);
        advancedPanel.setOrientation(LinearLayout.VERTICAL);
        advancedPanel.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                open ? LinearLayout.LayoutParams.WRAP_CONTENT : 0));

        LinearLayout prev = currentCard;
        currentCard = advancedPanel;
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
        addSwitchRow("调试模式", "显示播放器调试信息", HawkConfig.DEBUG_OPEN, false, null);
        currentCard = prev;

        currentCard.addView(advancedPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                open ? ViewGroup.LayoutParams.WRAP_CONTENT : 0));
    }

    private void toggleAdvanced() {
        boolean open = Hawk.get(HawkConfig.UI_ADVANCED, false);
        boolean next = !open;
        Hawk.put(HawkConfig.UI_ADVANCED, next);
        animateAdvanced(next);
    }

    private void animateAdvanced(final boolean expand) {
        if (advancedPanel == null || advancedChevron == null) return;
        final LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) advancedPanel.getLayoutParams();
        if (MotionCompat.reducedMotion(this)) {
            // 「移除动画」：直接置位，无弹簧
            lp.height = expand ? ViewGroup.LayoutParams.WRAP_CONTENT : 0;
            advancedPanel.setLayoutParams(lp);
            advancedPanel.setAlpha(1f);
            advancedChevron.setRotation(expand ? 90f : 0f);
            return;
        }
        // 起点：展开态参数是 WRAP_CONTENT，要用实测像素值
        int from = lp.height == ViewGroup.LayoutParams.WRAP_CONTENT
                ? advancedPanel.getHeight() : Math.max(0, lp.height);
        final int target = expand ? advancedNaturalHeight() : 0;
        if (advancedHeightAnim != null) {
            advancedHeightAnim.cancel(); // 连续点击时先停掉上一次的弹簧
        }

        SpringAnimation heightAnim = new SpringAnimation(new FloatValueHolder(from));
        heightAnim.setSpring(new SpringForce()
                .setStiffness(MotionCompat.SPRING_STIFFNESS)
                .setDampingRatio(MotionCompat.SPRING_DAMPING));
        heightAnim.addUpdateListener(new DynamicAnimation.OnAnimationUpdateListener() {
            @Override
            public void onAnimationUpdate(DynamicAnimation animation, float value, float velocity) {
                lp.height = Math.round(value);
                advancedPanel.setLayoutParams(lp);
            }
        });
        heightAnim.addEndListener(new DynamicAnimation.OnAnimationEndListener() {
            @Override
            public void onAnimationEnd(DynamicAnimation animation, boolean canceled, float value, float velocity) {
                if (expand) {
                    // 结束后交还给 WRAP_CONTENT，用户在弹窗里改系统字号后仍能正确自适应
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                } else {
                    lp.height = 0;
                }
                advancedPanel.setLayoutParams(lp);
            }
        });
        heightAnim.animateToFinalPosition(target);
        advancedHeightAnim = heightAnim;

        // 内容淡入/淡出
        SpringAnimation fade = new SpringAnimation(advancedPanel, DynamicAnimation.ALPHA);
        fade.setSpring(new SpringForce()
                .setStiffness(MotionCompat.SPRING_STIFFNESS)
                .setDampingRatio(MotionCompat.SPRING_DAMPING));
        fade.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_ALPHA);
        fade.animateToFinalPosition(expand ? 1f : 0f);

        // 箭头旋转 90°
        SpringAnimation rotate = new SpringAnimation(advancedChevron, DynamicAnimation.ROTATION);
        rotate.setSpring(new SpringForce()
                .setStiffness(MotionCompat.SPRING_STIFFNESS)
                .setDampingRatio(MotionCompat.SPRING_DAMPING));
        // dynamicanimation 1.0.0 没有 MIN_VISIBLE_CHANGE_ROTATION，
        // 收敛阈值用 0.1°（旋转一圈结束的判定足够平滑）
        rotate.setMinimumVisibleChange(0.1f);
        rotate.animateToFinalPosition(expand ? 90f : 0f);
    }

    /** 展开态的目标高度：按当前真实宽度重新测一次自然高度（副文字可能换行） */
    private int advancedNaturalHeight() {
        int width = advancedPanel.getMeasuredWidth();
        if (width <= 0) width = advancedPanel.getWidth();
        if (width <= 0) return 0;
        advancedPanel.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return advancedPanel.getMeasuredHeight();
    }

    /* ============================ 播放手势 ============================ */

    private void buildGesture() {
        header("长按加速");
        card();
        addSwitchRow("长按加速", "播放时长按屏幕进入变速，左右滑动按 0.2 调速（左减右加）", HawkConfig.GESTURE_SPEED_BOOST, true, null);
        addSwitchRow("锁定时可加速", "控制条锁定后仍可长按加速；退出键始终不受锁定影响",
                HawkConfig.GESTURE_SPEED_BOOST_LOCKED, true, null);
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
        addValueRow("双击快进/快退", "左右两侧双击的快进快退秒数，设为 0 即关闭", new TextProvider() {
            @Override
            public String get() {
                int ms = Hawk.get(HawkConfig.GESTURE_DOUBLE_TAP_SEEK, 3000);
                if (ms <= 0) return "关闭";
                return Math.round(ms / 1000f) + " 秒";
            }
        }, new ViewAction() {
            @Override
            public void run(View row) {
                pickDoubleTapSeek(row);
            }
        });

        header("滑动");
        card();
        addSwitchRow("竖屏上下滑切集", "竖屏播放时上下滑动切换上一集/下一集",
                HawkConfig.GESTURE_PORTRAIT_SWIPE, true, null);
    }

    /** 双击快进快退秒数：常用档位 + 自定义，0 表示关闭该手势 */
    private void pickDoubleTapSeek(View anchor) {
        final String[] items = {"关闭", "3 秒", "5 秒", "10 秒", "自定义…"};
        final int ms = Hawk.get(HawkConfig.GESTURE_DOUBLE_TAP_SEEK, 3000);
        int selected;
        if (ms <= 0) selected = 0;
        else if (ms == 3000) selected = 1;
        else if (ms == 5000) selected = 2;
        else if (ms == 10000) selected = 3;
        else selected = 4;
        showPick(anchor, "请选择快进快退秒数", items, selected, new IntAction() {
            @Override
            public void run(int value) {
                switch (value) {
                    case 0:
                        Hawk.put(HawkConfig.GESTURE_DOUBLE_TAP_SEEK, 0);
                        break;
                    case 1:
                        Hawk.put(HawkConfig.GESTURE_DOUBLE_TAP_SEEK, 3000);
                        break;
                    case 2:
                        Hawk.put(HawkConfig.GESTURE_DOUBLE_TAP_SEEK, 5000);
                        break;
                    case 3:
                        Hawk.put(HawkConfig.GESTURE_DOUBLE_TAP_SEEK, 10000);
                        break;
                    default:
                        // 等选择弹窗关掉之后再弹输入框，避免两个弹窗叠在一起
                        contentContainer.post(new Runnable() {
                            @Override
                            public void run() {
                                showDoubleTapSeekInput(ms);
                            }
                        });
                        break;
                }
            }
        });
    }

    private void showDoubleTapSeekInput(int currentMs) {
        int seconds = currentMs > 0 ? Math.round(currentMs / 1000f) : 5;
        NumberInputDialog dialog = new NumberInputDialog(this, "双击快进快退秒数",
                "可填 1 ~ 60 秒；填 0 表示关闭该手势", seconds, 0, 60);
        dialog.setOnConfirm(new NumberInputDialog.OnConfirm() {
            @Override
            public void onConfirm(int value) {
                Hawk.put(HawkConfig.GESTURE_DOUBLE_TAP_SEEK, value * 1000);
                refreshRows();
            }
        });
        dialog.show();
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

    /* ============================ 备份与同步 ============================ */

    /**
     * 备份 = 用户用系统文件选择器自选保存位置，把配置快照导出成 JSON；
     * 同步 = 在另一台设备（或重装后）选择备份文件读回，覆盖现有同名配置。
     */
    private void buildBackup() {
        header("备份");
        card();
        addInfoRow("备份范围", new TextProvider() {
            @Override
            public String get() {
                return "数据源配置 · 外观主题 · 播放与字幕 · 直播 · 弹幕 · 播放手势 · 短剧源。\n"
                        + "观看记录与收藏等媒体数据不包含在内。";
            }
        });
        addActionRow("备份配置", "弹出系统文件选择器，自行选择保存位置与文件名", new Action() {
            @Override
            public void run() {
                startBackupExport();
            }
        });

        header("同步");
        card();
        addActionRow("从备份导入", "选择之前导出的备份文件，同一项设置将被覆盖", new Action() {
            @Override
            public void run() {
                startBackupImport();
            }
        });
    }

    private void startBackupExport() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            Toast.makeText(this, "当前系统版本太低，不支持选择保存位置", Toast.LENGTH_SHORT).show();
            return;
        }
        Calendar c = Calendar.getInstance();
        String name = String.format(Locale.getDefault(),
                "tvboxos_backup_%04d%02d%02d_%02d%02d.json",
                c.get(Calendar.YEAR),
                c.get(Calendar.MONTH) + 1, // MONTH 从 0 起，显示用要 +1
                c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(Intent.createChooser(intent, "选择备份的保存位置"),
                    REQUEST_BACKUP_EXPORT);
        } catch (Throwable th) {
            Toast.makeText(this, "没有找到可用的文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    private void startBackupImport() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            Toast.makeText(this, "当前系统版本太低，不支持选择备份文件", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(Intent.createChooser(intent, "选择备份文件"),
                    REQUEST_BACKUP_IMPORT);
        } catch (Throwable th) {
            Toast.makeText(this, "没有找到可用的文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    private void writeBackupToUri(Uri uri) {
        OutputStream output = null;
        try {
            byte[] data = BackupUtil.buildJson().getBytes(StandardCharsets.UTF_8);
            output = getContentResolver().openOutputStream(uri);
            if (output == null) {
                Toast.makeText(this, "保存位置无法写入", Toast.LENGTH_SHORT).show();
                return;
            }
            output.write(data);
            output.flush();
            Toast.makeText(this, "备份成功（" + data.length / 1024 + " KB）", Toast.LENGTH_SHORT).show();
        } catch (Throwable th) {
            Toast.makeText(this, "备份失败：" + th.getMessage(), Toast.LENGTH_SHORT).show();
        } finally {
            try {
                if (output != null) output.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void restoreBackupFromUri(Uri uri) {
        BufferedReader reader = null;
        try {
            InputStream input = getContentResolver().openInputStream(uri);
            if (input == null) {
                Toast.makeText(this, "读取备份失败", Toast.LENGTH_SHORT).show();
                return;
            }
            StringBuilder sb = new StringBuilder();
            reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            BackupUtil.RestoreResult result = BackupUtil.restore(sb.toString());
            // 即时生效的类型：主题 / 播放内核 / 弹幕事件 / 纯色背景
            if (result.uiModeChanged) {
                ThemeUtil.setMode(Hawk.get(HawkConfig.UI_MODE, ThemeUtil.getMode()));
            }
            if (result.playerChanged) {
                PlayerHelper.init();
            }
            if (result.danmuChanged) {
                postDanmuSettings();
            }
            if (result.wallpaperChanged) {
                applyWallpaperNow();
            }
            refreshRows();
            Toast.makeText(this, "已同步 " + result.count + " 项配置"
                    + (isApiConfigIncluded(sb.toString()) ? "；数据源配置重启应用后生效" : ""),
                    Toast.LENGTH_LONG).show();
        } catch (org.json.JSONException je) {
            Toast.makeText(this, "这不是有效的备份文件", Toast.LENGTH_SHORT).show();
        } catch (Throwable th) {
            Toast.makeText(this, "读取备份失败：" + th.getMessage(), Toast.LENGTH_SHORT).show();
        } finally {
            try {
                if (reader != null) reader.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 是否在备份里带上了数据源配置（提示重启生效用） */
    private boolean isApiConfigIncluded(String json) {
        try {
            JSONArray entries = new JSONObject(json).optJSONArray("entries");
            if (entries == null) return false;
            for (int i = 0; i < entries.length(); i++) {
                JSONObject e = entries.optJSONObject(i);
                if (e != null && HawkConfig.API_URL.equals(e.optString("k", ""))) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
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

        header("版本更新");
        card();
        addValueRow("检查更新", "从 GitHub Releases 获取最新版本", new TextProvider() {
            @Override
            public String get() {
                return TextUtils.isEmpty(updateStatus) ? BuildConfig.VERSION_NAME : updateStatus;
            }
        }, new Action() {
            @Override
            public void run() {
                checkUpdateManually();
            }
        });
        addSwitchRow("启动时自动检查", "每天最多一次，只有发现新版本才会提示",
                HawkConfig.UPDATE_AUTO_CHECK, true, null);

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
                return "基于 TVBoxOS 二次开发，针对手机竖屏重做详情页与播放器交互\n" +
                        "https://github.com/q215613905/TVBoxOS\n" +
                        "https://github.com/Gladglod/MBox";
            }
        });
    }

    /* ------------------------- 检查更新 ------------------------- */

    /**
     * 手动检查：行右侧先显示「检查中…」，结果回填到同一行；
     * 有新版本直接弹更新弹窗，没有就 Toast 一句，失败给「浏览器打开」的兜底。
     */
    private void checkUpdateManually() {
        if (checkingUpdate) {
            Toast.makeText(this, "正在检查，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        checkingUpdate = true;
        updateStatus = "检查中…";
        refreshRows();
        UpdateChecker.check(true, new UpdateChecker.CheckCallback() {
            @Override
            public void onNewVersion(GithubRelease release, boolean manual) {
                checkingUpdate = false;
                updateStatus = "发现 " + release.tagName;
                refreshRows();
                if (isFinishing()) return;
                new UpdateDialog(MineSettingActivity.this, release, true).show();
            }

            @Override
            public void onLatest(boolean manual) {
                checkingUpdate = false;
                updateStatus = "已是最新";
                refreshRows();
                Toast.makeText(MineSettingActivity.this, "当前已是最新版本", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String msg, boolean manual) {
                checkingUpdate = false;
                updateStatus = "检查失败";
                refreshRows();
                if (isFinishing()) return;
                showUpdateErrorTip(msg);
            }
        });
    }

    private void showUpdateErrorTip(String msg) {
        // 弹窗要在自己的按钮回调里关掉自己，所以先挂到字段上（回调里引用不到局部变量）
        updateErrorTip = new TipDialog(this,
                msg + "\n也可以直接在浏览器里打开 Releases 页面手动查看。",
                "浏览器打开", "知道了", new TipDialog.OnListener() {
            @Override
            public void left() {
                dismissQuietly(updateErrorTip);
                UpdateChecker.openReleasePage(MineSettingActivity.this, null);
            }

            @Override
            public void right() {
                dismissQuietly(updateErrorTip);
            }

            @Override
            public void cancel() {
            }
        });
        updateErrorTip.show();
    }

    private void dismissQuietly(android.app.Dialog dialog) {
        if (dialog == null) return;
        try {
            if (dialog.isShowing()) dialog.dismiss();
        } catch (Throwable ignored) {
        }
    }

    /* ============================ 通用 UI 构建 ============================ */
    private void header(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        // 分组标题：小号、中等字重、灰色，浮在玻璃容器外部上方（iOS 设置页分组感）
        tv.setTextColor(getResources().getColor(R.color.ui_muted));
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.ts_14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(16); // 与玻璃卡片内行的左内边距（14dp）+ 一点边际对齐
        lp.topMargin = dp(contentContainer.getChildCount() == 0 ? 6 : 22);
        lp.bottomMargin = dp(6);
        contentContainer.addView(tv, lp);
        currentCard = null;
    }

    private void card() {
        // 玻璃分组容器：半透明填充 + 高光描边 + 26dp 大圆角；
        // Android 12+ 且背后有壁纸时自带 backdrop 预模糊，其余降级为半透明实色
        GlassGroupView card = new GlassGroupView(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        contentContainer.addView(card, lp);
        currentCard = card;
    }

    private LinearLayout makeRow(String title, String sub) {
        if (currentCard == null) card();
        LinearLayout row = (LinearLayout) LayoutInflater.from(this)
                .inflate(R.layout.item_mine_setting_row, currentCard, false);
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
            // 左右缩进对齐行内边距（14dp），Apple 分组列表的分隔线细节
            dlp.leftMargin = dp(14);
            dlp.rightMargin = dp(14);
            currentCard.addView(divider, dlp);
        }
        currentCard.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addActionRow(String title, String sub, final Action action) {
        addActionRow(title, sub, action, false);
    }

    /**
     * @param danger 危险项：文字换成红色强调，但行背景仍是普通行（不做红色玻璃，
     *               避免警报感过强）
     */
    private void addActionRow(String title, String sub, final Action action, boolean danger) {
        View row = makeRow(title, sub);
        row.findViewById(R.id.tvRowValue).setVisibility(View.GONE);
        if (danger) {
            // 保留普通行外观，只用文字颜色提示后果
            ((TextView) row.findViewById(R.id.tvRowTitle))
                    .setTextColor(getResources().getColor(R.color.ui_danger));
        }
        MotionCompat.attachPressScale(row);
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
            MotionCompat.attachPressScale(row);
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

    /** 需要「来源行」的值行变体：下拉菜单从这一行下面弹出 */
    private void addValueRow(String title, String sub, final TextProvider provider, final ViewAction onClick) {
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
            MotionCompat.attachPressScale(row);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FastClickCheckUtil.check(v);
                    onClick.run(v);
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
        // 信息行内容可能较长（如项目来源含网址），放开行数并关闭末尾省略号，避免网址被截断
        tvSub.setEllipsize(null);
        tvSub.setMaxLines(10);
        // 内容里的网址要能点开：悬挂 LinkMovementMethod，链接着色用品牌链接色
        tvSub.setMovementMethod(LinkMovementMethod.getInstance());
        tvSub.setLinkTextColor(getResources().getColor(R.color.ui_link));
        Runnable refresher = new Runnable() {
            @Override
            public void run() {
                String value = provider.get();
                tvSub.setVisibility(View.VISIBLE);
                tvSub.setText(value == null ? "" : value);
                // 每次回填都重新扫描一遍网址并转为可点链接
                Linkify.addLinks(tvSub, Linkify.WEB_URLS);
            }
        };
        refreshers.add(refresher);
        refresher.run();
        attachRow(row);
    }

    /** 开关行：右侧 SpringSwitch（品牌色选中态 + 弹簧滑块），行内任意位置可点 */
    private void addSwitchRow(final String title, String sub, final String key, final boolean def, final Runnable after) {
        LinearLayout row = makeRow(title, sub);
        row.findViewById(R.id.tvRowValue).setVisibility(View.GONE);
        row.findViewById(R.id.tvRowArrow).setVisibility(View.GONE);
        final SpringSwitch switchView = new SpringSwitch(this);
        switchView.setContentDescription(title);
        Runnable refresher = new Runnable() {
            @Override
            public void run() {
                Object value = Hawk.get(key, def);
                boolean on = value instanceof Boolean ? (Boolean) value : def;
                // 回填不做动画（无弹簧、无缩放脉冲），真实切换才有
                switchView.setChecked(on, false);
            }
        };
        refreshers.add(refresher);
        refresher.run();
        row.addView(switchView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        // 开关本体不可点，整行统一接管：视觉开关只展示状态
        MotionCompat.attachPressScale(row);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                boolean on = switchView.isChecked();
                Hawk.put(key, !on);
                // 真实切换：弹簧滑块 + 轻微脉冲缩放
                switchView.setChecked(!on);
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
        }, new ViewAction() {
            @Override
            public void run(View row) {
                showPick(row, tip, items, current.get(), onPick);
            }
        });
    }

    /**
     * 选择交互：行下方的下拉菜单（不再是居中弹窗）。
     *
     * <p>动效设计：PopupWindow 以 {@link com.github.tvbox.osc.R.style#MineDropdownPopup}
     * 进出场——菜单从行下方下落 12% + 淡入、上收 6% + 淡出；菜单内容在出现的一刻
     * 再叠加逐项 25ms 的淡入，形成「拉开一层帘」的层次。按压态用品牌色 10% 玻璃底
     * （bg_dropdown_item），不引入默认灰 ripple。</p>
     */
    private void showPick(final View anchor, String tip, final String[] items, int selected, final IntAction onPick) {
        if (items == null || items.length == 0) return;
        if (selected < 0 || selected >= items.length) selected = 0;
        // 面板宽度 = 屏幕宽的 1/3（下限 120dp 保证文字可读），右缘与来源行右缘对齐
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        final int width = Math.max(Math.round(screenWidth / 3.0f), dp(120));
        final int panelPadding = dp(6);
        final int itemHeight = dp(44);
        final float corner = dp(14);
        final boolean scrollable = items.length > 8;
        // 展开后的自然高度：给「放不下自动转到行上方打开」用
        final int naturalHeight = Math.min(
                items.length * itemHeight + panelPadding * 2 + (TextUtils.isEmpty(tip) ? 0 : dp(28)),
                dp(360));
        final int popupHeight = scrollable ? naturalHeight : ViewGroup.LayoutParams.WRAP_CONTENT;

        // 面板外框：菜单玻璃材质、14dp 圆角、6dp 内衬
        final FrameLayout panel = new FrameLayout(this);
        panel.setBackgroundResource(R.drawable.bg_dropdown_menu);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            panel.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), corner);
                }
            });
            panel.setClipToOutline(true);
        }
        panel.setPadding(panelPadding, panelPadding, panelPadding, panelPadding);

        final LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);

        // 提示语作为菜单页眉（可选），与选项之间留一道弱分隔
        if (!TextUtils.isEmpty(tip)) {
            TextView header = new TextView(this);
            header.setText(tip);
            header.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimensionPixelSize(R.dimen.ts_14));
            header.setTextColor(getResources().getColor(R.color.ui_muted));
            header.setPadding(dp(14), dp(4), dp(14), dp(6));
            menu.addView(header, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // 先建好 PopupWindow：菜单项的点击回调里要引用它
        final PopupWindow popup = new PopupWindow(panel, width, popupHeight, true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setAnimationStyle(R.style.MineDropdownPopup);

        for (int i = 0; i < items.length; i++) {
            final int index = i;
            final String item = items[i];
            LinearLayout itemRow = new LinearLayout(this);
            itemRow.setOrientation(LinearLayout.HORIZONTAL);
            itemRow.setGravity(Gravity.CENTER_VERTICAL);
            itemRow.setPadding(dp(14), 0, dp(14), 0);
            itemRow.setMinimumHeight(itemHeight);
            itemRow.setBackgroundResource(R.drawable.bg_dropdown_item);
            itemRow.setClickable(true);

            TextView text = new TextView(this);
            text.setText(item);
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimensionPixelSize(R.dimen.ts_18));
            text.setTextColor(getResources().getColor(
                    index == selected ? R.color.ui_accent : R.color.ui_text_primary));
            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            itemRow.addView(text, textLp);

            if (index == selected) {
                TextView check = new TextView(this);
                check.setText("✓");
                check.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.ts_18));
                check.setTextColor(getResources().getColor(R.color.ui_accent));
                itemRow.addView(check, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            itemRow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FastClickCheckUtil.check(v);
                    popup.dismiss();
                    onPick.run(index);
                    // 选项在菜单里被点选，此刻才拿到新值，必须在这里回刷一次
                    refreshRows();
                }
            });

            // 逐项淡入：25ms 递进的「帘子」层次
            itemRow.setAlpha(0f);
            final long itemDelay = i * 25L;
            menu.postDelayed(new Runnable() {
                @Override
                public void run() {
                    itemRow.animate().alpha(1f).setDuration(120).start();
                }
            }, itemDelay + 20);

            menu.addView(itemRow);
        }

        if (scrollable) {
            ScrollView scroller = new ScrollView(this);
            scroller.setVerticalScrollBarEnabled(false);
            panel.addView(scroller, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            scroller.addView(menu, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            panel.addView(menu, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // 右缘对齐来源行（PopupWindow 左缘相对 anchor 左缘偏移即可实现右对齐）
        final int shownWidth = width;
        final int rightAlignOffset = (anchor.getWidth() > shownWidth)
                ? anchor.getWidth() - shownWidth : 0;

        // 屏幕空间探测：行下方放不下完整菜单时，改为从行上方打开（菜单底缘贴行顶）
        // —— 修复「搜索展示 / 下次进入」这类行处于屏幕下部时菜单被截断的问题
        int[] anchorOnScreen = new int[2];
        anchor.getLocationOnScreen(anchorOnScreen);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int anchorBottom = anchorOnScreen[1] + anchor.getHeight();
        if (anchorBottom + dp(8) + naturalHeight <= screenH) {
            // 锚在行正下方；showAsDropDown 会自动在屏幕边缘内收
            popup.showAsDropDown(anchor, rightAlignOffset, dp(-4));
        } else {
            // showAtLocation + NO_GRAVITY 参照屏幕坐标：菜单底缘贴到行顶上方 4dp
            int leftPos = anchorOnScreen[0] + rightAlignOffset;
            int topPos = anchorOnScreen[1] - naturalHeight - dp(4);
            // 上方也放不下时贴顶展开（内部 ScrollView 保证可滚动）
            if (topPos < dp(16)) topPos = anchorOnScreen[1];
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, leftPos, topPos);
        }
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
