package com.callx.app.conversation;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.callx.app.models.Message;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MessageInfoActivity — Dedicated Message Info Screen (FIXED VERSION)
 *
 * FIXES:
 *  1. addListenerForSingleValueEvent → addValueEventListener (real-time updates)
 *  2. Listener onDestroy() mein remove hota hai (memory leak nahi)
 *  3. Receiver ke liye "Received at" / "Read at" rows properly dikhte hain
 *  4. isGroup check mein llDelivered/llRead hide hone ka bug fix
 *
 * Launch karo:
 *   Intent i = new Intent(ctx, MessageInfoActivity.class);
 *   i.putExtra("chatId",      chatId);
 *   i.putExtra("messageId",   message.id);
 *   i.putExtra("isGroup",     message.isGroup);
 *   i.putExtra("partnerName", partnerName);   // 1-on-1 only
 *   i.putExtra("isSender",    currentUid.equals(message.senderId));
 *   startActivity(i);
 */
public class MessageInfoActivity extends AppCompatActivity {

    private static final String TAG = "MessageInfoActivity";

    private final SimpleDateFormat dtFmt =
            new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault());

    // Views
    private TextView tvSentTime;
    private TextView tvStatus;
    private TextView tvDeliveredTime;
    private TextView tvReadTime;
    private TextView tvEditedTime;
    private TextView tvType;
    private TextView tvMediaMeta;
    // FIX: Label rows taaki "Delivered at" / "Received at" switch ho sake
    private TextView tvDeliveredLabel;
    private TextView tvReadLabel;

    private LinearLayout llDelivered;
    private LinearLayout llRead;
    private LinearLayout llEdited;
    private LinearLayout llMediaMeta;

    // Group read-by list
    private LinearLayout llGroupSection;
    private RecyclerView rvDeliveredTo;
    private RecyclerView rvReadBy;

    // Data
    private String chatId;
    private String messageId;
    private boolean isGroup;
    private boolean isSender;   // FIX: sender vs receiver ke liye alag labels
    private String partnerName;
    private DatabaseReference msgRef;

    // FIX: Real-time listener reference — onDestroy() mein remove karna hai
    private ValueEventListener realtimeListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_message_info);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Message Info");
        }

        Intent in = getIntent();
        chatId      = in.getStringExtra("chatId");
        messageId   = in.getStringExtra("messageId");
        isGroup     = in.getBooleanExtra("isGroup", false);
        isSender    = in.getBooleanExtra("isSender", true);
        partnerName = in.getStringExtra("partnerName");

        bindViews();

        if (chatId == null || messageId == null) {
            finish();
            return;
        }

        loadMessageInfoRealtime();   // FIX: was loadMessageInfo() with single-event
    }

    private void bindViews() {
        tvSentTime       = findViewById(R.id.tv_sent_time);
        tvStatus         = findViewById(R.id.tv_status);
        tvDeliveredTime  = findViewById(R.id.tv_delivered_time);
        tvReadTime       = findViewById(R.id.tv_read_time);
        tvEditedTime     = findViewById(R.id.tv_edited_time);
        tvType           = findViewById(R.id.tv_msg_type);
        tvMediaMeta      = findViewById(R.id.tv_media_meta);
        tvDeliveredLabel = findViewById(R.id.tv_delivered_label);  // optional
        tvReadLabel      = findViewById(R.id.tv_read_label);       // optional

        llDelivered   = findViewById(R.id.ll_delivered_row);
        llRead        = findViewById(R.id.ll_read_row);
        llEdited      = findViewById(R.id.ll_edited_row);
        llMediaMeta   = findViewById(R.id.ll_media_meta_row);
        llGroupSection = findViewById(R.id.ll_group_section);
        rvDeliveredTo  = findViewById(R.id.rv_delivered_to);
        rvReadBy       = findViewById(R.id.rv_read_by);
    }

    // ── FIX: Real-time listener (was addListenerForSingleValueEvent) ──────
    private void loadMessageInfoRealtime() {
        DatabaseReference base;
        try {
            base = com.callx.app.utils.FirebaseUtils.getMessagesRef(chatId)
                    .child(messageId);
        } catch (Exception e) {
            showError("Could not connect to database.");
            return;
        }
        msgRef = base;

        // FIX: addValueEventListener = live updates jab group members read karein
        realtimeListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snap) {
                if (!snap.exists()) {
                    showError("Message not found.");
                    return;
                }
                Message m = snap.getValue(Message.class);
                if (m == null) { showError("Could not read message."); return; }
                m.id = snap.getKey();
                populateUI(m, snap);
            }

            @Override
            public void onCancelled(DatabaseError error) {
                showError("Error: " + error.getMessage());
            }
        };
        base.addValueEventListener(realtimeListener);
    }

    // ── FIX: onDestroy mein listener remove karo — memory leak band ───────
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (msgRef != null && realtimeListener != null) {
            msgRef.removeEventListener(realtimeListener);
        }
    }

    private void populateUI(Message m, DataSnapshot snap) {

        // ── Sent time ──────────────────────────────────────────────────────
        String sentStr = (m.timestamp != null && m.timestamp > 0)
                ? dtFmt.format(new Date(m.timestamp)) : "—";
        if (tvSentTime != null) tvSentTime.setText(sentStr);

        // ── Status label ───────────────────────────────────────────────────
        if (tvStatus != null) tvStatus.setText(statusToLabel(m.status));

        // ── 1-on-1 chat: Delivered at / Read at ───────────────────────────
        if (!isGroup) {
            // FIX: Sender ke liye "Delivered at" / "Read at"
            //      Receiver ke liye "Received at" / "You read at"
            if (isSender) {
                if (tvDeliveredLabel != null) tvDeliveredLabel.setText("Delivered at");
                if (tvReadLabel      != null) tvReadLabel.setText("Read at");
            } else {
                if (tvDeliveredLabel != null) tvDeliveredLabel.setText("Received at");
                if (tvReadLabel      != null) tvReadLabel.setText("You read at");
            }

            // Delivered at timestamp
            if (llDelivered != null) {
                if (m.deliveredAt != null && m.deliveredAt > 0) {
                    llDelivered.setVisibility(View.VISIBLE);
                    if (tvDeliveredTime != null)
                        tvDeliveredTime.setText(dtFmt.format(new Date(m.deliveredAt)));
                } else {
                    llDelivered.setVisibility(View.GONE);
                }
            }

            // Read at timestamp
            if (llRead != null) {
                if (m.readAt != null && m.readAt > 0) {
                    llRead.setVisibility(View.VISIBLE);
                    if (tvReadTime != null)
                        tvReadTime.setText(dtFmt.format(new Date(m.readAt)));
                } else {
                    llRead.setVisibility(View.GONE);
                }
            }

        } else {
            // Group chat: 1-on-1 rows hide karo
            if (llDelivered != null) llDelivered.setVisibility(View.GONE);
            if (llRead      != null) llRead.setVisibility(View.GONE);
        }

        // ── Message type ───────────────────────────────────────────────────
        if (tvType != null) {
            String typeStr = m.type != null ? capitalize(m.type) : "Text";
            tvType.setText(typeStr);
        }

        // ── Media meta: fileSize + duration ───────────────────────────────
        if (llMediaMeta != null) {
            String meta = buildMediaMeta(m);
            if (meta != null) {
                llMediaMeta.setVisibility(View.VISIBLE);
                if (tvMediaMeta != null) tvMediaMeta.setText(meta);
            } else {
                llMediaMeta.setVisibility(View.GONE);
            }
        }

        // ── Edit info ──────────────────────────────────────────────────────
        if (llEdited != null) {
            if (Boolean.TRUE.equals(m.edited) && m.editedAt != null && m.editedAt > 0) {
                llEdited.setVisibility(View.VISIBLE);
                if (tvEditedTime != null)
                    tvEditedTime.setText(dtFmt.format(new Date(m.editedAt)));
            } else {
                llEdited.setVisibility(View.GONE);
            }
        }

        // ── Group section: Delivered To + Read By ─────────────────────────
        if (isGroup && llGroupSection != null) {
            llGroupSection.setVisibility(View.VISIBLE);

            // FIX: Firebase se directly parse karo — Long timestamps expected hain
            Map<String, Long> deliveredTo = parseTimestampMap(snap, "deliveredTo");
            Map<String, Long> readBy      = parseTimestampMap(snap, "readBy");

            if (rvDeliveredTo != null)
                loadGroupReceiptList(rvDeliveredTo, deliveredTo,
                        R.id.tv_delivered_to_empty);

            if (rvReadBy != null)
                loadGroupReceiptList(rvReadBy, readBy,
                        R.id.tv_read_by_empty);
        } else if (llGroupSection != null) {
            llGroupSection.setVisibility(View.GONE);
        }
    }

    // ── Helper: Firebase DataSnapshot se Map<uid, Long> parse karo ────────
    // FIX: Boolean aur Number dono handle karta hai (backward compatibility)
    private Map<String, Long> parseTimestampMap(DataSnapshot snap, String child) {
        Map<String, Long> result = new HashMap<>();
        DataSnapshot node = snap.child(child);
        if (!node.exists()) return result;
        for (DataSnapshot entry : node.getChildren()) {
            String uid = entry.getKey();
            Object val = entry.getValue();
            if (uid == null) continue;
            if (val instanceof Long) {
                result.put(uid, (Long) val);
            } else if (val instanceof Number) {
                // Firebase sometimes returns Integer for small values
                result.put(uid, ((Number) val).longValue());
            } else if (Boolean.TRUE.equals(val)) {
                // FIX backward compat: purana code Boolean true store karta tha
                // Use current time as fallback — actual timestamp nahi pata
                result.put(uid, 0L);
            }
        }
        return result;
    }

    // ── Helper: Room ke JSON string ko Map mein convert karo ──────────────
    public static Map<String, Long> parseReadMap(String json) {
        Map<String, Long> result = new HashMap<>();
        if (json == null || json.isEmpty()) return result;
        try {
            JSONObject obj = new JSONObject(json);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String uid = keys.next();
                result.put(uid, obj.getLong(uid));
            }
        } catch (JSONException ignored) {}
        return result;
    }

    // ── Group receipt list RecyclerView populate karo ─────────────────────
    private void loadGroupReceiptList(RecyclerView rv, Map<String, Long> map, int emptyViewId) {
        View emptyView = findViewById(emptyViewId);

        if (map.isEmpty()) {
            rv.setVisibility(View.GONE);
            if (emptyView != null) emptyView.setVisibility(View.VISIBLE);
            return;
        }
        if (emptyView != null) emptyView.setVisibility(View.GONE);
        rv.setVisibility(View.VISIBLE);
        rv.setLayoutManager(new LinearLayoutManager(this));

        List<GroupReceiptAdapter.ReceiptItem> items = new ArrayList<>();
        for (Map.Entry<String, Long> entry : map.entrySet()) {
            items.add(new GroupReceiptAdapter.ReceiptItem(entry.getKey(), entry.getValue()));
        }
        // Sort: latest timestamp pehle
        items.sort((a, b) -> Long.compare(b.ts, a.ts));
        rv.setAdapter(new GroupReceiptAdapter(items, dtFmt));
    }

    // ── Media meta string build karo ──────────────────────────────────────
    private String buildMediaMeta(Message m) {
        if (m.type == null) return null;
        StringBuilder sb = new StringBuilder();
        switch (m.type) {
            case "image":
            case "gif":
                if (m.fileSize != null && m.fileSize > 0)
                    sb.append("Size: ").append(formatFileSize(m.fileSize));
                break;
            case "video":
                if (m.duration != null && m.duration > 0)
                    sb.append("Duration: ").append(formatDuration(m.duration));
                if (m.fileSize != null && m.fileSize > 0) {
                    if (sb.length() > 0) sb.append("   ");
                    sb.append("Size: ").append(formatFileSize(m.fileSize));
                }
                break;
            case "audio":
                if (m.duration != null && m.duration > 0)
                    sb.append("Duration: ").append(formatDuration(m.duration));
                break;
            case "file":
                if (m.fileName != null && !m.fileName.isEmpty())
                    sb.append("File: ").append(m.fileName);
                if (m.fileSize != null && m.fileSize > 0) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append("Size: ").append(formatFileSize(m.fileSize));
                }
                break;
            default:
                return null;
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    // ── Status → human-readable label ────────────────────────────────────
    private String statusToLabel(String status) {
        if (status == null) return "✓  Sent";
        switch (status) {
            case "read":
            case "seen":      return "✓✓  Read";
            case "delivered": return "✓✓  Delivered";
            case "pending":   return "⏳  Pending (sending...)";
            case "failed":    return "⚠  Failed to send";
            default:          return "✓  Sent";
        }
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024)        return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
    }

    private String formatDuration(long ms) {
        long sec = ms / 1000;
        return String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void showError(String msg) {
        if (tvSentTime != null) tvSentTime.setText(msg);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }


    // ══════════════════════════════════════════════════════════════════════
    // GroupReceiptAdapter — Delivered To / Read By list adapter
    // ══════════════════════════════════════════════════════════════════════

    static class GroupReceiptAdapter
            extends RecyclerView.Adapter<GroupReceiptAdapter.VH> {

        static class ReceiptItem {
            final String uid;
            final long   ts;
            ReceiptItem(String uid, long ts) { this.uid = uid; this.ts = ts; }
        }

        private final List<ReceiptItem> items;
        private final SimpleDateFormat  fmt;

        GroupReceiptAdapter(List<ReceiptItem> items, SimpleDateFormat fmt) {
            this.items = items;
            this.fmt   = fmt;
        }

        @Override
        public VH onCreateViewHolder(android.view.ViewGroup p, int t) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(p.getContext());
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setPadding(48, 20, 48, 20);
            row.setLayoutParams(new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

            de.hdodenhof.circleimageview.CircleImageView avatar =
                    new de.hdodenhof.circleimageview.CircleImageView(p.getContext());
            android.widget.LinearLayout.LayoutParams avatarLp =
                    new android.widget.LinearLayout.LayoutParams(96, 96);
            avatarLp.setMarginEnd(28);
            avatar.setLayoutParams(avatarLp);
            avatar.setTag("avatar");
            row.addView(avatar);

            android.widget.LinearLayout col = new android.widget.LinearLayout(p.getContext());
            col.setOrientation(android.widget.LinearLayout.VERTICAL);
            col.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            android.widget.TextView tvName = new android.widget.TextView(p.getContext());
            tvName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f);
            tvName.setTextColor(0xFF212121);
            tvName.setTypeface(null, android.graphics.Typeface.BOLD);
            tvName.setTag("name");
            col.addView(tvName);

            android.widget.TextView tvTime = new android.widget.TextView(p.getContext());
            tvTime.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
            tvTime.setTextColor(0xFF757575);
            tvTime.setTag("time");
            col.addView(tvTime);

            row.addView(col);
            return new VH(row);
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            ReceiptItem item = items.get(pos);
            android.widget.TextView tvName = h.itemView.findViewWithTag("name");
            android.widget.TextView tvTime = h.itemView.findViewWithTag("time");
            de.hdodenhof.circleimageview.CircleImageView avatar =
                    h.itemView.findViewWithTag("avatar");

            // Name load karo
            com.callx.app.utils.FirebaseUtils.db()
                    .getReference("users").child(item.uid).child("name")
                    .addListenerForSingleValueEvent(
                        new com.google.firebase.database.ValueEventListener() {
                            @Override public void onDataChange(
                                    com.google.firebase.database.DataSnapshot s) {
                                String name = s.getValue(String.class);
                                if (tvName != null)
                                    tvName.setText(name != null ? name : item.uid);
                            }
                            @Override public void onCancelled(
                                    com.google.firebase.database.DatabaseError e) {
                                if (tvName != null) tvName.setText(item.uid);
                            }
                        });

            // Avatar load karo
            com.callx.app.utils.FirebaseUtils.db()
                    .getReference("users").child(item.uid).child("photoUrl")
                    .addListenerForSingleValueEvent(
                        new com.google.firebase.database.ValueEventListener() {
                            @Override public void onDataChange(
                                    com.google.firebase.database.DataSnapshot s) {
                                String url = s.getValue(String.class);
                                if (avatar != null && url != null && !url.isEmpty()) {
                                    com.bumptech.glide.Glide.with(avatar.getContext())
                                            .load(url)
                                            .placeholder(
                                                com.callx.app.chat.R.drawable.bg_circle_white)
                                            .into(avatar);
                                }
                            }
                            @Override public void onCancelled(
                                    com.google.firebase.database.DatabaseError e) {}
                        });

            // Timestamp dikhao
            if (tvTime != null) {
                if (item.ts > 0) {
                    tvTime.setText(fmt.format(new java.util.Date(item.ts)));
                } else {
                    // FIX: 0L = backward compat (purana Boolean true tha)
                    tvTime.setText("(time unavailable)");
                }
            }
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            VH(android.view.View v) { super(v); }
        }
    }
}
