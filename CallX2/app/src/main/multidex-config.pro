# ══════════════════════════════════════════════════════════════════════
#  Dex Layout Optimization — Primary Dex Keep Rules
#  ─────────────────────────────────────────────────────────────────────
#  Ye file multiDexKeepProguard ke through primary dex (.dex shard 0)
#  mein hot classes ko FORCE karta hai.
#
#  WHY: Android class loader primary dex se pehle load karta hai.
#  Agar startup + chat critical path ke classes secondary dex mein
#  chale gaye, toh class loading pe extra I/O hit padta hai.
#  Isse cold start + chat open time mein 50-150ms fark padta hai.
#
#  HOW: AGP 8.x mein `dexLayoutOptimization { enabled = true }` ke saath
#  ye file milake kaam karta hai. Baseline profile (baseline-prof.txt) se
#  AGP khud bhi dex reordering karta hai, lekin ye file explicit guarantee
#  deta hai ki in classes ka primary dex class loader path fast hoga.
#
#  MAINTENANCE: Naye hot classes sirf tab add karo jab profiler mein
#  clearly startup ya chat-open hot path mein dikh rahi hoon.
# ══════════════════════════════════════════════════════════════════════

# ── App entry points (always in primary dex) ──────────────────────────
-keep class com.callx.app.CallxApp { *; }
-keep class com.callx.app.activities.MainActivity { *; }
-keep class com.callx.app.activities.SplashActivity { *; }
-keep class com.callx.app.activities.LoginActivity { *; }

# ── Chat core — most-launched path in the app ─────────────────────────
-keep class com.callx.app.conversation.ChatActivity { *; }
-keep class com.callx.app.conversation.MessageAdapter { *; }
-keep class com.callx.app.conversation.MessageAdapter$VH { *; }
-keep class com.callx.app.conversation.MessagePagingAdapter { *; }
-keep class com.callx.app.conversation.MessagePagingAdapter$VH { *; }
-keep class com.callx.app.chatlist.ChatListAdapter { *; }
-keep class com.callx.app.chatlist.ChatListAdapter$VH { *; }
-keep class com.callx.app.chatlist.ChatsFragment { *; }

# ── Chat controllers (loaded in ChatActivity.onCreate) ────────────────
-keep class com.callx.app.conversation.controllers.ChatMessageSender { *; }
-keep class com.callx.app.conversation.controllers.ChatActivityDelegate { *; }
-keep class com.callx.app.conversation.controllers.ChatPresenceController { *; }
-keep class com.callx.app.conversation.controllers.ChatLiveTypingController { *; }
-keep class com.callx.app.conversation.controllers.ChatMediaController { *; }
-keep class com.callx.app.conversation.controllers.ChatReactionController { *; }
-keep class com.callx.app.conversation.controllers.ChatThemeController { *; }
-keep class com.callx.app.conversation.controllers.ChatPinController { *; }
-keep class com.callx.app.conversation.controllers.ChatStarredController { *; }
-keep class com.callx.app.conversation.controllers.ChatSearchController { *; }
-keep class com.callx.app.conversation.controllers.ChatScheduledSendController { *; }
-keep class com.callx.app.conversation.controllers.ChatBlockController { *; }
-keep class com.callx.app.conversation.controllers.ChatViewOnceController { *; }
-keep class com.callx.app.conversation.controllers.ChatPlaybackPresenceController { *; }
-keep class com.callx.app.conversation.controllers.ChatEmojiBurstController { *; }
-keep class com.callx.app.conversation.controllers.ChatPollController { *; }
-keep class com.callx.app.conversation.controllers.ChatContactShareController { *; }
-keep class com.callx.app.conversation.controllers.ChatLocationShareController { *; }
-keep class com.callx.app.conversation.controllers.ChatExportController { *; }
-keep class com.callx.app.conversation.controllers.MessageEditHistoryController { *; }

# ── Chat UI components (inflate + bind during first frame) ────────────
-keep class com.callx.app.chat.ui.ReplyBarView { *; }
-keep class com.callx.app.chat.ui.TypingDotsAnimator { *; }
-keep class com.callx.app.chat.ui.MessageHighlightAnimator { *; }
-keep class com.callx.app.chat.ui.AudioWaveformView { *; }
-keep class com.callx.app.chat.ui.GifAwareEditText { *; }
-keep class com.callx.app.chat.ui.BannerPriorityCoordinator { *; }

# ── Reply system (swipe path, loaded on first swipe) ─────────────────
-keep class com.callx.app.chat.reply.ReplyController { *; }
-keep class com.callx.app.chat.reply.ReplyStateManager { *; }
-keep class com.callx.app.chat.reply.ReplyDataMapper { *; }
-keep class com.callx.app.chat.gesture.SwipeReplyHandler { *; }
-keep class com.callx.app.chat.performance.SwipeOptimizer { *; }

# ── ViewModel (created in ChatActivity.onCreate → blocks UI thread) ───
-keep class com.callx.app.viewmodel.ChatViewModel { *; }
-keep class com.callx.app.viewmodel.ChatViewModelFactory { *; }

# ── DB + crypto (3-sec chat-open bottleneck fix) ──────────────────────
-keep class com.callx.app.db.AppDatabase { *; }
-keep class com.callx.app.utils.SecurityManager { *; }

# ── Theme cache (GradientDrawable inflation) ──────────────────────────
-keep class com.callx.app.utils.ChatThemeManager { *; }

# ── Group chat (frequently accessed, keep together with 1:1 chat) ─────
-keep class com.callx.app.group.GroupChatActivity { *; }
-keep class com.callx.app.group.GroupAdapter { *; }
-keep class com.callx.app.group.GroupAdapter$** { *; }
-keep class com.callx.app.group.GroupsFragment { *; }
-keep class com.callx.app.group.GroupMemberAdapter { *; }
-keep class com.callx.app.group.GroupStarredController { *; }
-keep class com.callx.app.group.GroupWatchingController { *; }

# ── Glide (image loading during first RecyclerView scroll) ────────────
-keep class com.bumptech.glide.Glide { *; }
-keep class com.bumptech.glide.RequestManager { *; }
-keep class com.bumptech.glide.load.engine.** { *; }

# ── RecyclerView core (layout pass during first frame) ────────────────
-keep class androidx.recyclerview.widget.RecyclerView { *; }
-keep class androidx.recyclerview.widget.RecyclerView$Recycler { *; }
-keep class androidx.recyclerview.widget.LinearLayoutManager { *; }

# ── Firebase (needed for auth check on startup) ───────────────────────
-keep class com.google.firebase.auth.FirebaseAuth { *; }
-keep class com.google.firebase.database.FirebaseDatabase { *; }

# ── Conversation gallery helpers (opened frequently from chat) ────────
-keep class com.callx.app.conversation.MediaGroupLayoutHelper { *; }
-keep class com.callx.app.media.MediaThumbAdapter { *; }
-keep class com.callx.app.conversation.GalleryForwardBridge { *; }
-keep class com.callx.app.conversation.GalleryReplyBridge { *; }
