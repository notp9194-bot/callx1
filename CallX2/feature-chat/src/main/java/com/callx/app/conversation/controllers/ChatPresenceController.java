package com.callx.app.conversation.controllers;

import android.view.View;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.models.Message;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.SecurityManager;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;

/**
 * Handles typing indicator, online/last-seen status, mute, mark-read logic,
 * and the "watching banner" — an animated avatar + name strip that pops in
 * when the partner currently has THIS chat screen open & foregrounded
 * (as opposed to having navigated away to another screen/app). This is
 * fully separate from — and never overrides — the normal online/last-seen
 * status line.
 */
public class ChatPresenceController {

    // Presence path is centralized in FirebaseUtils.getChatPresenceRef(id) —
    // shared with GroupWatchingController so both 1:1 and group chats use
    // the same chatPresence/{id}/{uid}=true node.

    private final ChatActivityDelegate delegate;

    // ── PERF FIX: batch Firebase "status=read"/"status=delivered" writes ────
    // markRead(m) used to fire ONE setValue("read") network call PER message
    // the instant chat opened — with 5 unread messages that's invisible, but
    // with 80-100 unread messages (a chat you haven't opened in a while) that
    // was 80-100 separate Firebase round-trips fired back to back, which is
    // exactly what made "chat khulna slow" scale with message count. All of
    // these are now buffered into pendingReadFirebaseIds and flushed together
    // as ONE multi-path updateChildren() call.
    //
    // PERF FIX v25: the "delivered" half used to be a SEPARATE per-message
    // MessageStatusSync.upgradeStatus() call fired straight from ChatActivity's
    // onChildAdded — a real Firebase runTransaction() PLUS a synchronous
    // SharedPreferences read-modify-write (PendingAckQueue) on the main thread,
    // for every single incoming message. On a chat with a big unread backlog
    // that was N transactions + N disk writes racing the very first
    // RecyclerView layout/scroll frames. pendingDeliveredFirebaseIds folds that
    // into the SAME batched flush as the read receipts below — kept as a
    // separate (ungated) queue because "delivered" must still happen even when
    // the user has read receipts turned OFF (WhatsApp-style: grey delivered
    // tick always shows; blue read tick is the one privacy hides).
    // PERF ADV: 150ms -> 300ms. A fast burst of incoming messages (e.g.
    // reopening a chat with a big unread backlog, or the other side sending
    // several messages back-to-back) was often split across 2-3 flushes
    // instead of 1, because 150ms wasn't always enough to catch the whole
    // burst before the timer fired. 300ms is still imperceptible to the user
    // (ticks update well under the time it takes to notice) but comfortably
    // covers a realistic burst window, so more messages land in a single
    // updateChildren() call instead of triggering a second network round-trip.
    private static final long READ_FLUSH_DEBOUNCE_MS = 300;
    private final LinkedHashSet<String> pendingDeliveredFirebaseIds = new LinkedHashSet<>();
    private final LinkedHashSet<String> pendingReadFirebaseIds = new LinkedHashSet<>();
    private final android.os.Handler readFlushHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable readFlushRunnable = this::flushPendingReadStatus;

    private ValueEventListener typingListener;
    private ValueEventListener onlineListener;
    private ValueEventListener inChatListener;
    private ValueEventListener viewingListener;
    private ValueEventListener typingReplyListener;

    public ChatPresenceController(ChatActivityDelegate delegate) {
        this.delegate = delegate;
    }

    // ── Init ──────────────────────────────────────────────────────────────

    /**
     * FIX #7: Stagger the 7 Firebase listeners across 3 dispatch slots.
     *
     * Old: all 7 listeners attach in the same frame → 7 concurrent Firebase
     * subscriptions open on the network thread at once, competing with the
     * initial message fetch from startRealtimeListener().
     *
     * Fix: attach the 2 most critical listeners immediately (status + typing),
     * then 2 more at +80ms (after the first frame renders), then 3 more at
     * +160ms (fully deferred). Total wall-clock delay is 160ms — imperceptible
     * to the user but lets the message pager start first.
     */
    public void init() {
        // Batch 1 — immediately: the two listeners that visibly affect the toolbar
        // and typing strip on first open. User sees these on the very first frame.
        watchPartnerStatus();
        watchTyping();
        markMessagesRead();
        setupBannerTap();

        // Batch 2 — +80ms: reply-target glow and in-chat presence banner
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(() -> {
            watchPartnerTypingReplyTarget();
            watchPartnerInChatScreen();
        }, 80);

        // Batch 3 — +160ms: lower-priority features (viewing message, recording badge, mute)
        h.postDelayed(() -> {
            watchPartnerViewingMessage();
            watchPartnerRecording();
            watchMute();
        }, 160);
    }

    // ── Tap banner → jump to whatever message the partner is currently
    // viewing ("scroll-to-their-position"). Falls back to a toast if we
    // don't have a specific message yet (they just have the screen open,
    // haven't settled on a bubble, or are on a chat with no messages). ──

    private void setupBannerTap() {
        ActivityChatBinding binding = delegate.getBinding();
        if (binding.llWatchingBanner == null) return;
        binding.llWatchingBanner.setClickable(true);
        binding.llWatchingBanner.setFocusable(true);
        binding.llWatchingBanner.setOnClickListener(v -> jumpToPartnerPosition());
    }

    private void jumpToPartnerPosition() {
        if (lastPartnerViewingMessageId == null || lastPartnerViewingMessageId.isEmpty()) {
            String name = delegate.getPartnerName();
            delegate.showToast((name != null ? name : "Partner")
                    + " has the chat open but isn't on a specific message yet");
            return;
        }
        delegate.navigateToOriginal(lastPartnerViewingMessageId);
    }

    // ── Typing ────────────────────────────────────────────────────────────
    // Drives ll_typing_strip — a floating bottom-left pill (partner's avatar
    // + name + animated dots) over the message list. Fully independent of
    // tv_status now: online/last-seen always stays visible in the header,
    // typing never hides it and never shares a view with it.

    private com.callx.app.chat.ui.TypingDotsAnimator typingDotsAnimator;
    /** Last known "is the partner typing" state from Firebase, independent of
     *  whether the strip/animator is currently running — lets onResume()
     *  know whether to restart the dots loop without re-querying Firebase. */
    private boolean lastPartnerTypingState = false;
    /** True while this screen is paused/backgrounded — the dots loop is kept
     *  stopped during this window even if lastPartnerTypingState is true, so
     *  it never burns battery animating a view nobody can see. */
    private boolean screenPaused = false;

    /** Last value we actually wrote to Firebase for OUR typing/{chatId}/{uid}
     *  node. ChatActivity's TextWatcher calls setOurTypingStatus(true) on
     *  every keystroke (it has its own separate 2s "went quiet" timeout that
     *  calls setOurTypingStatus(false) — see onStopTypingTimeout()), so
     *  without this guard every single character typed would fire a Firebase
     *  write even though the value never actually changes from `true`. */
    private Boolean lastWrittenTypingValue = null;

    public void setOurTypingStatus(boolean typing) {
        if (lastWrittenTypingValue != null && lastWrittenTypingValue == typing) {
            // No-op write avoided — still re-publish the reply target below
            // in case the user switched which message they're replying to
            // without the typing state itself flipping.
            publishTypingReplyTarget();
            return;
        }
        lastWrittenTypingValue = typing;
        FirebaseUtils.db().getReference("typing")
                .child(delegate.getChatId()).child(delegate.getCurrentUid()).setValue(typing);
        publishTypingReplyTarget();
    }

    public void clearOurTypingStatus() {
        lastWrittenTypingValue = false;
        FirebaseUtils.db().getReference("typing")
                .child(delegate.getChatId()).child(delegate.getCurrentUid()).setValue(false);
        clearTypingReplyTarget();
    }

    private void watchTyping() {
        typingListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                boolean typing = false;
                for (DataSnapshot child : s.getChildren()) {
                    if (child.getKey() != null
                            && !child.getKey().equals(delegate.getCurrentUid())
                            && Boolean.TRUE.equals(child.getValue(Boolean.class))) {
                        typing = true;
                        break;
                    }
                }
                lastPartnerTypingState = typing;
                if (typing) showTypingStrip(); else hideTypingStrip();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.db().getReference("typing").child(delegate.getChatId())
                .addValueEventListener(typingListener);
    }

    /** Call from the Activity's onPause(). Stops the dots loop (the strip
     *  itself stays visible/laid out — only the bouncing animation halts) so
     *  it isn't burning frames while the screen is invisible. We deliberately
     *  do NOT touch the underlying Firebase listener or lastPartnerTypingState
     *  here — that keeps tracking in the background exactly like the watching
     *  banner does, so the strip is correctly shown/hidden the instant we
     *  resume, just without an animation loop running while paused. */
    public void onScreenPaused() {
        screenPaused = true;
        if (typingDotsAnimator != null) typingDotsAnimator.stop();
    }

    /** Call from the Activity's onResume(). Restarts the dots loop if the
     *  partner is (still) typing — covers the common case of "they were
     *  typing when I backgrounded the app and are still typing now". */
    public void onScreenResumed() {
        screenPaused = false;
        if (lastPartnerTypingState) {
            ActivityChatBinding binding = delegate.getBinding();
            if (binding.llTypingStrip != null
                    && binding.llTypingStrip.getVisibility() == View.VISIBLE
                    && typingDotsAnimator != null) {
                typingDotsAnimator.start();
            }
        }
    }

    private void showTypingStrip() {
        ActivityChatBinding binding = delegate.getBinding();
        if (binding.llTypingStrip == null) return;

        binding.tvTypingName.setText(
                (delegate.getPartnerName() != null ? delegate.getPartnerName() : "") + " typing");

        String photo = delegate.getPartnerPhoto();
        if (photo != null && !photo.isEmpty() && delegate.getActivity() != null) {
            Glide.with(delegate.getActivity())
                    .load(photo)
                    .placeholder(R.drawable.ic_person)
                    .into(binding.ivTypingAvatar);
        }

        if (typingDotsAnimator == null) {
            typingDotsAnimator = new com.callx.app.chat.ui.TypingDotsAnimator(
                    binding.dotTyping1, binding.dotTyping2, binding.dotTyping3);
        }
        // Don't start the bounce loop while the screen is paused/backgrounded
        // — onScreenResumed() will start it once we're visible again.
        if (!screenPaused) typingDotsAnimator.start();

        if (binding.llTypingStrip.getVisibility() == View.VISIBLE) {
            // Already showing — still make sure the watching banner reflects
            // current priority (e.g. it could have freshly appeared after
            // typing started).
            com.callx.app.chat.ui.BannerPriorityCoordinator.onTypingStripShown(
                    binding.llWatchingBanner, binding.llTypingStrip);
            return;
        }

        binding.llTypingStrip.setAlpha(1f);
        binding.llTypingStrip.setScaleX(1f);
        binding.llTypingStrip.setScaleY(1f);
        binding.llTypingStrip.setVisibility(View.VISIBLE);
        com.callx.app.chat.ui.BannerPriorityCoordinator.onTypingStripShown(
                binding.llWatchingBanner, binding.llTypingStrip);
    }

    private void hideTypingStrip() {
        ActivityChatBinding binding = delegate.getBinding();
        if (binding.llTypingStrip == null) return;
        if (binding.llTypingStrip.getVisibility() != View.VISIBLE) return;

        if (typingDotsAnimator != null) typingDotsAnimator.stop();
        binding.llTypingStrip.setVisibility(View.GONE);
        binding.llTypingStrip.setAlpha(1f);
        binding.llTypingStrip.setScaleX(1f);
        binding.llTypingStrip.setScaleY(1f);
        com.callx.app.chat.ui.BannerPriorityCoordinator.onTypingStripHidden(
                binding.llWatchingBanner);
    }

    // ── Per-message "replying to this" glow ─────────────────────────────────
    // chatTypingReply/{chatId}/{uid} = messageId, written alongside the plain
    // typing/{chatId}/{uid} flag above. Only set while BOTH typing is active
    // AND a reply bar is open targeting a specific message — gives the exact
    // bubble being replied to a highlight, distinct from "they have the
    // chat open" (watching banner) or "this message is in view" (seen dot).

    private String lastWrittenReplyTargetId = null;
    private String lastPartnerReplyTargetId;

    /** Re-publishes (or clears) the reply-target highlight against whatever
     *  setOurTypingStatus last wrote for the plain typing flag. Public so
     *  ChatActivity#clearReply() can call this immediately when the reply
     *  bar is dismissed, instead of waiting for the next keystroke. */
    public void publishTypingReplyTarget() {
        String chatId = delegate.getChatId();
        String uid = delegate.getCurrentUid();
        if (chatId == null || uid == null) return;

        String targetId = Boolean.TRUE.equals(lastWrittenTypingValue)
                ? delegate.getCurrentReplyTargetId()
                : null;

        // Same value as last write (including both-null) — skip the round trip.
        if (targetId == null ? lastWrittenReplyTargetId == null : targetId.equals(lastWrittenReplyTargetId)) {
            return;
        }
        lastWrittenReplyTargetId = targetId;

        DatabaseReference ref = FirebaseUtils.getChatTypingReplyRef(chatId).child(uid);
        if (targetId == null) {
            ref.removeValue();
            ref.onDisconnect().cancel();
        } else {
            ref.setValue(targetId);
            ref.onDisconnect().removeValue();
        }
    }

    private void clearTypingReplyTarget() {
        if (lastWrittenReplyTargetId == null) return; // nothing to clear
        lastWrittenReplyTargetId = null;
        String chatId = delegate.getChatId();
        String uid = delegate.getCurrentUid();
        if (chatId == null || uid == null) return;
        DatabaseReference ref = FirebaseUtils.getChatTypingReplyRef(chatId).child(uid);
        ref.removeValue();
        ref.onDisconnect().cancel();
    }

    private void watchPartnerTypingReplyTarget() {
        String chatId = delegate.getChatId();
        String partnerUid = delegate.getPartnerUid();
        if (chatId == null || partnerUid == null || partnerUid.isEmpty()) return;

        typingReplyListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                lastPartnerReplyTargetId = s.getValue(String.class);
                java.util.Set<String> ids = lastPartnerReplyTargetId == null
                        ? java.util.Collections.emptySet()
                        : java.util.Collections.singleton(lastPartnerReplyTargetId);
                if (delegate.getPagingAdapter() != null) {
                    delegate.getPagingAdapter().setReplyTargetMessageIds(ids);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getChatTypingReplyRef(chatId)
                .child(partnerUid)
                .addValueEventListener(typingReplyListener);
    }

    // ── Online / last-seen status ─────────────────────────────────────────

    private void watchPartnerStatus() {
        String partnerUid = delegate.getPartnerUid();
        if (partnerUid == null || partnerUid.isEmpty()) return;

        onlineListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                ActivityChatBinding binding = delegate.getBinding();
                if (binding.tvStatus == null) return;

                Boolean partnerGhost = s.child("privacy").child("ghost").getValue(Boolean.class);
                if (Boolean.TRUE.equals(partnerGhost)) {
                    binding.tvStatus.setVisibility(View.GONE);
                    return;
                }

                String lastSeenVis = s.child("privacy").child("lastSeenVisibility").getValue(String.class);
                boolean hideLastSeen = SecurityManager.VIS_NOBODY.equals(lastSeenVis);

                Boolean online   = s.child("online").getValue(Boolean.class);
                Long lastSeen    = s.child("lastSeen").getValue(Long.class);

                String statusText;
                if (Boolean.TRUE.equals(online)) {
                    Boolean partnerIncognito = s.child("privacy").child("incognito").getValue(Boolean.class);
                    statusText = Boolean.TRUE.equals(partnerIncognito) ? "" : "online";
                } else if (!hideLastSeen && lastSeen != null && lastSeen > 0) {
                    statusText = formatLastSeenRelative(lastSeen);
                } else {
                    statusText = "";
                }

                binding.tvStatus.setText(statusText);
                binding.tvStatus.setVisibility(statusText.length() > 0 ? View.VISIBLE : View.GONE);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getUserRef(partnerUid).addValueEventListener(onlineListener);
    }

    private String formatLastSeenRelative(long ts) {
        long diff = System.currentTimeMillis() - ts;
        if (diff < 0) diff = 0;
        if (diff < 60_000L) {
            return "last seen just now";
        } else if (diff < 3_600_000L) {
            long mins = diff / 60_000L;
            return "last seen " + mins + " min" + (mins == 1 ? "" : "s") + " ago";
        } else if (diff < 86_400_000L) {
            return "last seen at " + new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(new Date(ts));
        } else if (diff < 7 * 86_400_000L) {
            return "last seen " + new SimpleDateFormat("EEE, hh:mm a", Locale.getDefault()).format(new Date(ts));
        } else {
            return "last seen " + new SimpleDateFormat("dd MMM", Locale.getDefault()).format(new Date(ts));
        }
    }

    // ── In-chat-screen presence ("watching banner") ───────────────────────
    // Tracks whether the partner currently has THIS chat screen open & in
    // foreground. Purely additive — never touches tv_status or the
    // separate ll_typing_strip.

    private final android.os.Handler presenceDebounceHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingOffWrite;
    /** Quick away-and-back navigation (e.g. rotation, brief app-switch) within
     *  this window won't flicker the "watching" banner for the partner. */
    private static final long PRESENCE_OFF_DEBOUNCE_MS = 1500;

    /** How long after the partner leaves the chat we keep showing a soft
     *  "active Xm ago" strip before fading the banner out completely. */
    private static final long JUST_LEFT_GRACE_MS = 90_000;
    private long lastSeenActiveAt = 0L;
    private final android.os.Handler justLeftTickHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable justLeftTickRunnable;

    /** Called by the Activity on resume/pause to publish our own in-chat presence. */
    public void setOurInChatScreen(boolean active) {
        // Cancel any pending "going offline" write — we either just came back
        // (active=true) or we're scheduling a fresh one below (active=false).
        if (pendingOffWrite != null) {
            presenceDebounceHandler.removeCallbacks(pendingOffWrite);
            pendingOffWrite = null;
        }

        if (active) {
            // Becoming active: publish immediately so the banner shows promptly.
            writePresence(true);
        } else {
            // Becoming inactive: debounce. If the user comes right back
            // (e.g. screen rotation, quick app-switcher peek) before this
            // fires, it gets cancelled above and the partner never sees a flicker.
            pendingOffWrite = () -> {
                writePresence(false);
                pendingOffWrite = null;
            };
            presenceDebounceHandler.postDelayed(pendingOffWrite, PRESENCE_OFF_DEBOUNCE_MS);
        }
    }

    private void writePresence(boolean active) {
        String chatId = delegate.getChatId();
        String uid = delegate.getCurrentUid();
        if (chatId == null || uid == null) return;

        // Respect the "Chat Activity Status" privacy toggle — if the user has
        // turned this off, we never publish true (always clear instead).
        if (active && delegate.getActivity() != null) {
            SecurityManager secMgr = SecurityManager.get(delegate.getActivity());
            if (!secMgr.isWatchingPresenceEnabled()) active = false;
        }

        DatabaseReference ref = FirebaseUtils.getChatPresenceRef(chatId).child(uid);
        ref.setValue(active);
        if (active) {
            // Safety net: if the app/connection dies without onPause firing
            // (process killed, network drop), Firebase clears this for us.
            ref.onDisconnect().setValue(false);
        } else {
            ref.onDisconnect().cancel();
        }
    }

    private void watchPartnerInChatScreen() {
        String chatId = delegate.getChatId();
        String partnerUid = delegate.getPartnerUid();
        if (chatId == null || partnerUid == null || partnerUid.isEmpty()) return;

        inChatListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                boolean inChat = Boolean.TRUE.equals(s.getValue(Boolean.class));
                if (inChat) {
                    lastSeenActiveAt = System.currentTimeMillis();
                    showWatchingBanner();
                } else {
                    beginJustLeftWindow();
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getChatPresenceRef(chatId)
                .child(partnerUid)
                .addValueEventListener(inChatListener);
    }

    // ── Per-message viewing ("seen-this-bubble" dot) ────────────────────────
    // Separate, finer-grained sibling of the chatPresence screen-open node:
    // chatViewing/{chatId}/{uid} = messageId of whatever message is currently
    // scrolled into view. Lets a single bubble show a tiny live dot exactly
    // while the partner is looking at THAT message, not just "screen open".

    private final android.os.Handler viewingDebounceHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingViewingWrite;
    private static final long VIEWING_DEBOUNCE_MS = 400;
    private String lastPublishedViewingId;
    /** Partner's most recently published "currently viewing" messageId —
     *  what the watching-banner tap jumps to. Null until they settle on
     *  a bubble (or once they scroll away / leave, depending on the last
     *  event received). */
    private String lastPartnerViewingMessageId;

    /** Call from the Activity's RecyclerView scroll-idle callback with the
     *  messageId of the topmost (or otherwise "focused") visible message. */
    public void publishViewingMessage(String messageId) {
        if (pendingViewingWrite != null) {
            viewingDebounceHandler.removeCallbacks(pendingViewingWrite);
            pendingViewingWrite = null;
        }
        if (messageId != null && messageId.equals(lastPublishedViewingId)) return;
        pendingViewingWrite = () -> {
            writeViewingMessage(messageId);
            pendingViewingWrite = null;
        };
        viewingDebounceHandler.postDelayed(pendingViewingWrite, VIEWING_DEBOUNCE_MS);
    }

    private void writeViewingMessage(String messageId) {
        String chatId = delegate.getChatId();
        String uid = delegate.getCurrentUid();
        if (chatId == null || uid == null) return;

        if (messageId != null && delegate.getActivity() != null) {
            SecurityManager secMgr = SecurityManager.get(delegate.getActivity());
            if (!secMgr.isWatchingPresenceEnabled()) messageId = null;
        }

        DatabaseReference ref = FirebaseUtils.getChatViewingRef(chatId).child(uid);
        lastPublishedViewingId = messageId;
        if (messageId == null) {
            ref.removeValue();
            ref.onDisconnect().cancel();
        } else {
            ref.setValue(messageId);
            ref.onDisconnect().removeValue();
        }
    }

    /** Stops publishing which message we're looking at (chat left/paused). */
    public void clearViewingMessage() {
        if (pendingViewingWrite != null) {
            viewingDebounceHandler.removeCallbacks(pendingViewingWrite);
            pendingViewingWrite = null;
        }
        writeViewingMessage(null);
    }

    private void watchPartnerViewingMessage() {
        String chatId = delegate.getChatId();
        String partnerUid = delegate.getPartnerUid();
        if (chatId == null || partnerUid == null || partnerUid.isEmpty()) return;

        viewingListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                String messageId = s.getValue(String.class);
                lastPartnerViewingMessageId = messageId;
                java.util.Set<String> ids = messageId == null
                        ? java.util.Collections.emptySet()
                        : java.util.Collections.singleton(messageId);
                if (delegate.getPagingAdapter() != null) {
                    delegate.getPagingAdapter().setViewingMessageIds(ids);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getChatViewingRef(chatId)
                .child(partnerUid)
                .addValueEventListener(viewingListener);
    }

    private void showWatchingBanner() {
        // Coming back (or first time showing) cancels any pending "just left" fade.
        cancelJustLeftWindow();

        ActivityChatBinding binding = delegate.getBinding();
        if (binding.llWatchingBanner == null) return;

        String name = delegate.getPartnerName();
        binding.tvWatchingName.setText((name != null ? name : "") + " aapko dekh rha hai");

        String photo = delegate.getPartnerPhoto();
        if (photo != null && !photo.isEmpty() && delegate.getActivity() != null) {
            Glide.with(delegate.getActivity())
                    .load(photo)
                    .placeholder(R.drawable.ic_person)
                    .into(binding.ivWatchingAvatar);
        }

        boolean alreadyShowing = binding.llWatchingBanner.getVisibility() == View.VISIBLE;
        if (!alreadyShowing) {
            // Fresh pop-in: alpha/scale belong to the entrance animation below,
            // not the priority system — full prominence by default, then
            // immediately deferred to applyCurrentPriority() if typing is
            // already active (avoids a one-frame flash at full opacity).
            binding.llWatchingBanner.setAlpha(1f);
            binding.llWatchingBanner.setScaleX(1f);
            binding.llWatchingBanner.setScaleY(1f);
        }

        if (alreadyShowing) {
            // Already on screen — just a content refresh, leave whatever
            // alpha/scale the priority coordinator currently has it at.
            return;
        }

        binding.ivWatchingAvatar.setScaleX(1f);
        binding.ivWatchingAvatar.setScaleY(1f);
        binding.llWatchingBanner.setVisibility(View.VISIBLE);

        // New arrival — immediately yield to typing if it's already showing,
        // instead of waiting for the next typing Firebase tick.
        com.callx.app.chat.ui.BannerPriorityCoordinator.applyCurrentPriority(
                binding.llWatchingBanner, binding.llTypingStrip);
    }

    /**
     * Partner just navigated away. Instead of snapping the banner away
     * instantly, swap its text to a soft "active Xm ago" strip (same avatar,
     * dimmed) for a short grace window, ticking every 20s, then fade out for
     * real. A quick return (caught by showWatchingBanner's cancel above)
     * cancels this with no flicker at all.
     */
    private void beginJustLeftWindow() {
        ActivityChatBinding binding = delegate.getBinding();
        if (binding.llWatchingBanner == null) return;
        if (binding.llWatchingBanner.getVisibility() != View.VISIBLE) return; // wasn't shown anyway

        cancelJustLeftWindow();
        String name = delegate.getPartnerName();
        final String label = name != null ? name : "";
        final long leftAt = lastSeenActiveAt > 0 ? lastSeenActiveAt : System.currentTimeMillis();

        binding.llWatchingBanner.setAlpha(0.6f);

        justLeftTickRunnable = new Runnable() {
            @Override public void run() {
                long elapsed = System.currentTimeMillis() - leftAt;
                if (elapsed >= JUST_LEFT_GRACE_MS) {
                    hideWatchingBanner();
                    return;
                }
                ActivityChatBinding b = delegate.getBinding();
                if (b.llWatchingBanner == null || b.llWatchingBanner.getVisibility() != View.VISIBLE) return;
                long mins = elapsed / 60_000L;
                String agoText = mins < 1 ? "abhi tak active tha" : ("active " + mins + "m pehle");
                b.tvWatchingName.setText(label.isEmpty() ? agoText : (label + " " + agoText));
                justLeftTickHandler.postDelayed(this, 20_000);
            }
        };
        justLeftTickHandler.post(justLeftTickRunnable);
    }

    private void cancelJustLeftWindow() {
        if (justLeftTickRunnable != null) {
            justLeftTickHandler.removeCallbacks(justLeftTickRunnable);
            justLeftTickRunnable = null;
        }
    }

    private void hideWatchingBanner() {
        cancelJustLeftWindow();
        ActivityChatBinding binding = delegate.getBinding();
        if (binding.llWatchingBanner == null) return;
        if (binding.llWatchingBanner.getVisibility() != View.VISIBLE) return;

        binding.llWatchingBanner.setVisibility(View.GONE);
        binding.llWatchingBanner.setAlpha(1f);
    }

    // ── Voice Recording Indicator ─────────────────────────────────────────
    // Mirrors the typing indicator concept but for voice notes: shows an
    // animated waveform pill + mic icon + partner name when the partner is
    // actively holding the mic button and recording a voice message.
    //
    // Firebase path: chatRecording/{chatId}/{uid} = true while recording,
    // removed immediately on release/cancel/send or disconnect.
    //
    // OUTGOING: ChatActivity calls publishOurRecordingState(true) when the
    //   user presses-and-holds the mic button, and false on release/cancel/send.
    // INCOMING: watchPartnerRecording() listens and drives ll_voice_recording_strip.

    private ValueEventListener recordingListener;

    /** Waveform bar IDs — animated in a staggered scale loop. */
    private static final int[] WAVE_BAR_IDS = {
        com.callx.app.chat.R.id.bar_wave_1,
        com.callx.app.chat.R.id.bar_wave_2,
        com.callx.app.chat.R.id.bar_wave_3,
        com.callx.app.chat.R.id.bar_wave_4,
        com.callx.app.chat.R.id.bar_wave_5,
    };
    private static final float[] WAVE_PEAK_SCALES = {0.5f, 1.0f, 0.4f, 0.8f, 0.3f};
    private final android.os.Handler waveHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable waveRunnable;
    private boolean waveAnimRunning = false;

    /** Called by ChatActivity when the user presses (recording=true) or
     *  releases/cancels/sends (recording=false) their own voice note mic. */
    public void publishOurRecordingState(boolean recording) {
        String chatId = delegate.getChatId();
        String uid = delegate.getCurrentUid();
        if (chatId == null || uid == null) return;
        DatabaseReference ref = FirebaseUtils.getChatRecordingRef(chatId).child(uid);
        if (recording) {
            ref.setValue(true);
            ref.onDisconnect().removeValue(); // safety net: process killed mid-recording
        } else {
            ref.removeValue();
            ref.onDisconnect().cancel();
        }
    }

    private void watchPartnerRecording() {
        String chatId = delegate.getChatId();
        String partnerUid = delegate.getPartnerUid();
        if (chatId == null || partnerUid == null || partnerUid.isEmpty()) return;

        recordingListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                boolean recording = Boolean.TRUE.equals(s.getValue(Boolean.class));
                if (recording) showVoiceRecordingStrip(); else hideVoiceRecordingStrip();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getChatRecordingRef(chatId)
                .child(partnerUid)
                .addValueEventListener(recordingListener);
    }

    private void showVoiceRecordingStrip() {
        ActivityChatBinding binding = delegate.getBinding();
        if (binding == null) return;
        android.view.View strip = binding.getRoot().findViewById(
                com.callx.app.chat.R.id.ll_voice_recording_strip);
        if (strip == null) return;

        // Populate avatar + name
        android.widget.TextView tvName = binding.getRoot().findViewById(
                com.callx.app.chat.R.id.tv_recording_name);
        if (tvName != null) {
            String name = delegate.getPartnerName();
            tvName.setText((name != null ? name : "") + " recording… \uD83C\uDF99\uFE0F");
        }
        de.hdodenhof.circleimageview.CircleImageView ivAvatar = binding.getRoot().findViewById(
                com.callx.app.chat.R.id.iv_recording_avatar);
        if (ivAvatar != null && delegate.getActivity() != null) {
            String photo = delegate.getPartnerPhoto();
            if (photo != null && !photo.isEmpty()) {
                Glide.with(delegate.getActivity())
                        .load(photo)
                        .placeholder(com.callx.app.chat.R.drawable.ic_person)
                        .into(ivAvatar);
            }
        }

        if (strip.getVisibility() == android.view.View.VISIBLE) {
            return; // already showing — don't re-animate
        }

        strip.setAlpha(1f);
        strip.setScaleX(1f);
        strip.setScaleY(1f);
        strip.setVisibility(android.view.View.VISIBLE);
    }

    private void hideVoiceRecordingStrip() {
        ActivityChatBinding binding = delegate.getBinding();
        if (binding == null) return;
        android.view.View strip = binding.getRoot().findViewById(
                com.callx.app.chat.R.id.ll_voice_recording_strip);
        if (strip == null || strip.getVisibility() != android.view.View.VISIBLE) return;

        stopWaveformAnimation();
        strip.setVisibility(android.view.View.GONE);
        strip.setAlpha(1f);
        strip.setScaleX(1f);
        strip.setScaleY(1f);
    }

    private void startWaveformAnimation(android.view.View root) {
        // Animation removed for performance
    }

    private void stopWaveformAnimation() {
        waveAnimRunning = false;
        if (waveRunnable != null) {
            waveHandler.removeCallbacks(waveRunnable);
            waveRunnable = null;
        }
    }

    private void startMicPulse(android.view.View root) {
        // Animation removed for performance
    }

    // ── Mute ─────────────────────────────────────────────────────────────

    private void watchMute() {
        String currentUid = delegate.getCurrentUid();
        String partnerUid = delegate.getPartnerUid();
        if (currentUid == null || currentUid.isEmpty() || partnerUid == null || partnerUid.isEmpty()) return;

        FirebaseUtils.db().getReference("muted")
                .child(currentUid).child(partnerUid)
                .addValueEventListener(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        delegate.setMuted(Boolean.TRUE.equals(s.getValue(Boolean.class)));
                        delegate.invalidateMenu();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
    }

    public void toggleMute() {
        FirebaseUtils.db().getReference("muted")
                .child(delegate.getCurrentUid()).child(delegate.getPartnerUid())
                .setValue(!delegate.isMuted());
    }

    // ── Mark read ─────────────────────────────────────────────────────────

    public void markMessagesRead() {
        delegate.getIoExecutor().execute(() -> {
            if (delegate.getDb() != null && delegate.getChatId() != null) {
                delegate.getDb().chatDao().updateUnread(delegate.getChatId(), 0);
            }
        });
        if (delegate.isOnline()) {
            FirebaseUtils.getContactsRef(delegate.getCurrentUid())
                    .child(delegate.getPartnerUid()).child("unread").setValue(0);
        } else {
            delegate.getIoExecutor().execute(() -> {
                if (delegate.getDb() != null && delegate.getChatId() != null) {
                    delegate.getDb().chatDao().queueMarkRead(delegate.getChatId());
                }
            });
        }
    }

    public void markRead(Message m) {
        if (m == null || m.id == null) return;
        if (delegate.getCurrentUid().equals(m.senderId)) return; // own message, nothing to ack
        if ("read".equals(m.status)) return; // already fully read, nothing left to do

        // Delivered ack is ALWAYS queued the FIRST time — regardless of the
        // read-receipts privacy toggle — matching WhatsApp-style behavior
        // where the grey "delivered" double-tick is independent of the blue
        // "read" tick. Guarded on m.status so a LATER markRead() call for the
        // same message (e.g. the Firebase listener re-attaching when the chat
        // is reopened, which re-fires onChildAdded for messages still inside
        // the query window) doesn't re-stamp deliveredAt with a fresh
        // timestamp — that was overwriting the true original delivered time
        // with whatever time "read" happened to fire at, so Message Info
        // showed identical Delivered/Seen times even though delivery had
        // actually happened much earlier.
        if (!"delivered".equals(m.status)) {
            pendingDeliveredFirebaseIds.add(m.id);
            scheduleReadFlush();
        }

        SecurityManager secMgr = SecurityManager.get(delegate.getActivity());
        if (!secMgr.isReadReceiptsEnabled()) return;

        // PERF FIX: don't fire a Firebase write per message — buffer the
        // id and flush ALL pending ids in one updateChildren() call after
        // a short debounce. See pendingReadFirebaseIds above.
        pendingReadFirebaseIds.add(m.id);
        scheduleReadFlush();
        // PERF FIX: was a per-message ioExecutor.execute(updateStatus(...))
        // here, i.e. one Room write (→ one PagingSource invalidation) per
        // historical unread message on chat open. Now buffered and applied
        // together with the rest of the initial sync in one transaction.
        delegate.queueMarkRead(m.id);
    }

    private void scheduleReadFlush() {
        readFlushHandler.removeCallbacks(readFlushRunnable);
        readFlushHandler.postDelayed(readFlushRunnable, READ_FLUSH_DEBOUNCE_MS);
    }

    private void flushPendingReadStatus() {
        if (pendingDeliveredFirebaseIds.isEmpty() && pendingReadFirebaseIds.isEmpty()) return;
        DatabaseReference messagesRef = delegate.getMessagesRef();
        if (messagesRef == null) {
            pendingDeliveredFirebaseIds.clear();
            pendingReadFirebaseIds.clear();
            return;
        }
        Map<String, Object> updates = new HashMap<>();

        // Delivered stamp — only ever queued the first time a message leaves
        // "sent" (see markRead()'s !"delivered".equals(m.status) guard), so
        // this is written at most once per message and never clobbers the
        // true original deliveredAt on a later, separate read flush.
        for (String id : pendingDeliveredFirebaseIds) {
            updates.put(id + "/status", "delivered");
            updates.put(id + "/deliveredAt", com.google.firebase.database.ServerValue.TIMESTAMP);
        }

        // Read stamp — the final state. Only touches "status" + "readAt";
        // deliveredAt (already set above, either in this same flush or an
        // earlier one) is left untouched so it keeps recording the real
        // delivery moment instead of getting overwritten with the read time.
        for (String id : pendingReadFirebaseIds) {
            updates.put(id + "/status", "read");
            // TICK ADVANCE #5: readAt timestamp, same field MessageStatusSync
            // stamps on the single-message transaction path — lets a future
            // "message info" screen show exactly when each message was read.
            updates.put(id + "/readAt", com.google.firebase.database.ServerValue.TIMESTAMP);
        }

        pendingDeliveredFirebaseIds.clear();
        pendingReadFirebaseIds.clear();
        // ONE network round-trip for the whole batch instead of one per message.
        messagesRef.updateChildren(updates)
                .addOnSuccessListener(unused -> {
                    for (String id : updates.keySet()) {
                        if (!id.endsWith("/status")) continue;
                        String msgId = id.substring(0, id.indexOf("/status"));
                        com.callx.app.utils.FirebaseUtils.getDeliveryPendingRef()
                                .child(msgId).removeValue();
                    }
                });
    }

    // ── Cleanup ───────────────────────────────────────────────────────────

    public void release() {
        readFlushHandler.removeCallbacks(readFlushRunnable);
        flushPendingReadStatus();
        if (typingListener != null && delegate.getChatId() != null) {
            FirebaseUtils.db().getReference("typing").child(delegate.getChatId())
                    .removeEventListener(typingListener);
        }
        if (onlineListener != null && delegate.getPartnerUid() != null) {
            FirebaseUtils.getUserRef(delegate.getPartnerUid())
                    .removeEventListener(onlineListener);
        }
        if (inChatListener != null && delegate.getChatId() != null && delegate.getPartnerUid() != null) {
            FirebaseUtils.getChatPresenceRef(delegate.getChatId())
                    .child(delegate.getPartnerUid())
                    .removeEventListener(inChatListener);
        }
        if (viewingListener != null && delegate.getChatId() != null && delegate.getPartnerUid() != null) {
            FirebaseUtils.getChatViewingRef(delegate.getChatId())
                    .child(delegate.getPartnerUid())
                    .removeEventListener(viewingListener);
        }
        if (typingReplyListener != null && delegate.getChatId() != null && delegate.getPartnerUid() != null) {
            FirebaseUtils.getChatTypingReplyRef(delegate.getChatId())
                    .child(delegate.getPartnerUid())
                    .removeEventListener(typingReplyListener);
        }
        if (recordingListener != null && delegate.getChatId() != null && delegate.getPartnerUid() != null) {
            FirebaseUtils.getChatRecordingRef(delegate.getChatId())
                    .child(delegate.getPartnerUid())
                    .removeEventListener(recordingListener);
        }
        if (typingDotsAnimator != null) {
            typingDotsAnimator.stop();
            typingDotsAnimator = null;
        }
        stopWaveformAnimation();
        cancelJustLeftWindow();
        clearOurTypingStatus();
        clearViewingMessage();
        publishOurRecordingState(false); // flush immediately on destroy
        // Activity is being destroyed for good — flush immediately, skip debounce.
        if (pendingOffWrite != null) {
            presenceDebounceHandler.removeCallbacks(pendingOffWrite);
            pendingOffWrite = null;
        }
        writePresence(false);
    }
}
