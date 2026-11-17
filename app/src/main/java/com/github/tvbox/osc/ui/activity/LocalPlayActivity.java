package com.github.tvbox.osc.ui.activity;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.cache.CacheManager;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.player.controller.VodController;
import com.github.tvbox.osc.util.GlassTip;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.PlayerHelper;
import com.orhanobut.hawk.Hawk;

import org.json.JSONObject;

import java.util.HashMap;

import xyz.doikki.videoplayer.player.ProgressManager;

/**
 * 离线视频播放器：播放已缓存到本地的文件，支持同剧多集连播。
 * 直接复用项目自带的 VodController（与在线播放同一套控制条：
 * 手势进度/亮度/音量、倍速、选集/上一集下一集、播放设定、错误重试等），
 * 内核跟随用户「播放设定」的选择（IJK / EXO / 渲染方式 / 画面比例）。
 *
 * @author MBox
 */
public class LocalPlayActivity extends BaseActivity {

    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_TITLE = "title";
    /** 多集连播：完整文件路径列表 */
    public static final String EXTRA_PATHS = "paths";
    /** 多集连播：对应标题列表 */
    public static final String EXTRA_TITLES = "titles";
    /** 多集连播：起始下标 */
    public static final String EXTRA_INDEX = "index";

    private MyVideoView mVideoView;
    private VodController mController;

    private String[] paths = new String[0];
    private String[] titles = new String[0];
    private int index = 0;
    private String currentPath;
    /** 自动重试次数（播放失败时最多静默重试 1 次，避免坏文件无限循环） */
    private int errorRetry = 0;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_local_play;
    }

    @Override
    protected void init() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // 沉浸式全屏 + 刘海区短边延伸：横屏时状态栏/导航栏隐藏、视频铺进刘海区，
        // 否则横屏播放上方会露出状态栏、刘海侧留一条黑边
        applyImmersive();
        mVideoView = findViewById(R.id.mVideoView);

        resolvePlaylist();
        if (paths.length == 0) {
            finish();
            return;
        }
        index = Math.max(0, Math.min(index, paths.length - 1));

        mController = new VodController(mContext);
        mController.setCanChangePosition(true);
        mController.setEnableInNormal(true);
        mController.setGestureEnabled(true);
        // 本地文件不需要「解析线路」入口
        mController.showParse(false);
        mController.setListener(new VodController.VodControlListener() {
            @Override
            public void playNext(boolean rmProgress) {
                if (!LocalPlayActivity.this.playNext()) {
                    finish();
                }
            }

            @Override
            public void playPre() {
                LocalPlayActivity.this.playPrevious();
            }

            @Override
            public void showEpisodeDialog() {
            }

            @Override
            public void prepared() {
                errorRetry = 0;
                rotateIfWideVideo();
            }

            @Override
            public void changeParse(ParseBean pb) {
            }

            @Override
            public void updatePlayerCfg() {
            }

            @Override
            public void replay(boolean replay) {
                guardedRetry();
            }

            @Override
            public void errReplay() {
                guardedRetry();
            }

            @Override
            public void selectSubtitle() {
            }

            @Override
            public void selectAudioTrack() {
            }

            @Override
            public void selectVideoTrack() {
            }

            @Override
            public void showDanmuSetting() {
            }

            @Override
            public boolean toggleDanmu() {
                return false;
            }

            @Override
            public void searchDanmuUi(boolean longClick) {
            }

            @Override
            public void startPlayUrl(String url, HashMap<String, String> headers) {
            }

            @Override
            public void onM3u8ProxyUrl(String proxyUrl, String sourceUrl) {
            }

            @Override
            public void clickCast() {
            }

            @Override
            public void setAllowSwitchPlayer(boolean isAllow) {
            }

            @Override
            public void enterFullScreen() {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            }

            @Override
            public void exitFullScreen() {
                // 独立播放页没有「详情页」可回：
                // 横屏全屏中 → 先转回竖屏；已在竖屏 → 退出本页回到缓存列表
                if (getRequestedOrientation() == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                } else {
                    finish();
                }
            }
        });
        mVideoView.setVideoController(mController);
        mVideoView.setProgressManager(new ProgressManager() {
            @Override
            public void saveProgress(String url, long progress) {
                CacheManager.save(MD5.string2MD5(url), progress);
            }

            @Override
            public long getSavedProgress(String url) {
                Object cache = CacheManager.getCache(MD5.string2MD5(url));
                if (cache instanceof Long) {
                    return (Long) cache;
                }
                if (cache instanceof Integer) {
                    return (Integer) cache;
                }
                return 0;
            }
        });

        // 播放器配置与在线播放一致（IJK/EXO、解码方式、渲染、画面比例、倍速等）
        JSONObject cfg = buildPlayerCfg();
        mController.setPlayerConfig(cfg);
        PlayerHelper.updateCfg(mVideoView, cfg);

        updateTitle();
        startPlay(paths[index]);
    }

    /** 与 PlayFragment.initPlayerCfg 对齐的播放器配置（本地播放无站点级覆盖，取全局设定） */
    private JSONObject buildPlayerCfg() {
        JSONObject cfg = new JSONObject();
        try {
            cfg.put("pl", (int) Hawk.get(HawkConfig.PLAY_TYPE, 2));
            cfg.put("pr", Hawk.get(HawkConfig.PLAY_RENDER, 0));
            cfg.put("ijk", Hawk.get(HawkConfig.IJK_CODEC, "硬解码"));
            cfg.put("sc", Hawk.get(HawkConfig.PLAY_SCALE, 0));
            cfg.put("sp", 1.0f);
            cfg.put("st", 0);
            cfg.put("et", 0);
        } catch (Throwable ignored) {
        }
        return cfg;
    }

    private void resolvePlaylist() {
        Intent intent = getIntent();
        String[] extraPaths = intent.getStringArrayExtra(EXTRA_PATHS);
        String[] extraTitles = intent.getStringArrayExtra(EXTRA_TITLES);
        String single = intent.getStringExtra(EXTRA_PATH);
        if (extraPaths != null && extraPaths.length > 0) {
            paths = extraPaths;
            titles = extraTitles == null ? new String[0] : extraTitles;
            index = intent.getIntExtra(EXTRA_INDEX, 0);
        } else if (!TextUtils.isEmpty(single)) {
            paths = new String[]{single};
            titles = new String[]{intent.getStringExtra(EXTRA_TITLE)};
            index = 0;
        }
    }

    private void updateTitle() {
        String title = index < titles.length ? titles[index] : null;
        if (TextUtils.isEmpty(title)) {
            title = "离线播放";
        }
        if (paths.length > 1 && index < titles.length) {
            title = title + "（" + (index + 1) + "/" + paths.length + "）";
        }
        mController.setTitle(title);
    }

    private void startPlay(String path) {
        if (TextUtils.isEmpty(path)) return;
        currentPath = path;
        mVideoView.release();
        mVideoView.setUrl("file://" + path);
        mVideoView.start();
    }

    private boolean playNext() {
        if (index + 1 < paths.length) {
            index++;
            updateTitle();
            startPlay(paths[index]);
            return true;
        }
        return false;
    }

    private boolean playPrevious() {
        if (index > 0) {
            index--;
            updateTitle();
            startPlay(paths[index]);
            return true;
        }
        return false;
    }

    /** 播放失败自动重试：最多静默重试 1 次，之后提示文件可能损坏，避免坏文件无限循环 */
    private void guardedRetry() {
        if (errorRetry < 1) {
            errorRetry++;
            startPlay(currentPath);
        } else {
            GlassTip.show(mContext, "文件无法播放，可能不完整或已损坏");
        }
    }

    /** 宽>高的本地视频自动进横屏 */
    private void rotateIfWideVideo() {
        int[] size = mVideoView.getVideoSize();
        if (size != null && size[0] > 0 && size[1] > 0
                && size[0] > size[1]
                && getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }
    }

    /** 沉浸式全屏：隐藏状态栏/导航栏，刘海屏 shortEdges 模式让画面铺满整屏 */
    private void applyImmersive() {
        Window window = getWindow();
        if (window == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(lp);
        }
        // 只用 SYSTEM_UI_FLAG_* 一套：targetSdk 28 下在 Android 11+ 依然有效（与 DetailActivity 同方案）
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 横竖屏切换 / 用户下拉唤出系统栏后，焦点回来时重新收起
        if (hasFocus) {
            applyImmersive();
        }
    }

    @Override
    public void onBackPressed() {
        if (mController != null && mController.onBackPressed()) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mVideoView.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mVideoView != null) mVideoView.release();
    }
}
