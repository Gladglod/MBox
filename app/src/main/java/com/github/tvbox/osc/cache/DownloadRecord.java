package com.github.tvbox.osc.cache;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;

/**
 * 离线缓存任务记录（Room 实体）。
 * 一条记录 = 一个待缓存 / 已缓存的分集视频文件。
 *
 * @author MBox
 */
@Entity(tableName = "downloadRecord")
public class DownloadRecord implements Serializable {

    /** 等待中（排队） */
    public static final int STATE_WAIT = 0;
    /** 下载中 */
    public static final int STATE_RUNNING = 1;
    /** 已暂停 */
    public static final int STATE_PAUSE = 2;
    /** 已完成 */
    public static final int STATE_DONE = 3;
    /** 失败 */
    public static final int STATE_ERROR = 4;

    /** 直链文件（mp4 / mkv ...） */
    public static final int TYPE_DIRECT = 0;
    /** m3u8（HLS，分段下载后合并） */
    public static final int TYPE_M3U8 = 1;

    @PrimaryKey(autoGenerate = true)
    private int id;

    @ColumnInfo(name = "sourceKey")
    public String sourceKey;

    @ColumnInfo(name = "vodId")
    public String vodId;

    @ColumnInfo(name = "vodName")
    public String vodName;

    @ColumnInfo(name = "vodPic")
    public String vodPic;

    @ColumnInfo(name = "playFlag")
    public String playFlag;

    @ColumnInfo(name = "playIndex")
    public int playIndex;

    @ColumnInfo(name = "episodeName")
    public String episodeName;

    /** 原始集地址（详情页里存的页面/直链地址） */
    @ColumnInfo(name = "url")
    public String url;

    /** 播放请求头 JSON（playerContent 返回的 header），下载时带上 */
    @ColumnInfo(name = "headersJson")
    public String headersJson;

    /** 存储目录（每个剧一个子目录） */
    @ColumnInfo(name = "saveDir")
    public String saveDir;

    /** 最终文件路径（完成后有效） */
    @ColumnInfo(name = "filePath")
    public String filePath;

    /** TYPE_DIRECT / TYPE_M3U8 */
    @ColumnInfo(name = "type")
    public int type;

    /** STATE_* */
    @ColumnInfo(name = "state")
    public int state = STATE_WAIT;

    /** 进度百分比 0~100（m3u8 按分段数估算，直链按字节） */
    @ColumnInfo(name = "progress")
    public int progress = 0;

    /** 已下载字节数（m3u8 为已落盘分段累计） */
    @ColumnInfo(name = "currentLen")
    public long currentLen = 0;

    /** 总字节数（直链为 contentLength；m3u8 未知时为 0） */
    @ColumnInfo(name = "totalLen")
    public long totalLen = 0;

    /** 失败原因 */
    @ColumnInfo(name = "errorMsg")
    public String errorMsg;

    @ColumnInfo(name = "createTime")
    public long createTime;

    @ColumnInfo(name = "updateTime")
    public long updateTime;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    /** 判断是否还在队列里（未完成） */
    public boolean isActive() {
        return state == STATE_WAIT || state == STATE_RUNNING || state == STATE_PAUSE;
    }

    public boolean isDone() {
        return state == STATE_DONE;
    }
}
