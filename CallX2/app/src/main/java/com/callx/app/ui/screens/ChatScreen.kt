package com.callx.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
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
import com.callx.app.models.Message
import com.callx.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    partnerName: String,
    partnerPhotoUrl: String?,
    partnerIsOnline: Boolean,
    partnerTyping: Boolean,
    messages: List<Message>,
    currentUid: String,
    isPinnedMessageVisible: Boolean,
    pinnedMsgText: String?,
    onBack: () -> Unit,
    onCall: () -> Unit,
    onVideoCall: () -> Unit,
    onSendText: (text: String) -> Unit,
    onAttach: () -> Unit,
    onRecord: () -> Unit,
    onMessageLongClick: (Message) -> Unit,
    onPinnedBannerClick: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val gradient = Brush.verticalGradient(listOf(BrandGradientStart, BrandGradientEnd))

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(gradient)
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                    }

                    // Avatar
                    Box(modifier = Modifier.size(40.dp)) {
                        if (!partnerPhotoUrl.isNullOrEmpty()) {
                            AsyncImage(
                                model = partnerPhotoUrl,
                                contentDescription = partnerName,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(0.3f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    (partnerName.firstOrNull() ?: 'U').toString().uppercase(),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        if (partnerIsOnline) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .align(Alignment.BottomEnd)
                                    .clip(CircleShape)
                                    .background(SurfaceCard)
                                    .padding(2.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                        .background(StatusOnline)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = partnerName,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            maxLines = 1
                        )
                        Text(
                            text = when {
                                partnerTyping  -> "typing..."
                                partnerIsOnline -> "online"
                                else           -> ""
                            },
                            color = if (partnerTyping) StatusTyping else Color.White.copy(0.75f),
                            fontSize = 12.sp
                        )
                    }

                    IconButton(onClick = onCall) {
                        Icon(Icons.Filled.Phone, null, tint = Color.White)
                    }
                    IconButton(onClick = onVideoCall) {
                        Icon(Icons.Filled.Videocam, null, tint = Color.White)
                    }
                }
            }
        },
        containerColor = SurfaceChatBg
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            // Pinned message banner
            AnimatedVisibility(visible = isPinnedMessageVisible && !pinnedMsgText.isNullOrEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPinnedBannerClick() },
                    color = BrandPrimary.copy(alpha = 0.1f),
                    tonalElevation = 0.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(32.dp)
                                .background(BrandPrimary, RoundedCornerShape(2.dp))
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Pinned Message", fontSize = 11.sp, color = BrandPrimary, fontWeight = FontWeight.SemiBold)
                            Text(pinnedMsgText ?: "", fontSize = 13.sp, color = TextSecondary, maxLines = 1)
                        }
                        Icon(Icons.Outlined.PushPin, null, tint = BrandPrimary, modifier = Modifier.size(16.dp))
                    }
                }
            }

            // Messages list
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(messages, key = { it.id ?: it.hashCode().toString() }) { msg ->
                    MessageBubble(
                        message = msg,
                        isMine = msg.senderId == currentUid,
                        onLongClick = { onMessageLongClick(msg) }
                    )
                }
            }

            // Input bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = SurfaceCard,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    IconButton(onClick = onAttach) {
                        Icon(Icons.Outlined.AttachFile, null, tint = TextSecondary)
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("Message...", color = TextMuted) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp, max = 120.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = SurfaceInput,
                            unfocusedContainerColor = SurfaceInput,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        ),
                        maxLines = 5
                    )

                    Spacer(Modifier.width(8.dp))

                    if (inputText.isBlank()) {
                        IconButton(
                            onClick = onRecord,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(BrandPrimary)
                        ) {
                            Icon(Icons.Filled.Mic, null, tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                    } else {
                        IconButton(
                            onClick = {
                                val text = inputText.trim()
                                if (text.isNotEmpty()) {
                                    onSendText(text)
                                    inputText = ""
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(BrandPrimary)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, null, tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    isMine: Boolean,
    onLongClick: () -> Unit
) {
    val timeStr = message.timestamp?.let {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it))
    } ?: ""

    val bubbleColor = if (isMine) BubbleSent else BubbleReceived
    val textColor   = if (isMine) BubbleSentText else BubbleReceivedText
    val shape = if (isMine)
        RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
    else
        RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)

    // Deleted message
    if (message.deleted == true) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
        ) {
            Surface(
                shape = shape,
                color = if (isMine) BrandPrimary.copy(0.3f) else SurfaceInput,
                modifier = Modifier.combinedClickable(onLongClick = onLongClick, onClick = {})
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Block, null, tint = TextMuted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("This message was deleted", color = TextMuted, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontSize = 13.sp)
                }
            }
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = shape,
            color = bubbleColor,
            shadowElevation = 1.dp,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .combinedClickable(onLongClick = onLongClick, onClick = {})
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                // Reply quote
                if (!message.replyToText.isNullOrEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isMine) Color.White.copy(0.2f) else BrandPrimary.copy(0.1f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(6.dp)) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .background(if (isMine) Color.White else BrandPrimary, RoundedCornerShape(2.dp))
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                message.replyToText ?: "",
                                color = textColor.copy(alpha = 0.8f),
                                fontSize = 12.sp,
                                maxLines = 2
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }

                // Text content
                if (!message.text.isNullOrEmpty()) {
                    Text(
                        text = message.text ?: "",
                        color = textColor,
                        fontSize = 15.sp,
                        lineHeight = 21.sp
                    )
                }

                // Image content
                if (message.type == "image" && !message.mediaUrl.isNullOrEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    AsyncImage(
                        model = message.mediaUrl,
                        contentDescription = "Image",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                }

                // Audio content
                if (message.type == "audio") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Icon(Icons.Filled.PlayArrow, null, tint = textColor, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(6.dp))
                        LinearProgressIndicator(
                            progress = { 0f },
                            modifier = Modifier.weight(1f).height(3.dp),
                            color = textColor.copy(0.7f),
                            trackColor = textColor.copy(0.2f)
                        )
                    }
                }

                // Reactions
                if (!message.reactions.isNullOrEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isMine) Color.White.copy(0.2f) else BrandPrimary.copy(0.08f)
                    ) {
                        Text(
                            text = message.reactions!!.values.joinToString(" "),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                // Edited indicator + time + read status
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                ) {
                    if (message.edited == true) {
                        Text("edited ", color = textColor.copy(0.6f), fontSize = 10.sp)
                    }
                    Text(timeStr, color = textColor.copy(0.6f), fontSize = 10.sp)
                    if (isMine) {
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = when (message.status) {
                                "read"      -> "✓✓"
                                "delivered" -> "✓✓"
                                else        -> "✓"
                            },
                            color = when (message.status) {
                                "read" -> Color(0xFF60A5FA)
                                else   -> textColor.copy(0.6f)
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
