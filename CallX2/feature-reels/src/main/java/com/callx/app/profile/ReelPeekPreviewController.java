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
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.callx.app.models.ReelModel;
import com.callx.app.reels.R;

import java.util.Locale;

/**
 * Owns the Instagram/iOS-style long-press "peek" popup used by
 * UserReelsActivity's grid: a small floating, muted-looping video preview
 * card + a compact quick-action sheet, shown centered over a dimmed scrim
 * without navigating away from the grid.
 *
 * Lifecycle is driven entirely by the two edges of the hold gesture that
 * ReelGridAdapter reports back to the Activity:
 *   show()    — hold crossed the long-press timeout (ReelGridAdapter.LongPressListener)
 *   dismiss() — finger lifted / gesture cancelled (ReelGridAdapter.LongPressReleaseListener)
 * A quick-action tap inside the card dismisses itself before invoking its
 * callback, so callers never need to call dismiss() redundantly there.
 */
public class ReelPeekPreviewController {

    public interface Callback {
        /** "Watch Reel" tapped. */
        void onWatchFull();
        /** Secondary action tapped — meaning depends on the label passed to show(). */
        void onSecondaryAction();
    }

    private final Activity activity;
    private PopupWindow popupWindow;
    private ExoPlayer    player;
    private boolean      showing = false;

    public ReelPeekPreviewController(Activity activity) {
        this.activity = activity;
    }

    public boolean isShowing() { return showing; }

    /**
     * @param reel             reel to preview — no-op if null or lacking a video URL to preview.
     * @param secondaryLabel   label for the second quick-action row, e.g. "Options" for the
     *                         owner's own reel (opens the analytics/pin/delete sheet) or
     *                         "Select" for anyone else's (enters multi-select).
     * @param secondaryIconRes drawable resource id for that row's icon (0 for none).
     */
    public void show(ReelModel reel, String secondaryLabel, int secondaryIconRes, Callback callback) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (reel == null) return;
        dismiss(); // only one peek at a time

        View content = LayoutInflater.from(activity).inflate(R.layout.popup_reel_peek, null, false);
        View scrim            = content.findViewById(R.id.view_peek_scrim);
        View peekContent       = content.findViewById(R.id.layout_peek_content);
        PlayerView playerView  = content.findViewById(R.id.peek_player_view);
        ProgressBar loading    = content.findViewById(R.id.peek_loading);
        TextView tvCaption     = content.findViewById(R.id.tv_peek_caption);
        TextView tvDuration    = content.findViewById(R.id.tv_peek_duration);
        TextView tvViews       = content.findViewById(R.id.tv_peek_views);
        TextView tvLikes       = content.findViewById(R.id.tv_peek_likes);
        TextView btnPlay       = content.findViewById(R.id.btn_peek_play);
        TextView btnSecondary  = content.findViewById(R.id.btn_peek_secondary);

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

        if (btnSecondary != null) {
            if (secondaryLabel != null) {
                btnSecondary.setText(secondaryLabel);
                if (secondaryIconRes != 0) {
                    btnSecondary.setCompoundDrawablesWithIntrinsicBounds(secondaryIconRes, 0, 0, 0);
                }
                btnSecondary.setVisibility(View.VISIBLE);
            } else {
                btnSecondary.setVisibility(View.GONE);
            }
        }

        if (btnPlay != null) {
            btnPlay.setOnClickListener(v -> { dismiss(); if (callback != null) callback.onWatchFull(); });
        }
        if (btnSecondary != null) {
            btnSecondary.setOnClickListener(v -> { dismiss(); if (callback != null) callback.onSecondaryAction(); });
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
                dismiss();
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

        // Muted, looping preview — same convention as the full-screen preview
        // and grid thumbnails elsewhere in this Activity.
        if (reel.videoUrl != null && !reel.videoUrl.isEmpty() && playerView != null) {
            player = new ExoPlayer.Builder(activity).build();
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
            player.setMediaItem(MediaItem.fromUri(reel.videoUrl));
            player.prepare();
            player.setPlayWhenReady(true);
        } else if (loading != null) {
            loading.setVisibility(View.GONE);
        }
    }

    /** Safe to call any number of times, including when nothing is showing. */
    public void dismiss() {
        showing = false;
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
