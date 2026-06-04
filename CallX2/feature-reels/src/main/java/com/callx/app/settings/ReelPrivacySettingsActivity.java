package com.callx.app.settings;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.reels.R;
import com.callx.app.followers.ReelCloseFriendsActivity;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.util.HashMap;
import java.util.Map;

public class ReelPrivacySettingsActivity extends AppCompatActivity {
    public static final String EXTRA_REEL_ID = "reel_id";

    private RadioGroup rgViewAudience, rgCommentAudience;
    private Switch switchDuet, switchStitch, switchDownload, switchShare, switchHideLikes, switchHideViews, switchAllowRepost;
    private Button btnSave;
    private ProgressBar progressSave;
    private View progressLoad;
    private View btnCloseFriends;
    private String reelId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_privacy_settings);
        reelId = getIntent().getStringExtra(EXTRA_REEL_ID);
        if (reelId == null || reelId.isEmpty()) { finish(); return; }
        bindViews();
        loadSettings();
    }

    private void bindViews() {
        ((ImageButton) findViewById(R.id.btn_privacy_back)).setOnClickListener(v -> finish());
        rgViewAudience    = findViewById(R.id.rg_view_audience);
        rgCommentAudience = findViewById(R.id.rg_comment_audience);
        switchDuet        = findViewById(R.id.switch_allow_duet);
        switchStitch      = findViewById(R.id.switch_allow_stitch);
        switchDownload    = findViewById(R.id.switch_allow_download);
        switchShare       = findViewById(R.id.switch_allow_share);
        switchAllowRepost = findViewById(R.id.switch_allow_repost);
        switchHideLikes   = findViewById(R.id.switch_hide_likes);
        switchHideViews   = findViewById(R.id.switch_hide_views);
        btnSave           = findViewById(R.id.btn_privacy_save);
        progressSave      = findViewById(R.id.progress_privacy_save);
        progressLoad      = findViewById(R.id.progress_privacy_load);
        btnSave.setOnClickListener(v -> saveSettings());
        btnCloseFriends   = findViewById(R.id.btn_close_friends);
        if (btnCloseFriends != null) {
            btnCloseFriends.setOnClickListener(v -> {
                Intent i = new Intent(this, ReelCloseFriendsActivity.class);
                i.putExtra("reel_id", reelId);
                startActivity(i);
            });
        }
    }

    private void loadSettings() {
        progressLoad.setVisibility(View.VISIBLE);
        btnSave.setEnabled(false);
        FirebaseUtils.getReelsRef().child(reelId).child("privacy")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    if (isFinishing() || isDestroyed()) return;
                    progressLoad.setVisibility(View.GONE);
                    btnSave.setEnabled(true);
                    String va = str(s,"viewAudience","everyone"), ca = str(s,"commentAudience","everyone");
                    applyBool(s,"allowDuet",true,switchDuet); applyBool(s,"allowStitch",true,switchStitch);
                    applyBool(s,"allowDownload",true,switchDownload); applyBool(s,"allowShare",true,switchShare);
                    applyBool(s,"allowReposts",true,switchAllowRepost);
                    applyBool(s,"hideLikes",false,switchHideLikes); applyBool(s,"hideViews",false,switchHideViews);
                    if("followers".equals(va)) rgViewAudience.check(R.id.rb_view_followers);
                    else if("only_me".equals(va)) rgViewAudience.check(R.id.rb_view_only_me);
                    else rgViewAudience.check(R.id.rb_view_everyone);
                    if("followers".equals(ca)) rgCommentAudience.check(R.id.rb_com_followers);
                    else if("no_one".equals(ca)) rgCommentAudience.check(R.id.rb_com_no_one);
                    else rgCommentAudience.check(R.id.rb_com_everyone);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (isFinishing()||isDestroyed()) return;
                    progressLoad.setVisibility(View.GONE); btnSave.setEnabled(true);
                }
            });
    }

    private void saveSettings() {
        btnSave.setEnabled(false); progressSave.setVisibility(View.VISIBLE);
        int vid=rgViewAudience.getCheckedRadioButtonId(), cid=rgCommentAudience.getCheckedRadioButtonId();
        String va = vid==R.id.rb_view_followers?"followers":vid==R.id.rb_view_only_me?"only_me":"everyone";
        String ca = cid==R.id.rb_com_followers?"followers":cid==R.id.rb_com_no_one?"no_one":"everyone";
        Map<String,Object> p = new HashMap<>();
        p.put("viewAudience",va); p.put("commentAudience",ca);
        p.put("allowDuet",switchDuet.isChecked()); p.put("allowStitch",switchStitch.isChecked());
        p.put("allowDownload",switchDownload.isChecked()); p.put("allowShare",switchShare.isChecked());
        p.put("allowReposts",switchAllowRepost.isChecked());
        p.put("hideLikes",switchHideLikes.isChecked()); p.put("hideViews",switchHideViews.isChecked());
        FirebaseUtils.getReelsRef().child(reelId).child("privacy").setValue(p)
            .addOnSuccessListener(u -> { if(isFinishing()||isDestroyed()) return;
                progressSave.setVisibility(View.GONE); btnSave.setEnabled(true);
                Toast.makeText(this,"Privacy settings saved",Toast.LENGTH_SHORT).show(); finish(); })
            .addOnFailureListener(ex -> { if(isFinishing()||isDestroyed()) return;
                progressSave.setVisibility(View.GONE); btnSave.setEnabled(true);
                Toast.makeText(this,"Save failed: "+ex.getMessage(),Toast.LENGTH_SHORT).show(); });
    }

    private String str(DataSnapshot s,String k,String def){String v=s.child(k).getValue(String.class);return v!=null?v:def;}
    private void applyBool(DataSnapshot s,String k,boolean def,Switch sw){Boolean v=s.child(k).getValue(Boolean.class);sw.setChecked(v!=null?v:def);}
}
