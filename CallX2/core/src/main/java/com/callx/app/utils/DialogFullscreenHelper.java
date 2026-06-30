package com.callx.app.utils;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageButton;
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
 * (optional) bottom-center name label + AvatarZoomSwipeHelper se
 * swipe-down-to-close.
 *
 * Drawable resource ids har module ka apna R class use karta hai
 * (app / feature-chat / feature-reels / feature-calls sab alag R hain),
 * isliye yeh caller se liye jaate hain — hardcode nahi kiye.
 */
public final class DialogFullscreenHelper {

    private DialogFullscreenHelper() {
        // no instances
    }

    /** Avatar zoom dialog — bina name label ke (zyada jagah use hota hai). */
    public static Dialog showAvatarZoom(Context ctx, String photoUrl,
                                         int icPersonRes, int icCloseRes) {
        return showAvatarZoom(ctx, photoUrl, null, icPersonRes, icCloseRes);
    }

    /**
     * Avatar zoom dialog — optional name label ke saath (UserReelsActivity,
     * ChatListAdapter use karte hain).
     */
    public static Dialog showAvatarZoom(Context ctx, String photoUrl, String name,
                                         int icPersonRes, int icCloseRes) {
        if (ctx == null) return null;

        Dialog dialog = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout root = new FrameLayout(ctx);
        root.setBackgroundColor(0xEE000000);

        float dp = ctx.getResources().getDisplayMetrics().density;

        PhotoView photoView = new PhotoView(ctx);
        FrameLayout.LayoutParams ivLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT);
        photoView.setLayoutParams(ivLp);
        photoView.setMinimumScale(1f);
        photoView.setMediumScale(2f);
        photoView.setMaximumScale(5f);
        photoView.setOnOutsidePhotoTapListener(v -> dialog.dismiss());
        photoView.setOnPhotoTapListener((v, x, y) -> { /* prevent dismiss on photo tap */ });
        AvatarZoomSwipeHelper.attachSwipeToClose(photoView, dialog);

        ImageButton btnClose = new ImageButton(ctx);
        int closeSizePx = (int) (40 * dp);
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(closeSizePx, closeSizePx);
        closeLp.gravity = Gravity.TOP | Gravity.END;
        closeLp.topMargin = (int) (40 * dp);
        closeLp.rightMargin = (int) (16 * dp);
        btnClose.setLayoutParams(closeLp);
        btnClose.setImageResource(icCloseRes);
        btnClose.setBackgroundColor(Color.TRANSPARENT);
        btnClose.setOnClickListener(v -> dialog.dismiss());

        GlideLoader.load(ctx, photoUrl, photoView, icPersonRes, icPersonRes);

        root.addView(photoView);

        if (name != null && !name.isEmpty()) {
            TextView tvName = new TextView(ctx);
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
            root.addView(tvName);
        }

        root.addView(btnClose);
        dialog.setContentView(root);

        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT);
        }
        dialog.show();
        return dialog;
    }
}
