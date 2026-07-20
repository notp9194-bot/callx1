package com.callx.app.community;

import android.content.Context;

import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.util.HashMap;
import java.util.Map;

/**
 * v34: Additional CommunityRepository methods as a companion helper.
 * These methods are called by CommunityFeedFragment and CommunityPostComposerActivity
 * until they can be migrated into CommunityRepository directly.
 *
 * Usage: call static methods directly — they use Firebase RTDB.
 */
public class CommunityRepositoryV34Extensions {

    // ─── Post engagement counters ─────────────────────────────────────────────

    public static void incrementShareCount(String communityId, String postId) {
        FirebaseDatabase.getInstance().getReference("communities")
                .child(communityId).child("posts").child(postId)
                .child("shareCount").setValue(ServerValue.increment(1));
    }

    public static void incrementBookmarkCount(String communityId, String postId) {
        FirebaseDatabase.getInstance().getReference("communities")
                .child(communityId).child("posts").child(postId)
                .child("bookmarkCount").setValue(ServerValue.increment(1));
    }

    public static void incrementViewCount(String communityId, String postId) {
        FirebaseDatabase.getInstance().getReference("communities")
                .child(communityId).child("posts").child(postId)
                .child("viewCount").setValue(ServerValue.increment(1));
    }

    // ─── Community meta updates ───────────────────────────────────────────────

    /**
     * Update community info including new v34 fields: bannerUrl, rules, category.
     */
    public static void updateCommunityFull(String communityId, String name, String description,
                                           String iconUrl, boolean isPrivate,
                                           String bannerUrl, String category, String rules,
                                           CommunityRepository.Callback2<Boolean, String> cb) {
        Map<String,Object> updates = new HashMap<>();
        if (name != null)        updates.put("name", name);
        if (description != null) updates.put("description", description);
        if (iconUrl != null)     updates.put("iconUrl", iconUrl);
        updates.put("isPrivate", isPrivate);
        if (bannerUrl != null)   updates.put("bannerUrl", bannerUrl);
        if (category != null)    updates.put("category", category);
        if (rules != null)       updates.put("rules", rules);
        // Public-communities index for Discover page
        if (!isPrivate)          updates.put("isPublic", true);
        else                     updates.put("isPublic", false);

        FirebaseDatabase.getInstance().getReference("communities").child(communityId)
                .updateChildren(updates)
                .addOnSuccessListener(v -> { if (cb != null) cb.onResult(true, null); })
                .addOnFailureListener(e -> { if (cb != null) cb.onResult(false, e.getMessage()); });
    }

    // ─── Post publishing with carousel fields ─────────────────────────────────

    /**
     * Publish a community post with full v34 fields including carousel JSON arrays.
     */
    public static void publishPost(Context ctx,
                                   String communityId, String authorUid, String authorName,
                                   String authorPhoto, String text,
                                   String primaryMediaUrl, String primaryMediaType,
                                   boolean isAnnouncement,
                                   String mediaUrlsJson, String mediaTypesJson,
                                   String mentionedUids, long scheduledAt,
                                   CommunityRepository repo,
                                   CommunityRepository.Callback2<Boolean, String> cb) {
        repo.publishPost(communityId, authorUid, authorName, authorPhoto,
                text, primaryMediaUrl, primaryMediaType, isAnnouncement,
                mediaUrlsJson, mediaTypesJson,
                mentionedUids, scheduledAt, cb);
    }
}
