package com.callx.app.voicerooms

import android.content.Context
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * VrAudio — LiveKit ka chhota Java-friendly wrapper (voice rooms ka audio).
 *
 * - Participant identity/name = Firebase uid (server token me name=uid set karta hai),
 *   isliye "speaking" set seedha uids ka set hai.
 * - Mic permission (canPublish) server se aati hai; listener ke liye false.
 * - Saare callbacks main thread par.
 */
class VrAudio(context: Context) {

    interface Listener {
        fun onConnected()
        fun onReconnecting()
        fun onReconnected()
        fun onDisconnected(reason: String?)
        fun onSpeakers(uids: Set<String>)
        fun onCanPublish(canPublish: Boolean)
        fun onError(message: String)
    }

    fun interface Result {
        fun onResult(ok: Boolean, error: String?)
    }

    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var room: Room? = null

    @Volatile
    private var listener: Listener? = null

    fun setListener(l: Listener?) {
        listener = l
    }

    fun connect(url: String, token: String) {
        if (room != null) return
        val r = LiveKit.create(appContext)
        room = r

        scope.launch {
            r.events.collect { ev ->
                when (ev) {
                    is RoomEvent.ActiveSpeakersChanged -> {
                        val set = HashSet<String>()
                        for (p in ev.speakers) {
                            val n = p.name
                            if (n != null) set.add(n)
                        }
                        listener?.onSpeakers(set)
                    }
                    is RoomEvent.ParticipantPermissionsChanged -> {
                        if (ev.participant === r.localParticipant) {
                            listener?.onCanPublish(ev.newPermissions?.canPublish ?: false)
                        } else {
                            Unit
                        }
                    }
                    is RoomEvent.Reconnecting -> listener?.onReconnecting()
                    is RoomEvent.Reconnected -> listener?.onReconnected()
                    is RoomEvent.Disconnected -> listener?.onDisconnected(ev.error?.message)
                    is RoomEvent.FailedToConnect -> listener?.onError(ev.error.message ?: "Connection failed")
                    else -> {}
                }
            }
        }

        scope.launch {
            try {
                r.connect(url, token)
                listener?.onConnected()
                listener?.onCanPublish(r.localParticipant.permissions?.canPublish ?: false)
            } catch (e: Exception) {
                listener?.onError(e.message ?: "Connection failed")
            }
        }
    }

    /** Mic on/off (sirf tab kaam karega jab server ne publish permission di ho). */
    fun setMicEnabled(enabled: Boolean, done: Result?) {
        val r = room
        if (r == null) {
            done?.onResult(false, "Not connected")
            return
        }
        scope.launch {
            try {
                r.localParticipant.setMicrophoneEnabled(enabled)
                done?.onResult(true, null)
            } catch (e: Exception) {
                done?.onResult(false, e.message ?: "Mic error")
            }
        }
    }

    fun disconnect() {
        try {
            room?.disconnect()
        } catch (_: Exception) {
        }
    }

    fun release() {
        listener = null
        try {
            room?.disconnect()
            room?.release()
        } catch (_: Exception) {
        }
        room = null
        scope.cancel()
    }
}
