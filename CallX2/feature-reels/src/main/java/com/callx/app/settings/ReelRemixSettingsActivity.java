package com.callx.app.settings;

import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * ReelRemixSettingsActivity — Production-level Remix / Collab Settings Screen.
 *
 * ✅ FIX (GAP #1): Settings now saved to the CORRECT Firebase paths:
 *   - allowDuetLevel   → reels/{reelId}/allowDuetLevel   (read by ReelPlayerFragment ✅)
 *   - allowDuet        → reels/{reelId}/allowDuet         (legacy boolean ✅)
 *   - allowStitchLevel → reels/{reelId}/allowStitchLevel  (read by ReelPlayerFragment ✅)
 *   - allowStitch      → reels/{reelId}/allowStitch       (legacy boolean ✅)
 *   - remix/audio/show_views/show_likes/allow_share → reels/{reelId}/remix_settings/... (extra prefs)
 *
 * Previously all fields were buried under remix_settings sub-node, which the feed
 * never read — making all permission changes silently ineffective.
 */
public class ReelRemixSettingsActivity extends AppCompatActivity {

    public static final String EXTRA_REEL_ID = "remix_reel_id";

    private ImageButton btnBack;
    private TextView    btnSave;
    private Spinner     spDuet, spStitch, spRemix, spAudio;
    private Switch      swShowViews, swShowLikes, swAllowShare;
    private ProgressBar progressSave;

    private String reelId;
    private static final String[] OPTIONS = {"Everyone", "Followers Only", "Off"};
    private static final String[] VALUES  = {"everyone", "followers", "off"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_remix_settings);

        reelId = getIntent().getStringExtra(EXTRA_REEL_ID);

        bindViews();
        loadCurrentSettings();
        setupClickListeners();
    }

    private void bindViews() {
        btnBack       = findViewById(R.id.btn_remix_back);
        btnSave       = findViewById(R.id.btn_remix_save);
        spDuet        = findViewById(R.id.sp_duet_permission);
        spStitch      = findViewById(R.id.sp_stitch_permission);
        spRemix       = findViewById(R.id.sp_remix_permission);
        spAudio       = findViewById(R.id.sp_audio_permission);
        swShowViews   = findViewById(R.id.sw_show_views);
        swShowLikes   = findViewById(R.id.sw_show_likes);
        swAllowShare  = findViewById(R.id.sw_allow_share);
        progressSave  = findViewById(R.id.progress_remix_save);

        for (Spinner sp : new Spinner[]{spDuet, spStitch, spRemix, spAudio}) {
            android.widget.ArrayAdapter<String> a = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, OPTIONS);
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            sp.setAdapter(a);
        }

        swShowViews.setChecked(true);
        swShowLikes.setChecked(true);
        swAllowShare.setChecked(true);
    }

    private void loadCurrentSettings() {
        if (reelId == null) return;
        // ✅ FIX: Read duet/stitch levels from top-level reel node (where feed reads them)
        FirebaseUtils.db().getReference("reels").child(reelId)
            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                @Override public void onDataChange(
                        @androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) return;
                    // Duet/stitch from top-level reel fields
                    applySpinner(spDuet,   snap.child("allowDuetLevel").getValue(String.class));
                    applySpinner(spStitch, snap.child("allowStitchLevel").getValue(String.class));
                    // Remix/audio from remix_settings sub-node
                    com.google.firebase.database.DataSnapshot rs = snap.child("remix_settings");
                    applySpinner(spRemix, rs.child("remix").getValue(String.class));
                    applySpinner(spAudio, rs.child("audio").getValue(String.class));
                    Boolean showViews = rs.child("show_views").getValue(Boolean.class);
                    Boolean showLikes = rs.child("show_likes").getValue(Boolean.class);
                    Boolean allowShare= rs.child("allow_share").getValue(Boolean.class);
                    if (showViews  != null) swShowViews.setChecked(showViews);
                    if (showLikes  != null) swShowLikes.setChecked(showLikes);
                    if (allowShare != null) swAllowShare.setChecked(allowShare);
                }
                @Override public void onCancelled(
                        @androidx.annotation.NonNull com.google.firebase.database.DatabaseError e) {}
            });
    }

    private void applySpinner(Spinner sp, String value) {
        if (value == null) return;
        for (int i = 0; i < VALUES.length; i++) {
            if (VALUES[i].equals(value)) { sp.setSelection(i); break; }
        }
    }

    private String getSpinnerValue(Spinner sp) {
        int pos = sp.getSelectedItemPosition();
        return pos >= 0 && pos < VALUES.length ? VALUES[pos] : "everyone";
    }

    private void setupClickListeners() {
        btnBack.setOnClickListener(v -> finish());

        btnSave.setOnClickListener(v -> {
            if (reelId == null) { finish(); return; }
            progressSave.setVisibility(android.view.View.VISIBLE);
            btnSave.setEnabled(false);

            String duetLevel   = getSpinnerValue(spDuet);
            String stitchLevel = getSpinnerValue(spStitch);

            // ✅ FIX: Save allowDuetLevel / allowStitchLevel to the TOP-LEVEL reel node
            // so that ReelPlayerFragment.effectiveAllowDuetLevel() reads the updated value.
            Map<String, Object> reelFields = new HashMap<>();
            reelFields.put("allowDuetLevel",   duetLevel);
            reelFields.put("allowDuet",         !"off".equals(duetLevel));   // legacy bool
            reelFields.put("allowStitchLevel", stitchLevel);
            reelFields.put("allowStitch",       !"off".equals(stitchLevel)); // legacy bool

            // Extra remix prefs stay in remix_settings sub-node (used by analytics/other screens)
            Map<String, Object> remixSettings = new HashMap<>();
            remixSettings.put("remix",       getSpinnerValue(spRemix));
            remixSettings.put("audio",       getSpinnerValue(spAudio));
            remixSettings.put("show_views",  swShowViews.isChecked());
            remixSettings.put("show_likes",  swShowLikes.isChecked());
            remixSettings.put("allow_share", swAllowShare.isChecked());

            com.google.firebase.database.DatabaseReference reelRef =
                FirebaseUtils.db().getReference("reels").child(reelId);

            // Atomic: update top-level fields + remix_settings in one call
            Map<String, Object> allUpdates = new HashMap<>();
            allUpdates.put("allowDuetLevel",          duetLevel);
            allUpdates.put("allowDuet",               !"off".equals(duetLevel));
            allUpdates.put("allowStitchLevel",        stitchLevel);
            allUpdates.put("allowStitch",             !"off".equals(stitchLevel));
            allUpdates.put("remix_settings/remix",    getSpinnerValue(spRemix));
            allUpdates.put("remix_settings/audio",    getSpinnerValue(spAudio));
            allUpdates.put("remix_settings/show_views",  swShowViews.isChecked());
            allUpdates.put("remix_settings/show_likes",  swShowLikes.isChecked());
            allUpdates.put("remix_settings/allow_share", swAllowShare.isChecked());

            reelRef.updateChildren(allUpdates)
                .addOnSuccessListener(unused -> {
                    if (!isFinishing()) {
                        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                })
                .addOnFailureListener(e -> {
                    if (!isFinishing()) {
                        progressSave.setVisibility(android.view.View.GONE);
                        btnSave.setEnabled(true);
                        Toast.makeText(this, "Failed to save", Toast.LENGTH_SHORT).show();
                    }
                });
        });
    }
}
