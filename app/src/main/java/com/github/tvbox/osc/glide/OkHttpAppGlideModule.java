package com.github.tvbox.osc.glide;

import android.content.Context;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
import com.bumptech.glide.load.engine.cache.LruResourceCache;
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator;
import com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.module.AppGlideModule;

import java.io.InputStream;

@GlideModule
public class OkHttpAppGlideModule extends AppGlideModule {

    /** 磁盘缓存目录（在 context.getCacheDir() 下） */
    private static final String DISK_CACHE_DIR = "glide_cache";
    /** 磁盘缓存上限 300MB：首页海报 + 详情页缩略图反复进出，缓存太小会一直回源 */
    private static final long DISK_CACHE_SIZE = 300L * 1024 * 1024;

    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {
        registry.replace(GlideUrl.class, InputStream.class, new OkHttpUrlLoader.Factory(com.github.catvod.net.OkHttp.client()));
    }

    /**
     * 限制图片缓存上限，并显式指定磁盘缓存。
     * Glide 默认按"屏幕数量"推算内存缓存，在大内存/高分屏设备上会算出几百 MB 的 bitmap 缓存
     * （Android 8+ 的 bitmap 分配在 native heap，实测模拟器上能涨到 600MB+）。
     * 首页海报只有固定几档尺寸，按 2 屏内存缓存 + 3 屏 bitmap 池就足够了。
     */
    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        MemorySizeCalculator calculator = new MemorySizeCalculator.Builder(context)
                .setMemoryCacheScreens(2f)
                .setBitmapPoolScreens(3f)
                .setMaxSizeMultiplier(0.2f)
                .build();
        builder.setMemoryCache(new LruResourceCache(calculator.getMemoryCacheSize()));
        builder.setBitmapPool(new LruBitmapPool(calculator.getBitmapPoolSize()));
        // 显式开启磁盘缓存，避免同一张海报在「首页 → 详情 → 返回」之间反复走网络
        builder.setDiskCache(new InternalCacheDiskCacheFactory(context, DISK_CACHE_DIR, DISK_CACHE_SIZE));
    }

    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
}
