package com.callx.app.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.callx.app.ui.screens.IncomingCallScreen
import com.callx.app.ui.theme.CallXTheme

class IncomingCallActivityKt : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val callerName  = intent.getStringExtra("callerName")  ?: "Unknown"
        val callerPhoto = intent.getStringExtra("callerPhoto")
        val partnerUid  = intent.getStringExtra("partnerUid")  ?: ""
        val isVideo     = intent.getBooleanExtra("isVideo", false)
        val chatId      = intent.getStringExtra("chatId") ?: ""

        setContent {
            CallXTheme {
                IncomingCallScreen(
                    callerName  = callerName,
                    callerPhoto = callerPhoto,
                    isVideo     = isVideo,
                    onAccept    = {
                        startActivity(
                            Intent(this, CallActivityKt::class.java)
                                .putExtra("partnerUid",  partnerUid)
                                .putExtra("partnerName", callerName)
                                .putExtra("callerPhoto", callerPhoto)
                                .putExtra("isVideo",     isVideo)
                                .putExtra("chatId",      chatId)
                        )
                        finish()
                    },
                    onReject    = { finish() }
                )
            }
        }
    }
}
