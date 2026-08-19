package com.callx.app.profile;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.cardview.widget.CardView;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.callx.app.models.ReelModel;
import com.callx.app.player.AdaptiveStreamingManager;
import com.callx.app.player.ExoPlayerPool;
import com.callx.app.reels.R;
import com.callx.app.utils.MediaSwipeReplyCloseHelper;

import java.util.List;
import java.util.Locale;

/**
 * Owns the Instagram/iOS-style long-press "peek" popup used by
 * UserReelsActivity's grid: a small floating video preview card + a
 * compact quick-action sheet, shown centered over a dimmed scrim without
 * navigating away from the grid.
 *
 * show() fires the moment the hold crosses the long-press timeout
 * (ReelGridAdapter.LongPressListener). Unlike a typical "hold to peek,
 * release to close" gesture, dismiss() is NOT tied to the finger lifting —
 * UserReelsActivity no longer wires ReelGridAdapter.LongPressReleaseListener
 * to close this popup, so the preview stays open after release. It closes
 * only when the user taps the dimmed scrim OUTSIDE the mini player/card
 * (see the scrim click listener below), taps "Watch Reel", or the host
 * activity tears it down (onPause/onDestroy/tab switch).
 *
 * ULTRA: long-press now does ONLY this — the mini player preview. It no
 * longer also fires an AlertDialog or multi-select alongside itself (that
 * was the bug: both used to fire together on the same long-press). Any
 * per-reel management options (Insights/Pin/Share/Delete) are passed in via
 * the `options` list and rendered as a second small tight card
 * (card_peek_options) in this SAME popup, below the actions sheet — its own
 * area, separate from the mini player — instead of a system AlertDialog.
 * Multi-select is no longer reachable from long-press at all — it now lives
 * in the 3-dot menu (see UserReelsActivity#setupMoreMenu()).
 *
 * FAST-SWITCH UPGRADE: when show() is called again WHILE a peek is already
 * up (the user fast long-pressed a different grid cell before dismissing
 * the current one), the popup is no longer torn down and rebuilt from
 * scratch. Instead switchTo() reuses the same PopupWindow/content view and
 * preloads the new reel's preview in the background — the reel that was
 * already playing keeps playing right up until the new one is actually
 * ready, at which point they're swapped and the old player is released.
 * A monotonically-updated `pendingToken` makes sure that if the user keeps
 * fast-switching, only the most recently requested reel ever wins the swap.
 */
public class ReelPeekPreviewController {

    /**
     * Backdrop blur tuning — Instagram-style: the scrim no longer goes fully
     * opaque, it stops at SCRIM_MAX_ALPHA so the blurred screenshot behind it
     * (iv_peek_blur_bg) still shows through. BLUR_DOWNSCALE shrinks the
     * captured screenshot before blurring (cheap + blur hides the softness),
     * BLUR_RADIUS is the box-blur radius applied to that shrunk copy.
     */
    private static final float SCRIM_MAX_ALPHA = 0.55f;
    private static final int   BLUR_DOWNSCALE  = 4;
    private static final int   BLUR_RADIUS     = 14;

    // ── Telegram-style dock-to-source close animation ────────────────────
    // Same technique as MediaViewerActivity's animateCloseToSource: instead
    // of PopupWindow's instant teardown, the peek card shrinks/translates
    // back into the exact on-screen rect of the grid cell it was
    // long-pressed from, then the popup actually dismisses. Reused here
    // (not duplicated pixel-for-pixel) as its own small animator since this
    // is a PopupWindow content view, not an Activity window — MediaViewerActivity's
    // version also drives live corner-radius via MediaSwipeReplyCloseHelper,
    // which the peek card has no equivalent of, so this sticks to
    // translate+scale+alpha, which is the part that actually reads as
    // "docking back into the grid".
    private static final long DOCK_ANIM_BASE_MS = 300L;
    private static final long DOCK_ANIM_MIN_MS  = 170L;
    private static final android.view.animation.Interpolator DOCK_EASE =
            new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);

    /** Screen rect of the grid cell that was long-pressed to open the currently-showing peek (null if not supplied — falls back to instant dismiss). */
    private Rect sourceRect;

    // ── Swipe-up/down-to-close (Telegram/MediaViewerActivity gesture) ────
    // Same MediaSwipeReplyCloseHelper the full-screen media viewer uses —
    // live drag shrinks+translates the card and reveals the screen behind
    // it (scrim fade) in lockstep with the drag, not just on release.
    // Wired via PopupWindow#setTouchInterceptor since a popup has no
    // Activity#dispatchTouchEvent of its own to hook into — that's the
    // popup-window equivalent of the same "see every touch event first"
    // requirement MediaViewerActivity satisfies at the Activity level.
    private MediaSwipeReplyCloseHelper swipeHelper;

    /** One row in the compact options card (card_peek_options). */
    public static class PeekOption {
        public final String label;
        public final int    iconRes;
        public final Runnable action;
        public PeekOption(String label, int iconRes, Runnable action) {
            this.label = label; this.iconRes = iconRes; this.action = action;
        }
    }

    public interface Callback {
        /** "Watch Reel" tapped. */
        void onWatchFull();
    }

    /**
     * Remembers the mute state across peeks — shared process-wide (static)
     * rather than per-controller-instance, so muting/unmuting the preview
     * on the profile grid also carries over to the SoundDetail mini
     * player and vice versa, and reopening a peek never silently resets
     * back to muted. Defaults to muted for a brand-new app process, same
     * as the original behavior.
     */
    private static boolean lastMuted = true;

    private final Activity activity;
    private PopupWindow popupWindow;
    private View         currentContent;   // reused across fast switches; null once dismissed
    private ExoPlayer    player;           // currently visible/playing preview
    private ExoPlayer    pendingPlayer;    // preloading preview for a reel we're switching to
    private String       pendingToken;     // guards stale preload callbacks on rapid re-switching
    private boolean      showing = false;
    private boolean      optionsExpanded = false;
    private boolean      muted = lastMuted;
    private View         cardPeekOptions;
    // Cached refs to currentContent's animatable pieces — set once in
    // buildAndShow(), read by dismissAnimated() so it doesn't need to
    // re-findViewById() the popup content on every close.
    private View         peekContentView;
    private View         scrimView;
    private View         blurBgView; // the blurred-screenshot backdrop — dissolved (not just the scrim) on swipe/dock close so what's revealed behind the card is the real live screen, not a still-opaque blur

    private ReelModel        currentReel;
    private List<PeekOption> currentOptions;
    private Callback         currentCallback;

    // ── Optional per-call size override ─────────────────────────────────
    // Every existing caller (UserReelsActivity's grid, SoundDetailFragment's
    // mini player) keeps the original fixed 331x475dp card — that XML
    // default is untouched. A caller that needs a bigger card (e.g. the
    // Home feed's "Suggested reels" row long-press, sized to match a
    // full-width preview) can pass explicit pixel dimensions via the
    // 6-arg show() overload below instead. Null means "use the XML default".
    private Integer overrideCardWidthPx  = null;
    private Integer overrideVideoHeightPx = null;

    // ── Optional per-call position override ─────────────────────────────
    // Every existing caller keeps the shared centered popup position (XML
    // layout_gravity="center" on layout_peek_content, untouched). Only a
    // caller that also passes anchorAboveSource=true in the 7-arg show()
    // overload below gets the card nudged up to sit directly above
    // sourceView's on-screen rect instead — used by ReelSharePeekBridge
    // (feature-chat) so the chat screen's reel-share peek opens above the
    // share card rather than dead-center of the screen. Reset on every
    // show() call so it never leaks from one caller/screen to another.
    private boolean anchorAboveSourceForThisShow = false;

    public ReelPeekPreviewController(Activity activity) {
        this.activity = activity;
    }

    public boolean isShowing() { return showing; }

    /**
     * @param reel     reel to preview — no-op if null or lacking a video URL to preview.
     * @param options  management options shown in the compact card below the
     *                 actions sheet when "Options" is tapped (e.g. Insights/
     *                 Pin/Share/Delete for the reel owner). Pass null or an
     *                 empty list to hide the "Options" row entirely — used
     *                 for reels the current user doesn't manage.
     * @param sourceView the long-pressed grid cell — its on-screen rect is
     *                    where the peek card docks back into on close (scrim
     *                    tap / back press). Pass null to fall back to the
     *                    plain instant dismiss (no docking target to animate to).
     */
    public void show(ReelModel reel, List<PeekOption> options, Callback callback, View sourceView) {
        show(reel, options, callback, sourceView, null, null);
    }

    /**
     * Same as {@link #show(ReelModel, List, Callback, View)} but lets the
     * caller override the mini player card's size in pixels instead of
     * using the shared XML default (331x475dp). Pass null for either arg
     * to keep that dimension at its default.
     *
     * @param cardWidthPx   overrides card_peek's (and the two sheet cards
     *                      below it, so they stay aligned) width, in px.
     * @param videoHeightPx overrides just the video frame's height, in px —
     *                      the card's overall height is wrap_content, so this
     *                      is effectively the mini player's visible height.
     */
    public void show(ReelModel reel, List<PeekOption> options, Callback callback, View sourceView,
                      Integer cardWidthPx, Integer videoHeightPx) {
        show(reel, options, callback, sourceView, cardWidthPx, videoHeightPx, false);
    }

    /**
     * Same as {@link #show(ReelModel, List, Callback, View, Integer, Integer)}
     * but additionally lets the caller anchor the card directly above
     * sourceView's on-screen rect instead of the shared dead-center
     * position, once its (possibly overridden) size is known after the
     * first layout pass. Every existing caller keeps calling one of the
     * shorter overloads (which pass false here), so their centered popup
     * is completely unaffected.
     *
     * @param anchorAboveSource true to dock the popped-out card just above
     *                          sourceView instead of screen-center.
     */
    public void show(ReelModel reel, List<PeekOption> options, Callback callback, View sourceView,
                      Integer cardWidthPx, Integer videoHeightPx, boolean anchorAboveSource) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (reel == null) return;

        overrideCardWidthPx   = cardWidthPx;
        overrideVideoHeightPx = videoHeightPx;
        anchorAboveSourceForThisShow = anchorAboveSource;
        sourceRect = captureScreenRect(sourceView);

        if (showing && popupWindow != null && currentContent != null) {
            // A peek is already open — fast long-press onto another grid
            // cell (or, for chat, a second reel-share bubble dwelling to
            // 3s while the first peek is still up) lands here instead of
            // dismiss()+rebuild.
            switchTo(reel, options, callback);
            return;
        }
        buildAndShow(reel, options, callback);
    }

    /** Applies overrideCardWidthPx/overrideVideoHeightPx (if set) to the
     *  freshly-inflated popup content. No-op — leaves the XML defaults in
     *  place — when both overrides are null, so every existing caller
     *  (which never sets them) is unaffected. */
    private void applySizeOverride(View content) {
        if (content == null) return;
        if (overrideCardWidthPx != null) {
            View cardPeek        = content.findViewById(R.id.card_peek);
            View cardPeekActions = content.findViewById(R.id.card_peek_actions);
            View cardPeekOptionsV = content.findViewById(R.id.card_peek_options);
            for (View v : new View[]{cardPeek, cardPeekActions, cardPeekOptionsV}) {
                if (v == null) continue;
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                if (lp != null) { lp.width = overrideCardWidthPx; v.setLayoutParams(lp); }
            }
        }
        if (overrideVideoHeightPx != null) {
            View videoFrame = content.findViewById(R.id.frame_peek_video);
            if (videoFrame != null) {
                ViewGroup.LayoutParams lp = videoFrame.getLayoutParams();
                if (lp != null) { lp.height = overrideVideoHeightPx; videoFrame.setLayoutParams(lp); }
            }
        }
    }

    private Rect captureScreenRect(View v) {
        if (v == null || v.getWidth() == 0 || v.getHeight() == 0) return null;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return new Rect(loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight());
    }

    // ── First open of the popup ─────────────────────────────────────────

    private void buildAndShow(ReelModel reel, List<PeekOption> options, Callback callback) {
        View content = LayoutInflater.from(activity).inflate(R.layout.popup_reel_peek, null, false);
        currentContent = content;
        applySizeOverride(content);

        View scrim              = content.findViewById(R.id.view_peek_scrim);
        ImageView blurBg        = content.findViewById(R.id.iv_peek_blur_bg);
        View peekContent        = content.findViewById(R.id.layout_peek_content);
        PlayerView playerView   = content.findViewById(R.id.peek_player_view);
        ProgressBar loading     = content.findViewById(R.id.peek_loading);
        TextView btnPlay        = content.findViewById(R.id.btn_peek_play);
        View muteBadge          = content.findViewById(R.id.layout_peek_mute_badge);
        android.widget.ImageView ivMuteIcon = content.findViewById(R.id.iv_peek_mute_icon);
        peekContentView = peekContent;
        scrimView = scrim;
        blurBgView = blurBg;

        bindStaticContent(content, reel, options, callback);

        if (btnPlay != null) {
            btnPlay.setOnClickListener(v -> {
                dismiss();
                if (currentCallback != null) currentCallback.onWatchFull();
            });
        }
        if (scrim != null) scrim.setOnClickListener(v -> dismissAnimated());

        popupWindow = new PopupWindow(content,
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, true);
        popupWindow.setTouchable(true);
        popupWindow.setOutsideTouchable(false);
        popupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popupWindow.setAnimationStyle(0);

        // Swipe-up/down-to-close — see swipeHelper field doc. peekContent is
        // the drag view (shrinks/translates/fades), scrim is what reveals
        // the grid behind it as the drag progresses.
        swipeHelper = new MediaSwipeReplyCloseHelper(activity, peekContent, scrim, null, null,
                new MediaSwipeReplyCloseHelper.Callback() {
                    @Override public void onSwipeUpReply() { /* retired — see class doc */ }
                    @Override public void onSwipeDownClose(float velocityY) {
                        dismissAnimated(velocityY);
                    }
                });
        // Same real-screen reveal MediaViewerActivity's swipe-close already
        // has: the blurred backdrop dissolves along with the scrim as the
        // user drags, instead of staying opaque the whole time — see
        // MediaSwipeReplyCloseHelper#setExtraFadeViews doc.
        swipeHelper.setExtraFadeViews(blurBg);
        popupWindow.setTouchInterceptor((pv, event) -> swipeHelper != null && swipeHelper.onTouch(event));

        // Grab + blur a screenshot of whatever's on screen RIGHT NOW (the
        // grid, still un-obscured — the popup hasn't been shown yet) so
        // iv_peek_blur_bg has an Instagram-style blurred backdrop instead of
        // a flat dim. Must happen before showAtLocation() below, or we'd be
        // capturing the popup itself.
        //
        // Chat-only: anchorAboveSourceForThisShow skips this entirely, so
        // blurBg is simply never populated and stays at its XML-default
        // alpha="0" — the chat screen behind the mini player stays clear
        // (just the normal SCRIM_MAX_ALPHA dim below it), instead of the
        // blurred-screenshot backdrop every other caller still gets.
        if (!anchorAboveSourceForThisShow) {
            captureAndBlurBackdrop(blurBg, popupWindow);
        }

        content.setFocusableInTouchMode(true);
        content.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                // First back press just collapses the options card (if open)
                // instead of closing the whole preview, matching the "tight
                // card, separate area" feel — second press closes the peek.
                if (optionsExpanded && cardPeekOptions != null) {
                    toggleOptionsCard((CardView) cardPeekOptions);
                } else {
                    dismissAnimated();
                }
                return true;
            }
            return false;
        });

        View decor = activity.getWindow().getDecorView();
        popupWindow.showAtLocation(decor, Gravity.NO_GRAVITY, 0, 0);
        showing = true;
        content.requestFocus();

        // Reveal: scale up from thumbnail-ish size + fade in, mirroring an
        // Instagram/iOS peek "pop out" of the pressed cell.
        if (peekContent != null) {
            peekContent.setTranslationX(0f);
            peekContent.setTranslationY(0f);
            if (anchorAboveSourceForThisShow && sourceRect != null) {
                // Chat-only path (see anchorAboveSourceForThisShow doc):
                // position is computed and applied via explicit
                // LayoutParams BEFORE this view is ever measured/laid out
                // (using the already-known, possibly size-overridden,
                // card width/video height in px) — no need to wait on a
                // layout pass or nudge via translation afterward.
                applyChatAnchorPosition(peekContent);
            }
            startPeekRevealAnimation(peekContent);
        }
        // Only dims to SCRIM_MAX_ALPHA (not fully opaque) so the blurred
        // backdrop underneath stays visible, iOS/Instagram peek-style.
        //
        // Chat-only: anchorAboveSourceForThisShow skips this dim entirely
        // too (not just the blur, see captureAndBlurBackdrop skip above) —
        // scrim stays at its XML-default alpha="0" so the chat screen
        // behind the mini player renders fully clear, not just unblurred.
        // It's still there and still tap-to-dismiss (see the click
        // listener set on it above), just invisible.
        if (scrim != null && !anchorAboveSourceForThisShow) {
            scrim.animate().alpha(SCRIM_MAX_ALPHA).setDuration(180).start();
        }

        startPlayerForCurrentReel(reel, playerView, loading, muteBadge, ivMuteIcon, false);
    }

    // ── Fast switch to a different reel while already showing ──────────

    private void switchTo(ReelModel reel, List<PeekOption> options, Callback callback) {
        View content = currentContent;
        if (content == null) { buildAndShow(reel, options, callback); return; }
        applySizeOverride(content);

        PlayerView playerView   = content.findViewById(R.id.peek_player_view);
        ProgressBar loading     = content.findViewById(R.id.peek_loading);
        View muteBadge          = content.findViewById(R.id.layout_peek_mute_badge);
        android.widget.ImageView ivMuteIcon = content.findViewById(R.id.iv_peek_mute_icon);

        bindStaticContent(content, reel, options, callback);

        // Any expanded options card belonged to the previous reel's action
        // list — collapse it instantly rather than animate, since its rows
        // have already been rebuilt for the new reel by bindStaticContent().
        View cardOptions = content.findViewById(R.id.card_peek_options);
        if (cardOptions != null) cardOptions.setVisibility(View.GONE);

        // Fast-switch while anchored (chat: a second reel-share bubble
        // dwelled to 3s while the first peek was still open) — recompute
        // fresh (not additive) explicit LayoutParams for the new
        // sourceRect immediately.
        if (anchorAboveSourceForThisShow && sourceRect != null && peekContentView != null) {
            applyChatAnchorPosition(peekContentView);
        }

        startPlayerForCurrentReel(reel, playerView, loading, muteBadge, ivMuteIcon, true);
    }

    /** Shared reveal animation: scale up from thumbnail-ish size + fade in,
     *  mirroring an Instagram/iOS peek "pop out" of the pressed cell. Used
     *  as-is (centered) by every existing caller. */
    private void startPeekRevealAnimation(View peekContent) {
        peekContent.setScaleX(0.55f);
        peekContent.setScaleY(0.55f);
        peekContent.setAlpha(0f);
        peekContent.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
    }

    /**
     * Chat-only: replaces peekContent's shared centered
     * FrameLayout.LayoutParams (layout_gravity="center" from the XML) with
     * an explicit top/left position — directly above sourceRect,
     * horizontally centered on it — computed entirely from already-known
     * values (sourceRect + the card width/video height in px, either the
     * chat override or the XML default) so it's correct on the very first
     * frame, with no layout-pass race and no drift from repeated
     * additive nudges.
     *
     * Height is the sum of: card_peek (== the video frame height, since
     * neither has any content padding in the XML) + the 8dp margin above
     * card_peek_actions + card_peek_actions' own height. ReelSharePeekBridge
     * (the only caller that sets anchorAboveSource) always passes null
     * options, so card_peek_actions only ever shows the "Watch Reel" row
     * (46dp) plus its hairline divider (0.6dp) — the "Options" row and
     * card_peek_options stay View.GONE and contribute 0dp, exactly as
     * bindStaticContent() leaves them for a null/empty options list.
     */
    private void applyChatAnchorPosition(View peekContent) {
        if (peekContent == null || sourceRect == null || activity == null) return;
        ViewGroup.LayoutParams rawLp = peekContent.getLayoutParams();
        if (!(rawLp instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) rawLp;

        float density = activity.getResources().getDisplayMetrics().density;
        int cardWidthPx = overrideCardWidthPx != null ? overrideCardWidthPx
                : Math.round(331 * density);
        int videoHeightPx = overrideVideoHeightPx != null ? overrideVideoHeightPx
                : Math.round(475 * density);
        int actionsCardHeightPx = Math.round(46.6f * density); // btnPlay row + hairline divider
        int marginBetweenCardsPx = Math.round(8 * density);
        int totalHeightPx = videoHeightPx + marginBetweenCardsPx + actionsCardHeightPx;

        int gapPx = Math.round(12 * density);          // gap between card bottom and source top
        int edgePaddingPx = Math.round(12 * density);   // never render flush against a screen edge
        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;

        int desiredTop = sourceRect.top - gapPx - totalHeightPx;
        if (desiredTop < edgePaddingPx) desiredTop = edgePaddingPx; // never off the top edge

        int desiredLeft = sourceRect.centerX() - cardWidthPx / 2;
        if (desiredLeft < edgePaddingPx) desiredLeft = edgePaddingPx;
        if (desiredLeft + cardWidthPx > screenWidth - edgePaddingPx) {
            desiredLeft = screenWidth - edgePaddingPx - cardWidthPx;
        }

        lp.gravity = Gravity.NO_GRAVITY;
        lp.leftMargin = desiredLeft;
        lp.topMargin = desiredTop;
        peekContent.setLayoutParams(lp);
    }

    /** Text/counts/options card — shared between the first build and every fast switch. */
    private void bindStaticContent(View content, ReelModel reel, List<PeekOption> options, Callback callback) {
        currentReel = reel;
        currentOptions = options;
        currentCallback = callback;
        optionsExpanded = false;

        TextView tvCaption      = content.findViewById(R.id.tv_peek_caption);
        TextView tvDuration     = content.findViewById(R.id.tv_peek_duration);
        TextView tvViews        = content.findViewById(R.id.tv_peek_views);
        TextView tvLikes        = content.findViewById(R.id.tv_peek_likes);
        TextView btnSecondary   = content.findViewById(R.id.btn_peek_secondary);
        CardView cardOptions    = content.findViewById(R.id.card_peek_options);
        LinearLayout optionsRow = content.findViewById(R.id.layout_peek_options_rows);
        cardPeekOptions = cardOptions;

        if (tvCaption != null) {
            boolean has = reel.caption != null && !reel.caption.trim().isEmpty();
            if (has) {
                tvCaption.setText(reel.caption.trim());
                tvCaption.setVisibility(View.VISIBLE);
            } else {
                // Explicit GONE matters now that the same TextView is reused
                // across fast switches — a caption left over from the
                // previous reel would otherwise stay visible on one that has
                // none of its own.
                tvCaption.setVisibility(View.GONE);
            }
        }
        if (tvDuration != null) {
            if (reel.duration > 0) {
                int s = (reel.duration / 1000) % 60, m = reel.duration / 60000;
                tvDuration.setText(String.format(Locale.getDefault(), "%d:%02d", m, s));
                tvDuration.setVisibility(View.VISIBLE);
            } else {
                tvDuration.setVisibility(View.GONE);
            }
        }
        if (tvViews != null)  tvViews.setText(formatCount(Math.max(reel.viewsCount, 0)));
        if (tvLikes != null)  tvLikes.setText(formatCount(Math.max(reel.likesCount, 0)));

        boolean hasOptions = options != null && !options.isEmpty();
        if (cardOptions != null) cardOptions.setVisibility(View.GONE);
        if (btnSecondary != null) {
            if (hasOptions) {
                btnSecondary.setVisibility(View.VISIBLE);
                if (optionsRow != null) buildOptionRows(optionsRow, options, cardOptions);
                btnSecondary.setOnClickListener(v -> toggleOptionsCard(cardOptions));
            } else {
                btnSecondary.setVisibility(View.GONE);
            }
        }
    }

    /**
     * Starts (isSwitch == false) or preload-swaps (isSwitch == true) the
     * preview player for `reel`.
     *
     * ULTRA DATA OPTIMIZATION (unchanged from before): this card is only
     * ~230dp wide, so there is zero visual benefit to pulling 720p/1080p
     * bytes for it. Reuses the app's existing AdaptiveStreamingManager
     * (same cache pool + tuned buffering as the main reel player) so:
     *   • pickLowDataUrl() hands it the smallest asset the reel actually
     *     has (HLS manifest > explicit 480p > original) — for progressive
     *     MP4s a bitrate cap alone can't reduce bytes downloaded (there's
     *     only one rendition), so picking the smallest FILE is what
     *     actually saves data; for an HLS manifest the Q360P cap below
     *     then also stops ExoPlayer's adaptive selection from stepping
     *     up to a higher-bitrate segment.
     *   • QualityCap.Q360P caps resolution/bitrate for HLS/DASH sources.
     *   • UnifiedVideoCacheManager-backed CacheDataSource means a reel
     *     peeked more than once (or already partially cached from the
     *     main feed/player) is served from disk, not re-downloaded.
     *   • Network-tier-aware LoadControl keeps the upfront buffer small
     *     instead of eagerly reading far ahead of an on-screen preview
     *     the user might dismiss in under a second.
     *
     * FAST-SWITCH PATH (isSwitch == true): builds the new reel's player
     * and lets it buffer in the background WHILE the previous reel's
     * player keeps playing on-screen. Only once the new player reaches
     * STATE_READY do we swap PlayerView over to it and release the old
     * one — so back-to-back long-presses across the grid crossfade
     * smoothly instead of hard-cutting to a blank/spinner card. A
     * `pendingToken` check means only the most-recently-requested reel
     * ever gets swapped in, even if the user switches several times
     * before any one of them finishes buffering.
     */
    private void startPlayerForCurrentReel(ReelModel reel, PlayerView playerView, ProgressBar loading,
                                            View muteBadge, android.widget.ImageView ivMuteIcon,
                                            boolean isSwitch) {
        // Reflect the remembered/current mute state on the badge right
        // away — this no longer force-resets to "muted" on every open (see
        // the `muted` field / lastMuted above), so the icon must match
        // whatever `muted` actually holds at this point.
        if (ivMuteIcon != null) {
            ivMuteIcon.setImageResource(muted ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
            ivMuteIcon.setContentDescription(muted ? "Muted" : "Unmuted");
        }
        if (muteBadge != null) {
            muteBadge.setBackgroundResource(R.drawable.bg_reel_count);
            muteBadge.setAlpha(muted ? 0.85f : 1f);
        }

        if (!hasPreviewableVideo(reel) || playerView == null) {
            releasePendingPlayer();
            if (isSwitch && player != null) {
                try { player.release(); } catch (Throwable ignored) {}
                player = null;
                playerView.setPlayer(null);
            }
            if (loading != null) loading.setVisibility(View.GONE);
            return;
        }

        String previewUrl = pickLowDataUrl(reel);

        if (!isSwitch) {
            // ULTRA PERF (pooled reuse): acquire from the SAME ExoPlayerPool
            // the main reels feed prewarms into, instead of building a
            // brand-new ExoPlayer (fresh renderers/codec setup + internal
            // playback thread — the actually-expensive part) on every single
            // peek open. Pool hands back an already-constructed idle
            // instance when one's free, and transparently falls back to a
            // throwaway bare player if the pool's momentarily exhausted
            // (e.g. feed also mid-scroll) — see ExoPlayerPool#acquire.
            ExoPlayerPool pool = ExoPlayerPool.get(activity);
            player = pool.acquire();
            Player.Listener abrListener = AdaptiveStreamingManager.get(activity)
                    .attachToPlayer(player, previewUrl, AdaptiveStreamingManager.QualityCap.Q360P, null);
            pool.trackListener(player, abrListener);
            player.setVolume(muted ? 0f : 1f);
            player.setRepeatMode(Player.REPEAT_MODE_ONE);
            playerView.setPlayer(player);
            final ProgressBar loadingRef = loading;
            Player.Listener bufferListener = new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    if (loadingRef != null) {
                        loadingRef.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                    }
                }
            };
            player.addListener(bufferListener);
            pool.trackListener(player, bufferListener);
            player.prepare();
            player.setPlayWhenReady(true);
            playerView.setOnClickListener(v -> toggleMute(muteBadge, ivMuteIcon));
            return;
        }

        // Fast switch: preload in the background, keep the old one playing
        // until the new one is ready. Also pooled — same ULTRA PERF reasoning
        // as above, and it matters MORE here: fast re-long-pressing across
        // several grid cells used to build a fresh ExoPlayer per cell.
        releasePendingPlayer();
        final String token = (reel.reelId != null) ? reel.reelId : String.valueOf(System.identityHashCode(reel));
        pendingToken = token;
        if (loading != null) loading.setVisibility(View.VISIBLE);

        final ExoPlayerPool pool = ExoPlayerPool.get(activity);
        final ExoPlayer newPlayer = pool.acquire();
        Player.Listener abrListener = AdaptiveStreamingManager.get(activity)
                .attachToPlayer(newPlayer, previewUrl, AdaptiveStreamingManager.QualityCap.Q360P, null);
        pool.trackListener(newPlayer, abrListener);
        newPlayer.setVolume(muted ? 0f : 1f);
        newPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
        pendingPlayer = newPlayer;

        final ProgressBar loadingRef = loading;
        final PlayerView playerViewRef = playerView;
        Player.Listener readyListener = new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY && pendingPlayer == newPlayer && token.equals(pendingToken)) {
                    // Still the most-recently-requested reel — swap it in
                    // and return whatever was showing before to the pool
                    // (not a hard release), so it's warm for the next peek
                    // instead of paying full construction cost again.
                    if (player != null) {
                        pool.release(player);
                    }
                    player = newPlayer;
                    pendingPlayer = null;
                    playerViewRef.setPlayer(player);
                    if (loadingRef != null) loadingRef.setVisibility(View.GONE);
                }
            }
        };
        newPlayer.addListener(readyListener);
        pool.trackListener(newPlayer, readyListener);
        newPlayer.prepare();
        newPlayer.setPlayWhenReady(true);
        playerView.setOnClickListener(v -> toggleMute(muteBadge, ivMuteIcon));
    }

    // ── Instagram-style blurred backdrop ────────────────────────────────

    /**
     * Captures a shrunk screenshot of the activity's decor view (the screen
     * as it looks right before the peek popup goes up), box-blurs it on a
     * background thread, then fades it into `target`. Pure Java/software —
     * no RenderScript or RenderEffect (API 31+ only) dependency — so it
     * works across the app's full minSdk range.
     *
     * The capture itself (view.draw) must run synchronously on the main
     * thread, but it draws directly into an already-downscaled bitmap
     * (canvas scaled by 1/BLUR_DOWNSCALE) so it's cheap — the blur math,
     * which is the actually expensive part, runs off the main thread.
     *
     * `builtPopup` guards against setting a bitmap into a popup the user
     * already dismissed by the time the background blur finishes (fast
     * long-press + quick release/tap-outside).
     */
    private void captureAndBlurBackdrop(ImageView target, PopupWindow builtPopup) {
        if (target == null) return;
        View decor = activity.getWindow().getDecorView();
        int w = decor.getWidth();
        int h = decor.getHeight();
        if (w <= 0 || h <= 0) return;

        int sw = Math.max(1, w / BLUR_DOWNSCALE);
        int sh = Math.max(1, h / BLUR_DOWNSCALE);

        Bitmap small;
        try {
            small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(small);
            canvas.scale(1f / BLUR_DOWNSCALE, 1f / BLUR_DOWNSCALE);
            decor.draw(canvas);
        } catch (Throwable t) {
            // Screenshotting can fail on some OEM skins/secure windows —
            // just skip the blur, scrim alone still dims the screen fine.
            return;
        }

        final int fullW = w, fullH = h;
        new Thread(() -> {
            try {
                boxBlur(small, BLUR_RADIUS);
                final Bitmap blurred = Bitmap.createScaledBitmap(small, fullW, fullH, true);
                if (!small.isRecycled()) small.recycle();
                new Handler(Looper.getMainLooper()).post(() -> {
                    // Bail if this popup isn't the one currently showing —
                    // avoids stamping a stale screenshot onto a reused/
                    // discarded content view.
                    if (popupWindow != builtPopup || !showing) return;
                    target.setImageBitmap(blurred);
                    target.animate().alpha(1f).setDuration(160)
                            .setInterpolator(new DecelerateInterpolator()).start();
                });
            } catch (Throwable ignored) {
                // Best-effort — a failed blur just leaves the plain scrim.
            }
        }).start();
    }

    /**
     * Separable two-pass box blur (horizontal then vertical), applied
     * in-place. Same approach as ReelBlurTransformation's photo-reel
     * backdrop blur — simple, correct, and fast on the small (downscaled)
     * bitmap this is called with.
     */
    private static void boxBlur(Bitmap bmp, int radius) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] pix = new int[w * h];
        bmp.getPixels(pix, 0, w, 0, 0, w, h);
        int[] tmp = new int[w * h];

        for (int y = 0; y < h; y++) {
            int rowOff = y * w;
            for (int x = 0; x < w; x++) {
                long rsum = 0, gsum = 0, bsum = 0;
                int count = 0;
                int xStart = Math.max(0, x - radius);
                int xEnd   = Math.min(w - 1, x + radius);
                for (int nx = xStart; nx <= xEnd; nx++) {
                    int c = pix[rowOff + nx];
                    rsum += (c >> 16) & 0xff;
                    gsum += (c >>  8) & 0xff;
                    bsum +=  c        & 0xff;
                    count++;
                }
                tmp[rowOff + x] = (pix[rowOff + x] & 0xff000000)
                        | ((int) (rsum / count) << 16)
                        | ((int) (gsum / count) <<  8)
                        |  (int) (bsum / count);
            }
        }

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                long rsum = 0, gsum = 0, bsum = 0;
                int count = 0;
                int yStart = Math.max(0, y - radius);
                int yEnd   = Math.min(h - 1, y + radius);
                for (int ny = yStart; ny <= yEnd; ny++) {
                    int c = tmp[ny * w + x];
                    rsum += (c >> 16) & 0xff;
                    gsum += (c >>  8) & 0xff;
                    bsum +=  c        & 0xff;
                    count++;
                }
                pix[y * w + x] = (tmp[y * w + x] & 0xff000000)
                        | ((int) (rsum / count) << 16)
                        | ((int) (gsum / count) <<  8)
                        |  (int) (bsum / count);
            }
        }

        bmp.setPixels(pix, 0, w, 0, 0, w, h);
    }

    private void releasePendingPlayer() {
        if (pendingPlayer != null) {
            // Pooled release, not a hard release — see ULTRA PERF note in
            // startPlayerForCurrentReel: this instance goes back into
            // ExoPlayerPool for the next peek/prewarm to reuse instead of
            // being torn down and rebuilt from scratch.
            try { ExoPlayerPool.get(activity).release(pendingPlayer); } catch (Throwable ignored) {}
            pendingPlayer = null;
        }
    }

    /** Flips the mini player's mute state and updates the badge to match. */
    private void toggleMute(View muteBadge, android.widget.ImageView ivMuteIcon) {
        if (player == null) return;
        muted = !muted;
        lastMuted = muted; // remember it for the next peek — on this screen or the other one
        player.setVolume(muted ? 0f : 1f);
        if (ivMuteIcon != null) {
            ivMuteIcon.setImageResource(muted ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
            ivMuteIcon.setContentDescription(muted ? "Muted" : "Unmuted");
        }
        if (muteBadge != null) {
            // Same pill background either way — just tint it to show state,
            // matching the reel feed's mute button treatment (gold when on).
            muteBadge.setBackgroundResource(R.drawable.bg_reel_count);
            muteBadge.setAlpha(muted ? 0.85f : 1f);
        }
    }

    /** True if the reel has ANY playable source for the mini preview. */
    private boolean hasPreviewableVideo(ReelModel reel) {
        return (reel.hlsManifestUrl != null && !reel.hlsManifestUrl.isEmpty())
                || (reel.videoUrl  != null && !reel.videoUrl.isEmpty())
                || (reel.video480  != null && !reel.video480.isEmpty())
                || (reel.video720  != null && !reel.video720.isEmpty())
                || (reel.video1080 != null && !reel.video1080.isEmpty());
    }

    /**
     * Smallest-byte-footprint URL available for this reel's mini preview.
     * Priority: HLS adaptive manifest (so the Q360P cap can actually
     * restrict which segments get fetched) > explicit 480p rendition >
     * 720p > 1080p > original videoUrl as the last resort — covers reels
     * that only populated the higher-quality fields.
     */
    private String pickLowDataUrl(ReelModel reel) {
        if (reel.hlsManifestUrl != null && !reel.hlsManifestUrl.isEmpty()) return reel.hlsManifestUrl;
        if (reel.video480  != null && !reel.video480.isEmpty())  return reel.video480;
        if (reel.videoUrl  != null && !reel.videoUrl.isEmpty())  return reel.videoUrl;
        if (reel.video720  != null && !reel.video720.isEmpty())  return reel.video720;
        return reel.video1080;
    }

    /** Builds one small row per PeekOption into container — tapping a row dismisses the whole peek then runs its action. */
    private void buildOptionRows(LinearLayout container, List<PeekOption> options, CardView cardOptions) {
        container.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(activity);
        for (int i = 0; i < options.size(); i++) {
            PeekOption opt = options.get(i);
            TextView row = new TextView(activity);
            int heightPx = (int) (42 * activity.getResources().getDisplayMetrics().density);
            int padPx    = (int) (16 * activity.getResources().getDisplayMetrics().density);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, heightPx));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(padPx, 0, padPx, 0);
            row.setText(opt.label);
            row.setTextColor(0xFF1C1C1C);
            row.setTextSize(13.5f);
            row.setBackgroundResource(android.R.drawable.list_selector_background);
            if (opt.iconRes != 0) {
                row.setCompoundDrawablesWithIntrinsicBounds(opt.iconRes, 0, 0, 0);
                row.setCompoundDrawablePadding((int) (10 * activity.getResources().getDisplayMetrics().density));
            }
            row.setOnClickListener(v -> { dismiss(); if (opt.action != null) opt.action.run(); });
            container.addView(row);
            if (i < options.size() - 1) {
                View divider = new View(activity);
                divider.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (int) (0.6f * activity.getResources().getDisplayMetrics().density)));
                divider.setBackgroundColor(0xFFD9D9D9);
                container.addView(divider);
            }
        }
    }

    /** Expands/collapses the compact options card in place — mini player keeps playing underneath the whole time. */
    private void toggleOptionsCard(CardView cardOptions) {
        if (cardOptions == null) return;
        optionsExpanded = !optionsExpanded;
        if (optionsExpanded) {
            cardOptions.setAlpha(0f);
            cardOptions.setVisibility(View.VISIBLE);
            cardOptions.setScaleY(0.85f);
            cardOptions.animate().alpha(1f).scaleY(1f)
                    .setDuration(140).setInterpolator(new DecelerateInterpolator()).start();
        } else {
            cardOptions.animate().alpha(0f).scaleY(0.85f)
                    .setDuration(120)
                    .withEndAction(() -> cardOptions.setVisibility(View.GONE))
                    .start();
        }
    }

    /** Safe to call any number of times, including when nothing is showing. */
    public void dismiss() {
        showing = false;
        optionsExpanded = false;
        cardPeekOptions = null;
        pendingToken = null;
        currentContent = null;
        currentReel = null;
        currentOptions = null;
        currentCallback = null;
        peekContentView = null;
        scrimView = null;
        blurBgView = null;
        sourceRect = null;
        swipeHelper = null;
        releasePendingPlayer();
        if (player != null) {
            // Pooled release (see ULTRA PERF note above) — dismissing a
            // peek is the common case (every close), so this is what
            // actually keeps the pool warm for the next open.
            try { ExoPlayerPool.get(activity).release(player); } catch (Throwable ignored) {}
            player = null;
        }
        if (popupWindow != null) {
            if (popupWindow.isShowing()) {
                try { popupWindow.dismiss(); } catch (Throwable ignored) {}
            }
            popupWindow = null;
        }
    }

    /** Scrim tap / back press — no drag velocity to seed from. */
    public void dismissAnimated() {
        dismissAnimated(0f);
    }

    /**
     * User-initiated close (scrim tap / back press / swipe-to-close) —
     * shrinks+translates the peek card back into {@link #sourceRect} (the
     * long-pressed grid cell) before actually tearing the popup down,
     * mirroring MediaViewerActivity's animateCloseToSource "chipakna" dock
     * animation. Starts from whatever transform the view is CURRENTLY at —
     * if this follows a live swipe-to-close drag, that's already
     * mid-shrink/translate (see swipeHelper), so the dock animation
     * continues seamlessly from there instead of jumping.
     *
     * @param velocityY signed px/sec from the swipe gesture that triggered
     *                  this close (0 for scrim-tap/back-press), shortens
     *                  the animation on a fast fling — same as
     *                  MediaViewerActivity#velocityAdjustedDuration.
     * Falls back to the plain instant {@link #dismiss()} when there's no
     * source rect to dock into (e.g. show() was called without a
     * sourceView) or the content isn't in a state to animate.
     */
    public void dismissAnimated(float velocityY) {
        final View v = peekContentView;
        if (sourceRect == null || v == null || v.getWidth() == 0 || v.getHeight() == 0
                || activity == null || activity.isFinishing() || activity.isDestroyed()) {
            dismiss();
            return;
        }

        v.animate().cancel();
        if (v.getLayerType() != View.LAYER_TYPE_HARDWARE) {
            v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }

        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        float startScaleX = v.getScaleX();
        float startScaleY = v.getScaleY();
        float curCenterX = loc[0] + (v.getWidth() * startScaleX) / 2f;
        float curCenterY = loc[1] + (v.getHeight() * startScaleY) / 2f;
        float targetCenterX = sourceRect.left + sourceRect.width() / 2f;
        float targetCenterY = sourceRect.top + sourceRect.height() / 2f;

        float startTx = v.getTranslationX();
        float startTy = v.getTranslationY();
        float endTx = startTx + (targetCenterX - curCenterX);
        float endTy = startTy + (targetCenterY - curCenterY);
        float endScaleX = v.getWidth()  > 0 ? sourceRect.width()  / (float) v.getWidth()  : 1f;
        float endScaleY = v.getHeight() > 0 ? sourceRect.height() / (float) v.getHeight() : 1f;
        float startAlpha = v.getAlpha();

        final View scrim = scrimView;
        float startScrimAlpha = scrim != null ? scrim.getAlpha() : 0f;
        final View blurBg = blurBgView;
        float startBlurAlpha = blurBg != null ? blurBg.getAlpha() : 0f;

        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(velocityAdjustedDuration(velocityY));
        anim.setInterpolator(DOCK_EASE);
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            v.setTranslationX(lerp(startTx, endTx, t));
            v.setTranslationY(lerp(startTy, endTy, t));
            v.setScaleX(lerp(startScaleX, endScaleX, t));
            v.setScaleY(lerp(startScaleY, endScaleY, t));
            v.setAlpha(lerp(startAlpha, 0f, t));
            if (scrim != null) scrim.setAlpha(lerp(startScrimAlpha, 0f, t));
            // Same real-screen reveal as the live drag (see setExtraFadeViews
            // wiring above) — dissolve the blurred backdrop along with the
            // scrim during this dock-back animation too, so a scrim-tap or
            // back-press close reveals the real screen just as smoothly as
            // an actual swipe does, not just the swipe path.
            if (blurBg != null) blurBg.setAlpha(lerp(startBlurAlpha, 0f, t));
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                v.setLayerType(View.LAYER_TYPE_NONE, null);
                dismiss();
            }
        });
        anim.start();
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    /** Mirrors MediaViewerActivity#velocityAdjustedDuration — a hard fling shortens the dock duration toward DOCK_ANIM_MIN_MS instead of always running the full base duration. */
    private long velocityAdjustedDuration(float velocityY) {
        float density = activity.getResources().getDisplayMetrics().density;
        float speedDpPerSec = Math.abs(velocityY) / density;
        float speedFactor = Math.min(1f, speedDpPerSec / 2500f);
        return Math.round(DOCK_ANIM_BASE_MS - speedFactor * (DOCK_ANIM_BASE_MS - DOCK_ANIM_MIN_MS));
    }

    private String formatCount(int n) {
        if (n >= 1_000_000) return String.format(Locale.getDefault(), "%.1fM", n / 1_000_000f);
        if (n >= 1_000)     return String.format(Locale.getDefault(), "%.1fK", n / 1_000f);
        return String.valueOf(n);
    }
}
