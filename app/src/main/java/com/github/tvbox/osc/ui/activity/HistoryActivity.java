package com.github.tvbox.osc.ui.activity;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.ui.adapter.HistoryBarAdapter;
import com.github.tvbox.osc.ui.dialog.ConfirmClearDialog;
import com.github.tvbox.osc.ui.widget.SmoothTopScroller;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

/**
 * 播放历史：条状列表。
 * 支持左滑「置顶 / 删除」，以及右上角「编辑」进入多选批量删除。
 */
public class HistoryActivity extends BaseActivity {

    private RecyclerView mGridView;
    private TextView tvEdit;
    private TextView tvEmpty;
    private TextView tvSelectAll;
    private TextView tvDeleteSelected;
    private View bottomBar;
    public static HistoryBarAdapter historyAdapter;
    private boolean editMode = false;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_history;
    }

    @Override
    protected boolean shouldRefreshAutoSize() {
        return true;
    }

    @Override
    protected void init() {
        initView();
        initData();
    }

    private void initView() {
        EventBus.getDefault().register(this);
        tvEdit = findViewById(R.id.tvEdit);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvSelectAll = findViewById(R.id.tvSelectAll);
        tvDeleteSelected = findViewById(R.id.tvDeleteSelected);
        bottomBar = findViewById(R.id.bottomBar);
        mGridView = findViewById(R.id.mGridView);
        findViewById(R.id.ivBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                onBackPressed();
            }
        });
        mGridView.setLayoutManager(new LinearLayoutManager(mContext, LinearLayoutManager.VERTICAL, false));
        // 条目高度固定，固定尺寸可以省掉每次数据变化后的重新测量
        mGridView.setHasFixedSize(true);
        historyAdapter = new HistoryBarAdapter();
        mGridView.setAdapter(historyAdapter);

        // 点标题 = 平滑回到顶部（0 号 item 半露时也能精确收顶）
        findViewById(R.id.tvTitle).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SmoothTopScroller.scrollToTop(mGridView);
            }
        });

        tvEdit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                toggleEditMode();
            }
        });
        findViewById(R.id.tvDone).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                toggleEditMode();
            }
        });
        tvSelectAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                historyAdapter.selectAll();
                updateDeleteLabel();
            }
        });
        tvDeleteSelected.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                deleteSelected();
            }
        });

        historyAdapter.setOnActionListener(new HistoryBarAdapter.OnActionListener() {
            @Override
            public void onPin(VodInfo item) {
                RoomDataManger.pinVodRecord(item.sourceKey, item.id);
                initData();
                Toast.makeText(mContext, "已置顶", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onDelete(VodInfo item) {
                RoomDataManger.deleteVodRecord(item.sourceKey, item);
                historyAdapter.getData().remove(item);
                historyAdapter.notifyDataSetChanged();
                updateEmpty();
            }
        });

        historyAdapter.setOnItemActionListener(new HistoryBarAdapter.OnItemActionListener() {
            @Override
            public void onItemClick(VodInfo vodInfo) {
                if (vodInfo == null) return;
                if (editMode) {
                    // 编辑模式下点条目 = 勾选 / 取消勾选
                    historyAdapter.toggleSelection(vodInfo);
                    updateDeleteLabel();
                    return;
                }
                openDetail(vodInfo);
            }

            @Override
            public void onItemLongClick(VodInfo vodInfo) {
                if (editMode || vodInfo == null) return;
                Bundle bundle = new Bundle();
                bundle.putString("title", vodInfo.name);
                if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)) {
                    jumpActivity(FastSearchActivity.class, bundle);
                } else {
                    jumpActivity(SearchActivity.class, bundle);
                }
            }
        });
    }

    private void openDetail(VodInfo vodInfo) {
        Bundle bundle = new Bundle();
        bundle.putString("id", vodInfo.id);
        bundle.putString("sourceKey", vodInfo.sourceKey);
        SourceBean sourceBean = ApiConfig.get().getSource(vodInfo.sourceKey);
        bundle.putString("title", vodInfo.name);
        bundle.putString("picture", vodInfo.pic);
        if (sourceBean != null) {
            jumpActivity(DetailActivity.class, bundle);
        } else if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)) {
            jumpActivity(FastSearchActivity.class, bundle);
        } else {
            jumpActivity(SearchActivity.class, bundle);
        }
    }

    /* ============================ 编辑模式 ============================ */

    private void toggleEditMode() {
        editMode = !editMode;
        historyAdapter.setEditMode(editMode);
        bottomBar.setVisibility(editMode ? View.VISIBLE : View.GONE);
        tvEdit.setText(editMode ? "取消" : "编辑");
        updateDeleteLabel();
    }

    private void updateDeleteLabel() {
        int count = historyAdapter == null ? 0 : historyAdapter.getSelectedCount();
        tvDeleteSelected.setText(count > 0 ? "删除(" + count + ")" : "删除");
    }

    private void deleteSelected() {
        List<VodInfo> selected = historyAdapter.getSelectedItems();
        if (selected.isEmpty()) {
            Toast.makeText(mContext, "请先选择要删除的记录", Toast.LENGTH_SHORT).show();
            return;
        }
        for (VodInfo info : selected) {
            RoomDataManger.deleteVodRecord(info.sourceKey, info);
        }
        historyAdapter.setEditMode(false);
        editMode = false;
        bottomBar.setVisibility(View.GONE);
        tvEdit.setText("编辑");
        initData();
        Toast.makeText(mContext, "已删除 " + selected.size() + " 条", Toast.LENGTH_SHORT).show();
    }

    private void initData() {
        List<VodInfo> allVodRecord = RoomDataManger.getAllVodRecord(100);
        List<VodInfo> vodInfoList = new ArrayList<>();
        if (allVodRecord != null) {
            for (VodInfo vodInfo : allVodRecord) {
                if (vodInfo.playNote != null && !vodInfo.playNote.isEmpty()) {
                    vodInfo.note = "上次看到" + vodInfo.playNote;
                }
                vodInfoList.add(vodInfo);
            }
        }
        historyAdapter.setNewData(vodInfoList);
        updateEmpty();
        updateDeleteLabel();
    }

    private void updateEmpty() {
        boolean empty = historyAdapter == null || historyAdapter.getData().isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_HISTORY_REFRESH) {
            initData();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
    }

    @Override
    public void onBackPressed() {
        if (editMode) {
            toggleEditMode();
            return;
        }
        super.onBackPressed();
    }
}
