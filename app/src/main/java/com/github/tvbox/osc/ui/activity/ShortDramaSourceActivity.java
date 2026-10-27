package com.github.tvbox.osc.ui.activity;

import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.ShortDramaSource;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.ui.adapter.ShortDramaSourceAdapter;
import com.github.tvbox.osc.ui.dialog.ChoiceListDialog;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.ShortDramaConfig;
import com.github.tvbox.osc.util.ShortDramaScanner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「短剧源」设置页。
 *
 * <p>入口在「我的 → 数据源地址」里。这一页负责三件事：</p>
 * <ol>
 *     <li>自动识别：进入即续扫配置里还没扫过的站点，结果直接并进列表；</li>
 *     <li>勾选：决定哪些源出现在首页「短剧」页；</li>
 *     <li>手动添加：从任意站点里挑一个分类，作为短剧源加进来 ——
 *     自动识别靠关键词，总有漏网（比如叫「微剧场」「精选」的分类）。</li>
 * </ol>
 */
public class ShortDramaSourceActivity extends BaseActivity {

    private ShortDramaSourceAdapter adapter;
    private TextView tvEmpty;
    private TextView tvScanStatus;
    private TextView btnAdd;

    private int lastFound = 0;
    private int scanDone = 0;
    private int scanTotal = 0;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_short_drama_source;
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
        findViewById(R.id.tvDone).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                finish();
            }
        });

        tvEmpty = findViewById(R.id.tvEmpty);
        tvScanStatus = findViewById(R.id.tvScanStatus);
        btnAdd = findViewById(R.id.btnAdd);

        RecyclerView recyclerView = findViewById(R.id.sourceList);
        adapter = new ShortDramaSourceAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setHasFixedSize(true);
        recyclerView.setAdapter(adapter);

        adapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter baseAdapter, View view, int position) {
                FastClickCheckUtil.check(view);
                if (position < 0 || position >= adapter.getData().size()) return;
                ShortDramaSource item = adapter.getData().get(position);
                if (!item.alive) {
                    Toast.makeText(ShortDramaSourceActivity.this,
                            R.string.short_drama_source_dead_tag, Toast.LENGTH_SHORT).show();
                    return;
                }
                item.enabled = !item.enabled;
                ShortDramaConfig.saveAll(adapter.getData());
                adapter.notifyItemChanged(position);
            }
        });
        adapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter baseAdapter, View view, int position) {
                if (position < 0 || position >= adapter.getData().size()) return false;
                ShortDramaSource item = adapter.getData().get(position);
                if (item.manual || !item.alive) {
                    remove(item);
                    return true;
                }
                return false;
            }
        });

        findViewById(R.id.btnRescan).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                startScan(true);
            }
        });
        btnAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                pickSite();
            }
        });

        reload();
        // 进页面就续扫：没扫过的站点补上，扫过的跳过，所以点进来几乎立刻有结果
        startScan(false);
    }

    /* ============================ 列表 ============================ */

    private void reload() {
        List<ShortDramaSource> list = ShortDramaConfig.loadAll();
        adapter.setNewData(list);
        tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void remove(ShortDramaSource item) {
        ShortDramaConfig.remove(item);
        reload();
        Toast.makeText(this, R.string.short_drama_source_removed, Toast.LENGTH_SHORT).show();
    }

    /* ============================ 自动识别 ============================ */

    private void startScan(boolean force) {
        if (!ShortDramaScanner.scan(force, new ShortDramaScanner.Listener() {
            @Override
            public void onStart(int total) {
                if (isFinishing()) return;
                scanDone = 0;
                scanTotal = total;
                showScanStatus(getString(R.string.short_drama_scanning, scanDone, scanTotal));
            }

            @Override
            public void onProgress(int done, int total, int found) {
                if (isFinishing()) return;
                scanDone = done;
                scanTotal = total;
                showScanStatus(getString(R.string.short_drama_scanning, scanDone, scanTotal));
                if (found > lastFound) {
                    lastFound = found;
                    reload();
                }
            }

            @Override
            public void onFinish(int found, boolean complete) {
                if (isFinishing()) return;
                lastFound = found;
                reload();
                if (complete) {
                    showScanStatus(getString(R.string.short_drama_source_scanned, scanDone));
                } else {
                    showScanStatus(getString(R.string.short_drama_scan_partial, Math.max(0, scanTotal - scanDone)));
                }
            }
        })) {
            showScanStatus(getString(R.string.short_drama_scanning, scanDone, scanTotal));
        }
    }

    private void showScanStatus(String text) {
        if (tvScanStatus == null) return;
        tvScanStatus.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
        tvScanStatus.setText(text);
    }

    /* ============================ 手动添加 ============================ */

    private void pickSite() {
        final List<SourceBean> sites = new ArrayList<>();
        for (SourceBean bean : ApiConfig.get().getSourceBeanList()) {
            if (bean == null) continue;
            int type = bean.getType();
            if (type != 0 && type != 1 && type != 3 && type != 4) continue;
            String name = bean.getName();
            if (!TextUtils.isEmpty(name) && name.length() <= 3 && name.endsWith("搜")) continue;
            sites.add(bean);
        }
        if (sites.isEmpty()) {
            Toast.makeText(this, R.string.short_drama_source_no_site, Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> names = new ArrayList<>();
        Map<String, Integer> dup = new HashMap<>();
        for (SourceBean bean : sites) {
            String name = TextUtils.isEmpty(bean.getName()) ? bean.getKey() : bean.getName();
            Integer count = dup.get(name);
            dup.put(name, count == null ? 1 : count + 1);
            names.add(name);
        }
        // 同名站点补上 key，否则列表里会出现两个一模一样的名字没法选
        for (int i = 0; i < names.size(); i++) {
            if (dup.get(names.get(i)) > 1) {
                names.set(i, names.get(i) + " · " + sites.get(i).getKey());
            }
        }
        new ChoiceListDialog(this, getString(R.string.short_drama_source_pick_site), names, -1,
                new ChoiceListDialog.OnPick() {
                    @Override
                    public void onPick(int position, String value) {
                        if (position < 0 || position >= sites.size()) return;
                        loadCategories(sites.get(position));
                    }
                }).show();
    }

    private void loadCategories(final SourceBean bean) {
        btnAdd.setEnabled(false);
        showScanStatus(getString(R.string.short_drama_source_loading));
        ShortDramaScanner.fetchCategories(bean.getKey(), new ShortDramaScanner.CategoriesCallback() {
            @Override
            public void onResult(List<MovieSort.SortData> list) {
                if (isFinishing()) return;
                btnAdd.setEnabled(true);
                showScanStatus("");
                if (list == null || list.isEmpty()) {
                    Toast.makeText(ShortDramaSourceActivity.this,
                            R.string.short_drama_source_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                final List<MovieSort.SortData> categories = new ArrayList<>();
                List<String> names = new ArrayList<>();
                for (MovieSort.SortData sort : list) {
                    if (sort == null || TextUtils.isEmpty(sort.id)) continue;
                    categories.add(sort);
                    names.add(TextUtils.isEmpty(sort.name) ? sort.id : sort.name);
                }
                if (categories.isEmpty()) {
                    Toast.makeText(ShortDramaSourceActivity.this,
                            R.string.short_drama_source_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                new ChoiceListDialog(ShortDramaSourceActivity.this,
                        getString(R.string.short_drama_source_pick_category), names, -1,
                        new ChoiceListDialog.OnPick() {
                            @Override
                            public void onPick(int position, String value) {
                                if (position < 0 || position >= categories.size()) return;
                                MovieSort.SortData sort = categories.get(position);
                                boolean added = ShortDramaConfig.addManual(bean.getKey(), sort.id, sort.name);
                                Toast.makeText(ShortDramaSourceActivity.this,
                                        added ? getString(R.string.short_drama_source_added, sort.name)
                                                : getString(R.string.short_drama_source_exists),
                                        Toast.LENGTH_SHORT).show();
                                reload();
                            }
                        }).show();
            }
        });
    }
}
