package com.callx.app.ui.screens

import android.view.View
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.callx.app.ui.theme.*

data class CallUiState(
    val partnerName: String = "",
    val partnerPhoto: String? = null,
    val statusText: String = "Calling...",
    val isVideo: Boolean = false,
    val micEnabled: Boolean = true,
    val cameraEnabled: Boolean = true,
    val speakerEnabled: Boolean = false,
    val isConnected: Boolean = false
)

@Composable
fun CallScreen(
    state: CallUiState,
    localVideoView: View?,
    remoteVideoView: View?,
    onEndCall: () -> Unit,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleSpeaker: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gradientBrush = Brush.verticalGradient(
        colors = listOf(Color(0xFF1A1A2E), Color(0xFF16213E), Color(0xFF0F3460))
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(gradientBrush)
    ) {
        if (state.isVideo && remoteVideoView != null) {
            AndroidView(
                factory = { remoteVideoView },
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (state.isVideo && state.isConnected)
                        Color(0x44000000) else Color.Transparent
                )
        )

        if (state.isVideo && localVideoView != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .padding(top = 80.dp)
                    .size(110.dp, 150.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(2.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            ) {
                AndroidView(
                    factory = { localVideoView },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .align(Alignment.TopCenter)
                .padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!state.isVideo || !state.isConnected) {
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.1f))
                        .border(2.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                ) {
                    if (!state.partnerPhoto.isNullOrEmpty()) {
                        AsyncImage(
                            model = state.partnerPhoto,
                            contentDescription = state.partnerName,
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                state.partnerName.take(1).uppercase(),
                                color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            Text(
                state.partnerName,
                color = Color.White,
                fontSize = 26.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                state.statusText,
                color = Color(0xFFD0E4FF), fontSize = 14.sp
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 40.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            if (state.isVideo) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CallControlButton(
                        icon = if (state.speakerEnabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                        label = "Speaker",
                        enabled = state.speakerEnabled,
                        onClick = onToggleSpeaker,
                        size = 52
                    )
                    CallControlButton(
                        icon = Icons.Filled.Cameraswitch,
                        label = "Flip",
                        enabled = true,
                        onClick = onSwitchCamera,
                        size = 52
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!state.isVideo) {
                    CallControlButton(
                        icon = if (state.speakerEnabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeDown,
                        label = if (state.speakerEnabled) "Speaker" else "Earpiece",
                        enabled = state.speakerEnabled,
                        onClick = onToggleSpeaker,
                        size = 60
                    )
                }

                CallControlButton(
                    icon = if (state.micEnabled) Icons.Filled.Mic else Icons.Filled.MicOff,
                    label = if (state.micEnabled) "Mute" else "Unmute",
                    enabled = state.micEnabled,
                    onClick = onToggleMic,
                    size = 60
                )

                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(CallReject)
                        .clickable { onEndCall() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.CallEnd, "End Call",
                        tint = Color.White, modifier = Modifier.size(34.dp))
                }

                if (state.isVideo) {
                    CallControlButton(
                        icon = if (state.cameraEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                        label = if (state.cameraEnabled) "Camera" else "No Cam",
                        enabled = state.cameraEnabled,
                        onClick = onToggleCamera,
                        size = 60
                    )
                }
            }
        }
    }
}

@Composable
private fun CallControlButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    size: Int = 52
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(
                    if (enabled) Color.White.copy(alpha = 0.2f)
                    else Color.White.copy(alpha = 0.08f)
                )
                .border(1.dp, Color.White.copy(alpha = if (enabled) 0.3f else 0.1f), CircleShape)
                .clickable(onClick = onClick)
                .alpha(if (enabled) 1f else 0.5f),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = Color.White,
                modifier = Modifier.size((size * 0.42f).dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
    }
}
