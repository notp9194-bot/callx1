package com.callx.app.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import com.callx.app.ui.screens.CallScreen
import com.callx.app.ui.theme.CallXTheme

class CallActivityKt : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val partnerName = intent.getStringExtra("partnerName") ?: "Unknown"
        val partnerPhoto = intent.getStringExtra("callerPhoto")
        val isVideo     = intent.getBooleanExtra("isVideo", false)

        setContent {
            CallXTheme {
                var isMuted     by remember { mutableStateOf(false) }
                var isSpeaker   by remember { mutableStateOf(false) }
                var seconds     by remember { mutableIntStateOf(0) }

                LaunchedEffect(Unit) {
                    while (true) { delay(1000); seconds++ }
                }

                val duration = remember(seconds) {
                    val m = seconds / 60; val s = seconds % 60
                    "%02d:%02d".format(m, s)
                }

                CallScreen(
                    partnerName    = partnerName,
                    partnerPhoto   = partnerPhoto,
                    callDuration   = duration,
                    isMuted        = isMuted,
                    isSpeakerOn    = isSpeaker,
                    isVideo        = isVideo,
                    onEndCall      = { finish() },
                    onToggleMute   = { isMuted = !isMuted },
                    onToggleSpeaker = { isSpeaker = !isSpeaker },
                    onToggleVideo  = {}
                )
            }
        }
    }
}
