package com.callx.app.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.callx.app.ui.theme.*

data class AccountMenuUiState(
    val name: String = "User",
    val about: String = "Hey there! I am using CallX",
    val callxId: String = "",
    val photoUrl: String? = null
)

sealed class AccountMenuAction {
    object EditProfile : AccountMenuAction()
    object CopyCallxId : AccountMenuAction()
    object OpenPrivacy : AccountMenuAction()
    object OpenNotifications : AccountMenuAction()
    object OpenChats : AccountMenuAction()
    object OpenStorage : AccountMenuAction()
    object OpenHelp : AccountMenuAction()
    object OpenAbout : AccountMenuAction()
    object Logout : AccountMenuAction()
    object Back : AccountMenuAction()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountMenuScreen(
    state: AccountMenuUiState,
    onAction: (AccountMenuAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val gradientBrush = Brush.horizontalGradient(
        colors = listOf(BrandGradientStart, BrandGradientEnd)
    )

    var showLogoutDialog by remember { mutableStateOf(false) }
    var showAboutDialog   by remember { mutableStateOf(false) }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            icon = { Icon(Icons.Filled.Logout, null, tint = ActionDanger) },
            title = { Text("Logout", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to logout from CallX?") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    onAction(AccountMenuAction.Logout)
                }) {
                    Text("Logout", color = ActionDanger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            icon = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(gradientBrush),
                    contentAlignment = Alignment.Center
                ) {
                    Text("C", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                }
            },
            title = { Text("CallX", fontWeight = FontWeight.ExtraBold) },
            text = {
                Column {
                    Text("Version 3.1.0", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("Production-grade messaging and video calling app.")
                    Spacer(Modifier.height(4.dp))
                    Text("Built with Firebase + WebRTC", color = TextSecondary)
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("OK", color = BrandPrimary)
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { onAction(AccountMenuAction.Back) }) {
                        Icon(Icons.Filled.ArrowBackIos, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceCard,
                    titleContentColor = TextPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(SurfaceBg)
                .verticalScroll(rememberScrollState())
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(0.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(72.dp).clip(CircleShape)
                            .background(BrandPrimary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!state.photoUrl.isNullOrEmpty()) {
                            AsyncImage(
                                model = state.photoUrl,
                                contentDescription = state.name,
                                modifier = Modifier.fillMaxSize().clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text(state.name.take(1).uppercase(),
                                color = BrandPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(state.name, style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(state.about, style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary, maxLines = 2)
                        if (state.callxId.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(BrandPrimary.copy(alpha = 0.1f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .clickable { onAction(AccountMenuAction.CopyCallxId) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Tag, null, tint = BrandPrimary,
                                    modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("ID: ${state.callxId}",
                                    fontSize = 12.sp, color = BrandPrimary, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Outlined.ContentCopy, null, tint = BrandPrimary,
                                    modifier = Modifier.size(12.dp))
                            }
                        }
                    }
                    IconButton(onClick = { onAction(AccountMenuAction.EditProfile) }) {
                        Icon(Icons.Filled.Edit, "Edit", tint = BrandPrimary)
                    }
                }
            }

            SectionHeader("Account")
            MenuSection {
                MenuItem(Icons.Outlined.Person, "Edit Profile", "Name, photo, about", BrandPrimary) {
                    onAction(AccountMenuAction.EditProfile)
                }
                MenuDivider()
                MenuItem(Icons.Outlined.Tag, "My CallX ID",
                    state.callxId.ifEmpty { "Tap to copy" }, BrandAccent) {
                    onAction(AccountMenuAction.CopyCallxId)
                }
            }

            SectionHeader("Privacy & Notifications")
            MenuSection {
                MenuItem(Icons.Outlined.Security, "Privacy & Security",
                    "App lock, fingerprint, PIN", Color(0xFF8B5CF6)) {
                    onAction(AccountMenuAction.OpenPrivacy)
                }
                MenuDivider()
                MenuItem(Icons.Outlined.Notifications, "Notifications",
                    "Message and call alerts", Color(0xFFF59E0B)) {
                    onAction(AccountMenuAction.OpenNotifications)
                }
            }

            SectionHeader("Data & Support")
            MenuSection {
                MenuItem(Icons.Outlined.Chat, "Chats",
                    "Chat history and media", BrandPrimary) {
                    onAction(AccountMenuAction.OpenChats)
                }
                MenuDivider()
                MenuItem(Icons.Outlined.Storage, "Storage & Data",
                    "Network usage, auto-download", Color(0xFF0EA5E9)) {
                    onAction(AccountMenuAction.OpenStorage)
                }
                MenuDivider()
                MenuItem(Icons.Outlined.HelpOutline, "Help Center",
                    "FAQ, contact support", Color(0xFF22D3A6)) {
                    onAction(AccountMenuAction.OpenHelp)
                }
                MenuDivider()
                MenuItem(Icons.Outlined.Info, "About CallX",
                    "App version and licenses", TextSecondary) {
                    showAboutDialog = true
                }
            }

            SectionHeader("")
            MenuSection {
                MenuItem(Icons.Outlined.Logout, "Logout", null, ActionDanger,
                    titleColor = ActionDanger) {
                    showLogoutDialog = true
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    if (title.isNotEmpty()) {
        Text(
            title,
            modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            color = TextMuted
        )
    }
}

@Composable
private fun MenuSection(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(0.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard)
    ) {
        Column(content = content)
    }
}

@Composable
private fun MenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    iconTint: Color,
    titleColor: Color = TextPrimary,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconTint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, title, tint = iconTint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, color = titleColor)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary)
            }
        }
        Icon(Icons.Filled.ChevronRight, null, tint = TextMuted,
            modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun MenuDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 70.dp, end = 16.dp),
        color = Divider, thickness = 0.5.dp
    )
}
