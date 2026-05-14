package com.callx.app.activities;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import com.callx.app.reels.R;

import java.util.ArrayList;
import java.util.List;

/**
 * ReelCreatorHubActivity — TikTok-style Creator Hub Menu with live search.
 *
 * All 25 inaccessible Reels screens collected in one place with:
 *  ✅ Search bar — type any keyword to filter rows instantly
 *  ✅ 4 sections: Creator Tools, Content, Social, Settings
 *  ✅ White background, grey section headers, emoji icons, chevrons
 *  ✅ Tap any row → opens corresponding Activity
 */
public class ReelCreatorHubActivity extends AppCompatActivity {

    // Model for each menu item
    private static class HubItem {
        final String section;
        final String emoji;
        final String label;
        final Class<?> target;
        HubItem(String s, String e, String l, Class<?> t) { section=s; emoji=e; label=l; target=t; }
    }

    private final List<HubItem> allItems = new ArrayList<>();
    private LinearLayout        container;
    private TextView            tvNoResults;
    private String              currentQuery = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_creator_hub);

        container   = findViewById(R.id.container_hub);
        tvNoResults = findViewById(R.id.tv_hub_no_results);

        findViewById(R.id.btn_hub_back).setOnClickListener(v -> finish());

        setupItems();
        setupSearch();
        renderAll(allItems);
    }

    // ── Data ─────────────────────────────────────────────────────────────

    private void setupItems() {
        // CREATOR TOOLS
        allItems.add(new HubItem("CREATOR TOOLS",    "🎬", "Creator Dashboard",      ReelCreatorDashboardActivity.class));
        allItems.add(new HubItem("CREATOR TOOLS",    "👥", "Audience Insights",       ReelAudienceInsightsActivity.class));
        allItems.add(new HubItem("CREATOR TOOLS",    "🔧", "Creator Tools",            ReelCreatorToolsActivity.class));
        allItems.add(new HubItem("CREATOR TOOLS",    "💰", "Creator Fund",             ReelCreatorFundActivity.class));
        allItems.add(new HubItem("CREATOR TOOLS",    "📈", "Deep Analytics",           ReelDeepAnalyticsActivity.class));
        allItems.add(new HubItem("CREATOR TOOLS",    "🔴", "Go Live",                  ReelLiveActivity.class));
        allItems.add(new HubItem("CREATOR TOOLS",    "📹", "Live Replays",             ReelLiveReplayActivity.class));
        // CONTENT MANAGEMENT
        allItems.add(new HubItem("CONTENT MANAGEMENT","📝", "Drafts",                  ReelDraftsActivity.class));
        allItems.add(new HubItem("CONTENT MANAGEMENT","🔖", "Saved Reels",             SavedReelsActivity.class));
        allItems.add(new HubItem("CONTENT MANAGEMENT","❤️",  "Liked Reels",             LikedReelsActivity.class));
        allItems.add(new HubItem("CONTENT MANAGEMENT","📌", "Bookmark Collections",     ReelBookmarkCollectionsActivity.class));
        allItems.add(new HubItem("CONTENT MANAGEMENT","📋", "Post Details",             ReelPostDetailsActivity.class));
        allItems.add(new HubItem("CONTENT MANAGEMENT","🗓️",  "Scheduled Posts",         ReelSchedulerActivity.class));
        // SOCIAL & COLLAB
        allItems.add(new HubItem("SOCIAL & COLLAB",  "🤝", "Collab Inbox",             ReelCollabInboxActivity.class));
        allItems.add(new HubItem("SOCIAL & COLLAB",  "💬", "Mentions",                 ReelMentionsActivity.class));
        allItems.add(new HubItem("SOCIAL & COLLAB",  "👥", "Close Friends",            ReelCloseFriendsActivity.class));
        allItems.add(new HubItem("SOCIAL & COLLAB",  "🎁", "Gifting",                  ReelGiftingActivity.class));
        allItems.add(new HubItem("SOCIAL & COLLAB",  "📱", "QR Code / Share",          ReelQRCodeActivity.class));
        allItems.add(new HubItem("SOCIAL & COLLAB",  "🔁", "Repost Analytics",          ReelRepostListActivity.class));
        allItems.add(new HubItem("SOCIAL & COLLAB",  "📋", "My Reposts",                UserRepostedReelsActivity.class));
        allItems.add(new HubItem("SOCIAL & COLLAB",  "🚫", "Block Reposters",           ReelRepostBlockActivity.class));
        // SETTINGS
        allItems.add(new HubItem("SETTINGS",         "🔒", "Privacy & Safety",         ReelPrivacyAndSecurityActivity.class));
        allItems.add(new HubItem("SETTINGS",         "📰", "Feed Settings",            ReelFeedSettingsActivity.class));
        allItems.add(new HubItem("SETTINGS",         "🔔", "Notification Settings",    ReelNotificationSettingsActivity.class));
        allItems.add(new HubItem("SETTINGS",         "🛡️",  "Moderation",              ReelModerationActivity.class));
        allItems.add(new HubItem("SETTINGS",         "🔄", "Remix Settings",           ReelRemixSettingsActivity.class));
        allItems.add(new HubItem("SETTINGS",         "🏷️",  "Watermark Settings",      ReelWatermarkSettingsActivity.class));
    }

    // ── Search ────────────────────────────────────────────────────────────

    private void setupSearch() {
        EditText etSearch   = findViewById(R.id.et_hub_search);
        ImageButton btnClear = findViewById(R.id.btn_hub_search_clear);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                currentQuery = s.toString().toLowerCase().trim();
                btnClear.setVisibility(currentQuery.isEmpty() ? View.GONE : View.VISIBLE);
                filter(currentQuery);
            }
        });

        btnClear.setOnClickListener(v -> {
            etSearch.setText("");
            etSearch.clearFocus();
        });
    }

    private void filter(String query) {
        if (query.isEmpty()) {
            renderAll(allItems);
            tvNoResults.setVisibility(View.GONE);
            return;
        }

        List<HubItem> results = new ArrayList<>();
        for (HubItem item : allItems) {
            if (item.label.toLowerCase().contains(query)
                    || item.section.toLowerCase().contains(query)) {
                results.add(item);
            }
        }

        renderAll(results);
        tvNoResults.setVisibility(results.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ── Render ────────────────────────────────────────────────────────────

    private void renderAll(List<HubItem> items) {
        container.removeAllViews();
        if (items.isEmpty()) return;

        String currentSection = null;
        for (HubItem item : items) {
            if (!item.section.equals(currentSection)) {
                currentSection = item.section;
                addSection(item.section);
            }
            addRow(item.emoji, item.label, item.target);
        }
    }

    // ── Builder helpers ───────────────────────────────────────────────────

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void addSection(String title) {
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));
        spacer.setBackgroundColor(Color.parseColor("#F2F2F2"));
        container.addView(spacer);

        TextView tv = new TextView(this);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        tv.setText(title);
        tv.setTextColor(Color.parseColor("#8A8A8E"));
        tv.setTextSize(12f);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setLetterSpacing(0.08f);
        tv.setPadding(dp(16), dp(12), dp(16), dp(6));
        tv.setBackgroundColor(Color.parseColor("#F2F2F2"));
        container.addView(tv);

        View border = new View(this);
        border.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        border.setBackgroundColor(Color.parseColor("#E5E5EA"));
        container.addView(border);
    }

    private void addRow(String emoji, String label, Class<?> target) {
        LinearLayout row = new LinearLayout(this);
        row.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(Color.WHITE);
        row.setPadding(dp(16), 0, dp(16), 0);
        row.setClickable(true);
        row.setFocusable(true);

        int[] attrs = {android.R.attr.selectableItemBackground};
        android.content.res.TypedArray ta = obtainStyledAttributes(attrs);
        row.setForeground(ta.getDrawable(0));
        ta.recycle();

        // Emoji
        TextView tvEmoji = new TextView(this);
        tvEmoji.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        tvEmoji.setText(emoji);
        tvEmoji.setTextSize(20f);
        tvEmoji.setGravity(Gravity.CENTER);
        row.addView(tvEmoji);

        // Label
        TextView tvLabel = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(dp(12));
        tvLabel.setLayoutParams(lp);
        tvLabel.setText(label);
        tvLabel.setTextColor(Color.parseColor("#1C1C1E"));
        tvLabel.setTextSize(15f);
        row.addView(tvLabel);

        // Chevron
        ImageView ivChevron = new ImageView(this);
        ivChevron.setLayoutParams(new LinearLayout.LayoutParams(dp(16), dp(16)));
        ivChevron.setImageResource(R.drawable.ic_arrow_forward);
        ivChevron.setColorFilter(Color.parseColor("#C7C7CC"));
        row.addView(ivChevron);

        row.setOnClickListener(v -> startActivity(new Intent(this, target)));

        container.addView(row);

        // Divider
        View divider = new View(this);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        divider.setBackgroundColor(Color.parseColor("#E5E5EA"));
        container.addView(divider);
    }
}
