package com.github.tvbox.osc.util;

import android.util.Log;

import java.util.ArrayDeque;

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
public class LOG {
    private static String TAG = "TVBox-runtime";
    private static final int MAX_LOG_LENGTH = 3000;
    /** 内存环形日志：崩溃落盘时一并带出（见 {@link CrashLog}），正常运行时开销可忽略 */
    private static final int RING_CAPACITY = 150;
    private static final ArrayDeque<String> RING = new ArrayDeque<>();

    public static void e(String msg) {
        ring("E " + msg);
        Log.e(TAG, "" + msg);
    }

    public static void i(String msg) {
        ring("I " + msg);
        Log.i(TAG, "" + msg);
    }

    public static void longI(String prefix, String msg) {
        ring("I " + prefix + shorten(msg));
        longLog(Log.INFO, prefix, msg);
    }

    public static void longE(String prefix, String msg) {
        ring("E " + prefix + shorten(msg));
        longLog(Log.ERROR, prefix, msg);
    }

    private static String shorten(String msg) {
        if (msg == null) return "null";
        return msg.length() <= 400 ? msg : msg.substring(0, 400) + "…";
    }

    private static void ring(String line) {
        try {
            synchronized (RING) {
                if (RING.size() >= RING_CAPACITY) {
                    RING.removeFirst();
                }
                RING.addLast(System.currentTimeMillis() + " " + line);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 最近运行日志快照（崩溃落盘用，按时间正序） */
    public static String dumpRecent() {
        StringBuilder sb = new StringBuilder(4096);
        try {
            synchronized (RING) {
                for (String line : RING) {
                    sb.append(line).append('\n');
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private static void longLog(int priority, String prefix, String msg) {
        String text = msg == null ? "null" : msg;
        String title = prefix == null ? "" : prefix;
        int length = text.length();
        if (length <= MAX_LOG_LENGTH) {
            Log.println(priority, TAG, title + text);
            return;
        }
        int count = (length + MAX_LOG_LENGTH - 1) / MAX_LOG_LENGTH;
        for (int i = 0; i < count; i++) {
            int start = i * MAX_LOG_LENGTH;
            int end = Math.min(start + MAX_LOG_LENGTH, length);
            Log.println(priority, TAG, title + "[" + (i + 1) + "/" + count + "] " + text.substring(start, end));
        }
    }
}
