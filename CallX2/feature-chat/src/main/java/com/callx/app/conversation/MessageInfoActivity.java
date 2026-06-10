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
import com.google.firebase.auth.FirebaseAuth;
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
 * MessageInfoActivity — Dedicated Message Info Screen
 *
 * Dikhata hai:
 *  • Sent time
 *  • Delivered at timestamp (1-on-1 + group per-member)
 *  • Read at timestamp     (1-on-1 + group per-member)
 *  • Edit history (edited flag + editedAt)
 *  • Media details (fileSize, duration) for non-text messages
 *
 * Launch karo:
 *   Intent i = new Intent(ctx, MessageInfoActivity.class);
 *   i.putExtra("chatId",      chatId);
 *   i.putExtra("messageId",   message.id);
 *   i.putExtra("isGroup",     message.isGroup);
 *   i.putExtra("partnerName", partnerName);   // 1-on-1 only
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
    private String partnerName;
    private DatabaseReference msgRef;

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
        partnerName = in.getStringExtra("partnerName");

        bindViews();

        if (chatId == null || messageId == null) {
            finish();
            return;
        }

        loadMessageInfo();
    }

    private void bindViews() {
        tvSentTime      = findViewById(R.id.tv_sent_time);
        tvStatus        = findViewById(R.id.tv_status);
        tvDeliveredTime = findViewById(R.id.tv_delivered_time);
        tvReadTime      = findViewById(R.id.tv_read_time);
        tvEditedTime    = findViewById(R.id.tv_edited_time);
        tvType          = findViewById(R.id.tv_msg_type);
        tvMediaMeta     = findViewById(R.id.tv_media_meta);

        llDelivered  = findViewById(R.id.ll_delivered_row);
        llRead       = findViewById(R.id.ll_read_row);
        llEdited     = findViewById(R.id.ll_edited_row);
        llMediaMeta  = findViewById(R.id.ll_media_meta_row);
        llGroupSection = findViewById(R.id.ll_group_section);
        rvDeliveredTo = findViewById(R.id.rv_delivered_to);
        rvReadBy      = findViewById(R.id.rv_read_by);
    }

    private void loadMessageInfo() {
        // FIX: group messages are at "groupMessages/{groupId}/{msgId}",
        //      1-on-1 messages are at "messages/{chatId}/{msgId}".
        DatabaseReference base;
        try {
            if (isGroup) {
                base = com.callx.app.utils.FirebaseUtils.getGroupMessagesRef(chatId)
                        .child(messageId);
            } else {
                base = com.callx.app.utils.FirebaseUtils.getMessagesRef(chatId)
                        .child(messageId);
            }
        } catch (Exception e) {
            showError("Could not connect to database.");
            return;
        }

        base.addListenerForSingleValueEvent(new ValueEventListener() {
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
        });
    }

    private void populateUI(Message m, DataSnapshot snap) {
        // ── Sent time ──────────────────────────────────────────────────────
        String sentStr = (m.timestamp != null && m.timestamp > 0)
                ? dtFmt.format(new Date(m.timestamp)) : "—";
        tvSentTime.setText(sentStr);

        // ── Status label ───────────────────────────────────────────────────
        String statusLabel = statusToLabel(m.status);
        tvStatus.setText(statusLabel);

        // ── Delivered at ───────────────────────────────────────────────────
        if (!isGroup) {
            if (m.deliveredAt != null && m.deliveredAt > 0) {
                llDelivered.setVisibility(View.VISIBLE);
                tvDeliveredTime.setText(dtFmt.format(new Date(m.deliveredAt)));
            } else {
                llDelivered.setVisibility(View.GONE);
            }

            // ── Read at ────────────────────────────────────────────────────
            if (m.readAt != null && m.readAt > 0) {
                llRead.setVisibility(View.VISIBLE);
                tvReadTime.setText(dtFmt.format(new Date(m.readAt)));
            } else {
                llRead.setVisibility(View.GONE);
            }
        }

        // ── Message type ───────────────────────────────────────────────────
        String typeStr = m.type != null ? capitalize(m.type) : "Text";
        tvType.setText(typeStr);

        // ── Media meta: fileSize + duration ───────────────────────────────
        String meta = buildMediaMeta(m);
        if (meta != null) {
            llMediaMeta.setVisibility(View.VISIBLE);
            tvMediaMeta.setText(meta);
        } else {
            llMediaMeta.setVisibility(View.GONE);
        }

        // ── Edit info ──────────────────────────────────────────────────────
        if (Boolean.TRUE.equals(m.edited) && m.editedAt != null && m.editedAt > 0) {
            llEdited.setVisibility(View.VISIBLE);
            tvEditedTime.setText(dtFmt.format(new Date(m.editedAt)));
        } else {
            llEdited.setVisibility(View.GONE);
        }

        // ── Group section: Delivered To + Read By ─────────────────────────
        if (isGroup) {
            llGroupSection.setVisibility(View.VISIBLE);
            // Hide the 1-on-1 delivered/read rows
            if (llDelivered != null) llDelivered.setVisibility(View.GONE);
            if (llRead != null) llRead.setVisibility(View.GONE);

            Map<String, Long> deliveredTo = parseTimestampMap(snap, "deliveredTo");
            Map<String, Long> readBy      = parseTimestampMap(snap, "readBy");

            // Delivered To list
            loadGroupReceiptList(rvDeliveredTo, deliveredTo,
                    R.id.tv_delivered_to_empty);

            // Read By list
            loadGroupReceiptList(rvReadBy, readBy,
                    R.id.tv_read_by_empty);
        } else {
            llGroupSection.setVisibility(View.GONE);
        }
    }

    // ── Helper: parse group timestamp map from DataSnapshot ───────────────
    private Map<String, Long> parseTimestampMap(DataSnapshot snap, String child) {
        Map<String, Long> result = new HashMap<>();
        DataSnapshot node = snap.child(child);
        if (!node.exists()) return result;
        for (DataSnapshot entry : node.getChildren()) {
            String uid = entry.getKey();
            Object val = entry.getValue();
            if (uid != null && val instanceof Long) {
                result.put(uid, (Long) val);
            } else if (uid != null && val instanceof Number) {
                result.put(uid, ((Number) val).longValue());
            }
        }
        return result;
    }

    // ── Helper: parse a JSON string into Map<uid, timestamp> (Room fallback) ─
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

    // ── Load group receipt list into RecyclerView ──────────────────────────
    private void loadGroupReceiptList(RecyclerView rv, Map<String, Long> map, int emptyViewId) {
        View emptyView = rv.getRootView().findViewById(emptyViewId);

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
        rv.setAdapter(new GroupReceiptAdapter(items, dtFmt));
    }

    // ── Build media meta string (size + duration) ──────────────────────────
    private String buildMediaMeta(Message m) {
        if (m.type == null) return null;
        StringBuilder sb = new StringBuilder();
        switch (m.type) {
            case "image":
            case "gif":
                if (m.fileSize != null && m.fileSize > 0) {
                    sb.append("Size: ").append(formatFileSize(m.fileSize));
                }
                break;
            case "video":
                if (m.duration != null && m.duration > 0) {
                    sb.append("Duration: ").append(formatDuration(m.duration));
                }
                if (m.fileSize != null && m.fileSize > 0) {
                    if (sb.length() > 0) sb.append("   ");
                    sb.append("Size: ").append(formatFileSize(m.fileSize));
                }
                break;
            case "audio":
                if (m.duration != null && m.duration > 0) {
                    sb.append("Duration: ").append(formatDuration(m.duration));
                }
                break;
            case "file":
                if (m.fileName != null && !m.fileName.isEmpty()) {
                    sb.append("File: ").append(m.fileName);
                }
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

    // ── Util: status → human label ─────────────────────────────────────────
    private String statusToLabel(String status) {
        if (status == null) return "✓  Sent";
        switch (status) {
            case "read":
            case "seen":      return "✓✓  Read";
            case "delivered": return "✓✓  Delivered";
            case "pending":   return "⏳  Pending";
            case "failed":    return "⚠  Failed — tap to retry";
            default:          return "✓  Sent";
        }
    }

    // ── Util: format file size ─────────────────────────────────────────────
    private String formatFileSize(long bytes) {
        if (bytes < 1024)       return bytes + " B";
        if (bytes < 1024*1024)  return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
    }

    // ── Util: format duration ms → "m:ss" ─────────────────────────────────
    private String formatDuration(long ms) {
        long sec = ms / 1000;
        long min = sec / 60;
        sec = sec % 60;
        return String.format(Locale.US, "%d:%02d", min, sec);
    }

    // ── Util: capitalize first letter ─────────────────────────────────────
    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private void showError(String msg) {
        if (tvSentTime != null) tvSentTime.setText(msg);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { onBackPressed(); return true; }
        return super.onOptionsItemSelected(item);
    }


    // ─────────────────────────────────────────────────────────────────────────
    // GroupReceiptAdapter — inner adapter for Delivered To / Read By lists
    // ─────────────────────────────────────────────────────────────────────────

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

            // Load user name + avatar from Firebase users node
            com.callx.app.utils.FirebaseUtils.db()
                    .getReference("users")
                    .child(item.uid)
                    .child("name")
                    .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                        @Override public void onDataChange(com.google.firebase.database.DataSnapshot s) {
                            String name = s.getValue(String.class);
                            if (tvName != null) tvName.setText(name != null ? name : item.uid);
                        }
                        @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {
                            if (tvName != null) tvName.setText(item.uid);
                        }
                    });

            com.callx.app.utils.FirebaseUtils.db()
                    .getReference("users")
                    .child(item.uid)
                    .child("photoUrl")
                    .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                        @Override public void onDataChange(com.google.firebase.database.DataSnapshot s) {
                            String url = s.getValue(String.class);
                            if (avatar != null && url != null && !url.isEmpty()) {
                                com.bumptech.glide.Glide.with(avatar.getContext())
                                        .load(url)
                                        .placeholder(com.callx.app.chat.R.drawable.bg_circle_white)
                                        .into(avatar);
                            }
                        }
                        @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {}
                    });

            if (tvTime != null) {
                tvTime.setText(item.ts > 0 ? fmt.format(new java.util.Date(item.ts)) : "—");
            }
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            VH(android.view.View v) { super(v); }
        }
    }
}
