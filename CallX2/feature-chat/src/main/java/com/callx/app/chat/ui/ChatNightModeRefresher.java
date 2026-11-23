package com.callx.app.chat.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import com.callx.app.chat.R;
import com.callx.app.chat.databinding.ActivityChatBinding;

/**
 * In-place re-theme of the chat screen's header and input bar after a system day/night flip
 * (ChatActivity handles {@code uiMode} itself, so views keep their inflate-time colors).
 *
 * v834 (ultra pass) over v833:
 *  - no findViewById: uses the already-bound ActivityChatBinding fields (v833 walked the whole tree,
 *    RecyclerView children included, ~15 times per flip);
 *  - colors resolved once per call, and a view is only touched when its value actually changes
 *    (no redundant invalidate/requestLayout);
 *  - input pill recolored IN PLACE (mutate + setColor/setStroke) instead of re-inflating the shape
 *    drawable and re-setting the background;
 *  - {@link #prewarmOppositeTheme} fills IconTintCache for the other mode after open, so the flip
 *    itself does no drawable tint/draw work.
 */
public final class ChatNightModeRefresher {

    private ChatNightModeRefresher() {}

    /** Both modes' icon bitmaps live in the process-wide IconTintCache, so the warm-up runs once per process. */
    private static final java.util.concurrent.atomic.AtomicBoolean WARMED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public static void refresh(@NonNull Activity a, @NonNull ActivityChatBinding b) {
        // ── Header ────────────────────────────────────────────────────────
        final int primary = ContextCompat.getColor(a, R.color.text_primary);
        final ColorStateList primaryTint = ColorStateList.valueOf(primary);
        tint(b.btnBack, primaryTint);
        tint(b.btnToolbarVoiceCall, primaryTint);
        tint(b.btnToolbarVideoCall, primaryTint);
        tint(b.btnMoreOptions, primaryTint);
        text(b.tvPartnerName, primary);
        text(b.tvStatus, ContextCompat.getColor(a, R.color.text_secondary));

        // ── Input bar ─────────────────────────────────────────────────────
        recolorCapsule(a, b.cvInputCapsule);
        final int muted = ContextCompat.getColor(a, R.color.text_muted);
        if (b.etMessage != null) {
            final int inputText = ContextCompat.getColor(a, R.color.chat_input_text);
            if (b.etMessage.getCurrentTextColor() != inputText) b.etMessage.setTextColor(inputText);
            if (b.etMessage.getCurrentHintTextColor() != muted) b.etMessage.setHintTextColor(muted);
        }
        if (b.chatIconBar != null) b.chatIconBar.refreshTheme();

        text(b.tvCharCount, muted);
        final int surface = ContextCompat.getColor(a, R.color.surface_input);
        if (b.layoutSendLockedBanner != null) {
            b.layoutSendLockedBanner.setBackgroundColor(surface);
            text(b.layoutSendLockedBanner, muted);
        }
        if (b.cvRecordLock != null) b.cvRecordLock.setCardBackgroundColor(surface);
        tint(b.ivRecordLockIcon, ColorStateList.valueOf(ContextCompat.getColor(a, R.color.brand_primary)));
    }

    /**
     * Pre-draws the opposite day/night mode's icon bitmaps into IconTintCache. Call once, off the open
     * path (idle / background). Safe to call from any thread; failures are ignored (the live flip then
     * just draws them on demand, exactly like v833).
     */
    public static void prewarmOppositeTheme(@NonNull Context appCtx, @Nullable ChatIconBarView bar, int viewOnceSizePx) {
        if (!WARMED.compareAndSet(false, true)) return;
        try {
            final Configuration cfg = new Configuration(appCtx.getResources().getConfiguration());
            final boolean night = (cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                    | (night ? Configuration.UI_MODE_NIGHT_NO : Configuration.UI_MODE_NIGHT_YES);
            final Context other = appCtx.createConfigurationContext(cfg);
            if (bar != null) bar.prewarmIconsFor(other);
            IconTintCache.get(other, R.drawable.ic_view_once, R.color.chat_input_text, viewOnceSizePx);
        } catch (Throwable ignored) { /* best-effort warm-up */ }
    }

    // Recolor the pill in place: the view owns a private drawable instance (inflated from XML), so after
    // mutate() setColor/setStroke touch only this view. Falls back to re-inflating for any other drawable.
    private static void recolorCapsule(Activity a, @Nullable View capsule) {
        if (capsule == null) return;
        final int fill = ContextCompat.getColor(a, R.color.chat_input_bar_bg);
        final int stroke = ContextCompat.getColor(a, R.color.divider);
        final Drawable bg = capsule.getBackground();
        if (bg instanceof GradientDrawable) {
            final GradientDrawable g = (GradientDrawable) bg.mutate();
            g.setColor(fill);
            g.setStroke(Math.max(1, Math.round(0.5f * a.getResources().getDisplayMetrics().density)), stroke);
        } else {
            capsule.setBackgroundResource(R.drawable.bg_chat_input_capsule);
        }
    }

    private static void tint(@Nullable View v, ColorStateList tint) {
        if (v instanceof ImageView) ImageViewCompat.setImageTintList((ImageView) v, tint);
    }

    private static void text(@Nullable View v, int color) {
        if (v instanceof TextView) {
            final TextView t = (TextView) v;
            if (t.getCurrentTextColor() != color) t.setTextColor(color);
        }
    }
}
