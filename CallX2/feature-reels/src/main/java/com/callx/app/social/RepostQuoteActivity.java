package com.callx.app.social;

import android.content.Intent;
import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.workers.ReelRepostWorker;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.*;

/**
 * RepostQuoteActivity — Instagram-style Quote Repost.
 *
 * Creates a NEW reel record that embeds the original reel as a "quote card".
 * The original thumb + @creator + caption is shown inside the user's new post.
 *
 * Firebase writes:
 *  • reels/{newReelId}: full reel record with isQuoteRepost=true + repostedFromReelId
 *  • reels/{originalReelId}/quoteCount: +1 (transaction)
 *  • userReposts/{myUid}/{originalReelId}: timestamp
 *  • reel_notifications/{ownerUid}: in-app notification
 *
 * Production features:
 *  ✅ 280-char quote text with live char counter + emoji tray
 *  ✅ Embedded original reel card (thumb + avatar + caption excerpt)
 *  ✅ Audience: Everyone / Followers / Close Friends
 *  ✅ Rate limit (3s) + duplicate prevention
 *  ✅ Notifies original creator via ReelRepostWorker
 */
public class RepostQuoteActivity extends AppCompatActivity {

    public static final String EXTRA_REEL_ID    = "qr_reel_id";
    public static final String EXTRA_OWNER_UID  = "qr_owner_uid";
    public static final String EXTRA_OWNER_NAME = "qr_owner_name";
    public static final String EXTRA_THUMB_URL  = "qr_thumb_url";
    public static final String EXTRA_CAPTION    = "qr_caption";
    public static final String EXTRA_VIDEO_URL  = "qr_video_url";

    private static final int    MAX_QUOTE = 280;
    private static final long   RATE_MS   = 3000L;
    private static final String[] EMOJI_ROW = {"🔥","💯","❤️","😂","🤩","👀","🙌","💪","✨","🎬"};

    private EditText       etQuote;
    private TextView       tvChars;
    private CircleImageView ivOwnerAvatar;
    private TextView       tvOwner, tvOrigCaption;
    private ImageView      ivThumb;
    private Button         btnPost;
    private ProgressBar    progress;
    private RadioGroup     rgAudience;

    private String myUid, myName, reelId, ownerUid, ownerName, thumbUrl, origCaption, videoUrl;
    private long   lastPostMs = 0L;
    private boolean posted = false;

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        myUid      = FirebaseUtils.getCurrentUid();
        myName     = FirebaseUtils.getCurrentName();
        reelId     = getIntent().getStringExtra(EXTRA_REEL_ID);
        ownerUid   = getIntent().getStringExtra(EXTRA_OWNER_UID);
        ownerName  = getIntent().getStringExtra(EXTRA_OWNER_NAME);
        thumbUrl   = getIntent().getStringExtra(EXTRA_THUMB_URL);
        origCaption= getIntent().getStringExtra(EXTRA_CAPTION);
        videoUrl   = getIntent().getStringExtra(EXTRA_VIDEO_URL);

        if (reelId == null || myUid == null) { finish(); return; }
        if (myUid.equals(ownerUid)) {
            Toast.makeText(this, "You can\u2019t quote your own reel", Toast.LENGTH_SHORT).show();
            finish(); return;
        }
        buildUI();
        fetchOwnerAvatar();
    }

    private void buildUI() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0D0D0D);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0D0D0D);

        // ── Toolbar ──────────────────────────────────────────────────────────
        LinearLayout tb = new LinearLayout(this);
        tb.setOrientation(LinearLayout.HORIZONTAL);
        tb.setGravity(Gravity.CENTER_VERTICAL);
        tb.setBackgroundColor(0xFF161616);
        tb.setPadding(dp(4), 0, dp(12), 0);

        ImageButton btnBack = new ImageButton(this);
        btnBack.setImageResource(R.drawable.ic_close);
        btnBack.setBackground(null);
        btnBack.getDrawable().setTint(0xFFFFFFFF);
        btnBack.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        btnBack.setOnClickListener(v -> finish());
        tb.addView(btnBack);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("Quote Repost");
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTextSize(17);
        tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tvTitle.setPadding(dp(6), 0, 0, 0);
        tb.addView(tvTitle);

        btnPost = new Button(this);
        btnPost.setText("Post");
        btnPost.setTextColor(0xFFFFFFFF);
        btnPost.setBackgroundColor(0xFF4CAF50);
        btnPost.setPadding(dp(20), dp(8), dp(20), dp(8));
        btnPost.setOnClickListener(v -> doPost());
        tb.addView(btnPost);

        root.addView(tb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        View tbDiv = new View(this);
        tbDiv.setBackgroundColor(0xFF222222);
        root.addView(tbDiv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        // ── My avatar + quote input ───────────────────────────────────────────
        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setPadding(dp(12), dp(14), dp(12), 0);

        CircleImageView myAvatar = new CircleImageView(this);
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        avLp.setMarginEnd(dp(10));
        avLp.topMargin = dp(2);
        myAvatar.setImageResource(R.drawable.ic_person);
        inputRow.addView(myAvatar, avLp);
        // Load my Reels avatar (reels/users/{uid})
        com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("reels/users").child(myUid)
            .get().addOnSuccessListener(snap -> {
                String thumb = snap.child("thumbUrl").getValue(String.class);
                String photo = snap.child("photoUrl").getValue(String.class);
                String url = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                if (url != null && !url.isEmpty() && !isFinishing())
                    Glide.with(this).load(url).circleCrop().placeholder(R.drawable.ic_person).into(myAvatar);
            });

        LinearLayout inputCol = new LinearLayout(this);
        inputCol.setOrientation(LinearLayout.VERTICAL);
        inputCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvMyName = new TextView(this);
        tvMyName.setText(myName != null ? myName : "You");
        tvMyName.setTextColor(0xFFFFFFFF);
        tvMyName.setTextSize(14);
        tvMyName.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        inputCol.addView(tvMyName);

        etQuote = new EditText(this);
        etQuote.setHint("Add your thoughts\u2026");
        etQuote.setHintTextColor(0xFF555555);
        etQuote.setTextColor(0xFFEEEEEE);
        etQuote.setTextSize(16);
        etQuote.setBackground(null);
        etQuote.setMinLines(3);
        etQuote.setMaxLines(8);
        etQuote.setGravity(Gravity.TOP);
        etQuote.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                int left = MAX_QUOTE - s.length();
                tvChars.setText(left + " left");
                tvChars.setTextColor(left < 30 ? 0xFFFF3B5C : 0xFF888888);
                if (s.length() > MAX_QUOTE) {
                    etQuote.setText(s.subSequence(0, MAX_QUOTE));
                    etQuote.setSelection(MAX_QUOTE);
                }
            }
        });
        inputCol.addView(etQuote);

        // Emoji tray
        HorizontalScrollView emojiScroll = new HorizontalScrollView(this);
        LinearLayout emojiRow = new LinearLayout(this);
        emojiRow.setOrientation(LinearLayout.HORIZONTAL);
        emojiRow.setPadding(0, dp(4), 0, dp(4));
        for (String e : EMOJI_ROW) {
            TextView btn = new TextView(this);
            btn.setText(e); btn.setTextSize(22);
            btn.setPadding(dp(8), dp(4), dp(8), dp(4));
            btn.setOnClickListener(v -> {
                int p = etQuote.getSelectionStart();
                etQuote.getText().insert(p, e);
            });
            emojiRow.addView(btn);
        }
        emojiScroll.addView(emojiRow);
        inputCol.addView(emojiScroll);

        tvChars = new TextView(this);
        tvChars.setText(MAX_QUOTE + " left");
        tvChars.setTextColor(0xFF888888);
        tvChars.setTextSize(11);
        tvChars.setGravity(Gravity.END);
        inputCol.addView(tvChars);

        inputRow.addView(inputCol);
        root.addView(inputRow);

        // ── Original reel quote card ──────────────────────────────────────────
        LinearLayout quoteCard = new LinearLayout(this);
        quoteCard.setOrientation(LinearLayout.HORIZONTAL);
        quoteCard.setBackgroundColor(0xFF1A1A1A);
        quoteCard.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(dp(12), dp(12), dp(12), 0);
        // Green left border
        View accent = new View(this);
        accent.setBackgroundColor(0xFF4CAF50);
        LinearLayout.LayoutParams accentLp = new LinearLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT);
        accentLp.setMarginEnd(dp(10));
        quoteCard.addView(accent, accentLp);

        ivThumb = new ImageView(this);
        ivThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(dp(64), dp(96));
        thumbLp.setMarginEnd(dp(10));
        if (thumbUrl != null && !thumbUrl.isEmpty())
            Glide.with(this).load(thumbUrl).centerCrop().into(ivThumb);
        else ivThumb.setImageResource(R.drawable.ic_reels);
        quoteCard.addView(ivThumb, thumbLp);

        LinearLayout ownerCol = new LinearLayout(this);
        ownerCol.setOrientation(LinearLayout.VERTICAL);
        ownerCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout ownerRow = new LinearLayout(this);
        ownerRow.setOrientation(LinearLayout.HORIZONTAL);
        ownerRow.setGravity(Gravity.CENTER_VERTICAL);
        ivOwnerAvatar = new CircleImageView(this);
        LinearLayout.LayoutParams oavLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        oavLp.setMarginEnd(dp(4));
        ivOwnerAvatar.setImageResource(R.drawable.ic_person);
        ownerRow.addView(ivOwnerAvatar, oavLp);
        tvOwner = new TextView(this);
        tvOwner.setText("@" + (ownerName != null ? ownerName : "creator"));
        tvOwner.setTextColor(0xFFAAAAAA);
        tvOwner.setTextSize(12);
        tvOwner.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        ownerRow.addView(tvOwner);
        ownerCol.addView(ownerRow);

        tvOrigCaption = new TextView(this);
        tvOrigCaption.setText(origCaption != null && !origCaption.isEmpty() ? origCaption : "(no caption)");
        tvOrigCaption.setTextColor(0xFFCCCCCC);
        tvOrigCaption.setTextSize(13);
        tvOrigCaption.setMaxLines(3);
        tvOrigCaption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams capLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        capLp.topMargin = dp(4);
        ownerCol.addView(tvOrigCaption, capLp);

        TextView tvBadge = new TextView(this);
        tvBadge.setText("\uD83C\uDFAC Reel");
        tvBadge.setTextColor(0xFF4CAF50);
        tvBadge.setTextSize(11);
        tvBadge.setPadding(0, dp(6), 0, 0);
        ownerCol.addView(tvBadge);
        quoteCard.addView(ownerCol);
        root.addView(quoteCard, cardLp);

        // ── Audience selector ─────────────────────────────────────────────────
        LinearLayout audCard = new LinearLayout(this);
        audCard.setOrientation(LinearLayout.VERTICAL);
        audCard.setBackgroundColor(0xFF1A1A1A);
        audCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams audLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        audLp.setMargins(dp(12), dp(10), dp(12), dp(12));
        TextView tvAudLabel = new TextView(this);
        tvAudLabel.setText("Audience");
        tvAudLabel.setTextColor(0xFFFFFFFF);
        tvAudLabel.setTextSize(13);
        tvAudLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        audCard.addView(tvAudLabel);
        rgAudience = new RadioGroup(this);
        rgAudience.setOrientation(RadioGroup.HORIZONTAL);
        String[] opts = {"Everyone", "Followers", "Close Friends"};
        for (int i = 0; i < opts.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(opts[i]);
            rb.setTextColor(0xFFCCCCCC);
            rb.setId(View.generateViewId());
            if (i == 0) rb.setChecked(true);
            rgAudience.addView(rb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        audCard.addView(rgAudience);
        root.addView(audCard, audLp);

        // ── Progress ──────────────────────────────────────────────────────────
        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pLp.gravity = Gravity.CENTER_HORIZONTAL;
        pLp.setMargins(0, dp(8), 0, dp(20));
        root.addView(progress, pLp);

        scroll.addView(root);
        setContentView(scroll);
    }

    private void fetchOwnerAvatar() {
        if (ownerUid == null) return;
        // Load owner Reels avatar (reels/users/{uid})
        com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("reels/users").child(ownerUid)
            .get().addOnSuccessListener(snap -> {
                String thumb = snap.child("thumbUrl").getValue(String.class);
                String photo = snap.child("photoUrl").getValue(String.class);
                String url = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                if (url != null && !url.isEmpty() && !isFinishing() && ivOwnerAvatar != null)
                    Glide.with(this).load(url).circleCrop().placeholder(R.drawable.ic_person).into(ivOwnerAvatar);
            });
    }

    private void doPost() {
        long now = System.currentTimeMillis();
        if (now - lastPostMs < RATE_MS) { Toast.makeText(this, "Please wait\u2026", Toast.LENGTH_SHORT).show(); return; }
        if (posted) { Toast.makeText(this, "Already posted!", Toast.LENGTH_SHORT).show(); return; }
        String quote = etQuote.getText() != null ? etQuote.getText().toString().trim() : "";
        if (quote.isEmpty()) { Toast.makeText(this, "Add your thoughts first!", Toast.LENGTH_SHORT).show(); return; }

        lastPostMs = now;
        btnPost.setEnabled(false);
        progress.setVisibility(View.VISIBLE);

        // Determine audience
        RadioButton checked = rgAudience.findViewById(rgAudience.getCheckedRadioButtonId());
        String audience = "everyone";
        if (checked != null) {
            String t = checked.getText().toString().toLowerCase();
            if (t.contains("follower")) audience = "followers";
            else if (t.contains("close")) audience = "close_friends";
        }

        String newReelId = FirebaseUtils.getReelsRef().push().getKey();
        if (newReelId == null) { progress.setVisibility(View.GONE); btnPost.setEnabled(true); return; }

        Map<String, Object> data = new HashMap<>();
        data.put("reelId",             newReelId);
        data.put("uid",                myUid);
        data.put("ownerName",          myName != null ? myName : "");
        data.put("caption",            quote);
        data.put("thumbUrl",           thumbUrl   != null ? thumbUrl   : "");
        data.put("videoUrl",           videoUrl   != null ? videoUrl   : "");
        data.put("timestamp",          now);
        data.put("repostedFromReelId", reelId);
        data.put("repostedFromUid",    ownerUid   != null ? ownerUid   : "");
        data.put("repostedFromName",   ownerName  != null ? ownerName  : "");
        data.put("isQuoteRepost",      true);
        data.put("audienceType",       audience);
        data.put("likesCount",  0); data.put("commentsCount", 0); data.put("viewsCount", 0);

        FirebaseUtils.getReelsRef().child(newReelId).setValue(data)
            .addOnSuccessListener(u -> {
                if (isFinishing()) return;
                // Increment quoteCount on original reel
                FirebaseUtils.getReelsRef().child(reelId).child("quoteCount")
                    .runTransaction(new com.google.firebase.database.Transaction.Handler() {
                        @NonNull @Override
                        public com.google.firebase.database.Transaction.Result doTransaction(
                                @NonNull com.google.firebase.database.MutableData d) {
                            Integer c = d.getValue(Integer.class);
                            d.setValue(c != null ? c + 1 : 1);
                            return com.google.firebase.database.Transaction.success(d);
                        }
                        @Override public void onComplete(
                                com.google.firebase.database.DatabaseError e, boolean b,
                                com.google.firebase.database.DataSnapshot sn) {}
                    });
                // Mark in userReposts
                FirebaseUtils.db().getReference("userReposts").child(myUid).child(reelId).setValue(now);
                // Notify original creator
                if (ownerUid != null && !ownerUid.equals(myUid)) {
                    ReelRepostWorker.enqueue(this, reelId, myUid,
                        myName != null ? myName : "Someone",
                        ownerUid, ownerName != null ? ownerName : "",
                        thumbUrl != null ? thumbUrl : "", quote);
                }
                posted = true;
                progress.setVisibility(View.GONE);
                Toast.makeText(this, "Quote reposted! \uD83D\uDD01", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK, new Intent()
                    .putExtra("quoted", true)
                    .putExtra("newReelId", newReelId));
                finish();
            })
            .addOnFailureListener(ex -> {
                progress.setVisibility(View.GONE);
                btnPost.setEnabled(true);
                Toast.makeText(this, "Post failed: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
            });
    }

    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density); }
}
