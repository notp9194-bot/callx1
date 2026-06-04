package com.callx.app.youtube.core.models;

public class YouTubeChannel {
    public String uid;
    public String channelName;
    public String handle;          // @handle
    public String photoUrl;
    public String bannerUrl;
    public String bio;
    public long   subscriberCount;
    public long   videoCount;
    public long   totalViews;
    public long   createdAt;
    public boolean isVerified;
    public String  country;

    public YouTubeChannel() {}

    public YouTubeChannel(String uid, String channelName, String handle,
                          String photoUrl, String bio) {
        this.uid           = uid;
        this.channelName   = channelName;
        this.handle        = handle;
        this.photoUrl      = photoUrl;
        this.bio           = bio;
        this.subscriberCount = 0;
        this.videoCount    = 0;
        this.totalViews    = 0;
        this.createdAt     = System.currentTimeMillis();
        this.isVerified    = false;
    }
}
