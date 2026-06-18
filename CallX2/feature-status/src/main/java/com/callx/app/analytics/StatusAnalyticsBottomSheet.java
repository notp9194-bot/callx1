package com.callx.app.analytics;
import android.content.Context;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.StatusAnalyticsHelper;
import java.util.Map;
/**
 * StatusAnalyticsBottomSheet v25 — Proper analytics sheet replacing plain AlertDialog.
 * Shows: total views, unique reactions, avg view duration, expiry, reaction breakdown bar.
 * FIX: Was a plain AlertDialog with raw text — now a proper BottomSheetDialog with cards.
 */
public class StatusAnalyticsBottomSheet {
    public interface OnDismissListener {
        void onDismissed();
    }
    public static void show(Context ctx, StatusItem item, OnDismissListener listener) {
        if (item == null) return;
        BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        ScrollView scroll = new ScrollView(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 20), dp(ctx, 16), dp(ctx, 20), dp(ctx, 32));
        // Title
        TextView title = new TextView(ctx);
        title.setText("Status Analytics");
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(ctx, 20));
        root.addView(title);
        StatusAnalyticsHelper.Analytics a = StatusAnalyticsHelper.compute(item, 0);
        // Stat cards row
        LinearLayout cardRow = new LinearLayout(ctx);
        cardRow.setOrientation(LinearLayout.HORIZONTAL);
        cardRow.setPadding(0, 0, 0, dp(ctx, 20));
        cardRow.setWeightSum(3);
        cardRow.addView(makeStatCard(ctx, "👁", String.valueOf(a.totalViews), "Views"));
        cardRow.addView(makeStatCard(ctx, "💬", String.valueOf(a.totalReactions), "Reactions"));
        cardRow.addView(makeStatCard(ctx, "⏱", String.format("%.1fs", a.avgViewDurationSec), "Avg View"));
        root.addView(cardRow);
        // Expiry row
        TextView expiry = new TextView(ctx);
        expiry.setText("⏳ " + (item.expiresAt > 0 ? new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(new java.util.Date(item.expiresAt)) : "N/A"));
        expiry.setTextSize(14);
        expiry.setTextColor(Color.parseColor("#888888"));
        expiry.setPadding(0, 0, 0, dp(ctx, 16));
        root.addView(expiry);
        // Reaction breakdown
        if (!a.reactionBreakdown.isEmpty()) {
            TextView breakdownTitle = new TextView(ctx);
            breakdownTitle.setText("Reaction breakdown");
            breakdownTitle.setTextSize(15);
            breakdownTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            breakdownTitle.setPadding(0, 0, 0, dp(ctx, 10));
            root.addView(breakdownTitle);
            for (Map.Entry<String, Integer> e : a.reactionBreakdown.entrySet()) {
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(ctx, 6), 0, dp(ctx, 6));
                TextView emoji = new TextView(ctx);
                emoji.setText(e.getKey());
                emoji.setTextSize(22);
                emoji.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, 40),
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                row.addView(emoji);
                // Progress bar fill
                int percent = a.totalReactions > 0
                        ? (int) (e.getValue() * 100f / a.totalReactions) : 0;
                ProgressBar bar = new ProgressBar(ctx, null,
                        android.R.attr.progressBarStyleHorizontal);
                bar.setMax(100);
                bar.setProgress(percent);
                LinearLayout.LayoutParams barlp = new LinearLayout.LayoutParams(
                        0, dp(ctx, 14), 1f);
                barlp.setMarginStart(dp(ctx, 8));
                barlp.setMarginEnd(dp(ctx, 8));
                bar.setLayoutParams(barlp);
                bar.getProgressDrawable().setColorFilter(
                        Color.parseColor("#6200EE"), android.graphics.PorterDuff.Mode.SRC_IN);
                row.addView(bar);
                TextView count = new TextView(ctx);
                count.setText(e.getValue() + " (" + percent + "%)");
                count.setTextSize(13);
                count.setTextColor(Color.GRAY);
                row.addView(count);
                root.addView(row);
            }
        } else {
            TextView noReactions = new TextView(ctx);
            noReactions.setText("No reactions yet");
            noReactions.setTextSize(14);
            noReactions.setTextColor(Color.GRAY);
            root.addView(noReactions);
        }
        // Close button
        Button closeBtn = new Button(ctx);
        closeBtn.setText("Close");
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        closeLp.topMargin = dp(ctx, 24);
        closeBtn.setLayoutParams(closeLp);
        closeBtn.setOnClickListener(v -> sheet.dismiss());
        root.addView(closeBtn);
        scroll.addView(root);
        sheet.setContentView(scroll);
        sheet.setOnDismissListener(d -> { if (listener != null) listener.onDismissed(); });
        sheet.show();
    }
    private static LinearLayout makeStatCard(Context ctx, String icon, String value, String label) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(android.view.Gravity.CENTER);
        card.setPadding(dp(ctx, 8), dp(ctx, 16), dp(ctx, 8), dp(ctx, 16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(dp(ctx, 8));
        card.setLayoutParams(lp);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(ctx, 12));
        bg.setColor(Color.parseColor("#F5F0FF"));
        card.setBackground(bg);
        TextView tvIcon = new TextView(ctx);
        tvIcon.setText(icon);
        tvIcon.setTextSize(24);
        tvIcon.setGravity(android.view.Gravity.CENTER);
        card.addView(tvIcon);
        TextView tvValue = new TextView(ctx);
        tvValue.setText(value);
        tvValue.setTextSize(20);
        tvValue.setTypeface(null, android.graphics.Typeface.BOLD);
        tvValue.setGravity(android.view.Gravity.CENTER);
        tvValue.setTextColor(Color.parseColor("#6200EE"));
        card.addView(tvValue);
        TextView tvLabel = new TextView(ctx);
        tvLabel.setText(label);
        tvLabel.setTextSize(11);
        tvLabel.setGravity(android.view.Gravity.CENTER);
        tvLabel.setTextColor(Color.GRAY);
        card.addView(tvLabel);
        return card;
    }
    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}