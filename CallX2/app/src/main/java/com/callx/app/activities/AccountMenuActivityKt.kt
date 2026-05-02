package com.callx.app.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.callx.app.ui.screens.AccountMenuScreen
import com.callx.app.ui.theme.CallXTheme
import com.callx.app.utils.FirebaseUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener

class AccountMenuActivityKt : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            CallXTheme {
                var myName    by remember { mutableStateOf("") }
                var myPhoto   by remember { mutableStateOf<String?>(null) }
                var myAbout   by remember { mutableStateOf("") }

                LaunchedEffect(Unit) {
                    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@LaunchedEffect
                    FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(snap: DataSnapshot) {
                            myName  = snap.child("name").getValue(String::class.java) ?: ""
                            myPhoto = snap.child("photoUrl").getValue(String::class.java)
                            myAbout = snap.child("about").getValue(String::class.java) ?: ""
                        }
                        override fun onCancelled(e: DatabaseError) {}
                    })
                }

                AccountMenuScreen(
                    myName             = myName,
                    myPhotoUrl         = myPhoto,
                    myAbout            = myAbout,
                    onBack             = { finish() },
                    onProfile          = { startActivity(Intent(this, ProfileActivity::class.java)) },
                    onPrivacySecurity  = { startActivity(Intent(this, PrivacySecurityActivity::class.java)) },
                    onStarredMessages  = { startActivity(Intent(this, StarredMessagesActivity::class.java)) },
                    onRequests         = { startActivity(Intent(this, RequestsActivity::class.java)) },
                    onLogout           = {
                        FirebaseAuth.getInstance().signOut()
                        startActivity(
                            Intent(this, AuthActivityKt::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        )
                    }
                )
            }
        }
    }
}
