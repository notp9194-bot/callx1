package com.callx.app.ui.screens

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.callx.app.models.Message
import com.callx.app.models.User
import com.callx.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(
    groupName: String,
    groupPhotoUrl: String?,
    memberCount: Int,
    members: List<User>,
    messages: List<Message>,
    currentUid: String,
    isPinnedVisible: Boolean,
    pinnedText: String?,
    onBack: () -> Unit,
    onGroupInfo: () -> Unit,
    onSend: (String) -> Unit,
    onAttach: () -> Unit,
    onMessageLongClick: (Message) -> Unit,
    onPinnedClick: () -> Unit
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

                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(0.3f))
                            .clickable { onGroupInfo() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (!groupPhotoUrl.isNullOrEmpty()) {
                            AsyncImage(
                                model = groupPhotoUrl,
                                contentDescription = groupName,
                                modifier = Modifier.size(40.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(Icons.Filled.Group, null, tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                    }

                    Spacer(Modifier.width(10.dp))

                    Column(modifier = Modifier
                        .weight(1f)
                        .clickable { onGroupInfo() }) {
                        Text(groupName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
                        Text("$memberCount members", color = Color.White.copy(0.75f), fontSize = 12.sp)
                    }

                    IconButton(onClick = { /* group call */ }) {
                        Icon(Icons.Filled.Phone, null, tint = Color.White)
                    }
                    IconButton(onClick = { /* group video call */ }) {
                        Icon(Icons.Filled.Videocam, null, tint = Color.White)
                    }
                }
            }
        },
        containerColor = SurfaceChatBg
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            // Pinned banner
            if (isPinnedVisible && !pinnedText.isNullOrEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onPinnedClick() },
                    color = BrandPrimary.copy(0.1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.width(3.dp).height(32.dp).background(BrandPrimary, RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Pinned Message", fontSize = 11.sp, color = BrandPrimary, fontWeight = FontWeight.SemiBold)
                            Text(pinnedText, fontSize = 13.sp, color = TextSecondary, maxLines = 1)
                        }
                    }
                }
            }

            // Messages
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(messages, key = { it.id ?: it.hashCode().toString() }) { msg ->
                    GroupMessageBubble(
                        message = msg,
                        isMine = msg.senderId == currentUid,
                        onLongClick = { onMessageLongClick(msg) }
                    )
                }
            }

            // Input bar
            Surface(modifier = Modifier.fillMaxWidth(), color = SurfaceCard, shadowElevation = 8.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    IconButton(onClick = onAttach) {
                        Icon(Icons.Outlined.AttachFile, null, tint = TextSecondary)
                    }
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("Message...", color = TextMuted) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 120.dp),
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
                    IconButton(
                        onClick = {
                            val t = inputText.trim()
                            if (t.isNotEmpty()) { onSend(t); inputText = "" }
                        },
                        modifier = Modifier.size(48.dp).clip(CircleShape).background(BrandPrimary)
                    ) {
                        Icon(
                            if (inputText.isBlank()) Icons.Filled.Mic else Icons.AutoMirrored.Filled.Send,
                            null, tint = Color.White, modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupMessageBubble(message: Message, isMine: Boolean, onLongClick: () -> Unit) {
    val shape = if (isMine)
        RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
    else
        RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = shape,
            color = if (isMine) BubbleSent else BubbleReceived,
            shadowElevation = 1.dp,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .combinedClickable(onLongClick = onLongClick, onClick = {})
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (!isMine) {
                    Text(
                        message.senderName ?: "Unknown",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BrandPrimary
                    )
                    Spacer(Modifier.height(2.dp))
                }
                if (!message.text.isNullOrEmpty()) {
                    Text(
                        text = message.text ?: "",
                        color = if (isMine) BubbleSentText else BubbleReceivedText,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}
