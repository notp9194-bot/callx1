package com.callx.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.callx.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

data class ChatItem(
    val uid: String,
    val name: String,
    val lastMessage: String,
    val photoUrl: String?,
    val timeMs: Long?,
    val unread: Long,
    val isSpecialRequest: Boolean = false
)

data class CallLogItem(
    val id: String,
    val partnerName: String,
    val partnerPhoto: String?,
    val direction: String,
    val mediaType: String,
    val timeMs: Long,
    val durationMs: Long
)

data class GroupItem(
    val gid: String,
    val name: String,
    val photoUrl: String?,
    val lastMessage: String,
    val timeMs: Long?
)

data class MainUiState(
    val myPhotoUrl: String? = null,
    val myName: String = "",
    val selectedTab: Int = 0,
    val chats: List<ChatItem> = emptyList(),
    val callLogs: List<CallLogItem> = emptyList(),
    val groups: List<GroupItem> = emptyList()
)

sealed class MainAction {
    object OpenSearch : MainAction()
    object OpenNewStatus : MainAction()
    object OpenNewGroup : MainAction()
    data class OpenChat(val uid: String, val name: String) : MainAction()
    data class OpenCall(val uid: String, val name: String) : MainAction()
    data class OpenGroup(val gid: String, val name: String) : MainAction()
    object OpenAccountMenu : MainAction()
}

private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val gradientBrush = Brush.horizontalGradient(
        colors = listOf(BrandGradientStart, BrandGradientEnd)
    )
    val tabs = listOf(
        Triple("Chats", Icons.Outlined.Chat, Icons.Filled.Chat),
        Triple("Status", Icons.Outlined.AutoStories, Icons.Filled.AutoStories),
        Triple("Groups", Icons.Outlined.Group, Icons.Filled.Group),
        Triple("Calls", Icons.Outlined.Call, Icons.Filled.Call)
    )

    Scaffold(
        modifier = modifier,
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(gradientBrush)
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("CallX",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = { onAction(MainAction.OpenSearch) }) {
                        Icon(Icons.Filled.Search, "Search",
                            tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable { onAction(MainAction.OpenAccountMenu) }
                            .border(2.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                    ) {
                        if (!state.myPhotoUrl.isNullOrEmpty()) {
                            AsyncImage(
                                model = state.myPhotoUrl,
                                contentDescription = "Profile",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Person, null,
                                    tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                ) {
                    ScrollableTabRow(
                        selectedTabIndex = state.selectedTab,
                        containerColor = Color.Transparent,
                        contentColor = Color.White,
                        edgePadding = 0.dp,
                        indicator = { tabPositions ->
                            if (state.selectedTab < tabPositions.size) {
                                TabRowDefaults.SecondaryIndicator(
                                    modifier = Modifier.tabIndicatorOffset(tabPositions[state.selectedTab]),
                                    color = Color.White
                                )
                            }
                        }
                    ) {
                        tabs.forEachIndexed { index, (label, outIcon, filledIcon) ->
                            Tab(
                                selected = state.selectedTab == index,
                                onClick = { onTabSelected(index) },
                                text = { Text(label, fontSize = 13.sp,
                                    fontWeight = if (state.selectedTab == index)
                                        FontWeight.Bold else FontWeight.Normal) },
                                icon = {
                                    Icon(
                                        if (state.selectedTab == index) filledIcon else outIcon,
                                        null, modifier = Modifier.size(20.dp)
                                    )
                                },
                                selectedContentColor = Color.White,
                                unselectedContentColor = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    when (state.selectedTab) {
                        0 -> onAction(MainAction.OpenSearch)
                        1 -> onAction(MainAction.OpenNewStatus)
                        2 -> onAction(MainAction.OpenNewGroup)
                        3 -> onAction(MainAction.OpenSearch)
                    }
                },
                containerColor = BrandPrimary,
                contentColor = Color.White,
                shape = CircleShape
            ) {
                Icon(
                    when (state.selectedTab) {
                        1 -> Icons.Filled.CameraAlt
                        2 -> Icons.Filled.GroupAdd
                        3 -> Icons.Filled.Phone
                        else -> Icons.Filled.Edit
                    }, "Action"
                )
            }
        },
        bottomBar = {}
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(SurfaceBg)
        ) {
            when (state.selectedTab) {
                0 -> ChatsTab(state.chats, onAction)
                1 -> StatusTab()
                2 -> GroupsTab(state.groups, onAction)
                3 -> CallsTab(state.callLogs, onAction)
            }
        }
    }
}

@Composable
private fun ChatsTab(chats: List<ChatItem>, onAction: (MainAction) -> Unit) {
    if (chats.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.ChatBubbleOutline,
            title = "No chats yet",
            subtitle = "Use the button below to find and add a contact"
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(chats, key = { it.uid }) { chat ->
                ChatListItem(chat) {
                    onAction(MainAction.OpenChat(chat.uid, chat.name))
                }
            }
        }
    }
}

@Composable
private fun ChatListItem(chat: ChatItem, onClick: () -> Unit) {
    val bgColor = if (chat.isSpecialRequest)
        Color(0xFFFFF8E1) else SurfaceCard

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarImage(chat.photoUrl, chat.name, size = 52)

            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        chat.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (chat.unread > 0) TextPrimary else TextPrimary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (chat.timeMs != null && chat.timeMs > 0) {
                        Text(
                            TIME_FMT.format(Date(chat.timeMs)),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (chat.unread > 0) BrandPrimary else TextMuted
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (chat.isSpecialRequest) "⭐ Special unblock request"
                        else chat.lastMessage.ifEmpty { "Tap to chat" },
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            chat.isSpecialRequest -> Color(0xFFFF8F00)
                            chat.unread > 0 -> TextPrimary
                            else -> TextSecondary
                        },
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                        fontWeight = if (chat.unread > 0) FontWeight.SemiBold else FontWeight.Normal
                    )
                    if (chat.unread > 0) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(BrandPrimary)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (chat.unread > 99) "99+" else chat.unread.toString(),
                                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupsTab(groups: List<GroupItem>, onAction: (MainAction) -> Unit) {
    if (groups.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.Group,
            title = "No groups yet",
            subtitle = "Create a group using the button below"
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(groups, key = { it.gid }) { group ->
                Card(
                    onClick = { onAction(MainAction.OpenGroup(group.gid, group.name)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AvatarImage(group.photoUrl, group.name, size = 52,
                            fallbackIcon = Icons.Filled.Group)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(group.name, style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold, color = TextPrimary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(group.lastMessage.ifEmpty { "Tap to open" },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (group.timeMs != null && group.timeMs > 0) {
                            Text(TIME_FMT.format(Date(group.timeMs)),
                                style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CallsTab(logs: List<CallLogItem>, onAction: (MainAction) -> Unit) {
    if (logs.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.Phone,
            title = "No call history",
            subtitle = "Your call history will appear here"
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(logs, key = { it.id }) { log ->
                CallLogListItem(log) { onAction(MainAction.OpenCall(log.id, log.partnerName)) }
            }
        }
    }
}

@Composable
private fun CallLogListItem(log: CallLogItem, onCallBack: () -> Unit) {
    val isIncoming = log.direction == "incoming"
    val isVideo    = log.mediaType == "video"
    val callColor  = if (isIncoming) CallAccept else BrandPrimary

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarImage(log.partnerPhoto, log.partnerName, size = 46)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(log.partnerName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold, color = TextPrimary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isIncoming) Icons.Filled.CallReceived else Icons.Filled.CallMade,
                        null, tint = callColor, modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        buildString {
                            append(if (isIncoming) "Incoming" else "Outgoing")
                            append(" • ")
                            append(if (isVideo) "Video" else "Audio")
                            if (log.durationMs > 0) {
                                val sec = log.durationMs / 1000
                                append(" • ${sec / 60}:${"%02d".format(sec % 60)}")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(TIME_FMT.format(Date(log.timeMs)),
                    style = MaterialTheme.typography.labelSmall, color = TextMuted)
                Spacer(Modifier.height(4.dp))
                IconButton(
                    onClick = onCallBack,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        if (isVideo) Icons.Filled.Videocam else Icons.Filled.Call,
                        "Call back", tint = BrandPrimary, modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusTab() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.AutoStories, null,
                tint = TextMuted, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(12.dp))
            Text("Status", style = MaterialTheme.typography.titleLarge,
                color = TextSecondary, fontWeight = FontWeight.Bold)
            Text("Status updates from your contacts", color = TextMuted,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(BrandPrimary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = BrandPrimary, modifier = Modifier.size(40.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
fun AvatarImage(
    photoUrl: String?,
    name: String,
    size: Int = 48,
    fallbackIcon: ImageVector = Icons.Filled.Person
) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(BrandPrimary.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) {
        if (!photoUrl.isNullOrEmpty()) {
            AsyncImage(
                model = photoUrl,
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            if (name.isNotEmpty()) {
                Text(
                    name.first().uppercaseChar().toString(),
                    color = BrandPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size * 0.35f).sp
                )
            } else {
                Icon(fallbackIcon, null, tint = BrandPrimary,
                    modifier = Modifier.size((size * 0.5f).dp))
            }
        }
    }
}

private fun Modifier.tabIndicatorOffset(currentTabPosition: androidx.compose.material3.TabPosition): Modifier =
    this.then(Modifier.fillMaxWidth().wrapContentSize(Alignment.BottomStart)
        .offset(x = currentTabPosition.left)
        .width(currentTabPosition.width))
