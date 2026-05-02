package com.callx.app.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.callx.app.models.User
import com.callx.app.ui.screens.SearchScreen
import com.callx.app.ui.theme.CallXTheme
import com.callx.app.utils.Constants
import com.callx.app.utils.FirebaseUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class SearchActivityKt : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            CallXTheme {
                var results    by remember { mutableStateOf<List<User>>(emptyList()) }
                var isSearching by remember { mutableStateOf(false) }

                SearchScreen(
                    results     = results,
                    isSearching = isSearching,
                    onBack      = { finish() },
                    onSearch    = { query ->
                        if (query.length < 3) { results = emptyList(); return@SearchScreen }
                        isSearching = true
                        val cleanQuery = query.replace(Regex("[^0-9a-zA-Z]"), "")
                        FirebaseDatabase.getInstance(Constants.DB_URL)
                            .getReference("users")
                            .orderByChild("callxId").equalTo(cleanQuery)
                            .addListenerForSingleValueEvent(object : ValueEventListener {
                                override fun onDataChange(snap: DataSnapshot) {
                                    val list = mutableListOf<User>()
                                    for (c in snap.children) {
                                        val u = c.getValue(User::class.java) ?: continue
                                        if (u.uid == null) u.uid = c.key
                                        val myUid = FirebaseAuth.getInstance().currentUser?.uid
                                        if (u.uid != myUid) list.add(u)
                                    }
                                    // Also search by name if no callxId match
                                    if (list.isEmpty()) {
                                        FirebaseDatabase.getInstance(Constants.DB_URL)
                                            .getReference("users")
                                            .orderByChild("name").startAt(query).endAt(query + "\uf8ff")
                                            .addListenerForSingleValueEvent(object : ValueEventListener {
                                                override fun onDataChange(snap2: DataSnapshot) {
                                                    for (c in snap2.children) {
                                                        val u = c.getValue(User::class.java) ?: continue
                                                        if (u.uid == null) u.uid = c.key
                                                        val myUid = FirebaseAuth.getInstance().currentUser?.uid
                                                        if (u.uid != myUid) list.add(u)
                                                    }
                                                    results = list; isSearching = false
                                                }
                                                override fun onCancelled(e: DatabaseError) { isSearching = false }
                                            })
                                    } else {
                                        results = list; isSearching = false
                                    }
                                }
                                override fun onCancelled(e: DatabaseError) { isSearching = false }
                            })
                    },
                    onUserClick = { user ->
                        startActivity(
                            Intent(this, ChatActivity::class.java)
                                .putExtra("partnerUid", user.uid)
                                .putExtra("partnerName", user.name)
                        )
                    },
                    onAddContact = { user ->
                        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return@SearchScreen
                        FirebaseUtils.getContactsRef(myUid).child(user.uid ?: "")
                            .setValue(user)
                    }
                )
            }
        }
    }
}
