package com.github.tvbox.osc.ui.adapter;

import android.view.KeyEvent;
import android.view.View;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.DanmuSearchResult;

import java.util.ArrayList;

/** 搜索弹幕第二层：某一部剧下的每一集；与当前播放集数一致的会标「疑似此集」 */
public class SearchDanmuAdapter extends BaseQuickAdapter<DanmuSearchResult, BaseViewHolder> {

    /** 当前正在播放的集数，0 表示未知（不做「疑似」标记） */
    private int currentEpisode = 0;

    public SearchDanmuAdapter() {
        super(R.layout.item_search_danmu_result, new ArrayList<DanmuSearchResult>());
    }

    public void setCurrentEpisode(int episode) {
        this.currentEpisode = episode;
    }

    @Override
    protected void convert(BaseViewHolder helper, DanmuSearchResult item) {
        int episode = item.getEpisode();
        // 标题原样显示接口返回的内容，不做任何替换/裁剪；集数只用来打「疑似此集」的标
        helper.setText(R.id.danmuName, item.getName());
        View guess = helper.getView(R.id.danmuGuess);
        if (guess != null) {
            guess.setVisibility(currentEpisode > 0 && episode == currentEpisode ? View.VISIBLE : View.GONE);
        }
        helper.addOnClickListener(R.id.danmuItem);
        View itemView = helper.getView(R.id.danmuItem);
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
