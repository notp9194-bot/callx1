package com.callx.app.settings;

import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DatabaseReference;

import java.util.HashMap;
import java.util.Map;

/**
 * ReelRemixSettingsActivity — Production-level Remix / Collab Settings Screen.
 *
 * Features:
 *  ✅ Duet permission (Everyone / Followers / Off)
 *  ✅ Stitch permission (Everyone / Followers / Off)
 *  ✅ Remix permission (Everyone / Followers / Off)
 *  ✅ Allow audio use (Everyone / Followers / Off)
 *  ✅ Show/hide view count
 *  ✅ Show/hide like count
 *  ✅ Allow sharing outside app
 *  ✅ Save settings to Firebase per reel
 *  ✅ Changes reflected immediately in reel feed
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

        android.widget.ArrayAdapter<String> adapter =
            new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, OPTIONS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        spDuet.setAdapter(adapter);
        spStitch.setAdapter(new android.widget.ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, OPTIONS));
        spRemix.setAdapter(new android.widget.ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, OPTIONS));
        spAudio.setAdapter(new android.widget.ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, OPTIONS));

        swShowViews.setChecked(true);
        swShowLikes.setChecked(true);
        swAllowShare.setChecked(true);
    }

    private void loadCurrentSettings() {
        if (reelId == null) return;
        FirebaseUtils.db().getReference("reels").child(reelId).child("remix_settings")
            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                @Override public void onDataChange(@androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) return;
                    applySpinner(spDuet,   snap.child("duet").getValue(String.class));
                    applySpinner(spStitch, snap.child("stitch").getValue(String.class));
                    applySpinner(spRemix,  snap.child("remix").getValue(String.class));
                    applySpinner(spAudio,  snap.child("audio").getValue(String.class));
                    Boolean showViews = snap.child("show_views").getValue(Boolean.class);
                    Boolean showLikes = snap.child("show_likes").getValue(Boolean.class);
                    Boolean allowShare= snap.child("allow_share").getValue(Boolean.class);
                    if (showViews != null) swShowViews.setChecked(showViews);
                    if (showLikes != null) swShowLikes.setChecked(showLikes);
                    if (allowShare!= null) swAllowShare.setChecked(allowShare);
                }
                @Override public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError e) {}
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

            Map<String, Object> settings = new HashMap<>();
            settings.put("duet",        getSpinnerValue(spDuet));
            settings.put("stitch",      getSpinnerValue(spStitch));
            settings.put("remix",       getSpinnerValue(spRemix));
            settings.put("audio",       getSpinnerValue(spAudio));
            settings.put("show_views",  swShowViews.isChecked());
            settings.put("show_likes",  swShowLikes.isChecked());
            settings.put("allow_share", swAllowShare.isChecked());

            FirebaseUtils.db().getReference("reels").child(reelId)
                .child("remix_settings").updateChildren(settings)
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
