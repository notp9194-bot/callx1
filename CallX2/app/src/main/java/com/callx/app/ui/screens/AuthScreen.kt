package com.callx.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.callx.app.ui.theme.*

data class AuthUiState(
    val isLoginMode: Boolean = true,
    val name: String = "",
    val mobile: String = "",
    val email: String = "",
    val password: String = "",
    val errorMsg: String = "",
    val infoMsg: String = "",
    val isLoading: Boolean = false,
    val avatarUri: android.net.Uri? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    state: AuthUiState,
    onNameChange: (String) -> Unit,
    onMobileChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onToggleMode: () -> Unit,
    onSubmit: () -> Unit,
    onPickAvatar: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gradientBrush = Brush.verticalGradient(
        colors = listOf(BrandGradientStart, BrandGradientEnd, BrandPrimaryDark)
    )

    Box(
        modifier = modifier.fillMaxSize().background(SurfaceBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(gradientBrush),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("C", style = MaterialTheme.typography.displayMedium,
                            color = Color.White, fontWeight = FontWeight.ExtraBold)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("CallX", style = MaterialTheme.typography.displayMedium,
                        color = Color.White, fontWeight = FontWeight.ExtraBold)
                    Text("Connect. Call. Chat.", color = Color.White.copy(alpha = 0.8f),
                        fontSize = 15.sp)
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .offset(y = (-24).dp),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(8.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        if (state.isLoginMode) "Welcome Back" else "Create Account",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        if (state.isLoginMode) "Sign in to continue" else "Join CallX today",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(24.dp))

                    AnimatedVisibility(!state.isLoginMode) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(100.dp)
                                    .clip(CircleShape)
                                    .background(SurfaceInput)
                                    .clickable { onPickAvatar() },
                                contentAlignment = Alignment.Center
                            ) {
                                if (state.avatarUri != null) {
                                    AsyncImage(
                                        model = state.avatarUri,
                                        contentDescription = "Avatar",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Filled.CameraAlt, null,
                                            tint = BrandPrimary, modifier = Modifier.size(32.dp))
                                        Text("Add Photo", fontSize = 11.sp, color = TextSecondary)
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(BrandPrimary)
                                        .align(Alignment.BottomEnd),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Filled.Edit, null,
                                        tint = Color.White, modifier = Modifier.size(14.dp))
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("Profile photo (optional)", fontSize = 12.sp, color = TextMuted)
                            Spacer(Modifier.height(16.dp))

                            OutlinedTextField(
                                value = state.name,
                                onValueChange = onNameChange,
                                label = { Text("Full Name") },
                                leadingIcon = { Icon(Icons.Outlined.Person, null) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Next),
                                singleLine = true,
                                colors = callxTextFieldColors()
                            )
                            Spacer(Modifier.height(12.dp))

                            OutlinedTextField(
                                value = state.mobile,
                                onValueChange = onMobileChange,
                                label = { Text("Mobile Number (CallX ID)") },
                                leadingIcon = { Icon(Icons.Outlined.Phone, null) },
                                prefix = { Text("+") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone,
                                    imeAction = ImeAction.Next),
                                singleLine = true,
                                colors = callxTextFieldColors()
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                    }

                    OutlinedTextField(
                        value = state.email,
                        onValueChange = onEmailChange,
                        label = { Text("Email") },
                        leadingIcon = { Icon(Icons.Outlined.Email, null) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next),
                        singleLine = true,
                        colors = callxTextFieldColors()
                    )
                    Spacer(Modifier.height(12.dp))

                    var passwordVisible by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = onPasswordChange,
                        label = { Text("Password") },
                        leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Filled.VisibilityOff
                                    else Icons.Filled.Visibility, null
                                )
                            }
                        },
                        visualTransformation = if (passwordVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done),
                        singleLine = true,
                        colors = callxTextFieldColors()
                    )
                    Spacer(Modifier.height(8.dp))

                    if (state.errorMsg.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(ActionDanger.copy(alpha = 0.1f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Error, null, tint = ActionDanger,
                                modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(state.errorMsg, color = ActionDanger, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    if (state.infoMsg.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(BrandAccent.copy(alpha = 0.1f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Info, null, tint = BrandAccent,
                                modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(state.infoMsg, color = BrandAccent, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onSubmit,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        enabled = !state.isLoading,
                        colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary)
                    ) {
                        if (state.isLoading) {
                            CircularProgressIndicator(
                                color = Color.White, modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                if (state.isLoginMode) "Login" else "Create Account",
                                fontWeight = FontWeight.Bold, fontSize = 16.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onToggleMode,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.5.dp, BrandPrimary)
                    ) {
                        Text(
                            if (state.isLoginMode) "New account? Sign up" else "Back to Login",
                            color = BrandPrimary, fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun callxTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BrandPrimary,
    focusedLabelColor = BrandPrimary,
    focusedLeadingIconColor = BrandPrimary,
    cursorColor = BrandPrimary
)
