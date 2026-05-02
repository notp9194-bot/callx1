package com.callx.app.ui.screens

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.callx.app.adapters.ChatListAdapter
import com.callx.app.ui.theme.BrandGradientEnd
import com.callx.app.ui.theme.BrandGradientStart
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

// ─── Chats Compose Screen ─────────────────────────────────────────────────────
@Composable
fun ChatsComposeScreen() {
    var pendingRequests by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val uid = FirebaseAuth.getInstance().currentUser?.uid

    // Listen for pending requests
    LaunchedEffect(uid) {
        if (uid == null) return@LaunchedEffect
        FirebaseDatabase.getInstance()
            .getReference("friendRequests").child(uid)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snap: DataSnapshot) {
                    pendingRequests = snap.children
                        .count { it.child("status").getValue(String::class.java) == "pending" }
                }
                override fun onCancelled(e: DatabaseError) {}
            })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Request banner
        AnimatedVisibility(
            visible = pendingRequests > 0,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            RequestBanner(count = pendingRequests, onClick = {
                context.startActivity(Intent(context,
                    com.callx.app.activities.RequestsActivity::class.java))
            })
        }

        // RecyclerView via AndroidView embedded in Compose
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                val rv = androidx.recyclerview.widget.RecyclerView(ctx)
                rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx)
                rv.adapter = ChatListAdapter(ctx, mutableListOf())
                rv.setPadding(0, 8, 0, 8)
                rv.clipToPadding = false
                // Add item animator
                rv.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator()
                rv
            },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun RequestBanner(count: Int, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Box(
            modifier = Modifier
                .background(Brush.linearGradient(listOf(BrandGradientStart, BrandGradientEnd)))
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.PersonAdd, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Contact Requests", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Tap to review", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(0.8f))
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Text("$count", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                        color = BrandGradientStart)
                }
            }
        }
    }
}

// ─── Status Screen ────────────────────────────────────────────────────────────
@Composable
fun StatusComposeScreen() {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            val rv = androidx.recyclerview.widget.RecyclerView(ctx)
            rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx)
            rv.setPadding(0, 8, 0, 88)
            rv.clipToPadding = false
            rv
        },
        modifier = Modifier.fillMaxSize()
    )
}

// ─── Groups Screen ────────────────────────────────────────────────────────────
@Composable
fun GroupsComposeScreen() {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            val rv = androidx.recyclerview.widget.RecyclerView(ctx)
            rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx)
            rv.setPadding(0, 8, 0, 8)
            rv.clipToPadding = false
            rv.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator()
            rv
        },
        modifier = Modifier.fillMaxSize()
    )
}

// ─── Calls Screen ─────────────────────────────────────────────────────────────
@Composable
fun CallsComposeScreen() {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            val rv = androidx.recyclerview.widget.RecyclerView(ctx)
            rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx)
            rv.setPadding(0, 8, 0, 8)
            rv.clipToPadding = false
            rv.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator()
            rv
        },
        modifier = Modifier.fillMaxSize()
    )
}
