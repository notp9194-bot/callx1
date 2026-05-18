package com.callx.app.fragments

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.callx.app.adapters.ChatListAdapter
import com.callx.app.chat.R
import com.callx.app.db.AppDatabase
import com.callx.app.db.entity.ChatEntity
import com.callx.app.models.User
import com.callx.app.utils.FirebaseUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.util.concurrent.Executors

/**
 * ChatsFragment — Offline-First
 *
 * Flow:
 *   1. onCreateView → loadFromRoom() immediately (zero-latency offline display)
 *   2. loadContacts() → Firebase listener → saves to Room → UI refreshes
 */
class ChatsFragment : Fragment() {

    private val contacts = mutableListOf<User>()
    private lateinit var adapter: ChatListAdapter
    private var emptyState: View? = null
    private var shimmer: com.facebook.shimmer.ShimmerFrameLayout? = null
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val v = inflater.inflate(R.layout.fragment_chats, container, false)
        val rv = v.findViewById<RecyclerView>(R.id.rv_chats)
        emptyState = v.findViewById(R.id.empty_chats)
        shimmer = v.findViewById(R.id.shimmer_chats)

        rv.layoutManager = LinearLayoutManager(context)
        adapter = ChatListAdapter(contacts, null)
        rv.adapter = adapter

        shimmer?.startShimmer()
        loadFromRoom()
        loadContacts()
        return v
    }

    private fun loadFromRoom() {
        val ctx = context ?: return
        executor.execute {
            val db = AppDatabase.getInstance(ctx)
            val cached = db.chatDao().getAllChatsSync()
            if (cached.isNullOrEmpty()) return@execute
            val users = cached.map { it.toUser() }
            activity?.runOnUiThread {
                shimmer?.stopShimmer()
                shimmer?.visibility = View.GONE
                contacts.clear()
                contacts.addAll(users)
                adapter.notifyDataSetChanged()
                updateEmptyState()
            }
        }
    }

    private fun loadContacts() {
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseUtils.getUserRef(currentUid).child("contacts")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val list = mutableListOf<User>()
                    for (child in snapshot.children) {
                        val u = child.getValue(User::class.java) ?: continue
                        if (u.uid == null) u.uid = child.key
                        list.add(u)
                    }
                    list.sortByDescending { it.lastMessageAt ?: 0 }
                    saveToRoom(list)
                    activity?.runOnUiThread {
                        shimmer?.stopShimmer()
                        shimmer?.visibility = View.GONE
                        contacts.clear()
                        contacts.addAll(list)
                        adapter.notifyDataSetChanged()
                        updateEmptyState()
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    activity?.runOnUiThread {
                        shimmer?.stopShimmer()
                        shimmer?.visibility = View.GONE
                    }
                }
            })
    }

    private fun saveToRoom(users: List<User>) {
        val ctx = context ?: return
        executor.execute {
            val db = AppDatabase.getInstance(ctx)
            val entities = users.map { it.toChatEntity() }
            db.chatDao().insertChats(entities)
        }
    }

    private fun updateEmptyState() {
        emptyState?.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun ChatEntity.toUser() = User(
        uid = partnerUid, name = partnerName, photoUrl = partnerPhoto,
        thumbUrl = partnerThumb, lastMessage = lastMessage, lastMessageAt = lastMessageAt,
        unread = unread
    )

    private fun User.toChatEntity() = com.callx.app.db.entity.ChatEntity(
        chatId = buildChatId(FirebaseAuth.getInstance().currentUser?.uid ?: "", uid ?: ""),
        type = "private", partnerUid = uid, partnerName = name, partnerPhoto = photoUrl,
        partnerThumb = thumbUrl, lastMessage = lastMessage, lastMessageAt = lastMessageAt,
        unread = unread
    )

    private fun buildChatId(a: String, b: String) = if (a < b) "${a}_${b}" else "${b}_${a}"
}
