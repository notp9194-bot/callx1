package com.callx.app.activities;

import android.content.Context;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.Downsampler;
import com.bumptech.glide.request.RequestOptions;
import com.github.chrisbanes.photoview.PhotoView;
import com.callx.app.utils.ChatMediaGalleryBuilder;
import com.callx.app.utils.E2eeDecryptExecutor;
import com.callx.app.utils.HighResImageDecoder;
import com.callx.app.utils.LocalMediaAvailability;
import com.callx.app.utils.MediaCache;
import com.callx.app.utils.MediaE2ECrypto;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Backs the ViewPager2 in MediaViewerActivity for grouped/multi-media
 * messages — one page per image/video, swipe left/right to move between
 * them (mirrors WhatsApp / Instagram's grouped-media viewer).
 *
 * Each page lazily builds its own PhotoView (image) or ExoPlayer+PlayerView
 * (video). Video players are created on bind and released on recycle /
 * page-away so only the currently-visible video keeps decoding.
 *
 * AUTO-DOWNLOAD GATE (bug fix): the chat bubble shows a manual "Download"
 * pill for a RECEIVED photo/video the user hasn't downloaded yet — but this
 * adapter used to Glide-load / stream / MediaCache.get() every bound page
 * regardless, so simply swiping (or the neighbour pre-bind from
 * offscreenPageLimit=1, or MediaViewerActivity#prefetchAdjacentMedia) fetched
 * media the user had deliberately not downloaded. Every page is now resolved
 * through {@link #resolveSource} first:
 *   - SENT by me                      -> loads exactly as before
 *   - already on device (MediaCache
 *     file or a live mediaLocalPath)  -> loads from the device, no network
 *   - explicitly opened / tapped
 *     "Download" in here              -> loads (see {@link #allowLoad})
 *   - anything else (received, not
 *     downloaded)                     -> shows a tap-to-download page and
 *                                        touches NO network for the media
 */
public class GalleryPagerAdapter extends RecyclerView.Adapter<GalleryPagerAdapter.PageVH> {

    // PERF (memory/decode speed): full-screen viewer photos don't need
    // alpha, and RGB_565 halves per-pixel memory (2 bytes vs 4) versus the
    // ARGB_8888 default, with a faster decode since there's a quarter as
    // much data to write. Any very-low-color-depth banding this can cause
    // is imperceptible on real photos at full-screen viewing distance, and
    // this is exactly the format WhatsApp/Instagram viewers use for the
    // same reason. Shared instance — RequestOptions is immutable-safe to
    // reuse across every Glide call below.
    //
    // PERF (hardware bitmaps, one step past RGB_565): ALLOW_HARDWARE_CONFIG
    // lets Glide hand back an ARGB_8888/HARDWARE Bitmap backed directly by
    // GPU memory (API 26+) instead of a Java-heap Bitmap — the decoded
    // pixels never cross into normal heap at all, and drawing is a
    // zero-copy GPU blit instead of a CPU upload-then-draw each frame.
    // Glide already restricts this to safe cases on its own (falls back to
    // RGB_565 automatically below API 26, or wherever a transformation
    // needs pixel-level access), so it's safe to request unconditionally
    // here — nothing in this adapter's Glide calls needs software pixel
    // access to the final Bitmap.
    private static final RequestOptions RGB_565 =
            RequestOptions.formatOf(DecodeFormat.PREFER_RGB_565)
                    .set(Downsampler.ALLOW_HARDWARE_CONFIG, true);

    // PERF (region decoding): single background thread for HighResImageDecoder
    // work. Oversized images are rare, so one thread is plenty and keeps this
    // off Glide's own executors/MediaCache's download pool entirely.
    private static final ExecutorService REGION_DECODE_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final android.os.Handler MAIN_HANDLER = new android.os.Handler(android.os.Looper.getMainLooper());

    public interface TapListener { void onTap(); }
    /** #1 fix — long-press a page to enter multi-select mode (forward/delete/star). */
    public interface LongPressListener { void onLongPress(int position); }
    /** Fired when the user taps a page's selection checkbox while already in select mode. */
    public interface SelectionToggleListener { void onToggle(int position); }

    // ── Auto-download gate state (see class doc) ────────────────────────
    private static final int SRC_REMOTE_OK = 0; // sent / already cached / user-approved — original load path
    private static final int SRC_LOCAL     = 1; // received, but the device still has it via mediaLocalPath
    private static final int SRC_GATED     = 2; // received + not downloaded -> tap-to-download page

    // Which uid is "me" (item senderId == currentUid => sent). Falls back to
    // defaultSent (the Intent's isOwnMessage) for items that carry no senderId,
    // e.g. the single-message grouped-media JSON path.
    private String currentUid;
    private boolean defaultSent;
    // URLs the user explicitly opted into: the item the viewer was opened on
    // (they tapped it) plus anything they've tapped "Download" on in here.
    private final Set<String> allowedUrls = new HashSet<>();
    // url -> last progress % for downloads started from a gated page. Main thread only.
    private final Map<String, Integer> downloadingUrls = new HashMap<>();
    // Set when a Download-pill download finishes on the ACTIVE video page: the rebind that
    // follows builds a fresh (paused) player, and onPageSelected won't fire again, so
    // onBindViewHolder starts playback for exactly that one rebind.
    private Map<String, Object> pendingAutoPlayItem;

    private final List<Map<String, Object>> items;
    private final TapListener tapListener;
    private LongPressListener longPressListener;
    private SelectionToggleListener selectionToggleListener;

    private boolean selectMode = false;
    private final java.util.Set<Integer> selectedPositions = new java.util.HashSet<>();

    // PERF (priority tuning): which page is currently on-screen, set by
    // MediaViewerActivity. Drives Glide request priority in bindImage() —
    // previously every page (current AND the neighbor kept alive by
    // offscreenPageLimit=1 / prefetch) queued its image load at the same
    // default priority, so a neighbor's request could contend with and
    // delay the actually-visible page's own load on Glide's shared
    // network/decode executors.
    private int activePosition = RecyclerView.NO_POSITION;

    /** Called by MediaViewerActivity whenever the visible page changes. */
    public void setActivePosition(int position) {
        activePosition = position;
    }

    /** Uid of the signed-in user — lets the adapter tell sent items from received ones per item. */
    public void setCurrentUid(String uid) { this.currentUid = uid; }

    /** Sent/received verdict for items that carry no senderId of their own (single-message JSON gallery). */
    public void setDefaultSent(boolean sent) { this.defaultSent = sent; }

    /** Marks {@code url} as user-approved so it loads normally even though it isn't downloaded yet. */
    public void allowLoad(String url) {
        if (url != null && !url.isEmpty()) allowedUrls.add(url);
    }

    /**
     * True only when {@code position}'s media would load straight from the
     * network on its own (sent by me / user-approved) — i.e. it's safe for
     * MediaViewerActivity#prefetchAdjacentMedia to warm it. Received items the
     * user hasn't downloaded return false, so swiping toward them never
     * triggers a background download.
     */
    public boolean mayPrefetch(Context ctx, int position) {
        if (items == null || position < 0 || position >= items.size()) return false;
        Map<String, Object> item = items.get(position);
        return resolveSource(ctx, item, safeStr(item.get("url"))) == SRC_REMOTE_OK;
    }

    public GalleryPagerAdapter(List<Map<String, Object>> items, TapListener tapListener) {
        this.items = items;
        this.tapListener = tapListener;
    }

    public void setLongPressListener(LongPressListener l) { this.longPressListener = l; }
    public void setSelectionToggleListener(SelectionToggleListener l) { this.selectionToggleListener = l; }

    /** #1 — enables/disables the checkbox overlay + tap-to-toggle behavior. */
    public void setSelectMode(boolean enabled) {
        if (selectMode == enabled) return;
        selectMode = enabled;
        if (!enabled) selectedPositions.clear();
        notifyDataSetChanged();
    }

    public boolean isSelectMode() { return selectMode; }

    public void toggleSelected(int position) {
        if (selectedPositions.contains(position)) selectedPositions.remove(position);
        else selectedPositions.add(position);
        notifyItemChanged(position);
    }

    public java.util.List<Integer> getSelectedPositions() {
        return new java.util.ArrayList<>(selectedPositions);
    }

    public int getSelectedCount() { return selectedPositions.size(); }

    @NonNull @Override
    public PageVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Context ctx = parent.getContext();

        FrameLayout root = new FrameLayout(ctx);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        PhotoView photoView = new PhotoView(ctx);
        photoView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        photoView.setScaleType(PhotoView.ScaleType.FIT_CENTER);
        root.addView(photoView);

        // BUG FIX (black video): was `new PlayerView(ctx)` = default SurfaceView, which
        // renders black under the pager's hardware-layer / scale / alpha / outline-clip
        // animations. Inflated from a layout so surface_type=texture_view can be set
        // (a PlayerView can't switch surface type after construction).
        PlayerView playerView = (PlayerView) android.view.LayoutInflater.from(ctx)
                .inflate(com.callx.app.R.layout.view_gallery_player, root, false);
        playerView.setVisibility(View.GONE);
        root.addView(playerView);

        ProgressBar spinner = new ProgressBar(ctx);
        FrameLayout.LayoutParams spinnerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        spinnerLp.gravity = android.view.Gravity.CENTER;
        spinner.setLayoutParams(spinnerLp);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        spinner.setVisibility(View.GONE);
        root.addView(spinner);

        // #1 — selection checkbox (top-right), shown only in select mode
        android.widget.CheckBox checkbox = new android.widget.CheckBox(ctx);
        FrameLayout.LayoutParams cbLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cbLp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        int cbMargin = (int) (12 * ctx.getResources().getDisplayMetrics().density);
        cbLp.setMargins(0, cbMargin, cbMargin, 0);
        checkbox.setLayoutParams(cbLp);
        checkbox.setButtonTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        checkbox.setVisibility(View.GONE);
        checkbox.setClickable(false); // tap handled by root so the whole page toggles, not just the tiny box
        root.addView(checkbox);

        // #2 — per-item caption overlay (bottom), shown only when this item
        // has its own caption distinct from the group-level caption.
        android.widget.TextView tvCaption = new android.widget.TextView(ctx);
        FrameLayout.LayoutParams capLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        capLp.gravity = android.view.Gravity.BOTTOM;
        capLp.setMargins(0, 0, 0, (int) (64 * ctx.getResources().getDisplayMetrics().density));
        tvCaption.setLayoutParams(capLp);
        tvCaption.setTextColor(0xFFFFFFFF);
        tvCaption.setTextSize(15f);
        tvCaption.setPadding(
                (int) (16 * ctx.getResources().getDisplayMetrics().density), (int) (8 * ctx.getResources().getDisplayMetrics().density),
                (int) (16 * ctx.getResources().getDisplayMetrics().density), (int) (8 * ctx.getResources().getDisplayMetrics().density));
        tvCaption.setBackgroundColor(0x66000000);
        tvCaption.setVisibility(View.GONE);
        root.addView(tvCaption);

        // Tap-to-download pill — only visible on a gated page (received media the
        // user hasn't downloaded). Centered, same dark-glass look as the caption strip.
        float density = ctx.getResources().getDisplayMetrics().density;
        android.widget.TextView pill = new android.widget.TextView(ctx);
        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillLp.gravity = android.view.Gravity.CENTER;
        pill.setLayoutParams(pillLp);
        pill.setTextColor(0xFFFFFFFF);
        pill.setTextSize(15f);
        pill.setTypeface(null, android.graphics.Typeface.BOLD);
        pill.setGravity(android.view.Gravity.CENTER);
        pill.setPadding((int) (22 * density), (int) (12 * density), (int) (22 * density), (int) (12 * density));
        android.graphics.drawable.GradientDrawable pillBg = new android.graphics.drawable.GradientDrawable();
        pillBg.setColor(0xB3000000);
        pillBg.setCornerRadius(28 * density);
        pill.setBackground(pillBg);
        pill.setVisibility(View.GONE);
        root.addView(pill);

        return new PageVH(root, photoView, playerView, spinner, checkbox, tvCaption, pill);
    }

    @Override
    public void onBindViewHolder(@NonNull PageVH h, int position) {
        Map<String, Object> item = items.get(position);
        String url      = safeStr(item.get("url"));
        String thumbUrl = safeStr(item.get("thumbUrl"));
        boolean isVideo = "video".equals(item.get("mediaType"));

        // #8 — accessibility content description per page
        h.root.setContentDescription((isVideo ? "Video" : "Photo") + " " + (position + 1) + " of " + items.size());

        // #1 — selection mode: tap toggles checkbox instead of the normal
        // tap-to-toggle-toolbar behavior; long-press always enters select mode.
        h.checkbox.setVisibility(selectMode ? View.VISIBLE : View.GONE);
        h.checkbox.setChecked(selectedPositions.contains(position));

        h.root.setOnClickListener(v -> {
            // BUG FIX: this used to also call tapListener.onTap() (→
            // toggleUI()) here when not in select mode. MediaViewerActivity
            // now toggles the top bar itself via a dispatchTouchEvent-level
            // GestureDetector (see its class doc) that sees every tap
            // regardless of whether it lands on an image or a video page —
            // calling it a second time from here would double-toggle a
            // video-page tap (immediate here, then again from that
            // detector), which nets out to no visible change at all. Select
            // mode still toggles the item's checkbox from right here, same
            // as before.
            if (selectMode) {
                toggleSelected(position);
                if (selectionToggleListener != null) selectionToggleListener.onToggle(position);
            }
        });
        h.root.setOnLongClickListener(v -> {
            if (longPressListener != null) longPressListener.onLongPress(position);
            return true;
        });

        // #2 — per-item caption, falls back to hidden if this item has none
        Object captionObj = item.get("caption");
        if (captionObj instanceof String && !((String) captionObj).isEmpty()) {
            h.tvCaption.setText((String) captionObj);
            h.tvCaption.setVisibility(View.VISIBLE);
        } else {
            h.tvCaption.setVisibility(View.GONE);
        }

        h.boundItem = item;
        int src = resolveSource(h.root.getContext(), item, url);

        // BUG FIX (swipe auto-download): a received item the user hasn't
        // downloaded gets a tap-to-download page instead of being fetched.
        if (src == SRC_GATED) {
            bindGate(h, item, url, thumbUrl, isVideo);
            return;
        }
        hideGate(h);

        if (isVideo) {
            h.photoView.setVisibility(View.GONE);
            h.playerView.setVisibility(View.VISIBLE);
            h.playerView.setOnClickListener(v -> h.root.callOnClick());
            h.playerView.setOnLongClickListener(v -> { h.root.performLongClick(); return true; });
            bindVideo(h, url, src == SRC_LOCAL ? safeStr(item.get(ChatMediaGalleryBuilder.KEY_LOCAL_PATH)) : null);
            if (pendingAutoPlayItem == item) {
                pendingAutoPlayItem = null;
                if (position == activePosition && h.player != null) h.player.setPlayWhenReady(true);
            }
        } else {
            h.playerView.setVisibility(View.GONE);
            h.photoView.setVisibility(View.VISIBLE);
            // PERF (priority tuning): current page loads IMMEDIATE, every
            // other bound page (neighbor kept alive by offscreenPageLimit=1)
            // loads LOW.
            Priority priority = (position == activePosition) ? Priority.IMMEDIATE : Priority.LOW;
            if (src == SRC_LOCAL) {
                bindLocalImage(h, safeStr(item.get(ChatMediaGalleryBuilder.KEY_LOCAL_PATH)), priority);
            } else {
                bindImage(h, url, thumbUrl, priority);
            }
        }
    }

    // ── Auto-download gate ───────────────────────────────────────────────

    private boolean isSent(Map<String, Object> item) {
        String sender = safeStr(item.get(ChatMediaGalleryBuilder.KEY_SENDER_ID));
        if (!sender.isEmpty() && currentUid != null && !currentUid.isEmpty()) {
            return currentUid.equals(sender);
        }
        return defaultSent;
    }

    /**
     * Mirrors the chat bubble's own "is this downloaded?" test (image bubble:
     * MediaCache.getCached; video bubble: mediaLocalPath-available OR
     * MediaCache.getCached; sent bubbles: never gated).
     */
    private int resolveSource(Context ctx, Map<String, Object> item, String url) {
        if (url.isEmpty()) return SRC_REMOTE_OK;
        if (isSent(item)) return SRC_REMOTE_OK;
        if (allowedUrls.contains(url)) return SRC_REMOTE_OK;
        if (MediaCache.getCached(ctx, url) != null) return SRC_REMOTE_OK;
        String lp = safeStr(item.get(ChatMediaGalleryBuilder.KEY_LOCAL_PATH));
        if (!lp.isEmpty() && LocalMediaAvailability.isAvailable(ctx, lp)) return SRC_LOCAL;
        return SRC_GATED;
    }

    // ── Black-screen protection ──────────────────────────────────────────
    // Glide/ExoPlayer failing used to be completely silent: the page just stayed
    // black. Now every failure lands in onPageMediaFailed(), which self-heals
    // where it safely can and otherwise shows a "Couldn't load · Tap to retry" pill.
    private static final int LOAD_CACHE  = 0; // played/decoded from a MediaCache file
    private static final int LOAD_LOCAL  = 1; // played/decoded from the device's mediaLocalPath Uri
    private static final int LOAD_REMOTE = 2; // streamed/decoded from the network
    private static final int LOAD_KEEP   = 3; // failed for a reason that says nothing about the file (OOM, codec) — never delete anything

    private com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> failListener(
            PageVH h, String url, int kind) {
        final Map<String, Object> item = h.boundItem;
        return new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
            @Override public boolean onLoadFailed(
                    @androidx.annotation.Nullable com.bumptech.glide.load.engine.GlideException e,
                    Object model,
                    com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                    boolean isFirstResource) {
                onPageMediaFailed(h, item, url, isOom(e) ? LOAD_KEEP : kind);
                return false; // let Glide run its normal error handling too
            }
            @Override public boolean onResourceReady(
                    android.graphics.drawable.Drawable resource, Object model,
                    com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                    com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                return false;
            }
        };
    }

    private static boolean isOom(com.bumptech.glide.load.engine.GlideException e) {
        if (e == null) return false;
        List<Throwable> roots = e.getRootCauses();
        if (roots == null) return false;
        for (Throwable t : roots) {
            if (t instanceof OutOfMemoryError) return true;
        }
        return false;
    }

    /** May be called from any thread (Glide reports decode failures off-main). */
    private void onPageMediaFailed(PageVH h, Map<String, Object> item, String url, int kind) {
        MAIN_HANDLER.post(() -> {
            if (item == null || h.boundItem != item) return; // page already recycled / moved on
            Context app = h.root.getContext().getApplicationContext();
            int pos = indexOfItem(item);
            if (kind == LOAD_CACHE && !isSent(item)) {
                // A RECEIVED file that "is downloaded" but can't be shown is damaged (truncated, or
                // ciphertext an old key-less prefetch cached as if it were the photo). Drop it so
                // the bubble + viewer read "not downloaded" again instead of black-screening forever.
                // Sent items are never deleted here — their cache copy is the plaintext seed and the
                // remote URL may be undecryptable ciphertext, so it can't be recreated.
                MediaCache.invalidate(app, url);
                allowedUrls.remove(url);
                if (pos >= 0) notifyItemChanged(pos); // -> tap-to-download page
                return;
            }
            if (kind == LOAD_LOCAL) {
                // Device copy vanished / permission revoked since the availability probe.
                item.remove(ChatMediaGalleryBuilder.KEY_LOCAL_PATH);
                if (pos >= 0) notifyItemChanged(pos);
                return;
            }
            showLoadError(h, item);
        });
    }

    private void showLoadError(PageVH h, Map<String, Object> item) {
        h.spinner.setVisibility(View.GONE);
        h.downloadPill.setText("Couldn't load  \u00B7  Tap to retry");
        h.downloadPill.setVisibility(View.VISIBLE);
        h.downloadPill.setOnClickListener(v -> {
            if (selectMode) { h.root.callOnClick(); return; }
            int p = indexOfItem(item);
            if (p >= 0) notifyItemChanged(p); // full rebind = one more attempt
        });
    }

    private void hideGate(PageVH h) {
        h.downloadPill.setVisibility(View.GONE);
        h.downloadPill.setOnClickListener(null);
        h.photoView.setZoomable(true);
    }

    /** Received + not downloaded: no media network I/O at all, just a thumb from Glide's cache (if any) and a tap-to-download pill. */
    private void bindGate(PageVH h, Map<String, Object> item, String url, String thumbUrl, boolean isVideo) {
        Context ctx = h.root.getContext();
        releasePlayer(h); // holder may be a recycled video page
        h.playerView.setVisibility(View.GONE);
        h.spinner.setVisibility(View.GONE);
        h.photoView.setVisibility(View.VISIBLE);
        h.photoView.setZoomable(false);
        try { Glide.with(ctx).clear(h.photoView); } catch (Exception ignored) {}
        h.photoView.setImageDrawable(null);
        if (!thumbUrl.isEmpty() && !thumbUrl.equals(url)) {
            // onlyRetrieveFromCache: the bubble usually already put this thumb in
            // Glide's disk cache; if not we just show a dark page — never a fetch.
            Glide.with(ctx).load(thumbUrl).apply(RGB_565)
                    .onlyRetrieveFromCache(true)
                    .into(h.photoView);
        }

        h.gateSizeLabel = null;
        long size = numberToLong(item.get("fileSize"));
        if (size > 0) h.gateSizeLabel = formatSize(size);

        h.downloadPill.setContentDescription(isVideo ? "Download video" : "Download photo");
        h.downloadPill.setVisibility(View.VISIBLE);
        h.downloadPill.setOnClickListener(v -> {
            if (selectMode) { h.root.callOnClick(); return; } // select mode: tap toggles the page
            startGateDownload(h, item, url, isVideo);
        });
        h.downloadPill.setOnLongClickListener(v -> h.root.performLongClick());

        Integer inFlight = downloadingUrls.get(url);
        if (inFlight != null) {
            showPillProgress(h, inFlight);
            return;
        }
        showPillIdle(h);
        if (size <= 0) {
            // HEAD request only (Content-Length) — same call the chat bubble uses for its
            // size label; it never downloads the file itself.
            MediaCache.getRemoteSize(ctx, url, new MediaCache.SizeCallback() {
                @Override public void onSize(long bytes) {
                    if (h.boundItem != item || downloadingUrls.containsKey(url)) return;
                    h.gateSizeLabel = formatSize(bytes);
                    showPillIdle(h);
                }
                @Override public void onError(String reason) {}
            });
        }
    }

    private void showPillIdle(PageVH h) {
        h.downloadPill.setText(h.gateSizeLabel != null
                ? "\u2193  Download  \u00B7  " + h.gateSizeLabel : "\u2193  Download");
    }

    private void showPillProgress(PageVH h, int percent) {
        h.downloadPill.setText(percent > 0 ? "Downloading  " + percent + "%" : "Downloading\u2026");
    }

    private void showPillError(PageVH h) {
        h.downloadPill.setText("Couldn't download  \u00B7  Tap to retry");
    }

    /** User tapped the pill: download this ONE item (with its E2E key if it's an encrypted image), then rebind the page. */
    private void startGateDownload(PageVH h, Map<String, Object> item, String url, boolean isVideo) {
        if (url.isEmpty() || downloadingUrls.containsKey(url)) return;
        final Context app = h.root.getContext().getApplicationContext();
        downloadingUrls.put(url, 0);
        showPillProgress(h, 0);

        // Media-E2E applies to images only (a video's envelope, if any, covers just its thumb).
        final String enc = isVideo ? "" : safeStr(item.get(ChatMediaGalleryBuilder.KEY_MEDIA_KEY_ENC));
        final String senderId = safeStr(item.get(ChatMediaGalleryBuilder.KEY_SENDER_ID));
        final String msgId = safeStr(item.get(ChatMediaGalleryBuilder.KEY_MESSAGE_ID));
        if (!enc.isEmpty() && !senderId.isEmpty()) {
            // Ratchet decrypt off the main thread, on the sender's own FIFO bucket —
            // same executor the chat bubble uses for this exact decrypt.
            E2eeDecryptExecutor.execute(senderId, () -> {
                MediaE2ECrypto.KeyEnvelope env = MediaE2ECrypto.decryptEnvelopeForMessage(
                        app, enc, senderId, msgId.isEmpty() ? null : msgId);
                final byte[] key = env != null ? env.fullKey() : null;
                final byte[] digest = env != null ? env.fullDigest : null;
                MAIN_HANDLER.post(() -> {
                    if (key == null) {
                        // Never download ciphertext without its key — it would be cached as if it were the image.
                        downloadingUrls.remove(url);
                        if (h.boundItem == item) showPillError(h);
                        return;
                    }
                    runGateDownload(app, h, item, url, key, digest);
                });
            });
        } else {
            runGateDownload(app, h, item, url, null, null);
        }
    }

    private void runGateDownload(Context app, PageVH h, Map<String, Object> item, String url,
                                 byte[] key, byte[] digest) {
        MediaCache.getWithProgress(app, url, key, digest, new MediaCache.ProgressCallback() {
            @Override public void onProgress(int percent) {
                downloadingUrls.put(url, percent);
                if (h.boundItem == item) showPillProgress(h, percent);
            }
            @Override public void onReady(File file) {
                downloadingUrls.remove(url);
                allowedUrls.add(url);
                int pos = indexOfItem(item);
                if (pos >= 0) {
                    if (pos == activePosition && "video".equals(item.get("mediaType"))) pendingAutoPlayItem = item;
                    notifyItemChanged(pos); // rebinds through the normal (now cached) path
                }
            }
            @Override public void onError(String reason) {
                downloadingUrls.remove(url);
                if (h.boundItem == item) showPillError(h);
            }
        });
    }

    /** Identity lookup — the window can grow at either end while a download is running, so positions shift. */
    private int indexOfItem(Map<String, Object> item) {
        if (items == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) == item) return i;
        }
        return -1;
    }

    private static long numberToLong(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        if (o instanceof String) {
            try { return Long.parseLong((String) o); } catch (NumberFormatException ignored) {}
        }
        return 0L;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return Math.round(kb) + " kB";
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.1f GB", mb / 1024.0);
    }

    private void bindImage(PageVH h, String fullUrl, String thumbUrl, Priority priority) {
        Context ctx = h.photoView.getContext();
        if (thumbUrl != null && !thumbUrl.isEmpty()) {
            Glide.with(ctx).load(thumbUrl)
                    .apply(RGB_565)
                    .priority(priority)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .override(400, 400)
                    .into(h.photoView);
            // If the full image is already in MediaCache (downloaded from the
            // chat bubble, or just now via this page's Download pill) use that
            // file — for a Media-E2E image it's the DECRYPTED copy, whereas the
            // remote URL is ciphertext Glide can't decode.
            File cachedFull = MediaCache.getCached(ctx, fullUrl);
            com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> fullReq =
                    cachedFull != null ? Glide.with(ctx).load(cachedFull) : Glide.with(ctx).load(fullUrl);
            fullReq
                    .apply(RGB_565)
                    .listener(failListener(h, fullUrl, cachedFull != null ? LOAD_CACHE : LOAD_REMOTE))
                    .priority(priority)
                    .thumbnail(Glide.with(ctx).load(thumbUrl).apply(RGB_565).priority(priority).diskCacheStrategy(DiskCacheStrategy.ALL))
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .transition(com.bumptech.glide.load.resource.drawable
                            .DrawableTransitionOptions.withCrossFade(400))
                    .into(h.photoView);
        } else {
            File cached = MediaCache.getCached(ctx, fullUrl);
            if (cached != null) {
                // PERF (region decoding): outlier-sized images (panorama/
                // high-res scan) skip Glide's normal decode entirely and go
                // through BitmapRegionDecoder instead — see
                // HighResImageDecoder's class doc for why/scope.
                if (HighResImageDecoder.isOversized(cached)) {
                    decodeHighResOnBackground(h, cached, priority, fullUrl);
                } else {
                    Glide.with(ctx).load(cached).apply(RGB_565).priority(priority).diskCacheStrategy(DiskCacheStrategy.ALL)
                            .listener(failListener(h, fullUrl, LOAD_CACHE)).into(h.photoView);
                }
            } else {
                Glide.with(ctx).load(fullUrl).apply(RGB_565).priority(priority).diskCacheStrategy(DiskCacheStrategy.ALL)
                        .listener(failListener(h, fullUrl, LOAD_REMOTE)).into(h.photoView);
                MediaCache.get(ctx, fullUrl, new MediaCache.Callback() {
                    @Override public void onReady(File file) {}
                    @Override public void onError(String reason) {}
                });
            }
        }
    }

    /** Received image the device still has via its mediaLocalPath Uri — loads from disk, never the network. */
    private void bindLocalImage(PageVH h, String localPath, Priority priority) {
        Context ctx = h.photoView.getContext();
        Glide.with(ctx).load(Uri.parse(localPath))
                .apply(RGB_565)
                .priority(priority)
                .listener(failListener(h, "", LOAD_LOCAL))
                .into(h.photoView);
    }

    /**
     * Decodes an oversized local file via HighResImageDecoder off the main
     * thread, then sets the result directly on the PhotoView. Falls back to
     * the normal (RGB_565 + hardware) Glide path if region decoding fails
     * for any reason, so a weird/unsupported file never leaves the page blank.
     */
    private void decodeHighResOnBackground(PageVH h, File file, Priority priority, String url) {
        Context ctx = h.photoView.getContext();
        int reqW = ctx.getResources().getDisplayMetrics().widthPixels;
        int reqH = ctx.getResources().getDisplayMetrics().heightPixels;
        // BUG FIX: only checking "still has an adapter position" let a stale decode land on a
        // holder that ViewPager2 had already re-bound to a DIFFERENT item (wrong/blank image).
        final Map<String, Object> boundAtStart = h.boundItem;
        h.spinner.setVisibility(View.VISIBLE);
        REGION_DECODE_EXECUTOR.execute(() -> {
            android.graphics.Bitmap bmp = HighResImageDecoder.decodeSafely(file, reqW, reqH);
            MAIN_HANDLER.post(() -> {
                if (h.getAdapterPosition() == RecyclerView.NO_POSITION) return; // recycled meanwhile
                if (h.boundItem != boundAtStart) return;                         // re-bound to another item meanwhile
                h.spinner.setVisibility(View.GONE);
                if (bmp != null) {
                    h.photoView.setImageBitmap(bmp);
                } else {
                    // Region decode couldn't handle this file — let Glide try normally.
                    Glide.with(ctx).load(file).apply(RGB_565).priority(priority)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .listener(failListener(h, url, LOAD_CACHE)).into(h.photoView);
                }
            });
        });
    }

    /** @param localUri non-null (a device mediaLocalPath Uri string) for a received video that's already on the phone but not in MediaCache. */
    private void bindVideo(PageVH h, String url, String localUri) {
        Context ctx = h.playerView.getContext();
        releasePlayer(h); // safety — in case of view-holder reuse without unbind

        Uri playUri;
        final int loadKind;
        File cached = MediaCache.getCached(ctx, url);
        if (cached != null) {
            playUri = Uri.fromFile(cached);
            loadKind = LOAD_CACHE;
        } else if (localUri != null && !localUri.isEmpty()) {
            playUri = Uri.parse(localUri); // already on device — no stream, no MediaCache.get()
            loadKind = LOAD_LOCAL;
        } else {
            loadKind = LOAD_REMOTE;
            playUri = Uri.parse(url);
            h.spinner.setVisibility(View.VISIBLE);
            MediaCache.get(ctx, url, new MediaCache.Callback() {
                @Override public void onReady(File file) { h.spinner.setVisibility(View.GONE); }
                @Override public void onError(String reason) { h.spinner.setVisibility(View.GONE); }
            });
        }

        // PERF: acquire from the shared ExoPlayerPool instead of building a
        // fresh ExoPlayer on every bind — see ExoPlayerPool class doc.
        h.player = com.callx.app.utils.ExoPlayerPool.acquire(ctx);
        h.playerView.setPlayer(h.player);
        h.player.setMediaItem(MediaItem.fromUri(playUri));
        h.player.prepare();
        // Auto-play only the currently active page — MediaViewerActivity
        // calls play()/pause() via onPageSelected so other pages stay paused.
        h.player.setPlayWhenReady(false);
        // Keep a reference so releasePlayer() can remove exactly this
        // listener before the player goes back to the pool — a reused
        // pooled player must not carry a previous page's listener forward
        // (would fire spinner/state callbacks against a recycled holder).
        final Map<String, Object> boundItemAtBind = h.boundItem;
        h.playerListener = new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) h.spinner.setVisibility(View.GONE);
            }
            @Override public void onPlayerError(androidx.media3.common.PlaybackException error) {
                // Previously silent -> black page. Only a container-level parse failure says the
                // FILE is bad (truncated / ciphertext); codec/IO/network errors must never delete it.
                boolean badFile = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                        || error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED;
                h.spinner.setVisibility(View.GONE);
                onPageMediaFailed(h, boundItemAtBind, url, (loadKind == LOAD_CACHE && !badFile) ? LOAD_KEEP : loadKind);
            }
        };
        h.player.addListener(h.playerListener);
    }

    /** Called by the activity when a video page becomes the active/visible one. */
    public void setActive(PageVH h, boolean active) {
        if (h.player == null) return;
        h.player.setPlayWhenReady(active);
    }

    public void releasePlayer(PageVH h) {
        if (h.player != null) {
            if (h.playerListener != null) {
                h.player.removeListener(h.playerListener);
                h.playerListener = null;
            }
            h.playerView.setPlayer(null);
            // PERF: return to the shared pool instead of releasing native
            // resources outright — see ExoPlayerPool class doc.
            com.callx.app.utils.ExoPlayerPool.release(h.player);
            h.player = null;
        }
    }

    @Override
    public void onViewRecycled(@NonNull PageVH h) {
        super.onViewRecycled(h);
        releasePlayer(h);
        h.boundItem = null; // in-flight gate-download callbacks check this before touching the holder
        h.downloadPill.setOnClickListener(null);
        // PERF (verify release timing): once a page is recycled (i.e. it's
        // gone past offscreenPageLimit=1's window), cancel any Glide
        // request still in flight for it — otherwise a background
        // thumbnail/full-image download for a page the user can no longer
        // reach keeps running and competing for the same network/decode
        // executors as the page actually on screen.
        try {
            Glide.with(h.photoView.getContext()).clear(h.photoView);
        } catch (Exception ignored) {
            // Context may already be torn down (activity finishing) — safe to no-op.
        }
    }

    @Override public int getItemCount() { return items == null ? 0 : items.size(); }

    private static String safeStr(Object o) { return (o instanceof String) ? (String) o : ""; }

    static class PageVH extends RecyclerView.ViewHolder {
        final FrameLayout root;
        final PhotoView photoView;
        final PlayerView playerView;
        final ProgressBar spinner;
        final android.widget.CheckBox checkbox;
        final android.widget.TextView tvCaption;
        final android.widget.TextView downloadPill;
        ExoPlayer player;
        Player.Listener playerListener;
        // The gallery item this holder is currently bound to (identity-compared by
        // async gate-download callbacks) and its pre-formatted size label, if known.
        Map<String, Object> boundItem;
        String gateSizeLabel;

        PageVH(FrameLayout root, PhotoView photoView, PlayerView playerView, ProgressBar spinner,
               android.widget.CheckBox checkbox, android.widget.TextView tvCaption,
               android.widget.TextView downloadPill) {
            super(root);
            this.root = root;
            this.photoView = photoView;
            this.playerView = playerView;
            this.spinner = spinner;
            this.checkbox = checkbox;
            this.tvCaption = tvCaption;
            this.downloadPill = downloadPill;
        }
    }
}
