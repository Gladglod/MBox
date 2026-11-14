package com.github.tvbox.osc.player.controller;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.animation.Animation;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;

import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.util.PlayerUtils;

/**
 * 离线播放器的轻量控制条：
 * 顶栏（返回 / 标题 / 倍速 / 全屏）+ 底栏（播放暂停 / 进度 / 时间 / 下一集）+ 错误重试。
 * 手势（进度 / 亮度 / 音量 / 双击暂停）、单击显隐、加载圈、暂停遮罩由 BaseController 统一处理。
 *
 * @author MBox
 */
public class LocalVideoController extends BaseController {

    public interface LocalControlListener {
        void onBack();

        void onRetry();

        void onNext();

        void onSpeed();

        void onToggleFullScreen();
    }

    private TextView btnPlay;
    private TextView tvPosition;
    private TextView tvDuration;
    private SeekBar sbProgress;
    private TextView tvTitle;
    private TextView btnNext;
    private View topBar;
    private View bottomBar;
    private View errorBar;

    private boolean userSeeking;
    private LocalControlListener listener;
    private String title;

    public LocalVideoController(@NonNull Context context) {
        super(context);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.player_local_control_view;
    }

    @Override
    protected void initView() {
        super.initView();
        topBar = findViewById(R.id.localTopBar);
        bottomBar = findViewById(R.id.localBottomBar);
        errorBar = findViewById(R.id.localErrorBar);
        tvTitle = findViewById(R.id.tvLocalTitle);
        btnPlay = findViewById(R.id.btnLocalPlay);
        tvPosition = findViewById(R.id.tvLocalPos);
        tvDuration = findViewById(R.id.tvLocalDur);
        btnNext = findViewById(R.id.btnLocalNext);
        sbProgress = findViewById(R.id.sbLocalProgress);

        if (title != null) tvTitle.setText(title);

        findViewById(R.id.ivLocalBack).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onBack();
            }
        });
        findViewById(R.id.btnLocalSpeed).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onSpeed();
            }
        });
        findViewById(R.id.btnLocalFull).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onToggleFullScreen();
            }
        });
        findViewById(R.id.btnLocalRetry).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onRetry();
            }
        });
        btnPlay.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mControlWrapper != null) mControlWrapper.togglePlay();
                startFadeOut();
            }
        });
        btnNext.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onNext();
            }
        });

        sbProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && mControlWrapper != null) {
                    long duration = mControlWrapper.getDuration();
                    if (duration > 0) {
                        tvPosition.setText(PlayerUtils.stringForTime((int) (progress / 1000d * duration)));
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                userSeeking = false;
                if (mControlWrapper != null) {
                    long duration = mControlWrapper.getDuration();
                    if (duration > 0) {
                        mControlWrapper.seekTo((int) (seekBar.getProgress() / 1000d * duration));
                    }
                }
                startFadeOut();
            }
        });

        setCanChangePosition(true);
        setEnableInNormal(true);
        setGestureEnabled(true);
    }

    @Override
    protected void setProgress(int duration, int position) {
        super.setProgress(duration, position);
        if (userSeeking) return;
        if (duration > 0) {
            sbProgress.setMax(1000);
            sbProgress.setProgress((int) (position * 1000d / duration));
            tvDuration.setText(PlayerUtils.stringForTime(duration));
            tvPosition.setText(PlayerUtils.stringForTime(position));
        }
    }

    @Override
    protected void onPlayStateChanged(int playState) {
        super.onPlayStateChanged(playState);
        switch (playState) {
            case VideoView.STATE_PLAYING:
                btnPlay.setText("❚❚");
                errorBar.setVisibility(GONE);
                break;
            case VideoView.STATE_PAUSED:
                btnPlay.setText("▶");
                break;
            case VideoView.STATE_ERROR:
                btnPlay.setText("▶");
                errorBar.setVisibility(VISIBLE);
                topBar.setVisibility(GONE);
                bottomBar.setVisibility(GONE);
                break;
            case VideoView.STATE_IDLE:
            case VideoView.STATE_PREPARING:
            case VideoView.STATE_PREPARED:
            case VideoView.STATE_BUFFERING:
            case VideoView.STATE_BUFFERED:
            case VideoView.STATE_PLAYBACK_COMPLETED:
                errorBar.setVisibility(GONE);
                break;
        }
    }

    @Override
    protected void onVisibilityChanged(boolean isVisible, Animation anim) {
        super.onVisibilityChanged(isVisible, anim);
        int visibility = isVisible ? VISIBLE : GONE;
        topBar.setVisibility(visibility);
        bottomBar.setVisibility(visibility);
        if (isVisible) {
            btnNext.requestFocus();
            // 错误重试出现时顶底栏已被隐藏，保持错误条独占
            if (errorBar.getVisibility() == VISIBLE) {
                topBar.setVisibility(GONE);
                bottomBar.setVisibility(GONE);
            }
        }
    }

    public void setTitle(String title) {
        this.title = title;
        if (tvTitle != null && title != null) tvTitle.setText(title);
    }

    public void setNextEnabled(boolean enabled) {
        if (btnNext != null) btnNext.setVisibility(enabled ? VISIBLE : GONE);
    }

    public void setListener(LocalControlListener listener) {
        this.listener = listener;
    }

    /** 给 Activity 查询当前是否横屏全屏态用 */
    public boolean isErrorShowing() {
        return errorBar != null && errorBar.getVisibility() == VISIBLE;
    }

    Activity getHostActivity() {
        return PlayerUtils.scanForActivity(getContext());
    }
}
