package com.callx.app.lock;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import java.util.concurrent.Executor;

/**
 * ChatLockGate — real unlock flow for a per-chat lock, wired to a device
 * biometric/PIN/pattern prompt (same as WhatsApp Chat Lock: no separate
 * secret, it reuses whatever screen-lock credential the device already
 * has). Previously ChatSecurityBottomSheet only had a UI toggle with no
 * logic behind it — this class + ChatLockManager is that logic.
 *
 * Usage (ChatActivity/GroupChatActivity onCreate, right after the chat id
 * is known, before any message content is meaningfully visible):
 *
 *   locked = ChatLockGate.attachIfLocked(this, binding.getRoot(), chatId,
 *       partnerName, this::finish /* onCancelled * /);
 *
 * And in onStop():
 *   ChatLockGate.onHostStopped(chatId);
 *
 * The gate is a full-screen opaque overlay added as the LAST child of the
 * given root ViewGroup, so it sits on top of and blocks touches to
 * everything already laid out underneath (the chat keeps loading behind
 * it — cheap, and avoids restructuring onCreate's existing flow — but
 * nothing is visible or reachable until authentication succeeds).
 */
public final class ChatLockGate {

    private ChatLockGate() {}

    public interface UnlockCallback {
        void onUnlocked();
        void onCancelled();
    }

    /**
     * If {@code chatId} is locked and not already unlocked this session,
     * adds a blocking overlay to {@code root} and starts the device
     * credential prompt. Returns true if a gate was attached (caller
     * should treat the screen as locked until the callback fires).
     */
    public static boolean attachIfLocked(@NonNull FragmentActivity activity,
                                          @NonNull ViewGroup root,
                                          @NonNull String chatId,
                                          @Nullable String chatTitle,
                                          @NonNull UnlockCallback callback) {
        ChatLockManager mgr = ChatLockManager.getInstance(activity);
        if (!mgr.isLocked(chatId) || mgr.isUnlockedThisSession(chatId)) return false;

        View[] overlayRef = new View[1];
        UnlockCallback wrapped = new UnlockCallback() {
            @Override public void onUnlocked() {
                if (overlayRef[0] != null) root.removeView(overlayRef[0]);
                callback.onUnlocked();
            }
            @Override public void onCancelled() {
                // Leave the overlay up — user can tap "Unlock" to retry,
                // or the caller's onCancelled() (typically finish()) fires.
                callback.onCancelled();
            }
        };

        View overlay = buildOverlay(activity, chatTitle, () -> launchPrompt(activity, chatId, mgr, wrapped));
        overlayRef[0] = overlay;
        root.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Kick off the prompt immediately — the retry button on the
        // overlay covers the case where the user dismisses it.
        launchPrompt(activity, chatId, mgr, wrapped);
        return true;
    }

    /** Call from the host Activity's onStop() so leaving the chat re-locks it. */
    public static void onHostStopped(@NonNull String chatId) {
        // Any Context works since ChatLockManager is a process-wide
        // singleton once first created; this only touches the in-memory
        // session-unlock set, not the encrypted prefs, so no Context is
        // actually needed here beyond having been initialized already.
        ChatLockManagerHolder.clearIfInitialized(chatId);
    }

    // ── Prompt ────────────────────────────────────────────────────────────

    private static void launchPrompt(FragmentActivity activity, String chatId,
                                      ChatLockManager mgr, UnlockCallback callback) {
        authenticate(activity, "Unlock chat", "Use your fingerprint, face, or device PIN",
                () -> { mgr.markUnlockedThisSession(chatId); callback.onUnlocked(); },
                callback::onCancelled);
    }

    /**
     * Standalone device-credential prompt — used by the Chat Lock toggle
     * (ChatSecurityBottomSheet / PrivacyDirectDialog) to confirm identity
     * before turning the lock on or off, independent of any particular
     * chat's session-unlock state.
     */
    public static void authenticate(@NonNull FragmentActivity activity,
                                      @NonNull Runnable onSuccess, @NonNull Runnable onCancelled) {
        authenticate(activity, "Confirm it's you", "Use your fingerprint, face, or device PIN",
                onSuccess, onCancelled);
    }

    private static void authenticate(FragmentActivity activity, String title, String subtitle,
                                      Runnable onSuccess, Runnable onCancelled) {
        BiometricManager bm = BiometricManager.from(activity);
        int can = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG
                | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            android.widget.Toast.makeText(activity,
                    "Set a screen lock (fingerprint/PIN) on your device to use Chat Lock",
                    android.widget.Toast.LENGTH_LONG).show();
            onCancelled.run();
            return;
        }

        Executor executor = ContextCompat.getMainExecutor(activity);
        BiometricPrompt prompt = new BiometricPrompt(activity, executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult r) {
                        onSuccess.run();
                    }
                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errMsg) {
                        onCancelled.run();
                    }
                    @Override
                    public void onAuthenticationFailed() {
                        // Wrong fingerprint — BiometricPrompt keeps its own
                        // UI open for retry; nothing to do here.
                    }
                });

        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG
                        | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build();
        prompt.authenticate(info);
    }

    // ── Overlay UI (built programmatically — :core has no viewBinding) ────

    private static View buildOverlay(Context ctx, @Nullable String chatTitle, Runnable onUnlockTapped) {
        float d = ctx.getResources().getDisplayMetrics().density;

        FrameLayout overlay = new FrameLayout(ctx);
        overlay.setBackgroundColor(0xFF0F172A); // opaque — fully hides chat content behind it
        overlay.setClickable(true);
        overlay.setFocusable(true);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams colLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        overlay.addView(col, colLp);

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(android.R.drawable.ic_lock_lock);
        icon.setColorFilter(Color.WHITE);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams((int) (48 * d), (int) (48 * d));
        iconLp.bottomMargin = (int) (16 * d);
        col.addView(icon, iconLp);

        TextView title = new TextView(ctx);
        title.setText(chatTitle != null && !chatTitle.isEmpty() ? chatTitle + " is locked" : "This chat is locked");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        col.addView(title);

        TextView subtitle = new TextView(ctx);
        subtitle.setText("Unlock with fingerprint, face, or device PIN");
        subtitle.setTextColor(0xB3FFFFFF);
        subtitle.setTextSize(13);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = (int) (6 * d);
        subLp.bottomMargin = (int) (24 * d);
        col.addView(subtitle, subLp);

        Button btn = new Button(ctx);
        btn.setText("Unlock");
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        btn.setBackgroundColor(0xFF0F4C3A); // brand_primary
        btn.setPadding((int) (32 * d), (int) (10 * d), (int) (32 * d), (int) (10 * d));
        btn.setOnClickListener(v -> onUnlockTapped.run());
        col.addView(btn);

        return overlay;
    }

    /** Thin indirection so onHostStopped() doesn't need a Context param. */
    static final class ChatLockManagerHolder {
        static void clearIfInitialized(String chatId) {
            ChatLockManager instance = ChatLockManager.peekInstance();
            if (instance != null) instance.clearSessionUnlock(chatId);
        }
    }
}
