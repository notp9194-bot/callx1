package com.callx.app.activities;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.models.Message;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MessageInfoActivity — v22 (NEW — was completely missing before this version).
 *
 * Shows delivery and read receipt details for a sent message.
 *
 * ┌──────────────────────────────────────┐
 * │  [Message preview]                   │
 * │  Sent at 10:32 AM                    │
 * │                                      │
 * │  [✓] Sent  [✓✓] Delivered  [✓✓🔵] Read │
 * │                                      │
 * │  READ BY (group only)                │
 * │  ○ Alice    10:35 AM        [✓✓🔵]   │
 * │  ○ Bob      10:36 AM        [✓✓🔵]   │
 * │                                      │
 * │  DELIVERED TO (group only)           │
 * │  ○ Charlie  10:33 AM        [✓✓]     │
 * └──────────────────────────────────────┘
 *
 * Intent extras (required):
 *   "messageId"  — String
 *   "chatId"     — String
 *   "isGroup"    — boolean
 *
 * Intent extras (passed for fast offline preview):
 *   "messageText"    — String (nullable)
 *   "messageType"    — String (text/image/video/audio/file)
 *   "messageStatus"  — String (sent/delivered/read)
 *   "timestamp"      — long
 *   "mediaUrl"       — String (nullable)
 *   "thumbnailUrl"   — String (nullable)
 *   "deliveredAt"    — long (0 = unknown)
 *   "readAt"         — long (0 = unknown)
 */
public class MessageInfoActivity extends AppCompatActivity {

    private static final String TAG = "MessageInfoActivity";

    // Views
    private TextView  tvPreviewText, tvPreviewMediaLabel, tvSentTime;
    private ImageView ivPreviewMedia;
    private TextView  tvDeliveredTime, tvReadTime;
    private RecyclerView rvReadBy, rvDeliveredTo;
    private TextView  tvReadByHeader, tvDeliveredByHeader;
    private LinearLayout llEmpty;

    // Data
    private String  chatId;
    private String  messageId;
    private boolean isGroup;

    private final SimpleDateFormat fullFmt =
            new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault());
    private final SimpleDateFormat timeFmt =
            new SimpleDateFormat("hh:mm a", Locale.getDefault());

    // ══════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_message_info);

        // Toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Message Info");
        }

        // Bind views
        tvPreviewText        = findViewById(R.id.tv_preview_text);
        ivPreviewMedia       = findViewById(R.id.iv_preview_media);
        tvPreviewMediaLabel  = findViewById(R.id.tv_preview_media_label);
        tvSentTime           = findViewById(R.id.tv_sent_time);
        tvDeliveredTime      = findViewById(R.id.tv_delivered_time);
        tvReadTime           = findViewById(R.id.tv_read_time);
        rvReadBy             = findViewById(R.id.rv_read_by);
        rvDeliveredTo        = findViewById(R.id.rv_delivered_to);
        tvReadByHeader       = findViewById(R.id.tv_read_by_header);
        tvDeliveredByHeader  = findViewById(R.id.tv_delivered_by_header);
        llEmpty              = findViewById(R.id.ll_empty);

        // Extract intent extras
        messageId = getIntent().getStringExtra("messageId");
        chatId    = getIntent().getStringExtra("chatId");
        isGroup   = getIntent().getBooleanExtra("isGroup", false);

        if (messageId == null || chatId == null) {
            finish();
            return;
        }

        // Render offline preview from passed extras immediately (zero latency)
        renderPreview();

        // Then load live data from Firebase
        loadMessageInfo();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ══════════════════════════════════════════════════════════════
    // Preview — renders immediately from intent extras
    // ══════════════════════════════════════════════════════════════

    private void renderPreview() {
        String type      = getIntent().getStringExtra("messageType");
        String text      = getIntent().getStringExtra("messageText");
        long   timestamp = getIntent().getLongExtra("timestamp", 0);
        String mediaUrl  = getIntent().getStringExtra("mediaUrl");
        String thumbUrl  = getIntent().getStringExtra("thumbnailUrl");
        long   delivAt   = getIntent().getLongExtra("deliveredAt", 0);
        long   readAt    = getIntent().getLongExtra("readAt", 0);

        if (type == null) type = "text";

        // Sent time
        if (timestamp > 0) tvSentTime.setText(fullFmt.format(new Date(timestamp)));

        // Message preview
        if ("text".equals(type)) {
            tvPreviewText.setVisibility(View.VISIBLE);
            tvPreviewText.setText(text != null ? text : "");
        } else if ("image".equals(type) || "video".equals(type)) {
            ivPreviewMedia.setVisibility(View.VISIBLE);
            String loadUrl = thumbUrl != null ? thumbUrl : mediaUrl;
            if (loadUrl != null && !loadUrl.isEmpty()) {
                Glide.with(this).load(loadUrl).centerCrop()
                        .placeholder(R.drawable.ic_video).into(ivPreviewMedia);
            }
        } else if ("audio".equals(type)) {
            tvPreviewMediaLabel.setVisibility(View.VISIBLE);
            tvPreviewMediaLabel.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_audio, 0, 0, 0);
            tvPreviewMediaLabel.setText("Voice message");
        } else if ("file".equals(type)) {
            String fileName = getIntent().getStringExtra("fileName");
            tvPreviewMediaLabel.setVisibility(View.VISIBLE);
            tvPreviewMediaLabel.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_file, 0, 0, 0);
            tvPreviewMediaLabel.setText(fileName != null ? fileName : "File");
        }

        // Delivered / read timestamps from extras (offline fast path)
        if (delivAt > 0) {
            tvDeliveredTime.setVisibility(View.VISIBLE);
            tvDeliveredTime.setText(timeFmt.format(new Date(delivAt)));
        }
        if (readAt > 0) {
            tvReadTime.setVisibility(View.VISIBLE);
            tvReadTime.setText(timeFmt.format(new Date(readAt)));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Firebase — live message info
    // ══════════════════════════════════════════════════════════════

    private void loadMessageInfo() {
        FirebaseDatabase.getInstance()
                .getReference("chats")
                .child(chatId)
                .child("messages")
                .child(messageId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snap) {
                        Message m = snap.getValue(Message.class);
                        if (m == null) return;
                        if (m.id == null) m.id = snap.getKey();

                        // Update delivered/read timestamps
                        if (m.deliveredAt != null && m.deliveredAt > 0) {
                            tvDeliveredTime.setVisibility(View.VISIBLE);
                            tvDeliveredTime.setText(timeFmt.format(new Date(m.deliveredAt)));
                        }
                        if (m.readAt != null && m.readAt > 0) {
                            tvReadTime.setVisibility(View.VISIBLE);
                            tvReadTime.setText(timeFmt.format(new Date(m.readAt)));
                        }

                        if (isGroup) {
                            renderGroupReceipts(m);
                        } else {
                            renderOneToOneStatus(m);
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        android.util.Log.e(TAG, "loadMessageInfo error: " + error.getMessage());
                    }
                });
    }

    // ── 1:1 chat status ──────────────────────────────────────────
    private void renderOneToOneStatus(Message m) {
        String status = m.status == null ? "sent" : m.status;
        boolean delivered = "delivered".equals(status) || "read".equals(status);
        boolean read      = "read".equals(status);

        if (!delivered && !read) {
            llEmpty.setVisibility(View.VISIBLE);
        }

        if (m.deliveredAt != null && m.deliveredAt > 0) {
            tvDeliveredTime.setVisibility(View.VISIBLE);
            tvDeliveredTime.setText(timeFmt.format(new Date(m.deliveredAt)));
        }
        if (m.readAt != null && m.readAt > 0) {
            tvReadTime.setVisibility(View.VISIBLE);
            tvReadTime.setText(timeFmt.format(new Date(m.readAt)));
        }
    }

    // ── Group chat read/delivered receipts ───────────────────────
    private void renderGroupReceipts(Message m) {
        // READ BY
        if (m.readBy != null && !m.readBy.isEmpty()) {
            tvReadByHeader.setVisibility(View.VISIBLE);
            rvReadBy.setVisibility(View.VISIBLE);
            rvReadBy.setLayoutManager(new LinearLayoutManager(this));
            loadMemberList(m.readBy, rvReadBy, true);
        }

        // DELIVERED TO (exclude already-read members)
        if (m.deliveredTo != null && !m.deliveredTo.isEmpty()) {
            // Build delivered-only list (not in readBy)
            java.util.Map<String, Long> deliveredOnly = new java.util.LinkedHashMap<>(m.deliveredTo);
            if (m.readBy != null) deliveredOnly.keySet().removeAll(m.readBy.keySet());
            if (!deliveredOnly.isEmpty()) {
                tvDeliveredByHeader.setVisibility(View.VISIBLE);
                rvDeliveredTo.setVisibility(View.VISIBLE);
                rvDeliveredTo.setLayoutManager(new LinearLayoutManager(this));
                loadMemberList(deliveredOnly, rvDeliveredTo, false);
            }
        }

        // Empty state
        boolean hasAny = (m.readBy != null && !m.readBy.isEmpty())
                || (m.deliveredTo != null && !m.deliveredTo.isEmpty());
        llEmpty.setVisibility(hasAny ? View.GONE : View.VISIBLE);
    }

    /**
     * Loads member names + avatars from Firebase for the uid→timestamp map,
     * then renders them in the given RecyclerView.
     */
    private void loadMemberList(Map<String, Long> uidTimestampMap,
                                RecyclerView rv, boolean isRead) {
        List<MemberReceiptItem> items = new ArrayList<>();
        int[] pending = {uidTimestampMap.size()};
        MemberReceiptAdapter adapter = new MemberReceiptAdapter(items, isRead);
        rv.setAdapter(adapter);

        for (Map.Entry<String, Long> entry : uidTimestampMap.entrySet()) {
            String uid       = entry.getKey();
            long   timestamp = entry.getValue();

            FirebaseDatabase.getInstance()
                    .getReference("users").child(uid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override
                        public void onDataChange(@NonNull DataSnapshot snap) {
                            String name  = snap.child("username").getValue(String.class);
                            String photo = snap.child("profileImageUrl").getValue(String.class);
                            if (name == null || name.isEmpty())
                                name = snap.child("email").getValue(String.class);
                            if (name == null) name = "User";

                            items.add(new MemberReceiptItem(uid, name, photo, timestamp));
                            items.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
                            adapter.notifyDataSetChanged();
                            pending[0]--;
                        }

                        @Override
                        public void onCancelled(@NonNull DatabaseError e) {
                            pending[0]--;
                        }
                    });
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Data model
    // ══════════════════════════════════════════════════════════════

    private static class MemberReceiptItem {
        final String uid;
        final String name;
        final String photoUrl;
        final long   timestamp;

        MemberReceiptItem(String uid, String name, String photoUrl, long timestamp) {
            this.uid       = uid;
            this.name      = name;
            this.photoUrl  = photoUrl;
            this.timestamp = timestamp;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Member receipt adapter
    // ══════════════════════════════════════════════════════════════

    private class MemberReceiptAdapter
            extends RecyclerView.Adapter<MemberReceiptAdapter.VH> {

        private final List<MemberReceiptItem> items;
        private final boolean isRead;

        MemberReceiptAdapter(List<MemberReceiptItem> items, boolean isRead) {
            this.items  = items;
            this.isRead = isRead;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_member_read_receipt, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            MemberReceiptItem item = items.get(pos);
            h.tvName.setText(item.name);
            h.tvTime.setText(timeFmt.format(new Date(item.timestamp)));
            h.ivTick.setImageResource(isRead
                    ? R.drawable.ic_double_tick_blue
                    : R.drawable.ic_double_tick);
            if (item.photoUrl != null && !item.photoUrl.isEmpty()) {
                Glide.with(h.itemView.getContext())
                        .load(item.photoUrl)
                        .circleCrop()
                        .placeholder(R.drawable.ic_person)
                        .into(h.ivAvatar);
            } else {
                h.ivAvatar.setImageResource(R.drawable.ic_person);
            }
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            de.hdodenhof.circleimageview.CircleImageView ivAvatar;
            TextView  tvName, tvTime;
            ImageView ivTick;

            VH(@NonNull View v) {
                super(v);
                ivAvatar = v.findViewById(R.id.iv_avatar);
                tvName   = v.findViewById(R.id.tv_name);
                tvTime   = v.findViewById(R.id.tv_time);
                ivTick   = v.findViewById(R.id.iv_tick);
            }
        }
    }
}
