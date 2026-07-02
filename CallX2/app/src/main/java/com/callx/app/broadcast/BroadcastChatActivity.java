package com.callx.app.broadcast;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.R;
import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BroadcastChatActivity — full-featured broadcast message composer.
 *
 * Advanced features implemented:
 *  1. Scheduled Send       — long-press send button → DateTimePicker
 *  2. Per-recipient Analytics — tap message → BroadcastAnalyticsActivity
 *  3. Reply Tracking       — watches recipient 1:1 chats, increments replyCount
 *  4. Caption for media    — dialog before upload for image/video
 *  5. Link preview         — detects URLs, fetches OG tags in background
 *  6. Recipient limit      — 256 cap (enforced in CreateBroadcastActivity)
 *  7. CSV export           — full delivery report from menu
 *  8. Poll / Survey        — create poll, delivered as formatted text
 *  9. Multi-media send     — select multiple images at once
 * 10. Search               — SearchView filters message history
 * 11. Auto-expiry          — set expiry time on any message (long-press)
 * 12. Forward broadcast    — long-press → forward to another list
 */
public class BroadcastChatActivity extends AppCompatActivity {

    private static final int MAX_SEEN_LISTENERS = 20;

    // ── Views ─────────────────────────────────────────────────────────────────
    private RecyclerView         rvMessages;
    private EditText             etMessage;
    private ImageButton          btnSend;
    private ImageButton          btnAttach;
    private TextView             tvSubtitle;

    // ── Adapter + data ────────────────────────────────────────────────────────
    private BroadcastMsgAdapter  adapter;
    private final List<BroadcastMessage> messages         = new ArrayList<>();
    private final List<BroadcastMessage> filteredMessages = new ArrayList<>();
    private String searchQuery = "";

    // ── Firebase ──────────────────────────────────────────────────────────────
    private String listId;
    private String listName;
    private String myUid;
    private DatabaseReference msgRef;
    private DatabaseReference listRef;
    private ValueEventListener msgListener;

    // Recipient cache
    private final Map<String, RecipientInfo> recipients   = new HashMap<>();
    // Seen listeners: msgId+recipientUid → listener
    private final Map<String, ValueEventListener> seenListeners = new HashMap<>();
    // Reply listeners: chatId → listener
    private final Map<String, ChildEventListener> replyListeners = new HashMap<>();

    // ── Feature 1: Scheduled send ─────────────────────────────────────────────
    private long scheduledSendAt = 0; // 0 = send now

    // ── Feature 3: Link preview (background fetch) ────────────────────────────
    private static final Pattern URL_PATTERN = Pattern.compile(
            "https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+", Pattern.CASE_INSENSITIVE);

    // ── Media pickers ─────────────────────────────────────────────────────────
    private ActivityResultLauncher<String>   imagePicker;
    private ActivityResultLauncher<String>   videoPicker;
    private ActivityResultLauncher<String>   docPicker;
    private ActivityResultLauncher<String>   audioPicker;
    private ActivityResultLauncher<String> multiImagePicker;

    private AlertDialog uploadDialog;

    // ── Formatters ────────────────────────────────────────────────────────────
    private final SimpleDateFormat timeFmt =
            new SimpleDateFormat("h:mm a", Locale.getDefault());
    private final SimpleDateFormat dtFmt =
            new SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault());

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_broadcast_chat);

        listId   = getIntent().getStringExtra(BroadcastListsActivity.EXTRA_LIST_ID);
        listName = getIntent().getStringExtra(BroadcastListsActivity.EXTRA_LIST_NAME);
        if (listId == null) { finish(); return; }

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        if (myUid == null) { finish(); return; }

        registerPickers();

        // Toolbar
        androidx.appcompat.widget.Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(listName != null ? listName : "Broadcast");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        tb.setNavigationOnClickListener(v -> finish());

        tvSubtitle = findViewById(R.id.tv_broadcast_subtitle);
        rvMessages = findViewById(R.id.rv_broadcast_messages);
        etMessage  = findViewById(R.id.et_broadcast_message);
        btnSend    = findViewById(R.id.btn_broadcast_send);
        btnAttach  = findViewById(R.id.btn_broadcast_attach);

        rvMessages.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BroadcastMsgAdapter(filteredMessages,
                this::onMessageTapped, this::onMessageLongPressed);
        rvMessages.setAdapter(adapter);

        msgRef  = FirebaseUtils.db()
                .getReference("broadcast_messages").child(myUid).child(listId);
        listRef = FirebaseUtils.db()
                .getReference("broadcast_lists").child(myUid).child(listId);

        etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                btnSend.setEnabled(!s.toString().trim().isEmpty());
            }
        });

        btnSend.setEnabled(false);
        btnSend.setOnClickListener(v -> sendTextMessage());

        // Feature 1: Long-press send → schedule picker
        btnSend.setOnLongClickListener(v -> {
            showSchedulePicker();
            return true;
        });

        btnAttach.setOnClickListener(v -> showAttachOptions());

        // Start background expiry worker
        BroadcastExpiryWorker.schedule(this);

        loadRecipients();
        attachMessageListener();
    }

    // ── Feature 10: Search ────────────────────────────────────────────────────

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_broadcast_chat, menu);

        MenuItem searchItem = menu.findItem(R.id.action_broadcast_search);
        if (searchItem != null) {
            SearchView sv = (SearchView) searchItem.getActionView();
            if (sv != null) {
                sv.setQueryHint("Search messages…");
                sv.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                    @Override public boolean onQueryTextSubmit(String q) { applySearch(q); return true; }
                    @Override public boolean onQueryTextChange(String q) { applySearch(q); return true; }
                });
                searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
                    @Override public boolean onMenuItemActionExpand(MenuItem i) { return true; }
                    @Override public boolean onMenuItemActionCollapse(MenuItem i) {
                        applySearch(""); return true;
                    }
                });
            }
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_broadcast_info) {
            showRecipientsInfo();
            return true;
        }
        if (id == R.id.action_broadcast_edit) {
            Intent i = new Intent(this, CreateBroadcastActivity.class);
            i.putExtra(BroadcastListsActivity.EXTRA_LIST_ID,   listId);
            i.putExtra(BroadcastListsActivity.EXTRA_LIST_NAME, listName);
            startActivity(i);
            return true;
        }
        // Feature 7: CSV export
        if (id == R.id.action_export_csv) {
            exportAllToCsv();
            return true;
        }
        // Scheduled messages list
        if (id == R.id.action_scheduled_messages) {
            showScheduledMessages();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pickers registration
    // ─────────────────────────────────────────────────────────────────────────
    private void registerPickers() {
        imagePicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) showCaptionDialog(uri, "image", "image"); });
        videoPicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) showCaptionDialog(uri, "video", "video"); });
        docPicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "file", "raw", null); });
        audioPicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "audio", "raw", null); });
        // Feature 9: Multi-image picker
        multiImagePicker = registerForActivityResult(
                new ActivityResultContracts.GetMultipleContents(),
                uris -> { if (uris != null && !uris.isEmpty()) uploadMultipleImages(uris); });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Recipients
    // ─────────────────────────────────────────────────────────────────────────
    private void loadRecipients() {
        listRef.child("recipients").addListenerForSingleValueEvent(
                new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        recipients.clear();
                        long count = snap.getChildrenCount();
                        tvSubtitle.setText("📢 " + count + " recipients");
                        for (DataSnapshot r : snap.getChildren()) {
                            String uid = r.getKey();
                            if (uid == null) continue;
                            FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(
                                    new ValueEventListener() {
                                        @Override public void onDataChange(@NonNull DataSnapshot us) {
                                            String name = us.child("name").getValue(String.class);
                                            recipients.put(uid, new RecipientInfo(uid,
                                                    name != null ? name : "User"));
                                        }
                                        @Override public void onCancelled(@NonNull DatabaseError e) {}
                                    });
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Message listener
    // ─────────────────────────────────────────────────────────────────────────
    private void attachMessageListener() {
        msgListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                messages.clear();
                for (DataSnapshot c : snap.getChildren()) {
                    BroadcastMessage m = c.getValue(BroadcastMessage.class);
                    if (m != null) {
                        if (m.id == null) m.id = c.getKey();
                        // Skip hard-expired messages
                        if (!m.isExpired()) messages.add(m);
                    }
                }
                applySearch(searchQuery);
                attachSeenTracking();
                attachReplyListeners();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        msgRef.orderByChild("timestamp").addValueEventListener(msgListener);
    }

    // Feature 10: Apply search filter
    private void applySearch(String query) {
        searchQuery = query == null ? "" : query.trim().toLowerCase(Locale.getDefault());
        filteredMessages.clear();
        if (searchQuery.isEmpty()) {
            filteredMessages.addAll(messages);
        } else {
            for (BroadcastMessage m : messages) {
                if ((m.text != null && m.text.toLowerCase(Locale.getDefault()).contains(searchQuery))
                        || (m.pollQuestion != null && m.pollQuestion.toLowerCase(Locale.getDefault()).contains(searchQuery))
                        || (m.caption != null && m.caption.toLowerCase(Locale.getDefault()).contains(searchQuery)))
                    filteredMessages.add(m);
            }
        }
        adapter.notifyDataSetChanged();
        if (!filteredMessages.isEmpty())
            rvMessages.scrollToPosition(filteredMessages.size() - 1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Seen tracking (Feature 2 — with timestamps)
    // ─────────────────────────────────────────────────────────────────────────
    private void attachSeenTracking() {
        int from = Math.max(0, messages.size() - MAX_SEEN_LISTENERS);
        for (int i = from; i < messages.size(); i++) {
            BroadcastMessage m = messages.get(i);
            if (m.id == null || !"sent".equals(m.status)) continue;
            if (m.seenCount >= m.deliveredCount) continue;

            for (RecipientInfo r : recipients.values()) {
                String key = m.id + ":" + r.uid;
                if (seenListeners.containsKey(key)) continue;

                String chatId = myUid.compareTo(r.uid) < 0
                        ? myUid + "_" + r.uid : r.uid + "_" + myUid;
                DatabaseReference seenRef = FirebaseUtils.db()
                        .getReference("chats").child(chatId)
                        .child("messages").child(m.id).child("seen");

                ValueEventListener l = new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        if (Boolean.TRUE.equals(snap.getValue(Boolean.class))) {
                            markSeen(m.id, r.uid);
                            seenRef.removeEventListener(this);
                            seenListeners.remove(key);
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                };
                seenRef.addValueEventListener(l);
                seenListeners.put(key, l);
            }
        }
    }

    /**
     * Atomically mark a recipient as seen (with timestamp) and increment seenCount.
     * Uses Firebase Transaction to prevent double-counting.
     */
    private void markSeen(String msgId, String recipientUid) {
        DatabaseReference msgEntry = msgRef.child(msgId);
        long nowMs = System.currentTimeMillis();

        msgEntry.runTransaction(new Transaction.Handler() {
            @NonNull @Override
            public Transaction.Result doTransaction(@NonNull MutableData data) {
                if (data.getValue() == null) return Transaction.abort();
                if (Boolean.TRUE.equals(
                        data.child("seenBy").child(recipientUid).getValue(Boolean.class)))
                    return Transaction.abort();

                data.child("seenBy").child(recipientUid).setValue(true);
                // Feature 2: store seen timestamp
                data.child("seenByTs").child(recipientUid).setValue(nowMs);
                Integer cur = data.child("seenCount").getValue(Integer.class);
                data.child("seenCount").setValue(cur != null ? cur + 1 : 1);
                return Transaction.success(data);
            }
            @Override public void onComplete(DatabaseError e, boolean ok, DataSnapshot s) {
                if (e != null)
                    android.util.Log.w("BroadcastChat", "markSeen failed: " + e.getMessage());
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Feature 3: Reply tracking
    // ─────────────────────────────────────────────────────────────────────────
    private void attachReplyListeners() {
        // For each sent message (capped to last 20 to save resources), listen
        // to each recipient's personal chat for new messages arriving AFTER
        // the broadcast timestamp. When detected, increment replyCount.
        int from = Math.max(0, messages.size() - 20);
        for (int i = from; i < messages.size(); i++) {
            BroadcastMessage m = messages.get(i);
            if (m.id == null || !"sent".equals(m.status)) continue;

            for (RecipientInfo r : recipients.values()) {
                String chatId = myUid.compareTo(r.uid) < 0
                        ? myUid + "_" + r.uid : r.uid + "_" + myUid;
                String listenerKey = m.id + ":reply:" + r.uid;
                if (replyListeners.containsKey(listenerKey)) continue;

                DatabaseReference chatMsgsRef = FirebaseUtils.db()
                        .getReference("chats").child(chatId).child("messages");

                ChildEventListener cl = new ChildEventListener() {
                    @Override public void onChildAdded(@NonNull DataSnapshot snap, String prev) {
                        String senderId = snap.child("senderId").getValue(String.class);
                        Long ts = snap.child("timestamp").getValue(Long.class);
                        // It's a reply if: sender is the recipient, message arrived after broadcast
                        if (r.uid.equals(senderId) && ts != null && ts > m.timestamp) {
                            msgRef.child(m.id).child("replyCount")
                                  .setValue(ServerValue.increment(1));

                            // Feature 8: if this broadcast was a poll, parse the reply as a vote
                            if (m.isPoll() && m.pollOptions != null) {
                                String replyText = snap.child("text").getValue(String.class);
                                if (replyText != null) {
                                    try {
                                        int choice = Integer.parseInt(replyText.trim()) - 1;
                                        if (choice >= 0 && choice < m.pollOptions.size()) {
                                            msgRef.child(m.id).child("pollVotes")
                                                  .child(r.uid).setValue(choice);
                                        }
                                    } catch (NumberFormatException ignored) {
                                        // Not a numeric reply — not a vote, just a normal reply
                                    }
                                }
                            }

                            // Only track first reply per recipient
                            chatMsgsRef.removeEventListener(this);
                            replyListeners.remove(listenerKey);
                        }
                    }
                    @Override public void onChildChanged(DataSnapshot s, String p) {}
                    @Override public void onChildRemoved(DataSnapshot s) {}
                    @Override public void onChildMoved(DataSnapshot s, String p) {}
                    @Override public void onCancelled(DatabaseError e) {}
                };
                chatMsgsRef.orderByChild("timestamp")
                        .startAt(m.timestamp + 1)
                        .addChildEventListener(cl);
                replyListeners.put(listenerKey, cl);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Attach options
    // ─────────────────────────────────────────────────────────────────────────
    private void showAttachOptions() {
        CharSequence[] options = {
                "📷 Image", "🎥 Video", "🎤 Audio/Voice",
                "📄 Document", "🖼️ Multiple Images", "📊 Poll"
        };
        new AlertDialog.Builder(this)
                .setTitle("Attachment bhejo")
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0: imagePicker.launch("image/*");    break;
                        case 1: videoPicker.launch("video/*");    break;
                        case 2: audioPicker.launch("audio/*");    break;
                        case 3: docPicker.launch("*/*");          break;
                        case 4: multiImagePicker.launch("image/*"); break; // Feature 9
                        case 5: showPollCreator();                break; // Feature 8
                    }
                })
                .show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Feature 4: Caption dialog before image/video upload
    // ─────────────────────────────────────────────────────────────────────────
    private void showCaptionDialog(Uri uri, String type, String cloudinaryType) {
        EditText etCaption = new EditText(this);
        etCaption.setHint("Caption likhna chahte ho? (optional)");
        etCaption.setPadding(48, 24, 48, 24);
        new AlertDialog.Builder(this)
                .setTitle("Caption add karo")
                .setView(etCaption)
                .setPositiveButton("Send", (d, w) -> {
                    String caption = etCaption.getText().toString().trim();
                    uploadAndSend(uri, type, cloudinaryType, caption.isEmpty() ? null : caption);
                })
                .setNegativeButton("Skip", (d, w) -> uploadAndSend(uri, type, cloudinaryType, null))
                .show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Feature 8: Poll creator
    // ─────────────────────────────────────────────────────────────────────────
    private void showPollCreator() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        EditText etQuestion = new EditText(this);
        etQuestion.setHint("Poll question likhein…");
        layout.addView(etQuestion);

        List<EditText> optionFields = new ArrayList<>();
        // Start with 2 options
        for (int i = 0; i < 2; i++) {
            EditText etOpt = new EditText(this);
            etOpt.setHint("Option " + (i + 1));
            layout.addView(etOpt);
            optionFields.add(etOpt);
        }

        TextView tvAddOption = new TextView(this);
        tvAddOption.setText("+ Option add karo");
        tvAddOption.setTextColor(0xFF1565C0);
        tvAddOption.setPadding(0, pad / 2, 0, 0);
        layout.addView(tvAddOption);

        tvAddOption.setOnClickListener(v -> {
            if (optionFields.size() >= 10) {
                Toast.makeText(this, "Maximum 10 options allowed", Toast.LENGTH_SHORT).show();
                return;
            }
            EditText etOpt = new EditText(this);
            etOpt.setHint("Option " + (optionFields.size() + 1));
            layout.addView(etOpt, layout.indexOfChild(tvAddOption));
            optionFields.add(etOpt);
        });

        new AlertDialog.Builder(this)
                .setTitle("📊 Poll banao")
                .setView(layout)
                .setPositiveButton("Create Poll", (d, w) -> {
                    String question = etQuestion.getText().toString().trim();
                    if (question.isEmpty()) {
                        Toast.makeText(this, "Question likhein", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    List<String> options = new ArrayList<>();
                    for (EditText ef : optionFields) {
                        String opt = ef.getText().toString().trim();
                        if (!opt.isEmpty()) options.add(opt);
                    }
                    if (options.size() < 2) {
                        Toast.makeText(this, "Kam se kam 2 options chahiye", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    dispatchPoll(question, options);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void dispatchPoll(String question, List<String> options) {
        if (recipients.isEmpty()) {
            Toast.makeText(this, "Recipients load ho rahe hain", Toast.LENGTH_SHORT).show();
            return;
        }
        long now = System.currentTimeMillis();
        String msgId = msgRef.push().getKey();
        if (msgId == null) return;

        BroadcastMessage bm = new BroadcastMessage(msgId, question, "poll",
                null, null, null, myUid, now, recipients.size());
        bm.pollQuestion = question;
        bm.pollOptions  = options;
        bm.pollVotes    = new HashMap<>();
        bm.expiresAt    = 0; // no auto-expire for polls by default

        // Build formatted text for delivery
        StringBuilder sb = new StringBuilder("📊 Poll: ").append(question).append("\n\n");
        for (int i = 0; i < options.size(); i++)
            sb.append(i + 1).append(". ").append(options.get(i)).append("\n");
        sb.append("\n(Reply with your choice number)");

        // Feature 1: honor scheduled send for polls too
        if (scheduledSendAt > 0 && scheduledSendAt > now) {
            bm.status      = "scheduled";
            bm.scheduledAt = scheduledSendAt;
            msgRef.child(msgId).setValue(bm);
            scheduleDelivery(msgId, sb.toString(), "poll", null, null, null, now, 0);
            Toast.makeText(this, "📅 Poll scheduled for " + dtFmt.format(new Date(scheduledSendAt)),
                    Toast.LENGTH_LONG).show();
            resetSchedule();
            return;
        }

        msgRef.child(msgId).setValue(bm);
        BroadcastDeliveryWorker.enqueue(this, myUid, listId, msgId,
                sb.toString(), "poll", null, null, null, now, 0);

        resetSchedule();
        Toast.makeText(this, "📊 Poll bheja ja raha hai…", Toast.LENGTH_SHORT).show();
    }


    // ─────────────────────────────────────────────────────────────────────────
    // Feature 9: Multi-image upload
    // ─────────────────────────────────────────────────────────────────────────
    private void uploadMultipleImages(List<Uri> uris) {
        if (uris.isEmpty()) return;
        if (recipients.isEmpty()) {
            Toast.makeText(this, "Recipients load ho rahe hain", Toast.LENGTH_SHORT).show();
            return;
        }
        if (uris.size() > 10) {
            Toast.makeText(this, "Max 10 images at once", Toast.LENGTH_SHORT).show();
            return;
        }

        showUploadDialog("Uploading " + uris.size() + " images…");
        List<String> uploadedUrls = new ArrayList<>(Collections.nCopies(uris.size(), null));
        final int[] done = {0};

        for (int i = 0; i < uris.size(); i++) {
            final int idx = i;
            CloudinaryUploader.upload(this, uris.get(i), "broadcast", "image",
                    new CloudinaryUploader.UploadCallback() {
                        @Override public void onSuccess(CloudinaryUploader.Result result) {
                            uploadedUrls.set(idx, result.secureUrl);
                            done[0]++;
                            if (done[0] == uris.size()) {
                                // All uploaded — remove nulls and send
                                uploadedUrls.removeIf(u -> u == null);
                                runOnUiThread(() -> {
                                    dismissUploadDialog();
                                    if (!uploadedUrls.isEmpty())
                                        dispatchMultiMedia(uploadedUrls);
                                });
                            }
                        }
                        @Override public void onError(String message) {
                            done[0]++;
                            if (done[0] == uris.size()) {
                                runOnUiThread(() -> {
                                    dismissUploadDialog();
                                    uploadedUrls.removeIf(u -> u == null);
                                    if (!uploadedUrls.isEmpty()) dispatchMultiMedia(uploadedUrls);
                                    else Toast.makeText(BroadcastChatActivity.this,
                                            "All uploads failed", Toast.LENGTH_SHORT).show();
                                });
                            }
                        }
                    });
        }
    }

    private void dispatchMultiMedia(List<String> urls) {
        long now = System.currentTimeMillis();
        String msgId = msgRef.push().getKey();
        if (msgId == null) return;

        BroadcastMessage bm = new BroadcastMessage(msgId, null, "multi_media",
                urls.get(0), null, "+" + (urls.size() - 1) + " more",
                myUid, now, recipients.size());
        bm.mediaUrls = new ArrayList<>(urls);
        bm.expiresAt = 0;

        // Feature 1: honor scheduled send for multi-media too
        if (scheduledSendAt > 0 && scheduledSendAt > now) {
            bm.status      = "scheduled";
            bm.scheduledAt = scheduledSendAt;
            msgRef.child(msgId).setValue(bm);
            scheduleDelivery(msgId, null, "multi_media", urls.get(0),
                    null, "+" + (urls.size() - 1) + " more", now, 0);
            Toast.makeText(this, "📅 Photos scheduled for " + dtFmt.format(new Date(scheduledSendAt)),
                    Toast.LENGTH_LONG).show();
            resetSchedule();
            return;
        }

        msgRef.child(msgId).setValue(bm);
        BroadcastDeliveryWorker.enqueue(this, myUid, listId, msgId,
                null, "multi_media", urls.get(0),
                null, "+" + (urls.size() - 1) + " more", now, 0);
        resetSchedule();
    }


    // ─────────────────────────────────────────────────────────────────────────
    // Feature 5: Link preview fetch (background)
    // ─────────────────────────────────────────────────────────────────────────
    private void fetchLinkPreview(String msgId, String text) {
        Matcher m = URL_PATTERN.matcher(text);
        if (!m.find()) return;
        String url = m.group();

        new Thread(() -> {
            try {
                java.net.URL netUrl = new java.net.URL(url);
                java.net.HttpURLConnection con =
                        (java.net.HttpURLConnection) netUrl.openConnection();
                con.setRequestMethod("GET");
                con.setConnectTimeout(5000);
                con.setReadTimeout(5000);
                con.setRequestProperty("User-Agent", "Mozilla/5.0");
                con.connect();

                java.io.InputStream is = con.getInputStream();
                byte[] buf = new byte[32768]; // only read first 32KB for OG tags
                int read = is.read(buf);
                String html = read > 0 ? new String(buf, 0, read, "UTF-8") : "";
                is.close();

                String title = extractMeta(html, "og:title");
                String desc  = extractMeta(html, "og:description");
                String img   = extractMeta(html, "og:image");
                if (title == null) title = extractTag(html, "title");

                if (title != null && !title.trim().isEmpty()) {
                    final String fTitle = title.trim();
                    final String fDesc  = desc  != null ? desc.trim()  : "";
                    final String fImg   = img   != null ? img.trim()   : "";

                    runOnUiThread(() -> {
                        Map<String, Object> preview = new HashMap<>();
                        preview.put("linkPreviewUrl",      url);
                        preview.put("linkPreviewTitle",    fTitle);
                        preview.put("linkPreviewDesc",     fDesc);
                        preview.put("linkPreviewImageUrl", fImg);
                        msgRef.child(msgId).updateChildren(preview);
                    });
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private String extractMeta(String html, String property) {
        Pattern p = Pattern.compile(
                "<meta[^>]+property=['\"]" + property + "['\"][^>]+content=['\"]([^'\"]+)['\"]",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        if (m.find()) return m.group(1);
        // try reversed attribute order
        Pattern p2 = Pattern.compile(
                "<meta[^>]+content=['\"]([^'\"]+)['\"][^>]+property=['\"]" + property + "['\"]",
                Pattern.CASE_INSENSITIVE);
        Matcher m2 = p2.matcher(html);
        return m2.find() ? m2.group(1) : null;
    }

    private String extractTag(String html, String tag) {
        Pattern p = Pattern.compile("<" + tag + "[^>]*>([^<]+)</" + tag + ">",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        return m.find() ? m.group(1).trim() : null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Send text message
    // ─────────────────────────────────────────────────────────────────────────
    private void sendTextMessage() {
        String text = etMessage.getText().toString().trim();
        if (text.isEmpty()) return;
        etMessage.setText("");

        dispatchBroadcast(text, "text", null, null, null);

        // Feature 5: fetch link preview in background
        String msgId = getLastMsgId();
        if (msgId != null && URL_PATTERN.matcher(text).find()) {
            fetchLinkPreview(msgId, text);
        }
    }

    private String lastDispatchedMsgId = null;

    private String getLastMsgId() { return lastDispatchedMsgId; }

    // ─────────────────────────────────────────────────────────────────────────
    // Core dispatch
    // ─────────────────────────────────────────────────────────────────────────
    private void dispatchBroadcast(String text, String type,
                                   String mediaUrl, String fileName, String caption) {
        if (recipients.isEmpty()) {
            Toast.makeText(this, "Recipients load ho rahe hain", Toast.LENGTH_SHORT).show();
            return;
        }
        long now = System.currentTimeMillis();
        String msgId = msgRef.push().getKey();
        if (msgId == null) return;
        lastDispatchedMsgId = msgId;

        BroadcastMessage bm = new BroadcastMessage(msgId, text, type,
                mediaUrl, fileName, caption, myUid, now, recipients.size());

        // Feature 1: Scheduled
        if (scheduledSendAt > 0 && scheduledSendAt > now) {
            bm.status      = "scheduled";
            bm.scheduledAt = scheduledSendAt;
            msgRef.child(msgId).setValue(bm);
            long delay = scheduledSendAt - now;
            scheduleDelivery(msgId, text, type, mediaUrl, fileName, caption, now, 0);
            Toast.makeText(this, "📅 Scheduled for " + dtFmt.format(new Date(scheduledSendAt)),
                    Toast.LENGTH_LONG).show();
            resetSchedule();
            return;
        }

        msgRef.child(msgId).setValue(bm);
        BroadcastDeliveryWorker.enqueue(this, myUid, listId, msgId,
                text, type, mediaUrl, fileName, caption, now, 0);
    }

    /** Enqueue a scheduled delivery via BroadcastScheduleWorker. */
    private void scheduleDelivery(String msgId, String text, String type,
                                  String mediaUrl, String fileName, String caption,
                                  long timestamp, long expiresAt) {
        long delay = scheduledSendAt - System.currentTimeMillis();
        BroadcastScheduleWorker.enqueue(this, Math.max(0, delay),
                myUid, listId, msgId, text, type,
                mediaUrl, fileName, caption, timestamp, expiresAt);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Feature 1: Schedule picker (long-press send)
    // ─────────────────────────────────────────────────────────────────────────
    private void showSchedulePicker() {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.MINUTE, 5); // default: 5 min from now

        DatePickerDialog dpd = new DatePickerDialog(this,
                (view, year, month, day) -> {
                    TimePickerDialog tpd = new TimePickerDialog(this,
                            (tv, hour, minute) -> {
                                Calendar selected = Calendar.getInstance();
                                selected.set(year, month, day, hour, minute, 0);
                                selected.set(Calendar.MILLISECOND, 0);
                                long ts = selected.getTimeInMillis();
                                if (ts <= System.currentTimeMillis()) {
                                    Toast.makeText(this, "Future time select karo",
                                            Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                scheduledSendAt = ts;
                                btnSend.setContentDescription("Send scheduled for "
                                        + dtFmt.format(new Date(ts)));
                                Toast.makeText(this,
                                        "📅 Scheduled: " + dtFmt.format(new Date(ts))
                                                + "\n(Send button se bhejo)",
                                        Toast.LENGTH_LONG).show();
                            },
                            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false);
                    tpd.show();
                },
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
        dpd.getDatePicker().setMinDate(System.currentTimeMillis());
        dpd.show();
    }

    private void resetSchedule() {
        scheduledSendAt = 0;
        btnSend.setContentDescription("Send broadcast");
    }

    // Show scheduled messages in this list
    private void showScheduledMessages() {
        List<BroadcastMessage> scheduled = new ArrayList<>();
        for (BroadcastMessage m : messages)
            if ("scheduled".equals(m.status)) scheduled.add(m);
        if (scheduled.isEmpty()) {
            Toast.makeText(this, "Koi scheduled message nahi", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (BroadcastMessage m : scheduled) {
            sb.append("• ").append(m.text != null ? m.text : m.type)
              .append("\n  📅 ").append(dtFmt.format(new Date(m.scheduledAt)))
              .append("\n\n");
        }
        new AlertDialog.Builder(this)
                .setTitle("📅 Scheduled Messages (" + scheduled.size() + ")")
                .setMessage(sb.toString())
                .setNeutralButton("Cancel All", (d, w) -> {
                    for (BroadcastMessage m : scheduled) {
                        BroadcastScheduleWorker.cancel(this, m.id);
                        msgRef.child(m.id).child("status").setValue("failed");
                    }
                })
                .setPositiveButton("OK", null)
                .show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Upload + send (single file)
    // ─────────────────────────────────────────────────────────────────────────
    private void uploadAndSend(Uri uri, String type, String cloudinaryResourceType,
                               String caption) {
        if (recipients.isEmpty()) {
            Toast.makeText(this, "Recipients load ho rahe hain", Toast.LENGTH_SHORT).show();
            return;
        }
        showUploadDialog("Upload ho raha hai…");
        String fileName = queryFileName(uri, type);

        CloudinaryUploader.upload(this, uri, "broadcast", cloudinaryResourceType,
                new CloudinaryUploader.UploadCallback() {
                    @Override public void onSuccess(CloudinaryUploader.Result result) {
                        runOnUiThread(() -> {
                            dismissUploadDialog();
                            dispatchBroadcast(caption, type, result.secureUrl, fileName, caption);
                        });
                    }
                    @Override public void onError(String message) {
                        runOnUiThread(() -> {
                            dismissUploadDialog();
                            Toast.makeText(BroadcastChatActivity.this,
                                    "Upload fail: " + message, Toast.LENGTH_SHORT).show();
                        });
                    }
                });
    }

    private String queryFileName(Uri uri, String type) {
        String name = null;
        try (android.database.Cursor cursor = getContentResolver()
                .query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = cursor.getString(idx);
            }
        } catch (Exception ignored) {}
        if (name == null || name.isEmpty()) {
            String mime = getContentResolver().getType(uri);
            String ext  = mime != null
                    ? MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) : null;
            name = type + "." + (ext != null ? ext : "bin");
        }
        return name;
    }

    private void showUploadDialog(String message) {
        ProgressBar pb = new ProgressBar(this);
        int pad = (int)(24 * getResources().getDisplayMetrics().density);
        pb.setPadding(pad, pad, pad, pad);
        uploadDialog = new AlertDialog.Builder(this)
                .setTitle(message)
                .setView(pb)
                .setCancelable(false)
                .show();
    }

    private void dismissUploadDialog() {
        if (uploadDialog != null && uploadDialog.isShowing()) uploadDialog.dismiss();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Message tap / long-press
    // ─────────────────────────────────────────────────────────────────────────
    private void onMessageTapped(BroadcastMessage m) {
        if ("failed".equals(m.status)) {
            retryMessage(m);
        } else if ("sent".equals(m.status)) {
            // Feature 2: open analytics on tap
            openAnalytics(m);
        }
    }

    /** Feature 2 + 7 + 11 + 12: long-press action sheet */
    private void onMessageLongPressed(BroadcastMessage m) {
        List<String> options = new ArrayList<>();
        options.add("📊 Analytics (who saw it)");
        options.add("↩️ Forward to another list");
        options.add("⏱ Set auto-expiry");
        options.add("📤 Export this message CSV");
        if ("scheduled".equals(m.status)) options.add("❌ Cancel scheduled send");
        options.add("🗑️ Delete from history");

        new AlertDialog.Builder(this)
                .setTitle(m.text != null && !m.text.isEmpty()
                        ? (m.text.length() > 40 ? m.text.substring(0, 40) + "…" : m.text)
                        : m.type)
                .setItems(options.toArray(new CharSequence[0]), (d, which) -> {
                    String chosen = options.get(which);
                    if (chosen.startsWith("📊")) openAnalytics(m);
                    else if (chosen.startsWith("↩️")) forwardToList(m);
                    else if (chosen.startsWith("⏱")) showExpiryPicker(m);
                    else if (chosen.startsWith("📤")) exportMessageCsv(m);
                    else if (chosen.startsWith("❌")) {
                        BroadcastScheduleWorker.cancel(this, m.id);
                        msgRef.child(m.id).child("status").setValue("failed");
                        Toast.makeText(this, "Scheduled send cancelled", Toast.LENGTH_SHORT).show();
                    }
                    else if (chosen.startsWith("🗑️")) confirmDeleteMessage(m);
                })
                .show();
    }

    /** Feature 2: Open per-recipient analytics */
    private void openAnalytics(BroadcastMessage m) {
        Intent i = new Intent(this, BroadcastAnalyticsActivity.class);
        i.putExtra(BroadcastAnalyticsActivity.EXTRA_MSG_ID,   m.id);
        i.putExtra(BroadcastAnalyticsActivity.EXTRA_LIST_ID,  listId);
        i.putExtra(BroadcastAnalyticsActivity.EXTRA_MSG_TEXT,
                m.text != null ? m.text : (m.pollQuestion != null ? m.pollQuestion : m.type));
        startActivity(i);
    }

    /** Feature 12: Forward to another broadcast list */
    private void forwardToList(BroadcastMessage original) {
        // Load all other lists owned by the user
        FirebaseUtils.db()
                .getReference("broadcast_lists").child(myUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        List<String> listNames = new ArrayList<>();
                        List<String> listIds   = new ArrayList<>();
                        for (DataSnapshot child : snap.getChildren()) {
                            String lid  = child.getKey();
                            String lname = child.child("name").getValue(String.class);
                            if (lid != null && !lid.equals(listId)) {
                                listIds.add(lid);
                                listNames.add(lname != null ? lname : lid);
                            }
                        }
                        if (listIds.isEmpty()) {
                            Toast.makeText(BroadcastChatActivity.this,
                                    "Koi aur list nahi hai", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        new AlertDialog.Builder(BroadcastChatActivity.this)
                                .setTitle("Forward to…")
                                .setItems(listNames.toArray(new CharSequence[0]),
                                        (d, which) -> {
                                            String targetListId = listIds.get(which);
                                            forwardMessageToList(original, targetListId,
                                                    listNames.get(which));
                                        })
                                .show();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
    }

    private void forwardMessageToList(BroadcastMessage original,
                                      String targetListId, String targetListName) {
        DatabaseReference targetMsgRef = FirebaseUtils.db()
                .getReference("broadcast_messages").child(myUid).child(targetListId);
        DatabaseReference targetListRef = FirebaseUtils.db()
                .getReference("broadcast_lists").child(myUid).child(targetListId);

        // Count recipients in target list first
        targetListRef.child("recipients").addListenerForSingleValueEvent(
                new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot rSnap) {
                        long count = rSnap.getChildrenCount();
                        if (count == 0) {
                            Toast.makeText(BroadcastChatActivity.this,
                                    targetListName + " mein koi recipient nahi",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }

                        long now   = System.currentTimeMillis();
                        String fwdId = targetMsgRef.push().getKey();
                        if (fwdId == null) return;

                        BroadcastMessage fwd = new BroadcastMessage(fwdId,
                                original.text, original.type, original.mediaUrl,
                                original.fileName, original.caption,
                                myUid, now, (int) count);
                        fwd.linkPreviewUrl      = original.linkPreviewUrl;
                        fwd.linkPreviewTitle    = original.linkPreviewTitle;
                        fwd.linkPreviewDesc     = original.linkPreviewDesc;
                        fwd.linkPreviewImageUrl = original.linkPreviewImageUrl;
                        fwd.pollQuestion        = original.pollQuestion;
                        fwd.pollOptions         = original.pollOptions;
                        fwd.mediaUrls           = original.mediaUrls;

                        targetMsgRef.child(fwdId).setValue(fwd);
                        BroadcastDeliveryWorker.enqueue(BroadcastChatActivity.this,
                                myUid, targetListId, fwdId,
                                original.text, original.type, original.mediaUrl,
                                original.fileName, original.caption, now, 0);

                        Toast.makeText(BroadcastChatActivity.this,
                                "Forwarded to \"" + targetListName + "\" ✓",
                                Toast.LENGTH_SHORT).show();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
    }

    /** Feature 11: Set auto-expiry on a message */
    private void showExpiryPicker(BroadcastMessage m) {
        CharSequence[] options = {
                "1 hour", "6 hours", "24 hours", "3 days", "7 days", "Remove expiry"
        };
        long[] millis = {
                3_600_000L, 21_600_000L, 86_400_000L,
                3 * 86_400_000L, 7 * 86_400_000L, 0L
        };
        new AlertDialog.Builder(this)
                .setTitle("⏱ Auto-expiry set karo")
                .setItems(options, (d, which) -> {
                    long expiresAt = millis[which] > 0
                            ? System.currentTimeMillis() + millis[which] : 0;
                    msgRef.child(m.id).child("expiresAt").setValue(expiresAt);
                    String msg = expiresAt > 0
                            ? "Message " + options[which] + " baad delete ho jayega"
                            : "Expiry remove ho gayi";
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void confirmDeleteMessage(BroadcastMessage m) {
        new AlertDialog.Builder(this)
                .setTitle("Delete message?")
                .setMessage("Yeh message sirf aapke broadcast history se delete hoga.")
                .setPositiveButton("Delete", (d, w) ->
                        msgRef.child(m.id).removeValue()
                                .addOnSuccessListener(a -> Toast.makeText(this,
                                        "Message deleted", Toast.LENGTH_SHORT).show()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Feature 7: Export a single message delivery stats */
    private void exportMessageCsv(BroadcastMessage m) {
        Intent i = new Intent(this, BroadcastAnalyticsActivity.class);
        i.putExtra(BroadcastAnalyticsActivity.EXTRA_MSG_ID,   m.id);
        i.putExtra(BroadcastAnalyticsActivity.EXTRA_LIST_ID,  listId);
        i.putExtra(BroadcastAnalyticsActivity.EXTRA_MSG_TEXT,
                m.text != null ? m.text : m.type);
        startActivity(i);
        // CSV export button is on the analytics screen
    }

    /** Feature 7: Export ALL messages from this broadcast list to CSV */
    private void exportAllToCsv() {
        if (messages.isEmpty()) {
            Toast.makeText(this, "Export karne ke liye koi message nahi", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            File dir = getExternalFilesDir("exports");
            if (dir != null) dir.mkdirs();
            File csv = new File(dir, "broadcast_" + listId + "_"
                    + System.currentTimeMillis() + ".csv");
            FileWriter fw = new FileWriter(csv);
            fw.write("MessageID,Type,Text,Timestamp,Status,TotalRecipients,"
                    + "Delivered,Seen,Skipped,Replies,ExpiresAt,ScheduledAt\n");
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            for (BroadcastMessage m : messages) {
                fw.write(csv(m.id) + "," + csv(m.type) + "," + csv(m.text) + ","
                        + csv(m.timestamp > 0 ? sdf.format(new Date(m.timestamp)) : "") + ","
                        + csv(m.status) + "," + m.totalRecipients + ","
                        + m.deliveredCount + "," + m.seenCount + ","
                        + m.skippedCount + "," + m.replyCount + ","
                        + (m.expiresAt > 0 ? sdf.format(new Date(m.expiresAt)) : "") + ","
                        + (m.scheduledAt > 0 ? sdf.format(new Date(m.scheduledAt)) : "")
                        + "\n");
            }
            fw.close();

            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", csv);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/csv");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, "Broadcast Report — " + listName);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Share Report"));
        } catch (IOException e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n"))
            return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    private String csv(long l) { return String.valueOf(l); }

    // ─────────────────────────────────────────────────────────────────────────
    // Recipients info sheet
    // ─────────────────────────────────────────────────────────────────────────
    private void showRecipientsInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append(recipients.size()).append(" recipients:\n\n");
        List<RecipientInfo> sorted = new ArrayList<>(recipients.values());
        sorted.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        for (RecipientInfo r : sorted) sb.append("• ").append(r.name).append("\n");
        new AlertDialog.Builder(this)
                .setTitle("📢 " + listName)
                .setMessage(sb.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    private void retryMessage(BroadcastMessage m) {
        if (m.id == null) return;
        msgRef.child(m.id).child("status").setValue("sending");
        BroadcastDeliveryWorker.enqueue(this, myUid, listId, m.id,
                m.text, m.type, m.mediaUrl, m.fileName, m.caption, m.timestamp, m.expiresAt);
        Toast.makeText(this, "Retry ho raha hai…", Toast.LENGTH_SHORT).show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected void onStop() {
        super.onStop();
        // Detach lightweight seen + reply listeners when activity is hidden.
        // Prevents leaks in long-running backgrounded activities.
        // They are re-attached automatically via attachMessageListener → attachSeenTracking()
        // next time onStart() causes the RecyclerView to re-bind.
        detachAllListeners();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (msgRef != null && msgListener != null) msgRef.removeEventListener(msgListener);
        detachAllListeners();
    }

    private void detachAllListeners() {
        if (myUid == null) return;

        for (Map.Entry<String, ValueEventListener> e : seenListeners.entrySet()) {
            // key format: "{msgId}:{recipientUid}"
            String[] parts = e.getKey().split(":", 2);
            if (parts.length < 2) continue;
            String recipientUid = parts[1];
            String cid = myUid.compareTo(recipientUid) < 0
                    ? myUid + "_" + recipientUid : recipientUid + "_" + myUid;
            FirebaseUtils.db().getReference("chats").child(cid)
                    .child("messages").child(parts[0]).child("seen")
                    .removeEventListener(e.getValue());
        }
        seenListeners.clear();

        for (Map.Entry<String, ChildEventListener> e : replyListeners.entrySet()) {
            // key format: "{msgId}:reply:{recipientUid}"
            int sep = e.getKey().indexOf(":reply:");
            if (sep < 0) continue;
            String recipientUid = e.getKey().substring(sep + 7);
            String cid = myUid.compareTo(recipientUid) < 0
                    ? myUid + "_" + recipientUid : recipientUid + "_" + myUid;
            FirebaseUtils.db().getReference("chats").child(cid)
                    .child("messages").removeEventListener(e.getValue());
        }
        replyListeners.clear();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────
    static class RecipientInfo {
        String uid, name;
        RecipientInfo(String uid, String name) { this.uid = uid; this.name = name; }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Message Adapter (all advanced features rendered)
    // ─────────────────────────────────────────────────────────────────────────
    interface OnMsgTap      { void tap(BroadcastMessage m); }
    interface OnMsgLongTap  { void tap(BroadcastMessage m); }

    static class BroadcastMsgAdapter
            extends RecyclerView.Adapter<BroadcastMsgAdapter.VH> {

        private final List<BroadcastMessage> data;
        private final OnMsgTap      onTap;
        private final OnMsgLongTap  onLongTap;
        private final SimpleDateFormat timeFmt =
                new SimpleDateFormat("h:mm a", Locale.getDefault());
        private final SimpleDateFormat dtFmt =
                new SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault());

        BroadcastMsgAdapter(List<BroadcastMessage> data,
                            OnMsgTap onTap, OnMsgLongTap onLongTap) {
            this.data = data; this.onTap = onTap; this.onLongTap = onLongTap;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_broadcast_message, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            BroadcastMessage m = data.get(pos);

            // ── Feature 1: Scheduled indicator ─────────────────────────────
            if (m.isScheduled()) {
                h.tvScheduledLabel.setVisibility(View.VISIBLE);
                h.tvScheduledLabel.setText("📅 Scheduled: " + dtFmt.format(new Date(m.scheduledAt)));
            } else {
                h.tvScheduledLabel.setVisibility(View.GONE);
            }

            // ── Feature 8: Poll card ────────────────────────────────────────
            if (m.isPoll() && m.pollQuestion != null) {
                h.llPollCard.setVisibility(View.VISIBLE);
                h.tvMessage.setVisibility(View.GONE);
                h.tvPollQuestion.setText("📊 " + m.pollQuestion);
                h.llPollOptions.removeAllViews();
                int[] voteCounts = m.getPollVoteCounts();
                int totalVotes   = m.pollVotes != null ? m.pollVotes.size() : 0;
                if (m.pollOptions != null) {
                    for (int i = 0; i < m.pollOptions.size(); i++) {
                        String opt = m.pollOptions.get(i);
                        int votes  = i < voteCounts.length ? voteCounts[i] : 0;
                        int pct    = totalVotes > 0 ? (votes * 100 / totalVotes) : 0;

                        LinearLayout row = new LinearLayout(h.itemView.getContext());
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setPadding(0, 4, 0, 4);

                        TextView tvOpt = new TextView(h.itemView.getContext());
                        tvOpt.setText((i + 1) + ". " + opt + "  " + pct + "% (" + votes + ")");
                        tvOpt.setTextSize(12);
                        row.addView(tvOpt);
                        h.llPollOptions.addView(row);
                    }
                }
                h.tvPollTotalVotes.setText(totalVotes + " votes");
            } else {
                h.llPollCard.setVisibility(View.GONE);
                h.tvMessage.setVisibility(View.VISIBLE);
            }

            // ── Feature 6: Link preview ─────────────────────────────────────
            if (m.hasLinkPreview()) {
                h.llLinkPreview.setVisibility(View.VISIBLE);
                h.tvLinkTitle.setText(m.linkPreviewTitle);
                h.tvLinkDesc.setVisibility(m.linkPreviewDesc != null && !m.linkPreviewDesc.isEmpty()
                        ? View.VISIBLE : View.GONE);
                h.tvLinkDesc.setText(m.linkPreviewDesc);
                h.tvLinkUrl.setText(m.linkPreviewUrl);
                if (m.linkPreviewImageUrl != null && !m.linkPreviewImageUrl.isEmpty()) {
                    h.ivLinkThumb.setVisibility(View.VISIBLE);
                    Glide.with(h.itemView.getContext())
                            .asBitmap()
                            .load(m.linkPreviewImageUrl)
                            .into(h.ivLinkThumb);
                } else {
                    h.ivLinkThumb.setVisibility(View.GONE);
                }
                h.llLinkPreview.setOnClickListener(v -> {
                    try {
                        Intent browser = new Intent(Intent.ACTION_VIEW,
                                Uri.parse(m.linkPreviewUrl));
                        v.getContext().startActivity(browser);
                    } catch (Exception ignored) {}
                });
            } else {
                h.llLinkPreview.setVisibility(View.GONE);
            }

            // ── Feature 9: Multi-media strip ────────────────────────────────
            if (m.isMultiMedia() && m.mediaUrls != null && !m.mediaUrls.isEmpty()) {
                h.llMultiMedia.setVisibility(View.VISIBLE);
                h.llMultiMedia.removeAllViews();
                int thumbSize = (int)(64 * h.itemView.getContext()
                        .getResources().getDisplayMetrics().density);
                int max = Math.min(m.mediaUrls.size(), 5);
                for (int i = 0; i < max; i++) {
                    ImageView iv = new ImageView(h.itemView.getContext());
                    LinearLayout.LayoutParams lp =
                            new LinearLayout.LayoutParams(thumbSize, thumbSize);
                    lp.setMarginEnd(4);
                    iv.setLayoutParams(lp);
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    Glide.with(h.itemView.getContext()).load(m.mediaUrls.get(i)).into(iv);
                    h.llMultiMedia.addView(iv);
                }
                if (m.mediaUrls.size() > 5) {
                    TextView more = new TextView(h.itemView.getContext());
                    more.setText("+" + (m.mediaUrls.size() - 5));
                    more.setTextSize(12);
                    h.llMultiMedia.addView(more);
                }
            } else {
                h.llMultiMedia.setVisibility(View.GONE);
            }

            // ── Message text ────────────────────────────────────────────────
            if (!m.isPoll()) {
                String preview;
                if ("text".equals(m.type) || m.type == null) {
                    preview = m.text != null ? m.text : "";
                } else if ("multi_media".equals(m.type)) {
                    preview = m.mediaUrls != null
                            ? m.mediaUrls.size() + " images"
                            : (m.caption != null ? m.caption : "Photos");
                } else {
                    preview = typeIcon(m.type)
                            + (m.caption != null && !m.caption.isEmpty()
                            ? " " + m.caption : " " + typeName(m.type));
                }
                h.tvMessage.setText(preview);
            }

            // ── Feature 11: Expiry label ─────────────────────────────────────
            if (m.expiresAt > 0) {
                h.tvExpiryLabel.setVisibility(View.VISIBLE);
                long remaining = m.expiresAt - System.currentTimeMillis();
                if (remaining > 0) {
                    h.tvExpiryLabel.setText("⏱ Expires in " + formatDuration(remaining));
                } else {
                    h.tvExpiryLabel.setText("⏱ Expired");
                }
            } else {
                h.tvExpiryLabel.setVisibility(View.GONE);
            }

            // ── Feature 3: Reply count ───────────────────────────────────────
            if (m.replyCount > 0) {
                h.tvReplyCount.setVisibility(View.VISIBLE);
                h.tvReplyCount.setText("💬 " + m.replyCount + " "
                        + (m.replyCount == 1 ? "reply" : "replies"));
            } else {
                h.tvReplyCount.setVisibility(View.GONE);
            }

            // ── Delivery status footer ───────────────────────────────────────
            if ("failed".equals(m.status)) {
                h.tvDelivery.setText("⚠️ Failed — tap to retry");
            } else if ("sending".equals(m.status)) {
                h.tvDelivery.setText("⏳ Sending…");
            } else if ("scheduled".equals(m.status)) {
                h.tvDelivery.setText("📅 Scheduled");
            } else {
                String d = "📢 " + m.deliveredCount + "/" + m.totalRecipients;
                if (m.seenCount > 0) d += " • 👁 " + m.seenCount + " seen";
                if (m.skippedCount > 0) d += " • " + m.skippedCount + " skipped";
                h.tvDelivery.setText(d);
            }

            h.tvTime.setText(timeFmt.format(new Date(m.timestamp > 0
                    ? m.timestamp : System.currentTimeMillis())));

            h.itemView.setOnClickListener(v -> onTap.tap(m));
            h.itemView.setOnLongClickListener(v -> { onLongTap.tap(m); return true; });
        }

        private String typeIcon(String type) {
            if (type == null) return "💬";
            switch (type) {
                case "image": return "📷"; case "video": return "🎥";
                case "audio": return "🎤"; case "file":  return "📄";
                case "poll":  return "📊"; case "multi_media": return "🖼️";
                default:      return "💬";
            }
        }
        private String typeName(String type) {
            if (type == null) return "Message";
            switch (type) {
                case "image": return "Photo"; case "video": return "Video";
                case "audio": return "Voice"; case "file":  return "Document";
                case "poll":  return "Poll";  case "multi_media": return "Photos";
                default:      return "Message";
            }
        }
        private String formatDuration(long ms) {
            if (ms < 3_600_000) return (ms / 60_000) + "m";
            if (ms < 86_400_000) return (ms / 3_600_000) + "h";
            return (ms / 86_400_000) + "d";
        }

        @Override public int getItemCount() { return data.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView    tvMessage, tvDelivery, tvTime, tvScheduledLabel,
                        tvPollQuestion, tvPollTotalVotes,
                        tvLinkTitle, tvLinkDesc, tvLinkUrl,
                        tvExpiryLabel, tvReplyCount;
            LinearLayout llPollCard, llPollOptions, llLinkPreview, llMultiMedia;
            ImageView   ivLinkThumb;

            VH(@NonNull View v) {
                super(v);
                tvMessage       = v.findViewById(R.id.tv_broadcast_msg_text);
                tvDelivery      = v.findViewById(R.id.tv_broadcast_delivery);
                tvTime          = v.findViewById(R.id.tv_broadcast_msg_time);
                tvScheduledLabel= v.findViewById(R.id.tv_scheduled_label);
                llPollCard      = v.findViewById(R.id.ll_poll_card);
                tvPollQuestion  = v.findViewById(R.id.tv_poll_question);
                llPollOptions   = v.findViewById(R.id.ll_poll_options);
                tvPollTotalVotes= v.findViewById(R.id.tv_poll_total_votes);
                llLinkPreview   = v.findViewById(R.id.ll_link_preview);
                ivLinkThumb     = v.findViewById(R.id.iv_link_thumb);
                tvLinkTitle     = v.findViewById(R.id.tv_link_title);
                tvLinkDesc      = v.findViewById(R.id.tv_link_desc);
                tvLinkUrl       = v.findViewById(R.id.tv_link_url);
                llMultiMedia    = v.findViewById(R.id.ll_multi_media);
                tvExpiryLabel   = v.findViewById(R.id.tv_expiry_label);
                tvReplyCount    = v.findViewById(R.id.tv_reply_count);
            }
        }
    }
}
