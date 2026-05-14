package com.callx.app.models;

  /**
   * NotificationItem — Model for Firebase notification log.
   * Used by NotificationCenterActivity and NotificationFirebaseStore.
   */
  public class NotificationItem {
      public String key;          // Firebase push key
      public String type;         // message | group | call | reel | status | system
      public String title;
      public String body;
      public String senderUid;
      public String senderName;
      public String senderPhoto;
      public String reelId;
      public String chatId;
      public String groupId;
      public long   timestamp;
      public boolean read;
      public String deliveryState; // foreground | background | killed

      public NotificationItem() {}

      public NotificationItem(String type, String title, String body,
                              String senderUid, String senderName, String senderPhoto) {
          this.type        = type;
          this.title       = title;
          this.body        = body;
          this.senderUid   = senderUid;
          this.senderName  = senderName;
          this.senderPhoto = senderPhoto;
          this.timestamp   = System.currentTimeMillis();
          this.read        = false;
      }
  }
  