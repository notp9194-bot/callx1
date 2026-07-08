package com.callx.app.conversation.info;

import android.os.Bundle;
import android.view.View;
import android.view.LayoutInflater;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.google.android.material.appbar.MaterialToolbar;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * MessageInfoActivity — lightweight, single, full-screen "Message Info"
 * view shared by ChatActivity (1:1) and GroupChatActivity (group), replacing
 * the two separate AlertDialogs (showMessageInfoDialog /
 * showGroupMessageInfoDialog) that used to just dump a text blob.
 *
 * Same information as before (sent/delivered/seen for 1:1; per-member
 * read-by / delivered-to / pending for groups) — just rendered as a real
 * WhatsApp-style screen instead of a dialog. All data arrives precomputed
 * via MessageInfoBridge; this Activity does no Firebase/Room work itself.
 */
public class MessageInfoActivity extends AppCompatActivity {

    private static final SimpleDateFormat SDF =
            new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault());

    private LinearLayout llContent;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_message_info);

        MessageInfoData data = MessageInfoBridge.take();
        if (data == null) { finish(); return; }

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView tvPreview = findViewById(R.id.tv_preview);
        TextView tvSentTime = findViewById(R.id.tv_sent_time);
        llContent = findViewById(R.id.ll_info_content);

        tvPreview.setText(data.previewLabel);
        tvSentTime.setText("Sent  •  " + formatTime(data.sentAt));

        if (!data.isOutgoing) {
            // Received message (1:1 or group) — nothing more granular to show.
            addStatusRow(
                    data.incomingStatus != null ? capitalize(data.incomingStatus) : "Sent",
                    "",
                    R.drawable.ic_single_tick,
                    false);
            return;
        }

        if (!data.isGroup) {
            // 1:1 outgoing — two rows: Seen, Delivered (WhatsApp order: newest first).
            addStatusRow("Seen",
                    data.readAt != null ? formatTime(data.readAt) : "Not seen yet",
                    R.drawable.ic_double_tick_blue,
                    data.readAt == null);
            addStatusRow("Delivered",
                    data.deliveredAt != null ? formatTime(data.deliveredAt) : "Not delivered yet",
                    R.drawable.ic_double_tick,
                    data.deliveredAt == null);
            return;
        }

        // Group outgoing — sectioned per-member breakdown.
        addHeader("READ BY (" + data.readBy.size() + "/" + data.totalOthers + ")");
        if (data.readBy.isEmpty()) {
            addEmptyRow("No one yet");
        } else {
            for (MessageInfoData.MemberReceipt r : data.readBy) {
                addMemberRow(r, R.drawable.ic_double_tick_blue);
            }
        }

        addHeader("DELIVERED TO (" + data.deliveredOnly.size() + ")");
        if (data.deliveredOnly.isEmpty()) {
            addEmptyRow("—");
        } else {
            for (MessageInfoData.MemberReceipt r : data.deliveredOnly) {
                addMemberRow(r, R.drawable.ic_double_tick);
            }
        }

        if (!data.pending.isEmpty()) {
            addHeader("PENDING (" + data.pending.size() + ")");
            for (MessageInfoData.MemberReceipt r : data.pending) {
                addMemberRow(r, 0);
            }
        }
    }

    // ── Row builders ─────────────────────────────────────────────────────

    private void addHeader(String text) {
        View header = LayoutInflater.from(this)
                .inflate(R.layout.item_message_info_header, llContent, false);
        ((TextView) header).setText(text);
        llContent.addView(header);
    }

    private void addStatusRow(String label, String time, int iconRes, boolean dim) {
        View row = LayoutInflater.from(this)
                .inflate(R.layout.item_message_info_status_row, llContent, false);
        ((TextView) row.findViewById(R.id.tv_status_label)).setText(label);
        TextView tvTime = row.findViewById(R.id.tv_status_time);
        tvTime.setText(time);
        ImageView icon = row.findViewById(R.id.iv_status_icon);
        icon.setImageResource(iconRes);
        icon.setAlpha(dim ? 0.35f : 1f);
        llContent.addView(row);
    }

    private void addMemberRow(MessageInfoData.MemberReceipt r, int tickRes) {
        View row = LayoutInflater.from(this)
                .inflate(R.layout.item_message_info_member, llContent, false);
        ((TextView) row.findViewById(R.id.tv_member_name)).setText(r.name);
        TextView tvTime = row.findViewById(R.id.tv_member_time);
        ImageView ivTick = row.findViewById(R.id.iv_member_tick);
        if (r.timestamp != null) {
            tvTime.setText(formatTime(r.timestamp));
            if (tickRes != 0) {
                ivTick.setVisibility(View.VISIBLE);
                ivTick.setImageResource(tickRes);
            }
        } else {
            tvTime.setText("");
        }
        de.hdodenhof.circleimageview.CircleImageView avatar = row.findViewById(R.id.iv_member_avatar);
        if (r.photoUrl != null && !r.photoUrl.isEmpty()) {
            Glide.with(this).load(r.photoUrl).placeholder(R.drawable.ic_person).into(avatar);
        }
        llContent.addView(row);
    }

    private void addEmptyRow(String text) {
        View row = LayoutInflater.from(this)
                .inflate(R.layout.item_message_info_member, llContent, false);
        row.findViewById(R.id.iv_member_avatar).setVisibility(View.GONE);
        row.findViewById(R.id.iv_member_tick).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.tv_member_name)).setText(text);
        ((TextView) row.findViewById(R.id.tv_member_name)).setTextColor(
                getResources().getColor(R.color.text_muted));
        row.findViewById(R.id.tv_member_time).setVisibility(View.GONE);
        llContent.addView(row);
    }

    private static String formatTime(long ts) {
        if (ts <= 0) return "";
        return SDF.format(new Date(ts));
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
