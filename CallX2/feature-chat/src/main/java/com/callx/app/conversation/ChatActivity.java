package com.callx.app.conversation;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.callx.app.cache.CacheManager;
import com.callx.app.chat.R;
import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.conversation.delegates.ChatBlockDelegate;
import com.callx.app.conversation.delegates.ChatCustomizeDelegate;
import com.callx.app.conversation.delegates.ChatMediaDelegate;
import com.callx.app.conversation.delegates.ChatNetworkDelegate;
import com.callx.app.conversation.delegates.ChatPagingDelegate;
import com.callx.app.conversation.delegates.ChatPresenceDelegate;
import com.callx.app.conversation.delegates.ChatReplyDelegate;
import com.callx.app.conversation.delegates.ChatSearchDelegate;
import com.callx.app.conversation.delegates.ChatSenderDelegate;
import com.callx.app.conversation.delegates.ChatUiDelegate;
import com.callx.app.db.AppDatabase;
import com.callx.app.models.Message;
import com.callx.app.utils.FileUtils;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * ══════════════════════════════════════════════════════════════════════
 * ChatActivity — Coordinator (slim)
 * ══════════════════════════════════════════════════════════════════════
 *
 * Ye class sirf lifecycle, menu, registerForActivityResult, aur
 * delegate wiring sambhalti hai. Sab actual logic delegates mein:
 *
 *   ChatPagingDelegate      — Paging 3, Firebase listener, Room sync
 *   ChatSenderDelegate      — Message send, push, offline retry
 *   ChatMediaDelegate       — Upload, camera, voice recording, pickers
 *   ChatPresenceDelegate    — Typing, online status, mark read
 *   ChatBlockDelegate       — Block, mute, perma-block, special request
 *   ChatReplyDelegate       — Swipe-to-reply, reply bar, navigate
 *   ChatSearchDelegate      — In-chat search
 *   ChatCustomizeDelegate   — Theme, wallpaper, privacy, pinned
 *   ChatUiDelegate          — Toolbar, animations, multi-select
 *   ChatNetworkDelegate     — Network monitor, offline banner
 *
 * NOTE: registerForActivityResult() calls MUST remain in Activity.
 * ══════════════════════════════════════════════════════════════════════
 */
public class ChatActivity extends AppCompatActivity
        implements ChatCustomizeDelegate.ChatCustomizationCallback {

    // ── Intent extras ─────────────────────────────────────────────────────
    public static final String EXTRA_PARTNER_UID   = "partnerUid";
    public static final String EXTRA_PARTNER_NAME  = "partnerName";
    public static final String EXTRA_PARTNER_PHOTO = "partnerPhoto";
    public static final String EXTRA_PARTNER_THUMB = "partnerThumb";
    public static final String EXTRA_CHAT_ID       = "chatId";

    // ── State ─────────────────────────────────────────────────────────────
    private ActivityChatBinding binding;
    private String               chatId;
    private String               currentUid;
    private String               partnerUid;
    private String               partnerName;
    private String               currentName;
    private AppDatabase          db;
    private Executor             ioExecutor;
    private DatabaseReference    messagesRef;

    private final Set<String> selectedMessageIds = new HashSet<>();

    // ── Delegates ─────────────────────────────────────────────────────────
    private ChatPagingDelegate    pagingDelegate;
    private ChatSenderDelegate    senderDelegate;
    private ChatMediaDelegate     mediaDelegate;
    private ChatPresenceDelegate  presenceDelegate;
    private ChatBlockDelegate     blockDelegate;
    private ChatReplyDelegate     replyDelegate;
    private ChatSearchDelegate    searchDelegate;
    private ChatCustomizeDelegate customizeDelegate;
    private ChatUiDelegate        uiDelegate;
    private ChatNetworkDelegate   networkDelegate;

    // ── Activity-result launchers (MUST stay in Activity) ─────────────────
    private ActivityResultLauncher<String> imagePicker;
    private ActivityResultLauncher<String> videoPicker;
    private ActivityResultLauncher<String> audioPicker;
    private ActivityResultLauncher<String> filePicker;
    private ActivityResultLauncher<Uri>    cameraCapturer;
    private ActivityResultLauncher<String> wallpaperPicker;

    // ══════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        readIntentExtras();
        if (!initDependencies()) { finish(); return; }

        registerPickers();
        initDelegates();
        setupInputBar();
        setupMultiSelectBar();

        pagingDelegate.setupPagingRecyclerView(buildActionListener(), buildMultiSelectListener());
        pagingDelegate.observePagedMessages();
        pagingDelegate.startRealtimeListener();

        uiDelegate.setupToolbar(this,
                getIntent().getStringExtra(EXTRA_PARTNER_PHOTO),
                getIntent().getStringExtra(EXTRA_PARTNER_THUMB));
        uiDelegate.watchPartnerAvatar();
        uiDelegate.setupSocialButtons();

        presenceDelegate.watchTyping();
        presenceDelegate.watchPartnerStatus();

        blockDelegate.watchMute();
        blockDelegate.watchBlock();
        blockDelegate.watchPartnerPermaBlock();
        blockDelegate.watchMyPermaBlock();

        customizeDelegate.watchPinnedMessage();
        customizeDelegate.applyScreenTheme();

        networkDelegate.setupNetworkMonitor();
        networkDelegate.scheduleExpiryCleanup();

        replyDelegate.setupSwipeToReply(currentUid);
        replyDelegate.setupFabBackToLatest();

        senderDelegate.restoreDraft();
        blockDelegate.checkAndShowUnblockJoy();
        blockDelegate.checkAndShowPendingSpecialRequest();
        presenceDelegate.markMessagesRead();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra("show_unblock_joy", false))
            blockDelegate.checkAndShowUnblockJoy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        presenceDelegate.clearOurTypingStatus();
        senderDelegate.saveDraft();
        FirebaseUtils.getUserRef(currentUid).child("online").setValue(false);
        FirebaseUtils.getUserRef(currentUid).child("lastSeen").setValue(System.currentTimeMillis());
    }

    @Override
    protected void onResume() {
        super.onResume();
        FirebaseUtils.getUserRef(currentUid).child("online").setValue(true);
        presenceDelegate.markMessagesRead();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pagingDelegate.detach();
        presenceDelegate.detach();
        blockDelegate.detach();
        customizeDelegate.detach();
        networkDelegate.detach();
        replyDelegate.release();
    }

    // ── Intent reading ────────────────────────────────────────────────────

    private void readIntentExtras() {
        Intent i   = getIntent();
        partnerUid = i.getStringExtra(EXTRA_PARTNER_UID);
        partnerName = i.getStringExtra(EXTRA_PARTNER_NAME);
        chatId     = i.getStringExtra(EXTRA_CHAT_ID);
        if (chatId == null && partnerUid != null)
            chatId = buildChatId(getMyUid(), partnerUid);
    }

    private boolean initDependencies() {
        FirebaseUser me = FirebaseAuth.getInstance().getCurrentUser();
        if (me == null) return false;
        currentUid  = me.getUid();
        currentName = me.getDisplayName() != null ? me.getDisplayName() : "User";
        db          = AppDatabase.getInstance(getApplicationContext());
        ioExecutor  = Executors.newFixedThreadPool(3);
        messagesRef = FirebaseUtils.db().getReference("messages").child(chatId);
        return true;
    }

    private String getMyUid() {
        FirebaseUser me = FirebaseAuth.getInstance().getCurrentUser();
        return me != null ? me.getUid() : "";
    }

    private String buildChatId(String a, String b) {
        return a.compareTo(b) < 0 ? a + "_" + b : b + "_" + a;
    }

    // ══════════════════════════════════════════════════════════════════════
    // DELEGATE INITIALISATION
    // ══════════════════════════════════════════════════════════════════════

    private void initDelegates() {
        pagingDelegate = new ChatPagingDelegate(
                this, binding, chatId, currentUid, db, ioExecutor, messagesRef,
                new ChatPagingDelegate.Callback() {
                    @Override public void onMessageAdded(Message m) {
                        presenceDelegate.markRead(m);
                        CacheManager.getInstance(ChatActivity.this)
                                .bumpLastSyncTimestamp(chatId, m.timestamp);
                    }
                    @Override public void runOnUiThread(Runnable r) { ChatActivity.this.runOnUiThread(r); }
                    @Override public androidx.lifecycle.LifecycleOwner getLifecycleOwner() { return ChatActivity.this; }
                });

        presenceDelegate = new ChatPresenceDelegate(
                this, binding, chatId, currentUid, partnerUid, db, ioExecutor, messagesRef);

        replyDelegate = new ChatReplyDelegate(
                this, binding, db, ioExecutor,
                new ChatReplyDelegate.Callback() {
                    @Override public void runOnUiThread(Runnable r) { ChatActivity.this.runOnUiThread(r); }
                    @Override public String getCurrentUid() { return currentUid; }
                    @Override public MessagePagingAdapter getAdapter() { return pagingDelegate.pagingAdapter; }
                });

        senderDelegate = new ChatSenderDelegate(
                this, binding, chatId, currentUid, partnerUid, currentName, db, ioExecutor, messagesRef,
                new ChatSenderDelegate.Callback() {
                    @Override public boolean isOnline() { return networkDelegate != null && networkDelegate.isOnline(); }
                    @Override public boolean isMuted()  { return blockDelegate != null && blockDelegate.isMuted; }
                    @Override public Message buildOutgoing() { return ChatActivity.this.buildOutgoing(); }
                    @Override public void clearReply()  { replyDelegate.clearReply(); }
                    @Override public void runOnUiThread(Runnable r) { ChatActivity.this.runOnUiThread(r); }
                    @Override public void clearOurTypingStatus() { presenceDelegate.clearOurTypingStatus(); }
                });

        mediaDelegate = new ChatMediaDelegate(this, binding,
                new ChatMediaDelegate.Callback() {
                    @Override public boolean isOnline() { return networkDelegate != null && networkDelegate.isOnline(); }
                    @Override public Message buildOutgoing() { return ChatActivity.this.buildOutgoing(); }
                    @Override public void pushMessage(Message m, String p) { senderDelegate.pushMessage(m, p); }
                    @Override public void clearReply() { replyDelegate.clearReply(); }
                    @Override public void runOnUiThread(Runnable r) { ChatActivity.this.runOnUiThread(r); }
                });
        mediaDelegate.setLaunchers(imagePicker, videoPicker, audioPicker, filePicker, cameraCapturer, wallpaperPicker);
        mediaDelegate.setWallpaperPickedListener(uri -> customizeDelegate.showWallpaperScopeDialog(uri));

        blockDelegate = new ChatBlockDelegate(
                this, binding, chatId, currentUid, partnerUid, partnerName, currentName,
                new ChatBlockDelegate.Callback() {
                    @Override public void applyBlockUiChanged() { blockDelegate.applyBlockUi(); }
                    @Override public void invalidateOptionsMenu() { ChatActivity.this.invalidateOptionsMenu(); }
                });

        searchDelegate = new ChatSearchDelegate(this, binding, chatId, db, ioExecutor);

        customizeDelegate = new ChatCustomizeDelegate(this, binding, chatId, currentUid, partnerName,
                new ChatCustomizeDelegate.Callback() {
                    @Override public MessagePagingAdapter getAdapter() { return pagingDelegate.pagingAdapter; }
                    @Override public void applyWallpaper() { customizeDelegate.applyWallpaper(); }
                });

        uiDelegate = new ChatUiDelegate(this, binding, chatId, currentUid, partnerUid, partnerName,
                new ChatUiDelegate.Callback() {
                    @Override public void runOnUiThread(Runnable r) { ChatActivity.this.runOnUiThread(r); }
                    @Override public void onCallClicked() { startCall(false); }
                    @Override public void onVideoCallClicked() { startCall(true); }
                });

        networkDelegate = new ChatNetworkDelegate(
                this, binding, chatId, currentUid, partnerUid, partnerName, db, ioExecutor, messagesRef,
                new ChatNetworkDelegate.Callback() {
                    @Override public void onNetworkAvailable() {}
                    @Override public void onNetworkLost() {}
                    @Override public void retryPendingMessages() { senderDelegate.retryPendingMessages(); }
                    @Override public void updateSendButtons(boolean o) { senderDelegate.updateSendButtonState(o); }
                    @Override public void runOnUiThread(Runnable r) { ChatActivity.this.runOnUiThread(r); }
                });
    }

    // ══════════════════════════════════════════════════════════════════════
    // PICKER REGISTRATION (must stay in Activity)
    // ══════════════════════════════════════════════════════════════════════

    private void registerPickers() {
        imagePicker = registerForActivityResult(new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) mediaDelegate.uploadAndSend(uri, "image", "image", null); });
        videoPicker = registerForActivityResult(new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) mediaDelegate.uploadAndSend(uri, "video", "video", null); });
        audioPicker = registerForActivityResult(new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) mediaDelegate.uploadAndSend(uri, "audio", "raw", null); });
        filePicker = registerForActivityResult(new ActivityResultContracts.GetContent(),
                uri -> { if (uri == null) return;
                         mediaDelegate.uploadAndSend(uri, "file", "raw", FileUtils.fileName(this, uri)); });
        wallpaperPicker = registerForActivityResult(new ActivityResultContracts.GetContent(),
                uri -> mediaDelegate.handleWallpaperPicked(uri));
        cameraCapturer = registerForActivityResult(new ActivityResultContracts.TakePicture(),
                success -> { if (success && mediaDelegate.cameraOutputUri != null)
                    mediaDelegate.uploadAndSend(mediaDelegate.cameraOutputUri, "image", "image", null); });
    }

    @Override public void launchWallpaperPicker() { mediaDelegate.showWallpaperPicker(); }

    // ══════════════════════════════════════════════════════════════════════
    // INPUT BAR
    // ══════════════════════════════════════════════════════════════════════

    private void setupInputBar() {
        binding.btnSend.setOnClickListener(v -> senderDelegate.sendTextMessage());
        binding.btnMic.setOnClickListener(v -> mediaDelegate.toggleRecording());
        binding.btnAttach.setOnClickListener(v -> mediaDelegate.showAttachSheet());
        binding.btnCamera.setOnClickListener(v -> mediaDelegate.launchCamera());
        binding.btnCloseReply.setOnClickListener(v -> replyDelegate.clearReply());

        binding.etMessage.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(android.text.Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                boolean has = s != null && s.toString().trim().length() > 0;
                binding.btnSend.setVisibility(has ? View.VISIBLE : View.GONE);
                binding.btnMic.setVisibility(has ? View.GONE : View.VISIBLE);
                if (has) presenceDelegate.setOurTypingStatus(true);
                else     presenceDelegate.clearOurTypingStatus();
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    // MULTI-SELECT
    // ══════════════════════════════════════════════════════════════════════

    private void setupMultiSelectBar() {
        if (binding.llMultiSelectBar == null) return;
        if (binding.btnMultiDelete  != null) binding.btnMultiDelete.setOnClickListener(v -> deleteSelectedMessages());
        if (binding.btnMultiForward != null) binding.btnMultiForward.setOnClickListener(v -> forwardSelectedMessages());
        if (binding.btnMultiClose   != null) binding.btnMultiClose.setOnClickListener(v -> {
            selectedMessageIds.clear();
            if (pagingDelegate.pagingAdapter != null) pagingDelegate.pagingAdapter.clearSelection();
            uiDelegate.hideMultiSelectBar();
        });
    }

    private MessagePagingAdapter.ActionListener buildActionListener() {
        return new MessagePagingAdapter.ActionListener() {
            @Override public void onCopy(Message m)             { copyText(m); }
            @Override public void onEdit(Message m)             { editMessage(m); }
            @Override public void onDelete(Message m)           { confirmDeleteMessage(m); }
            @Override public void onStar(Message m)             { toggleStar(m); }
            @Override public void onReply(Message m)            { replyDelegate.startReply(m); }
            @Override public void onReact(Message m, String e)  { sendReaction(m, e); }
            @Override public void onForward(Message m)          { forwardMessage(m); }
            @Override public void onPin(Message m)              { customizeDelegate.pinMessage(m.id, chatId); }
            @Override public void onInfo(Message m)             { uiDelegate.showMessageInfoDialog(m); }
            @Override public void onNavigateToReply(String id)  { replyDelegate.navigateToOriginal(id); }
            @Override public void onRead(Message m)             { presenceDelegate.markRead(m); }
        };
    }

    private MessagePagingAdapter.MultiSelectListener buildMultiSelectListener() {
        return ids -> {
            selectedMessageIds.clear();
            selectedMessageIds.addAll(ids);
            if (ids.isEmpty()) uiDelegate.hideMultiSelectBar();
            else { uiDelegate.showMultiSelectBar(ids, pagingDelegate.pagingAdapter); uiDelegate.updateMultiSelectCount(ids.size()); }
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    // MESSAGE ACTIONS
    // ══════════════════════════════════════════════════════════════════════

    private void copyText(Message m) {
        if (m.text == null || m.text.isEmpty()) return;
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("message", m.text));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }

    private void editMessage(Message m) {
        if (m == null || m.id == null) return;
        if (!currentUid.equals(m.senderId)) {
            Toast.makeText(this, "Sirf apne messages edit kar sakte ho", Toast.LENGTH_SHORT).show(); return;
        }
        binding.etMessage.setText(m.text);
        binding.etMessage.setSelection(m.text != null ? m.text.length() : 0);
        binding.btnSend.setOnClickListener(v -> {
            String edited = binding.etMessage.getText().toString().trim();
            if (edited.isEmpty()) return;
            m.text = edited; m.edited = true;
            messagesRef.child(m.id).child("text").setValue(edited);
            messagesRef.child(m.id).child("edited").setValue(true);
            ioExecutor.execute(() -> db.messageDao().updateTextAndEdited(m.id, edited, true));
            binding.etMessage.setText("");
            binding.btnSend.setOnClickListener(x -> senderDelegate.sendTextMessage());
        });
    }

    private void toggleStar(Message m) {
        if (m == null || m.id == null) return;
        boolean nowStarred = !Boolean.TRUE.equals(m.starred);
        messagesRef.child(m.id).child("starred").setValue(nowStarred);
        ioExecutor.execute(() -> db.messageDao().updateStarred(m.id, nowStarred));
    }

    private void sendReaction(Message m, String emoji) {
        if (m == null || m.id == null) return;
        messagesRef.child(m.id).child("reactions").child(currentUid).setValue(emoji);
    }

    private void confirmDeleteMessage(Message m) {
        if (m == null || m.id == null) return;
        String[] opts = currentUid.equals(m.senderId)
                ? new String[]{"Delete for me", "Delete for everyone"}
                : new String[]{"Delete for me"};
        new android.app.AlertDialog.Builder(this).setTitle("Delete message?")
                .setItems(opts, (d, which) -> {
                    if (which == 1) {
                        messagesRef.child(m.id).child("deleted").setValue(true);
                        messagesRef.child(m.id).child("text").setValue("\uD83D\uDEAB This message was deleted");
                    }
                    ioExecutor.execute(() -> db.messageDao().softDelete(m.id));
                }).setNegativeButton("Cancel", null).show();
    }

    private void forwardMessage(Message m) {
        if (m == null) return;
        try {
            Class<?> cls = Class.forName("com.callx.app.activities.ForwardMessageActivity");
            Intent i = new Intent(this, cls);
            i.putExtra("messageId", m.id); i.putExtra("text", m.text);
            i.putExtra("type", m.type); i.putExtra("mediaUrl", m.mediaUrl);
            startActivity(i);
        } catch (ClassNotFoundException e) {
            android.util.Log.e("ChatActivity", "ForwardMessageActivity not found", e);
        }
    }

    private void forwardSelectedMessages() {
        if (selectedMessageIds.isEmpty()) return;
        try {
            Class<?> cls = Class.forName("com.callx.app.activities.ForwardMessageActivity");
            Intent i = new Intent(this, cls);
            i.putStringArrayListExtra("messageIds", new ArrayList<>(selectedMessageIds));
            startActivity(i);
        } catch (ClassNotFoundException e) {
            android.util.Log.e("ChatActivity", "ForwardMessageActivity not found", e);
        }
        selectedMessageIds.clear();
        if (pagingDelegate.pagingAdapter != null) pagingDelegate.pagingAdapter.clearSelection();
        uiDelegate.hideMultiSelectBar();
    }

    private void deleteSelectedMessages() {
        if (selectedMessageIds.isEmpty()) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle("Delete " + selectedMessageIds.size() + " messages?")
                .setPositiveButton("Delete", (d, w) -> {
                    for (String id : selectedMessageIds) ioExecutor.execute(() -> db.messageDao().softDelete(id));
                    selectedMessageIds.clear();
                    if (pagingDelegate.pagingAdapter != null) pagingDelegate.pagingAdapter.clearSelection();
                    uiDelegate.hideMultiSelectBar();
                }).setNegativeButton("Cancel", null).show();
    }

    // ══════════════════════════════════════════════════════════════════════
    // CALLS
    // ══════════════════════════════════════════════════════════════════════

    private void startCall(boolean video) {
        try {
            Class<?> cls = Class.forName("com.callx.app.calling.CallActivity");
            Intent i = new Intent(this, cls);
            i.putExtra("calleeUid", partnerUid); i.putExtra("calleeName", partnerName);
            i.putExtra("calleeAvatar", getIntent().getStringExtra(EXTRA_PARTNER_PHOTO));
            i.putExtra("callType", video ? "video" : "audio");
            startActivity(i);
        } catch (ClassNotFoundException e) {
            Toast.makeText(this, "Call unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // MENU
    // ══════════════════════════════════════════════════════════════════════

    @Override public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.chat_menu, menu); return true;
    }

    @Override public boolean onPrepareOptionsMenu(android.view.Menu menu) {
        MenuItem mi = menu.findItem(R.id.action_mute);
        if (mi != null) mi.setTitle(blockDelegate != null && blockDelegate.isMuted ? "\uD83D\uDD14 Unmute" : "\uD83D\uDD15 Mute");
        return super.onPrepareOptionsMenu(menu);
    }

    @Override public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home)              { onBackPressed();                              return true; }
        if (id == R.id.action_view_profile)       { uiDelegate.openAvatarZoom();                 return true; }
        if (id == R.id.action_edit_profile)       { uiDelegate.openEditProfile();                return true; }
        if (id == R.id.action_search)             { searchDelegate.openSearch();                 return true; }
        if (id == R.id.action_mute)               { blockDelegate.toggleMute();                  return true; }
        if (id == R.id.action_block)              { blockDelegate.confirmBlockUser();             return true; }
        if (id == R.id.action_clear_chat)         { networkDelegate.confirmClearChat();           return true; }
        if (id == R.id.action_chat_customization) { customizeDelegate.showChatCustomizationMenu(); return true; }
        if (id == R.id.action_security)           { customizeDelegate.showChatSecuritySheet();    return true; }
        if (id == R.id.action_privacy)            { customizeDelegate.showChatPrivacySheet();     return true; }
        if (id == R.id.action_small_window)       { networkDelegate.openSmallWindow();            return true; }
        if (id == R.id.action_media_links_docs) {
            searchDelegate.openAllMediaLinksDocs(chatId, partnerName); return true;
        }
        if (id == R.id.action_starred) {
            Intent i = new Intent(this, com.callx.app.starred.StarredMessagesActivity.class);
            i.putExtra("chatId", chatId); i.putExtra("isGroup", false); startActivity(i); return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ══════════════════════════════════════════════════════════════════════
    // PERMISSIONS
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == ChatMediaDelegate.REQ_CAMERA
                && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
            mediaDelegate.launchCamera();
        if (requestCode == ChatMediaDelegate.REQ_AUDIO
                && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
            mediaDelegate.toggleRecording();
    }

    // ══════════════════════════════════════════════════════════════════════
    // SHARED HELPER — build outgoing Message template
    // ══════════════════════════════════════════════════════════════════════

    private Message buildOutgoing() {
        Message m    = new Message();
        m.senderId   = currentUid;
        m.senderName = currentName;
        m.timestamp  = System.currentTimeMillis();
        m.status     = "sending";
        replyDelegate.applyReplyFieldsToMessage(m, currentUid);
        return m;
    }
}
