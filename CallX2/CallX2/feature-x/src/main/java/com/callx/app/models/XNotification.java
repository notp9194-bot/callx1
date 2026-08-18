package com.callx.app.models;

public class XNotification {
    public String  id;
    public String  type;           // "like" | "retweet" | "reply" | "follow" | "mention"
    public String  fromUid;
    public String  fromName;
    public String  fromPhotoUrl;
    public String  fromThumbUrl;
    public String  tweetId;
    public String  tweetSnippet;
    public long    timestamp;
    public boolean read;
    public boolean notified;
}
