package com.callx.app.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.callx.app.models.User
import com.callx.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    results: List<User>,
    isSearching: Boolean,
    onBack: () -> Unit,
    onSearch: (query: String) -> Unit,
    onUserClick: (User) -> Unit,
    onAddContact: (User) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val gradient = Brush.verticalGradient(listOf(BrandGradientStart, BrandGradientEnd))

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
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it; onSearch(it) },
                        placeholder = { Text("Mobile number ya naam se search karo", color = Color.White.copy(0.6f)) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.White.copy(0.15f),
                            unfocusedContainerColor = Color.White.copy(0.1f),
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        leadingIcon = { Icon(Icons.Filled.Search, null, tint = Color.White.copy(0.7f)) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = ""; onSearch("") }) {
                                    Icon(Icons.Filled.Clear, null, tint = Color.White.copy(0.7f))
                                }
                            }
                        },
                        singleLine = true
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
        },
        containerColor = SurfaceBg
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                isSearching -> {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator(color = BrandPrimary)
                    }
                }
                query.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.Search, null, tint = TextMuted, modifier = Modifier.size(64.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("Mobile number se search karo", color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                results.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.PersonOff, null, tint = TextMuted, modifier = Modifier.size(64.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("Koi user nahi mila", color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
                            Text("\"$query\"", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                else -> {
                    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(results) { user ->
                            SearchResultItem(
                                user = user,
                                onClick = { onUserClick(user) },
                                onAdd = { onAddContact(user) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultItem(user: User, onClick: () -> Unit, onAdd: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(BrandPrimary.copy(0.15f), androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    (user.name?.firstOrNull() ?: 'U').toString().uppercase(),
                    color = BrandPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(user.name ?: "Unknown", fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text("+${user.mobile ?: user.callxId ?: ""}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            OutlinedButton(
                onClick = onAdd,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, BrandPrimary)
            ) {
                Text("Add", color = BrandPrimary, fontSize = 13.sp)
            }
        }
    }
}
