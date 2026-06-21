package com.callx.app.conversation.controllers;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.OvershootInterpolator;

import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.models.Message;

import java.util.regex.Pattern;

/**
 * Big emoji "burst" overlay — when the PARTNER sends a message that is
 * emoji-only (no other text), it pops up large in the middle of the chat
 * area with a scale+fade animation, holds briefly, then fades back out.
 *
 * Anti-spam: a hard 4-second cooldown between bursts. If the partner sends
 * emoji message after emoji message rapidly, only one burst plays per
 * 4-second window — the rest are silently swallowed (their bubble still
 * shows normally in the chat list, just no overlay replay).
 */
public class ChatEmojiBurstController {

    private static final long COOLDOWN_MS = 4000;
    private static final long HOLD_MS = 900;
    private static final long FADE_IN_MS = 220;
    private static final long FADE_OUT_MS = 280;

    // Emoji-only detector: strips whitespace/variation-selectors/ZWJ, then
    // checks every remaining codepoint is in a common emoji block. Keeps
    // this dependency-free (no external emoji library needed).
    private static final Pattern EMOJI_RANGE = Pattern.compile(
            "[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{1F1E6}-\\x{1F1FF}\\x{2190}-\\x{21FF}\\x{2B00}-\\x{2BFF}]");

    private final ChatActivityDelegate delegate;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private long lastBurstAt = 0L;
    private Runnable pendingHide;
    private AnimatorSet currentAnim;

    public ChatEmojiBurstController(ChatActivityDelegate delegate) {
        this.delegate = delegate;
    }

    public void release() {
        if (pendingHide != null) handler.removeCallbacks(pendingHide);
        if (currentAnim != null) currentAnim.cancel();
    }

    /** Call this from the realtime message listener (onChildAdded) for
     *  every incoming message — handles its own filtering. */
    public void onMessageReceived(Message m) {
        if (m == null || m.text == null) return;
        // Only the partner's emoji-bursts overlay — not our own echoed sends.
        if (m.senderId == null || m.senderId.equals(delegate.getCurrentUid())) return;
        if (!isEmojiOnly(m.text)) return;

        long now = System.currentTimeMillis();
        if (now - lastBurstAt < COOLDOWN_MS) return; // spam guard
        lastBurstAt = now;

        showBurst(m.text.trim());
    }

    // ── Emoji-only detection ────────────────────────────────────────────

    private boolean isEmojiOnly(String raw) {
        String text = raw.trim();
        if (text.isEmpty() || text.length() > 12) return false; // a few emojis max
        String stripped = text
                .replace("\uFE0F", "")   // variation selector
                .replace("\u200D", "")   // ZWJ
                .replaceAll("\\s+", "");
        if (stripped.isEmpty()) return false;

        int i = 0;
        boolean sawEmoji = false;
        while (i < stripped.length()) {
            int cp = stripped.codePointAt(i);
            int charCount = Character.charCount(cp);
            String unit = stripped.substring(i, i + charCount);
            if (!EMOJI_RANGE.matcher(unit).matches()) return false;
            sawEmoji = true;
            i += charCount;
        }
        return sawEmoji;
    }

    // ── Animation ────────────────────────────────────────────────────────

    private void showBurst(String emoji) {
        ActivityChatBinding binding = delegate.getBinding();
        if (binding == null || binding.tvEmojiBurst == null) return;

        if (pendingHide != null) handler.removeCallbacks(pendingHide);
        if (currentAnim != null) currentAnim.cancel();

        binding.tvEmojiBurst.setText(emoji);
        binding.tvEmojiBurst.setVisibility(View.VISIBLE);
        binding.tvEmojiBurst.setAlpha(0f);
        binding.tvEmojiBurst.setScaleX(0.3f);
        binding.tvEmojiBurst.setScaleY(0.3f);

        ObjectAnimator fadeIn = ObjectAnimator.ofFloat(binding.tvEmojiBurst, View.ALPHA, 0f, 1f);
        ObjectAnimator scaleXIn = ObjectAnimator.ofFloat(binding.tvEmojiBurst, View.SCALE_X, 0.3f, 1f);
        ObjectAnimator scaleYIn = ObjectAnimator.ofFloat(binding.tvEmojiBurst, View.SCALE_Y, 0.3f, 1f);
        AnimatorSet inSet = new AnimatorSet();
        inSet.setDuration(FADE_IN_MS);
        inSet.setInterpolator(new OvershootInterpolator(2.2f));
        inSet.playTogether(fadeIn, scaleXIn, scaleYIn);

        currentAnim = inSet;
        inSet.start();

        pendingHide = () -> fadeOutAndHide(binding);
        handler.postDelayed(pendingHide, FADE_IN_MS + HOLD_MS);
    }

    private void fadeOutAndHide(ActivityChatBinding binding) {
        if (binding == null || binding.tvEmojiBurst == null) return;

        ObjectAnimator fadeOut = ObjectAnimator.ofFloat(binding.tvEmojiBurst, View.ALPHA, 1f, 0f);
        ObjectAnimator scaleXOut = ObjectAnimator.ofFloat(binding.tvEmojiBurst, View.SCALE_X, 1f, 1.3f);
        ObjectAnimator scaleYOut = ObjectAnimator.ofFloat(binding.tvEmojiBurst, View.SCALE_Y, 1f, 1.3f);
        AnimatorSet outSet = new AnimatorSet();
        outSet.setDuration(FADE_OUT_MS);
        outSet.playTogether(fadeOut, scaleXOut, scaleYOut);
        outSet.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                binding.tvEmojiBurst.setVisibility(View.GONE);
            }
        });
        currentAnim = outSet;
        outSet.start();
    }
}
