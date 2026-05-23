package com.callx.app.models;

import java.util.HashMap;
import java.util.Map;

public class XUser {
    public String uid;
    public String name;
    public String handle;
    public String bio;
    public String photoUrl;
    public String bannerUrl;
    public String website;
    public String location;
    public long   joinedTs;
    public boolean verified;
    public boolean blueVerified;
    public long   followerCount;
    public long   followingCount;
    public long   tweetCount;
    public String pinnedTweetId;

    public Map<String, Boolean> followers  = new HashMap<>();
    public Map<String, Boolean> muted      = new HashMap<>();
    public Map<String, Boolean> blocked    = new HashMap<>();

    public boolean isFollowedBy(String uid) {
        return uid != null && Boolean.TRUE.equals(followers.get(uid));
    }
    public boolean hasMuted(String uid) {
        return uid != null && Boolean.TRUE.equals(muted.get(uid));
    }
    public boolean hasBlocked(String uid) {
        return uid != null && Boolean.TRUE.equals(blocked.get(uid));
    }
}
