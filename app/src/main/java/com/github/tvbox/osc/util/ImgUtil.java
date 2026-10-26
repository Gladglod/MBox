package com.github.tvbox.osc.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.FitCenter;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import me.jessyan.autosize.utils.AutoSizeUtils;

public class ImgUtil {
    // 占位图缓存加个上限，避免浏览越多列表 bitmap 越多（原来是无上限 HashMap）
    private static final android.util.LruCache<String, Drawable> drawableCache = new android.util.LruCache<>(48);
    /**
     * Glide 的 model 缓存：URL → GlideUrl。
     * {@link #getUrl(String)} 每次都要做 split / URLDecoder / Gson 解析 @Headers=、@Cookie= 这些后缀，
     * 而列表 bind 时同一个 URL 会被反复请求（滚动回来、复用到同一个 ViewHolder），
     * 所以解析结果必须缓存，否则 onBindViewHolder 里就有一坨字符串运算。
     */
    private static final android.util.LruCache<String, Object> modelCache = new android.util.LruCache<>(256);
    /** RequestOptions 缓存：列表里只有少数几种「尺寸 + 圆角」组合，没必要每次 bind 都 new 一堆 Transform */
    private static final Map<String, RequestOptions> optionsCache = new HashMap<>();
    private static final int OPTIONS_CACHE_MAX = 64;

    public static int defaultWidth = 244;
    public static int defaultHeight = 320;

    public static class Style {
        public float ratio;
        public String type;

        public Style(float ratio, String type) {
            this.ratio = ratio;
            this.type = type;
        }
    }

    public static boolean isBase64Image(String picUrl) {
        return picUrl != null && picUrl.startsWith("data:image");
    }

    public static Style initStyle() {
        String bStyle = ApiConfig.get().getHomeSourceBean().getStyle();
        if (!bStyle.isEmpty()) {
            try {
                JSONObject jsonObject = new JSONObject(bStyle);
                return new Style((float) jsonObject.getDouble("ratio"), jsonObject.getString("type"));
            } catch (JSONException ignored) {
            }
        }
        return null;
    }

    public static int spanCountByStyle(Style style, int defaultCount) {
        int spanCount = defaultCount;
        if ("rect".equals(style.type)) {
            if (style.ratio >= 1.7) {
                spanCount = 3;
            } else if (style.ratio >= 1.3) {
                spanCount = 4;
            }
        } else if ("list".equals(style.type)) {
            spanCount = 1;
        }
        return spanCount;
    }

    public static int getStyleDefaultWidth(Style style) {
        int styleDefaultWidth = 280;
        if (style.ratio < 1) styleDefaultWidth = 214;
        if (style.ratio > 1.7) styleDefaultWidth = 380;
        return styleDefaultWidth;
    }

    public static Bitmap decodeBase64ToBitmap(String base64Str) {
        String base64Data = base64Str.substring(base64Str.indexOf(",") + 1);
        byte[] decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT);
        return BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
    }

    public static void load(String url, ImageView view, int roundingRadius) {
        load(url, view, roundingRadius, 0, 0, null);
    }

    public static void load(String url, ImageView view, int roundingRadius, int newWidth, int newHeight) {
        load(url, view, roundingRadius, newWidth, newHeight, null);
    }

    /**
     * 铺满（CENTER_CROP）加载。
     *
     * @param newWidth  目标宽度（px）。传 0 表示不指定，让 Glide 按 ImageView 实际尺寸解码
     * @param newHeight 目标高度（px）
     */
    public static void load(String url, ImageView view, int roundingRadius, int newWidth, int newHeight, String label) {
        loadInternal(url, view, roundingRadius, newWidth, newHeight, label,
                ImageView.ScaleType.CENTER_CROP, true);
    }

    /**
     * 指定 ScaleType 加载（内部用 FitCenter，适合直播台标这类不想被裁切的图）。
     */
    public static void load(String url, ImageView view, int roundingRadius, int newWidth, int newHeight, String label, ImageView.ScaleType scaleType) {
        loadInternal(url, view, roundingRadius, newWidth, newHeight, label, scaleType, false);
    }

    /**
     * 统一的加载入口。原来这里有两份几乎一样的实现，各自 new 一套 RequestOptions / Transform /
     * RequestListener —— 列表滚动时每次 bind 都在产生垃圾。现在：
     * <ul>
     *     <li>{@link RequestOptions} 按「尺寸 + 圆角 + 是否铺满」缓存复用；</li>
     *     <li>model（{@link GlideUrl}）按 URL 缓存复用；</li>
     *     <li>始终指定 override 尺寸，避免把原图整张解码进内存。</li>
     * </ul>
     */
    private static void loadInternal(String url, ImageView view, int roundingRadius, int newWidth, int newHeight,
                                     String label, ImageView.ScaleType scaleType, boolean centerCrop) {
        if (view == null) return;
        final ImageView.ScaleType finalScale = scaleType == null ? ImageView.ScaleType.CENTER_CROP : scaleType;
        view.setScaleType(finalScale);
        // radius <= 0 表示「不要圆角」：调用方自己已经有圆角背景 + clipToOutline 时，
        // 再让 Glide 复制一张全尺寸 bitmap 去切圆角纯属浪费。
        final int radius = Math.max(0, roundingRadius);
        if (isInvalidImageUrl(url)) {
            view.setImageDrawable(createTextDrawable(TextUtils.isEmpty(label) ? "TVBox" : label, newWidth, newHeight, radius));
            return;
        }
        // 占位图本身也是缓存过的，这里只是查一次 LruCache
        Drawable placeholder = createImagePlaceholderDrawable(newWidth, newHeight, radius);
        Glide.with(App.getInstance())
                .asBitmap()
                .load(getUrl(url))
                .placeholder(placeholder)
                .error(placeholder)
                .listener(getListener(view, finalScale, label, newWidth, newHeight, radius))
                .apply(obtainOptions(centerCrop, newWidth, newHeight, radius))
                .into(view);
    }

    /** 按「尺寸 + 圆角 + 是否铺满」复用 RequestOptions，避免每次 bind 都新建 Transform 对象 */
    private static RequestOptions obtainOptions(boolean centerCrop, int width, int height, int radius) {
        String key = (centerCrop ? "c" : "f") + '_' + width + 'x' + height + '_' + radius;
        RequestOptions options = optionsCache.get(key);
        if (options != null) return options;
        BitmapTransformation scale = centerCrop ? new CenterCrop() : new FitCenter();
        options = new RequestOptions()
                .format(DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .dontAnimate();
        if (radius > 0) {
            options = options.transform(scale, new RoundedCorners(radius));
        } else {
            // 只做缩放，不复制位图去切圆角
            options = options.transform(scale);
        }
        if (width > 0 && height > 0) {
            // 关键：按显示尺寸解码。不指定的话 Glide 会按 ImageView 尺寸推，
            // 而有些场景 ImageView 还没测量完（宽高为 0），就会退回解码整张原图。
            options = options.override(width, height);
        }
        if (optionsCache.size() >= OPTIONS_CACHE_MAX) {
            optionsCache.clear();
        }
        optionsCache.put(key, options);
        return options;
    }

    public static void loadUrl(String url, ImageView view) {
        load(url, view, 10);
    }

    public static void loadVideoScreenshot(String uri, ImageView imageView, long frameTimeMicros) {
        RequestOptions requestOptions = RequestOptions.frameOf(frameTimeMicros * 1000)
                .transform(new CenterCrop(), new RoundedCorners(10));
        Glide.with(App.getInstance())
                .load(uri)
                .skipMemoryCache(true)
                .apply(requestOptions)
                .into(imageView);
    }

    public static int getRandomColor() {
        Random random = new Random();
        return Color.argb(255, random.nextInt(256), random.nextInt(256), random.nextInt(256));
    }

    public static Drawable createTextDrawable(String text) {
        return createTextDrawable(text, 0, 0, AutoSizeUtils.mm2px(App.getInstance(), 10));
    }

    private static Drawable createTextDrawable(String text, int width, int height, float cornerRadius) {
        if (TextUtils.isEmpty(text)) text = "TVBox";
        if (width <= 0) width = 180;
        if (height <= 0) height = 240;
        if (cornerRadius <= 0) cornerRadius = 1;
        // key 用首字而不是全名：同一首字的影片复用同一张占位图，
        // 否则每部剧都会新建一张 200x300 的 bitmap 常驻内存（列表越长越卡）。
        String firstChar = text.substring(0, 1);
        String key = "text_" + firstChar + "_" + width + "x" + height + "_" + (int) cornerRadius;
        Drawable cached = drawableCache.get(key);
        if (cached != null) return cached;
        int randomColor = getRandomColor();
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(randomColor);
        paint.setStyle(Paint.Style.FILL);
        RectF rectF = new RectF(0, 0, width, height);
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint);
        paint.setColor(Color.WHITE);
        paint.setTextSize(60);
        paint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fontMetrics = paint.getFontMetrics();
        float x = width / 2f;
        float y = (height - fontMetrics.bottom - fontMetrics.top) / 2f;
        canvas.drawText(firstChar, x, y, paint);
        Drawable drawable = new BitmapDrawable(App.getInstance().getResources(), bitmap);
        drawableCache.put(key, drawable);
        return drawable;
    }

    private static Drawable createImagePlaceholderDrawable(int width, int height, float cornerRadius) {
        if (width <= 0 || height <= 0) {
            // 尺寸未知（让 Glide 按 View 实测尺寸解码的场景）：直接给一个半透明纯色，
            // 不再为「未知尺寸」专门生成一张 180×240 的 bitmap 占位图。
            // 列表项本身有 bg_poster 背景，视觉上不会空。
            return new ColorDrawable(0x14FFFFFF);
        }
        if (cornerRadius <= 0) cornerRadius = 1;
        String key = "placeholder_" + width + "x" + height + "_" + (int) cornerRadius;
        Drawable cached = drawableCache.get(key);
        if (cached != null) return cached;

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bitmap);

        Bitmap icon = BitmapFactory.decodeResource(App.getInstance().getResources(), com.github.tvbox.osc.R.drawable.icon_img_placeholder);
        if (icon != null) {
            float left = (width - icon.getWidth()) / 2f;
            float top = (height - icon.getHeight()) / 2f;
            canvas.drawBitmap(icon, left, top, null);
        }

        Drawable drawable = new BitmapDrawable(App.getInstance().getResources(), bitmap);
        drawableCache.put(key, drawable);
        return drawable;
    }

    public static void clearCache() {
        drawableCache.evictAll();
        modelCache.evictAll();
        optionsCache.clear();
    }

    public static void clearMemoryCache() {
        clearCache();
        try {
            Glide.get(App.getInstance()).clearMemory();
            LOG.i("echo-img-clear-memory-cache");
        } catch (Throwable th) {
            LOG.i("echo-img-clear-memory-cache-error:" + th.getMessage());
        }
    }

    /**
     * 把带 {@code @Headers=} / {@code @Cookie=} / {@code @Referer=} / {@code @User-Agent=} 后缀的
     * 图片地址解析成 Glide 能用的 model。解析结果是不可变的，直接按 URL 缓存。
     */
    private static Object getUrl(String url) {
        if (TextUtils.isEmpty(url)) return null;
        Object cached = modelCache.get(url);
        if (cached != null) return cached;
        Object model = buildModel(url);
        if (model != null) {
            modelCache.put(url, model);
        }
        return model;
    }

    private static Object buildModel(String url) {
        if (url.startsWith("data:")) return url;
        String header = null;
        String referer = null;
        String ua = null;
        String cookie = null;
        if (url.contains("@Headers=")) {
            header = url.split("@Headers=")[1].split("@")[0];
            try {
                header = URLDecoder.decode(header, "UTF-8");
            } catch (UnsupportedEncodingException ignored) {
            }
        }
        if (url.contains("@Cookie=")) cookie = url.split("@Cookie=")[1].split("@")[0];
        if (url.contains("@User-Agent=")) ua = url.split("@User-Agent=")[1].split("@")[0];
        if (url.contains("@Referer=")) referer = url.split("@Referer=")[1].split("@")[0];
        url = url.split("@")[0];
        if (TextUtils.isEmpty(url)) return null;

        LazyHeaders.Builder builder = new LazyHeaders.Builder();
        Map<String, String> headers = new HashMap<>();
        if (!TextUtils.isEmpty(header)) {
            try {
                JsonObject jsonInfo = new Gson().fromJson(header, JsonObject.class);
                for (String key : jsonInfo.keySet()) {
                    putHeader(headers, key, jsonInfo.get(key).getAsString());
                }
            } catch (Throwable ignored) {
            }
        }
        putHeader(headers, "Cookie", cookie);
        if (!TextUtils.isEmpty(ua)) putHeader(headers, "User-Agent", ua);
        if (!TextUtils.isEmpty(referer)) putHeader(headers, "Referer", referer);
        for (Map.Entry<String, String> entry : headers.entrySet()) builder.setHeader(entry.getKey(), entry.getValue());
        return new GlideUrl(url, builder.build());
    }

    public static Object getImageModel(String url) {
        return getUrl(url);
    }

    private static boolean isInvalidImageUrl(String url) {
        if (TextUtils.isEmpty(url)) return true;
        url = url.trim();
        if (TextUtils.isEmpty(url)) return true;
        return hasEmptyProxyParam(url, "img");
    }

    private static boolean hasEmptyProxyParam(String url, String key) {
        if (!url.startsWith("proxy://") && !url.contains("/proxy?")) return false;
        int queryIndex = url.indexOf('?');
        String query = queryIndex >= 0 ? url.substring(queryIndex + 1) : url.substring("proxy://".length());
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int eqIndex = pair.indexOf('=');
            if (eqIndex < 0) continue;
            if (key.equals(pair.substring(0, eqIndex)) && TextUtils.isEmpty(pair.substring(eqIndex + 1))) {
                return true;
            }
        }
        return false;
    }

    private static void putHeader(Map<String, String> headers, String key, String value) {
        if (TextUtils.isEmpty(key) || TextUtils.isEmpty(value)) return;
        headers.put(key, value.trim());
    }

    private static RequestListener<Bitmap> getListener(final ImageView view, final ImageView.ScaleType scaleType,
                                                      final String label, final int width, final int height,
                                                      final float radius) {
        return new RequestListener<Bitmap>() {
            @Override
            public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Bitmap> target, boolean isFirstResource) {
                view.setScaleType(scaleType);
                // 失败时才画文字占位图
                view.setImageDrawable(createTextDrawable(TextUtils.isEmpty(label) ? "TVBox" : label, width, height, radius));
                return true;
            }

            @Override
            public boolean onResourceReady(Bitmap resource, Object model, Target<Bitmap> target, DataSource dataSource, boolean isFirstResource) {
                view.setScaleType(scaleType);
                return false;
            }
        };
    }
}
