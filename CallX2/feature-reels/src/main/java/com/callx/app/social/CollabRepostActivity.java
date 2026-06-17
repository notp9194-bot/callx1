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
import androidx.recyclerview.widget.*;
import com.bumptech.glide.Glide;
import com.callx.app.models.CollabRepostModel;
import com.callx.app.notifications.CollabRepostNotificationHelper;
import com.callx.app.reels.R;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.ReelDailyRepostQuotaHelper;
import com.callx.app.workers.CollabRepostWorker;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.*;

/**
 * CollabRepostActivity — Initiate a Collaborative Repost.
 *
 * Flow:
 *  1. User A opens this from the share-sheet or more-menu of any reel.
 *  2. Sees the original reel preview + their own caption field.
 *  3. Searches for a collaborator (User B) by username.
 *  4. Taps "Send Collab Invite" → Firebase writes the invite.
 *  5. User B receives a push notification.
 *
 * Production features:
 *  ✅ Live video preview (muted loop) with thumbnail fallback
 *  ✅ Original reel attribution card
 *  ✅ 300-char initiator caption with live counter + emoji tray
 *  ✅ User search — searches reels/users by displayName or handle
 *  ✅ Selected collaborator chip with avatar + remove button
 *  ✅ Audience selector: Everyone / Followers / Close Friends
 *  ✅ Daily quota check (respects ReelDailyRepostQuotaHelper limits)
 *  ✅ 3-second anti-spam rate limit
 *  ✅ No self-invite guard
 *  ✅ No repost-of-repost guard (original reels only)
 *  ✅ allowReposts=false guard
 *  ✅ WorkManager-backed invite dispatch (kill-safe)
 *  ✅ In-app + push notification to collaborator
 *
 * Launch from ReelShareSheetActivity or ReelMoreBottomSheet:
 *   Intent i = new Intent(ctx, CollabRepostActivity.class);
 *   i.putExtra(EXTRA_REEL_ID,        reel.reelId);
 *   i.putExtra(EXTRA_OWNER_UID,      reel.uid);
 *   i.putExtra(EXTRA_OWNER_NAME,     reel.ownerName);
 *   i.putExtra(EXTRA_THUMB_URL,      reel.thumbUrl);
 *   i.putExtra(EXTRA_VIDEO_URL,      reel.videoUrl);
 *   i.putExtra(EXTRA_CAPTION,        reel.caption);
 *   i.putExtra(EXTRA_ALLOW_REPOSTS,  reel.allowReposts);
 *   i.putExtra(EXTRA_MEDIA_TYPE,     reel.mediaType);
 *   startActivity(i);
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class CollabRepostActivity extends AppCompatActivity {

    // ── Intent extras ────────────────────────────────────────────────────────
    public static final String EXTRA_REEL_ID       = "cr_reel_id";
    public static final String EXTRA_OWNER_UID     = "cr_owner_uid";
    public static final String EXTRA_OWNER_NAME    = "cr_owner_name";
    public static final String EXTRA_THUMB_URL     = "cr_thumb_url";
    public static final String EXTRA_VIDEO_URL     = "cr_video_url";
    public static final String EXTRA_CAPTION       = "cr_caption";
    public static final String EXTRA_ALLOW_REPOSTS = "cr_allow_reposts";
    public static final String EXTRA_MEDIA_TYPE    = "cr_media_type";

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final int    MAX_CAPTION   = 300;
    private static final long   RATE_LIMIT_MS = 3000L;
    private static final String[] EMOJI_TRAY  = {"🔥","❤️","😂","🎉","👏","💯","🙌","✨","🎬","🤝"};
    private static final int    SEARCH_LIMIT  = 20;

    // ── UI refs ───────────────────────────────────────────────────────────────
    private PlayerView    playerView;
    private ExoPlayer     player;
    private ImageView     ivThumb;
    private TextView      tvOrigOwner, tvOrigCaption;
    private EditText      etMyCaption, etSearch;
    private TextView      tvCharCount;
    private LinearLayout  llCollaboratorChip;
    private CircleImageView ivCollabAvatar;
    private TextView      tvCollabName, tvSearching;
    private RecyclerView  rvSearch;
    private RadioGroup    rgAudience;
    private Button        btnSendInvite;
    private ProgressBar   progressSend;

    // ── State ─────────────────────────────────────────────────────────────────
    private String  myUid, myName, myPhoto;
    private String  reelId, ownerUid, ownerName, thumbUrl, videoUrl, originalCaption, mediaType;
    private boolean allowReposts;
    private String  selectedCollabUid   = null;
    private String  selectedCollabName  = null;
    private String  selectedCollabPhoto = null;
    private long    lastSendMs          = 0L;
    private boolean inviteSent          = false;
    private final android.os.Handler     searchHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable                      pendingSearch = null;
    private int                           pendingQueries = 0;   // tracks in-flight Firebase queries

    // ── Search adapter ────────────────────────────────────────────────────────
    private final List<CollabUserItem> searchResults = new ArrayList<>();
    private CollabUserSearchAdapter    searchAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Extras
        reelId          = getIntent().getStringExtra(EXTRA_REEL_ID);
        ownerUid        = getIntent().getStringExtra(EXTRA_OWNER_UID);
        ownerName       = getIntent().getStringExtra(EXTRA_OWNER_NAME);
        thumbUrl        = getIntent().getStringExtra(EXTRA_THUMB_URL);
        videoUrl        = getIntent().getStringExtra(EXTRA_VIDEO_URL);
        originalCaption = getIntent().getStringExtra(EXTRA_CAPTION);
        allowReposts    = getIntent().getBooleanExtra(EXTRA_ALLOW_REPOSTS, true);
        mediaType       = getIntent().getStringExtra(EXTRA_MEDIA_TYPE);
        if (mediaType == null) mediaType = "video";

        if (reelId == null || reelId.isEmpty()) { finish(); return; }
        if (!allowReposts) {
            Toast.makeText(this, "This creator has disabled reposts.", Toast.LENGTH_SHORT).show();
            finish(); return;
        }

        try {
            myUid  = FirebaseUtils.getCurrentUid();
            myName = FirebaseUtils.getCurrentName();
        } catch (Exception e) { finish(); return; }

        if (myUid.equals(ownerUid)) {
            Toast.makeText(this, "You cannot collab repost your own reel.", Toast.LENGTH_SHORT).show();
            finish(); return;
        }

        // Block repost-of-repost
        checkAndBuild();
    }

    private void checkAndBuild() {
        // Check if this reel is itself a repost (chain guard)
        FirebaseUtils.getReelsRef().child(reelId).child("repostedFromReelId")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String srcId = snap.getValue(String.class);
                    if (srcId != null && !srcId.isEmpty()) {
                        if (!isFinishing())
                            Toast.makeText(CollabRepostActivity.this,
                                "Cannot collab repost a repost. Only original reels can be reposted.",
                                Toast.LENGTH_LONG).show();
                        finish(); return;
                    }
                    // Also load my photo
                    FirebaseDatabase.getInstance(Constants.DB_URL)
                        .getReference("reels/users").child(myUid)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot u) {
                                String t = u.child("thumbUrl").getValue(String.class);
                                String p = u.child("photoUrl").getValue(String.class);
                                myPhoto = (t != null && !t.isEmpty()) ? t : p;
                                buildLayout();
                            }
                            @Override public void onCancelled(@NonNull DatabaseError e) { buildLayout(); }
                        });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { buildLayout(); }
            });
    }

    // ── Build layout programmatically (no XML dependency) ────────────────────
    @SuppressWarnings("deprecation")
    private void buildLayout() {
        if (isFinishing() || isDestroyed()) return;

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
        btnBack.setImageResource(R.drawable.ic_arrow_back);
        btnBack.setBackground(null);
        btnBack.getDrawable().setTint(0xFFFFFFFF);
        btnBack.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        btnBack.setOnClickListener(v -> finish());
        tb.addView(btnBack);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("Collab Repost");
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTextSize(17);
        tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tvTitle.setLayoutParams(titleLp);
        tvTitle.setPadding(dp(6), 0, 0, 0);
        tb.addView(tvTitle);

        root.addView(tb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        // Divider
        addDivider(root);

        // ── Original reel card ───────────────────────────────────────────────
        LinearLayout origCard = buildOriginalReelCard();
        LinearLayout.LayoutParams origLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        origLp.setMargins(dp(12), dp(12), dp(12), 0);
        root.addView(origCard, origLp);

        // ── My Caption ───────────────────────────────────────────────────────
        LinearLayout captionCard = buildCaptionCard();
        LinearLayout.LayoutParams capLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        capLp.setMargins(dp(12), dp(10), dp(12), 0);
        root.addView(captionCard, capLp);

        // ── Find Collaborator ─────────────────────────────────────────────────
        LinearLayout collabCard = buildCollabSearchCard();
        LinearLayout.LayoutParams collabLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        collabLp.setMargins(dp(12), dp(10), dp(12), 0);
        root.addView(collabCard, collabLp);

        // ── Audience ─────────────────────────────────────────────────────────
        LinearLayout audCard = buildAudienceCard();
        LinearLayout.LayoutParams audLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        audLp.setMargins(dp(12), dp(10), dp(12), 0);
        root.addView(audCard, audLp);

        // ── Send button ───────────────────────────────────────────────────────
        btnSendInvite = new Button(this);
        btnSendInvite.setText("🤝 Send Collab Invite");
        btnSendInvite.setTextColor(0xFFFFFFFF);
        btnSendInvite.setBackgroundColor(0xFF7C3AED);
        btnSendInvite.setTextSize(16);
        btnSendInvite.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        btnLp.setMargins(dp(12), dp(16), dp(12), 0);
        btnSendInvite.setOnClickListener(v -> doSendInvite());
        root.addView(btnSendInvite, btnLp);

        // Progress
        progressSend = new ProgressBar(this);
        progressSend.setVisibility(View.GONE);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pLp.gravity = Gravity.CENTER_HORIZONTAL;
        pLp.setMargins(0, dp(8), 0, dp(24));
        root.addView(progressSend, pLp);

        scroll.addView(root);
        setContentView(scroll);

        setupPlayer();
    }

    private LinearLayout buildOriginalReelCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xFF1A1A1A);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));

        // Badge
        TextView badge = new TextView(this);
        badge.setText("🔁 Collab reposting from @" + (ownerName != null ? ownerName : "user"));
        badge.setTextColor(0xFF7C3AED);
        badge.setTextSize(12);
        badge.setPadding(0, 0, 0, dp(8));
        card.addView(badge);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        // Thumbnail / player
        playerView = new PlayerView(this);
        playerView.setUseController(false);
        LinearLayout.LayoutParams pvLp = new LinearLayout.LayoutParams(dp(72), dp(108));
        pvLp.setMarginEnd(dp(12));
        playerView.setLayoutParams(pvLp);
        row.addView(playerView, pvLp);

        ivThumb = new ImageView(this);
        ivThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (thumbUrl != null && !thumbUrl.isEmpty())
            Glide.with(this).load(thumbUrl).centerCrop().into(ivThumb);
        else ivThumb.setImageResource(R.drawable.ic_reels);

        LinearLayout infoCol = new LinearLayout(this);
        infoCol.setOrientation(LinearLayout.VERTICAL);
        infoCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tvOrigOwner = new TextView(this);
        tvOrigOwner.setText("@" + (ownerName != null ? ownerName : "creator"));
        tvOrigOwner.setTextColor(0xFFFFFFFF);
        tvOrigOwner.setTextSize(14);
        tvOrigOwner.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        infoCol.addView(tvOrigOwner);

        tvOrigCaption = new TextView(this);
        tvOrigCaption.setText(originalCaption != null && !originalCaption.isEmpty()
            ? originalCaption : "(no caption)");
        tvOrigCaption.setTextColor(0xFFAAAAAA);
        tvOrigCaption.setTextSize(12);
        tvOrigCaption.setMaxLines(3);
        tvOrigCaption.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams capLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        capLp.topMargin = dp(4);
        infoCol.addView(tvOrigCaption, capLp);

        // Media type badge
        TextView mediaTypeBadge = new TextView(this);
        mediaTypeBadge.setText("photo_slideshow".equals(mediaType) ? "🖼️ Photo Reel" : "🎬 Video Reel");
        mediaTypeBadge.setTextColor(0xFF7C3AED);
        mediaTypeBadge.setTextSize(11);
        mediaTypeBadge.setPadding(0, dp(6), 0, 0);
        infoCol.addView(mediaTypeBadge);

        row.addView(infoCol);
        card.addView(row);
        return card;
    }

    private LinearLayout buildCaptionCard() {
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
        etMyCaption.setHint("Say something about this collab…");
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

        // Char counter
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
        LinearLayout.LayoutParams emojiLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        emojiLp.topMargin = dp(4);
        card.addView(emojiScroll, emojiLp);
        return card;
    }

    private LinearLayout buildCollabSearchCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xFF1A1A1A);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView label = new TextView(this);
        label.setText("Find Collaborator");
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(13);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        card.addView(label);

        // Collaborator chip (shown after selection)
        llCollaboratorChip = new LinearLayout(this);
        llCollaboratorChip.setOrientation(LinearLayout.HORIZONTAL);
        llCollaboratorChip.setGravity(Gravity.CENTER_VERTICAL);
        llCollaboratorChip.setBackgroundColor(0xFF2A1A3E);
        llCollaboratorChip.setPadding(dp(10), dp(8), dp(10), dp(8));
        llCollaboratorChip.setVisibility(View.GONE);
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.topMargin = dp(8);
        chipLp.bottomMargin = dp(6);

        ivCollabAvatar = new CircleImageView(this);
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        avLp.setMarginEnd(dp(10));
        ivCollabAvatar.setImageResource(R.drawable.ic_person);
        llCollaboratorChip.addView(ivCollabAvatar, avLp);

        LinearLayout chipInfo = new LinearLayout(this);
        chipInfo.setOrientation(LinearLayout.VERTICAL);
        chipInfo.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tvCollabName = new TextView(this);
        tvCollabName.setTextColor(0xFFE9D5FF);
        tvCollabName.setTextSize(13);
        tvCollabName.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        chipInfo.addView(tvCollabName);

        TextView tvCollabSub = new TextView(this);
        tvCollabSub.setText("Co-author");
        tvCollabSub.setTextColor(0xFF9F7AEA);
        tvCollabSub.setTextSize(11);
        chipInfo.addView(tvCollabSub);
        llCollaboratorChip.addView(chipInfo);

        ImageButton btnRemoveCollab = new ImageButton(this);
        btnRemoveCollab.setImageResource(R.drawable.ic_close);
        btnRemoveCollab.setBackground(null);
        btnRemoveCollab.getDrawable().setTint(0xFFE9D5FF);
        btnRemoveCollab.setLayoutParams(new LinearLayout.LayoutParams(dp(32), dp(32)));
        btnRemoveCollab.setOnClickListener(v -> clearCollaborator());
        llCollaboratorChip.addView(btnRemoveCollab);
        card.addView(llCollaboratorChip, chipLp);

        // Search field
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setBackgroundColor(0xFF222222);
        searchRow.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        searchLp.topMargin = dp(8);

        ImageView searchIcon = new ImageView(this);
        searchIcon.setImageResource(R.drawable.ic_search);
        searchIcon.getDrawable().setTint(0xFF888888);
        searchIcon.setLayoutParams(new LinearLayout.LayoutParams(dp(20), dp(20)));
        searchRow.addView(searchIcon);

        etSearch = new EditText(this);
        etSearch.setHint("Search by name or @handle");
        etSearch.setHintTextColor(0xFF555555);
        etSearch.setTextColor(0xFFEEEEEE);
        etSearch.setTextSize(14);
        etSearch.setBackground(null);
        etSearch.setSingleLine(true);
        LinearLayout.LayoutParams etSLp = new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        etSLp.leftMargin = dp(8);
        etSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                  String query = s.toString().trim();
                  if (pendingSearch != null) searchHandler.removeCallbacks(pendingSearch);
                  if (query.length() < 2) {
                      searchResults.clear();
                      if (searchAdapter != null) searchAdapter.notifyDataSetChanged();
                      if (tvSearching != null) tvSearching.setVisibility(View.GONE);
                      return;
                  }
                  pendingSearch = () -> searchUsers(query);
                  searchHandler.postDelayed(pendingSearch, 300);
              }
        });
        searchRow.addView(etSearch, etSLp);
        card.addView(searchRow, searchLp);

        // Searching indicator
        tvSearching = new TextView(this);
        tvSearching.setTextColor(0xFF888888);
        tvSearching.setTextSize(12);
        tvSearching.setVisibility(View.GONE);
        tvSearching.setPadding(0, dp(4), 0, 0);
        card.addView(tvSearching);

        // Results list
        rvSearch = new RecyclerView(this);
        rvSearch.setLayoutManager(new LinearLayoutManager(this));
        searchAdapter = new CollabUserSearchAdapter(searchResults, this::selectCollaborator);
        rvSearch.setAdapter(searchAdapter);
        rvSearch.setNestedScrollingEnabled(false);
        LinearLayout.LayoutParams rvLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rvLp.topMargin = dp(4);
        card.addView(rvSearch, rvLp);
        return card;
    }

    private LinearLayout buildAudienceCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xFF1A1A1A);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView label = new TextView(this);
        label.setText("Collab Repost Audience");
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(13);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        card.addView(label);

        TextView sub = new TextView(this);
        sub.setText("Both collaborators' audiences will see this repost.");
        sub.setTextColor(0xFF888888);
        sub.setTextSize(11);
        sub.setPadding(0, dp(2), 0, dp(8));
        card.addView(sub);

        rgAudience = new RadioGroup(this);
        rgAudience.setOrientation(RadioGroup.HORIZONTAL);
        String[] opts = {"Everyone", "Followers", "Close Friends"};
        for (int i = 0; i < opts.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(opts[i]);
            rb.setTextColor(0xFFCCCCCC);
            rb.setId(View.generateViewId());
            if (i == 0) rb.setChecked(true);
            rgAudience.addView(rb, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        card.addView(rgAudience);
        return card;
    }

    // ── ExoPlayer for video preview ───────────────────────────────────────────
    private void setupPlayer() {
        if (videoUrl == null || videoUrl.isEmpty()) return;
        try {
            player = new ExoPlayer.Builder(this).build();
            playerView.setPlayer(player);
            player.setMediaItem(MediaItem.fromUri(videoUrl));
            player.setRepeatMode(ExoPlayer.REPEAT_MODE_ONE);
            player.setVolume(0f);
            player.prepare();
            player.play();
        } catch (Exception ignored) {
            if (ivThumb != null && thumbUrl != null && !thumbUrl.isEmpty())
                Glide.with(this).load(thumbUrl).centerCrop().into(ivThumb);
        }
    }

    // ── User search (case-insensitive, multi-query, 300 ms debounce) ──────────
      /**
       * Firebase Realtime DB orderByChild is CASE-SENSITIVE.
       * We fire 4 prefix variants (lower, Title, UPPER, original) for both
       * displayName and handle fields in parallel, then merge on the main thread.
       * Client-side .contains() ensures partial matches work even across styles.
       */
      private void searchUsers(String query) {
          if (tvSearching != null) {
              tvSearching.setText("Searching…");
              tvSearching.setVisibility(View.VISIBLE);
          }

          final String raw    = query.trim().replace("@", "");
          if (raw.isEmpty()) { if (tvSearching != null) tvSearching.setVisibility(View.GONE); return; }

          final String qLower = raw.toLowerCase();
          final String qTitle = Character.toUpperCase(raw.charAt(0)) + raw.substring(1).toLowerCase();
          final String qUpper = raw.toUpperCase();

          // Unique prefix variants (LinkedHashSet deduplicates identical strings)
          final java.util.Set<String> variants = new java.util.LinkedHashSet<>();
          variants.add(qLower);
          variants.add(qTitle);
          variants.add(qUpper);
          variants.add(raw);

          final String[] fields = {"displayName", "handle"};
          final java.util.List<String[]> pairs = new java.util.ArrayList<>();
          for (String field : fields)
              for (String v : variants)
                  pairs.add(new String[]{field, v});

          final int total = pairs.size();
          final java.util.concurrent.atomic.AtomicInteger done =
              new java.util.concurrent.atomic.AtomicInteger(0);
          // Accumulator shared across callbacks — accessed only on main thread via searchHandler.post()
          final java.util.List<CollabUserItem> acc = new java.util.ArrayList<>();

          for (String[] pair : pairs) {
              final String field  = pair[0];
              final String prefix = pair[1];
              FirebaseDatabase.getInstance(Constants.DB_URL)
                  .getReference("reels/users")
                  .orderByChild(field)
                  .startAt(prefix).endAt(prefix + "\uf8ff")
                  .limitToFirst(SEARCH_LIMIT)
                  .addListenerForSingleValueEvent(new ValueEventListener() {
                      @Override public void onDataChange(@NonNull DataSnapshot snap) {
                          searchHandler.post(() -> {
                              if (isFinishing() || isDestroyed()) return;
                              for (DataSnapshot s : snap.getChildren()) {
                                  String uid = s.getKey();
                                  if (uid == null || uid.equals(myUid)) continue;
                                  // Client-side case-insensitive partial match
                                  String storedName   = safe(s.child("displayName").getValue(String.class));
                                  String storedHandle = safe(s.child("handle").getValue(String.class));
                                  if (!storedName.toLowerCase().contains(qLower) &&
                                      !storedHandle.toLowerCase().contains(qLower)) continue;
                                  // Dedup
                                  boolean dup = false;
                                  for (CollabUserItem x : acc)
                                      if (x.uid.equals(uid)) { dup = true; break; }
                                  if (!dup) {
                                      CollabUserItem u = parseUser(s, uid);
                                      if (u != null) acc.add(u);
                                  }
                              }
                              if (done.incrementAndGet() == total) finalizeSearch(acc, qLower);
                          });
                      }
                      @Override public void onCancelled(@NonNull DatabaseError e) {
                          searchHandler.post(() -> {
                              if (done.incrementAndGet() == total) finalizeSearch(acc, qLower);
                          });
                      }
                  });
          }
      }

      /** Sorts and shows results once all parallel queries have returned. */
      private void finalizeSearch(java.util.List<CollabUserItem> acc, String qLower) {
          if (isFinishing() || isDestroyed()) return;
          // Sort: exact match > prefix match > contains
          acc.sort((a2, b2) -> searchScore(b2, qLower) - searchScore(a2, qLower));
          searchResults.clear();
          for (int i = 0; i < Math.min(acc.size(), SEARCH_LIMIT); i++)
              searchResults.add(acc.get(i));
          if (tvSearching != null) tvSearching.setVisibility(View.GONE);
          if (searchAdapter != null) searchAdapter.notifyDataSetChanged();
          if (searchResults.isEmpty() && tvSearching != null) {
              tvSearching.setText("No users found for \"" + qLower + "\"");
              tvSearching.setVisibility(View.VISIBLE);
          }
      }

      private int searchScore(CollabUserItem u, String q) {
          String n = u.name.toLowerCase(), h = u.handle.toLowerCase();
          if (n.equals(q) || h.equals(q))         return 3;
          if (n.startsWith(q) || h.startsWith(q)) return 2;
          if (n.contains(q)   || h.contains(q))   return 1;
          return 0;
      }

      private CollabUserItem parseUser(DataSnapshot s, String uid) {
        try {
            String name  = s.child("displayName").getValue(String.class);
            String handle = s.child("handle").getValue(String.class);
            String thumb = s.child("thumbUrl").getValue(String.class);
            String photo = s.child("photoUrl").getValue(String.class);
            if (name == null || name.isEmpty()) name = handle != null ? handle : uid;
            String photoUrl = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
            return new CollabUserItem(uid, name, handle != null ? handle : "", photoUrl != null ? photoUrl : "");
        } catch (Exception e) { return null; }
    }

    private void selectCollaborator(CollabUserItem user) {
        selectedCollabUid   = user.uid;
        selectedCollabName  = user.name;
        selectedCollabPhoto = user.photo;

        // Update chip
        tvCollabName.setText("@" + (user.handle.isEmpty() ? user.name : user.handle));
        if (!user.photo.isEmpty())
            Glide.with(this).load(user.photo).circleCrop()
                .placeholder(R.drawable.ic_person).into(ivCollabAvatar);
        else
            ivCollabAvatar.setImageResource(R.drawable.ic_person);
        llCollaboratorChip.setVisibility(View.VISIBLE);

        // Hide search results
        searchResults.clear();
        searchAdapter.notifyDataSetChanged();
        if (etSearch != null) etSearch.setText("");
        if (tvSearching != null) tvSearching.setVisibility(View.GONE);

        // Dismiss keyboard
        android.view.inputmethod.InputMethodManager imm =
            (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && etSearch != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
    }

    private void clearCollaborator() {
        selectedCollabUid = selectedCollabName = selectedCollabPhoto = null;
        llCollaboratorChip.setVisibility(View.GONE);
    }

    // ── Send Invite ───────────────────────────────────────────────────────────
    private void doSendInvite() {
        long now = System.currentTimeMillis();
        if (now - lastSendMs < RATE_LIMIT_MS) {
            Toast.makeText(this, "Please wait before sending again.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (inviteSent) {
            Toast.makeText(this, "Invite already sent!", Toast.LENGTH_SHORT).show();
            return;
        }
        if (selectedCollabUid == null || selectedCollabUid.isEmpty()) {
            Toast.makeText(this, "Select a collaborator first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (selectedCollabUid.equals(myUid)) {
            Toast.makeText(this, "You can't collab with yourself.", Toast.LENGTH_SHORT).show();
            return;
        }
        lastSendMs = now;

        String myCaption = etMyCaption.getText() != null ? etMyCaption.getText().toString().trim() : "";
        String audience  = getSelectedAudience();

        btnSendInvite.setEnabled(false);
        progressSend.setVisibility(View.VISIBLE);

        // Daily quota check
        ReelDailyRepostQuotaHelper.checkAndIncrement(myUid, (allowed, count, limit) -> {
            if (!allowed) {
                runOnUiThread(() -> {
                    progressSend.setVisibility(View.GONE);
                    btnSendInvite.setEnabled(true);
                    Toast.makeText(this,
                        "Daily repost limit reached (" + count + "/" + limit + "). Try again tomorrow.",
                        Toast.LENGTH_LONG).show();
                });
                return;
            }
            runOnUiThread(() -> writeInviteToFirebase(myCaption, audience, now));
        });
    }

    private void writeInviteToFirebase(String myCaption, String audience, long now) {
        DatabaseReference inviteRef = FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("collabRepostInvites")
            .child(selectedCollabUid)
            .push();

        String inviteId = inviteRef.getKey();
        if (inviteId == null) {
            progressSend.setVisibility(View.GONE);
            btnSendInvite.setEnabled(true);
            Toast.makeText(this, "Error creating invite. Try again.", Toast.LENGTH_SHORT).show();
            return;
        }

        CollabRepostModel model = new CollabRepostModel(
            inviteId,
            reelId,
            ownerUid   != null ? ownerUid   : "",
            ownerName  != null ? ownerName  : "",
            thumbUrl   != null ? thumbUrl   : "",
            videoUrl   != null ? videoUrl   : "",
            originalCaption != null ? originalCaption : "",
            myUid,
            myName  != null ? myName  : "",
            myPhoto != null ? myPhoto : "",
            myCaption,
            selectedCollabUid,
            selectedCollabName != null ? selectedCollabName : "",
            audience,
            mediaType
        );

        inviteRef.setValue(model.toMap())
            .addOnSuccessListener(unused -> {
                if (isFinishing() || isDestroyed()) return;

                // Mirror in initiator's sent invites
                FirebaseDatabase.getInstance(Constants.DB_URL)
                    .getReference("collabRepostSent")
                    .child(myUid)
                    .child(inviteId)
                    .setValue(model.toMap());

                // Dispatch WorkManager worker (kill-safe notification delivery)
                CollabRepostWorker.enqueueInvite(
                    this, inviteId, reelId,
                    myUid, myName != null ? myName : "",
                    myPhoto != null ? myPhoto : "",
                    myCaption,
                    selectedCollabUid,
                    selectedCollabName != null ? selectedCollabName : "",
                    ownerUid != null ? ownerUid : "",
                    ownerName != null ? ownerName : "",
                    thumbUrl != null ? thumbUrl : ""
                );

                inviteSent = true;
                progressSend.setVisibility(View.GONE);
                Toast.makeText(this,
                    "Collab invite sent to @" + selectedCollabName + "! 🤝",
                    Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK, new Intent().putExtra("invite_sent", true));
                finish();
            })
            .addOnFailureListener(ex -> {
                progressSend.setVisibility(View.GONE);
                btnSendInvite.setEnabled(true);
                Toast.makeText(this, "Failed: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
            });
    }

    private String getSelectedAudience() {
        if (rgAudience == null) return "everyone";
        RadioButton rb = rgAudience.findViewById(rgAudience.getCheckedRadioButtonId());
        if (rb == null) return "everyone";
        String t = rb.getText().toString().toLowerCase();
        if (t.contains("follower")) return "followers";
        if (t.contains("close"))    return "close_friends";
        return "everyone";
    }

    private void addDivider(LinearLayout parent) {
        View div = new View(this);
        div.setBackgroundColor(0xFF222222);
        parent.addView(div, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override protected void onPause() {
        if (player != null) player.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }

    // ── Data model ─────────────────────────────────────────────────────────────
    static class CollabUserItem {
        final String uid, name, handle, photo;
        CollabUserItem(String uid, String name, String handle, String photo) {
            this.uid = uid; this.name = name; this.handle = handle; this.photo = photo;
        }
    }

    // ── Search adapter ────────────────────────────────────────────────────────
    interface OnUserSelected { void onSelect(CollabUserItem user); }

    static class CollabUserSearchAdapter
            extends RecyclerView.Adapter<CollabUserSearchAdapter.VH> {
        private final List<CollabUserItem> items;
        private final OnUserSelected       listener;

        CollabUserSearchAdapter(List<CollabUserItem> items, OnUserSelected listener) {
            this.items = items; this.listener = listener;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(parent, 14), dp(parent, 10), dp(parent, 14), dp(parent, 10));
            row.setClickable(true);
            row.setFocusable(true);
            row.setBackground(parent.getContext().getResources()
                .getDrawable(android.R.drawable.list_selector_background));

            CircleImageView av = new CircleImageView(parent.getContext());
            av.setTag("av");
            LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(dp(parent, 40), dp(parent, 40));
            avLp.setMarginEnd(dp(parent, 12));
            av.setImageResource(R.drawable.ic_person);
            row.addView(av, avLp);

            LinearLayout col = new LinearLayout(parent.getContext());
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView tvN = new TextView(parent.getContext());
            tvN.setTag("name");
            tvN.setTextColor(0xFFFFFFFF);
            tvN.setTextSize(14);
            tvN.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            col.addView(tvN);

            TextView tvH = new TextView(parent.getContext());
            tvH.setTag("handle");
            tvH.setTextColor(0xFF888888);
            tvH.setTextSize(12);
            col.addView(tvH);

            row.addView(col);

            TextView badge = new TextView(parent.getContext());
            badge.setText("Invite");
            badge.setTextColor(0xFF7C3AED);
            badge.setTextSize(12);
            badge.setPadding(dp(parent, 10), dp(parent, 4), dp(parent, 10), dp(parent, 4));
            badge.setBackgroundColor(0xFF2A1A3E);
            row.addView(badge);
            return new VH(row);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            CollabUserItem u = items.get(pos);
            h.tvName.setText(u.name);
            h.tvHandle.setText(u.handle.isEmpty() ? "" : "@" + u.handle);
            if (!u.photo.isEmpty())
                Glide.with(h.av).load(u.photo).circleCrop()
                    .placeholder(R.drawable.ic_person).into(h.av);
            else h.av.setImageResource(R.drawable.ic_person);
            h.itemView.setOnClickListener(v -> listener.onSelect(u));
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            CircleImageView av; TextView tvName, tvHandle;
            VH(View v) {
                super(v);
                av       = v.findViewWithTag("av");
                tvName   = v.findViewWithTag("name");
                tvHandle = v.findViewWithTag("handle");
            }
        }

        private static int dp(ViewGroup p, int v) {
            return (int)(v * p.getContext().getResources().getDisplayMetrics().density);
        }
    }
}
