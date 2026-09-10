package com.callx.app.creator;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.corelite.RenderActionClient;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The creator-facing monetization journey for Reels.
 *
 * The screen is intentionally backed by the creatorMonetizationAction callable:
 * eligibility, earnings and payout state are calculated server-side, while
 * this activity only renders the result and requests state changes.
 */
public class ReelMonetizationActivity extends AppCompatActivity {
    private TextView tvPlan, tvFollowers, tvViews, tvFollowing, tvGoal, tvEligibility,
        tvBalance, tvLifetime, tvStatus, tvPayoutStatus;
    private Button btnEarn, btnPayout;
    private ProgressBar progress;
    private ScrollView scroll;
    private String uid;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_reel_monetization);
        uid = FirebaseUtils.getCurrentUid();
        bind();
        load();
    }

    @Override protected void onResume() {
        super.onResume();
        if (uid != null && tvPlan != null) load();
    }

    private void bind() {
        scroll = findViewById(R.id.scroll_monetization);
        progress = findViewById(R.id.progress_monetization);
        tvPlan = findViewById(R.id.tv_monetization_plan);
        tvFollowers = findViewById(R.id.tv_monetization_followers);
        tvViews = findViewById(R.id.tv_monetization_views);
        tvFollowing = findViewById(R.id.tv_monetization_following);
        tvGoal = findViewById(R.id.tv_monetization_goal);
        tvEligibility = findViewById(R.id.tv_monetization_eligibility);
        tvBalance = findViewById(R.id.tv_monetization_balance);
        tvLifetime = findViewById(R.id.tv_monetization_lifetime);
        tvStatus = findViewById(R.id.tv_monetization_status);
        tvPayoutStatus = findViewById(R.id.tv_monetization_payout_status);
        btnEarn = findViewById(R.id.btn_monetization_earn);
        btnPayout = findViewById(R.id.btn_monetization_payout);

        findViewById(R.id.btn_monetization_back).setOnClickListener(v -> finish());
        btnEarn.setOnClickListener(v -> toggleEnrollment());
        btnPayout.setOnClickListener(v -> requestPayout());
    }

    private void load() {
        setLoading(true);
        call("get", null, new Callback() {
            @Override public void success(Map<String, Object> data) {
                setLoading(false);
                render(data);
            }
            @Override public void error(String message) {
                setLoading(false);
                Toast.makeText(ReelMonetizationActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void render(Map<String, Object> data) {
        Map<String, Object> stats = map(data.get("stats"));
        Map<String, Object> earnings = map(data.get("earnings"));
        boolean enrolled = bool(data.get("enrolled"));
        boolean eligible = bool(data.get("eligible"));
        String plan = text(data.get("eligiblePlan"), "Not eligible yet");
        long followers = number(stats.get("followers"));
        long views = number(stats.get("totalViews"));
        long following = number(stats.get("following"));
        long goal = number(data.get("nextFollowerGoal"));

        tvPlan.setText(text(data.get("creatorLevel"), "Level 1 Creator"));
        tvFollowers.setText(format(followers));
        tvViews.setText(format(views));
        tvFollowing.setText(format(following));
        tvGoal.setText(goal > 0 ? format(goal) + " goal" : "Top level unlocked");
        tvEligibility.setText(eligible
            ? "Eligible for " + plan + " • Continue creating to unlock faster payouts."
            : text(data.get("eligibilitySummary"),
                "Keep growing your audience and complete the requirements below."));
        tvBalance.setText("$" + money(earnings.get("availableUsd")));
        tvLifetime.setText("$" + money(earnings.get("lifetimeUsd")));
        tvStatus.setText(enrolled ? "ACTIVE" : "NOT ENROLLED");
        tvStatus.setTextColor(enrolled ? 0xFF0B9F83 : 0xFF8A8A8E);
        btnEarn.setText(enrolled ? "Pause monetization" : "Earn Now");
        btnPayout.setEnabled(enrolled && number(earnings.get("availableCents")) > 0);
        tvPayoutStatus.setText(text(data.get("payoutSummary"),
            "Payouts are reviewed and released after approval."));
    }

    private void toggleEnrollment() {
        final boolean joining = "Earn Now".contentEquals(btnEarn.getText());
        new AlertDialog.Builder(this)
            .setTitle(joining ? "Start monetizing?" : "Pause monetization?")
            .setMessage(joining
                ? "Your eligible Reel views will be reviewed under the creator plan shown above."
                : "New earnings will stop until you enable monetization again. Existing earnings are not deleted.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton(joining ? "Start" : "Pause", (d, w) -> {
                Map<String, Object> p = new HashMap<>();
                p.put("enabled", joining);
                setLoading(true);
                call("setEnrollment", p, new Callback() {
                    @Override public void success(Map<String, Object> data) {
                        Toast.makeText(ReelMonetizationActivity.this,
                            joining ? "Monetization started" : "Monetization paused",
                            Toast.LENGTH_SHORT).show();
                        load();
                    }
                    @Override public void error(String message) {
                        setLoading(false);
                        Toast.makeText(ReelMonetizationActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
            }).show();
    }

    private void requestPayout() {
        new AlertDialog.Builder(this)
            .setTitle("Request payout?")
            .setMessage("Your available balance will move to review. An admin will approve and mark the payout as paid.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Request", (d, w) -> {
                setLoading(true);
                call("requestPayout", null, new Callback() {
                    @Override public void success(Map<String, Object> data) {
                        Toast.makeText(ReelMonetizationActivity.this,
                            "Payout request submitted", Toast.LENGTH_LONG).show();
                        load();
                    }
                    @Override public void error(String message) {
                        setLoading(false);
                        Toast.makeText(ReelMonetizationActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
            }).show();
    }

    private void setLoading(boolean loading) {
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnEarn.setEnabled(!loading);
        btnPayout.setEnabled(!loading);
    }

    private interface Callback {
        void success(Map<String, Object> data);
        void error(String message);
    }

    private void call(String action, Map<String, Object> payload, Callback callback) {
        Map<String, Object> request = new HashMap<>();
        request.put("action", action);
        request.put("payload", payload == null ? new HashMap<>() : payload);
        RenderActionClient.post("/creator-monetization/action", action, payload,
            new RenderActionClient.Result() {
                @Override public void onSuccess(Map<String, Object> data) {
                    callback.success(data);
                }
                @Override public void onError(String message) {
                    callback.error(message == null ? "Monetization request failed" : message);
                }
            });
    }

    private static Map<String, Object> map(Object raw) {
        return raw instanceof Map ? (Map<String, Object>) raw : new HashMap<>();
    }

    private static String text(Object value, String fallback) {
        return value == null || String.valueOf(value).trim().isEmpty()
            ? fallback : String.valueOf(value);
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean && (Boolean) value;
    }

    private static long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static String format(long value) {
        if (value >= 1_000_000) return String.format(Locale.US, "%.1fM", value / 1_000_000d);
        if (value >= 1_000) return String.format(Locale.US, "%.1fK", value / 1_000d);
        return String.valueOf(value);
    }

    private static String money(Object value) {
        return String.format(Locale.US, "%.2f", value instanceof Number
            ? ((Number) value).doubleValue() : 0d);
    }
}