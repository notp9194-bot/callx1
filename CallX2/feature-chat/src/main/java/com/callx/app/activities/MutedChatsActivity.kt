package com.callx.app.activities

import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.callx.app.chat.R
import com.callx.app.utils.FirebaseUtils
import com.google.firebase.database.*
import de.hdodenhof.circleimageview.CircleImageView
import java.util.*

/**
 * MutedChatsActivity — View and manage all muted 1:1 chats.
 */
class MutedChatsActivity : AppCompatActivity() {

    data class MutedContact(val uid: String, val name: String, val photoUrl: String?, val mutedAt: Long)

    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView
    private val items = mutableListOf<MutedContact>()
    private lateinit var myUid: String
    private lateinit var mutedRef: DatabaseReference
    private var listener: ValueEventListener? = null

    inner class MutedAdapter : RecyclerView.Adapter<MutedAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_muted_chat, parent, false)
            return VH(v)
        }
        override fun onBindViewHolder(h: VH, pos: Int) {
            val item = items[pos]
            h.tvName.text = item.name
            if (!item.photoUrl.isNullOrEmpty()) {
                Glide.with(h.itemView.context).load(item.photoUrl).circleCrop()
                    .placeholder(R.drawable.ic_person).into(h.ivAvatar)
            } else h.ivAvatar.setImageResource(R.drawable.ic_person)
            h.btnUnmute.setOnClickListener { unmute(item.uid) }
            h.itemView.setOnClickListener {
                startActivity(Intent(this@MutedChatsActivity, ChatActivity::class.java).apply {
                    putExtra("uid", item.uid); putExtra("name", item.name)
                })
            }
        }
        override fun getItemCount() = items.size
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName:   TextView        = v.findViewById(R.id.tv_name)
            val ivAvatar: CircleImageView = v.findViewById(R.id.iv_avatar)
            val btnUnmute: TextView       = v.findViewById(R.id.btn_unmute)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_muted_chats)

        myUid = try { FirebaseUtils.getCurrentUid() } catch (e: Exception) { finish(); return }
        mutedRef = FirebaseUtils.db().getReference("muted").child(myUid)

        rv = findViewById(R.id.rv_muted)
        tvEmpty = findViewById(R.id.tv_empty)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = MutedAdapter()

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply { setDisplayHomeAsUpEnabled(true); title = "Muted Chats" }

        loadMuted()
    }

    private fun loadMuted() {
        listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val uids = snapshot.children.mapNotNull { it.key }
                items.clear()
                if (uids.isEmpty()) { rv.adapter?.notifyDataSetChanged(); updateEmpty(); return }
                var count = 0
                for (uid in uids) {
                    FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(s: DataSnapshot) {
                            val name = s.child("name").getValue(String::class.java) ?: uid
                            val photo = s.child("photoUrl").getValue(String::class.java)
                            items.add(MutedContact(uid, name, photo, 0L))
                            count++
                            if (count == uids.size) { rv.adapter?.notifyDataSetChanged(); updateEmpty() }
                        }
                        override fun onCancelled(e: DatabaseError) { count++ }
                    })
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        mutedRef.addValueEventListener(listener!!)
    }

    private fun unmute(uid: String) {
        mutedRef.child(uid).removeValue()
    }

    private fun updateEmpty() {
        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        listener?.let { mutedRef.removeEventListener(it) }
    }
}
