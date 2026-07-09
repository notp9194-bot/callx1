package com.callx.app.utils;

import com.callx.app.db.entity.MessageEntity;
import com.callx.app.models.Message;

/**
 * Single source of truth for MessageEntity → Message mapping.
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
        m.timestamp = e.timestamp; m.status = e.status; m.replyToId = e.replyToId;
        m.deliveredAt = e.deliveredAt; m.readAt = e.readAt;
        m.replyToText = e.replyToText; m.replyToSenderName = e.replyToSenderName;
        m.replyToType = e.replyToType; m.replyToMediaUrl = e.replyToMediaUrl;
        m.edited = e.edited; m.editedAt = e.editedAt; m.deleted = e.deleted; m.forwardedFrom = e.forwardedFrom;
        m.editHistory = com.callx.app.utils.EditHistoryJsonUtil.historyFromJson(e.editHistoryJson);
        m.starred = e.starred; m.pinned = e.pinned; m.reelId = e.reelId;
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
        m.broadcast = e.broadcast;
        return m;
    }
}
