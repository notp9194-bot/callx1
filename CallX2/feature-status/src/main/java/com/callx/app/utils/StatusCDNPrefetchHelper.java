package com.callx.app.utils;

import android.content.Context;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.callx.app.models.StatusItem;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * StatusCDNPrefetchHelper v26 — Prefetch top-N contact status URLs on app open.
 * Images via Glide, videos via StatusVideoCacheManager.
 */
public final class StatusCDNPrefetchHelper {
    private static final int MAX_PREFETCH = 10;
    private StatusCDNPrefetchHelper() {}

    public static void prefetchAll(Context ctx, List<StatusItem> priorityItems) {
        if (ctx == null || priorityItems == null || priorityItems.isEmpty()) return;
        int count = Math.min(priorityItems.size(), MAX_PREFETCH);
        for (int i = 0; i < count; i++) {
            StatusItem item = priorityItems.get(i);
            if (item == null) continue;
            if ("image".equals(item.type) || "gif".equals(item.type)) {
                String url = item.mediaUrl != null ? item.mediaUrl : item.linkImageUrl;
                if (url != null && !url.isEmpty()) {
                    Glide.with(ctx).downloadOnly().diskCacheStrategy(DiskCacheStrategy.ALL).load(url).preload();
                }
            } else if ("video".equals(item.type) && item.mediaUrl != null) {
                prefetchVideo(item.mediaUrl);
            }
            // Prefetch thumbnail too
            if (item.thumbnailUrl != null && !item.thumbnailUrl.isEmpty()) {
                Glide.with(ctx).downloadOnly().diskCacheStrategy(DiskCacheStrategy.DATA).load(item.thumbnailUrl).preload();
            }
        }
    }

    private static void prefetchVideo(String url) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                if (StatusVideoCacheManager.isInitialized()) {
                    // Write first 3MB to cache
                    android.net.Uri uri = android.net.Uri.parse(url);
                    androidx.media3.datasource.cache.CacheDataSource.Factory factory =
                            StatusVideoCacheManager.getCacheDataSourceFactory();
                    androidx.media3.common.MediaItem mediaItem = androidx.media3.common.MediaItem.fromUri(uri);
                    androidx.media3.exoplayer.source.ProgressiveMediaSource source =
                            new androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(factory)
                                    .createMediaSource(mediaItem);
                    // 3MB limit
                    androidx.media3.datasource.cache.CacheWriter writer =
                            new androidx.media3.datasource.cache.CacheWriter(
                                    factory.createDataSource(), new androidx.media3.datasource.DataSpec(uri),
                                    new byte[8192], null);
                    writer.cache();
                }
            } catch (Exception ignored) {}
        });
    }
}
