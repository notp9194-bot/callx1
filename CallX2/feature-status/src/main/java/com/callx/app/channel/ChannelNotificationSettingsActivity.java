package com.callx.app.channel;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import com.callx.app.db.entity.ChannelEntity;
import com.callx.app.status.R;
import com.callx.app.viewmodel.ChannelViewModel;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

/**
 * ChannelNotificationSettingsActivity — complete per-channel notification settings.
 *
 * Settings (all stored in SharedPreferences "channel_notif_prefs"):
 *   ┌─ Mute ────────────────────────────────────────────────────────────────┐
 *   │  Master mute switch  (calls viewModel.muteChannel / unmuteChannel)    │
 *   │  Mute duration: 8h / 1 week / Always                                  │
 *   ├─ Post type filters ────────────────────────────────────────────────────┤
 *   │  Text posts  │  Photos & videos  │  Polls  │  Links                   │
 *   │  Audio       │  Documents                                              │
 *   ├─ Appearance ───────────────────────────────────────────────────────────┤
 *   │  Show message preview  │  Notification sound  │  Vibrate               │
 *   └──────────────────────────────────────────────────────────────────────┘
 */
public class ChannelNotificationSettingsActivity extends AppCompatActivity {

    public static final String EXTRA_CHANNEL_ID   = "channelId";
    public static final String EXTRA_CHANNEL_NAME = "channelName";

    private static final String PREFS = "channel_notif_prefs";

    private ChannelViewModel viewModel;
    private String           channelId, channelName;
    private ChannelEntity    channelEntity;

    // Views
    private SwitchMaterial switchMute;
    private RadioGroup     rgMuteDuration;
    private TextView       tvMuteStatus;

    private SwitchMaterial switchTextPosts;
    private SwitchMaterial switchImagePosts;
    private SwitchMaterial switchPollPosts;
    private SwitchMaterial switchLinkPosts;
    private SwitchMaterial switchAudioPosts;
    private SwitchMaterial switchDocPosts;

    private SwitchMaterial switchShowPreview;
    private SwitchMaterial switchNotifSound;
    private SwitchMaterial switchVibrate;

    private MaterialButton btnReset;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_notification_settings);

        channelId   = getIntent().getStringExtra(EXTRA_CHANNEL_ID);
        channelName = getIntent().getStringExtra(EXTRA_CHANNEL_NAME);
        if (channelId == null) { finish(); return; }

        viewModel = new ViewModelProvider(this).get(ChannelViewModel.class);

        Toolbar toolbar = findViewById(R.id.toolbar_notif_settings);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(channelName != null ? channelName : "Notifications");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        bindViews();
        loadSavedPrefs();
        observeChannel();
        setListeners();

        viewModel.toastMessage.observe(this, msg -> {
            if (msg != null && !msg.isEmpty()) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });
    }

    private void bindViews() {
        switchMute       = findViewById(R.id.switch_mute_channel);
        rgMuteDuration   = findViewById(R.id.rg_mute_duration);
        tvMuteStatus     = findViewById(R.id.tv_mute_status);

        switchTextPosts  = findViewById(R.id.switch_text_posts);
        switchImagePosts = findViewById(R.id.switch_image_posts);
        switchPollPosts  = findViewById(R.id.switch_poll_posts);
        switchLinkPosts  = findViewById(R.id.switch_link_posts);
        switchAudioPosts = findViewById(R.id.switch_audio_posts);
        switchDocPosts   = findViewById(R.id.switch_doc_posts);

        switchShowPreview= findViewById(R.id.switch_show_preview);
        switchNotifSound = findViewById(R.id.switch_notif_sound);
        switchVibrate    = findViewById(R.id.switch_vibrate);

        btnReset         = findViewById(R.id.btn_reset_notif_defaults);
    }

    private void loadSavedPrefs() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        setSwitch(switchTextPosts,  p.getBoolean(key("text_posts"),   true));
        setSwitch(switchImagePosts, p.getBoolean(key("image_posts"),  true));
        setSwitch(switchPollPosts,  p.getBoolean(key("poll_posts"),   true));
        setSwitch(switchLinkPosts,  p.getBoolean(key("link_posts"),   true));
        setSwitch(switchAudioPosts, p.getBoolean(key("audio_posts"),  true));
        setSwitch(switchDocPosts,   p.getBoolean(key("doc_posts"),    true));
        setSwitch(switchShowPreview,p.getBoolean(key("show_preview"), true));
        setSwitch(switchNotifSound, p.getBoolean(key("notif_sound"),  true));
        setSwitch(switchVibrate,    p.getBoolean(key("vibrate"),      true));
    }

    private void observeChannel() {
        viewModel.getChannel(channelId).observe(this, ch -> {
            if (ch == null) return;
            channelEntity = ch;

            // Sync mute state from Room
            boolean muted = ch.isMuted;
            setSwitch(switchMute, muted);
            updateMuteUi(muted);
        });
    }

    private void setListeners() {
        // ── Mute switch ──────────────────────────────────────────────────
        if (switchMute != null) {
            switchMute.setOnCheckedChangeListener((btn, checked) -> {
                if (channelEntity == null) return;
                if (checked) {
                    viewModel.muteChannel(channelEntity, 0L); // permanent by default
                    updateMuteUi(true);
                } else {
                    viewModel.unmuteChannel(channelEntity);
                    updateMuteUi(false);
                }
            });
        }

        // ── Mute duration ────────────────────────────────────────────────
        if (rgMuteDuration != null) {
            rgMuteDuration.setOnCheckedChangeListener((group, checkedId) -> {
                if (channelEntity == null) return;
                long until = 0L; // always (0 = permanent)
                if (checkedId == R.id.rb_mute_8h) {
                    until = System.currentTimeMillis() + 8L * 3_600_000L;
                } else if (checkedId == R.id.rb_mute_1week) {
                    until = System.currentTimeMillis() + 7L * 24L * 3_600_000L;
                }
                viewModel.muteChannel(channelEntity, until);
            });
        }

        // ── Post type switches ────────────────────────────────────────────
        attachTypeSwitchListener(switchTextPosts,  "text_posts");
        attachTypeSwitchListener(switchImagePosts, "image_posts");
        attachTypeSwitchListener(switchPollPosts,  "poll_posts");
        attachTypeSwitchListener(switchLinkPosts,  "link_posts");
        attachTypeSwitchListener(switchAudioPosts, "audio_posts");
        attachTypeSwitchListener(switchDocPosts,   "doc_posts");

        // ── Appearance switches ───────────────────────────────────────────
        attachTypeSwitchListener(switchShowPreview, "show_preview");
        attachTypeSwitchListener(switchNotifSound,  "notif_sound");
        attachTypeSwitchListener(switchVibrate,     "vibrate");

        // ── Reset button ──────────────────────────────────────────────────
        if (btnReset != null) {
            btnReset.setOnClickListener(v -> resetToDefaults());
        }
    }

    private void attachTypeSwitchListener(SwitchMaterial sw, String keyName) {
        if (sw == null) return;
        sw.setOnCheckedChangeListener((btn, checked) -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(key(keyName), checked)
                    .apply();
        });
    }

    private void updateMuteUi(boolean muted) {
        if (tvMuteStatus != null) tvMuteStatus.setText(muted ? "Muted" : "Notifications on");
        if (rgMuteDuration != null)
            rgMuteDuration.setVisibility(muted ? View.VISIBLE : View.GONE);
    }

    private void resetToDefaults() {
        SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        String[] keys = { "text_posts","image_posts","poll_posts","link_posts",
                           "audio_posts","doc_posts","show_preview","notif_sound","vibrate" };
        for (String k : keys) ed.putBoolean(key(k), true);
        ed.apply();

        // Update UI without triggering listeners (set without listeners then re-attach)
        setSwitch(switchTextPosts,  true);
        setSwitch(switchImagePosts, true);
        setSwitch(switchPollPosts,  true);
        setSwitch(switchLinkPosts,  true);
        setSwitch(switchAudioPosts, true);
        setSwitch(switchDocPosts,   true);
        setSwitch(switchShowPreview,true);
        setSwitch(switchNotifSound, true);
        setSwitch(switchVibrate,    true);

        // Unmute
        if (channelEntity != null && channelEntity.isMuted) {
            viewModel.unmuteChannel(channelEntity);
            setSwitch(switchMute, false);
            updateMuteUi(false);
        }
        Toast.makeText(this, "Notification settings reset.", Toast.LENGTH_SHORT).show();
    }

    // ── Utilities ─────────────────────────────────────────────────────────

    /** Build prefs key namespaced by channelId to avoid cross-channel collision. */
    private String key(String name) { return channelId + "_" + name; }

    /** Set switch state without triggering its listener. */
    private void setSwitch(SwitchMaterial sw, boolean checked) {
        if (sw == null) return;
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(checked);
    }

    /**
     * Convenience: check whether a given post type notification is enabled.
     * Called from notification dispatch code in repository/Firebase messaging.
     */
    public static boolean isPostTypeEnabled(android.content.Context ctx,
                                              String channelId, String postType) {
        String key = channelId + "_" + postType + "_posts";
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(key, true);
    }

    public static boolean isShowPreviewEnabled(android.content.Context ctx, String channelId) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(channelId + "_show_preview", true);
    }

    public static boolean isNotifSoundEnabled(android.content.Context ctx, String channelId) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(channelId + "_notif_sound", true);
    }

    public static boolean isVibrateEnabled(android.content.Context ctx, String channelId) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(channelId + "_vibrate", true);
    }
}
