package com.callx.app.voicerooms;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * VrSession — ek chalta hua voice-room session (app-wide singleton).
 *
 * Audio (LiveKit) is object ke paas rehta hai, Activity ke paas nahi. Isliye room ko
 * minimize / Activity finish karne par audio chalta rehta hai (mini player), aur
 * VoiceRoomService (foreground service) process ko zinda rakhti hai.
 */
final class VrSession implements VrAudio.Listener {

    interface Observer {
        void onSessionState();                   // connected / reconnecting / canPublish / mic badla
        void onSpeaking(Set<String> uids);
        void onEnded(String reason);             // room band / kick / network hamesha ke liye gaya
    }

    interface StartCb { void onStarted(); void onFailed(String message); }

    private static final VrSession INSTANCE = new VrSession();
    static VrSession get() { return INSTANCE; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Context app;
    private VrAudio audio;
    private Observer observer;

    private boolean active, starting, leaving;
    private String roomId = "", title = "", hostName = "", hostUid = "";
    private boolean host, micOn, canPublish, connected, reconnecting;
    private Set<String> speaking = Collections.emptySet();

    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (active && host && roomId != null && !roomId.isEmpty()) VrRepo.heartbeat(roomId);
            if (active && host) main.postDelayed(this, 60_000);
        }
    };

    private VrSession() {}

    // ───────────────────────── state ─────────────────────────
    boolean isActive() { return active; }
    boolean isActiveFor(String id) { return active && id != null && id.equals(roomId); }
    String roomId() { return roomId; }
    String title() { return title; }
    String hostName() { return hostName; }
    boolean isHost() { return host; }
    boolean micOn() { return micOn; }
    boolean canPublish() { return canPublish; }
    boolean isConnected() { return connected; }
    boolean isReconnecting() { return reconnecting; }
    Set<String> speaking() { return speaking; }

    void setObserver(Observer o) { observer = o; }
    void clearObserver(Observer o) { if (observer == o) observer = null; }

    static boolean hasMicPermission(Context c) {
        return ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ───────────────────────── start / leave ─────────────────────────
    void start(Context ctx, String roomId, String title, String hostName, String hostUid,
               boolean amHost, StartCb cb) {
        app = ctx.getApplicationContext();
        if (active && roomId.equals(this.roomId)) { if (cb != null) cb.onStarted(); return; }
        if (starting) { if (cb != null) cb.onFailed("Already connecting"); return; }
        if (active) leave(false);

        starting = true;
        this.roomId = roomId; this.title = title; this.hostName = hostName; this.hostUid = hostUid;
        this.host = amHost;
        this.micOn = false; this.canPublish = amHost; this.connected = false; this.reconnecting = false;

        VrRepo.joinPresence(roomId);
        VrApi.token(roomId, new VrApi.Done() {
            @Override public void onDone(Map<String, Object> data) {
                String token = VrApi.str(data, "token");
                String url = VrApi.str(data, "url");
                String role = VrApi.str(data, "role");
                if (token.isEmpty() || url.isEmpty()) { fail("Voice server unavailable"); return; }
                host = "host".equals(role);
                canPublish = !"listener".equals(role);
                audio = new VrAudio(app);
                audio.setListener(VrSession.this);
                audio.connect(url, token);
                active = true;
                starting = false;
                VoiceRoomService.start(app);
                main.removeCallbacks(heartbeat);
                if (host) main.post(heartbeat);
                if (cb != null) cb.onStarted();
                notifyState();
            }
            @Override public void onError(String message) { fail(message); }

            private void fail(String m) {
                starting = false;
                VrRepo.leavePresence(VrSession.this.roomId);
                reset();
                if (cb != null) cb.onFailed(m == null || m.isEmpty() ? "Couldn't join the room" : m);
            }
        });
    }

    /** endForAll: host ke liye room band karna. */
    void leave(boolean endForAll) {
        if (!active && !starting) return;
        leaving = true;
        main.removeCallbacks(heartbeat);
        String id = roomId;
        boolean wasHost = host;
        if (audio != null) { audio.release(); audio = null; }
        if (id != null && !id.isEmpty()) {
            VrRepo.leavePresence(id);
            if (wasHost && endForAll) VrApi.endRoom(id, null);
            else if (!wasHost) VrApi.leaveRoom(id, null);
        }
        if (app != null) VoiceRoomService.stop(app);
        reset();
        leaving = false;
    }

    private void reset() {
        active = false; starting = false;
        roomId = ""; title = ""; hostName = ""; hostUid = "";
        host = false; micOn = false; canPublish = false; connected = false; reconnecting = false;
        speaking = Collections.emptySet();
    }

    // ───────────────────────── mic ─────────────────────────
    interface MicCb { void onResult(boolean ok, String error); }

    /** User ka mic toggle. Permission Activity pehle le chuki hoti hai. */
    void setMic(boolean on, MicCb cb) {
        if (!active || audio == null) { if (cb != null) cb.onResult(false, "Not connected"); return; }
        if (on && !canPublish) { if (cb != null) cb.onResult(false, "Raise your hand to speak"); return; }
        audio.setMicEnabled(on, (ok, err) -> {
            if (ok) {
                micOn = on;
                if (canPublishAndInRoom()) VrRepo.setSelfMuted(roomId, !on);
                if (app != null) VoiceRoomService.refresh(app);
                notifyState();
            }
            if (cb != null) cb.onResult(ok, err);
        });
    }

    /** Host ne mute kiya / demote hua => mic zabardasti band. */
    void forceMicOff() {
        if (!active || audio == null || !micOn) return;
        audio.setMicEnabled(false, (ok, err) -> { micOn = false; notifyState(); });
    }

    private boolean canPublishAndInRoom() { return canPublish && roomId != null && !roomId.isEmpty(); }

    // ───────────────────────── VrAudio.Listener ─────────────────────────
    @Override public void onConnected() { connected = true; reconnecting = false; notifyState(); }
    @Override public void onReconnecting() { reconnecting = true; notifyState(); }
    @Override public void onReconnected() { reconnecting = false; connected = true; notifyState(); }

    @Override public void onDisconnected(String reason) {
        if (leaving || !active) return;
        endLocal(reason == null || reason.isEmpty() ? "disconnected" : reason);
    }

    @Override public void onSpeakers(Set<String> uids) {
        speaking = new HashSet<>(uids);
        if (observer != null) observer.onSpeaking(speaking);
    }

    @Override public void onCanPublish(boolean can) {
        boolean was = canPublish;
        canPublish = can || host;
        if (was && !canPublish && micOn) forceMicOff();
        notifyState();
    }

    @Override public void onError(String message) {
        if (!connected && active) endLocal(message == null ? "connect_failed" : message);
    }

    /** Server ne room band kiya / kick kiya / connection gaya. */
    private void endLocal(String reason) {
        String id = roomId;
        main.removeCallbacks(heartbeat);
        if (audio != null) { audio.release(); audio = null; }
        if (id != null && !id.isEmpty()) VrRepo.leavePresence(id);
        if (app != null) VoiceRoomService.stop(app);
        reset();
        if (observer != null) observer.onEnded(reason);
    }

    private void notifyState() { if (observer != null) observer.onSessionState(); }
}
