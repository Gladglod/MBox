package com.github.tvbox.osc.ui.activity;

import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.ui.adapter.HomeTabAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HomeTabConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 「首页标签」编辑页：勾选决定哪些分类出现在首页，长按拖动调整顺序。
 *
 * <p>保存后首页会在 {@code onResume} 里检测到配置变化并重建页签与页面。</p>
 */
public class HomeTabSettingActivity extends BaseActivity {

    private HomeTabAdapter adapter;
    private RecyclerView recyclerView;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_home_tab_setting;
    }

    @Override
    protected boolean shouldRefreshAutoSize() {
        return true;
    }

    @Override
    protected void init() {
        findViewById(R.id.ivBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                onBackPressed();
            }
        });
        findViewById(R.id.tvSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                save();
            }
        });
        findViewById(R.id.tvSelectAll).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                setAllVisible(true);
            }
        });
        findViewById(R.id.tvSelectNone).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                setAllVisible(false);
            }
        });
        findViewById(R.id.tvReset).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                HomeTabConfig.reset();
                Toast.makeText(HomeTabSettingActivity.this, "已恢复默认", Toast.LENGTH_SHORT).show();
                finish();
            }
        });

        recyclerView = findViewById(R.id.tabList);
        adapter = new HomeTabAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setHasFixedSize(true);
        recyclerView.setAdapter(adapter);
        adapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter baseAdapter, View view, int position) {
                FastClickCheckUtil.check(view);
                if (position < 0 || position >= adapter.getData().size()) return;
                HomeTabConfig.Tab tab = adapter.getData().get(position);
                tab.visible = !tab.visible;
                adapter.notifyItemChanged(position);
            }
        });
        attachDragHelper();

        List<HomeTabConfig.Tab> tabs = new ArrayList<>(HomeTabConfig.build(HomeActivity.getSortSnapshot()));
        adapter.setNewData(tabs);
        TextView tip = findViewById(R.id.tvTip);
        if (tabs.isEmpty()) {
            tip.setText("还没有拿到数据源的分类，请先回到首页等数据加载完成后再进来设置。");
        } else {
            tip.setText("勾选决定该分类是否显示在首页，长按可拖动排序。「热门推荐」固定在第 1 位。");
        }
    }

    private void attachDragHelper() {
        ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder,
                                  RecyclerView.ViewHolder target) {
                int from = viewHolder.getAdapterPosition();
                int to = target.getAdapterPosition();
                List<HomeTabConfig.Tab> data = adapter.getData();
                if (from < 0 || to < 0 || from >= data.size() || to >= data.size()) return false;
                Collections.swap(data, from, to);
                adapter.notifyItemMoved(from, to);
                return true;
            }

            @Override
            public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return true;
            }
        });
        helper.attachToRecyclerView(recyclerView);
    }

    private void setAllVisible(boolean visible) {
        List<HomeTabConfig.Tab> data = adapter.getData();
        for (HomeTabConfig.Tab tab : data) {
            tab.visible = visible;
        }
        adapter.notifyDataSetChanged();
    }

    private void save() {
        if (adapter.getData().isEmpty()) {
            Toast.makeText(this, "没有可保存的分类", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        HomeTabConfig.save(adapter.getData());
        Toast.makeText(this, "首页标签已更新", Toast.LENGTH_SHORT).show();
        finish();
    }
}
