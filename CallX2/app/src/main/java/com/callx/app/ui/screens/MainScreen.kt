package com.callx.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
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
import com.callx.app.models.CallLog
import com.callx.app.models.User
import com.callx.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

sealed class NavTab(val label: String, val icon: ImageVector, val iconSelected: ImageVector) {
    object Chats  : NavTab("Chats",  Icons.Outlined.ChatBubbleOutline, Icons.Filled.ChatBubble)
    object Status : NavTab("Status", Icons.Outlined.Circle, Icons.Filled.Circle)
    object Groups : NavTab("Groups", Icons.Outlined.Group, Icons.Filled.Group)
    object Calls  : NavTab("Calls",  Icons.Outlined.Phone, Icons.Filled.Phone)
}

private val tabs = listOf(NavTab.Chats, NavTab.Status, NavTab.Groups, NavTab.Calls)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    myAvatarUrl: String?,
    contacts: List<User>,
    callLogs: List<CallLog>,
    onContactClick: (User) -> Unit,
    onCallLogClick: (CallLog) -> Unit,
    onAvatarClick: () -> Unit,
    onSearchClick: () -> Unit,
    onFabClick: (tab: Int) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val gradient = Brush.verticalGradient(
        colors = listOf(BrandGradientStart, BrandGradientEnd)
    )

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
                        .height(56.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "CallX",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onSearchClick) {
                        Icon(Icons.Filled.Search, contentDescription = "Search", tint = Color.White)
                    }
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f))
                            .clickable { onAvatarClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (!myAvatarUrl.isNullOrEmpty()) {
                            AsyncImage(
                                model = myAvatarUrl,
                                contentDescription = "My avatar",
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(Icons.Filled.Person, null, tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = SurfaceCard,
                tonalElevation = 8.dp
            ) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = {
                            Icon(
                                if (selectedTab == index) tab.iconSelected else tab.icon,
                                contentDescription = tab.label
                            )
                        },
                        label = {
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = BrandPrimary,
                            selectedTextColor = BrandPrimary,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = BrandPrimary.copy(alpha = 0.12f)
                        )
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onFabClick(selectedTab) },
                containerColor = BrandPrimary,
                contentColor = Color.White,
                shape = CircleShape
            ) {
                Icon(
                    when (selectedTab) {
                        0 -> Icons.Filled.Edit
                        1 -> Icons.Filled.CameraAlt
                        2 -> Icons.Filled.GroupAdd
                        else -> Icons.Filled.AddCall
                    },
                    contentDescription = "Action"
                )
            }
        },
        containerColor = SurfaceBg
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> ChatsTab(contacts, onContactClick)
                1 -> StatusTab(contacts)
                2 -> GroupsTab(contacts, onContactClick)
                3 -> CallsTab(callLogs, onCallLogClick)
            }
        }
    }
}

@Composable
private fun ChatsTab(contacts: List<User>, onContactClick: (User) -> Unit) {
    if (contacts.isEmpty()) {
        EmptyState(icon = Icons.Outlined.ChatBubbleOutline, text = "Koi contact nahi mila\nSearch karke add karo")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(contacts) { user ->
                ChatListItem(user = user, onClick = { onContactClick(user) })
            }
        }
    }
}

@Composable
private fun StatusTab(contacts: List<User>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            Text("Status", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
        }
        items(contacts.filter { !it.statusUrl.isNullOrEmpty() }) { user ->
            StatusListItem(user = user)
        }
        if (contacts.none { !it.statusUrl.isNullOrEmpty() }) {
            item {
                EmptyState(icon = Icons.Outlined.Circle, text = "Koi status update nahi\nStatus lagao")
            }
        }
    }
}

@Composable
private fun GroupsTab(groups: List<User>, onGroupClick: (User) -> Unit) {
    if (groups.isEmpty()) {
        EmptyState(icon = Icons.Outlined.Group, text = "Koi group nahi\nNaya group banao")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(groups) { g ->
                ChatListItem(user = g, onClick = { onGroupClick(g) })
            }
        }
    }
}

@Composable
private fun CallsTab(logs: List<CallLog>, onLogClick: (CallLog) -> Unit) {
    if (logs.isEmpty()) {
        EmptyState(icon = Icons.Outlined.Phone, text = "Koi call history nahi")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(logs) { log ->
                CallLogItem(log = log, onClick = { onLogClick(log) })
            }
        }
    }
}

@Composable
fun ChatListItem(user: User, onClick: () -> Unit) {
    val timeStr = user.lastMessageAt?.let {
        val cal = Calendar.getInstance().also { c -> c.timeInMillis = it }
        val now = Calendar.getInstance()
        if (cal.get(Calendar.DATE) == now.get(Calendar.DATE))
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it))
        else
            SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(it))
    } ?: ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        Box(modifier = Modifier.size(52.dp)) {
            if (!user.photoUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = user.photoUrl,
                    contentDescription = user.name,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(BrandPrimary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = (user.name?.firstOrNull() ?: 'U').toString().uppercase(),
                        color = BrandPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                }
            }
            // Online dot
            if (user.isOnline == true) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
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

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = user.name ?: "Unknown",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = user.lastMessage ?: "Tap to chat",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                val unread = user.getUnreadCount()
                if (unread > 0) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(BrandPrimary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (unread > 99) "99+" else unread.toString(),
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    HorizontalDivider(
        modifier = Modifier.padding(start = 80.dp),
        thickness = 0.5.dp,
        color = Divider
    )
}

@Composable
private fun StatusListItem(user: User) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .border(3.dp, BrandPrimary, CircleShape)
                .padding(3.dp)
        ) {
            AsyncImage(
                model = user.photoUrl,
                contentDescription = user.name,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(user.name ?: "", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Tap to view status", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}

@Composable
private fun CallLogItem(log: CallLog, onClick: () -> Unit) {
    val isIncoming = log.direction == "incoming"
    val isMissed   = log.direction == "missed"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar placeholder
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(if (isMissed) ActionDanger.copy(0.12f) else BrandPrimary.copy(0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (log.mediaType == "video") Icons.Filled.Videocam else Icons.Filled.Phone,
                contentDescription = null,
                tint = if (isMissed) ActionDanger else BrandPrimary,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = log.partnerName ?: "Unknown",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isMissed) ActionDanger else TextPrimary
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        isMissed   -> Icons.Filled.CallMissed
                        isIncoming -> Icons.Filled.CallReceived
                        else       -> Icons.Filled.CallMade
                    },
                    contentDescription = null,
                    tint = when {
                        isMissed   -> ActionDanger
                        isIncoming -> BrandAccent
                        else       -> BrandPrimary
                    },
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = when {
                        isMissed   -> "Missed"
                        isIncoming -> "Incoming"
                        else       -> "Outgoing"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        Text(
            text = log.timestamp?.let {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it))
            } ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted
        )
    }

    HorizontalDivider(
        modifier = Modifier.padding(start = 80.dp),
        thickness = 0.5.dp,
        color = Divider
    )
}

@Composable
private fun EmptyState(icon: ImageVector, text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = TextMuted
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
