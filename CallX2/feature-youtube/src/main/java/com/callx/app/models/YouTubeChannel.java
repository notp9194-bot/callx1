package com.callx.app.models;

public class YouTubeChannel {
    public String uid;
    public String channelName;
    public String handle;
    public String photoUrl;
    public String bannerUrl;
    public String bio;
    public long   subscriberCount;
    public long   videoCount;
    public long   totalViews;
    public long   totalLikes;
    public long   createdAt;
    public boolean isVerified;
    public boolean isMonetized;
    public String  country;
    public String  websiteUrl;
    public String  twitterHandle;
    public String  instagramHandle;
    public String  category;

    public YouTubeChannel() {}

    public YouTubeChannel(String uid, String channelName, String handle,
                          String photoUrl, String bio) {
        this.uid             = uid;
        this.channelName     = channelName;
        this.handle          = handle;
        this.photoUrl        = photoUrl;
        this.bio             = bio;
        this.subscriberCount = 0;
        this.videoCount      = 0;
        this.totalViews      = 0;
        this.totalLikes      = 0;
        this.createdAt       = System.currentTimeMillis();
        this.isVerified      = false;
        this.isMonetized     = false;
    }
}
