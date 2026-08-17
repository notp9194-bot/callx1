package com.callx.app.activities;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.github.chrisbanes.photoview.PhotoView;
import com.callx.app.databinding.ActivityMediaViewerBinding;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.MediaCache;
import com.callx.app.utils.MediaSwipeReplyCloseHelper;
import com.callx.app.utils.MediaViewerSourceRect;
import com.callx.app.utils.PhotoViewZoomUtils;
import com.google.firebase.database.DatabaseReference;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MediaViewerActivity — Full-screen media viewer.
 *
 * Features:
 *  • Pinch-to-zoom for images (PhotoView)
 *  • Tap image → toggle top bar (WhatsApp style)
 *  • Swipe down gesture → dismiss (via PhotoView scale + back)
 *  • Video with ExoPlayer (cache-first)
 *  • Share button in top bar
 *  • Video playback presence — while a video opened FROM a chat is actually
 *    playing, publishes chatPlayback/{chatId}/{uid}=messageId (same node
 *    ChatPlaybackPresenceController watches) so the partner's chat list
 *    shows a live "▶ watching…" badge on that bubble. No-op if this viewer
 *    was opened without chatId/messageId extras (e.g. from a non-chat caller).
 */
public class MediaViewerActivity extends AppCompatActivity {

    private ActivityMediaViewerBinding binding;
    private ExoPlayer player;
    private boolean uiVisible = true;
    private String sharedUrl;

    // Local-first single-item mode (see onCreate doc + LocalMediaAvailability).
    private String singleItemLocalPath;
    // BUG FIX (Voice Caption on Photo — image can't be opened after send):
    // a Media-E2E image's `url` extra is CIPHERTEXT (resource_type=raw).
    // Previously this activity had no way to decrypt it at all — only the
    // sender's own singleItemLocalPath fallback ever rendered correctly.
    // showMediaActionSheet now derives the full-res subkey and passes it
    // here (see MessagePagingAdapter#sheetMediaKeyB64) so a receiver's
    // View/Edit actually decrypts instead of trying to decode raw
    // ciphertext bytes as an image.
    private byte[] mediaDecryptKey;

    // ── Gallery mode (swipeable grouped-media viewer) ────────────────────
    private GalleryPagerAdapter galleryAdapter;
    private List<Map<String, Object>> galleryItems;
    private int galleryActivePos = -1;

    // ── Swipe-down-to-close / swipe-up-to-reply (single + gallery mode) ──
    // Common core helper — works for single-media mode now too, not just
    // the grouped-media gallery.
    private String replyChatId;
    private String replyMessageId;
    private MediaSwipeReplyCloseHelper swipeHelper;

    // ── Telegram-style open/close animation ───────────────────────────
    // The tapped chat-bubble thumbnail's on-screen rect (null if the
    // caller didn't supply one — falls back to the plain fade/translate
    // close everywhere below). `activeDragView` is whichever content view
    // is actually on screen for the current mode (ivFull / player /
    // mediaPager) — set once in setupSwipeHelper() and reused by both the
    // open and close animations.
    private Rect sourceRect;
    private View activeDragView;

    // ── Video playback presence (see class doc above) ───────────────────
    private String playbackChatId;
    private String playbackMessageId;
    private DatabaseReference playbackRef;
    private boolean playbackPublished = false;

    // ── "Edit" action (WhatsApp-style: view a photo → edit → resend) ────
    private ActivityResultLauncher<Intent> mediaEditLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Full-screen immersive
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        // MEDIA_VIEWER_TELEGRAM_CLOSE — belt-and-suspenders alongside the
        // Theme.CallX.Fullscreen.Translucent manifest theme: makes sure the
        // Window itself paints nothing behind binding.getRoot(), so the
        // live-drag scrim fade in MediaSwipeReplyCloseHelper actually
        // reveals the calling screen (chat) instead of an opaque backdrop.
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));

        binding = ActivityMediaViewerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        hideSystemUI();

        String url  = getIntent().getStringExtra("url");
        String type = getIntent().getStringExtra("type");
        sharedUrl   = url;

        // Telegram-style open/close animation — see field doc above.
        sourceRect = MediaViewerSourceRect.read(getIntent());

        // WhatsApp-style local-first: when the chat bubble passed along the
        // original local file/content Uri (still present on the device),
        // render straight from it here — full quality, no network — instead
        // of the (possibly compressed) remote mediaUrl. Falls back to `url`
        // automatically if the file's gone (see LocalMediaAvailability).
        singleItemLocalPath = getIntent().getStringExtra("localPath");

        // Media E2E — see mediaDecryptKey field doc above.
        String mediaKeyB64 = getIntent().getStringExtra("mediaKeyB64");
        if (mediaKeyB64 != null && !mediaKeyB64.isEmpty()) {
            try {
                mediaDecryptKey = android.util.Base64.decode(mediaKeyB64, android.util.Base64.NO_WRAP);
            } catch (Exception ignored) {
                mediaDecryptKey = null;
            }
        }

        // Optional — only present when opened from a grouped-media message
        // tap. Used to send a swipe-up "reply" request back to the chat
        // screen via GalleryReplyBridge. Both null disables that gesture's
        // action (the swipe-down-to-close part always works regardless).
        replyChatId    = getIntent().getStringExtra("chatId");
        replyMessageId = getIntent().getStringExtra("messageId");

        // Optional — only present when opened from a chat bubble. Both
        // null is the normal/expected case for other callers (status
        // viewer, all-media grid, etc.) and simply disables presence.
        playbackChatId    = getIntent().getStringExtra("chatId");
        playbackMessageId = getIntent().getStringExtra("messageId");

        // Close button
        binding.btnClose.setOnClickListener(v -> closeViewer());

        // Share button
        binding.btnShare.setOnClickListener(v -> shareMedia(sharedUrl));

        // #3 fix — Save to gallery (previously only Share was available)
        binding.btnSave.setOnClickListener(v -> saveCurrentToGallery());

        // #4/#2 fix — More options now opens a real menu (per-item delete/
        // star/caption-edit for grouped media, or just Save+Share fallback
        // for single-media mode) instead of being a dead placeholder.
        binding.btnMoreOptions.setOnClickListener(v -> showMoreOptionsMenu());

        // "Edit" — WhatsApp-style: view a photo OR video in the chat, tweak
        // it in the same full-screen editor the attach sheet uses, and
        // resend it as a new message (see GalleryEditBridge for the handoff
        // back to ChatActivity / GroupChatActivity).
        mediaEditLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
                    ArrayList<String> resultUris = result.getData().getStringArrayListExtra(
                            com.callx.app.conversation.controllers.MediaEditActivity.RESULT_URIS);
                    if (resultUris == null || resultUris.isEmpty()) return;
                    String caption = result.getData().getStringExtra(
                            com.callx.app.conversation.controllers.MediaEditActivity.RESULT_CAPTION);
                    boolean editedHD = result.getData().getBooleanExtra(
                            com.callx.app.conversation.controllers.MediaEditActivity.RESULT_HD, false);
                    if (replyChatId != null) {
                        com.callx.app.conversation.GalleryEditBridge.requestSend(
                                replyChatId, resultUris.get(0), caption, editedHD);
                    }
                    finish();
                    overridePendingTransition(0, 0);
                });
        binding.btnEdit.setOnClickListener(v -> onEditClicked());

        // #1 fix — selection-mode toolbar wiring
        binding.btnSelectClose.setOnClickListener(v -> exitSelectMode());
        binding.btnSelectForward.setOnClickListener(v -> forwardSelection());
        binding.btnSelectDelete.setOnClickListener(v -> deleteSelection());
        binding.btnSelectStar.setOnClickListener(v -> starSelection());

        // WhatsApp-style "Edit" shortcut — set by showImageActionSheet's Edit
        // option so the viewer jumps straight into MediaEditActivity instead
        // of making the user tap the pencil a second time once the viewer is
        // open. Works for video too now — MediaEditActivity fully supports
        // video (trim/stickers/text/draw, baked into the actual video on send).
        boolean autoEdit = getIntent().getBooleanExtra("autoEdit", false);

        String mediaItemsJson = getIntent().getStringExtra("mediaItemsJson");
        if (mediaItemsJson != null && !mediaItemsJson.isEmpty()) {
            setupGalleryMode(mediaItemsJson, getIntent().getIntExtra("startIndex", 0));
            if (autoEdit) {
                binding.getRoot().post(this::onEditClicked);
            }
            return;
        }

        if (url == null) { finish(); return; }

        if ("video".equals(type)) {
            binding.player.setVisibility(View.VISIBLE);
            binding.ivFull.setVisibility(View.GONE);
            binding.btnEdit.setVisibility(View.VISIBLE);
            playVideo(url, singleItemLocalPath);
            if (autoEdit) {
                binding.getRoot().post(this::onEditClicked);
            }

            // For video — tap player toggles top bar
            binding.player.setOnClickListener(v -> toggleUI());

            // Video isn't zoomable, so no pinch-zoom guard needed here.
            setupSwipeHelper(binding.player, null, null);
            animateOpenFromSource(binding.player, sourceRect);

        } else {
            binding.ivFull.setVisibility(View.VISIBLE);
            binding.player.setVisibility(View.GONE);
            binding.btnEdit.setVisibility(View.VISIBLE);
            if (autoEdit) {
                binding.getRoot().post(this::onEditClicked);
            }

            String thumbUrl = getIntent().getStringExtra("thumbUrl");
            loadImageProgressive(url, thumbUrl, singleItemLocalPath);

            // Tap image → toggle top bar
            binding.ivFull.setOnViewTapListener((view, x, y) -> toggleUI());

            // #single-media fix — swipe-up-to-reply / swipe-down-to-close
            // now works here too (previously gallery-mode only), guarded
            // against pinch-zoom via the shared PhotoViewZoomUtils check.
            setupSwipeHelper(binding.ivFull, null,
                    () -> PhotoViewZoomUtils.isZoomedIn(binding.ivFull));
            animateOpenFromSource(binding.ivFull, sourceRect);
        }
    }

    /**
     * Common wiring for MediaSwipeReplyCloseHelper — used by both
     * single-media mode (image/video) and gallery mode.
     *
     * @param dragView            view that translates/fades during the drag
     * @param viewToCancelOnDrag  horizontal-scrolling view to cancel once a
     *                            vertical drag is confirmed (ViewPager2 in
     *                            gallery mode, null in single-media mode)
     * @param zoomedStateProvider guard against pinch-zoom conflicts, null
     *                            when the content isn't zoomable (video)
     */
    private void setupSwipeHelper(View dragView, View viewToCancelOnDrag,
                                   MediaSwipeReplyCloseHelper.ZoomedStateProvider zoomedStateProvider) {
        activeDragView = dragView;
        swipeHelper = new MediaSwipeReplyCloseHelper(
                this, dragView, binding.getRoot(), viewToCancelOnDrag, zoomedStateProvider,
                new MediaSwipeReplyCloseHelper.Callback() {
                    @Override public void onSwipeUpReply() {
                        // Retired — MediaSwipeReplyCloseHelper now routes
                        // BOTH swipe-up and swipe-down to onSwipeDownClose()
                        // (see that class), so this never actually fires
                        // anymore. Left in place only because the interface
                        // still declares it.
                    }
                    @Override public void onSwipeDownClose(float velocityY) {
                        closeViewer(velocityY);
                    }
                });
        // Top bar / select-toolbar / page-counter now fade LIVE with the
        // drag itself (not just once release triggers the close animation)
        // — see MediaSwipeReplyCloseHelper's class doc.
        swipeHelper.setChromeViews(binding.llTopBar, binding.llSelectToolbar, binding.tvPageCounter);
    }

    /** Bubble media corner radius (MessageBubbleCanvasView#MEDIA_CORNER_RADIUS_DP) — kept in sync so the
     *  docking animation's final rounding matches the actual chat-bubble thumbnail exactly. */
    private static final float BUBBLE_MEDIA_CORNER_RADIUS_DP = 18f;
    /** Base duration for the dock-to-source / expand-from-source animation, before velocity adjusts it. */
    private static final long DOCK_ANIM_BASE_MS = 300L;
    private static final long DOCK_ANIM_MIN_MS = 170L;
    // Material "emphasized" easing — fast out, gentle settle. Reads far more
    // premium than a plain DecelerateInterpolator for a docking/undocking
    // motion like this.
    private static final android.view.animation.Interpolator DOCK_EASE =
            new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);

    /**
     * Closes the viewer. If a source thumbnail rect was supplied (see
     * MediaViewerSourceRect), animates the visible content shrinking back
     * into that exact spot — Telegram-style "chipakna" — instead of just
     * cutting away. Safe no-op fallback (plain instant close) when there's
     * no rect or no content view to animate.
     *
     * @param velocityY signed px/sec from the swipe gesture that triggered
     *                  this close (0 for close-button/back-press), used to
     *                  shorten the dock animation on a fast fling so it
     *                  reads as a continuation of the throw, not a reset.
     */
    private void closeViewer(float velocityY) {
        if (sourceRect != null && activeDragView != null && !isFinishing()) {
            animateCloseToSource(sourceRect, velocityY);
        } else {
            finishNow();
        }
    }

    private void closeViewer() {
        closeViewer(0f);
    }

    private void finishNow() {
        finish();
        overridePendingTransition(0, 0);
    }

    /**
     * Shrinks+moves `activeDragView` from its current on-screen spot (which
     * may already be mid-drag — scaled/translated/rounded by
     * MediaSwipeReplyCloseHelper's live gesture) into `target`, then
     * finishes. Everything — position, scale, corner radius, content alpha,
     * chrome alpha, background scrim — is driven off a single ValueAnimator
     * fraction so nothing can drift out of sync with anything else.
     */
    private void animateCloseToSource(Rect target, float velocityY) {
        final View v = activeDragView;
        v.animate().cancel();
        // PERF (ultra-advanced pass): same GPU-layer caching trick already
        // used by the avatar-zoom dock animation (DialogFullscreenHelper) —
        // this animator drives translation+scale+alpha+outline-clip on `v`
        // together every frame, which without a hardware layer means a full
        // re-draw (and, for video, a full re-composite) of the content each
        // frame. Caching it once up front turns every subsequent frame into
        // a cheap GPU-composited transform instead. Matters most for the
        // close-button/back-press/tap-outside paths, where no swipe drag
        // happened first — in that case `v` was never layered by
        // MediaSwipeReplyCloseHelper's live-drag gesture, so without this it
        // would run completely un-cached for the whole animation. Released
        // in onAnimationEnd, right before the Activity finishes anyway.
        if (v.getLayerType() != View.LAYER_TYPE_HARDWARE) {
            v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }

        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        float startScaleX = v.getScaleX();
        float startScaleY = v.getScaleY();
        float curCenterX = loc[0] + (v.getWidth() * startScaleX) / 2f;
        float curCenterY = loc[1] + (v.getHeight() * startScaleY) / 2f;
        float targetCenterX = target.left + target.width() / 2f;
        float targetCenterY = target.top + target.height() / 2f;

        float startTx = v.getTranslationX();
        float startTy = v.getTranslationY();
        float endTx = startTx + (targetCenterX - curCenterX);
        float endTy = startTy + (targetCenterY - curCenterY);
        float endScaleX = v.getWidth()  > 0 ? target.width()  / (float) v.getWidth()  : 1f;
        float endScaleY = v.getHeight() > 0 ? target.height() / (float) v.getHeight() : 1f;

        float startAlpha = v.getAlpha();
        float startBgAlpha = Color.alpha(currentBgAlphaOr(255));
        float startChromeAlpha = binding.llTopBar.getAlpha();
        float startOnScreenRadius = swipeHelper != null ? swipeHelper.getCurrentOnScreenRadiusPx() : 0f;
        float endOnScreenRadius = dp(BUBBLE_MEDIA_CORNER_RADIUS_DP);

        // A fast fling should feel like the photo is being THROWN into the
        // thumbnail slot — shorten the animation proportionally instead of
        // always running the same fixed duration regardless of how hard the
        // user flicked.
        long duration = velocityAdjustedDuration(velocityY);

        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(duration);
        anim.setInterpolator(DOCK_EASE);
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            v.setTranslationX(lerp(startTx, endTx, t));
            v.setTranslationY(lerp(startTy, endTy, t));
            float sx = lerp(startScaleX, endScaleX, t);
            float sy = lerp(startScaleY, endScaleY, t);
            v.setScaleX(sx);
            v.setScaleY(sy);
            v.setAlpha(lerp(startAlpha, 1f, t));
            if (swipeHelper != null) {
                swipeHelper.setLiveCornerRadius(lerp(startOnScreenRadius, endOnScreenRadius, t), sx);
            }
            int bgAlpha = Math.round(lerp(startBgAlpha, 0f, t));
            binding.getRoot().setBackgroundColor(Color.argb(bgAlpha, 0, 0, 0));
            float chromeAlpha = lerp(startChromeAlpha, 0f, t);
            binding.llTopBar.setAlpha(chromeAlpha);
            binding.llSelectToolbar.setAlpha(chromeAlpha);
            binding.tvPageCounter.setAlpha(chromeAlpha);
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                v.setLayerType(View.LAYER_TYPE_NONE, null);
                finishNow();
            }
        });
        anim.start();
    }

    /** Starts `v` scaled/translated/rounded to look like it's still `source`, then docks up to full-screen. */
    private void animateOpenFromSource(View v, Rect source) {
        if (source == null || v == null) return;
        v.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (v.getWidth() == 0 || v.getHeight() == 0) return;
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            float targetCenterX = loc[0] + v.getWidth() / 2f;
            float targetCenterY = loc[1] + v.getHeight() / 2f;
            float srcCenterX = source.left + source.width() / 2f;
            float srcCenterY = source.top + source.height() / 2f;

            float startScaleX = source.width()  / (float) v.getWidth();
            float startScaleY = source.height() / (float) v.getHeight();
            float startTx = srcCenterX - targetCenterX;
            float startTy = srcCenterY - targetCenterY;
            float startOnScreenRadius = dp(BUBBLE_MEDIA_CORNER_RADIUS_DP);

            v.setScaleX(startScaleX);
            v.setScaleY(startScaleY);
            v.setTranslationX(startTx);
            v.setTranslationY(startTy);
            if (swipeHelper != null) swipeHelper.setLiveCornerRadius(startOnScreenRadius, startScaleX);
            // PERF (ultra-advanced pass): see animateCloseToSource's identical
            // comment — cache `v` into a GPU layer for the open animation's
            // duration too, released once it settles into its idle state.
            if (v.getLayerType() != View.LAYER_TYPE_HARDWARE) {
                v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            }

            binding.getRoot().setBackgroundColor(Color.TRANSPARENT);
            binding.llTopBar.setAlpha(0f);
            binding.tvPageCounter.setAlpha(0f);

            ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(DOCK_ANIM_BASE_MS);
            anim.setInterpolator(DOCK_EASE);
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    // Open dock finished — view is now idle (pinch-zoomable /
                    // paging) until the next drag/close, so release the GPU
                    // layer; the swipe helper re-acquires it itself the
                    // moment a new drag starts.
                    v.setLayerType(View.LAYER_TYPE_NONE, null);
                }
            });
            anim.addUpdateListener(a -> {
                float t = (float) a.getAnimatedValue();
                v.setTranslationX(lerp(startTx, 0f, t));
                v.setTranslationY(lerp(startTy, 0f, t));
                float sx = lerp(startScaleX, 1f, t);
                float sy = lerp(startScaleY, 1f, t);
                v.setScaleX(sx);
                v.setScaleY(sy);
                if (swipeHelper != null) {
                    swipeHelper.setLiveCornerRadius(lerp(startOnScreenRadius, 0f, t), sx);
                }
                int bgAlpha = Math.round(lerp(0f, 255f, t));
                binding.getRoot().setBackgroundColor(Color.argb(bgAlpha, 0, 0, 0));
            });
            anim.start();
            // Chrome eases in slightly after the content starts moving —
            // matches the reference Telegram timing (chrome never leads).
            binding.llTopBar.animate().alpha(1f).setDuration(220).setStartDelay(90).start();
        });
    }

    private long velocityAdjustedDuration(float velocityY) {
        float speedDpPerSec = Math.abs(velocityY) / getResources().getDisplayMetrics().density;
        // 0 dp/s → base duration; ~2500 dp/s (a hard flick) → floor duration.
        float speedFactor = Math.min(1f, speedDpPerSec / 2500f);
        long duration = Math.round(lerp(DOCK_ANIM_BASE_MS, DOCK_ANIM_MIN_MS, speedFactor));
        return Math.max(DOCK_ANIM_MIN_MS, duration);
    }

    private static float lerp(float start, float end, float t) {
        return start + (end - start) * t;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    /** Best-effort read of the root's current background alpha (falls back to `fallback` if not a solid color). */
    private int currentBgAlphaOr(int fallback) {
        android.graphics.drawable.Drawable bg = binding.getRoot().getBackground();
        if (bg instanceof android.graphics.drawable.ColorDrawable) {
            return Color.alpha(((android.graphics.drawable.ColorDrawable) bg).getColor());
        }
        return fallback;
    }

    // ── Gallery mode — swipeable multi-image/video viewer ────────────────
    private void setupGalleryMode(String json, int startIndex) {
        galleryItems = parseMediaItems(json);
        if (galleryItems.isEmpty()) { finish(); return; }
        int start = Math.max(0, Math.min(startIndex, galleryItems.size() - 1));

        binding.ivFull.setVisibility(View.GONE);
        binding.player.setVisibility(View.GONE);
        binding.mediaPager.setVisibility(View.VISIBLE);
        binding.tvPageCounter.setVisibility(galleryItems.size() > 1 ? View.VISIBLE : View.GONE);

        galleryAdapter = new GalleryPagerAdapter(galleryItems, this::toggleUI);
        galleryAdapter.setLongPressListener(pos -> enterSelectMode(pos));
        galleryAdapter.setSelectionToggleListener(pos -> updateSelectToolbar());
        binding.mediaPager.setAdapter(galleryAdapter);
        binding.mediaPager.setCurrentItem(start, false);
        updatePageCounter(start);
        sharedUrl = safeStr(galleryItems.get(start).get("url"));
        binding.btnEdit.setVisibility(View.VISIBLE);

        binding.mediaPager.registerOnPageChangeCallback(new androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                pauseAllExcept(position);
                galleryActivePos = position;
                sharedUrl = safeStr(galleryItems.get(position).get("url"));
                updatePageCounter(position);
                binding.btnEdit.setVisibility(View.VISIBLE);
            }
        });
        galleryActivePos = start;
        // Slight delay so RecyclerView has a bound ViewHolder to play on first open
        binding.mediaPager.post(() -> pauseAllExcept(start));

        // Same shared helper as single-media mode — swipe up to reply
        // (quoting the specific tapped item via galleryActivePos), swipe
        // down to close. ViewPager2 is passed as viewToCancelOnDrag so it
        // stops trying to interpret the vertical drag as a horizontal page
        // swipe once we've claimed it.
        setupSwipeHelper(binding.mediaPager, binding.mediaPager,
                () -> PhotoViewZoomUtils.isZoomedIn(currentGalleryPhotoView()));
        // Only meaningful when the gallery opened directly on the tapped
        // item (grouped-media grid tap) — sourceRect corresponds to that
        // one cell, so the animation only looks right for the page that's
        // actually showing at `start`. Fine either way: it's a no-op when
        // sourceRect is null.
        animateOpenFromSource(binding.mediaPager, sourceRect);
    }

    /** Currently-active page's PhotoView, or null (video page / not bound yet). */
    private PhotoView currentGalleryPhotoView() {
        if (binding.mediaPager.getChildCount() == 0) return null;
        View v0 = binding.mediaPager.getChildAt(0);
        if (!(v0 instanceof androidx.recyclerview.widget.RecyclerView)) return null;
        androidx.recyclerview.widget.RecyclerView rv = (androidx.recyclerview.widget.RecyclerView) v0;
        for (int i = 0; i < rv.getChildCount(); i++) {
            androidx.recyclerview.widget.RecyclerView.ViewHolder vh =
                    rv.getChildViewHolder(rv.getChildAt(i));
            if (vh instanceof GalleryPagerAdapter.PageVH
                    && vh.getAdapterPosition() == galleryActivePos) {
                return ((GalleryPagerAdapter.PageVH) vh).photoView;
            }
        }
        return null;
    }

    /** Plays the video on `activePos` (if it's a video page) and pauses every other bound page. */
    private void pauseAllExcept(int activePos) {
        androidx.recyclerview.widget.RecyclerView rv =
                (androidx.recyclerview.widget.RecyclerView) binding.mediaPager.getChildAt(0);
        if (rv == null) return;
        for (int i = 0; i < rv.getChildCount(); i++) {
            android.view.View child = rv.getChildAt(i);
            androidx.recyclerview.widget.RecyclerView.ViewHolder vh = rv.getChildViewHolder(child);
            if (vh instanceof GalleryPagerAdapter.PageVH) {
                GalleryPagerAdapter.PageVH pvh = (GalleryPagerAdapter.PageVH) vh;
                galleryAdapter.setActive(pvh, pvh.getAdapterPosition() == activePos);
            }
        }
    }

    private void updatePageCounter(int position) {
        if (galleryItems == null) return;
        binding.tvPageCounter.setText((position + 1) + " / " + galleryItems.size());
    }

    private List<Map<String, Object>> parseMediaItems(String json) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                Map<String, Object> item = new HashMap<>();
                java.util.Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    item.put(k, obj.opt(k));
                }
                result.add(item);
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static String safeStr(Object o) { return (o instanceof String) ? (String) o : ""; }

    // ── Swipe down (close) / swipe up (reply) — single + gallery mode ────
    // Delegates to the shared MediaSwipeReplyCloseHelper (core module) so
    // the exact same gesture logic backs both modes instead of being
    // duplicated. The helper internally guards against a predominantly-
    // horizontal drag (so ViewPager2 paging isn't hijacked) and against an
    // active pinch-zoom (so PhotoView's own gesture always wins first).
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        boolean gallerySelecting = galleryAdapter != null && galleryAdapter.isSelectMode();
        if (swipeHelper != null && !gallerySelecting && swipeHelper.onTouch(ev)) {
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void onBackPressed() {
        if (galleryAdapter != null && galleryAdapter.isSelectMode()) {
            exitSelectMode();
            return;
        }
        // Same shrink-into-thumbnail animation as swipe/close-button — see
        // closeViewer(). Falls back to a plain finish() when there's no
        // sourceRect (super.onBackPressed()'s old behavior).
        closeViewer();
    }

    // ── #1 — Multi-select / forward from gallery ─────────────────────────
    private void enterSelectMode(int startPos) {
        if (galleryAdapter == null) return;
        galleryAdapter.setSelectMode(true);
        galleryAdapter.toggleSelected(startPos);
        binding.llTopBar.setVisibility(View.GONE);
        binding.llSelectToolbar.setVisibility(View.VISIBLE);
        updateSelectToolbar();
    }

    private void exitSelectMode() {
        if (galleryAdapter == null) return;
        galleryAdapter.setSelectMode(false);
        binding.llSelectToolbar.setVisibility(View.GONE);
        binding.llTopBar.setVisibility(View.VISIBLE);
    }

    private void updateSelectToolbar() {
        if (galleryAdapter == null) return;
        int count = galleryAdapter.getSelectedCount();
        if (count == 0) { exitSelectMode(); return; }
        binding.tvSelectCount.setText(count + " selected");
    }

    private void forwardSelection() {
        if (galleryAdapter == null || replyChatId == null || replyMessageId == null) {
            android.widget.Toast.makeText(this, "Can't forward — not opened from a chat", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.List<Integer> selected = galleryAdapter.getSelectedPositions();
        com.callx.app.conversation.GalleryForwardBridge.requestForward(replyChatId, replyMessageId, selected);
        exitSelectMode();
        finish();
        overridePendingTransition(0, 0);
    }

    private void deleteSelection() {
        if (galleryAdapter == null || replyChatId == null || replyMessageId == null) return;
        java.util.List<Integer> selected = new ArrayList<>(galleryAdapter.getSelectedPositions());
        if (selected.isEmpty()) return;
        com.callx.app.utils.AlertDialogStyler.showReusableConfirm(this,
                "media_viewer_delete_selection", com.callx.app.utils.AlertDialogStyler.DialogSize.DEFAULT,
                selected.size() == 1 ? "Delete this item?" : "Delete " + selected.size() + " items?",
                "This can't be undone.",
                "Delete", () -> {
                    // Highest index first so earlier indices stay valid as
                    // each delete request is queued (bridge is one-shot, so
                    // queue them with small delays — same pattern used for
                    // multi-forward sends elsewhere in this codebase).
                    java.util.Collections.sort(selected, java.util.Collections.reverseOrder());
                    for (int idx = 0; idx < selected.size(); idx++) {
                        final int pos = selected.get(idx);
                        binding.getRoot().postDelayed(() ->
                            com.callx.app.conversation.GalleryItemActionBridge.request(
                                replyChatId, replyMessageId, pos,
                                com.callx.app.conversation.GalleryItemActionBridge.ACTION_DELETE_ITEM, null),
                            idx * 50L);
                    }
                    exitSelectMode();
                    finish();
                    overridePendingTransition(0, 0);
                },
                null, null,
                "Cancel");
    }

    private void starSelection() {
        if (galleryAdapter == null || replyChatId == null || replyMessageId == null) return;
        java.util.List<Integer> selected = galleryAdapter.getSelectedPositions();
        for (int idx = 0; idx < selected.size(); idx++) {
            final int pos = selected.get(idx);
            binding.getRoot().postDelayed(() ->
                com.callx.app.conversation.GalleryItemActionBridge.request(
                    replyChatId, replyMessageId, pos,
                    com.callx.app.conversation.GalleryItemActionBridge.ACTION_STAR_ITEM, null),
                idx * 50L);
        }
        android.widget.Toast.makeText(this, "Starred", android.widget.Toast.LENGTH_SHORT).show();
        exitSelectMode();
    }

    // ── #4/#2 — single-item more-options menu (delete / star / edit caption) ─
    private void showMoreOptionsMenu() {
        boolean isGalleryMode = galleryItems != null && !galleryItems.isEmpty();
        java.util.List<String> labels = new ArrayList<>();
        labels.add("Save to gallery");
        labels.add("Share");
        if (isGalleryMode && replyChatId != null && replyMessageId != null) {
            labels.add("Select multiple");
            labels.add("Remove this item from group");
            labels.add("Star this item");
            labels.add("Edit caption for this item");
        }
        new android.app.AlertDialog.Builder(this)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    String chosen = labels.get(which);
                    switch (chosen) {
                        case "Save to gallery": saveCurrentToGallery(); break;
                        case "Share": shareMedia(sharedUrl); break;
                        case "Select multiple": enterSelectMode(galleryActivePos); break;
                        case "Remove this item from group": deleteSingleActiveItem(); break;
                        case "Star this item": starSingleActiveItem(); break;
                        case "Edit caption for this item": editCaptionForActiveItem(); break;
                        default: break;
                    }
                })
                .show();
    }

    private void deleteSingleActiveItem() {
        if (galleryActivePos < 0 || replyChatId == null || replyMessageId == null) return;
        com.callx.app.utils.AlertDialogStyler.showReusableConfirm(this,
                "media_viewer_delete_single", com.callx.app.utils.AlertDialogStyler.DialogSize.DEFAULT,
                "Delete this item?", "This can't be undone.",
                "Delete", () -> {
                    com.callx.app.conversation.GalleryItemActionBridge.request(
                            replyChatId, replyMessageId, galleryActivePos,
                            com.callx.app.conversation.GalleryItemActionBridge.ACTION_DELETE_ITEM, null);
                    finish();
                    overridePendingTransition(0, 0);
                },
                null, null,
                "Cancel");
    }

    private void starSingleActiveItem() {
        if (galleryActivePos < 0 || replyChatId == null || replyMessageId == null) return;
        com.callx.app.conversation.GalleryItemActionBridge.request(
                replyChatId, replyMessageId, galleryActivePos,
                com.callx.app.conversation.GalleryItemActionBridge.ACTION_STAR_ITEM, null);
        android.widget.Toast.makeText(this, "Starred", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void editCaptionForActiveItem() {
        if (galleryActivePos < 0 || replyChatId == null || replyMessageId == null) return;
        final android.widget.EditText input = new android.widget.EditText(this);
        Object existing = galleryItems.get(galleryActivePos).get("caption");
        if (existing instanceof String) input.setText((String) existing);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Caption for this photo")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String newCaption = input.getText() != null ? input.getText().toString().trim() : "";
                    com.callx.app.conversation.GalleryItemActionBridge.request(
                            replyChatId, replyMessageId, galleryActivePos,
                            com.callx.app.conversation.GalleryItemActionBridge.ACTION_EDIT_CAPTION, newCaption);
                    // Reflect immediately in this still-open viewer too.
                    galleryItems.get(galleryActivePos).put("caption", newCaption);
                    if (galleryAdapter != null) galleryAdapter.notifyItemChanged(galleryActivePos);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── "Edit" — view a photo/video, tweak it in MediaEditActivity, resend ─
    private void onEditClicked() {
        if (replyChatId == null || replyMessageId == null) {
            Toast.makeText(this, "Can't edit — not opened from a chat", Toast.LENGTH_SHORT).show();
            return;
        }
        String url = sharedUrl;
        if (url == null || url.isEmpty()) return;
        boolean isVideo = isGalleryActiveVideo();

        // MediaEditActivity reads via ContentResolver, so a remote http(s)
        // URL won't work directly — resolve to the same locally-cached
        // File that Save/Share already use, downloading first if needed.
        File cached = MediaCache.getCached(this, url);
        if (cached != null) {
            launchEditorFor(cached, isVideo);
            return;
        }
        showLoading(true);
        MediaCache.get(this, url, new MediaCache.Callback() {
            @Override public void onReady(File file) {
                showLoading(false);
                launchEditorFor(file, isVideo);
            }
            @Override public void onError(String reason) {
                showLoading(false);
                Toast.makeText(MediaViewerActivity.this, "Couldn't load media for editing", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void launchEditorFor(File file, boolean isVideo) {
        Uri contentUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        grantUriPermission(getPackageName(), contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);

        Intent intent = new Intent(this, com.callx.app.conversation.controllers.MediaEditActivity.class);
        ArrayList<String> uris = new ArrayList<>();
        uris.add(contentUri.toString());
        ArrayList<Integer> videoFlags = new ArrayList<>();
        videoFlags.add(isVideo ? 1 : 0);
        intent.putStringArrayListExtra(com.callx.app.conversation.controllers.MediaEditActivity.EXTRA_URIS, uris);
        intent.putIntegerArrayListExtra(com.callx.app.conversation.controllers.MediaEditActivity.EXTRA_IS_VIDEO, videoFlags);
        intent.putExtra(com.callx.app.conversation.controllers.MediaEditActivity.EXTRA_CAPTION, "");
        intent.putExtra(com.callx.app.conversation.controllers.MediaEditActivity.EXTRA_HD, false);
        mediaEditLauncher.launch(intent);
    }

    // ── #3 — Save current media to device gallery ────────────────────────
    private void saveCurrentToGallery() {
        if (sharedUrl == null || sharedUrl.isEmpty()) return;
        boolean isVideo = isGalleryActiveVideo();
        android.widget.Toast.makeText(this, "Saving…", android.widget.Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                File cached = MediaCache.getCached(this, sharedUrl);
                File source;
                if (cached != null) {
                    source = cached;
                } else if (mediaDecryptKey != null) {
                    // Media E2E: sharedUrl is ciphertext — a raw byte-copy
                    // (the old fallback below) would save undecodable
                    // garbage to the gallery. Route through the decrypting
                    // MediaCache instead, same fix as loadImageProgressive.
                    final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                    final File[] result = new File[1];
                    MediaCache.get(this, sharedUrl, mediaDecryptKey, new MediaCache.Callback() {
                        @Override public void onReady(File f) { result[0] = f; latch.countDown(); }
                        @Override public void onError(String r) { latch.countDown(); }
                    });
                    latch.await();
                    if (result[0] == null) throw new java.io.IOException("Decrypt failed");
                    source = result[0];
                } else {
                    // Blocking download fallback (off main thread already).
                    java.io.InputStream in = new java.net.URL(sharedUrl).openStream();
                    File tmp = File.createTempFile("save_", isVideo ? ".mp4" : ".jpg", getCacheDir());
                    try (java.io.OutputStream out = new java.io.FileOutputStream(tmp)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    }
                    in.close();
                    source = tmp;
                }
                String displayName = "CallX2_" + System.currentTimeMillis() + (isVideo ? ".mp4" : ".jpg");
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName);
                values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, isVideo ? "video/mp4" : "image/jpeg");
                Uri collection;
                if (isVideo) {
                    values.put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/CallX2");
                    collection = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else {
                    values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CallX2");
                    collection = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                }
                Uri dest = getContentResolver().insert(collection, values);
                if (dest != null) {
                    try (java.io.InputStream in = new java.io.FileInputStream(source);
                         java.io.OutputStream out = getContentResolver().openOutputStream(dest)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    }
                    runOnUiThread(() -> android.widget.Toast.makeText(this, "Saved to gallery", android.widget.Toast.LENGTH_SHORT).show());
                } else {
                    runOnUiThread(() -> android.widget.Toast.makeText(this, "Save failed", android.widget.Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                runOnUiThread(() -> android.widget.Toast.makeText(this, "Save failed", android.widget.Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private boolean isGalleryActiveVideo() {
        if (galleryItems != null && galleryActivePos >= 0 && galleryActivePos < galleryItems.size()) {
            return "video".equals(galleryItems.get(galleryActivePos).get("mediaType"));
        }
        return "video".equals(getIntent().getStringExtra("type"));
    }

    private void toggleUI() {
        uiVisible = !uiVisible;
        LinearLayout topBar = binding.llTopBar;
        if (uiVisible) {
            topBar.setVisibility(View.VISIBLE);
            topBar.animate().alpha(1f).setDuration(200).start();
        } else {
            topBar.animate().alpha(0f).setDuration(200).withEndAction(
                () -> topBar.setVisibility(View.GONE)
            ).start();
        }
    }

    // ── Progressive image load ────────────────────────────────────
    private void loadImageProgressive(String fullUrl, String thumbUrl) {
        loadImageProgressive(fullUrl, thumbUrl, null);
    }

    private void loadImageProgressive(String fullUrl, String thumbUrl, String localPath) {
        PhotoView pv = binding.ivFull;

        // WhatsApp-style local-first: original local file still on the
        // device → show it directly, full quality, and skip the remote
        // load entirely. Falls back to the normal progressive-load path
        // below the moment the file's gone.
        if (localPath != null && !localPath.isEmpty()
                && com.callx.app.utils.LocalMediaAvailability.isAvailable(this, localPath)) {
            Glide.with(this)
                .load(Uri.parse(localPath))
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .into(pv);
            return;
        }

        if (thumbUrl != null && !thumbUrl.isEmpty()) {
            Glide.with(this)
                .load(thumbUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .override(400, 400)
                .into(pv);

            Glide.with(this)
                .load(fullUrl)
                .thumbnail(Glide.with(this)
                    .load(thumbUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL))
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .transition(com.bumptech.glide.load.resource.drawable
                    .DrawableTransitionOptions.withCrossFade(500))
                .into(pv);
        } else {
            File cachedImg = MediaCache.getCached(this, fullUrl);
            if (cachedImg != null) {
                Glide.with(this).load(cachedImg)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(pv);
            } else if (mediaDecryptKey != null) {
                // Media E2E: fullUrl is ciphertext (resource_type=raw) — it
                // can't be Glide-loaded directly (that used to happen here
                // unconditionally, showing a broken image). Decrypt via
                // MediaCache first, then load the resulting plaintext file.
                MediaCache.get(this, fullUrl, mediaDecryptKey, new MediaCache.Callback() {
                    @Override public void onReady(File f) {
                        Glide.with(MediaViewerActivity.this).load(f)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .into(pv);
                    }
                    @Override public void onError(String r) {
                        Toast.makeText(MediaViewerActivity.this,
                                "Couldn't decrypt photo: " + r, Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                Glide.with(this).load(fullUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(pv);
                MediaCache.get(this, fullUrl, new MediaCache.Callback() {
                    @Override public void onReady(File f) {}
                    @Override public void onError(String r) {}
                });
            }
        }
    }

    // ── Video playback (cache-first) ──────────────────────────────
    private void playVideo(String url) {
        playVideo(url, null);
    }

    private void playVideo(String url, String localPath) {
        // WhatsApp-style local-first: original local file still on the
        // device → play it directly, full quality, no download at all.
        // Falls back to the normal cache-first remote path the moment
        // the file's gone (deleted from gallery, storage cleared, etc).
        if (localPath != null && !localPath.isEmpty()
                && com.callx.app.utils.LocalMediaAvailability.isAvailable(this, localPath)) {
            startExoPlayer(Uri.parse(localPath));
            return;
        }

        File cached = MediaCache.getCached(this, url);
        if (cached != null) {
            startExoPlayer(Uri.fromFile(cached));
            return;
        }
        showLoading(true);
        MediaCache.get(this, url, new MediaCache.Callback() {
            @Override public void onReady(File file) {
                showLoading(false);
                startExoPlayer(Uri.fromFile(file));
            }
            @Override public void onError(String reason) {
                showLoading(false);
                startExoPlayer(Uri.parse(url));
            }
        });
    }

    private void startExoPlayer(Uri uri) {
        if (isFinishing() || isDestroyed()) return;
        player = new ExoPlayer.Builder(this).build();
        binding.player.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(uri));
        player.prepare();
        player.setPlayWhenReady(true);

        // Mirror actual play/pause state into chatPlayback — onIsPlayingChanged
        // fires for user pause/resume AND for buffering stalls, which is
        // exactly the granularity we want for a "watching…" badge.
        if (playbackChatId != null && messageIdPresent()) {
            player.addListener(new Player.Listener() {
                @Override public void onIsPlayingChanged(boolean isPlaying) {
                    publishPlaybackPresence(isPlaying);
                }
            });
        }
    }

    private boolean messageIdPresent() {
        return playbackMessageId != null && !playbackMessageId.isEmpty();
    }

    private void publishPlaybackPresence(boolean playing) {
        if (playbackChatId == null || !messageIdPresent()) return;
        String uid = FirebaseUtils.getCurrentUid();
        if (uid == null || uid.isEmpty()) return;
        if (playbackRef == null) {
            playbackRef = FirebaseUtils.getChatPlaybackRef(playbackChatId).child(uid);
        }
        if (playing == playbackPublished) return;
        playbackPublished = playing;
        if (playing) {
            playbackRef.setValue(playbackMessageId);
            // Safety net: if the app dies mid-playback, Firebase clears it for us.
            playbackRef.onDisconnect().removeValue();
        } else {
            playbackRef.removeValue();
            playbackRef.onDisconnect().cancel();
        }
    }

    // ── Share ─────────────────────────────────────────────────────
    private void shareMedia(String url) {
        if (url == null) return;
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, url);
        startActivity(Intent.createChooser(shareIntent, "Share via"));
    }

    private void showLoading(boolean show) {
        runOnUiThread(() -> binding.pbLoading.setVisibility(show ? View.VISIBLE : View.GONE));
    }

    private void hideSystemUI() {
        WindowInsetsControllerCompat controller =
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    @Override
    protected void onDestroy() {
        if (player != null) { player.release(); player = null; }
        if (binding != null && binding.mediaPager.getChildCount() > 0
                && binding.mediaPager.getChildAt(0) instanceof androidx.recyclerview.widget.RecyclerView) {
            androidx.recyclerview.widget.RecyclerView rv =
                    (androidx.recyclerview.widget.RecyclerView) binding.mediaPager.getChildAt(0);
            for (int i = 0; i < rv.getChildCount(); i++) {
                androidx.recyclerview.widget.RecyclerView.ViewHolder vh =
                        rv.getChildViewHolder(rv.getChildAt(i));
                if (vh instanceof GalleryPagerAdapter.PageVH && galleryAdapter != null) {
                    galleryAdapter.releasePlayer((GalleryPagerAdapter.PageVH) vh);
                }
            }
        }
        // Viewer is closing — clear the "watching…" badge immediately rather
        // than waiting on onDisconnect (that's only the crash/kill safety net).
        publishPlaybackPresence(false);
        super.onDestroy();
    }
}
