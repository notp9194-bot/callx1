package com.callx.app.activities

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.callx.app.adapters.MessageAdapter
import com.callx.app.chat.R
import com.callx.app.db.AppDatabase
import com.callx.app.models.Message
import com.callx.app.utils.FirebaseUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.util.concurrent.Executors

/**
 * StarredMessagesActivity — Shows all starred messages from a chat.
 */
class StarredMessagesActivity : AppCompatActivity(), MessageAdapter.ActionListener {

    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: MessageAdapter
    private val starred = mutableListOf<Message>()
    private lateinit var chatId: String
    private var isGroup = false
    private lateinit var currentUid: String
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_starred_messages)

        chatId = intent.getStringExtra("chatId") ?: run { finish(); return }
        isGroup = intent.getBooleanExtra("isGroup", false)
        val user = FirebaseAuth.getInstance().currentUser ?: run { finish(); return }
        currentUid = user.uid

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        if (toolbar != null) {
            setSupportActionBar(toolbar)
            supportActionBar?.apply { setDisplayHomeAsUpEnabled(true); title = "Starred Messages" }
            toolbar.setNavigationOnClickListener { finish() }
        }

        rv = findViewById(R.id.rv_starred)
        tvEmpty = findViewById(R.id.tv_empty)

        rv.layoutManager = LinearLayoutManager(this)
        adapter = MessageAdapter(starred, currentUid, isGroup, this)
        rv.adapter = adapter

        loadFromRoom()
        loadFromFirebase()
    }

    private fun loadFromRoom() {
        executor.execute {
            val db = AppDatabase.getInstance(this)
            val entities = db.messageDao().getStarredMessagesSync()
            val messages = entities.filter { it.chatId == chatId }.map { e ->
                val m = Message()
                m.id          = e.id
                m.messageId   = e.id
                m.senderId    = e.senderId
                m.senderName  = e.senderName
                m.text        = e.text
                m.type        = e.type
                m.mediaUrl    = e.mediaUrl
                m.timestamp   = e.timestamp
                m.starred     = e.starred
                m
            }
            runOnUiThread {
                starred.clear(); starred.addAll(messages)
                adapter.notifyDataSetChanged()
                tvEmpty.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun loadFromFirebase() {
        val ref = if (isGroup) FirebaseUtils.getGroupMessagesRef(chatId)
                  else FirebaseUtils.getChatMessagesRef(chatId)
        ref.orderByChild("starred").equalTo(true)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val list = mutableListOf<Message>()
                    for (child in snapshot.children) {
                        val m = child.getValue(Message::class.java) ?: continue
                        if (m.id == null) m.id = child.key
                        if (m.starred == true) list.add(m)
                    }
                    list.sortByDescending { it.timestamp }
                    runOnUiThread {
                        starred.clear(); starred.addAll(list)
                        adapter.notifyDataSetChanged()
                        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    // MessageAdapter.ActionListener stubs
    override fun onReply(m: Message) {}
    override fun onDelete(m: Message) {}
    override fun onEdit(m: Message) {}
    override fun onReact(m: Message, emoji: String) {}
    override fun onForward(m: Message) {}
    override fun onStar(m: Message) {
        m.starred = false
        val ref = if (isGroup) FirebaseUtils.getGroupMessagesRef(chatId) else FirebaseUtils.getChatMessagesRef(chatId)
        m.id?.let { ref.child(it).child("starred").setValue(false) }
        starred.remove(m)
        adapter.notifyDataSetChanged()
        tvEmpty.visibility = if (starred.isEmpty()) View.VISIBLE else View.GONE
        executor.execute { AppDatabase.getInstance(this).messageDao().updateStarred(m.id ?: "", false) }
    }
    override fun onPin(m: Message) {}
    override fun onCopy(m: Message) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("message", m.text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }
    override fun onInfo(m: Message) {}
}
