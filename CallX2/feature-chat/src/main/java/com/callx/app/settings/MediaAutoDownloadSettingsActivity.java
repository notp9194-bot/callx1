package com.callx.app.settings;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import android.graphics.Color;
import android.util.TypedValue;

import com.callx.app.utils.MediaAutoDownloadPolicy;

/**
 * MediaAutoDownloadSettingsActivity — WhatsApp-style auto-download settings.
 *
 * Three rows (Images, Videos, GIFs / Audio), each with a 3-way radio:
 *   • When on WiFi   (auto-download on WiFi only — default for images/GIFs)
 *   • Always         (auto-download on WiFi + mobile data)
 *   • Never          (always manual tap — default for videos)
 *
 * Register in AndroidManifest inside feature-chat:
 *   <activity android:name=".settings.MediaAutoDownloadSettingsActivity"
 *             android:label="Auto-download media"
 *             android:theme="@style/Theme.CallX.DayNight" />
 *
 * Launch from chat settings / app settings wherever appropriate.
 */
public class MediaAutoDownloadSettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── Root scroll ────────────────────────────────────────────────────
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#121212"));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(32));

        // ── Title ──────────────────────────────────────────────────────────
        TextView title = new TextView(this);
        title.setText("Auto-download media");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Choose when media is downloaded automatically without tapping.");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        sub.setTextColor(Color.parseColor("#AAAAAA"));
        sub.setPadding(0, 0, 0, dp(24));
        root.addView(sub);

        // ── Media rows ─────────────────────────────────────────────────────
        addMediaRow(root, "Images",        "image");
        addMediaRow(root, "Videos",        "video");
        addMediaRow(root, "GIFs & Audio",  "gif");

        scroll.addView(root);
        setContentView(scroll);

        // Back arrow in action bar
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
            getActionBar().setTitle("Auto-download media");
        }
    }

    @Override
    public boolean onNavigateUp() {
        finish();
        return true;
    }

    // ── Row builder ────────────────────────────────────────────────────────

    private void addMediaRow(LinearLayout parent, String label, String mediaType) {
        // Section header
        TextView header = new TextView(this);
        header.setText(label);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        header.setTextColor(Color.parseColor("#4FC3F7"));   // light blue accent
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        header.setPadding(0, dp(8), 0, dp(4));
        parent.addView(header);

        // Card
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.parseColor("#1E1E1E"));
        card.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(16);
        card.setLayoutParams(cardLp);

        // 3 radio options
        String current = MediaAutoDownloadPolicy.getPolicy(this, mediaType);

        RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(RadioGroup.VERTICAL);

        addRadioOption(rg, mediaType, "When on WiFi",    MediaAutoDownloadPolicy.POLICY_WIFI,
                current, card);
        addRadioOption(rg, mediaType, "On WiFi and data", MediaAutoDownloadPolicy.POLICY_WIFI_DATA,
                current, card);
        addRadioOption(rg, mediaType, "Never",           MediaAutoDownloadPolicy.POLICY_NEVER,
                current, card);

        parent.addView(card);
    }

    private void addRadioOption(RadioGroup rg, String mediaType, String optLabel,
                                String policyValue, String currentPolicy, LinearLayout card) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        RadioButton rb = new RadioButton(this);
        rb.setId(View.generateViewId());
        rb.setButtonTintList(android.content.res.ColorStateList.valueOf(
                Color.parseColor("#4FC3F7")));
        rb.setChecked(policyValue.equals(currentPolicy));
        rg.addView(rb);

        TextView tv = new TextView(this);
        tv.setText(optLabel);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        tv.setTextColor(Color.WHITE);
        tv.setPadding(dp(12), dp(14), 0, dp(14));

        row.addView(rb);
        row.addView(tv);

        // Tap the whole row to toggle the button
        row.setOnClickListener(v -> {
            rb.setChecked(true);
            MediaAutoDownloadPolicy.setPolicy(this, mediaType, policyValue);
        });

        card.addView(row);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
