package com.callx.app.activities

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.callx.app.adapters.MemberSelectAdapter
import com.callx.app.chat.databinding.ActivityNewGroupBinding
import com.callx.app.models.Group
import com.callx.app.models.User
import com.callx.app.utils.CloudinaryUploader
import com.callx.app.utils.FirebaseUtils
import com.google.firebase.database.*

/**
 * NewGroupActivity — Create a new group chat.
 */
class NewGroupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNewGroupBinding
    private val contacts = mutableListOf<User>()
    private val selected = mutableSetOf<String>()
    private lateinit var adapter: MemberSelectAdapter
    private lateinit var currentUid: String
    private var groupIconUrl: String? = null

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            Glide.with(this).load(it).circleCrop().into(binding.ivGroupIcon)
            binding.ivGroupIcon.setPadding(0, 0, 0, 0)
            binding.btnCreate.isEnabled = false
            binding.btnCreate.text = "Uploading photo..."
            CloudinaryUploader.upload(this, it, "group_avatars", "image",
                object : CloudinaryUploader.UploadCallback {
                    override fun onSuccess(url: String) {
                        groupIconUrl = url
                        binding.btnCreate.isEnabled = true
                        binding.btnCreate.text = "Create Group"
                    }
                    override fun onFailure(error: String) {
                        groupIconUrl = null
                        binding.btnCreate.isEnabled = true
                        binding.btnCreate.text = "Create Group"
                        Toast.makeText(this@NewGroupActivity, "Photo upload failed", Toast.LENGTH_SHORT).show()
                    }
                })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNewGroupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        currentUid = FirebaseUtils.getCurrentUid()

        binding.rvMembers.layoutManager = LinearLayoutManager(this)
        adapter = MemberSelectAdapter(contacts, selected)
        binding.rvMembers.adapter = adapter

        binding.ivGroupIcon.setOnClickListener { imagePicker.launch("image/*") }
        loadContacts()
        binding.btnCreate.setOnClickListener { create() }
    }

    private fun loadContacts() {
        FirebaseUtils.getUserRef(currentUid).child("contacts")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    contacts.clear()
                    for (child in snapshot.children) {
                        val u = child.getValue(User::class.java) ?: continue
                        if (u.uid == null) u.uid = child.key
                        contacts.add(u)
                    }
                    adapter.notifyDataSetChanged()
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun create() {
        val name = binding.etGroupName.text.toString().trim()
        if (name.isEmpty()) { Toast.makeText(this, "Enter group name", Toast.LENGTH_SHORT).show(); return }
        if (selected.isEmpty()) { Toast.makeText(this, "Select at least one member", Toast.LENGTH_SHORT).show(); return }

        val groupRef = FirebaseUtils.getGroupsRef().push()
        val groupId = groupRef.key ?: return

        val members = mutableMapOf<String, Any>()
        members[currentUid] = mapOf("role" to "admin")
        for (uid in selected) members[uid] = mapOf("role" to "member")

        val group = Group().apply {
            id = groupId; this.name = name; createdBy = currentUid
            iconUrl = groupIconUrl; this.members = members.keys.toMutableList()
        }

        groupRef.setValue(group).addOnSuccessListener {
            for (uid in members.keys) {
                FirebaseUtils.db().getReference("users").child(uid).child("groups").child(groupId).setValue(true)
            }
            Toast.makeText(this, "Group created!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
