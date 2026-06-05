package com.callx.app.db.entity;

  import androidx.annotation.NonNull;
  import androidx.room.Entity;
  import androidx.room.Index;
  import androidx.room.PrimaryKey;

  /**
   * Room DB entity for cached messages.
   *
   * v6: Added fields for disappearing messages, location, poll, schedule.
   * Schema version bumped — run migration or fallbackToDestructiveMigration().
   */
  @Entity(
      tableName = "messages",
      indices = {
          @Index(value = {"chatId", "timestamp"}),
          @Index(value = {"chatId", "starred"}),
          @Index(value = {"syncedAt"}),
          @Index(value = {"expiresAt"}),       // v6: fast expired-message lookup
          @Index(value = {"chatId", "text"})   // v6: fast in-chat search
      }
  )
  public class MessageEntity {

      @PrimaryKey @NonNull
      public String id = "";

      public String  chatId;
      public String  senderId;
      public String  senderName;
      public String  senderPhoto;
      public String  text;
      /** text|image|video|audio|file|location|sticker|gif|poll|status_seen|reel_seen */
      public String  type;
      public String  mediaUrl;
      public String  thumbnailUrl;
      public String  fileName;
      public Long    fileSize;
      public Long    duration;
      public Long    timestamp;
      public String  status;
      public String  replyToId;
      public String  replyToText;
      public String  replyToSenderName;
      public String  replyToType;
      public String  replyToMediaUrl;
      public Boolean edited;
      public Long    editedAt;
      public Boolean deleted;
      public String  forwardedFrom;
      public Boolean starred;
      public Boolean pinned;
      public Boolean isGroup;
      public long    syncedAt;
      public String  reelId;
      public String  reelThumbUrl;
      public String  mediaLocalPath;
      public String  mediaResourceType;
      public int     fontStyle;

      // ── v6 new fields ────────────────────────────────────
      /** Auto-delete timestamp. 0 = never expires. */
      public long    expiresAt = 0L;

      // Location
      public double  locationLat = 0.0;
      public double  locationLng = 0.0;
      public String  locationName;

      /** Poll data stored as JSON string (Gson). Non-null when type = "poll" */
      public String  pollJson;

      /** true = sent via ScheduleMessageManager */
      public boolean scheduled = false;

      /** Comma-separated mentioned UIDs */
      public String  mentionedUids;

      public MessageEntity() {}
  }