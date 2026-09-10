package com.callx.app.creator;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.callx.app.reels.R;
import com.google.firebase.functions.FirebaseFunctions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Creator-facing verified badge shop.
 *
 * The button creates a server-side verification request. It does not set
 * users/{uid}/isVerified from the client; the standalone admin app reviews
 * the request and the callable applies the decision atomically.
 */
public class VerifiedBadgeActivity extends AppCompatActivity {
    private static final int BLUE = Color.rgb(33, 161, 224);
    private static final int INK = Color.rgb(25, 25, 28);
    private static final int MUTED = Color.rgb(105, 105, 110);

    private final List<Map<String, Object>> plans = new ArrayList<>();
    private LinearLayout planStrip;
    private TextView statusText;
    private Button unlockButton;
    private String selectedPlan = "monthly";
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
        plans.add(plan("monthly", "Verified Badge", "49", "Monthly",
            "Verified badge on your profile", "Enhanced discovery in feed & explore",
            "Exclusive creator benefits"));
        plans.add(plan("quarterly", "Verified Badge Plus", "99", "3 Months",
            "Everything in Verified Badge", "Priority eligibility in reels",
            "Go Live and Watch Live included"));
        plans.add(plan("half_year", "Verified Badge Premium", "199", "6 Months",
            "Long-term creator advantages", "6 months of Go Live access",
            "6 months of Watch Live access"));
        plans.add(plan("yearly", "Verified Badge Super Plus", "349", "Yearly",
            "Verified badge for a full year", "50 free boost credits every month",
            "Priority creator support"));
    }

    private Map<String, Object> plan(String key, String name, String price,
                                      String period, String... benefits) {
        Map<String, Object> out = new HashMap<>();
        out.put("key", key);
        out.put("name", name);
        out.put("priceRupees", price);
        out.put("period", period);
        List<String> items = new ArrayList<>();
        for (String benefit : benefits) items.add(benefit);
        out.put("benefits", items);
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
        toolbar.addView(text("Get Verified", 20, INK, true),
            new LinearLayout.LayoutParams(0, dp(56), 1));
        root.addView(toolbar);

        LinearLayout intro = column();
        intro.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView badge = text("✓", 34, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(round(BLUE, 100));
        intro.addView(badge, new LinearLayout.LayoutParams(dp(76), dp(76)));
        TextView name = text("Your CallX profile", 18, INK, true);
        name.setPadding(0, dp(8), 0, 0);
        intro.addView(name);
        intro.addView(text("Stand out with a verified badge on your profile.\n"
            + "Show your audience you’re the real deal.", 13, MUTED, false));
        root.addView(intro);

        statusText = text("Choose a plan to unlock creator benefits.", 13, INK, false);
        statusText.setPadding(0, dp(18), 0, dp(8));
        root.addView(statusText);

        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        planStrip = row();
        horizontal.addView(planStrip);
        root.addView(horizontal);

        unlockButton = button("Unlock benefits");
        unlockButton.setTextColor(Color.WHITE);
        unlockButton.setBackground(round(BLUE, 28));
        unlockButton.setOnClickListener(v -> submit());
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(-1, dp(52));
        actionLp.setMargins(0, dp(18), 0, 0);
        root.addView(unlockButton, actionLp);
        renderPlans();
        return scroll;
    }

    private void renderPlans() {
        if (planStrip == null) return;
        planStrip.removeAllViews();
        for (Map<String, Object> plan : plans) {
            boolean selected = selectedPlan.equals(text(plan.get("key"), ""));
            LinearLayout card = column();
            card.setPadding(dp(16), dp(16), dp(16), dp(16));
            card.setBackground(round(selected ? Color.rgb(232, 244, 255) : Color.WHITE, 18));
            card.setElevation(dp(selected ? 5 : 1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(274), dp(390));
            lp.setMargins(0, 0, dp(12), 0);
            planStrip.addView(card, lp);
            TextView title = text(text(plan.get("name"), "Verified Badge"), 17, INK, true);
            card.addView(title);
            TextView price = text("INR " + text(plan.get("priceRupees"), "0"),
                22, BLUE, true);
            price.setPadding(0, dp(4), 0, 0);
            card.addView(price);
            card.addView(text("/" + text(plan.get("period"), "period"), 12, MUTED, false));

            LinearLayout perks = column();
            perks.setPadding(0, dp(18), 0, 0);
            Object raw = plan.get("benefits");
            if (raw instanceof List) {
                for (Object item : (List<?>) raw) {
                    TextView perk = text("✓  " + String.valueOf(item), 13,
                        Color.rgb(48, 145, 77), false);
                    perk.setPadding(0, dp(7), 0, 0);
                    perks.addView(perk);
                }
            }
            card.addView(perks);
            TextView selectedLabel = text(selected ? "✓ Selected" : "Tap to select",
                12, selected ? BLUE : MUTED, true);
            selectedLabel.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams selectLp = new LinearLayout.LayoutParams(-1, 0, 1);
            card.addView(selectedLabel, selectLp);
            card.setOnClickListener(v -> {
                selectedPlan = text(plan.get("key"), "monthly");
                renderPlans();
            });
        }
        if (verified) {
            unlockButton.setText("Verified ✓");
            unlockButton.setEnabled(false);
        } else if ("pending".equals(requestStatus)) {
            unlockButton.setText("Verification under review");
            unlockButton.setEnabled(false);
        } else {
            unlockButton.setText("Unlock benefits");
            unlockButton.setEnabled(true);
        }
    }

    private void load() {
        call("get", null, new Callback() {
            @Override public void success(Map<String, Object> data) {
                verified = bool(data.get("isVerified"));
                requestStatus = text(data.get("status"), "");
                List<?> remote = data.get("plans") instanceof List
                    ? (List<?>) data.get("plans") : null;
                if (remote != null && !remote.isEmpty()) {
                    plans.clear();
                    for (Object raw : remote) plans.add(map(raw));
                }
                if (verified) statusText.setText("Your profile is verified and creator benefits are active.");
                else if ("pending".equals(requestStatus)) statusText.setText(
                    "Your request is under review. We’ll update your badge after admin approval.");
                else if ("rejected".equals(requestStatus)) statusText.setText(
                    "Your previous request was not approved. You can apply again with a stronger profile.");
                renderPlans();
            }
            @Override public void error(String message) {
                statusText.setText("Choose a plan to unlock creator benefits.");
                renderPlans();
            }
        });
    }

    private void submit() {
        if (selectedPlan.isEmpty()) return;
        unlockButton.setEnabled(false);
        Map<String, Object> payload = new HashMap<>();
        payload.put("planKey", selectedPlan);
        call("purchase", payload, new Callback() {
            @Override public void success(Map<String, Object> data) {
                requestStatus = "pending";
                statusText.setText("Request submitted. Admin approval will activate your badge.");
                Toast.makeText(VerifiedBadgeActivity.this,
                    "Verification request submitted", Toast.LENGTH_LONG).show();
                renderPlans();
            }
            @Override public void error(String message) {
                unlockButton.setEnabled(true);
                Toast.makeText(VerifiedBadgeActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void call(String action, Map<String, Object> payload, Callback callback) {
        Map<String, Object> request = new HashMap<>();
        request.put("action", action);
        request.put("payload", payload == null ? new HashMap<>() : payload);
        FirebaseFunctions.getInstance().getHttpsCallable("verificationBadgeAction")
            .call(request)
            .addOnSuccessListener(result -> callback.success(map(result == null ? null : result.getData())))
            .addOnFailureListener(error -> callback.error(error.getMessage() == null
                ? "Verification request failed" : error.getMessage()));
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

    private static boolean bool(Object value) {
        return value instanceof Boolean && (Boolean) value;
    }
}