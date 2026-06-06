package com.callx.app.explore;

import com.callx.app.music.MusicPickerActivity;

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
 * ReelChallengeCreateActivity — Create & launch your own reel challenge.
 *
 * Features:
 *  ✅ Challenge name input + auto-hashtag preview (#ChallengeName)
 *  ✅ Challenge description / rules text box
 *  ✅ Duration picker (3 / 7 / 14 / 30 days)
 *  ✅ Audio track selection (opens MusicPickerActivity)
 *  ✅ Reward/prize description (optional)
 *  ✅ Cover video/image URL
 *  ✅ Privacy: Public / Followers Only
 *  ✅ Submit → writes to reelChallenges node + indexes under userChallenges
 *  ✅ Live preview of challenge card before submitting
 */
public class ReelChallengeCreateActivity extends AppCompatActivity {

    private static final int    RC_MUSIC = 301;
    private static final int[]  DURATIONS = {3, 7, 14, 30};

    private ImageButton  btnBack;
    private EditText     etChallengeName, etDescription, etRules, etReward, etCoverUrl;
    private TextView     tvHashtagPreview, btnSelectAudio, tvAudioSelected;
    private RadioGroup   rgDuration, rgPrivacy;
    private Button       btnSubmit, btnPreview;
    private ProgressBar  progress;
    private ScrollView   svForm;

    private String myUid, selectedAudioId = null, selectedAudioTitle = null;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_reel_challenge_create);
        try { myUid = FirebaseUtils.getCurrentUid(); } catch (Exception e) { finish(); return; }
        bindViews();
    }

    private void bindViews() {
        btnBack         = findViewById(R.id.btn_cc_back);
        etChallengeName = findViewById(R.id.et_challenge_name);
        etDescription   = findViewById(R.id.et_challenge_desc);
        etRules         = findViewById(R.id.et_challenge_rules);
        etReward        = findViewById(R.id.et_challenge_reward);
        etCoverUrl      = findViewById(R.id.et_challenge_cover_url);
        tvHashtagPreview= findViewById(R.id.tv_challenge_hashtag_preview);
        btnSelectAudio  = findViewById(R.id.btn_challenge_select_audio);
        tvAudioSelected = findViewById(R.id.tv_challenge_audio_selected);
        rgDuration      = findViewById(R.id.rg_challenge_duration);
        rgPrivacy       = findViewById(R.id.rg_challenge_privacy);
        btnSubmit       = findViewById(R.id.btn_challenge_submit);
        btnPreview      = findViewById(R.id.btn_challenge_preview);
        progress        = findViewById(R.id.progress_challenge_create);
        svForm          = findViewById(R.id.sv_challenge_form);

        btnBack.setOnClickListener(v -> finish());
        btnSubmit.setOnClickListener(v -> submitChallenge());
        btnPreview.setOnClickListener(v -> previewChallenge());
        btnSelectAudio.setOnClickListener(v -> {
            startActivityForResult(new android.content.Intent(this, MusicPickerActivity.class), RC_MUSIC);
        });

        etChallengeName.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                String name = s.toString().trim().replaceAll("\\s+", "");
                tvHashtagPreview.setText(name.isEmpty() ? "#YourChallenge" : "#" + name + "Challenge");
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req == RC_MUSIC && res == RESULT_OK && data != null) {
            selectedAudioId    = data.getStringExtra("track_id");
            selectedAudioTitle = data.getStringExtra("track_title");
            tvAudioSelected.setText(selectedAudioTitle != null ? selectedAudioTitle : "Audio selected");
            tvAudioSelected.setVisibility(View.VISIBLE);
        }
    }

    private void previewChallenge() {
        String name = etChallengeName.getText() != null ? etChallengeName.getText().toString().trim() : "";
        if (name.isEmpty()) { Toast.makeText(this, "Enter challenge name first", Toast.LENGTH_SHORT).show(); return; }
        String hashtag = "#" + name.replaceAll("\\s+", "") + "Challenge";
        String desc    = etDescription.getText() != null ? etDescription.getText().toString() : "";
        String dur     = getDuration() + " days";
        new android.app.AlertDialog.Builder(this)
            .setTitle(hashtag)
            .setMessage("📋 " + desc + "\n\n⏱ Duration: " + dur
                + (selectedAudioTitle != null ? "\n🎵 " + selectedAudioTitle : ""))
            .setPositiveButton("Looks Good!", null).show();
    }

    private void submitChallenge() {
        String name = etChallengeName.getText() != null ? etChallengeName.getText().toString().trim() : "";
        String desc = etDescription.getText() != null ? etDescription.getText().toString().trim() : "";
        if (name.isEmpty()) { Toast.makeText(this, "Challenge name required", Toast.LENGTH_SHORT).show(); return; }
        if (desc.isEmpty()) { Toast.makeText(this, "Description required", Toast.LENGTH_SHORT).show(); return; }

        progress.setVisibility(View.VISIBLE);
        btnSubmit.setEnabled(false);

        DatabaseReference ref = FirebaseUtils.db().getReference("reelChallenges").push();
        String challengeId = ref.getKey();
        if (challengeId == null) { progress.setVisibility(View.GONE); btnSubmit.setEnabled(true); return; }

        String hashtag = "#" + name.replaceAll("\\s+", "") + "Challenge";
        int dur = getDuration();
        String privacy = ((RadioButton) rgPrivacy.findViewById(rgPrivacy.getCheckedRadioButtonId()))
            .getText().toString();

        Map<String, Object> m = new HashMap<>();
        m.put("id",           challengeId);
        m.put("name",         name);
        m.put("hashtag",      hashtag);
        m.put("description",  desc);
        m.put("rules",        etRules.getText()   != null ? etRules.getText().toString()   : "");
        m.put("reward",       etReward.getText()  != null ? etReward.getText().toString()  : "");
        m.put("coverUrl",     etCoverUrl.getText()!= null ? etCoverUrl.getText().toString(): "");
        m.put("durationDays", dur);
        m.put("privacy",      privacy);
        m.put("creatorUid",   myUid);
        m.put("creatorName",  FirebaseUtils.getCurrentName());
        m.put("audioId",      selectedAudioId != null ? selectedAudioId : "");
        m.put("audioTitle",   selectedAudioTitle != null ? selectedAudioTitle : "");
        m.put("createdAt",    System.currentTimeMillis());
        m.put("endsAt",       System.currentTimeMillis() + (long) dur * 86400000L);
        m.put("participantCount", 0);
        m.put("status",       "active");

        Map<String, Object> upd = new HashMap<>();
        upd.put("reelChallenges/" + challengeId, m);
        upd.put("userChallenges/" + myUid + "/" + challengeId, System.currentTimeMillis());
        upd.put("trendingHashtags/" + name.replaceAll("\\s+", "") + "Challenge/count", 0);

        FirebaseUtils.db().getReference().updateChildren(upd).addOnCompleteListener(t -> {
            progress.setVisibility(View.GONE);
            btnSubmit.setEnabled(true);
            if (t.isSuccessful()) {
                Toast.makeText(this, "Challenge launched! 🎉 " + hashtag, Toast.LENGTH_LONG).show();
                finish();
            } else {
                Toast.makeText(this, "Failed to create challenge", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private int getDuration() {
        int checked = rgDuration.getCheckedRadioButtonId();
        RadioButton rb = rgDuration.findViewById(checked);
        if (rb != null) {
            String t = rb.getText().toString();
            for (int d : DURATIONS) if (t.contains(String.valueOf(d))) return d;
        }
        return 7;
    }
}
