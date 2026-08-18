package com.callx.app.utils;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.github.chrisbanes.photoview.PhotoView;

/**
 * DIALOG FULLSCREEN HELPER — common "avatar zoom" full-screen dialog
 * ──────────────────────────────────────────────────────────────────────
 * Yeh exactly wahi 7 jagah repeat ho rahe showAvatarZoom() ko consolidate
 * karta hai:
 *   - UserProfileActivity, ProfileActivity, CallsFragment, ChatsFragment,
 *     ReelUserProfileSheet, UserReelsActivity, ChatListAdapter.
 *
 * Build karta hai: fullscreen Dialog + dim background FrameLayout +
 * pinch-zoom PhotoView (Glide se load) + top-right close button +
 * (optional) bottom-center name label.
 *
 * TELEGRAM-STYLE DOCK ANIMATION (reused from MediaViewerActivity's
 * MEDIA_VIEWER_TELEGRAM_CLOSE feature — same MediaSwipeReplyCloseHelper +
 * MediaViewerSourceRect machinery, no gesture/animation code duplicated):
 *   - OPEN: agar caller ek `sourceView` (jis avatar ImageView par tap hua
 *     tha) pass karta hai, photo us view ke exact on-screen rect se
 *     shuru hoke ek CIRCLE ke roop me hi expand hoti hai — chhoti avatar
 *     circle se ek bade, screen-center pe fixed-size circle tak (kabhi
 *     square me "un-round" nahi hoti) — WhatsApp/Instagram profile-photo
 *     viewer jaisa exact feel.
 *   - CLOSE: close button / back-press / outside-tap / swipe (up ya down,
 *     dono) — sab isi ek `closeToSource()` path se hote hain, jo photo ko
 *     wapas usi avatar ke gol (circular) rect ki taraf shrink karta hai
 *     taaki band hote hi wahi "chipak" jaye jaha se woh khuli thi.
 *   - `sourceView` na diya jaye (ya abhi layout na hua ho) to purana
 *     plain instant show/dismiss hi chalta hai — safe no-op fallback,
 *     kabhi crash nahi karta (jaise ReelCommentsAdapter ka comment-image
 *     tap, jo sirf Context overload use karta hai).
 *
 * Drawable resource ids har module ka apna R class use karta hai
 * (app / feature-chat / feature-reels / feature-calls sab alag R hain),
 * isliye yeh caller se liye jaate hain — hardcode nahi kiye.
 */
public final class DialogFullscreenHelper {

    private DialogFullscreenHelper() {
        // no instances
    }

    /** Dim scrim's resting alpha (0-255) — matches the old fixed 0xEE background. */
    private static final int SCRIM_ALPHA = 0xEE;
    private static final long DOCK_ANIM_BASE_MS = 300L;
    private static final long DOCK_ANIM_MIN_MS = 170L;
    private static final Interpolator DOCK_EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);
    /**
     * WHATSAPP/INSTAGRAM-STYLE FULLSCREEN CIRCLE: the resting (fully open)
     * avatar photo diameter, as a fraction of the screen's shorter side.
     * Unlike the old behaviour (un-round to a full-bleed square), the photo
     * now stops growing at this fixed circular size and stays a circle for
     * the whole time it's open — matching WhatsApp/Instagram's profile
     * photo viewer, not the chat-media viewer's square dock.
     */
    private static final float AVATAR_FULLSCREEN_CIRCLE_RATIO = 0.8f;

    /** Resting (fully open) circle radius in px — shared by idle-state config and the open animation's end target. */
    private static float avatarRestingRadiusPx(Context ctx) {
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        return Math.min(dm.widthPixels, dm.heightPixels) * AVATAR_FULLSCREEN_CIRCLE_RATIO / 2f;
    }

    // ── Public entry points (backward compatible — no sourceView) ──────────

    /** Avatar zoom dialog — bina name label ke, bina dock animation ke (no source view). */
    public static Dialog showAvatarZoom(Context ctx, String photoUrl,
                                         int icPersonRes, int icCloseRes) {
        return showAvatarZoom(ctx, null, photoUrl, null, icPersonRes, icCloseRes);
    }

    /** Avatar zoom dialog — optional name label ke saath, bina dock animation ke (no source view). */
    public static Dialog showAvatarZoom(Context ctx, String photoUrl, String name,
                                         int icPersonRes, int icCloseRes) {
        return showAvatarZoom(ctx, null, photoUrl, name, icPersonRes, icCloseRes);
    }

    // ── New entry points — WITH sourceView (Telegram-style dock animation) ─

    /** Avatar zoom dialog — Telegram/Instagram-style circular dock animation, bina name label ke. */
    public static Dialog showAvatarZoom(Context ctx, View sourceView, String photoUrl,
                                         int icPersonRes, int icCloseRes) {
        return showAvatarZoom(ctx, sourceView, photoUrl, null, icPersonRes, icCloseRes);
    }

    /**
     * Avatar zoom dialog — Telegram/Instagram-style circular dock animation +
     * optional name label ke saath.
     *
     * @param sourceView the tapped avatar View (nullable) — its current
     *                    on-screen rect drives the open/close dock animation.
     */
    public static Dialog showAvatarZoom(Context ctx, View sourceView, String photoUrl, String name,
                                         int icPersonRes, int icCloseRes) {
        if (ctx == null) return null;

        final Rect srcRect = MediaViewerSourceRect.ofView(sourceView);

        Dialog dialog = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        SwipeAwareRoot root = new SwipeAwareRoot(ctx);
        root.setBackgroundColor(srcRect != null ? Color.TRANSPARENT : Color.argb(SCRIM_ALPHA, 0, 0, 0));

        float dp = ctx.getResources().getDisplayMetrics().density;

        PhotoView photoView = new PhotoView(ctx);
        FrameLayout.LayoutParams ivLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT);
        photoView.setLayoutParams(ivLp);
        photoView.setMinimumScale(1f);
        photoView.setMediumScale(2f);
        photoView.setMaximumScale(5f);
        photoView.setOnPhotoTapListener((v, x, y) -> { /* prevent dismiss on photo tap */ });

        ImageButton btnClose = new ImageButton(ctx);
        int closeSizePx = (int) (40 * dp);
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(closeSizePx, closeSizePx);
        closeLp.gravity = Gravity.TOP | Gravity.END;
        closeLp.topMargin = (int) (40 * dp);
        closeLp.rightMargin = (int) (16 * dp);
        btnClose.setLayoutParams(closeLp);
        btnClose.setImageResource(icCloseRes);
        btnClose.setBackgroundColor(Color.TRANSPARENT);

        TextView tvName = null;
        if (name != null && !name.isEmpty()) {
            tvName = new TextView(ctx);
            tvName.setText(name);
            tvName.setTextColor(Color.WHITE);
            tvName.setTextSize(15f);
            tvName.setGravity(Gravity.CENTER);
            tvName.setPadding(0, 0, 0, (int) (32 * dp));
            FrameLayout.LayoutParams nameLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
            nameLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            tvName.setLayoutParams(nameLp);
        }

        // Same interactive live-drag + spring-back + fling-to-dismiss gesture
        // MediaViewerActivity uses for chat media (see MEDIA_VIEWER_TELEGRAM_CLOSE
        // docs) — reused as-is, nothing gesture-related is reimplemented here.
        final TextView finalTvName = tvName;
        MediaSwipeReplyCloseHelper[] swipeHelperRef = new MediaSwipeReplyCloseHelper[1];
        MediaSwipeReplyCloseHelper swipeHelper = new MediaSwipeReplyCloseHelper(
            ctx, photoView, root, null,
            () -> PhotoViewZoomUtils.isZoomedIn(photoView),
            new MediaSwipeReplyCloseHelper.Callback() {
                @Override public void onSwipeUpReply() { /* retired — both directions close, see helper class doc */ }
                @Override public void onSwipeDownClose(float velocityY) {
                    closeToSource(dialog, root, photoView, srcRect, velocityY, btnClose, finalTvName, swipeHelperRef[0]);
                }
            });
        swipeHelperRef[0] = swipeHelper;
        if (finalTvName != null) swipeHelper.setChromeViews(btnClose, finalTvName);
        else swipeHelper.setChromeViews(btnClose);
        root.swipeHelper = swipeHelper;
        // BUG FIX: without this, the shared gesture helper assumes the old
        // media-viewer baseline (scale=1, radius=0, full-view-rect rounded
        // corners) — the instant a swipe-to-close drag started, radius/scale
        // snapped to that wrong baseline, which is what made the photo pop
        // to a near-full rectangle on the very first drag frame. Also
        // switches the outline to an inset-square clip, since a rounded-rect
        // spanning the FULL (non-square, full-screen) view only ever reads
        // as a circle when the view itself happens to be square.
        swipeHelper.configureIdleState(1f, avatarRestingRadiusPx(ctx), true);

        photoView.setOnOutsidePhotoTapListener(v ->
            closeToSource(dialog, root, photoView, srcRect, 0f, btnClose, finalTvName, swipeHelper));
        btnClose.setOnClickListener(v ->
            closeToSource(dialog, root, photoView, srcRect, 0f, btnClose, finalTvName, swipeHelper));
        // Now that the resting photo is a circle smaller than the screen
        // (not a full-bleed square), tapping the dark area *around* the
        // circle lands on `root` itself, not on photoView — wire that up
        // as an outside-tap close too (WhatsApp/Instagram behaviour).
        root.setOnClickListener(v ->
            closeToSource(dialog, root, photoView, srcRect, 0f, btnClose, finalTvName, swipeHelper));

        // PERF (ultra-advanced pass): if the tapped avatar view already has
        // a decoded bitmap in it (the normal case — every call site here
        // taps an ImageView that just showed the small circle avatar),
        // reuse THAT as the placeholder instead of the generic ic_person
        // icon. getConstantState().newDrawable().mutate() takes a fresh
        // copy rather than the live Drawable instance, so it's safe to hand
        // to a second ImageView (a shared Drawable instance carries its own
        // bounds/callback state and showing it in two views at once can
        // visually glitch one of them). Net effect: the dock-open animation
        // starts on real pixels immediately — no generic-icon "pop" once the
        // full-res 720x720 load lands a moment later, at zero extra decode
        // cost since the source drawable was already decoded.
        Drawable instantPlaceholder = null;
        if (sourceView instanceof ImageView) {
            Drawable d = ((ImageView) sourceView).getDrawable();
            if (d != null && d.getConstantState() != null) {
                instantPlaceholder = d.getConstantState().newDrawable(ctx.getResources()).mutate();
            }
        }
        if (instantPlaceholder != null) {
            GlideLoader.load(ctx, photoUrl, photoView, instantPlaceholder, icPersonRes);
        } else {
            GlideLoader.load(ctx, photoUrl, photoView, icPersonRes, icPersonRes);
        }

        root.addView(photoView);
        if (tvName != null) root.addView(tvName);
        root.addView(btnClose);
        dialog.setContentView(root);

        // Back press → same dock-to-avatar close instead of the default
        // instant Dialog dismiss.
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                closeToSource(dialog, root, photoView, srcRect, 0f, btnClose, finalTvName, swipeHelper);
                return true;
            }
            return false;
        });

        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT);
            // We drive our own open/close transform — disable the system
            // dialog show/hide animation so it can't fight it.
            w.setWindowAnimations(0);
            // MEDIA_VIEWER_TELEGRAM_CLOSE — Theme_Black_NoTitleBar_Fullscreen's
            // own windowBackground paints solid opaque black at the Window
            // level, underneath `root`. Without clearing it, MediaSwipeReply-
            // CloseHelper's live-drag scrim fade (root's background alpha
            // going 255→0 as the user drags) has nothing real to reveal — it
            // just fades from black to the theme's own black. Making the
            // Window itself transparent means the actual screen behind this
            // dialog (already fully resumed, since a Dialog never stops its
            // host Activity) becomes visible immediately as the drag starts,
            // and progressively more visible as the drag continues — same
            // live "peek behind" feel Telegram's avatar viewer has.
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.show();

        if (srcRect != null) {
            animateOpenFromSource(root, photoView, srcRect, btnClose, tvName, swipeHelper);
        }

        return dialog;
    }

    /**
     * Shrinks+moves `photoView` from its current on-screen spot (which may
     * already be mid-drag) into the avatar's `target` rect as a circle,
     * then dismisses. Same single-ValueAnimator-drives-everything approach
     * as MediaViewerActivity#animateCloseToSource — position, scale,
     * corner radius, content alpha, chrome alpha and scrim never drift out
     * of sync. Falls back to an instant dismiss when there's no target rect.
     */
    private static void closeToSource(Dialog dialog, View root, View photoView, Rect target,
                                       float velocityY, ImageButton btnClose, TextView tvName,
                                       MediaSwipeReplyCloseHelper swipeHelper) {
        if (dialog == null || !dialog.isShowing()) return;

        if (target == null) {
            dialog.dismiss();
            return;
        }

        photoView.animate().cancel();
        // PERF: same GPU-layer caching trick as the live drag (see
        // MediaSwipeReplyCloseHelper#enableHardwareLayerForGesture) — this
        // dock-close animator drives scale+translate+alpha+outline-clip
        // together every frame too, so cache the content once instead of
        // re-drawing+re-clipping it ~18 times over the animation. Released
        // in the animator's onAnimationEnd below, right before the dialog
        // (and this whole view tree) is torn down anyway.
        if (photoView.getLayerType() != View.LAYER_TYPE_HARDWARE) {
            photoView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }

        int[] loc = new int[2];
        photoView.getLocationOnScreen(loc);
        float startScaleX = photoView.getScaleX();
        float startScaleY = photoView.getScaleY();
        float curCenterX = loc[0] + (photoView.getWidth() * startScaleX) / 2f;
        float curCenterY = loc[1] + (photoView.getHeight() * startScaleY) / 2f;
        float targetCenterX = target.left + target.width() / 2f;
        float targetCenterY = target.top + target.height() / 2f;

        float startTx = photoView.getTranslationX();
        float startTy = photoView.getTranslationY();
        float endTx = startTx + (targetCenterX - curCenterX);
        float endTy = startTy + (targetCenterY - curCenterY);
        // Scale always relaxes back to 1 (never shrinks the view itself) —
        // sizing is handled entirely by the shrinking circular clip radius
        // below, same reasoning as animateOpenFromSource.
        float endScaleX = 1f;
        float endScaleY = 1f;

        float startAlpha = photoView.getAlpha();
        float startBgAlpha = currentBgAlpha(root);
        float startCloseAlpha = btnClose.getAlpha();
        float startNameAlpha = tvName != null ? tvName.getAlpha() : 0f;
        float startOnScreenRadius = swipeHelper.getCurrentOnScreenRadiusPx();
        // Avatars are circular — dock back into a full circle sized to the
        // (roughly square) source rect, Instagram-style.
        float endOnScreenRadius = Math.min(target.width(), target.height()) / 2f;

        long duration = velocityAdjustedDuration(velocityY, photoView.getResources().getDisplayMetrics().density);

        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(duration);
        anim.setInterpolator(DOCK_EASE);
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            photoView.setTranslationX(lerp(startTx, endTx, t));
            photoView.setTranslationY(lerp(startTy, endTy, t));
            float sx = lerp(startScaleX, endScaleX, t);
            float sy = lerp(startScaleY, endScaleY, t);
            photoView.setScaleX(sx);
            photoView.setScaleY(sy);
            photoView.setAlpha(lerp(startAlpha, 1f, t));
            swipeHelper.setLiveCornerRadius(lerp(startOnScreenRadius, endOnScreenRadius, t), sx);
            int bgAlpha = Math.round(lerp(startBgAlpha, 0f, t));
            root.setBackgroundColor(Color.argb(bgAlpha, 0, 0, 0));
            btnClose.setAlpha(lerp(startCloseAlpha, 0f, t));
            if (tvName != null) tvName.setAlpha(lerp(startNameAlpha, 0f, t));
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                photoView.setLayerType(View.LAYER_TYPE_NONE, null);
                if (dialog.isShowing()) dialog.dismiss();
            }
        });
        anim.start();
    }

    /** Starts `photoView` scaled/positioned/rounded to look like `source`, then docks up to a centered, fixed-size WhatsApp/Instagram-style circle (never un-rounds to a square). */
    private static void animateOpenFromSource(View root, View photoView, Rect source,
                                               ImageButton btnClose, TextView tvName,
                                               MediaSwipeReplyCloseHelper swipeHelper) {
        photoView.post(() -> {
            if (photoView.getWidth() == 0 || photoView.getHeight() == 0) {
                // Layout never happened (e.g. dialog dismissed instantly) — just
                // make sure the scrim is visible instead of leaving it transparent.
                root.setBackgroundColor(Color.argb(SCRIM_ALPHA, 0, 0, 0));
                return;
            }
            int[] loc = new int[2];
            photoView.getLocationOnScreen(loc);
            // photoView itself NEVER changes size (stays match_parent /
            // scale=1 for the whole open→resting→close lifecycle) — only
            // its TRANSLATION (position) and its circular CLIP RADIUS
            // change. This is deliberate: photoView's real width/height is
            // the full (rectangular) screen, not a square, and a
            // rounded-rect outline only ever reads as a true circle when
            // it's clipping a SQUARE region — trying to also scale the
            // view anisotropically (different X/Y factors, since a
            // rectangle can't become a square via uniform scale) is what
            // previously made the "circle" render as a warped oval/pill.
            // Position+radius-only animation sidesteps that entirely, and
            // is also exactly how a real circular avatar aperture behaves:
            // the photo underneath doesn't resize, just how much of it is
            // revealed through the growing/shrinking circular window.
            float targetCenterX = loc[0] + photoView.getWidth() / 2f;
            float targetCenterY = loc[1] + photoView.getHeight() / 2f;
            float srcCenterX = source.left + source.width() / 2f;
            float srcCenterY = source.top + source.height() / 2f;

            float startTx = srcCenterX - targetCenterX;
            float startTy = srcCenterY - targetCenterY;
            // Circular at the source spot — matches an avatar's own round shape.
            float startOnScreenRadius = Math.min(source.width(), source.height()) / 2f;
            float endOnScreenRadius = avatarRestingRadiusPx(photoView.getContext());

            photoView.setScaleX(1f);
            photoView.setScaleY(1f);
            photoView.setTranslationX(startTx);
            photoView.setTranslationY(startTy);
            swipeHelper.setLiveCornerRadius(startOnScreenRadius, 1f);
            // PERF: see closeToSource's identical comment — cache the view
            // into a GPU layer for the open animation's duration too.
            if (photoView.getLayerType() != View.LAYER_TYPE_HARDWARE) {
                photoView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            }

            root.setBackgroundColor(Color.TRANSPARENT);
            btnClose.setAlpha(0f);
            if (tvName != null) tvName.setAlpha(0f);

            ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(DOCK_ANIM_BASE_MS);
            anim.setInterpolator(DOCK_EASE);
            anim.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    // Open dock finished — the view is now static (idle,
                    // pinch-zoomable) until the user next drags/closes it,
                    // so release the GPU layer; MediaSwipeReplyCloseHelper
                    // re-acquires it itself the moment a new drag starts.
                    photoView.setLayerType(View.LAYER_TYPE_NONE, null);
                }
            });
            anim.addUpdateListener(a -> {
                float t = (float) a.getAnimatedValue();
                photoView.setTranslationX(lerp(startTx, 0f, t));
                photoView.setTranslationY(lerp(startTy, 0f, t));
                // Grows from the small avatar circle to the bigger resting
                // circle — stays round the whole way, never un-rounds to a
                // square (WhatsApp/Instagram profile-photo viewer feel).
                swipeHelper.setLiveCornerRadius(lerp(startOnScreenRadius, endOnScreenRadius, t), 1f);
                int bgAlpha = Math.round(lerp(0f, SCRIM_ALPHA, t));
                root.setBackgroundColor(Color.argb(bgAlpha, 0, 0, 0));
            });
            anim.start();
            // Chrome (close button / name label) eases in slightly after the
            // content starts moving — never leads, matches the media viewer.
            btnClose.animate().alpha(1f).setDuration(220).setStartDelay(90).start();
            if (tvName != null) tvName.animate().alpha(1f).setDuration(220).setStartDelay(90).start();
        });
    }

    private static long velocityAdjustedDuration(float velocityY, float density) {
        float speedDpPerSec = Math.abs(velocityY) / density;
        float speedFactor = Math.min(1f, speedDpPerSec / 2500f);
        long duration = Math.round(lerp(DOCK_ANIM_BASE_MS, DOCK_ANIM_MIN_MS, speedFactor));
        return Math.max(DOCK_ANIM_MIN_MS, duration);
    }

    private static float currentBgAlpha(View root) {
        Drawable bg = root.getBackground();
        if (bg instanceof ColorDrawable) return Color.alpha(((ColorDrawable) bg).getColor());
        return 255f;
    }

    private static float lerp(float start, float end, float t) {
        return start + (end - start) * t;
    }

    /**
     * Root FrameLayout that forwards every touch event to the
     * MediaSwipeReplyCloseHelper first (same pattern MediaViewerActivity
     * uses via dispatchTouchEvent) so the live-drag gesture can intercept
     * before PhotoView's own pan/zoom handling sees it.
     */
    private static class SwipeAwareRoot extends FrameLayout {
        MediaSwipeReplyCloseHelper swipeHelper;

        SwipeAwareRoot(Context ctx) {
            super(ctx);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            if (swipeHelper != null && swipeHelper.onTouch(ev)) return true;
            return super.dispatchTouchEvent(ev);
        }
    }
}
