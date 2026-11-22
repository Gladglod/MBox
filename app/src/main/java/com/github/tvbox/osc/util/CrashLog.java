package com.github.tvbox.osc.util;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import com.orhanobut.hawk.Hawk;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 真机崩溃落盘：未捕获异常的堆栈 + 机型/系统信息 + 最近的运行日志，
 * 写到应用外部私有目录（无需存储权限）：
 * <pre>
 * Android/data/&lt;包名&gt;/files/crash/crash-时间.log
 * </pre>
 * 只保留最近 {@link #KEEP_FILES} 个文件，避免占空间。
 *
 * <p>用途：厂商 ROM（如鸿蒙/荣耀）上的闪退无法在模拟器复现，又拿不到 adb 时，
 * 让用户复现一次后把该目录里的日志发回来即可定位。原生崩溃（native tombstone）
 * 不走 Java 异常链，这里只能覆盖 Java/Kotlin 层异常。</p>
 */
public final class CrashLog {

    private static final String DIR_NAME = "crash";
    private static final int KEEP_FILES = 5;

    private CrashLog() {
    }

    /** 在 Application.onCreate 里调用一次（安装全局未捕获异常处理器） */
    public static void install(final Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                try {
                    write(app, thread, throwable);
                } catch (Throwable ignored) {
                    // 落盘失败不能吞掉原始崩溃：继续交给系统/原处理器
                }
                if (previous != null) {
                    previous.uncaughtException(thread, throwable);
                }
            }
        });
    }

    private static void write(Context context, Thread thread, Throwable throwable) throws Exception {
        StringWriter trace = new StringWriter();
        PrintWriter writer = new PrintWriter(trace);
        throwable.printStackTrace(writer);
        writer.flush();

        StringBuilder sb = new StringBuilder(4096);
        sb.append("time=").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append('\n');
        sb.append("device=").append(Build.MANUFACTURER).append('/').append(Build.MODEL).append('\n');
        sb.append("sdk=").append(Build.VERSION.SDK_INT)
                .append(" release=").append(Build.VERSION.RELEASE).append('\n');
        sb.append("version=").append(versionName(context)).append('\n');
        sb.append("thread=").append(thread == null ? "?" : thread.getName()).append('\n');
        sb.append("pip_mode=").append(Hawk.get(HawkConfig.PIP_MODE, -1))
                .append(" auto=").append(Hawk.get(HawkConfig.PIP_AUTO_ENTER, false)).append('\n');
        sb.append('\n').append(trace);

        sb.append("\n--- recent logs ---\n");
        sb.append(LOG.dumpRecent());

        File dir = crashDir(context);
        if (dir == null) return;
        if (!dir.exists() && !dir.mkdirs()) return;
        String name = "crash-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".log";
        FileOutputStream out = new FileOutputStream(new File(dir, name));
        try {
            out.write(sb.toString().getBytes("UTF-8"));
            out.flush();
        } finally {
            out.close();
        }
        prune(dir);
    }

    private static File crashDir(Context context) {
        try {
            File base = context.getExternalFilesDir(null);
            if (base == null) base = context.getFilesDir();
            return base == null ? null : new File(base, DIR_NAME);
        } catch (Throwable th) {
            return null;
        }
    }

    /** 只保留最近 KEEP_FILES 个日志（按文件名时间排序） */
    private static void prune(File dir) {
        try {
            File[] files = dir.listFiles();
            if (files == null || files.length <= KEEP_FILES) return;
            java.util.Arrays.sort(files, new java.util.Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return a.getName().compareTo(b.getName());
                }
            });
            for (int i = 0; i < files.length - KEEP_FILES; i++) {
                //noinspection ResultOfMethodCallIgnored
                files[i].delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String versionName(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName + "(" + info.versionCode + ")";
        } catch (Throwable th) {
            return "?";
        }
    }

    /** 供排查用的信息：外部日志目录绝对路径（找不到时返回空串） */
    public static String crashDirPath(Context context) {
        File dir = crashDir(context);
        return dir == null ? "" : dir.getAbsolutePath();
    }
}
