package com.callx.app.activities;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * MessageInfoActivity — Production-grade message delivery info screen.
 *
 * Features:
 *   • Real-time Firebase listener — status instantly update hota hai
 *   • Sent / Delivered / Seen time with exact date + time
 *   • Message preview (text / media thumbnail)
 *   • Graceful "—" for pending states (dimmed)
 *   • Lifecycle-safe: listener onDestroy mein detach hota hai
 */
public class MessageInfoActivity extends AppCompatActivity {

    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault());

    private static final int COLOUR_BLUE    = 0xFF2196F3;  // seen  ✓✓
    private static final int COLOUR_GREY    = 0xFF9E9E9E;  // sent/delivered ✓ / ✓✓
    private static final int COLOUR_PENDING = 0xFFBDBDBD;  // not yet reached (dimmed)

    private DatabaseReference msgRef;
    private ValueEventListener liveListener;

    // Views
    private TextView tvSentTime, tvDelivTime, tvSeenTime;
    private View rowSent, rowDeliv, rowSeen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_message_info);

        String chatId    = getIntent().getStringExtra("chatId");
        String messageId = getIntent().getStringExtra("messageId");
        String msgText   = getIntent().getStringExtra("msgText");
        String msgType   = getIntent().getStringExtra("msgType");
        String mediaUrl  = getIntent().getStringExtra("mediaUrl");
        String thumbUrl  = getIntent().getStringExtra("thumbUrl");
        long   sentAt    = getIntent().getLongExtra("sentAt",      0);
        long   delivAt   = getIntent().getLongExtra("deliveredAt", 0);
        long   seenAt    = getIntent().getLongExtra("seenAt",      0);

        // ── Back button ───────────────────────────────────────────────
        ImageButton btnBack = findViewById(R.id.btn_back_info);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        // ── Message preview ───────────────────────────────────────────
        bindPreview(msgType, msgText, mediaUrl, thumbUrl);

        // ── Bind views ────────────────────────────────────────────────
        rowSent  = findViewById(R.id.ll_info_sent);
        rowDeliv = findViewById(R.id.ll_info_delivered);
        rowSeen  = findViewById(R.id.ll_info_seen);
        tvSentTime  = findViewById(R.id.tv_info_sent_time);
        tvDelivTime = findViewById(R.id.tv_info_deliv_time);
        tvSeenTime  = findViewById(R.id.tv_info_seen_time);

        // ── Render with intent data first (instant — no network wait) ─
        renderTimestamps(sentAt, delivAt, seenAt);

        // ── Live Firebase listener — real-time updates ─────────────────
        if (chatId != null && !chatId.isEmpty() &&
            messageId != null && !messageId.isEmpty()) {
            msgRef = FirebaseUtils.getMessagesRef(chatId).child(messageId);
            attachLiveListener();
        }
    }

    // ── Live listener — attaches on resume, detaches on pause ────────
    private void attachLiveListener() {
        liveListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                Long fbSentAt  = snap.child("sentAt").getValue(Long.class);
                Long fbDeliv   = snap.child("deliveredAt").getValue(Long.class);
                Long fbSeen    = snap.child("seenAt").getValue(Long.class);
                renderTimestamps(
                    fbSentAt != null ? fbSentAt : 0L,
                    fbDeliv  != null ? fbDeliv  : 0L,
                    fbSeen   != null ? fbSeen   : 0L
                );
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        msgRef.addValueEventListener(liveListener);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Lifecycle-safe — memory leak nahi hoga
        if (msgRef != null && liveListener != null) {
            msgRef.removeEventListener(liveListener);
        }
    }

    // ── Render all three timestamp rows ──────────────────────────────
    private void renderTimestamps(long sentAt, long delivAt, long seenAt) {
        bindRow(rowSent,  tvSentTime,  sentAt,  "✓  Sent",      COLOUR_GREY);
        bindRow(rowDeliv, tvDelivTime, delivAt, "✓✓ Delivered",  COLOUR_GREY);
        bindRow(rowSeen,  tvSeenTime,  seenAt,  "✓✓ Seen",       COLOUR_BLUE);
    }

    private void bindRow(View row, TextView tvTime, long ts,
                         String label, int colour) {
        if (row == null || tvTime == null) return;

        // Label TextView (first child)
        TextView tvLabel = null;
        if (row instanceof LinearLayout) {
            View child = ((LinearLayout) row).getChildAt(0);
            if (child instanceof TextView) tvLabel = (TextView) child;
        }

        if (tvLabel != null) {
            tvLabel.setText(label);
            tvLabel.setTextColor(ts > 0 ? colour : COLOUR_PENDING);
        }

        if (ts > 0) {
            tvTime.setText(DATE_FMT.format(new Date(ts)));
            row.setAlpha(1f);
        } else {
            tvTime.setText("—");
            row.setAlpha(0.45f);  // dimmed = not yet reached this state
        }
    }

    // ── Message preview ───────────────────────────────────────────────
    private void bindPreview(String msgType, String msgText,
                              String mediaUrl, String thumbUrl) {
        TextView  tvPreview = findViewById(R.id.tv_info_msg_preview);
        ImageView ivPreview = findViewById(R.id.iv_info_msg_thumb);
        LinearLayout llMedia = findViewById(R.id.ll_info_media_preview);

        if (tvPreview == null) return;

        if ("image".equals(msgType)) {
            tvPreview.setText("📷 Photo");
            showThumb(ivPreview, llMedia, thumbUrl != null && !thumbUrl.isEmpty() ? thumbUrl : mediaUrl);
        } else if ("video".equals(msgType)) {
            tvPreview.setText("🎬 Video");
            showThumb(ivPreview, llMedia, thumbUrl);
        } else if ("audio".equals(msgType)) {
            tvPreview.setText("🎤 Voice message");
            if (llMedia != null) llMedia.setVisibility(View.GONE);
        } else if ("file".equals(msgType) || "document".equals(msgType)) {
            tvPreview.setText("📎 " + (msgText != null && !msgText.isEmpty() ? msgText : "File"));
            if (llMedia != null) llMedia.setVisibility(View.GONE);
        } else {
            // text
            String display = (msgText != null && !msgText.isEmpty()) ? msgText : "Message";
            tvPreview.setText(display);
            if (llMedia != null) llMedia.setVisibility(View.GONE);
        }
    }

    private void showThumb(ImageView iv, LinearLayout ll, String url) {
        if (iv == null || ll == null) return;
        if (url != null && !url.isEmpty()) {
            ll.setVisibility(View.VISIBLE);
            Glide.with(this).load(url).centerCrop()
                 .placeholder(R.drawable.ic_file).into(iv);
        } else {
            ll.setVisibility(View.GONE);
        }
    }
}
