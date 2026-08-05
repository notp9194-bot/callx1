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
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.callx.app.models.ReelModel;
import com.callx.app.reels.R;

import java.util.List;
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
        cardPeekOptions = cardOptions;
        optionsExpanded = false;

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
