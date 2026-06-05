package com.callx.app.models;

  import java.util.List;
  import java.util.Map;

  /**
   * Represents a single chat message (1-on-1 or group).
   *
   * v6 Production — fields added for new features are annotated.
   * Firebase serialisation: default no-arg constructor + public fields.
   */
  public class Message {

      // ── Core ──────────────────────────────────────────────
      public String id;
      public String messageId;       // alias for id — used by adapters
      public String senderId;
      public String senderName;
      public String senderPhoto;
      public String text;
      /** text | image | video | audio | file | location | sticker | gif | poll | status_seen | reel_seen */
      public String type;
      public String mediaUrl;
      public String thumbnailUrl;
      public String fileName;
      public Long   fileSize;
      public Long   duration;        // ms — audio/video
      public Long   timestamp;
      public String imageUrl;        // legacy — backward compat

      // ── Feature 1: Read Receipts ──────────────────────────
      /** sent | delivered | read */
      public String status;

      // ── Feature 2: Reply / Quote ──────────────────────────
      public String replyToId;
      public String replyToText;
      public String replyToSenderName;
      public String replyToType;
      public String replyToMediaUrl;

      // ── Feature 3: Emoji Reactions ────────────────────────
      /** Map of uid → emoji */
      public Map<String, String> reactions;

      // ── Feature 4: Message Editing ────────────────────────
      public Boolean edited;
      public Long    editedAt;

      // ── Feature 5: Delete for Everyone ───────────────────
      public Boolean deleted;

      // ── Feature 6: Forward ───────────────────────────────
      public String forwardedFrom;

      // ── Feature 7: Starred ───────────────────────────────
      public Boolean starred;

      // ── Feature 8: Pinned ────────────────────────────────
      public Boolean pinned;

      // ── Feature 9: Reel Seen Bubble ──────────────────────
      public String reelId;
      public String reelThumbUrl;

      // ── Feature 10: Status Seen Bubble ───────────────────
      public String statusOwnerUid;
      public String statusOwnerName;
      public String statusThumbUrl;

      // ── Group flag ───────────────────────────────────────
      public boolean isGroup;

      // ── Typing Font Style ────────────────────────────────
      public int fontStyle;  // 0 = Normal. Maps to TypingStyleManager.STYLE_*

      // ═══════════════════════════════════════════════════════
      // v6 NEW FEATURES
      // ═══════════════════════════════════════════════════════

      // ── v6 Feature A: Disappearing Messages ──────────────
      /** Unix ms — when this message auto-deletes. 0 / null = never */
      public Long expiresAt;

      // ── v6 Feature B: Location Sharing ───────────────────
      /** Set when type = "location" */
      public Double locationLat;
      public Double locationLng;
      public String locationName;

      // ── v6 Feature C: Poll ───────────────────────────────
      /** Set when type = "poll" */
      public String               pollQuestion;
      /** optId → {text, votes{uid:true}} */
      public Map<String, PollOption> pollOptions;
      public Boolean              pollMultiple;   // allow multiple votes
      public Boolean              pollAnonymous;  // hide voter identities

      // ── v6 Feature D: Sticker / GIF ──────────────────────
      // type = "sticker" or "gif" — mediaUrl holds the CDN URL

      // ── v6 Feature E: Scheduled Message ──────────────────
      public Boolean scheduled;     // true if sent via ScheduleMessageManager
      public Long    scheduledFor;  // original scheduled timestamp (ms)

      // ── v6 Feature F: @Mentions ──────────────────────────
      /** UIDs of users mentioned via @ in this message */
      public List<String> mentionedUids;

      // ── PollOption inner class ────────────────────────────
      public static class PollOption {
          public String text;
          /** uid → true */
          public Map<String, Boolean> votes;
          public PollOption() {}
      }

      public Message() {}
  }