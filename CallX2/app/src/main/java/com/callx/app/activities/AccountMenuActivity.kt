package com.callx.app.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.callx.app.ui.screens.AccountMenuAction
import com.callx.app.ui.screens.AccountMenuScreen
import com.callx.app.ui.screens.AccountMenuUiState
import com.callx.app.ui.theme.CallXTheme
import com.callx.app.utils.FirebaseUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener

class AccountMenuActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CallXTheme {
                var state by remember { mutableStateOf(AccountMenuUiState()) }

                LaunchedEffect(Unit) { loadProfile { state = it } }

                AccountMenuScreen(
                    state = state,
                    onAction = { action ->
                        when (action) {
                            AccountMenuAction.Back -> finish()
                            AccountMenuAction.EditProfile -> {
                                startActivity(Intent(this@AccountMenuActivity, ProfileActivity::class.java))
                            }
                            AccountMenuAction.CopyCallxId -> {
                                if (state.callxId.isNotEmpty()) {
                                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("CallX ID", state.callxId))
                                    Toast.makeText(this@AccountMenuActivity,
                                        "CallX ID copied!", Toast.LENGTH_SHORT).show()
                                }
                            }
                            AccountMenuAction.OpenPrivacy -> {
                                try {
                                    startActivity(Intent(this@AccountMenuActivity,
                                        Class.forName("com.callx.app.activities.PrivacySecurityActivity")))
                                } catch (_: Exception) {}
                            }
                            AccountMenuAction.OpenNotifications -> {
                                Toast.makeText(this@AccountMenuActivity,
                                    "Notifications — coming soon", Toast.LENGTH_SHORT).show()
                            }
                            AccountMenuAction.OpenChats -> {
                                Toast.makeText(this@AccountMenuActivity,
                                    "Chat settings — coming soon", Toast.LENGTH_SHORT).show()
                            }
                            AccountMenuAction.OpenStorage -> {
                                Toast.makeText(this@AccountMenuActivity,
                                    "Storage settings — coming soon", Toast.LENGTH_SHORT).show()
                            }
                            AccountMenuAction.OpenHelp -> {
                                Toast.makeText(this@AccountMenuActivity,
                                    "Help — coming soon", Toast.LENGTH_SHORT).show()
                            }
                            AccountMenuAction.OpenAbout -> { /* handled inside screen */ }
                            AccountMenuAction.Logout -> {
                                FirebaseAuth.getInstance().signOut()
                                startActivity(
                                    Intent(this@AccountMenuActivity, AuthActivity::class.java).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                                Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    }
                                )
                            }
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
    }

    private fun loadProfile(onResult: (AccountMenuUiState) -> Unit) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                onResult(AccountMenuUiState(
                    name     = snap.child("name").getValue(String::class.java) ?: "User",
                    about    = snap.child("about").getValue(String::class.java)
                        ?: "Hey there! I am using CallX",
                    callxId  = snap.child("callxId").getValue(String::class.java) ?: "",
                    photoUrl = snap.child("photoUrl").getValue(String::class.java)
                        ?.takeIf { it.isNotEmpty() }
                ))
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }
}
