package com.callx.app.conversation.info;

import com.callx.app.chat.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Builds the flattened List<MessageInfoRow> MessageInfoBottomSheet's
 * RecyclerView is backed by. Same sent/delivered/seen (1:1) and
 * read-by/delivered-to/pending (group) logic MessageInfoActivity used to
 * run straight against inflated Views — here it just appends plain data,
 * so it runs once, cheaply, before the RecyclerView is even attached.
 */
public final class MessageInfoRowBuilder {

    private static final SimpleDateFormat SDF =
            new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault());

    private MessageInfoRowBuilder() {}

    public static List<MessageInfoRow> build(MessageInfoData data) {
        List<MessageInfoRow> rows = new ArrayList<>();

        rows.add(MessageInfoRow.preview(data.previewLabel, "Sent  •  " + formatTime(data.sentAt)));

        if (!data.isOutgoing) {
            // Received message (1:1 or group) — nothing more granular to show.
            rows.add(MessageInfoRow.status(
                    data.incomingStatus != null ? capitalize(data.incomingStatus) : "Sent",
                    "",
                    R.drawable.ic_single_tick,
                    false));
            return rows;
        }

        if (!data.isGroup) {
            // 1:1 outgoing — two rows: Seen, Delivered (WhatsApp order: newest first).
            rows.add(MessageInfoRow.status("Seen",
                    data.readAt != null ? formatTime(data.readAt) : "Not seen yet",
                    R.drawable.ic_double_tick_blue,
                    data.readAt == null));
            rows.add(MessageInfoRow.status("Delivered",
                    data.deliveredAt != null ? formatTime(data.deliveredAt) : "Not delivered yet",
                    R.drawable.ic_double_tick,
                    data.deliveredAt == null));
            return rows;
        }

        // Group outgoing — sectioned per-member breakdown.
        rows.add(MessageInfoRow.header("READ BY (" + data.readBy.size() + "/" + data.totalOthers + ")"));
        if (data.readBy.isEmpty()) {
            rows.add(MessageInfoRow.empty("No one yet"));
        } else {
            for (MessageInfoData.MemberReceipt r : data.readBy) {
                rows.add(MessageInfoRow.member(r.name,
                        r.timestamp != null ? formatTime(r.timestamp) : "",
                        R.drawable.ic_double_tick_blue, r.photoUrl));
            }
        }

        rows.add(MessageInfoRow.header("DELIVERED TO (" + data.deliveredOnly.size() + ")"));
        if (data.deliveredOnly.isEmpty()) {
            rows.add(MessageInfoRow.empty("—"));
        } else {
            for (MessageInfoData.MemberReceipt r : data.deliveredOnly) {
                rows.add(MessageInfoRow.member(r.name,
                        r.timestamp != null ? formatTime(r.timestamp) : "",
                        R.drawable.ic_double_tick, r.photoUrl));
            }
        }

        if (!data.pending.isEmpty()) {
            rows.add(MessageInfoRow.header("PENDING (" + data.pending.size() + ")"));
            for (MessageInfoData.MemberReceipt r : data.pending) {
                rows.add(MessageInfoRow.member(r.name, "", 0, r.photoUrl));
            }
        }

        return rows;
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
