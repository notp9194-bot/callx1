package com.callx.app.activities

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.callx.app.services.IncomingRingService
import com.callx.app.ui.screens.IncomingCallScreen
import com.callx.app.ui.theme.CallXTheme
import com.callx.app.utils.Constants
import com.callx.app.utils.FirebaseUtils
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener

class IncomingCallActivity : ComponentActivity() {

    private var ringtonePlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var callId: String?   = null
    private var fromUid: String?  = null
    private var fromName: String? = null
    private var fromPhoto: String? = null
    private var isVideo = false
    private var acted = false
    private var statusListener: ValueEventListener? = null
    private val autoRejectHandler = Handler(Looper.getMainLooper())
    private val AUTO_REJECT_MS = 60_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        callId   = getIntent().getStringExtra(Constants.EXTRA_CALL_ID)
            ?: getIntent().getStringExtra("callId")
        fromUid  = getIntent().getStringExtra(Constants.EXTRA_PARTNER_UID)
            ?: getIntent().getStringExtra("fromUid")
        fromName = getIntent().getStringExtra(Constants.EXTRA_PARTNER_NAME)
            ?: getIntent().getStringExtra("fromName")
        fromPhoto = getIntent().getStringExtra("fromPhoto")
        isVideo  = getIntent().getBooleanExtra(Constants.EXTRA_IS_VIDEO, false)
            .let { if (!it) getIntent().getBooleanExtra("video", false) else it }

        acquireWakeLock()
        startLoopingRingtone()
        watchCallStatus()
        autoRejectHandler.postDelayed({ if (!acted) reject() }, AUTO_REJECT_MS)

        setContent {
            CallXTheme {
                IncomingCallScreen(
                    callerName  = fromName ?: "Unknown",
                    callerPhoto = fromPhoto,
                    isVideo     = isVideo,
                    onAccept    = ::accept,
                    onReject    = ::reject
                )
            }
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "callx:incoming_call"
            )
            wakeLock?.acquire(AUTO_REJECT_MS + 5_000L)
        } catch (_: Exception) {}
    }

    private fun startLoopingRingtone() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtonePlayer = MediaPlayer().apply {
                setDataSource(this@IncomingCallActivity, uri)
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {}
    }

    private fun stopRingtone() {
        try {
            ringtonePlayer?.stop(); ringtonePlayer?.release(); ringtonePlayer = null
        } catch (_: Exception) {}
    }

    private fun watchCallStatus() {
        if (callId.isNullOrEmpty()) return
        statusListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val st = s.getValue(String::class.java)
                if (st == "ended" || st == "cancelled") {
                    runOnUiThread { if (!acted) reject() }
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        FirebaseUtils.db().getReference("activeCalls")
            .child(callId!!).child("status").addValueEventListener(statusListener!!)
    }

    private fun accept() {
        if (acted) return
        acted = true
        autoRejectHandler.removeCallbacksAndMessages(null)
        stopRingtone()
        stopIncomingRingService()
        cancelRingNotification()
        if (!callId.isNullOrEmpty()) {
            FirebaseUtils.db().getReference("activeCalls")
                .child(callId!!).child("status").setValue("accepted")
        }
        startActivity(Intent(this, CallActivity::class.java).apply {
            putExtra("partnerUid",  fromUid)
            putExtra("partnerName", fromName ?: "")
            putExtra("partnerPhoto",fromPhoto ?: "")
            putExtra("isCaller",    false)
            putExtra("video",       isVideo)
            putExtra("callId",      callId)
        })
        finish()
    }

    private fun reject() {
        if (acted) return
        acted = true
        autoRejectHandler.removeCallbacksAndMessages(null)
        stopRingtone()
        stopIncomingRingService()
        cancelRingNotification()
        if (!callId.isNullOrEmpty()) {
            FirebaseUtils.db().getReference("activeCalls")
                .child(callId!!).child("status").setValue("rejected")
        }
        finish()
    }

    private fun stopIncomingRingService() {
        try { stopService(Intent(this, IncomingRingService::class.java)) } catch (_: Exception) {}
    }

    private fun cancelRingNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(Constants.CALL_RING_NOTIF_ID)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        autoRejectHandler.removeCallbacksAndMessages(null)
        stopRingtone()
        wakeLock?.takeIf { it.isHeld }?.release()
        statusListener?.let {
            callId?.takeIf { it.isNotEmpty() }?.let { id ->
                FirebaseUtils.db().getReference("activeCalls")
                    .child(id).child("status").removeEventListener(it)
            }
        }
        super.onDestroy()
    }
}
