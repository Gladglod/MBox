package com.github.tvbox.osc.player;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Rational;

import com.github.tvbox.osc.util.LOG;

import java.util.Locale;

import xyz.doikki.videoplayer.player.AbstractPlayer;

/**
 * 系统 PiP（Picture-in-Picture）辅助：只承担参数构造、进出调用与身份诊断打印。
 *
 * <p>架构约定：系统 PiP 不做任何 Surface 迁移——进入 PiP 时 Activity 整窗缩放，
 * {@code mVideoView} 原地不动，Surface / 解码全部零改变（红线 1/2/5 天然满足）。
 * 自定义 Overlay 路径（{@link FloatPlayWindow}）才做视图 reparent。</p>
 */
public final class PipHelper {

    /** 系统 PiP 接受的画面比例边界（API 26+ 官方限制 0.418~2.39） */
    public static final float MIN_ASPECT = 0.418f;
    public static final float MAX_ASPECT = 2.39f;

    private PipHelper() {
    }

    /** 系统 PiP 需要 Android 8.0+ */
    public static boolean isSupported(Context context) {
        return context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    public static boolean isInPip(Activity activity) {
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        try {
            return activity.isInPictureInPictureMode();
        } catch (Throwable th) {
            return false;
        }
    }

    /** 视频宽高比（w/h）→ 收敛到系统允许区间后的 Rational */
    public static Rational ratioToRational(float ratio) {
        if (ratio <= 0f || Float.isNaN(ratio) || Float.isInfinite(ratio)) {
            ratio = 16f / 9f;
        }
        ratio = Math.max(MIN_ASPECT, Math.min(MAX_ASPECT, ratio));
        return new Rational(Math.round(ratio * 1000f), 1000);
    }

    private static PictureInPictureParams buildParams(float videoRatio) {
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
        builder.setAspectRatio(ratioToRational(videoRatio));
        try {
            // 无缝缩放：PiP 边角拖大拖小时不触发 Activity 重启，表面连续无闪断
            // （API 26+ 可用；Android 12+ 该行为由系统自动接管，此调用被平滑忽略）
            builder.setSeamlessResizeEnabled(true);
        } catch (Throwable ignored) {
        }
        return builder.build();
    }

    /**
     * 进入系统 PiP。返回 true 表示已请求成功（随后 Activity 会收到
     * onPictureInPictureModeChanged(true)），false 表示不支持或被系统拒绝。
     *
     * <p><b>系统手势语义说明（App 无法干预）：</b>PiP 的拖动/双击/双指缩放全部由系统
     * WM Shell 在 App 之前处理（InputMonitor 抢先收口）。双击的行为依赖设备配置
     * {@code config_pipEnablePinchResize}（AOSP 默认 true，即 Android 12+ 的
     * 「双击在最大/默认尺寸间切换」；该配置关闭或 Android &lt; 12 时，系统把双击
     * 固定映射为 <b>回全屏</b>，此时双指缩放同样不可用）。App 没有公开 API 能
     * 查询或改变该配置——无法在系统模式里自造「双击改尺寸」。需要自由缩放的
     * 用户请使用自定义小窗（PIP_MODE=1）。</p>
     */
    public static boolean enter(Activity activity, float videoRatio) {
        if (!isSupported(activity) || activity.isFinishing() || activity.isDestroyed()) {
            LOG.i("pip enter: unsupported or finishing");
            return false;
        }
        try {
            // 真机验收归因：把 ROM 信息打进 pip-surface 通道，便于核对
            // 「双击=尺寸切换还是回全屏」这类系统侧差异（见类头手势语义说明）
            LOG.i("pip enter: device="
                    + Build.MANUFACTURER + "/" + Build.MODEL
                    + " sdk=" + Build.VERSION.SDK_INT);
            activity.enterPictureInPictureMode(buildParams(videoRatio));
            return true;
        } catch (Throwable th) {
            th.printStackTrace();
            return false;
        }
    }

    /** PiP 期间视频源切换 / 宽高变化时刷新系统 PiP 的画面比例 */
    public static void updateParams(Activity activity, float videoRatio) {
        if (!isInPip(activity)) return;
        try {
            activity.setPictureInPictureParams(buildParams(videoRatio));
        } catch (Throwable ignored) {
        }
    }

    /**
     * PiP 内回全屏。Android 没有公开的「程序化展开 PiP」API，用把本 Activity 重新
     * 置前排队的官方惯用法触发系统展开（失败时静默，用户仍可用系统手势回全屏）。
     */
    public static void expand(Activity activity) {
        if (activity == null || !isInPip(activity)) return;
        try {
            Intent self = new Intent(activity, activity.getClass());
            self.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            activity.startActivity(self);
            LOG.i("pip expand: requested");
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /**
     * 播放器实例与已绑定 Surface 的身份打印。
     *
     * <p>自检项：进入 / 退出 PiP 前后各打一次，两次的 player= 与 surface= 编号必须一致。
     * 一致即证明「单一播放器实例 + Surface 未随窗口切换销毁重建」两条红线都成立。</p>
     */
    public static void logSurfaceIdentity(String where, MyVideoView videoView) {
        if (videoView == null) {
            LOG.i("pip-surface " + where + ": videoView=null");
            return;
        }
        AbstractPlayer player = null;
        try {
            player = videoView.getMediaPlayer();
        } catch (Throwable ignored) {
        }
        Object surface = player == null ? null : player.getSurface();
        LOG.i(String.format(Locale.US,
                "pip-surface %s: player=0x%08x class=%s surface=0x%08x renderingState=%s",
                where,
                System.identityHashCode(player),
                player == null ? "null" : player.getClass().getSimpleName(),
                System.identityHashCode(surface),
                safePlayState(videoView)));
    }

    private static String safePlayState(MyVideoView videoView) {
        try {
            return videoView.getCurrentPlayState() + "";
        } catch (Throwable th) {
            return "unknown";
        }
    }
}
