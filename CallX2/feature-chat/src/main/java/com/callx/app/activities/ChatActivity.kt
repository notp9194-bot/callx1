package com.callx.app.activities

import android.Manifest
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.paging.*
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.callx.app.adapters.MessagePagingAdapter
import com.callx.app.cache.CacheManager
import com.callx.app.chat.R
import com.callx.app.chat.analytics.ReplyAnalyticsTracker
import com.callx.app.chat.databinding.ActivityChatBinding
import com.callx.app.chat.gesture.SwipeReplyHandler
import com.callx.app.chat.performance.SwipeOptimizer
import com.callx.app.chat.reply.ReplyController
import com.callx.app.chat.reply.ReplyDataMapper
import com.callx.app.chat.ui.MessageHighlightAnimator
import com.callx.app.db.AppDatabase
import com.callx.app.db.entity.MessageEntity
import com.callx.app.models.Message
import com.callx.app.repository.ChatRepository
import com.callx.app.utils.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.util.concurrent.Executors

/**
 * ChatActivity — Production-grade 1:1 chat screen (Kotlin).
 *
 * Architecture:
 *   Firebase RT DB ──ChildEventListener──► Room DB
 *   MessageDao.getMessagesPagingSource() ◄── auto-invalidates
 *   Pager<Int, MessageEntity> → PagingData<Message> → MessagePagingAdapter
 */
class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var db: AppDatabase
    private val ioExecutor = Executors.newFixedThreadPool(3)

    private var partnerUid  = ""
    private var partnerName = ""
    private var partnerPhoto = ""
    private var chatId = ""
    private lateinit var currentUid: String

    private lateinit var pagingAdapter: MessagePagingAdapter
    private lateinit var chatRepo: ChatRepository
    private lateinit var messagesRef: DatabaseReference
    private var childEventListener: ChildEventListener? = null

    private var replyingTo: Message? = null
    private val replyController by lazy {
        ReplyController(object : ReplyController.Callback {
            override fun onReplyActivated(message: Message) { showReplyBar(message) }
            override fun onReplyCancelled() { clearReplyBar() }
            override fun onPendingUndo(message: Message, cancelAction: Runnable) {
                Snackbar.make(binding.root, "Reply added", Snackbar.LENGTH_SHORT)
                    .setAction("Undo") { cancelAction.run() }.show()
            }
            override fun onNavigateToOriginal(messageId: String) {
                // scroll to original message
            }
            override fun onUndoConfirmed() { clearReplyBar() }
        })
    }

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
        currentUid  = auth.currentUser!!.uid
        partnerUid  = intent.getStringExtra("uid") ?: ""
        partnerName = intent.getStringExtra("name") ?: "User"
        partnerPhoto = intent.getStringExtra("photo") ?: ""
        chatId = buildChatId(currentUid, partnerUid)

        db = AppDatabase.getInstance(this)
        chatRepo = ChatRepository.getInstance(this)

        setupToolbar()
        setupRecyclerView()
        setupInput()
        setupReplyBar()
        setupPagingObserver()
        startFirebaseListener()
        markChatRead()
    }

    private fun buildChatId(a: String, b: String) = if (a < b) "${a}_${b}" else "${b}_${a}"

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = partnerName
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
        // Load partner avatar if present
        binding.ivPartnerAvatar?.let { iv ->
            if (partnerPhoto.isNotEmpty()) {
                com.bumptech.glide.Glide.with(this).load(partnerPhoto).circleCrop()
                    .placeholder(R.drawable.ic_person).into(iv)
            }
        }
    }

    private fun setupRecyclerView() {
        pagingAdapter = MessagePagingAdapter(currentUid, false)
        val lm = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvMessages.layoutManager = lm
        binding.rvMessages.adapter = pagingAdapter
        SwipeOptimizer.disableChangeAnimations(binding.rvMessages)

        val swipeHandler = SwipeReplyHandler(pagingAdapter.snapshot().items, currentUid) { message ->
            ReplyAnalyticsTracker.get.onSwipeTriggered()
            replyController.onSwipeReply(message)
        }
        ItemTouchHelper(swipeHandler).attachToRecyclerView(binding.rvMessages)
    }

    private fun setupPagingObserver() {
        val pager = Pager(PagingConfig(pageSize = 50, enablePlaceholders = false)) {
            db.messageDao().getMessagesPagingSource(chatId)
        }
        pager.liveData.observe(this) { pagingData ->
            val mapped = pagingData.map { entity -> entityToMessage(entity) }
            pagingAdapter.submitData(lifecycle, mapped)
        }
    }

    private fun setupInput() {
        binding.etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val hasText = !s.isNullOrEmpty()
                binding.btnSend.visibility = if (hasText) View.VISIBLE else View.GONE
                binding.btnMic?.visibility = if (hasText) View.GONE else View.VISIBLE
                updateTypingStatus(!s.isNullOrEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString().trim()
            if (text.isNotEmpty()) sendTextMessage(text)
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

    private fun sendTextMessage(text: String) {
        val ref = messagesRef.push()
        val msgId = ref.key ?: return
        val m = Message(
            id = msgId, senderId = currentUid,
            text = text, type = "text",
            timestamp = System.currentTimeMillis(), status = "sent"
        )
        replyingTo?.let { ReplyDataMapper.applyReplyFields(m, it, currentUid) }

        binding.etMessage.setText("")
        clearReplyBar()

        ioExecutor.execute {
            db.messageDao().insertMessage(entityFromMessage(m, chatId))
        }
        ref.setValue(m)
        updateChatMeta(chatId, text)
        PushNotify.sendToUser(this, partnerUid, partnerName, text, chatId)
    }

    private fun uploadAndSend(uri: Uri, msgType: String, resourceType: String, fileName: String?) {
        if (!isOnline()) {
            Toast.makeText(this, "No internet connection", Toast.LENGTH_SHORT).show()
            return
        }
        CloudinaryUploader.upload(this, uri, "chat_media", resourceType,
            object : CloudinaryUploader.UploadCallback {
                override fun onSuccess(url: String) {
                    val ref = messagesRef.push()
                    val msgId = ref.key ?: return
                    val m = Message(
                        id = msgId, senderId = currentUid, type = msgType,
                        mediaUrl = url, fileName = fileName,
                        timestamp = System.currentTimeMillis(), status = "sent"
                    )
                    ref.setValue(m)
                    ioExecutor.execute { db.messageDao().insertMessage(entityFromMessage(m, chatId)) }
                    updateChatMeta(chatId, "[$msgType]")
                }
                override fun onFailure(error: String) {
                    Toast.makeText(this@ChatActivity, "Upload failed: $error", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun startFirebaseListener() {
        messagesRef = FirebaseUtils.getChatMessagesRef(chatId)
        childEventListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val m = snapshot.getValue(Message::class.java) ?: return
                if (m.id == null) m.id = snapshot.key
                ioExecutor.execute { db.messageDao().insertMessage(entityFromMessage(m, chatId)) }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                val m = snapshot.getValue(Message::class.java) ?: return
                if (m.id == null) m.id = snapshot.key
                ioExecutor.execute { db.messageDao().insertMessage(entityFromMessage(m, chatId)) }
            }
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        messagesRef.addChildEventListener(childEventListener!!)
    }

    private fun updateTypingStatus(typing: Boolean) {
        FirebaseUtils.getChatTypingRef(chatId)?.child(currentUid)?.let { ref ->
            if (typing) ref.setValue(true) else ref.removeValue()
        }
    }

    private fun markChatRead() {
        ioExecutor.execute { db.chatDao().updateUnread(chatId, 0) }
        FirebaseUtils.getUserRef(currentUid).child("contacts").child(partnerUid).child("unread").setValue(0)
    }

    private fun updateChatMeta(chatId: String, lastMsg: String) {
        val ts = System.currentTimeMillis()
        ioExecutor.execute { db.chatDao().updateLastMessage(chatId, lastMsg, ts) }
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

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun entityFromMessage(m: Message, chatId: String): MessageEntity {
        val e = MessageEntity()
        e.id                = m.id ?: ""
        e.chatId            = chatId
        e.senderId          = m.senderId
        e.senderName        = m.senderName
        e.text              = m.text
        e.type              = m.type ?: "text"
        e.mediaUrl          = m.mediaUrl ?: m.imageUrl
        e.thumbnailUrl      = m.thumbnailUrl
        e.fileName          = m.fileName
        e.fileSize          = m.fileSize
        e.duration          = m.duration
        e.timestamp         = m.timestamp
        e.status            = m.status
        e.replyToId         = m.replyToId
        e.replyToText       = m.replyToText
        e.replyToSenderName = m.replyToSenderName
        e.edited            = m.edited
        e.editedAt          = m.editedAt
        e.deleted           = m.deleted
        e.forwardedFrom     = m.forwardedFrom
        e.starred           = m.starred
        e.pinned            = m.pinned
        e.syncedAt          = System.currentTimeMillis()
        return e
    }

    private fun entityToMessage(e: MessageEntity) = Message(
        id = e.id, messageId = e.id, senderId = e.senderId, senderName = e.senderName,
        text = e.text, type = e.type, mediaUrl = e.mediaUrl, thumbnailUrl = e.thumbnailUrl,
        fileName = e.fileName, fileSize = e.fileSize, duration = e.duration,
        timestamp = e.timestamp, status = e.status, replyToId = e.replyToId,
        replyToText = e.replyToText, replyToSenderName = e.replyToSenderName,
        edited = e.edited, editedAt = e.editedAt, deleted = e.deleted,
        forwardedFrom = e.forwardedFrom, starred = e.starred, pinned = e.pinned
    )

    override fun onDestroy() {
        super.onDestroy()
        childEventListener?.let { messagesRef.removeEventListener(it) }
        updateTypingStatus(false)
    }
}
