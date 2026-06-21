package com.callx.app.models;

/**
 * A message queued to be sent at a future time — lives in its own Firebase
 * node (scheduledMessages/{chatId}/{scheduleId}) and its own Room table
 * (scheduled_messages), completely separate from the live "messages" node/
 * table. It is NEVER inserted into the chat's message list until
 * ChatScheduledMessageWorker actually fires and publishes it — exactly
 * like XTweet's scheduled_posts queue, just kept as its own lightweight
 * POJO instead of overloading the regular Message model with fields most
 * messages never use.
 *
 * Firebase serialisation uses default no-arg constructor + public fields.
 */
public class ScheduledMessage {

    public String id;            // == scheduleId, mirrors the Firebase key
    public String chatId;
    public String senderId;
    public String senderName;
    public String partnerUid;    // recipient — needed by the worker to fire without ChatActivity context
    public String text;
    /** Currently text-only — media scheduling isn't supported (kept simple
     *  on purpose; a media upload can't safely be "queued" while offline). */
    public String type = "text";
    public int    fontStyle;
    /** When this should be sent, epoch ms. */
    public long   sendAt;
    /** When the user created this scheduled entry, epoch ms — shown in the
     *  manage-scheduled list ("queued 2h ago"). */
    public long   createdAt;
    /** replyToId/replyToText etc. intentionally omitted — replying to a
     *  message that may itself disappear/edit before send time adds more
     *  edge cases than the feature is worth; scheduled sends are plain text. */

    public ScheduledMessage() {}
}
