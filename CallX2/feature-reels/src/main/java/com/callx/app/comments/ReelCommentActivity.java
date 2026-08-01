package com.callx.app.comments;

import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.appcompat.app.AppCompatActivity;

/**
 * ReelCommentActivity — thin host only (matches SoundDetailActivity's pattern).
 *
 * Sara logic ReelCommentFragment mein hai ("single source of truth" for the
 * reel comment UI — sort/search, edit/pin/report, reactions, replies, etc).
 * Yeh class sirf:
 *   1. Intent extras ko ReelCommentFragment ke args mein map karti hai
 *   2. Fragment ko fullscreen add karti hai (isSheet = false)
 *   3. Close callback ke roop mein finish() deti hai
 *
 * Koi duplicate code nahi. Comment count tap pe ab ReelCommentSheetFragment
 * khulti hai (see ReelPlayerFragment/ReelShareController) — yeh Activity
 * sirf un jagah use hoti hai jahan pehle se hi fullscreen comment screen
 * chahiye (HomeFragment, ReelUiController, deep links wagera).
 */
public class ReelCommentActivity extends AppCompatActivity {
    public static final String EXTRA_REEL_ID  = "reel_id";
    public static final String EXTRA_REEL_UID = "reel_uid";
    public static final String EXTRA_HIGHLIGHT_COMMENT_ID = "EXTRA_HIGHLIGHT_COMMENT_ID";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout frame = new FrameLayout(this);
        frame.setId(android.R.id.content);
        setContentView(frame, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (savedInstanceState == null) {
            String reelId  = getIntent().getStringExtra(EXTRA_REEL_ID);
            String reelUid = getIntent().getStringExtra(EXTRA_REEL_UID);
            String highlight = getIntent().getStringExtra(EXTRA_HIGHLIGHT_COMMENT_ID);

            ReelCommentFragment fragment = ReelCommentFragment.newInstance(
                reelId, reelUid, highlight, false /* isSheet = false */);
            fragment.setOnCloseListener(this::finish);

            getSupportFragmentManager()
                .beginTransaction()
                .replace(android.R.id.content, fragment)
                .commit();
        }
    }
}
