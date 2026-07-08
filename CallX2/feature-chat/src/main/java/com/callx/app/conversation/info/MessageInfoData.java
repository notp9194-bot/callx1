package com.callx.app.conversation.info;

import java.util.ArrayList;
import java.util.List;

/**
 * MessageInfoData — everything MessageInfoActivity needs to render the
 * lightweight, WhatsApp-style "Message Info" screen, precomputed by the
 * caller (ChatActivity for 1:1, GroupChatActivity for groups) so the
 * screen itself stays a dumb renderer with zero Firebase/Room access.
 *
 * Replaces the old showMessageInfoDialog() / showGroupMessageInfoDialog()
 * AlertDialogs — same underlying data, just handed to a real screen
 * instead of a text-blob dialog.
 */
public class MessageInfoData {

    /** One row in a "Read by" / "Delivered to" section (group chats only). */
    public static class MemberReceipt {
        public final String uid;
        public final String name;
        public final String photoUrl;
        /** read-timestamp or delivered-timestamp depending which list this sits in. */
        public final Long timestamp;

        public MemberReceipt(String uid, String name, String photoUrl, Long timestamp) {
            this.uid = uid;
            this.name = name;
            this.photoUrl = photoUrl;
            this.timestamp = timestamp;
        }
    }

    // ── Message preview (shown at the top, like WhatsApp's small bubble) ──
    public String previewLabel;   // e.g. "📷 Photo", "🎤 Voice message", or the text itself
    public String messageType;

    public boolean isGroup;
    public boolean isOutgoing;

    public long sentAt;

    // ── 1:1 fields (only meaningful when !isGroup) ──────────────────────
    public Long deliveredAt;
    public Long readAt;

    // ── Incoming-message fallback (both 1:1 and group) ──────────────────
    public String incomingStatus;

    // ── Group fields (only meaningful when isGroup && isOutgoing) ───────
    public int totalOthers;
    public final List<MemberReceipt> readBy = new ArrayList<>();
    public final List<MemberReceipt> deliveredOnly = new ArrayList<>();
    public final List<MemberReceipt> pending = new ArrayList<>();
}
