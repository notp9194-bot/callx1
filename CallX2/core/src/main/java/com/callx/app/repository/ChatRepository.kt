package com.callx.app.repository

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import com.callx.app.cache.CacheManager
import com.callx.app.db.AppDatabase
import com.callx.app.db.entity.MessageEntity
import com.callx.app.db.entity.UserEntity
import com.callx.app.models.Message
import com.callx.app.models.User
import com.callx.app.utils.Constants
import com.google.firebase.database.*
import java.util.concurrent.Executors

/**
 * ChatRepository — Offline-First + Predictive + Delta Sync.
 *
 * Strategy:
 *   1. Serve from local cache immediately (zero latency for UI)
 *   2. Fetch delta from Firebase (only new messages since last sync)
 *   3. Merge & save — LiveData auto-updates the UI
 */
class ChatRepository private constructor(ctx: Context) {

    private val mCache = CacheManager.getInstance(ctx)
    private val mDb = AppDatabase.getInstance(ctx)
    private val mExecutor = Executors.newFixedThreadPool(4)
    private val mFirebase = FirebaseDatabase.getInstance(Constants.DB_URL)

    companion object {
        private const val TAG = "ChatRepository"
        private const val PAGE_SIZE = 50

        @Volatile private var sInstance: ChatRepository? = null

        fun getInstance(ctx: Context): ChatRepository =
            sInstance ?: synchronized(this) {
                sInstance ?: ChatRepository(ctx.applicationContext).also { sInstance = it }
            }
    }

    // ── Messages — offline-first LiveData ────────────────────────────

    fun getMessages(chatId: String): LiveData<List<MessageEntity>> {
        syncMessagesDelta(chatId)
        return mDb.messageDao().getMessages(chatId)
    }

    fun syncMessagesDelta(chatId: String) {
        mExecutor.execute {
            val lastTs = mCache.getLastSyncTimestamp(chatId)
            Log.d(TAG, "Delta sync chatId=$chatId since=$lastTs")

            val query: Query = mFirebase.getReference("chats")
                .child(chatId)
                .child("messages")
                .orderByChild("timestamp")
                .apply {
                    if (lastTs != 0L) startAfter(lastTs.toDouble(), "timestamp")
                }
                .limitToLast(PAGE_SIZE)

            query.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val newMessages = mutableListOf<MessageEntity>()
                    for (child in snapshot.children) {
                        val m = child.getValue(Message::class.java) ?: continue
                        if (m.id == null) m.id = child.key
                        newMessages.add(toEntity(m, chatId))
                    }
                    if (newMessages.isNotEmpty()) {
                        mExecutor.execute {
                            mDb.messageDao().insertMessages(newMessages)
                            mCache.invalidateMessages(chatId)
                            Log.d(TAG, "Delta sync: inserted ${newMessages.size} new messages for $chatId")
                        }
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.w(TAG, "Delta sync cancelled: ${error.message}")
                }
            })
        }
    }

    // ── User Profile — offline-first ─────────────────────────────────

    fun getUserProfile(uid: String): LiveData<UserEntity> {
        refreshUserProfile(uid)
        return mDb.userDao().getUserLive(uid)
    }

    private fun refreshUserProfile(uid: String) {
        mExecutor.execute {
            mFirebase.getReference("users").child(uid)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val u = snapshot.getValue(User::class.java) ?: return
                        if (u.uid == null) u.uid = snapshot.key
                        mExecutor.execute { mCache.saveUser(userToEntity(u)) }
                    }
                    override fun onCancelled(error: DatabaseError) {}
                })
        }
    }

    // ── Predictive Preloading ────────────────────────────────────────

    fun preloadRecentChats(currentChatId: String) {
        mExecutor.execute {
            val topChats = mCache.analytics.getTopChats(5)
            for (chatId in topChats) {
                if (chatId != currentChatId) syncMessagesDelta(chatId)
            }
        }
    }

    // ── Cleanup ──────────────────────────────────────────────────────

    fun pruneOldMessages(chatId: String, keepCount: Int) {
        mExecutor.execute { mDb.messageDao().pruneOldMessages(chatId, keepCount) }
    }

    // ── Helpers — model ↔ entity conversion ──────────────────────────

    private fun toEntity(m: Message, chatId: String): MessageEntity {
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

    // UserEntity is a Java class — use field assignment, NOT named constructor args
    private fun userToEntity(u: User): UserEntity {
        val e = UserEntity()
        e.uid          = u.uid ?: ""
        e.email        = u.email
        e.name         = u.name
        e.emoji        = u.emoji
        e.callxId      = u.callxId
        e.about        = u.about
        e.photoUrl     = u.photoUrl
        e.fcmToken     = u.fcmToken
        e.lastSeen     = u.lastSeen
        e.lastMessage  = u.lastMessage
        e.lastMessageAt = u.lastMessageAt
        e.unread       = u.unread
        e.cachedAt     = System.currentTimeMillis()
        return e
    }
}
