package com.github.tvbox.osc.ui.adapter;

import android.util.SparseArray;
import android.view.ViewGroup;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.fragment.app.FragmentTransaction;

import com.github.tvbox.osc.base.BaseLazyFragment;

/**
 * 首页各分类页的 PagerAdapter。
 *
 * <p>相比原来的「先把所有 Fragment 实例都 new 出来放进 List」，这里改成按需创建：
 * ViewPager 真正需要某一页时才回调 {@link PageProvider#create(int)}，页面被滑出
 * offscreenPageLimit 后由 {@link FragmentPagerAdapter} detach 掉，视图树随之销毁
 * （{@link BaseLazyFragment#isKeepViewOnDestroy()} 返回 false 的页面会释放 rootView），
 * 这样首页再多的分类也不会一次性把海报 bitmap 全堆在内存里。</p>
 */
public class HomePageAdapter extends FragmentPagerAdapter {

    public interface PageProvider {
        BaseLazyFragment create(int position);
    }

    public FragmentManager fragmentManager;
    private final long adapterId = System.nanoTime();
    private final int count;
    private final PageProvider provider;
    private final SparseArray<BaseLazyFragment> created = new SparseArray<>();

    public HomePageAdapter(FragmentManager fm, int count, PageProvider provider) {
        super(fm);
        this.fragmentManager = fm;
        this.count = count;
        this.provider = provider;
    }

    /**
     * 已经创建过的页面；还没被 ViewPager 请求过时返回 null。
     * 调用方需要判空（例如返回键逻辑）。
     */
    public BaseLazyFragment getFragment(int position) {
        return created.get(position);
    }

    public void removeAll() {
        if (fragmentManager == null) return;
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        for (int i = 0; i < created.size(); i++) {
            BaseLazyFragment fragment = created.valueAt(i);
            // 这里不能只判断 isAdded()：页面被 detach 之后 isAdded() 已经是 false，
            // 但 Fragment 仍挂在 FragmentManager 上，不 remove 掉会随着每次刷新越积越多。
            if (fragment != null) {
                transaction.remove(fragment);
            }
        }
        transaction.commitAllowingStateLoss();
        fragmentManager.executePendingTransactions();
        created.clear();
        notifyDataSetChanged();
    }

    @Override
    public Fragment getItem(int position) {
        BaseLazyFragment fragment = created.get(position);
        if (fragment == null) {
            fragment = provider.create(position);
            created.put(position, fragment);
        }
        return fragment;
    }

    @Override
    public long getItemId(int position) {
        return adapterId + position;
    }

    @Override
    public int getCount() {
        return count;
    }

    @Override
    public void destroyItem(ViewGroup container, int position, Object object) {
        // 默认实现就是 detach：保留 Fragment 实例（省掉重建），但销毁它的视图树。
        // 原来这里改成了 hide()，页面滑走后 RecyclerView 和海报 bitmap 会一直留着。
        super.destroyItem(container, position, object);
    }
}
