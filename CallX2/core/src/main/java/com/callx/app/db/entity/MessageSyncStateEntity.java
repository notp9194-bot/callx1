package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Durable per-chat Firebase sync cursor.
 *
 * A timestamp alone is not a total order: multiple messages can share the
 * same millisecond. The message id is therefore stored as the keyset
 * tiebreaker and survives process death, force-stop, and screen recreation.
 */
@Entity(tableName = "message_sync_state")
public class MessageSyncStateEntity {

    @PrimaryKey
    @NonNull
    public String chatId = "";

    public long cursorTimestamp;
    public String cursorMessageId;
    // GAP FIX (#1 — true server cursor): server-assigned seq this chat's
    // cursor has advanced to, when known. Null until the first message
    // carrying a `seq` (see Message#seq) has been synced for this chat —
    // see MessageSyncStateDao#advance for how the (timestamp, id) vs. seq
    // comparison is chosen.
    public Long cursorSeq;
    public long updatedAt;

    public MessageSyncStateEntity() {
    }

    public MessageSyncStateEntity(@NonNull String chatId, long cursorTimestamp,
                                  String cursorMessageId) {
        this(chatId, cursorTimestamp, cursorMessageId, null);
    }

    public MessageSyncStateEntity(@NonNull String chatId, long cursorTimestamp,
                                  String cursorMessageId, Long cursorSeq) {
        this.chatId = chatId;
        this.cursorTimestamp = cursorTimestamp;
        this.cursorMessageId = cursorMessageId;
        this.cursorSeq = cursorSeq;
        this.updatedAt = System.currentTimeMillis();
    }
}