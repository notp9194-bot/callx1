package com.callx.app.conversation;

import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.RotateAnimation;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.cache.CacheManager;
import com.callx.app.chat.R;
import com.callx.app.chat.analytics.ReplyAnalyticsTracker;
import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.chat.gesture.SwipeReplyHandler;
import com.callx.app.chat.reply.ReplyController;
import com.callx.app.chat.reply.ReplyDataMapper;
import com.callx.app.chat.ui.MessageHighlightAnimator;
import com.callx.app.conversation.controllers.*;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MessageEntity;
import com.callx.app.models.Message;
import com.callx.app.repository.ChatRepository;
import com.callx.app.starred.StarredMessagesActivity;
import com.callx.app.utils.FirebaseUtils;

import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * ChatActivity v21 — slim coordinator.
 *
 * All heavy logic lives in dedicated controllers:
 *   ChatBlockController         — block / perma-block / unblock-joy / special-request
 *   ChatPresenceController      — typing, online-status, mute, mark-read
 *   ChatPinController           — pin / unpin
 *   ChatSearchController        — in-chat search
 *   ChatThemeController         — theme, wallpaper, customization, privacy dialogs
 *   ChatMediaController         — media pickers, upload, camera, GIF, voice
 *   ChatMessageSender           — local-first send, Firebase push, pending retry
 *   ChatMessageActionController — copy, edit, star, react, delete, forward [NEW v21]
 *   ChatNavigationController    — profile, media/links, small window, reply-jump [NEW v21]
 *   ChatInputController         — input bar, typing debounce, draft [NEW v21]
 *   ChatPagingController        — paging3, realtime listener, entity↔model [NEW v21]
 */
public class ChatActivity extends AppCompatActivity implements ChatActivityDelegate {

    // ── View binding ───────────────────────────────────────────────────────
    private ActivityChatBinding binding;

    // ── Identity ───────────────────────────────────────────────────────────
    private String chatId;
    private String partnerUid;
    private String partnerName;
    private String partnerPhoto;
    private String partnerThumb;
    private String currentUid;
    private String currentName;

    // ── State ──────────────────────────────────────────────────────────────
    private boolean isMuted               = false;
    private boolean isBlocked             = false;
    private boolean isRecording           = false;
    private boolean partnerPermaBlockedMe = false;
    private boolean iPermaBlockedPartner  = false;

    // ── DB / executor ──────────────────────────────────────────────────────
    private AppDatabase    db;
    private final Executor ioExecutor = Executors.newFixedThreadPool(2);

    // ── Firebase ───────────────────────────────────────────────────────────
    private DatabaseReference messagesRef;

    // ── Paging ────────────────────────────────────────────────────────────
    private MessagePagingAdapter pagingAdapter;

    // ── Reply ─────────────────────────────────────────────────────────────
    private Message        replyingTo = null;
    private ReplyController replyController;
    private ItemTouchHelper swipeHelper;

    // ── Network ────────────────────────────────────────────────────────────
    private ConnectivityManager                   connMgr;
    private ConnectivityManager.NetworkCallback   netCallback;

    // ── Multi-select ───────────────────────────────────────────────────────
    private boolean selectionToolbarSetup = false;

    // ── Controllers ────────────────────────────────────────────────────────
    private ChatBlockController          blockController;
    private ChatPresenceController       presenceController;
    private ChatPinController            pinController;
    private ChatSearchController         searchController;
    private ChatThemeController          themeController;
    private ChatMediaController          mediaController;
    private ChatMessageSender            messageSender;
    private ChatMessageActionController  actionController;
    private ChatNavigationController     navController;
    private ChatInputController          inputController;
    private ChatPagingController         pagingController;

    // ──────────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ──────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // ActivityResultLaunchers MUST be created before super.onCreate()
        mediaController = new ChatMediaController(this, this);
        mediaController.registerPickers();

        super.onCreate(savedInstanceState);
        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        readIntentExtras();
        if (partnerUid == null || partnerUid.isEmpty()) { finish(); return; }

        db = AppDatabase.getInstance(this);

        // Create remaining controllers
        blockController    = new ChatBlockController(this);
        presenceController = new ChatPresenceController(this);
        pinController      = new ChatPinController(this);
        searchController   = new ChatSearchController(this);
        themeController    = new ChatThemeController(this);
        messageSender      = new ChatMessageSender(this);
        actionController   = new ChatMessageActionController(this);
        navController      = new ChatNavigationController(this);
        inputController    = new ChatInputController(this, presenceController);
        pagingController   = new ChatPagingController(this, this,
                                binding.rvMessages, binding.fabBackToLatest);

        // Core setup
        setupToolbar();
        themeController.applyScreenTheme();

        // Paging
        pagingAdapter = new MessagePagingAdapter(currentUid, false);
        pagingController.setupPagingRecyclerView(actionController, pinController);
        pagingController.observePagedMessages(pagingAdapter);
        pagingController.startRealtimeListener(presenceController);

        // UI features
        inputController.setupInputBar(mediaController);
        setupSwipeToReply();
        setupFabBackToLatest();
        setupMultiSelectListener();

        // Controller Firebase init
        presenceController.init();
        blockController.init();
        pinController.init();

        presenceController.markMessagesRead();
        setupNetworkMonitor();
        ioExecutor.execute(() -> db.messageDao().pruneOldMessages(chatId, 500));
        pagingController.scheduleExpiryCleanup();

        ChatRepository.getInstance(this).preloadRecentChats(chatId);
        inputController.restoreDraft();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra("show_unblock_joy", false))
            binding.getRoot().postDelayed(() -> blockController.checkAndShowUnblockJoy(), 600);
    }

    @Override protected void onPause() {
        super.onPause();
        inputController.saveDraft();
        inputController.clearTypingOnPause();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        inputController.saveDraft();
        inputController.releaseTypingHandler();
        pagingController.releaseListener();
        pagingController.cancelExpiryCleanup();
        if (connMgr != null && netCallback != null) {
            try { connMgr.unregisterNetworkCallback(netCallback); } catch (Exception ignored) {}
        }
        if (replyController  != null) replyController.release();
        if (presenceController != null) presenceController.release();
        if (blockController    != null) blockController.release();
    }

    // ──────────────────────────────────────────────────────────────────────
    // ChatActivityDelegate IMPLEMENTATION
    // ──────────────────────────────────────────────────────────────────────

    @Override public ActivityChatBinding getBinding()         { return binding; }
    @Override public String getChatId()                       { return chatId; }
    @Override public String getPartnerUid()                   { return partnerUid; }
    @Override public String getPartnerName()                  { return partnerName; }
    @Override public String getPartnerPhoto()                 { return partnerPhoto; }
    @Override public String getPartnerThumb()                 { return partnerThumb; }
    @Override public String getCurrentUid()                   { return currentUid; }
    @Override public String getCurrentName()                  { return currentName; }
    @Override public AppDatabase getDb()                      { return db; }
    @Override public Executor getIoExecutor()                 { return ioExecutor; }
    @Override public DatabaseReference getMessagesRef()       { return messagesRef; }
    @Override public boolean isMuted()                        { return isMuted; }
    @Override public void setMuted(boolean v)                 { isMuted = v; }
    @Override public boolean isBlocked()                      { return isBlocked; }
    @Override public void setBlocked(boolean v)               { isBlocked = v; }
    @Override public boolean isPartnerPermaBlockedMe()        { return partnerPermaBlockedMe; }
    @Override public void setPartnerPermaBlockedMe(boolean v) { partnerPermaBlockedMe = v; }
    @Override public boolean isIPermaBlockedPartner()         { return iPermaBlockedPartner; }
    @Override public void setIPermaBlockedPartner(boolean v)  { iPermaBlockedPartner = v; }
    @Override public boolean isRecording()                    { return isRecording; }
    @Override public void setRecording(boolean v)             { isRecording = v; }
    @Override public void runOnMain(Runnable r)               { runOnUiThread(r); }
    @Override public void showToast(String msg)               { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }
    @Override public void invalidateMenu()                    { invalidateOptionsMenu(); }
    @Override public android.app.Activity getActivity()       { return this; }
    @Override public androidx.fragment.app.FragmentManager getSupportFragmentManager() {
        return super.getSupportFragmentManager();
    }
    @Override public MessagePagingAdapter getPagingAdapter()  { return pagingAdapter; }
    @Override public void refreshScreenTheme()                { themeController.applyScreenTheme(); }
    @Override public void refreshWallpaper()                  { themeController.applyWallpaper(); }
    @Override public void launchWallpaperPicker()             { mediaController.launchWallpaperPicker(); }
    @Override public void navigateToOriginal(String messageId) {
        navController.navigateToOriginalMsg(messageId, binding.rvMessages, binding.fabBackToLatest);
    }
    @Override public void showMultiSelectBar(int count) {
        setupSelectionToolbar();
        binding.toolbar.setVisibility(View.GONE);
        View selBar = binding.getRoot().findViewById(R.id.ll_selection_toolbar);
        if (selBar != null) selBar.setVisibility(View.VISIBLE);
        android.widget.TextView tvCount =
            binding.getRoot().findViewById(R.id.tv_selection_count);
        if (tvCount != null) tvCount.setText(String.valueOf(count));
        View btnInfo = binding.getRoot().findViewById(R.id.btn_selection_info);
        if (btnInfo != null) btnInfo.setVisibility(count == 1 ? View.VISIBLE : View.GONE);
    }
    @Override public void hideMultiSelectBar() {
        binding.toolbar.setVisibility(View.VISIBLE);
        View selBar = binding.getRoot().findViewById(R.id.ll_selection_toolbar);
        if (selBar != null) selBar.setVisibility(View.GONE);
    }

    @Override public boolean isOnline() {
        if (connMgr == null) return true;
        Network active = connMgr.getActiveNetwork();
        if (active == null) return false;
        NetworkCapabilities caps = connMgr.getNetworkCapabilities(active);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    @Override public Message buildOutgoing() {
        Message m    = new Message();
        m.senderId   = currentUid;
        m.senderName = currentName;
        m.timestamp  = System.currentTimeMillis();
        m.status     = "sent";
        if (replyingTo != null) ReplyDataMapper.applyReplyFields(m, replyingTo, currentUid);
        return m;
    }

    @Override public void pushMessage(Message m, String previewText) {
        messageSender.pushMessage(m, previewText);
    }
    @Override public void firebasePushMessage(Message m, String key, String previewText) {
        messageSender.firebasePushMessage(m, key, previewText);
    }

    @Override public void clearReply() {
        replyingTo = null;
        if (replyController != null) replyController.cancel();
        if (binding.llReplyBar == null) return;
        binding.llReplyBar.animate()
            .alpha(0f).translationY(20f).setDuration(150)
            .withEndAction(() -> {
                binding.llReplyBar.setVisibility(View.GONE);
                binding.llReplyBar.setAlpha(1f);
                binding.llReplyBar.setTranslationY(0f);
            }).start();
        if (binding.ivReplyBarThumb != null) binding.ivReplyBarThumb.setVisibility(View.GONE);
    }

    @Override public void startReply(Message m) {
        if (replyController != null) replyController.onSwipeReply(m);
        else activateReplyDirect(m);
    }

    @Override public void activateReplyDirect(Message m) {
        replyingTo = m;
        if (binding.llReplyBar == null) return;
        String senderName = (currentUid != null && currentUid.equals(m.senderId))
            ? "You" : (m.senderName != null ? m.senderName : "");
        String preview;
        if (Boolean.TRUE.equals(m.deleted)) {
            preview = "\uD83D\uDEAB  Original message unavailable";
        } else if (m.text != null && !m.text.isEmpty()) {
            preview = m.text;
        } else {
            preview = buildTypePreview(m);
        }
        if (binding.tvReplyBarName != null) binding.tvReplyBarName.setText(senderName);
        if (binding.tvReplyBarText != null) binding.tvReplyBarText.setText(preview);
        if (binding.ivReplyBarThumb != null) {
            String thumbUrl = "image".equals(m.type) ? m.mediaUrl
                            : "video".equals(m.type) ? m.thumbnailUrl : null;
            if (thumbUrl != null && !thumbUrl.isEmpty()) {
                binding.ivReplyBarThumb.setVisibility(View.VISIBLE);
                Glide.with(this).load(thumbUrl).centerCrop().into(binding.ivReplyBarThumb);
            } else {
                binding.ivReplyBarThumb.setVisibility(View.GONE);
            }
        }
        binding.llReplyBar.setVisibility(View.VISIBLE);
        binding.llReplyBar.setAlpha(0f); binding.llReplyBar.setTranslationY(40f);
        binding.llReplyBar.animate().alpha(1f).translationY(0f).setDuration(200).start();
        binding.etMessage.requestFocus();
        ReplyAnalyticsTracker.get().onSwipeTriggered();
    }

    // ──────────────────────────────────────────────────────────────────────
    // INTENT EXTRAS + FORWARD PAYLOAD
    // ──────────────────────────────────────────────────────────────────────

    private void readIntentExtras() {
        Intent i    = getIntent();
        partnerUid  = i.getStringExtra("partnerUid");
        partnerName = i.getStringExtra("partnerName");
        partnerPhoto= i.getStringExtra("partnerPhoto");
        partnerThumb= i.getStringExtra("partnerThumb");
        currentName = i.getStringExtra("currentName");

        com.google.firebase.auth.FirebaseUser fu = FirebaseAuth.getInstance().getCurrentUser();
        currentUid = fu != null ? fu.getUid() : "";
        chatId     = buildChatId(currentUid, partnerUid);
        messagesRef= FirebaseUtils.getMessagesRef(chatId);

        handleForwardPayload(i);
    }

    private void handleForwardPayload(Intent i) {
        String fwdText  = i.getStringExtra("forwardText");
        String fwdType  = i.getStringExtra("forwardType");
        String fwdMedia = i.getStringExtra("forwardMedia");
        ArrayList<String> fwdTexts     = i.getStringArrayListExtra("forwardTexts");
        ArrayList<String> fwdTypes     = i.getStringArrayListExtra("forwardTypes");
        ArrayList<String> fwdMedias    = i.getStringArrayListExtra("forwardMedias");
        ArrayList<String> fwdFileNames = i.getStringArrayListExtra("forwardFileNames");

        if (fwdTexts != null && !fwdTexts.isEmpty()) {
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
                            m2.type = "text"; m2.text = t;
                            pushMessage(m2, t != null ? t : "");
                        } else if (mu != null && !mu.isEmpty()) {
                            m2.type = tp; m2.mediaUrl = mu;
                            m2.imageUrl = "image".equals(tp) ? mu : null;
                            m2.fileName = fwdFileNames != null && fi < fwdFileNames.size()
                                ? fwdFileNames.get(fi) : null;
                            pushMessage(m2, fwdPreviewLabel(tp));
                        }
                    }, idx * 150L);
                }
            });
        } else if (fwdText != null && !fwdText.isEmpty() && "text".equals(fwdType)) {
            if (binding.etMessage != null) {
                binding.etMessage.post(() -> {
                    binding.etMessage.setText(fwdText);
                    binding.etMessage.setSelection(fwdText.length());
                });
            }
        } else if (fwdMedia != null && !fwdMedia.isEmpty()) {
            binding.getRoot().post(() -> {
                Message m  = buildOutgoing();
                m.type     = fwdType != null ? fwdType : "image";
                m.mediaUrl = fwdMedia;
                m.imageUrl = "image".equals(m.type) ? fwdMedia : null;
                m.forwardedFrom = partnerName;
                pushMessage(m, fwdPreviewLabel(m.type));
            });
        }
    }

    private static String fwdPreviewLabel(String type) {
        if (type == null) return "\uD83D\uDCCE File (forwarded)";
        switch (type) {
            case "image": return "\uD83D\uDCF7 Photo (forwarded)";
            case "video": return "\uD83C\uDFAC Video (forwarded)";
            case "audio": return "\uD83C\uDFA4 Voice (forwarded)";
            default:      return "\uD83D\uDCCE File (forwarded)";
        }
    }

    private static String buildChatId(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        return a.compareTo(b) < 0 ? a + "_" + b : b + "_" + a;
    }

    // ──────────────────────────────────────────────────────────────────────
    // ENTITY ↔ MODEL (static — used by ChatPagingController via lambda)
    // ──────────────────────────────────────────────────────────────────────

    public static Message entityToModel(MessageEntity e) {
        Message m = new Message();
        m.id = e.id; m.messageId = e.id; m.senderId = e.senderId; m.senderName = e.senderName;
        m.senderPhoto = e.senderPhoto; m.text = e.text; m.type = e.type; m.mediaUrl = e.mediaUrl;
        m.imageUrl = "image".equals(e.type) ? e.mediaUrl : null; m.thumbnailUrl = e.thumbnailUrl;
        m.fileName = e.fileName; m.fileSize = e.fileSize; m.duration = e.duration;
        m.timestamp = e.timestamp; m.status = e.status; m.replyToId = e.replyToId;
        m.replyToText = e.replyToText; m.replyToSenderName = e.replyToSenderName;
        m.replyToType = e.replyToType; m.replyToMediaUrl = e.replyToMediaUrl;
        m.edited = e.edited; m.deleted = e.deleted; m.forwardedFrom = e.forwardedFrom;
        m.starred = e.starred; m.pinned = e.pinned; m.reelId = e.reelId;
        m.reelThumbUrl = e.reelThumbUrl; m.fontStyle = e.fontStyle; m.expiresAt = e.expiresAt;
        return m;
    }

    // ──────────────────────────────────────────────────────────────────────
    // TOOLBAR SETUP
    // ──────────────────────────────────────────────────────────────────────

    private void setupToolbar() {
        binding.btnBack.setOnClickListener(v -> {
            if (pagingAdapter != null && pagingAdapter.isInMultiSelectMode()) {
                pagingAdapter.exitMultiSelectMode();
                hideMultiSelectBar();
            } else {
                finish();
            }
        });

        if (partnerName != null) binding.tvPartnerName.setText(partnerName);
        binding.ivPartnerAvatar.setOnClickListener(v -> navController.openAvatarZoom());

        String headerAvatar = (partnerThumb != null && !partnerThumb.isEmpty())
            ? partnerThumb : partnerPhoto;
        if (headerAvatar != null && !headerAvatar.isEmpty()) {
            Glide.with(this).load(headerAvatar).placeholder(R.drawable.ic_person)
                .circleCrop().into(binding.ivPartnerAvatar);
        } else {
            FirebaseUtils.getUserRef(partnerUid).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    String thumb = s.child("thumbUrl").getValue(String.class);
                    String photo = s.child("photoUrl").getValue(String.class);
                    String url = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                    if (url != null && !url.isEmpty()) {
                        if (photo != null) partnerPhoto = photo;
                        if (thumb != null) partnerThumb = thumb;
                        Glide.with(ChatActivity.this).load(url)
                            .placeholder(R.drawable.ic_person)
                            .circleCrop().into(binding.ivPartnerAvatar);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
        }

        binding.btnToolbarVoiceCall.setOnClickListener(v -> startCall(false));
        binding.btnToolbarVideoCall.setOnClickListener(v -> startCall(true));

        binding.btnToolbarReel.setOnClickListener(v -> {
            if (partnerUid == null || partnerUid.isEmpty()) return;
            try {
                Class<?> cls = Class.forName("com.callx.app.profile.UserReelsActivity");
                Intent in = new Intent(this, cls);
                in.putExtra("uid",   partnerUid);
                in.putExtra("name",  partnerName  != null ? partnerName  : "");
                in.putExtra("photo", partnerPhoto != null ? partnerPhoto : "");
                startActivity(in);
            } catch (ClassNotFoundException e) {
                showToast("Reels profile not available");
            }
        });

        // Hanging reel animation
        LinearLayout reelHanging = binding.llReelHanging;
        if (reelHanging != null) {
            reelHanging.post(() -> {
                RotateAnimation swing = new RotateAnimation(-12f, 12f,
                    Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.0f);
                swing.setDuration(1800);
                swing.setRepeatCount(Animation.INFINITE);
                swing.setRepeatMode(Animation.REVERSE);
                swing.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
                reelHanging.startAnimation(swing);
            });
        }

        binding.btnToolbarX.setOnClickListener(v -> {
            if (partnerUid == null || partnerUid.isEmpty()) return;
            try {
                Class<?> cls = Class.forName("com.callx.app.profile.XProfileSheet");
                java.lang.reflect.Method method = cls.getMethod("showProfile",
                    androidx.fragment.app.FragmentManager.class, String.class);
                method.invoke(null, getSupportFragmentManager(), partnerUid);
            } catch (Exception e) {
                showToast("X profile not available");
            }
        });

        binding.btnToolbarYoutube.setOnClickListener(v -> {
            if (partnerUid == null || partnerUid.isEmpty()) return;
            try {
                Class<?> cls = Class.forName("com.callx.app.channel.YouTubeChannelActivity");
                Intent in = new Intent(this, cls);
                in.putExtra("uid",  partnerUid);
                in.putExtra("name", partnerName != null ? partnerName : "");
                startActivity(in);
            } catch (ClassNotFoundException e) {
                showToast("YouTube channel not available");
            }
        });

        binding.btnMoreOptions.setOnClickListener(v -> {
            android.widget.PopupMenu popup =
                new android.widget.PopupMenu(this, binding.btnMoreOptions);
            popup.getMenuInflater().inflate(R.menu.chat_menu, popup.getMenu());
            android.view.MenuItem muteItem = popup.getMenu().findItem(R.id.action_mute);
            if (muteItem != null)
                muteItem.setTitle(isMuted ? "\uD83D\uDD14 Unmute" : "\uD83D\uDD15 Mute");
            popup.setOnMenuItemClickListener(item -> onOptionsItemSelected(item));
            popup.show();
        });
    }

    private void startCall(boolean isVideo) {
        Intent i = new Intent().setClassName(this, "com.callx.app.call.CallActivity");
        i.putExtra("partnerUid",   partnerUid);
        i.putExtra("partnerName",  partnerName);
        i.putExtra("partnerPhoto", partnerPhoto);
        i.putExtra("partnerThumb", partnerThumb);
        i.putExtra("isCaller",     true);
        i.putExtra("video",        isVideo);
        startActivity(i);
    }

    // ──────────────────────────────────────────────────────────────────────
    // MULTI-SELECT LISTENER + TOOLBAR
    // ──────────────────────────────────────────────────────────────────────

    private void setupMultiSelectListener() {
        if (pagingAdapter == null) return;
        pagingAdapter.setMultiSelectListener(count -> {
            if (count > 0) showMultiSelectBar(count);
            else hideMultiSelectBar();
        });
    }

    private void setupSelectionToolbar() {
        if (selectionToolbarSetup) return;
        selectionToolbarSetup = true;

        View btnClose = binding.getRoot().findViewById(R.id.btn_selection_close);
        if (btnClose != null) btnClose.setOnClickListener(v -> {
            pagingAdapter.exitMultiSelectMode(); hideMultiSelectBar();
        });

        View btnFwd = binding.getRoot().findViewById(R.id.btn_selection_forward);
        if (btnFwd != null) btnFwd.setOnClickListener(v ->
            actionController.forwardSelectedMessages());

        View btnDel = binding.getRoot().findViewById(R.id.btn_selection_delete);
        if (btnDel != null) btnDel.setOnClickListener(v ->
            actionController.deleteSelectedMessages(() -> {
                pagingAdapter.exitMultiSelectMode(); hideMultiSelectBar();
            }));

        View btnStar = binding.getRoot().findViewById(R.id.btn_selection_star);
        if (btnStar != null) btnStar.setOnClickListener(v -> {
            for (Message m : pagingAdapter.getSelectedMessages()) actionController.toggleStar(m);
            pagingAdapter.exitMultiSelectMode(); hideMultiSelectBar();
        });

        View btnReply = binding.getRoot().findViewById(R.id.btn_selection_reply);
        if (btnReply != null) btnReply.setOnClickListener(v -> {
            java.util.List<Message> sel = pagingAdapter.getSelectedMessages();
            if (!sel.isEmpty()) startReply(sel.get(0));
            pagingAdapter.exitMultiSelectMode(); hideMultiSelectBar();
        });

        View btnInfo = binding.getRoot().findViewById(R.id.btn_selection_info);
        if (btnInfo != null) btnInfo.setOnClickListener(v -> {
            java.util.List<Message> sel = pagingAdapter.getSelectedMessages();
            if (sel.size() == 1) actionController.showMessageInfoDialog(sel.get(0));
            pagingAdapter.exitMultiSelectMode(); hideMultiSelectBar();
        });
    }

    // ──────────────────────────────────────────────────────────────────────
    // SWIPE TO REPLY + FAB
    // ──────────────────────────────────────────────────────────────────────

    private void setupSwipeToReply() {
        SwipeReplyHandler handler = new SwipeReplyHandler(this,
            new java.util.AbstractList<Message>() {
                @Override public Message get(int index) { return pagingAdapter.peek(index); }
                @Override public int size() { return pagingAdapter.getItemCount(); }
            },
            currentUid,
            (message, adapterPosition) -> {
                ReplyAnalyticsTracker.get().onSwipeAttempt(100f);
                startReply(message);
            });
        swipeHelper = new ItemTouchHelper(handler);
        swipeHelper.attachToRecyclerView(binding.rvMessages);

        replyController = new ReplyController(new ReplyController.Callback() {
            @Override public void onReplyActivated(Message message) { activateReplyDirect(message); }
            @Override public void onReplyCancelled() {}
            @Override public void onPendingUndo(Message message, Runnable cancelAction) {
                String senderName = (currentUid != null && currentUid.equals(message.senderId))
                    ? "You" : (message.senderName != null ? message.senderName : "Unknown");
                Snackbar.make(binding.getRoot(),
                        "Replying to " + senderName + "\u2026", Snackbar.LENGTH_SHORT)
                    .setAction("UNDO", v -> { cancelAction.run(); ReplyAnalyticsTracker.get().onUndoUsed(); })
                    .show();
            }
            @Override public void onNavigateToOriginal(String messageId) { navigateToOriginal(messageId); }
            @Override public void onUndoConfirmed() {}
        });
    }

    private void setupFabBackToLatest() {
        if (binding.fabBackToLatest == null) return;
        binding.fabBackToLatest.setOnClickListener(v -> {
            int last = pagingAdapter.getItemCount() - 1;
            if (last >= 0) binding.rvMessages.smoothScrollToPosition(last);
            MessageHighlightAnimator.hideFab(binding.fabBackToLatest);
        });
        binding.rvMessages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm == null) return;
                if (lm.findLastVisibleItemPosition() >= pagingAdapter.getItemCount() - 3)
                    MessageHighlightAnimator.hideFab(binding.fabBackToLatest);
            }
        });
    }

    // ──────────────────────────────────────────────────────────────────────
    // NETWORK MONITOR
    // ──────────────────────────────────────────────────────────────────────

    private void setupNetworkMonitor() {
        connMgr = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (connMgr == null) return;
        boolean online = isOnline();
        updateOfflineBanner(!online);
        messageSender.updateSendButtonState(online);

        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) {
                runOnUiThread(() -> {
                    updateOfflineBanner(false);
                    messageSender.updateSendButtonState(true);
                    messageSender.retryPendingMessages();
                    ChatRepository.getInstance(getApplicationContext())
                        .syncMessagesDelta(chatId);
                });
            }
            @Override public void onLost(Network n) {
                runOnUiThread(() -> {
                    updateOfflineBanner(true);
                    messageSender.updateSendButtonState(false);
                });
            }
        };
        try {
            NetworkRequest req = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
            connMgr.registerNetworkCallback(req, netCallback);
        } catch (Exception ignored) {}
    }

    private void updateOfflineBanner(boolean offline) {
        if (binding == null) return;
        android.widget.TextView banner =
            binding.getRoot().findViewById(R.id.tv_offline_banner);
        if (banner != null) banner.setVisibility(offline ? View.VISIBLE : View.GONE);
    }

    // ──────────────────────────────────────────────────────────────────────
    // MENU
    // ──────────────────────────────────────────────────────────────────────

    @Override public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.chat_menu, menu); return true;
    }

    @Override public boolean onPrepareOptionsMenu(android.view.Menu menu) {
        android.view.MenuItem muteItem = menu.findItem(R.id.action_mute);
        if (muteItem != null) muteItem.setTitle(isMuted ? "\uD83D\uDD14 Unmute" : "\uD83D\uDD15 Mute");
        return super.onPrepareOptionsMenu(menu);
    }

    @Override public boolean onOptionsItemSelected(android.view.MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_view_profile)       { navController.openAvatarZoom();                    return true; }
        if (id == R.id.action_edit_profile)        { navController.openEditProfile();                   return true; }
        if (id == R.id.action_starred) {
            Intent i = new Intent(this, StarredMessagesActivity.class);
            i.putExtra("chatId", chatId); i.putExtra("isGroup", false); startActivity(i); return true;
        }
        if (id == R.id.action_search)             { searchController.openSearch();                     return true; }
        if (id == R.id.action_mute)               { presenceController.toggleMute();                   return true; }
        if (id == R.id.action_block)              { blockController.confirmBlockUser();                 return true; }
        if (id == R.id.action_clear_chat)         { confirmClearChat();                                 return true; }
        if (id == R.id.action_chat_customization) { themeController.showChatCustomizationMenu();       return true; }
        if (id == R.id.action_media_links_docs)   { navController.openAllMediaLinksDocs();             return true; }
        if (id == R.id.action_security)           { themeController.showChatSecuritySheet();           return true; }
        if (id == R.id.action_chat_privacy)       { themeController.showChatPrivacySheet();            return true; }
        if (id == R.id.action_small_window)       { navController.openSmallWindow();                   return true; }
        return super.onOptionsItemSelected(item);
    }

    // ──────────────────────────────────────────────────────────────────────
    // CLEAR CHAT
    // ──────────────────────────────────────────────────────────────────────

    private void confirmClearChat() {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Clear chat?")
            .setMessage("All messages will be deleted locally.")
            .setPositiveButton("Clear", (dlg, w) -> {
                ioExecutor.execute(() -> db.messageDao().deleteAllForChat(chatId));
                CacheManager.getInstance(this).invalidateMessages(chatId);
                showToast("Chat cleared");
            })
            .setNegativeButton("Cancel", null).show();
    }

    // ──────────────────────────────────────────────────────────────────────
    // PERMISSIONS
    // ──────────────────────────────────────────────────────────────────────

    @Override public void onRequestPermissionsResult(int requestCode,
            @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == ChatMediaController.REQ_CAMERA
                && grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED)
            mediaController.launchCamera();
        if (requestCode == ChatMediaController.REQ_AUDIO
                && grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED)
            mediaController.toggleRecording();
    }

    // ──────────────────────────────────────────────────────────────────────
    // UTILS
    // ──────────────────────────────────────────────────────────────────────

    private String buildTypePreview(Message m) {
        if (m.type == null) return "[message]";
        switch (m.type) {
            case "image": return "\uD83D\uDCF7 Photo";
            case "gif":   return "\uD83C\uDEDF\uFE0F GIF";
            case "video": return "\uD83C\uDFAC Video";
            case "audio": return "\uD83C\uDFA4 Voice message";
            case "file":  return "\uD83D\uDCCE " + (m.fileName != null ? m.fileName : "File");
            default:      return "[" + m.type + "]";
        }
    }
}
