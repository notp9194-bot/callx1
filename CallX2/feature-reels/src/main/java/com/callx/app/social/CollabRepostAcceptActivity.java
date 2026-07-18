package com.callx.app.social;

import android.content.Intent;
import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import com.bumptech.glide.Glide;
import com.callx.app.models.CollabRepostModel;
import com.callx.app.models.ReelModel;
import com.callx.app.notifications.CollabRepostNotificationHelper;
import com.callx.app.reels.R;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.*;

/**
 * CollabRepostAcceptActivity — Collaborator reviews, adds their caption, then accepts or declines.
 *
 * Flow after opening:
 *  1. Shows the original reel preview + initiator's caption.
 *  2. Collaborator (User B) adds their own caption (optional).
 *  3. Taps ACCEPT  → creates the joint collab repost reel, notifies User A, both appear on both profiles.
 *  4. Taps DECLINE → marks invite declined, notifies User A.
 *
 * On ACCEPT, writes to Firebase:
 *  • reels/{newId}                              — new collab reel document (isCollabRepost=true)
 *  • reelsByUser/{initiatorUid}/{newId}         — appears on initiator's profile
 *  • reelsByUser/{collaboratorUid}/{newId}      — appears on collaborator's profile
 *  • collabReposts/{collabRepostId}             — canonical collab record (status=accepted)
 *  • collabRepostInvites/{collaboratorUid}/{id} — status=accepted
 *  • collabRepostSent/{initiatorUid}/{id}       — status=accepted + collabReelId
 *  • reel_notifications/{initiatorUid}/{notifId}— collab_accepted notification
 *  • reel_notifications/{originalOwnerUid}/...  — collab_repost_received (if different from initiator)
 *  • reels/{originalReelId}/collabRepostCount   — incremented
 *
 * WorkManager dispatches are used for notification delivery (kill-safe).
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class CollabRepostAcceptActivity extends AppCompatActivity {

    // ── Intent extras ────────────────────────────────────────────────────────
    public static final String EXTRA_COLLAB_REPOST_ID = "ca_collab_repost_id";
    public static final String EXTRA_REEL_ID          = "ca_reel_id";
    public static final String EXTRA_OWNER_UID        = "ca_owner_uid";
    public static final String EXTRA_OWNER_NAME       = "ca_owner_name";
    public static final String EXTRA_THUMB_URL        = "ca_thumb_url";
    public static final String EXTRA_VIDEO_URL        = "ca_video_url";
    public static final String EXTRA_ORIG_CAPTION     = "ca_orig_caption";
    public static final String EXTRA_INITIATOR_UID    = "ca_init_uid";
    public static final String EXTRA_INITIATOR_NAME   = "ca_init_name";
    public static final String EXTRA_INITIATOR_PHOTO  = "ca_init_photo";
    public static final String EXTRA_INITIATOR_CAP    = "ca_init_cap";
    public static final String EXTRA_AUDIENCE         = "ca_audience";
    public static final String EXTRA_MEDIA_TYPE       = "ca_media_type";

    private static final int MAX_CAPTION = 300;
    private static final String[] EMOJI_TRAY = {"🔥","❤️","😂","🎉","👏","💯","🙌","✨","🎬","🤝"};

    // ── Extras ─────────────────────────────────────────────────────────────────
    private String collabRepostId, originalReelId, originalOwnerUid, originalOwnerName;
    private String thumbUrl, videoUrl, originalCaption;
    private String initiatorUid, initiatorName, initiatorPhoto, initiatorCaption;
    private String audienceType, mediaType;

    // ── My info ────────────────────────────────────────────────────────────────
    private String myUid, myName, myPhoto;

    // ── UI ─────────────────────────────────────────────────────────────────────
    private PlayerView playerView;
    private ExoPlayer  player;
    private EditText   etMyCaption;
    private TextView   tvCharCount;
    private Button     btnAccept, btnDecline;
    private ProgressBar progressBar;

    private boolean actionTaken = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        collabRepostId  = getIntent().getStringExtra(EXTRA_COLLAB_REPOST_ID);
        originalReelId  = getIntent().getStringExtra(EXTRA_REEL_ID);
        originalOwnerUid = getIntent().getStringExtra(EXTRA_OWNER_UID);
        originalOwnerName = getIntent().getStringExtra(EXTRA_OWNER_NAME);
        thumbUrl        = getIntent().getStringExtra(EXTRA_THUMB_URL);
        videoUrl        = getIntent().getStringExtra(EXTRA_VIDEO_URL);
        originalCaption = getIntent().getStringExtra(EXTRA_ORIG_CAPTION);
        initiatorUid    = getIntent().getStringExtra(EXTRA_INITIATOR_UID);
        initiatorName   = getIntent().getStringExtra(EXTRA_INITIATOR_NAME);
        initiatorPhoto  = getIntent().getStringExtra(EXTRA_INITIATOR_PHOTO);
        initiatorCaption = getIntent().getStringExtra(EXTRA_INITIATOR_CAP);
        audienceType    = getIntent().getStringExtra(EXTRA_AUDIENCE);
        mediaType       = getIntent().getStringExtra(EXTRA_MEDIA_TYPE);

        if (collabRepostId == null || originalReelId == null) { finish(); return; }
        if (audienceType == null) audienceType = "everyone";
        if (mediaType == null)    mediaType    = "video";

        try {
            myUid  = FirebaseUtils.getCurrentUid();
            myName = FirebaseUtils.getCurrentName();
        } catch (Exception e) { finish(); return; }

        loadMyPhoto();
    }

    private void loadMyPhoto() {
        FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("reels/users").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    String t = s.child("thumbUrl").getValue(String.class);
                    String p = s.child("photoUrl").getValue(String.class);
                    myPhoto = (t != null && !t.isEmpty()) ? t : p;
                    buildLayout();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { buildLayout(); }
            });
    }

    private void buildLayout() {
        if (isFinishing() || isDestroyed()) return;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0D0D0D);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0D0D0D);

        // Toolbar
        LinearLayout tb = buildToolbar();
        root.addView(tb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        root.addView(divider());

        // Collab banner
        LinearLayout banner = buildBanner();
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bLp.setMargins(dp(12), dp(12), dp(12), 0);
        root.addView(banner, bLp);

        // Initiator's caption
        if (initiatorCaption != null && !initiatorCaption.isEmpty()) {
            LinearLayout icard = buildInitiatorCapCard();
            LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            iLp.setMargins(dp(12), dp(10), dp(12), 0);
            root.addView(icard, iLp);
        }

        // My caption
        LinearLayout myCap = buildMyCaptionCard();
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mLp.setMargins(dp(12), dp(10), dp(12), 0);
        root.addView(myCap, mLp);

        // What happens info
        root.addView(buildInfoCard(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{ setMargins(dp(12), dp(10), dp(12), 0); }});

        // Progress
        progressBar = new ProgressBar(this);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pLp.gravity = Gravity.CENTER_HORIZONTAL;
        pLp.topMargin = dp(8);
        root.addView(progressBar, pLp);

        // ACCEPT / DECLINE buttons
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(dp(12), dp(12), dp(12), dp(24));

        btnDecline = new Button(this);
        btnDecline.setText("✕ Decline");
        btnDecline.setTextColor(0xFFFF3B5C);
        btnDecline.setBackgroundColor(0xFF1A0A0F);
        btnDecline.setTextSize(15);
        btnDecline.setLayoutParams(new LinearLayout.LayoutParams(0, dp(50), 1f) {{ setMarginEnd(dp(8)); }});
        btnDecline.setOnClickListener(v -> doDecline());
        btnRow.addView(btnDecline);

        btnAccept = new Button(this);
        btnAccept.setText("✓ Accept & Post");
        btnAccept.setTextColor(0xFFFFFFFF);
        btnAccept.setBackgroundColor(0xFF7C3AED);
        btnAccept.setTextSize(15);
        btnAccept.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        btnAccept.setLayoutParams(new LinearLayout.LayoutParams(0, dp(50), 1.5f));
        btnAccept.setOnClickListener(v -> doAccept());
        btnRow.addView(btnAccept);

        root.addView(btnRow, rowLp);

        scroll.addView(root);
        setContentView(scroll);

        setupPlayer();
    }

    private LinearLayout buildToolbar() {
        LinearLayout tb = new LinearLayout(this);
        tb.setOrientation(LinearLayout.HORIZONTAL);
        tb.setGravity(Gravity.CENTER_VERTICAL);
        tb.setBackgroundColor(0xFF161616);
        tb.setPadding(dp(4), 0, dp(12), 0);

        ImageButton btnBack = new ImageButton(this);
        btnBack.setImageResource(R.drawable.ic_arrow_back);
        btnBack.setBackground(null);
        btnBack.getDrawable().setTint(0xFFFFFFFF);
        btnBack.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        btnBack.setOnClickListener(v -> finish());
        tb.addView(btnBack);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("Collab Repost Invite");
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTextSize(17);
        tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvTitle.setPadding(dp(6), 0, 0, 0);
        tb.addView(tvTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return tb;
    }

    private LinearLayout buildBanner() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xFF1A1A1A);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));

        // Avatar row
        LinearLayout avatarRow = new LinearLayout(this);
        avatarRow.setOrientation(LinearLayout.HORIZONTAL);
        avatarRow.setGravity(Gravity.CENTER_VERTICAL);

        CircleImageView ivInit = new CircleImageView(this);
        ivInit.setImageResource(R.drawable.ic_person);
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        avLp.setMarginEnd(dp(4));
        if (initiatorPhoto != null && !initiatorPhoto.isEmpty())
            Glide.with(this).load(initiatorPhoto).circleCrop()
                .override(96, 96)
                .placeholder(R.drawable.ic_person).into(ivInit);
        avatarRow.addView(ivInit, avLp);

        TextView tvPlus = new TextView(this);
        tvPlus.setText("🤝");
        tvPlus.setTextSize(18);
        tvPlus.setPadding(dp(2), 0, dp(2), 0);
        avatarRow.addView(tvPlus);

        CircleImageView ivMe = new CircleImageView(this);
        ivMe.setImageResource(R.drawable.ic_person);
        LinearLayout.LayoutParams avMeLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        avMeLp.leftMargin = dp(4);
        if (myPhoto != null && !myPhoto.isEmpty())
            Glide.with(this).load(myPhoto).circleCrop()
                .override(96, 96)
                .placeholder(R.drawable.ic_person).into(ivMe);
        avatarRow.addView(ivMe, avMeLp);

        LinearLayout infoCol = new LinearLayout(this);
        infoCol.setOrientation(LinearLayout.VERTICAL);
        infoCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        infoCol.setPadding(dp(10), 0, 0, 0);

        TextView tvNames = new TextView(this);
        tvNames.setText((initiatorName != null ? initiatorName : "Someone") + " + You");
        tvNames.setTextColor(0xFFE9D5FF);
        tvNames.setTextSize(14);
        tvNames.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        infoCol.addView(tvNames);

        TextView tvSub = new TextView(this);
        tvSub.setText("Collab Repost Invite");
        tvSub.setTextColor(0xFF9F7AEA);
        tvSub.setTextSize(12);
        infoCol.addView(tvSub);
        avatarRow.addView(infoCol);
        card.addView(avatarRow);

        // Reel preview row
        LinearLayout previewRow = new LinearLayout(this);
        previewRow.setOrientation(LinearLayout.HORIZONTAL);
        previewRow.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        prevLp.topMargin = dp(12);

        playerView = new PlayerView(this);
        playerView.setUseController(false);
        LinearLayout.LayoutParams pvLp = new LinearLayout.LayoutParams(dp(72), dp(108));
        pvLp.setMarginEnd(dp(10));
        previewRow.addView(playerView, pvLp);

        LinearLayout infoC = new LinearLayout(this);
        infoC.setOrientation(LinearLayout.VERTICAL);
        infoC.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvOwner = new TextView(this);
        tvOwner.setText("@" + (originalOwnerName != null ? originalOwnerName : "creator"));
        tvOwner.setTextColor(0xFFFFFFFF);
        tvOwner.setTextSize(13);
        tvOwner.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        infoC.addView(tvOwner);

        TextView tvCap = new TextView(this);
        tvCap.setText(originalCaption != null && !originalCaption.isEmpty()
            ? originalCaption : "(no caption)");
        tvCap.setTextColor(0xFFAAAAAA);
        tvCap.setTextSize(12);
        tvCap.setMaxLines(3);
        tvCap.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams capLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        capLp.topMargin = dp(4);
        infoC.addView(tvCap, capLp);

        TextView tvType = new TextView(this);
        tvType.setText("photo_slideshow".equals(mediaType) ? "🖼️ Photo Reel" : "🎬 Video Reel");
        tvType.setTextColor(0xFF7C3AED);
        tvType.setTextSize(11);
        tvType.setPadding(0, dp(6), 0, 0);
        infoC.addView(tvType);

        previewRow.addView(infoC);
        card.addView(previewRow, prevLp);
        return card;
    }

    private LinearLayout buildInitiatorCapCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xFF1A0D2E);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));

        TextView label = new TextView(this);
        label.setText("💬 " + (initiatorName != null ? initiatorName : "Initiator") + " says:");
        label.setTextColor(0xFFAA88FF);
        label.setTextSize(12);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        card.addView(label);

        TextView tvCap = new TextView(this);
        tvCap.setText("\"" + initiatorCaption + "\"");
        tvCap.setTextColor(0xFFE9D5FF);
        tvCap.setTextSize(13);
        tvCap.setPadding(dp(8), dp(6), dp(8), 0);
        card.addView(tvCap);
        return card;
    }

    private LinearLayout buildMyCaptionCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xFF1A1A1A);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView label = new TextView(this);
        label.setText("Your caption (optional)");
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(13);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        card.addView(label);

        etMyCaption = new EditText(this);
        etMyCaption.setHint("Add your own take on this collab…");
        etMyCaption.setHintTextColor(0xFF444444);
        etMyCaption.setTextColor(0xFFEEEEEE);
        etMyCaption.setTextSize(14);
        etMyCaption.setBackgroundColor(0xFF222222);
        etMyCaption.setPadding(dp(10), dp(10), dp(10), dp(10));
        etMyCaption.setMinLines(3);
        etMyCaption.setMaxLines(6);
        etMyCaption.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        etLp.topMargin = dp(8);

        etMyCaption.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                int left = MAX_CAPTION - s.length();
                tvCharCount.setText(left + " left");
                tvCharCount.setTextColor(left < 30 ? 0xFFFF3B5C : 0xFF888888);
                if (s.length() > MAX_CAPTION) {
                    etMyCaption.setText(s.subSequence(0, MAX_CAPTION));
                    etMyCaption.setSelection(MAX_CAPTION);
                }
            }
        });
        card.addView(etMyCaption, etLp);

        tvCharCount = new TextView(this);
        tvCharCount.setText(MAX_CAPTION + " left");
        tvCharCount.setTextColor(0xFF888888);
        tvCharCount.setTextSize(11);
        tvCharCount.setGravity(Gravity.END);
        card.addView(tvCharCount);

        // Emoji tray
        HorizontalScrollView emojiScroll = new HorizontalScrollView(this);
        LinearLayout emojiRow = new LinearLayout(this);
        emojiRow.setOrientation(LinearLayout.HORIZONTAL);
        emojiRow.setPadding(0, dp(4), 0, dp(4));
        for (String e : EMOJI_TRAY) {
            TextView btn = new TextView(this);
            btn.setText(e); btn.setTextSize(20);
            btn.setPadding(dp(8), dp(4), dp(8), dp(4));
            btn.setOnClickListener(v -> {
                int p = etMyCaption.getSelectionStart();
                etMyCaption.getText().insert(p, e);
            });
            emojiRow.addView(btn);
        }
        emojiScroll.addView(emojiRow);
        card.addView(emojiScroll);
        return card;
    }

    private LinearLayout buildInfoCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xFF111120);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView tv = new TextView(this);
        tv.setText("ℹ️ If you accept:\n" +
            "• This collab repost appears on both your profiles\n" +
            "• You and " + (initiatorName != null ? initiatorName : "the initiator") + " are shown as co-authors\n" +
            "• The original creator receives a notification\n" +
            "• Audience: " + audienceType);
        tv.setTextColor(0xFF9999BB);
        tv.setTextSize(12);
        tv.setLineSpacing(0, 1.4f);
        card.addView(tv);
        return card;
    }

    // ── Player ────────────────────────────────────────────────────────────────
    private void setupPlayer() {
        if (videoUrl == null || videoUrl.isEmpty()) {
            if (playerView != null && thumbUrl != null && !thumbUrl.isEmpty()) {
                ImageView overlay = new ImageView(this);
                overlay.setScaleType(ImageView.ScaleType.CENTER_CROP);
                Glide.with(this).load(thumbUrl).centerCrop().override(720, 720).into(overlay);
                playerView.addView(overlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            return;
        }
        try {
            player = new ExoPlayer.Builder(this).build();
            playerView.setPlayer(player);
            player.setMediaItem(MediaItem.fromUri(videoUrl));
            player.setRepeatMode(ExoPlayer.REPEAT_MODE_ONE);
            player.setVolume(0f);
            player.prepare();
            player.play();
        } catch (Exception ignored) {
            if (thumbUrl != null && !thumbUrl.isEmpty()) {
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                Glide.with(this).load(thumbUrl).centerCrop().override(720, 720).into(iv);
                if (playerView != null)
                    playerView.addView(iv, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
        }
    }

    // ── Accept ─────────────────────────────────────────────────────────────────
    private void doAccept() {
        if (actionTaken) return;
        actionTaken = true;

        String myCaption = etMyCaption.getText() != null ? etMyCaption.getText().toString().trim() : "";

        btnAccept.setEnabled(false);
        btnDecline.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);

        // Create new collab reel document
        // NOTE: must use the SAME path the rest of the app uses for reels (FirebaseUtils.getReelsRef()
        // = "reels/{reelId}"), NOT "reels/videos/...". Writing to the wrong path silently created the
        // collab reel in a location no profile/feed screen ever reads from.
        DatabaseReference newReelRef = com.callx.app.utils.FirebaseUtils.getReelsRef().push();
        String newReelId = newReelRef.getKey();
        if (newReelId == null) {
            resetButtons(); return;
        }

        long now = System.currentTimeMillis();

        // Build collab reel data
        Map<String, Object> reelData = new HashMap<>();
        reelData.put("reelId",             newReelId);
        reelData.put("uid",                initiatorUid);        // primary author = initiator
        reelData.put("ownerName",          initiatorName != null ? initiatorName : "");
        reelData.put("videoUrl",           videoUrl      != null ? videoUrl      : "");
        reelData.put("thumbUrl",           thumbUrl      != null ? thumbUrl      : "");
        reelData.put("caption",            buildJointCaption(myCaption));
        reelData.put("mediaType",          mediaType);
        reelData.put("timestamp",          now);
        reelData.put("likesCount",         0);
        reelData.put("commentsCount",      0);
        reelData.put("sharesCount",        0);
        reelData.put("viewsCount",         0);
        reelData.put("repostCount",        0);
        reelData.put("collabRepostCount",  0);
        reelData.put("allowReposts",       true);
        // Collab repost flags
        reelData.put("isCollabRepost",         true);
        reelData.put("collabRepostId",         collabRepostId);
        reelData.put("repostedFromReelId",     originalReelId);
        reelData.put("repostedFromUid",        originalOwnerUid  != null ? originalOwnerUid  : "");
        reelData.put("repostedFromName",       originalOwnerName != null ? originalOwnerName : "");
        reelData.put("originalCaption",        originalCaption   != null ? originalCaption   : "");
        // Co-author data
        reelData.put("collabInitiatorUid",     initiatorUid   != null ? initiatorUid   : "");
        reelData.put("collabInitiatorName",    initiatorName  != null ? initiatorName  : "");
        reelData.put("collabInitiatorPhoto",   initiatorPhoto != null ? initiatorPhoto : "");
        reelData.put("collabInitiatorCaption", initiatorCaption != null ? initiatorCaption : "");
        reelData.put("collabColaboratorUid",   myUid);
        reelData.put("collabCollaboratorName", myName  != null ? myName  : "");
        reelData.put("collabCollaboratorPhoto",myPhoto != null ? myPhoto : "");
        reelData.put("collabCollaboratorCaption", myCaption);
        reelData.put("audienceType",           audienceType);

        // Batch all Firebase writes atomically
        DatabaseReference root = FirebaseDatabase.getInstance(Constants.DB_URL).getReference();
        Map<String, Object> updates = new HashMap<>();

        // 1. New reel document — must match FirebaseUtils.getReelsRef() = "reels/{reelId}"
        updates.put("reels/" + newReelId, reelData);

        // 2. Appears on initiator's profile — must match FirebaseUtils.getReelsByUserRef(uid)
        //    = "reelsByUser/{uid}/{reelId}", same path UserReelsActivity/AllReelsFullActivity query.
        updates.put("reelsByUser/" + initiatorUid + "/" + newReelId, true);

        // 3. Appears on collaborator's profile
        updates.put("reelsByUser/" + myUid + "/" + newReelId, true);

        // 4. Update collab invite status
        updates.put("collabRepostInvites/" + myUid + "/" + collabRepostId + "/status",      "accepted");
        updates.put("collabRepostInvites/" + myUid + "/" + collabRepostId + "/collabReelId", newReelId);
        updates.put("collabRepostInvites/" + myUid + "/" + collabRepostId + "/acceptedAt",  now);
        updates.put("collabRepostInvites/" + myUid + "/" + collabRepostId + "/collaboratorCaption", myCaption);

        // 5. Update sent invite
        updates.put("collabRepostSent/" + initiatorUid + "/" + collabRepostId + "/status",      "accepted");
        updates.put("collabRepostSent/" + initiatorUid + "/" + collabRepostId + "/collabReelId", newReelId);
        updates.put("collabRepostSent/" + initiatorUid + "/" + collabRepostId + "/acceptedAt",  now);
        updates.put("collabRepostSent/" + initiatorUid + "/" + collabRepostId + "/collaboratorCaption", myCaption);

        // 6. Canonical collab record
        updates.put("collabReposts/" + collabRepostId + "/status",                  "accepted");
        updates.put("collabReposts/" + collabRepostId + "/collabReelId",             newReelId);
        updates.put("collabReposts/" + collabRepostId + "/acceptedAt",              now);
        updates.put("collabReposts/" + collabRepostId + "/collaboratorCaption",     myCaption);
        updates.put("collabReposts/" + collabRepostId + "/collabCollaboratorUid",   myUid);
        updates.put("collabReposts/" + collabRepostId + "/collabCollaboratorName",  myName != null ? myName : "");
        updates.put("collabReposts/" + collabRepostId + "/collabCollaboratorPhoto", myPhoto != null ? myPhoto : "");

        // 7. Notification for initiator (collab_accepted)
        String notifId1 = root.child("reel_notifications").child(initiatorUid).push().getKey();
        if (notifId1 != null) {
            Map<String, Object> notif1 = new HashMap<>();
            notif1.put("type",       "collab_repost_accepted");
            notif1.put("senderUid",  myUid);
            notif1.put("senderName", myName != null ? myName : "");
            notif1.put("reel_id",    originalReelId);
            notif1.put("newReelId",  newReelId);
            notif1.put("collabRepostId", collabRepostId);
            notif1.put("thumbUrl",   thumbUrl != null ? thumbUrl : "");
            notif1.put("timestamp",  now);
            notif1.put("seen",       false);
            updates.put("reel_notifications/" + initiatorUid + "/" + notifId1, notif1);
        }

        // 8. Notification for original creator (if different from initiator)
        if (originalOwnerUid != null && !originalOwnerUid.isEmpty()
            && !originalOwnerUid.equals(initiatorUid)) {
            String notifId2 = root.child("reel_notifications").child(originalOwnerUid).push().getKey();
            if (notifId2 != null) {
                Map<String, Object> notif2 = new HashMap<>();
                notif2.put("type",           "collab_repost_received");
                notif2.put("senderUid",      initiatorUid != null ? initiatorUid : "");
                notif2.put("senderName",     initiatorName != null ? initiatorName : "");
                notif2.put("collaboratorUid", myUid);
                notif2.put("collaboratorName", myName != null ? myName : "");
                notif2.put("reel_id",        originalReelId);
                notif2.put("newReelId",      newReelId);
                notif2.put("thumbUrl",       thumbUrl != null ? thumbUrl : "");
                notif2.put("timestamp",      now);
                notif2.put("seen",           false);
                updates.put("reel_notifications/" + originalOwnerUid + "/" + notifId2, notif2);
            }
        }

        // 9. Increment collabRepostCount on original reel
        root.child("reels/" + originalReelId + "/collabRepostCount").runTransaction(
            new Transaction.Handler() {
                @NonNull @Override public Transaction.Result doTransaction(@NonNull MutableData d) {
                    Integer c = d.getValue(Integer.class);
                    d.setValue(c != null ? c + 1 : 1);
                    return Transaction.success(d);
                }
                @Override public void onComplete(DatabaseError e, boolean b, DataSnapshot s) {}
            });

        root.updateChildren(updates)
            .addOnSuccessListener(v -> {
                if (isFinishing() || isDestroyed()) return;

                // WorkManager: notify initiator (kill-safe push)
                com.callx.app.workers.CollabRepostWorker.enqueueAccepted(
                    this, collabRepostId, newReelId, originalReelId,
                    myUid, myName != null ? myName : "",
                    myPhoto != null ? myPhoto : "",
                    initiatorUid, thumbUrl != null ? thumbUrl : ""
                );

                progressBar.setVisibility(View.GONE);
                Toast.makeText(this,
                    "🎉 Collab repost published! It's on both profiles.",
                    Toast.LENGTH_LONG).show();

                setResult(RESULT_OK, new Intent().putExtra("accepted", true).putExtra("collab_reel_id", newReelId));
                finish();
            })
            .addOnFailureListener(ex -> {
                actionTaken = false;
                progressBar.setVisibility(View.GONE);
                resetButtons();
                Toast.makeText(this, "Error: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
            });
    }

    // ── Decline ────────────────────────────────────────────────────────────────
    private void doDecline() {
        if (actionTaken) return;
        actionTaken = true;

        btnAccept.setEnabled(false);
        btnDecline.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);

        long now = System.currentTimeMillis();
        DatabaseReference root = FirebaseDatabase.getInstance(Constants.DB_URL).getReference();
        Map<String, Object> updates = new HashMap<>();

        updates.put("collabRepostInvites/" + myUid + "/" + collabRepostId + "/status",      "declined");
        updates.put("collabRepostInvites/" + myUid + "/" + collabRepostId + "/declinedAt",  now);
        updates.put("collabRepostSent/" + initiatorUid + "/" + collabRepostId + "/status",  "declined");
        updates.put("collabRepostSent/" + initiatorUid + "/" + collabRepostId + "/declinedAt", now);
        updates.put("collabReposts/" + collabRepostId + "/status",     "declined");
        updates.put("collabReposts/" + collabRepostId + "/declinedAt", now);

        // Notify initiator
        String notifId = root.child("reel_notifications").child(initiatorUid).push().getKey();
        if (notifId != null) {
            Map<String, Object> notif = new HashMap<>();
            notif.put("type",       "collab_repost_declined");
            notif.put("senderUid",  myUid);
            notif.put("senderName", myName != null ? myName : "");
            notif.put("reel_id",    originalReelId);
            notif.put("collabRepostId", collabRepostId);
            notif.put("thumbUrl",   thumbUrl != null ? thumbUrl : "");
            notif.put("timestamp",  now);
            notif.put("seen",       false);
            updates.put("reel_notifications/" + initiatorUid + "/" + notifId, notif);
        }

        root.updateChildren(updates)
            .addOnSuccessListener(v -> {
                if (isFinishing() || isDestroyed()) return;
                com.callx.app.workers.CollabRepostWorker.enqueueDeclined(
                    this, collabRepostId, originalReelId,
                    myUid, myName != null ? myName : "",
                    initiatorUid, thumbUrl != null ? thumbUrl : ""
                );
                progressBar.setVisibility(View.GONE);
                Toast.makeText(this, "Invite declined.", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK, new Intent().putExtra("declined", true));
                finish();
            })
            .addOnFailureListener(ex -> {
                actionTaken = false;
                progressBar.setVisibility(View.GONE);
                resetButtons();
                Toast.makeText(this, "Error: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
            });
    }

    private void resetButtons() {
        if (btnAccept  != null) btnAccept.setEnabled(true);
        if (btnDecline != null) btnDecline.setEnabled(true);
    }

    /** Builds a combined caption like "Init cap | My cap" for display on the reel. */
    private String buildJointCaption(String myCaption) {
        boolean hasInit = initiatorCaption != null && !initiatorCaption.isEmpty();
        boolean hasMine = myCaption != null && !myCaption.isEmpty();
        if (hasInit && hasMine)  return initiatorCaption + " | " + myCaption;
        if (hasInit)             return initiatorCaption;
        if (hasMine)             return myCaption;
        return originalCaption != null ? originalCaption : "";
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0xFF222222);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    private int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density);
    }

    @Override protected void onPause() {
        if (player != null) player.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }
}
