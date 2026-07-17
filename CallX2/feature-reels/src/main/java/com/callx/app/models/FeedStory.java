package com.callx.app.models;

/**
 * FeedStory — represents a single story item in the home feed stories bar.
 *
 * Firebase paths (existing status system):
 *  status/{ownerUid}/{itemId}   → status item data
 *  statusSeen/{viewerUid}/{ownerUid} → seen flag
 *
 * Also used for reels users who have a "today's reel" story ring.
 */
public class FeedStory {

    public String ownerUid;
    public String ownerName;
    public String ownerHandle;
    public String ownerPhotoUrl;

    /** True if there is at least one unseen story item for this owner. */
    public boolean hasUnseen;

    /** Timestamp of the most recent story item (for sorting). */
    public long latestTimestamp;

    /** True if this is "My Story" (current user's own entry). */
    public boolean isMyStory;

    /** Number of story items from this owner. */
    public int itemCount;

    public FeedStory() {}

    public FeedStory(String ownerUid, String ownerName, String ownerPhotoUrl,
                     boolean hasUnseen, long latestTimestamp, boolean isMyStory) {
        this.ownerUid       = ownerUid;
        this.ownerName      = ownerName;
        this.ownerPhotoUrl  = ownerPhotoUrl;
        this.hasUnseen      = hasUnseen;
        this.latestTimestamp = latestTimestamp;
        this.isMyStory      = isMyStory;
    }

    /**
     * Comparator: My Story first, then unseen (latest first), then seen (latest first).
     */
    public static final java.util.Comparator<FeedStory> SORT_ORDER =
        (a, b) -> {
            if (a.isMyStory != b.isMyStory) return a.isMyStory ? -1 : 1;
            if (a.hasUnseen != b.hasUnseen) return a.hasUnseen ? -1 : 1;
            return Long.compare(b.latestTimestamp, a.latestTimestamp);
        };
}
