package com.github.tvbox.osc.ui.activity;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.text.TextUtils;
import android.view.WindowManager;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.cache.CacheManager;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.player.controller.LocalVideoController;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.PlayerHelper;

import xyz.doikki.videoplayer.player.ProgressManager;
import xyz.doikki.videoplayer.player.VideoView;

/**
 * 离线视频播放器：播放已缓存到本地的文件，支持同剧多集连播。
 * 控制条复用 BaseController 手势框架（滑动进度/亮度/音量、单击显隐、双击暂停），
 * 支持进度记忆、倍速、全屏切换、错误重试、播完自动下一集。
 * 播放器内核跟随用户「播放设定」的选择（IJK / EXO）。
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

    /** 倍速档位，循环切换 */
    private static final float[] SPEEDS = {1.0f, 1.25f, 1.5f, 2.0f, 0.5f};

    private MyVideoView mVideoView;
    private LocalVideoController mController;

    private String[] paths = new String[0];
    private String[] titles = new String[0];
    private int index = 0;
    private String currentPath;
    private int speedIndex = 0;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_local_play;
    }

    @Override
    protected void init() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mVideoView = findViewById(R.id.mVideoView);

        resolvePlaylist();

        mController = new LocalVideoController(this);
        mController.setCanChangePosition(true);
        mController.setEnableInNormal(true);
        mController.setGestureEnabled(true);
        mController.setListener(new LocalVideoController.LocalControlListener() {
            @Override
            public void onBack() {
                finish();
            }

            @Override
            public void onRetry() {
                startPlay(currentPath);
            }

            @Override
            public void onNext() {
                playNext();
            }

            @Override
            public void onSpeed() {
                switchSpeed();
            }

            @Override
            public void onToggleFullScreen() {
                toggleFullScreen();
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
        mVideoView.addOnStateChangeListener(new VideoView.SimpleOnStateChangeListener() {
            @Override
            public void onPlayStateChanged(int playState) {
                switch (playState) {
                    case VideoView.STATE_PREPARED:
                        int[] size = mVideoView.getVideoSize();
                        if (size != null && size[0] > 0 && size[1] > 0
                                && size[0] > size[1]
                                && getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                        }
                        break;
                    case VideoView.STATE_ERROR:
                        // 错误条由控制器展示，用户点击重试
                        break;
                    case VideoView.STATE_PLAYBACK_COMPLETED:
                        if (!playNext()) {
                            finish();
                        }
                        break;
                }
            }
        });

        if (paths.length == 0) {
            finish();
            return;
        }
        index = Math.max(0, Math.min(index, paths.length - 1));
        updateTitle();
        mController.setNextEnabled(paths.length > 1);
        startPlay(paths[index]);
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
        mVideoView.setSpeed(SPEEDS[speedIndex]);
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

    private void switchSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.length;
        mVideoView.setSpeed(SPEEDS[speedIndex]);
    }

    private void toggleFullScreen() {
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        setRequestedOrientation(landscape
                ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
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
