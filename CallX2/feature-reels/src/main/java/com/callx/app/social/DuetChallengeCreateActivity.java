package com.callx.app.social;

  import android.os.Bundle;
  import android.view.View;
  import android.widget.*;
  import androidx.annotation.NonNull;
  import androidx.appcompat.app.AppCompatActivity;
  import com.callx.app.reels.R;
  import com.callx.app.utils.FirebaseUtils;
  import com.google.firebase.auth.FirebaseAuth;
  import com.google.firebase.database.*;
  import java.util.*;

  /**
   * DuetChallengeCreateActivity — Creator launches a named duet challenge.
   *
   * Firebase structure:
   *   duet_challenges/{challengeId} = {
   *     title, description, hashtag,
   *     originalReelId, videoUrl, thumbUrl,
   *     hostUid, hostName,
   *     createdAt, endsAt,
   *     entryCount: 0, totalViews: 0,
   *     status: "active" | "ended",
   *     prizeDescription (optional)
   *   }
   *   reels/{reelId}/challengeId = challengeId  (set on upload)
   */
  public class DuetChallengeCreateActivity extends AppCompatActivity {

      public static final String EXTRA_REEL_ID    = "challenge_reel_id";
      public static final String EXTRA_VIDEO_URL  = "challenge_video_url";
      public static final String EXTRA_THUMB_URL  = "challenge_thumb_url";
      public static final String EXTRA_OWNER_NAME = "challenge_owner_name";

      private EditText   etTitle, etDesc, etHashtag, etPrize;
      private RadioGroup rgDuration;
      private Button     btnCreate;
      private ProgressBar progress;
      private ImageButton btnBack;

      private String myUid, myName;
      private String reelId, videoUrl, thumbUrl, ownerName;

      @Override
      protected void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);
          setContentView(R.layout.activity_duet_challenge_create);

          reelId   = getIntent().getStringExtra(EXTRA_REEL_ID);
          videoUrl = getIntent().getStringExtra(EXTRA_VIDEO_URL);
          thumbUrl = getIntent().getStringExtra(EXTRA_THUMB_URL);
          ownerName= getIntent().getStringExtra(EXTRA_OWNER_NAME);
          myUid    = FirebaseAuth.getInstance().getUid();

          btnBack   = findViewById(R.id.btn_challenge_create_back);
          etTitle   = findViewById(R.id.et_challenge_title);
          etDesc    = findViewById(R.id.et_challenge_desc);
          etHashtag = findViewById(R.id.et_challenge_hashtag);
          etPrize   = findViewById(R.id.et_challenge_prize);
          rgDuration= findViewById(R.id.rg_challenge_duration);
          btnCreate = findViewById(R.id.btn_create_challenge);
          progress  = findViewById(R.id.progress_challenge_create);

          btnBack.setOnClickListener(v -> finish());
          btnCreate.setOnClickListener(v -> createChallenge());

          loadMyName();
      }

      private void loadMyName() {
          if (myUid == null) return;
          FirebaseUtils.db().getReference("users").child(myUid).child("displayName")
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      myName = snap.getValue(String.class);
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {}
              });
      }

      private void createChallenge() {
          String title   = etTitle.getText().toString().trim();
          String desc    = etDesc.getText().toString().trim();
          String hashtag = etHashtag.getText().toString().trim().replace("#", "");
          String prize   = etPrize.getText().toString().trim();

          if (title.isEmpty()) { etTitle.setError("Required"); return; }
          if (hashtag.isEmpty()) { etHashtag.setError("Required"); return; }

          int checked = rgDuration.getCheckedRadioButtonId();
          long daysMs = 7 * 86400 * 1000L;
          if      (checked == R.id.rb_challenge_3d)  daysMs = 3  * 86400 * 1000L;
          else if (checked == R.id.rb_challenge_14d) daysMs = 14 * 86400 * 1000L;
          else if (checked == R.id.rb_challenge_30d) daysMs = 30 * 86400 * 1000L;

          progress.setVisibility(View.VISIBLE);
          btnCreate.setEnabled(false);

          String key = FirebaseUtils.db().getReference("duet_challenges").push().getKey();
          if (key == null) { progress.setVisibility(View.GONE); return; }

          Map<String, Object> ch = new HashMap<>();
          ch.put("title",           title);
          ch.put("description",     desc);
          ch.put("hashtag",         hashtag);
          ch.put("prize",           prize);
          ch.put("originalReelId",  reelId   != null ? reelId   : "");
          ch.put("videoUrl",        videoUrl != null ? videoUrl : "");
          ch.put("thumbUrl",        thumbUrl != null ? thumbUrl : "");
          ch.put("hostUid",         myUid);
          ch.put("hostName",        myName   != null ? myName   : "Creator");
          ch.put("createdAt",       com.google.firebase.database.ServerValue.TIMESTAMP);
          ch.put("endsAt",          System.currentTimeMillis() + daysMs);
          ch.put("entryCount",      0);
          ch.put("totalViews",      0);
          ch.put("status",          "active");

          FirebaseUtils.db().getReference("duet_challenges").child(key).setValue(ch)
              .addOnSuccessListener(v -> {
                  // Tag the original reel
                  if (reelId != null) {
                      FirebaseUtils.db().getReference("reels").child(reelId)
                          .child("challengeId").setValue(key);
                      FirebaseUtils.db().getReference("reels").child(reelId)
                          .child("challengeHashtag").setValue(hashtag);
                  }
                  progress.setVisibility(View.GONE);
                  Toast.makeText(this, "#" + hashtag + " challenge created! 🎉", Toast.LENGTH_LONG).show();
                  finish();
              })
              .addOnFailureListener(e -> {
                  progress.setVisibility(View.GONE);
                  btnCreate.setEnabled(true);
                  Toast.makeText(this, "Failed to create challenge", Toast.LENGTH_SHORT).show();
              });
      }
  }
  