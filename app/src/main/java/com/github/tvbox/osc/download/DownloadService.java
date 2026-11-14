package com.github.tvbox.osc.download;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.cache.DownloadRecord;
import com.github.tvbox.osc.event.DownloadEvent;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/**
 * 离线缓存前台服务：下载期间保活 + 通知栏显示「下载中 n 个任务」。
 * 实际下载逻辑在 {@link DownloadManager}，这里只负责保活与通知；
 * 没有进行中的任务时自动停止自己。
 *
 * @author MBox
 */
public class DownloadService extends Service {

    private static final String CHANNEL_ID = "download_channel";
    private static final int NOTIFY_ID = 9001;

    public static void ensureStarted(Context context) {
        try {
            Intent intent = new Intent(context, DownloadService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable ignored) {
            // 部分机型后台启动受限，下载会在进程存活期间继续
        }
    }

    public static void ensureStopped(Context context) {
        try {
            context.stopService(new Intent(context, DownloadService.class));
        } catch (Throwable ignored) {
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        EventBus.getDefault().register(this);
        startForeground(NOTIFY_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        refresh();
        return START_NOT_STICKY;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDownloadEvent(DownloadEvent event) {
        refresh();
    }

    private void refresh() {
        try {
            if (!DownloadManager.get().hasActiveTasks()) {
                stopSelf();
                return;
            }
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFY_ID, buildNotification());
        } catch (Throwable ignored) {
        }
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "离线缓存",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mine_download)
                .setContentTitle("正在缓存视频")
                .setContentText("离线缓存任务进行中")
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }
}
