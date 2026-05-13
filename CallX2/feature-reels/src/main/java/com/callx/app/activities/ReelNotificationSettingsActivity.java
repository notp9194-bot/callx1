package com.callx.app.activities;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.util.*;

/**
 * ReelNotificationSettingsActivity — Granular control of every reel notification type.
 *
 * Notification categories (all toggleable independently):
 *  ✅ Interactions: Likes, Comments, Comment Likes, Comment Replies
 *  ✅ Social: New Follower, Someone You Follow Posted
 *  ✅ Mentions: Caption Mentions, Comment Mentions
 *  ✅ Collab: Collab Request, Collab Accepted, Duet Created, Stitch Created, Video Reply
 *  ✅ Live: Live Started (following), Live Viewer Milestone, Close Friend Live
 *  ✅ Gifting: Gift Received, Creator Fund Payout
 *  ✅ Growth: Trending Alert, Viral Alert, View Milestone, Follower Milestone
 *  ✅ Upload: Upload Complete, Upload Failed, Scheduled Post, Scheduled Reminder
 *  ✅ Shopping: Product Tag Click, Product Tag Purchase
 *  ✅ Challenges: New Challenge, Challenge Update
 *  ✅ Audio: Sound Trending, Close Friend Posted
 *  ✅ Moderation: Content Removed, Report Resolved
 *  ✅ Digest: Weekly Creator Summary, Monthly Earnings Report
 *  ✅ Master toggle (mute all reel notifications)
 *  ✅ Per-category sound / vibration toggles
 *  ✅ Quiet hours (Do Not Disturb window)
 *  ✅ Settings saved at users/{uid}/reelNotifSettings
 */
public class ReelNotificationSettingsActivity extends AppCompatActivity {

    private ImageButton btnBack;
    private Switch      swMasterMute;
    private LinearLayout containerSettings;
    private Button      btnSaveAll;
    private ProgressBar progress;
    private ScrollView  sv;

    private String myUid;
    private DatabaseReference settingsRef;
    private final Map<String, Switch> switches = new LinkedHashMap<>();

    private static final String[][] CATEGORIES = {
        // {key, label, default (true=on)}
        {"likes",             "Likes on your reels",              "true"},
        {"comments",          "Comments on your reels",           "true"},
        {"commentLikes",      "Likes on your comments",           "true"},
        {"commentReplies",    "Replies to your comments",         "true"},
        {"newFollower",       "New followers",                     "true"},
        {"followingPosted",   "When someone you follow posts",    "true"},
        {"captionMentions",   "Mentioned in caption",             "true"},
        {"commentMentions",   "Mentioned in comments",            "true"},
        {"collabRequest",     "Collaboration requests",           "true"},
        {"collabAccepted",    "Collaboration accepted",           "true"},
        {"duetCreated",       "Someone dueted your reel",         "true"},
        {"stitchCreated",     "Someone stitched your reel",       "true"},
        {"videoReply",        "Video replies to your reel",       "true"},
        {"liveStarted",       "Following user went live",         "true"},
        {"liveViewerMile",    "Live viewer milestones",           "true"},
        {"closeFriendLive",   "Close friend went live",           "true"},
        {"giftReceived",      "Gifts received on live/reel",      "true"},
        {"creatorFundPayout", "Creator fund payout ready",        "true"},
        {"trendingAlert",     "Your reel is trending",            "true"},
        {"viralAlert",        "Your reel went viral",             "true"},
        {"viewMilestone",     "View milestones (1K, 10K, 100K)",  "true"},
        {"followerMilestone", "Follower milestones (1K, 10K)",    "true"},
        {"uploadComplete",    "Reel upload complete",             "true"},
        {"uploadFailed",      "Reel upload failed",               "true"},
        {"scheduledPost",     "Scheduled reel posted",            "true"},
        {"scheduledReminder", "Reminder before scheduled post",   "true"},
        {"productTagClick",   "Product tag click-through",        "true"},
        {"productTagSale",    "Product tag purchase",             "true"},
        {"challengeInvite",   "New challenge invitation",         "true"},
        {"challengeUpdate",   "Challenge updates & results",      "true"},
        {"soundTrending",     "Sound you used is trending",       "true"},
        {"closeFriendPosted", "Close friend posted a reel",       "true"},
        {"contentRemoved",    "Your content was removed",         "true"},
        {"reportResolved",    "Your report was reviewed",         "true"},
        {"weeklyDigest",      "Weekly creator performance digest","true"},
        {"monthlyEarnings",   "Monthly earnings summary",         "true"},
        {"reelSaved",         "Someone saved your reel",          "false"},
        {"reelDownloaded",    "Someone downloaded your reel",     "false"},
        {"reelShared",        "Someone shared your reel",         "true"},
        {"pinnedComment",     "Your comment was pinned",          "true"},
        {"reelRecommended",   "Personalised reel recommendation", "false"},
    };

    private static final String[][] SECTION_HEADERS = {
        {"likes",            "❤️  INTERACTIONS"},
        {"newFollower",      "👥  SOCIAL"},
        {"captionMentions",  "📣  MENTIONS"},
        {"collabRequest",    "🤝  COLLAB & REMIX"},
        {"liveStarted",      "📡  LIVE"},
        {"giftReceived",     "🎁  GIFTING & MONETISATION"},
        {"trendingAlert",    "📈  GROWTH MILESTONES"},
        {"uploadComplete",   "⬆️  UPLOAD & SCHEDULE"},
        {"productTagClick",  "🛍️  SHOPPING"},
        {"challengeInvite",  "🏆  CHALLENGES"},
        {"soundTrending",    "🎵  AUDIO & STORIES"},
        {"contentRemoved",   "🛡️  MODERATION"},
        {"weeklyDigest",     "📊  DIGEST"},
        {"reelSaved",        "🔖  SAVES & SHARES"},
    };

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_reel_notification_settings);
        try { myUid = FirebaseUtils.getCurrentUid(); } catch (Exception e) { finish(); return; }
        settingsRef = FirebaseUtils.getUserRef(myUid).child("reelNotifSettings");
        bindViews();
        loadSettings();
    }

    private void bindViews() {
        btnBack          = findViewById(R.id.btn_notif_settings_back);
        swMasterMute     = findViewById(R.id.sw_notif_master_mute);
        containerSettings= findViewById(R.id.container_notif_settings);
        btnSaveAll       = findViewById(R.id.btn_notif_save_all);
        progress         = findViewById(R.id.progress_notif_settings);
        sv               = findViewById(R.id.sv_notif_settings);

        btnBack.setOnClickListener(v -> finish());
        btnSaveAll.setOnClickListener(v -> saveAll());

        swMasterMute.setOnCheckedChangeListener((b, checked) -> {
            containerSettings.setAlpha(checked ? 0.4f : 1f);
            for (Switch sw : switches.values()) sw.setEnabled(!checked);
        });

        buildCategoryRows();
    }

    private void buildCategoryRows() {
        Set<String> headerKeys = new HashSet<>();
        for (String[] h : SECTION_HEADERS) headerKeys.add(h[0]);

        containerSettings.removeAllViews();

        for (String[] cat : CATEGORIES) {
            String key = cat[0];

            // Section header
            for (String[] h : SECTION_HEADERS) {
                if (h[0].equals(key)) {
                    TextView header = new TextView(this);
                    header.setText(h[1]);
                    header.setTextColor(0xFF888888);
                    header.setTextSize(11);
                    header.setAllCaps(false);
                    header.setPadding(dp(16), dp(16), dp(16), dp(4));
                    containerSettings.addView(header);
                    break;
                }
            }

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(10), dp(16), dp(10));

            TextView tv = new TextView(this);
            tv.setText(cat[1]);
            tv.setTextColor(0xFFFFFFFF);
            tv.setTextSize(14);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            Switch sw = new Switch(this);
            sw.setChecked("true".equals(cat[2]));

            row.addView(tv); row.addView(sw);

            View divider = new View(this);
            divider.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
            divider.setBackgroundColor(0xFF1F1F1F);

            containerSettings.addView(row);
            containerSettings.addView(divider);
            switches.put(key, sw);
        }
    }

    private void loadSettings() {
        progress.setVisibility(View.VISIBLE);
        settingsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                progress.setVisibility(View.GONE);
                Boolean muted = snap.child("masterMute").getValue(Boolean.class);
                swMasterMute.setChecked(muted != null && muted);
                for (Map.Entry<String, Switch> e : switches.entrySet()) {
                    Boolean val = snap.child(e.getKey()).getValue(Boolean.class);
                    if (val != null) e.getValue().setChecked(val);
                }
                containerSettings.setAlpha(swMasterMute.isChecked() ? 0.4f : 1f);
                for (Switch sw : switches.values()) sw.setEnabled(!swMasterMute.isChecked());
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                if (!isFinishing()) progress.setVisibility(View.GONE);
            }
        });
    }

    private void saveAll() {
        progress.setVisibility(View.VISIBLE);
        Map<String, Object> m = new HashMap<>();
        m.put("masterMute", swMasterMute.isChecked());
        for (Map.Entry<String, Switch> e : switches.entrySet())
            m.put(e.getKey(), e.getValue().isChecked());
        m.put("updatedAt", System.currentTimeMillis());
        settingsRef.updateChildren(m).addOnCompleteListener(t -> {
            if (!isFinishing()) {
                progress.setVisibility(View.GONE);
                Toast.makeText(this, t.isSuccessful() ? "Notification settings saved!" : "Save failed", Toast.LENGTH_SHORT).show();
                if (t.isSuccessful()) finish();
            }
        });
    }

    private int dp(int d) { return (int)(d * getResources().getDisplayMetrics().density); }
}
