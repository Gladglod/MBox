package com.github.tvbox.osc.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.cache.DownloadRecord;
import com.github.tvbox.osc.download.DownloadManager;
import com.github.tvbox.osc.event.DownloadEvent;
import com.github.tvbox.osc.ui.adapter.DownloadBarAdapter;
import com.github.tvbox.osc.ui.dialog.ConfirmClearDialog;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.GlassTip;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 离线缓存任务列表：
 * 展示所有缓存任务（排队 / 下载中 / 暂停 / 已完成 / 失败），
 * 支持 暂停 / 继续 / 删除（长按，含文件）/ 清空已完成，已完成任务点击即可离线播放。
 *
 * @author MBox
 */
public class DownloadActivity extends BaseActivity {

    private RecyclerView mGridView;
    private TextView tvEmpty;
    private DownloadBarAdapter adapter;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_download;
    }

    @Override
    public void finish() {
        applyMineBackTransition();
    }

    @Override
    protected boolean shouldRefreshAutoSize() {
        return true;
    }

    @Override
    protected void init() {
        EventBus.getDefault().register(this);
        tvEmpty = findViewById(R.id.tvEmpty);
        mGridView = findViewById(R.id.mGridView);
        mGridView.setLayoutManager(new LinearLayoutManager(mContext, LinearLayoutManager.VERTICAL, false));
        adapter = new DownloadBarAdapter();
        mGridView.setAdapter(adapter);

        findViewById(R.id.ivBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                onBackPressed();
            }
        });
        findViewById(R.id.tvClean).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                DownloadManager.get().clearFinished(true);
                GlassTip.show(mContext, "已清空已完成任务");
            }
        });

        adapter.setOnItemActionListener(new DownloadBarAdapter.OnItemActionListener() {
            @Override
            public void onAction(DownloadRecord item) {
                if (item == null) return;
                if (item.isDone()) {
                    playCached(item);
                } else if (item.state == DownloadRecord.STATE_PAUSE || item.state == DownloadRecord.STATE_ERROR) {
                    DownloadManager.get().resume(item);
                    GlassTip.show(mContext, "已继续下载");
                } else {
                    DownloadManager.get().pause(item);
                    GlassTip.show(mContext, "已暂停");
                }
            }

            @Override
            public void onItemLongClick(final DownloadRecord item) {
                if (item == null) return;
                new ConfirmClearDialog(DownloadActivity.this, "")
                        .setMessage("删除该缓存并清除文件?")
                        .setOnConfirm(new Runnable() {
                            @Override
                            public void run() {
                                DownloadManager.get().delete(item);
                                GlassTip.show(DownloadActivity.this, "已删除");
                            }
                        })
                        .show();
            }
        });

        initData();
    }

    private void playCached(DownloadRecord item) {
        File file = new File(item.filePath);
        if (!file.exists()) {
            GlassTip.show(mContext, "缓存文件不存在，已标记失败");
            item.state = DownloadRecord.STATE_ERROR;
            item.errorMsg = "缓存文件不存在";
            DownloadManager.get().delete(item);
            initData();
            return;
        }
        // 同一剧同一线路的全部已完成集数，按集数排序后传给播放器做多集连播
        List<DownloadRecord> all = DownloadManager.get().getAll();
        List<DownloadRecord> playlist = new ArrayList<>();
        if (all != null) {
            for (DownloadRecord r : all) {
                if (r.isDone()
                        && r.sourceKey != null && r.sourceKey.equals(item.sourceKey)
                        && r.vodId != null && r.vodId.equals(item.vodId)
                        && r.playFlag != null && r.playFlag.equals(item.playFlag)
                        && r.filePath != null
                        && new File(r.filePath).exists()) {
                    playlist.add(r);
                }
            }
        }
        Collections.sort(playlist, new Comparator<DownloadRecord>() {
            @Override
            public int compare(DownloadRecord o1, DownloadRecord o2) {
                return o1.playIndex - o2.playIndex;
            }
        });
        int index = 0;
        for (int i = 0; i < playlist.size(); i++) {
            if (playlist.get(i).getId() == item.getId()) {
                index = i;
                break;
            }
        }
        Bundle bundle = new Bundle();
        if (playlist.size() > 1) {
            String[] paths = new String[playlist.size()];
            String[] titles = new String[playlist.size()];
            for (int i = 0; i < playlist.size(); i++) {
                DownloadRecord r = playlist.get(i);
                paths[i] = r.filePath;
                titles[i] = (r.vodName == null ? "" : r.vodName + " · ")
                        + (r.episodeName == null ? "EP" + (r.playIndex + 1) : r.episodeName);
            }
            bundle.putStringArray(LocalPlayActivity.EXTRA_PATHS, paths);
            bundle.putStringArray(LocalPlayActivity.EXTRA_TITLES, titles);
            bundle.putInt(LocalPlayActivity.EXTRA_INDEX, index);
        } else {
            bundle.putString(LocalPlayActivity.EXTRA_PATH, item.filePath);
            String title = (item.vodName == null ? "" : item.vodName + " · ")
                    + (item.episodeName == null ? "EP" + (item.playIndex + 1) : item.episodeName);
            bundle.putString(LocalPlayActivity.EXTRA_TITLE, title);
        }
        jumpActivity(LocalPlayActivity.class, bundle);
    }

    private void initData() {
        List<DownloadRecord> records = DownloadManager.get().getAll();
        adapter.setNewData(records);
        tvEmpty.setVisibility(records == null || records.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDownloadEvent(DownloadEvent event) {
        DownloadRecord record = event.obj instanceof DownloadRecord ? (DownloadRecord) event.obj : null;
        if (record == null) {
            initData();
            return;
        }
        switch (event.type) {
            case DownloadEvent.TYPE_ADD:
                adapter.addData(0, record);
                tvEmpty.setVisibility(View.GONE);
                break;
            case DownloadEvent.TYPE_UPDATE:
                updateRecord(record);
                break;
            case DownloadEvent.TYPE_REMOVE:
                removeRecord(record);
                break;
        }
    }

    private void updateRecord(DownloadRecord record) {
        List<DownloadRecord> data = adapter.getData();
        for (int i = 0; i < data.size(); i++) {
            DownloadRecord item = data.get(i);
            if (item.getId() == record.getId()) {
                // 保留内存对象与 DB 一致（同一对象引用时直接刷新）
                item.state = record.state;
                item.progress = record.progress;
                item.currentLen = record.currentLen;
                item.totalLen = record.totalLen;
                item.errorMsg = record.errorMsg;
                item.filePath = record.filePath;
                adapter.notifyItemChanged(i);
                return;
            }
        }
        initData();
    }

    private void removeRecord(DownloadRecord record) {
        List<DownloadRecord> data = adapter.getData();
        for (int i = 0; i < data.size(); i++) {
            if (data.get(i).getId() == record.getId()) {
                data.remove(i);
                adapter.notifyItemRemoved(i);
                break;
            }
        }
        tvEmpty.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
    }
}
