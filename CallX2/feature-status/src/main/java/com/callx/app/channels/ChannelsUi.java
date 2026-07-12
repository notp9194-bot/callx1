package com.callx.app.channels;

import android.widget.TextView;

/** Small shared UI helpers for rendering channels consistently across the Updates
 *  screen, the Explore sheet, and the channel viewer — no real channel artwork exists,
 *  so every surface derives the same initials-avatar + colour from the channel name. */
public final class ChannelsUi {
    private ChannelsUi() {}

    private static final int[] PALETTE = {
            0xFF25D366, 0xFF2196F3, 0xFFFF7043, 0xFF9C27B0,
            0xFF00ACC1, 0xFFEF5350, 0xFF7CB342, 0xFFFFA000
    };

    public static String initial(String name) {
        if (name == null || name.isEmpty()) return "?";
        return String.valueOf(name.trim().charAt(0)).toUpperCase();
    }

    public static int colorFor(String name) {
        int hash = name == null ? 0 : name.hashCode();
        int idx = Math.abs(hash) % PALETTE.length;
        return PALETTE[idx];
    }

    public static void applyFollowButtonStyle(TextView btn, boolean following) {
        if (following) {
            btn.setText("Following");
            btn.setBackgroundResource(com.callx.app.status.R.drawable.bg_pill_following);
            btn.setTextColor(0xFFFFFFFF);
        } else {
            btn.setText("Follow");
            btn.setBackgroundResource(com.callx.app.status.R.drawable.bg_pill_follow);
            btn.setTextColor(0xFF25D366);
        }
    }
}
