package com.github.tvbox.osc.event;

/**
 * 离线缓存任务变化事件（EventBus 主线程分发）。
 * obj 为发生变化的 {@link com.github.tvbox.osc.cache.DownloadRecord}。
 *
 * @author MBox
 */
public class DownloadEvent {
    /** 新增任务 */
    public static final int TYPE_ADD = 0;
    /** 任务状态 / 进度更新 */
    public static final int TYPE_UPDATE = 1;
    /** 任务被删除 */
    public static final int TYPE_REMOVE = 2;

    public int type;
    public Object obj;

    public DownloadEvent(int type, Object obj) {
        this.type = type;
        this.obj = obj;
    }
}
