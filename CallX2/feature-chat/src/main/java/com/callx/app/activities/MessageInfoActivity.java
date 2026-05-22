package com.callx.app.activities;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import androidx.annotation.NonNull;

/**
 * MessageInfoActivity — shows per-message delivery timestamps.
 *
 * Opened from ChatActivity long-press → "ℹ Info" (sender's own messages only).
 *
 * Displays:
 *   • Message preview (text or media type icon)
 *   • ✓  Sent time
 *   • ✓✓ Delivered time  (grey — if available)
 *   • ✓✓ Seen/Read time  (blue — if available)
 *
 * Data sources (in priority order):
 *   1. Intent extras (sentAt / deliveredAt / seenAt) — already in Room
 *   2. Firebase live fetch on messages/{chatId}/{messageId} — for real-time update
 */
public class MessageInfoActivity extends AppCompatActivity {

    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault());

    // Colours matching WhatsApp convention
    private static final int COLOUR_BLUE = 0xFF2196F3;
    private static final int COLOUR_GREY = 0xFF9E9E9E;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_message_info);

        // ── Extract intent extras ──────────────────────────────────────
        String chatId    = getIntent().getStringExtra("chatId");
        String messageId = getIntent().getStringExtra("messageId");
        String msgText   = getIntent().getStringExtra("msgText");
        String msgType   = getIntent().getStringExtra("msgType");
        String mediaUrl  = getIntent().getStringExtra("mediaUrl");
        String thumbUrl  = getIntent().getStringExtra("thumbUrl");
        long   sentAt    = getIntent().getLongExtra("sentAt",    0);
        long   delivAt   = getIntent().getLongExtra("deliveredAt", 0);
        long   seenAt    = getIntent().getLongExtra("seenAt",    0);

        // ── Back button ───────────────────────────────────────────────
        ImageButton btnBack = findViewById(R.id.btn_back_info);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        // ── Message preview ───────────────────────────────────────────
        TextView tvPreview    = findViewById(R.id.tv_info_msg_preview);
        ImageView ivPreview   = findViewById(R.id.iv_info_msg_thumb);
        LinearLayout llMedia  = findViewById(R.id.ll_info_media_preview);

        if (tvPreview != null) {
            if ("image".equals(msgType)) {
                tvPreview.setText("📷 Photo");
                showThumb(ivPreview, llMedia, thumbUrl != null ? thumbUrl : mediaUrl);
            } else if ("video".equals(msgType)) {
                tvPreview.setText("🎬 Video");
                showThumb(ivPreview, llMedia, thumbUrl);
            } else if ("audio".equals(msgType)) {
                tvPreview.setText("🎤 Voice message");
                if (llMedia != null) llMedia.setVisibility(View.GONE);
            } else if ("file".equals(msgType) || "document".equals(msgType)) {
                tvPreview.setText("📎 File");
                if (llMedia != null) llMedia.setVisibility(View.GONE);
            } else {
                // text
                tvPreview.setText(msgText != null && !msgText.isEmpty() ? msgText : "Message");
                if (llMedia != null) llMedia.setVisibility(View.GONE);
            }
        }

        // ── Timestamp rows ────────────────────────────────────────────
        bindRow(R.id.ll_info_sent,       R.id.tv_info_sent_label,    R.id.tv_info_sent_time,
                "✓  Sent",      sentAt,  COLOUR_GREY);
        bindRow(R.id.ll_info_delivered,  R.id.tv_info_deliv_label,   R.id.tv_info_deliv_time,
                "✓✓ Delivered", delivAt, COLOUR_GREY);
        bindRow(R.id.ll_info_seen,       R.id.tv_info_seen_label,    R.id.tv_info_seen_time,
                "✓✓ Seen",      seenAt,  COLOUR_BLUE);

        // ── Live Firebase fetch to update if timestamps arrive later ──
        if (chatId != null && messageId != null) {
            FirebaseUtils.getMessagesRef(chatId).child(messageId)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override
                        public void onDataChange(@NonNull DataSnapshot snap) {
                            Long fbSentAt  = snap.child("sentAt").getValue(Long.class);
                            Long fbDeliv   = snap.child("deliveredAt").getValue(Long.class);
                            Long fbSeen    = snap.child("seenAt").getValue(Long.class);
                            // Refresh UI if Firebase has newer data
                            if (fbSentAt != null && fbSentAt > 0)
                                updateTime(R.id.tv_info_sent_time, fbSentAt);
                            if (fbDeliv  != null && fbDeliv  > 0)
                                updateTime(R.id.tv_info_deliv_time, fbDeliv);
                            if (fbSeen   != null && fbSeen   > 0)
                                updateTime(R.id.tv_info_seen_time, fbSeen);
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {}
                    });
        }
    }

    // ── Helper: bind a single timestamp row ──────────────────────
    private void bindRow(int rowId, int labelId, int timeId,
                         String label, long ts, int colour) {
        View row = findViewById(rowId);
        if (row == null) return;

        TextView tvLabel = row.findViewById(labelId);
        TextView tvTime  = row.findViewById(timeId);

        if (tvLabel != null) {
            tvLabel.setText(label);
            tvLabel.setTextColor(colour);
        }
        if (tvTime != null) {
            if (ts > 0) {
                tvTime.setText(DATE_FMT.format(new Date(ts)));
                row.setVisibility(View.VISIBLE);
            } else {
                tvTime.setText("—");
                row.setAlpha(0.4f);   // dim — not yet reached this state
                row.setVisibility(View.VISIBLE);
            }
        }
    }

    // ── Helper: update time TextView (called from Firebase callback) ──
    private void updateTime(int tvId, long ts) {
        TextView tv = findViewById(tvId);
        if (tv != null && ts > 0) {
            tv.setText(DATE_FMT.format(new Date(ts)));
            // Make sure parent row is fully visible (not dimmed)
            if (tv.getParent() instanceof View)
                ((View) tv.getParent()).setAlpha(1f);
        }
    }

    // ── Helper: show media thumbnail ─────────────────────────────
    private void showThumb(ImageView iv, LinearLayout ll, String url) {
        if (iv == null || ll == null) return;
        if (url != null && !url.isEmpty()) {
            ll.setVisibility(View.VISIBLE);
            Glide.with(this).load(url).centerCrop()
                    .placeholder(R.drawable.ic_file)
                    .into(iv);
        } else {
            ll.setVisibility(View.GONE);
        }
    }
}
