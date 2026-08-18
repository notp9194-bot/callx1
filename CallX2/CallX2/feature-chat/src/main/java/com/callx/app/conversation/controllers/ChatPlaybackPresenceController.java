package com.callx.app.conversation.controllers;

import androidx.annotation.NonNull;

import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.Collections;
import java.util.Set;

/**
 * Playback presence — Instagram-DM-style "listening…/watching…" indicator.
 * Sibling of ChatPresenceController's per-message viewing dot, but driven by
 * actual audio/video PLAYBACK instead of scroll position:
 *
 * Firebase path: chatPlayback/{chatId}/{uid} = messageId currently playing
 * (removed the instant playback pauses, finishes, errors out, or the
 * Activity is torn down). See FirebaseUtils#getChatPlaybackRef.
 *
 * Wiring:
 *  • OUTGOING — MessagePagingAdapter calls back through
 *    ActionListener#onPlaybackStateChanged(Message, boolean) whenever ITS
 *    own MediaPlayer starts/stops a voice note; MediaViewerActivity (full-
 *    screen video) writes directly via the same FirebaseUtils ref since it
 *    lives outside the chat screen. Both funnel into publishOurPlayback().
 *  • INCOMING — watchPartnerPlayback() mirrors the partner's node into a
 *    single-element (or empty) Set<messageId>, fed straight into
 *    MessagePagingAdapter#setPlayingMessageIds so the exact bubble being
 *    played lights up with a small "🎧 listening…" badge.
 */
public class ChatPlaybackPresenceController {

    private final ChatActivityDelegate delegate;
    private ValueEventListener partnerPlaybackListener;

    /** Last messageId we actually wrote — guards against redundant writes
     *  (e.g. the seekbar progress tick firing far more often than the
     *  play/pause state actually changes). */
    private String lastWrittenMessageId;

    public ChatPlaybackPresenceController(ChatActivityDelegate delegate) {
        this.delegate = delegate;
    }

    // ── Init / teardown ──────────────────────────────────────────────────

    public void init() {
        watchPartnerPlayback();
    }

    public void release() {
        if (partnerPlaybackListener != null && delegate.getChatId() != null
                && delegate.getPartnerUid() != null) {
            FirebaseUtils.getChatPlaybackRef(delegate.getChatId())
                    .child(delegate.getPartnerUid())
                    .removeEventListener(partnerPlaybackListener);
            partnerPlaybackListener = null;
        }
        // Activity is going away for good — flush immediately, no debounce,
        // so we don't leave a stale "listening" badge on the partner's screen.
        publishOurPlayback(null);
    }

    // ── Outgoing: publish what WE are currently playing ─────────────────

    /** Call with the messageId + true when our own MediaPlayer/ExoPlayer
     *  starts a voice note or video, and with playing=false (messageId is
     *  ignored) the moment it pauses, finishes, errors, or we switch to a
     *  different bubble. Safe to call redundantly. */
    public void publishPlaybackState(String messageId, boolean playing) {
        publishOurPlayback(playing ? messageId : null);
    }

    private void publishOurPlayback(String messageId) {
        String chatId = delegate.getChatId();
        String uid = delegate.getCurrentUid();
        if (chatId == null || uid == null) return;
        if (messageId != null && messageId.equals(lastWrittenMessageId)) return;
        if (messageId == null && lastWrittenMessageId == null) return;
        lastWrittenMessageId = messageId;

        DatabaseReference ref = FirebaseUtils.getChatPlaybackRef(chatId).child(uid);
        if (messageId == null) {
            ref.removeValue();
            ref.onDisconnect().cancel();
        } else {
            ref.setValue(messageId);
            // Safety net: if the app dies mid-playback (process killed,
            // connection drop), Firebase clears the badge for us.
            ref.onDisconnect().removeValue();
        }
    }

    // ── Incoming: mirror the PARTNER's playback into the bubble badge ───

    private void watchPartnerPlayback() {
        String chatId = delegate.getChatId();
        String partnerUid = delegate.getPartnerUid();
        if (chatId == null || partnerUid == null || partnerUid.isEmpty()) return;

        partnerPlaybackListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                String messageId = s.getValue(String.class);
                Set<String> ids = (messageId == null || messageId.isEmpty())
                        ? Collections.emptySet()
                        : Collections.singleton(messageId);
                if (delegate.getPagingAdapter() != null) {
                    delegate.getPagingAdapter().setPlayingMessageIds(ids);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getChatPlaybackRef(chatId)
                .child(partnerUid)
                .addValueEventListener(partnerPlaybackListener);
    }
}
