package com.callx.app.models;

/**
 * ReelProfile — Reels system ka alag profile model.
 *
 * Firebase path: reels/users/{uid}
 * Cloudinary folders:
 *   Avatar thumb  → callx/reels/avatars/thumbs/{uid}.webp
 *   Avatar full   → callx/reels/avatars/{uid}.jpg
 *   Banner        → callx/reels/banners/{uid}.jpg
 *
 * Chat profile (users/{uid}) se bilkul alag — ek dusre ko overwrite nahi karte.
 */
public class ReelProfile {

    public String uid;
    public String displayName;         // Reels display name (chat name se alag ho sakta hai)
    public String handle;              // @handle — unique across reels system
    public String bio;                 // Short bio (150 chars max)
    public String category;           // Creator category: "Entertainment", "Music", "Comedy" etc.
    public String photoUrl;           // Full avatar (800×800 JPEG)
    public String thumbUrl;           // 100×100 WebP — feed avatars ke liye
    public String bannerUrl;          // Profile banner image
    public String website;            // Personal website link
    public String instagramHandle;    // Instagram link (optional)
    public String twitterHandle;      // Twitter/X handle (optional)
    public String youtubeChannelUrl;  // YouTube channel link (optional)

    public long   followerCount;
    public long   followingCount;
    public long   reelCount;
    public long   totalLikes;
    public long   profileViews;

    public boolean verified;          // Creator verified badge
    public boolean privateAccount;    // Private account — follow required
    public boolean allowDuet;
    public boolean allowStitch;
    public boolean allowComments;

    public long   createdAt;
    public long   updatedAt;

    public ReelProfile() {}

    public ReelProfile(String uid, String displayName, String handle) {
        this.uid         = uid;
        this.displayName = displayName;
        this.handle      = handle;
        this.createdAt   = System.currentTimeMillis();
        this.updatedAt   = System.currentTimeMillis();
        this.allowDuet   = true;
        this.allowStitch = true;
        this.allowComments = true;
    }
}
