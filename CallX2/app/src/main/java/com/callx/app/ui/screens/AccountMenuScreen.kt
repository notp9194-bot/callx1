package com.callx.app.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.callx.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountMenuScreen(
    myName: String,
    myPhotoUrl: String?,
    myAbout: String,
    onBack: () -> Unit,
    onProfile: () -> Unit,
    onPrivacySecurity: () -> Unit,
    onStarredMessages: () -> Unit,
    onRequests: () -> Unit,
    onLogout: () -> Unit
) {
    val gradient = Brush.verticalGradient(listOf(BrandGradientStart, BrandGradientEnd))
    var showLogoutDialog by remember { mutableStateOf(false) }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Logout") },
            text = { Text("Kya aap logout karna chahte ho?") },
            confirmButton = {
                TextButton(onClick = { showLogoutDialog = false; onLogout() }) {
                    Text("Logout", color = ActionDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            Box(modifier = Modifier.fillMaxWidth().background(gradient).statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                    }
                    Text("Account", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                }
            }
        },
        containerColor = SurfaceBg
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
        ) {
            // Profile card (tap to edit)
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp).clickable { onProfile() },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                elevation = CardDefaults.cardElevation(4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!myPhotoUrl.isNullOrEmpty()) {
                        AsyncImage(
                            model = myPhotoUrl,
                            contentDescription = myName,
                            modifier = Modifier.size(64.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier.size(64.dp).clip(CircleShape).background(BrandPrimary),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                (myName.firstOrNull() ?: 'U').toString().uppercase(),
                                color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(myName, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary)
                        Text(myAbout.ifEmpty { "Hey, I'm on CallX!" }, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, maxLines = 1)
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = TextMuted)
                }
            }

            // Menu items
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard)
            ) {
                Column {
                    MenuRow(Icons.Outlined.Star, "Starred Messages", onClick = onStarredMessages)
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = Divider)
                    MenuRow(Icons.Outlined.Shield, "Privacy & Security", onClick = onPrivacySecurity)
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = Divider)
                    MenuRow(Icons.Outlined.PersonAdd, "Friend Requests", onClick = onRequests)
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = Divider)
                    MenuRow(Icons.AutoMirrored.Outlined.Logout, "Logout", tint = ActionDanger, onClick = { showLogoutDialog = true })
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    tint: Color = TextPrimary,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (tint == TextPrimary) BrandPrimary else tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = tint, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(18.dp))
    }
}
