package com.callx.app.audio;

import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * WhatsApp-level persistent voice-note playback.
 *
 * ChatActivity's MessagePagingAdapter still owns the actual MediaPlayer
 * creation/prepare/E2E-decrypt logic for a chat-bubble voice note — this
 * class does NOT duplicate any of that. What it does is hold a SECOND,
 * app-scoped strong reference to whichever MediaPlayer is currently
 * playing (via {@link #notifyStarted}), plus a tiny metadata snapshot
 * (who, which chat, playing/paused). Because that reference lives here —
 * not inside the Activity/Adapter — the MediaPlayer object is never
 * garbage-collected or released just because ChatActivity is destroyed:
 * leaving the chat screen simply means the Activity drops ITS OWN copy of
 * the reference (see MessagePagingAdapter#onDetachedFromRecyclerView),
 * while this singleton keeps the audio playing.
 *
 * MainActivity listens via {@link Listener} and renders the small green
 * "mini player" strip above the toolbar (▶/❚❚, avatar, name, ✕) exactly
 * like WhatsApp's own voice-note-still-playing bar. Tapping ✕ or letting
 * the clip finish calls {@link #stopAndClear()}, which is the only path
 * that actually stops/releases the MediaPlayer.
 */
public final class GlobalVoicePlaybackManager {

    private static GlobalVoicePlaybackManager instance;

    public static synchronized GlobalVoicePlaybackManager getInstance() {
        if (instance == null) instance = new GlobalVoicePlaybackManager();
        return instance;
    }

    private GlobalVoicePlaybackManager() {}

    public interface Listener {
        /** New voice note became the active one (or metadata changed while active). */
        void onPlaybackStarted(String messageId, String chatId, String partnerUid,
                                String displayName, String avatarUrl, boolean outgoing);
        /** Same active message, just paused/resumed. */
        void onPlaybackToggled(String messageId, boolean playing);
        /** Active message finished, errored, or was explicitly stopped — nothing playing now. */
        void onPlaybackStopped(String messageId);
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MediaPlayer activePlayer;
    private String messageId;
    private String chatId;
    private String partnerUid;
    private String displayName;
    private String avatarUrl;
    private boolean outgoing;

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        if (l != null) listeners.remove(l);
    }

    // ── State queried by the mini player / chat bubble UI ───────────────

    public boolean hasActiveMessage() {
        return messageId != null;
    }

    public boolean isActiveMessage(String mid) {
        return mid != null && mid.equals(messageId);
    }

    public boolean isPlaying() {
        try {
            return activePlayer != null && activePlayer.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isPlayingMessage(String mid) {
        return isActiveMessage(mid) && isPlaying();
    }

    public String getCurrentMessageId() { return messageId; }
    public String getCurrentChatId()    { return chatId; }
    public String getCurrentPartnerUid(){ return partnerUid; }
    public String getDisplayName()      { return displayName; }
    public String getAvatarUrl()        { return avatarUrl; }
    public boolean isOutgoing()         { return outgoing; }

    // ── Called by MessagePagingAdapter whenever ITS OWN MediaPlayer for a
    //    voice-note bubble starts/pauses/stops. See call sites in
    //    playAudioFromPath()/toggleAudio(). ──────────────────────────────

    /** A brand-new (or restarted) voice note began playing — takes over as the active one. */
    public void notifyStarted(MediaPlayer player, String messageId, String chatId, String partnerUid,
                               String displayName, String avatarUrl, boolean outgoing) {
        this.activePlayer = player;
        this.messageId = messageId;
        this.chatId = chatId;
        this.partnerUid = partnerUid;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
        this.outgoing = outgoing;
        for (Listener l : listeners) {
            l.onPlaybackStarted(messageId, chatId, partnerUid, displayName, avatarUrl, outgoing);
        }
    }

    /** The active message was paused or resumed in place (same MediaPlayer, same message). */
    public void notifyToggled(String messageId, boolean playing) {
        if (!isActiveMessage(messageId)) return;
        for (Listener l : listeners) l.onPlaybackToggled(messageId, playing);
    }

    /** The active message finished, errored, or was replaced — clears state, nothing playing now. */
    public void notifyStopped(String messageId) {
        if (!isActiveMessage(messageId)) return;
        String mid = this.messageId;
        this.activePlayer = null;
        this.messageId = null;
        this.chatId = null;
        this.partnerUid = null;
        this.displayName = null;
        this.avatarUrl = null;
        for (Listener l : listeners) l.onPlaybackStopped(mid);
    }

    /**
     * Called by MessagePagingAdapter#onDetachedFromRecyclerView when the
     * chat screen is going away while a voice note is still mid-playback.
     * No-op on this class — the adapter simply drops its own local
     * `player` field reference without calling stop()/release(); this
     * singleton's separately-held reference (set in notifyStarted) is what
     * keeps the audio alive and controllable from the mini player. Kept as
     * a named call site purely for readability at the adapter's call site.
     */
    public void onChatScreenLeftWhilePlaying() { /* intentionally no-op */ }

    // ── Mini player controls (MainActivity) ──────────────────────────────

    /** Toggles play/pause on the currently active MediaPlayer (no-op if nothing active). */
    public void togglePlayPause() {
        if (activePlayer == null || messageId == null) return;
        try {
            if (activePlayer.isPlaying()) {
                activePlayer.pause();
                notifyToggled(messageId, false);
            } else {
                activePlayer.start();
                notifyToggled(messageId, true);
            }
        } catch (Exception ignored) {}
    }

    /** Stops and releases the active MediaPlayer entirely, clears state, hides the mini player. */
    public void stopAndClear() {
        String mid = this.messageId;
        if (activePlayer != null) {
            try { activePlayer.stop(); } catch (Exception ignored) {}
            try { activePlayer.release(); } catch (Exception ignored) {}
        }
        activePlayer = null;
        this.messageId = null;
        this.chatId = null;
        this.partnerUid = null;
        this.displayName = null;
        this.avatarUrl = null;
        if (mid != null) {
            for (Listener l : listeners) l.onPlaybackStopped(mid);
        }
    }
}
