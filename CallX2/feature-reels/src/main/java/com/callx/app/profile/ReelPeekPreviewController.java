package com.callx.app.profile;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
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
import com.callx.app.reels.R;

import java.util.List;
import java.util.Locale;

/**
 * Owns the Instagram/iOS-style long-press "peek" popup used by
 * UserReelsActivity's grid: a small floating, muted-looping video preview
 * card + a compact quick-action sheet, shown centered over a dimmed scrim
 * without navigating away from the grid.
 *
 * show() fires the moment the hold crosses the long-press timeout
 * (ReelGridAdapter.LongPressListener). Unlike a typical "hold to peek,
 * release to close" gesture, dismiss() is NOT tied to the finger lifting —
 * UserReelsActivity no longer wires ReelGridAdapter.LongPressReleaseListener
 * to close this popup, so the preview stays open after release. It closes
 * only when the user taps the dimmed scrim OUTSIDE the mini player/card
 * (see the scrim click listener in show() below), taps "Watch Reel", or the
 * host activity tears it down (onPause/onDestroy/tab switch).
 *
 * ULTRA: long-press now does ONLY this — the mini player preview. It no
 * longer also fires an AlertDialog or multi-select alongside itself (that
 * was the bug: both used to fire together on the same long-press). Any
 * per-reel management options (Insights/Pin/Share/Delete) are passed in via
 * the `options` list and rendered as a second small tight card
 * (card_peek_options) in this SAME popup, below the actions sheet — its own
 * area, separate from the mini player — instead of a system AlertDialog.
 * Multi-select is no longer reachable from long-press at all; it now lives
 * in the 3-dot menu (see UserReelsActivity#setupMoreMenu()).
 */
public class ReelPeekPreviewController {

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

    private final Activity activity;
    private PopupWindow popupWindow;
    private ExoPlayer    player;
    private boolean      showing = false;
    private boolean      optionsExpanded = false;
    private boolean      muted = true;
    private View         cardPeekOptions;

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
     */
    public void show(ReelModel reel, List<PeekOption> options, Callback callback) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (reel == null) return;
        dismiss(); // only one peek at a time

        View content = LayoutInflater.from(activity).inflate(R.layout.popup_reel_peek, null, false);
        View scrim              = content.findViewById(R.id.view_peek_scrim);
        View peekContent        = content.findViewById(R.id.layout_peek_content);
        PlayerView playerView   = content.findViewById(R.id.peek_player_view);
        ProgressBar loading     = content.findViewById(R.id.peek_loading);
        TextView tvCaption      = content.findViewById(R.id.tv_peek_caption);
        TextView tvDuration     = content.findViewById(R.id.tv_peek_duration);
        TextView tvViews        = content.findViewById(R.id.tv_peek_views);
        TextView tvLikes        = content.findViewById(R.id.tv_peek_likes);
        TextView btnPlay        = content.findViewById(R.id.btn_peek_play);
        TextView btnSecondary   = content.findViewById(R.id.btn_peek_secondary);
        CardView cardOptions    = content.findViewById(R.id.card_peek_options);
        LinearLayout optionsRow = content.findViewById(R.id.layout_peek_options_rows);
        View muteBadge          = content.findViewById(R.id.layout_peek_mute_badge);
        android.widget.ImageView ivMuteIcon = content.findViewById(R.id.iv_peek_mute_icon);
        cardPeekOptions = cardOptions;
        optionsExpanded = false;
        muted = true; // every peek opens muted, same as before this change

        if (tvCaption != null) {
            boolean has = reel.caption != null && !reel.caption.trim().isEmpty();
            if (has) { tvCaption.setText(reel.caption.trim()); tvCaption.setVisibility(View.VISIBLE); }
        }
        if (tvDuration != null) {
            if (reel.duration > 0) {
                int s = (reel.duration / 1000) % 60, m = reel.duration / 60000;
                tvDuration.setText(String.format(Locale.getDefault(), "%d:%02d", m, s));
            } else {
                tvDuration.setVisibility(View.GONE);
            }
        }
        if (tvViews != null)  tvViews.setText(formatCount(Math.max(reel.viewsCount, 0)));
        if (tvLikes != null)  tvLikes.setText(formatCount(Math.max(reel.likesCount, 0)));

        boolean hasOptions = options != null && !options.isEmpty();
        if (btnSecondary != null) {
            if (hasOptions) {
                btnSecondary.setVisibility(View.VISIBLE);
                if (optionsRow != null) buildOptionRows(optionsRow, options, cardOptions);
                btnSecondary.setOnClickListener(v -> toggleOptionsCard(cardOptions));
            } else {
                btnSecondary.setVisibility(View.GONE);
            }
        }

        if (btnPlay != null) {
            btnPlay.setOnClickListener(v -> { dismiss(); if (callback != null) callback.onWatchFull(); });
        }
        if (scrim != null) scrim.setOnClickListener(v -> dismiss());

        popupWindow = new PopupWindow(content,
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, true);
        popupWindow.setTouchable(true);
        popupWindow.setOutsideTouchable(false);
        popupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popupWindow.setAnimationStyle(0);

        content.setFocusableInTouchMode(true);
        content.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                // First back press just collapses the options card (if open)
                // instead of closing the whole preview, matching the "tight
                // card, separate area" feel — second press closes the peek.
                if (optionsExpanded && cardOptions != null) {
                    toggleOptionsCard(cardOptions);
                } else {
                    dismiss();
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
            peekContent.setScaleX(0.55f);
            peekContent.setScaleY(0.55f);
            peekContent.setAlpha(0f);
            peekContent.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
        }
        if (scrim != null) scrim.animate().alpha(1f).setDuration(180).start();

        // Muted, looping preview — ULTRA DATA OPTIMIZATION: this card is only
        // ~230dp wide, so there is zero visual benefit to pulling 720p/1080p
        // bytes for it. Reuses the app's existing AdaptiveStreamingManager
        // (same cache pool + tuned buffering as the main reel player) so:
        //   • pickLowDataUrl() hands it the smallest asset the reel actually
        //     has (HLS manifest > explicit 480p > original) — for progressive
        //     MP4s a bitrate cap alone can't reduce bytes downloaded (there's
        //     only one rendition), so picking the smallest FILE is what
        //     actually saves data; for an HLS manifest the Q360P cap below
        //     then also stops ExoPlayer's adaptive selection from stepping
        //     up to a higher-bitrate segment.
        //   • QualityCap.Q360P caps resolution/bitrate for HLS/DASH sources.
        //   • UnifiedVideoCacheManager-backed CacheDataSource means a reel
        //     peeked more than once (or already partially cached from the
        //     main feed/player) is served from disk, not re-downloaded.
        //   • Network-tier-aware LoadControl keeps the upfront buffer small
        //     instead of eagerly reading far ahead of an on-screen preview
        //     the user might dismiss in under a second.
        // Only ONE peek player ever exists at a time — dismiss() releases it
        // (see below) before a new one is ever built, so no more than the
        // single actively-shown preview is ever pulling network data.
        if (hasPreviewableVideo(reel) && playerView != null) {
            String previewUrl = pickLowDataUrl(reel);
            player = AdaptiveStreamingManager.get(activity)
                    .buildPlayer(previewUrl, AdaptiveStreamingManager.QualityCap.Q360P, null);
            player.setVolume(0f);
            player.setRepeatMode(Player.REPEAT_MODE_ONE);
            playerView.setPlayer(player);
            final ProgressBar loadingRef = loading;
            player.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    if (loadingRef != null) {
                        loadingRef.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                    }
                }
            });
            player.prepare();
            player.setPlayWhenReady(true);

            // Tap the mini player to toggle sound on/off — mirrors the main
            // reel player's tap-to-mute. Starts muted (see the ULTRA data
            // note above); tapping flips it, and the badge icon/tint flips
            // with it so the current state is always visible at a glance.
            playerView.setOnClickListener(v -> toggleMute(muteBadge, ivMuteIcon));
        } else if (loading != null) {
            loading.setVisibility(View.GONE);
        }
    }

    /** Flips the mini player's mute state and updates the badge to match. */
    private void toggleMute(View muteBadge, android.widget.ImageView ivMuteIcon) {
        if (player == null) return;
        muted = !muted;
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
        if (player != null) {
            try { player.release(); } catch (Throwable ignored) {}
            player = null;
        }
        if (popupWindow != null) {
            if (popupWindow.isShowing()) {
                try { popupWindow.dismiss(); } catch (Throwable ignored) {}
            }
            popupWindow = null;
        }
    }

    private String formatCount(int n) {
        if (n >= 1_000_000) return String.format(Locale.getDefault(), "%.1fM", n / 1_000_000f);
        if (n >= 1_000)     return String.format(Locale.getDefault(), "%.1fK", n / 1_000f);
        return String.valueOf(n);
    }
}
