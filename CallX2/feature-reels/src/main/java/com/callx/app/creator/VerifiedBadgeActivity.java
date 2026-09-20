package com.callx.app.creator;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.callx.app.corelite.RenderActionClient;
import com.callx.app.reels.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Single entry point for both "Get Verification Badge" and the old
 * "Star Talent" request. A request contains one tier and one duration; the
 * server owns the catalog, validates the selection, and writes the request.
 */
public class VerifiedBadgeActivity extends AppCompatActivity {
    private static final int INK = Color.rgb(25, 25, 28);
    private static final int MUTED = Color.rgb(105, 105, 110);
    private static final int BLUE = Color.rgb(45, 132, 224);
    private static final int GOLD = Color.rgb(206, 145, 20);
    private static final int PLATINUM = Color.rgb(126, 103, 214);

    private final List<Map<String, Object>> plans = new ArrayList<>();
    private LinearLayout tierStrip;
    private LinearLayout periodStrip;
    private TextView statusText;
    private Button requestButton;
    private EditText reasonInput;
    private String selectedTier = "star";
    private String selectedPeriod = "monthly";
    private String requestStatus = "";
    private boolean verified;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        seedPlans();
        setContentView(buildScreen());
        load();
    }

    private void seedPlans() {
        plans.clear();
        addDefaults("star", "Star", 195, 395, 795, 1395);
        addDefaults("gold", "Gold", 299, 599, 1199, 2099);
        addDefaults("platinum", "Platinum", 499, 999, 1999, 3499);
    }

    private void addDefaults(String tier, String name, int monthly, int quarterly,
                             int halfYear, int yearly) {
        plans.add(plan(tier + "_monthly", tier, name, "monthly", monthly, "Monthly"));
        plans.add(plan(tier + "_quarterly", tier, name, "quarterly", quarterly, "3 Months"));
        plans.add(plan(tier + "_half_year", tier, name, "half_year", halfYear, "6 Months"));
        plans.add(plan(tier + "_yearly", tier, name, "yearly", yearly, "Yearly"));
    }

    private Map<String, Object> plan(String key, String tierKey, String tierName,
                                     String periodKey, int price, String period) {
        Map<String, Object> out = new HashMap<>();
        out.put("key", key);
        out.put("tierKey", tierKey);
        out.put("tierName", tierName);
        out.put("periodKey", periodKey);
        out.put("priceRupees", price);
        out.put("period", period);
        List<String> benefits = new ArrayList<>();
        benefits.add(tierName + " badge on your profile");
        benefits.add("Verified checkmark beside your name");
        benefits.add(tierKey.equals("platinum") ? "Featured creator placement"
            : tierKey.equals("gold") ? "Enhanced creator discovery" : "Priority creator support");
        out.put("benefits", benefits);
        return out;
    }

    private View buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = column();
        root.setPadding(dp(16), 0, dp(16), dp(28));
        scroll.addView(root);

        LinearLayout toolbar = row();
        TextView back = text("‹", 38, INK, false);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(42), dp(56)));
        toolbar.addView(text("Verified Badge", 20, INK, true),
            new LinearLayout.LayoutParams(0, dp(56), 1));
        root.addView(toolbar);

        LinearLayout intro = column();
        intro.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView badge = text("✓", 34, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(round(BLUE, 100));
        intro.addView(badge, new LinearLayout.LayoutParams(dp(76), dp(76)));
        intro.addView(text("One badge. Three creator tiers.", 18, INK, true));
        intro.addView(text("Choose your tier and duration. Approval adds the checkmark and tier badge together.",
            13, MUTED, false));
        root.addView(intro);

        statusText = text("Choose a tier and duration.", 13, INK, false);
        statusText.setPadding(0, dp(18), 0, dp(8));
        root.addView(statusText);

        root.addView(text("1. Choose a tier", 16, INK, true));
        HorizontalScrollView tierScroll = new HorizontalScrollView(this);
        tierScroll.setHorizontalScrollBarEnabled(false);
        tierStrip = row();
        tierScroll.addView(tierStrip);
        root.addView(tierScroll);

        TextView periodTitle = text("2. Choose duration", 16, INK, true);
        periodTitle.setPadding(0, dp(16), 0, dp(6));
        root.addView(periodTitle);
        HorizontalScrollView periodScroll = new HorizontalScrollView(this);
        periodScroll.setHorizontalScrollBarEnabled(false);
        periodStrip = row();
        periodScroll.addView(periodStrip);
        root.addView(periodScroll);

        TextView reasonTitle = text("Why verify? (optional)", 16, INK, true);
        reasonTitle.setPadding(0, dp(18), 0, dp(6));
        root.addView(reasonTitle);
        reasonInput = new EditText(this);
        reasonInput.setHint("Tell the CallX team about your work or identity");
        reasonInput.setTextSize(13);
        reasonInput.setMinLines(3);
        reasonInput.setGravity(Gravity.TOP);
        reasonInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        reasonInput.setBackground(round(Color.rgb(248, 248, 250), 12));
        root.addView(reasonInput, new LinearLayout.LayoutParams(-1, dp(96)));

        requestButton = button("Request verification");
        requestButton.setTextColor(Color.WHITE);
        requestButton.setBackground(round(BLUE, 28));
        requestButton.setOnClickListener(v -> submit());
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(-1, dp(52));
        actionLp.setMargins(0, dp(18), 0, 0);
        root.addView(requestButton, actionLp);

        renderTiers();
        renderPeriods();
        return scroll;
    }

    private void renderTiers() {
        if (tierStrip == null) return;
        tierStrip.removeAllViews();
        String[] keys = {"star", "gold", "platinum"};
        String[] names = {"STAR", "GOLD", "PLATINUM"};
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            boolean selected = selectedTier.equals(key);
            TextView card = text((key.equals("platinum") ? "♛ " : "★ ") + names[i],
                15, selected ? Color.WHITE : tierColor(key), true);
            card.setGravity(Gravity.CENTER);
            card.setBackground(round(selected ? tierColor(key) : Color.WHITE, 18));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(142), dp(58));
            lp.setMargins(0, dp(8), dp(10), 0);
            tierStrip.addView(card, lp);
            card.setOnClickListener(v -> {
                selectedTier = key;
                renderTiers();
                renderPeriods();
            });
        }
    }

    private void renderPeriods() {
        if (periodStrip == null) return;
        periodStrip.removeAllViews();
        String[] keys = {"monthly", "quarterly", "half_year", "yearly"};
        String[] labels = {"Monthly", "3 Months", "6 Months", "Yearly"};
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            Map<String, Object> plan = findPlan(selectedTier, key);
            boolean selected = selectedPeriod.equals(key);
            TextView chip = text(labels[i] + "\n₹" + number(plan.get("priceRupees")),
                13, selected ? Color.WHITE : INK, selected);
            chip.setGravity(Gravity.CENTER);
            chip.setBackground(round(selected ? BLUE : Color.WHITE, 16));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(112), dp(58));
            lp.setMargins(0, 0, dp(8), 0);
            periodStrip.addView(chip, lp);
            chip.setOnClickListener(v -> {
                selectedPeriod = key;
                renderPeriods();
            });
        }
    }

    private void load() {
        call("get", null, new Callback() {
            @Override public void success(Map<String, Object> data) {
                verified = bool(data.get("isVerified"));
                requestStatus = text(data.get("status"), "");
                String remoteTier = text(data.get("badgeTier"), "");
                String remotePeriod = text(data.get("badgePeriod"), "");
                if (!remoteTier.isEmpty()) selectedTier = remoteTier;
                if (!remotePeriod.isEmpty()) selectedPeriod = remotePeriod;
                List<?> remote = data.get("plans") instanceof List ? (List<?>) data.get("plans") : null;
                if (remote != null && !remote.isEmpty()) {
                    plans.clear();
                    for (Object raw : remote) plans.add(map(raw));
                }
                if (verified) {
                    statusText.setText("Your verification is active. The checkmark and tier badge are live.");
                } else if ("pending".equals(requestStatus)) {
                    statusText.setText("Your request is under review.");
                } else if ("rejected".equals(requestStatus)) {
                    statusText.setText("Your previous request was not approved. You can apply again.");
                }
                renderButton();
                renderTiers();
                renderPeriods();
            }
            @Override public void error(String message) {
                statusText.setText("Catalog unavailable. Please try again.");
                renderButton();
                renderTiers();
                renderPeriods();
            }
        });
    }

    private void submit() {
        if (verified || "pending".equals(requestStatus)) return;
        requestButton.setEnabled(false);
        Map<String, Object> payload = new HashMap<>();
        payload.put("tierKey", selectedTier);
        payload.put("periodKey", selectedPeriod);
        payload.put("reason", reasonInput == null ? "" : reasonInput.getText().toString().trim());
        call("request", payload, new Callback() {
            @Override public void success(Map<String, Object> data) {
                requestStatus = "pending";
                statusText.setText("Request submitted. Admin approval will activate your badge.");
                Toast.makeText(VerifiedBadgeActivity.this,
                    "Verification request submitted", Toast.LENGTH_LONG).show();
                renderButton();
            }
            @Override public void error(String message) {
                requestButton.setEnabled(true);
                Toast.makeText(VerifiedBadgeActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void renderButton() {
        if (requestButton == null) return;
        if (verified) {
            requestButton.setText("Verified ✓");
            requestButton.setEnabled(false);
        } else if ("pending".equals(requestStatus)) {
            requestButton.setText("Verification under review");
            requestButton.setEnabled(false);
        } else {
            requestButton.setText("Request verification");
            requestButton.setEnabled(true);
        }
    }

    private Map<String, Object> findPlan(String tier, String period) {
        for (Map<String, Object> plan : plans) {
            if (tier.equals(text(plan.get("tierKey"), ""))
                && period.equals(text(plan.get("periodKey"), ""))) return plan;
        }
        return new HashMap<>();
    }

    private void call(String action, Map<String, Object> payload, Callback callback) {
        RenderActionClient.post("/verification/action", action, payload,
            new RenderActionClient.Result() {
                @Override public void onSuccess(Map<String, Object> data) { callback.success(data); }
                @Override public void onError(String message) {
                    callback.error(message == null ? "Verification request failed" : message);
                }
            });
    }

    private interface Callback {
        void success(Map<String, Object> data);
        void error(String message);
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

    private TextView text(String value, int size, int color, boolean bold) {
        TextView out = new TextView(this);
        out.setText(value);
        out.setTextSize(size);
        out.setTextColor(color);
        out.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return out;
    }

    private Button button(String label) {
        Button out = new Button(this);
        out.setText(label);
        out.setTextSize(14);
        out.setAllCaps(false);
        return out;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable out = new GradientDrawable();
        out.setColor(color);
        out.setCornerRadius(dp(radius));
        out.setStroke(dp(1), Color.rgb(225, 225, 230));
        return out;
    }

    private int tierColor(String tier) {
        return "platinum".equals(tier) ? PLATINUM : "gold".equals(tier) ? GOLD : BLUE;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new HashMap<>();
    }

    private static String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static String number(Object value) {
        return value instanceof Number ? String.valueOf(((Number) value).intValue()) : text(value, "0");
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean && (Boolean) value;
    }
}