package com.callx.app.models;

import com.google.firebase.database.Exclude;
import java.util.HashMap;
import java.util.Map;

public class XUser {
    public String  uid;
    public String  name;
    public String  handle;
    public String  bio;
    public String  photoUrl;
    public String  bannerUrl;
    public String  website;
    public String  location;
    public long    joinedTs;
    public boolean verified;
    public boolean blueVerified;
    public long    followerCount;
    public long    followingCount;
    public long    tweetCount;
    public String  pinnedTweetId;

    // FIX: Do NOT pre-initialize these here — Firebase deserializer overwrites with null
    // if the DB node doesn't exist, causing NPE in helper methods.
    // Use @Exclude getters that null-guard instead.
    public Map<String, Boolean> followers;
    public Map<String, Boolean> muted;
    public Map<String, Boolean> blocked;

    /** Safe null-guarded helpers — never crash even if maps weren't in DB */
    @Exclude
    public boolean isFollowedBy(String uid) {
        return uid != null && followers != null && Boolean.TRUE.equals(followers.get(uid));
    }

    @Exclude
    public boolean hasMuted(String uid) {
        return uid != null && muted != null && Boolean.TRUE.equals(muted.get(uid));
    }

    @Exclude
    public boolean hasBlocked(String uid) {
        return uid != null && blocked != null && Boolean.TRUE.equals(blocked.get(uid));
    }

    /** Called after deserialization to ensure maps are never null */
    @Exclude
    public void ensureMapsNotNull() {
        if (followers == null) followers = new HashMap<>();
        if (muted     == null) muted     = new HashMap<>();
        if (blocked   == null) blocked   = new HashMap<>();
    }
}
