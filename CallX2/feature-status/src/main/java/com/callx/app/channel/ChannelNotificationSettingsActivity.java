package com.callx.app.channel;

import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.status.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.firebase.database.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ChannelNotificationSettingsActivity — full WhatsApp-level notification prefs (v5).
 *
 * v5 additions:
 *   ✓ NEW: Mention-only switch — only notify when @mentioned in a post or reply
 *   ✓ NEW: Milestone alerts switch — notify when a channel reaches a follower milestone
 *   ✓ NEW: Event reminders switch — notify X hours before a channel event starts
 *   ✓ NEW: Poll close alerts switch — notify when a poll you voted in closes
 *   ✓ Existing switches retained: all posts, broadcasts, polls, new follower alerts
 *   ✓ All settings persisted to Firebase channelNotifSettings/{uid}/{channelId}
 */
public class ChannelNotificationSettingsActivity extends AppCompatActivity {

    public static final String EXTRA_CHANNEL_ID   = "channelId";
    public static final String EXTRA_CHANNEL_NAME = "channelName";

    private static final String KEY_ALL_POSTS    = "allPosts";
    private static final String KEY_BROADCASTS   = "broadcasts";
    private static final String KEY_POLLS        = "polls";
    private static final String KEY_NEW_FOLLOWER = "newFollower";
    private static final String KEY_MENTION_ONLY = "mentionOnly";
    private static final String KEY_MILESTONES   = "milestoneAlerts";
    private static final String KEY_EVENT_REM    = "eventReminders";
    private static final String KEY_POLL_CLOSE   = "pollCloseAlerts";

    private String channelId, myUid;
    private DatabaseReference settingsRef;
    private boolean changingProgrammatically = false;

    private SwitchMaterial swAllPosts, swBroadcasts, swPolls, swNewFollower;
    private SwitchMaterial swMentionOnly, swMilestones, swEventReminders, swPollClose;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_notification_settings);

        channelId = getIntent().getStringExtra(EXTRA_CHANNEL_ID);
        String channelName = getIntent().getStringExtra(EXTRA_CHANNEL_NAME);
        if (channelId == null) { finish(); return; }

        myUid = FirebaseUtils.getMyUid();
        if (myUid == null) { finish(); return; }

        settingsRef = FirebaseUtils.db()
            .getReference("channelNotifSettings").child(myUid).child(channelId);

        Toolbar toolbar = findViewById(R.id.toolbar_notif_settings);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Notifications");
            getSupportActionBar().setSubtitle(channelName);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        // Bind all switches
        swAllPosts       = findViewById(R.id.switch_all_posts);
        swBroadcasts     = findViewById(R.id.switch_broadcasts);
        swPolls          = findViewById(R.id.switch_polls);
        swNewFollower    = findViewById(R.id.switch_new_follower);
        swMentionOnly    = findViewById(R.id.switch_mention_only);   // NEW
        swMilestones     = findViewById(R.id.switch_milestone_alerts); // NEW
        swEventReminders = findViewById(R.id.switch_event_reminders); // NEW
        swPollClose      = findViewById(R.id.switch_poll_close_alerts); // NEW

        // Load persisted settings from Firebase
        settingsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                changingProgrammatically = true;
                setSwitch(swAllPosts,       snap, KEY_ALL_POSTS,    true);
                setSwitch(swBroadcasts,     snap, KEY_BROADCASTS,   true);
                setSwitch(swPolls,          snap, KEY_POLLS,        true);
                setSwitch(swNewFollower,    snap, KEY_NEW_FOLLOWER, false);
                setSwitch(swMentionOnly,    snap, KEY_MENTION_ONLY, false);
                setSwitch(swMilestones,     snap, KEY_MILESTONES,   true);
                setSwitch(swEventReminders, snap, KEY_EVENT_REM,    true);
                setSwitch(swPollClose,      snap, KEY_POLL_CLOSE,   true);
                changingProgrammatically = false;
                wireListeners();
            }
            @Override public void onCancelled(DatabaseError e) {
                changingProgrammatically = false;
                wireListeners();
            }
        });
    }

    private void setSwitch(SwitchMaterial sw, DataSnapshot snap, String key, boolean def) {
        if (sw == null) return;
        Boolean v = snap.child(key).getValue(Boolean.class);
        sw.setChecked(v != null ? v : def);
    }

    private void wireListeners() {
        wireSave(swAllPosts,       KEY_ALL_POSTS);
        wireSave(swBroadcasts,     KEY_BROADCASTS);
        wireSave(swPolls,          KEY_POLLS);
        wireSave(swNewFollower,    KEY_NEW_FOLLOWER);
        wireSave(swMentionOnly,    KEY_MENTION_ONLY);
        wireSave(swMilestones,     KEY_MILESTONES);
        wireSave(swEventReminders, KEY_EVENT_REM);
        wireSave(swPollClose,      KEY_POLL_CLOSE);

        // Mention-only: when enabled, disable all-posts switch
        if (swMentionOnly != null) {
            swMentionOnly.setOnCheckedChangeListener((btn, checked) -> {
                if (!changingProgrammatically && checked && swAllPosts != null) {
                    changingProgrammatically = true;
                    swAllPosts.setChecked(false);
                    save(KEY_ALL_POSTS, false);
                    changingProgrammatically = false;
                }
                if (!changingProgrammatically) save(KEY_MENTION_ONLY, checked);
                showSaved();
            });
        }
    }

    private void wireSave(SwitchMaterial sw, String key) {
        if (sw == null) return;
        sw.setOnCheckedChangeListener((btn, checked) -> {
            if (changingProgrammatically) return;
            save(key, checked);
            showSaved();
        });
    }

    private void save(String key, boolean value) {
        if (settingsRef != null) settingsRef.child(key).setValue(value);
    }

    private void showSaved() {
        Snackbar.make(findViewById(android.R.id.content), "Saved", Snackbar.LENGTH_SHORT).show();
    }
}
