package com.callx.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.callx.app.ui.theme.*

@Composable
fun IncomingCallScreen(
    callerName: String,
    callerPhoto: String?,
    isVideo: Boolean,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gradientBrush = Brush.verticalGradient(
        colors = listOf(BrandGradientStart, BrandGradientEnd, Color(0xFF2D1B69))
    )

    val pulseAnim = rememberInfiniteTransition(label = "pulse")
    val ringScale by pulseAnim.animateFloat(
        initialValue = 1f, targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ), label = "ring"
    )
    val ringAlpha by pulseAnim.animateFloat(
        initialValue = 0.6f, targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseIn),
            repeatMode = RepeatMode.Restart
        ), label = "alpha"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(gradientBrush),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Spacer(Modifier.height(80.dp))

            Text(
                if (isVideo) "Incoming Video Call" else "Incoming Voice Call",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 16.sp, fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp
            )

            Spacer(Modifier.height(32.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(160.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .scale(ringScale)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = ringAlpha))
                )
                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.15f))
                        .border(3.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                ) {
                    if (!callerPhoto.isNullOrEmpty()) {
                        AsyncImage(
                            model = callerPhoto,
                            contentDescription = callerName,
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            Modifier.fillMaxSize().background(BrandPrimary.copy(alpha = 0.4f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                callerName.take(1).uppercase(),
                                color = Color.White,
                                fontSize = 52.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                callerName,
                color = Color.White,
                fontSize = 32.sp, fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp
            )

            Spacer(Modifier.height(8.dp))
            Text(
                if (isVideo) "CallX Video" else "CallX Voice",
                color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp
            )

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp)
                    .padding(bottom = 72.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(CallReject)
                            .clickableSafe(onReject),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.CallEnd, "Reject",
                            tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Decline", color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(CallAccept)
                            .clickableSafe(onAccept),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isVideo) Icons.Filled.Videocam else Icons.Filled.Call,
                            "Accept", tint = Color.White, modifier = Modifier.size(34.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Accept", color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
                }
            }
        }
    }
}

private fun Modifier.clickableSafe(onClick: () -> Unit) =
    this.clickable(onClick = onClick)
