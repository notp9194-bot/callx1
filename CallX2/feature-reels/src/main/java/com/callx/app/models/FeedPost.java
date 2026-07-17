package com.callx.app.models;

import com.google.firebase.database.IgnoreExtraProperties;

import java.util.List;

/**
 * FeedPost — unified model for the Instagram-like Home Feed.
 *
 * Wraps a ReelModel with additional feed-level metadata:
 *  - postType  : "video" | "photo" | "carousel" | "sponsored"
 *  - isLiked   : client-side state (loaded per user from reel_likes/)
 *  - isSaved   : client-side state (loaded per user from reel_saves/)
 *  - isFollowing: client-side state (loaded per user from user_followers/)
 *
 * Firebase paths (via ReelFirebaseUtils):
 *  reels/videos/{reelId}               → core reel data (ReelModel)
 *  reels/reel_likes/{reelId}/{uid}     → like toggle (true/absent)
 *  reels/reel_saves/{reelId}/{uid}     → save toggle (true/absent)
 *  reels/user_followers/{ownerUid}/{myUid} → follow state
 *  reels/users/{uid}                   → owner profile
 */
@IgnoreExtraProperties
public class FeedPost {

    // ── Post type constants ────────────────────────────────────────────────
    public static final String TYPE_VIDEO    = "video";
    public static final String TYPE_PHOTO    = "photo";
    public static final String TYPE_CAROUSEL = "carousel";
    public static final String TYPE_SPONSORED = "sponsored";

    // ── Core data ─────────────────────────────────────────────────────────
    public String reelId;
    public String uid;
    public String ownerName;
    public String ownerHandle;
    public String ownerPhotoUrl;
    public boolean ownerVerified;

    // Post content
    public String postType = TYPE_VIDEO;
    public String videoUrl;
    public String thumbUrl;
    public String caption;
    public String location;
    public String musicName;
    public String musicArtist;
    public String musicCoverUrl;

    // Carousel photos
    public List<String> photoUrls;

    // Counts (snapshot from Firebase — live updates via listeners in adapter)
    public long likesCount;
    public long commentsCount;
    public long sharesCount;
    public long repostCount;
    public long viewsCount;

    // Timestamp
    public long timestamp;

    // Hashtags
    public List<String> hashtags;

    // Audience
    public String audienceType; // "everyone" | "followers" | "private"

    // ── Client-side state (not stored in Firebase) ─────────────────────────
    /** True if the current user has liked this post. */
    public transient boolean isLiked;
    /** True if the current user has saved this post. */
    public transient boolean isSaved;
    /** True if the current user follows the post owner. */
    public transient boolean isFollowing;
    /** True for repost-style posts. */
    public transient boolean isRepost;
    public transient String repostByName;

    // ── Sponsored post metadata ────────────────────────────────────────────
    public String sponsorLabel;   // e.g. "Sponsored"
    public String ctaText;        // e.g. "Shop Now"
    public String ctaUrl;         // deep-link or web URL

    // ── Constructor helpers ────────────────────────────────────────────────
    /** Build a FeedPost from an existing ReelModel. */
    public static FeedPost fromReelModel(ReelModel r) {
        FeedPost p = new FeedPost();
        p.reelId       = r.reelId;
        p.uid          = r.uid;
        p.ownerName    = r.ownerName != null ? r.ownerName : "";
        p.ownerPhotoUrl = r.ownerPhoto != null ? r.ownerPhoto : "";
        p.ownerVerified = r.isVerified;
        p.postType     = (r.mediaType != null && r.mediaType.equals("photo_slideshow"))
                         ? (r.photoUrls != null && r.photoUrls.size() > 1 ? TYPE_CAROUSEL : TYPE_PHOTO)
                         : TYPE_VIDEO;
        p.videoUrl     = r.videoUrl;
        p.thumbUrl     = r.effectiveThumbUrl();
        p.caption      = r.caption;
        p.musicName    = r.musicName;
        p.musicArtist  = r.musicArtist;
        p.musicCoverUrl = r.musicCoverUrl;
        p.photoUrls    = r.photoUrls;
        p.likesCount   = r.likesCount;
        p.commentsCount = r.commentsCount;
        p.sharesCount  = r.sharesCount;
        p.repostCount  = r.repostCount;
        p.viewsCount   = r.viewsCount;
        p.timestamp    = r.timestamp;
        p.hashtags     = r.hashtags;
        p.audienceType = r.audienceType;
        p.isRepost     = (r.repostedFromReelId != null && !r.repostedFromReelId.isEmpty());
        p.repostByName = r.repostedFromName;
        return p;
    }

    // ── Utility ────────────────────────────────────────────────────────────
    public String formatCount(long n) {
        if (n >= 1_000_000) return String.format(java.util.Locale.US, "%.1fM", n / 1_000_000f);
        if (n >= 1_000)     return String.format(java.util.Locale.US, "%.1fK", n / 1_000f);
        return String.valueOf(n);
    }

    public String formatAgo() {
        long diff = System.currentTimeMillis() - timestamp;
        long secs = diff / 1000;
        if (secs < 60)  return secs + "s";
        long mins = secs / 60;
        if (mins < 60)  return mins + "m";
        long hours = mins / 60;
        if (hours < 24) return hours + "h";
        long days = hours / 24;
        if (days < 7)   return days + "d";
        return (days / 7) + "w";
    }
}
