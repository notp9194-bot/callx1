package com.callx.app.creator;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.callx.app.reels.R;
import com.google.firebase.functions.FirebaseFunctions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Creator-facing Level 1–8 milestone journey.
 *
 * Counts and reward crediting are returned by milestoneEarningsAction. The
 * client never writes balances, completed levels, or payout states directly.
 */
public class MilestoneEarningsActivity extends AppCompatActivity {
    private static final int INK = Color.rgb(28, 28, 30);
    private static final int MUTED = Color.rgb(105, 105, 110);
    private static final int BRAND = Color.rgb(153, 82, 22);
    private static final int PALE_BLUE = Color.rgb(231, 243, 255);
    private static final int PALE_RED = Color.rgb(255, 220, 218);

    private LinearLayout content;
    private TextView levelBadge, readyText, summaryTitle, activeTitle, availableText, pendingText, withdrawText;
    private TextView rewardText, overallText, howToText;
    private ProgressBar overallProgress;
    private LinearLayout metricRows, roadmap;
    private Button withdrawButton;
    private boolean withdrawalsUnlocked;
    private Map<String, Object> latest = new HashMap<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildScreen());
        load();
    }

    @Override protected void onResume() {
        super.onResume();
        if (content != null) load();
    }

    private View buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = column();
        content.setPadding(dp(16), 0, dp(16), dp(28));
        scroll.addView(content);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(0, dp(8), 0, dp(8));
        TextView back = text("‹", 38, INK, false);
        back.setGravity(Gravity.CENTER);
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(42), dp(56)));
        back.setOnClickListener(v -> finish());
        TextView title = text("Milestone Earnings", 20, INK, true);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(56), 1));
        content.addView(toolbar);

        LinearLayout summary = card(Color.rgb(224, 224, 224), 18);
        LinearLayout summaryTop = row();
        levelBadge = text("L1", 15, INK, true);
        levelBadge.setGravity(Gravity.CENTER);
        levelBadge.setBackground(round(Color.rgb(190, 190, 192), 100));
        summaryTop.addView(levelBadge, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout summaryCopy = column();
        summaryCopy.setPadding(dp(12), 0, 0, 0);
        readyText = text("Ready to Earn", 12, MUTED, false);
        summaryCopy.addView(readyText);
        summaryTitle = text("Complete milestones", 20, INK, true);
        summaryCopy.addView(summaryTitle);
        summaryTop.addView(summaryCopy, new LinearLayout.LayoutParams(0, -2, 1));
        summary.addView(summaryTop);
        summary.addView(separator());
        LinearLayout balances = row();
        balances.addView(balanceBlock("Available", availableText = text("Processing", 14, INK, false)),
            new LinearLayout.LayoutParams(0, -2, 1));
        balances.addView(balanceBlock("Pending", pendingText = text("No requests", 14, INK, false)),
            new LinearLayout.LayoutParams(0, -2, 1));
        summary.addView(balances);
        withdrawText = text("🔒  Withdrawals unlock at Level 3", 13, INK, true);
        withdrawText.setPadding(dp(14), dp(12), dp(14), dp(12));
        withdrawText.setBackground(round(Color.rgb(203, 203, 203), 14));
        summary.addView(withdrawText);
        LinearLayout actions = row();
        withdrawButton = button("Unlocks at L3");
        actions.addView(withdrawButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button history = button("◷  History");
        actions.addView(history, new LinearLayout.LayoutParams(0, dp(48), 1));
        history.setOnClickListener(v -> showHistory());
        summary.addView(actions);
        content.addView(summary);

        content.addView(gap(16));
        content.addView(text("Level progress", 16, INK, true));
        LinearLayout progressCard = card(Color.WHITE, 16);
        LinearLayout progressHeader = row();
        progressHeader.addView(activeTitle = text("Level 1 → Level 2", 16, INK, true),
            new LinearLayout.LayoutParams(0, -2, 1));
        progressHeader.addView(rewardText = text("₹0 reward", 12, BRAND, true));
        progressCard.addView(progressHeader);
        progressCard.addView(overallText = text("Overall: 0%", 12, INK, false));
        overallProgress = progressBar();
        progressCard.addView(overallProgress);
        metricRows = column();
        progressCard.addView(metricRows);
        content.addView(progressCard);

        content.addView(gap(16));
        LinearLayout how = card(PALE_BLUE, 16);
        how.addView(text("ⓘ  How to Progress", 14, Color.rgb(31, 82, 133), true));
        howToText = text("", 12, Color.rgb(31, 82, 133), false);
        howToText.setPadding(0, dp(8), 0, 0);
        how.addView(howToText);
        content.addView(how);

        content.addView(gap(16));
        content.addView(text("Level Roadmap", 16, INK, true));
        roadmap = column();
        content.addView(roadmap);

        LinearLayout policy = card(PALE_RED, 16);
        policy.addView(text("⚠  Important: Milestone Rules & Fraud Policy", 14, INK, true));
        policy.addView(text(
            "\n📤 SHARE REQUIREMENTS:\n" +
            "✓ Only WhatsApp shares count\n" +
            "• In-app shares do NOT count\n" +
            "• Copy link does NOT count\n" +
            "• Instagram/Facebook/Twitter do NOT count\n\n" +
            "❤️ LIKES POLICY:\n" +
            "⚠ Like and Unlike = Rewards CANCELLED\n" +
            "• If you unlike after earning rewards, rewards may be reversed\n" +
            "• Repeated like/unlike patterns are flagged as fraud\n" +
            "• Only genuine likes count towards milestones\n\n" +
            "👥 FOLLOWING POLICY:\n" +
            "⚠ Follow and Unfollow = Rewards CANCELLED\n" +
            "• If you unfollow after earning rewards, rewards may be reversed",
            12, INK, false));
        content.addView(policy);
        content.addView(gap(12));
        cardText(Color.WHITE, "Disclaimer\nTerms and conditions apply. This feature is for a limited time only.\n\nAny fraudulent, automated, or bot-like activity may lead to account suspension or permanent ban. In such cases, earnings may be forfeited and payouts may be withheld as per platform policy.");
        return scroll;
    }

    private void load() {
        call("get", null, new Callback() {
            @Override public void success(Map<String, Object> data) {
                latest = data;
                render(data);
            }
            @Override public void error(String message) {
                Toast.makeText(MilestoneEarningsActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void render(Map<String, Object> data) {
        Map<String, Object> stats = map(data.get("stats"));
        Map<String, Object> active = map(data.get("activeLevel"));
        Map<String, Object> percents = map(data.get("metricPercents"));
        int current = (int) number(data.get("currentLevel"));
        int highest = (int) number(data.get("highestCompletedLevel"));
        withdrawalsUnlocked = bool(data.get("withdrawalsUnlocked"));
        levelBadge.setText("L" + Math.max(1, current));
        readyText.setText(highest > 0 ? "Keep going to unlock the next reward" : "Ready to Earn");
        summaryTitle.setText(highest > 0 ? "Keep going to Level " + Math.min(8, current)
            : "Complete milestones");
        activeTitle.setText("Level " + current + " → Level " + Math.min(8, current + 1));
        rewardText.setText("₹" + rupees(active.get("rewardPaise")) + " reward");
        overallText.setText("Overall: " + number(data.get("overallPercent")) + "%");
        overallProgress.setProgress((int) number(data.get("overallPercent")));
        availableText.setText("₹" + rupees(data.get("availablePaise")));
        pendingText.setText(number(data.get("pendingPaise")) > 0
            ? "₹" + rupees(data.get("pendingPaise")) : "No requests");
        withdrawText.setText(withdrawalsUnlocked
            ? "✓  Withdrawals unlocked at Level 3"
            : "🔒  Withdrawals unlock at Level 3");
        withdrawButton.setText(withdrawalsUnlocked ? "Withdraw" : "Unlocks at L3");
        withdrawButton.setEnabled(withdrawalsUnlocked && number(data.get("availablePaise")) > 0
            && number(data.get("pendingPaise")) == 0);
        withdrawButton.setOnClickListener(v -> requestPayout());

        metricRows.removeAllViews();
        addMetric(metricRows, "♥", "Unique Likes", number(stats.get("uniqueLikes")),
            number(active.get("likes")), number(percents.get("likes")), Color.rgb(235, 87, 87));
        addMetric(metricRows, "＋", "Following", number(stats.get("following")),
            number(active.get("following")), number(percents.get("following")), Color.rgb(117, 76, 220));
        addMetric(metricRows, "↗", "WhatsApp Shares", number(stats.get("whatsappShares")),
            number(active.get("shares")), number(percents.get("shares")), Color.rgb(227, 153, 31));
        howToText.setText("Likes: You completed " + number(stats.get("uniqueLikes"))
            + " likes for this level. Like " + Math.max(0, number(active.get("likes"))
            - number(stats.get("uniqueLikes"))) + " more reels to complete Level " + current + ".\n\n"
            + "Following: You completed " + number(stats.get("following"))
            + " follows for this level. Follow " + Math.max(0, number(active.get("following"))
            - number(stats.get("following"))) + " more users to complete Level " + current + ".\n\n"
            + "Shares: You completed " + number(stats.get("whatsappShares"))
            + " shares for this level. Share " + Math.max(0, number(active.get("shares"))
            - number(stats.get("whatsappShares"))) + " more times on WhatsApp.");

        roadmap.removeAllViews();
        List<?> levels = data.get("levels") instanceof List ? (List<?>) data.get("levels")
            : new ArrayList<>();
        for (Object raw : levels) {
            Map<String, Object> level = map(raw);
            int levelNo = (int) number(level.get("level"));
            boolean complete = levelNo <= highest;
            LinearLayout item = card(complete ? Color.rgb(237, 237, 237) : Color.WHITE, 14);
            LinearLayout line = row();
            TextView dot = text(complete ? "●" : "○", 22, complete ? INK : MUTED, false);
            line.addView(dot, new LinearLayout.LayoutParams(dp(36), -2));
            LinearLayout copy = column();
            copy.addView(text("Level " + levelNo, 14, INK, true));
            copy.addView(text(number(level.get("likes")) + " likes • "
                + number(level.get("following")) + " following • "
                + number(level.get("shares")) + " shares", 12, MUTED, false));
            line.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(text("₹" + rupees(level.get("rewardPaise")), 14, BRAND, true));
            item.addView(line);
            roadmap.addView(item);
        }
    }

    private void addMetric(LinearLayout parent, String icon, String label, long value,
                           long target, long percent, int color) {
        LinearLayout block = column();
        block.setPadding(0, dp(10), 0, dp(4));
        LinearLayout line = row();
        line.addView(text(icon, 20, color, true), new LinearLayout.LayoutParams(dp(42), -2));
        LinearLayout copy = column();
        copy.addView(text(label, 12, INK, false));
        copy.addView(text(value + " / " + target, 12, color, true));
        line.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        line.addView(text(percent + "%", 11, color, false));
        block.addView(line);
        ProgressBar bar = progressBar();
        bar.setProgress((int) Math.min(100, percent));
        block.addView(bar);
        parent.addView(block);
    }

    private void requestPayout() {
        new AlertDialog.Builder(this)
            .setTitle("Request withdrawal?")
            .setMessage("Your available milestone balance will move to admin review. Rejected requests restore the balance.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Request", (d, which) -> call("requestPayout", null,
                new Callback() {
                    @Override public void success(Map<String, Object> data) {
                        Toast.makeText(MilestoneEarningsActivity.this,
                            "Withdrawal request submitted", Toast.LENGTH_LONG).show();
                        load();
                    }
                    @Override public void error(String message) {
                        Toast.makeText(MilestoneEarningsActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                })).show();
    }

    private void showHistory() {
        List<?> rows = latest.get("history") instanceof List ? (List<?>) latest.get("history")
            : new ArrayList<>();
        if (rows.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Earnings history")
                .setMessage("No milestone rewards or withdrawals yet.")
                .setPositiveButton("Close", null).show();
            return;
        }
        StringBuilder message = new StringBuilder();
        for (Object raw : rows) {
            Map<String, Object> item = map(raw);
            String title = "milestone_reward".equals(text(item.get("type"), ""))
                ? "Level " + number(item.get("level")) + " reward"
                : "Withdrawal";
            message.append(title).append("  ₹").append(rupees(item.get("amountPaise")))
                .append("  • ").append(text(item.get("status"), "credited")).append('\n');
        }
        new AlertDialog.Builder(this).setTitle("Earnings history")
            .setMessage(message.toString()).setPositiveButton("Close", null).show();
    }

    private void call(String action, Map<String, Object> payload, Callback callback) {
        Map<String, Object> request = new HashMap<>();
        request.put("action", action);
        request.put("payload", payload == null ? new HashMap<>() : payload);
        FirebaseFunctions.getInstance().getHttpsCallable("milestoneEarningsAction")
            .call(request)
            .addOnSuccessListener(result -> callback.success(map(result == null ? null : result.getData())))
            .addOnFailureListener(error -> callback.error(error.getMessage() == null
                ? "Milestone request failed" : error.getMessage()));
    }

    private interface Callback {
        void success(Map<String, Object> data);
        void error(String message);
    }

    private LinearLayout balanceBlock(String label, TextView value) {
        LinearLayout block = column();
        block.addView(text(label, 11, MUTED, false));
        block.addView(value);
        return block;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return view;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13);
        b.setAllCaps(false);
        return b;
    }

    private ProgressBar progressBar() {
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(40, 150, 215)));
        bar.setPadding(0, dp(4), 0, dp(4));
        return bar;
    }

    private LinearLayout row() {
        LinearLayout out = new LinearLayout(this);
        out.setOrientation(LinearLayout.HORIZONTAL);
        out.setGravity(Gravity.CENTER_VERTICAL);
        return out;
    }

    private LinearLayout column() {
        LinearLayout out = new LinearLayout(this);
        out.setOrientation(LinearLayout.VERTICAL);
        return out;
    }

    private LinearLayout card(int color, int radius) {
        LinearLayout out = column();
        out.setPadding(dp(16), dp(14), dp(16), dp(14));
        out.setBackground(round(color, radius));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(6), 0, dp(6));
        out.setLayoutParams(lp);
        return out;
    }

    private TextView cardText(int color, String value) {
        LinearLayout card = card(color, 14);
        TextView view = text(value, 12, INK, false);
        card.addView(view);
        content.addView(card);
        return view;
    }

    private View separator() {
        View v = new View(this);
        v.setBackgroundColor(Color.rgb(200, 200, 200));
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
        return v;
    }

    private View gap(int height) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(height)));
        return v;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new HashMap<>();
    }

    private static long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean && (Boolean) value;
    }

    private static String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static String rupees(Object paise) {
        return String.format(Locale.US, "%.2f", number(paise) / 100d);
    }
}