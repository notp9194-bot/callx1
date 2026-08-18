# ══════════════════════════════════════════════════════════════════════
#  Dex Layout — Primary Dex Keep Rules  (v2 — All Feature Modules)
#  ─────────────────────────────────────────────────────────────────────
#  Hot classes primary dex (.dex shard 0) mein lock hoti hain.
#  Class loader inhe secondary dex I/O bina load karta hai — cold start
#  aur chat open pe 30-80ms measurable gain.
#
#  Coverage: App + Chat + Calls + Status + DB + Crypto + Framework
# ══════════════════════════════════════════════════════════════════════

# ── App entry points ──────────────────────────────────────────────────
-keep class com.callx.app.CallxApp { *; }
-keep class com.callx.app.activities.MainActivity { *; }
-keep class com.callx.app.activities.SplashActivity { *; }
-keep class com.callx.app.activities.LoginActivity { *; }

# ── Chat core ─────────────────────────────────────────────────────────
-keep class com.callx.app.conversation.ChatActivity { *; }
-keep class com.callx.app.conversation.MessageAdapter { *; }
-keep class com.callx.app.conversation.MessageAdapter$VH { *; }
-keep class com.callx.app.conversation.MessagePagingAdapter { *; }
-keep class com.callx.app.conversation.MessagePagingAdapter$VH { *; }
-keep class com.callx.app.chatlist.ChatListAdapter { *; }
-keep class com.callx.app.chatlist.ChatListAdapter$VH { *; }
-keep class com.callx.app.chatlist.ChatsFragment { *; }

# ── Chat controllers ──────────────────────────────────────────────────
-keep class com.callx.app.conversation.controllers.** { *; }
-keep class com.callx.app.viewmodel.ChatViewModel { *; }
-keep class com.callx.app.viewmodel.ChatViewModelFactory { *; }

# ── Chat UI components ────────────────────────────────────────────────
-keep class com.callx.app.chat.ui.ReplyBarView { *; }
-keep class com.callx.app.chat.ui.TypingDotsAnimator { *; }
-keep class com.callx.app.chat.ui.MessageHighlightAnimator { *; }
-keep class com.callx.app.chat.ui.AudioWaveformView { *; }
-keep class com.callx.app.chat.ui.GifAwareEditText { *; }
-keep class com.callx.app.chat.ui.BannerPriorityCoordinator { *; }

# ── Reply + swipe gesture ─────────────────────────────────────────────
-keep class com.callx.app.chat.gesture.SwipeReplyHandler { *; }
-keep class com.callx.app.chat.reply.ReplyController { *; }
-keep class com.callx.app.chat.reply.ReplyStateManager { *; }
-keep class com.callx.app.chat.reply.ReplyDataMapper { *; }
-keep class com.callx.app.chat.performance.SwipeOptimizer { *; }

# ── Group chat ────────────────────────────────────────────────────────
-keep class com.callx.app.group.GroupChatActivity { *; }
-keep class com.callx.app.group.GroupAdapter { *; }
-keep class com.callx.app.group.GroupAdapter$** { *; }
-keep class com.callx.app.group.GroupsFragment { *; }
-keep class com.callx.app.group.GroupMemberAdapter { *; }
-keep class com.callx.app.group.GroupStarredController { *; }
-keep class com.callx.app.group.GroupWatchingController { *; }

# ── feature-calls (latency-critical — IncomingCallActivity first) ─────
-keep class com.callx.app.incoming.IncomingCallActivity { *; }
-keep class com.callx.app.incoming.IncomingGroupCallActivity { *; }
-keep class com.callx.app.call.CallActivity { *; }
-keep class com.callx.app.group.GroupCallActivity { *; }
-keep class com.callx.app.group.GroupCallParticipantAdapter { *; }
-keep class com.callx.app.history.CallsFragment { *; }
-keep class com.callx.app.history.CallHistoryAdapter { *; }
-keep class com.callx.app.services.CallForegroundService { *; }
-keep class com.callx.app.services.IncomingRingService { *; }

# ── feature-status (first tab many users open) ────────────────────────
-keep class com.callx.app.feed.StatusFragment { *; }
-keep class com.callx.app.feed.StatusListAdapter { *; }
-keep class com.callx.app.cache.StatusMediaPreloader { *; }
-keep class com.callx.app.cache.StatusVideoCacheManager { *; }
-keep class com.callx.app.compose.NewStatusActivity { *; }
-keep class com.callx.app.interactions.StatusReactionBottomSheet { *; }
-keep class com.callx.app.interactions.StatusReplyBottomSheet { *; }

# ── DB + crypto (3-sec chat-open fix) ────────────────────────────────
-keep class com.callx.app.db.AppDatabase { *; }
-keep class com.callx.app.utils.SecurityManager { *; }
-keep class com.callx.app.utils.ChatThemeManager { *; }

# ── SQLCipher (encryption hot path — called on every DB open) ─────────
-keep class net.sqlcipher.database.SQLiteDatabase { *; }
-keep class net.sqlcipher.database.SQLiteOpenHelper { *; }
-keep class net.sqlcipher.database.SQLiteCursor { *; }
-keep class net.sqlcipher.Cursor { *; }

# ── Media helpers ─────────────────────────────────────────────────────
-keep class com.callx.app.conversation.MediaGroupLayoutHelper { *; }
-keep class com.callx.app.media.MediaThumbAdapter { *; }
-keep class com.callx.app.conversation.GalleryForwardBridge { *; }
-keep class com.callx.app.conversation.GalleryReplyBridge { *; }

# ── Glide (first-scroll image load) ──────────────────────────────────
-keep class com.bumptech.glide.Glide { *; }
-keep class com.bumptech.glide.RequestManager { *; }
-keep class com.bumptech.glide.load.engine.** { *; }

# ── RecyclerView framework ────────────────────────────────────────────
-keep class androidx.recyclerview.widget.RecyclerView { *; }
-keep class androidx.recyclerview.widget.RecyclerView$Recycler { *; }
-keep class androidx.recyclerview.widget.LinearLayoutManager { *; }

# ── Firebase auth (checked on every screen open) ─────────────────────
-keep class com.google.firebase.auth.FirebaseAuth { *; }
-keep class com.google.firebase.database.FirebaseDatabase { *; }

# ── Spring animation (SwipeReply DynamicAnimation) ───────────────────
-keep class androidx.dynamicanimation.animation.SpringAnimation { *; }
-keep class androidx.dynamicanimation.animation.SpringForce { *; }

# ── WorkManager (scheduled messages, view-once expiry) ───────────────
-keep class androidx.work.WorkManager { *; }
-keep class com.callx.app.conversation.workers.** { *; }
