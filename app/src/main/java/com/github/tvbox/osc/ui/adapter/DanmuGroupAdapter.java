package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.DanmuGroup;

import java.util.ArrayList;

/** 搜索弹幕第一层/同名各部层：一级显示合成剧名，同名多来源时会先进各部（完整剧名）列表 */
public class DanmuGroupAdapter extends BaseQuickAdapter<DanmuGroup, BaseViewHolder> {

    /** 当前播放集数，用于在分组上提示「含第N集」 */
    private int currentEpisode = 0;

    public DanmuGroupAdapter() {
        super(R.layout.item_danmu_group, new ArrayList<DanmuGroup>());
    }

    public void setCurrentEpisode(int episode) {
        this.currentEpisode = episode;
    }

    @Override
    protected void convert(BaseViewHolder helper, DanmuGroup item) {
        helper.setText(R.id.groupName, item.getShowName());
        // 标题里已经带了来源就不再重复，避免「xx弹幕 · xx弹幕 · 共3集」
        StringBuilder meta = new StringBuilder();
        String source = item.getSource();
        if (!TextUtils.isEmpty(source) && !item.getShowName().contains(source)) {
            meta.append(source).append(" · ");
        }
        meta.append("共 ").append(item.size()).append(" 条");
        if (currentEpisode > 0 && item.containsEpisode(currentEpisode)) {
            meta.append(" · 含第").append(currentEpisode).append("集");
        }
        helper.setText(R.id.groupMeta, meta.toString());
        helper.addOnClickListener(R.id.groupItem);
        View itemView = helper.getView(R.id.groupItem);
        itemView.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP
                    && (keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                    || keyCode == KeyEvent.KEYCODE_BUTTON_A)) {
                view.performClick();
                return true;
            }
            return false;
        });
    }
}
