package com.callx.app.community.canvas;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.ListPreloader;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.util.FixedPreloadSizeProvider;

import com.callx.app.cache.CommunityAvatarBinder;
import com.callx.app.utils.AvatarSizeTier;

import java.util.Collections;
import java.util.List;

/**
 * Glide RecyclerViewPreloader factory for community list screens.
 *
 * How it works:
 *   As the user scrolls, the RecyclerViewPreloader asks the data provider for
 *   the photo URL MAX_PRELOAD items ahead of the last visible position and
 *   starts Glide loads for those URLs at the exact pixel size they will appear.
 *   By the time those items scroll into view, Glide's memory cache is warm
 *   and the avatar renders immediately — eliminating the "grey circle pops
 *   in after 200 ms" artifact that XML-inflate adapters always suffered from.
 *
 * Two factory methods:
 *   {@link #attachAvatar}  — 44 dp circles (Members, JoinRequests, MemberSearch)
 *   {@link #attachCover}   — full-width cover images (Events)
 *
 * Usage (in Fragment.onViewCreated or Activity.onCreate, after setting adapter):
 *   CommunityAvatarPreloader.attachAvatar(this, rvMembers, pos -> adapter.photoAt(pos),
 *       () -> adapter.getItemCount(), 44);
 *
 * FIX (avatar-pipeline parity): {@link #attachAvatar} used to preload the
 * raw photo URL at a hand-rolled dp*density size straight into Glide's OWN
 * memory/disk cache — a completely different cache key from the
 * tier-bucketed, CDN-responsive URL {@link CommunityAvatarBinder} actually
 * binds with (see that class), so this preloader's fling-ahead work never
 * warmed anything the real bind() call could hit; the row still paid a
 * fresh decode when it scrolled into view. Now builds the exact same
 * {@link CommunityAvatarBinder#url} for the caller's size (bucketed via
 * {@link AvatarSizeTier#forViewSizeDp}) and, on a successful preload
 * decode, write-throughs it into {@code ChatAvatarL2Cache}/L3 via
 * {@link CommunityAvatarBinder#warmCache} — so binding the row when it
 * scrolls into view is a real L2 hit, not just a second Glide fetch.
 * {@link #attachCover} is untouched: cover images aren't square avatars,
 * don't go through the avatar tier system, and have no matching bind()
 * call site to share a cache key with.
 */
public final class CommunityAvatarPreloader {

    /** Items prefetched ahead of the last visible position. */
    private static final int MAX_PRELOAD = 6;

    private CommunityAvatarPreloader() {}

    // ── Public contract ───────────────────────────────────────────────────────

    /**
     * Callback the caller implements to expose photo URLs from the adapter.
     * Both methods are called on the main thread.
     */
    public interface UrlProvider {
        /** @return photo URL for adapter position, or null/empty if none. */
        @Nullable String urlAt(int position);
        /** @return adapter.getItemCount() — used to clamp the preload window. */
        int count();
    }

    /**
     * Attach an avatar preloader (circle-crop, 44 dp) to {@code rv}.
     * The returned preloader is already added as an OnScrollListener; callers
     * typically discard the return value unless they need to remove it later.
     */
    public static RecyclerViewPreloader<String> attachAvatar(
            @NonNull Fragment fragment,
            @NonNull RecyclerView rv,
            @NonNull UrlProvider provider,
            int sizeDp) {
        return attach(fragment.requireContext(), rv, provider, sizeDp, true);
    }

    /** Activity-based variant of {@link #attachAvatar}. */
    public static RecyclerViewPreloader<String> attachAvatar(
            @NonNull Context ctx,
            @NonNull RecyclerView rv,
            @NonNull UrlProvider provider,
            int sizeDp) {
        return attach(ctx, rv, provider, sizeDp, true);
    }

    /**
     * Attach a cover-image preloader (center-crop, full-width × 160 dp) for Events.
     * {@code coverWidthPx} is the RecyclerView width in pixels (from rv.getWidth()
     * or DisplayMetrics; call after the view is laid out or use a post()).
     */
    public static RecyclerViewPreloader<String> attachCover(
            @NonNull Fragment fragment,
            @NonNull RecyclerView rv,
            @NonNull UrlProvider provider,
            int coverWidthPx,
            int coverHeightDp) {
        Context ctx = fragment.requireContext();
        int hPx = Math.round(coverHeightDp * ctx.getResources().getDisplayMetrics().density);
        return attach(ctx, rv, provider, new int[]{coverWidthPx, hPx}, false);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Avatar path — tier-bucketed to share a cache key/entry with CommunityAvatarBinder's real bind(). */
    private static RecyclerViewPreloader<String> attach(
            Context ctx, RecyclerView rv, UrlProvider provider, int sizeDp, boolean circle) {
        if (!circle) {
            int sizePx = Math.round(sizeDp * ctx.getResources().getDisplayMetrics().density);
            return attachCoverInternal(ctx, rv, provider, new int[]{sizePx, sizePx});
        }

        AvatarSizeTier tier = AvatarSizeTier.forViewSizeDp(sizeDp);
        int sizePx = com.callx.app.utils.AvatarUrlBuilder.tierPx(ctx, tier);
        FixedPreloadSizeProvider<String> sizeProvider = new FixedPreloadSizeProvider<>(sizePx, sizePx);

        RequestOptions opts = RequestOptions.circleCropTransform()
                .override(sizePx, sizePx)
                .format(CommunityAvatarBinder.AVATAR_FORMAT)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE);

        ListPreloader.PreloadModelProvider<String> modelProvider =
                new ListPreloader.PreloadModelProvider<String>() {
                    @NonNull @Override
                    public List<String> getPreloadItems(int position) {
                        if (position < 0 || position >= provider.count()) return Collections.emptyList();
                        String rawUrl = provider.urlAt(position);
                        if (rawUrl == null || rawUrl.isEmpty()) return Collections.emptyList();
                        return Collections.singletonList(rawUrl);
                    }
                    @Nullable @Override
                    public RequestBuilder<Bitmap> getPreloadRequestBuilder(@NonNull String rawUrl) {
                        String url = CommunityAvatarBinder.url(ctx, rawUrl, tier);
                        if (url == null) return null;
                        return Glide.with(ctx).asBitmap().load(url).apply(opts)
                                .listener(new com.bumptech.glide.request.RequestListener<Bitmap>() {
                                    @Override
                                    public boolean onLoadFailed(com.bumptech.glide.load.engine.GlideException e, Object model,
                                                                 com.bumptech.glide.request.target.Target<Bitmap> target, boolean isFirstResource) {
                                        return false;
                                    }
                                    @Override
                                    public boolean onResourceReady(Bitmap resource, Object model,
                                                                    com.bumptech.glide.request.target.Target<Bitmap> target,
                                                                    com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                                        // write-through so the real bind() below hits L2 instead of decoding again
                                        CommunityAvatarBinder.warmCache(ctx, rawUrl, tier, resource);
                                        return false;
                                    }
                                });
                    }
                };

        RecyclerViewPreloader<String> preloader = new RecyclerViewPreloader<>(
                Glide.with(ctx), modelProvider, sizeProvider, MAX_PRELOAD);
        rv.addOnScrollListener(preloader);
        return preloader;
    }

    /** Cover-image path (Events) — untouched flat Glide preload, no avatar tier/cache involved. */
    private static RecyclerViewPreloader<String> attach(
            Context ctx, RecyclerView rv, UrlProvider provider,
            int[] sizePx, boolean circle) {
        return attachCoverInternal(ctx, rv, provider, sizePx);
    }

    private static RecyclerViewPreloader<String> attachCoverInternal(
            Context ctx, RecyclerView rv, UrlProvider provider, int[] sizePx) {

        FixedPreloadSizeProvider<String> sizeProvider = new FixedPreloadSizeProvider<>(sizePx[0], sizePx[1]);

        RequestOptions opts = RequestOptions.centerCropTransform()
                .override(sizePx[0], sizePx[1])
                .format(DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.ALL);

        ListPreloader.PreloadModelProvider<String> modelProvider =
                new ListPreloader.PreloadModelProvider<String>() {
                    @NonNull @Override
                    public List<String> getPreloadItems(int position) {
                        if (position < 0 || position >= provider.count()) return Collections.emptyList();
                        String url = provider.urlAt(position);
                        if (url == null || url.isEmpty()) return Collections.emptyList();
                        return Collections.singletonList(url);
                    }
                    @Nullable @Override
                    public RequestBuilder<Bitmap> getPreloadRequestBuilder(@NonNull String url) {
                        return Glide.with(ctx).asBitmap().load(url).apply(opts);
                    }
                };

        RecyclerViewPreloader<String> preloader = new RecyclerViewPreloader<>(
                Glide.with(ctx), modelProvider, sizeProvider, MAX_PRELOAD);
        rv.addOnScrollListener(preloader);
        return preloader;
    }
}
