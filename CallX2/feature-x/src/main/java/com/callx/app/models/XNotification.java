package com.callx.app.models;

  public class XNotification {
      public String id;
      public String type;           // "like" | "retweet" | "reply" | "mention" | "follow" | "quote" | "dm"
      public String fromUid;
      public String fromName;
      public String fromPhotoUrl;
      public String tweetId;
      public String tweetSnippet;
      public String conversationId;
      public long   timestamp;
      public boolean read;
      public boolean notified;      // WorkManager has already posted a system notification
  }