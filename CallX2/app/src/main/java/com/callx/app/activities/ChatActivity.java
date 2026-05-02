package com.callx.app.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.callx.app.R;
import com.callx.app.adapters.MessageAdapter;
import com.callx.app.databinding.ActivityChatBinding;
import com.callx.app.models.Message;
import com.callx.app.utils.*;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;

import java.util.*;

/**
 * 1-on-1 chat screen — all 15 new features integrated.
 *
 * Existing features (1-8):
 *   1. Read Receipts  2. Reply/Quote  3. Emoji Reactions  4. Edit
 *   5. Delete         6. Forward      7. Starred          8. Pin
 *
 * NEW features (added in this revision):
 *   F01. In-chat message search
 *   F02. Location sharing
 *   F03. Contact/vCard sharing
 *   F04. GIF / Sticker
 *   F05. Link preview (auto-detected on send)
 *   F06. Polls (1-on-1 polls)
 *   F07. @Mention (N/A for 1-on-1, forwarded to group logic)
 *   F08. Disappearing messages
 *   F09. Broadcast — launched from main menu
 *   F10. Seen-by — N/A for 1-on-1 (group only)
 *   F11. Draft save / restore
 *   F12. Voice message transcription
 *   F13. Multiple emoji reactions
 *   F14. Chat wallpaper / theme
 *   F15. End-to-End Encryption
 */
public class ChatActivity extends AppCompatActivity
        implements MessageAdapter.ActionListener {

    private ActivityChatBinding binding;
    private MessageAdapter adapter;
    private final List<Message> messages = new ArrayList<>();

    private String partnerUid, partnerName, chatId, currentUid, currentName;
    private final AudioRecorderHelper recorder = new AudioRecorderHelper();
    private boolean isRecording = false;

    private ActivityResultLauncher<String>  imagePicker, videoPicker,
                                             audioPicker, filePicker;
    private ActivityResultLauncher<Intent>  contactPicker, gifPicker, wallpaperPicker;
    private static final int REQ_LOCATION   = 901;
    private static final int REQ_GIF        = 902;

    // Existing reply state
    private Message replyingTo = null;
    // Existing pin state
    private String  pinnedMsgId   = null;
    private String  pinnedMsgText = null;

    // Feature 11 – draft
    private DraftManager draftManager;
    // Feature 14 – wallpaper
    private WallpaperManager wallpaperMgr;
    // Feature 08 – disappearing
    private DisappearingMessageManager disappearMgr;
    // Feature 15 – E2E
    private E2EEncryptionManager e2eMgr;
    private boolean e2eEnabled = false;
    // Feature 05 – link preview
    private LinkPreviewHelper.LinkData pendingLink = null;

    private boolean partnerPermaBlockedMe = false;
    private long    lastTypingPingMs      = 0L;

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        partnerUid  = getIntent().getStringExtra("partnerUid");
        partnerName = getIntent().getStringExtra("partnerName");
        if (partnerUid == null || FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish(); return;
        }
        currentUid  = FirebaseAuth.getInstance().getCurrentUser().getUid();
        currentName = FirebaseUtils.getCurrentName();
        chatId      = FirebaseUtils.getChatId(currentUid, partnerUid);

        // Init managers
        draftManager  = DraftManager.getInstance(this);
        wallpaperMgr  = WallpaperManager.getInstance(this);
        disappearMgr  = DisappearingMessageManager.getInstance(this);
        e2eMgr        = E2EEncryptionManager.getInstance(this);

        setupToolbar();
        setupRecyclerView();
        setupInputBar();
        setupPickers();
        setupReplyBarButtons();
        setupPinnedBanner();

        // F14: Apply wallpaper
        applyWallpaper();
        // F11: Restore draft
        restoreDraft();
        // F15: Init E2E
        initE2E();

        loadMessages();
        listenForReadReceipts();
        watchPinnedMessage();
        watchPartnerPermaBlock();
        watchDisappearTimer();  // F08

        FirebaseUtils.getContactsRef(currentUid)
                .child(partnerUid).child("unread").setValue(0);
    }

    @Override protected void onResume() {
        super.onResume();
        if (currentUid != null && partnerUid != null) {
            FirebaseUtils.getContactsRef(currentUid)
                    .child(partnerUid).child("unread").setValue(0);
            markAllReceivedAsRead();
        }
    }

    @Override protected void onPause() {
        super.onPause();
        // F11: Save draft
        if (binding.etMessage != null)
            draftManager.saveDraft(chatId, binding.etMessage.getText().toString());
    }

    @Override protected void onDestroy() {
        disappearMgr.cancelAll();
        if (adapter != null) adapter.releasePlayer();
        super.onDestroy();
    }

    // ── Setup ──────────────────────────────────────────────────────────────

    private void setupToolbar() {
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(partnerName != null ? partnerName : "Chat");
        }
        binding.toolbar.setNavigationOnClickListener(v -> finish());
    }

    private void setupRecyclerView() {
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        binding.rvMessages.setLayoutManager(lm);
        adapter = new MessageAdapter(messages, currentUid, false);
        adapter.setActionListener(this);
        adapter.setChatId(chatId);
        adapter.setE2EManager(e2eMgr);
        binding.rvMessages.setAdapter(adapter);
    }

    private void setupInputBar() {
        binding.etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int a,int b,int c){}
            @Override public void onTextChanged(CharSequence s,int a,int b,int c){}
            @Override public void afterTextChanged(Editable s) {
                boolean has = s.toString().trim().length() > 0;
                binding.btnSend.setVisibility(has ? View.VISIBLE : View.GONE);
                binding.btnMic.setVisibility(has ? View.GONE : View.VISIBLE);
                if (has) throttleTypingPing();
                // F05: live link preview detection
                detectLinkPreview(s.toString());
            }
        });
        binding.btnAttach.setOnClickListener(v -> showAttachSheet());
        binding.btnCamera.setOnClickListener(v -> imagePicker.launch("image/*"));
        binding.btnSend.setOnClickListener(v -> sendText());
        binding.btnMic.setOnClickListener(v -> toggleRecording());
    }

    private void setupReplyBarButtons() {
        if (binding.btnCancelReply != null)
            binding.btnCancelReply.setOnClickListener(v -> clearReply());
    }

    private void setupPinnedBanner() {
        if (binding.llPinnedBanner == null) return;
        binding.llPinnedBanner.setOnClickListener(v -> scrollToPinned());
        if (binding.btnUnpin != null)
            binding.btnUnpin.setOnClickListener(v -> {
                if (pinnedMsgId != null) unpinMessage(pinnedMsgId);
            });
    }

    private void setupPickers() {
        imagePicker  = registerForActivityResult(new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "image"); });
        videoPicker  = registerForActivityResult(new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "video"); });
        audioPicker  = registerForActivityResult(new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "audio"); });
        filePicker   = registerForActivityResult(new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "file"); });
        // F03: Contact picker
        contactPicker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) sendContact(uri);
                    }
                });
        // F04: GIF picker
        gifPicker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        String url  = result.getData().getStringExtra(GifStickerPickerActivity.EXTRA_GIF_URL);
                        String type = result.getData().getStringExtra(GifStickerPickerActivity.EXTRA_TYPE);
                        if (url != null) sendGif(url, type);
                    }
                });
        // F14: Wallpaper picker
        wallpaperPicker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            wallpaperMgr.setWallpaperUri(chatId, uri.toString());
                            applyWallpaper();
                        }
                    }
                });
    }

    // ── Feature 11: Draft ─────────────────────────────────────────────────

    private void restoreDraft() {
        String draft = draftManager.getDraft(chatId);
        if (!draft.isEmpty() && binding.etMessage != null) {
            binding.etMessage.setText(draft);
            binding.etMessage.setSelection(draft.length());
        }
    }

    // ── Feature 14: Wallpaper ─────────────────────────────────────────────

    private void applyWallpaper() {
        String uri   = wallpaperMgr.getWallpaperUri(chatId);
        Integer color = wallpaperMgr.getWallpaperColor(chatId);
        if (uri != null) {
            com.bumptech.glide.Glide.with(this).load(uri)
                    .into(new com.bumptech.glide.request.target.CustomTarget<android.graphics.drawable.Drawable>() {
                        @Override public void onResourceReady(android.graphics.drawable.Drawable r,
                                com.bumptech.glide.request.transition.Transition<? super android.graphics.drawable.Drawable> t) {
                            binding.rvMessages.setBackground(r);
                        }
                        @Override public void onLoadCleared(android.graphics.drawable.Drawable p) {}
                    });
        } else if (color != null) {
            binding.rvMessages.setBackgroundColor(color);
        } else {
            binding.rvMessages.setBackground(null);
        }
    }

    private void showWallpaperPicker() {
        String[] options = WallpaperManager.THEME_NAMES;
        new AlertDialog.Builder(this)
                .setTitle("Chat Wallpaper")
                .setItems(new String[]{"Pick from Gallery", "Choose Color Theme", "Reset"}, (d, which) -> {
                    if (which == 0) {
                        Intent i = new Intent(Intent.ACTION_PICK);
                        i.setType("image/*");
                        wallpaperPicker.launch(i);
                    } else if (which == 1) {
                        showColorThemePicker();
                    } else {
                        wallpaperMgr.clearWallpaper(chatId);
                        binding.rvMessages.setBackground(null);
                    }
                }).show();
    }

    private void showColorThemePicker() {
        new AlertDialog.Builder(this)
                .setTitle("Choose Theme Color")
                .setItems(WallpaperManager.THEME_NAMES, (d, which) -> {
                    wallpaperMgr.setWallpaperColor(chatId, WallpaperManager.THEME_COLORS[which]);
                    applyWallpaper();
                }).show();
    }

    // ── Feature 15: E2E Encryption ────────────────────────────────────────

    private void initE2E() {
        // Upload our public key
        String pubKey = e2eMgr.getPublicKeyBase64();
        if (pubKey != null)
            FirebaseUtils.getUserPublicKeyRef(currentUid).setValue(pubKey);
        // Try to load existing session key
        FirebaseUtils.getE2EKeyRef(chatId, currentUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        String encKey = snap.getValue(String.class);
                        if (encKey != null) {
                            try {
                                javax.crypto.SecretKey sk = e2eMgr.decryptSessionKey(encKey);
                                e2eMgr.cacheSessionKey(chatId, sk);
                                e2eEnabled = true;
                                showE2EBadge(true);
                            } catch (Exception ignored) { initiateKeyExchange(); }
                        } else { initiateKeyExchange(); }
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    private void initiateKeyExchange() {
        FirebaseUtils.getUserPublicKeyRef(partnerUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        String partnerPubB64 = snap.getValue(String.class);
                        if (partnerPubB64 == null) return;
                        try {
                            java.security.PublicKey partnerPub = e2eMgr.parsePublicKey(partnerPubB64);
                            javax.crypto.SecretKey sk = e2eMgr.generateSessionKey();
                            e2eMgr.cacheSessionKey(chatId, sk);
                            // Encrypt for partner
                            String encForPartner = e2eMgr.encryptSessionKey(sk, partnerPub);
                            FirebaseUtils.getE2EKeyRef(chatId, partnerUid).setValue(encForPartner);
                            // Encrypt for self
                            String encForSelf = e2eMgr.encryptSessionKey(sk, e2eMgr.parsePublicKey(e2eMgr.getPublicKeyBase64()));
                            FirebaseUtils.getE2EKeyRef(chatId, currentUid).setValue(encForSelf);
                            e2eEnabled = true;
                            showE2EBadge(true);
                        } catch (Exception ex) {
                            android.util.Log.e("E2E", "Key exchange failed", ex);
                        }
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    private void showE2EBadge(boolean on) {
        if (getSupportActionBar() != null)
            getSupportActionBar().setSubtitle(on ? "🔒 End-to-end encrypted" : null);
    }

    // ── Feature 08: Disappearing messages ────────────────────────────────

    private void watchDisappearTimer() {
        FirebaseUtils.getChatMetaRef(chatId).child("disappearTimer")
                .addValueEventListener(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        Long timer = snap.getValue(Long.class);
                        long t = timer != null ? timer : DisappearingMessageManager.TIMER_OFF;
                        disappearMgr.setTimer(chatId, t);
                        updateDisappearBadge(t);
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    private void updateDisappearBadge(long timer) {
        if (getSupportActionBar() == null) return;
        if (timer > 0 && !e2eEnabled)
            getSupportActionBar().setSubtitle("⏱ " + DisappearingMessageManager.timerLabel(timer));
        else if (timer > 0 && e2eEnabled)
            getSupportActionBar().setSubtitle("🔒 Encrypted · ⏱ " + DisappearingMessageManager.timerLabel(timer));
    }

    private void showDisappearTimerPicker() {
        String[] labels = {"Off", "24 hours", "7 days", "90 days"};
        long[] values = {
            DisappearingMessageManager.TIMER_OFF,
            DisappearingMessageManager.TIMER_24H,
            DisappearingMessageManager.TIMER_7D,
            DisappearingMessageManager.TIMER_90D
        };
        new AlertDialog.Builder(this)
                .setTitle("Disappearing Messages")
                .setItems(labels, (d, which) -> {
                    long chosen = values[which];
                    FirebaseUtils.getChatMetaRef(chatId).child("disappearTimer").setValue(chosen);
                    Toast.makeText(this, "Timer set to " + labels[which], Toast.LENGTH_SHORT).show();
                }).show();
    }

    // ── Feature 05: Link preview detection ───────────────────────────────

    private void detectLinkPreview(String text) {
        String url = LinkPreviewHelper.extractUrl(text);
        if (url == null) { pendingLink = null; hideLinkPreviewBar(); return; }
        LinkPreviewHelper.fetch(url, new LinkPreviewHelper.Callback() {
            @Override public void onResult(String title, String desc, String img, String site) {
                LinkPreviewHelper.LinkData d = new LinkPreviewHelper.LinkData();
                d.url = url; d.title = title; d.description = desc;
                d.imageUrl = img; d.siteName = site;
                pendingLink = d;
                showLinkPreviewBar(d);
            }
            @Override public void onError() { pendingLink = null; hideLinkPreviewBar(); }
        });
    }

    private void showLinkPreviewBar(LinkPreviewHelper.LinkData d) {
        if (binding.llLinkPreview == null) return;
        binding.llLinkPreview.setVisibility(View.VISIBLE);
        if (binding.tvLinkTitle != null) binding.tvLinkTitle.setText(d.title != null ? d.title : d.url);
        if (binding.tvLinkSite  != null) binding.tvLinkSite.setText(d.siteName != null ? d.siteName : "");
        if (binding.btnCancelLink != null) binding.btnCancelLink.setOnClickListener(v -> {
            pendingLink = null; hideLinkPreviewBar();
        });
    }

    private void hideLinkPreviewBar() {
        if (binding.llLinkPreview != null) binding.llLinkPreview.setVisibility(View.GONE);
    }

    // ── Attach sheet (expanded with new options) ──────────────────────────

    private void showAttachSheet() {
        BottomSheetDialog bsd = new BottomSheetDialog(this);
        View v = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_attach, null);
        bsd.setContentView(v);

        v.findViewById(R.id.btn_gallery).setOnClickListener(x -> { imagePicker.launch("image/*"); bsd.dismiss(); });
        v.findViewById(R.id.btn_video).setOnClickListener(x  -> { videoPicker.launch("video/*");  bsd.dismiss(); });
        v.findViewById(R.id.btn_audio).setOnClickListener(x  -> { audioPicker.launch("audio/*");  bsd.dismiss(); });
        v.findViewById(R.id.btn_file).setOnClickListener(x   -> { filePicker.launch("*/*");       bsd.dismiss(); });
        // F02: Location
        v.findViewById(R.id.btn_location).setOnClickListener(x -> { bsd.dismiss(); requestLocation(); });
        // F03: Contact
        v.findViewById(R.id.btn_contact).setOnClickListener(x  -> {
            bsd.dismiss();
            contactPicker.launch(ContactShareHelper.pickContactIntent());
        });
        // F04: GIF
        v.findViewById(R.id.btn_gif).setOnClickListener(x -> {
            bsd.dismiss();
            Intent gi = new Intent(this, GifStickerPickerActivity.class);
            gi.putExtra("mode", "gif");
            gifPicker.launch(gi);
        });
        // F04: Sticker
        v.findViewById(R.id.btn_sticker).setOnClickListener(x -> {
            bsd.dismiss();
            Intent si = new Intent(this, GifStickerPickerActivity.class);
            si.putExtra("mode", "sticker");
            gifPicker.launch(si);
        });
        // F06: Poll
        v.findViewById(R.id.btn_poll).setOnClickListener(x -> { bsd.dismiss(); showPollBuilder(); });

        bsd.show();
    }

    // ── Feature 02: Location ──────────────────────────────────────────────

    private LocationShareHelper locationHelper;

    private void requestLocation() {
        if (!LocationShareHelper.hasPermission(this)) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
            return;
        }
        sendCurrentLocation();
    }

    private void sendCurrentLocation() {
        if (locationHelper == null) locationHelper = new LocationShareHelper(this);
        Toast.makeText(this, "Getting location…", Toast.LENGTH_SHORT).show();
        locationHelper.getCurrentLocation(new LocationShareHelper.LocationCallback2() {
            @Override public void onLocation(double lat, double lng, String addr, String mapUrl) {
                Message msg = buildBaseMessage();
                msg.type            = "location";
                msg.locationLat     = lat;
                msg.locationLng     = lng;
                msg.locationAddress = addr;
                msg.locationMapUrl  = mapUrl;
                pushMessage(msg);
            }
            @Override public void onError(String reason) {
                Toast.makeText(ChatActivity.this, "Location failed: " + reason, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_LOCATION && grants.length > 0
                && grants[0] == PackageManager.PERMISSION_GRANTED)
            sendCurrentLocation();
    }

    // ── Feature 03: Contact ───────────────────────────────────────────────

    private void sendContact(Uri uri) {
        ContactShareHelper.ContactData cd = ContactShareHelper.readFromUri(this, uri);
        Message msg = buildBaseMessage();
        ContactShareHelper.applyToMessage(msg, cd);
        pushMessage(msg);
    }

    // ── Feature 04: GIF / Sticker ─────────────────────────────────────────

    private void sendGif(String url, String type) {
        Message msg = buildBaseMessage();
        msg.type   = "gif".equals(type) ? "gif" : "sticker";
        msg.gifUrl = url;
        pushMessage(msg);
    }

    // ── Feature 06: Poll ─────────────────────────────────────────────────

    private void showPollBuilder() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_poll_builder, null);
        EditText etQ = v.findViewById(R.id.et_poll_question);
        LinearLayout llOpts = v.findViewById(R.id.ll_poll_options);
        Button btnAdd = v.findViewById(R.id.btn_add_option);

        final List<EditText> optFields = new ArrayList<>();
        // Add 2 default options
        for (int i = 0; i < 2; i++) {
            EditText et = new EditText(this);
            et.setHint("Option " + (i + 1));
            llOpts.addView(et);
            optFields.add(et);
        }
        btnAdd.setOnClickListener(x -> {
            if (optFields.size() >= 10) { Toast.makeText(this,"Max 10 options",Toast.LENGTH_SHORT).show(); return; }
            EditText et = new EditText(this);
            et.setHint("Option " + (optFields.size() + 1));
            llOpts.addView(et);
            optFields.add(et);
        });

        new AlertDialog.Builder(this)
                .setTitle("Create Poll")
                .setView(v)
                .setPositiveButton("Send", (d, w) -> {
                    String q = etQ.getText().toString().trim();
                    if (q.isEmpty()) return;
                    List<String> opts = new ArrayList<>();
                    for (EditText ef : optFields) {
                        String o = ef.getText().toString().trim();
                        if (!o.isEmpty()) opts.add(o);
                    }
                    if (opts.size() < 2) { Toast.makeText(this,"Need at least 2 options",Toast.LENGTH_SHORT).show(); return; }
                    Message poll = PollManager.buildPoll(currentUid, currentName, q, opts, false, 0);
                    disappearMgr.applyTimer(chatId, poll);
                    pushMessage(poll);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Send helpers ──────────────────────────────────────────────────────

    private void sendText() {
        String raw = binding.etMessage.getText().toString().trim();
        if (raw.isEmpty() || partnerPermaBlockedMe) return;
        Message msg = buildBaseMessage();
        msg.type = "text";

        // F05: Attach link preview if detected
        if (pendingLink != null) {
            msg.type            = "link_preview";
            msg.linkUrl         = pendingLink.url;
            msg.linkTitle       = pendingLink.title;
            msg.linkDescription = pendingLink.description;
            msg.linkImageUrl    = pendingLink.imageUrl;
            msg.linkSiteName    = pendingLink.siteName;
            pendingLink = null;
            hideLinkPreviewBar();
        }

        // F15: Encrypt if E2E active
        if (e2eEnabled && msg.type.equals("text")) {
            String[] enc = e2eMgr.encryptForChat(chatId, raw);
            if (enc != null) {
                msg.text         = enc[0];
                msg.iv           = enc[1];
                msg.e2eEncrypted = true;
            } else { msg.text = raw; }
        } else {
            msg.text = raw;
        }

        binding.etMessage.setText("");
        draftManager.clearDraft(chatId);
        pendingLink = null;
        hideLinkPreviewBar();
        pushMessage(msg);
    }

    private Message buildBaseMessage() {
        Message msg = new Message();
        msg.id          = FirebaseUtils.getMessagesRef(chatId).push().getKey();
        msg.senderId    = currentUid;
        msg.senderName  = currentName;
        msg.timestamp   = System.currentTimeMillis();
        msg.status      = "sent";
        // F08: Apply disappearing timer
        disappearMgr.applyTimer(chatId, msg);
        // Reply
        if (replyingTo != null) {
            msg.replyToId         = replyingTo.id;
            msg.replyToText       = replyingTo.text;
            msg.replyToSenderName = replyingTo.senderName;
            clearReply();
        }
        return msg;
    }

    private void pushMessage(Message msg) {
        if (msg.id == null) return;
        DatabaseReference ref = FirebaseUtils.getMessagesRef(chatId).child(msg.id);
        ref.setValue(msg).addOnSuccessListener(u -> {
            // F08: Schedule auto-delete
            if (msg.expiresAt != null && msg.expiresAt > 0)
                disappearMgr.scheduleDelete(msg, ref);
        });
        updateContactList(msg);
        PushNotify.send(partnerUid, currentName, previewText(msg), chatId, false);
        binding.rvMessages.scrollToPosition(messages.size() - 1);
    }

    private String previewText(Message m) {
        if (m == null) return "";
        if ("text".equals(m.type)) return m.text != null ? m.text : "";
        if ("image".equals(m.type))    return "📷 Photo";
        if ("video".equals(m.type))    return "🎥 Video";
        if ("audio".equals(m.type))    return "🎵 Audio";
        if ("file".equals(m.type))     return "📎 " + (m.fileName != null ? m.fileName : "File");
        if ("location".equals(m.type)) return "📍 Location";
        if ("contact".equals(m.type))  return "👤 " + (m.contactName != null ? m.contactName : "Contact");
        if ("gif".equals(m.type))      return "GIF";
        if ("sticker".equals(m.type))  return "Sticker";
        if ("poll".equals(m.type))     return "📊 " + (m.pollQuestion != null ? m.pollQuestion : "Poll");
        if ("link_preview".equals(m.type)) return "🔗 " + (m.linkTitle != null ? m.linkTitle : m.linkUrl);
        return "";
    }

    // ── Recording ────────────────────────────────────────────────────────

    private void toggleRecording() {
        if (!isRecording) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.RECORD_AUDIO}, 100);
                return;
            }
            recorder.start(this);
            isRecording = true;
            binding.btnMic.setImageResource(R.drawable.ic_pause);
        } else {
            String path = recorder.stop();
            isRecording = false;
            binding.btnMic.setImageResource(R.drawable.ic_mic);
            if (path != null) uploadAndSend(Uri.parse("file://" + path), "audio");
        }
    }

    // ── Media upload ──────────────────────────────────────────────────────

    private void uploadAndSend(Uri uri, String type) {
        Toast.makeText(this, "Uploading…", Toast.LENGTH_SHORT).show();
        CloudinaryUploader.upload(this, uri, type, new CloudinaryUploader.UploadCallback() {
            @Override public void onSuccess(CloudinaryUploader.Result result) {
                String url      = result.secureUrl;
                String thumbUrl = null;
                String fn       = result.publicId;
                long   size     = result.bytes != null ? result.bytes : 0L;
                Message msg = buildBaseMessage();
                msg.type         = type;
                msg.mediaUrl     = url;
                msg.thumbnailUrl = thumbUrl;
                msg.fileName     = fn;
                msg.fileSize     = size;
                // F12: Transcription for audio
                if ("audio".equals(type)) {
                    msg.transcript = null;
                    pushMessage(msg);
                    // Async transcription
                    String finalMsgId = msg.id;
                    TranscriptionHelper.transcribeUrl(url, new TranscriptionHelper.TranscriptCallback() {
                        @Override public void onTranscript(String text) {
                            if (finalMsgId != null)
                                FirebaseUtils.getMessagesRef(chatId).child(finalMsgId)
                                        .child("transcript").setValue(text);
                        }
                        @Override public void onError(String r) {}
                    });
                } else {
                    pushMessage(msg);
                }
            }
            @Override public void onError(String error) {
                Toast.makeText(ChatActivity.this, "Upload failed: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ── Typing indicator ──────────────────────────────────────────────────

    private void throttleTypingPing() {
        long now = System.currentTimeMillis();
        if (now - lastTypingPingMs < 3000) return;
        lastTypingPingMs = now;
        FirebaseUtils.getContactsRef(partnerUid).child(currentUid).child("typing").setValue(true);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() ->
            FirebaseUtils.getContactsRef(partnerUid).child(currentUid).child("typing").removeValue(),
            4000);
    }

    // ── Feature 1 (existing): Read receipts ──────────────────────────────

    private void markAllReceivedAsRead() {
        FirebaseUtils.getMessagesRef(chatId)
                .orderByChild("senderId").equalTo(partnerUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        for (DataSnapshot c : snap.getChildren()) {
                            String st = c.child("status").getValue(String.class);
                            if (!"read".equals(st)) c.getRef().child("status").setValue("read");
                        }
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    private void listenForReadReceipts() {
        FirebaseUtils.getMessagesRef(chatId)
                .orderByChild("senderId").equalTo(currentUid)
                .addChildEventListener(new ChildEventListener() {
                    @Override public void onChildChanged(DataSnapshot s, String prev) {
                        String st = s.child("status").getValue(String.class);
                        if (st == null) return;
                        for (int i = 0; i < messages.size(); i++) {
                            if (messages.get(i).id != null && messages.get(i).id.equals(s.getKey())) {
                                messages.get(i).status = st;
                                adapter.notifyItemChanged(i);
                                break;
                            }
                        }
                    }
                    @Override public void onChildAdded(DataSnapshot s, String p) {}
                    @Override public void onChildRemoved(DataSnapshot s) {}
                    @Override public void onChildMoved(DataSnapshot s, String p) {}
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    // ── Load messages ─────────────────────────────────────────────────────

    private void loadMessages() {
        FirebaseUtils.getMessagesRef(chatId)
                .orderByChild("timestamp")
                .addChildEventListener(new ChildEventListener() {
                    @Override public void onChildAdded(DataSnapshot s, String prev) {
                        Message m = s.getValue(Message.class);
                        if (m == null) return;
                        if (m.id == null) m.id = s.getKey();
                        // F08: Schedule auto-delete for received expiring messages
                        if (m.expiresAt != null && m.expiresAt > 0)
                            disappearMgr.scheduleDelete(m,
                                    FirebaseUtils.getMessagesRef(chatId).child(m.id));
                        messages.add(m);
                        adapter.notifyItemInserted(messages.size() - 1);
                        binding.rvMessages.scrollToPosition(messages.size() - 1);
                    }
                    @Override public void onChildChanged(DataSnapshot s, String p) {
                        Message updated = s.getValue(Message.class);
                        if (updated == null) return;
                        if (updated.id == null) updated.id = s.getKey();
                        for (int i = 0; i < messages.size(); i++) {
                            if (updated.id.equals(messages.get(i).id)) {
                                messages.set(i, updated);
                                adapter.notifyItemChanged(i);
                                break;
                            }
                        }
                    }
                    @Override public void onChildRemoved(DataSnapshot s) {
                        String key = s.getKey();
                        for (int i = 0; i < messages.size(); i++) {
                            if (key != null && key.equals(messages.get(i).id)) {
                                messages.remove(i);
                                adapter.notifyItemRemoved(i);
                                break;
                            }
                        }
                    }
                    @Override public void onChildMoved(DataSnapshot s, String p) {}
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    // ── Pinned message ────────────────────────────────────────────────────

    private void watchPinnedMessage() {
        FirebaseUtils.getChatMetaRef(chatId).child("pinnedMsg")
                .addValueEventListener(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        pinnedMsgId  = snap.child("id").getValue(String.class);
                        pinnedMsgText = snap.child("text").getValue(String.class);
                        if (binding.llPinnedBanner == null) return;
                        boolean has = pinnedMsgId != null;
                        binding.llPinnedBanner.setVisibility(has ? View.VISIBLE : View.GONE);
                        if (has && binding.tvPinnedPreview != null)
                            binding.tvPinnedPreview.setText(pinnedMsgText != null ? pinnedMsgText : "Pinned message");
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    private void scrollToPinned() {
        if (pinnedMsgId == null) return;
        for (int i = 0; i < messages.size(); i++) {
            if (pinnedMsgId.equals(messages.get(i).id)) {
                binding.rvMessages.scrollToPosition(i); return;
            }
        }
    }

    private void unpinMessage(String msgId) {
        FirebaseUtils.getChatMetaRef(chatId).child("pinnedMsg").removeValue();
        FirebaseUtils.getMessagesRef(chatId).child(msgId).child("pinned").setValue(false);
    }

    // ── Partner perma-block ───────────────────────────────────────────────

    private void watchPartnerPermaBlock() {
        FirebaseUtils.getUserRef(currentUid).child("blockedBy").child(partnerUid)
                .addValueEventListener(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot s) {
                        partnerPermaBlockedMe = Boolean.TRUE.equals(s.getValue(Boolean.class));
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    // ── Reply ─────────────────────────────────────────────────────────────

    private void clearReply() {
        replyingTo = null;
        if (binding.llReplyBar != null) binding.llReplyBar.setVisibility(View.GONE);
    }

    private void showReplyBar(Message m) {
        replyingTo = m;
        if (binding.llReplyBar != null)  binding.llReplyBar.setVisibility(View.VISIBLE);
        if (binding.tvReplyBarName != null) binding.tvReplyBarName.setText(m.senderName);
        if (binding.tvReplyBarText != null) binding.tvReplyBarText.setText(
                m.text != null ? m.text : previewText(m));
    }

    // ── Contact list update ───────────────────────────────────────────────

    private void updateContactList(Message msg) {
        String preview = Boolean.TRUE.equals(msg.e2eEncrypted)
                ? "🔒 Encrypted message" : previewText(msg);
        Map<String, Object> up = new HashMap<>();
        up.put("lastMessage",   preview);
        up.put("lastTimestamp", msg.timestamp);
        FirebaseUtils.getContactsRef(currentUid).child(partnerUid).updateChildren(up);
        FirebaseUtils.getContactsRef(partnerUid).child(currentUid).updateChildren(up);
        FirebaseUtils.getContactsRef(partnerUid).child(currentUid)
                .child("unread").addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot s) {
                        Long cur = s.getValue(Long.class);
                        FirebaseUtils.getContactsRef(partnerUid).child(currentUid)
                                .child("unread").setValue((cur != null ? cur : 0) + 1);
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    // ── Menu ──────────────────────────────────────────────────────────────

    @Override public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.chat_menu, menu);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_search) {
            Intent i = new Intent(this, MessageSearchActivity.class);
            i.putExtra("chatId", chatId); i.putExtra("isGroup", false);
            startActivity(i); return true;
        }
        if (id == R.id.action_starred) {
            Intent i = new Intent(this, StarredMessagesActivity.class);
            i.putExtra("chatId", chatId); i.putExtra("isGroup", false);
            startActivity(i); return true;
        }
        if (id == R.id.action_wallpaper) { showWallpaperPicker(); return true; }
        if (id == R.id.action_disappear) { showDisappearTimerPicker(); return true; }
        return super.onOptionsItemSelected(item);
    }

    // ── MessageAdapter.ActionListener ────────────────────────────────────

    @Override public void onReply(Message m)   { showReplyBar(m); }

    @Override public void onEdit(Message m) {
        if (!currentUid.equals(m.senderId)) return;
        android.widget.EditText et = new android.widget.EditText(this);
        et.setText(m.text); et.setSelection(m.text != null ? m.text.length() : 0);
        new AlertDialog.Builder(this).setTitle("Edit message").setView(et)
                .setPositiveButton("Save", (d, w) -> {
                    String newText = et.getText().toString().trim();
                    if (newText.isEmpty()) return;
                    FirebaseUtils.getMessagesRef(chatId).child(m.id)
                            .child("text").setValue(newText);
                    FirebaseUtils.getMessagesRef(chatId).child(m.id)
                            .child("edited").setValue(true);
                    FirebaseUtils.getMessagesRef(chatId).child(m.id)
                            .child("editedAt").setValue(System.currentTimeMillis());
                }).setNegativeButton("Cancel", null).show();
    }

    @Override public void onDelete(Message m) {
        if (!currentUid.equals(m.senderId)) {
            FirebaseUtils.getMessagesRef(chatId).child(m.id).child("deleted").setValue(true);
            return;
        }
        new AlertDialog.Builder(this).setTitle("Delete message")
                .setItems(new String[]{"Delete for me", "Delete for everyone"}, (d, w) -> {
                    if (w == 0) {
                        for (int i = 0; i < messages.size(); i++) {
                            if (m.id.equals(messages.get(i).id)) {
                                messages.remove(i); adapter.notifyItemRemoved(i); break;
                            }
                        }
                    } else {
                        FirebaseUtils.getMessagesRef(chatId).child(m.id)
                                .child("deleted").setValue(true);
                    }
                }).show();
    }

    @Override public void onReact(Message m, String emoji) {
        ReactionsManager.toggleReaction(
                FirebaseUtils.getMessagesRef(chatId).child(m.id), currentUid, emoji, m);
    }

    @Override public void onForward(Message m) {
        Toast.makeText(this, "Forward: select a contact", Toast.LENGTH_SHORT).show();
    }

    @Override public void onStar(Message m) {
        boolean cur = Boolean.TRUE.equals(m.starred);
        FirebaseUtils.getMessagesRef(chatId).child(m.id).child("starred").setValue(!cur);
    }

    @Override public void onPin(Message m) {
        Map<String, Object> pin = new HashMap<>();
        pin.put("id", m.id);
        pin.put("text", m.text != null ? m.text : previewText(m));
        FirebaseUtils.getChatMetaRef(chatId).child("pinnedMsg").setValue(pin);
        FirebaseUtils.getMessagesRef(chatId).child(m.id).child("pinned").setValue(true);
    }

    @Override public void onReactionTap(Message m) {
        Map<String, Integer> counts = ReactionsManager.aggregateCounts(m);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : counts.entrySet())
            sb.append(e.getKey()).append(" × ").append(e.getValue()).append("\n");
        new AlertDialog.Builder(this).setTitle("Reactions")
                .setMessage(sb.length() > 0 ? sb.toString() : "No reactions yet")
                .setPositiveButton("OK", null).show();
    }

    // Poll vote from adapter
    public void onPollVote(Message m, int optionIndex) {
        DatabaseReference ref = FirebaseUtils.getMessagesRef(chatId).child(m.id);
        PollManager.vote(ref, currentUid, optionIndex);
    }
}
