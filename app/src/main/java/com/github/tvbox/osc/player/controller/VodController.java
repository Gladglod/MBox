package com.github.tvbox.osc.player.controller;

import android.app.Activity;
import android.animation.ValueAnimator;
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
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.PopupWindow;
import android.widget.ScrollView;
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
import com.github.tvbox.osc.ui.dialog.NumberInputDialog;
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
import xyz.doikki.videoplayer.util.PlayerUtils;

import static xyz.doikki.videoplayer.util.PlayerUtils.stringForTime;
import static xyz.doikki.videoplayer.util.PlayerUtils.safeTimeMs;

public class VodController extends BaseController {
    private static final float PORTRAIT_EPISODE_SWIPE_DP = 80f;
    private static final long PORTRAIT_EPISODE_TITLE_SHOW_MS = 3000L;

    /* ===== 长按加速 ===== */
    /** 横向滑动多少 dp 对应 0.2 倍速的变化（左减右加） */
    private static final float SPEED_BOOST_STEP_DP = 56f;
    private static final float SPEED_BOOST_MIN = 0.5f;
    private static final float SPEED_BOOST_MAX = 3.0f;
    /** 上滑多少 dp 判定为「固定当前倍速」 */
    private static final float SPEED_PIN_SWIPE_DP = 64f;
    /** 手势振动反馈：进入加速 / 固定倍速时轻微一震 */
    private static final long SPEED_VIBRATE_MS = 22L;
    private static final int SPEED_VIBRATE_AMPLITUDE = 80;
    /** 固定态抬手后提示文字停留时长 */
    private static final long SPEED_TIP_LINGER_MS = 1100L;
    /** 倍速按钮常态文字颜色（始终叠在深色播放条上，与 XML 一致） */
    private static final int SPEED_BTN_COLOR_NORMAL = 0xF2FFFFFF;
    /** 倍速菜单里的预置档位（升序；展示时按「最高倍速」设置过滤） */
    private static final float[] SPEED_PRESET_VALUES = {
            0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f
    };

    private boolean speedBoostActive;
    private float speedBoostStartX;
    private float speedBoostStartY;
    private float speedBoostBaseSpeed = 1.0f;
    private float speedBoostSpeed = 1.0f;
    /** 上滑固定后：抬手保持该倍速；未固定则抬手回落原速。重新长按自动解除 */
    private boolean speedBoostLocked;
    /** 一次上滑只固定一次，手指退回阈值一半以内才允许再次触发 */
    private boolean speedPinSwipeConsumed;
    /** 进入本轮手势前的原速，未固定抬手时回到这里 */
    private float speedBoostNaturalSpeed = 1.0f;
    /** 顶部加速提示浮层（数值 + 小字手势提示，无底框纯文字） */
    private LinearLayout mSpeedBoostTip;
    private TextView mSpeedBoostTipValue;
    private TextView mSpeedBoostTipHint;

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
                        // 暂停时只保留中央播放图标，剧名不再带出来（点击收起后保持隐藏）
                        mPlayTitle.setVisibility(GONE);
                        if (videoPlayState == VideoView.STATE_PAUSED) {
                            showPauseRoot();
                        } else {
                            hidePauseRoot();
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
     * 长按进入加速状态：
     * 左右滑动按 0.2 一步增减倍速（左减右加）；上滑固定当前倍速。
     * 若上一轮已固定，重新长按会自动解除固定，回到临时变速状态。
     */
    private void enterSpeedBoost(float downX, float downY) {
        speedBoostActive = true;
        speedBoostStartX = downX;
        speedBoostStartY = downY;
        speedBoostBaseSpeed = currentPlaySpeed();
        speedBoostSpeed = speedBoostBaseSpeed;
        if (speedBoostLocked) {
            // 再次长按即解除固定：本轮属于临时变速，抬手不保持
            speedBoostLocked = false;
        }
        speedBoostNaturalSpeed = speedBoostBaseSpeed;
        speedPinSwipeConsumed = false;
        vibrateBoostFeedback();
        showSpeedBoostTip(speedBoostSpeed);
    }

    private void updateSpeedBoost(float currentX) {
        if (!speedBoostActive) return;
        float step = getResources().getDisplayMetrics().density * SPEED_BOOST_STEP_DP;
        if (step <= 0) return;
        float maxSpeed = Hawk.get(HawkConfig.GESTURE_MAX_SPEED, SPEED_BOOST_MAX);
        if (maxSpeed < SPEED_BOOST_MIN) maxSpeed = SPEED_BOOST_MIN;
        // 横向滑动位移换算成 0.2 一档：右移为正加速，左移为负减速，上下位移不参与
        float gear = Math.round((currentX - speedBoostStartX) / step);
        float speed = speedBoostBaseSpeed + gear * 0.2f;
        if (speed < SPEED_BOOST_MIN) speed = SPEED_BOOST_MIN;
        if (speed > maxSpeed) speed = maxSpeed;
        speed = Math.round(speed * 10) / 10f;
        if (speed == speedBoostSpeed) return;
        speedBoostSpeed = speed;
        if (mControlWrapper != null) mControlWrapper.setSpeed(speed);
        showSpeedBoostTip(speed);
        syncSpeedButton(speed, speedBoostLocked, false);
    }

    /**
     * 上滑固定当前倍速。固定后抬手保持该倍速（否则视为临时变速，回落原速）。
     * 手指退回阈值一半以内重置标记，但本轮手势内不重复触发固定。
     */
    private void handleSpeedPinSwipe(MotionEvent event) {
        if (event == null) return;
        float threshold = getResources().getDisplayMetrics().density * SPEED_PIN_SWIPE_DP;
        if (threshold <= 0) return;
        float dy = speedBoostStartY - event.getY(); // 上滑为正
        if (dy < threshold * 0.5f) {
            speedPinSwipeConsumed = false;
            return;
        }
        if (dy < threshold || speedPinSwipeConsumed || speedBoostLocked) return;
        speedPinSwipeConsumed = true;
        speedBoostLocked = true;
        // 固定后以「此刻的手指位置」作为新的水平基准，否则接着左右滑动会把固定前走过的
        // 位移再算一遍，倍速会突然跳档
        speedBoostStartX = event.getX();
        speedBoostStartY = event.getY();
        speedBoostBaseSpeed = speedBoostSpeed;
        vibrateBoostFeedback();
        showSpeedBoostTip(speedBoostSpeed);
        syncSpeedButton(speedBoostSpeed, true, false);
    }

    private void exitSpeedBoost() {
        if (!speedBoostActive) return;
        speedBoostActive = false;
        if (mSpeedBoostTip != null) mSpeedBoostTip.setVisibility(GONE);
        if (speedBoostLocked) {
            // 已固定：抬手保持当前倍速并落库；提示文字多留一会儿，让用户看到「已固定」的结果
            speedBoostBaseSpeed = speedBoostSpeed;
            syncSpeedButton(speedBoostSpeed, true, true);
            showSpeedBoostTip(speedBoostSpeed);
            if (mHandler != null) {
                mHandler.removeCallbacks(speedTipHideRunnable);
                mHandler.postDelayed(speedTipHideRunnable, SPEED_TIP_LINGER_MS);
            }
            return;
        }
        // 未固定：临时变速，抬手回落到进入手势时的原速
        if (mControlWrapper != null) mControlWrapper.setSpeed(speedBoostNaturalSpeed);
        speedBoostSpeed = speedBoostNaturalSpeed;
        syncSpeedButton(speedBoostNaturalSpeed, false, true);
    }

    /** 固定态抬手后，提示文字延迟收起（再次长按时会被新一轮手势重新点亮） */
    private final Runnable speedTipHideRunnable = new Runnable() {
        @Override
        public void run() {
            if (speedBoostActive) return;
            if (mSpeedBoostTip != null) mSpeedBoostTip.setVisibility(GONE);
        }
    };

    private void showSpeedBoostTip(float speed) {
        if (mSpeedBoostTip == null) return;
        if (mSpeedBoostTipValue != null) {
            mSpeedBoostTipValue.setText(fmtSpeed(speed) + "x");
        }
        if (mSpeedBoostTipHint != null) {
            mSpeedBoostTipHint.setText(speedBoostLocked ? "已固定 · 再长按可解除" : "左右滑动调整倍速 · 上滑固定");
        }
        mSpeedBoostTip.setVisibility(VISIBLE);
    }

    /** 倍速浮点转展示文本：最多两位小数并去掉无意义的 0（1x → 1.0x，1.75 保持 1.75） */
    private static String fmtSpeed(double speed) {
        try {
            String text = new java.math.BigDecimal(speed)
                    .setScale(2, java.math.BigDecimal.ROUND_HALF_UP)
                    .stripTrailingZeros()
                    .toPlainString();
            if (text.endsWith(".")) text = text.substring(0, text.length() - 1);
            if (!text.contains(".")) text = text + ".0";
            return text;
        } catch (Throwable t) {
            return String.valueOf(Math.round(speed * 100) / 100.0);
        }
    }

    /** 取「固定」态倍速按钮高亮色（强调色，随日 / 夜间主题切换） */
    private int getSpeedLockColor() {
        try {
            return androidx.core.content.ContextCompat.getColor(getContext(), R.color.ui_accent);
        } catch (Throwable ignore) {
            return 0xFF00A396;
        }
    }

    /**
     * 把长按手势得到的倍速同步到「倍速」按钮与播放配置，并反映「固定」状态。
     *
     * <p>persist=true 时通知监听器落盘（固定 / 解除 / 手势结束等离散时刻）；
     * 滑动过程中逐帧调用时传 false，仅刷新内存与按钮文本，避免频繁写盘。</p>
     */
    private void syncSpeedButton(float speed, boolean locked, boolean persist) {
        if (mPlayerSpeedBtn == null) return;
        try {
            if (mPlayerConfig != null) mPlayerConfig.put("sp", speed);
        } catch (JSONException ignore) { }
        mPlayerSpeedBtn.setText(fmtSpeed(speed) + "x");
        mPlayerSpeedBtn.setTextColor(locked ? getSpeedLockColor() : SPEED_BTN_COLOR_NORMAL);
        if (persist && listener != null) listener.updatePlayerCfg();
    }

    /** 进入加速 / 固定倍速时的轻微振动反馈（没有振动马达或权限被拒时静默降级） */
    private void vibrateBoostFeedback() {
        vibrateOnce(SPEED_VIBRATE_MS, SPEED_VIBRATE_AMPLITUDE);
    }

    /** 扇形菜单跟手选择时，切到下一项给一次更轻的「哒」 */
    private void vibrateTick() {
        vibrateOnce(8L, 40);
    }

    private void vibrateOnce(long ms, int amplitude) {
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
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(ms, amplitude));
            } else {
                vibrator.vibrate(ms);
            }
        } catch (Throwable ignore) {
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
                // 长按后：左右滑动按 0.2 一档变速（左减右加）；上滑固定当前倍速
                handleSpeedPinSwipe(event);
                updateSpeedBoost(event.getX());
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
        // 0 = 用户关闭了双击快进快退
        if (seekMs <= 0) return true;
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
            handleSpeedPinSwipe(e2);
            updateSpeedBoost(e2.getX());
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
    private LinearLayout mRowPrimary;        // 按钮主行（竖屏单行 / 横屏按钮行共用）
    private LinearLayout mProgressRow;       // 进度条块（竖屏内联，横屏独立成行）
    private View mBottomSpacer;              // 横屏时把倍速推到右侧的空白
    private LinearLayout mPlayPauseBtn;      // 播放·暂停
    private ImageView mPlayPauseIcon;
    private TextView mDanmuBtn;              // 弹幕开关
    private ImageView mDanmuCfgBtn;          // 弹幕设置（弹幕开关右侧的小齿轮）
    private LinearLayout mPortraitBtn;       // 竖屏满屏（短剧）
    private TextView mPortraitLabel;
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
        // 详情页内嵌预览区域很小，锁定没有意义，也会挡住控制条
        if (previewMode) {
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

    /* ==================== 锁定态触摸 ==================== */

    private float lockedDownX;
    private float lockedDownY;
    private boolean lockedLongPressPending;

    private final Runnable lockedLongPressRunnable = new Runnable() {
        @Override
        public void run() {
            lockedLongPressPending = false;
            if (!isLock) return;
            startSpeedBoostWhileLocked(lockedDownX, lockedDownY);
        }
    };

    /**
     * 锁定后普通点击 / 滑动一律吞掉（防误触），只保留长按加速与返回退出。
     * 这里不走手势识别器，避免解锁状态下才该有的双击、滑动、单击等行为一起被放开。
     */
    private void handleLockedTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lockedDownX = event.getX();
                lockedDownY = event.getY();
                lockedLongPressPending = true;
                mHandler.removeCallbacks(lockedLongPressRunnable);
                mHandler.postDelayed(lockedLongPressRunnable, ViewConfiguration.getLongPressTimeout());
                break;
            case MotionEvent.ACTION_MOVE:
                if (speedBoostActive) {
                    handleSpeedPinSwipe(event);
                    updateSpeedBoost(event.getX());
                } else if (lockedLongPressPending) {
                    int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                    if (Math.abs(event.getX() - lockedDownX) > slop
                            || Math.abs(event.getY() - lockedDownY) > slop) {
                        lockedLongPressPending = false;
                        mHandler.removeCallbacks(lockedLongPressRunnable);
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mHandler.removeCallbacks(lockedLongPressRunnable);
                lockedLongPressPending = false;
                if (speedBoostActive) {
                    exitSpeedBoost();
                } else {
                    // 轻点一下唤出锁图标，方便解除锁定
                    showLockView();
                }
                break;
            default:
                break;
        }
    }

    /** 锁定状态下的长按加速：受「锁定时可加速」开关控制 */
    private void startSpeedBoostWhileLocked(float x, float y) {
        if (!Hawk.get(HawkConfig.GESTURE_SPEED_BOOST, true)) return;
        if (!Hawk.get(HawkConfig.GESTURE_SPEED_BOOST_LOCKED, true)) return;
        if (videoPlayState == VideoView.STATE_PAUSED) return;
        if (!isInPlaybackState()) return;
        enterSpeedBoost(x, y);
    }

    public void setPreviewMode(boolean previewMode) {
        this.previewMode = previewMode;
        mHandler.removeCallbacks(lockRunnable);
        if (mLockView != null) {
            mLockView.setVisibility(INVISIBLE);
        }
        updateFullScreenIcon(!previewMode);
        applyControlBarMode();
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
        mSpeedBoostTipValue = findViewById(R.id.tv_speed_boost_value);
        mSpeedBoostTipHint = findViewById(R.id.tv_speed_boost_hint);
        mPlayPauseBtn = findViewById(R.id.btn_play_pause);
        mPlayPauseIcon = findViewById(R.id.iv_play_pause);
        mDanmuBtn = findViewById(R.id.btn_danmu);
        mDanmuCfgBtn = findViewById(R.id.btn_danmu_cfg);
        mRowPrimary = findViewById(R.id.bottom_row_primary);
        mProgressRow = findViewById(R.id.bottom_progress_row);
        mBottomSpacer = findViewById(R.id.bottom_spacer);
        mPortraitBtn = findViewById(R.id.btn_portrait);
        mPortraitLabel = findViewById(R.id.tv_portrait);
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
                if (!isLock) {
                    // 未锁定：不消费，事件继续冒泡给手势识别器
                    return false;
                }
                // 已锁定：吞掉全部点击与滑动防误触，只放行长按加速这一条通道
                handleLockedTouch(event);
                return true;
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
                showSpeedPicker(view);
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
                    // 长按重置倍速到 1.0x 时，同步清掉长按手势的滑动基准与固定态
                    speedBoostLocked = false;
                    speedBoostBaseSpeed = 1.0f;
                    speedBoostSpeed = 1.0f;
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
        showOptionList(anchor, options, checkedIndex, callback, 0);
    }

    private void showOptionList(View anchor, final List<String> options, int checkedIndex,
                                final OnOptionPicked callback, int minWidthPx) {
        if (anchor == null || options == null || options.isEmpty()) return;
        try {
            final Context ctx = getContext();
            LinearLayout list = new LinearLayout(ctx);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setBackgroundResource(R.drawable.bg_player_drawer);
            int pad = dp(5);
            list.setPadding(pad, pad, pad, pad);

            // 宽度至少跟住锚点；倍速按钮这种小锚点给个兜底最小宽，保证选项文字完整
            float popupWidth = Math.max(anchor.getWidth(), minWidthPx);
            if (popupWidth <= 0) popupWidth = dp(80);
            final PopupWindow popup = new PopupWindow(list,
                    (int) popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true);
            popup.setBackgroundDrawable(new ColorDrawable(0x00000000));
            popup.setOutsideTouchable(true);
            popup.setFocusable(true);
            applyImmersiveToPopup(popup);

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
            reApplyImmersiveToPopup(popup);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    interface OnOptionPicked {
        void onPicked(int index);
    }

    /**
     * 沉浸式全屏（系统栏隐藏）时，弹窗是一个会抢焦点的独立窗口，
     * 系统栏会按弹窗窗口自己的系统 UI 标志重新渲染，于是一弹窗状态栏就冒出来。
     * 这里把宿主 Activity 的沉浸标志复制到弹窗内容上，保持系统栏隐藏；
     * 宿主不在沉浸式时什么都不做，交给系统默认表现。
     */
    private void applyImmersiveToPopup(PopupWindow popup) {
        try {
            Activity activity = PlayerUtils.scanForActivity(getContext());
            if (activity == null || popup == null) return;
            View decor = activity.getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            int hidden = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            if ((flags & hidden) != hidden) return;
            popup.getContentView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | hidden
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } catch (Throwable ignore) {
        }
    }

    /**
     * 真机上，attach 之前设置的 systemUiVisibility 会被部分 ROM 忽略，导致弹出
     * 倍速 / 画面比例这类锚点列表时状态栏重新露出。展示之后再往内容 View（以及它的
     * 父容器 PopupDecorView）补挂一遍，双保险。
     */
    private void reApplyImmersiveToPopup(final PopupWindow popup) {
        try {
            Activity activity = PlayerUtils.scanForActivity(getContext());
            if (activity == null || popup == null) return;
            View decor = activity.getWindow().getDecorView();
            final int flags = decor.getSystemUiVisibility();
            int hidden = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            if ((flags & hidden) != hidden) return;
            popup.getContentView().post(new Runnable() {
                @Override
                public void run() {
                    try {
                        popup.getContentView().setSystemUiVisibility(flags);
                        View parent = popup.getContentView().getParent() instanceof View
                                ? (View) popup.getContentView().getParent() : null;
                        if (parent != null) parent.setSystemUiVisibility(flags);
                    } catch (Throwable ignore) {
                    }
                }
            });
        } catch (Throwable ignore) {
        }
    }

    /**
     * 倍速：在「倍速」按钮正上方弹出一列窄菜单（一行一个档位），最后一项是「自定义」。
     *
     * <p>档位按当前倍速动态生成：命中预置档位就直接高亮；如果当前是手势 / 输入得到的
     * 非档位值（例如 1.33x），会把它作为一个额外的选项插进来并高亮，
     * 避免出现「菜单里没有任何一项被选中、用户无从点选」的情况。</p>
     */
    private void showSpeedPicker(View anchor) {
        if (mPlayerConfig == null || anchor == null) return;
        final float current = currentPlaySpeed();
        float max = Hawk.get(HawkConfig.GESTURE_MAX_SPEED, SPEED_BOOST_MAX);
        if (max < SPEED_BOOST_MIN) max = SPEED_BOOST_MIN;

        List<Float> values = new ArrayList<>();
        for (float preset : SPEED_PRESET_VALUES) {
            if (preset <= max + 0.001f) values.add(preset);
        }
        if (values.isEmpty()) values.add(1.0f);
        boolean matched = false;
        for (float v : values) {
            if (Math.abs(v - current) < 0.005f) {
                matched = true;
                break;
            }
        }
        if (!matched) {
            int pos = 0;
            while (pos < values.size() && values.get(pos) < current) pos++;
            values.add(pos, current);
        }

        List<String> options = new ArrayList<>();
        int checked = -1;
        for (int i = 0; i < values.size(); i++) {
            float v = values.get(i);
            options.add(fmtSpeed(v) + "x");
            if (Math.abs(v - current) < 0.005f) checked = i;
        }
        final int customIndex = options.size();
        options.add("自定义");

        showOptionListAbove(anchor, options, checked, new OnOptionPicked() {
            @Override
            public void onPicked(int index) {
                if (index == customIndex) {
                    showCustomSpeedDialog();
                    return;
                }
                if (index < 0 || index >= values.size()) return;
                applySpeed(values.get(index));
            }
        }, dp(84));
    }

    /**
     * 在锚点正上方弹出的窄列表（一行一项），供播放条上的「画面比例 / 倍速」使用。
     * 与抽屉里的下拉列表同一套视觉，只是定位改到按钮上方；条目多时最高只占屏幕一半并滚动。
     */
    private void showOptionListAbove(View anchor, final List<String> options, int checkedIndex,
                                     final OnOptionPicked callback, int minWidthPx) {
        if (anchor == null || options == null || options.isEmpty()) return;
        try {
            final Context ctx = getContext();
            final int pad = dp(5);

            LinearLayout list = new LinearLayout(ctx);
            list.setOrientation(LinearLayout.VERTICAL);

            // 列表放进 ScrollView：词条超过半屏时滚动，面板本身不会顶到画面顶端
            ScrollView scroll = new ScrollView(ctx);
            scroll.setBackgroundResource(R.drawable.bg_glass_menu);
            scroll.setPadding(pad, pad, pad, pad);
            scroll.setClipToPadding(false);
            scroll.setVerticalScrollBarEnabled(true);
            scroll.setScrollbarFadingEnabled(false);
            scroll.addView(list, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            int popupWidth = Math.max(anchor.getWidth(), minWidthPx);
            if (popupWidth <= 0) popupWidth = dp(80);
            final PopupWindow popup = new PopupWindow(scroll, popupWidth,
                    ViewGroup.LayoutParams.WRAP_CONTENT, true);
            popup.setBackgroundDrawable(new ColorDrawable(0x00000000));
            popup.setOutsideTouchable(true);
            popup.setFocusable(true);
            applyImmersiveToPopup(popup);
            // 菜单打开期间不要自动收起控制条，否则锚点跟着消失、菜单会悬在半空
            if (myHandle != null) myHandle.removeCallbacks(myRunnable);
            popup.setOnDismissListener(new PopupWindow.OnDismissListener() {
                @Override
                public void onDismiss() {
                    if (myHandle != null && myRunnable != null) {
                        myHandle.removeCallbacks(myRunnable);
                        myHandle.postDelayed(myRunnable, myHandleSeconds);
                    }
                }
            });

            for (int i = 0; i < options.size(); i++) {
                TextView tv = new TextView(ctx);
                tv.setText(options.get(i));
                tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.ts_22));
                tv.setTextColor(i == checkedIndex
                        ? getResources().getColor(R.color.color_02F8E1) : 0xFFFFFFFF);
                tv.setGravity(Gravity.CENTER);
                tv.setSingleLine(true);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                tv.setPadding(dp(8), 0, dp(8), 0);
                tv.setBackgroundResource(i == checkedIndex
                        ? R.drawable.bg_glass_chip_selected : R.drawable.bg_glass_chip);
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

            // 先量一次内容高度：最多占屏幕一半，超出就交给 ScrollView 滚动
            int widthSpec = View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY);
            int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            list.measure(widthSpec, heightSpec);
            int contentHeight = list.getMeasuredHeight() + pad * 2;
            int maxHeight = getResources().getDisplayMetrics().heightPixels / 2;
            int popupHeight = Math.max(dp(36), Math.min(contentHeight, maxHeight));
            popup.setHeight(popupHeight);

            int xoff = (anchor.getWidth() - popupWidth) / 2;
            popup.showAsDropDown(anchor, xoff, -(anchor.getHeight() + popupHeight + dp(6)));
            reApplyImmersiveToPopup(popup);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /** 应用一个播放倍速并同步各处 UI / 配置（预置选项与自定义输入共用） */
    private void applySpeed(float value) {
        try {
            mPlayerConfig.put("sp", value);
            updatePlayerCfgView();
            listener.updatePlayerCfg();
            speed_old = value;
            mControlWrapper.setSpeed(value);
            // 手动选定后同步长按手势的基准，避免下次长按从旧值跳变；同时解除「固定」态
            speedBoostBaseSpeed = value;
            speedBoostSpeed = value;
            speedBoostLocked = false;
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    /** 自定义倍速输入：0.5 ~ 最高档位（手势设置里的「最高倍速」），最多两位小数 */
    private void showCustomSpeedDialog() {
        if (mPlayerConfig == null || mActivity == null) return;
        try {
            float current = (float) mPlayerConfig.getDouble("sp");
            final double max = Hawk.get(HawkConfig.GESTURE_MAX_SPEED, SPEED_BOOST_MAX);
            NumberInputDialog dialog = new NumberInputDialog(mActivity, "自定义倍速",
                    "可填 " + fmtSpeed(SPEED_BOOST_MIN) + " ~ " + fmtSpeed(max) + "，最多两位小数",
                    current, SPEED_BOOST_MIN, max);
            dialog.setOnConfirm((NumberInputDialog.OnConfirmFloat) value -> {
                applySpeed((float) value);
            });
            dialog.show();
        } catch (JSONException e) {
            e.printStackTrace();
        }
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

    /** 画面比例：与倍速同一套「按钮上方窄列表」，不再用系统对话框 */
    private void showScalePicker(View anchor) {
        if (mPlayerConfig == null || anchor == null) return;
        try {
            final int current = mPlayerConfig.getInt("sc");
            List<String> names = new ArrayList<>();
            for (int i = 0; i <= 5; i++) {
                names.add(PlayerHelper.getScaleName(i));
            }
            showOptionListAbove(anchor, names, current, new OnOptionPicked() {
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
            }, dp(88));
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

    private void hideLiveAboutBtn() {
        refreshControlBarVisibility();
    }

    /**
     * 统一断言底部控制条各按钮的可见性。
     * <p>
     * 首帧时 {@code STATE_PREPARED} 里 {@code getDuration()} 还是 0（时长要等媒体信息解析完才有），
     * 早期版本在这里直接把倍速置 GONE 就再没人恢复，所以出现「首次播放倍速 / 下一集 / 画面比例
     * 不显示，暂停再播放才出现」——因为暂停→播放会再走一遍 STATE_PLAYING。
     * 现在把显隐收敛到这一个方法，并在播放状态与首次拿到时长时反复调用，结果幂等。
     */
    private void refreshControlBarVisibility() {
        if (mBottomRoot == null || mRowPrimary == null || mProgressRow == null) return;
        // 先按方向铺好：横屏 下一集 / 画面比例 / 倍速 可见，竖屏收起
        applyControlBarMode();
        boolean hasDuration = mControlWrapper != null && safeTimeMs(mControlWrapper.getDuration()) > 0;
        if (!hasDuration) {
            // 直播 / 时长未知：倍速与片头片尾无意义
            if (mPlayerSpeedBtn != null) mPlayerSpeedBtn.setVisibility(GONE);
            if (mPlayerTimeStartBtn != null) mPlayerTimeStartBtn.setVisibility(GONE);
            if (mPlayerTimeSkipBtn != null) mPlayerTimeSkipBtn.setVisibility(GONE);
        }
        if (mPlayerTimeStartEndText != null) mPlayerTimeStartEndText.setVisibility(GONE);
    }

    public void initLandscapePortraitBtnInfo() {
        boolean isPortrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        // 时长显示：横竖屏都保留（当前时间 / 总时长）
        if (mCurrentTime != null) mCurrentTime.setVisibility(VISIBLE);
        if (mTotalTime != null) mTotalTime.setVisibility(VISIBLE);
        if (isPortrait) {
            if (backBtn != null) backBtn.setVisibility(INVISIBLE);
            // 锁定按钮的显隐统一交给 showLockView()，竖屏同样支持锁定
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

    /** 玻璃按钮通用按压动效（与直播页同一套）：按下缩到 0.86，松开平滑缩回 1.0（不过冲，避免视觉上忽大忽小） */
    private void bindGlassPressEffect(final View view) {
        if (view == null) return;
        view.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.animate().cancel();
                        v.animate().scaleX(0.86f).scaleY(0.86f).setDuration(90).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.animate().cancel();
                        v.animate().scaleX(1f).scaleY(1f).setDuration(180)
                                .setInterpolator(new DecelerateInterpolator()).start();
                        break;
                }
                return false;
            }
        });
    }

    private void bindMainControlButtons() {
        // 玻璃质感控件统一挂按压动效（按下缩小、松开平滑回弹），点击逻辑不受影响；
        // 菜单键 mMenuBtn 除外 —— 触摸模式下它有自己的跟手选择手势，动效会覆盖手势
        bindGlassPressEffect(mPlayPauseBtn);
        bindGlassPressEffect(mNextBtn);
        bindGlassPressEffect(mPortraitBtn);
        bindGlassPressEffect(mFullScreenBtn);
        bindGlassPressEffect(backBtn);
        bindGlassPressEffect(mCastBtn);
        bindGlassPressEffect(mPlayerScaleBtn);
        bindGlassPressEffect(mPlayerSpeedBtn);
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
            bindGlassPressEffect(mDanmuBtn);
            updateDanmuSwitch(DanmuHelper.isOpen());
        }
        if (mDanmuCfgBtn != null) {
            mDanmuCfgBtn.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    FastClickCheckUtil.check(v);
                    showDanmuSettingPanel(v);
                }
            });
            bindGlassPressEffect(mDanmuCfgBtn);
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
        if (mPortraitBtn != null) {
            mPortraitBtn.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    FastClickCheckUtil.check(v);
                    togglePortraitFullScreen();
                }
            });
        }
        if (mMenuBtn != null) {
            boolean tv = ScreenUtils.isTv(getContext());
            if (tv) {
                // TV / 遥控场景保留原抽屉（扇形菜单是触摸优先的交互）
                mMenuBtn.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        FastClickCheckUtil.check(v);
                        showDrawer();
                    }
                });
            } else {
                // 单击展开扇形功能菜单（点选），长按进入跟手选择（按住滑动、到项上松手确认）
                mMenuBtn.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        FastClickCheckUtil.check(v);
                        showMenuFan(v, false);
                    }
                });
                mMenuBtn.setOnLongClickListener(new OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        showMenuFan(v, true);
                        return true;
                    }
                });
                mMenuBtn.setOnTouchListener(new OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        if (menuFan == null || !menuFan.dragArmed) return false;
                        int action = event.getActionMasked();
                        if (action == MotionEvent.ACTION_MOVE) {
                            menuFanPointer(event.getRawX(), event.getRawY());
                        } else if (action == MotionEvent.ACTION_UP
                                || action == MotionEvent.ACTION_CANCEL) {
                            FanLayer fan = menuFan;
                            fan.dragArmed = false;
                            menuFanDragEnd(event.getRawX(), event.getRawY());
                        }
                        return true;
                    }
                });
            }
        }
        applyControlBarMode();
        updateFullScreenIcon(isPlayerFullScreen());
    }

    /**
     * 按当前屏幕方向重排底部控制条（只有一套控件，避免两份 seekBar 各记一套状态）：
     * 竖屏 → [播放] [进度条] [竖屏] [全屏] 单行；
     * 横屏 → 进度条独立成行，按钮行 = 左组[播放 下一集 弹] + 中缝空白 + 右组[倍速]。
     */
    private void applyControlBarMode() {
        if (mBottomRoot == null || mRowPrimary == null || mProgressRow == null) return;
        boolean portrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        boolean tv = ScreenUtils.isTv(getContext());

        if (portrait) {
            if (mProgressRow.getParent() != mRowPrimary) {
                ViewGroup parent = (ViewGroup) mProgressRow.getParent();
                if (parent != null) parent.removeView(mProgressRow);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                mRowPrimary.addView(mProgressRow, 1, lp);
            }
            if (mPortraitLabel != null) {
                mPortraitLabel.setText(isPortraitFullScreen() ? "退出" : "竖屏");
            }
        } else {
            if (mProgressRow.getParent() != mBottomRoot) {
                ViewGroup parent = (ViewGroup) mProgressRow.getParent();
                if (parent != null) parent.removeView(mProgressRow);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp(2);
                mBottomRoot.addView(mProgressRow, 0, lp);
            }
        }

        // 竖屏只留「播放 / 进度条 / 竖屏 / 全屏」；横屏留「播放 / 下一集 / 弹幕 / 画面比例 / 倍速」
        if (mNextBtn != null) mNextBtn.setVisibility(portrait ? GONE : VISIBLE);
        if (mDanmuBtn != null) mDanmuBtn.setVisibility(portrait ? GONE : VISIBLE);
        if (mDanmuCfgBtn != null) mDanmuCfgBtn.setVisibility(portrait ? GONE : VISIBLE);
        if (mBottomSpacer != null) mBottomSpacer.setVisibility(portrait ? GONE : VISIBLE);
        if (mPlayerScaleBtn != null) mPlayerScaleBtn.setVisibility(portrait ? GONE : VISIBLE);
        if (mPlayerSpeedBtn != null) mPlayerSpeedBtn.setVisibility(portrait ? GONE : VISIBLE);
        if (mPortraitBtn != null) mPortraitBtn.setVisibility(portrait ? VISIBLE : GONE);
        // 横屏全屏下退出全屏靠左上角返回键；TV 的返回键是隐藏的，必须保留全屏键
        if (mFullScreenBtn != null) mFullScreenBtn.setVisibility(portrait || tv ? VISIBLE : GONE);
    }

    /** 当前是否处于「竖屏满屏」状态（短剧播放模式） */
    public boolean isPortraitFullScreen() {
        return isPlayerFullScreen()
                && getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    /**
     * 竖屏按钮：铺满整屏但保持竖屏（短剧场景），再点一次退回详情页内嵌预览。
     * 与全屏按钮的区别只在锁定的屏幕方向。
     */
    private void togglePortraitFullScreen() {
        if (mActivity == null) return;
        if (isPortraitFullScreen()) {
            if (listener != null) {
                listener.exitFullScreen();
            }
            mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        } else {
            if (listener != null) {
                listener.enterFullScreen();
            }
            mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        }
        mRowPrimary.post(new Runnable() {
            @Override
            public void run() {
                applyControlBarMode();
                updateFullScreenIcon(isPlayerFullScreen());
            }
        });
    }

    /** 切换全屏：非全屏（含竖屏满屏）→ 横屏铺满；横屏全屏 → 退出全屏回到详情页 */
    private void togglePlayerFullScreen() {
        if (mActivity == null) return;
        boolean landscape = getResources().getConfiguration().orientation != Configuration.ORIENTATION_PORTRAIT;
        if (isPlayerFullScreen() && landscape) {
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
        applyControlBarMode();
    }

    /**
     * 全屏键的图标语义是「点下去会发生什么」：
     * 竖屏时点它一定是转横屏，所以始终画「进入全屏」；
     * 只有横屏且已铺满时，才画「退出全屏」。
     */
    private void updateFullScreenIcon(boolean fullscreen) {
        if (mFullScreenIcon == null) return;
        boolean landscape = getResources().getConfiguration().orientation != Configuration.ORIENTATION_PORTRAIT;
        mFullScreenIcon.setImageResource(fullscreen && landscape
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

    /* ==================== 右上角扇形功能菜单 ==================== */

    /** 一级扇形：半径 / 气泡尺寸 / 展开角度（以菜单键为圆心向下扇开） */
    private static final int MENU_FAN_RADIUS_DP = 78;
    private static final int MENU_FAN_ITEM_DP = 38;
    /** 起始角 110°：整条弧相对竖直向下顺时针转过 26°，第一个气泡更靠左下 */
    private static final float MENU_FAN_START_DEG = 100f;
    private static final float MENU_FAN_SWEEP_DEG = 92f;
    /** 二级扇形（片头片尾）：从一级气泡的位置再扇开 */
    private static final int MENU_FAN_SUB_RADIUS_DP = 64;
    private static final int MENU_FAN_SUB_ITEM_DP = 28;
    private static final float MENU_FAN_SUB_START_DEG = 100f;
    private static final float MENU_FAN_SUB_SWEEP_DEG = 70f;
    /** 一级里「片头片尾」的下标：跟手滑到它上面就自动展开二级 */
    private static final int MENU_FAN_SUB_PARENT = 0;

    /** 一级功能：片头片尾 / 弹幕设置 / 刷新 / 屏显（+ 更多，保留解码、播放器、解析等入口） */
    private static final String[] MENU_FAN_LABELS = {"片头片尾", "弹幕", "刷新", "屏显", "更多"};
    /** 片头片尾展开的二级功能 */
    private static final String[] MENU_FAN_SUB_LABELS = {"片头", "片尾", "重置"};

    /** 一级扇形（null 表示未展开） */
    private FanLayer menuFan;
    /** 片头片尾展开的二级扇形 */
    private FanLayer menuFanSub;

    /** 展开扇形菜单；dragArmed=true 表示配合长按：菜单键上的后续移动 / 抬手转发进来选择 */
    private void showMenuFan(View anchor, boolean dragArmed) {
        if (menuFan != null) {
            // 已经展开时再点一次菜单键 = 收起
            dismissMenuFan();
            return;
        }
        menuFan = new FanLayer(MENU_FAN_LABELS, false);
        menuFan.dragArmed = dragArmed;
        menuFan.show(anchor);
        if (myHandle != null) myHandle.removeCallbacks(myRunnable);
    }

    private void dismissMenuFan() {
        closeFanSub();
        if (menuFan != null) {
            menuFan.dismiss();
            menuFan = null;
        }
        if (myHandle != null && myRunnable != null) {
            myHandle.removeCallbacks(myRunnable);
            myHandle.postDelayed(myRunnable, myHandleSeconds);
        }
    }

    private void closeFanSub() {
        if (menuFanSub == null) return;
        menuFanSub.dismiss();
        menuFanSub = null;
    }

    /** 一级扇形选中 */
    private void onMenuFanPicked(int index) {
        switch (index) {
            case MENU_FAN_SUB_PARENT: // 片头片尾：一级留着，从该项再扇开二级
                expandFanSub();
                break;
            case 1:
                // 「弹幕」→ 直接弹出搜索弹幕（弹幕参数的调整已挪到弹幕开关旁的小齿轮）
                dismissMenuFan();
                if (listener != null) listener.openSearchDanmuDialog();
                break;
            case 2:
                dismissMenuFan();
                clickIfBound(mPlayrefresh);
                break;
            case 3:
                dismissMenuFan();
                clickIfBound(mScreenDisplay);
                break;
            case 4:
            default:
                dismissMenuFan();
                showDrawer();
                break;
        }
    }

    /** 片头片尾的二级扇形：以一级气泡为圆心再扇开三项（已展开则不重建） */
    private void expandFanSub() {
        if (menuFan == null || menuFanSub != null) return;
        View bubble = menuFan.focusedItem();
        if (bubble == null) return;
        menuFanSub = new FanLayer(MENU_FAN_SUB_LABELS, true);
        menuFanSub.show(bubble);
    }

    /** 二级扇形选中：复用抽屉里「片头 / 片尾 / 重置」的原逻辑 */
    private void onMenuFanSubPicked(int index) {
        dismissMenuFan();
        switch (index) {
            case 0: {
                int before = getTimeCfg(true);
                clickIfBound(mPlayerTimeStartBtn);
                int after = getTimeCfg(true);
                if (after > 0 && after != before) {
                    toast("片头已设为 " + stringForTime(after * 1000));
                } else if (after == 0) {
                    toast("请在前半段设置片头");
                }
                break;
            }
            case 1: {
                int before = getTimeCfg(false);
                clickIfBound(mPlayerTimeSkipBtn);
                int after = getTimeCfg(false);
                if (after > 0 && after != before) {
                    toast("片尾已设为倒数 " + stringForTime(after * 1000));
                } else if (after == 0) {
                    toast("请在后半段设置片尾");
                }
                break;
            }
            case 2:
            default:
                clickIfBound(mPlayerTimeResetBtn);
                toast("已清除片头片尾");
                break;
        }
    }

    /**
     * 长按跟手：移动时先判二级、再回落一级；滑到「片头片尾」上会自动把二级弹出来。
     */
    private void menuFanPointer(float rawX, float rawY) {
        if (menuFan == null) return;
        if (menuFanSub != null) {
            int subHit = menuFanSub.itemAt(rawX, rawY);
            if (subHit >= 0) {
                menuFanSub.setFocus(subHit);
                return;
            }
            int l1 = menuFan.itemAt(rawX, rawY);
            if (l1 >= 0 && l1 != MENU_FAN_SUB_PARENT) {
                // 滑回一级的其它项：二级收起来，别两层同时亮着
                closeFanSub();
                menuFan.setFocus(l1);
                return;
            }
            menuFanSub.setFocus(-1);
            return;
        }
        menuFan.setFocus(menuFan.itemAt(rawX, rawY));
    }

    /** 长按跟手：抬手结算 */
    private void menuFanDragEnd(float rawX, float rawY) {
        if (menuFan == null) return;
        if (menuFanSub != null) {
            int subHit = menuFanSub.itemAt(rawX, rawY);
            if (subHit >= 0) {
                menuFanSub.pickAt(subHit);
                return;
            }
            int l1 = menuFan.itemAt(rawX, rawY);
            if (l1 >= 0) {
                menuFan.pickAt(l1);
                return;
            }
            dismissMenuFan();
            return;
        }
        int hit = menuFan.itemAt(rawX, rawY);
        if (hit >= 0) menuFan.pickAt(hit);
        else dismissMenuFan();
    }

    /** 触发抽屉里某个按钮原有的点击逻辑（不复制一份实现，避免两处走偏） */
    private void clickIfBound(View v) {
        if (v != null) v.performClick();
    }

    private void toast(String text) {
        Context ctx = getContext();
        if (ctx != null) Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
    }

    /**
     * 软糖 Q 弹入场：位置用带过冲的插值飞过去（越过目标再弹回），
     * 缩放用更强的过冲从很小弹到原大，两者叠加就是果冻感。
     */
    private void jellyIn(final View item, final float fromX, final float fromY,
                         final float toX, final float toY, long delay) {
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setStartDelay(delay);
        animator.setDuration(420);
        final OvershootInterpolator moveInterp = new OvershootInterpolator(2.4f);
        final OvershootInterpolator scaleInterp = new OvershootInterpolator(3.4f);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float t = (float) animation.getAnimatedValue();
                float p = moveInterp.getInterpolation(t);
                item.setX(fromX + (toX - fromX) * p);
                item.setY(fromY + (toY - fromY) * p);
                float s = 0.32f + 0.68f * scaleInterp.getInterpolation(t);
                item.setScaleX(s);
                item.setScaleY(s);
                item.setAlpha(Math.min(1f, t * 2.4f));
            }
        });
        animator.start();
    }

    /** 焦点气泡的果冻回弹：先鼓一下再回原大小 */
    private void jellyPop(final View v, float peak) {
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(220);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float t = (float) animation.getAnimatedValue();
                float s = 1f + (peak - 1f) * (float) Math.sin(t * Math.PI);
                v.setScaleX(s);
                v.setScaleY(s);
            }
        });
        animator.start();
    }

    /**
     * 一层扇形菜单：自建浮层、把气泡摆到弧线上、跟手命中判定与选中动画。
     * 一级（menuFan）与「片头片尾」的二级（menuFanSub）各持有一个实例。
     */
    private final class FanLayer {
        private final String[] labels;
        private final boolean sub;
        private FrameLayout root;
        private TextView[] items;
        private float[] cx;
        private float[] cy;
        private int focus = -1;
        /** 气泡落位前不做命中判定，避免动画期间按坐标 0,0 误选第一项 */
        private boolean ready;
        private boolean dragArmed;

        FanLayer(String[] labels, boolean sub) {
            this.labels = labels;
            this.sub = sub;
        }

        void show(final View anchor) {
            final Context ctx = getContext();
            if (ctx == null || root != null) return;
            try {
                FrameLayout layer = new FrameLayout(ctx);
                View scrim = new View(ctx);
                scrim.setBackgroundColor(sub ? 0x2E000000 : 0x52000000);
                layer.addView(scrim, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                int size = dp(sub ? MENU_FAN_SUB_ITEM_DP : MENU_FAN_ITEM_DP);
                items = new TextView[labels.length];
                for (int i = 0; i < labels.length; i++) {
                    TextView item = new TextView(ctx);
                    item.setText(labels[i]);
                    // 气泡很小，四字标签折成两行（片头 / 片尾），两字标签一行
                    item.setSingleLine(labels[i].length() <= 3);
                    item.setMaxLines(labels[i].length() <= 3 ? 1 : 2);
                    item.setTextSize(TypedValue.COMPLEX_UNIT_SP, labels[i].length() <= 3 ? 10 : 9);
                    item.setTextColor(0xF2FFFFFF);
                    item.setGravity(Gravity.CENTER);
                    item.setIncludeFontPadding(false);
                    item.setClickable(false);
                    item.setFocusable(false);
                    item.setAlpha(0f);
                    item.setBackgroundResource(R.drawable.bg_menu_fan_item);
                    item.setLayoutParams(new FrameLayout.LayoutParams(size, size));
                    layer.addView(item);
                    items[i] = item;
                }
                cx = new float[labels.length];
                cy = new float[labels.length];
                root = layer;
                focus = -1;
                ready = false;
                addView(layer, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                layer.setOnTouchListener(new OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        float rawX = event.getRawX();
                        float rawY = event.getRawY();
                        switch (event.getActionMasked()) {
                            case MotionEvent.ACTION_DOWN:
                            case MotionEvent.ACTION_MOVE:
                                setFocus(itemAt(rawX, rawY));
                                return true;
                            default: {
                                int hit = itemAt(rawX, rawY);
                                if (hit >= 0) pickAt(hit);
                                else dismissMenuFan();
                                return true;
                            }
                        }
                    }
                });
                layer.post(new Runnable() {
                    @Override
                    public void run() {
                        position(anchor);
                    }
                });
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }

        /** 把气泡沿弧线摆到锚点周围 */
        private void position(View anchor) {
            if (root == null || items == null) return;
            int[] loc = new int[2];
            int[] rootLoc = new int[2];
            float ax;
            float ay;
            if (anchor != null && anchor.isAttachedToWindow()) {
                anchor.getLocationOnScreen(loc);
                root.getLocationOnScreen(rootLoc);
                ax = loc[0] - rootLoc[0] + anchor.getMeasuredWidth() / 2f;
                ay = loc[1] - rootLoc[1] + anchor.getMeasuredHeight() / 2f;
            } else {
                // 锚点不可用：退化到右上角，避免扇形跑到屏幕外
                ax = getMeasuredWidth() - dp(32);
                ay = dp(56);
            }
            float radius = dp(sub ? MENU_FAN_SUB_RADIUS_DP : MENU_FAN_RADIUS_DP);
            float half = dp(sub ? MENU_FAN_SUB_ITEM_DP : MENU_FAN_ITEM_DP) / 2f;
            float start = sub ? MENU_FAN_SUB_START_DEG : MENU_FAN_START_DEG;
            float sweep = sub ? MENU_FAN_SUB_SWEEP_DEG : MENU_FAN_SWEEP_DEG;
            int n = items.length;
            // 弧整体顺时针转过来后，最靠上的那颗会顶出屏幕（菜单键就贴着右上角）。
            // 这里只把「弧心」下移到刚好够放，气泡飞出的起点仍是菜单键本身。
            float arcY = ay;
            float minSin = Math.min((float) Math.sin(Math.toRadians(start)),
                    (float) Math.sin(Math.toRadians(start + sweep)));
            float topLimit = half + dp(6);
            if (minSin < 0 && arcY + minSin * radius < topLimit) {
                arcY = topLimit - minSin * radius;
            }
            for (int i = 0; i < n; i++) {
                double theta = Math.toRadians(start + (n <= 1 ? 0 : sweep / (n - 1)) * i);
                float tx = (float) (ax + Math.cos(theta) * radius);
                float ty = (float) (arcY + Math.sin(theta) * radius);
                // setX/setY 设置的是左上角，命中判定用的是圆心，所以这里存圆心坐标
                cx[i] = tx + half;
                cy[i] = ty + half;
                TextView item = items[i];
                // 从菜单键中心「啵」地弹出来
                item.setX(ax - half);
                item.setY(ay - half);
                item.setScaleX(0.32f);
                item.setScaleY(0.32f);
                item.setAlpha(0f);
                jellyIn(item, ax - half, ay - half, tx, ty, i * 26L);
            }
            ready = true;
        }

        /** 就近命中：气泡很小，取离手指最近且落在容差内的一项，避免邻项误选 */
        int itemAt(float rawX, float rawY) {
            if (root == null || cx == null || !ready) return -1;
            int[] loc = new int[2];
            root.getLocationOnScreen(loc);
            float maxDist = dp(sub ? MENU_FAN_SUB_ITEM_DP : MENU_FAN_ITEM_DP) / 2f + dp(14);
            float best = maxDist * maxDist;
            int bestIndex = -1;
            for (int i = 0; i < cx.length; i++) {
                float dx = rawX - loc[0] - cx[i];
                float dy = rawY - loc[1] - cy[i];
                float d = dx * dx + dy * dy;
                if (d < best) {
                    best = d;
                    bestIndex = i;
                }
            }
            return bestIndex;
        }

        void setFocus(int index) {
            if (index == focus) return;
            if (focus >= 0 && focus < items.length) {
                items[focus].setBackgroundResource(R.drawable.bg_menu_fan_item);
                items[focus].setTextColor(0xF2FFFFFF);
                jellyPop(items[focus], 0.9f);
            }
            focus = index;
            if (index >= 0 && index < items.length) {
                items[index].setBackgroundResource(R.drawable.bg_menu_fan_item_selected);
                items[index].setTextColor(0xFFFFFFFF);
                // 焦点项鼓一下，配合一次极轻的「哒」，让跟手选择有落点感
                jellyPop(items[index], 1.24f);
                vibrateTick();
                // 跟手滑到「片头片尾」上：二级自己弹出来
                if (!sub && index == MENU_FAN_SUB_PARENT) expandFanSub();
            }
        }

        /** 当前处于焦点态的气泡（片头片尾展开二级时用它当锚点） */
        TextView focusedItem() {
            if (items == null || focus < 0 || focus >= items.length) return null;
            return items[focus];
        }

        void pickAt(int index) {
            if (sub) onMenuFanSubPicked(index);
            else onMenuFanPicked(index);
        }

        void dismiss() {
            if (root == null) return;
            final FrameLayout layer = root;
            root = null;
            items = null;
            cx = null;
            cy = null;
            focus = -1;
            ready = false;
            dragArmed = false;
            layer.animate()
                    .alpha(0f)
                    .setDuration(120)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            ViewGroup parent = (ViewGroup) layer.getParent();
                            if (parent != null) parent.removeView(layer);
                        }
                    })
                    .start();
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
        // 一个带「弹」字的开关：开启时描边高亮，关闭时压暗
        mDanmuBtn.setText("弹");
        mDanmuBtn.setBackgroundResource(opened
                ? R.drawable.bg_player_switch_on
                : R.drawable.bg_player_switch_off);
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

    /* ==================== 播放条上的弹幕设置（玻璃面板 + 进度条） ==================== */

    private static final float DM_SPEED_MIN = 0.8f;
    private static final int DM_SIZE_MIN = 6;
    private static final int DM_SIZE_MAX = 20;
    private static final int DM_LINE_MIN = 1;
    private static final int DM_LINE_MAX = 15;
    private static final int DM_ALPHA_MIN = 10;
    private static final int DM_ALPHA_MAX = 100;
    private static final int DM_SEEK_SPEED = 0;
    private static final int DM_SEEK_SIZE = 1;
    private static final int DM_SEEK_LINE = 2;
    private static final int DM_SEEK_ALPHA = 3;

    private PopupWindow danmuPanelPopup;

    /**
     * 在「弹」开关右侧的齿轮上弹出弹幕设置面板：与倍速菜单同一套透明玻璃视觉，
     * 颜色用两枚胶囊，其余（速度 / 大小 / 行数 / 透明度）改成进度条拖动。
     */
    private void showDanmuSettingPanel(View anchor) {
        if (anchor == null) return;
        if (danmuPanelPopup != null && danmuPanelPopup.isShowing()) {
            danmuPanelPopup.dismiss();
            return;
        }
        try {
            final Context ctx = getContext();
            final int width = dp(272);
            final View panel = LayoutInflater.from(ctx)
                    .inflate(R.layout.view_player_danmu_panel, null, false);

            final PopupWindow popup = new PopupWindow(panel, width,
                    ViewGroup.LayoutParams.WRAP_CONTENT, true);
            popup.setBackgroundDrawable(new ColorDrawable(0x00000000));
            popup.setOutsideTouchable(true);
            popup.setFocusable(true);
            applyImmersiveToPopup(popup);
            // 面板打开期间不要自动收起控制条，否则锚点跟着消失
            if (myHandle != null) myHandle.removeCallbacks(myRunnable);
            popup.setOnDismissListener(new PopupWindow.OnDismissListener() {
                @Override
                public void onDismiss() {
                    danmuPanelPopup = null;
                    if (myHandle != null && myRunnable != null) {
                        myHandle.removeCallbacks(myRunnable);
                        myHandle.postDelayed(myRunnable, myHandleSeconds);
                    }
                }
            });
            danmuPanelPopup = popup;

            // 颜色：默认 / 随机
            final TextView chipDefault = panel.findViewById(R.id.dm_panel_default);
            final TextView chipRandom = panel.findViewById(R.id.dm_panel_random);
            refreshDanmuColorChips(chipDefault, chipRandom);
            if (chipDefault != null) {
                chipDefault.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        DanmuHelper.setRandomColor(false);
                        refreshDanmuColorChips(chipDefault, chipRandom);
                        postDanmuSettingChanged(true);
                    }
                });
            }
            if (chipRandom != null) {
                chipRandom.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        DanmuHelper.setRandomColor(true);
                        refreshDanmuColorChips(chipDefault, chipRandom);
                        postDanmuSettingChanged(true);
                    }
                });
            }

            bindDanmuSeek(panel, R.id.dm_seek_speed, R.id.dm_val_speed, DM_SEEK_SPEED,
                    Math.round(DanmuHelper.getSpeed() * 100) - 80, 100);
            bindDanmuSeek(panel, R.id.dm_seek_size, R.id.dm_val_size, DM_SEEK_SIZE,
                    Math.round(DanmuHelper.getSizeScale() * 10) - DM_SIZE_MIN, DM_SIZE_MAX - DM_SIZE_MIN);
            bindDanmuSeek(panel, R.id.dm_seek_line, R.id.dm_val_line, DM_SEEK_LINE,
                    DanmuHelper.getMaxLine() - DM_LINE_MIN, DM_LINE_MAX - DM_LINE_MIN);
            bindDanmuSeek(panel, R.id.dm_seek_alpha, R.id.dm_val_alpha, DM_SEEK_ALPHA,
                    Math.round(DanmuHelper.getAlpha() * 100) - DM_ALPHA_MIN, DM_ALPHA_MAX - DM_ALPHA_MIN);

            // 先量出真实高度，才能把面板整体顶到按钮上方
            panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int popupHeight = panel.getMeasuredHeight();
            popup.setHeight(popupHeight);

            int xoff = (anchor.getWidth() - width) / 2;
            // 面板贴着屏幕左边缘时会被切掉，往右推回来
            int[] loc = new int[2];
            anchor.getLocationOnScreen(loc);
            int screenW = getResources().getDisplayMetrics().widthPixels;
            int panelLeft = loc[0] + xoff;
            int margin = dp(8);
            if (panelLeft < margin) {
                xoff += margin - panelLeft;
            } else if (panelLeft + width > screenW - margin) {
                xoff -= (panelLeft + width) - (screenW - margin);
            }
            popup.showAsDropDown(anchor, xoff, -(anchor.getHeight() + popupHeight + dp(6)));
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private void refreshDanmuColorChips(TextView def, TextView random) {
        boolean useRandom = DanmuHelper.useRandomColor();
        applyChoice(def, !useRandom);
        applyChoice(random, useRandom);
    }

    /**
     * 把一条进度条绑定成弹幕参数。
     *
     * @param type        {@link #DM_SEEK_SPEED} / {@link #DM_SEEK_SIZE} / {@link #DM_SEEK_LINE} / {@link #DM_SEEK_ALPHA}
     * @param startValue  当前真实值（会被夹到合法区间并换算成进度）
     * @param maxProgress 进度条最大值
     */
    private void bindDanmuSeek(View root, int seekId, int valueId, final int type,
                               int startValue, int maxProgress) {
        SeekBar seek = root.findViewById(seekId);
        final TextView valueText = root.findViewById(valueId);
        if (seek == null) return;
        seek.setMax(Math.max(1, maxProgress));
        int progress = Math.max(0, Math.min(maxProgress, Math.round(startValue)));
        seek.setProgress(progress);
        if (valueText != null) valueText.setText(formatDanmuValue(type, progress));

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (valueText != null) valueText.setText(formatDanmuValue(type, progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                applyDanmuSeekValue(type, bar.getProgress());
                refreshDanmuPanel();
                postDanmuSettingChanged(false);
            }
        });
    }

    /** 进度值 → 显示文案（速度带 x，其余带档 / 行 / %） */
    private String formatDanmuValue(int type, int progress) {
        switch (type) {
            case DM_SEEK_SPEED:
                return String.format(Locale.getDefault(), "%.1fx", DM_SPEED_MIN + progress * 0.016f);
            case DM_SEEK_SIZE:
                return (DM_SIZE_MIN + progress) + "档";
            case DM_SEEK_LINE:
                return (DM_LINE_MIN + progress) + "行";
            default:
                return (DM_ALPHA_MIN + progress) + "%";
        }
    }

    /** 进度值 → 写入 DanmuHelper */
    private void applyDanmuSeekValue(int type, int progress) {
        switch (type) {
            case DM_SEEK_SPEED:
                DanmuHelper.setSpeed(DM_SPEED_MIN + progress * 0.016f);
                break;
            case DM_SEEK_SIZE:
                DanmuHelper.setSizeScale((DM_SIZE_MIN + progress) / 10f);
                break;
            case DM_SEEK_LINE:
                DanmuHelper.setMaxLine(DM_LINE_MIN + progress);
                break;
            default:
                DanmuHelper.setAlpha((DM_ALPHA_MIN + progress) / 100f);
                break;
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
        applyControlBarMode();
        updateFullScreenIcon(isPlayerFullScreen());
        // 横竖屏切换 / 分屏尺寸变化后，扇形菜单与弹幕面板的落位已失效，直接收起避免错位
        if (w != oldw || h != oldh) {
            if (menuFan != null) dismissMenuFan();
            if (danmuPanelPopup != null) {
                danmuPanelPopup.dismiss();
                danmuPanelPopup = null;
            }
        }
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
            mPlayerSpeedBtn.setText(fmtSpeed(mPlayerConfig.getDouble("sp")) + "x");
            if (mPlayerSpeedBtn != null) mPlayerSpeedBtn.setTextColor(SPEED_BTN_COLOR_NORMAL);
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

    /** 本集是否已经拿到过时长（用于「首次拿到时长时补一次控制条可见性」） */
    private boolean controlBarDurationKnown = false;

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
            if (!controlBarDurationKnown) {
                // 首次拿到时长：此时才真正知道是点播，补一次控制条可见性
                controlBarDurationKnown = true;
                refreshControlBarVisibility();
            }
            mSeekBar.setEnabled(true);
            int pos = (int) (position * 1.0 / duration * mSeekBar.getMax());
            mSeekBar.setProgress(pos);
        } else {
            controlBarDurationKnown = false;
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
                // 播放真正开始后再断言一次：PREPARED 阶段时长为 0 造成的误隐藏在这里被纠正
                refreshControlBarVisibility();
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
        // 长按进入加速状态：左右滑动 0.2 一档变速（左减右加），上滑固定，再长按自动解除
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
        // 弹幕设置面板浮起时：返回键只收起面板
        if (danmuPanelPopup != null) {
            danmuPanelPopup.dismiss();
            danmuPanelPopup = null;
            return true;
        }
        // 扇形菜单浮起时：返回键只收起菜单，不退出播放
        if (menuFan != null) {
            dismissMenuFan();
            return true;
        }
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
        if (danmuPanelPopup != null) {
            danmuPanelPopup.dismiss();
            danmuPanelPopup = null;
        }
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
