package com.callx.app.conversation.controllers;

import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.OvershootInterpolator;

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
import java.util.Locale;

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

    private ValueEventListener typingListener;
    private ValueEventListener onlineListener;
    private ValueEventListener inChatListener;
    private ValueEventListener viewingListener;

    public ChatPresenceController(ChatActivityDelegate delegate) {
        this.delegate = delegate;
    }

    // ── Init ──────────────────────────────────────────────────────────────

    public void init() {
        watchPartnerStatus();
        watchTyping();
        watchPartnerInChatScreen();
        watchPartnerViewingMessage();
        watchMute();
        markMessagesRead();
        setupBannerTap();
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

    public void setOurTypingStatus(boolean typing) {
        FirebaseUtils.db().getReference("typing")
                .child(delegate.getChatId()).child(delegate.getCurrentUid()).setValue(typing);
    }

    public void clearOurTypingStatus() {
        FirebaseUtils.db().getReference("typing")
                .child(delegate.getChatId()).child(delegate.getCurrentUid()).setValue(false);
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
                ActivityChatBinding binding = delegate.getBinding();
                if (binding.tvTyping == null || binding.tvStatus == null) return;
                if (typing) {
                    binding.tvTyping.setVisibility(View.VISIBLE);
                    binding.tvStatus.setVisibility(View.GONE);
                } else {
                    binding.tvTyping.setVisibility(View.GONE);
                    binding.tvStatus.setVisibility(
                            binding.tvStatus.getText().length() > 0 ? View.VISIBLE : View.GONE);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.db().getReference("typing").child(delegate.getChatId())
                .addValueEventListener(typingListener);
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
                boolean typingVisible = binding.tvTyping != null
                        && binding.tvTyping.getVisibility() == View.VISIBLE;
                binding.tvStatus.setVisibility(
                        (!typingVisible && statusText.length() > 0) ? View.VISIBLE : View.GONE);
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
    // foreground. Purely additive — never touches tv_status / tv_typing.

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
            SecurityManager secMgr = new SecurityManager(delegate.getActivity());
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
            SecurityManager secMgr = new SecurityManager(delegate.getActivity());
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
        binding.llWatchingBanner.setAlpha(1f);

        String photo = delegate.getPartnerPhoto();
        if (photo != null && !photo.isEmpty() && delegate.getActivity() != null) {
            Glide.with(delegate.getActivity())
                    .load(photo)
                    .placeholder(R.drawable.ic_person)
                    .into(binding.ivWatchingAvatar);
        }

        if (binding.llWatchingBanner.getVisibility() == View.VISIBLE) return; // already showing

        binding.ivWatchingAvatar.setScaleX(0f);
        binding.ivWatchingAvatar.setScaleY(0f);
        binding.llWatchingBanner.setVisibility(View.VISIBLE);
        binding.ivWatchingAvatar.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        binding.ivWatchingAvatar.animate()
                .scaleX(1f).scaleY(1f)
                .setDuration(380)
                .setInterpolator(new OvershootInterpolator(2.2f))
                .start();
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

        binding.llWatchingBanner.animate().alpha(0.6f).setDuration(250).start();

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

        binding.ivWatchingAvatar.animate()
                .scaleX(0f).scaleY(0f)
                .setDuration(200)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    binding.llWatchingBanner.setVisibility(View.GONE);
                    binding.llWatchingBanner.setAlpha(1f);
                })
                .start();
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
        if (!delegate.getCurrentUid().equals(m.senderId) && !"read".equals(m.status)) {
            SecurityManager secMgr = new SecurityManager(delegate.getActivity());
            if (!secMgr.isReadReceiptsEnabled()) return;
            delegate.getMessagesRef().child(m.id).child("status").setValue("read");
            delegate.getIoExecutor().execute(() ->
                    delegate.getDb().messageDao().updateStatus(m.id, "read"));
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────

    public void release() {
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
        cancelJustLeftWindow();
        clearOurTypingStatus();
        clearViewingMessage();
        // Activity is being destroyed for good — flush immediately, skip debounce.
        if (pendingOffWrite != null) {
            presenceDebounceHandler.removeCallbacks(pendingOffWrite);
            pendingOffWrite = null;
        }
        writePresence(false);
    }
}
