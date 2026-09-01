package com.callx.app.utils;

import com.callx.app.db.entity.MessageEntity;
import com.callx.app.models.Message;

/**
 * Single source of truth for MessageEntity ↔ Message mapping.
 *
 * Previously this exact ~30-field mapping was duplicated independently in
 * ChatActivity and GroupChatActivity (entityToModel()). Extracted here so
 * ChatRepository can also build real Message objects (needed to warm
 * LastMessagesCache from Room during chat-list preload) without a third
 * hand-copied, drift-prone duplicate.
 */
public final class MessageEntityMapper {

    private MessageEntityMapper() {}

    public static Message toModel(MessageEntity e) {
        if (e == null) return null;
        Message m = new Message();
        m.id = e.id; m.messageId = e.id; m.senderId = e.senderId; m.senderName = e.senderName;
        m.senderPhoto = e.senderPhoto; m.text = e.text; m.type = e.type; m.mediaUrl = e.mediaUrl;
        m.imageUrl = "image".equals(e.type) ? e.mediaUrl : null; m.thumbnailUrl = e.thumbnailUrl;
        m.fileName = e.fileName; m.fileSize = e.fileSize; m.duration = e.duration;
        m.timestamp = e.timestamp; m.seq = e.seq; m.status = e.status; m.replyToId = e.replyToId;
        m.deliveredAt = e.deliveredAt; m.readAt = e.readAt;
        m.deliveredBy = GroupReceiptJsonUtil.receiptsFromJson(e.groupDeliveredByJson);
        m.readBy = GroupReceiptJsonUtil.receiptsFromJson(e.groupReadByJson);
        m.replyToText = e.replyToText; m.replyToSenderName = e.replyToSenderName;
        m.replyToType = e.replyToType; m.replyToMediaUrl = e.replyToMediaUrl;
        m.edited = e.edited; m.editedAt = e.editedAt; m.deleted = e.deleted; m.forwardedFrom = e.forwardedFrom;
        m.editHistory = com.callx.app.utils.EditHistoryJsonUtil.historyFromJson(e.editHistoryJson);
        m.starred = e.starred; m.pinned = e.pinned; m.reelId = e.reelId;
        m.isGroup = Boolean.TRUE.equals(e.isGroup);
        m.isAnonymous = Boolean.TRUE.equals(e.isAnonymous);
        m.broadcast = e.broadcast;
        m.reelOwnerUid = e.reelOwnerUid;
        m.statusOwnerUid = e.statusOwnerUid; m.statusOwnerName = e.statusOwnerName;
        m.statusThumbUrl = e.statusThumbUrl;
        m.reactions = com.callx.app.utils.ReactionJsonUtil.reactionsFromJson(e.reactionsJson);
        m.reelThumbUrl = e.reelThumbUrl; m.fontStyle = e.fontStyle; m.expiresAt = e.expiresAt;
        m.viewOnce = e.viewOnce; m.viewOnceState = e.viewOnceState; m.openedAt = e.openedAt; m.viewOnceExpiresAt = e.viewOnceExpiresAt;
        m.pollQuestion = e.pollQuestion;
        m.pollOptions  = com.callx.app.utils.PollJsonUtil.optionsFromJson(e.pollOptionsJson);
        m.pollVotes    = com.callx.app.utils.PollJsonUtil.votesFromJson(e.pollVotesJson);
        m.pollAnonymous = e.pollAnonymous;
        m.pollClosed    = e.pollClosed;
        m.pollMultiChoice = e.pollMultiChoice;
        m.reelShareUrl        = e.reelShareUrl;
        m.reelShareThumb      = e.reelShareThumb;
        m.reelShareCaption    = e.reelShareCaption;
        m.reelShareUsername   = e.reelShareUsername;
        m.reelShareOwnerPhoto = e.reelShareOwnerPhoto;
        m.mediaItems = com.callx.app.utils.MediaItemsJsonUtil.mediaItemsFromJson(e.mediaItemsJson);
        m.caption    = e.caption;
        m.contactName = e.contactName; m.contactPhone = e.contactPhone;
        m.contactPhone2 = e.contactPhone2; m.contactPhotoUrl = e.contactPhotoUrl;
        m.locationLat = e.locationLat; m.locationLng = e.locationLng; m.locationAddress = e.locationAddress;
        // BUG FIX (v43): these were being dropped on every Room round-trip —
        // see AppDatabase.MIGRATION_42_43 / MessageEntity#mediaWidth.
        m.mediaWidth = e.mediaWidth; m.mediaHeight = e.mediaHeight;
        // BUG FIX (v44): blurHash — see AppDatabase.MIGRATION_43_44.
        m.blurHash = e.blurHash;
        // v46: Media E2E (image) — see AppDatabase.MIGRATION_45_46.
        m.mediaKeyEnc = e.mediaKeyEnc;
        // BUG FIX: mediaLocalPath was never copied from entity → model, so
        // Message.mediaLocalPath was always null after a Room round-trip.
        // This broke the WhatsApp-style local-first upload bubble: the adapter's
        // localPendingMedia check (mediaLocalPath != null && mediaUrl == null)
        // always evaluated false, so the canvas upload-progress gate (spinner /
        // tap-to-retry) was never armed — only a gray placeholder was drawn.
        m.mediaLocalPath = e.mediaLocalPath;
        // v48: Voice Caption on Photo — see MessageEntity#voiceUrl.
        m.voiceUrl = e.voiceUrl;
        m.voiceDuration = e.voiceDuration;
        m.mediaResourceType = e.mediaResourceType;
        m.topicId = e.topicId;
        m.topicName = e.topicName;
        return m;
    }

    /** Complete Message → Room mapping shared by 1:1, group, and repository sync. */
    public static MessageEntity fromModel(Message m, String chatId) {
        MessageEntity e = new MessageEntity();
        if (m == null) return e;
        e.id = m.id != null ? m.id : (m.messageId != null ? m.messageId : "");
        e.chatId = chatId;
        e.senderId = m.senderId;
        e.senderName = m.senderName;
        e.senderPhoto = m.senderPhoto;
        e.text = m.text;
        e.type = m.type != null ? m.type : "text";
        e.mediaUrl = m.mediaUrl != null ? m.mediaUrl : m.imageUrl;
        e.thumbnailUrl = m.thumbnailUrl;
        e.fileName = m.fileName;
        e.fileSize = m.fileSize;
        e.duration = m.duration;
        e.timestamp = m.timestamp;
        e.seq = m.seq;
        e.status = m.status;
        e.deliveredAt = m.deliveredAt;
        e.readAt = m.readAt;
        e.groupDeliveredByJson = GroupReceiptJsonUtil.receiptsToJson(m.deliveredBy);
        e.groupReadByJson = GroupReceiptJsonUtil.receiptsToJson(m.readBy);
        e.replyToId = m.replyToId;
        e.replyToText = m.replyToText;
        e.replyToSenderName = m.replyToSenderName;
        e.replyToType = m.replyToType;
        e.replyToMediaUrl = m.replyToMediaUrl;
        e.edited = m.edited;
        e.editedAt = m.editedAt;
        e.editHistoryJson = EditHistoryJsonUtil.historyToJson(m.editHistory);
        e.deleted = m.deleted;
        e.isAnonymous = m.isAnonymous;
        e.forwardedFrom = m.forwardedFrom;
        e.starred = m.starred;
        e.pinned = m.pinned;
        e.isGroup = m.isGroup;
        e.reactionsJson = ReactionJsonUtil.reactionsToJson(m.reactions);
        e.reelId = m.reelId;
        e.reelThumbUrl = m.reelThumbUrl;
        e.reelOwnerUid = m.reelOwnerUid;
        e.statusOwnerUid = m.statusOwnerUid;
        e.statusOwnerName = m.statusOwnerName;
        e.statusThumbUrl = m.statusThumbUrl;
        e.reelShareUrl = m.reelShareUrl;
        e.reelShareThumb = m.reelShareThumb;
        e.reelShareCaption = m.reelShareCaption;
        e.reelShareUsername = m.reelShareUsername;
        e.reelShareOwnerPhoto = m.reelShareOwnerPhoto;
        e.mediaLocalPath = m.mediaLocalPath;
        e.mediaResourceType = m.mediaResourceType;
        e.fontStyle = m.fontStyle;
        e.expiresAt = m.expiresAt;
        e.pollQuestion = m.pollQuestion;
        e.pollOptionsJson = PollJsonUtil.optionsToJson(m.pollOptions);
        e.pollVotesJson = PollJsonUtil.votesToJson(m.pollVotes);
        e.pollAnonymous = m.pollAnonymous;
        e.pollClosed = m.pollClosed;
        e.pollMultiChoice = m.pollMultiChoice;
        e.viewOnce = m.viewOnce;
        e.viewOnceState = m.viewOnceState;
        e.openedAt = m.openedAt;
        e.viewOnceExpiresAt = m.viewOnceExpiresAt;
        e.mediaItemsJson = MediaItemsJsonUtil.mediaItemsToJson(m.mediaItems);
        e.caption = m.caption;
        e.contactName = m.contactName;
        e.contactPhone = m.contactPhone;
        e.contactPhone2 = m.contactPhone2;
        e.contactPhotoUrl = m.contactPhotoUrl;
        e.locationLat = m.locationLat;
        e.locationLng = m.locationLng;
        e.locationAddress = m.locationAddress;
        e.broadcast = m.broadcast;
        e.voiceUrl = m.voiceUrl;
        e.voiceDuration = m.voiceDuration;
        e.mediaWidth = m.mediaWidth;
        e.mediaHeight = m.mediaHeight;
        e.blurHash = m.blurHash;
        e.mediaKeyEnc = m.mediaKeyEnc;
        e.topicId = m.topicId;
        e.topicName = m.topicName;
        e.syncedAt = System.currentTimeMillis();
        return e;
    }
}
