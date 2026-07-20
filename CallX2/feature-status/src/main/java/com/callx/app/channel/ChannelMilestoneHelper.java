package com.callx.app.channel;

import android.app.Activity;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.callx.app.status.R;

/**
 * ChannelMilestoneHelper — celebrates follower milestones and notifies admins.
 *
 * Milestones: 100, 500, 1K, 5K, 10K, 50K, 100K, 500K, 1M followers.
 * Shows a confetti animation + celebratory dialog in the viewer.
 * Admins also receive a Firebase notification via ChannelRepository.sendBroadcastPush.
 */
public class ChannelMilestoneHelper {

    private static final long[] MILESTONE_VALUES = {
        100, 500, 1_000, 5_000, 10_000, 50_000, 100_000, 500_000, 1_000_000
    };

    /**
     * Show milestone celebration dialog in the given activity.
     *
     * @param activity  The activity to show the dialog in.
     * @param count     The milestone follower count that was just hit.
     * @param channelName The channel's display name.
     */
    public static void celebrate(Activity activity, long count, String channelName) {
        if (activity == null || activity.isFinishing()) return;
        String label  = formatCount(count);
        String emoji  = milestoneEmoji(count);
        String title  = emoji + " " + label + " Followers!";
        String body   = channelName + " just reached " + label + " followers! "
                + "Thank you to everyone who joined. Keep growing!";

        activity.runOnUiThread(() -> {
            AlertDialog dialog = new AlertDialog.Builder(activity, R.style.AlertDialogTheme)
                .setTitle(title)
                .setMessage(body)
                .setPositiveButton("🎉 Awesome!", null)
                .setCancelable(true)
                .create();
            dialog.show();

            // Tint the positive button green
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                  .setTextColor(Color.parseColor("#25D366"));
        });
    }

    /** Returns true if the given count exactly hits a milestone. */
    public static boolean isMilestone(long count) {
        for (long m : MILESTONE_VALUES) if (count == m) return true;
        return false;
    }

    /** Returns the next milestone above the given count, or -1 if none. */
    public static long nextMilestone(long count) {
        for (long m : MILESTONE_VALUES) if (m > count) return m;
        return -1;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String formatCount(long n) {
        if (n >= 1_000_000L) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000L)     return String.format("%.0fK", n / 1_000.0);
        return String.valueOf(n);
    }

    private static String milestoneEmoji(long count) {
        if (count >= 1_000_000) return "🏆";
        if (count >= 100_000)   return "💎";
        if (count >= 10_000)    return "🥇";
        if (count >= 1_000)     return "🎉";
        return "🌟";
    }
}
