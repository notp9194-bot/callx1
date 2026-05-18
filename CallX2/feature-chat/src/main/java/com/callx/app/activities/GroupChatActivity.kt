package com.callx.app.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.paging.*
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.callx.app.adapters.MessagePagingAdapter
import com.callx.app.chat.R
import com.callx.app.chat.databinding.ActivityChatBinding
import com.callx.app.chat.gesture.SwipeReplyHandler
import com.callx.app.chat.performance.SwipeOptimizer
import com.callx.app.chat.reply.ReplyController
import com.callx.app.chat.reply.ReplyDataMapper
import com.callx.app.db.AppDatabase
import com.callx.app.db.entity.MessageEntity
import com.callx.app.models.Message
import com.callx.app.utils.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.util.concurrent.Executors

/**
 * GroupChatActivity — Production-grade group chat screen (Kotlin).
 *
 * Features:
 *   - Reply to message (swipe gesture + ReplyController)
 *   - Pinned message banner
 *   - Group Admin Controls
 *   - Typing indicator, online member count
 *   - Voice/media messages
 *   - Paging 3 via Room PagingSource
 */
class GroupChatActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GroupChatActivity"
        private const val AUDIO_PERM = Manifest.permission.RECORD_AUDIO
    }

    private lateinit var binding: ActivityChatBinding
    private lateinit var db: AppDatabase
    private val ioExecutor = Executors.newFixedThreadPool(3)

    private var groupId = ""
    private var groupName = ""
    private lateinit var currentUid: String
    private var currentName: String? = null
    private var isAdmin = false

    private lateinit var pagingAdapter: MessagePagingAdapter
    private lateinit var groupMessagesRef: DatabaseReference
    private var childEventListener: ChildEventListener? = null

    private var replyingTo: Message? = null
    private val replyController by lazy {
        ReplyController(object : ReplyController.Callback {
            override fun onReplyActivated(message: Message) { showReplyBar(message) }
            override fun onReplyCancelled() { clearReplyBar() }
            override fun onPendingUndo(message: Message, cancelAction: Runnable) {}
            override fun onNavigateToOriginal(messageId: String) {}
            override fun onUndoConfirmed() { clearReplyBar() }
        })
    }

    // Typing / presence
    private var typingRef: DatabaseReference? = null
    private var typingListener: ValueEventListener? = null
    private val typingNames = mutableMapOf<String, String>()
    private val memberNames = mutableMapOf<String, String>()
    private val memberRoles = mutableMapOf<String, String>()
    private val memberLastSeen = mutableMapOf<String, Long>()
    private var totalMembers = 0

    // Pinned message
    private var pinnedMsgId: String? = null

    // Media pickers
    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { uploadAndSend(it, "image", "image", null) }
    }
    private val videoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { uploadAndSend(it, "video", "video", null) }
    }
    private val audioPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { uploadAndSend(it, "audio", "raw", null) }
    }
    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { uploadAndSend(it, "file", "raw", FileUtils.fileName(this, it)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) { finish(); return }
        currentUid = auth.currentUser!!.uid
        groupId    = intent.getStringExtra("groupId") ?: run { finish(); return }
        groupName  = intent.getStringExtra("groupName") ?: "Group"

        db = AppDatabase.getInstance(this)

        setupToolbar()
        setupRecyclerView()
        setupInput()
        setupReplyBar()
        setupPinnedBanner()
        setupPagingObserver()
        startFirebaseListener()
        setupRealtimeHeader()
        checkAdminStatus()

        // Load current user name
        FirebaseUtils.getUserRef(currentUid).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { currentName = s.child("name").getValue(String::class.java) }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = groupName
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnClickListener {
            startActivity(Intent(this, GroupInfoActivity::class.java).apply {
                putExtra("groupId", groupId); putExtra("groupName", groupName)
            })
        }
    }

    private fun setupRecyclerView() {
        pagingAdapter = MessagePagingAdapter(currentUid, true)
        val lm = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvMessages.layoutManager = lm
        binding.rvMessages.adapter = pagingAdapter
        SwipeOptimizer.disableChangeAnimations(binding.rvMessages)

        val swipeHandler = SwipeReplyHandler(pagingAdapter.snapshot().items, currentUid) { message ->
            replyController.onSwipeReply(message)
        }
        ItemTouchHelper(swipeHandler).attachToRecyclerView(binding.rvMessages)
    }

    private fun setupPagingObserver() {
        val chatId = "group_$groupId"
        val pager = Pager(PagingConfig(pageSize = 50, enablePlaceholders = false)) {
            db.messageDao().getMessagesPagingSource(chatId)
        }
        pager.liveData.observe(this) { pagingData ->
            pagingAdapter.submitData(lifecycle, pagingData.map { entity ->
                Message(
                    id = entity.id, messageId = entity.id, senderId = entity.senderId,
                    senderName = entity.senderName, text = entity.text, type = entity.type,
                    mediaUrl = entity.mediaUrl, thumbnailUrl = entity.thumbnailUrl,
                    fileName = entity.fileName, timestamp = entity.timestamp,
                    status = entity.status, replyToId = entity.replyToId,
                    replyToText = entity.replyToText, replyToSenderName = entity.replyToSenderName,
                    edited = entity.edited, deleted = entity.deleted,
                    starred = entity.starred, pinned = entity.pinned, isGroup = true
                )
            })
        }
    }

    private fun setupInput() {
        val chatId = "group_$groupId"
        binding.etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val hasText = !s.isNullOrEmpty()
                binding.btnSend.visibility = if (hasText) View.VISIBLE else View.GONE
                binding.btnMic?.visibility = if (hasText) View.GONE else View.VISIBLE
                setMyTyping(hasText)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString().trim()
            if (text.isNotEmpty()) sendTextMessage(text, chatId)
        }
        binding.btnAttach?.setOnClickListener { showAttachSheet() }
    }

    private fun setupReplyBar() {
        binding.btnCancelReply?.setOnClickListener { clearReplyBar() }
    }

    private fun showReplyBar(m: Message) {
        replyingTo = m
        binding.llReplyBar?.visibility = View.VISIBLE
        binding.tvReplyBarName?.text = m.senderName ?: ""
        binding.tvReplyBarText?.text = m.text?.takeIf { it.isNotEmpty() } ?: "[${m.type}]"
        binding.etMessage.requestFocus()
    }

    private fun clearReplyBar() {
        replyingTo = null
        binding.llReplyBar?.visibility = View.GONE
    }

    private fun sendTextMessage(text: String, chatId: String) {
        val ref = groupMessagesRef.push()
        val msgId = ref.key ?: return
        val m = Message(
            id = msgId, senderId = currentUid, senderName = currentName,
            text = text, type = "text",
            timestamp = System.currentTimeMillis(), status = "sent", isGroup = true
        )
        replyingTo?.let { ReplyDataMapper.applyReplyFields(m, it, currentUid) }
        binding.etMessage.setText("")
        clearReplyBar()
        ref.setValue(m)
        ioExecutor.execute {
            db.messageDao().insertMessage(MessageEntity(
                id = msgId, chatId = chatId, senderId = currentUid, senderName = currentName,
                text = text, type = "text", timestamp = m.timestamp, status = "sent",
                replyToId = m.replyToId, replyToText = m.replyToText,
                replyToSenderName = m.replyToSenderName, syncedAt = System.currentTimeMillis()
            ))
        }
        updateGroupMeta(text)
    }

    private fun uploadAndSend(uri: Uri, msgType: String, resourceType: String, fileName: String?) {
        CloudinaryUploader.upload(this, uri, "group_media", resourceType,
            object : CloudinaryUploader.UploadCallback {
                override fun onSuccess(url: String) {
                    val ref = groupMessagesRef.push()
                    val msgId = ref.key ?: return
                    val m = Message(
                        id = msgId, senderId = currentUid, senderName = currentName,
                        type = msgType, mediaUrl = url, fileName = fileName,
                        timestamp = System.currentTimeMillis(), status = "sent", isGroup = true
                    )
                    ref.setValue(m)
                    updateGroupMeta("[$msgType]")
                }
                override fun onFailure(error: String) {
                    Toast.makeText(this@GroupChatActivity, "Upload failed", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun startFirebaseListener() {
        val chatId = "group_$groupId"
        groupMessagesRef = FirebaseUtils.getGroupMessagesRef(groupId)
        childEventListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val m = snapshot.getValue(Message::class.java) ?: return
                if (m.id == null) m.id = snapshot.key
                ioExecutor.execute {
                    db.messageDao().insertMessage(MessageEntity(
                        id = m.id ?: return@execute, chatId = chatId,
                        senderId = m.senderId, senderName = m.senderName,
                        text = m.text, type = m.type ?: "text",
                        mediaUrl = m.mediaUrl, thumbnailUrl = m.thumbnailUrl,
                        fileName = m.fileName, timestamp = m.timestamp, status = m.status,
                        replyToId = m.replyToId, replyToText = m.replyToText,
                        replyToSenderName = m.replyToSenderName,
                        edited = m.edited, deleted = m.deleted,
                        starred = m.starred, pinned = m.pinned,
                        syncedAt = System.currentTimeMillis()
                    ))
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) = onChildAdded(snapshot, previousChildName)
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        groupMessagesRef.addChildEventListener(childEventListener!!)
        watchPinnedMessage()
    }

    private fun setupPinnedBanner() {
        binding.llPinnedBanner?.let { banner ->
            binding.btnUnpin?.setOnClickListener { pinnedMsgId?.let { unpinMessage(it) } }
        }
    }

    private fun watchPinnedMessage() {
        FirebaseUtils.getGroupsRef().child(groupId).child("pinnedMessageId")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    pinnedMsgId = s.getValue(String::class.java)
                    if (pinnedMsgId.isNullOrEmpty()) hidePinnedBanner()
                    else fetchAndShowPinnedBanner(pinnedMsgId!!)
                }
                override fun onCancelled(e: DatabaseError) {}
            })
    }

    private fun fetchAndShowPinnedBanner(msgId: String) {
        groupMessagesRef.child(msgId).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val m = s.getValue(Message::class.java) ?: run { hidePinnedBanner(); return }
                val txt = m.text?.takeIf { it.isNotEmpty() } ?: "[${m.type ?: "media"}]"
                binding.llPinnedBanner?.visibility = View.VISIBLE
                binding.tvPinnedPreview?.text = "📌  $txt"
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    private fun hidePinnedBanner() {
        binding.llPinnedBanner?.visibility = View.GONE
        pinnedMsgId = null
    }

    private fun unpinMessage(msgId: String) {
        groupMessagesRef.child(msgId).child("pinned").setValue(false)
        FirebaseUtils.getGroupsRef().child(groupId).child("pinnedMessageId").removeValue()
        hidePinnedBanner()
        Toast.makeText(this, "Unpinned", Toast.LENGTH_SHORT).show()
    }

    private fun setupRealtimeHeader() {
        typingRef = FirebaseUtils.getGroupTypingRef(groupId)
        typingListener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                typingNames.clear()
                for (c in snap.children) {
                    val uid = c.key ?: continue
                    if (uid == currentUid) continue
                    val name = c.getValue(String::class.java) ?: memberNames[uid] ?: "Someone"
                    typingNames[uid] = name
                }
                refreshSubtitle()
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        typingRef?.addValueEventListener(typingListener!!)

        FirebaseUtils.getGroupMembersRef(groupId).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                totalMembers = snap.childrenCount.toInt()
                for (c in snap.children) {
                    val uid = c.key ?: continue
                    memberNames[uid] = c.child("name").getValue(String::class.java) ?: "Member"
                    memberRoles[uid] = c.child("role").getValue(String::class.java) ?: "member"
                }
                refreshSubtitle()
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    private fun refreshSubtitle() {
        if (typingNames.isNotEmpty()) {
            val n = typingNames.size
            val sub = if (n == 1) "${typingNames.values.first()} is typing…" else "$n people are typing…"
            supportActionBar?.subtitle = sub
            return
        }
        val now = System.currentTimeMillis()
        val online = memberLastSeen.values.count { now - it < Constants.ONLINE_WINDOW_MS }
        val total = if (totalMembers > 0) totalMembers else memberLastSeen.size + 1
        var sub = "$total ${if (total == 1) "member" else "members"}"
        if (online > 0) sub = "$online online, $sub"
        supportActionBar?.subtitle = sub
    }

    private fun checkAdminStatus() {
        FirebaseUtils.getGroupMembersRef(groupId).child(currentUid).child("role")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    isAdmin = "admin" == s.getValue(String::class.java)
                    invalidateOptionsMenu()
                }
                override fun onCancelled(e: DatabaseError) {}
            })
    }

    private fun setMyTyping(typing: Boolean) {
        val ref = FirebaseUtils.getGroupTypingRef(groupId).child(currentUid)
        if (typing) {
            ref.setValue(currentName ?: "Someone")
            ref.onDisconnect().removeValue()
        } else {
            ref.removeValue()
        }
    }

    private fun updateGroupMeta(lastMsg: String) {
        val ts = System.currentTimeMillis()
        FirebaseUtils.getGroupsRef().child(groupId).apply {
            child("lastMessage").setValue(lastMsg)
            child("lastSenderName").setValue(currentName)
            child("lastMessageAt").setValue(ts)
        }
    }

    private fun showAttachSheet() {
        val sheet = BottomSheetDialog(this)
        val v = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_attach, null)
        v.findViewById<View>(R.id.opt_gallery).setOnClickListener { sheet.dismiss(); imagePicker.launch("image/*") }
        v.findViewById<View>(R.id.opt_video).setOnClickListener  { sheet.dismiss(); videoPicker.launch("video/*") }
        v.findViewById<View>(R.id.opt_audio).setOnClickListener  { sheet.dismiss(); audioPicker.launch("audio/*") }
        v.findViewById<View>(R.id.opt_file).setOnClickListener   { sheet.dismiss(); filePicker.launch("*/*") }
        sheet.setContentView(v); sheet.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        childEventListener?.let { groupMessagesRef.removeEventListener(it) }
        typingListener?.let { typingRef?.removeEventListener(it) }
        setMyTyping(false)
    }
}
