package com.callx.app.repost;

import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/**
 * Quote Repost — play original reel + record your reaction overlay.
 * The result is saved as a new reel with repostType="quote" linking back to original.
 */
public class QuoteRepostActivity extends AppCompatActivity {

    public static final String EXTRA_REEL_ID    = "reelId";
    public static final String EXTRA_OWNER_UID  = "ownerUid";
    public static final String EXTRA_REEL_VIDEO = "reelVideo";
    public static final String EXTRA_REEL_THUMB = "reelThumb";

    private SurfaceView svOriginal;
    private VideoView vvOriginal;
    private EditText etCaption;
    private TextView tvCharCount, tvOriginalOwner;
    private MaterialButton btnPublish, btnCancel;
    private ProgressBar progress;
    private ImageView ivOriginalThumb;

    private String reelId, ownerUid, reelVideo, reelThumb;
    private RepostManager repostManager;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_quote_repost);

        reelId    = getIntent().getStringExtra(EXTRA_REEL_ID);
        ownerUid  = getIntent().getStringExtra(EXTRA_OWNER_UID);
        reelVideo = getIntent().getStringExtra(EXTRA_REEL_VIDEO);
        reelThumb = getIntent().getStringExtra(EXTRA_REEL_THUMB);

        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u != null) {
            repostManager = new RepostManager(
                u.getUid(),
                u.getDisplayName() != null ? u.getDisplayName() : "User",
                u.getPhotoUrl()    != null ? u.getPhotoUrl().toString() : ""
            );
        }

        bindViews();
        setupPlayer();
        setupUI();
    }

    private void bindViews() {
        vvOriginal     = findViewById(R.id.vv_original_reel);
        etCaption      = findViewById(R.id.et_quote_caption);
        tvCharCount    = findViewById(R.id.tv_char_count_quote);
        tvOriginalOwner= findViewById(R.id.tv_original_owner);
        btnPublish     = findViewById(R.id.btn_publish_quote);
        btnCancel      = findViewById(R.id.btn_cancel_quote);
        progress       = findViewById(R.id.progress_quote);
        ivOriginalThumb= findViewById(R.id.iv_original_thumb);
    }

    private void setupPlayer() {
        if (reelVideo != null && !reelVideo.isEmpty()) {
            vvOriginal.setVideoURI(Uri.parse(reelVideo));
            vvOriginal.setOnPreparedListener(mp -> {
                mp.setLooping(true);
                vvOriginal.start();
            });
        }
    }

    private void setupUI() {
        tvOriginalOwner.setText("Quoting from @" + ownerUid);

        etCaption.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                tvCharCount.setText(s.length() + "/150");
            }
            public void afterTextChanged(Editable e) {}
        });

        btnCancel.setOnClickListener(v -> finish());

        btnPublish.setOnClickListener(v -> publishQuoteRepost());
    }

    private void publishQuoteRepost() {
        String caption = etCaption.getText().toString().trim();
        if (caption.isEmpty()) {
            etCaption.setError("Caption required for quote repost");
            return;
        }
        progress.setVisibility(View.VISIBLE);
        btnPublish.setEnabled(false);

        repostManager.doRepost(reelId, ownerUid, caption, "quote", (isNow, err) -> {
            progress.setVisibility(View.GONE);
            btnPublish.setEnabled(true);
            if (err != null) {
                Toast.makeText(this, "Failed: " + err, Toast.LENGTH_SHORT).show();
                return;
            }
            // Send notification to original owner
            RepostNotificationWorker.enqueue(this, reelId, ownerUid,
                    repostManager.myUid(), repostManager.myName(),
                    repostManager.myPhoto(), reelThumb, caption);
            Toast.makeText(this, "Quote Repost published!", Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    @Override protected void onPause()  { super.onPause();  vvOriginal.pause(); }
    @Override protected void onResume() { super.onResume(); if (vvOriginal != null) vvOriginal.start(); }
    @Override protected void onDestroy(){ super.onDestroy(); vvOriginal.stopPlayback(); }
}
