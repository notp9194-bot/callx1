package com.callx.app.conversation.controllers;

import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

/**
 * Snapchat-style screenshot notification for 1:1 chats.
 *
 * ── OUTGOING (we took a screenshot) ────────────────────────────────────
 * Detects screenshots via a MediaStore ContentObserver watching the
 * external images URI. When a new image lands in a "Screenshots" folder
 * while this chat screen is foregrounded, we write:
 *
 *   chatScreenshot/{chatId}/{ourUid} = ServerValue.TIMESTAMP
 *
 * The node is self-clearing: after writing we schedule a remove after
 * SELF_CLEAR_MS (5 s) — long enough for the partner's listener to fire,
 * short enough that re-opening the chat later never re-triggers it.
 *
 * ── INCOMING (partner took a screenshot) ────────────────────────────────
 * watchPartnerScreenshot() listens on chatScreenshot/{chatId}/{partnerUid}.
 * Any non-null value triggers showScreenshotBanner() — an animated red
 * pill that slides in from the top, holds for BANNER_HOLD_MS (3 s), then
 * fades out. After the banner disappears we remove the partner's node so
 * the same event never re-fires if the user rotates / re-opens the screen.
 *
 * ── Privacy note ────────────────────────────────────────────────────────
 * We never expose WHAT was captured — only that a screenshot occurred.
 * The ContentObserver fires on any new image in Screenshots/, so if the
 * user has another app open at the same moment it could theoretically
 * false-positive; we guard against this with a 2-second debounce and only
 * write when the chat Activity is foregrounded (screenActive flag).
 */
public class ChatScreenshotNotifier {

    // How long (ms) to keep our own "I took a screenshot" node in Firebase
    // before self-removing. Partner's listener needs to fire within this window.
    private static final long SELF_CLEAR_MS   = 5_000;

    // How long the red banner stays on screen before auto-fading.
    private static final long BANNER_HOLD_MS  = 3_000;

    // Debounce: ignore duplicate MediaStore callbacks within this window.
    private static final long DETECT_DEBOUNCE_MS = 2_000;

    private final ChatActivityDelegate delegate;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Outgoing detection ───────────────────────────────────────────────
    private ContentObserver screenshotObserver;
    private boolean screenActive = false;   // true only while Activity is resumed
    private long    lastDetectedAt = 0L;    // debounce guard

    // ── Incoming listener ────────────────────────────────────────────────
    private ValueEventListener partnerScreenshotListener;

    // ── Banner auto-hide ─────────────────────────────────────────────────
    private Runnable bannerHideRunnable;

    public ChatScreenshotNotifier(ChatActivityDelegate delegate) {
        this.delegate = delegate;
    }

    // ── Init / teardown ──────────────────────────────────────────────────

    public void init() {
        registerScreenshotObserver();
        watchPartnerScreenshot();
    }

    public void release() {
        unregisterScreenshotObserver();
        removePartnerListener();
        cancelBannerHide();
    }

    /** Call from Activity.onResume() */
    public void onScreenResumed() { screenActive = true; }

    /** Call from Activity.onPause() */
    public void onScreenPaused()  { screenActive = false; }

    // ── Outgoing: detect our own screenshots ─────────────────────────────

    private void registerScreenshotObserver() {
        if (delegate.getActivity() == null) return;

        screenshotObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (!screenActive) return;          // chat not in foreground
                if (uri == null)   return;

                long now = System.currentTimeMillis();
                if (now - lastDetectedAt < DETECT_DEBOUNCE_MS) return; // debounce
                lastDetectedAt = now;

                // Confirm the new image is in a Screenshots folder
                // (some ROMs fire the observer for every camera shot too).
                String path = uri.toString().toLowerCase();
                if (!path.contains("screenshot")) {
                    // Secondary check via last-modified query — skip if not a screenshot path
                    try {
                        android.database.Cursor c = delegate.getActivity()
                                .getContentResolver().query(
                                        uri,
                                        new String[]{MediaStore.Images.Media.DATA},
                                        null, null, null);
                        if (c != null) {
                            if (c.moveToFirst()) {
                                String data = c.getString(0);
                                if (data == null || !data.toLowerCase().contains("screenshot")) {
                                    c.close(); return;
                                }
                            }
                            c.close();
                        }
                    } catch (Exception ignored) { return; }
                }

                publishOurScreenshot();
            }
        };

        delegate.getActivity().getContentResolver().registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                /* notifyForDescendants= */ true,
                screenshotObserver);
    }

    private void unregisterScreenshotObserver() {
        if (screenshotObserver != null && delegate.getActivity() != null) {
            try {
                delegate.getActivity().getContentResolver()
                        .unregisterContentObserver(screenshotObserver);
            } catch (Exception ignored) {}
            screenshotObserver = null;
        }
    }

    private void publishOurScreenshot() {
        String chatId = delegate.getChatId();
        String uid    = delegate.getCurrentUid();
        if (chatId == null || uid == null) return;

        // Write timestamp — partner's ValueEventListener fires immediately.
        FirebaseUtils.getChatScreenshotRef(chatId)
                .child(uid)
                .setValue(ServerValue.TIMESTAMP);

        // Self-clear after SELF_CLEAR_MS so re-opening never re-fires.
        mainHandler.postDelayed(() ->
                FirebaseUtils.getChatScreenshotRef(chatId).child(uid).removeValue(),
                SELF_CLEAR_MS);
    }

    // ── Incoming: watch partner's screenshot node ─────────────────────────

    private void watchPartnerScreenshot() {
        String chatId     = delegate.getChatId();
        String partnerUid = delegate.getPartnerUid();
        if (chatId == null || partnerUid == null || partnerUid.isEmpty()) return;

        partnerScreenshotListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (!s.exists() || s.getValue() == null) return; // node cleared — ignore
                showScreenshotBanner();
                // Remove the node after we've processed it so rotate / re-open
                // never shows the banner a second time.
                mainHandler.postDelayed(() ->
                        FirebaseUtils.getChatScreenshotRef(chatId)
                                .child(partnerUid).removeValue(),
                        500);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };

        FirebaseUtils.getChatScreenshotRef(chatId)
                .child(partnerUid)
                .addValueEventListener(partnerScreenshotListener);
    }

    private void removePartnerListener() {
        String chatId     = delegate.getChatId();
        String partnerUid = delegate.getPartnerUid();
        if (partnerScreenshotListener != null && chatId != null && partnerUid != null) {
            FirebaseUtils.getChatScreenshotRef(chatId)
                    .child(partnerUid)
                    .removeEventListener(partnerScreenshotListener);
            partnerScreenshotListener = null;
        }
    }

    // ── Banner UI ─────────────────────────────────────────────────────────

    private void showScreenshotBanner() {
        ActivityChatBinding binding = delegate.getBinding();
        if (binding == null) return;

        View banner = binding.getRoot().findViewById(R.id.ll_screenshot_banner);
        if (banner == null) return;

        // Populate name + avatar
        android.widget.TextView tvMsg = binding.getRoot()
                .findViewById(R.id.tv_screenshot_msg);
        if (tvMsg != null) {
            String name = delegate.getPartnerName();
            tvMsg.setText((name != null ? name : "Partner") + " ne screenshot liya \uD83D\uDCF8");
        }

        de.hdodenhof.circleimageview.CircleImageView ivAvatar = binding.getRoot()
                .findViewById(R.id.iv_screenshot_avatar);
        if (ivAvatar != null && delegate.getActivity() != null) {
            String photo = delegate.getPartnerPhoto();
            if (photo != null && !photo.isEmpty()) {
                Glide.with(delegate.getActivity())
                        .load(photo)
                        .placeholder(R.drawable.ic_person)
                        .into(ivAvatar);
            }
        }

        // Cancel any pending hide from a previous banner cycle.
        cancelBannerHide();

        // Animate in from top (slide down + fade in).
        banner.setTranslationY(-120f);
        banner.setAlpha(0f);
        banner.setVisibility(View.VISIBLE);
        banner.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(320)
                .setInterpolator(new OvershootInterpolator(1.4f))
                .start();

        // Schedule auto-hide after BANNER_HOLD_MS.
        bannerHideRunnable = () -> hideScreenshotBanner(banner);
        mainHandler.postDelayed(bannerHideRunnable, BANNER_HOLD_MS);
    }

    private void hideScreenshotBanner(View banner) {
        cancelBannerHide();
        if (banner == null || banner.getVisibility() != View.VISIBLE) return;
        banner.animate()
                .translationY(-120f)
                .alpha(0f)
                .setDuration(240)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    banner.setVisibility(View.GONE);
                    banner.setTranslationY(0f);
                    banner.setAlpha(1f);
                })
                .start();
    }

    private void cancelBannerHide() {
        if (bannerHideRunnable != null) {
            mainHandler.removeCallbacks(bannerHideRunnable);
            bannerHideRunnable = null;
        }
    }
}
