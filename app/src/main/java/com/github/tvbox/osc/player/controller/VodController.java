package com.github.tvbox.osc.player.controller;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.PopupWindow;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.api.DanmakuApi;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.IJKCode;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.server.RemoteServer;
import com.github.tvbox.osc.subtitle.widget.SimpleSubtitleView;
import com.google.android.exoplayer2.ui.SubtitleView;
import com.github.tvbox.osc.ui.adapter.ParseAdapter;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.dialog.SelectDialog;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.DanmuHelper;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.M3u8;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.ScreenUtils;
import com.github.tvbox.osc.util.SubtitleHelper;
import com.github.tvbox.osc.util.VideoParseRuler;
import com.github.tvbox.osc.util.thunder.Jianpian;
import com.github.tvbox.osc.util.thunder.Thunder;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.HttpHeaders;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xwalk.core.XWalkView;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import java.util.Date;
import java.util.Map;
import java.util.Locale;

import xyz.doikki.videoplayer.player.VideoView;

import static xyz.doikki.videoplayer.util.PlayerUtils.stringForTime;
import static xyz.doikki.videoplayer.util.PlayerUtils.safeTimeMs;

public class VodController extends BaseController {
    private static final float PORTRAIT_EPISODE_SWIPE_DP = 80f;
    private static final long PORTRAIT_EPISODE_TITLE_SHOW_MS = 3000L;

    /* ===== 长按加速 ===== */
    /** 竖直方向滑动多少 dp 对应 1 倍速的变化 */
    private static final float SPEED_BOOST_RANGE_DP = 240f;
    private static final float SPEED_BOOST_MIN = 0.5f;
    private static final float SPEED_BOOST_MAX = 3.0f;
    /** 横向右滑多少 dp 判定为「固定 / 解除固定当前倍速」 */
    private static final float SPEED_LOCK_SWIPE_DP = 56f;
    /** 倍速提示的透明度：调低一档，别太抢画面 */
    private static final float SPEED_TIP_ALPHA = 0.55f;
    /** 固定 / 解除固定时的触感反馈时长（毫秒） */
    private static final long SPEED_LOCK_VIBRATE_MS = 22L;

    private boolean speedBoostActive;
    private float speedBoostStartY;
    private float speedBoostStartX;
    private float speedBoostBaseSpeed = 1.0f;
    private float speedBoostSpeed = 1.0f;
    /** 当前倍速是否已被「右滑固定」：固定后抬手不回落到原速 */
    private boolean speedBoostLocked;
    /** 固定前的原始倍速，解除固定时回到这里 */
    private float speedBoostNaturalSpeed = 1.0f;
    /** 一次横滑只触发一次切换，避免手指轻微抖动来回翻转 */
    private boolean speedLockSwipeConsumed;
    private TextView mSpeedBoostTip;

    /* ===== 双击左右快退/快进 ===== */
    private static final int DOUBLE_TAP_SEEK_MS = 3000;
    /** 双击快进/快退的左右热区宽度占比，中间剩余区域用于播放/暂停 */
    private static final float DOUBLE_TAP_SEEK_ZONE = 0.35f;

    private boolean portraitEpisodeSwipeTriggered;
    private final Runnable portraitEpisodeTitleRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isBottomVisible()) {
                mTopRoot1.setVisibility(GONE);
                if (mTopBar != null) mTopBar.setVisibility(GONE);
            }
        }
    };

    public VodController(@NonNull @NotNull Context context) {
        super(context);
        mHandlerCallback = new HandlerCallback() {
            @Override
            public void callback(Message msg) {
                switch (msg.what) {
                    case 1000: { // seek 刷新
                        mProgressRoot.setVisibility(VISIBLE);
                        break;
                    }
                    case 1001: { // seek 关闭
                        mProgressRoot.setVisibility(GONE);
                        break;
                    }
                    case 1002: { // 显示底部菜单
                        updateDanmuSearchUiBtn();
                        hidePauseRoot();
                        mPlayTitle.setVisibility(GONE);
                        mBottomRoot.setVisibility(VISIBLE);
                        if (mTopBar != null) mTopBar.setVisibility(VISIBLE);
                        mTopRoot1.setVisibility(VISIBLE);
                        mTopRoot2.setVisibility(VISIBLE);
                        mPlayLoadNetSpeedRightTop.setVisibility(VISIBLE);
                        if(Hawk.get(HawkConfig.SCREEN_DISPLAY,GONE)==GONE){
                            mPlayPauseTime.setVisibility(VISIBLE);
                        }else {
                            net_play_speed.setVisibility(GONE);
                        }
                        boolean isPortrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
                        // 手机竖屏：常显返回键；TV/横屏（非 TV 外部播放器）按原逻辑
                        backBtn.setVisibility(ScreenUtils.isTv(context) ? INVISIBLE : VISIBLE);
                        showLockView();
                        if (listener != null) listener.onControlsVisibilityChanged(true);
                        break;
                    }
                    case 1003: { // 隐藏底部菜单
                        mBottomRoot.setVisibility(GONE);
                        // 顶部整栏（退出/投屏/菜单）跟随控制条一起隐藏
                        if (mTopBar != null) mTopBar.setVisibility(GONE);
                        mTopRoot1.setVisibility(GONE);
                        mPlayLoadNetSpeedRightTop.setVisibility(GONE);
                        if (videoPlayState == VideoView.STATE_PAUSED) {
                            showPauseRoot();
                            mPlayTitle.setVisibility(VISIBLE);
                        } else {
                            hidePauseRoot();
                            mPlayTitle.setVisibility(GONE);
                        }
                        if(Hawk.get(HawkConfig.SCREEN_DISPLAY,GONE)==GONE){
                            mPlayPauseTime.setVisibility(GONE);
                        }else {
                            net_play_speed.setVisibility(VISIBLE);
                        }
                        // 手机竖屏：隐藏菜单时仍保留返回键；仅 TV 按原逻辑隐藏
                        backBtn.setVisibility(ScreenUtils.isTv(context) ? INVISIBLE : VISIBLE);
                        mHandler.removeCallbacks(lockRunnable);
                        if (mLockView != null) {
                            mLockView.setVisibility(INVISIBLE);
                        }
                        if (listener != null) listener.onControlsVisibilityChanged(false);
                        break;
                    }
                    case 1004: { // 设置速度
                        if (isInPlaybackState()) {
                            try {
                                float speed = (float) mPlayerConfig.getDouble("sp");
                                mControlWrapper.setSpeed(speed);
                            } catch (JSONException e) {
                                e.printStackTrace();
                            }
                        } else
                            mHandler.sendEmptyMessageDelayed(1004, 100);
                        break;
                    }
                }
            }
        };
    }

    @Override
    public boolean onDown(MotionEvent e) {
        portraitEpisodeSwipeTriggered = false;
        exitSpeedBoost();
        return super.onDown(e);
    }

    /**
     * 长按进入加速状态：此后上下滑动调倍速，**向右滑动**把当前倍速固定下来
     * （已固定时再右滑一次解除固定）。
     */
    private void enterSpeedBoost(float downX, float downY) {
        speedBoostActive = true;
        speedBoostStartX = downX;
        speedBoostStartY = downY;
        speedBoostBaseSpeed = currentPlaySpeed();
        if (!speedBoostLocked) {
            // 未固定时，这一轮加速的「原速」就是进入时的倍速
            speedBoostNaturalSpeed = speedBoostBaseSpeed;
        }
        speedBoostSpeed = speedBoostBaseSpeed;
        speedLockSwipeConsumed = false;
        showSpeedBoostTip(speedBoostSpeed);
    }

    private void updateSpeedBoost(float currentY) {
        if (!speedBoostActive) return;
        float range = getResources().getDisplayMetrics().density * SPEED_BOOST_RANGE_DP;
        if (range <= 0) return;
        float maxSpeed = Hawk.get(HawkConfig.GESTURE_MAX_SPEED, SPEED_BOOST_MAX);
        if (maxSpeed < SPEED_BOOST_MIN) maxSpeed = SPEED_BOOST_MIN;
        // 手指向上移动为正 → 加速；向下为负 → 减速
        float delta = (speedBoostStartY - currentY) / range;
        float speed = speedBoostBaseSpeed + delta;
        if (speed < SPEED_BOOST_MIN) speed = SPEED_BOOST_MIN;
        if (speed > maxSpeed) speed = maxSpeed;
        speed = Math.round(speed * 10) / 10f;
        if (Math.abs(speed - speedBoostSpeed) < 0.05f) return;
        speedBoostSpeed = speed;
        if (mControlWrapper != null) mControlWrapper.setSpeed(speed);
        showSpeedBoostTip(speed);
    }

    /**
     * 横向右滑：固定 / 解除固定当前倍速。
     *
     * <p>判定用的是相对按下点的水平位移，超过 {@link #SPEED_LOCK_SWIPE_DP} 才触发；
     * 手指回到阈值一半以内会重置标记，所以一次长按里可以连续固定 → 解除 → 再固定。</p>
     *
     * @return true 表示这次移动已被判定为横滑（调用方仍可继续按竖直位移调倍速）
     */
    private boolean handleSpeedLockSwipe(MotionEvent event) {
        float threshold = getResources().getDisplayMetrics().density * SPEED_LOCK_SWIPE_DP;
        if (threshold <= 0) return false;
        float dx = event.getX() - speedBoostStartX;
        if (dx < threshold * 0.5f) {
            // 手指收回去了，允许下一次再触发
            speedLockSwipeConsumed = false;
            return false;
        }
        if (dx < threshold || speedLockSwipeConsumed) return speedLockSwipeConsumed;
        speedLockSwipeConsumed = true;
        if (speedBoostLocked) {
            speedBoostLocked = false;
            speedBoostSpeed = speedBoostNaturalSpeed;
            speedBoostBaseSpeed = speedBoostNaturalSpeed;
            if (mControlWrapper != null) mControlWrapper.setSpeed(speedBoostNaturalSpeed);
        } else {
            speedBoostLocked = true;
            speedBoostBaseSpeed = speedBoostSpeed;
        }
        // 以当前位置作为新的竖直基准，切换后不会因为历史位移让倍速突然跳变
        speedBoostStartY = event.getY();
        speedBoostStartX = event.getX();
        speedLockSwipeConsumed = true;
        showSpeedBoostTip(speedBoostSpeed);
        vibrateSpeedLock();
        return true;
    }

    private void exitSpeedBoost() {
        if (!speedBoostActive) return;
        speedBoostActive = false;
        if (mSpeedBoostTip != null) mSpeedBoostTip.setVisibility(GONE);
        if (speedBoostLocked) {
            // 已固定：抬手保持当前倍速
            speedBoostBaseSpeed = speedBoostSpeed;
            return;
        }
        if (mControlWrapper != null) mControlWrapper.setSpeed(speedBoostBaseSpeed);
        speedBoostSpeed = speedBoostBaseSpeed;
    }

    private void showSpeedBoostTip(float speed) {
        if (mSpeedBoostTip == null) return;
        String text = String.format(Locale.getDefault(), "%.1fX 倍速", speed);
        if (speedBoostLocked) text = text + " · 已固定";
        mSpeedBoostTip.setText(text);
        mSpeedBoostTip.setAlpha(SPEED_TIP_ALPHA);
        mSpeedBoostTip.setVisibility(VISIBLE);
    }

    /** 固定 / 解除固定时的触感反馈（短促一下） */
    private void vibrateSpeedLock() {
        try {
            Context context = getContext();
            if (context == null) return;
            android.os.Vibrator vibrator;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.os.VibratorManager manager =
                        (android.os.VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                vibrator = manager == null ? null : manager.getDefaultVibrator();
            } else {
                vibrator = (android.os.Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (vibrator == null || !vibrator.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(
                        SPEED_LOCK_VIBRATE_MS, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(SPEED_LOCK_VIBRATE_MS);
            }
        } catch (Throwable ignore) {
            // 没有振动马达 / 权限被拒时静默降级，不影响手势本身
        }
    }

    /** 手指抬起/取消时结束加速 */
    private void handleSpeedBoostRelease(MotionEvent event) {
        if (!speedBoostActive || event == null) return;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            exitSpeedBoost();
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        // 注意：GestureDetector 在触发长按后会忽略 ACTION_MOVE（不回调 onScroll），
        // 所以长按加速的滑动必须在这里直接处理
        if (speedBoostActive && event != null) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_MOVE) {
                // 先判横滑（固定 / 解除固定），再按竖直位移调倍速
                handleSpeedLockSwipe(event);
                updateSpeedBoost(event.getY());
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                exitSpeedBoost();
            }
        }
        return super.onTouch(v, event);
    }

    /**
     * 双击分区：
     * 左侧 35% → 后退 3 秒；右侧 35% → 前进 3 秒；中间 30% → 播放 / 暂停。
     */
    @Override
    public boolean onDoubleTap(MotionEvent e) {
        if (e == null || isLocked() || !isInPlaybackState()) return true;
        int width = getMeasuredWidth();
        if (width <= 0) return true;

        float x = e.getX();
        float leftEdge = width * DOUBLE_TAP_SEEK_ZONE;          // 0.35
        float rightEdge = width * (1f - DOUBLE_TAP_SEEK_ZONE);  // 0.65
        if (x >= leftEdge && x <= rightEdge) {
            // 中间区域：播放 / 暂停
            if (Hawk.get(HawkConfig.GESTURE_DOUBLE_TAP_PLAY, true)) {
                togglePlay();
            }
            return true;
        }

        int duration = safeTimeMs(mControlWrapper.getDuration());
        if (duration <= 0) return true;
        int seekMs = Hawk.get(HawkConfig.GESTURE_DOUBLE_TAP_SEEK, DOUBLE_TAP_SEEK_MS);
        if (seekMs <= 0) seekMs = DOUBLE_TAP_SEEK_MS;
        boolean forward = x > rightEdge;
        int current = safeTimeMs(mControlWrapper.getCurrentPosition());
        int target = current + (forward ? seekMs : -seekMs);
        if (target < 0) target = 0;
        if (target > duration) target = duration;
        mControlWrapper.seekTo(target);
        showDoubleTapSeekTip(forward, seekMs, target, duration);
        return true;
    }

    private void showDoubleTapSeekTip(boolean forward, int seekMs, int target, int duration) {
        if (mProgressIcon != null) {
            mProgressIcon.setVisibility(VISIBLE);
            mProgressIcon.setImageResource(forward ? R.drawable.exo_icon_fastforward : R.drawable.exo_icon_rewind);
        }
        if (mProgressText != null) {
            mProgressText.setText((forward ? "快进 " : "快退 ") + (seekMs / 1000) + " 秒"
                    + "  " + stringForTime(target) + " / " + stringForTime(duration));
        }
        mHandler.sendEmptyMessage(1000);
        mHandler.removeMessages(1001);
        mHandler.sendEmptyMessageDelayed(1001, 900);
    }

    private float currentPlaySpeed() {
        try {
            if (mPlayerConfig != null) return (float) mPlayerConfig.getDouble("sp");
        } catch (Exception ignore) {
        }
        return 1.0f;
    }

    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        if (speedBoostActive) {
            updateSpeedBoost(e2.getY());
            return true;
        }
        if (isPortraitEpisodeSwipe(e1, e2)) {
            if (!portraitEpisodeSwipeTriggered && Math.abs(e2.getY() - e1.getY()) >= portraitEpisodeSwipeThreshold()) {
                portraitEpisodeSwipeTriggered = true;
                if (listener != null) {
                    if (e2.getY() < e1.getY()) listener.playNext(false);
                    else listener.playPre();
                    showPortraitEpisodeTitle();
                }
            }
            return true;
        }
        return super.onScroll(e1, e2, distanceX, distanceY);
    }

    private boolean isPortraitEpisodeSwipe(MotionEvent e1, MotionEvent e2) {
        if (e1 == null || e2 == null || !canHandleGesture(e1)) return false;
        if (!Hawk.get(HawkConfig.GESTURE_PORTRAIT_SWIPE, true)) return false;
        if (getResources().getConfiguration().orientation != Configuration.ORIENTATION_PORTRAIT) return false;
        return Math.abs(e2.getY() - e1.getY()) > Math.abs(e2.getX() - e1.getX());
    }

    private float portraitEpisodeSwipeThreshold() {
        return getResources().getDisplayMetrics().density * PORTRAIT_EPISODE_SWIPE_DP;
    }

    private void showPortraitEpisodeTitle() {
        if (isBottomVisible()) return;
        mHandler.removeCallbacks(portraitEpisodeTitleRunnable);
        // 切集提示依附于顶部栏，临时把顶部栏带出来，3 秒后自动收回
        if (mTopBar != null) mTopBar.setVisibility(VISIBLE);
        mTopRoot1.setVisibility(VISIBLE);
        mHandler.postDelayed(portraitEpisodeTitleRunnable, PORTRAIT_EPISODE_TITLE_SHOW_MS);
    }

    SeekBar mSeekBar;
    TextView mCurrentTime;
    TextView mTotalTime;
    boolean mIsDragging;
    private boolean mSeekBarKeyTracking;
    LinearLayout mProgressRoot;
    TextView mProgressText;
    ImageView mProgressIcon;
    ImageView mLockView;
    LinearLayout mBottomRoot;
    LinearLayout mPlayBtnGroup;
    LinearLayout mTopRoot1;
    LinearLayout mTopRoot2;
    LinearLayout mParseRoot;
    RecyclerView mGridParseView;
    TextView mPlayTitle;
    TextView mPlayTitle1;
    TextView mPlayLabel;
    TextView mPlayLoadNetSpeedRightTop;
    View mNextBtn;
    TextView mPlayerScaleBtn;
    public TextView mPlayerSpeedBtn;
    TextView mPlayerBtn;
    TextView mPlayerIJKBtn;
    TextView mPlayerRetry;
    TextView mPlayrefresh;
    public TextView mPlayerTimeStartEndText;
    public TextView mPlayerTimeStartBtn;
    public TextView mPlayerTimeSkipBtn;
    public TextView mPlayerTimeResetBtn;
    TextView mPlayPauseTime;
    TextView mPlayLoadNetSpeed;
    TextView mVideoSize;
    public SimpleSubtitleView mSubtitleView;
    public SimpleSubtitleView mLyricView;
    public SubtitleView mExoSubtitleView;
    TextView mZimuBtn;
    TextView mAudioTrackBtn;
    TextView mVideoTrackBtn;
    TextView mDanmuSettingBtn;
    TextView mDanmuSearchUiBtn;
    public TextView mLandscapePortraitBtn;
    private View backBtn;//返回键
    private boolean isClickBackBtn;
    TextView seekTime; //右上角进度时间显示
    TextView mScreenDisplay; //增加屏显开关
    LinearLayout tv_screen_display; //增加屏显布局
    View mCastBtn;
    TextView net_play_speed;
    private boolean hasDanmu = false;
    private boolean showParse;

    /* ===== 手机竖屏主按钮行 / 右上角菜单 / 抽屉 ===== */
    private LinearLayout mTopBar;            // 顶部整栏（返回/标题/投屏/菜单）
    private LinearLayout mPlayPauseBtn;      // 播放·暂停
    private ImageView mPlayPauseIcon;
    private TextView mDanmuBtn;              // 弹幕开关
    private LinearLayout mFullScreenBtn;     // 全屏
    private ImageView mFullScreenIcon;
    private ImageView mMenuBtn;              // 右上角菜单
    private LinearLayout mMorePanel;         // 设置抽屉
    private View mDrawerScrim;               // 抽屉遮罩
    private ImageView mDrawerClose;          // 抽屉关闭

    /* ===== 抽屉内的弹幕设置 ===== */
    private TextView tvDanmuSource;
    private TextView btnDanmuSearch;
    private TextView dmColorDefault, dmColorRandom;
    private TextView dmSpeed0, dmSpeed1, dmSpeed2, dmSpeed3;
    private TextView dmSizeSub, dmSize, dmSizeAdd;
    private TextView dmLineSub, dmLine, dmLineAdd;
    private TextView dmAlphaSub, dmAlpha, dmAlphaAdd;
    // 注意：必须是 static。initView() 由 BaseVideoController 构造函数回调，
    // 此时子类实例字段初始化器尚未执行，非 static 的数组仍是 null。
    private static final float[] DM_SPEED_RATES = {2.4f, 1.8f, 1.5f, 1.0f}; // 与 DanmuSettingDialog 的档位一致

    LockRunnable lockRunnable = new LockRunnable();
    private boolean isLock = false;
    private boolean previewMode = false;
    Handler myHandle;
    Runnable myRunnable;
    int myHandleSeconds = 10000;//闲置多少毫秒秒关闭底栏  默认6秒

    int videoPlayState = 0;

    private final Runnable myRunnable2 = new Runnable() {
        @SuppressLint("SetTextI18n")
        @Override
        public void run() {
            Date date = new Date();
            @SuppressLint("SimpleDateFormat") SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm");
            mPlayPauseTime.setText(timeFormat.format(date));
            long mSpeed = mControlWrapper.getTcpSpeed();
            String speed = PlayerHelper.getDisplaySpeed(mSpeed,false);
            String speedBps = PlayerHelper.getDisplaySpeedBps(mSpeed,true);
            mPlayLoadNetSpeedRightTop.setText(speedBps);
            mPlayLoadNetSpeed.setText(speed);
            net_play_speed.setText(speedBps);
            int[] mVideoSizes = mControlWrapper.getVideoSize();
            String width = Integer.toString(mVideoSizes[0]);
            String height = Integer.toString(mVideoSizes[1]);
            mVideoSize.setText(width + " X " + height);

            mHandler.postDelayed(this, 1000);
        }
    };
    
    private void showLockView() {
        if (previewMode || getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
            if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) isLock = false;
            mHandler.removeCallbacks(lockRunnable);
            if (mLockView != null) {
                mLockView.setVisibility(INVISIBLE);
            }
            return;
        }
        mLockView.setVisibility(ScreenUtils.isTv(getContext()) ? INVISIBLE : VISIBLE);
        mHandler.removeCallbacks(lockRunnable);
        if (isLock) {
            mHandler.postDelayed(lockRunnable, 3000);
        }
    }

    public void setPreviewMode(boolean previewMode) {
        this.previewMode = previewMode;
        mHandler.removeCallbacks(lockRunnable);
        if (mLockView != null) {
            mLockView.setVisibility(INVISIBLE);
        }
        updateFullScreenIcon(!previewMode);
    }

    @Override
    protected void initView() {
        super.initView();
        mCurrentTime = findViewById(R.id.curr_time);
        mTotalTime = findViewById(R.id.total_time);
        mPlayTitle = findViewById(R.id.tv_info_name);
        mPlayTitle1 = findViewById(R.id.tv_info_name1);
        mPlayLabel = findViewById(R.id.play_label);
        mPlayLoadNetSpeedRightTop = findViewById(R.id.tv_play_load_net_speed_right_top);
        mSeekBar = findViewById(R.id.seekBar);
        CircleThumbDrawable seekThumb = new CircleThumbDrawable(getContext());
        mSeekBar.setThumb(seekThumb);
        mSeekBar.setThumbOffset(seekThumb.getIntrinsicWidth() / 2);
        mProgressRoot = findViewById(R.id.tv_progress_container);
        mProgressIcon = findViewById(R.id.tv_progress_icon);
        mProgressText = findViewById(R.id.tv_progress_text);
        mBottomRoot = findViewById(R.id.bottom_container);
        mTopRoot1 = findViewById(R.id.tv_top_l_container);
        mTopRoot2 = findViewById(R.id.tv_top_r_container);
        mPlayBtnGroup = findViewById(R.id.play_btn_group);
        tv_screen_display = findViewById(R.id.tv_screen_display);
        net_play_speed = findViewById(R.id.net_play_speed);
        mParseRoot = findViewById(R.id.parse_root);
        mGridParseView = findViewById(R.id.mGridParseView);
        mPlayerRetry = findViewById(R.id.play_retry);
        mPlayrefresh = findViewById(R.id.play_refresh);
        mNextBtn = findViewById(R.id.play_next);
        mPlayerScaleBtn = findViewById(R.id.play_scale);
        mPlayerSpeedBtn = findViewById(R.id.play_speed);
        mPlayerBtn = findViewById(R.id.play_player);
        mPlayerIJKBtn = findViewById(R.id.play_ijk);
        mPlayerTimeStartEndText = findViewById(R.id.play_time_start_end_text);
        mPlayerTimeStartBtn = findViewById(R.id.play_time_start);
        mPlayerTimeSkipBtn = findViewById(R.id.play_time_end);
        mPlayerTimeResetBtn = findViewById(R.id.play_time_reset);
        mPlayPauseTime = findViewById(R.id.tv_sys_time);
        mPlayLoadNetSpeed = findViewById(R.id.tv_play_load_net_speed);
        mVideoSize = findViewById(R.id.tv_videosize);
        mSubtitleView = findViewById(R.id.subtitle_view);
        mLyricView = findViewById(R.id.lyric_view);
        mExoSubtitleView = findViewById(R.id.exo_subtitle_view);
        mZimuBtn = findViewById(R.id.zimu_select);
        mAudioTrackBtn = findViewById(R.id.audio_track_select);
        mVideoTrackBtn = findViewById(R.id.video_track_select);
        mDanmuSettingBtn = findViewById(R.id.danmu_setting);
        mDanmuSearchUiBtn = findViewById(R.id.danmu_search_ui);
        mLandscapePortraitBtn = findViewById(R.id.landscape_portrait);
        backBtn = findViewById(R.id.tv_back);
        seekTime = findViewById(R.id.tv_seek_time);
        mScreenDisplay = findViewById(R.id.screen_display);
        mCastBtn = findViewById(R.id.play_cast);
        mTopBar = findViewById(R.id.player_top_bar);
        mSpeedBoostTip = findViewById(R.id.tv_speed_boost);
        mPlayPauseBtn = findViewById(R.id.btn_play_pause);
        mPlayPauseIcon = findViewById(R.id.iv_play_pause);
        mDanmuBtn = findViewById(R.id.btn_danmu);
        mFullScreenBtn = findViewById(R.id.btn_fullscreen);
        mFullScreenIcon = findViewById(R.id.iv_fullscreen);
        mMenuBtn = findViewById(R.id.iv_player_menu);
        mMorePanel = findViewById(R.id.player_more_panel);
        mDrawerScrim = findViewById(R.id.drawer_scrim);
        mDrawerClose = findViewById(R.id.iv_drawer_close);
        bindMainControlButtons();
        bindDrawer();
        bindDanmuSettingPanel();
        updateDanmuBtn();
        updateDanmuSearchUiBtn();
        backBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                hideDrawer();
                Activity host = mActivity;
                if (host == null && getContext() instanceof Activity) {
                    host = (Activity) getContext();
                }
                if (host == null) return;
                if (isPlayerFullScreen()) {
                    // 全屏：退出全屏，回到详情页
                    hideBottom();
                    if (listener != null) {
                        listener.exitFullScreen();
                    } else {
                        host.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                    }
                } else {
                    // 非全屏（详情页内嵌播放）：退出当前播放
                    hideBottom();
                    host.onBackPressed();
                }
            }
        });
        mLockView = findViewById(R.id.tv_lock);
        mLockView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                isLock = !isLock;
                mLockView.setImageResource(isLock ? R.drawable.icon_lock : R.drawable.icon_unlock);
                if (isLock) {
                    Message obtain = Message.obtain();
                    obtain.what = 1003;//隐藏底部菜单
                    mHandler.sendMessage(obtain);
                }
                showLockView();
            }
        });
        View rootView = findViewById(R.id.rootView);
        rootView.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (previewMode) {
                    return false;
                }
                if (isLock) {
                    if (event.getAction() == MotionEvent.ACTION_UP) {
                        showLockView();
                    }
                }
                return isLock;
            }
        });

        initSubtitleInfo();

        myHandle = new Handler();
        myRunnable = new Runnable() {
            @Override
            public void run() {
                // 暂停状态下控制条常显，否则用户点不到播放键
                if (videoPlayState == VideoView.STATE_PAUSED) {
                    myHandle.postDelayed(this, myHandleSeconds);
                    return;
                }
                hideBottom();
            }
        };

        mPlayPauseTime.post(new Runnable() {
            @Override
            public void run() {
                mHandler.post(myRunnable2);
            }
        });

        mGridParseView.setLayoutManager(new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false));
        ParseAdapter parseAdapter = new ParseAdapter();
        parseAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                ParseBean parseBean = parseAdapter.getItem(position);
                // 当前默认解析需要刷新
                int currentDefault = parseAdapter.getData().indexOf(ApiConfig.get().getDefaultParse());
                parseAdapter.notifyItemChanged(currentDefault);
                ApiConfig.get().setDefaultParse(parseBean);
                parseAdapter.notifyItemChanged(position);
                listener.changeParse(parseBean);
                hideBottom();
            }
        });
        mGridParseView.setAdapter(parseAdapter);
        parseAdapter.setNewData(ApiConfig.get().getParseBeanList());

        mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }

                updateSeekBarTime(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                beginSeekBarTracking();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                finishSeekBarTracking();
            }
        });
        mSeekBar.setOnHoverListener(new OnHoverListener() {
            @Override
            public boolean onHover(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_HOVER_ENTER:
                        keepSeekBarActive();
                        v.setSelected(true);
                        v.refreshDrawableState();
                        v.jumpDrawablesToCurrentState();
                        v.invalidate();
                        break;
                    case MotionEvent.ACTION_HOVER_MOVE:
                        keepSeekBarActive();
                        break;
                    case MotionEvent.ACTION_HOVER_EXIT:
                        v.setSelected(false);
                        v.refreshDrawableState();
                        v.jumpDrawablesToCurrentState();
                        v.invalidate();
                        break;
                }
                return false;
            }
        });
        mSeekBar.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    boolean hasParse = mParseRoot.getVisibility() == VISIBLE && mGridParseView.getAdapter() != null && mGridParseView.getAdapter().getItemCount() > 0;
                    mSeekBar.setNextFocusUpId(hasParse ? R.id.mGridParseView : R.id.play_next);
                    keepSeekBarActive();
                    v.setSelected(true);
                    v.refreshDrawableState();
                    v.jumpDrawablesToCurrentState();
                    v.invalidate();
                } else {
                    v.setSelected(false);
                    v.refreshDrawableState();
                    v.jumpDrawablesToCurrentState();
                    v.invalidate();
                    if (mSeekBarKeyTracking) {
                        finishSeekBarTracking();
                    }
                }
            }
        });
        mSeekBar.setOnKeyListener(new OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) {
                    return false;
                }
                if (!isInPlaybackState()) {
                    return true;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    beginSeekBarTracking();
                    mSeekBarKeyTracking = true;
                    v.setSelected(true);
                    v.jumpDrawablesToCurrentState();
                    v.invalidate();
                    moveSeekBarByKey(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    return true;
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    v.setSelected(v.hasFocus());
                    v.jumpDrawablesToCurrentState();
                    v.invalidate();
                    if (mSeekBarKeyTracking) {
                        finishSeekBarTracking();
                    }
                    return true;
                }
                return true;
            }
        });
        mSeekBar.setOnGenericMotionListener(new OnGenericMotionListener() {
            @Override
            public boolean onGenericMotion(View v, MotionEvent event) {
                if (event.getAction() != MotionEvent.ACTION_SCROLL || !isInPlaybackState()) {
                    return false;
                }
                float scroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (scroll == 0) {
                    scroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
                }
                if (scroll == 0) {
                    return false;
                }
                beginSeekBarTracking();
                moveSeekBarByKey(scroll > 0 ? 1 : -1);
                finishSeekBarTracking();
                return true;
            }
        });
        mPlayerRetry.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.replay(true);
                hideBottom();
            }
        });
        mPlayrefresh.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.replay(false);
                hideBottom();
            }
        });
        mNextBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                listener.playNext(false);
                hideBottom();
            }
        });
        setOnTvLongClickListener(mNextBtn, new OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                FastClickCheckUtil.check(view);
                listener.showEpisodeDialog();
                hideBottom();
                return true;
            }
        });
        mPlayerScaleBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, myHandleSeconds);
                showScalePicker(view);
            }
        });
        setOnTvLongClickListener(mPlayerScaleBtn, new OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, myHandleSeconds);
                FastClickCheckUtil.check(view);
                try {
                    mPlayerConfig.put("sc", 0);
                    updatePlayerCfgView();
                    listener.updatePlayerCfg();
                    mControlWrapper.setScreenScaleType(0);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                return true;
            }
        });
        mPlayerSpeedBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, myHandleSeconds);
                showSpeedDialog();
            }
        });

        setOnTvLongClickListener(mPlayerSpeedBtn, new OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, myHandleSeconds);
                FastClickCheckUtil.check(view);
                try {
                    mPlayerConfig.put("sp", 1.0f);
                    updatePlayerCfgView();
                    listener.updatePlayerCfg();
                    speed_old = 1.0f;
                    mControlWrapper.setSpeed(1.0f);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                return true;
            }
        });
        mPlayerBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, myHandleSeconds);
                showPlayerPicker(view);
            }
        });

        setOnTvLongClickListener(mPlayerBtn, new OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, myHandleSeconds);
                FastClickCheckUtil.check(view);
                try {
                    int playerType = mPlayerConfig.getInt("pl");
                    int defaultPos = 0;
                    ArrayList<Integer> players = PlayerHelper.getExistPlayerTypes();
                    ArrayList<Integer> renders = new ArrayList<>();
                    for(int p = 0; p<players.size(); p++) {
                        renders.add(p);
                        if (players.get(p) == playerType) {
                            defaultPos = p;
                        }
                    }
                    SelectDialog<Integer> dialog = new SelectDialog<>(mActivity);
                    dialog.setTip("请选择播放器");
                    dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                        @Override
                        public void click(Integer value, int pos) {
                            try {
                                dialog.cancel();
                                int thisPlayType = players.get(pos);
                                if (thisPlayType != playerType) {
                                    mPlayerConfig.put("pl", thisPlayType);
                                    updatePlayerCfgView();
                                    listener.updatePlayerCfg();
                                    listener.replay(false);
                                    listener.setAllowSwitchPlayer(false);
                                    hideBottom();
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public String getDisplay(Integer val) {
                            Integer playerType = players.get(val);
                            return PlayerHelper.getPlayerName(playerType);
                        }
                    }, new DiffUtil.ItemCallback<Integer>() {
                        @Override
                        public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                            return oldItem.intValue() == newItem.intValue();
                        }

                        @Override
                        public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                            return oldItem.intValue() == newItem.intValue();
                        }
                    }, renders, defaultPos);
                    dialog.show();
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                return true;
            }
        });
        mPlayerIJKBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, myHandleSeconds);
                showCodecPicker(view);
            }
        });
//        增加播放页面片头片尾时间重置
        mPlayerTimeResetBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, myHandleSeconds);
                try {
                    mPlayerConfig.put("et", 0);
                    mPlayerConfig.put("st", 0);
                    updatePlayerCfgView();
                    listener.updatePlayerCfg();
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        });
        mPlayerTimeStartBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, myHandleSeconds);
                try {
                    int current = safeTimeMs(mControlWrapper.getCurrentPosition());
                    int duration = safeTimeMs(mControlWrapper.getDuration());
                    if (current > duration / 2) return;
                    mPlayerConfig.put("st",current/1000);
                    updatePlayerCfgView();
                    listener.updatePlayerCfg();
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        });
        bindTimeAdjust(mPlayerTimeStartBtn, true);
        mPlayerTimeSkipBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, myHandleSeconds);
                try {
                    int current = safeTimeMs(mControlWrapper.getCurrentPosition());
                    int duration = safeTimeMs(mControlWrapper.getDuration());
                    if (current < duration / 2) return;
                    mPlayerConfig.put("et", (duration - current)/1000);
                    updatePlayerCfgView();
                    listener.updatePlayerCfg();
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        });
        bindTimeAdjust(mPlayerTimeSkipBtn, false);
        mZimuBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                listener.selectSubtitle();
                hideBottom();
            }
        });
        setOnTvLongClickListener(mZimuBtn, new OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                mSubtitleView.setVisibility(View.GONE);
                mSubtitleView.destroy();
                mSubtitleView.clearSubtitleCache();
                mSubtitleView.isInternal = false;
                mLyricView.setVisibility(View.GONE);
                mLyricView.destroy();
                mLyricView.clearSubtitleCache();
                mExoSubtitleView.setVisibility(View.GONE);
                hideBottom();
                Toast.makeText(getContext(), "字幕已关闭", Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        mAudioTrackBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                listener.selectAudioTrack();
                hideBottom();
            }
        });
        mVideoTrackBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                listener.selectVideoTrack();
                hideBottom();
            }
        });
        mDanmuSettingBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                listener.showDanmuSetting();
            }
        });
        setOnTvLongClickListener(mDanmuSettingBtn, new OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                FastClickCheckUtil.check(view);
                boolean opened = listener.toggleDanmu();
                hideBottom();
                Toast.makeText(getContext(), opened ? "弹幕已开启" : "弹幕已临时关闭", Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        mDanmuSearchUiBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                listener.searchDanmuUi(false);
                hideBottom();
            }
        });
        setOnTvLongClickListener(mDanmuSearchUiBtn, new OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                listener.searchDanmuUi(true);
                hideBottom();
                return true;
            }
        });
        mLandscapePortraitBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                setLandscapePortrait();
                hideBottom();
            }
        });
        //屏显
        int disPlay = Hawk.get(HawkConfig.SCREEN_DISPLAY, GONE);
        mTopRoot2.setVisibility(disPlay);
        seekTime.setVisibility(disPlay);
        net_play_speed.setVisibility(disPlay);
        mPlayPauseTime.setVisibility(disPlay);
        mScreenDisplay.setTextColor(disPlay==VISIBLE?getResources().getColor(R.color.color_02F8E1): Color.WHITE);
        mScreenDisplay.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                int disPlay =(Hawk.get(HawkConfig.SCREEN_DISPLAY, GONE) == VISIBLE) ? GONE : VISIBLE;
                seekTime.setVisibility(disPlay);
                net_play_speed.setVisibility(disPlay);
                if(disPlay==VISIBLE)mPlayPauseTime.setVisibility(disPlay);
                Hawk.put(HawkConfig.SCREEN_DISPLAY, disPlay);
                mScreenDisplay.setTextColor(disPlay==VISIBLE?getResources().getColor(R.color.color_02F8E1): Color.WHITE);
                hideBottom();
            }
        });
        mCastBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                if (listener != null) listener.clickCast();
            }
        });
        if (Build.VERSION.SDK_INT < 30) {
            mCastBtn.setVisibility(GONE);
        } else {
            mCastBtn.setVisibility(VISIBLE);
        }
        mScreenDisplay.setNextFocusRightId(R.id.play_next);
        mNextBtn.setNextFocusLeftId(R.id.screen_display);
    }

    /* ==================== 下拉框选择器（播放器 / 解码 / 画面比例） ==================== */

    /**
     * 抽屉内的下拉选项：用与抽屉同风格的浮层列表，宽度跟随锚点，
     * 避免系统 PopupMenu 在播放器里尺寸/配色不协调、还容易被放大到超出屏幕。
     */
    private void showOptionList(View anchor, final List<String> options, int checkedIndex,
                                final OnOptionPicked callback) {
        if (anchor == null || options == null || options.isEmpty()) return;
        try {
            final Context ctx = getContext();
            LinearLayout list = new LinearLayout(ctx);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setBackgroundResource(R.drawable.bg_player_drawer);
            int pad = dp(5);
            list.setPadding(pad, pad, pad, pad);

            final PopupWindow popup = new PopupWindow(list,
                    anchor.getWidth(), ViewGroup.LayoutParams.WRAP_CONTENT, true);
            popup.setBackgroundDrawable(new ColorDrawable(0x00000000));
            popup.setOutsideTouchable(true);
            popup.setFocusable(true);

            for (int i = 0; i < options.size(); i++) {
                TextView tv = new TextView(ctx);
                tv.setText(options.get(i));
                tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.ts_22));
                tv.setTextColor(i == checkedIndex
                        ? getResources().getColor(R.color.color_02F8E1) : 0xFFFFFFFF);
                tv.setGravity(Gravity.CENTER_VERTICAL);
                tv.setSingleLine(true);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                tv.setPadding(dp(10), 0, dp(10), 0);
                tv.setBackgroundResource(i == checkedIndex
                        ? R.drawable.bg_chip_selected : R.drawable.bg_player_chip);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
                lp.topMargin = i == 0 ? 0 : dp(3);
                tv.setLayoutParams(lp);
                final int index = i;
                tv.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        popup.dismiss();
                        if (callback != null) callback.onPicked(index);
                    }
                });
                list.addView(tv);
            }
            popup.showAsDropDown(anchor, 0, dp(4));
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    interface OnOptionPicked {
        void onPicked(int index);
    }

    private void showPlayerPicker(View anchor) {
        if (mPlayerConfig == null || anchor == null) return;
        try {
            final int currentType = mPlayerConfig.getInt("pl");
            final ArrayList<Integer> players = PlayerHelper.getExistPlayerTypes();
            if (players.isEmpty()) return;
            List<String> names = new ArrayList<>();
            int checked = 0;
            for (int i = 0; i < players.size(); i++) {
                int type = players.get(i);
                names.add(PlayerHelper.getPlayerName(type));
                if (type == currentType) checked = i;
            }
            showOptionList(anchor, names, checked, new OnOptionPicked() {
                @Override
                public void onPicked(int index) {
                    if (index < 0 || index >= players.size()) return;
                    int thisPlayType = players.get(index);
                    if (thisPlayType != currentType) {
                        try {
                            mPlayerConfig.put("pl", thisPlayType);
                            updatePlayerCfgView();
                            listener.updatePlayerCfg();
                            listener.replay(false);
                            listener.setAllowSwitchPlayer(false);
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }
                }
            });
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void showCodecPicker(View anchor) {
        if (mPlayerConfig == null || anchor == null) return;
        try {
            final String current = mPlayerConfig.getString("ijk");
            final List<IJKCode> codecs = ApiConfig.get().getIjkCodes();
            if (codecs == null || codecs.isEmpty()) return;
            List<String> names = new ArrayList<>();
            int checked = 0;
            for (int i = 0; i < codecs.size(); i++) {
                String name = codecs.get(i).getName();
                names.add(getCodecShortName(name));
                if (name != null && name.equals(current)) checked = i;
            }
            showOptionList(anchor, names, checked, new OnOptionPicked() {
                @Override
                public void onPicked(int index) {
                    if (index < 0 || index >= codecs.size()) return;
                    String name = codecs.get(index).getName();
                    if (!name.equals(current)) {
                        try {
                            mPlayerConfig.put("ijk", name);
                            updatePlayerCfgView();
                            listener.updatePlayerCfg();
                            listener.replay(false);
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }
                }
            });
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void showScalePicker(View anchor) {
        if (mPlayerConfig == null || anchor == null) return;
        try {
            final int current = mPlayerConfig.getInt("sc");
            List<String> names = new ArrayList<>();
            for (int i = 0; i <= 5; i++) {
                names.add(PlayerHelper.getScaleName(i));
            }
            showOptionList(anchor, names, current, new OnOptionPicked() {
                @Override
                public void onPicked(int index) {
                    if (index < 0 || index > 5) return;
                    try {
                        mPlayerConfig.put("sc", index);
                        updatePlayerCfgView();
                        listener.updatePlayerCfg();
                        mControlWrapper.setScreenScaleType(index);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            });
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void showScaleDialog() {
        try {
            int scaleType = mPlayerConfig.getInt("sc");
            ArrayList<Integer> scales = new ArrayList<>();
            for (int i = 0; i <= 5; i++) scales.add(i);
            SelectDialog<Integer> dialog = new SelectDialog<>(mActivity);
            dialog.setTip("请选择画面尺寸");
            dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                @Override
                public void click(Integer value, int pos) {
                    try {
                        dialog.cancel();
                        mPlayerConfig.put("sc", value);
                        updatePlayerCfgView();
                        listener.updatePlayerCfg();
                        mControlWrapper.setScreenScaleType(value);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public String getDisplay(Integer value) {
                    return PlayerHelper.getScaleName(value);
                }
            }, new DiffUtil.ItemCallback<Integer>() {
                @Override
                public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                    return oldItem.intValue() == newItem.intValue();
                }

                @Override
                public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                    return oldItem.intValue() == newItem.intValue();
                }
            }, scales, scaleType);
            dialog.show();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void showSpeedDialog() {
        try {
            float speed = (float) mPlayerConfig.getDouble("sp");
            ArrayList<Float> speeds = new ArrayList<>();
            float[] speedOptions = {0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 3.0f};
            for (float value : speedOptions) speeds.add(value);
            int defaultPos = speeds.indexOf(speed);
            SelectDialog<Float> dialog = new SelectDialog<>(mActivity);
            dialog.setTip("请选择播放倍速");
            dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<Float>() {
                @Override
                public void click(Float value, int pos) {
                    try {
                        dialog.cancel();
                        mPlayerConfig.put("sp", value);
                        updatePlayerCfgView();
                        listener.updatePlayerCfg();
                        speed_old = value;
                        mControlWrapper.setSpeed(value);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public String getDisplay(Float value) {
                    return value + "x";
                }
            }, new DiffUtil.ItemCallback<Float>() {
                @Override
                public boolean areItemsTheSame(@NonNull @NotNull Float oldItem, @NonNull @NotNull Float newItem) {
                    return oldItem.equals(newItem);
                }

                @Override
                public boolean areContentsTheSame(@NonNull @NotNull Float oldItem, @NonNull @NotNull Float newItem) {
                    return oldItem.equals(newItem);
                }
            }, speeds, defaultPos < 0 ? 1 : defaultPos);
            dialog.show();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void hideLiveAboutBtn() {
        if (mControlWrapper != null && mControlWrapper.getDuration() == 0) {
            mPlayerSpeedBtn.setVisibility(GONE);
            mPlayerTimeStartEndText.setVisibility(GONE);
            mPlayerTimeStartBtn.setVisibility(GONE);
            mPlayerTimeSkipBtn.setVisibility(GONE);
            mPlayerTimeResetBtn.setVisibility(GONE);
        } else {
            mPlayerSpeedBtn.setVisibility(View.VISIBLE);
            mPlayerTimeStartEndText.setVisibility(GONE);
            mPlayerTimeStartBtn.setVisibility(View.VISIBLE);
            mPlayerTimeSkipBtn.setVisibility(View.VISIBLE);
            mPlayerTimeResetBtn.setVisibility(View.VISIBLE);
        }
    }

    public void initLandscapePortraitBtnInfo() {
        boolean isPortrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        // 时长显示：横竖屏都保留（当前时间 / 总时长）
        if (mCurrentTime != null) mCurrentTime.setVisibility(VISIBLE);
        if (mTotalTime != null) mTotalTime.setVisibility(VISIBLE);
        if (isPortrait) {
            if (backBtn != null) backBtn.setVisibility(INVISIBLE);
            if (mLockView != null) mLockView.setVisibility(INVISIBLE);
        }
        updatePortraitMenu(isPortrait);

        if (mLandscapePortraitBtn == null) return;
        boolean showButton = false;
        if (mControlWrapper != null && mActivity != null && !ScreenUtils.isTv(mActivity)) {
            int[] videoSize = mControlWrapper.getVideoSize();
            int width = videoSize[0];
            int height = videoSize[1];
            long duration = safeTimeMs(mControlWrapper.getDuration());
            boolean shortVideo = duration > 0 && duration < 12 * 60 * 1000L;
            showButton = width > 0 && height > 0 && (width <= height || shortVideo) && ScreenUtils.getSqrt(mActivity) < 10.0;
        }
        mLandscapePortraitBtn.setVisibility(showButton ? VISIBLE : GONE);
        if (showButton) mLandscapePortraitBtn.setText(isPortrait ? "横屏" : "竖屏");
    }

    private void updatePortraitMenu(boolean isPortrait) {
        if (isPortrait) {
            // 竖屏：这些功能都收进「更多功能」面板里，不再隐藏，否则菜单里会缺项
            mParseRoot.setVisibility(showParse ? VISIBLE : GONE);
            mPlayLabel.setVisibility(GONE);
            mPlayerTimeStartEndText.setVisibility(GONE);
            mCastBtn.setVisibility(Build.VERSION.SDK_INT < 30 ? GONE : VISIBLE);
            if (mPlayerConfig != null) updatePlayerCfgView();
            updateDanmuBtn();
            updateDanmuSearchUiBtn();
            updateFullScreenIcon(false);
            return;
        }

        mParseRoot.setVisibility(showParse ? VISIBLE : GONE);
        mPlayrefresh.setVisibility(VISIBLE);
        mPlayerScaleBtn.setVisibility(VISIBLE);
        mPlayerTimeStartEndText.setVisibility(GONE);
        mPlayerTimeStartBtn.setVisibility(VISIBLE);
        mPlayerTimeSkipBtn.setVisibility(VISIBLE);
        mPlayerTimeResetBtn.setVisibility(VISIBLE);
        mZimuBtn.setVisibility(VISIBLE);
        mScreenDisplay.setVisibility(VISIBLE);
        mCastBtn.setVisibility(Build.VERSION.SDK_INT < 30 ? GONE : VISIBLE);
        if (mPlayerConfig != null) updatePlayerCfgView();
        updateDanmuBtn();
        updateDanmuSearchUiBtn();
        updateFullScreenIcon(true);
    }

    /* ==================== 手机竖屏主控按钮（播放 / 弹幕 / 倍速 / 全屏 + 菜单） ==================== */

    private void bindMainControlButtons() {
        if (mPlayPauseBtn != null) {
            mPlayPauseBtn.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    FastClickCheckUtil.check(v);
                    togglePlay();
                    myHandle.removeCallbacks(myRunnable);
                    myHandle.postDelayed(myRunnable, myHandleSeconds);
                }
            });
        }
        if (mDanmuBtn != null) {
            mDanmuBtn.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    FastClickCheckUtil.check(v);
                    boolean opened = listener != null && listener.toggleDanmu();
                    updateDanmuSwitch(opened);
                    myHandle.removeCallbacks(myRunnable);
                    myHandle.postDelayed(myRunnable, myHandleSeconds);
                }
            });
            updateDanmuSwitch(DanmuHelper.isOpen());
        }
        if (mFullScreenBtn != null) {
            mFullScreenBtn.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    FastClickCheckUtil.check(v);
                    togglePlayerFullScreen();
                }
            });
        }
        if (mMenuBtn != null) {
            mMenuBtn.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    FastClickCheckUtil.check(v);
                    showDrawer();
                }
            });
        }
        updateFullScreenIcon(isPlayerFullScreen());
    }

    /** 切换全屏：非全屏 → 横屏铺满；全屏 → 退出全屏回到详情页 */
    private void togglePlayerFullScreen() {
        if (mActivity == null) return;
        if (isPlayerFullScreen()) {
            if (listener != null) {
                listener.exitFullScreen();
            } else {
                mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
            }
        } else {
            if (listener != null) {
                listener.enterFullScreen();
            }
            mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }
        updateFullScreenIcon(isPlayerFullScreen());
    }

    private void updateFullScreenIcon(boolean fullscreen) {
        if (mFullScreenIcon == null) return;
        mFullScreenIcon.setImageResource(fullscreen
                ? R.drawable.ic_player_exit_fullscreen
                : R.drawable.ic_player_fullscreen);
    }

    /* ==================== 设置抽屉 ==================== */

    private void bindDrawer() {
        if (mDrawerScrim != null) {
            mDrawerScrim.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    hideDrawer();
                }
            });
        }
        if (mDrawerClose != null) {
            mDrawerClose.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    hideDrawer();
                }
            });
        }
    }

    private boolean isDrawerShowing() {
        return mMorePanel != null && mMorePanel.getVisibility() == VISIBLE;
    }

    /** 展开抽屉：横屏从右侧向左滑出，竖屏自底部升起 */
    private void showDrawer() {
        if (mMorePanel == null) return;
        boolean portrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mMorePanel.getLayoutParams();
        if (portrait) {
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT;
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT;
            lp.gravity = Gravity.BOTTOM;
            lp.setMargins(dp(8), dp(8), dp(8), dp(8));
        } else {
            lp.width = dp(308);
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT;
            lp.gravity = Gravity.END | Gravity.TOP;
            lp.setMargins(0, dp(8), dp(8), dp(8));
        }
        mMorePanel.setLayoutParams(lp);
        refreshDanmuPanel();

        if (mDrawerScrim != null) {
            mDrawerScrim.setVisibility(VISIBLE);
            mDrawerScrim.setAlpha(0f);
            mDrawerScrim.animate().alpha(1f).setDuration(180).start();
        }
        mMorePanel.setVisibility(VISIBLE);
        mMorePanel.setTranslationX(portrait ? 0f : dp(340));
        mMorePanel.setTranslationY(portrait ? dp(420) : 0f);
        mMorePanel.animate()
                .translationX(0f)
                .translationY(0f)
                .setDuration(220)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        myHandle.removeCallbacks(myRunnable);
    }

    /** 收起抽屉 */
    void hideDrawer() {
        if (mMorePanel == null || mMorePanel.getVisibility() != VISIBLE) return;
        boolean portrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        mMorePanel.animate()
                .translationX(portrait ? 0f : dp(340))
                .translationY(portrait ? dp(420) : 0f)
                .setDuration(180)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        mMorePanel.setVisibility(GONE);
                    }
                })
                .start();
        if (mDrawerScrim != null) {
            mDrawerScrim.animate().alpha(0f).setDuration(160).withEndAction(new Runnable() {
                @Override
                public void run() {
                    mDrawerScrim.setVisibility(GONE);
                }
            }).start();
        }
    }

    /* ==================== 片头 / 片尾微调 ==================== */

    /** 点击记录当前位置；长按后左右滑动增减秒数 */
    private void bindTimeAdjust(final TextView btn, final boolean isStart) {
        if (btn == null) return;
        btn.setOnTouchListener(new OnTouchListener() {
            private float downRawX;
            private boolean adjusting;
            private int baseValue;
            private final Runnable enterAdjust = new Runnable() {
                @Override
                public void run() {
                    adjusting = true;
                    baseValue = getTimeCfg(isStart);
                    btn.setBackgroundResource(R.drawable.bg_chip_selected);
                    btn.setTextColor(getResources().getColor(R.color.color_02F8E1));
                }
            };

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        v.postDelayed(enterAdjust, 420);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (!adjusting) {
                            if (Math.abs(event.getRawX() - downRawX) > dp(10)) v.removeCallbacks(enterAdjust);
                        } else {
                            int delta = Math.round((event.getRawX() - downRawX) / dp(14));
                            setTimeCfg(isStart, baseValue + delta, false);
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.removeCallbacks(enterAdjust);
                        if (adjusting) {
                            adjusting = false;
                            btn.setBackgroundResource(R.drawable.bg_player_chip);
                            btn.setTextColor(0xFFFFFFFF);
                            setTimeCfg(isStart, getTimeCfg(isStart), true);
                            return true;
                        }
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    private int getTimeCfg(boolean isStart) {
        if (mPlayerConfig == null) return 0;
        return mPlayerConfig.optInt(isStart ? "st" : "et", 0);
    }

    private void setTimeCfg(boolean isStart, int value, boolean persist) {
        if (mPlayerConfig == null) return;
        int v = Math.max(0, Math.min(1800, value));
        try {
            mPlayerConfig.put(isStart ? "st" : "et", v);
            updatePlayerCfgView();
            if (persist && listener != null) listener.updatePlayerCfg();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /* ==================== 弹幕开关 ==================== */

    private void updateDanmuSwitch(boolean opened) {
        if (mDanmuBtn == null) return;
        mDanmuBtn.setText(opened ? "弹幕开" : "弹幕关");
        mDanmuBtn.setTextColor(opened ? getResources().getColor(R.color.color_02F8E1) : 0x99FFFFFF);
    }

    /* ==================== 抽屉内的弹幕设置 ==================== */

    private void bindDanmuSettingPanel() {
        tvDanmuSource = findViewById(R.id.tv_danmu_source);
        btnDanmuSearch = findViewById(R.id.btn_danmu_search);

        dmColorDefault = findViewById(R.id.dm_color_default);
        dmColorRandom = findViewById(R.id.dm_color_random);
        dmSpeed0 = findViewById(R.id.dm_speed_0);
        dmSpeed1 = findViewById(R.id.dm_speed_1);
        dmSpeed2 = findViewById(R.id.dm_speed_2);
        dmSpeed3 = findViewById(R.id.dm_speed_3);
        dmSizeSub = findViewById(R.id.dm_size_sub);
        dmSize = findViewById(R.id.dm_size);
        dmSizeAdd = findViewById(R.id.dm_size_add);
        dmLineSub = findViewById(R.id.dm_line_sub);
        dmLine = findViewById(R.id.dm_line);
        dmLineAdd = findViewById(R.id.dm_line_add);
        dmAlphaSub = findViewById(R.id.dm_alpha_sub);
        dmAlpha = findViewById(R.id.dm_alpha);
        dmAlphaAdd = findViewById(R.id.dm_alpha_add);

        if (btnDanmuSearch != null) {
            btnDanmuSearch.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    FastClickCheckUtil.check(v);
                    if (listener != null) listener.openSearchDanmuDialog();
                }
            });
        }
        // 点弹幕源名字也能更换，扩大点击热区
        if (tvDanmuSource != null) {
            tvDanmuSource.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    FastClickCheckUtil.check(v);
                    if (listener != null) listener.openSearchDanmuDialog();
                }
            });
        }
        if (dmColorDefault != null) {
            dmColorDefault.setOnClickListener(v -> {
                DanmuHelper.setRandomColor(false);
                refreshDanmuPanel();
                postDanmuSettingChanged(true);
            });
        }
        if (dmColorRandom != null) {
            dmColorRandom.setOnClickListener(v -> {
                DanmuHelper.setRandomColor(true);
                refreshDanmuPanel();
                postDanmuSettingChanged(true);
            });
        }
        bindDanmuSpeed(dmSpeed0, 0);
        bindDanmuSpeed(dmSpeed1, 1);
        bindDanmuSpeed(dmSpeed2, 2);
        bindDanmuSpeed(dmSpeed3, 3);

        bindStepper(dmSizeSub, dmSizeAdd, true);
        bindStepper(dmLineSub, dmLineAdd, false);
        bindStepper(dmAlphaSub, dmAlphaAdd, null);

        refreshDanmuPanel();
    }

    private void bindDanmuSpeed(TextView view, final int index) {
        if (view == null) return;
        view.setOnClickListener(v -> {
            DanmuHelper.setSpeed(DM_SPEED_RATES[index]);
            refreshDanmuPanel();
            postDanmuSettingChanged(false);
        });
    }

    /** type: TRUE=大小, FALSE=行数, null=透明度 */
    private void bindStepper(TextView sub, TextView add, final Boolean type) {
        if (sub != null) {
            sub.setOnClickListener(v -> {
                if (type == null) {
                    int value = Math.round(DanmuHelper.getAlpha() * 100);
                    if (value <= 10) return;
                    DanmuHelper.setAlpha((value - 10) / 100f);
                } else if (type) {
                    int value = Math.round(DanmuHelper.getSizeScale() * 10);
                    if (value <= 6) return;
                    DanmuHelper.setSizeScale((value - 1) / 10f);
                } else {
                    int value = DanmuHelper.getMaxLine();
                    if (value <= 1) return;
                    DanmuHelper.setMaxLine(value - 1);
                }
                refreshDanmuPanel();
                postDanmuSettingChanged(false);
            });
        }
        if (add != null) {
            add.setOnClickListener(v -> {
                if (type == null) {
                    int value = Math.round(DanmuHelper.getAlpha() * 100);
                    if (value >= 100) return;
                    DanmuHelper.setAlpha((value + 10) / 100f);
                } else if (type) {
                    int value = Math.round(DanmuHelper.getSizeScale() * 10);
                    if (value >= 20) return;
                    DanmuHelper.setSizeScale((value + 1) / 10f);
                } else {
                    int value = DanmuHelper.getMaxLine();
                    if (value >= 15) return;
                    DanmuHelper.setMaxLine(value + 1);
                }
                refreshDanmuPanel();
                postDanmuSettingChanged(false);
            });
        }
    }

    private void postDanmuSettingChanged(boolean colorChanged) {
        if (listener != null) {
            // 与 DanmuSettingDialog 保持一致：走 RefreshEvent 通知弹幕层刷新
            org.greenrobot.eventbus.EventBus.getDefault().post(
                    new com.github.tvbox.osc.event.RefreshEvent(
                            com.github.tvbox.osc.event.RefreshEvent.TYPE_SET_DANMU_SETTINGS, colorChanged));
        }
    }

    /** 刷新抽屉里的弹幕设置显示 */
    private void refreshDanmuPanel() {
        if (tvDanmuSource != null) tvDanmuSource.setText(currentDanmuSourceName());
        boolean random = DanmuHelper.useRandomColor();
        applyChoice(dmColorDefault, !random);
        applyChoice(dmColorRandom, random);

        float speed = DanmuHelper.getSpeed();
        int speedIdx = 2;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < DM_SPEED_RATES.length; i++) {
            float diff = Math.abs(DM_SPEED_RATES[i] - speed);
            if (diff < best) {
                best = diff;
                speedIdx = i;
            }
        }
        applyChoice(dmSpeed0, speedIdx == 0);
        applyChoice(dmSpeed1, speedIdx == 1);
        applyChoice(dmSpeed2, speedIdx == 2);
        applyChoice(dmSpeed3, speedIdx == 3);

        if (dmSize != null) dmSize.setText(Math.round(DanmuHelper.getSizeScale() * 10) + "档");
        if (dmLine != null) dmLine.setText(DanmuHelper.getMaxLine() + "行");
        if (dmAlpha != null) dmAlpha.setText(Math.round(DanmuHelper.getAlpha() * 100) + "%");
    }

    private void applyChoice(TextView view, boolean selected) {
        if (view == null) return;
        view.setBackgroundResource(selected ? R.drawable.bg_chip_selected : R.drawable.bg_player_chip);
        view.setTextColor(selected ? getResources().getColor(R.color.color_02F8E1) : 0xF2FFFFFF);
    }

    /** 当前在线弹幕源名称 */
    private String currentDanmuSourceName() {
        try {
            if (DanmakuApi.isUseDefault()) return "内置弹幕";
            String url = DanmakuApi.getDisplayApiUrl();
            if (TextUtils.isEmpty(url)) return "内置弹幕";
            String host = android.net.Uri.parse(url).getHost();
            return TextUtils.isEmpty(host) ? url : host;
        } catch (Throwable th) {
            return "内置弹幕";
        }
    }

    private void updatePlayPauseIcon(boolean playing) {
        if (mPlayPauseIcon == null) return;
        mPlayPauseIcon.setImageResource(playing ? R.drawable.ic_player_pause : R.drawable.ic_player_play);
    }

    void setLandscapePortrait() {
        if (mActivity == null) return;
        if (mActivity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
            mLandscapePortraitBtn.setText("竖屏");
            mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else {
            mLandscapePortraitBtn.setText("横屏");
            mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        initLandscapePortraitBtnInfo();
        updateFullScreenIcon(isPlayerFullScreen());
    }

    void initSubtitleInfo() {
        int subtitleTextSize = SubtitleHelper.getTextSize(mActivity);
        mSubtitleView.setTextSize(subtitleTextSize);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.player_vod_control_view;
    }

    public void showParse(boolean userJxList) {
        showParse = userJxList;
        // 解析线路已收进「更多功能」面板，竖屏同样可用
        mParseRoot.setVisibility(showParse ? VISIBLE : GONE);
    }

    private JSONObject mPlayerConfig = null;

    public void setPlayerConfig(JSONObject playerCfg) {
        this.mPlayerConfig = playerCfg;
        updatePlayerCfgView();
    }

    void updatePlayerCfgView() {
        try {
            int playerType = mPlayerConfig.getInt("pl");
            mPlayerBtn.setText(getPlayerShortName(playerType));
            mPlayerScaleBtn.setText(PlayerHelper.getScaleName(mPlayerConfig.getInt("sc")));
            mPlayerIJKBtn.setText(getCodecShortName(mPlayerConfig.getString("ijk")));
            View codecRow = findViewById(R.id.row_codec);
            if (codecRow != null) codecRow.setVisibility(playerType == 1 ? VISIBLE : GONE);
            mPlayerScaleBtn.setText(PlayerHelper.getScaleName(mPlayerConfig.getInt("sc")));
            mPlayerSpeedBtn.setText(mPlayerConfig.getDouble("sp") + "x");
            int start = mPlayerConfig.getInt("st");
            int end = mPlayerConfig.getInt("et");
            mPlayerTimeStartBtn.setText(start == 0 ? "片头" : stringForTime(start * 1000));
            mPlayerTimeSkipBtn.setText(end == 0 ? "片尾" : stringForTime(end * 1000));
            mAudioTrackBtn.setVisibility((playerType == 1 || playerType == 2) ? VISIBLE : GONE);
            mVideoTrackBtn.setVisibility((playerType == 1 || playerType == 2) ? VISIBLE : GONE);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private String getPlayerShortName(int playerType) {
        String playerName = PlayerHelper.getPlayerName(playerType);
        return playerName;
    }

    private String getCodecShortName(String codecName) {
        if ("硬解码".equals(codecName)) {
            return "硬解";
        }
        if ("软解码".equals(codecName)) {
            return "软解";
        }
        return codecName;
    }

    public void setTitle(String playTitleInfo) {
        mPlayTitle.setText(playTitleInfo);
        mPlayTitle1.setText(playTitleInfo);
    }

    public void setUrlTitle(String playTitleInfo) {
        mPlayTitle.setText(playTitleInfo);
    }

    public void resetSpeed() {
        skipEnd = true;
        mHandler.removeMessages(1004);
        mHandler.sendEmptyMessageDelayed(1004, 100);
    }

    public void setHasDanmu(boolean hasDanmu) {
        this.hasDanmu = hasDanmu;
        updateDanmuBtn();
    }

    public void updateDanmuBtn() {
        if (mDanmuSettingBtn == null) return;
        mDanmuSettingBtn.setVisibility(DanmuHelper.isOpen() ? VISIBLE : GONE);
        updatePlayLabelVisibility();
    }

    public void updateDanmuSearchUiBtn() {
        if (mDanmuSearchUiBtn == null) return;
        boolean hasDanmuSearchUi = ApiConfig.get().hasDanmuSearchUi();
        mDanmuSearchUiBtn.setVisibility(hasDanmuSearchUi ? VISIBLE : GONE);
        updatePlayLabelVisibility();
    }

    private void updatePlayLabelVisibility() {
        // 手机端「播放」文字标签已无意义（原为 TV 焦点提示），恒隐藏
        if (mPlayLabel != null) mPlayLabel.setVisibility(GONE);
    }

    public interface VodControlListener {
        void playNext(boolean rmProgress);

        void playPre();

        void showEpisodeDialog();

        void prepared();

        void changeParse(ParseBean pb);

        void updatePlayerCfg();

        void replay(boolean replay);

        void errReplay();

        void selectSubtitle();

        void selectAudioTrack();

        void selectVideoTrack();

        void showDanmuSetting();

        /** 打开「在线弹幕」搜索，默认回退到完整的弹幕设置对话框 */
        default void openSearchDanmuDialog() {
            showDanmuSetting();
        }

        /** 退出全屏（回到详情页），默认回退为切换竖屏 */
        default void exitFullScreen() {
        }

        /** 进入全屏（横屏铺满），默认空实现 */
        default void enterFullScreen() {
        }

        /** 控制条显示/隐藏回调：详情页用它来控制预览区域点击热区的显隐 */
        default void onControlsVisibilityChanged(boolean visible) {
        }

        boolean toggleDanmu();

        void searchDanmuUi(boolean longClick);

        void startPlayUrl(String url, HashMap<String, String> headers);

        void onM3u8ProxyUrl(String proxyUrl, String sourceUrl);

        void clickCast();

        void setAllowSwitchPlayer(boolean isAllow);
    }

    public void setListener(VodControlListener listener) {
        this.listener = listener;
    }

    private VodControlListener listener;

    private boolean skipEnd = true;

    private void keepSeekBarActive() {
        if (myHandle != null && myRunnable != null) {
            myHandle.removeCallbacks(myRunnable);
            myHandle.postDelayed(myRunnable, myHandleSeconds);
        }
        mHandler.removeMessages(1002);
        mHandler.removeMessages(1003);
        if (mBottomRoot != null && mBottomRoot.getVisibility() != VISIBLE) {
            mHandler.sendEmptyMessage(1002);
        }
    }

    private void beginSeekBarTracking() {
        keepSeekBarActive();
        if (mIsDragging) {
            return;
        }
        mIsDragging = true;
        mControlWrapper.stopProgress();
        mControlWrapper.stopFadeOut();
    }

    private void finishSeekBarTracking() {
        keepSeekBarActive();
        long newPosition = getSeekBarPosition(mSeekBar.getProgress());
        mControlWrapper.seekTo(newPosition);
        mIsDragging = false;
        mSeekBarKeyTracking = false;
        mControlWrapper.startProgress();
        mControlWrapper.startFadeOut();
    }

    private long getSeekBarPosition(int progress) {
        long duration = safeTimeMs(mControlWrapper.getDuration());
        int max = mSeekBar == null ? 0 : mSeekBar.getMax();
        if (duration <= 0 || max <= 0) {
            return 0;
        }
        return (duration * progress) / max;
    }

    private void updateSeekBarTime(int progress) {
        long newPosition = getSeekBarPosition(progress);
        if (mCurrentTime != null) {
            mCurrentTime.setText(stringForTime(safeTimeMs(newPosition)));
        }
    }

    private void moveSeekBarByKey(int dir) {
        int duration = safeTimeMs(mControlWrapper.getDuration());
        if (duration <= 0 || mSeekBar.getMax() <= 0) {
            return;
        }
        int progress = mSeekBar.getProgress() + getSeekBarKeyProgress(duration) * dir;
        if (progress < 0) {
            progress = 0;
        } else if (progress > mSeekBar.getMax()) {
            progress = mSeekBar.getMax();
        }
        mSeekBar.setProgress(progress);
        updateSeekBarTime(progress);
        updateSeekUI(safeTimeMs(mControlWrapper.getCurrentPosition()), safeTimeMs(getSeekBarPosition(progress)), duration);
    }

    private int getSeekBarKeyProgress(int duration) {
        long increment;
        if (duration > 3 * 60 * 60 * 1000) {
            increment = 5 * 60 * 1000;
        } else if (duration > 30 * 60 * 1000) {
            increment = 60 * 1000;
        } else if (duration > 15 * 60 * 1000) {
            increment = 30 * 1000;
        } else if (duration > 10 * 60 * 1000) {
            increment = 15 * 1000;
        } else {
            increment = 10 * 1000;
        }
        return Math.max(1, (int) (increment * mSeekBar.getMax() / duration));
    }

    @SuppressLint("SetTextI18n")
    @Override
    protected void setProgress(int duration, int position) {

        if (mIsDragging) {
            return;
        }
        super.setProgress(duration, position);
        if (skipEnd && position != 0 && duration != 0) {
            int et = 0;
            try {
                et = mPlayerConfig.getInt("et");
            } catch (JSONException e) {
                e.printStackTrace();
            }
            if (et > 0 && position + (et * 1000) >= duration) {
                skipEnd = false;
                listener.playNext(true);
            }
        }
        mCurrentTime.setText(stringForTime(position));
        mTotalTime.setText(stringForTime(duration));
        seekTime.setText(formatSeekTime(position) + " | " + formatSeekTime(duration)); //右上角进度条时间显示
        if (duration > 0) {
            mSeekBar.setEnabled(true);
            int pos = (int) (position * 1.0 / duration * mSeekBar.getMax());
            mSeekBar.setProgress(pos);
        } else {
            mSeekBar.setEnabled(false);
        }
        int percent = mControlWrapper.getBufferedPercentage();
        if (percent >= 95) {
            mSeekBar.setSecondaryProgress(mSeekBar.getMax());
        } else {
            mSeekBar.setSecondaryProgress(percent * 10);
        }
    }

    private static String formatSeekTime(int timeMs) {
        int totalSeconds = Math.max(0, timeMs) / 1000;
        int seconds = totalSeconds % 60;
        int minutes = (totalSeconds / 60) % 60;
        int hours = totalSeconds / 3600;
        return hours > 0
                ? String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }

    private boolean simSlideStart = false;
    private int simSeekPosition = 0;
    private long simSlideOffset = 0;
    private long lastSlideTime = 0;

    public void tvSlideStop() {
        if (!simSlideStart)
            return;
        mControlWrapper.seekTo(simSeekPosition);
        if (!mControlWrapper.isPlaying())
            mControlWrapper.start();
        simSlideStart = false;
        simSeekPosition = 0;
        simSlideOffset = 0;
    }
    public void tvSlideStart(int dir) {
        int duration = safeTimeMs(mControlWrapper.getDuration());
        if (duration <= 0)
            return;

        long currentTime = System.currentTimeMillis();
        final int baseSkip = 10000; // 基础跳转10秒
        final float accelerationFactor = 2.0f; // 连续操作时的加速因子
        final long threshold = 800; // 操作间隔阈值500ms

        if (!simSlideStart) {
            simSlideStart = true;
            simSlideOffset = (long) baseSkip * dir;
        } else {
            if (currentTime - lastSlideTime <= threshold) {
                simSlideOffset += (baseSkip * accelerationFactor * dir);
            } else {
                simSlideOffset = (long) baseSkip * dir;
            }
        }
        lastSlideTime = currentTime;
        int currentPosition = safeTimeMs(mControlWrapper.getCurrentPosition());
        int position = (int) (currentPosition + simSlideOffset);
        if (position > duration) position = duration;
        if (position < 0) position = 0;
        updateSeekUI(currentPosition, position, duration);
        simSeekPosition = position;
    }

    @Override
    protected void updateSeekUI(int curr, int seekTo, int duration) {
        super.updateSeekUI(curr, seekTo, duration);
        if (seekTo > curr) {
            mProgressIcon.setImageResource(R.drawable.exo_icon_fastforward);
        } else {
            mProgressIcon.setImageResource(R.drawable.exo_icon_rewind);
        }
        mProgressText.setText(stringForTime(seekTo) + " / " + stringForTime(duration));
        mHandler.sendEmptyMessage(1000);
        mHandler.removeMessages(1001);
        mHandler.sendEmptyMessageDelayed(1001, 1000);
    }

    @Override
    protected void onPlayStateChanged(int playState) {
        super.onPlayStateChanged(playState);
        videoPlayState = playState;
        switch (playState) {
            case VideoView.STATE_IDLE:
                break;
            case VideoView.STATE_PLAYING:
                updatePlayPauseIcon(true);
                initLandscapePortraitBtnInfo();
                startProgress();
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, myHandleSeconds);
                break;
            case VideoView.STATE_PAUSED:
                updatePlayPauseIcon(false);
                mPlayLoadNetSpeedRightTop.setVisibility(GONE);
                // 暂停后保留底部控制条（里面就有播放键），不再弹中央大按钮
                if (!isBottomVisible()) {
                    mHandler.removeMessages(1003);
                    mHandler.sendEmptyMessage(1002);
                }
                break;
            case VideoView.STATE_ERROR:
                listener.errReplay();
                break;
            case VideoView.STATE_PREPARED:
                mPlayLoadNetSpeed.setVisibility(GONE);
                hideLiveAboutBtn();
                listener.prepared();
                break;
            case VideoView.STATE_BUFFERED:
            case VideoView.STATE_PREPARING:
            case VideoView.STATE_BUFFERING:
                mPlayLoadNetSpeed.setVisibility(GONE);
                break;
            case VideoView.STATE_PLAYBACK_COMPLETED:
                listener.playNext(true);
                break;
        }
    }

    boolean isBottomVisible() {
        return mBottomRoot.getVisibility() == VISIBLE;
    }

    void showBottom() {
        mHandler.removeMessages(1003);
        mHandler.sendEmptyMessage(1002);
        mNextBtn.requestFocus();
    }

    void showUpBottom() {
        mHandler.removeMessages(1003);
        mHandler.sendEmptyMessage(1002);
        mPlayerTimeStartBtn.requestFocus();
    }

    void hideBottom() {
        mHandler.removeMessages(1002);
        hideDrawer();
        mHandler.sendEmptyMessage(1003);
    }

    /** 当前是否处于全屏播放状态（详情页内嵌预览视为非全屏） */
    public boolean isPlayerFullScreen() {
        return !previewMode;
    }

    /** 控制条当前是否可见 */
    public boolean isControlsVisible() {
        return isBottomVisible();
    }

    /** 显示控制条（外部点击预览区域时调用） */
    public void showControls() {
        if (isLocked()) return;
        showBottom();
        if (myHandle != null && myRunnable != null) {
            myHandle.removeCallbacks(myRunnable);
            myHandle.postDelayed(myRunnable, myHandleSeconds);
        }
    }

    /** 隐藏控制条 */
    public void hideControls() {
        hideBottom();
    }

    /** 切换控制条显隐：非全屏状态下点击播放区域调用 */
    public void toggleControls() {
        if (isLocked()) {
            showLockView();
            return;
        }
        if (isBottomVisible() || isDrawerShowing()) {
            hideBottom();
        } else {
            showControls();
        }
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        myHandle.removeCallbacks(myRunnable);
        if (super.onKeyEvent(event)) {
            return true;
        }
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && keyCode == KeyEvent.KEYCODE_MENU) {
            listener.showEpisodeDialog();
            hideBottom();
            return true;
        }
        if (isBottomVisible()) {
            mHandler.removeMessages(1002);
            mHandler.removeMessages(1003);
            myHandle.postDelayed(myRunnable, myHandleSeconds);
            return super.dispatchKeyEvent(event);
        }
        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                && videoPlayState == VideoView.STATE_ERROR
                && (keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
            showBottom();
            myHandle.postDelayed(myRunnable, myHandleSeconds);
            return true;
        }
        boolean isInPlayback = isInPlaybackState();
        if (action == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (isInPlayback) {
                    tvSlideStart(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    return true;
                }
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                if (isInPlayback) {
                    if (event.getRepeatCount() == 0) togglePlay();
                    return true;
                }
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (!isBottomVisible()) {
                    showBottom();
                    myHandle.postDelayed(myRunnable, myHandleSeconds);
                    return true;
                }
            }
        } else if (action == KeyEvent.ACTION_UP) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (isInPlayback) {
                    tvSlideStop();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }


    private boolean fromLongPress;
    private float speed_old = 1.0f;

    private void speedPlayStart(){
        fromLongPress = true;
        try {
            speed_old = (float) mPlayerConfig.getDouble("sp");
            float speed = 3.0f;
            mPlayerConfig.put("sp", speed);
            updatePlayerCfgView();
            listener.updatePlayerCfg();
            mControlWrapper.setSpeed(speed);
            findViewById(R.id.play_speed_3_container).setVisibility(View.VISIBLE);
        } catch (JSONException f) {
            f.printStackTrace();
        }
    }
    private void speedPlayEnd(){
        if (fromLongPress) {
            fromLongPress =false;
            try {
                float speed = speed_old;
                mPlayerConfig.put("sp", speed);
                updatePlayerCfgView();
                listener.updatePlayerCfg();
                mControlWrapper.setSpeed(speed);
            } catch (JSONException f) {
                f.printStackTrace();
            }
            findViewById(R.id.play_speed_3_container).setVisibility(View.GONE);
        }
    }
    @Override
    public void onLongPress(MotionEvent e) {
        if (e == null) return;
        if (!Hawk.get(HawkConfig.GESTURE_SPEED_BOOST, true)) return;
        if (videoPlayState == VideoView.STATE_PAUSED) return;
        if (!isInPlaybackState() || isLocked()) return;
        // 长按进入加速状态，随后上滑加速、下滑减速、右滑固定倍速
        enterSpeedBoost(e.getX(), e.getY());
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        handleSpeedBoostRelease(e);
        return super.onTouchEvent(e);
    }

    /**
     * 为播放菜单按钮补充遥控器确认键的长按处理。
     * 部分电视盒子不会把确认键长按分发为 View.OnLongClickListener。
     */
    private void setOnTvLongClickListener(final View view, final OnLongClickListener listener) {
        view.setOnLongClickListener(listener);
        view.setOnKeyListener(new OnKeyListener() {
            private boolean longPressTriggered;
            private final Runnable longPressRunnable = new Runnable() {
                @Override
                public void run() {
                    if (view.hasFocus() && view.isShown()) {
                        longPressTriggered = true;
                        view.performLongClick();
                    }
                }
            };

            private boolean isConfirmKey(int keyCode) {
                return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER
                        || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                        || keyCode == KeyEvent.KEYCODE_BUTTON_A;
            }

            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (!isConfirmKey(keyCode)) {
                    return false;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (event.getRepeatCount() == 0) {
                        longPressTriggered = false;
                        view.removeCallbacks(longPressRunnable);
                        view.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout());
                    }
                    return true;
                }
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    view.removeCallbacks(longPressRunnable);
                    if (!longPressTriggered) {
                        view.performClick();
                    }
                    return true;
                }
                return true;
            }
        });
    }


    private final Handler mmHandler = new Handler();
    private Runnable mLongPressRunnable;
    private static final long LONG_PRESS_DELAY = ViewConfiguration.getLongPressTimeout();
    private boolean isLongPressTriggered = false;

    private boolean setMinPlayTimeChange(String typeEt,boolean increase){
        myHandle.removeCallbacks(myRunnable);
        myHandle.postDelayed(myRunnable, myHandleSeconds);
        try {
            int currentValue = mPlayerConfig.optInt(typeEt, 0);
            if(currentValue!=0){
                int newValue = increase ? currentValue + 1 : currentValue - 1;
                if(newValue < 0) {
                    newValue = 0;
                }
                mPlayerConfig.put(typeEt,newValue);
                updatePlayerCfgView();
                listener.updatePlayerCfg();
                return true;
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return false;
    }
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isBottomVisible()) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP ) {
                if(mPlayerTimeStartBtn.hasFocus()){
                    if(setMinPlayTimeChange("st",true)){
                        return true;
                    }
                }
                View focusedView = mPlayBtnGroup.findFocus();
                if (focusedView instanceof TextView) {
                    return true;
                }
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN ) {
                if(mPlayerTimeStartBtn.hasFocus()){
                    if(setMinPlayTimeChange("st",false))return true;
                }
            }
            return super.onKeyDown(keyCode, event);
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.getRepeatCount() == 0) {
            isLongPressTriggered = false;
            mLongPressRunnable = new Runnable() {
                @Override
                public void run() {
                    speedPlayStart();
                    isLongPressTriggered = true;
                }
            };
            mmHandler.postDelayed(mLongPressRunnable, LONG_PRESS_DELAY);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            // 移除长按回调
            if (mLongPressRunnable != null) {
                mmHandler.removeCallbacks(mLongPressRunnable);
                mLongPressRunnable = null;
            }
            if (isLongPressTriggered) {
                speedPlayEnd();
            } else {
                if (!isBottomVisible()) {
                    showUpBottom();
                    myHandle.postDelayed(myRunnable, myHandleSeconds);
                }else {
                    return super.onKeyUp(keyCode, event);
                }
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onSingleTapConfirmed(MotionEvent e) {
        myHandle.removeCallbacks(myRunnable);
        if (!isBottomVisible()) {
            showBottom();
            // 闲置计时关闭
            myHandle.postDelayed(myRunnable, myHandleSeconds);
        } else {
            hideBottom();
        }
        return true;
    }
    
    private class LockRunnable implements Runnable {
        @Override
        public void run() {
            mLockView.setVisibility(INVISIBLE);
        }
    }
    
    @Override
    public boolean onBackPressed() {
        if (isClickBackBtn) {
            isClickBackBtn = false;
            if (isBottomVisible()) {
                hideBottom();
            }
            return false;
        }
        if (super.onBackPressed()) {
            return true;
        }
        if (isBottomVisible()) {
            hideBottom();
            return true;
        }
        return false;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mHandler.removeCallbacks(myRunnable2);
    }


    //尝试去bom
    public String getWebPlayUrlIfNeeded(String webPlayUrl) {
        if (webPlayUrl != null && !webPlayUrl.contains("127.0.0.1:9978") &&  webPlayUrl.contains(".m3u8")) {
            try {
                String urlEncode = URLEncoder.encode(webPlayUrl, "UTF-8");
                LOG.i("echo-BOM-------");
                return ControlManager.get().getAddress(true) + "proxy?go=bom&url=" + urlEncode;
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }
        return webPlayUrl;
    }

    public String encodeUrl(String url) {
        try {
            return URLEncoder.encode(url, "UTF-8");
        } catch (Exception e) {
            return url;
        }
    }

    public boolean switchPlayer(){
        try {
            int playerType= mPlayerConfig.getInt("pl");
            int p_type = (playerType == 1) ? playerType + 1 : (playerType == 2) ? playerType - 1 : playerType;
            if (p_type != playerType) {
                LOG.i("echo-switchPlayer: " + playerType + " -> " + p_type);
//                Toast.makeText(getContext(), "切换到"+(p_type==1?"IJK":"EXO"), Toast.LENGTH_SHORT).show();
                mPlayerConfig.put("pl", p_type);
                updatePlayerCfgView();
                listener.updatePlayerCfg();
            }else {
                LOG.i("echo-switchPlayer: skip unsupported playerType=" + playerType);
                return true;
            }
        }catch (Exception e){
            LOG.i("echo-switchPlayer error: " + e.getMessage());
            return true;
        }
        return false;
    }

    public void playM3u8(final String url, final HashMap<String, String> headers) {
        if(url.contains("url=")){
            listener.startPlayUrl(url, headers);
            return;
        }
        OkGo.getInstance().cancelTag("m3u8-1");
        OkGo.getInstance().cancelTag("m3u8-2");
        final HttpHeaders okGoHeaders = new HttpHeaders();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                okGoHeaders.put(entry.getKey(), entry.getValue());
            }
        }
        OkGo.<String>get(url)
                .tag("m3u8-1")
                .headers(okGoHeaders)
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        String content = response.body();
                        if (!content.startsWith("#EXTM3U")) {
                            listener.startPlayUrl(url, headers);
                            return;
                        }
                        String forwardUrl = extractForwardUrl(url, content);
                        if (forwardUrl.isEmpty()) {
                            LOG.i("echo-m3u81-to-play");
                            processM3u8Content(url, content, headers);
                        } else {
                            fetchAndProcessForwardUrl(forwardUrl, headers, okGoHeaders, url);
                        }
                    }

                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        return response.body().string();
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        LOG.e("echo-m3u8请求错误1: " + response.getException());
                        listener.startPlayUrl(url, headers);
                    }
                });
    }

    private String extractForwardUrl(String baseUrl, String content) {
        String[] lines = content.split("\\r?\\n",50);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                // 只需要找接下来的几行
                for (int j = i + 1; j < lines.length; j++) {
                    String targetLine = lines[j].trim();
                    if (targetLine.isEmpty()) continue;
                    if (isValidM3u8Line(targetLine)) {
                        return resolveForwardUrl(baseUrl, targetLine);
                    }
                }
            }
        }
        return "";
    }

    private boolean isValidM3u8Line(String line) {
        return !line.startsWith("#") && (line.endsWith(".m3u8") || line.contains(".m3u8?"));
    }

    private void processM3u8Content(String url, String content, HashMap<String, String> headers) {
        String basePath = getBasePath(url);
        RemoteServer.m3u8Content = M3u8.purify(basePath, content);
        if (RemoteServer.m3u8Content == null || M3u8.currentAdCount==0) {
            LOG.i("echo-m3u8内容解析：未检测到广告");
            listener.startPlayUrl(url, headers);
        } else {
            String proxyUrl = ControlManager.get().getAddress(true) + "proxyM3u8";
            listener.onM3u8ProxyUrl(proxyUrl, url);
            listener.startPlayUrl(proxyUrl, headers);
            Toast.makeText(getContext(), "已移除视频广告 "+M3u8.currentAdCount+" 条", Toast.LENGTH_SHORT).show();
        }
    }

    private void fetchAndProcessForwardUrl(final String forwardUrl, final HashMap<String, String> headers,
                                           HttpHeaders okGoHeaders, final String fallbackUrl) {
        OkGo.<String>get(forwardUrl)
                .tag("m3u8-2")
                .headers(okGoHeaders)
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        String content = response.body();
                        LOG.i("echo-m3u82-to-play");
                        processM3u8Content(forwardUrl, content, headers);
                    }
                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        return response.body().string();
                    }
                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        LOG.e("echo-重定向 m3u8 请求错误: " + response.getException());
                        listener.startPlayUrl(fallbackUrl, headers);
                    }
                });
    }

    private String getBasePath(String url) {
        int ilast = url.lastIndexOf('/');
        return url.substring(0, ilast + 1);
    }

    private String resolveForwardUrl(String baseUrl, String line) {
        try {
            // 使用 URL 构造器自动解析相对路径
            URL base = new URL(baseUrl);
            URL resolved = new URL(base, line);
            return resolved.toString();
        } catch (MalformedURLException e) {
            // 出现异常时可以记录日志，并返回原始 line
            LOG.e("echo-resolveForwardUrl异常: " + e.getMessage());
            return line;
        }
    }

    public String firstUrlByArray(String url)
    {
        try {
            JSONArray urlArray = new JSONArray(url);
            for (int i = 0; i < urlArray.length(); i++) {
                String item = urlArray.getString(i);
                if (item.contains("http")) {
                    url = item;
                    break; // 找到第一个立即终止循环
                }
            }
        } catch (JSONException e) {
        }
        return url;
    }

    public void evaluateScript(SourceBean sourceBean,String url, WebView web_view, XWalkView xWalk_view){
        String clickSelector = sourceBean.getClickSelector().trim();
        clickSelector=clickSelector.isEmpty()?VideoParseRuler.getHostScript(url):clickSelector;
        if (!clickSelector.isEmpty()) {
            String selector;
            if (clickSelector.contains(";") && !clickSelector.endsWith(";")) {
                String[] parts = clickSelector.split(";", 2);
                if (!url.contains(parts[0])) {
                    return;
                }
                selector = parts[1].trim();
            } else {
                selector = clickSelector.trim();
            }
            // 构造点击的 JS 代码
            String js = selector;
//            if(!selector.contains("click()"))js+=".click();";
            LOG.i("echo-javascript:" + js);
            if(web_view!=null){
                //4.4以上才支持这种写法
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    web_view.evaluateJavascript(js, null);
                } else {
                    web_view.loadUrl("javascript:" + js);
                }
            }
            if(xWalk_view!=null){
                //4.0+开始全部支持这种写法
                xWalk_view.evaluateJavascript(js, null);
            }
        }
    }

    public void stopOther()
    {
        Thunder.stop(false);//停止磁力下载
        Jianpian.finish();//停止p2p下载
        App.getInstance().setDashData(null);
    }

    private static class CircleThumbDrawable extends Drawable {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int normalSize;
        private final int activeSize;
        private final float strokeWidth;
        private boolean active;

        CircleThumbDrawable(Context context) {
            normalSize = dp(context, 12);
            activeSize = dp(context, 16);
            strokeWidth = dp(context, 2);
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(Color.WHITE);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(strokeWidth);
            strokePaint.setColor(Color.parseColor("#FF4081"));
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            float cx = bounds.exactCenterX();
            float cy = bounds.exactCenterY();
            int size = active ? activeSize : normalSize;
            float radius = Math.max(0, size / 2f - strokeWidth / 2f);
            canvas.drawCircle(cx, cy, radius, fillPaint);
            canvas.drawCircle(cx, cy, radius, strokePaint);
        }

        @Override
        public boolean isStateful() {
            return true;
        }

        @Override
        protected boolean onStateChange(int[] stateSet) {
            boolean newActive = false;
            if (stateSet != null) {
                for (int state : stateSet) {
                    if (state == android.R.attr.state_pressed
                            || state == android.R.attr.state_focused
                            || state == android.R.attr.state_selected) {
                        newActive = true;
                        break;
                    }
                }
            }
            if (active == newActive) {
                return false;
            }
            active = newActive;
            invalidateSelf();
            return true;
        }

        @Override
        public void setAlpha(int alpha) {
            fillPaint.setAlpha(alpha);
            strokePaint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            fillPaint.setColorFilter(colorFilter);
            strokePaint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return activeSize;
        }

        @Override
        public int getIntrinsicHeight() {
            return activeSize;
        }

        private static int dp(Context context, float value) {
            return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
        }
    }
}
