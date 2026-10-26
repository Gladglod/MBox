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
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.cache.VodCollect;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.ui.adapter.CollectBarAdapter;
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
 * 收藏：与「播放历史」一致的条状列表。
 * 支持左滑「置顶 / 删除」，以及右上角「编辑」进入多选批量删除。
 */
public class CollectActivity extends BaseActivity {

    private RecyclerView mGridView;
    private TextView tvEdit;
    private TextView tvEmpty;
    private TextView tvDeleteSelected;
    private View bottomBar;
    public static CollectBarAdapter collectAdapter;
    private boolean editMode = false;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_collect;
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
        collectAdapter = new CollectBarAdapter();
        mGridView.setAdapter(collectAdapter);

        // 点标题 = 平滑回到顶部
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
        findViewById(R.id.tvSelectAll).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                collectAdapter.selectAll();
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

        collectAdapter.setOnActionListener(new CollectBarAdapter.OnActionListener() {
            @Override
            public void onPin(VodCollect item) {
                RoomDataManger.pinVodCollect(item.getId());
                initData();
                Toast.makeText(mContext, "已置顶", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onDelete(VodCollect item) {
                RoomDataManger.deleteVodCollect(item.getId());
                collectAdapter.getData().remove(item);
                collectAdapter.notifyDataSetChanged();
                updateEmpty();
            }
        });

        collectAdapter.setOnItemActionListener(new CollectBarAdapter.OnItemActionListener() {
            @Override
            public void onItemClick(VodCollect item) {
                if (item == null) return;
                if (editMode) {
                    collectAdapter.toggleSelection(item);
                    updateDeleteLabel();
                    return;
                }
                openDetail(item);
            }

            @Override
            public void onItemLongClick(VodCollect item) {
                if (editMode || item == null) return;
                Bundle bundle = new Bundle();
                bundle.putString("title", item.name);
                if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)) {
                    jumpActivity(FastSearchActivity.class, bundle);
                } else {
                    jumpActivity(SearchActivity.class, bundle);
                }
            }
        });
    }

    private void openDetail(VodCollect item) {
        if (ApiConfig.get().getSource(item.sourceKey) != null) {
            Bundle bundle = new Bundle();
            bundle.putString("id", item.vodId);
            bundle.putString("sourceKey", item.sourceKey);
            bundle.putString("title", item.name);
            bundle.putString("picture", item.pic);
            bundle.putBoolean("collect", true);
            jumpActivity(DetailActivity.class, bundle);
        } else {
            Bundle bundle = new Bundle();
            bundle.putString("title", item.name);
            if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)) {
                jumpActivity(FastSearchActivity.class, bundle);
            } else {
                jumpActivity(SearchActivity.class, bundle);
            }
        }
    }

    /* ============================ 编辑模式 ============================ */

    private void toggleEditMode() {
        editMode = !editMode;
        collectAdapter.setEditMode(editMode);
        bottomBar.setVisibility(editMode ? View.VISIBLE : View.GONE);
        tvEdit.setText(editMode ? "取消" : "编辑");
        updateDeleteLabel();
    }

    private void updateDeleteLabel() {
        int count = collectAdapter == null ? 0 : collectAdapter.getSelectedCount();
        tvDeleteSelected.setText(count > 0 ? "删除(" + count + ")" : "删除");
    }

    private void deleteSelected() {
        List<VodCollect> selected = collectAdapter.getSelectedItems();
        if (selected.isEmpty()) {
            Toast.makeText(mContext, "请先选择要删除的收藏", Toast.LENGTH_SHORT).show();
            return;
        }
        for (VodCollect item : selected) {
            RoomDataManger.deleteVodCollect(item.getId());
        }
        collectAdapter.setEditMode(false);
        editMode = false;
        bottomBar.setVisibility(View.GONE);
        tvEdit.setText("编辑");
        initData();
        Toast.makeText(mContext, "已删除 " + selected.size() + " 条", Toast.LENGTH_SHORT).show();
    }

    private void initData() {
        List<VodCollect> all = RoomDataManger.getAllVodCollect();
        List<VodCollect> list = new ArrayList<>();
        if (all != null) list.addAll(all);
        collectAdapter.setNewData(list);
        updateEmpty();
        updateDeleteLabel();
    }

    private void updateEmpty() {
        boolean empty = collectAdapter == null || collectAdapter.getData().isEmpty();
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
