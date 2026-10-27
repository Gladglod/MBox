package com.github.tvbox.osc.callback;

import com.github.tvbox.osc.R;
import com.kingja.loadsir.callback.Callback;

/**
 * 首页专用的加载占位：海报骨架。
 *
 * <p>首页内容是一面海报墙，用一个居中的转圈既说明不了"正在加载什么"，
 * 又会在内容出现的瞬间整块跳一下。骨架屏按真实网格的尺寸先占好位置，
 * 数据回来后直接替换，位置不跳。</p>
 */
public class HomeSkeletonCallback extends Callback {

    @Override
    protected int onCreateView() {
        return R.layout.loadsir_home_skeleton;
    }
}
