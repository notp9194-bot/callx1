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
import com.callx.app.R;
import com.callx.app.adapters.MessagePagingAdapter;
import com.callx.app.cache.CacheManager;
import com.callx.app.databinding.ActivityChatBinding;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MessageEntity;
import com.callx.app.models.Message;
import com.callx.app.repository.ChatRepository;
import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.FileUtils;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.PushNotify;
import com.callx.app.utils.AudioRecorderHelper;
import com.facebook.shimmer.ShimmerFrameLayout;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
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

    // ── Media pickers ──────────────────────────────────────────────────────
    private ActivityResultLauncher<String> imagePicker;
    private ActivityResultLauncher<String> videoPicker;
    private ActivityResultLauncher<String> audioPicker;
    private ActivityResultLauncher<String> filePicker;
    private ActivityResultLauncher<Uri>    cameraCapturer;
    private Uri cameraOutputUri;

    // ── Voice recorder ─────────────────────────────────────────────────────
    private AudioRecorderHelper recorder;

    // ── Pinned message ─────────────────────────────────────────────────────
    private String pinnedMsgId   = null;
    private String pinnedMsgText = null;

    // ─────────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        readIntentExtras();
        setupToolbar();
        setupPickers();
        recorder = new AudioRecorderHelper();

        db = AppDatabase.getInstance(this);

        // ── Core Paging 3 setup ──
        setupPagingRecyclerView();   // [FIX-1]  wire adapter
        observePagedMessages();      // [FIX-2]  start Pager → LiveData
        startRealtimeListener();     // [FIX-3]  Firebase → Room (auto-invalidates)

        // ── All other features ──
        setupInputBar();
        watchPartnerStatus();
        watchTyping();
        watchMute();
        watchBlock();
        watchPartnerPermaBlock();
        watchPinnedMessage();
        markMessagesRead();

        // Predictive preload of other hot chats (background)
        ChatRepository.getInstance(this).preloadRecentChats(chatId);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (messagesRef != null && messageListener != null)
            messagesRef.removeEventListener(messageListener);
        clearOurTypingStatus();
    }

    // ─────────────────────────────────────────────────────────────────────
    // INTENT EXTRAS
    // ─────────────────────────────────────────────────────────────────────

    private void readIntentExtras() {
        Intent i    = getIntent();
        partnerUid  = i.getStringExtra("partnerUid");
        partnerName = i.getStringExtra("partnerName");
        partnerPhoto= i.getStringExtra("partnerPhoto");
        currentName = i.getStringExtra("currentName");

        com.google.firebase.auth.FirebaseUser fu =
                FirebaseAuth.getInstance().getCurrentUser();
        currentUid = fu != null ? fu.getUid() : "";

        chatId      = buildChatId(currentUid, partnerUid);
        messagesRef = FirebaseUtils.getMessagesRef(chatId);
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
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(partnerName != null ? partnerName : "Chat");
        }
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        binding.ivPartnerAvatar.setOnClickListener(v -> openAvatarZoom());
        if (partnerPhoto != null && !partnerPhoto.isEmpty()) {
            Glide.with(this).load(partnerPhoto)
                    .placeholder(R.drawable.ic_person)
                    .into(binding.ivPartnerAvatar);
        }
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
        });

        LinearLayoutManager llm = new LinearLayoutManager(this);
        llm.setStackFromEnd(true);    // newest items at the bottom
        llm.setReverseLayout(false);
        binding.rvMessages.setLayoutManager(llm);
        binding.rvMessages.setAdapter(pagingAdapter);

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
        long lastTs = CacheManager.getInstance(this).getLastSyncTimestamp(chatId);

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
        m.text                  = e.text;
        m.type                  = e.type;
        m.mediaUrl              = e.mediaUrl;
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
        m.edited                = e.edited;
        m.deleted               = e.deleted;
        m.forwardedFrom         = e.forwardedFrom;
        m.starred               = e.starred;
        m.pinned                = e.pinned;
        return m;
    }

    private MessageEntity modelToEntity(Message m) {
        MessageEntity e           = new MessageEntity();
        e.id                      = m.id != null ? m.id : "";
        e.chatId                  = chatId;
        e.senderId                = m.senderId;
        e.senderName              = m.senderName;
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
        e.edited                  = m.edited;
        e.deleted                 = m.deleted;
        e.forwardedFrom           = m.forwardedFrom;
        e.starred                 = Boolean.TRUE.equals(m.starred);
        e.pinned                  = Boolean.TRUE.equals(m.pinned);
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
                setOurTypingStatus(hasText);
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
        clearOurTypingStatus();
        Message m = buildOutgoing();
        m.type = "text";
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
            m.replyToId         = replyingTo.id;
            m.replyToText       = replyingTo.text != null
                    ? replyingTo.text : "[" + replyingTo.type + "]";
            m.replyToSenderName = replyingTo.senderName;
        }
        return m;
    }

    private void pushMessage(Message m, String previewText) {
        String key = messagesRef.push().getKey();
        if (key == null) return;
        m.id = key;
        messagesRef.child(key).setValue(m);

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

    // ─────────────────────────────────────────────────────────────────────
    // REPLY (N1)
    // ─────────────────────────────────────────────────────────────────────

    private void startReply(Message m) {
        replyingTo = m;
        if (binding.llReplyBar != null) {
            binding.llReplyBar.setVisibility(View.VISIBLE);
            if (binding.tvReplyBarName != null)
                binding.tvReplyBarName.setText(
                        m.senderName != null ? m.senderName : "");
            if (binding.tvReplyBarText != null)
                binding.tvReplyBarText.setText(
                        m.text != null ? m.text : "[" + m.type + "]");
        }
        binding.etMessage.requestFocus();
    }

    private void clearReply() {
        replyingTo = null;
        if (binding.llReplyBar != null)
            binding.llReplyBar.setVisibility(View.GONE);
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
    // FORWARD
    // ─────────────────────────────────────────────────────────────────────

    private void forwardMessage(Message m) {
        Intent i = new Intent(this, com.callx.app.activities.ContactsActivity.class);
        i.putExtra("forwardText", m.text);
        i.putExtra("forwardType", m.type);
        i.putExtra("forwardMedia", m.mediaUrl);
        startActivity(i);
    }

    // ─────────────────────────────────────────────────────────────────────
    // MARK READ
    // ─────────────────────────────────────────────────────────────────────

    private void markMessagesRead() {
        FirebaseUtils.getContactsRef(currentUid)
                .child(partnerUid).child("unread").setValue(0);
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
                    if (!child.getKey().equals(currentUid)
                            && Boolean.TRUE.equals(child.getValue(Boolean.class))) {
                        typing = true;
                        break;
                    }
                }
                if (binding.tvTyping != null)
                    binding.tvTyping.setVisibility(typing ? View.VISIBLE : View.GONE);
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
        onlineListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                Boolean online = s.child("online").getValue(Boolean.class);
                Long lastSeen  = s.child("lastSeen").getValue(Long.class);
                if (binding.tvStatus == null) return;
                if (Boolean.TRUE.equals(online)) {
                    binding.tvStatus.setText("online");
                } else if (lastSeen != null) {
                    java.text.SimpleDateFormat sdf =
                            new java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault());
                    binding.tvStatus.setText("last seen " + sdf.format(new java.util.Date(lastSeen)));
                } else {
                    binding.tvStatus.setText("");
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getUserRef(partnerUid).addValueEventListener(onlineListener);
    }

    // ─────────────────────────────────────────────────────────────────────
    // MUTE (N8)
    // ─────────────────────────────────────────────────────────────────────

    private void watchMute() {
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

    private void openSearch() {
        if (binding.llSearchBar == null) return;
        binding.llSearchBar.setVisibility(View.VISIBLE);
        if (binding.etSearch != null) {
            binding.etSearch.requestFocus();
            binding.etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void afterTextChanged(Editable s) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                    // Snapshot search via Room
                    String query = s.toString().trim();
                    if (query.length() < 2) return;
                    ioExecutor.execute(() -> {
                        java.util.List<MessageEntity> results =
                                db.messageDao().getMessagesPaged(chatId, 200, 0);
                        // Filter in memory
                        java.util.List<Integer> matchPositions = new java.util.ArrayList<>();
                        for (int idx = 0; idx < results.size(); idx++) {
                            MessageEntity me = results.get(idx);
                            if (me.text != null && me.text.toLowerCase()
                                    .contains(query.toLowerCase())) {
                                matchPositions.add(idx);
                            }
                        }
                        runOnUiThread(() -> {
                            if (!matchPositions.isEmpty()) {
                                binding.rvMessages.scrollToPosition(matchPositions.get(0));
                            }
                        });
                    });
                }
            });
        }
        if (binding.btnCloseSearch != null)
            binding.btnCloseSearch.setOnClickListener(v ->
                    binding.llSearchBar.setVisibility(View.GONE));
    }

    // ─────────────────────────────────────────────────────────────────────
    // AVATAR ZOOM (N3)
    // ─────────────────────────────────────────────────────────────────────

    private void openAvatarZoom() {
        if (partnerPhoto == null || partnerPhoto.isEmpty()) return;
        Intent i = new Intent(this, com.callx.app.activities.MediaViewerActivity.class);
        i.putExtra("url",  partnerPhoto);
        i.putExtra("type", "image");
        startActivity(i);
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
        binding.uploadProgress.setVisibility(View.VISIBLE);
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
                uri -> { if (uri != null) uploadAndSend(uri, "audio", "video", null); });
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
            if (uri != null) uploadAndSend(uri, "audio", "video", null);
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
}
