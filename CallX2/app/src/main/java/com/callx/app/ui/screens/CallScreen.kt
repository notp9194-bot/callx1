package com.callx.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.callx.app.ui.theme.*

@Composable
fun CallScreen(
    partnerName: String,
    partnerPhoto: String?,
    callDuration: String,
    isMuted: Boolean,
    isSpeakerOn: Boolean,
    isVideo: Boolean,
    onEndCall: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleVideo: () -> Unit
) {
    val bgGradient = Brush.verticalGradient(
        colors = listOf(Color(0xFF1A1040), Color(0xFF2D1B69), BrandPrimaryDark)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgGradient),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Spacer(Modifier.height(80.dp))

            // Avatar
            if (!partnerPhoto.isNullOrEmpty()) {
                AsyncImage(
                    model = partnerPhoto,
                    contentDescription = partnerName,
                    modifier = Modifier
                        .size(130.dp)
                        .clip(CircleShape)
                        .border(3.dp, Color.White.copy(0.4f), CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .clip(CircleShape)
                        .background(BrandPrimary.copy(0.4f))
                        .border(3.dp, Color.White.copy(0.4f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        (partnerName.firstOrNull() ?: 'U').toString().uppercase(),
                        color = Color.White,
                        fontSize = 52.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = partnerName,
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = callDuration,
                color = Color.White.copy(0.75f),
                fontSize = 18.sp
            )

            Spacer(Modifier.weight(1f))

            // Control buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CallControlButton(
                    icon = if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    label = if (isMuted) "Unmute" else "Mute",
                    active = isMuted,
                    onClick = onToggleMute
                )
                CallControlButton(
                    icon = if (isSpeakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    label = "Speaker",
                    active = isSpeakerOn,
                    onClick = onToggleSpeaker
                )
                if (isVideo) {
                    CallControlButton(
                        icon = Icons.Filled.Videocam,
                        label = "Camera",
                        active = true,
                        onClick = onToggleVideo
                    )
                }
                CallControlButton(
                    icon = Icons.Filled.ChatBubble,
                    label = "Chat",
                    active = false,
                    onClick = {}
                )
            }

            Spacer(Modifier.height(40.dp))

            // End call button
            IconButton(
                onClick = onEndCall,
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(ActionCallReject)
            ) {
                Icon(
                    Icons.Filled.CallEnd,
                    contentDescription = "End Call",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(Modifier.height(64.dp))
        }
    }
}

@Composable
private fun CallControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(
                    if (active) Color.White.copy(0.3f) else Color.White.copy(0.12f)
                )
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White.copy(0.8f), fontSize = 11.sp)
    }
}
