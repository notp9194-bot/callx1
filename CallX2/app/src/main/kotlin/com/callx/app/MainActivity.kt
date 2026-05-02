package com.callx.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.fragment.app.FragmentActivity
import com.callx.app.activities.AuthActivity
import com.callx.app.ui.screens.ChatsComposeScreen
import com.callx.app.ui.screens.CallsComposeScreen
import com.callx.app.ui.screens.StatusComposeScreen
import com.callx.app.ui.screens.GroupsComposeScreen
import com.callx.app.ui.screens.MainScreen
import com.callx.app.ui.theme.CallXTheme
import com.callx.app.utils.Constants
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (FirebaseAuth.getInstance().currentUser == null) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish(); return
        }

        requestPermissions()
        refreshFcmToken()

        setContent {
            CallXTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var selectedTab by remember { mutableIntStateOf(0) }

                    MainScreen(
                        selectedTab = selectedTab,
                        onTabSelected = { selectedTab = it },
                        avatarUrl = null
                    ) {
                        AnimatedContent(
                            targetState = selectedTab,
                            transitionSpec = {
                                val direction = if (targetState > initialState)
                                    AnimatedContentTransitionScope.SlideDirection.Start
                                else
                                    AnimatedContentTransitionScope.SlideDirection.End
                                slideIntoContainer(direction, tween(300)) +
                                fadeIn(tween(200)) togetherWith
                                slideOutOfContainer(direction, tween(300)) +
                                fadeOut(tween(150))
                            }, label = "tab_content"
                        ) { tab ->
                            when (tab) {
                                0 -> ChatsComposeScreen()
                                1 -> StatusComposeScreen()
                                2 -> GroupsComposeScreen()
                                3 -> CallsComposeScreen()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !android.provider.Settings.canDrawOverlays(this)) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")))
            } catch (ignored: Exception) {}
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm != null && !nm.canUseFullScreenIntent()) {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        android.net.Uri.parse("package:$packageName")))
                } catch (ignored: Exception) {}
            }
        }
    }

    private fun refreshFcmToken() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            token ?: return@addOnSuccessListener
            FirebaseDatabase.getInstance(Constants.DB_URL)
                .getReference("users").child(uid).child("fcmToken").setValue(token)
        }
    }
}
