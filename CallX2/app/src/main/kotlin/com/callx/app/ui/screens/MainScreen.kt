package com.callx.app.ui.screens

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.callx.app.activities.AccountMenuActivity
import com.callx.app.activities.NewGroupActivity
import com.callx.app.activities.NewStatusActivity
import com.callx.app.activities.SearchActivity
import com.callx.app.ui.theme.BrandGradientEnd
import com.callx.app.ui.theme.BrandGradientStart

sealed class BottomNavItem(
    val route: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val label: String
) {
    object Chats : BottomNavItem("chats", Icons.Rounded.ChatBubbleOutline, Icons.Rounded.ChatBubble, "Chats")
    object Status : BottomNavItem("status", Icons.Rounded.CircleNotifications, Icons.Rounded.Notifications, "Status")
    object Groups : BottomNavItem("groups", Icons.Rounded.Group, Icons.Rounded.Group, "Groups")
    object Calls : BottomNavItem("calls", Icons.Rounded.Call, Icons.Rounded.Call, "Calls")
}

val navItems = listOf(
    BottomNavItem.Chats,
    BottomNavItem.Status,
    BottomNavItem.Groups,
    BottomNavItem.Calls
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    avatarUrl: String?,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    val fabIcons = listOf(
        Icons.Rounded.Search,
        Icons.Rounded.AddAPhoto,
        Icons.Rounded.GroupAdd,
        Icons.Rounded.AddIcCall
    )

    Scaffold(
        bottomBar = {
            ModernBottomNavBar(
                selectedTab = selectedTab,
                onTabSelected = onTabSelected
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = true,
                enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                FloatingActionButton(
                    onClick = {
                        when (selectedTab) {
                            0 -> context.startActivity(Intent(context, SearchActivity::class.java))
                            1 -> context.startActivity(Intent(context, NewStatusActivity::class.java))
                            2 -> context.startActivity(Intent(context, NewGroupActivity::class.java))
                            3 -> context.startActivity(Intent(context, SearchActivity::class.java))
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(16.dp),
                    elevation = FloatingActionButtonDefaults.elevation(6.dp, 8.dp)
                ) {
                    AnimatedContent(
                        targetState = selectedTab,
                        transitionSpec = {
                            scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) +
                            fadeIn() togetherWith scaleOut() + fadeOut()
                        }, label = "fab_icon"
                    ) { tab ->
                        Icon(fabIcons[tab], contentDescription = null, modifier = Modifier.size(26.dp))
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Top App Bar with gradient
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(listOf(BrandGradientStart, BrandGradientEnd))
                    )
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AnimatedContent(
                        targetState = navItems[selectedTab].label,
                        transitionSpec = {
                            slideInVertically { it } + fadeIn() togetherWith
                            slideOutVertically { -it } + fadeOut()
                        }, label = "title"
                    ) { title ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { context.startActivity(Intent(context, SearchActivity::class.java)) }
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = "Search", tint = Color.White)
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.25f))
                    ) {
                        IconButton(
                            onClick = { context.startActivity(Intent(context, AccountMenuActivity::class.java)) },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                Icons.Rounded.Person,
                                contentDescription = "Profile",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            // Content
            Box(modifier = Modifier.weight(1f)) { content() }
        }
    }
}

@Composable
fun ModernBottomNavBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        navItems.forEachIndexed { index, item ->
            val selected = selectedTab == index

            NavigationBarItem(
                selected = selected,
                onClick = { onTabSelected(index) },
                icon = {
                    AnimatedContent(
                        targetState = selected,
                        transitionSpec = {
                            scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) +
                            fadeIn() togetherWith scaleOut() + fadeOut()
                        }, label = "nav_icon_$index"
                    ) { isSelected ->
                        Icon(
                            imageVector = if (isSelected) item.selectedIcon else item.icon,
                            contentDescription = item.label,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                label = {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}
