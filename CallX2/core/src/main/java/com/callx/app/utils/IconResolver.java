package com.callx.app.utils;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import androidx.annotation.ColorInt;
import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.callx.app.core.R;

/**
 * IconResolver — single place that decides "which drawable + which tint"
 * for a static (non-avatar) icon, mirroring why {@link ChatThemeManager} /
 * the per-module AvatarBinder classes exist: before this, every screen
 * resolved its own icon tint inline — some via {@code setColorFilter} with
 * a hardcoded hex (0xFFFFFFFF, Color.parseColor("#C7C7CC")...), some via
 * {@code DrawableCompat.setTint}, a few not tinting at all — so the same
 * conceptual icon (e.g. an inactive nav icon) could render a different grey
 * on different screens, and a theme/rebrand color change meant hunting down
 * every call site individually.
 *
 * DELIBERATELY STATELESS — unlike the avatar L2/L3 caches, this holds no
 * static cache of resolved Drawables. Avatars are cached because they're
 * expensive network+decode work reused across many equivalent renders;
 * static icon drawables are already cheap to inflate and Android's own
 * resource system shares their ConstantState internally, so an extra cache
 * layer here would only add memory and a stale-tint-after-theme-switch risk
 * for no real perf gain (see the perf-vs-hygiene discussion this came out
 * of). Every call resolves fresh from resources/theme — the win here is
 * consistency and one place to change, not caching.
 *
 * USAGE — replaces call sites like:
 *   iv.setImageResource(R.drawable.ic_mic);
 *   iv.setColorFilter(0xFF888888, PorterDuff.Mode.SRC_IN);
 * with:
 *   IconResolver.applyDefault(iv, R.drawable.ic_mic);
 * or, for a selected/active state (bottom nav, toggled toolbar icon):
 *   IconResolver.applyActive(iv, R.drawable.ic_mic);
 *
 * Migrate call sites incrementally (see the audit list) — this class is
 * additive and doesn't require touching every screen at once.
 */
public final class IconResolver {

    private IconResolver() {}

    // ── Drawable resolution ─────────────────────────────────────────────

    /** Plain, untinted resolve — use for full-color icons (emoji, brand marks)
     *  that should never be recolored. Goes through AppCompatResources so
     *  vector drawables inflate correctly pre-API21 too. */
    @Nullable
    public static Drawable resolve(Context ctx, @DrawableRes int iconRes) {
        return AppCompatResources.getDrawable(ctx, iconRes);
    }

    /** Resolves iconRes and tints it with an explicit color resource.
     *  Always {@code mutate()}s first so the tint never bleeds into other
     *  views sharing the same drawable ConstantState. */
    @Nullable
    public static Drawable resolveTinted(Context ctx, @DrawableRes int iconRes, @ColorRes int tintColorRes) {
        return resolveTintedInt(ctx, iconRes, ContextCompat.getColor(ctx, tintColorRes));
    }

    /** Same as {@link #resolveTinted(Context, int, int)} but takes a raw
     *  @ColorInt — for the rare case the tint is computed at runtime (e.g.
     *  a per-item accent color) rather than coming from a color resource.
     *  Prefer the @ColorRes overload wherever the tint is a fixed semantic
     *  color, so it stays themeable via values-night. */
    @Nullable
    public static Drawable resolveTintedInt(Context ctx, @DrawableRes int iconRes, @ColorInt int tintColor) {
        Drawable d = AppCompatResources.getDrawable(ctx, iconRes);
        if (d == null) return null;
        d = d.mutate();
        DrawableCompat.setTint(d, tintColor);
        DrawableCompat.setTintMode(d, PorterDuff.Mode.SRC_IN);
        return d;
    }

    // ── Semantic helpers — the two most common icon states across the app ──

    /** Normal/inactive icon state — see R.color.icon_default_tint. */
    @Nullable
    public static Drawable resolveDefault(Context ctx, @DrawableRes int iconRes) {
        return resolveTinted(ctx, iconRes, R.color.icon_default_tint);
    }

    /** Selected/on icon state (active bottom-nav tab, toggled toolbar icon) —
     *  see R.color.icon_active_tint. */
    @Nullable
    public static Drawable resolveActive(Context ctx, @DrawableRes int iconRes) {
        return resolveTinted(ctx, iconRes, R.color.icon_active_tint);
    }

    // ── ImageView convenience — most call sites just want to set + tint in one line ──

    public static void applyDefault(ImageView iv, @DrawableRes int iconRes) {
        iv.setImageDrawable(resolveDefault(iv.getContext(), iconRes));
    }

    public static void applyActive(ImageView iv, @DrawableRes int iconRes) {
        iv.setImageDrawable(resolveActive(iv.getContext(), iconRes));
    }

    public static void applyTinted(ImageView iv, @DrawableRes int iconRes, @ColorRes int tintColorRes) {
        iv.setImageDrawable(resolveTinted(iv.getContext(), iconRes, tintColorRes));
    }

    /** Re-tints an already-set drawable in place — for call sites that do
     *  {@code iv.getDrawable().setTint(...)} on a drawable already assigned
     *  via XML/setImageResource, so there's no drawableRes to re-resolve.
     *  No-op if the ImageView has no drawable yet. */
    public static void tintExisting(ImageView iv, @ColorRes int tintColorRes) {
        Drawable d = iv.getDrawable();
        if (d == null) return;
        d = d.mutate();
        DrawableCompat.setTint(d, ContextCompat.getColor(iv.getContext(), tintColorRes));
        iv.setImageDrawable(d);
    }

    /** White icon over a dark toolbar / media overlay (back button on a
     *  photo/video screen, play badge on a thumbnail) — see
     *  R.color.icon_on_media_tint for why this is its own token instead of
     *  a hardcoded 0xFFFFFFFF at each call site. */
    public static void applyOnMedia(ImageView iv, @DrawableRes int iconRes) {
        applyTinted(iv, iconRes, R.color.icon_on_media_tint);
    }

    /** Same as {@link #applyOnMedia(ImageView, int)} but for a drawable
     *  already assigned to the view (matches the common
     *  {@code btnBack.getDrawable().setTint(0xFFFFFFFF)} call-site shape). */
    public static void tintExistingOnMedia(ImageView iv) {
        tintExisting(iv, R.color.icon_on_media_tint);
    }

    /** Same idea as {@link #tintExisting(ImageView, int)} but for a bare
     *  {@link Drawable} — matches call sites like
     *  {@code toolbar.getNavigationIcon().setTint(0xFFFFFFFF)} where there's
     *  no ImageView, just a Drawable pulled off a Toolbar/MenuItem. Mutates
     *  and returns the same drawable so the call site can keep its existing
     *  one-liner shape if it wants: {@code tb.getNavigationIcon() != null &&
     *  IconResolver.tintOnMedia(tb.getNavigationIcon(), this) != null}. */
    @Nullable
    public static Drawable tintOnMedia(@Nullable Drawable d, Context ctx) {
        if (d == null) return null;
        d = d.mutate();
        DrawableCompat.setTint(d, ContextCompat.getColor(ctx, R.color.icon_on_media_tint));
        return d;
    }

    /** Toggle between default/active tint on an already-set icon without
     *  re-decoding the drawable resource — for frequent toggles (e.g. a
     *  like button flipping state) prefer this over calling
     *  applyDefault/applyActive with the same iconRes repeatedly. */
    public static void setActiveState(ImageView iv, boolean active) {
        Drawable d = iv.getDrawable();
        if (d == null) return;
        d = d.mutate();
        DrawableCompat.setTint(d, ContextCompat.getColor(iv.getContext(),
                active ? R.color.icon_active_tint : R.color.icon_default_tint));
        iv.setImageDrawable(d);
    }
}
