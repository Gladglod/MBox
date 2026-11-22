package com.github.tvbox.osc.player;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.player.controller.VodController;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.MotionCompat;
import com.orhanobut.hawk.Hawk;

import xyz.doikki.videoplayer.player.VideoView;

/**
 * 应用内唯一的视频小窗（悬浮窗）。
 *
 * <p>有意的全局会话：窗口存续期间用强引用保住播放器视图与控制器，拆除时统一置空，
 * 因此播放不依赖任何 Activity / Fragment 的存活。「唯一」由单例保证——当前持有者在此期间
 * 收回或被释放后，新视频才能进小窗。</p>
 *
 * <p>交互约定（与需求对齐）：</p>
 * <ul>
 *     <li>轻点画面呼出控件：中央 播放/暂停 大钮 + 右上「回到全屏」+ 左下倍速，
 *         3.5 秒无操作或再次轻点自动隐藏；所有控件统一半透明，不遮挡观看；</li>
 *     <li>按住画面拖动 = 移动窗口；</li>
 *     <li>双指捏合 = 缩放窗口大小：手势期间窗口不变、仅内容矩阵预览，松手一次提交（松手吸附）；
 *         宽高严格等比（宽为自变量，高按比例反算，宽/高边界联合作用在宽度上）；</li>
 *     <li>右下角手柄拖动 = 等比缩放窗口大小（同样严格等比，宽度可拖到几乎全屏宽）；</li>
 *     <li>比例锁（FREE / 16:9 / VIDEO）在 <b>我的→播放设定→小窗比例锁</b> 全局配置，
 *         捏合、手柄与进窗恢复统一走 {@link #effectiveRatio()}（FREE 时保持窗口当前形状）；</li>
 *     <li>上次尺寸 / 位置持久化（Hawk：PIP_WINDOW_SIZE / PIP_WINDOW_POS），下次进窗恢复。</li>
 * </ul>
 */
public final class FloatPlayWindow {

    /** 小窗拥有者：收回时由它恢复原页面 UI（或按需停止/释放播放） */
    public interface Owner {
        /**
         * @param releasePlayer true 表示被新的小窗顶替，需要停止并释放本次播放
         *                      （视图已由 FloatPlayWindow 挂回原页面）；false 表示正常收回，继续播放
         */
        void onFloatRecall(boolean releasePlayer);
    }

    private static FloatPlayWindow sInstance;

    public static synchronized FloatPlayWindow get() {
        if (sInstance == null) sInstance = new FloatPlayWindow();
        return sInstance;
    }

    private FloatPlayWindow() {
    }

    private static final float[] SPEED_PRESETS = {1.0f, 1.25f, 1.5f, 2.0f, 3.0f, 0.5f, 0.75f};
    /** 窗口最小宽度 / 高度（dp）；上限由屏幕可用区联合反算（宽度可以拖到几乎全屏宽） */
    private static final int MIN_W_DP = 150;
    private static final int MIN_H_DP = 90;
    private static final long ICON_SYNC_MS = 500L;
    /** 控件自动隐藏：无操作 3.5 秒后整组隐藏，轻点画面重新呼出 */
    private static final long CONTROLS_AUTO_HIDE_MS = 3500L;
    /** 控件统一半透明：悬于画面上不遮挡观看 */
    private static final float CONTROLS_ALPHA = 0.75f;

    /* —— 会话引用：窗口拆除时统一置空；窗口存续期间有意持有强引用 —— */
    private WindowManager windowManager;
    private Context appContext;
    private FrameLayout root;
    private ImageView playBtn;
    private ImageView recallBtn;
    private TextView speedBtn;
    private TextView resizeHandle;
    private WindowManager.LayoutParams winLp;
    private boolean controlsVisible;

    private View playerView;
    private VodController playController;
    private Owner owner;
    private ViewGroup origParent;
    private ViewGroup.LayoutParams origLp;
    private int origIndex;
    private View placeholder;
    private float videoRatio = 16f / 9f;
    private int speedIndex = 0;

    /* —— 双指捏合缩放（要求五.2）：手势期间窗口 LayoutParams 不动，只对窗口内的视频视图
          应用 Matrix 缩放预览（value 变化不触发 WindowManager 布局重算，中低端机跟手）；
          ACTION_UP/CANCEL 时把累积因子一次性换算成最终宽高，只调一次 updateViewLayout
          提交（「松手吸附到位」）。 —— */
    private ScaleGestureDetector scaleDetector;
    private boolean pinching;
    private float pinchFactor = 1f;
    /** 手柄拖动 / 捏合提交所用的等比比例（DOWN 时快照，手势期间恒定） */
    private float gestureRatio = 16f / 9f;

    /* —— 拖动 / 缩放的手势快照（DOWN 时统一记录） —— */
    private float downRawX;
    private float downRawY;
    private int startLpX;
    private int startLpY;
    private int startW;
    private boolean dragMoved;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable iconSync = new Runnable() {
        @Override
        public void run() {
            if (root == null) return;
            try {
                updatePlayBtnIcon();
                uiHandler.postDelayed(this, ICON_SYNC_MS);
            } catch (Throwable ignore) {
            }
        }
    };
    private final Runnable controlsAutoHide = new Runnable() {
        @Override
        public void run() {
            hideControls();
        }
    };

    public static boolean hasOverlayPermission(Context context) {
        if (context == null) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        try {
            return Settings.canDrawOverlays(context);
        } catch (Throwable th) {
            return false;
        }
    }

    /** 打开系统「显示在其他应用上层」设置页 */
    public static void openOverlayPermission(Context activity) {
        if (activity == null) return;
        try {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (Throwable th) {
            // 某些定制系统没有该入口
        }
    }

    public boolean isShowing() {
        return root != null;
    }

    public Owner getOwner() {
        return owner;
    }

    public boolean isOwner(Owner o) {
        return o != null && owner == o;
    }

    /** 归属者的页面已销毁但小窗继续播放：解除归属，小窗转为孤儿模式 */
    public void markOwnerGone(Owner o) {
        if (owner == o) owner = null;
    }

    /**
     * 把播放器视图挪进悬浮小窗并显示。
     *
     * @param video      播放器根视图（MyVideoView）
     * @param controller 播放器控制器（用于倍速持续写回配置；可为 null）
     * @param owner      归属页面（收回时恢复 UI；可为 null，孤儿模式）
     * @param ratio      视频宽高比（w/h），决定小窗初始形状
     */
    public boolean enter(Context context, View video, VodController controller, Owner owner, float ratio) {
        if (root != null || video == null || context == null) return false;
        try {
            appContext = context.getApplicationContext();
            playerView = video;
            playController = controller;
            this.owner = owner;
            videoRatio = ratio >= 0.4f ? Math.min(ratio, 2.39f) : 16f / 9f;

            // 记下原挂载点（页面布局里的位置与尺寸），收回时原样恢复
            ViewGroup parent = video.getParent() instanceof ViewGroup ? (ViewGroup) video.getParent() : null;
            origParent = parent;
            origLp = video.getLayoutParams();
            origIndex = parent == null ? 0 : parent.indexOfChild(video);

            if (parent != null) {
                addPlaceholder(parent);
                parent.removeView(video);
            }

            root = buildRoot(appContext);
            // 视频插在最底层（按钮 / 手柄 / 命中层压在其上）
            root.addView(video, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            resetSpeedPreset();
            updateSpeedBtnText(currentPresetSpeed());
        } catch (Throwable th) {
            th.printStackTrace();
            teardownWindow();
            return false;
        }

        try {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            lp.format = PixelFormat.TRANSLUCENT;
            lp.gravity = Gravity.TOP | Gravity.START;

            DisplayMetrics dm = appContext.getResources().getDisplayMetrics();
            // 恢复上次小窗尺寸/位置（要求五.4）；无效或越界时走默认值，
            // 全部统一 clamp 到当前屏幕可用区（换机/分屏/比例锁变化都不越界）
            int storedW = parsePair(Hawk.get(HawkConfig.PIP_WINDOW_SIZE, ""), 0);
            int storedH = parsePair(Hawk.get(HawkConfig.PIP_WINDOW_SIZE, ""), 1);
            int storedX = parsePair(Hawk.get(HawkConfig.PIP_WINDOW_POS, ""), 0);
            int storedY = parsePair(Hawk.get(HawkConfig.PIP_WINDOW_POS, ""), 1);

            // 比例基准：锁 16:9/VIDEO 用锁定比例；FREE 保持上次形状（等比恢复不变形）
            int lock = Hawk.get(HawkConfig.PIP_RATIO_LOCK, HawkConfig.PIP_RATIO_FREE);
            float restoreRatio;
            if (lock == HawkConfig.PIP_RATIO_16_9) {
                restoreRatio = 16f / 9f;
            } else if (lock == HawkConfig.PIP_RATIO_VIDEO) {
                restoreRatio = videoRatio;
            } else if (storedW > 0 && storedH > 0) {
                restoreRatio = storedW / (float) storedH;
            } else {
                restoreRatio = videoRatio;
            }
            int w = storedW > 0 ? storedW
                    : (int) (dm.widthPixels * (dm.widthPixels >= dm.heightPixels ? 0.45f : 0.42f));
            w = clampUniformWidth(w, restoreRatio);
            int h = Math.round(w / restoreRatio);

            if (storedX > 0) {
                lp.x = clamp(storedX, dp(4), dm.widthPixels - w - dp(4));
            } else {
                lp.x = Math.max(dp(8), dm.widthPixels - w - dp(12));
            }
            if (storedY > 0) {
                lp.y = clamp(storedY, dp(4), dm.heightPixels - h - dp(16));
            } else {
                // 底部给手势条 / 导航条留出空间
                lp.y = Math.max(dp(64), dm.heightPixels - h - dp(110));
            }
            lp.width = w;
            lp.height = h;
            winLp = lp;
            windowManager = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
            windowManager.addView(root, lp);
            playEnterAnimation();

            uiHandler.removeCallbacks(iconSync);
            uiHandler.post(iconSync);
            updatePlayBtnIcon();
            // 进窗先呼出一次控件（3.5 秒无操作自动隐藏），让功能可被发现
            showControls();
            return true;
        } catch (Throwable th) {
            th.printStackTrace();
            teardownWindow();
            return false;
        }
    }

    /** 收回：把播放视图挂回原页面再按需停止；owner 为空（孤儿）时直接释放 */
    public void recall(boolean releasePlayer) {
        Owner ownerRef = owner;
        teardownWindow();
        if (ownerRef != null) {
            ownerRef.onFloatRecall(releasePlayer);
        } else if (releasePlayer && playerView instanceof VideoView) {
            // 孤儿小窗没有页面可回，播放到此为止
            try {
                ((VideoView) playerView).release();
            } catch (Throwable ignore) {
            }
        }
    }

    /**
     * 入场动效：轻微放大 + 淡入（约 180ms），让小窗出现柔和而不突兀；
     * 系统「移除动画」开启时直接显示（尊重无障碍设置）。
     */
    private void playEnterAnimation() {
        if (root == null) return;
        try {
            if (MotionCompat.reducedMotion(appContext)) return;
            root.setAlpha(0f);
            root.setScaleX(0.88f);
            root.setScaleY(0.88f);
            root.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        } catch (Throwable ignore) {
        }
    }

    private FrameLayout buildRoot(Context c) {
        FrameLayout container = new FrameLayout(c);
        container.setBackgroundColor(Color.BLACK);

        // 拖动 / 轻点 / 双指捏合命中层：盖在视频上，负责移动窗口、缩放与轻点呼出控件
        FrameLayout tapCatcher = new FrameLayout(c);
        tapCatcher.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scaleDetector = new ScaleGestureDetector(c, scaleListener);
        final GestureDetector tapDetector = new GestureDetector(c,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        // 轻点画面：控件 显示 ⇄ 隐藏（双击判定窗口约 300ms，双指缩放不误触）
                        toggleControls();
                        return true;
                    }
                });
        tapCatcher.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (winLp == null) return true;
                try {
                    tapDetector.onTouchEvent(event);
                } catch (Throwable ignore) {
                }
                try {
                    scaleDetector.onTouchEvent(event);
                } catch (Throwable ignore) {
                }
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startLpX = winLp.x;
                        startLpY = winLp.y;
                        dragMoved = false;
                        // 控件显示中的任何触摸都算交互：续期自动隐藏计时
                        pokeControls();
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        // 捏合进行中：只做内容矩阵缩放，窗口不动、不拖动
                        if (pinching) return true;
                        if (event.getPointerCount() > 1) return true;
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (!dragMoved && Math.abs(dx) + Math.abs(dy) > dp(6)) dragMoved = true;
                        if (dragMoved) {
                            DisplayMetrics dm = appContext.getResources().getDisplayMetrics();
                            winLp.x = clamp((int) (startLpX + dx), dp(4), dm.widthPixels - winLp.width - dp(4));
                            winLp.y = clamp((int) (startLpY + dy), dp(4), dm.heightPixels - winLp.height - dp(16));
                            try {
                                windowManager.updateViewLayout(root, winLp);
                            } catch (Throwable ignore) {
                            }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        // 双指松手：把累积缩放因子换算成最终窗口尺寸一次性提交（松手吸附）
                        if (pinching) commitPinch();
                        pokeControls();
                        return true;
                    default:
                        return true;
                }
            }
        });
        container.addView(tapCatcher);

        // 中央播放/暂停大按钮：默认隐藏，轻点画面呼出，3.5 秒无操作自动消失
        playBtn = new ImageView(c);
        playBtn.setLayoutParams(new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        playBtn.setBackgroundResource(R.drawable.bg_player_circle);
        playBtn.setPadding(dp(13), dp(13), dp(13), dp(13));
        playBtn.setImageResource(R.drawable.ic_player_pause);
        playBtn.setAlpha(CONTROLS_ALPHA);
        playBtn.setVisibility(View.GONE);
        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlayState();
            }
        });
        container.addView(playBtn);

        // 右上角「回到全屏」：收回小窗，播放器挂回原页面并恢复应用
        recallBtn = new ImageView(c);
        FrameLayout.LayoutParams recallLp = new FrameLayout.LayoutParams(dp(30), dp(30));
        recallLp.gravity = Gravity.TOP | Gravity.END;
        recallLp.setMargins(0, dp(10), dp(10), 0);
        recallBtn.setLayoutParams(recallLp);
        recallBtn.setBackgroundResource(R.drawable.bg_player_circle);
        recallBtn.setPadding(dp(7), dp(7), dp(7), dp(7));
        recallBtn.setImageResource(R.drawable.ic_player_fullscreen);
        recallBtn.setAlpha(CONTROLS_ALPHA);
        recallBtn.setVisibility(View.GONE);
        recallBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                recall(false);
            }
        });
        container.addView(recallBtn);

        // 左下角倍速胶囊
        speedBtn = new TextView(c);
        FrameLayout.LayoutParams speedLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
        speedLp.gravity = Gravity.BOTTOM | Gravity.START;
        speedLp.setMargins(dp(10), 0, 0, dp(10));
        speedBtn.setLayoutParams(speedLp);
        speedBtn.setBackgroundResource(R.drawable.bg_player_circle);
        speedBtn.setGravity(Gravity.CENTER);
        speedBtn.setPadding(dp(12), 0, dp(12), 0);
        speedBtn.setTextColor(0xF2FFFFFF);
        speedBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        speedBtn.setTypeface(Typeface.DEFAULT_BOLD);
        speedBtn.setMinWidth(dp(48));
        speedBtn.setAlpha(0.8f);
        speedBtn.setVisibility(View.GONE);
        speedBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyNextPreset();
                pokeControls();
            }
        });
        container.addView(speedBtn);

        // 右下角缩放手柄：拖动严格等比改窗口大小（宽为自变量，高度按比例反算）
        resizeHandle = new TextView(c);
        resizeHandle.setText("◢");
        resizeHandle.setTextColor(0xB3FFFFFF);
        resizeHandle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        resizeHandle.setGravity(Gravity.CENTER);
        resizeHandle.setBackgroundResource(R.drawable.bg_player_circle);
        resizeHandle.setAlpha(0.6f);
        FrameLayout.LayoutParams handleLp = new FrameLayout.LayoutParams(dp(26), dp(26));
        handleLp.gravity = Gravity.BOTTOM | Gravity.END;
        handleLp.setMargins(0, 0, dp(6), dp(6));
        container.addView(resizeHandle, handleLp);
        resizeHandle.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (winLp == null) return true;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startW = winLp.width;
                        // 等比基准在 DOWN 时快照：手势期间形状恒定，宽高严格等比变化
                        gestureRatio = effectiveRatio();
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        DisplayMetrics dm = appContext.getResources().getDisplayMetrics();
                        // 宽为自变量：边界联合反算（宽度上限同时受高度上限 × 比例约束），
                        // 保证 newH = newW / ratio 恒成立 —— 拖动全程等比，可拖到几乎全屏宽
                        int newW = clampUniformWidth((int) (startW + (event.getRawX() - downRawX)), gestureRatio);
                        int newH = Math.round(newW / gestureRatio);
                        winLp.width = newW;
                        winLp.height = newH;
                        // 锚定左上角（拖右下手柄 → 窗口向右下生长），越界回拉
                        winLp.x = clamp(winLp.x, dp(4), dm.widthPixels - newW - dp(4));
                        winLp.y = clamp(winLp.y, dp(4), dm.heightPixels - newH - dp(16));
                        try {
                            windowManager.updateViewLayout(root, winLp);
                        } catch (Throwable ignore) {
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        persistGeometry();
                        return true;
                    default:
                        return true;
                }
            }
        });

        return container;
    }

    /* ==================== 控件显隐（轻点呼出 / 自动隐藏） ==================== */

    /** 控件显示中时续期自动隐藏计时（任何触摸都算交互） */
    private void pokeControls() {
        if (controlsVisible) scheduleControlsHide();
    }

    private void toggleControls() {
        if (controlsVisible) {
            hideControls();
        } else {
            showControls();
        }
    }

    private void showControls() {
        if (root == null) return;
        controlsVisible = true;
        if (playBtn != null) playBtn.setVisibility(View.VISIBLE);
        if (recallBtn != null) recallBtn.setVisibility(View.VISIBLE);
        if (speedBtn != null) speedBtn.setVisibility(View.VISIBLE);
        updatePlayBtnIcon();
        scheduleControlsHide();
    }

    private void hideControls() {
        controlsVisible = false;
        uiHandler.removeCallbacks(controlsAutoHide);
        if (playBtn != null) playBtn.setVisibility(View.GONE);
        if (recallBtn != null) recallBtn.setVisibility(View.GONE);
        if (speedBtn != null) speedBtn.setVisibility(View.GONE);
    }

    private void scheduleControlsHide() {
        uiHandler.removeCallbacks(controlsAutoHide);
        uiHandler.postDelayed(controlsAutoHide, CONTROLS_AUTO_HIDE_MS);
    }

    /* ==================== 双指捏合（尺寸可控核心） ==================== */

    private final ScaleGestureDetector.SimpleOnScaleGestureListener scaleListener =
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScaleBegin(ScaleGestureDetector detector) {
                    // 捏合开始：窗口 LayoutParams 保持不变，仅进入矩阵预览状态；
                    // 等比基准在开始时快照，提交时用它统一换算宽高
                    if (playerView == null || winLp == null) return false;
                    pinching = true;
                    pinchFactor = 1f;
                    gestureRatio = effectiveRatio();
                    return true;
                }

                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    if (!pinching || playerView == null) return false;
                    pinchFactor = clampFactor(pinchFactor * detector.getScaleFactor());
                    try {
                        // 捏合焦点作为缩放锚点，用户感知「双指往哪捏，画面往哪缩」
                        playerView.setPivotX(detector.getFocusX());
                        playerView.setPivotY(detector.getFocusY());
                        playerView.setScaleX(pinchFactor);
                        playerView.setScaleY(pinchFactor);
                    } catch (Throwable ignore) {
                    }
                    return true;
                }
            };

    private static float clampFactor(float f) {
        return Math.max(0.4f, Math.min(3.0f, f));
    }

    /**
     * 本次手势的等比基准：比例锁（16:9 / VIDEO）生效时用锁定比例；
     * 自由（FREE）时保持窗口当前形状 —— 无论哪种，宽高都严格等比变化。
     */
    private float effectiveRatio() {
        int lock = Hawk.get(HawkConfig.PIP_RATIO_LOCK, HawkConfig.PIP_RATIO_FREE);
        if (lock == HawkConfig.PIP_RATIO_16_9) return 16f / 9f;
        if (lock == HawkConfig.PIP_RATIO_VIDEO) return videoRatio;
        if (winLp != null && winLp.width > 0 && winLp.height > 0) {
            return winLp.width / (float) winLp.height;
        }
        return videoRatio;
    }

    /**
     * 等比尺寸的宽度边界（联合反算）：宽度上限同时受「屏幕宽度余量」与
     * 「屏幕高度余量 × 比例」约束，下限同时受最小宽与「最小高 × 比例」约束。
     * 返回值配合 {@code newH = round(newW / ratio)} 使用，保证宽高不破比、可拖到几乎全屏宽。
     */
    private int clampUniformWidth(int w, float ratio) {
        DisplayMetrics dm = appContext.getResources().getDisplayMetrics();
        int minW = Math.max(dp(MIN_W_DP), Math.round(dp(MIN_H_DP) * ratio));
        int maxW = dm.widthPixels - dp(8);
        int maxH = dm.heightPixels - dp(96);
        if (ratio > 0f) maxW = Math.min(maxW, Math.round(maxH * ratio));
        return clamp(w, minW, Math.max(minW, maxW));
    }

    /**
     * 松手吸附（要求五.2 的 ACTION_UP 阶段）：把累积缩放因子一次性换算成最终尺寸，
     * 只调用一次 {@code updateViewLayout} 提交。宽为自变量、高按比例反算并联合
     * clamp 边界（宽 / 高边界共同作用在宽度上）—— 任意比例锁下宽高都严格等比，
     * 不会再出现「宽度到顶了高度还在缩」的变形。
     */
    private void commitPinch() {
        boolean wasPinching = pinching;
        pinching = false;
        resetPlayerViewScale();
        if (!wasPinching || winLp == null || appContext == null || windowManager == null) return;
        try {
            DisplayMetrics dm = appContext.getResources().getDisplayMetrics();
            int baseW = winLp.width;
            int baseH = winLp.height;
            int newW = clampUniformWidth(Math.round(baseW * pinchFactor), gestureRatio);
            int newH = Math.round(newW / gestureRatio);
            // 以窗口中心为锚提交，缩放后窗口不跳位
            winLp.width = newW;
            winLp.height = newH;
            winLp.x = clamp(winLp.x - (newW - baseW) / 2, dp(4), dm.widthPixels - newW - dp(4));
            winLp.y = clamp(winLp.y - (newH - baseH) / 2, dp(4), dm.heightPixels - newH - dp(16));
            try {
                windowManager.updateViewLayout(root, winLp);
            } catch (Throwable ignore) {
            }
            persistGeometry();
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private void resetPlayerViewScale() {
        if (playerView == null) return;
        try {
            playerView.setScaleX(1f);
            playerView.setScaleY(1f);
        } catch (Throwable ignore) {
        }
    }

    /* ==================== 小窗几何持久化（要求五.4） ==================== */

    /** 上次尺寸/位置写入 Hawk，下次进小窗按屏幕可用区 clamp 后恢复 */
    private void persistGeometry() {
        try {
            if (winLp == null || appContext == null) return;
            Hawk.put(HawkConfig.PIP_WINDOW_SIZE, winLp.width + "|" + winLp.height);
            Hawk.put(HawkConfig.PIP_WINDOW_POS, winLp.x + "|" + winLp.y);
        } catch (Throwable ignore) {
        }
    }

    private static int parsePair(String stored, int index) {
        if (TextUtils.isEmpty(stored)) return 0;
        try {
            String[] parts = stored.split("\\|");
            if (index < 0 || index >= parts.length) return 0;
            return Integer.parseInt(parts[index].trim());
        } catch (Throwable th) {
            return 0;
        }
    }

    private void togglePlayState() {
        if (!(playerView instanceof VideoView)) return;
        try {
            VideoView vv = (VideoView) playerView;
            int state = vv.getCurrentPlayState();
            if (state == VideoView.STATE_PAUSED) {
                vv.resume();
            } else if (state == VideoView.STATE_PLAYING) {
                vv.pause();
            } else {
                vv.start();
            }
        } catch (Throwable ignore) {
        }
        updatePlayBtnIcon();
        // 操作过控件后续期自动隐藏计时
        pokeControls();
    }

    private void updatePlayBtnIcon() {
        if (playBtn == null || !(playerView instanceof VideoView)) return;
        boolean playing = false;
        try {
            playing = ((VideoView) playerView).getCurrentPlayState() == VideoView.STATE_PLAYING;
        } catch (Throwable ignore) {
        }
        playBtn.setImageResource(playing ? R.drawable.ic_player_pause : R.drawable.ic_player_play);
    }

    private void resetSpeedPreset() {
        float current = currentPresetSpeed();
        int nearest = 0;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < SPEED_PRESETS.length; i++) {
            float d = Math.abs(SPEED_PRESETS[i] - current);
            if (d < best) {
                best = d;
                nearest =i;
            }
        }
        speedIndex = nearest;
    }

    private float currentPresetSpeed() {
        try {
            if (playController != null) return playController.getCurrentSpeed();
        } catch (Throwable ignore) {
        }
        if (playerView instanceof VideoView) {
            try {
                return ((VideoView) playerView).getSpeed();
            } catch (Throwable ignore) {
            }
        }
        return 1.0f;
    }

    private void applyNextPreset() {
        speedIndex = (speedIndex + 1) % SPEED_PRESETS.length;
        float value = SPEED_PRESETS[speedIndex];
        boolean applied = false;
        if (playController != null) {
            try {
                playController.applyFloatSpeed(value);
                applied = true;
            } catch (Throwable ignore) {
            }
        }
        if (!applied && playerView instanceof VideoView) {
            try {
                ((VideoView) playerView).setSpeed(value);
            } catch (Throwable ignore) {
            }
        }
        updateSpeedBtnText(value);
    }

    private void updateSpeedBtnText(float value) {
        if (speedBtn == null) return;
        speedBtn.setText(fmtSpeed(value) + "x");
    }

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

    /** 原页面里视频离开后留下的占位（点按直接收回小窗） */
    private void addPlaceholder(ViewGroup parent) {
        try {
            FrameLayout p = new FrameLayout(appContext);
            p.setBackgroundColor(Color.BLACK);
            TextView tip = new TextView(appContext);
            tip.setText("小窗播放中");
            tip.setTextColor(0x99FFFFFF);
            tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tip.setGravity(Gravity.CENTER);
            p.addView(tip, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            p.setOnClickListener(null);
            p.setClickable(false);
            p.setFocusable(false);
            int w = origLp == null || origLp.width <= 0 ? ViewGroup.LayoutParams.MATCH_PARENT : origLp.width;
            int h = origLp == null || origLp.height <= 0 ? ViewGroup.LayoutParams.MATCH_PARENT : origLp.height;
            parent.addView(p, Math.max(0, Math.min(origIndex, parent.getChildCount())),
                    new ViewGroup.LayoutParams(w, h));
            placeholder = p;
        } catch (Throwable th) {
            th.printStackTrace();
            placeholder = null;
        }
    }

    private void removePlaceholder() {
        if (placeholder == null) return;
        try {
            ViewGroup p = placeholder.getParent() instanceof ViewGroup
                    ? (ViewGroup) placeholder.getParent() : null;
            if (p != null) p.removeView(placeholder);
        } catch (Throwable ignore) {
        }
        placeholder = null;
    }

    private void teardownWindow() {
        try {
            uiHandler.removeCallbacks(iconSync);
            uiHandler.removeCallbacks(controlsAutoHide);
        } catch (Throwable ignore) {
        }
        controlsVisible = false;
        persistGeometry();
        // 捏合矩阵复位：保证播放器视图挂回原页面时是原始 1:1 状态
        resetPlayerViewScale();
        if (windowManager != null && root != null) {
            try {
                windowManager.removeView(root);
            } catch (Throwable ignore) {
            }
        }
        // 播放视图回到原页面原位置（页面已销毁则跳过，由孤儿回收路径释放）
        if (playerView != null) {
            ViewGroup cur = playerView.getParent() instanceof ViewGroup
                    ? (ViewGroup) playerView.getParent() : null;
            if (cur != null) cur.removeView(playerView);
            if (origParent != null) {
                try {
                    int idx = Math.max(0, Math.min(origIndex, origParent.getChildCount()));
                    if (origLp != null) {
                        origParent.addView(playerView, idx, origLp);
                    } else {
                        origParent.addView(playerView, idx);
                    }
                } catch (Throwable th) {
                    th.printStackTrace();
                }
            }
        }
        removePlaceholder();
        playerView = null;
        playController = null;
        owner = null;
        origParent = null;
        origLp = null;
        root = null;
        playBtn = null;
        recallBtn = null;
        speedBtn = null;
        resizeHandle = null;
        winLp = null;
        scaleDetector = null;
        windowManager = null;
        appContext = null;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private int dp(int value) {
        if (appContext == null) return value;
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                value, appContext.getResources().getDisplayMetrics());
    }
}
