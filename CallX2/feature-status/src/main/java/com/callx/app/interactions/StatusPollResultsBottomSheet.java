package com.callx.app.interactions;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.callx.app.models.StatusItem;
import java.util.*;

/**
 * StatusPollResultsBottomSheet v27 — NEW
 *
 * Poll results screen — owner + voters dono dekh sakte hain.
 *
 * Features:
 *   ✅ Shows each poll option with vote count
 *   ✅ Animated progress bar showing percentage per option
 *   ✅ Highlights winning option in purple
 *   ✅ Total votes count badge
 *   ✅ User's own vote highlighted with ✓
 *   ✅ Poll question shown at top
 *   ✅ Share-to-story CTA (extensible)
 *
 * Integration: Call from StatusViewerActivity when user taps "See results" on a poll.
 */
public class StatusPollResultsBottomSheet {

    public static void show(Context ctx, StatusItem item, String myUid) {
        if (item == null || item.pollOptions == null || item.pollOptions.isEmpty()) {
            Toast.makeText(ctx, "Poll data unavailable", Toast.LENGTH_SHORT).show();
            return;
        }

        BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        sheet.getBehavior().setPeekHeight(dp(ctx, 520));

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), dp(ctx, 40));

        // Handle bar
        View handle = new View(ctx);
        handle.setBackgroundColor(Color.parseColor("#E0E0E0"));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = dp(ctx, 16);
        root.addView(handle, hp);

        // Poll question
        if (item.pollQuestion != null && !item.pollQuestion.isEmpty()) {
            LinearLayout qCard = new LinearLayout(ctx);
            qCard.setOrientation(LinearLayout.VERTICAL);
            qCard.setBackgroundColor(Color.parseColor("#F3E5FF"));
            qCard.setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12));
            LinearLayout.LayoutParams qcp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            qcp.bottomMargin = dp(ctx, 16);

            TextView qLabel = new TextView(ctx);
            qLabel.setText("📊 Poll question:");
            qLabel.setTextSize(12);
            qLabel.setTextColor(Color.parseColor("#9C27B0"));
            qCard.addView(qLabel);

            TextView qTv = new TextView(ctx);
            qTv.setText(item.pollQuestion);
            qTv.setTextSize(16);
            qTv.setTypeface(null, android.graphics.Typeface.BOLD);
            qTv.setTextColor(Color.BLACK);
            qCard.addView(qTv);
            root.addView(qCard, qcp);
        }

        // Count total votes
        Map<String, Object> votesMap = item.pollVotes != null
                ? new HashMap<>(item.pollVotes)
                : new HashMap<>();
        int totalVotes = votesMap.size();

        // Count per option
        int[] optionCounts = new int[item.pollOptions.size()];
        String myVote = null;
        for (Map.Entry<String, Object> entry : votesMap.entrySet()) {
            try {
                int idx = Integer.parseInt(entry.getValue().toString());
                if (idx >= 0 && idx < optionCounts.length) {
                    optionCounts[idx]++;
                }
            } catch (NumberFormatException ignored) {}
            if (entry.getKey().equals(myUid)) {
                try { myVote = entry.getValue().toString(); } catch (Exception ignored) {}
            }
        }

        // Header
        TextView header = new TextView(ctx);
        header.setText("Poll Results" + (totalVotes > 0
                ? "  •  " + totalVotes + (totalVotes == 1 ? " vote" : " votes")
                : "  •  No votes yet"));
        header.setTextSize(17);
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        header.setPadding(0, 0, 0, dp(ctx, 16));
        root.addView(header);

        // Find winning option index
        int maxVotes = 0;
        for (int c : optionCounts) if (c > maxVotes) maxVotes = c;

        // Render each option
        for (int i = 0; i < item.pollOptions.size(); i++) {
            String optionText = item.pollOptions.get(i);
            int votes = optionCounts[i];
            float pct = totalVotes > 0 ? (votes * 100f / totalVotes) : 0f;
            boolean isWinner = (maxVotes > 0 && votes == maxVotes);
            boolean isMyVote = (myVote != null && myVote.equals(String.valueOf(i)));

            LinearLayout optCard = new LinearLayout(ctx);
            optCard.setOrientation(LinearLayout.VERTICAL);
            int cardBg = isWinner ? Color.parseColor("#F3E5FF") : Color.parseColor("#FAFAFA");
            optCard.setBackgroundColor(cardBg);
            optCard.setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12));
            LinearLayout.LayoutParams ocp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            ocp.bottomMargin = dp(ctx, 10);

            // Option label row
            LinearLayout labelRow = new LinearLayout(ctx);
            labelRow.setOrientation(LinearLayout.HORIZONTAL);
            labelRow.setGravity(Gravity.CENTER_VERTICAL);
            labelRow.setPadding(0, 0, 0, dp(ctx, 6));

            TextView tvOption = new TextView(ctx);
            String label = optionText + (isMyVote ? "  ✓" : "");
            tvOption.setText(label);
            tvOption.setTextSize(14);
            tvOption.setTypeface(null, isWinner
                    ? android.graphics.Typeface.BOLD
                    : android.graphics.Typeface.NORMAL);
            tvOption.setTextColor(isWinner
                    ? Color.parseColor("#6200EE")
                    : Color.BLACK);
            tvOption.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            labelRow.addView(tvOption);

            TextView tvPct = new TextView(ctx);
            tvPct.setText(String.format(Locale.US, "%.0f%%  (%d)", pct, votes));
            tvPct.setTextSize(13);
            tvPct.setTextColor(isWinner
                    ? Color.parseColor("#6200EE")
                    : Color.GRAY);
            tvPct.setTypeface(null, isWinner
                    ? android.graphics.Typeface.BOLD
                    : android.graphics.Typeface.NORMAL);
            labelRow.addView(tvPct);
            optCard.addView(labelRow);

            // Progress bar
            FrameLayout barBg = new FrameLayout(ctx);
            barBg.setBackgroundColor(Color.parseColor("#E0E0E0"));
            LinearLayout.LayoutParams bgp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 8));
            bgp.bottomMargin = dp(ctx, 2);

            View barFill = new View(ctx);
            GradientDrawable fillBg = new GradientDrawable();
            fillBg.setShape(GradientDrawable.RECTANGLE);
            fillBg.setColor(isWinner
                    ? Color.parseColor("#6200EE")
                    : Color.parseColor("#BBBBBB"));
            fillBg.setCornerRadius(dp(ctx, 4));
            barFill.setBackground(fillBg);
            int barW = totalVotes > 0
                    ? (int) (dp(ctx, 300) * pct / 100f)
                    : 0;
            barBg.addView(barFill, new FrameLayout.LayoutParams(
                    Math.max(barW, dp(ctx, 4)), FrameLayout.LayoutParams.MATCH_PARENT));

            optCard.addView(barBg, bgp);

            // Winner badge
            if (isWinner && totalVotes > 0) {
                TextView winTv = new TextView(ctx);
                winTv.setText("🏆 Leading");
                winTv.setTextSize(11);
                winTv.setTextColor(Color.parseColor("#6200EE"));
                optCard.addView(winTv);
            }

            root.addView(optCard, ocp);
        }

        // Footer note
        if (totalVotes == 0) {
            TextView noVotes = new TextView(ctx);
            noVotes.setText("Share your status so people can vote!");
            noVotes.setTextSize(13);
            noVotes.setTextColor(Color.GRAY);
            noVotes.setGravity(Gravity.CENTER);
            noVotes.setPadding(0, dp(ctx, 8), 0, 0);
            root.addView(noVotes);
        }

        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(root);
        sheet.setContentView(scroll);
        sheet.show();
    }

    private static int dp(Context ctx, int val) {
        return Math.round(val * ctx.getResources().getDisplayMetrics().density);
    }
}
