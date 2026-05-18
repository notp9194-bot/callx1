package com.callx.app.activities

import android.app.ProgressDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.*
import com.bumptech.glide.Glide
import com.callx.app.adapters.GroupMemberAdapter
import com.callx.app.adapters.MediaThumbAdapter
import com.callx.app.chat.R
import com.callx.app.db.AppDatabase
import com.callx.app.db.entity.GroupEntity
import com.callx.app.models.Group
import com.callx.app.models.Message
import com.callx.app.utils.CloudinaryUploader
import com.callx.app.utils.FirebaseUtils
import com.google.android.material.appbar.AppBarLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.concurrent.Executors
import java.util.*

/**
 * GroupInfoActivity — Comprehensive group information screen (Kotlin).
 */
class GroupInfoActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_GROUP_ID   = "groupId"
        const val EXTRA_GROUP_NAME = "groupName"
    }

    private lateinit var ivGroupIcon: de.hdodenhof.circleimageview.CircleImageView
    private lateinit var tvGroupName: TextView
    private lateinit var tvGroupDesc: TextView
    private lateinit var tvMemberCount: TextView
    private lateinit var tvInviteLink: TextView

    private lateinit var groupId: String
    private lateinit var groupName: String
    private lateinit var currentUid: String
    private var isAdmin = false

    private val members = mutableListOf<GroupMemberAdapter.MemberItem>()
    private val mediaUrls = mutableListOf<String>()
    private lateinit var memberAdapter: GroupMemberAdapter
    private lateinit var mediaAdapter: MediaThumbAdapter

    private val executor = Executors.newSingleThreadExecutor()

    private val iconPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { uploadGroupIcon(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_group_info)

        groupId   = intent.getStringExtra(EXTRA_GROUP_ID) ?: run { finish(); return }
        groupName = intent.getStringExtra(EXTRA_GROUP_NAME) ?: "Group"
        currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: run { finish(); return }

        setupViews()
        loadGroupInfo()
        loadMembers()
        loadSharedMedia()
        checkAdminRole()
    }

    private fun setupViews() {
        ivGroupIcon   = findViewById(R.id.iv_group_icon)
        tvGroupName   = findViewById(R.id.tv_group_name)
        tvGroupDesc   = findViewById(R.id.tv_group_desc)
        tvMemberCount = findViewById(R.id.tv_member_count)
        tvInviteLink  = findViewById(R.id.tv_invite_link)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply { setDisplayHomeAsUpEnabled(true); title = "" }
        toolbar.setNavigationOnClickListener { finish() }

        // Members RecyclerView
        val rvMembers = findViewById<RecyclerView>(R.id.rv_members)
        memberAdapter = GroupMemberAdapter(members, currentUid) { uid, action ->
            handleMemberAction(uid, action)
        }
        rvMembers.layoutManager = LinearLayoutManager(this)
        rvMembers.adapter = memberAdapter
        rvMembers.isNestedScrollingEnabled = false

        // Media RecyclerView
        val rvMedia = findViewById<RecyclerView>(R.id.rv_shared_media)
        mediaAdapter = MediaThumbAdapter(mediaUrls) { url ->
            startActivity(Intent().apply {
                setClassName(packageName, "com.callx.app.activities.MediaViewerActivity")
                putExtra("url", url); putExtra("type", "image")
            })
        }
        rvMedia?.let {
            it.layoutManager = GridLayoutManager(this, 3)
            it.adapter = mediaAdapter
            it.isNestedScrollingEnabled = false
        }

        // Edit icon button
        findViewById<ImageButton>(R.id.btn_change_icon)?.setOnClickListener {
            if (isAdmin) iconPicker.launch("image/*")
        }

        // Edit name button
        findViewById<ImageButton>(R.id.btn_edit_name)?.setOnClickListener {
            if (isAdmin) showEditNameDialog()
        }

        // Invite link copy
        tvInviteLink.setOnClickListener {
            val link = tvInviteLink.text.toString()
            if (link.startsWith("https://")) {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("invite", link))
                Toast.makeText(this, "Link copied!", Toast.LENGTH_SHORT).show()
            }
        }

        // Leave group
        findViewById<View>(R.id.btn_leave_group)?.setOnClickListener { confirmLeaveGroup() }

        // Settings
        findViewById<View>(R.id.btn_group_settings)?.setOnClickListener {
            startActivity(Intent(this, GroupSettingsActivity::class.java).apply {
                putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, groupId)
                putExtra(GroupSettingsActivity.EXTRA_GROUP_NAME, groupName)
            })
        }
    }

    private fun loadGroupInfo() {
        FirebaseUtils.getGroupsRef().child(groupId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val g = snapshot.getValue(Group::class.java) ?: return
                    tvGroupName.text = g.name ?: "Group"
                    tvGroupDesc.text = g.description ?: ""
                    if (!g.iconUrl.isNullOrEmpty()) {
                        Glide.with(this@GroupInfoActivity).load(g.iconUrl).circleCrop()
                            .placeholder(R.drawable.ic_group).into(ivGroupIcon)
                    }
                    // Invite link
                    val inviteKey = snapshot.child("inviteKey").getValue(String::class.java)
                    if (inviteKey != null) {
                        tvInviteLink.text = "https://callx.app/join/$inviteKey"
                    }
                    // Cache to Room
                    executor.execute {
                        AppDatabase.getInstance(this@GroupInfoActivity).groupDao().insertGroup(
run {
                                val ge = GroupEntity()
                                ge.id = groupId
                                ge.name = g.name
                                ge.description = g.description
                                ge.iconUrl = g.iconUrl
                                ge.createdBy = g.createdBy
                                ge.lastMessage = g.lastMessage
                                ge.lastMessageAt = g.lastMessageAt
                                ge
                            }
                        )
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun loadMembers() {
        FirebaseUtils.getGroupMembersRef(groupId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val uids = snapshot.children.mapNotNull { it.key }
                    tvMemberCount.text = "${uids.size} members"
                    members.clear()
                    var loaded = 0
                    if (uids.isEmpty()) { memberAdapter.notifyDataSetChanged(); return }
                    for (uid in uids) {
                        val role = snapshot.child(uid).child("role").getValue(String::class.java) ?: "member"
                        FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(s: DataSnapshot) {
                                val name = s.child("name").getValue(String::class.java) ?: uid
                                val photo = s.child("photoUrl").getValue(String::class.java)
                                val lastSeen = s.child("lastSeen").getValue(Long::class.java)
                                val online = lastSeen != null && System.currentTimeMillis() - lastSeen < 120_000L
                                members.add(GroupMemberAdapter.MemberItem(uid, name, role, photo, online, lastSeen))
                                loaded++
                                if (loaded == uids.size) {
                                    members.sortWith(compareBy({ it.role != "creator" }, { it.role != "admin" }, { it.name }))
                                    memberAdapter.notifyDataSetChanged()
                                }
                            }
                            override fun onCancelled(e: DatabaseError) { loaded++ }
                        })
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun loadSharedMedia() {
        FirebaseUtils.getGroupMessagesRef(groupId)
            .orderByChild("type").equalTo("image")
            .limitToLast(9)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    mediaUrls.clear()
                    for (child in snapshot.children) {
                        val m = child.getValue(Message::class.java)
                        m?.mediaUrl?.let { mediaUrls.add(it) }
                    }
                    mediaAdapter.notifyDataSetChanged()
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun checkAdminRole() {
        FirebaseUtils.getGroupMembersRef(groupId).child(currentUid).child("role")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    isAdmin = "admin" == s.getValue(String::class.java) || "creator" == s.getValue(String::class.java)
                    memberAdapter.isAdmin = isAdmin
                    memberAdapter.notifyDataSetChanged()
                }
                override fun onCancelled(e: DatabaseError) {}
            })
    }

    private fun handleMemberAction(uid: String, action: String) {
        when (action) {
            "view"         -> Unit // TODO: open profile
            "message"      -> startActivity(Intent(this, ChatActivity::class.java).apply { putExtra("uid", uid) })
            "make_admin"   -> FirebaseUtils.getGroupMembersRef(groupId).child(uid).child("role").setValue("admin")
            "revoke_admin" -> FirebaseUtils.getGroupMembersRef(groupId).child(uid).child("role").setValue("member")
            "remove"       -> {
                FirebaseUtils.getGroupMembersRef(groupId).child(uid).removeValue()
                FirebaseUtils.db().getReference("users").child(uid).child("groups").child(groupId).removeValue()
            }
        }
    }

    private fun uploadGroupIcon(uri: Uri) {
        val pd = ProgressDialog(this).apply { setMessage("Uploading icon…"); show() }
        CloudinaryUploader.upload(this, uri, "group_icons", "image",
            object : CloudinaryUploader.UploadCallback {
                override fun onSuccess(url: String) {
                    pd.dismiss()
                    Glide.with(this@GroupInfoActivity).load(url).circleCrop().into(ivGroupIcon)
                    FirebaseUtils.getGroupsRef().child(groupId).child("iconUrl").setValue(url)
                }
                override fun onFailure(error: String) {
                    pd.dismiss(); Toast.makeText(this@GroupInfoActivity, "Upload failed", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun showEditNameDialog() {
        val et = EditText(this).apply { setText(tvGroupName.text) }
        AlertDialog.Builder(this)
            .setTitle("Edit Group Name")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty()) {
                    FirebaseUtils.getGroupsRef().child(groupId).child("name").setValue(n)
                    tvGroupName.text = n
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmLeaveGroup() {
        AlertDialog.Builder(this)
            .setTitle("Leave Group")
            .setMessage("Are you sure you want to leave ${tvGroupName.text}?")
            .setPositiveButton("Leave") { _, _ ->
                FirebaseUtils.getGroupMembersRef(groupId).child(currentUid).removeValue()
                FirebaseUtils.db().getReference("users").child(currentUid).child("groups").child(groupId).removeValue()
                Toast.makeText(this, "You left the group", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
