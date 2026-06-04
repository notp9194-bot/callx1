package com.callx.app.settings;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.utils.XFirebaseUtils;
import com.callx.app.x.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import androidx.annotation.NonNull;
import java.util.HashMap;
import java.util.Map;

/**
 * Privacy & Safety settings.
 *
 * "Protect your posts" — when toggled, syncs isPrivate flag to Firebase
 * so XHomeFragment and audience checks across all feeds respect the setting.
 */
public class XSettingsPrivacyActivity extends AppCompatActivity {

    private static final String PREFS = "x_privacy_prefs";
    private SharedPreferences prefs;
    private String myUid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x_settings_privacy);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        Toolbar toolbar = findViewById(R.id.toolbar_x_privacy);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Privacy and safety");
        }

        // Protect posts — syncs to Firebase
        setupProtectPostsSwitch();

        // Other local-only toggles
        setupSwitch(R.id.sw_x_filter_dm,     "filter_dm",       true);
        setupSwitch(R.id.sw_x_find_by_email, "find_by_email",   true);
        setupSwitch(R.id.sw_x_find_by_phone, "find_by_phone",   true);

        // Photo tagging dialog
        View rowPhotoTag       = findViewById(R.id.row_x_photo_tagging);
        TextView tvPhotoTagVal = findViewById(R.id.tv_x_photo_tag_val);
        String[] tagOpts = {"Anyone", "People you follow", "Nobody"};
        if (tvPhotoTagVal != null)
            tvPhotoTagVal.setText(tagOpts[prefs.getInt("photo_tagging", 0)]);
        if (rowPhotoTag != null)
            rowPhotoTag.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Photo tagging")
                .setSingleChoiceItems(tagOpts, prefs.getInt("photo_tagging", 0), (dlg, which) -> {
                    prefs.edit().putInt("photo_tagging", which).apply();
                    if (tvPhotoTagVal != null) tvPhotoTagVal.setText(tagOpts[which]);
                    dlg.dismiss();
                }).show());

        // DM allow dialog
        View rowDmAllow       = findViewById(R.id.row_x_dm_allow);
        TextView tvDmAllowVal = findViewById(R.id.tv_x_dm_allow_val);
        String[] dmOpts = {"Everyone", "People you follow", "Nobody"};
        if (tvDmAllowVal != null)
            tvDmAllowVal.setText(dmOpts[prefs.getInt("dm_allow", 0)]);
        if (rowDmAllow != null)
            rowDmAllow.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Allow message requests from")
                .setSingleChoiceItems(dmOpts, prefs.getInt("dm_allow", 0), (dlg, which) -> {
                    prefs.edit().putInt("dm_allow", which).apply();
                    if (tvDmAllowVal != null) tvDmAllowVal.setText(dmOpts[which]);
                    dlg.dismiss();
                    // Sync DM preference to Firebase as well
                    if (!myUid.isEmpty())
                        XFirebaseUtils.xUserRef(myUid).child("dmAllow").setValue(which);
                }).show());

        // Blocked accounts
        View rowBlocked = findViewById(R.id.row_x_blocked);
        if (rowBlocked != null)
            rowBlocked.setOnClickListener(v ->
                startActivity(new Intent(this, XBlockedUsersActivity.class)));

        // Muted accounts
        View rowMuted = findViewById(R.id.row_x_muted);
        if (rowMuted != null)
            rowMuted.setOnClickListener(v ->
                startActivity(new Intent(this, XMutedUsersActivity.class)));
    }

    /**
     * Protect Posts switch — reads initial state from Firebase, then syncs changes back.
     * When enabled: sets x/users/{uid}/isPrivate = true
     * When disabled: sets x/users/{uid}/isPrivate = false
     */
    private void setupProtectPostsSwitch() {
        SwitchCompat sw = findViewById(R.id.sw_x_protect_posts);
        if (sw == null) return;

        // Load live state from Firebase (source of truth)
        if (!myUid.isEmpty()) {
            XFirebaseUtils.xUserRef(myUid).child("isPrivate")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        Boolean isPrivate = snap.getValue(Boolean.class);
                        boolean val = Boolean.TRUE.equals(isPrivate);
                        // Sync local prefs with Firebase truth
                        prefs.edit().putBoolean("protect_posts", val).apply();
                        sw.setChecked(val);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        // Fall back to local prefs if Firebase unavailable
                        sw.setChecked(prefs.getBoolean("protect_posts", false));
                    }
                });
        } else {
            sw.setChecked(prefs.getBoolean("protect_posts", false));
        }

        sw.setOnCheckedChangeListener((btn, checked) -> {
            // Save locally
            prefs.edit().putBoolean("protect_posts", checked).apply();

            if (myUid.isEmpty()) return;

            // Sync to Firebase — update user's isPrivate field
            XFirebaseUtils.xUserRef(myUid).child("isPrivate").setValue(checked)
                .addOnSuccessListener(unused -> {
                    if (checked) {
                        // Also update existing public tweets to "followers" audience
                        updateExistingTweetsAudience("followers");
                        Toast.makeText(this,
                            "Your posts are now protected — only your followers can see them.",
                            Toast.LENGTH_LONG).show();
                    } else {
                        // Restore existing tweets to "public"
                        updateExistingTweetsAudience("public");
                        Toast.makeText(this,
                            "Your posts are now public.",
                            Toast.LENGTH_SHORT).show();
                    }
                })
                .addOnFailureListener(e -> {
                    // Revert switch on failure
                    sw.setChecked(!checked);
                    prefs.edit().putBoolean("protect_posts", !checked).apply();
                    Toast.makeText(this,
                        "Failed to update privacy setting. Please try again.",
                        Toast.LENGTH_SHORT).show();
                });
        });
    }

    /**
     * Updates all existing tweets by this user to the given audience value.
     * Uses a batch read + write approach: reads user_tweets, then updates each tweet's audience.
     */
    private void updateExistingTweetsAudience(String audience) {
        if (myUid.isEmpty()) return;
        XFirebaseUtils.userTweetsRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    Map<String, Object> batch = new HashMap<>();
                    for (DataSnapshot ds : snap.getChildren()) {
                        String tweetId = ds.getKey();
                        if (tweetId != null)
                            batch.put("tweets/" + tweetId + "/audience", audience);
                    }
                    if (!batch.isEmpty()) {
                        // Batch-write all tweet audience fields
                        XFirebaseUtils.root_x().updateChildren(batch);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void setupSwitch(int id, String key, boolean def) {
        SwitchCompat sw = findViewById(id);
        if (sw == null) return;
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setOnCheckedChangeListener((b, c) -> prefs.edit().putBoolean(key, c).apply());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
