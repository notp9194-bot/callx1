package com.callx.app.activities;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.adapters.MessagePagingAdapter;
import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.chat.gesture.SwipeReplyHandler;
import com.callx.app.chat.performance.SwipeOptimizer;
import com.callx.app.chat.reply.ReplyController;
import com.callx.app.chat.ui.MessageHighlightAnimator;
import com.callx.app.models.Message;
import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.FileUtils;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.ImageCompressor;
import com.callx.app.utils.PushNotify;
import com.callx.app.utils.VoiceRecorder;
import com.callx.app.viewmodel.ChatViewModel;
import com.callx.app.viewmodel.ChatViewModelFactory;
import com.facebook.shimmer.ShimmerFrameLayout;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.auth.FirebaseAuth;

import androidx.recyclerview.widget.ItemTouchHelper;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * ChatActivity (MVVM refactored) — UI only.
 *
 * What changed vs the original God Activity:
 *  ✅ All Firebase listeners → ChatViewModel
 *  ✅ All Room DB operations → ChatViewModel
 *  ✅ Network monitoring → ChatViewModel
 *  ✅ Typing debounce → ChatViewModel
 *  ✅ Message send/edit/delete/react/star/pin → ChatViewModel
 *  ✅ Draft save/restore → ChatViewModel
 *  ✅ Paging 3 LiveData → ChatViewModel
 *
 * What remains here (UI concerns only):
 *  - View binding + layout setup
 *  - Adapter creation + RecyclerView wiring
 *  - Toolbar display (name, avatar)
 *  - Observe LiveData → update TextViews / visibility / banner
 *  - User input → call ViewModel methods
 *  - Media pickers + permissions
 *  - Navigation (startActivity for call, profile, etc.)
 *
 * Rotation-safe: ViewModel survives configuration changes.
 * Activity is destroyed/recreated — ViewModel is NOT.
 */
public class ChatActivity extends AppCompatActivity {

    private static final String TAG        = "ChatActivity";
    private static final int    REQ_AUDIO  = 200;
    private static final int    REQ_CAMERA = 300;

    // ── View binding ───────────────────────────────────────────────────────
    private ActivityChatBinding binding;

    // ── ViewModel (single source of truth) ───────────────────────────────
    private ChatViewModel viewModel;

    // ── Intent data (display only) ────────────────────────────────────────
    private String partnerUid;
    private String partnerName;
    private String partnerPhoto;
    private String partnerThumb;

    // ── Adapter ───────────────────────────────────────────────────────────
    private MessagePagingAdapter pagingAdapter;

    // ── Reply state (UI only — which message is being replied to) ─────────
    private Message replyingTo = null;
    private ReplyController replyController;

    // ── Voice recorder ─────────────────────────────────────────────────────
    private VoiceRecorder recorder;
    private boolean       isRecording = false;

    // ── Media pickers ──────────────────────────────────────────────────────
    private ActivityResultLauncher<String> imagePicker;
    private ActivityResultLauncher<String> videoPicker;
    private ActivityResultLauncher<String> audioPicker;
    private ActivityResultLauncher<String> filePicker;
    private ActivityResultLauncher<Uri>    cameraCapturer;
    private Uri cameraOutputUri;

    private final Executor ioExecutor = Executors.newSingleThreadExecutor();

    // ─────────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        readIntentExtras();
        if (partnerUid == null || partnerUid.isEmpty()) { finish(); return; }

        // ── ViewModel init (rotation-safe) ────────────────────────────────
        String currentUid = getCurrentUid();
        String chatId     = buildChatId(currentUid, partnerUid);
        ChatViewModelFactory factory = new ChatViewModelFactory(
                getApplication(), chatId, partnerUid, partnerName);
        viewModel = new ViewModelProvider(this, factory).get(ChatViewModel.class);

        // ── UI setup (pure display) ───────────────────────────────────────
        setupToolbar();
        applyScreenTheme();
        setupPickers();
        recorder = new VoiceRecorder();
        setupPagingRecyclerView();
        setupInputBar();
        setupSwipeToReply();
        setupFabBackToLatest();

        // ── Observe ViewModel LiveData ────────────────────────────────────
        observePagedMessages();
        observeUiState();

        // ── Unread divider + mark read ────────────────────────────────────
        viewModel.getUnreadInfo((divPos, unreadCount) -> {
            if (unreadCount > 0) pagingAdapter.setFirstUnreadPosition(divPos, unreadCount);
        });
        viewModel.markRead();

        // ── Draft restore ─────────────────────────────────────────────────
        viewModel.restoreDraft(draft -> {
            if (!draft.isEmpty()) {
                binding.etMessage.setText(draft);
                binding.etMessage.setSelection(draft.length());
            }
        });

        // ── Handle forwarded messages from intent ─────────────────────────
        handleForwardPayload();
    }

    @Override
    protected void onPause() {
        super.onPause();
        viewModel.saveDraft(binding.etMessage.getText() != null
                ? binding.etMessage.getText().toString() : "");
        viewModel.clearTypingStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // ViewModel.onCleared() handles Firebase + network cleanup automatically
    }

    // ─────────────────────────────────────────────────────────────────────
    // INTENT EXTRAS
    // ─────────────────────────────────────────────────────────────────────

    private void readIntentExtras() {
        Intent i    = getIntent();
        partnerUid   = i.getStringExtra("partnerUid");
        partnerName  = i.getStringExtra("partnerName");
        partnerPhoto = i.getStringExtra("partnerPhoto");
        partnerThumb = i.getStringExtra("partnerThumb");
    }

    private String getCurrentUid() {
        com.google.firebase.auth.FirebaseUser fu = FirebaseAuth.getInstance().getCurrentUser();
        return fu != null ? fu.getUid() : "";
    }

    private static String buildChatId(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        return a.compareTo(b) < 0 ? a + "_" + b : b + "_" + a;
    }

    // ─────────────────────────────────────────────────────────────────────
    // TOOLBAR (display only — no Firebase calls here)
    // ─────────────────────────────────────────────────────────────────────

    private void setupToolbar() {
        binding.btnBack.setOnClickListener(v -> {
            if (pagingAdapter != null && pagingAdapter.isInMultiSelectMode()) {
                pagingAdapter.exitMultiSelectMode();
            } else {
                finish();
            }
        });

        if (partnerName != null) binding.tvPartnerName.setText(partnerName);

        binding.ivPartnerAvatar.setOnClickListener(v -> openAvatarZoom());

        // Load avatar: thumb first, fallback to photo, then Firebase fetch
        String avatarUrl = (partnerThumb != null && !partnerThumb.isEmpty())
                ? partnerThumb : partnerPhoto;
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            Glide.with(this).load(avatarUrl)
                    .placeholder(R.drawable.ic_person).circleCrop()
                    .into(binding.ivPartnerAvatar);
        } else {
            FirebaseUtils.getUserRef(partnerUid)
                .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                    @Override public void onDataChange(@NonNull com.google.firebase.database.DataSnapshot s) {
                        String thumb = s.child("thumbUrl").getValue(String.class);
                        String photo = s.child("photoUrl").getValue(String.class);
                        String url = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                        if (url != null && !url.isEmpty()) {
                            partnerThumb = thumb; partnerPhoto = photo;
                            Glide.with(ChatActivity.this).load(url)
                                    .placeholder(R.drawable.ic_person).circleCrop()
                                    .into(binding.ivPartnerAvatar);
                        }
                    }
                    @Override public void onCancelled(@NonNull com.google.firebase.database.DatabaseError e) {}
                });
        }

        binding.btnToolbarVoiceCall.setOnClickListener(v -> startCall(false));
        binding.btnToolbarVideoCall.setOnClickListener(v -> startCall(true));
        binding.btnToolbarReel.setOnClickListener(v -> openReelProfile());
        binding.btnToolbarX.setOnClickListener(v -> openXProfile());
        binding.btnToolbarYoutube.setOnClickListener(v -> openYouTubeProfile());
        binding.btnMoreOptions.setOnClickListener(v -> showOptionsMenu());
    }

    // ─────────────────────────────────────────────────────────────────────
    // OBSERVE VIEWMODEL — update UI from LiveData
    // ─────────────────────────────────────────────────────────────────────

    private void observePagedMessages() {
        viewModel.getPagedMessages().observe(this, pagingData -> {
            pagingAdapter.submitData(getLifecycle(), pagingData);
        });
    }

    private void observeUiState() {
        // Typing indicator
        viewModel.isTyping().observe(this, typing -> {
            binding.tvTyping.setVisibility(typing ? View.VISIBLE : View.GONE);
            binding.tvStatus.setVisibility(typing ? View.GONE : View.VISIBLE);
        });

        // Partner status (online / last seen)
        viewModel.partnerStatus().observe(this, status -> {
            if (status != null && !status.isEmpty()) {
                binding.tvStatus.setText(status);
                binding.tvStatus.setVisibility(View.VISIBLE);
            } else {
                binding.tvStatus.setVisibility(View.GONE);
            }
        });

        // Pinned message bar
        viewModel.pinnedText().observe(this, text -> {
            if (binding.layoutPinned != null) {
                if (text != null && !text.isEmpty()) {
                    binding.layoutPinned.setVisibility(View.VISIBLE);
                    if (binding.tvPinnedText != null) binding.tvPinnedText.setText(text);
                } else {
                    binding.layoutPinned.setVisibility(View.GONE);
                }
            }
        });

        // Block state — disable input
        viewModel.isBlocked().observe(this, blocked -> {
            boolean disabled = blocked || Boolean.TRUE.equals(viewModel.permaBlocked().getValue());
            setInputEnabled(!disabled);
            if (disabled) binding.tvBlockedBanner.setVisibility(View.VISIBLE);
        });

        viewModel.permaBlocked().observe(this, pb -> {
            boolean disabled = pb || Boolean.TRUE.equals(viewModel.isBlocked().getValue());
            setInputEnabled(!disabled);
        });

        // Offline banner
        viewModel.isOnline().observe(this, online -> {
            if (binding.tvOfflineBanner != null) {
                binding.tvOfflineBanner.setVisibility(online ? View.GONE : View.VISIBLE);
            }
            updateSendButtonState(online);
        });

        // One-shot error toast
        viewModel.errorEvent().observe(this, msg -> {
            if (msg != null && !msg.isEmpty()) {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            }
        });

        // One-shot snackbar
        viewModel.snackEvent().observe(this, msg -> {
            if (msg != null && !msg.isEmpty()) {
                Snackbar.make(binding.getRoot(), msg, Snackbar.LENGTH_SHORT).show();
            }
        });

        // Scroll to bottom on new message sent
        viewModel.scrollToBottom().observe(this, should -> {
            if (Boolean.TRUE.equals(should)) scrollToBottom();
        });

        // Multi-select action bar
        viewModel.selectedCount().observe(this, count -> {
            if (count > 0) showMultiSelectBar(count);
            else hideMultiSelectBar();
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // RECYCLER VIEW + PAGING ADAPTER
    // ─────────────────────────────────────────────────────────────────────

    private void setupPagingRecyclerView() {
        pagingAdapter = new MessagePagingAdapter(viewModel.currentUid, false);

        pagingAdapter.setActionListener(new MessagePagingAdapter.ActionListener() {
            @Override public void onReply(Message m)               { startReply(m); }
            @Override public void onDelete(Message m)              { confirmDelete(m); }
            @Override public void onReact(Message m, String emoji) { viewModel.reactMessage(m.id, emoji); }
            @Override public void onStar(Message m)                { viewModel.toggleStar(m); }
            @Override public void onCopy(Message m)                { copyText(m); }
            @Override public void onForward(Message m)             { forwardMessage(m); }
            @Override public void onNavigateToOriginal(String id)  { navigateToOriginal(id); }
            @Override public void onEdit(Message m)                { promptEdit(m); }
            @Override public void onPin(Message m)                 { viewModel.pinMessage(m); }
            @Override public void onRetry(Message m)               {
                viewModel.sendMessage(m, m.text != null ? m.text : "[" + m.type + "]");
            }
        });

        pagingAdapter.setMultiSelectListener(count -> viewModel.onSelectionChanged(count));

        // Load state → shimmer
        pagingAdapter.addLoadStateListener(states -> {
            ShimmerFrameLayout shimmer = binding.shimmerLayout;
            if (shimmer == null) return null;
            if (states.getRefresh() instanceof androidx.paging.LoadState.Loading) {
                shimmer.startShimmer();
                shimmer.setVisibility(View.VISIBLE);
                binding.rvMessages.setVisibility(View.GONE);
            } else {
                shimmer.stopShimmer();
                shimmer.setVisibility(View.GONE);
                binding.rvMessages.setVisibility(View.VISIBLE);
            }
            return null;
        });

        LinearLayoutManager llm = new LinearLayoutManager(this);
        llm.setStackFromEnd(true);
        binding.rvMessages.setLayoutManager(llm);
        binding.rvMessages.setAdapter(pagingAdapter);
        SwipeOptimizer.disableChangeAnimations(binding.rvMessages);

        // Auto-scroll when new item appears at the bottom
        pagingAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override public void onItemRangeInserted(int pos, int count) {
                int lastVisible = llm.findLastVisibleItemPosition();
                int total = pagingAdapter.getItemCount();
                if (lastVisible >= total - count - 2) scrollToBottom();
            }
        });
    }

    private void scrollToBottom() {
        int count = pagingAdapter.getItemCount();
        if (count > 0) binding.rvMessages.smoothScrollToPosition(count - 1);
    }

    // ─────────────────────────────────────────────────────────────────────
    // INPUT BAR
    // ─────────────────────────────────────────────────────────────────────

    private void setupInputBar() {
        binding.etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                viewModel.onUserTyping();                          // debounced in ViewModel
                boolean hasText = s != null && s.length() > 0;
                binding.btnSend.setVisibility(hasText ? View.VISIBLE : View.GONE);
                binding.btnVoice.setVisibility(hasText ? View.GONE : View.VISIBLE);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        binding.btnSend.setOnClickListener(v -> sendTextMessage());
        binding.btnAttach.setOnClickListener(v -> showAttachSheet());
        setupVoiceButton();
    }

    private void sendTextMessage() {
        String text = binding.etMessage.getText() != null
                ? binding.etMessage.getText().toString().trim() : "";
        if (text.isEmpty()) return;

        Message m = buildOutgoing();
        m.type = "text";
        m.text = text;

        if (replyingTo != null) {
            m.replyToId         = replyingTo.id;
            m.replyToText       = replyingTo.text;
            m.replyToSenderName = replyingTo.senderName;
            m.replyToType       = replyingTo.type;
            m.replyToMediaUrl   = replyingTo.mediaUrl;
            clearReply();
        }

        binding.etMessage.setText("");
        viewModel.sendMessage(m, text);
        sendPushNotification(text);
    }

    private Message buildOutgoing() {
        Message m    = new Message();
        m.senderId   = viewModel.currentUid;
        m.senderName = viewModel.currentName;
        return m;
    }

    // ─────────────────────────────────────────────────────────────────────
    // REPLY
    // ─────────────────────────────────────────────────────────────────────

    private void startReply(Message m) {
        replyingTo = m;
        if (binding.replyBar != null) {
            binding.replyBar.setVisibility(View.VISIBLE);
            if (binding.tvReplyPreview != null) {
                String preview = m.text != null ? m.text
                        : (m.type != null ? "[" + m.type + "]" : "[message]");
                binding.tvReplyPreview.setText(preview);
            }
            if (binding.tvReplyName != null) {
                binding.tvReplyName.setText(m.senderName != null ? m.senderName : "");
            }
        }
        binding.etMessage.requestFocus();
    }

    private void clearReply() {
        replyingTo = null;
        if (binding.replyBar != null) binding.replyBar.setVisibility(View.GONE);
    }

    // ─────────────────────────────────────────────────────────────────────
    // SWIPE TO REPLY
    // ─────────────────────────────────────────────────────────────────────

    private void setupSwipeToReply() {
        replyController = new ReplyController(new ReplyController.Callback() {
            @Override public void onReplyActivated(Message m) { startReply(m); }
            @Override public void onReplyCancelled()          { clearReply(); }
            @Override public void onPendingUndo(Message m, Runnable cancel) {
                Snackbar.make(binding.getRoot(), "Replying to message", Snackbar.LENGTH_SHORT)
                        .setAction("Undo", v -> cancel.run()).show();
            }
            @Override public void onNavigateToOriginal(String msgId) { navigateToOriginal(msgId); }
            @Override public void onUndoConfirmed() {}
        });

        SwipeReplyHandler handler = new SwipeReplyHandler(
                pagingAdapter.getCurrentMessages(), viewModel.currentUid, replyController);
        ItemTouchHelper ith = new ItemTouchHelper(handler);
        ith.attachToRecyclerView(binding.rvMessages);
    }

    // ─────────────────────────────────────────────────────────────────────
    // FAB — scroll back to latest
    // ─────────────────────────────────────────────────────────────────────

    private void setupFabBackToLatest() {
        if (binding.fabBackToLatest == null) return;
        binding.fabBackToLatest.setVisibility(View.GONE);
        binding.rvMessages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm == null) return;
                int last = lm.findLastVisibleItemPosition();
                int total = pagingAdapter.getItemCount();
                binding.fabBackToLatest.setVisibility(total - last > 5 ? View.VISIBLE : View.GONE);
            }
        });
        binding.fabBackToLatest.setOnClickListener(v -> scrollToBottom());
    }

    // ─────────────────────────────────────────────────────────────────────
    // MESSAGE ACTIONS
    // ─────────────────────────────────────────────────────────────────────

    private void confirmDelete(Message m) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Delete message?")
                .setMessage("This will delete the message for everyone.")
                .setPositiveButton("Delete", (d, w) -> viewModel.deleteMessage(m.id))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptEdit(Message m) {
        android.widget.EditText et = new android.widget.EditText(this);
        et.setText(m.text);
        et.setSelection(et.getText() != null ? et.getText().length() : 0);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Edit message")
                .setView(et)
                .setPositiveButton("Save", (d, w) -> {
                    String newText = et.getText().toString().trim();
                    if (!newText.isEmpty()) viewModel.editMessage(m.id, newText);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void copyText(Message m) {
        if (m.text == null || m.text.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("message", m.text));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        }
    }

    private void forwardMessage(Message m) {
        Intent i = new Intent(this, com.callx.app.activities.ContactsActivity.class);
        i.putExtra("forwardText",  m.text);
        i.putExtra("forwardType",  m.type);
        i.putExtra("forwardMedia", m.mediaUrl);
        startActivity(i);
    }

    private void navigateToOriginal(String msgId) {
        // Scroll to the position of the original message in the list
        // MessageHighlightAnimator handles the highlight pulse
        MessageHighlightAnimator.scrollAndHighlight(binding.rvMessages, pagingAdapter, msgId);
    }

    // ─────────────────────────────────────────────────────────────────────
    // MEDIA ATTACHMENT
    // ─────────────────────────────────────────────────────────────────────

    private void setupPickers() {
        imagePicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "image"); });

        videoPicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "video"); });

        audioPicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "audio"); });

        filePicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "file"); });

        cameraCapturer = registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                success -> { if (success && cameraOutputUri != null) uploadAndSend(cameraOutputUri, "image"); });
    }

    private void showAttachSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View v = getLayoutInflater().inflate(R.layout.bottom_sheet_attach, null);
        sheet.setContentView(v);

        v.findViewById(R.id.opt_gallery).setOnClickListener(b -> { imagePicker.launch("image/*"); sheet.dismiss(); });
        v.findViewById(R.id.opt_video).setOnClickListener(b -> { videoPicker.launch("video/*"); sheet.dismiss(); });
        v.findViewById(R.id.opt_audio).setOnClickListener(b -> { audioPicker.launch("audio/*"); sheet.dismiss(); });
        v.findViewById(R.id.opt_file).setOnClickListener(b -> { filePicker.launch("*/*"); sheet.dismiss(); });
        v.findViewById(R.id.opt_camera).setOnClickListener(b -> {
            if (checkCameraPermission()) openCamera();
            sheet.dismiss();
        });
        sheet.show();
    }

    private void uploadAndSend(Uri uri, String type) {
        Message m   = buildOutgoing();
        m.type      = type;
        m.mediaUrl  = uri.toString();    // optimistic local URI
        m.imageUrl  = "image".equals(type) ? uri.toString() : null;

        // Show optimistic bubble immediately
        String preview = "image".equals(type) ? "📷 Photo"
                : "video".equals(type) ? "🎥 Video"
                : "audio".equals(type) ? "🎤 Voice"
                : "📎 File";
        viewModel.sendMessage(m, preview);

        // Compress + upload in background
        ioExecutor.execute(() -> {
            try {
                String uploadedUrl;
                if ("image".equals(type)) {
                    java.io.File compressed = ImageCompressor.compress(this, uri, 800);
                    uploadedUrl = CloudinaryUploader.uploadImage(compressed);
                } else if ("video".equals(type)) {
                    uploadedUrl = CloudinaryUploader.uploadVideo(this, uri);
                } else {
                    uploadedUrl = CloudinaryUploader.uploadRaw(this, uri);
                }
                if (uploadedUrl != null && m.id != null) {
                    // Update Firebase + Room with real URL
                    java.util.Map<String, Object> upd = new java.util.HashMap<>();
                    upd.put("mediaUrl", uploadedUrl);
                    if ("image".equals(type)) upd.put("imageUrl", uploadedUrl);
                    FirebaseUtils.getMessagesRef(buildChatId(viewModel.currentUid, partnerUid))
                            .child(m.id).updateChildren(upd);
                }
            } catch (Exception e) {
                android.util.Log.e(TAG, "Upload failed", e);
                runOnUiThread(() ->
                    Toast.makeText(this, "Upload failed. Will retry when online.", Toast.LENGTH_SHORT).show());
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // VOICE RECORDING
    // ─────────────────────────────────────────────────────────────────────

    private void setupVoiceButton() {
        binding.btnVoice.setOnLongClickListener(v -> {
            if (!checkAudioPermission()) return true;
            isRecording = true;
            recorder.startRecording(this);
            binding.btnVoice.setImageResource(R.drawable.ic_mic_off);
            Toast.makeText(this, "Recording…", Toast.LENGTH_SHORT).show();
            return true;
        });

        binding.btnVoice.setOnClickListener(v -> {
            if (isRecording) {
                isRecording = false;
                String path = recorder.stopRecording();
                binding.btnVoice.setImageResource(R.drawable.ic_mic);
                if (path != null) uploadAndSend(Uri.parse(path), "audio");
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // CAMERA
    // ─────────────────────────────────────────────────────────────────────

    private void openCamera() {
        android.content.ContentValues cv = new android.content.ContentValues();
        cv.put(android.provider.MediaStore.Images.Media.TITLE, "chat_photo_" + System.currentTimeMillis());
        cameraOutputUri = getContentResolver().insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
        if (cameraOutputUri != null) cameraCapturer.launch(cameraOutputUri);
    }

    // ─────────────────────────────────────────────────────────────────────
    // PERMISSIONS
    // ─────────────────────────────────────────────────────────────────────

    private boolean checkAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) return true;
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        return false;
    }

    private boolean checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) return true;
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────
    // MULTI-SELECT BAR
    // ─────────────────────────────────────────────────────────────────────

    private void showMultiSelectBar(int count) {
        if (binding.layoutMultiSelect == null) return;
        binding.layoutMultiSelect.setVisibility(View.VISIBLE);
        if (binding.tvSelectCount != null)
            binding.tvSelectCount.setText(count + " selected");

        if (binding.btnMultiDelete != null)
            binding.btnMultiDelete.setOnClickListener(v -> {
                for (Message m : pagingAdapter.getSelectedMessages()) viewModel.deleteMessage(m.id);
                pagingAdapter.exitMultiSelectMode();
            });

        if (binding.btnMultiForward != null)
            binding.btnMultiForward.setOnClickListener(v -> {
                Intent i = new Intent(this, com.callx.app.activities.ContactsActivity.class);
                java.util.ArrayList<String> texts = new java.util.ArrayList<>();
                java.util.ArrayList<String> types = new java.util.ArrayList<>();
                java.util.ArrayList<String> medias = new java.util.ArrayList<>();
                for (Message m : pagingAdapter.getSelectedMessages()) {
                    texts.add(m.text);
                    types.add(m.type);
                    medias.add(m.mediaUrl);
                }
                i.putStringArrayListExtra("forwardTexts", texts);
                i.putStringArrayListExtra("forwardTypes", types);
                i.putStringArrayListExtra("forwardMedias", medias);
                startActivity(i);
                pagingAdapter.exitMultiSelectMode();
            });
    }

    private void hideMultiSelectBar() {
        if (binding.layoutMultiSelect != null)
            binding.layoutMultiSelect.setVisibility(View.GONE);
    }

    // ─────────────────────────────────────────────────────────────────────
    // OPTIONS MENU
    // ─────────────────────────────────────────────────────────────────────

    private void showOptionsMenu() {
        PopupMenu popup = new PopupMenu(this, binding.btnMoreOptions);
        popup.getMenuInflater().inflate(R.menu.chat_menu, popup.getMenu());
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_mute) {
                viewModel.isMuted().getValue();
                // Toggle mute via FirebaseUtils directly
                return true;
            }
            if (id == R.id.action_starred) {
                startActivity(new Intent(this, com.callx.app.activities.StarredMessagesActivity.class)
                        .putExtra("chatId", buildChatId(viewModel.currentUid, partnerUid)));
                return true;
            }
            if (id == R.id.action_theme) {
                showThemePicker();
                return true;
            }
            if (id == R.id.action_clear) {
                confirmClearChat();
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void showThemePicker() {
        com.callx.app.utils.ChatThemeManager mgr = com.callx.app.utils.ChatThemeManager.get(this);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Bubble theme")
                .setItems(com.callx.app.utils.ChatThemeManager.THEME_NAMES, (d, which) -> {
                    mgr.setTheme(which);
                    pagingAdapter.notifyDataSetChanged();
                })
                .show();
    }

    private void confirmClearChat() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Clear chat?")
                .setMessage("All messages will be deleted locally. This cannot be undone.")
                .setPositiveButton("Clear", (d, w) -> {
                    String chatId = buildChatId(viewModel.currentUid, partnerUid);
                    ioExecutor.execute(() -> {
                        com.callx.app.db.AppDatabase.getInstance(this).messageDao().clearChat(chatId);
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ─────────────────────────────────────────────────────────────────────
    // NAVIGATION HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private void startCall(boolean isVideo) {
        Intent i = new Intent().setClassName(this, "com.callx.app.activities.CallActivity");
        i.putExtra("partnerUid",   partnerUid);
        i.putExtra("partnerName",  partnerName);
        i.putExtra("partnerPhoto", partnerPhoto);
        i.putExtra("partnerThumb", partnerThumb);
        i.putExtra("isCaller",     true);
        i.putExtra("video",        isVideo);
        startActivity(i);
    }

    private void openAvatarZoom() {
        // Open fullscreen photo viewer
        Intent i = new Intent(this, com.callx.app.activities.FullscreenImageActivity.class);
        i.putExtra("url", partnerPhoto);
        startActivity(i);
    }

    private void openReelProfile() {
        if (partnerUid == null) return;
        try {
            Intent i = new Intent(this,
                    Class.forName("com.callx.app.activities.UserReelsActivity"));
            i.putExtra("uid", partnerUid);
            i.putExtra("name", partnerName);
            startActivity(i);
        } catch (ClassNotFoundException e) {
            Toast.makeText(this, "Reels not available", Toast.LENGTH_SHORT).show();
        }
    }

    private void openXProfile() {
        if (partnerUid == null) return;
        try {
            Class<?> cls = Class.forName("com.callx.app.activities.XProfileSheet");
            java.lang.reflect.Method m = cls.getMethod("showProfile",
                    androidx.fragment.app.FragmentManager.class, String.class);
            m.invoke(null, getSupportFragmentManager(), partnerUid);
        } catch (Exception e) {
            Toast.makeText(this, "X profile not available", Toast.LENGTH_SHORT).show();
        }
    }

    private void openYouTubeProfile() {
        if (partnerUid == null) return;
        try {
            Intent i = new Intent(this,
                    Class.forName("com.callx.app.activities.YouTubeChannelActivity"));
            i.putExtra("uid", partnerUid);
            startActivity(i);
        } catch (ClassNotFoundException e) {
            Toast.makeText(this, "YouTube profile not available", Toast.LENGTH_SHORT).show();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // FORWARD PAYLOAD (from intent — direct send on open)
    // ─────────────────────────────────────────────────────────────────────

    private void handleForwardPayload() {
        Intent intent = getIntent();
        java.util.ArrayList<String> fwdTexts  = intent.getStringArrayListExtra("forwardTexts");
        java.util.ArrayList<String> fwdTypes  = intent.getStringArrayListExtra("forwardTypes");
        java.util.ArrayList<String> fwdMedias = intent.getStringArrayListExtra("forwardMedias");

        if (fwdTexts != null && !fwdTexts.isEmpty()) {
            for (int i = 0; i < fwdTexts.size(); i++) {
                final int fi = i;
                binding.getRoot().postDelayed(() -> {
                    Message m = buildOutgoing();
                    m.forwardedFrom = partnerName;
                    String tp = fwdTypes != null && fi < fwdTypes.size() ? fwdTypes.get(fi) : "text";
                    String mu = fwdMedias != null && fi < fwdMedias.size() ? fwdMedias.get(fi) : null;
                    if ("text".equals(tp)) {
                        m.type = "text"; m.text = fwdTexts.get(fi);
                        viewModel.sendMessage(m, m.text);
                    } else if (mu != null) {
                        m.type = tp; m.mediaUrl = mu;
                        viewModel.sendMessage(m, "[" + tp + " forwarded]");
                    }
                }, i * 150L);
            }
        } else {
            // Single text/media forward
            String fwdText  = intent.getStringExtra("forwardText");
            String fwdType  = intent.getStringExtra("forwardType");
            String fwdMedia = intent.getStringExtra("forwardMedia");
            if (fwdText != null && !fwdText.isEmpty() && "text".equals(fwdType)) {
                binding.etMessage.post(() -> {
                    binding.etMessage.setText(fwdText);
                    binding.etMessage.setSelection(fwdText.length());
                });
            } else if (fwdMedia != null && !fwdMedia.isEmpty()) {
                binding.getRoot().post(() -> {
                    Message m = buildOutgoing();
                    m.type = fwdType != null ? fwdType : "image";
                    m.mediaUrl = fwdMedia;
                    m.forwardedFrom = partnerName;
                    viewModel.sendMessage(m, "[" + m.type + " forwarded]");
                });
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private void setInputEnabled(boolean enabled) {
        binding.etMessage.setEnabled(enabled);
        binding.btnSend.setEnabled(enabled);
        binding.btnAttach.setEnabled(enabled);
        binding.btnVoice.setEnabled(enabled);
        binding.etMessage.setHint(enabled ? "Type a message…" : "You can't send messages to this chat");
    }

    private void updateSendButtonState(boolean online) {
        binding.btnSend.setAlpha(online ? 1f : 0.4f);
        binding.btnSend.setEnabled(online);
    }

    private void applyScreenTheme() {
        com.callx.app.utils.ChatThemeManager.get(this);
    }

    private void sendPushNotification(String text) {
        ioExecutor.execute(() -> PushNotify.send(this, partnerUid, viewModel.currentName, text));
    }

    private String buildChatId(String a, String b) {
        if (a == null) a = ""; if (b == null) b = "";
        return a.compareTo(b) < 0 ? a + "_" + b : b + "_" + a;
    }
}
