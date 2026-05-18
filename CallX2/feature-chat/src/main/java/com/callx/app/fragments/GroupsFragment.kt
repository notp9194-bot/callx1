package com.callx.app.fragments

import android.content.Intent
import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.callx.app.activities.NewGroupActivity
import com.callx.app.adapters.GroupAdapter
import com.callx.app.chat.R
import com.callx.app.db.AppDatabase
import com.callx.app.db.entity.GroupEntity
import com.callx.app.models.Group
import com.callx.app.utils.FirebaseUtils
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.database.*
import java.util.concurrent.Executors

/**
 * GroupsFragment — Offline-First
 */
class GroupsFragment : Fragment() {

    private val groups = mutableListOf<Group>()
    private lateinit var adapter: GroupAdapter
    private var emptyState: View? = null
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val v = inflater.inflate(R.layout.fragment_groups, container, false)
        val rv = v.findViewById<RecyclerView>(R.id.rv_groups)
        emptyState = v.findViewById(R.id.empty_groups)
        rv.layoutManager = LinearLayoutManager(context)
        adapter = GroupAdapter(groups)
        rv.adapter = adapter

        v.findViewById<FloatingActionButton>(R.id.fab_new_group)?.setOnClickListener {
            startActivity(Intent(context, NewGroupActivity::class.java))
        }

        loadFromRoom()
        load()
        return v
    }

    private fun loadFromRoom() {
        val ctx = context ?: return
        executor.execute {
            val cached = AppDatabase.getInstance(ctx).groupDao().getAllGroupsSync()
            if (cached.isNullOrEmpty()) return@execute
            val roomGroups = cached.mapNotNull { it.toGroup() }
            activity?.runOnUiThread {
                groups.clear()
                groups.addAll(roomGroups)
                adapter.notifyDataSetChanged()
                updateEmptyState()
            }
        }
    }

    private fun load() {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseUtils.db().getReference("users").child(uid).child("groups")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val ids = snapshot.children.mapNotNull { it.key }
                    if (ids.isEmpty()) { activity?.runOnUiThread { groups.clear(); adapter.notifyDataSetChanged(); updateEmptyState() }; return }
                    val loaded = mutableListOf<Group>()
                    var count = 0
                    for (gid in ids) {
                        FirebaseUtils.getGroupsRef().child(gid)
                            .addListenerForSingleValueEvent(object : ValueEventListener {
                                override fun onDataChange(s: DataSnapshot) {
                                    val g = s.getValue(Group::class.java)
                                    if (g != null) { if (g.id == null) g.id = s.key; loaded.add(g) }
                                    count++
                                    if (count == ids.size) {
                                        loaded.sortByDescending { it.lastMessageAt ?: 0 }
                                        saveGroupsToRoom(loaded)
                                        activity?.runOnUiThread {
                                            groups.clear(); groups.addAll(loaded)
                                            adapter.notifyDataSetChanged(); updateEmptyState()
                                        }
                                    }
                                }
                                override fun onCancelled(e: DatabaseError) { count++ }
                            })
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun saveGroupsToRoom(list: List<Group>) {
        val ctx = context ?: return
        executor.execute {
            val db = AppDatabase.getInstance(ctx)
            val entities = list.mapNotNull { g ->
                if (g.id.isNullOrEmpty()) null
else run {
                    val ge = GroupEntity()
                    ge.id = g.id!!
                    ge.name = g.name
                    ge.description = g.description
                    ge.iconUrl = g.iconUrl
                    ge.createdBy = g.createdBy
                    ge.lastMessage = g.lastMessage
                    ge.lastSenderName = g.lastSenderName
                    ge.lastMessageAt = g.lastMessageAt
                    ge
                }
            }
            db.groupDao().insertGroups(entities)
        }
    }

    private fun updateEmptyState() {
        emptyState?.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun GroupEntity.toGroup(): Group? {
        if (id.isEmpty()) return null
        return Group().apply {
            this.id = this@toGroup.id; name = this@toGroup.name
            description = this@toGroup.description; iconUrl = this@toGroup.iconUrl
            createdBy = this@toGroup.createdBy; lastMessage = this@toGroup.lastMessage
            lastSenderName = this@toGroup.lastSenderName; lastMessageAt = this@toGroup.lastMessageAt
        }
    }
}
