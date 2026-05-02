package com.callx.app.activities

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.callx.app.ui.screens.*
import com.callx.app.ui.theme.CallXTheme
import com.callx.app.utils.FirebaseUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class MainActivity : ComponentActivity() {

    private var chatsListener: ValueEventListener? = null
    private var callsListener: ValueEventListener? = null
    private var groupsListener: ValueEventListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (FirebaseAuth.getInstance().currentUser == null) {
            startActivity(Intent(this, AuthActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            return
        }

        setContent {
            CallXTheme {
                var uiState by remember { mutableStateOf(MainUiState()) }

                LaunchedEffect(Unit) {
                    loadMyProfile { photo, name ->
                        uiState = uiState.copy(myPhotoUrl = photo, myName = name)
                    }
                    listenToChats   { uiState = uiState.copy(chats = it) }
                    listenToCallLogs { uiState = uiState.copy(callLogs = it) }
                    listenToGroups  { uiState = uiState.copy(groups = it) }
                }

                MainScreen(
                    state = uiState,
                    onTabSelected = { uiState = uiState.copy(selectedTab = it) },
                    onAction = { action ->
                        when (action) {
                            is MainAction.OpenChat -> {
                                startActivity(
                                    Intent(this@MainActivity, ChatActivity::class.java).apply {
                                        putExtra("partnerUid",  action.uid)
                                        putExtra("partnerName", action.name)
                                    }
                                )
                            }
                            is MainAction.OpenCall -> {
                                startActivity(
                                    Intent(this@MainActivity, CallActivity::class.java).apply {
                                        putExtra("partnerUid",  action.uid)
                                        putExtra("partnerName", action.name)
                                        putExtra("isCaller",    true)
                                        putExtra("video",       false)
                                    }
                                )
                            }
                            is MainAction.OpenGroup -> {
                                startActivity(
                                    Intent(this@MainActivity, GroupChatActivity::class.java).apply {
                                        putExtra("groupId",   action.gid)
                                        putExtra("groupName", action.name)
                                    }
                                )
                            }
                            MainAction.OpenSearch -> {
                                startActivity(Intent(this@MainActivity, SearchActivity::class.java))
                            }
                            MainAction.OpenNewGroup -> {
                                startActivity(Intent(this@MainActivity, NewGroupActivity::class.java))
                            }
                            MainAction.OpenNewStatus -> {
                                Toast.makeText(this@MainActivity,
                                    "Status — coming soon", Toast.LENGTH_SHORT).show()
                            }
                            MainAction.OpenAccountMenu -> {
                                startActivity(Intent(this@MainActivity, AccountMenuActivity::class.java))
                            }
                        }
                    }
                )
            }
        }
    }

    private fun loadMyProfile(onResult: (String?, String) -> Unit) {
        val uid = FirebaseUtils.getCurrentUid() ?: return
        FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val photo = s.child("photoUrl").getValue(String::class.java)
                val name  = s.child("name").getValue(String::class.java) ?: ""
                onResult(photo?.takeIf { it.isNotEmpty() }, name)
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    private fun listenToChats(onResult: (List<ChatItem>) -> Unit) {
        val uid = FirebaseUtils.getCurrentUid() ?: return
        chatsListener = FirebaseUtils.getContactsRef(uid)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snap: DataSnapshot) {
                    val list = mutableListOf<ChatItem>()
                    for (c in snap.children) {
                        val name    = c.child("name").getValue(String::class.java) ?: "User"
                        val photo   = c.child("photoUrl").getValue(String::class.java)
                        val lastMsg = c.child("lastMessage").getValue(String::class.java) ?: ""
                        val lastAt  = c.child("lastMessageAt").getValue(Long::class.java)
                        val unread  = c.child("unread").getValue(Long::class.java) ?: 0L
                        list.add(ChatItem(
                            uid = c.key ?: "", name = name, lastMessage = lastMsg,
                            photoUrl = photo, timeMs = lastAt, unread = unread
                        ))
                    }
                    list.sortByDescending { it.timeMs ?: 0L }
                    onResult(list)
                }
                override fun onCancelled(e: DatabaseError) {}
            })
    }

    private fun listenToCallLogs(onResult: (List<CallLogItem>) -> Unit) {
        val uid = FirebaseUtils.getCurrentUid() ?: return
        callsListener = FirebaseUtils.getCallsRef(uid)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snap: DataSnapshot) {
                    val list = mutableListOf<CallLogItem>()
                    for (c in snap.children) {
                        list.add(CallLogItem(
                            id          = c.key ?: "",
                            partnerName = c.child("partnerName").getValue(String::class.java) ?: "User",
                            partnerPhoto= c.child("partnerPhoto").getValue(String::class.java),
                            direction   = c.child("direction").getValue(String::class.java) ?: "outgoing",
                            mediaType   = c.child("mediaType").getValue(String::class.java) ?: "audio",
                            timeMs      = c.child("timestamp").getValue(Long::class.java) ?: 0L,
                            durationMs  = c.child("duration").getValue(Long::class.java) ?: 0L
                        ))
                    }
                    list.sortByDescending { it.timeMs }
                    onResult(list)
                }
                override fun onCancelled(e: DatabaseError) {}
            })
    }

    private fun listenToGroups(onResult: (List<GroupItem>) -> Unit) {
        val uid = FirebaseUtils.getCurrentUid() ?: return
        groupsListener = FirebaseDatabase.getInstance()
            .getReference("userGroups").child(uid)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snap: DataSnapshot) {
                    val list = mutableListOf<GroupItem>()
                    for (c in snap.children) {
                        list.add(GroupItem(
                            gid      = c.key ?: "",
                            name     = c.child("name").getValue(String::class.java) ?: "Group",
                            photoUrl = c.child("photoUrl").getValue(String::class.java),
                            lastMessage = c.child("lastMessage").getValue(String::class.java) ?: "",
                            timeMs   = c.child("lastMessageAt").getValue(Long::class.java)
                        ))
                    }
                    list.sortByDescending { it.timeMs ?: 0L }
                    onResult(list)
                }
                override fun onCancelled(e: DatabaseError) {}
            })
    }

    override fun onDestroy() {
        val uid = FirebaseUtils.getCurrentUid()
        if (uid != null) {
            chatsListener?.let   { FirebaseUtils.getContactsRef(uid).removeEventListener(it) }
            callsListener?.let   { FirebaseUtils.getCallsRef(uid).removeEventListener(it) }
        }
        super.onDestroy()
    }
}
