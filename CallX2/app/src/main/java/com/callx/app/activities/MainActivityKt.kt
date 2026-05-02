package com.callx.app.activities

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import com.callx.app.models.CallLog
import com.callx.app.models.User
import com.callx.app.ui.screens.MainScreen
import com.callx.app.ui.theme.CallXTheme
import com.callx.app.utils.Constants
import com.callx.app.utils.FirebaseUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.messaging.FirebaseMessaging

class MainActivityKt : ComponentActivity() {

    private val auth = FirebaseAuth.getInstance()

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (auth.currentUser == null) {
            startActivity(Intent(this, AuthActivityKt::class.java)); finish(); return
        }

        requestPermissions()
        refreshFcmToken()

        setContent {
            CallXTheme {
                var myAvatarUrl by remember { mutableStateOf<String?>(null) }
                var contacts    by remember { mutableStateOf<List<User>>(emptyList()) }
                var callLogs    by remember { mutableStateOf<List<CallLog>>(emptyList()) }

                // Load my avatar
                LaunchedEffect(Unit) {
                    val uid = auth.currentUser?.uid ?: return@LaunchedEffect
                    FirebaseUtils.getUserRef(uid)
                        .addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(snap: DataSnapshot) {
                                myAvatarUrl = snap.child("photoUrl").getValue(String::class.java)
                            }
                            override fun onCancelled(e: DatabaseError) {}
                        })
                }

                // Load contacts
                DisposableEffect(Unit) {
                    val uid = auth.currentUser?.uid ?: return@DisposableEffect onDispose {}
                    val ref = FirebaseUtils.getContactsRef(uid)
                    val listener = object : ValueEventListener {
                        override fun onDataChange(snap: DataSnapshot) {
                            val list = mutableListOf<User>()
                            for (c in snap.children) {
                                val u = c.getValue(User::class.java) ?: continue
                                if (u.uid == null) u.uid = c.key
                                list.add(u)
                            }
                            contacts = list.sortedByDescending { it.lastMessageAt ?: 0 }
                        }
                        override fun onCancelled(e: DatabaseError) {}
                    }
                    ref.addValueEventListener(listener)
                    onDispose { ref.removeEventListener(listener) }
                }

                // Load call logs
                DisposableEffect(Unit) {
                    val uid = auth.currentUser?.uid ?: return@DisposableEffect onDispose {}
                    val ref = FirebaseUtils.getCallsRef(uid)
                    val listener = object : ValueEventListener {
                        override fun onDataChange(snap: DataSnapshot) {
                            val list = mutableListOf<CallLog>()
                            for (c in snap.children) {
                                val log = c.getValue(CallLog::class.java) ?: continue
                                if (log.id == null) log.id = c.key
                                list.add(log)
                            }
                            callLogs = list.sortedByDescending { it.timestamp ?: 0 }
                        }
                        override fun onCancelled(e: DatabaseError) {}
                    }
                    ref.addValueEventListener(listener)
                    onDispose { ref.removeEventListener(listener) }
                }

                MainScreen(
                    myAvatarUrl  = myAvatarUrl,
                    contacts     = contacts,
                    callLogs     = callLogs,
                    onContactClick = { user ->
                        startActivity(
                            Intent(this, ChatActivity::class.java)
                                .putExtra("partnerUid", user.uid)
                                .putExtra("partnerName", user.name)
                        )
                    },
                    onCallLogClick = { log ->
                        startActivity(
                            Intent(this, ChatActivity::class.java)
                                .putExtra("partnerUid", log.partnerUid)
                                .putExtra("partnerName", log.partnerName)
                        )
                    },
                    onAvatarClick = {
                        startActivity(Intent(this, AccountMenuActivityKt::class.java))
                    },
                    onSearchClick = {
                        startActivity(Intent(this, SearchActivityKt::class.java))
                    },
                    onFabClick = { tab ->
                        when (tab) {
                            0 -> startActivity(Intent(this, SearchActivityKt::class.java))
                            1 -> startActivity(Intent(this, NewStatusActivity::class.java))
                            2 -> startActivity(Intent(this, NewGroupActivity::class.java))
                            3 -> startActivity(Intent(this, SearchActivityKt::class.java))
                        }
                    }
                )
            }
        }
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !android.provider.Settings.canDrawOverlays(this)) {
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
        val uid = auth.currentUser?.uid ?: return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            if (token != null) {
                FirebaseDatabase.getInstance(Constants.DB_URL)
                    .getReference("users").child(uid).child("fcmToken").setValue(token)
            }
        }
    }
}
