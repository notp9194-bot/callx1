package com.callx.app.creator;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
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
 * Star Talent tier picker and application form. Applications are reviewed by
 * the Admin app; a client never writes talentPlan or talentStatus directly.
 */
public class StarTalentActivity extends AppCompatActivity {
    private static final int INK = Color.rgb(25, 25, 28);
    private static final int MUTED = Color.rgb(100, 100, 106);
    private static final int PURPLE = Color.rgb(128, 76, 210);
    private static final int ORANGE = Color.rgb(232, 143, 35);

    private final List<Map<String, Object>> tiers = new ArrayList<>();
    private LinearLayout content;
    private TextView status;
    private Button applyButton;
    private EditText categoryInput;
    private EditText reasonInput;
    private String selectedTier = "star";
    private String applicationStatus = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        seedTiers();
        setContentView(buildScreen());
        load();
    }

    private void seedTiers() {
        tiers.clear();
        tiers.add(tier("star", "Become a Star Talent", "195", "Star Talent badge on your profile",
            "Listed in the Star Talent directory", "Priority creator support"));
        tiers.add(tier("gold", "Become a Gold Talent", "299", "Gold Talent badge on your profile",
            "Listed in the Gold Talent showcase", "Everything in Star Talent included"));
        tiers.add(tier("platinum", "Become a Platinum Talent", "499", "Platinum Talent badge — our highest tier",
            "Featured placement in the Platinum Talent directory",
            "Everything in Gold Talent included"));
    }

    private Map<String, Object> tier(String key, String name, String price, String... benefits) {
        Map<String, Object> out = new HashMap<>();
        out.put("key", key); out.put("name", name); out.put("priceRupees", price);
        List<String> items = new ArrayList<>();
        for (String benefit : benefits) items.add(benefit);
        out.put("benefits", items);
        return out;
    }

    private View buildScreen() {
        ScrollView scroll = new ScrollView(this);
        content = column();
        content.setPadding(dp(16), 0, dp(16), dp(28));
        scroll.addView(content);

        LinearLayout toolbar = row();
        TextView back = text("‹", 38, INK, false);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(42), dp(56)));
        toolbar.addView(text("Choose Your Tier", 20, INK, true),
            new LinearLayout.LayoutParams(0, dp(56), 1));
        content.addView(toolbar);

        content.addView(text("Choose Your Tier", 20, INK, true));
        content.addView(text("Select a tier to view details and apply.", 13, MUTED, false));
        status = text("Current tier: Normal", 13, MUTED, false);
        status.setPadding(0, dp(6), 0, dp(12));
        content.addView(status);
        renderTiers();

        TextView formTitle = text("Application details", 17, INK, true);
        formTitle.setPadding(0, dp(18), 0, dp(8));
        content.addView(formTitle);
        categoryInput = input("Creator category (for example, music, comedy, education)");
        content.addView(categoryInput);
        reasonInput = input("Why should you be selected as a Star Talent?");
        reasonInput.setMinLines(3);
        reasonInput.setGravity(Gravity.TOP);
        content.addView(reasonInput);
        applyButton = button("Apply for selected tier");
        applyButton.setTextColor(Color.WHITE);
        applyButton.setBackground(round(PURPLE, 28));
        applyButton.setOnClickListener(v -> submit());
        LinearLayout.LayoutParams applyLp = new LinearLayout.LayoutParams(-1, dp(52));
        applyLp.setMargins(0, dp(16), 0, 0);
        content.addView(applyButton, applyLp);
        content.addView(text("Limited spots available. Applications are reviewed by the CallX creator team.",
            12, PURPLE, false));
        return scroll;
    }

    private void renderTiers() {
        if (content == null) return;
        for (int i = 0; i < 3 && i < tiers.size(); i++) {
            Map<String, Object> tier = tiers.get(i);
            LinearLayout card = column();
            card.setPadding(dp(16), dp(14), dp(16), dp(14));
            boolean selected = selectedTier.equals(text(tier.get("key"), ""));
            card.setBackground(round(selected ? Color.rgb(245, 241, 255) : Color.WHITE, 16));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(7), 0, 0);
            content.addView(card, Math.min(4 + i, content.getChildCount()), lp);
            TextView title = text(text(tier.get("name"), "Talent"), 16, INK, true);
            card.addView(title);
            card.addView(text("INR " + text(tier.get("priceRupees"), "0"), 14,
                selected ? PURPLE : ORANGE, true));
            Object raw = tier.get("benefits");
            if (raw instanceof List) {
                for (Object benefit : (List<?>) raw) {
                    card.addView(text("✓  " + String.valueOf(benefit), 12,
                        selected ? PURPLE : MUTED, false));
                }
            }
            TextView chosen = text(selected ? "Selected" : "Tap to select", 12,
                selected ? PURPLE : MUTED, true);
            chosen.setPadding(0, dp(8), 0, 0);
            card.addView(chosen);
            card.setOnClickListener(v -> {
                selectedTier = text(tier.get("key"), "star");
                removeTierCards();
                renderTiers();
            });
        }
    }

    private void removeTierCards() {
        // Toolbar, heading, subtitle, status occupy the first four children.
        while (content.getChildCount() > 4) {
            View child = content.getChildAt(4);
            if (child == categoryInput || child == reasonInput || child == applyButton) break;
            content.removeViewAt(4);
        }
    }

    private void load() {
        call("get", null, new Callback() {
            @Override public void success(Map<String, Object> data) {
                applicationStatus = text(data.get("status"), "");
                String current = text(data.get("currentTier"), "normal");
                status.setText("Current tier: " + titleCase(current)
                    + ("pending".equals(applicationStatus) ? " • Application under review" : ""));
                List<?> remote = data.get("tiers") instanceof List
                    ? (List<?>) data.get("tiers") : null;
                if (remote != null && !remote.isEmpty()) {
                    tiers.clear();
                    for (Object raw : remote) tiers.add(map(raw));
                    removeTierCards();
                    renderTiers();
                }
                if ("pending".equals(applicationStatus)) {
                    applyButton.setText("Application under review");
                    applyButton.setEnabled(false);
                }
            }
            @Override public void error(String message) { /* defaults remain usable */ }
        });
    }

    private void submit() {
        String category = categoryInput.getText().toString().trim();
        String reason = reasonInput.getText().toString().trim();
        if (category.isEmpty() || reason.length() < 20) {
            Toast.makeText(this, "Add your category and at least 20 characters about your work.",
                Toast.LENGTH_LONG).show();
            return;
        }
        applyButton.setEnabled(false);
        Map<String, Object> payload = new HashMap<>();
        payload.put("tierKey", selectedTier);
        payload.put("category", category);
        payload.put("reason", reason);
        call("apply", payload, new Callback() {
            @Override public void success(Map<String, Object> data) {
                applicationStatus = "pending";
                status.setText("Current tier: Normal • Application under review");
                applyButton.setText("Application under review");
                Toast.makeText(StarTalentActivity.this,
                    "Star Talent application submitted", Toast.LENGTH_LONG).show();
            }
            @Override public void error(String message) {
                applyButton.setEnabled(true);
                Toast.makeText(StarTalentActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void call(String action, Map<String, Object> payload, Callback callback) {
        Map<String, Object> request = new HashMap<>();
        request.put("action", action);
        request.put("payload", payload == null ? new HashMap<>() : payload);
        RenderActionClient.post("/star-talent/action", action, payload,
            new RenderActionClient.Result() {
                @Override public void onSuccess(Map<String, Object> data) {
                    callback.success(data);
                }
                @Override public void onError(String message) {
                    callback.error(message == null ? "Talent application failed" : message);
                }
            });
    }

    private interface Callback {
        void success(Map<String, Object> data);
        void error(String message);
    }

    private EditText input(String hint) {
        EditText out = new EditText(this);
        out.setHint(hint);
        out.setTextSize(13);
        out.setPadding(dp(14), dp(10), dp(14), dp(10));
        out.setBackground(round(Color.rgb(248, 248, 250), 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(6), 0, 0);
        out.setLayoutParams(lp);
        return out;
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
        out.setText(value); out.setTextSize(size); out.setTextColor(color);
        out.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return out;
    }

    private Button button(String label) {
        Button out = new Button(this);
        out.setText(label); out.setTextSize(14); out.setAllCaps(false);
        return out;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable out = new GradientDrawable();
        out.setColor(color); out.setCornerRadius(dp(radius));
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

    private static String titleCase(String value) {
        if (value == null || value.isEmpty()) return "Normal";
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}