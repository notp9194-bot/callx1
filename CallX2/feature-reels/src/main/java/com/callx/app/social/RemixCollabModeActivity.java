package com.callx.app.social;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.callx.app.reels.R;

/**
 * v790 — Screen 2 "Choose Your Mode". Remix = use this post with your own changes (permission level of the
 * post decides whether approval is needed). Collab = create together with the original creator (always needs
 * his approval, and he becomes a co-creator of the result). Next → Select Post (the entry post is pre-selected).
 */
public class RemixCollabModeActivity extends AppCompatActivity {

    private String mode = RemixCollabTarget.MODE_REMIX;
    private View cardRemix, cardCollab;
    private ImageView checkRemix, checkCollab;
    private TextView tvNote;
    private RemixCollabTarget target;

    @Override
    protected void onCreate(@Nullable Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_remix_collab_mode);

        target = RemixCollabTarget.from(getIntent());
        String m = getIntent().getStringExtra(RemixCollabTarget.X_MODE);
        if (RemixCollabTarget.MODE_COLLAB.equals(m)) mode = RemixCollabTarget.MODE_COLLAB;
        if (b != null && b.getString("mode") != null) mode = b.getString("mode");

        cardRemix   = findViewById(R.id.card_remix);
        cardCollab  = findViewById(R.id.card_collab);
        checkRemix  = findViewById(R.id.iv_check_remix);
        checkCollab = findViewById(R.id.iv_check_collab);
        tvNote      = findViewById(R.id.tv_note);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        cardRemix.setOnClickListener(v -> select(RemixCollabTarget.MODE_REMIX));
        cardCollab.setOnClickListener(v -> select(RemixCollabTarget.MODE_COLLAB));
        findViewById(R.id.btn_next).setOnClickListener(v -> onNext());
        select(mode);
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("mode", mode);
    }

    private void select(String m) {
        mode = m;
        boolean remix = RemixCollabTarget.MODE_REMIX.equals(m);
        cardRemix.setBackgroundResource(remix ? R.drawable.rc_bg_card_selected : R.drawable.rc_bg_card);
        cardCollab.setBackgroundResource(remix ? R.drawable.rc_bg_card : R.drawable.rc_bg_card_selected);
        checkRemix.setBackgroundResource(remix ? R.drawable.rc_bg_check_on : R.drawable.rc_bg_check_off);
        checkCollab.setBackgroundResource(remix ? R.drawable.rc_bg_check_off : R.drawable.rc_bg_check_on);
        // the check mark is only visible on the selected card
        checkRemix.setImageAlpha(remix ? 255 : 0);
        checkCollab.setImageAlpha(remix ? 0 : 255);
        tvNote.setText(remix
                ? "If the creator needs approval for remixes, we'll ask first. Your remix always credits the original."
                : "The original creator will get notified and must approve. You'll both be credited as co-creators.");
    }

    private void onNext() {
        Intent i = new Intent(this, RemixSelectPostActivity.class);
        target.put(i);
        i.putExtra(RemixCollabTarget.X_MODE, mode);
        startActivity(i);
    }
}
