package com.callx.app.cache;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import com.callx.app.utils.AvatarNetworkQuality;
import com.callx.app.utils.AvatarSizeTier;
import com.callx.app.utils.AvatarUrlBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * AvatarBatchPrefetcher — batch prefetch API: fetch several already-uploaded
 * avatars in ONE HTTP round-trip instead of N.
 *
 * Every per-scroll prefetch path (AvatarBinderCore#prefetch, AvatarPrefetcher)
 * still issues one Glide request PER avatar — Glide's connection pooling
 * lets those share a warm HTTP/2 connection to res.cloudinary.com, and for
 * a few-row scroll lookahead that's fine. But warming a whole batch up
 * front (a cold-start following/DM pre-warm, opening a group-members sheet)
 * still pays N separate request/response round-trips for images that are
 * each only a few KB, where the round-trip itself (not the bytes) is the
 * dominant cost — that's the case this class targets, and AvatarPreWarmWorker
 * is its real consumer: both its following/closeFriends and DM warm passes
 * go through prefetchBatch() instead of a per-uid submit() loop.
 *
 * This is NOT Cloudinary's Sprite API (`/image/sprite/<tag>.png`) — that
 * needs every asset pre-tagged at UPLOAD time, which doesn't fit an
 * arbitrary avatar set chosen at runtime (a session's follow list, a DM
 * partner list). Instead this uses Cloudinary's layer-overlay transform
 * chain — a real, documented capability that composes any already-uploaded
 * public_ids on the fly, no pre-tagging needed: the first avatar in the
 * batch is resized to one "cell" and used as the base canvas (padded
 * rightward, left-aligned, to the full strip width), then every OTHER
 * avatar is chained on as an `l_<public_id>` overlay layer at its own
 * x-offset. ONE Glide request downloads the resulting strip image; the
 * response bitmap is then sliced client-side (Bitmap#createBitmap region
 * copy — no extra network) into N separate avatar bitmaps and written into
 * L2/L3 under each avatar's own normal
 * {@link AvatarUrlBuilder#buildResponsive} URL, so a LATER real bind() of
 * any of them is an ordinary L2 hit — indistinguishable from one warmed the
 * old one-request-at-a-time way.
 *
 * Best-effort by design: composing several independently-uploaded images
 * into one URL has more ways to fail than a plain single-avatar request
 * (a non-Cloudinary photo URL in the set, an unusual public_id shape). ANY
 * failure — a malformed URL, a network error, a timeout — falls back to
 * firing plain individual preload() calls for that batch instead. A broken
 * composite request must never mean those avatars simply never warm.
 */
public final class AvatarBatchPrefetcher {

    private static final String TAG = "AvatarBatchPrefetch";
    private AvatarBatchPrefetcher() {}

    /** Bounded so the composite URL length and the decoded strip's memory
     *  footprint both stay trivial — 6 cells at a small avatar tier is a
     *  few hundred px wide either way. */
    public static final int MAX_BATCH_SIZE = 6;

    private static final long SUBMIT_TIMEOUT_SEC = 12L;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    /** One avatar to warm — the same (photo, avatarVersion) pair every
     *  other prefetch path in this app already carries. */
    public static final class Item {
        public final String photo;
        public final long avatarVersion;
        public Item(String photo, long avatarVersion) {
            this.photo = photo;
            this.avatarVersion = avatarVersion;
        }
    }

    /**
     * Fire-and-forget: warms L2+L3 (via {@code cache}) for every item in
     * {@code items} at the given tier, chunked {@link #MAX_BATCH_SIZE} at a
     * time internally. Safe to call with any list size, including 0 or 1
     * (a batch of 1 has nothing to compose, so it's just a plain fetch).
     * Intended for prewarm-style bulk warming (cold-start following/DM
     * lists, opening a members sheet) — NOT a per-frame scroll prefetch
     * path, since composing a URL has more up-front overhead than a plain
     * preload() call for small batches.
     */
    public static void prefetchBatch(Context context, List<Item> items, AvatarSizeTier tier,
                                      AvatarBinderCore.CacheProvider cache) {
        if (context == null || items == null || items.isEmpty() || cache == null) return;
        Context appCtx = context.getApplicationContext();

        List<Item> chunk = new ArrayList<>(MAX_BATCH_SIZE);
        for (Item item : items) {
            if (item == null || item.photo == null || item.photo.isEmpty()) continue;
            chunk.add(item);
            if (chunk.size() == MAX_BATCH_SIZE) {
                fetchChunk(appCtx, new ArrayList<>(chunk), tier, cache);
                chunk.clear();
            }
        }
        if (!chunk.isEmpty()) fetchChunk(appCtx, chunk, tier, cache);
    }

    private static void fetchChunk(Context appCtx, List<Item> chunk, AvatarSizeTier tier,
                                    AvatarBinderCore.CacheProvider cache) {
        if (chunk.size() == 1) {
            fallbackIndividual(appCtx, chunk, tier, cache); // nothing to compose
            return;
        }

        String compositeUrl = buildCompositeUrl(appCtx, chunk, tier);
        if (compositeUrl == null) {
            fallbackIndividual(appCtx, chunk, tier, cache); // e.g. a non-Cloudinary URL in the set
            return;
        }

        int cellPx = AvatarUrlBuilder.tierPx(appCtx, tier);
        IO.execute(() -> {
            try {
                Bitmap composite = Glide.with(appCtx)
                        .asBitmap()
                        .load(compositeUrl)
                        .diskCacheStrategy(DiskCacheStrategy.NONE) // the strip itself is throwaway — only the sliced cells get cached
                        .submit()
                        .get(SUBMIT_TIMEOUT_SEC, TimeUnit.SECONDS);
                if (composite == null) throw new IllegalStateException("null composite bitmap");
                sliceAndCache(appCtx, composite, chunk, tier, cellPx, cache);
                if (!composite.isRecycled()) composite.recycle();
            } catch (Exception e) {
                Log.w(TAG, "composite batch fetch failed (" + chunk.size()
                        + " avatars), falling back individually: " + e.getMessage());
                fallbackIndividual(appCtx, chunk, tier, cache);
            }
        });
    }

    /** Slices the composite strip left-to-right into each item's own cell
     *  and writes it into L2+L3 under that item's NORMAL buildResponsive
     *  URL — the exact key a real bind() will look up later. */
    private static void sliceAndCache(Context appCtx, Bitmap composite, List<Item> chunk,
                                       AvatarSizeTier tier, int cellPx,
                                       AvatarBinderCore.CacheProvider cache) {
        int available = cellPx > 0 ? Math.min(chunk.size(), composite.getWidth() / cellPx) : 0;
        for (int i = 0; i < available; i++) {
            try {
                int x = i * cellPx;
                if (x + cellPx > composite.getWidth() || cellPx > composite.getHeight()) continue;
                Bitmap cell = Bitmap.createBitmap(composite, x, 0, cellPx, cellPx);
                Item item = chunk.get(i);
                String url = AvatarUrlBuilder.buildResponsive(appCtx, item.photo, tier, item.avatarVersion);
                if (url == null) continue;
                cache.l2(appCtx).put(url, cell);
                cache.l3(appCtx).put(url, cell);
                AvatarCacheAnalytics.getInstance(appCtx).record(AvatarCacheAnalytics.Tier.PREWARM);
            } catch (Exception e) {
                Log.w(TAG, "slice failed at index " + i + ": " + e.getMessage());
            }
        }
    }

    /** Same courtesy LOW-priority preload() every other prefetch path in
     *  this app uses — the fallback when composing isn't possible. */
    private static void fallbackIndividual(Context appCtx, List<Item> chunk, AvatarSizeTier tier,
                                            AvatarBinderCore.CacheProvider cache) {
        int px = AvatarUrlBuilder.tierPx(appCtx, tier);
        for (Item item : chunk) {
            String url = AvatarUrlBuilder.buildResponsive(appCtx, item.photo, tier, item.avatarVersion);
            if (url == null) continue;
            if (cache.l2(appCtx).get(url) != null) continue;
            try {
                Bitmap bmp = Glide.with(appCtx).asBitmap().load(url).override(px, px)
                        .submit().get(SUBMIT_TIMEOUT_SEC, TimeUnit.SECONDS);
                if (bmp != null) {
                    cache.l2(appCtx).put(url, bmp);
                    cache.l3(appCtx).put(url, bmp);
                    AvatarCacheAnalytics.getInstance(appCtx).record(AvatarCacheAnalytics.Tier.PREWARM);
                }
            } catch (Exception e) {
                Log.w(TAG, "fallback individual fetch failed: " + e.getMessage());
            }
        }
    }

    /**
     * Builds the composite layer-overlay URL: chunk.get(0) is the base
     * (resized to one cell, canvas padded rightward to the full strip
     * width, left-aligned), every other item is chained as an
     * l_<public_id> overlay at its own x-offset. Returns null if any item
     * isn't a Cloudinary "/upload/" delivery URL — nothing to build a
     * public_id from, caller falls back to individual fetches.
     */
    private static String buildCompositeUrl(Context ctx, List<Item> chunk, AvatarSizeTier tier) {
        String baseDelivery = chunk.get(0).photo;
        String basePid = publicId(baseDelivery);
        String host = deliveryHost(baseDelivery);
        if (basePid == null || host == null) return null;

        int cellPx = AvatarUrlBuilder.tierPx(ctx, tier);
        int totalPx = cellPx * chunk.size();
        String qAuto = AvatarNetworkQuality.qAutoParam(AvatarNetworkQuality.current(ctx));
        String fmt = android.os.Build.VERSION.SDK_INT >= 31 ? "f_avif" : "f_webp";

        StringBuilder sb = new StringBuilder(host).append("/");
        // Base: crop to one cell, then pad the canvas out to the full strip
        // width with the base image pinned LEFT (g_west) — cell 0's pixels
        // land exactly at x=[0, cellPx), matching sliceAndCache's math.
        sb.append("c_fill,w_").append(cellPx).append(",h_").append(cellPx).append(",g_face/")
          .append("c_pad,w_").append(totalPx).append(",h_").append(cellPx)
          .append(",g_west,b_black,").append(qAuto).append(",").append(fmt).append("/");

        for (int i = 1; i < chunk.size(); i++) {
            String pid = publicId(chunk.get(i).photo);
            if (pid == null) return null; // any non-Cloudinary item in the batch — bail, caller falls back individually
            String layerPid = pid.replace('/', ':'); // Cloudinary layer-param convention for folder paths
            int x = i * cellPx;
            sb.append("l_").append(layerPid)
              .append(",w_").append(cellPx).append(",h_").append(cellPx)
              .append(",c_fill,g_face/fl_layer_apply,g_west,x_").append(x).append(",y_0/");
        }
        sb.append(basePid);
        return sb.toString();
    }

    /** "https://res.cloudinary.com/<cloud>/image/upload" — everything up to
     *  and including "upload", so fresh composite transforms can be
     *  appended instead of reusing any transform the raw baseUrl had. */
    private static String deliveryHost(String deliveryUrl) {
        if (deliveryUrl == null) return null;
        String marker = "/upload/";
        int idx = deliveryUrl.indexOf(marker);
        if (idx < 0) return null;
        return deliveryUrl.substring(0, idx + marker.length() - 1); // keep "upload", drop the trailing slash (re-added above)
    }

    /** Folder/name public_id extraction (no extension, no Cloudinary auto
     *  version segment) — same shape as CloudinaryUploader's private
     *  publicIdFromUrl, duplicated rather than shared since that one is an
     *  upload-path internal and this is a read-only delivery-URL concern. */
    private static String publicId(String deliveryUrl) {
        if (deliveryUrl == null || deliveryUrl.isEmpty()) return null;
        String marker = "/upload/";
        int idx = deliveryUrl.indexOf(marker);
        if (idx < 0) return null;
        String rest = deliveryUrl.substring(idx + marker.length());
        if (rest.matches("^v\\d+/.*")) {
            rest = rest.substring(rest.indexOf('/') + 1);
        }
        int dot = rest.lastIndexOf('.');
        return dot > 0 ? rest.substring(0, dot) : rest;
    }
}
