package com.callx.app.utils;

import com.google.firebase.database.FirebaseDatabase;
import java.util.HashMap;
import java.util.Map;

/**
 * ViralRepostBadgeHelper — awards viral badges when reposts hit milestones.
 *
 * Badges:
 *   ⭐ Hot     — 100  reposts
 *   🚀 Trending— 500  reposts
 *   🔥 Viral   — 1000 reposts
 *   💎 Legend  — 5000 reposts
 *
 * Called from RepostManager after each repost.
 * Writes badge to reels/{reelId}/viralBadge for display in feed.
 */
public class ViralRepostBadgeHelper {

    public static final long MILESTONE_HOT      = 100L;
    public static final long MILESTONE_TRENDING  = 500L;
    public static final long MILESTONE_VIRAL     = 1000L;
    public static final long MILESTONE_LEGEND    = 5000L;

    public static void checkAndAward(String reelId, String ownerUid, long repostCount) {
        String badge = getBadgeForCount(repostCount);
        if (badge == null) return;

        // Write badge to reel
        FirebaseDatabase.getInstance().getReference("reels")
            .child(reelId).child("viralBadge").setValue(badge);

        // Notify owner of milestone
        sendMilestoneNotification(reelId, ownerUid, repostCount, badge);
    }

    public static String getBadgeForCount(long count) {
        if      (count >= MILESTONE_LEGEND)   return "legend";
        else if (count >= MILESTONE_VIRAL)    return "viral";
        else if (count >= MILESTONE_TRENDING) return "trending";
        else if (count >= MILESTONE_HOT)      return "hot";
        return null;
    }

    public static String getBadgeEmoji(String badge) {
        if (badge == null) return "";
        switch (badge) {
            case "legend":   return "💎";
            case "viral":    return "🔥";
            case "trending": return "🚀";
            case "hot":      return "⭐";
            default:         return "";
        }
    }

    public static String getBadgeLabel(String badge) {
        if (badge == null) return "";
        switch (badge) {
            case "legend":   return "💎 Legend";
            case "viral":    return "🔥 Viral";
            case "trending": return "🚀 Trending";
            case "hot":      return "⭐ Hot";
            default:         return "";
        }
    }

    public static boolean isMilestone(long count) {
        return count == MILESTONE_HOT || count == MILESTONE_TRENDING
            || count == MILESTONE_VIRAL || count == MILESTONE_LEGEND;
    }

    private static void sendMilestoneNotification(String reelId, String ownerUid,
                                                   long count, String badge) {
        String notifKey = FirebaseDatabase.getInstance()
            .getReference("reel_notifications").child(ownerUid).push().getKey();
        if (notifKey == null) return;

        Map<String, Object> n = new HashMap<>();
        n.put("type",       "repost_milestone");
        n.put("reel_id",    reelId);
        n.put("badge",      badge);
        n.put("count",      count);
        n.put("timestamp",  System.currentTimeMillis());
        n.put("read",       false);
        n.put("senderUid",  "system");
        n.put("senderName", "CallX");

        FirebaseDatabase.getInstance().getReference("reel_notifications")
            .child(ownerUid).child(notifKey).updateChildren(n);
    }
}
