package com.callx.app.activities;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Transformations;
import androidx.paging.Pager;
import androidx.paging.PagingConfig;
import androidx.paging.PagingData;
import androidx.paging.PagingDataTransforms;
import androidx.paging.PagingLiveData;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.adapters.MessagePagingAdapter;
import com.callx.app.cache.CacheManager;
import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MessageEntity;
import com.callx.app.models.Message;
import com.callx.app.repository.ChatRepository;
import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.ImageCompressor;
import com.callx.app.utils.FileUtils;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.PushNotify;
import com.callx.app.utils.VoiceRecorder;
import com.facebook.shimmer.ShimmerFrameLayout;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import androidx.recyclerview.widget.ItemTouchHelper;
import com.callx.app.chat.analytics.ReplyAnalyticsTracker;
import com.callx.app.chat.gesture.SwipeReplyHandler;
import com.callx.app.chat.performance.SwipeOptimizer;
import com.callx.app.chat.reply.ReplyController;
import com.callx.app.chat.reply.ReplyDataMapper;
import com.callx.app.chat.ui.MessageHighlightAnimator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * ChatActivity — Production-grade 1:1 chat screen.
 *
 * Architecture:
 *   ┌─────────────────────────────────────────────────────────────┐
 *   │  Firebase RT DB  ──ChildEventListener──►  Room DB           │
 *   │  (real-time new msgs)                  (insert/update)       │
 *   │                                              │               │
 *   │  MessageDao.getMessagesPagingSource()        │               │
 *   │  (auto-invalidates on Room change) ◄─────────┘               │
 *   │            │                                                  │
 *   │   Pager<Integer, MessageEntity>                              │
 *   │            │                                                  │
 *   │   PagingData<MessageEntity>                                  │
 *   │            │  PagingDataTransforms.map()                     │
 *   │   PagingData<Message>                                        │
 *   │            │                                                  │
 *   │   MessagePagingAdapter ──► RecyclerView                     │
 *   └─────────────────────────────────────────────────────────────┘
 *
 * Key fixes vs original:
 *   [FIX-1] Replaced MessageAdapter (full List load) with MessagePagingAdapter (Paging 3)
 *   [FIX-2] Firebase ChildEventListener → Room insert (not List.add) → auto-invalidates PagingSource
 *   [FIX-3] Delta-sync via ChatRepository: only fetch messages newer than last cached ts
 *   [FIX-4] Shimmer hidden via LoadState listener (not a fixed delay)
 *   [FIX-5] Auto-scroll only when new message arrives at tail (not on every page load)
 *   [FIX-6] RecyclerView adapter data observer scrolls to bottom on new insert
 */
public class ChatActivity extends AppCompatActivity {

    // ── Constants ──────────────────────────────────────────────────────────
    private static final String TAG           = "ChatActivity";
    private static final int    PAGE_SIZE     = 20;
    private static final int    PREFETCH_DIST = 10;
    private static final int    INITIAL_LOAD  = 40;
    private static final int    REQ_AUDIO     = 200;
    private static final int    REQ_CAMERA    = 300;

    // ── View binding ───────────────────────────────────────────────────────
    private ActivityChatBinding binding;

    // ── Chat identifiers ───────────────────────────────────────────────────
    private String chatId;
    private String partnerUid;
    private String partnerName;
    private String partnerPhoto;
    private String partnerThumb;   // 100×100 WebP — header avatar fast load
    private String currentUid;
    private String currentName;

    // ── State flags ────────────────────────────────────────────────────────
    private boolean isMuted              = false;
    private boolean isBlocked            = false;
    private boolean isRecording          = false;
    private boolean partnerPermaBlockedMe = false;

    // ── Paging 3 (core fix) ───────────────────────────────────────────────
    private MessagePagingAdapter pagingAdapter;
    private AppDatabase          db;
    private final Executor       ioExecutor = Executors.newFixedThreadPool(2);

    // ── Firebase refs & listeners ──────────────────────────────────────────
    private DatabaseReference  messagesRef;
    private ChildEventListener messageListener;
    private ValueEventListener typingListener;
    private ValueEventListener onlineListener;
    private ValueEventListener blockListener;
    private ValueEventListener permaBlockListener;

    // ── Reply state ────────────────────────────────────────────────────────
    private Message replyingTo = null;

    // ── SwipeReplySystem v1 fields ─────────────────────────────────────────
    private com.callx.app.chat.reply.ReplyController  replyController;
    private androidx.recyclerview.widget.ItemTouchHelper swipeHelper;

    // ── Media pickers ──────────────────────────────────────────────────────
    private ActivityResultLauncher<String> imagePicker;
    private ActivityResultLauncher<String> videoPicker;
    private ActivityResultLauncher<String> audioPicker;
    private ActivityResultLauncher<String> filePicker;
    private ActivityResultLauncher<Uri>    cameraCapturer;
    private Uri cameraOutputUri;

    // ── Voice recorder ─────────────────────────────────────────────────────
    private VoiceRecorder recorder;

    // ── Pinned message ─────────────────────────────────────────────────────
    private String pinnedMsgId   = null;
    private String pinnedMsgText = null;

    // ── Network monitoring (Task 5) ────────────────────────────────────────
    private ConnectivityManager          connMgr;
    private ConnectivityManager.NetworkCallback netCallback;

    // ── Typing debounce ───────────────────────────────────────────────────
    private final android.os.Handler typingHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable           stopTypingRunnable = () -> setOurTypingStatus(false);

    // ─────────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        readIntentExtras();
        if (partnerUid == null || partnerUid.isEmpty()) {
            android.util.Log.w("ChatActivity", "partnerUid null — finishing");
            finish(); return;
        }
        setupToolbar();
        applyScreenTheme();   // ← Apply full chat-screen theme on launch
        setupPickers();
        recorder = new VoiceRecorder();

        db = AppDatabase.getInstance(this);

        // ── Core Paging 3 setup ──
        setupPagingRecyclerView();   // [FIX-1]  wire adapter
        observePagedMessages();      // [FIX-2]  start Pager → LiveData
        startRealtimeListener();     // [FIX-3]  Firebase → Room (auto-invalidates)

        // ── All other features ──
        setupInputBar();
        setupSwipeToReply();
        setupFabBackToLatest();
        watchPartnerStatus();
        watchTyping();
        watchMute();
        watchBlock();
        watchPartnerPermaBlock();
        watchPinnedMessage();
        markMessagesRead();

        // ── Task 5: Offline banner + message pruning ──
        setupNetworkMonitor();
        ioExecutor.execute(() -> db.messageDao().pruneOldMessages(chatId, 500));

        // Predictive preload of other hot chats (background)
        ChatRepository.getInstance(this).preloadRecentChats(chatId);

        // v18 IMPROVEMENT 2: Draft restore — wapas aane par type kiya hua text restore karo
        restoreDraft();
    }

    // ── v18 IMPROVEMENT 2: Draft save/restore ─────────────────────────────

    private void saveDraft() {
        if (db == null || chatId == null || binding == null) return;
        String draftText = binding.etMessage.getText() != null
                ? binding.etMessage.getText().toString() : "";
        Executors.newSingleThreadExecutor().execute(() ->
            db.chatDao().saveDraft(chatId, draftText));
    }

    private void restoreDraft() {
        if (db == null || chatId == null) return;
        Executors.newSingleThreadExecutor().execute(() -> {
            String draft = db.chatDao().getDraft(chatId);
            if (draft != null && !draft.isEmpty()) {
                runOnUiThread(() -> {
                    if (binding != null && binding.etMessage != null) {
                        binding.etMessage.setText(draft);
                        binding.etMessage.setSelection(draft.length());
                    }
                });
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveDraft();  // v18 IMPROVEMENT 2: User navigate away — draft save
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        saveDraft();
        if (messagesRef != null && messageListener != null)
            messagesRef.removeEventListener(messageListener);
        typingHandler.removeCallbacks(stopTypingRunnable);
        clearOurTypingStatus();
        if (connMgr != null && netCallback != null) {
            try { connMgr.unregisterNetworkCallback(netCallback); } catch (Exception ignored) {}
        }
        if (replyController != null) replyController.release();
    }

    // ─────────────────────────────────────────────────────────────────────
    // INTENT EXTRAS
    // ─────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────
    // NETWORK MONITORING (Task 5)
    // ─────────────────────────────────────────────────────────────────────

    private void setupNetworkMonitor() {
        connMgr = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (connMgr == null) return;

        // Pehle current state check karo
        boolean online = isOnline();
        updateOfflineBanner(!online);
        updateSendButtonState(online);  // v15 FIX 3: initial send button state

        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) {
                runOnUiThread(() -> {
                    updateOfflineBanner(false);
                    // v15 FIX 3: online hone par send button enable + pending retry
                    updateSendButtonState(true);
                    retryPendingMessages();
                });
            }
            @Override public void onLost(Network n) {
                runOnUiThread(() -> {
                    updateOfflineBanner(true);
                    // v15 FIX 3: offline hone par send button disable
                    updateSendButtonState(false);
                });
            }
        };
        try {
            NetworkRequest req = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            connMgr.registerNetworkCallback(req, netCallback);
        } catch (Exception ignored) {}
    }

    private boolean isOnline() {
        if (connMgr == null) return true;
        Network active = connMgr.getActiveNetwork();
        if (active == null) return false;
        NetworkCapabilities caps = connMgr.getNetworkCapabilities(active);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void updateOfflineBanner(boolean offline) {
        if (binding == null) return;
        android.widget.TextView banner =
                binding.getRoot().findViewById(R.id.tv_offline_banner);
        if (banner != null) banner.setVisibility(offline ? View.VISIBLE : View.GONE);
    }

    private void readIntentExtras() {
        Intent i    = getIntent();
        partnerUid  = i.getStringExtra("partnerUid");
        partnerName = i.getStringExtra("partnerName");
        partnerPhoto= i.getStringExtra("partnerPhoto");
        partnerThumb= i.getStringExtra("partnerThumb");
        currentName = i.getStringExtra("currentName");

        com.google.firebase.auth.FirebaseUser fu =
                FirebaseAuth.getInstance().getCurrentUser();
        currentUid = fu != null ? fu.getUid() : "";

        chatId      = buildChatId(currentUid, partnerUid);
        messagesRef = FirebaseUtils.getMessagesRef(chatId);

        // Forward payload — ContactsActivity ne bheja tha
        String fwdText  = i.getStringExtra("forwardText");
        String fwdType  = i.getStringExtra("forwardType");
        String fwdMedia = i.getStringExtra("forwardMedia");

        // Multi-message forward payload
        java.util.ArrayList<String> fwdTexts     = i.getStringArrayListExtra("forwardTexts");
        java.util.ArrayList<String> fwdTypes     = i.getStringArrayListExtra("forwardTypes");
        java.util.ArrayList<String> fwdMedias    = i.getStringArrayListExtra("forwardMedias");
        java.util.ArrayList<String> fwdFileNames = i.getStringArrayListExtra("forwardFileNames");

        if (fwdTexts != null && !fwdTexts.isEmpty()) {
            // Multiple messages forward: queue all messages with small delay
            binding.getRoot().post(() -> {
                for (int idx = 0; idx < fwdTexts.size(); idx++) {
                    final int fi = idx;
                    binding.getRoot().postDelayed(() -> {
                        String t  = fwdTexts.get(fi);
                        String tp = fwdTypes.get(fi);
                        String mu = fwdMedias.get(fi);
                        Message m2 = buildOutgoing();
                        m2.forwardedFrom = partnerName;
                        if ("text".equals(tp) || tp == null) {
                            m2.type = "text";
                            m2.text = t;
                            pushMessage(m2, t != null ? t : "");
                        } else if (mu != null && !mu.isEmpty()) {
                            m2.type     = tp;
                            m2.mediaUrl = mu;
                            m2.imageUrl = "image".equals(tp) ? mu : null;
                            m2.fileName = fwdFileNames != null && fi < fwdFileNames.size()
                                    ? fwdFileNames.get(fi) : null;
                            String preview = "image".equals(tp) ? "\uD83D\uDCF7 Photo (forwarded)"
                                           : "video".equals(tp) ? "\uD83C\uDFAC Video (forwarded)"
                                           : "audio".equals(tp) ? "\uD83C\uDFA4 Voice (forwarded)"
                                           : "\uD83D\uDCCE File (forwarded)";
                            pushMessage(m2, preview);
                        }
                    }, idx * 150L);  // 150ms gap between messages
                }
            });
        } else if (fwdText != null && !fwdText.isEmpty() && "text".equals(fwdType)) {
            // Text forward: input bar mein pre-fill karo (user confirm kar sake)
            if (binding != null && binding.etMessage != null) {
                binding.etMessage.post(() -> {
                    binding.etMessage.setText(fwdText);
                    binding.etMessage.setSelection(fwdText.length());
                });
            }
        } else if (fwdMedia != null && !fwdMedia.isEmpty()) {
            // Media forward: directly send (WhatsApp jaisa behavior)
            if (binding != null) {
                binding.getRoot().post(() -> {
                    Message m  = buildOutgoing();
                    m.type     = fwdType != null ? fwdType : "image";
                    m.mediaUrl = fwdMedia;
                    m.imageUrl = "image".equals(m.type) ? fwdMedia : null;
                    m.forwardedFrom = partnerName;
                    String preview = "image".equals(m.type) ? "\uD83D\uDCF7 Photo (forwarded)"
                                   : "video".equals(m.type) ? "\uD83C\uDFAC Video (forwarded)"
                                   : "audio".equals(m.type) ? "\uD83C\uDFA4 Voice (forwarded)"
                                   : "\uD83D\uDCCE File (forwarded)";
                    pushMessage(m, preview);
                });
            }
        }
    }

    private static String buildChatId(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        return a.compareTo(b) < 0 ? a + "_" + b : b + "_" + a;
    }

    // ─────────────────────────────────────────────────────────────────────
    // TOOLBAR
    // ─────────────────────────────────────────────────────────────────────

    private void setupToolbar() {
        // Custom WhatsApp-style header — no MaterialToolbar, no ActionBar needed
        binding.btnBack.setOnClickListener(v -> {
            if (pagingAdapter != null && pagingAdapter.isInMultiSelectMode()) {
                pagingAdapter.exitMultiSelectMode();
                hideMultiSelectBar();
            } else {
                finish();
            }
        });

        if (partnerName != null) {
            binding.tvPartnerName.setText(partnerName);
        }

        binding.ivPartnerAvatar.setOnClickListener(v -> openAvatarZoom());

        // Header avatar: thumbUrl → fast 100px load; fallback partnerPhoto
        String headerAvatar = (partnerThumb != null && !partnerThumb.isEmpty())
            ? partnerThumb : partnerPhoto;
        if (headerAvatar != null && !headerAvatar.isEmpty()) {
            Glide.with(this).load(headerAvatar)
                    .placeholder(R.drawable.ic_person)
                    .circleCrop()
                    .into(binding.ivPartnerAvatar);
        } else {
            // Neither in intent — Firebase se fetch karo (thumbUrl pehle, photoUrl fallback)
            FirebaseUtils.getUserRef(partnerUid).addListenerForSingleValueEvent(
                    new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot s) {
                            String thumb = s.child("thumbUrl").getValue(String.class);
                            String photo = s.child("photoUrl").getValue(String.class);
                            String url = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                            if (url != null && !url.isEmpty()) {
                                if (photo != null) partnerPhoto = photo;
                                if (thumb != null) partnerThumb = thumb;
                                Glide.with(ChatActivity.this).load(url)
                                        .placeholder(R.drawable.ic_person)
                                        .circleCrop()
                                        .into(binding.ivPartnerAvatar);
                            }
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {}
                    });
        }

        // Voice call button
        binding.btnToolbarVoiceCall.setOnClickListener(v -> startCall(false));

        // Video call button
        binding.btnToolbarVideoCall.setOnClickListener(v -> startCall(true));

        // ── Social profile buttons ────────────────────────────────────────────
        // Reel profile button — open partner's reels profile
        binding.btnToolbarReel.setOnClickListener(v -> {
            if (partnerUid == null || partnerUid.isEmpty()) return;
            try {
                Class<?> cls = Class.forName("com.callx.app.activities.UserReelsActivity");
                Intent i = new Intent(this, cls);
                i.putExtra("uid",   partnerUid);
                i.putExtra("name",  partnerName != null ? partnerName : "");
                i.putExtra("photo", partnerPhoto != null ? partnerPhoto : "");
                startActivity(i);
            } catch (ClassNotFoundException e) {
                android.widget.Toast.makeText(this, "Reels profile not available", android.widget.Toast.LENGTH_SHORT).show();
            }
        });

        // X profile button — open partner's X profile sheet
        binding.btnToolbarX.setOnClickListener(v -> {
            if (partnerUid == null || partnerUid.isEmpty()) return;
            try {
                Class<?> cls = Class.forName("com.callx.app.activities.XProfileSheet");
                java.lang.reflect.Method method = cls.getMethod("showProfile",
                        androidx.fragment.app.FragmentManager.class, String.class);
                method.invoke(null, getSupportFragmentManager(), partnerUid);
            } catch (Exception e) {
                android.widget.Toast.makeText(this, "X profile not available", android.widget.Toast.LENGTH_SHORT).show();
            }
        });

        // YouTube channel button — open partner's YouTube channel
        binding.btnToolbarYoutube.setOnClickListener(v -> {
            if (partnerUid == null || partnerUid.isEmpty()) return;
            try {
                Class<?> cls = Class.forName("com.callx.app.activities.YouTubeChannelActivity");
                Intent i = new Intent(this, cls);
                i.putExtra("uid",  partnerUid);
                i.putExtra("name", partnerName != null ? partnerName : "");
                startActivity(i);
            } catch (ClassNotFoundException e) {
                android.widget.Toast.makeText(this, "YouTube channel not available", android.widget.Toast.LENGTH_SHORT).show();
            }
        });

        binding.btnMoreOptions.setOnClickListener(v -> {
            android.widget.PopupMenu popup = new android.widget.PopupMenu(this, binding.btnMoreOptions);
            popup.getMenuInflater().inflate(com.callx.app.chat.R.menu.chat_menu, popup.getMenu());
            popup.setOnMenuItemClickListener(item -> onOptionsItemSelected(item));
            popup.show();
        });
    }

    private void startCall(boolean isVideo) {
        Intent i = new Intent().setClassName(this, "com.callx.app.activities.CallActivity");
        i.putExtra("partnerUid",   partnerUid);
        i.putExtra("partnerName",  partnerName);
        i.putExtra("partnerPhoto", partnerPhoto);
        i.putExtra("isCaller",     true);
        i.putExtra("video",        isVideo);
        startActivity(i);
    }

    // ─────────────────────────────────────────────────────────────────────
    // [FIX-1] PAGING 3 — RecyclerView + Adapter
    // ─────────────────────────────────────────────────────────────────────

    private void setupPagingRecyclerView() {
        pagingAdapter = new MessagePagingAdapter(currentUid, false);

        pagingAdapter.setActionListener(new MessagePagingAdapter.ActionListener() {
            @Override public void onReply(Message m)               { startReply(m); }
            @Override public void onDelete(Message m)              { confirmDeleteMessage(m); }
            @Override public void onReact(Message m, String emoji) { sendReaction(m, emoji); }
            @Override public void onStar(Message m)                { toggleStar(m); }
            @Override public void onCopy(Message m)                { copyText(m); }
            @Override public void onForward(Message m)             { forwardMessage(m); }
            @Override public void onNavigateToOriginal(String messageId) { navigateToOriginal(messageId); }
        });

        // Multi-select: jab selection change ho, action bar update karo
        pagingAdapter.setMultiSelectListener(count -> {
            if (count > 0) {
                showMultiSelectBar(count);
            } else {
                hideMultiSelectBar();
            }
        });

        LinearLayoutManager llm = new LinearLayoutManager(this);
        llm.setStackFromEnd(true);    // newest items at the bottom
        llm.setReverseLayout(false);
        binding.rvMessages.setLayoutManager(llm);
        binding.rvMessages.setAdapter(pagingAdapter);
        SwipeOptimizer.disableChangeAnimations(binding.rvMessages);

        // [FIX-5] Auto-scroll only when new item arrives at the very end
        pagingAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                super.onItemRangeInserted(positionStart, itemCount);
                int total  = pagingAdapter.getItemCount();
                int lastVis = llm.findLastVisibleItemPosition();
                // Scroll to bottom only if user was already near the end
                if (lastVis >= total - itemCount - 2) {
                    binding.rvMessages.scrollToPosition(total - 1);
                }
            }
        });

        // [FIX-4] Shimmer controlled by real LoadState, not a timeout
        pagingAdapter.addLoadStateListener(states -> {
            androidx.paging.LoadState refresh = states.getRefresh();
            if (refresh instanceof androidx.paging.LoadState.Loading) {
                if (binding.rvMessages.getVisibility() == View.GONE) {
                    binding.shimmerContainer.startShimmer();
                    binding.shimmerContainer.setVisibility(View.VISIBLE);
                }
            } else {
                binding.shimmerContainer.stopShimmer();
                binding.shimmerContainer.setVisibility(View.GONE);
                binding.rvMessages.setVisibility(View.VISIBLE);
                if (refresh instanceof androidx.paging.LoadState.Error) {
                    String msg = ((androidx.paging.LoadState.Error) refresh)
                            .getError().getMessage();
                    Toast.makeText(this,
                            "Failed to load messages: " + msg,
                            Toast.LENGTH_SHORT).show();
                }
            }
            return null;
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // [FIX-2] PAGING 3 — Pager → LiveData → Adapter
    //
    // Uses Room's native PagingSource (MessageDao.getMessagesPagingSource).
    // Room auto-invalidates the PagingSource whenever ANY insert/update
    // touches the "messages" table — no manual invalidate() needed.
    // ─────────────────────────────────────────────────────────────────────

    private void observePagedMessages() {
        Pager<Integer, MessageEntity> pager = new Pager<>(
                new PagingConfig(
                        /* pageSize          */ PAGE_SIZE,
                        /* prefetchDistance  */ PREFETCH_DIST,
                        /* enablePlaceholders*/ false,
                        /* initialLoadSize   */ INITIAL_LOAD
                ),
                () -> db.messageDao().getMessagesPagingSource(chatId)
        );

        // Map PagingData<MessageEntity> → PagingData<Message> on ioExecutor
        Transformations.map(
                PagingLiveData.getLiveData(pager),
                pagingData -> PagingDataTransforms.map(
                        pagingData,
                        ioExecutor,
                        ChatActivity::entityToModel
                )
        ).observe(this, pagingData ->
                pagingAdapter.submitData(getLifecycle(), pagingData)
        );
    }

    // ─────────────────────────────────────────────────────────────────────
    // [FIX-3] FIREBASE REAL-TIME LISTENER
    //
    // Inserts new messages into Room DB.
    // Room's PagingSource auto-invalidates → Pager re-runs → RecyclerView
    // updates with the new item. No notifyDataSetChanged() needed.
    //
    // Delta-sync: startAt(lastTs) so we only pull what we don't already have.
    // ─────────────────────────────────────────────────────────────────────

    private void startRealtimeListener() {
        // DB query MUST run on a background thread — Room forbids main-thread access.
        ioExecutor.execute(() -> {
            long lastTs = CacheManager.getInstance(this).getLastSyncTimestamp(chatId);
            // Attach the Firebase listener back on the main thread.
            runOnUiThread(() -> attachFirebaseListener(lastTs));
        });
    }

    private void attachFirebaseListener(long lastTs) {
        com.google.firebase.database.Query query =
                lastTs > 0
                ? messagesRef.orderByChild("timestamp").startAfter((double) lastTs)
                : messagesRef.orderByChild("timestamp").limitToLast(INITIAL_LOAD);

        messageListener = new ChildEventListener() {

            @Override
            public void onChildAdded(DataSnapshot snapshot, String previousChild) {
                Message m = snapshot.getValue(Message.class);
                if (m == null) return;
                m.id = snapshot.getKey();
                saveToRoom(m, false);
                markRead(m);
            }

            @Override
            public void onChildChanged(DataSnapshot snapshot, String previousChild) {
                Message m = snapshot.getValue(Message.class);
                if (m == null) return;
                m.id = snapshot.getKey();
                saveToRoom(m, true); // update existing row (REPLACE strategy)
            }

            @Override
            public void onChildRemoved(DataSnapshot snapshot) {
                String key = snapshot.getKey();
                if (key == null) return;
                ioExecutor.execute(() -> db.messageDao().softDelete(key));
                // Room change triggers PagingSource invalidation automatically
            }

            @Override public void onChildMoved(DataSnapshot s, String p) {}
            @Override public void onCancelled(DatabaseError error) {}
        };

        query.addChildEventListener(messageListener);
    }

    /** Insert or update a Firebase message in Room. Room auto-invalidates PagingSource. */
    private void saveToRoom(Message m, boolean isUpdate) {
        ioExecutor.execute(() -> {
            MessageEntity entity = modelToEntity(m);
            if (isUpdate) {
                db.messageDao().insertMessage(entity); // REPLACE strategy handles update
            } else {
                db.messageDao().insertMessage(entity);
            }
            // No manual invalidate needed — Room does it automatically
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // ENTITY ↔ MODEL CONVERSION
    // ─────────────────────────────────────────────────────────────────────

    static Message entityToModel(MessageEntity e) {
        Message m               = new Message();
        m.id                    = e.id;
        m.messageId             = e.id;
        m.senderId              = e.senderId;
        m.senderName            = e.senderName;
        m.senderPhoto           = e.senderPhoto;  // FIX: status_seen bubble avatar
        m.text                  = e.text;
        m.type                  = e.type;
        m.mediaUrl              = e.mediaUrl;
        // imageUrl = mediaUrl for images (thumbnailUrl is the low-res version)
        m.imageUrl              = "image".equals(e.type) ? e.mediaUrl : null;
        m.thumbnailUrl          = e.thumbnailUrl;
        m.fileName              = e.fileName;
        m.fileSize              = e.fileSize;
        m.duration              = e.duration;
        m.timestamp             = e.timestamp;
        m.status                = e.status;
        m.replyToId             = e.replyToId;
        m.replyToText           = e.replyToText;
        m.replyToSenderName     = e.replyToSenderName;
        m.replyToType           = e.replyToType;
        m.replyToMediaUrl       = e.replyToMediaUrl;
        m.edited                = e.edited;
        m.deleted               = e.deleted;
        m.forwardedFrom         = e.forwardedFrom;
        m.starred               = e.starred;
        m.pinned                = e.pinned;
        m.reelId                = e.reelId;       // FIX: reel_seen bubble
        m.reelThumbUrl          = e.reelThumbUrl; // FIX: reel_seen bubble thumbnail
        m.fontStyle             = e.fontStyle;    // FIX: typing style — Room se load hone par preserve karo
        return m;
    }

    private MessageEntity modelToEntity(Message m) {
        MessageEntity e           = new MessageEntity();
        e.id                      = m.id != null ? m.id : "";
        e.chatId                  = chatId;
        e.senderId                = m.senderId;
        e.senderName              = m.senderName;
        e.senderPhoto             = m.senderPhoto;   // FIX: status_seen bubble avatar
        e.text                    = m.text;
        e.type                    = m.type != null ? m.type : "text";
        e.mediaUrl                = m.mediaUrl != null ? m.mediaUrl : m.imageUrl;
        e.thumbnailUrl            = m.thumbnailUrl;
        e.fileName                = m.fileName;
        e.fileSize                = m.fileSize;
        e.duration                = m.duration;
        e.timestamp               = m.timestamp;
        e.status                  = m.status;
        e.replyToId               = m.replyToId;
        e.replyToText             = m.replyToText;
        e.replyToSenderName       = m.replyToSenderName;
        e.replyToType             = m.replyToType;       // FIX: was missing — thumbnail type not saved
        e.replyToMediaUrl         = m.replyToMediaUrl;   // FIX: was missing — thumbnail URL not saved
        e.edited                  = m.edited;
        e.deleted                 = m.deleted;
        e.forwardedFrom           = m.forwardedFrom;
        e.starred                 = Boolean.TRUE.equals(m.starred);
        e.pinned                  = Boolean.TRUE.equals(m.pinned);
        e.reelId                  = m.reelId;           // FIX: reel_seen bubble
        e.reelThumbUrl            = m.reelThumbUrl;      // FIX: reel_seen bubble thumbnail
        e.fontStyle               = m.fontStyle;         // FIX: typing style preserve
        e.syncedAt                = System.currentTimeMillis();
        return e;
    }

    // ─────────────────────────────────────────────────────────────────────
    // INPUT BAR
    // ─────────────────────────────────────────────────────────────────────

    private void setupInputBar() {
        binding.etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                boolean hasText = s.toString().trim().length() > 0;
                binding.btnSend.setVisibility(hasText ? View.VISIBLE : View.GONE);
                binding.btnMic.setVisibility(hasText ? View.GONE : View.VISIBLE);

                // Typing debounce: type karte waqt "typing" set karo,
                // 2 sec baad koi input nahi toh auto-clear
                if (hasText) {
                    setOurTypingStatus(true);
                    typingHandler.removeCallbacks(stopTypingRunnable);
                    typingHandler.postDelayed(stopTypingRunnable, 2000);
                } else {
                    typingHandler.removeCallbacks(stopTypingRunnable);
                    setOurTypingStatus(false);
                }
            }
        });

        binding.btnSend.setOnClickListener(v -> sendTextMessage());
        binding.btnMic.setOnClickListener(v -> toggleRecording());
        binding.btnAttach.setOnClickListener(v -> showAttachSheet());
        binding.btnCamera.setOnClickListener(v -> launchCamera());

        if (binding.btnCancelReply != null)
            binding.btnCancelReply.setOnClickListener(v -> clearReply());
    }

    private void sendTextMessage() {
        String text = binding.etMessage.getText().toString().trim();
        if (text.isEmpty()) return;
        binding.etMessage.setText("");
        // setText ke baad post() se style apply karo — setText typeface disturb karta hai
        binding.etMessage.post(() ->
            com.callx.app.utils.TypingStyleManager.get(this).applyToInput(binding.etMessage)
        );
        clearOurTypingStatus();
        // v18 IMPROVEMENT 2: Message bheja — draft clear karo
        Executors.newSingleThreadExecutor().execute(() -> {
            if (db != null && chatId != null) db.chatDao().saveDraft(chatId, "");
        });
        Message m = buildOutgoing();
        m.type = "text";
        m.fontStyle = com.callx.app.utils.TypingStyleManager.get(this).getCurrentStyle();
        // Samsung Script style — text ko Unicode Mathematical Script mein convert karo
        if (m.fontStyle == com.callx.app.utils.TypingStyleManager.STYLE_SAMSUNG_SCRIPT) {
            text = com.callx.app.utils.UnicodeStyler.toScript(text);
        }
        m.text = text;
        pushMessage(m, text);
        clearReply();
    }

    private Message buildOutgoing() {
        Message m    = new Message();
        m.senderId   = currentUid;
        m.senderName = currentName;
        m.timestamp  = System.currentTimeMillis();
        m.status     = "sent";
        if (replyingTo != null) {
            ReplyDataMapper.applyReplyFields(m, replyingTo, currentUid);
        }
        return m;
    }

    // ─────────────────────────────────────────────────────────────────────
    // v15 FIX 2: Local-first message send — pehle Room save (status=pending),
    //            phir Firebase push. Offline hone par message Room mein rehta
    //            hai aur online hone par retryPendingMessages() bhejta hai.
    // ─────────────────────────────────────────────────────────────────────

    private void pushMessage(Message m, String previewText) {
        // Firebase push key — offline hone par bhi getKey() kaam karta hai
        String key = messagesRef.push().getKey();
        if (key == null) return;
        m.id = key;

        // Step 1: Room mein turant save karo (status = pending)
        MessageEntity entity = messageToEntity(m, "pending");
        Executors.newSingleThreadExecutor().execute(() ->
            AppDatabase.getInstance(getApplicationContext()).messageDao().insertMessage(entity));

        if (isOnline()) {
            // Step 2a: Online → Firebase push + status update
            firebasePushMessage(m, key, previewText);
        } else {
            // Step 2b: Offline → pending rehega, retry online hone par
            Toast.makeText(this, "No connection — message queued", Toast.LENGTH_SHORT).show();
        }
    }

    /** Firebase par actual push karo aur Room status "sent" karo. */
    private void firebasePushMessage(Message m, String key, String previewText) {
        messagesRef.child(key).setValue(m)
            .addOnSuccessListener(unused -> {
                // Room mein status update karo: pending → sent
                Executors.newSingleThreadExecutor().execute(() ->
                    AppDatabase.getInstance(getApplicationContext())
                        .messageDao().updateStatus(key, "sent"));
            })
            .addOnFailureListener(e -> {
                // Firebase reject kiya — pending rakho, retry baad mein
            });

        long ts = m.timestamp;
        Map<String, Object> myUpd = new HashMap<>();
        myUpd.put("lastMessage", previewText);
        myUpd.put("lastTs", ts);
        FirebaseUtils.getContactsRef(currentUid).child(partnerUid).updateChildren(myUpd);

        Map<String, Object> theirUpd = new HashMap<>();
        theirUpd.put("lastMessage", previewText);
        theirUpd.put("lastTs", ts);
        FirebaseUtils.getContactsRef(partnerUid).child(currentUid).updateChildren(theirUpd);
        FirebaseUtils.getContactsRef(partnerUid).child(currentUid).child("unread")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        Long cur = s.getValue(Long.class);
                        s.getRef().setValue((cur != null ? cur : 0) + 1);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });

        if (!isMuted)
            PushNotify.notifyMessage(partnerUid, currentUid, currentName,
                    chatId, m.id, previewText,
                    m.type != null ? m.type : "text",
                    m.mediaUrl != null ? m.mediaUrl : "");
    }

    /** Message model → MessageEntity converter. */
    private MessageEntity messageToEntity(Message m, String status) {
        MessageEntity e = new MessageEntity();
        e.id             = m.id;
        e.chatId         = chatId;
        e.senderId       = m.senderId;
        e.senderName     = m.senderName;
        e.text           = m.text;
        e.type           = m.type;
        e.mediaUrl       = m.mediaUrl;
        e.thumbnailUrl   = m.thumbnailUrl;
        e.fileName       = m.fileName;
        e.fileSize       = m.fileSize;
        e.duration       = m.duration;
        e.timestamp      = m.timestamp;
        e.status         = status;
        e.replyToId      = m.replyToId;
        e.replyToText    = m.replyToText;
        e.replyToSenderName = m.replyToSenderName;
        e.replyToType       = m.replyToType;
        e.replyToMediaUrl   = m.replyToMediaUrl;
        e.isGroup        = false;
        e.syncedAt       = System.currentTimeMillis();
        e.fontStyle      = m.fontStyle;
        return e;
    }

    // v15 FIX 3: Send button offline hone par disable karo
    private void updateSendButtonState(boolean online) {
        if (binding == null) return;
        binding.btnSend.setEnabled(online);
        binding.btnSend.setAlpha(online ? 1.0f : 0.4f);
        binding.btnMic.setEnabled(online);
        binding.btnMic.setAlpha(online ? 1.0f : 0.4f);
    }

    // v15 FIX 2: Online hone par pending messages retry karo
    private void retryPendingMessages() {
        Executors.newSingleThreadExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            // Sirf is chat ke pending messages dhundho
            List<MessageEntity> pending = db.messageDao().getPendingMessages(chatId);
            if (pending == null || pending.isEmpty()) return;
            for (MessageEntity pe : pending) {
                Message m = new Message();
                m.id         = pe.id;
                m.senderId   = pe.senderId;
                m.senderName = pe.senderName;
                m.text       = pe.text;
                m.type       = pe.type;
                m.mediaUrl   = pe.mediaUrl;
                m.thumbnailUrl = pe.thumbnailUrl;
                m.fileName   = pe.fileName;
                m.fileSize   = pe.fileSize;
                m.duration   = pe.duration;
                m.timestamp  = pe.timestamp;
                m.status     = "sent";
                m.replyToId  = pe.replyToId;
                m.replyToText = pe.replyToText;
                m.replyToSenderName = pe.replyToSenderName;
                m.fontStyle  = pe.fontStyle;
                String preview = pe.text != null ? pe.text : "[" + pe.type + "]";
                runOnUiThread(() -> firebasePushMessage(m, pe.id, preview));
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // REPLY (N1)
    // ─────────────────────────────────────────────────────────────────────

    private void startReply(Message m) {
        // Use ReplyController for undo support
        if (replyController != null) {
            replyController.onSwipeReply(m);
        } else {
            activateReplyDirect(m);
        }
    }

    /** Directly activates reply (called by ReplyController after undo window). */
    private void activateReplyDirect(Message m) {
        replyingTo = m;
        if (binding.llReplyBar == null) return;

        // Sender name — 'You' for self messages
        String senderName = (currentUid != null && currentUid.equals(m.senderId))
                ? "You" : (m.senderName != null ? m.senderName : "");

        // Content preview
        String preview;
        if (Boolean.TRUE.equals(m.deleted)) {
            preview = "🚫  Original message unavailable";
        } else if (m.text != null && !m.text.isEmpty()) {
            preview = m.text;
        } else {
            preview = buildTypePreviewLocal(m);
        }

        if (binding.tvReplyBarName != null) binding.tvReplyBarName.setText(senderName);
        if (binding.tvReplyBarText != null) binding.tvReplyBarText.setText(preview);

        // Media thumbnail
        if (binding.ivReplyBarThumb != null) {
            String thumbUrl = null;
            if ("image".equals(m.type)) thumbUrl = m.mediaUrl;
            else if ("video".equals(m.type)) thumbUrl = m.thumbnailUrl;

            if (thumbUrl != null && !thumbUrl.isEmpty()) {
                binding.ivReplyBarThumb.setVisibility(View.VISIBLE);
                Glide.with(this).load(thumbUrl).centerCrop().into(binding.ivReplyBarThumb);
            } else {
                binding.ivReplyBarThumb.setVisibility(View.GONE);
            }
        }

        // Animate slide-up
        binding.llReplyBar.setVisibility(View.VISIBLE);
        binding.llReplyBar.setAlpha(0f);
        binding.llReplyBar.setTranslationY(40f);
        binding.llReplyBar.animate().alpha(1f).translationY(0f).setDuration(200).start();

        binding.etMessage.requestFocus();

        // Track analytics
        ReplyAnalyticsTracker.get().onSwipeTriggered();
    }

    private String buildTypePreviewLocal(Message m) {
        if (m.type == null) return "[message]";
        switch (m.type) {
            case "image":  return "📷 Photo";
            case "video":  return "🎬 Video";
            case "audio":  return "🎤 Voice message";
            case "file":   return "📎 " + (m.fileName != null ? m.fileName : "File");
            default:       return "[" + m.type + "]";
        }
    }

    private void clearReply() {
        replyingTo = null;
        if (replyController != null) replyController.cancel();
        if (binding.llReplyBar == null) return;
        binding.llReplyBar.animate()
                .alpha(0f).translationY(20f).setDuration(150)
                .withEndAction(() -> {
                    binding.llReplyBar.setVisibility(View.GONE);
                    binding.llReplyBar.setAlpha(1f);
                    binding.llReplyBar.setTranslationY(0f);
                })
                .start();
        if (binding.ivReplyBarThumb != null) binding.ivReplyBarThumb.setVisibility(View.GONE);
    }

    // ─────────────────────────────────────────────────────────────────────
    // COPY (N4)
    // ─────────────────────────────────────────────────────────────────────

    private void copyText(Message m) {
        if (m.text == null || m.text.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("message", m.text));
            Toast.makeText(this, "Message copied", Toast.LENGTH_SHORT).show();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // STAR
    // ─────────────────────────────────────────────────────────────────────

    private void toggleStar(Message m) {
        boolean nowStarred = !Boolean.TRUE.equals(m.starred);
        ioExecutor.execute(() ->
                db.messageDao().updateStarred(m.id, nowStarred));
        messagesRef.child(m.id).child("starred").setValue(nowStarred);
    }

    // ─────────────────────────────────────────────────────────────────────
    // REACTION
    // ─────────────────────────────────────────────────────────────────────

    private void sendReaction(Message m, String emoji) {
        if (m.id == null) return;
        messagesRef.child(m.id)
                .child("reactions")
                .child(currentUid)
                .setValue(emoji);
    }

    // ─────────────────────────────────────────────────────────────────────
    // DELETE
    // ─────────────────────────────────────────────────────────────────────

    private void confirmDeleteMessage(Message m) {
        new AlertDialog.Builder(this)
                .setTitle("Delete message")
                .setMessage("Delete for everyone?")
                .setPositiveButton("Delete", (d, w) -> {
                    messagesRef.child(m.id).child("deleted").setValue(true);
                    messagesRef.child(m.id).child("text").setValue("");
                    ioExecutor.execute(() -> db.messageDao().softDelete(m.id));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ─────────────────────────────────────────────────────────────────────
    // FORWARD — single message (from bottom sheet)
    // ─────────────────────────────────────────────────────────────────────

    private void forwardMessage(Message m) {
        Intent i = new Intent().setClassName(this, "com.callx.app.activities.ContactsActivity");
        i.putExtra("forwardText",     m.text);
        i.putExtra("forwardType",     m.type != null ? m.type : "text");
        i.putExtra("forwardMedia",    m.mediaUrl);
        i.putExtra("forwardFileName", m.fileName);
        startActivity(i);
    }

    // MULTI-SELECT FORWARD — multiple messages to multiple contacts
    // ─────────────────────────────────────────────────────────────────────

    private void forwardSelectedMessages() {
        java.util.List<Message> selected = pagingAdapter.getSelectedMessages();
        if (selected.isEmpty()) {
            android.widget.Toast.makeText(this, "Koi message select nahi", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        java.util.ArrayList<String> texts     = new java.util.ArrayList<>();
        java.util.ArrayList<String> types     = new java.util.ArrayList<>();
        java.util.ArrayList<String> medias    = new java.util.ArrayList<>();
        java.util.ArrayList<String> fileNames = new java.util.ArrayList<>();

        // Timestamp ke order mein sort karo
        selected.sort((a, b) -> Long.compare(a.timestamp, b.timestamp));

        for (Message m : selected) {
            texts.add(m.text != null ? m.text : "");
            types.add(m.type != null ? m.type : "text");
            medias.add(m.mediaUrl != null ? m.mediaUrl : "");
            fileNames.add(m.fileName != null ? m.fileName : "");
        }

        Intent i = new Intent().setClassName(this, "com.callx.app.activities.ContactsActivity");
        i.putStringArrayListExtra("forwardTexts",     texts);
        i.putStringArrayListExtra("forwardTypes",     types);
        i.putStringArrayListExtra("forwardMedias",    medias);
        i.putStringArrayListExtra("forwardFileNames", fileNames);
        startActivity(i);
        pagingAdapter.exitMultiSelectMode();
    }

    // MULTI-SELECT BAR — toolbar ke neeche forward/cancel bar
    // ─────────────────────────────────────────────────────────────────────

    // ── WhatsApp-style selection toolbar (XML: ll_selection_toolbar) ──────
    private boolean selectionToolbarSetup = false;

    private void setupSelectionToolbar() {
        if (selectionToolbarSetup) return;
        selectionToolbarSetup = true;

        // Close / Cancel selection
        android.view.View btnClose = binding.getRoot().findViewById(
                com.callx.app.chat.R.id.btn_selection_close);
        if (btnClose != null) btnClose.setOnClickListener(v -> {
            pagingAdapter.exitMultiSelectMode();
            hideMultiSelectBar();
        });

        // Forward button
        android.view.View btnFwd = binding.getRoot().findViewById(
                com.callx.app.chat.R.id.btn_selection_forward);
        if (btnFwd != null) btnFwd.setOnClickListener(v -> forwardSelectedMessages());

        // Delete button
        android.view.View btnDel = binding.getRoot().findViewById(
                com.callx.app.chat.R.id.btn_selection_delete);
        if (btnDel != null) btnDel.setOnClickListener(v -> {
            java.util.List<Message> sel = pagingAdapter.getSelectedMessages();
            if (!sel.isEmpty()) {
                // Delete first selected (extend for bulk later)
                confirmDeleteMessage(sel.get(0));
                pagingAdapter.exitMultiSelectMode();
                hideMultiSelectBar();
            }
        });

        // Star button
        android.view.View btnStar = binding.getRoot().findViewById(
                com.callx.app.chat.R.id.btn_selection_star);
        if (btnStar != null) btnStar.setOnClickListener(v -> {
            java.util.List<Message> sel = pagingAdapter.getSelectedMessages();
            for (Message m : sel) toggleStar(m);
            pagingAdapter.exitMultiSelectMode();
            hideMultiSelectBar();
        });

        // Reply button — only first selected
        android.view.View btnReply = binding.getRoot().findViewById(
                com.callx.app.chat.R.id.btn_selection_reply);
        if (btnReply != null) btnReply.setOnClickListener(v -> {
            java.util.List<Message> sel = pagingAdapter.getSelectedMessages();
            if (!sel.isEmpty()) startReply(sel.get(0));
            pagingAdapter.exitMultiSelectMode();
            hideMultiSelectBar();
        });

        // Info — only for single select
        android.view.View btnInfo = binding.getRoot().findViewById(
                com.callx.app.chat.R.id.btn_selection_info);
        if (btnInfo != null) btnInfo.setOnClickListener(v -> {
            java.util.List<Message> sel = pagingAdapter.getSelectedMessages();
            if (sel.size() == 1) {
                // reuse existing info action
                if (pagingAdapter.getActionListener() != null)
                    pagingAdapter.getActionListener().onForward(sel.get(0)); // placeholder — info
            }
        });
    }

    private void showMultiSelectBar(int count) {
        setupSelectionToolbar();
        // Hide normal toolbar elements, show selection toolbar
        binding.toolbar.setVisibility(android.view.View.GONE);
        android.view.View selBar = binding.getRoot().findViewById(
                com.callx.app.chat.R.id.ll_selection_toolbar);
        if (selBar != null) selBar.setVisibility(android.view.View.VISIBLE);
        // Update count
        android.widget.TextView tvCount = binding.getRoot().findViewById(
                com.callx.app.chat.R.id.tv_selection_count);
        if (tvCount != null) tvCount.setText(String.valueOf(count));
        // Info button: only visible when exactly 1 selected
        android.view.View btnInfo = binding.getRoot().findViewById(
                com.callx.app.chat.R.id.btn_selection_info);
        if (btnInfo != null) btnInfo.setVisibility(count == 1 ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private void hideMultiSelectBar() {
        binding.toolbar.setVisibility(android.view.View.VISIBLE);
        android.view.View selBar = binding.getRoot().findViewById(
                com.callx.app.chat.R.id.ll_selection_toolbar);
        if (selBar != null) selBar.setVisibility(android.view.View.GONE);
    }

    // ─────────────────────────────────────────────────────────────────────
    // MARK READ
    // ─────────────────────────────────────────────────────────────────────

    private void markMessagesRead() {
        // v18 IMPROVEMENT 4: Offline mein markRead fail hota tha silently.
        // Ab Room mein unread=0 turant set karo, aur online hone par Firebase push karo.
        ioExecutor.execute(() -> {
            if (db != null && chatId != null) {
                db.chatDao().updateUnread(chatId, 0);  // Room badge turant reset
            }
        });
        if (isOnline()) {
            FirebaseUtils.getContactsRef(currentUid)
                    .child(partnerUid).child("unread").setValue(0);
        } else {
            // Offline: queue karo — SyncWorker online hone par push karega
            ioExecutor.execute(() -> {
                if (db != null && chatId != null) {
                    db.chatDao().queueMarkRead(chatId);
                }
            });
        }
    }

    private void markRead(Message m) {
        if (m == null || m.id == null) return;
        if (!currentUid.equals(m.senderId)) {
            messagesRef.child(m.id).child("status").setValue("read");
            ioExecutor.execute(() -> db.messageDao().updateStatus(m.id, "read"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // TYPING INDICATOR
    // ─────────────────────────────────────────────────────────────────────

    private void setOurTypingStatus(boolean typing) {
        FirebaseUtils.db().getReference("typing")
                .child(chatId).child(currentUid).setValue(typing);
    }

    private void clearOurTypingStatus() {
        FirebaseUtils.db().getReference("typing")
                .child(chatId).child(currentUid).setValue(false);
    }

    private void watchTyping() {
        typingListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                boolean typing = false;
                for (DataSnapshot child : s.getChildren()) {
                    if (child.getKey() != null && !child.getKey().equals(currentUid)
                            && Boolean.TRUE.equals(child.getValue(Boolean.class))) {
                        typing = true;
                        break;
                    }
                }
                if (binding.tvTyping == null || binding.tvStatus == null) return;
                if (typing) {
                    // typing... show karo, status hide karo
                    binding.tvTyping.setVisibility(View.VISIBLE);
                    binding.tvStatus.setVisibility(View.GONE);
                } else {
                    // typing band — status wapas dikhao
                    binding.tvTyping.setVisibility(View.GONE);
                    binding.tvStatus.setVisibility(
                            binding.tvStatus.getText().length() > 0 ? View.VISIBLE : View.GONE);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.db().getReference("typing").child(chatId)
                .addValueEventListener(typingListener);
    }

    // ─────────────────────────────────────────────────────────────────────
    // ONLINE / LAST-SEEN STATUS
    // ─────────────────────────────────────────────────────────────────────

    private void watchPartnerStatus() {
        if (partnerUid == null || partnerUid.isEmpty()) return;
        onlineListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                Boolean online  = s.child("online").getValue(Boolean.class);
                Long lastSeen   = s.child("lastSeen").getValue(Long.class);
                if (binding.tvStatus == null) return;

                String statusText;
                if (Boolean.TRUE.equals(online)) {
                    statusText = "online";
                } else if (lastSeen != null) {
                    // Smart formatting: aaj ka ho toh sirf time, warna date bhi
                    java.util.Calendar now = java.util.Calendar.getInstance();
                    java.util.Calendar then = java.util.Calendar.getInstance();
                    then.setTimeInMillis(lastSeen);
                    boolean isToday = now.get(java.util.Calendar.DATE) == then.get(java.util.Calendar.DATE)
                            && now.get(java.util.Calendar.MONTH) == then.get(java.util.Calendar.MONTH)
                            && now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR);
                    java.text.SimpleDateFormat sdf = isToday
                            ? new java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault())
                            : new java.text.SimpleDateFormat("dd MMM, hh:mm a", java.util.Locale.getDefault());
                    statusText = "last seen " + sdf.format(new java.util.Date(lastSeen));
                } else {
                    statusText = "";
                }

                binding.tvStatus.setText(statusText);

                // Typing indicator already visible ho toh status hide rakho
                boolean typingVisible = binding.tvTyping != null
                        && binding.tvTyping.getVisibility() == View.VISIBLE;
                binding.tvStatus.setVisibility(
                        (!typingVisible && statusText.length() > 0) ? View.VISIBLE : View.GONE);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getUserRef(partnerUid).addValueEventListener(onlineListener);
    }

    // ─────────────────────────────────────────────────────────────────────
    // MUTE (N8)
    // ─────────────────────────────────────────────────────────────────────

    private void watchMute() {
        if (currentUid == null || currentUid.isEmpty() || partnerUid == null || partnerUid.isEmpty()) return;
        FirebaseUtils.db().getReference("muted")
                .child(currentUid).child(partnerUid)
                .addValueEventListener(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        isMuted = Boolean.TRUE.equals(s.getValue(Boolean.class));
                        invalidateOptionsMenu();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
    }

    private void toggleMute() {
        FirebaseUtils.db().getReference("muted")
                .child(currentUid).child(partnerUid)
                .setValue(!isMuted);
    }

    // ─────────────────────────────────────────────────────────────────────
    // BLOCK (N8)
    // ─────────────────────────────────────────────────────────────────────

    private void watchBlock() {
        if (currentUid == null || currentUid.isEmpty() || partnerUid == null || partnerUid.isEmpty()) return;
        blockListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                isBlocked = Boolean.TRUE.equals(s.getValue(Boolean.class));
                applyBlockUi();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.db().getReference("blocked")
                .child(currentUid).child(partnerUid)
                .addValueEventListener(blockListener);
    }

    private void applyBlockUi() {
        binding.etMessage.setEnabled(!isBlocked);
        if (isBlocked) {
            binding.etMessage.setHint("You have blocked " + partnerName);
            binding.btnSend.setVisibility(View.GONE);
            binding.btnMic.setVisibility(View.GONE);
        } else {
            binding.etMessage.setHint(getString(R.string.hint_message));
            binding.btnMic.setVisibility(View.VISIBLE);
        }
    }

    private void confirmBlockUser() {
        String label = isBlocked ? "Unblock" : "Block";
        new AlertDialog.Builder(this)
                .setTitle(label + " " + partnerName + "?")
                .setPositiveButton(label, (d, w) -> {
                    FirebaseUtils.db().getReference("blocked")
                            .child(currentUid).child(partnerUid)
                            .setValue(!isBlocked);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ─────────────────────────────────────────────────────────────────────
    // PERMA-BLOCK
    // ─────────────────────────────────────────────────────────────────────

    private void watchPartnerPermaBlock() {
        if (partnerUid == null || partnerUid.isEmpty() || currentUid == null || currentUid.isEmpty()) return;
        permaBlockListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                partnerPermaBlockedMe = Boolean.TRUE.equals(s.getValue(Boolean.class));
                applyPermaBlockUi();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.db().getReference("permaBlocked")
                .child(partnerUid).child(currentUid)
                .addValueEventListener(permaBlockListener);
    }

    private void applyPermaBlockUi() {
        if (!partnerPermaBlockedMe) {
            binding.etMessage.setEnabled(true);
            binding.etMessage.setHint(getString(R.string.hint_message));
            return;
        }
        binding.etMessage.setEnabled(false);
        binding.etMessage.setHint(partnerName + " has blocked you");
        binding.btnSend.setVisibility(View.GONE);
        binding.btnMic.setVisibility(View.GONE);
        Snackbar.make(binding.getRoot(),
                        partnerName + " has permanently blocked you",
                        Snackbar.LENGTH_INDEFINITE)
                .setAction("Send request", v -> openSpecialRequestDialog())
                .show();
    }

    private void openSpecialRequestDialog() {
        EditText et = new EditText(this);
        et.setHint("Write your message (e.g. Sorry, please unblock me)");
        et.setMinLines(2);
        et.setMaxLines(4);
        int p = dp(16);
        et.setPadding(p, p, p, p);
        new AlertDialog.Builder(this)
                .setTitle("Send special request")
                .setMessage("Send a one-time unblock request to " + partnerName + ".")
                .setView(et)
                .setPositiveButton("Send", (d, w) -> {
                    String txt = et.getText().toString().trim();
                    if (txt.isEmpty()) txt = "Please unblock me";
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("text",     txt);
                    entry.put("ts",       System.currentTimeMillis());
                    entry.put("fromName", currentName);
                    entry.put("fromUid",  currentUid);
                    FirebaseUtils.db().getReference("specialRequests")
                            .child(partnerUid).child(currentUid).setValue(entry);
                    PushNotify.notifySpecialRequest(
                            partnerUid, currentUid, currentName, txt);
                    Toast.makeText(this, "Request sent", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ─────────────────────────────────────────────────────────────────────
    // PINNED MESSAGE (N8)
    // ─────────────────────────────────────────────────────────────────────

    private void watchPinnedMessage() {
        FirebaseUtils.db().getReference("pinnedMessages").child(chatId)
                .addValueEventListener(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        pinnedMsgId   = s.child("id").getValue(String.class);
                        pinnedMsgText = s.child("text").getValue(String.class);
                        if (binding.llPinnedBanner == null) return;
                        if (pinnedMsgId != null) {
                            binding.llPinnedBanner.setVisibility(View.VISIBLE);
                            if (binding.tvPinnedPreview != null)
                                binding.tvPinnedPreview.setText(
                                        pinnedMsgText != null ? pinnedMsgText : "Pinned message");
                            if (binding.btnUnpin != null)
                                binding.btnUnpin.setOnClickListener(v -> unpinMessage());
                        } else {
                            binding.llPinnedBanner.setVisibility(View.GONE);
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
    }

    private void unpinMessage() {
        FirebaseUtils.db().getReference("pinnedMessages").child(chatId).removeValue();
    }

    // ─────────────────────────────────────────────────────────────────────
    // SEARCH (N6)
    // ─────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────
    // IN-CHAT SEARCH — prev/next navigation + result count
    // ─────────────────────────────────────────────────────────────────────

    private final java.util.List<Integer> searchMatchPositions = new java.util.ArrayList<>();
    private int searchCurrentIndex = -1;

    /** Open WhatsApp-style Media, Links & Docs screen for this chat */
    private void openAllMediaLinksDocs() {
        try {
            // AllMediaLinksDocsActivity app module mein hai, isliye Class.forName se intent banate hain
            Class<?> cls = Class.forName("com.callx.app.activities.AllMediaLinksDocsActivity");
            Intent i = new Intent(this, cls);
            i.putExtra("chatId",      chatId);
            i.putExtra("partnerName", partnerName);
            i.putExtra("isGroup",     false);
            startActivity(i);
        } catch (ClassNotFoundException e) {
            android.util.Log.e("ChatActivity", "AllMediaLinksDocsActivity not found", e);
        }
    }

    private void openSearch() {
        if (binding.llSearchBar == null) return;
        binding.llSearchBar.setVisibility(View.VISIBLE);
        if (binding.etSearch != null) {
            binding.etSearch.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                    getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(binding.etSearch, 0);

            binding.etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void afterTextChanged(Editable s) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                    String query = s.toString().trim();
                    if (query.length() < 2) {
                        searchMatchPositions.clear();
                        searchCurrentIndex = -1;
                        updateSearchUI();
                        return;
                    }
                    runSearchQuery(query);
                }
            });

            binding.etSearch.setOnEditorActionListener((v, actionId, e) -> {
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    navigateSearch(true); // next result
                    return true;
                }
                return false;
            });
        }

        // Prev / Next buttons
        if (binding.btnSearchPrev != null)
            binding.btnSearchPrev.setOnClickListener(v -> navigateSearch(false));
        if (binding.btnSearchNext != null)
            binding.btnSearchNext.setOnClickListener(v -> navigateSearch(true));

        if (binding.btnCloseSearch != null)
            binding.btnCloseSearch.setOnClickListener(v -> closeSearch());
    }

    private void runSearchQuery(String query) {
        ioExecutor.execute(() -> {
            // Load all messages for this chat from Room (up to 2000)
            java.util.List<MessageEntity> all =
                db.messageDao().getMessagesPaged(chatId, 2000, 0);
            java.util.List<Integer> matches = new java.util.ArrayList<>();
            String lq = query.toLowerCase(java.util.Locale.getDefault());
            for (int i = 0; i < all.size(); i++) {
                MessageEntity me = all.get(i);
                if (me.text != null &&
                    me.text.toLowerCase(java.util.Locale.getDefault()).contains(lq)) {
                    matches.add(i);
                }
            }
            runOnUiThread(() -> {
                searchMatchPositions.clear();
                searchMatchPositions.addAll(matches);
                searchCurrentIndex = matches.isEmpty() ? -1 : matches.size() - 1;
                updateSearchUI();
                if (!matches.isEmpty())
                    binding.rvMessages.scrollToPosition(
                        searchMatchPositions.get(searchCurrentIndex));
            });
        });
    }

    private void navigateSearch(boolean forward) {
        if (searchMatchPositions.isEmpty()) return;
        if (forward) {
            searchCurrentIndex = (searchCurrentIndex + 1) % searchMatchPositions.size();
        } else {
            searchCurrentIndex = (searchCurrentIndex - 1 + searchMatchPositions.size())
                                  % searchMatchPositions.size();
        }
        updateSearchUI();
        binding.rvMessages.scrollToPosition(
            searchMatchPositions.get(searchCurrentIndex));
    }

    private void updateSearchUI() {
        if (binding.tvSearchCount == null) return;
        if (searchMatchPositions.isEmpty()) {
            binding.tvSearchCount.setVisibility(View.GONE);
            if (binding.btnSearchPrev != null) binding.btnSearchPrev.setVisibility(View.GONE);
            if (binding.btnSearchNext != null) binding.btnSearchNext.setVisibility(View.GONE);
        } else {
            String label = (searchCurrentIndex + 1) + " / " + searchMatchPositions.size();
            binding.tvSearchCount.setText(label);
            binding.tvSearchCount.setVisibility(View.VISIBLE);
            if (binding.btnSearchPrev != null) binding.btnSearchPrev.setVisibility(View.VISIBLE);
            if (binding.btnSearchNext != null) binding.btnSearchNext.setVisibility(View.VISIBLE);
        }
    }

    private void closeSearch() {
        if (binding.llSearchBar != null)
            binding.llSearchBar.setVisibility(View.GONE);
        if (binding.etSearch != null) binding.etSearch.setText("");
        searchMatchPositions.clear();
        searchCurrentIndex = -1;
        updateSearchUI();
    }

    // ─────────────────────────────────────────────────────────────────────
    // AVATAR ZOOM (N3)
    // ─────────────────────────────────────────────────────────────────────

    private void openEditProfile() {
        // ProfileActivity is in :app module — use reflection to avoid cross-module dep
        try {
            Class<?> cls = Class.forName("com.callx.app.activities.ProfileActivity");
            startActivity(new Intent(this, cls));
        } catch (ClassNotFoundException e) {
            android.widget.Toast.makeText(this, "Edit Profile unavailable", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void openAvatarZoom() {
        // Opens UserProfileActivity — from chat header avatar OR 3-dot View Profile
        if (partnerUid == null || partnerUid.isEmpty()) return;
        Intent intent = new Intent()
            .setClassName(this, "com.callx.app.activities.UserProfileActivity");
        intent.putExtra("uid",    partnerUid);
        intent.putExtra("name",   partnerName  != null ? partnerName  : "");
        intent.putExtra("photo",  partnerPhoto != null ? partnerPhoto : "");
        intent.putExtra("chatId", chatId       != null ? chatId       : "");
        startActivity(intent);
    }

    // ─────────────────────────────────────────────────────────────────────
    // CAMERA (N5)
    // ─────────────────────────────────────────────────────────────────────

    private void launchCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.Media.DISPLAY_NAME,
                "callx_" + System.currentTimeMillis() + ".jpg");
        cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        cameraOutputUri = getContentResolver()
                .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
        if (cameraOutputUri != null) cameraCapturer.launch(cameraOutputUri);
    }

    // ─────────────────────────────────────────────────────────────────────
    // MEDIA UPLOAD
    // ─────────────────────────────────────────────────────────────────────

    private void uploadAndSend(Uri uri, String msgType,
                               String resourceType, String fileName) {
        // OFFLINE FIX: Media upload needs internet — check before starting
        if (!isOnline()) {
            Toast.makeText(this,
                "No connection — media send kar'ne ke liye internet chahiye",
                Toast.LENGTH_LONG).show();
            return;
        }

        binding.uploadProgress.setVisibility(View.VISIBLE);

        // IMAGE: compress first (5MB → ~400KB WebP), then upload
        if ("image".equals(msgType)) {
            ImageCompressor.compress(this, uri, new ImageCompressor.Callback() {
                @Override
                public void onSuccess(ImageCompressor.Result result) {
                    // Upload full image to Cloudinary; store thumb URL separately
                    Uri fullUri  = Uri.fromFile(result.fullFile);
                    Uri thumbUri = Uri.fromFile(result.thumbFile);

                    // First upload thumbnail (fast, ~30KB)
                    CloudinaryUploader.upload(ChatActivity.this, thumbUri,
                        "callx/thumb", "image",
                        new CloudinaryUploader.UploadCallback() {
                            @Override
                            public void onSuccess(CloudinaryUploader.Result thumbResult) {
                                String thumbUrl = thumbResult.secureUrl;
                                // Now upload full image
                                CloudinaryUploader.upload(ChatActivity.this, fullUri,
                                    "callx/image", "image",
                                    new CloudinaryUploader.UploadCallback() {
                                        @Override
                                        public void onSuccess(CloudinaryUploader.Result fullResult) {
                                            binding.uploadProgress.setVisibility(View.GONE);
                                            // Cleanup temp files
                                            result.thumbFile.delete();
                                            result.fullFile.delete();
                                            // Build message with BOTH urls
                                            Message m     = buildOutgoing();
                                            m.type        = "image";
                                            m.mediaUrl    = fullResult.secureUrl;
                                            m.imageUrl    = fullResult.secureUrl;
                                            m.thumbnailUrl = thumbUrl;
                                            m.fileSize    = fullResult.bytes;
                                            pushMessage(m, "📷 Photo");
                                            clearReply();
                                        }
                                        @Override
                                        public void onError(String err) {
                                            binding.uploadProgress.setVisibility(View.GONE);
                                            result.thumbFile.delete();
                                            result.fullFile.delete();
                                            Toast.makeText(ChatActivity.this,
                                                err != null ? err : "Upload failed",
                                                Toast.LENGTH_LONG).show();
                                        }
                                    });
                            }
                            @Override
                            public void onError(String err) {
                                // Thumb failed — still upload full (no thumb preview)
                                CloudinaryUploader.upload(ChatActivity.this, fullUri,
                                    "callx/image", "image",
                                    new CloudinaryUploader.UploadCallback() {
                                        @Override
                                        public void onSuccess(CloudinaryUploader.Result r) {
                                            binding.uploadProgress.setVisibility(View.GONE);
                                            result.thumbFile.delete();
                                            result.fullFile.delete();
                                            Message m  = buildOutgoing();
                                            m.type     = "image";
                                            m.mediaUrl = r.secureUrl;
                                            m.imageUrl = r.secureUrl;
                                            pushMessage(m, "📷 Photo");
                                            clearReply();
                                        }
                                        @Override
                                        public void onError(String e) {
                                            binding.uploadProgress.setVisibility(View.GONE);
                                            result.thumbFile.delete();
                                            result.fullFile.delete();
                                            Toast.makeText(ChatActivity.this,
                                                "Upload failed", Toast.LENGTH_LONG).show();
                                        }
                                    });
                            }
                        });
                }
                @Override
                public void onError(Exception e) {
                    // Compression failed — fallback to direct upload
                    android.util.Log.w("ChatActivity", "Compression failed, uploading original", e);
                    doUpload(uri, msgType, resourceType, fileName);
                }
            });
            return;
        }

        // VIDEO: compress → dual Cloudinary upload (thumb + video)
        if ("video".equals(msgType)) {
            binding.uploadProgress.setVisibility(View.VISIBLE);
            binding.uploadProgress.setIndeterminate(false);
            binding.uploadProgress.setMax(100);
            binding.uploadProgress.setProgress(0);

            com.callx.app.utils.VideoCompressor.compress(
                    this, uri, new com.callx.app.utils.VideoCompressor.Callback() {

                @Override
                public void onProgress(int percent) {
                    binding.uploadProgress.setProgress(percent / 2); // 0–50% = compress
                }

                @Override
                public void onSuccess(com.callx.app.utils.VideoCompressor.Result result) {
                    // Compression done — upload thumb + video to Cloudinary
                    com.callx.app.utils.VideoUploader.upload(
                            ChatActivity.this, result,
                            new com.callx.app.utils.VideoUploader.UploadCallback() {

                        @Override
                        public void onProgress(int percent) {
                            // 50–100% = upload phase
                            binding.uploadProgress.setProgress(50 + percent / 2);
                        }

                        @Override
                        public void onSuccess(String thumbUrl, String videoUrl,
                                              int durationMs, int width, int height) {
                            binding.uploadProgress.setVisibility(View.GONE);
                            Message m       = buildOutgoing();
                            m.type          = "video";
                            m.mediaUrl      = videoUrl;
                            m.thumbnailUrl  = thumbUrl;
                            m.duration      = (long) durationMs;
                            pushMessage(m, "\uD83C\uDFAC Video");
                            clearReply();
                        }

                        @Override
                        public void onError(Exception e) {
                            binding.uploadProgress.setVisibility(View.GONE);
                            Toast.makeText(ChatActivity.this,
                                    e != null ? e.getMessage() : "Video upload failed",
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }

                @Override
                public void onError(Exception e) {
                    // Compression failed — fallback to direct upload
                    android.util.Log.w("ChatActivity", "Video compress failed, fallback", e);
                    doUpload(uri, msgType, resourceType, fileName);
                }
            });
            return;
        }

        // All other types (audio, file): upload directly
        doUpload(uri, msgType, resourceType, fileName);
    }

    private void doUpload(Uri uri, String msgType, String resourceType, String fileName) {
        long size = FileUtils.fileSize(this, uri);
        CloudinaryUploader.upload(this, uri, "callx/" + msgType, resourceType,
                new CloudinaryUploader.UploadCallback() {
                    @Override public void onSuccess(CloudinaryUploader.Result r) {
                        binding.uploadProgress.setVisibility(View.GONE);
                        Message m  = buildOutgoing();
                        m.type     = msgType;
                        m.mediaUrl = r.secureUrl;
                        m.imageUrl = "image".equals(msgType) ? r.secureUrl : null;
                        m.fileName = fileName;
                        m.fileSize = r.bytes != null ? r.bytes : size;
                        m.duration = r.durationMs;
                        pushMessage(m, mediaPreview(msgType, fileName));
                        clearReply();
                    }
                    @Override public void onError(String err) {
                        binding.uploadProgress.setVisibility(View.GONE);
                        Toast.makeText(ChatActivity.this,
                                err != null ? err : "Upload failed",
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private static String mediaPreview(String type, String fileName) {
        switch (type) {
            case "image": return "\uD83D\uDCF7 Photo";
            case "video": return "\uD83C\uDFAC Video";
            case "audio": return "\uD83C\uDFA4 Voice message";
            case "file":  return "\uD83D\uDCCE " + (fileName != null ? fileName : "File");
            default:      return "Media";
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // PICKERS
    // ─────────────────────────────────────────────────────────────────────

    private void setupPickers() {
        imagePicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "image", "image", null); });
        videoPicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "video", "video", null); });
        audioPicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "audio", "raw", null); });
        filePicker  = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri == null) return;
                    uploadAndSend(uri, "file", "raw", FileUtils.fileName(this, uri));
                });
        cameraCapturer = registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                success -> {
                    if (success && cameraOutputUri != null)
                        uploadAndSend(cameraOutputUri, "image", "image", null);
                });
    }

    private void showAttachSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View v = LayoutInflater.from(this)
                .inflate(R.layout.bottom_sheet_attach, null);
        v.findViewById(R.id.opt_gallery)
                .setOnClickListener(x -> { sheet.dismiss(); imagePicker.launch("image/*"); });
        v.findViewById(R.id.opt_video)
                .setOnClickListener(x -> { sheet.dismiss(); videoPicker.launch("video/*"); });
        v.findViewById(R.id.opt_audio)
                .setOnClickListener(x -> { sheet.dismiss(); audioPicker.launch("audio/*"); });
        v.findViewById(R.id.opt_file)
                .setOnClickListener(x -> { sheet.dismiss(); filePicker.launch("*/*"); });
        sheet.setContentView(v);
        sheet.show();
    }

    // ─────────────────────────────────────────────────────────────────────
    // VOICE RECORDING
    // ─────────────────────────────────────────────────────────────────────

    private void toggleRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        if (!isRecording) {
            if (recorder.start(this)) {
                isRecording = true;
                binding.btnMic.setBackgroundResource(R.drawable.circle_reject);
                Toast.makeText(this, "Recording\u2026 tap again to stop",
                        Toast.LENGTH_SHORT).show();
            }
        } else {
            isRecording = false;
            binding.btnMic.setBackgroundResource(R.drawable.circle_primary);
            Uri uri = recorder.stop(this);
            if (uri != null) uploadAndSend(uri, "audio", "raw", null);
            else Toast.makeText(this, "Recording was empty",
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // CLEAR CHAT (N8)
    // ─────────────────────────────────────────────────────────────────────

    private void confirmClearChat() {
        new AlertDialog.Builder(this)
                .setTitle("Clear chat?")
                .setMessage("All messages will be deleted locally.")
                .setPositiveButton("Clear", (d, w) -> {
                    ioExecutor.execute(() ->
                            db.messageDao().deleteAllForChat(chatId));
                    CacheManager.getInstance(this).invalidateMessages(chatId);
                    Toast.makeText(this, "Chat cleared", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Apply Chat Screen Theme (toolbar, bg, input bar, buttons) ─────────
    private void applyScreenTheme() {
        com.callx.app.utils.ChatThemeManager mgr =
                com.callx.app.utils.ChatThemeManager.get(this);

        // Toolbar = the root LinearLayout id="toolbar"
        android.view.View toolbar = binding.toolbar;

        // Root layout (for chat background)
        android.view.View chatRoot = binding.getRoot();

        // Input row
        android.view.View inputRow = binding.llInputRow;

        // Reply bar accent stripe
        android.view.View replyAccent = binding.viewReplyAccent;

        // Also update tv_reply_bar_name color to match primary
        if (binding.tvReplyBarName != null) {
            binding.tvReplyBarName.setTextColor(mgr.getPrimaryColor());
        }

        mgr.applyScreenTheme(
                toolbar,
                chatRoot,
                inputRow,
                binding.btnSend,
                binding.btnMic,
                binding.fabBackToLatest,
                replyAccent);

        // Apply saved typing style to input box
        com.callx.app.utils.TypingStyleManager.get(this).applyToInput(binding.etMessage);
    }

    // ── Typing Style Picker (10 styles for message input box) ─────────────
    private void showTypingStylePicker() {
        com.callx.app.utils.TypingStyleManager mgr =
                com.callx.app.utils.TypingStyleManager.get(this);
        int current = mgr.getCurrentStyle();
        new AlertDialog.Builder(this)
            .setTitle("✍️ Choose Typing Style")
            .setSingleChoiceItems(
                com.callx.app.utils.TypingStyleManager.STYLE_NAMES,
                current,
                (dialog, which) -> {
                    // Samsung style selected — submenu dikhao
                    if (which == com.callx.app.utils.TypingStyleManager.STYLE_SAMSUNG) {
                        dialog.dismiss();
                        showSamsungStyleSubmenu(mgr);
                        return;
                    }
                    mgr.setStyle(which);
                    dialog.dismiss();
                    // dialog dismiss ke baad post() se apply karo
                    // warna dismiss ka focus-change typeface reset kar deta hai
                    binding.etMessage.post(() ->
                        mgr.applyToInput(binding.etMessage)
                    );
                })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /** Samsung style ka submenu — Font vs Script choose karo */
    private void showSamsungStyleSubmenu(com.callx.app.utils.TypingStyleManager mgr) {
        String scriptPreview = com.callx.app.utils.UnicodeStyler.toScript("Samsung Style");
        String[] options = {
            "🅢 Samsung One (Font)",
            scriptPreview + " (Script ✨)"
        };
        new AlertDialog.Builder(this)
            .setTitle("🅢 Samsung Style — Choose")
            .setItems(options, (dialog, which) -> {
                if (which == 0) {
                    mgr.setStyle(com.callx.app.utils.TypingStyleManager.STYLE_SAMSUNG);
                } else {
                    mgr.setStyle(com.callx.app.utils.TypingStyleManager.STYLE_SAMSUNG_SCRIPT);
                }
                binding.etMessage.post(() ->
                    mgr.applyToInput(binding.etMessage)
                );
            })
            .setNegativeButton("Back", (d, w) -> showTypingStylePicker())
            .show();
    }

    // ── Chat Bubble Theme Picker ──────────────────────────────────────────
    private void showThemePicker() {
        com.callx.app.utils.ChatThemeManager mgr =
                com.callx.app.utils.ChatThemeManager.get(this);
        int current = mgr.getCurrentTheme();
        new AlertDialog.Builder(this)
            .setTitle("\uD83C\uDFA8 Choose Bubble Theme")
            .setSingleChoiceItems(
                com.callx.app.utils.ChatThemeManager.THEME_NAMES,
                current,
                (dialog, which) -> {
                    mgr.setTheme(which);
                    pagingAdapter.notifyDataSetChanged();
                    applyScreenTheme();   // ← Apply to toolbar/bg/buttons too
                    dialog.dismiss();
                })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ─────────────────────────────────────────────────────────────────────
    // MENU
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.chat_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(android.view.Menu menu) {
        MenuItem muteItem = menu.findItem(R.id.action_mute);
        if (muteItem != null)
            muteItem.setTitle(isMuted ? "\uD83D\uDD14 Unmute" : "\uD83D\uDD15 Mute");
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_view_profile)  { openAvatarZoom();     return true; }
        if (id == R.id.action_edit_profile)   { openEditProfile();    return true; }
        if (id == R.id.action_starred) {
            Intent i = new Intent(this, com.callx.app.activities.StarredMessagesActivity.class);
            i.putExtra("chatId",  chatId);
            i.putExtra("isGroup", false);
            startActivity(i);
            return true;
        }
        if (id == R.id.action_search)      { openSearch();          return true; }
        if (id == R.id.action_mute)        { toggleMute();          return true; }
        if (id == R.id.action_block)       { confirmBlockUser();    return true; }
        if (id == R.id.action_clear_chat)  { confirmClearChat();    return true; }
        if (id == R.id.action_chat_theme)   { showThemePicker();        return true; }
        if (id == R.id.action_typing_style) { showTypingStylePicker();   return true; }
        if (id == R.id.action_media_links_docs) { openAllMediaLinksDocs(); return true; }
        return super.onOptionsItemSelected(item);
    }

    // ─────────────────────────────────────────────────────────────────────
    // PERMISSIONS
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public void onRequestPermissionsResult(int requestCode,
            @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            launchCamera();
        }
        if (requestCode == REQ_AUDIO
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            toggleRecording();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // UTILS
    // ─────────────────────────────────────────────────────────────────────

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    // ─────────────────────────────────────────────────────────────────────
    // SWIPE-TO-REPLY SYSTEM (SwipeReplySystem v1)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Attaches SwipeReplyHandler to the RecyclerView.
     * Initializes ReplyController with undo support.
     */
    private void setupSwipeToReply() {
        // Build message supplier that reads from paging adapter
        SwipeReplyHandler.OnSwipeReplyListener swipeListener = (message, adapterPosition) -> {
            ReplyAnalyticsTracker.get().onSwipeAttempt(100f); // rough estimate
            startReply(message);
        };

        // Create adapter-bridged handler (uses peek for PagingDataAdapter)
        SwipeReplyHandler handler = new SwipeReplyHandler(this,
                new java.util.AbstractList<com.callx.app.models.Message>() {
                    @Override public com.callx.app.models.Message get(int index) {
                        return pagingAdapter.peek(index);
                    }
                    @Override public int size() { return pagingAdapter.getItemCount(); }
                },
                currentUid, swipeListener);

        swipeHelper = new ItemTouchHelper(handler);
        swipeHelper.attachToRecyclerView(binding.rvMessages);

        // Initialize ReplyController
        replyController = new ReplyController(new ReplyController.Callback() {
            @Override public void onReplyActivated(com.callx.app.models.Message message) {
                activateReplyDirect(message);
            }
            @Override public void onReplyCancelled() {
                // Already handled by clearReply()
            }
            @Override public void onPendingUndo(
                    com.callx.app.models.Message message, Runnable cancelAction) {
                // Show undo snackbar for 2 seconds
                String senderName = (currentUid != null && currentUid.equals(message.senderId))
                        ? "You" : (message.senderName != null ? message.senderName : "Unknown");
                Snackbar.make(binding.getRoot(),
                                "Replying to " + senderName + "…",
                                Snackbar.LENGTH_SHORT)
                        .setAction("UNDO", v -> {
                            cancelAction.run();
                            ReplyAnalyticsTracker.get().onUndoUsed();
                        })
                        .show();
            }
            @Override public void onNavigateToOriginal(String messageId) {
                navigateToOriginal(messageId);
            }
            @Override public void onUndoConfirmed() {
                // Nothing extra needed — state already reset
            }
        });
    }

    /**
     * Sets up the "Back to latest" FAB.
     * Shown when navigating to an original message, hidden when back at bottom.
     */
    private void setupFabBackToLatest() {
        if (binding.fabBackToLatest == null) return;
        binding.fabBackToLatest.setOnClickListener(v -> {
            int last = pagingAdapter.getItemCount() - 1;
            if (last >= 0) binding.rvMessages.smoothScrollToPosition(last);
            MessageHighlightAnimator.hideFab(binding.fabBackToLatest);
        });
        // Auto-hide when user scrolls back to bottom
        binding.rvMessages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                androidx.recyclerview.widget.LinearLayoutManager lm =
                        (androidx.recyclerview.widget.LinearLayoutManager) rv.getLayoutManager();
                if (lm == null) return;
                int last    = lm.findLastVisibleItemPosition();
                int total   = pagingAdapter.getItemCount();
                if (last >= total - 3) {
                    MessageHighlightAnimator.hideFab(binding.fabBackToLatest);
                }
            }
        });
    }

    /**
     * Scrolls to and highlights the original message a reply references.
     * Falls back gracefully if message not in current page.
     */
    private void navigateToOriginal(String messageId) {
        if (messageId == null || messageId.isEmpty()) return;
        // Search in current paged items
        int pos = -1;
        for (int i = 0; i < pagingAdapter.getItemCount(); i++) {
            com.callx.app.models.Message m = pagingAdapter.peek(i);
            if (m != null && (messageId.equals(m.id) || messageId.equals(m.messageId))) {
                pos = i; break;
            }
        }
        if (pos >= 0) {
            MessageHighlightAnimator.scrollAndHighlight(
                    binding.rvMessages, pos, binding.fabBackToLatest);
        } else {
            // Not in current window — load from DB and scroll
            final int finalPos = pos;
            ioExecutor.execute(() -> {
                // DB lookup for position (approximate — use timestamp)
                runOnUiThread(() ->
                    Toast.makeText(this,
                            "Original message not loaded — scroll up to find it",
                            Toast.LENGTH_SHORT).show());
            });
        }
    }
}

