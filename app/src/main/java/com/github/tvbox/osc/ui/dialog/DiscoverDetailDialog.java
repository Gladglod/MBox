package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.TmdbApi;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.GlassTip;
import com.github.tvbox.osc.util.ImgUtil;

import java.util.Locale;

/**
 * 发现页影片详情浮层。
 *
 * <p>TMDB 只提供资料，不提供播放地址。所以这里的「搜索片源」会把片名带回
 * 首页搜索（{@code FastSearchActivity} / {@code SearchActivity}），
 * 用已配置的数据源去找可播放的线路。</p>
 */
public class DiscoverDetailDialog extends BaseDialog {

    public interface OnSearchClickListener {
        void onSearchClick(TmdbApi.Item item);
    }

    private final TmdbApi.Item item;
    private final OnSearchClickListener searchListener;

    private TextView tvMeta;
    private TextView tvRating;
    private TextView tvGenres;
    private TextView tvRuntime;
    private TextView tvDirector;
    private TextView tvStatus;
    private TextView tvOverview;
    private TextView tvCastLabel;
    private TextView tvCast;

    public DiscoverDetailDialog(@NonNull Context context, TmdbApi.Item item,
                                OnSearchClickListener searchListener) {
        super(context, R.style.CustomDialogStyleDim);
        this.item = item;
        this.searchListener = searchListener;
        setContentView(R.layout.dialog_discover_detail);
        setCanceledOnTouchOutside(true);
        setupWindow();
        bindViews();
        bindInitialData();
        loadDetail();
    }

    /** 面板宽 90% 屏宽、高 82% 屏高：再长也不至于顶到状态栏，内容区自己滚动 */
    private void setupWindow() {
        Window window = getWindow();
        if (window == null) return;
        WindowManager.LayoutParams lp = window.getAttributes();
        DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
        if (dm != null) {
            lp.width = (int) (dm.widthPixels * 0.9f);
            lp.height = (int) (dm.heightPixels * 0.82f);
        }
        lp.gravity = Gravity.CENTER;
        window.setAttributes(lp);
    }

    private void bindViews() {
        tvMeta = findViewById(R.id.tvMeta);
        tvRating = findViewById(R.id.tvRating);
        tvGenres = findViewById(R.id.tvGenres);
        tvRuntime = findViewById(R.id.tvRuntime);
        tvDirector = findViewById(R.id.tvDirector);
        tvStatus = findViewById(R.id.tvStatus);
        tvOverview = findViewById(R.id.tvOverview);
        tvCastLabel = findViewById(R.id.tvCastLabel);
        tvCast = findViewById(R.id.tvCast);

        ((TextView) findViewById(R.id.tvTitle)).setText(item == null ? "" : item.displayTitle());

        ImageView ivPoster = findViewById(R.id.ivPoster);
        if (item != null && ivPoster != null) {
            ImgUtil.load(item.posterUrl(TmdbApi.POSTER_SIZE_BIG), ivPoster, 0, 0, 0,
                    item.displayTitle());
        }

        View btnSearch = findViewById(R.id.btnSearch);
        if (btnSearch != null) {
            btnSearch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FastClickCheckUtil.check(v);
                    dismiss();
                    if (searchListener != null) searchListener.onSearchClick(item);
                }
            });
        }

        View btnTmdb = findViewById(R.id.btnTmdb);
        if (btnTmdb != null) {
            btnTmdb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FastClickCheckUtil.check(v);
                    openTmdbPage();
                }
            });
        }

        View btnClose = findViewById(R.id.btnClose);
        if (btnClose != null) {
            btnClose.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                }
            });
        }
    }

    private void bindInitialData() {
        if (item == null) return;

        tvMeta.setText(buildMeta(item));
        if (item.rating > 0) {
            tvRating.setText(String.format(Locale.US, "★ %.1f", item.rating));
        } else {
            tvRating.setVisibility(View.GONE);
        }
        tvOverview.setText(TextUtils.isEmpty(item.overview)
                ? getContext().getString(R.string.discover_detail_no_overview)
                : item.overview);
    }

    private void loadDetail() {
        if (item == null || item.id <= 0) return;
        TmdbApi.fetchDetail(item.mediaType, item.id, new TmdbApi.DetailCallback() {
            @Override
            public void onSuccess(TmdbApi.Detail detail) {
                if (detail == null || !isShowing()) return;
                bindDetail(detail);
            }

            @Override
            public void onError(String message) {
                // 详情失败不打扰用户：列表里已有的标题 / 简介 / 海报足以做决策
            }
        });
    }

    private void bindDetail(TmdbApi.Detail detail) {
        if (detail.item != null) {
            if (!TextUtils.isEmpty(detail.item.overview)) {
                tvOverview.setText(detail.item.overview);
            }
            if (detail.item.rating > 0) {
                tvRating.setVisibility(View.VISIBLE);
                tvRating.setText(String.format(Locale.US, "★ %.1f", detail.item.rating));
            }
        }

        if (!detail.genres.isEmpty()) {
            tvGenres.setText(TextUtils.join(" / ", detail.genres));
        }
        if (!TextUtils.isEmpty(detail.runtimeText)) {
            tvRuntime.setText(detail.runtimeText);
        }
        if (!TextUtils.isEmpty(detail.director)) {
            String label = item != null && item.isTv()
                    ? getContext().getString(R.string.discover_detail_director_tv)
                    : getContext().getString(R.string.discover_detail_director_movie);
            tvDirector.setText(label + "：" + detail.director);
        }
        if (!TextUtils.isEmpty(detail.status)) {
            tvStatus.setText(detail.status);
        }
        if (!detail.cast.isEmpty()) {
            tvCastLabel.setVisibility(View.VISIBLE);
            tvCast.setVisibility(View.VISIBLE);
            tvCast.setText(TextUtils.join("、", detail.cast));
        }
    }

    private String buildMeta(TmdbApi.Item data) {
        StringBuilder builder = new StringBuilder();
        String year = data.year();
        if (!TextUtils.isEmpty(year)) builder.append(year);
        if (builder.length() > 0) builder.append(" · ");
        builder.append(data.mediaLabel());
        if (data.voteCount > 0) {
            builder.append(" · ");
            builder.append(getContext().getString(R.string.discover_detail_vote, data.voteCount));
        }
        return builder.toString();
    }

    private void openTmdbPage() {
        String url = TmdbApi.webUrl(item);
        if (TextUtils.isEmpty(url)) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        } catch (Throwable th) {
            GlassTip.show(getContext(), getContext().getString(R.string.discover_detail_no_browser));
        }
    }
}
