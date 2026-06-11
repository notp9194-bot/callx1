package com.callx.app.social;

  import android.content.Intent;
  import android.os.Bundle;
  import android.view.View;
  import android.widget.*;
  import androidx.annotation.NonNull;
  import androidx.appcompat.app.AppCompatActivity;
  import com.bumptech.glide.Glide;
  import com.callx.app.models.ReelModel;
  import com.callx.app.reels.R;
  import com.callx.app.utils.FirebaseUtils;
  import com.google.firebase.auth.FirebaseAuth;
  import com.google.firebase.database.*;
  import java.util.*;

  /**
   * DuetBattleCreateActivity — Challenge another duetor to a battle.
   *
   * Usage:
   *  1. Called from DuetsByReelActivity when user long-presses a duet:
   *     "Challenge this duet to a battle"
   *  2. Shows current user's duet (reelIdA) and the challenged duet (reelIdB)
   *  3. Picks battle duration (24h / 48h / 72h)
   *  4. Creates duet_battles/{battleId} node
   *  5. Notifies challenged duetor
   */
  public class DuetBattleCreateActivity extends AppCompatActivity {

      public static final String EXTRA_MY_REEL_ID       = "battle_my_reel_id";
      public static final String EXTRA_MY_REEL_THUMB    = "battle_my_thumb";
      public static final String EXTRA_MY_VIDEO_URL     = "battle_my_url";
      public static final String EXTRA_THEIR_REEL_ID    = "battle_their_reel_id";
      public static final String EXTRA_THEIR_REEL_THUMB = "battle_their_thumb";
      public static final String EXTRA_THEIR_VIDEO_URL  = "battle_their_url";
      public static final String EXTRA_THEIR_NAME       = "battle_their_name";
      public static final String EXTRA_THEIR_UID        = "battle_their_uid";
      public static final String EXTRA_ORIGINAL_REEL_ID = "battle_original_reel_id";

      private ImageView  ivMyThumb, ivTheirThumb;
      private TextView   tvMyLabel, tvTheirLabel;
      private RadioGroup rgDuration;
      private Button     btnCreateBattle;
      private ProgressBar progress;
      private ImageButton btnBack;

      private String myUid, myName;
      private String myReelId, myThumb, myUrl;
      private String theirReelId, theirThumb, theirUrl, theirName, theirUid;
      private String originalReelId;

      @Override
      protected void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);
          setContentView(R.layout.activity_duet_battle_create);

          myReelId       = getIntent().getStringExtra(EXTRA_MY_REEL_ID);
          myThumb        = getIntent().getStringExtra(EXTRA_MY_REEL_THUMB);
          myUrl          = getIntent().getStringExtra(EXTRA_MY_VIDEO_URL);
          theirReelId    = getIntent().getStringExtra(EXTRA_THEIR_REEL_ID);
          theirThumb     = getIntent().getStringExtra(EXTRA_THEIR_REEL_THUMB);
          theirUrl       = getIntent().getStringExtra(EXTRA_THEIR_VIDEO_URL);
          theirName      = getIntent().getStringExtra(EXTRA_THEIR_NAME);
          theirUid       = getIntent().getStringExtra(EXTRA_THEIR_UID);
          originalReelId = getIntent().getStringExtra(EXTRA_ORIGINAL_REEL_ID);
          myUid          = FirebaseAuth.getInstance().getUid();

          btnBack        = findViewById(R.id.btn_battle_create_back);
          ivMyThumb      = findViewById(R.id.iv_battle_my_thumb);
          ivTheirThumb   = findViewById(R.id.iv_battle_their_thumb);
          tvMyLabel      = findViewById(R.id.tv_battle_my_label);
          tvTheirLabel   = findViewById(R.id.tv_battle_their_label);
          rgDuration     = findViewById(R.id.rg_battle_duration);
          btnCreateBattle= findViewById(R.id.btn_create_battle);
          progress       = findViewById(R.id.progress_battle_create);

          tvTheirLabel.setText("vs @" + (theirName != null ? theirName : "them"));

          if (myThumb    != null) Glide.with(this).load(myThumb).centerCrop().into(ivMyThumb);
          if (theirThumb != null) Glide.with(this).load(theirThumb).centerCrop().into(ivTheirThumb);

          btnBack.setOnClickListener(v -> finish());
          btnCreateBattle.setOnClickListener(v -> createBattle());

          loadMyName();
      }

      private void loadMyName() {
          if (myUid == null) return;
          FirebaseUtils.db().getReference("users").child(myUid).child("displayName")
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      myName = snap.getValue(String.class);
                      tvMyLabel.setText(myName != null ? "You (@" + myName + ")" : "You");
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {}
              });
      }

      private void createBattle() {
          if (myReelId == null || theirReelId == null || myUid == null) return;

          int checkedId = rgDuration.getCheckedRadioButtonId();
          long durationMs = 48 * 3600 * 1000L; // default 48h
          if      (checkedId == R.id.rb_battle_24h) durationMs = 24 * 3600 * 1000L;
          else if (checkedId == R.id.rb_battle_72h) durationMs = 72 * 3600 * 1000L;

          progress.setVisibility(View.VISIBLE);
          btnCreateBattle.setEnabled(false);

          Map<String, Object> battle = new HashMap<>();
          battle.put("reelIdA",        myReelId);
          battle.put("reelIdB",        theirReelId);
          battle.put("videoUrlA",      myUrl      != null ? myUrl      : "");
          battle.put("videoUrlB",      theirUrl   != null ? theirUrl   : "");
          battle.put("thumbA",         myThumb    != null ? myThumb    : "");
          battle.put("thumbB",         theirThumb != null ? theirThumb : "");
          battle.put("nameA",          myName     != null ? myName     : "Challenger");
          battle.put("nameB",          theirName  != null ? theirName  : "Opponent");
          battle.put("uidA",           myUid);
          battle.put("uidB",           theirUid   != null ? theirUid   : "");
          battle.put("originalReelId", originalReelId != null ? originalReelId : "");
          battle.put("votesA",         0);
          battle.put("votesB",         0);
          battle.put("createdAt",      com.google.firebase.database.ServerValue.TIMESTAMP);
          battle.put("endsAt",         System.currentTimeMillis() + durationMs);
          battle.put("status",         "active");

          String battleKey = FirebaseUtils.db().getReference("duet_battles").push().getKey();
          if (battleKey == null) { progress.setVisibility(View.GONE); return; }

          FirebaseUtils.db().getReference("duet_battles").child(battleKey).setValue(battle)
              .addOnSuccessListener(v -> {
                  progress.setVisibility(View.GONE);
                  // Notify opponent
                  Map<String, Object> notif = new HashMap<>();
                  notif.put("type",     "duet_battle_challenge");
                  notif.put("battleId", battleKey);
                  notif.put("fromUid",  myUid);
                  notif.put("fromName", myName != null ? myName : "Someone");
                  notif.put("sentAt",   com.google.firebase.database.ServerValue.TIMESTAMP);
                  if (theirUid != null) {
                      FirebaseUtils.db().getReference("reel_notifications")
                          .child(theirUid).push().setValue(notif);
                  }
                  // Open battle immediately
                  Intent i = new Intent(DuetBattleCreateActivity.this, DuetBattleActivity.class);
                  i.putExtra(DuetBattleActivity.EXTRA_BATTLE_ID, battleKey);
                  startActivity(i);
                  finish();
              })
              .addOnFailureListener(e -> {
                  progress.setVisibility(View.GONE);
                  btnCreateBattle.setEnabled(true);
                  Toast.makeText(this, "Failed to create battle", Toast.LENGTH_SHORT).show();
              });
      }
  }
  