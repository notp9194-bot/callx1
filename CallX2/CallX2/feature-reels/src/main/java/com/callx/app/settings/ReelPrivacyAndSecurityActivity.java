package com.callx.app.settings;

import com.callx.app.feed.ReelFeedSettingsActivity;
import com.callx.app.profile.ReelQRCodeActivity;
import com.callx.app.creator.ReelCreatorFundActivity;
import com.callx.app.creator.ReelModerationActivity;
import com.callx.app.followers.ReelCollabInboxActivity;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.callx.app.reels.R;

/**
 * ReelPrivacyAndSecurityActivity — TikTok-style Privacy and Settings screen.
 *
 * UI matches the TikTok "Privacy and settings" screenshot exactly:
 *  ACCOUNT section  — Privacy & Safety, Moderation, Content preferences, Feed Settings,
 *                     Share profile (QR), Watermark
 *  CREATOR section  — Remix Settings, Notification Settings, Collab Inbox
 *  SUPPORT section  — Report a Problem, Help Center
 */
public class ReelPrivacyAndSecurityActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_privacy_and_security);

        findViewById(R.id.btn_privacy_sec_back).setOnClickListener(v -> finish());

        LinearLayout container = findViewById(R.id.container_privacy_sec);
        buildUI(container);
    }

    private void buildUI(LinearLayout root) {

        // ── ACCOUNT ───────────────────────────────────────────────────────
        addSection(root, "ACCOUNT");
        addRow(root, "👤", "Manage my account",        null);
        addRow(root, "🔒", "Privacy and safety",       ReelPrivacySettingsActivity.class);
        addRow(root, "📰", "Content preferences",      ReelFeedSettingsActivity.class);
        addRow(root, "💰", "Creator Fund / Balance",   ReelCreatorFundActivity.class);
        addRow(root, "📤", "Share profile",             ReelQRCodeActivity.class);
        addRow(root, "📱", "Reel QR Code",              ReelQRCodeActivity.class);

        // ── CREATOR ───────────────────────────────────────────────────────
        addSection(root, "CREATOR");
        addRow(root, "🔔", "Push notifications",       ReelNotificationSettingsActivity.class);
        addRow(root, "🔄", "Remix settings",           ReelRemixSettingsActivity.class);
        addRow(root, "🏷️",  "Watermark settings",      ReelWatermarkSettingsActivity.class);
        addRow(root, "🛡️",  "Moderation",              ReelModerationActivity.class);
        addRow(root, "🤝", "Collab settings",           ReelCollabInboxActivity.class);

        // ── SUPPORT ───────────────────────────────────────────────────────
        addSection(root, "SUPPORT");
        addRow(root, "✏️",  "Report a problem",        null);
        addRow(root, "❓", "Help Center",               null);
    }

    // ── Builder helpers ───────────────────────────────────────────────────

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void addSection(LinearLayout parent, String title) {
        View spacer = new View(this);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        spacer.setLayoutParams(sp);
        spacer.setBackgroundColor(Color.parseColor("#F2F2F2"));
        parent.addView(spacer);

        TextView tv = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tv.setLayoutParams(lp);
        tv.setText(title);
        tv.setTextColor(Color.parseColor("#8A8A8E"));
        tv.setTextSize(12f);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setLetterSpacing(0.08f);
        tv.setPadding(dp(16), dp(12), dp(16), dp(6));
        tv.setBackgroundColor(Color.parseColor("#F2F2F2"));
        parent.addView(tv);

        View topBorder = new View(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        topBorder.setLayoutParams(bp);
        topBorder.setBackgroundColor(Color.parseColor("#E5E5EA"));
        parent.addView(topBorder);
    }

    private void addRow(LinearLayout parent, String emoji, String label, Class<?> target) {
        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        row.setLayoutParams(rowLp);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(Color.WHITE);
        row.setPadding(dp(16), 0, dp(16), 0);
        row.setClickable(true);
        row.setFocusable(true);

        int[] attrs = new int[]{android.R.attr.selectableItemBackground};
        android.content.res.TypedArray ta = obtainStyledAttributes(attrs);
        row.setForeground(ta.getDrawable(0));
        ta.recycle();

        TextView tvEmoji = new TextView(this);
        LinearLayout.LayoutParams emojiLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        tvEmoji.setLayoutParams(emojiLp);
        tvEmoji.setText(emoji);
        tvEmoji.setTextSize(20f);
        tvEmoji.setGravity(Gravity.CENTER);
        row.addView(tvEmoji);

        TextView tvLabel = new TextView(this);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelLp.setMarginStart(dp(12));
        tvLabel.setLayoutParams(labelLp);
        tvLabel.setText(label);
        tvLabel.setTextColor(Color.parseColor("#1C1C1E"));
        tvLabel.setTextSize(15f);
        row.addView(tvLabel);

        ImageView ivChevron = new ImageView(this);
        LinearLayout.LayoutParams chevLp = new LinearLayout.LayoutParams(dp(16), dp(16));
        ivChevron.setLayoutParams(chevLp);
        ivChevron.setImageResource(R.drawable.ic_arrow_forward);
        ivChevron.setColorFilter(Color.parseColor("#C7C7CC"));
        row.addView(ivChevron);

        row.setOnClickListener(v -> {
            if (target != null) {
                startActivity(new Intent(this, target));
            } else {
                Toast.makeText(this, label + " — Coming soon", Toast.LENGTH_SHORT).show();
            }
        });

        parent.addView(row);

        View divider = new View(this);
        LinearLayout.LayoutParams dvLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        divider.setLayoutParams(dvLp);
        divider.setBackgroundColor(Color.parseColor("#E5E5EA"));
        parent.addView(divider);
    }
}
