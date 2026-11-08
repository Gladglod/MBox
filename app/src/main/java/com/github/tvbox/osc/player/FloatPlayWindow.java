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
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.player.controller.VodController;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
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
 *     <li>窗口内容只保留 播放/暂停 与 倍速 两个胶囊按钮，其余功能按钮全部省略；</li>
 *     <li>按住画面拖动 = 移动窗口；</li>
 *     <li>双指捏合 = 缩放窗口大小：手势期间窗口不变、仅内容矩阵预览，松手一次提交（松手吸附）；</li>
 *     <li>右下角手柄拖动 = 等比缩放窗口大小；</li>
 *     <li>比例锁（FREE / 16:9 / VIDEO）在 <b>我的→播放设定→小窗比例锁</b> 全局配置，
 *         捏合提交与进窗恢复时生效；</li>
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
    /** 窗口最小 / 最大宽度（dp），等比缩放在这个区间内 */
    private static final int MIN_W_DP = 150;
    private static final int MAX_W_DP = 420;
    private static final int MIN_H_DP = 90;
    private static final long ICON_SYNC_MS = 500L;

    /* —— 会话引用：窗口拆除时统一置空；窗口存续期间有意持有强引用 —— */
    private WindowManager windowManager;
    private Context appContext;
    private FrameLayout root;
    private ImageView playBtn;
    private TextView speedBtn;
    private TextView resizeHandle;
    private WindowManager.LayoutParams winLp;

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

            int maxW = Math.min(dp(MAX_W_DP), dm.widthPixels - dp(24));
            int w = storedW > 0 ? storedW
                    : (int) (dm.widthPixels * (dm.widthPixels >= dm.heightPixels ? 0.45f : 0.42f));
            w = clamp(w, dp(MIN_W_DP), maxW);

            int lock = Hawk.get(HawkConfig.PIP_RATIO_LOCK, HawkConfig.PIP_RATIO_FREE);
            int h = storedH > 0 ? storedH : Math.round(w / videoRatio);
            if (lock == HawkConfig.PIP_RATIO_16_9 || lock == HawkConfig.PIP_RATIO_VIDEO) {
                // 比例锁生效：无论恢复值如何，高度按锁定比例回算，避免拉伸/异常黑边
                float lockRatio = lock == HawkConfig.PIP_RATIO_16_9 ? 16f / 9f : videoRatio;
                h = Math.round(w / lockRatio);
            }
            h = clamp(h, dp(MIN_H_DP), dm.heightPixels - dp(96));

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

            uiHandler.removeCallbacks(iconSync);
            uiHandler.post(iconSync);
            updatePlayBtnIcon();
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

    private FrameLayout buildRoot(Context c) {
        FrameLayout container = new FrameLayout(c);
        container.setBackgroundColor(Color.BLACK);

        // 拖动 / 点按命中层：盖在视频上，负责移动窗口、双指捏合缩放与单击
        FrameLayout tapCatcher = new FrameLayout(c);
        tapCatcher.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scaleDetector = new ScaleGestureDetector(c, scaleListener);
        tapCatcher.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (winLp == null) return true;
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
                        return true;
                    default:
                        return true;
                }
            }
        });
        container.addView(tapCatcher);

        // 底部按钮行：回到全屏 / 播放暂停 / 倍速（其余功能按钮全部省略）
        LinearLayout controlRow = new LinearLayout(c);
        controlRow.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams rowLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.gravity = Gravity.BOTTOM | Gravity.START;
        rowLp.setMargins(dp(10), 0, 0, dp(10));
        container.addView(controlRow, rowLp);

        // 「回到全屏」：收回小窗，播放器挂回原页面并恢复应用
        ImageView recallBtn = new ImageView(c);
        LinearLayout.LayoutParams recallLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        recallLp.setMarginEnd(dp(8));
        recallBtn.setLayoutParams(recallLp);
        recallBtn.setBackgroundResource(R.drawable.bg_player_circle);
        recallBtn.setPadding(dp(8), dp(8), dp(8), dp(8));
        recallBtn.setImageResource(R.drawable.ic_player_fullscreen);
        recallBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                recall(false);
            }
        });
        controlRow.addView(recallBtn);

        playBtn = new ImageView(c);
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        playLp.setMarginEnd(dp(8));
        playBtn.setLayoutParams(playLp);
        playBtn.setBackgroundResource(R.drawable.bg_player_circle);
        playBtn.setPadding(dp(8), dp(8), dp(8), dp(8));
        playBtn.setImageResource(R.drawable.ic_player_pause);
        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlayState();
            }
        });
        controlRow.addView(playBtn);

        speedBtn = new TextView(c);
        speedBtn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)));
        speedBtn.setBackgroundResource(R.drawable.bg_player_circle);
        speedBtn.setGravity(Gravity.CENTER);
        speedBtn.setPadding(dp(12), 0, dp(12), 0);
        speedBtn.setTextColor(0xF2FFFFFF);
        speedBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        speedBtn.setTypeface(Typeface.DEFAULT_BOLD);
        speedBtn.setMinWidth(dp(52));
        speedBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyNextPreset();
            }
        });
        controlRow.addView(speedBtn);

        // 右下角缩放手柄：拖动等比改窗口大小
        resizeHandle = new TextView(c);
        resizeHandle.setText("◢");
        resizeHandle.setTextColor(0xB3FFFFFF);
        resizeHandle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        resizeHandle.setGravity(Gravity.CENTER);
        resizeHandle.setBackgroundResource(R.drawable.bg_player_circle);
        resizeHandle.setAlpha(0.85f);
        FrameLayout.LayoutParams handleLp = new FrameLayout.LayoutParams(dp(24), dp(24));
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
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        DisplayMetrics dm = appContext.getResources().getDisplayMetrics();
                        int newW = clamp((int) (startW + (event.getRawX() - downRawX)),
                                dp(MIN_W_DP), Math.min(dp(MAX_W_DP), dm.widthPixels - dp(24)));
                        int newH = clamp((int) Math.round(newW / videoRatio), dp(MIN_H_DP),
                                dm.heightPixels - dp(96));
                        winLp.width = newW;
                        winLp.height = newH;
                        winLp.x = clamp(winLp.x, dp(4), dm.widthPixels - newW - dp(4));
                        winLp.y = clamp(winLp.y, dp(4), dm.heightPixels - newH - dp(16));
                        try {
                            windowManager.updateViewLayout(root, winLp);
                        } catch (Throwable ignore) {
                        }
                        return true;
                    }
                    default:
                        return true;
                }
            }
        });

        return container;
    }

    /* ==================== 双指捏合（尺寸可控核心） ==================== */

    private final ScaleGestureDetector.SimpleOnScaleGestureListener scaleListener =
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScaleBegin(ScaleGestureDetector detector) {
                    // 捏合开始：窗口 LayoutParams 保持不变，仅进入矩阵预览状态
                    if (playerView == null || winLp == null) return false;
                    pinching = true;
                    pinchFactor = 1f;
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
     * 松手吸附（要求五.2 的 ACTION_UP 阶段）：把累积缩放因子一次性换算成最终窗口宽高，
     * 只调用一次 {@code updateViewLayout} 提交。最大尺寸不超屏幕可用区，比例锁生效时
     * 按锁定比例回算高度，避免画面拉伸或异常黑边。
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
            int lock = Hawk.get(HawkConfig.PIP_RATIO_LOCK, HawkConfig.PIP_RATIO_FREE);
            int newW;
            int newH;
            if (lock == HawkConfig.PIP_RATIO_16_9 || lock == HawkConfig.PIP_RATIO_VIDEO) {
                float lockRatio = lock == HawkConfig.PIP_RATIO_16_9 ? 16f / 9f : videoRatio;
                newW = Math.round(baseW * pinchFactor);
                newH = Math.round(newW / lockRatio);
            } else {
                newW = Math.round(baseW * pinchFactor);
                newH = Math.round(baseH * pinchFactor);
            }
            newW = clamp(newW, dp(MIN_W_DP), Math.min(dp(MAX_W_DP), dm.widthPixels - dp(24)));
            newH = clamp(newH, dp(MIN_H_DP), dm.heightPixels - dp(96));
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
        } catch (Throwable ignore) {
        }
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
