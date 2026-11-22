package com.callx.app.cache;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;

import com.callx.app.utils.AvatarSizeTier;
import com.callx.app.utils.AvatarUrlBuilder;

/**
 * CommunityAvatarBinder — same deep pipeline every other avatar screen has
 * (shared {@link AvatarSizeTier} bucketing + responsive CDN URL + L2/L3
 * write-through, all through {@link ChatAvatarL2Cache} since this is the
 * same feature-chat module), now covering the Community module's OWN
 * icons: the community icon itself (CommunityActivity toolbar + join-gate
 * header), a post author's avatar (CommunityPostAdapter), and a member row
 * avatar (CommunityMemberAdapter) — CommunityGroupAdapter's linked-GROUP
 * icon stays on {@link GroupAvatarBinder} since that's a group's own icon,
 * just surfaced inside a community list.
 *
 * Before this every one of those was a flat {@code Glide.load(url)} — the
 * community icon/gate icon had no override() at all (full-resolution
 * decode), and the post/member avatars used a hand-rolled dp*density
 * override with no tier bucketing, so the exact same user's photo used as
 * both a post author and a member-list row decoded and cached TWICE.
 *
 * Two shapes, matching how the two call sites already work:
 *  • bindIcon()   — plain ImageView target (community/gate icon). Delegates
 *                   to {@link AvatarBinderCore#bind} — same shared
 *                   L2-check -> Glide-decode -> L2/L3-write-through pipeline
 *                   {@link ChatAvatarBinder}/{@link GroupAvatarBinder} use,
 *                   instead of a third hand-rolled copy of it.
 *  • bindBitmap() — CustomTarget<Bitmap> target (post/member rows are
 *                   Canvas views, same as CommunityMemberAvatarStackView —
 *                   they need a raw Bitmap, not a Drawable/ImageView).
 *                   AvatarBinderCore#bind is ImageView-only, so this stays
 *                   its own implementation — same reasoning as
 *                   ChatAvatarBinder#bindBitmap — but still shares its
 *                   L2/L3 cache entries with bindIcon() for the same
 *                   photo/tier via the identical {@link #url} + CACHE.
 * FIX (avatar delta-sync gap): a post author's / member's photo IS a real
 * user avatar with a real avatarVersion — unlike the community/group ICON
 * itself, which stays genuinely versionless (a new icon upload already
 * produces a brand-new Cloudinary URL). url()/bindIcon()/bindBitmap() each
 * now have a 4/5/5-arg overload that takes that avatarVersion so post/
 * member/comment/reply/reaction avatars cache-bust and pick up
 * AvatarVersionSyncManager updates the same way chat's avatars do; the
 * community/group icon call sites keep using the shorter, version-less
 * overloads unchanged.
 */
public final class CommunityAvatarBinder {

    private CommunityAvatarBinder() {}

    /** This module's own L2/L3 cache pair, handed to AvatarBinderCore per
     *  bindIcon() call — same {@link ChatAvatarL2Cache} instance bindBitmap()
     *  below and ChatAvatarBinder/GroupAvatarBinder already use. */
    private static final com.callx.app.cache.AvatarBinderCore.CacheProvider CACHE =
            new com.callx.app.cache.AvatarBinderCore.CacheProvider() {
                @Override public AvatarL2MemoryCache l2(Context ctx) { return ChatAvatarL2Cache.get(ctx); }
                @Override public AvatarL3DiskCache l3(Context ctx) { return ChatAvatarL2Cache.l3(ctx); }
                @Override public boolean l3Readable() { return false; } // v821: chat's L3 is never read
            };

    /** CommunityActivity toolbar icon (iv_community_icon, 36dp). */
    public static final AvatarSizeTier TIER_TOOLBAR = AvatarSizeTier.forViewSizeDp(36);
    /** CommunityActivity join-gate header icon (iv_gate_icon, 88dp). */
    public static final AvatarSizeTier TIER_GATE = AvatarSizeTier.forViewSizeDp(88);
    /** CommunityPostAdapter author avatar (CommunityPostCanvasView.avatarPx = 40dp). */
    public static final AvatarSizeTier TIER_POST_AUTHOR = AvatarSizeTier.forViewSizeDp(40);
    /** CommunityMemberAdapter row avatar (44dp). */
    public static final AvatarSizeTier TIER_MEMBER = AvatarSizeTier.forViewSizeDp(44);
    /** CommunityMemberAvatarStackView overlapping stack avatar (26dp). */
    public static final AvatarSizeTier TIER_STACK = AvatarSizeTier.forViewSizeDp(26);

    public static final DecodeFormat AVATAR_FORMAT =
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                    ? DecodeFormat.PREFER_ARGB_8888
                    : DecodeFormat.PREFER_RGB_565;

    /** Server-side responsive, tier-bucketed URL. Communities/group icons don't
     *  carry an avatarVersion counter (see GroupAvatarBinder doc) — use this
     *  overload for those. Post authors/members ARE real user photos with a
     *  real avatarVersion on the User model — see the 4-arg overload below. */
    public static String url(Context ctx, String rawUrl, AvatarSizeTier tier) {
        return url(ctx, rawUrl, tier, 0L);
    }

    /**
     * FIX (avatar delta-sync gap): a post author's / member's photo IS a real
     * user avatar with a real avatarVersion (same field ChatAvatarBinder/
     * GroupMemberAdapter key their cache-busting on) — this class's original
     * doc lumped it in with the community/group ICON's genuine versionlessness
     * (a new icon upload already produces a brand-new Cloudinary URL, so no
     * version param was ever needed there). Author/member avatars never got
     * that same cache-bust: {@link AvatarUrlBuilder#appendVersion} needs a
     * real version to have anything to bump. Callers that have (or can
     * resolve via {@link AvatarVersionSyncManager#getCachedVersion}) a uid
     * for the photo should use this overload instead of the 3-arg one.
     */
    public static String url(Context ctx, String rawUrl, AvatarSizeTier tier, long avatarVersion) {
        if (rawUrl == null || rawUrl.isEmpty()) return null;
        return AvatarUrlBuilder.buildResponsive(ctx, rawUrl, tier, avatarVersion);
    }

    /** Delivers a resolved Bitmap (or null on clear/failure) to a Canvas-based row. */
    public interface BitmapCallback extends AvatarBitmapPipeline.Callback {
        @Override void onBitmap(@Nullable Bitmap bitmap);
    }

    /**
     * ImageView bind — CommunityActivity's toolbar icon and join-gate
     * header icon. Routes through {@link AvatarBinderCore#bind}: L2
     * fast-path first, else a tier-sized circleCrop decode written back
     * into L2+L3 on success — same shared pipeline every other ImageView
     * avatar bind in the app now goes through, plus the HIGH-priority
     * request + same-URL-already-bound skip that check gives for free.
     * Communities/posts don't carry an avatarVersion counter (see
     * GroupAvatarBinder's class doc), so this always passes
     * avatarVersion=0 — a no-op for AvatarUrlBuilder#appendVersion, so the
     * URL is byte-for-byte what {@link #url} already produced.
     */
    public static void bindIcon(Context ctx, ImageView iv, String rawUrl, AvatarSizeTier tier, int placeholderRes) {
        bindIcon(ctx, iv, rawUrl, tier, placeholderRes, 0L);
    }

    /**
     * Same as the 5-arg {@link #bindIcon} but for a real user avatar (post
     * author / member / comment author) that carries an avatarVersion —
     * see the {@link #url(Context, String, AvatarSizeTier, long)} doc for
     * why the community/group ICON overload above stays hardcoded at 0.
     */
    public static void bindIcon(Context ctx, ImageView iv, String rawUrl, AvatarSizeTier tier, int placeholderRes, long avatarVersion) {
        com.callx.app.cache.AvatarBinderCore.bind(ctx, iv, rawUrl, avatarVersion,
                CACHE, new com.callx.app.cache.AvatarBinderCore.BindOptions(
                        tier, AVATAR_FORMAT, /*circleCrop=*/true, /*dontAnimate=*/true,
                        /*recordDashboardStats=*/false, placeholderRes));
    }

    /** Call from onViewRecycled()/onDestroy() for an ImageView bound via {@link #bindIcon}. */
    public static void cancelIcon(Context ctx, ImageView iv) {
        com.callx.app.cache.AvatarBinderCore.cancel(ctx, iv);
    }

    /**
     * Write-through helper for out-of-band loaders that decode a bitmap for
     * one of this binder's tiers themselves — currently only
     * {@link com.callx.app.community.canvas.CommunityAvatarPreloader}'s
     * RecyclerView fling-ahead preload, which needs to land its decode in
     * the SAME {@link ChatAvatarL2Cache}/L3 slot {@link #bindBitmap} will
     * later look up, keyed by the exact tiered/responsive URL (not the raw
     * photo URL) so the preload and the real bind actually share one entry
     * instead of silently missing each other.
     */
    public static void warmCache(Context ctx, String rawUrl, AvatarSizeTier tier, Bitmap bitmap) {
        if (rawUrl == null || rawUrl.isEmpty() || bitmap == null) return;
        String url = url(ctx, rawUrl, tier);
        if (url == null) return;
        ChatAvatarL2Cache.get(ctx).put(url, bitmap);
        // v821: no L3 write — chat's L3 is never read. (Not promoted to the pipeline's hot cache
        // either: the preloader's bitmap is Glide-owned/pooled, only pipeline-made copies are safe to pin.)
    }

    /**
     * Canvas-row bind (post author / member avatar), through the SAME shared
     * {@link AvatarBitmapPipeline} instance as {@link ChatAvatarBinder#bindBitmapCancelable}
     * (hot strong cache -> ChatAvatarL2Cache -> one deduped Glide load per url). Returns the
     * handle the caller cancels in onViewRecycled() — never null except for an empty url
     * (callback gets null then, as before).
     */
    public static AvatarBitmapPipeline.Cancelable bindBitmap(Context ctx, String rawUrl, AvatarSizeTier tier, BitmapCallback callback) {
        return bindBitmap(ctx, rawUrl, tier, 0L, callback);
    }

    /** Same as the 4-arg {@link #bindBitmap} but for a real user avatar
     *  (post author / member) that carries an avatarVersion — see
     *  {@link #url(Context, String, AvatarSizeTier, long)}. */
    public static AvatarBitmapPipeline.Cancelable bindBitmap(Context ctx, String rawUrl, AvatarSizeTier tier, long avatarVersion, BitmapCallback callback) {
        if (rawUrl == null || rawUrl.isEmpty()) {
            callback.onBitmap(null);
            return null;
        }
        String url = url(ctx, rawUrl, tier, avatarVersion);
        return ChatAvatarBinder.PIPELINE.bind(ctx, url, AvatarUrlBuilder.tierPx(ctx, tier), callback);
    }

    /** Call from onViewRecycled() for a handle returned by {@link #bindBitmap}. Safe to call with null. */
    public static void cancelBitmap(Context ctx, AvatarBitmapPipeline.Cancelable handle) {
        if (handle != null) handle.cancel();
    }
}
