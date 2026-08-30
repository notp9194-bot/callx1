package com.callx.app.feed.controllers;
import com.callx.app.utils.AlertDialogStyler;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.app.AlertDialog;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.HorizontalScrollView;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.MultiTransformation;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import com.callx.app.explore.HashtagReelsActivity;
import com.callx.app.models.ReelModel;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.AvatarSizeTier;
import com.callx.app.utils.AvatarUrlBuilder;
import com.callx.app.utils.BlurHashPlaceholder;
import com.callx.app.cache.ReelsAvatarL2Cache;
import com.callx.app.cache.AvatarVersionSyncManager;
import com.callx.app.cache.AvatarCacheAnalytics;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Manages static UI population (owner info, captions, duet series chip, music ticker,
 * hashtag chips, duet/stitch chips, follow button, liker avatar row UI),
 * click listener wiring for root + all buttons, cinema mode, and disc animation.
 */
public class ReelUiController {

    private final ReelPlayerDelegate delegate;

    // ── Cinema mode ───────────────────────────────────────────────────────
    // Static so it survives ViewPager2 recycling
    private static final Set<String> cinemaHiddenReels = new HashSet<>();
    private boolean isUiHidden = false;

    // ── Owned views ───────────────────────────────────────────────────────
    private CircleImageView ivOwnerAvatar;
    // Set by bindOwnerAvatarGated() when this reel's view is populated while
    // still offscreen — holds the URL to load once onBecameVisible() fires.
    // Null means either nothing pending, or the avatar already loaded.
    private String pendingOwnerAvatarUrl;
    // True only while the real (network-capable) owner-avatar Glide request
    // from loadOwnerAvatarNow() is in flight — false once it resolves or
    // fails. Lets onBecameInvisible() cancel ONLY a still-running fetch
    // instead of clearing an avatar that already finished loading.
    private boolean avatarLoadInFlight = false;
    // Delta-sync (see AvatarVersionSyncManager): the uid we're currently
    // holding a targeted "avatarVersion" watch for, or null if none. Only
    // ever the CURRENTLY VISIBLE reel's owner is watched — attached in
    // onBecameVisible(), detached in onBecameInvisible() — so this never
    // accumulates one live listener per reel the user has ever scrolled past.
    private String watchedAvatarUid;
    private final AvatarVersionSyncManager.Listener avatarVersionListener = this::onAvatarVersionChanged;
    private ImageView       ivOwnerStoryRing;
    private TextView        tvOwnerName;
    private TextView        tvCaption;
    private TextView        tvMusicName;
    private com.callx.app.views.MusicTickerView tvBioSongName;
    // Lazily bound the first time a collab reel inflates stub_collab_row
    // (see populateStaticData()) — kept as a field, not a local, so
    // startDiscAnimation()/stopDiscAnimation() can pause/resume it in sync
    // with playback the same way they already do for tvBioSongName.
    private com.callx.app.views.MusicTickerView tvCollabSongName;
    private ImageView       ivMusicDisc;
    private android.widget.ImageButton btnCreateAudio;
    private LinearLayout    layoutMusicTicker;
    private com.callx.app.views.ReelChipRowLayout containerHashtags;
    private HorizontalScrollView scrollHashtags;
    private final View.OnClickListener hashtagClickListener;
    private final View.OnClickListener duetChipClickListener;
    private final View.OnClickListener stitchChipClickListener;
    private LinearLayout    llSeriesChip;
    private TextView        tvSeriesChipLabel;
    private TextView        tvRepostAttribution;
    private android.widget.ImageButton btnMore;
    private android.widget.ImageButton btnComment;
    private android.widget.ImageButton btnShare;
    private android.widget.ImageButton btnDownload;
    private View reelPinnedCommentContainer;
    private TextView tvPinnedAuthor, tvPinnedText, tvPinnedLikes;
    private CircleImageView ivPinnedAvatar;

    // ── Fragment root view ─────────────────────────────────────────────────
    private View fragmentView;

    // ── Disc animation ────────────────────────────────────────────────────
    private ObjectAnimator discAnimator;

    // ── uiHandler for reactions auto-hide ────────────────────────────────
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    // ── Long-press pause state ────────────────────────────────────────────
    // Instagram-style: holding a finger down pauses playback; lifting resumes
    // it — but only if THIS gesture was the one that paused it (if the user
    // had already tapped to pause before holding, releasing leaves it paused).
    private boolean pausedByLongPress = false;

    // ── Single-tap delay handler (for double-tap disambiguation) ──────────
    // onSingleTapConfirmed fires 280ms after tap only if no second tap arrives.
    private final Handler singleTapHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSingleTap;
    private boolean actionRailInsetLocked;

    public ReelUiController(ReelPlayerDelegate delegate) {
        this.delegate = delegate;
        // These listeners read delegate, so initialize them after the final
        // delegate field has been assigned. Field-level lambdas are evaluated
        // before the constructor body and fail javac's definite-assignment
        // check for a final delegate.
        this.hashtagClickListener = view -> {
            Object tag = view.getTag();
            if (!(tag instanceof String) || !this.delegate.isAdded()
                    || this.delegate.getContext() == null) return;
            Intent intent = new Intent(this.delegate.requireContext(), HashtagReelsActivity.class);
            intent.putExtra(HashtagReelsActivity.EXTRA_HASHTAG, (String) tag);
            this.delegate.getFragment().startActivity(intent);
        };
        this.duetChipClickListener = view -> {
            ReelModel reel = this.delegate.getReel();
            if (reel == null || !this.delegate.isAdded() || this.delegate.getActivity() == null) return;
            Intent intent = new Intent(this.delegate.getActivity(),
                com.callx.app.social.DuetsByReelActivity.class);
            intent.putExtra(com.callx.app.social.DuetsByReelActivity.EXTRA_REEL_ID, reel.reelId);
            intent.putExtra(com.callx.app.social.DuetsByReelActivity.EXTRA_OWNER_NAME, reel.ownerName);
            this.delegate.getFragment().startActivity(intent);
        };
        this.stitchChipClickListener = view -> {
            ReelModel reel = this.delegate.getReel();
            if (reel == null || !this.delegate.isAdded() || this.delegate.getActivity() == null) return;
            Intent intent = new Intent(this.delegate.getActivity(),
                com.callx.app.social.StitchesByReelActivity.class);
            intent.putExtra(com.callx.app.social.StitchesByReelActivity.EXTRA_REEL_ID, reel.reelId);
            intent.putExtra(com.callx.app.social.StitchesByReelActivity.EXTRA_OWNER_NAME, reel.ownerName);
            this.delegate.getFragment().startActivity(intent);
        };
    }

    // ── View binding ──────────────────────────────────────────────────────

    public void bindViews(View root) {
        this.fragmentView = root;
        ivOwnerAvatar      = root.findViewById(R.id.iv_owner_avatar);
        ivOwnerStoryRing   = root.findViewById(R.id.iv_owner_story_ring);
        // Ring itself is now bound per-reel in bindReelData() below (needs
        // reel.ownerUid to check seen state) — see StorySeenState.
        tvOwnerName        = root.findViewById(R.id.tv_owner_name);
        tvCaption          = root.findViewById(R.id.tv_caption);
        tvMusicName        = root.findViewById(R.id.tv_music_name);
        tvBioSongName      = root.findViewById(R.id.tv_bio_song_name);
        ivMusicDisc        = root.findViewById(R.id.iv_music_disc);
        btnCreateAudio     = root.findViewById(R.id.btn_create_audio);
        layoutMusicTicker  = root.findViewById(R.id.layout_music_ticker);
        containerHashtags  = root.findViewById(R.id.container_hashtags);
        scrollHashtags     = root.findViewById(R.id.scroll_hashtags);
        llSeriesChip       = root.findViewById(R.id.ll_series_chip);
        tvSeriesChipLabel  = root.findViewById(R.id.tv_series_chip_label);
        tvRepostAttribution = root.findViewWithTag("tv_repost_attribution");
        btnMore            = root.findViewById(R.id.btn_more);
        btnComment         = root.findViewById(R.id.btn_comment);
        btnShare           = root.findViewById(R.id.btn_share);
        btnDownload        = root.findViewById(R.id.btn_download);
        reelPinnedCommentContainer = root.findViewById(R.id.reel_pinned_comment_container);
        tvPinnedAuthor = root.findViewById(R.id.reel_pinned_comment_author);
        tvPinnedText   = root.findViewById(R.id.reel_pinned_comment_text);
        tvPinnedLikes  = root.findViewById(R.id.reel_pinned_comment_likes);
        ivPinnedAvatar = root.findViewById(R.id.reel_pinned_comment_avatar);

        // Edge-to-edge insets
        View rightActions = root.findViewById(R.id.right_actions);
        if (rightActions != null) {
            ViewCompat.setOnApplyWindowInsetsListener(rightActions, (view, insets) -> {
                // Use the stable navigation-bar inset, not the currently
                // visible inset. Transient system-bar visibility changes
                // during thumbnail -> first-frame playback must not resize
                // this bottom-anchored rail and move every action upward.
                int navBarHeight = insets.getInsetsIgnoringVisibility(
                    WindowInsetsCompat.Type.navigationBars()).bottom;
                int basePx = (int)(8 * view.getResources().getDisplayMetrics().density);
                int targetBottom = basePx + navBarHeight;
                if (!actionRailInsetLocked || view.getPaddingBottom() != targetBottom) {
                    view.setPadding(view.getPaddingLeft(), view.getPaddingTop(),
                        view.getPaddingRight(), targetBottom);
                    actionRailInsetLocked = true;
                }
                return insets;
            });
            ViewCompat.requestApplyInsets(rightActions);
        }
        View bottomInfo = root.findViewById(R.id.bottom_info);
        if (bottomInfo != null) {
            ViewCompat.setOnApplyWindowInsetsListener(bottomInfo, (view, insets) -> {
                int navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
                view.setPadding(view.getPaddingLeft(), view.getPaddingTop(),
                    view.getPaddingRight(), navBarHeight);
                return insets;
            });
        }
    }

    // ── Static data population ────────────────────────────────────────────

    /**
     * Re-points tv_caption's ConstraintLayout top constraint to sit below
     * {@code anchorViewId} (either ll_owner_row or the inflated
     * ll_collab_second_author). Needed because tv_caption's XML default
     * constrains to the ViewStub's own id, which stops resolving to
     * anything the moment the stub inflates for the first time — see the
     * call sites above for the full explanation.
     */
    /** Builds the "Song Name · Artist" (or "Original Audio") display string used by both the bio song row(s) and the bottom music ticker. */
    private String buildMusicDisplay(ReelModel reel) {
        String musicDisplay = reel.musicName != null && !reel.musicName.isEmpty()
            ? reel.musicName : "Original Audio";
        if (reel.musicArtist != null && !reel.musicArtist.isEmpty()
                && !musicDisplay.contains(reel.musicArtist)) {
            musicDisplay = musicDisplay + " · " + reel.musicArtist;
        }
        return musicDisplay;
    }

    private void retargetCaptionTopConstraint(int anchorViewId) {
        if (tvCaption == null) return;
        androidx.constraintlayout.widget.ConstraintLayout.LayoutParams lp =
            (androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) tvCaption.getLayoutParams();
        if (lp == null) return;
        lp.topToBottom = anchorViewId;
        lp.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET;
        tvCaption.setLayoutParams(lp);
    }

    /**
     * Fires the real owner-avatar Glide load only if this reel is currently
     * the visible one; otherwise attempts a free disk-cache-only load and
     * stashes the URL for the real fetch once actually visible. See the
     * visibility-gate comment at the call site in populateStaticData().
     */
    private void bindOwnerAvatarGated(String photoUrl) {
        if (delegate.isCurrentlyVisible()) {
            pendingOwnerAvatarUrl = null;
            loadOwnerAvatarNow(photoUrl);
        } else {
            pendingOwnerAvatarUrl = photoUrl;
            loadOwnerAvatarDiskCacheOnly(photoUrl);
        }
    }

    /**
     * LQIP: returns a Drawable wrapping the decoded BlurHash bitmap for this
     * reel's owner avatar (see ReelModel#ownerAvatarBlurHash), or null if the
     * reel has none — callers fall back to the plain ic_person placeholder in
     * that case, same as before this feature shipped. Decode is fully
     * offline (BlurHashPlaceholder decodes-then-caches by hash string), so
     * this never costs a network request even on a first-ever cold view of
     * this reel — unlike the disk-cache-only / network Glide requests below,
     * which is exactly the gap this closes: an instant, color-accurate
     * preview instead of a flat icon while those are still in flight.
     */
    private Drawable ownerAvatarBlurPlaceholder(android.content.Context ctx) {
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.ownerAvatarBlurHash == null || reel.ownerAvatarBlurHash.isEmpty()) return null;
        Bitmap blur = BlurHashPlaceholder.get(reel.ownerAvatarBlurHash, 32, 32);
        return blur != null ? new android.graphics.drawable.BitmapDrawable(ctx.getResources(), blur) : null;
    }

    /** Same as {@link #ownerAvatarBlurPlaceholder}, falling back to a plain drawable resource when there's no BlurHash. */
    private Drawable ownerAvatarBlurPlaceholderOr(android.content.Context ctx, int fallbackResId) {
        Drawable blur = ownerAvatarBlurPlaceholder(ctx);
        return blur != null ? blur : androidx.core.content.ContextCompat.getDrawable(ctx, fallbackResId);
    }

    /**
     * FIX (disk-cache-only offscreen load): previously an offscreen reel did
     * a hard skip — placeholder only, zero Glide work — until it actually
     * became visible. Correct for avoiding network, but it also meant a
     * reel AvatarPrefetcher had already warmed into the disk cache (see
     * AvatarPrefetcher) still sat on a bare placeholder for one extra frame
     * on becoming visible instead of painting immediately from what's
     * already on disk. onlyRetrieveFromCache(true) makes this completely
     * free — Glide checks disk only, NEVER opens a network connection, and
     * a cache miss just falls through to the same placeholder as before
     * (see .error() below), so this is strictly an upgrade over the old
     * hard skip, never a regression.
     */
    private void loadOwnerAvatarDiskCacheOnly(String photoUrl) {
        if (ivOwnerAvatar == null || !delegate.isAdded() || delegate.getContext() == null) return;
        if (photoUrl == null || photoUrl.isEmpty()) {
            ivOwnerAvatar.setImageResource(R.drawable.ic_person);
            return;
        }
        android.content.Context avatarCtx = delegate.requireContext();
        ReelModel reelForVersion = delegate.getReel();
        long avatarVersion = reelForVersion != null ? reelForVersion.avatarVersion : 0L;
        int sizePx = AvatarUrlBuilder.tierPx(avatarCtx, AvatarSizeTier.SMALL);
        String resizedUrl = AvatarUrlBuilder.buildResponsive(avatarCtx, photoUrl, AvatarSizeTier.SMALL, avatarVersion);

        Drawable fallback = ownerAvatarBlurPlaceholderOr(avatarCtx, R.drawable.ic_person); // LQIP

        Glide.with(avatarCtx)
            .load(resizedUrl)
            .apply(new RequestOptions()
                .override(sizePx, sizePx)
                .format(DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.DATA) // disk-only tier — no client-side resize to cache, so DATA (raw bytes) is what a cache hit here needs
                .onlyRetrieveFromCache(true)               // never touches the network — a miss just falls through to .error()
                .priority(com.bumptech.glide.Priority.LOW) // PERF: this reel is still offscreen — never contend with a visible reel's IMMEDIATE avatar request
                .placeholder(fallback)
                .error(fallback))
            .into(ivOwnerAvatar);
    }

    private void loadOwnerAvatarNow(String photoUrl) {
        loadOwnerAvatarNow(photoUrl, com.bumptech.glide.Priority.IMMEDIATE);
    }

    /**
     * FIX (priority-based Glide queue): Glide runs a single shared executor
     * per priority bucket, so if a background prefetch (AvatarPrefetcher) and
     * the CURRENTLY VISIBLE reel's avatar both land in the queue around the
     * same time, a same-priority FIFO queue can make the user wait behind
     * work for a reel they haven't even scrolled to yet. Making priority an
     * explicit parameter (instead of always IMMEDIATE) lets each caller say
     * how urgent ITS avatar actually is:
     *   - onBecameVisible()            → IMMEDIATE (this is what's on screen right now)
     *   - promotePendingAvatarIfSlow() → HIGH      (very likely about to be on screen, but not confirmed yet — see its own doc)
     *   - AvatarPrefetcher             → LOW       (speculative; never allowed to starve either of the above)
     * See loadOwnerAvatarDiskCacheOnly for the offscreen-gate side of this —
     * that one stays LOW/disk-only regardless of caller, since it never
     * touches the network anyway.
     */
    private void loadOwnerAvatarNow(String photoUrl, com.bumptech.glide.Priority priority) {
        if (ivOwnerAvatar == null || !delegate.isAdded() || delegate.getContext() == null) return;
        if (photoUrl == null || photoUrl.isEmpty()) {
            ivOwnerAvatar.setImageResource(R.drawable.ic_person);
            return;
        }
        android.content.Context avatarCtx = delegate.requireContext();
        ReelModel currentReel = delegate.getReel();
        long avatarVersion = currentReel != null ? currentReel.avatarVersion : 0L;
        int sizePx = AvatarUrlBuilder.tierPx(avatarCtx, AvatarSizeTier.SMALL); // 36dp view → shared SMALL tier
        // FIX (server-side responsive srcset): buildResponsive() sends
        // Cloudinary a plain CSS-like tier size + a separate dpr_ param
        // instead of pre-multiplying retina pixels client-side (see
        // AvatarUrlBuilder's doc) — the CDN does that math and caches the
        // (tier, dpr bucket) combination once for every device that shares
        // it. Still exactly one request for exactly the pixels this view
        // needs, same as before.
        String resizedUrl = AvatarUrlBuilder.buildResponsive(avatarCtx, photoUrl, AvatarSizeTier.SMALL, avatarVersion);

        // FIX (blur-up thumbnail): chain the TINY tier as a .thumbnail()
        // request — same tier AvatarPrefetcher.THUMBNAIL_TIER warms ahead of
        // time (see AvatarPrefetcher), so on a prefetched reel this tiny
        // frame is already a cache hit and paints instantly instead of
        // showing ic_person until the full SMALL-tier decode lands.
        int thumbPx = AvatarUrlBuilder.tierPx(avatarCtx, AvatarSizeTier.TINY);
        String thumbUrl = AvatarUrlBuilder.buildResponsive(avatarCtx, photoUrl, AvatarSizeTier.TINY, avatarVersion);

        // FIX #5 (onTrimMemory / L2 cache): a per-module WeakReference bitmap
        // cache that deliberately SURVIVES TRIM_MEMORY_MODERATE (only cleared
        // at COMPLETE — see AvatarL2MemoryCache/ReelsAvatarL2Cache). Glide's
        // own memory cache gets trimmed on the more-frequent MODERATE signal
        // (CallxApp#onTrimMemory), so on a warm restart right after a routine
        // backgrounding this is often still hit even when Glide's isn't —
        // skips the whole Glide request pipeline for an instant repaint.
        Bitmap l2Hit = ReelsAvatarL2Cache.get(avatarCtx).get(resizedUrl);
        if (l2Hit != null) {
            ivOwnerAvatar.setImageBitmap(l2Hit);
            AvatarCacheAnalytics.getInstance(avatarCtx).record(AvatarCacheAnalytics.Tier.L2_MEMORY);
            return;
        }

        // FIX (L3 disk tier): covers the one gap L2 can't — process death.
        // L2 is in-process memory only, so a fresh cold start after the
        // process was killed has nothing there either. Tag the view with
        // the URL this bind is for and fire the disk read in parallel with
        // the Glide request below (not instead of it — Glide is still the
        // source of truth and will normally win the race on a warm cache).
        // If the async disk read lands first AND this exact bind is still
        // the current one (tag still matches, request still in flight),
        // paint it immediately; if Glide already resolved by then, the
        // stale disk hit is simply dropped so it can never flicker over a
        // newer image.
        ivOwnerAvatar.setTag(resizedUrl);
        ReelsAvatarL2Cache.l3(avatarCtx).getAsync(resizedUrl, l3Bmp -> {
            if (l3Bmp == null) return;
            if (!delegate.isAdded() || ivOwnerAvatar == null) return;
            if (!resizedUrl.equals(ivOwnerAvatar.getTag())) return; // rebound to a different reel since
            if (!avatarLoadInFlight) return; // Glide already resolved this bind — don't flicker over it
            ivOwnerAvatar.setImageBitmap(l3Bmp);
            ReelsAvatarL2Cache.get(avatarCtx).put(resizedUrl, l3Bmp); // warm L2 too
            AvatarCacheAnalytics.getInstance(avatarCtx).record(AvatarCacheAnalytics.Tier.L3_DISK);
        });

        // v44 PERF: dropped .circleCrop(). ivOwnerAvatar is a
        // de.hdodenhof CircleImageView, which ALWAYS circular-clips
        // whatever bitmap it's given via its own BitmapShader in
        // onDraw() — circleCrop()-ing the source in Glide first was
        // pure duplicate work (a second full bitmap alloc+draw per
        // decode) AND it silently forced ARGB_8888 (circleCrop needs
        // an alpha channel for the transparent corners), defeating
        // the PREFER_RGB_565 hint right below — so every avatar was
        // decoding at double the intended memory footprint for a
        // shape that got clipped again anyway. Plain square decode
        // now actually gets the RGB_565 halving it was asking for.
        RequestOptions opts = new RequestOptions()
            .override(sizePx, sizePx)
            .format(DecodeFormat.PREFER_RGB_565) // opaque avatar, no alpha needed — now actually honored
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE) // PERF: cache resized variant on disk — re-scroll won't re-download
            .priority(priority) // PERF: visible reel jumps the queue ahead of any queued prefetch — see this method's doc
            .placeholder(ownerAvatarBlurPlaceholderOr(avatarCtx, R.drawable.ic_person)); // LQIP

        avatarLoadInFlight = true;
        Glide.with(avatarCtx)
            .load(resizedUrl)
            .apply(opts)
            .thumbnail(
                Glide.with(avatarCtx)
                    .load(thumbUrl)
                    .apply(new RequestOptions()
                        .override(thumbPx, thumbPx)
                        .format(DecodeFormat.PREFER_RGB_565)
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .priority(priority))) // PERF: blur-up frame inherits the same urgency as the main request
            // FIX (Job-cancel equivalent, see onBecameInvisible): tracks
            // whether this specific request is still running so an
            // invisible-transition mid-flight can cancel it without
            // touching a request that already resolved.
            .listener(new RequestListener<Drawable>() {
                @Override
                public boolean onLoadFailed(GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                    avatarLoadInFlight = false;
                    return false;
                }

                @Override
                public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                    avatarLoadInFlight = false;
                    // CDN/cache split monitoring — funnel Glide's own
                    // DataSource straight through the shared mapping so this
                    // stays consistent with every other module recording
                    // into the same AvatarCacheAnalytics instance.
                    AvatarCacheAnalytics.getInstance(avatarCtx)
                        .record(AvatarCacheAnalytics.fromGlideDataSource(dataSource));
                    // Feed L2 (memory, survives MODERATE) and L3 (disk,
                    // survives process death) so a future bind of this same
                    // reel — re-scroll, warm restart, OR a fresh cold start —
                    // can skip Glide's request pipeline entirely.
                    if (resource instanceof android.graphics.drawable.BitmapDrawable) {
                        Bitmap bmp = ((android.graphics.drawable.BitmapDrawable) resource).getBitmap();
                        ReelsAvatarL2Cache.get(avatarCtx).put(resizedUrl, bmp);
                        ReelsAvatarL2Cache.l3(avatarCtx).put(resizedUrl, bmp);
                    }
                    return false;
                }
            })
            .into(ivOwnerAvatar);
    }

    /**
     * Called by ReelPlayerFragment.applyVisibleState(true) when this reel
     * becomes the visible one. Fires the owner-avatar load if
     * populateStaticData() ran earlier while this reel was still offscreen
     * and deferred it (bindOwnerAvatarGated). No-op otherwise — either the
     * avatar already loaded, or reel.ownerPhoto was empty.
     */
    public void onBecameVisible() {
        if (pendingOwnerAvatarUrl != null) {
            String url = pendingOwnerAvatarUrl;
            pendingOwnerAvatarUrl = null;
            loadOwnerAvatarNow(url);
        }
        attachAvatarVersionWatch();
    }

    /**
     * FIX (avatar delta-sync): attach a TARGETED "avatarVersion" watch (see
     * AvatarVersionSyncManager) for this reel's owner, ONLY while their reel
     * is the visible one — so if the owner uploads a new avatar while the
     * viewer is sitting on this exact reel, it refreshes live instead of
     * waiting for the next full app restart or an unrelated Firebase read to
     * happen to refetch photoUrl. Deliberately not attached for every reel
     * in the adapter at once — see onBecameInvisible for the matching
     * detach, keeping exactly one live listener outstanding at a time
     * (matches "sirf changed users ka avatarVersion listen karo").
     */
    private void attachAvatarVersionWatch() {
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.uid == null || reel.uid.isEmpty()) return;
        if (reel.uid.equals(watchedAvatarUid)) return; // already watching this exact owner
        detachAvatarVersionWatch();
        watchedAvatarUid = reel.uid;
        AvatarVersionSyncManager.getInstance(fragmentView.getContext()).watch(reel.uid, avatarVersionListener);
    }

    private void detachAvatarVersionWatch() {
        if (watchedAvatarUid == null) return;
        AvatarVersionSyncManager.getInstance(fragmentView.getContext()).unwatch(watchedAvatarUid, avatarVersionListener);
        watchedAvatarUid = null;
    }

    /**
     * Fires (main thread) whenever the watched owner's avatarVersion changes
     * for real. AvatarUrlBuilder already bakes &v=<avatarVersion> into the
     * resolved URL, so bumping the version alone guarantees the next
     * loadOwnerAvatarNow() call produces a brand-new Glide/CDN cache key —
     * this just needs to trigger that reload while the reel is still on
     * screen, instead of waiting for the next cold bind.
     */
    private void onAvatarVersionChanged(String uid, long newVersion) {
        ReelModel reel = delegate.getReel();
        if (reel == null || !uid.equals(reel.uid) || !uid.equals(watchedAvatarUid)) return;
        if (!delegate.isAdded() || delegate.getContext() == null || ivOwnerAvatar == null) return;
        loadOwnerAvatarNow(reel.ownerPhoto, com.bumptech.glide.Priority.HIGH);
    }

    /**
     * FIX (proactive promotion on slow-scroll): previously a disk-cache-only
     * offscreen avatar (see bindOwnerAvatarGated/loadOwnerAvatarDiskCacheOnly)
     * only ever got promoted to the real network-capable load in
     * onBecameVisible() — i.e. only once ViewPager2 fully snapped onto this
     * page. On a slow, deliberate drag the adjacent page can sit mid-scroll
     * for a while before that snap fires, and if AvatarPrefetcher hasn't
     * already warmed this particular reel's disk cache, the user just watches
     * a bare placeholder the whole time even though the drag clearly shows
     * they're headed here.
     *
     * Called from ReelsFragment#onPageScrolled (via ReelPlayerFragment) once
     * scroll velocity drops low enough to read as "settling here" rather than
     * "flying past" — same visibility gate, just triggered earlier, off the
     * scroll signal instead of the eventual full-visibility callback. Shares
     * pendingOwnerAvatarUrl with onBecameVisible(), so it's naturally
     * idempotent: once either one consumes it (or the reel never needed the
     * gate to begin with), repeated calls while the drag lingers cost
     * nothing.
     */
    public void promotePendingAvatarIfSlow() {
        if (pendingOwnerAvatarUrl != null) {
            String url = pendingOwnerAvatarUrl;
            pendingOwnerAvatarUrl = null;
            // HIGH, not IMMEDIATE: this reel is very likely about to be the
            // visible one, but isn't confirmed yet (the drag could still
            // reverse) — it should jump ahead of any LOW-priority
            // AvatarPrefetcher work, but never contend with the CURRENTLY
            // visible reel's own IMMEDIATE request. See loadOwnerAvatarNow's doc.
            loadOwnerAvatarNow(url, com.bumptech.glide.Priority.HIGH);
        }
    }

    /**
     * Called by ReelPlayerFragment.applyVisibleState(false) when this reel
     * stops being the visible one.
     *
     * FIX (Lifecycle-aware cancel — the Java equivalent of cancelling a
     * coroutine Job on scope-exit): if the real network-capable avatar
     * fetch from loadOwnerAvatarNow() is still in flight (e.g. the user
     * flicked past this reel before it finished decoding), keeping it
     * running is wasted bandwidth/CPU for a target nobody is looking at,
     * and ViewPager2 may recycle this fragment's view into a completely
     * different reel before the fetch even completes. Glide.clear()
     * cancels the in-flight request outright. Only fires when
     * avatarLoadInFlight is true — a request that already resolved is left
     * alone so a quick back-swipe still shows the avatar instantly instead
     * of flickering back to the placeholder.
     */
    public void onBecameInvisible() {
        detachAvatarVersionWatch();
        if (avatarLoadInFlight && ivOwnerAvatar != null && delegate.isAdded() && delegate.getContext() != null) {
            Glide.with(delegate.requireContext()).clear(ivOwnerAvatar);
            avatarLoadInFlight = false;
            // Re-arm so the next onBecameVisible() resumes the load against
            // whatever the reel's current owner photo is (not necessarily
            // the exact URL just cancelled — a live profile-photo update
            // could have changed it in the meantime).
            ReelModel reel = delegate.getReel();
            if (reel != null) pendingOwnerAvatarUrl = reel.ownerPhoto;
        }
    }

    public void populateStaticData() {
        if ("close_friends".equals(delegate.getReel().audienceType)) {
            View avatarContainer = fragmentView.findViewById(R.id.avatar_container);
            if (avatarContainer != null) avatarContainer.setBackgroundResource(R.drawable.bg_close_friends_ring);
            TextView label = fragmentView.findViewById(R.id.tv_close_friends_label);
            if (label != null) label.setVisibility(View.VISIBLE);
        }
        ReelModel reel = delegate.getReel();
        if (reel == null) return;

        // Owner name + caption
        if (tvOwnerName != null) tvOwnerName.setText(reel.ownerName != null ? "@" + reel.ownerName : "@user");

        // Instagram-style: gradient only while an UNSEEN status exists;
        // flat gray once fully seen; hidden if no active status at all.
        // Uses the same app-wide StatusCacheManager the Status tab and
        // ReelCommentsAdapter already read from — single source of truth.
        if (ivOwnerStoryRing != null && reel.uid != null) {
            com.callx.app.cache.StatusCacheManager scm =
                    com.callx.app.cache.StatusCacheManager.getInstance(fragmentView.getContext());
            boolean hasUnseen = scm.hasUnseen(reel.uid);
            boolean hasAny    = scm.hasStatus(reel.uid);
            if (hasUnseen) {
                ivOwnerStoryRing.setImageDrawable(null);
                // v43 PERF: reuse the gradient Drawable already attached to
                // this exact ImageView instead of allocating a new
                // StoryRingGradientDrawable (+ 2 Paints) on every bind. The
                // underlying bitmap was already cached (v41/v42) but the
                // Drawable wrapper itself was still fresh garbage each time.
                // Safe to reuse: stroke width is a fixed constant (1.8dp), so
                // every rebind of this view wants the identical instance.
                Object existingRing = ivOwnerStoryRing.getTag(R.id.tag_story_ring_drawable);
                android.graphics.drawable.Drawable ringDrawable;
                if (existingRing instanceof com.callx.app.utils.StoryRingGradientDrawable) {
                    ringDrawable = (android.graphics.drawable.Drawable) existingRing;
                } else {
                    // v47: 2dp → 1.8dp stroke, matches the container/avatar
                    // resize in fragment_reel_player.xml (43.2dp ring,
                    // 36dp avatar, 1.8dp transparent gap between them —
                    // see that layout's comment for the full math).
                    ringDrawable = com.callx.app.utils.StoryRingGradientDrawable.withStrokeDp(1.8f,
                            fragmentView.getResources().getDisplayMetrics().density);
                    ivOwnerStoryRing.setTag(R.id.tag_story_ring_drawable, ringDrawable);
                }
                ivOwnerStoryRing.setBackground(ringDrawable);
                ivOwnerStoryRing.setVisibility(View.VISIBLE);
            } else if (hasAny) {
                ivOwnerStoryRing.setBackground(null);
                ivOwnerStoryRing.setImageResource(com.callx.app.core.R.drawable.circle_status_seen);
                ivOwnerStoryRing.setVisibility(View.VISIBLE);
            } else {
                ivOwnerStoryRing.setVisibility(View.GONE);
            }
        }

        // ── Instagram-style bio song-name (icon + text directly below the
        // username) — same source string as the (hidden) bottom music
        // ticker, computed once here and reused for both the normal owner
        // row (tv_bio_song_name) and the collab row (tv_collab_song_name).
        String bioMusicDisplay = buildMusicDisplay(reel);
        if (tvBioSongName != null) {
            tvBioSongName.setText(bioMusicDisplay);
            View llBioSongRow = fragmentView.findViewById(R.id.ll_bio_song_row);
            if (llBioSongRow != null) {
                llBioSongRow.setOnClickListener(v -> delegate.openSoundDetail());
            }
        }

        // ── Collab Joint-Author display ──────────────────────────────────────
        // ✅ MULTI-COLLABORATOR: renders an overlapping avatar stack (up to 3)
        // plus an Instagram-style "and N others" summary, and opens the full
        // Collaborators bottom sheet (with Follow buttons) on tap. Falls back
        // to reel.collabMap being empty but the legacy single collabUid field
        // being set (old data written before this feature shipped).
        View llOwnerRow = fragmentView.findViewById(R.id.ll_owner_row);
        java.util.List<com.callx.app.models.ReelModel.CollabCollaborator> accepted = reel.acceptedCollaborators();
        boolean legacySingleOnly = accepted.isEmpty() && reel.isCollabPost
            && reel.collabUid != null && !reel.collabUid.isEmpty();
        boolean isCollabDisplay = !accepted.isEmpty() || legacySingleOnly;

        // ll_collab_second_author is ViewStub-backed (stub_collab_row) — only
        // inflate it for reels that actually have a collaborator to show, so
        // the (much more common) non-collab reel skips this inflate entirely.
        View llCollabAuthors = fragmentView.findViewById(R.id.ll_collab_second_author);
        if (llCollabAuthors == null && isCollabDisplay) {
            View stub = fragmentView.findViewById(R.id.stub_collab_row);
            if (stub instanceof android.view.ViewStub) {
                llCollabAuthors = ((android.view.ViewStub) stub).inflate();
            }
        }
        if (llCollabAuthors != null) {
            if (isCollabDisplay) {
                llCollabAuthors.setVisibility(View.VISIBLE);
                // ✅ Merged row: hide the standalone owner row — the owner's
                // avatar/name now render as the first item of the collab stack
                // below, matching Instagram's single-line collab credit.
                if (llOwnerRow != null) llOwnerRow.setVisibility(View.GONE);
                // 🐛 FIX: tv_caption's XML default constrains to the
                // ViewStub's own id (stub_collab_row). A ViewStub can only
                // inflate once — after that, "stub_collab_row" no longer
                // exists in the hierarchy (it's been replaced by
                // ll_collab_second_author), so on every reel shown *after*
                // the first collab reel in this recycled fragment, that
                // constraint silently resolved to nothing and tv_caption
                // snapped back to the top, colliding with ll_owner_row on
                // normal (non-collab) reels too. Re-target explicitly on
                // every bind instead of trusting the stub id.
                retargetCaptionTopConstraint(R.id.ll_collab_second_author);

                com.callx.app.views.CollabAvatarStackView collabStack = fragmentView.findViewById(R.id.collab_avatar_stack);
                TextView tvCollabName = fragmentView.findViewById(R.id.tv_collab_author_name);
                TextView tvCollabFollowBtn = fragmentView.findViewById(R.id.tv_collab_follow_btn);

                // Owner goes first in the stack, then accepted collaborators —
                // Instagram shows the post owner's avatar at the front of the
                // overlapping stack, not just the collaborators.
                java.util.List<String> avatarUrls = new java.util.ArrayList<>();
                avatarUrls.add(reel.ownerPhoto);
                int totalCount;

                if (!accepted.isEmpty()) {
                    for (com.callx.app.models.ReelModel.CollabCollaborator c : accepted) avatarUrls.add(c.avatarUrl);
                    totalCount = accepted.size();
                } else {
                    avatarUrls.add(reel.collabAvatarUrl);
                    totalCount = 1;
                }

                if (collabStack != null) {
                    int stackCount = Math.min(avatarUrls.size(), 3);
                    collabStack.clearAvatars();
                    collabStack.setAvatarCount(stackCount);
                    for (int i = 0; i < stackCount; i++) {
                        String url = avatarUrls.get(i);
                        final int index = i;
                        if (url != null && !url.isEmpty() && delegate.isAdded()) {
                            // PERF: same pattern as before — resized URL +
                            // pinned decode size — but loaded as a raw Bitmap
                            // (asBitmap, no circleCrop) since the notch-cutout
                            // view does its own circular clipping/masking.
                            android.content.Context stackCtx = delegate.requireContext();
                            int stackSizePx = AvatarUrlBuilder.tierPx(stackCtx, AvatarSizeTier.TINY);
                            Glide.with(stackCtx)
                                .asBitmap()
                                .load(AvatarUrlBuilder.build(stackCtx, url, AvatarSizeTier.TINY))
                                .apply(new RequestOptions()
                                    .override(stackSizePx, stackSizePx)
                                    .format(DecodeFormat.PREFER_ARGB_8888) // needs alpha for the circular clip
                                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE))
                                .into(new com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>() {
                                    @Override
                                    public void onResourceReady(@androidx.annotation.NonNull android.graphics.Bitmap resource, @androidx.annotation.NonNull com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> transition) {
                                        collabStack.setAvatarBitmap(index, resource);
                                    }
                                    @Override
                                    public void onLoadCleared(android.graphics.drawable.Drawable placeholder) {
                                        // no-op: view keeps its placeholder ring until reused
                                    }
                                });
                        }
                    }
                }

                tvCollabSongName = fragmentView.findViewById(R.id.tv_collab_song_name);
                if (tvCollabSongName != null) {
                    tvCollabSongName.setText(bioMusicDisplay);
                    View llCollabSongRow = fragmentView.findViewById(R.id.ll_collab_song_row);
                    if (llCollabSongRow != null) {
                        llCollabSongRow.setOnClickListener(v -> delegate.openSoundDetail());
                    }
                }

                if (tvCollabName != null) {
                    // 🎨 UI FIX: Instagram always anchors this line on the REEL
                    // OWNER's name — "@ownerName and N other(s)" — not on the
                    // collaborator's own name, and shows "and N other(s)" even
                    // when N=1 (never just a bare name). Previously this used
                    // the first collaborator's name and only added "and N
                    // others" once totalCount was 2+, so with a single
                    // collaborator it silently dropped the "and 1 other" part.
                    String ownerName = reel.ownerName != null && !reel.ownerName.isEmpty()
                        ? reel.ownerName : "user";
                    String namePart = "@" + ownerName;
                    String othersPart = " and " + totalCount + (totalCount == 1 ? " other" : " others");
                    String full = namePart + othersPart;

                    // Instagram-style split tap targets on the same line:
                    // "@ownerName" opens that person's profile (the reel
                    // owner — collab captions always credit the owner here,
                    // never the collaborator), "and N other(s)" opens the
                    // full Collaborators sheet. Avatar stack does the same
                    // as the "and N other(s)" tap — see below.
                    android.text.SpannableString spannable = new android.text.SpannableString(full);
                    spannable.setSpan(new android.text.style.ClickableSpan() {
                        @Override public void onClick(@androidx.annotation.NonNull View widget) {
                            if (delegate.isAdded()) delegate.openUserReels();
                        }
                        @Override public void updateDrawState(@androidx.annotation.NonNull android.text.TextPaint ds) {
                            ds.setUnderlineText(false); // keep Instagram's plain (non-underlined) look
                        }
                    }, 0, namePart.length(), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    spannable.setSpan(new android.text.style.ClickableSpan() {
                        @Override public void onClick(@androidx.annotation.NonNull View widget) {
                            if (!delegate.isAdded()) return;
                            delegate.showBottomSheet(
                                com.callx.app.social.CollaboratorsBottomSheet.newInstance(
                                    reel.reelId, reel.uid, reel.ownerName, reel.ownerPhoto),
                                com.callx.app.social.CollaboratorsBottomSheet.TAG);
                        }
                        @Override public void updateDrawState(@androidx.annotation.NonNull android.text.TextPaint ds) {
                            ds.setUnderlineText(false);
                        }
                    }, namePart.length(), full.length(), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

                    tvCollabName.setText(spannable);
                    tvCollabName.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                    tvCollabName.setHighlightColor(android.graphics.Color.TRANSPARENT); // no gray flash on tap
                }

                // Follow button now lives on the merged collab row instead of
                // the (hidden) owner row. Click + Follow/Following state are
                // wired centrally in ReelSocialController (same as tv_follow_btn).
                if (tvCollabFollowBtn != null) tvCollabFollowBtn.setVisibility(View.VISIBLE);

                // Avatar stack tap → same as "and N other(s)": opens the full
                // Collaborators sheet (Instagram behavior — tapping the
                // overlapping avatars never jumps straight to one profile).
                if (collabStack != null) {
                    collabStack.setOnClickListener(v -> {
                        if (!delegate.isAdded()) return;
                        delegate.showBottomSheet(
                            com.callx.app.social.CollaboratorsBottomSheet.newInstance(
                                reel.reelId, reel.uid, reel.ownerName, reel.ownerPhoto),
                            com.callx.app.social.CollaboratorsBottomSheet.TAG);
                    });
                }
                // Row-level click removed — name/avatar now have their own
                // precise tap targets above (Instagram never treats the
                // whole line as one big tap target here).
                llCollabAuthors.setOnClickListener(null);
            } else {
                llCollabAuthors.setVisibility(View.GONE);
                llCollabAuthors.setOnClickListener(null);
                if (llOwnerRow != null) llOwnerRow.setVisibility(View.VISIBLE);
                // Same fix as above, mirrored: normal reel → caption goes
                // back under the owner row, not the (now permanently
                // inflated but GONE) collab row / stale stub id.
                retargetCaptionTopConstraint(R.id.ll_owner_row);
            }
        }
        // PERF advance — "precompute next reel's UI state": reuse the
        // caption text already built ahead of time by ReelUiStatePrecomputer
        // when present, instead of re-concatenating the duet prefix here on
        // the swipe-completion frame.
        com.callx.app.cache.ReelUiStateCache.State precomputedCaption =
            com.callx.app.cache.ReelUiStateCache.get(reel.reelId);
        if (precomputedCaption == null) {
            // Correctness fallback for reels opened outside ReelsFragment
            // (profile/deep-link entry). The feed precomputer normally makes
            // this a cache hit before the page is selected.
            precomputedCaption = com.callx.app.cache.ReelUiStateCache.compute(reel);
        }
        String captionText;
        if (precomputedCaption != null) {
            captionText = precomputedCaption.captionText;
        } else {
            captionText = reel.caption != null ? reel.caption : "";
            if (reel.duetOf != null && !reel.duetOf.isEmpty()) captionText = "🔀 Duet · " + captionText;
        }
        // Guard: Firebase POJO mapping (DataSnapshot.getValue(ReelModel.class))
        // sets `caption` via reflection, bypassing the ReelModel constructor's
        // truncation — so a malformed/huge caption in the DB can still reach
        // here untouched. Cap it right before it hits a View, since an
        // oversized TextView is what turns into a multi-hundred-KB saved
        // instance state and trips TransactionTooLargeException when this
        // fragment's Activity is stopped.
        captionText = com.callx.app.models.ReelModel.safeCaption(captionText);
        if (tvCaption != null) tvCaption.setText(captionText);

        // Duet Series chip
        if (llSeriesChip != null) {
            if (reel.seriesId != null && !reel.seriesId.isEmpty()) {
                String label = "Part " + reel.seriesEpisodeNumber + " of " +
                    (reel.seriesTitle != null && !reel.seriesTitle.isEmpty() ? reel.seriesTitle : "Series");
                if (tvSeriesChipLabel != null) tvSeriesChipLabel.setText(label);
                llSeriesChip.setVisibility(View.VISIBLE);
                llSeriesChip.setOnClickListener(v -> delegate.openDuetSeries());

                final String finalSeriesId    = reel.seriesId;
                final String finalSeriesTitle = reel.seriesTitle != null ? reel.seriesTitle : "Series";
                llSeriesChip.setOnLongClickListener(v -> {
                    String myUid = FirebaseUtils.getCurrentUid();
                    if (myUid == null || myUid.isEmpty()) {
                        android.widget.Toast.makeText(delegate.requireContext(),
                            "Login required to subscribe", android.widget.Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    com.google.firebase.database.DatabaseReference subRef =
                        FirebaseDatabase.getInstance(com.callx.app.utils.Constants.DB_URL)
                            .getReference("duetSeriesSubscriptions").child(finalSeriesId).child(myUid);
                    com.google.firebase.database.DatabaseReference userRef =
                        FirebaseDatabase.getInstance(com.callx.app.utils.Constants.DB_URL)
                            .getReference("userSubscribedSeries").child(myUid).child(finalSeriesId);
                    subRef.addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                        @Override
                        public void onDataChange(@androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snap) {
                            if (!delegate.isAdded()) return;
                            if (snap.exists()) {
                                subRef.removeValue(); userRef.removeValue();
                                FirebaseDatabase.getInstance(com.callx.app.utils.Constants.DB_URL)
                                    .getReference("duetSeries").child(finalSeriesId).child("subscriberCount")
                                    .setValue(ServerValue.increment(-1));
                                android.widget.Toast.makeText(delegate.requireContext(),
                                    "Unsubscribed from " + finalSeriesTitle, android.widget.Toast.LENGTH_SHORT).show();
                            } else {
                                subRef.setValue(true); userRef.setValue(true);
                                FirebaseDatabase.getInstance(com.callx.app.utils.Constants.DB_URL)
                                    .getReference("duetSeries").child(finalSeriesId).child("subscriberCount")
                                    .setValue(ServerValue.increment(1));
                                android.widget.Toast.makeText(delegate.requireContext(),
                                    "Subscribed to " + finalSeriesTitle + "! 🎬", android.widget.Toast.LENGTH_SHORT).show();
                            }
                        }
                        @Override public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError e) {}
                    });
                    return true;
                });
            } else {
                llSeriesChip.setVisibility(View.GONE);
            }
        }

        // Music ticker
        String musicDisplay = bioMusicDisplay;
        if (tvMusicName != null) {
            tvMusicName.setText(musicDisplay);
            tvMusicName.setSingleLine(true);
            tvMusicName.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
            tvMusicName.setMarqueeRepeatLimit(-1);
            tvMusicName.setSelected(true);
            tvMusicName.setHorizontallyScrolling(true);
        }

        // The right-rail audio tile replaces the old bottom-left audio ticker.
        // It always uses the existing sound-detail flow: for original audio,
        // ReelDuetController resolves the deterministic orig_{reelId} sound.
        String rawMusicName = reel.musicName == null ? "" : reel.musicName.trim();
        if (btnCreateAudio != null) {
            btnCreateAudio.setVisibility(View.VISIBLE);
            btnCreateAudio.setOnClickListener(v -> delegate.showSoundQuickActions());
            btnCreateAudio.setContentDescription(
                rawMusicName.isEmpty() ? "Original audio" : rawMusicName);

            // ✅ FIX: for OLD reels (posted before musicCoverUrl was saved on
            // reused-sound reels), fetch the cover from sounds/{musicId}
            // once at render time instead of silently showing no photo.
            if (TextUtils.isEmpty(reel.musicCoverUrl) && !TextUtils.isEmpty(reel.musicId)) {
                fetchAndBindMissingSoundCover(reel);
            }
            String audioImageUrl = !TextUtils.isEmpty(reel.musicCoverUrl)
                ? reel.musicCoverUrl
                : reel.ownerPhoto;
            if (delegate.isAdded() && delegate.getContext() != null
                    && !TextUtils.isEmpty(audioImageUrl)) {
                // PERF: same pattern as ivOwnerAvatar — resize server-side via
                // AvatarUrlBuilder AND pin the Glide decode size with
                // .override(), so this never decodes more pixels than the
                // 28dp tile actually shows (was loading full-res source).
                android.content.Context audioCtx = delegate.requireContext();
                int sizePx = AvatarUrlBuilder.tierPx(audioCtx, AvatarSizeTier.TINY); // 28dp view → shared TINY tier
                // Corner radius must match bg_audio_create_tile.xml's 4dp so the
                // photo's own rounded corners line up with the tile's rounded
                // background instead of showing square corners peeking through.
                int cornerRadiusPx = AvatarUrlBuilder.dpToPx(audioCtx, 4);
                String resizedAudioUrl = AvatarUrlBuilder.build(audioCtx, audioImageUrl, AvatarSizeTier.TINY);
                Glide.with(audioCtx)
                    .load(resizedAudioUrl)
                    .apply(new RequestOptions()
                        .transform(new MultiTransformation<>(
                                new CenterCrop(), new RoundedCorners(cornerRadiusPx)))
                        .override(sizePx, sizePx)
                        .format(DecodeFormat.PREFER_RGB_565) // opaque tile, no alpha needed — half the decode memory
                        // PERF: cache the final resized+cropped bitmap to disk (RESOURCE),
                        // not the original — re-scroll/re-bind never re-decodes or re-downloads.
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .placeholder(R.drawable.ic_audio))
                    .into(btnCreateAudio);
            } else {
                btnCreateAudio.setImageResource(R.drawable.ic_audio);
            }
        }
        if (layoutMusicTicker != null) {
            layoutMusicTicker.setVisibility(View.GONE);
        }

        // Music disc cover art
        startDiscAnimation();
        if (ivMusicDisc != null && delegate.isAdded() && delegate.getContext() != null) {
            // ✅ FIX: same fallback as btnCreateAudio — own thumbnail for a
            // brand-new original, else generic icon while the DB fetch runs.
            String coverUrl = !TextUtils.isEmpty(reel.musicCoverUrl)
                ? reel.musicCoverUrl
                : reel.ownerPhoto;
            if (coverUrl != null && !coverUrl.isEmpty()) {
                android.content.Context discCtx = delegate.requireContext();
                int discSizePx = AvatarUrlBuilder.tierPx(discCtx, AvatarSizeTier.TINY); // iv_music_disc 22dp → shared TINY tier
                Glide.with(discCtx)
                    .load(AvatarUrlBuilder.build(discCtx, coverUrl, AvatarSizeTier.TINY))
                    .apply(new RequestOptions().circleCrop()
                        .override(discSizePx, discSizePx)
                        .format(DecodeFormat.PREFER_RGB_565) // opaque disc art, no alpha needed
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE) // PERF: cache resized variant on disk
                        .placeholder(R.drawable.ic_music_note))
                    .into(ivMusicDisc);
            } else {
                ivMusicDisc.setImageResource(R.drawable.ic_music_note);
            }
        }

        // Owner avatar + story ring
        // PERF FIX: routed through the central AvatarUrlBuilder (exact
        // size, 2x retina, auto-format Cloudinary variant) instead of
        // loading the raw stored ownerPhoto URL, and .override() pins the
        // Glide decode size so recycling never decodes more than needed.
        //
        // VISIBILITY GATE: populateStaticData() runs unconditionally from
        // onCreateView() for every ViewPager2 fragment, including the
        // offscreenPageLimit=1 adjacent fragment that's created ahead of
        // time but never seen yet. Firing the real Glide .into() load here
        // meant every prefetched-but-unseen reel decoded an avatar bitmap
        // the user might never scroll to. Route through bindOwnerAvatarGated()
        // instead — it only fires the actual load when this reel is the
        // one currently visible; otherwise it defers until onBecameVisible()
        // is called (see ReelPlayerFragment.applyVisibleState). The
        // separate AvatarPrefetcher still warms Glide's cache for the
        // upcoming reel on a background thread, so the deferred load below
        // is normally an instant cache hit once it does fire.
        if (ivOwnerAvatar != null && delegate.isAdded() && delegate.getContext() != null) {
            bindOwnerAvatarGated(reel.ownerPhoto);
        }
        // v43 PERF: removed a duplicate story-ring block that used to sit
        // here — it re-fetched StatusCacheManager.getInstance() and called
        // hasStatus(reel.uid) a SECOND time (extra singleton lookup + map
        // lookup) purely to re-set the same visibility the block above
        // already sets correctly (with the correct gradient/seen-icon
        // distinction this one didn't even make). Pure dead duplicate work
        // on every single reel fragment creation — removed, not replaced.

        // Repost attribution
        if (tvRepostAttribution != null && reel.repostedFromName != null && !reel.repostedFromName.isEmpty()) {
            tvRepostAttribution.setVisibility(View.VISIBLE);
            tvRepostAttribution.setText("↻ Reposted by @" + reel.repostedFromName);
        }

        // Hashtags
        renderChipRow(precomputedCaption);
    }

    // ── Follow UI (called by SocialController) ────────────────────────────

    public void updateFollowUI(boolean following) {
        // Delegated to SocialController — handled there directly.
        // This method exists for cases where UiController needs to update follow state.
    }

    // ── Hashtag chips ─────────────────────────────────────────────────────

    private void renderChipRow(com.callx.app.cache.ReelUiStateCache.State state) {
        if (containerHashtags == null) return;
        containerHashtags.beginBatchUpdate();
        try {
            containerHashtags.recycleChildren();
            int visibleCount = state == null || state.hashtagLabels == null
                ? 0 : Math.min(state.hashtagLabels.length,
                    com.callx.app.cache.ReelUiStateCache.MAX_VISIBLE_HASHTAG_CHIPS);
            boolean hasDuet = state != null && state.duetLabel != null;
            boolean hasStitch = state != null && state.stitchLabel != null;

            if (hasDuet) {
                TextView chip = containerHashtags.obtainChip();
                configureActionChip(chip, state.duetLabel, R.drawable.bg_reel_duet_chip,
                    16, duetChipClickListener);
            }
            if (hasStitch) {
                TextView chip = containerHashtags.obtainChip();
                configureActionChip(chip, state.stitchLabel, R.drawable.bg_reel_stitch_chip,
                    16, stitchChipClickListener);
            }
            for (int i = 0; i < visibleCount; i++) {
                TextView chip = containerHashtags.obtainChip();
                configureHashtagChip(chip, state.hashtagLabels[i], state.hashtagTags[i]);
            }
            boolean hasAnyChip = visibleCount > 0 || hasDuet || hasStitch;
            if (scrollHashtags != null) scrollHashtags.setVisibility(
                hasAnyChip ? View.VISIBLE : View.GONE);
            if (hasAnyChip && scrollHashtags != null) scrollHashtags.scrollTo(0, 0);
        } finally {
            containerHashtags.endBatchUpdate();
        }
    }

    private void configureActionChip(TextView chip, String label, int backgroundRes,
                                     int endMarginDp, View.OnClickListener listener) {
        chip.setText(label);
        chip.setTag(null);
        chip.setBackgroundResource(backgroundRes);
        chip.setPadding(delegate.dpToPx(20), delegate.dpToPx(8),
            delegate.dpToPx(20), delegate.dpToPx(8));
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) chip.getLayoutParams();
        lp.setMargins(0, 0, delegate.dpToPx(endMarginDp), 0);
        chip.setOnClickListener(listener);
    }

    private void configureHashtagChip(TextView chip, String label, String rawTag) {
        chip.setText(label);
        chip.setTag(rawTag);
        chip.setBackgroundResource(R.drawable.bg_speed_chip);
        int dp8 = delegate.dpToPx(8);
        int dp4 = delegate.dpToPx(4);
        chip.setPadding(dp8, dp4, dp8, dp4);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) chip.getLayoutParams();
        lp.setMargins(0, 0, dp8, 0);
        chip.setOnClickListener(hashtagClickListener);
    }

    // ── Click listener wiring ─────────────────────────────────────────────

    public void setupClickListeners(View root) {
        // ── Instagram-style unified gesture handler ────────────────────────
        // Uses GestureDetector so single-tap, double-tap, and long-press are
        // mutually exclusive — no accidental play/pause toggle on double-tap.
        //
        //  • Single tap confirmed (300ms delay) → play / pause
        //  • Double tap (immediate)             → like animation
        //  • Long press (hold)                  → pause playback while held,
        //    resume on ACTION_UP / ACTION_CANCEL (only if this gesture is what
        //    paused it). Hiding the overlay UI is now a 3-dot menu action
        //    (Cinema Mode) instead — see toggleCinemaMode().
        //
        // The touch listener returns false for MOVE/CANCEL so ViewPager2's
        // RecyclerView can still intercept scroll gestures normally.

        android.view.GestureDetector gd = new android.view.GestureDetector(
            delegate.requireContext(),
            new android.view.GestureDetector.SimpleOnGestureListener() {

                @Override
                public boolean onDown(android.view.MotionEvent e) {
                    // Must return true so GestureDetector continues tracking this gesture
                    return true;
                }

                @Override
                public boolean onSingleTapConfirmed(android.view.MotionEvent e) {
                    // Fires ~300ms after tap only when no second tap arrived
                    if (delegate.isAdded()) {
                        delegate.hideReactions();
                        // Guard matches the old PlayerView click-listener behavior:
                        // don't toggle play/pause while docked above the open
                        // comments sheet (that tap is owned by the sheet's
                        // touchOutside overlay instead).
                        if (!delegate.isDocked()) {
                            delegate.togglePlayPause();
                        }
                    }
                    return true;
                }

                @Override
                public boolean onDoubleTap(android.view.MotionEvent e) {
                    // Fires immediately on second tap — cancels pending single-tap
                    if (delegate.isAdded()) {
                        if (!delegate.isLiked()) delegate.toggleLike();
                        delegate.showLikeAnimation();
                    }
                    return true;
                }

                @Override
                public void onLongPress(android.view.MotionEvent e) {
                    // Instagram-style: hold to pause. Photo-mode reels have
                    // their own hold-to-pause gesture on the photo ViewPager
                    // (ReelPhotoSlideshowController) so this is video-only.
                    // Skip if already paused (manually or by an earlier hold)
                    // so we don't mark ourselves as the one who paused it.
                    if (delegate.isAdded() && !delegate.isDocked()
                            && !delegate.isPhotoMode() && !pausedByLongPress
                            && delegate.isPlaybackActive()) {
                        delegate.pausePlayback();
                        pausedByLongPress = true;
                    }
                }
            });
        gd.setIsLongpressEnabled(true);

        // Remove legacy separate click/long-click listeners — GestureDetector owns them now
        root.setOnClickListener(null);
        root.setOnLongClickListener(null);

        root.setOnTouchListener((v, event) -> {
            boolean handled = gd.onTouchEvent(event);
            int action = event.getActionMasked();
            // Resume playback when the finger lifts, but only if this same
            // long-press paused it — a manual pre-existing pause (single tap)
            // should stay paused after release, matching Instagram.
            if ((action == android.view.MotionEvent.ACTION_UP
                    || action == android.view.MotionEvent.ACTION_CANCEL)
                    && pausedByLongPress) {
                pausedByLongPress = false;
                if (delegate.isAdded()) delegate.resumePlayback();
            }
            // Return true for ACTION_DOWN so GestureDetector continues tracking;
            // return false for MOVE events so ViewPager2 can intercept scrolls.
            return action == android.view.MotionEvent.ACTION_DOWN || handled;
        });

        // Buttons
        if (btnComment  != null) btnComment.setOnClickListener(v -> delegate.openComments());
        if (btnShare    != null) btnShare.setOnClickListener(v -> delegate.shareReel());
        if (btnMore     != null) btnMore.setOnClickListener(v -> delegate.showMoreOptions());
        if (btnDownload != null) btnDownload.setOnClickListener(v -> delegate.downloadReel());
        if (tvMusicName != null) tvMusicName.setOnClickListener(v -> delegate.openSoundDetail());
        if (ivMusicDisc != null) ivMusicDisc.setOnClickListener(v -> delegate.openSoundDetail());
        if (ivOwnerAvatar != null) ivOwnerAvatar.setOnClickListener(v -> delegate.openUserReels());
        if (tvOwnerName   != null) tvOwnerName.setOnClickListener(v -> delegate.openUserReels());
        // Instagram parity: tapping the caption/reel-name opens the same
        // comments bottom sheet as tapping the comment count (docked-video
        // sheet, video slides up) instead of the old standalone details
        // card — with the caption/owner row shown at the top of that sheet.
        if (tvCaption     != null) tvCaption.setOnClickListener(v -> delegate.openCommentsSheetWithCaption());
        if (ivOwnerStoryRing != null) ivOwnerStoryRing.setOnClickListener(v -> delegate.openOwnerStatus());
    }

    /**
     * Opens the compact reel-details card from the tappable reel name/caption.
     * The values come from ReelModel so old and newly uploaded reels behave
     * consistently without a second database request.
     */
    private void showReelDetailsCard() {
        if (!delegate.isAdded() || delegate.getContext() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null) return;

        String title = !TextUtils.isEmpty(reel.caption)
            ? reel.caption.split("\\R", 2)[0].trim()
            : "Untitled reel";
        String description = !TextUtils.isEmpty(reel.caption)
            ? reel.caption : "No description";
        String uploaded = reel.timestamp > 0
            ? new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
                .format(new Date(reel.timestamp))
            : "Not available";
        String audio = !TextUtils.isEmpty(reel.musicName)
            ? reel.musicName
            : "Original audio";
        String duration = reel.duration > 0
            ? String.format(Locale.getDefault(), "%d:%02d",
                reel.duration / 60, reel.duration % 60)
            : "Not available";
        String size = reel.width > 0 && reel.height > 0
            ? reel.width + " × " + reel.height : "Not available";

        // Everything EXCEPT description — description gets its own
        // truncate/expand block below so a long caption doesn't push the
        // Uploaded/Audio/Duration/etc. rows out of view.
        String metaDetails = "Uploaded\n" + uploaded
            + "\n\nAudio\n" + audio
            + "\n\nDuration\n" + duration
            + "\n\nSize\n" + size
            + "\n\nViews  " + delegate.formatCount(reel.viewsCount)
            + "   Likes  " + delegate.formatCount(reel.likesCount)
            + "\nComments  " + delegate.formatCount(reel.commentsCount)
            + "   Shares  " + delegate.formatCount(reel.sharesCount);

        android.widget.LinearLayout container = new android.widget.LinearLayout(delegate.requireContext());
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.setPadding(24, 8, 24, 8);

        // ── Description label ───────────────────────────────────────────
        TextView descLabel = new TextView(delegate.requireContext());
        descLabel.setText("Description");
        descLabel.setTextColor(0xFF222222);
        descLabel.setTextSize(15);
        descLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        container.addView(descLabel);

        // ── Description body — starts truncated to a few lines ─────────
        final int COLLAPSED_MAX_LINES = 3;
        final TextView descText = new TextView(delegate.requireContext());
        descText.setText(description);
        descText.setTextColor(0xFF222222);
        descText.setTextSize(15);
        descText.setLineSpacing(0f, 1.12f);
        descText.setPadding(0, 4, 0, 0);
        descText.setMaxLines(COLLAPSED_MAX_LINES);
        descText.setEllipsize(TextUtils.TruncateAt.END);
        container.addView(descText);

        // ── "Read more" / "Read less" toggle — only shown if the
        // description actually overflows COLLAPSED_MAX_LINES once laid out.
        final TextView readMoreToggle = new TextView(delegate.requireContext());
        readMoreToggle.setText("Read more");
        readMoreToggle.setTextColor(0xFF2E7D32); // matches AlertDialogStyler's primary green
        readMoreToggle.setTextSize(14);
        readMoreToggle.setTypeface(null, android.graphics.Typeface.BOLD);
        readMoreToggle.setPadding(0, delegate.dpToPx(6), 0, 0);
        readMoreToggle.setVisibility(View.GONE);
        readMoreToggle.setOnClickListener(v -> {
            boolean isCollapsed = descText.getMaxLines() == COLLAPSED_MAX_LINES;
            if (isCollapsed) {
                descText.setMaxLines(Integer.MAX_VALUE);
                descText.setEllipsize(null);
                readMoreToggle.setText("Read less");
            } else {
                descText.setMaxLines(COLLAPSED_MAX_LINES);
                descText.setEllipsize(TextUtils.TruncateAt.END);
                readMoreToggle.setText("Read more");
            }
        });
        container.addView(readMoreToggle);

        // Reveal the toggle only once the TextView has actually laid out
        // and we know whether it truncated — avoids showing "Read more"
        // on short descriptions that already fit in COLLAPSED_MAX_LINES.
        //
        // PERF: this used to be a ViewTreeObserver.OnPreDrawListener, which
        // hooks into the draw traversal itself — registered, invoked as
        // part of that pass, then torn back down, every single time this
        // dialog opens. A plain View.post() gives the same "run after this
        // view has laid out" guarantee here (descText is already attached
        // to the dialog window by the time this runs) without touching the
        // ViewTreeObserver at all — one queued Runnable instead of a
        // pre-draw hook. Cheaper for a dialog that reopens often (every
        // reel's "i" info sheet).
        descText.post(() -> {
            if (descText.getLineCount() > COLLAPSED_MAX_LINES
                    || descText.getLayout() != null
                       && descText.getLayout().getEllipsisCount(COLLAPSED_MAX_LINES - 1) > 0) {
                readMoreToggle.setVisibility(View.VISIBLE);
            }
        });

        // ── Divider + the rest of the metadata (unaffected by expand) ──
        View divider = new View(delegate.requireContext());
        android.widget.LinearLayout.LayoutParams dividerLp = new android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 2);
        dividerLp.topMargin = delegate.dpToPx(14);
        dividerLp.bottomMargin = delegate.dpToPx(10);
        divider.setLayoutParams(dividerLp);
        divider.setBackgroundColor(0x1F000000);
        container.addView(divider);

        TextView metaText = new TextView(delegate.requireContext());
        metaText.setText(metaDetails);
        metaText.setTextColor(0xFF222222);
        metaText.setTextSize(15);
        metaText.setLineSpacing(0f, 1.12f);
        container.addView(metaText);

        ScrollView detailsScroll = new ScrollView(delegate.requireContext());
        detailsScroll.setFillViewport(true);
        detailsScroll.addView(container, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        int maxDialogHeight = (int) (delegate.requireContext().getResources()
            .getDisplayMetrics().heightPixels * 0.48f);
        detailsScroll.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, maxDialogHeight));

        AlertDialogStyler.showRounded(new AlertDialog.Builder(delegate.requireContext())
            .setTitle("@" + (TextUtils.isEmpty(reel.ownerName) ? "user" : reel.ownerName)
                + " · " + title)
            .setView(detailsScroll)
            .setPositiveButton("Close", null)
            .create());
    }

    // ── Legacy sound-cover backfill ─────────────────────────────────────
    // Reels posted before the musicCoverUrl fix have that field missing in
    // Firebase even though they DO have a valid musicId. Rather than a full
    // data migration, fetch the cover once from sounds/{musicId} at render
    // time and bind it in — cached per musicId so repeat reels with the same
    // sound don't re-fetch.
    private static final java.util.Map<String, String> soundCoverCache = new java.util.HashMap<>();
    private static final Set<String> soundCoverFetchInFlight = new HashSet<>();

    private void fetchAndBindMissingSoundCover(ReelModel reel) {
        String musicId = reel.musicId;
        String cached = soundCoverCache.get(musicId);
        if (cached != null) {
            if (!cached.isEmpty()) bindMusicCover(reel, cached);
            return;
        }
        if (!soundCoverFetchInFlight.add(musicId)) return; // already fetching

        FirebaseDatabase.getInstance(com.callx.app.utils.Constants.DB_URL)
            .getReference("sounds").child(musicId).child("coverUrl")
            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                @Override public void onDataChange(@androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snap) {
                    String coverUrl = snap.getValue(String.class);
                    soundCoverCache.put(musicId, coverUrl != null ? coverUrl : "");
                    soundCoverFetchInFlight.remove(musicId);
                    if (coverUrl != null && !coverUrl.isEmpty()) {
                        bindMusicCover(reel, coverUrl);
                    }
                }
                @Override public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError e) {
                    soundCoverFetchInFlight.remove(musicId);
                }
            });
    }

    /** Sets reel.musicCoverUrl (so future binds don't re-fetch) and refreshes both music views if still on screen. */
    private void bindMusicCover(ReelModel reel, String coverUrl) {
        reel.musicCoverUrl = coverUrl;
        if (!delegate.isAdded() || delegate.getContext() == null) return;
        android.content.Context ctx = delegate.requireContext();
        if (btnCreateAudio != null) {
            int sizePx = AvatarUrlBuilder.tierPx(ctx, AvatarSizeTier.TINY); // 28dp view → shared TINY tier
            Glide.with(ctx).load(AvatarUrlBuilder.build(ctx, coverUrl, AvatarSizeTier.TINY))
                .apply(new RequestOptions().centerCrop()
                    .override(sizePx, sizePx)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .placeholder(R.drawable.ic_audio))
                .into(btnCreateAudio);
        }
        if (ivMusicDisc != null) {
            int discSizePx = AvatarUrlBuilder.tierPx(ctx, AvatarSizeTier.TINY); // iv_music_disc 22dp → shared TINY tier
            Glide.with(ctx).load(AvatarUrlBuilder.build(ctx, coverUrl, AvatarSizeTier.TINY))
                .apply(new RequestOptions().circleCrop()
                    .override(discSizePx, discSizePx)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .placeholder(R.drawable.ic_music_note))
                .into(ivMusicDisc);
        }
    }

    // ── Music disc animation ──────────────────────────────────────────────

    public void startDiscAnimation() {
        // PERF: keep the bio/collab music-name ticker's infinite scroll
        // animator in lockstep with real playback — this is called exactly
        // when a reel becomes the active/visible one (ReelPlayerFragment#
        // applyVisibleState) or resumes from a long-press pause, so there's
        // no reason for an off-screen or paused reel's ticker to keep
        // animating + redrawing in the background.
        if (tvBioSongName != null) tvBioSongName.resume();
        if (tvCollabSongName != null) tvCollabSongName.resume();

        if (ivMusicDisc == null) return;
        if (discAnimator != null) { discAnimator.cancel(); discAnimator = null; }
        // PERF: this is an infinite rotation running for as long as the reel
        // is on screen — was being redrawn via the CPU/software-render path
        // every frame. HARDWARE layer caches the view as a GPU texture and
        // just rotates that texture, so the rotation itself costs ~nothing
        // per frame instead of a full re-draw + re-rasterize each tick.
        ivMusicDisc.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        discAnimator = ObjectAnimator.ofFloat(ivMusicDisc, "rotation", 0f, 360f);
        discAnimator.setDuration(3000);
        discAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        discAnimator.setRepeatMode(ObjectAnimator.RESTART);
        discAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        discAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                revertDiscLayerType();
            }
            @Override public void onAnimationCancel(android.animation.Animator animation) {
                revertDiscLayerType();
            }
        });
        discAnimator.start();
    }

    public void stopDiscAnimation() {
        if (tvBioSongName != null) tvBioSongName.pause();
        if (tvCollabSongName != null) tvCollabSongName.pause();
        if (discAnimator != null) discAnimator.pause();
    }

    /** Drops the disc ImageView back to the default (NONE) layer type once rotation stops/cancels — a hardware layer left on an idle view just wastes GPU texture memory for no benefit. */
    private void revertDiscLayerType() {
        if (ivMusicDisc != null) ivMusicDisc.setLayerType(View.LAYER_TYPE_NONE, null);
    }

    // ── Cinema Mode ───────────────────────────────────────────────────────

    private void openCinemaSheet() {
        if (!delegate.isAdded() || delegate.getContext() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null) return;
        boolean currentlyHidden = reel.reelId != null && cinemaHiddenReels.contains(reel.reelId);
        com.callx.app.feed.ReelCinemaSheet sheet = com.callx.app.feed.ReelCinemaSheet.newInstance(currentlyHidden);
        sheet.setListener(this::toggleCinemaMode);
        sheet.show(delegate.getChildFragmentManager(), com.callx.app.feed.ReelCinemaSheet.TAG);
    }

    public void toggleCinemaMode() {
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.reelId == null) return;
        if (cinemaHiddenReels.contains(reel.reelId)) {
            cinemaHiddenReels.remove(reel.reelId);
        } else {
            cinemaHiddenReels.add(reel.reelId);
        }
        View root = delegate.getFragment().getView();
        if (root != null) applyCinemaState(root);
    }

    /** True if Cinema Mode (hidden overlay UI) is currently on for the current reel. */
    public boolean isCinemaModeOn() {
        ReelModel reel = delegate.getReel();
        return reel != null && reel.reelId != null && cinemaHiddenReels.contains(reel.reelId);
    }

    public void applyCinemaState(View root) {
        if (root == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.reelId == null) return;
        isUiHidden = cinemaHiddenReels.contains(reel.reelId);
        int vis = isUiHidden ? View.INVISIBLE : View.VISIBLE;

        View rightActions = root.findViewById(R.id.right_actions);
        View bottomInfo   = root.findViewById(R.id.bottom_info);
        View topControls  = root.findViewById(R.id.top_controls);
        View progressVid  = root.findViewById(R.id.progress_video);
        View repostAttr   = root.findViewById(R.id.ll_repost_attribution);
        View repostChip   = root.findViewById(R.id.ll_repost_count_chip);
        View seriesChip   = root.findViewById(R.id.ll_series_chip);
        View likers       = root.findViewById(R.id.ll_likers_avatar_row);

        if (rightActions != null) rightActions.setVisibility(vis);
        if (bottomInfo   != null) bottomInfo.setVisibility(vis);
        if (topControls  != null) topControls.setVisibility(vis);
        if (progressVid  != null) progressVid.setVisibility(vis);
        if (repostAttr != null && repostAttr.getVisibility() != View.GONE) repostAttr.setVisibility(vis);
        if (repostChip != null && repostChip.getVisibility() != View.GONE) repostChip.setVisibility(vis);
        if (seriesChip != null && seriesChip.getVisibility() != View.GONE) seriesChip.setVisibility(vis);
        if (likers     != null && likers.getVisibility()     != View.GONE) likers.setVisibility(vis);
    }

    // ── Release ───────────────────────────────────────────────────────────

    // Logic for pinned comments added here

    public void setupPinnedComment(ReelModel reel) {
        if (reelPinnedCommentContainer == null) return;
        if (reel.pinnedCommentId == null || reel.pinnedCommentId.isEmpty()) {
            reelPinnedCommentContainer.setVisibility(View.GONE);
            return;
        }

        if (!reel.pinnedCommentText.isEmpty()) {
            populatePinnedCommentUi(reel);
        } else {
            // Fetch from Firebase
            FirebaseDatabase.getInstance(com.callx.app.utils.Constants.DB_URL)
                .getReference("reelComments").child(reel.reelId).child(reel.pinnedCommentId)
                .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                    @Override
                    public void onDataChange(@androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snap) {
                        if (!delegate.isAdded()) return;
                        com.callx.app.models.ReelComment c = snap.getValue(com.callx.app.models.ReelComment.class);
                        if (c != null) {
                            reel.pinnedCommentText = c.text;
                            reel.pinnedCommentAuthorName = c.ownerName;
                            reel.pinnedCommentAuthorAvatar = c.ownerPhoto;
                            reel.pinnedCommentLikes = c.likesCount;
                            populatePinnedCommentUi(reel);
                        }
                    }
                    @Override public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError e) {}
                });
        }

        reelPinnedCommentContainer.setOnClickListener(v -> {
            Intent intent = new Intent(delegate.requireContext(), com.callx.app.comments.ReelCommentActivity.class);
            intent.putExtra(com.callx.app.comments.ReelCommentActivity.EXTRA_REEL_ID, reel.reelId);
            intent.putExtra(com.callx.app.comments.ReelCommentActivity.EXTRA_REEL_UID, reel.uid);
            intent.putExtra("EXTRA_HIGHLIGHT_COMMENT_ID", reel.pinnedCommentId);
            delegate.getFragment().startActivity(intent);
        });
    }

    private void populatePinnedCommentUi(ReelModel reel) {
        reelPinnedCommentContainer.setVisibility(View.VISIBLE);
        if (tvPinnedAuthor != null) tvPinnedAuthor.setText(reel.pinnedCommentAuthorName);
        if (tvPinnedText != null) tvPinnedText.setText(reel.pinnedCommentText);
        if (tvPinnedLikes != null) tvPinnedLikes.setText(String.valueOf(reel.pinnedCommentLikes));
        if (ivPinnedAvatar != null && delegate.isAdded()) {
            Glide.with(delegate.requireContext())
                .load(reel.pinnedCommentAuthorAvatar)
                .apply(new RequestOptions().circleCrop().placeholder(R.drawable.ic_person))
                .listener(AvatarCacheAnalytics.glideListener(delegate.requireContext())) // CDN/cache-tier split — same analytics as owner avatar
                .into(ivPinnedAvatar);
        }
    }
    public void release() {
        uiHandler.removeCallbacksAndMessages(null);
        singleTapHandler.removeCallbacksAndMessages(null);
        pendingSingleTap = null;
        pausedByLongPress = false;
        pendingOwnerAvatarUrl = null; // don't let a deferred load leak into the next recycled reel
        avatarLoadInFlight = false;   // don't let a stale in-flight flag leak into the next recycled reel
        detachAvatarVersionWatch();   // safety net — onBecameInvisible should already have done this, but a recycled ViewHolder must never carry a live listener into its next bind
        if (discAnimator != null) { discAnimator.cancel(); discAnimator = null; } // triggers onAnimationCancel -> revertDiscLayerType()
        if (tvBioSongName != null) tvBioSongName.release();
        if (tvCollabSongName != null) tvCollabSongName.release();
        if (containerHashtags != null) containerHashtags.recycleChildren();
    }

}
