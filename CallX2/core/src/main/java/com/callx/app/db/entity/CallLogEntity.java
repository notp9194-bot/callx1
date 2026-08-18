package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * v16: Room entity for call history cache.
 * Offline mein call logs dikhane ke liye.
 */
@Entity(
    tableName = "call_logs",
    indices = { @Index(value = {"timestamp"}) }
)
public class CallLogEntity {

    @PrimaryKey
    @NonNull
    public String id = "";

    public String partnerUid;
    public String partnerName;
    public String partnerPhoto;
    public String direction;   // incoming | outgoing | missed
    public String mediaType;   // audio | video
    public Long   timestamp;
    public Long   duration;
    public long   syncedAt;

    public CallLogEntity() {
        this.syncedAt = System.currentTimeMillis();
    }
}
