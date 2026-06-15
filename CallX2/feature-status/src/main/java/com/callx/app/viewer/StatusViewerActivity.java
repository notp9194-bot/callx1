package com.callx.app.viewer;
  import android.animation.Animator;
  import android.animation.AnimatorListenerAdapter;
  import android.animation.ObjectAnimator;
  import android.animation.ValueAnimator;
  import android.content.Intent;
  import android.graphics.Color;
  import android.net.Uri;
  import android.os.Bundle;
  import android.os.Handler;
  import android.os.Looper;
  import android.text.TextUtils;
  import android.view.*;
  import android.view.animation.*;
  import android.widget.*;
  import androidx.annotation.NonNull;
  import androidx.annotation.OptIn;
  import androidx.appcompat.app.AlertDialog;
  import androidx.appcompat.app.AppCompatActivity;
  import androidx.media3.common.*;
  import androidx.media3.common.util.UnstableApi;
  import androidx.media3.datasource.cache.CacheDataSource;
  import androidx.media3.exoplayer.ExoPlayer;
  import androidx.media3.exoplayer.source.ProgressiveMediaSource;
  import com.bumptech.glide.Glide;
  import com.callx.app.cache.StatusVideoCacheManager;
  import com.callx.app.status.R;
  import com.callx.app.status.databinding.ActivityStatusViewerBinding;
  import com.callx.app.models.StatusItem;
  import com.callx.app.utils.*;
  import com.google.android.material.bottomsheet.BottomSheetDialog;
  import com.google.firebase.database.*;
  import java.util.*;
  import com.callx.app.highlights.StatusAddToHighlightBottomSheet;
  import com.callx.app.analytics.StatusAnalyticsBottomSheet;
  import com.callx.app.interactions.StatusDeleteConfirmBottomSheet;
  import com.callx.app.interactions.StatusForwardBottomSheet;
  import com.callx.app.interactions.StatusReactionBottomSheet;
  import com.callx.app.interactions.StatusReplyBottomSheet;
  import com.callx.app.interactions.StatusSeenByBottomSheet;
  import com.callx.app.utils.StatusCloseFriendsManager;
  import com.callx.app.utils.StatusDownloadHelper;
  import com.callx.app.utils.StatusHighlightManager;
  import com.callx.app.utils.StatusMentionHelper;
  import com.callx.app.utils.StatusMuteManager;
  import com.callx.app.utils.StatusSeenTracker;
  /**
   * StatusViewerActivity v26 — Modern story/status viewer.
   *
   * MODERN FEATURES v26:
   *   NEW: Double-tap ❤️ — double-tap anywhere → fires heart reaction + floating heart animation
   *   NEW: Auto-advance to next contact — pass EXTRA_CONTACT_UIDS list; advances when current ends
   *   NEW: Poll type — renders options with live vote counts + tap to vote
   *   NEW: Question box type — viewer submits an answer sent to owner
   *   NEW: Music strip — animated ♫ marquee strip at top when musicTitle set
   *   NEW: Floating emoji animation — emoji floats up + fades on any reaction
   *   NEW: Swipe-up link — upward fling on swipe-up zone opens linkUrl
   *   NEW: Privacy badge — shows who can see this status under timestamp
   *
   * BUG FIXES v26:
   *   FIX: btn_download / btn_forward — was findViewWithTag() (broken), now binding refs
   *   FIX: tv_location_tag / tv_expiry_label — same fix
   *   FIX: sendReplyToChat — chatKey() → getChatId(), chats → getMessagesRef()
   *   FIX: showOwnerMoreMenu — "Who viewed this" added as option 0
   */
  public class StatusViewerActivity extends AppCompatActivity {
      public static final String EXTRA_OWNER_UID     = "ownerUid";
      public static final String EXTRA_OWNER_NAME    = "ownerName";
      /** Optional: ArrayList<String> of contact UIDs to auto-advance through */
      public static final String EXTRA_CONTACT_UIDS  = "contactUids";
      /** Optional: ArrayList<String> matching names for EXTRA_CONTACT_UIDS */
      public static final String EXTRA_CONTACT_NAMES = "contactNames";

      private ActivityStatusViewerBinding binding;
      private final List<StatusItem> items         = new ArrayList<>();
      private final List<String>     seenInSession  = new ArrayList<>();
      private int     idx         = 0;
      private ExoPlayer player;
      private final Handler  handler      = new Handler(Looper.getMainLooper());
      private Runnable       progressRunner;
      private boolean        paused       = false;
      private long           remainingMs  = 0;
      private boolean        isMuted      = false;
      private long           viewStartTime = 0;
      private String myUid, ownerUid, ownerName;
      private final List<ProgressBar> segmentBars = new ArrayList<>();
      private GestureDetector gestureDetector;
      // Auto-advance
      private ArrayList<String> contactUids;
      private ArrayList<String> contactNames;
      private int contactIdx = 0;

      // ── Lifecycle ─────────────────────────────────────────────────────────
      @Override
      protected void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);
          binding = ActivityStatusViewerBinding.inflate(getLayoutInflater());
          setContentView(binding.getRoot());
          getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
          getWindow().getDecorView().setSystemUiVisibility(
              View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
              | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
          ownerUid  = getIntent().getStringExtra(EXTRA_OWNER_UID);
          ownerName = getIntent().getStringExtra(EXTRA_OWNER_NAME);
          contactUids  = getIntent().getStringArrayListExtra(EXTRA_CONTACT_UIDS);
          contactNames = getIntent().getStringArrayListExtra(EXTRA_CONTACT_NAMES);
          if (contactUids != null) {
              int pos = contactUids.indexOf(ownerUid);
              contactIdx = Math.max(0, pos);
          }
          if (ownerUid == null) { finish(); return; }
          try { myUid = FirebaseUtils.getCurrentUid(); } catch (Exception e) { myUid = null; }
          setupGestures();
          setupTouchZones();
          setupCloseButton();
          setupReactionButton();
          setupReplyButton();
          setupMoreButton();
          setupMuteButton();
          setupDownloadButton();
          setupForwardButton();
          binding.tvOwner.setText(ownerName != null ? ownerName : "Status");
          if (StatusCloseFriendsManager.isCloseFriend(this, ownerUid))
              binding.tvOwner.setText("\u2B50 " + (ownerName != null ? ownerName : "Status"));
          load(ownerUid);
      }
      @Override protected void onPause()  { super.onPause();  pauseProgress(); }
      @Override protected void onResume() { super.onResume(); if (paused) resumeProgress(); }
      @Override protected void onDestroy() {
          releasePlayer(); stopProgress();
          handler.removeCallbacksAndMessages(null);
          getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
          if (!seenInSession.isEmpty() && ownerUid != null) {
              String thumb = "";
              if (!items.isEmpty()) {
                  StatusItem first = items.get(0);
                  if (first.thumbnailUrl != null) thumb = first.thumbnailUrl;
                  else if (first.mediaUrl != null && "image".equals(first.type)) thumb = first.mediaUrl;
              }
              StatusSeenTracker.markSeenBatch(ownerUid, seenInSession, ownerName != null ? ownerName : "", thumb);
          }
          if (viewStartTime > 0 && idx < items.size()) {
              StatusItem cur = items.get(idx);
              if (cur.id != null) StatusSeenTracker.recordViewDuration(ownerUid, cur.id,
                      System.currentTimeMillis() - viewStartTime);
          }
          super.onDestroy();
      }

      // ── Load ──────────────────────────────────────────────────────────────
      private void load(String uid) {
          FirebaseUtils.getStatusRef().child(uid)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      long now = System.currentTimeMillis();
                      for (DataSnapshot c : snap.getChildren()) {
                          StatusItem s = c.getValue(StatusItem.class);
                          if (s == null || s.deleted) continue;
                          if (s.expiresAt != null && s.expiresAt < now) continue;
                          items.add(s);
                      }
                      if (items.isEmpty()) { advanceToNextContact(); return; }
                      items.sort((a, b) -> Long.compare(
                              a.timestamp == null ? 0 : a.timestamp,
                              b.timestamp == null ? 0 : b.timestamp));
                      StatusItem first = items.get(0);
                      if (first.ownerPhoto != null)
                          Glide.with(StatusViewerActivity.this).load(first.ownerPhoto)
                               .circleCrop().into(binding.ivOwner);
                      buildSegmentBars(); showCurrent();
                      boolean isOwner = myUid != null && myUid.equals(ownerUid);
                      binding.btnDownload.setVisibility(isOwner ? View.GONE : View.VISIBLE);
                      binding.btnForward.setVisibility(isOwner ? View.GONE : View.VISIBLE);
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) { advanceToNextContact(); }
              });
      }

      // ── NEW: Auto-advance to next contact ────────────────────────────────
      private void advanceToNextContact() {
          if (contactUids == null || contactUids.isEmpty()) { finish(); return; }
          contactIdx++;
          if (contactIdx >= contactUids.size()) { finish(); return; }
          // Reset state for new contact
          items.clear(); seenInSession.clear(); idx = 0;
          releasePlayer(); stopProgress();
          ownerUid  = contactUids.get(contactIdx);
          ownerName = contactNames != null && contactIdx < contactNames.size()
                    ? contactNames.get(contactIdx) : "";
          binding.tvOwner.setText(ownerName);
          if (StatusCloseFriendsManager.isCloseFriend(this, ownerUid))
              binding.tvOwner.setText("\u2B50 " + ownerName);
          // Slide-in from right animation
          binding.getRoot().setTranslationX(binding.getRoot().getWidth());
          binding.getRoot().animate().translationX(0).setDuration(250).start();
          load(ownerUid);
      }

      // ── Segment bars ─────────────────────────────────────────────────────
      private void buildSegmentBars() {
          binding.segmentsContainer.removeAllViews();
          segmentBars.clear();
          int count = items.size();
          for (int i = 0; i < count; i++) {
              ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
              pb.setMax(1000); pb.setProgress(0);
              LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dpToPx(2), 1f);
              lp.setMarginEnd(i < count - 1 ? dpToPx(3) : 0);
              pb.setLayoutParams(lp);
              pb.getProgressDrawable().setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
              binding.segmentsContainer.addView(pb); segmentBars.add(pb);
          }
      }
      private void fillSegmentsBefore(int i) {
          for (int j = 0; j < segmentBars.size(); j++) segmentBars.get(j).setProgress(j < i ? 1000 : 0);
      }

      // ── Show current item ─────────────────────────────────────────────────
      private void showCurrent() {
          if (idx >= items.size()) { advanceToNextContact(); return; }
          StatusItem s = items.get(idx);
          if (viewStartTime > 0 && idx > 0) {
              StatusItem prev = items.get(idx - 1);
              if (prev.id != null) StatusSeenTracker.recordViewDuration(ownerUid, prev.id,
                      System.currentTimeMillis() - viewStartTime);
          }
          viewStartTime = System.currentTimeMillis();
          fillSegmentsBefore(idx);
          updateHeaderTimestamp(s);
          updateSeenByInfo(s);
          updateExpiryLabel(s);
          updatePrivacyBadge(s);
          updateMusicStrip(s);
          updateSwipeUpHint(s);
          crossFadeIn();
          if (s.id != null && !s.id.isEmpty() && myUid != null && !myUid.equals(ownerUid))
              if (!seenInSession.contains(s.id)) seenInSession.add(s.id);
          binding.btnMute.setVisibility(View.GONE);
          hideAllContent();
          switch (s.type != null ? s.type : "") {
              case "text":          showTextStatus(s); break;
              case "image":         if (s.mediaUrl != null) showImageStatusFromUrl(s.mediaUrl, s.caption); break;
              case "video": case "reel_story": case "reel_clip":
                  if (s.mediaUrl != null) { showVideoStatus(s); break; }
                  if (s.thumbnailUrl != null) { showImageStatusFromUrl(s.thumbnailUrl, s.caption); break; }
                  next(); break;
              case "link":          showLinkStatus(s); break;
              case "gif": case "sticker": showGifStatus(s); break;
              case "poll":          showPollStatus(s); break;
              case "question_box":  showQuestionBoxStatus(s); break;
              default: next();
          }
      }

      // ── Content renderers ─────────────────────────────────────────────────
      private void showTextStatus(StatusItem s) {
          binding.flTextStatus.setVisibility(View.VISIBLE);
          binding.tvTextStatus.setVisibility(View.VISIBLE);
          binding.tvTextStatus.setText(StatusMentionHelper.highlight(s.text != null ? s.text : ""));
          applyBackground(s);
          applyFontStyle(binding.tvTextStatus, s.fontStyle);
          if (s.textColor != null) try { binding.tvTextStatus.setTextColor(Color.parseColor(s.textColor)); } catch (Exception ignored){}
          if (s.textSize > 0) binding.tvTextStatus.setTextSize(s.textSize);
          if (s.textAlign != null) switch (s.textAlign) {
              case "left":  binding.tvTextStatus.setGravity(Gravity.START|Gravity.CENTER_VERTICAL); break;
              case "right": binding.tvTextStatus.setGravity(Gravity.END|Gravity.CENTER_VERTICAL); break;
              default:      binding.tvTextStatus.setGravity(Gravity.CENTER);
          }
          if (s.locationName != null && !s.locationName.isEmpty()) showLocationTag(s.locationName);
          showCaption(s.caption);
          startProgress(5_000L);
      }
      private void applyBackground(StatusItem s) {
          try {
              if (s.bgColor != null && s.bgColor2 != null) {
                  android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable(
                      android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                      new int[]{Color.parseColor(s.bgColor), Color.parseColor(s.bgColor2)});
                  binding.flTextStatus.setBackground(gd);
              } else if (s.bgColor != null) {
                  binding.flTextStatus.setBackgroundColor(Color.parseColor(s.bgColor));
              } else {
                  binding.flTextStatus.setBackgroundResource(R.drawable.gradient_brand);
              }
          } catch (Exception e) { binding.flTextStatus.setBackgroundResource(R.drawable.gradient_brand); }
      }
      private void showImageStatusFromUrl(String url, String caption) {
          binding.ivStatus.setVisibility(View.VISIBLE);
          Glide.with(this).load(url).diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
               .placeholder(android.R.drawable.screen_background_dark).into(binding.ivStatus);
          showCaption(caption); startProgress(5_000L);
      }
      private void showLinkStatus(StatusItem s) {
          if (s.linkImageUrl != null && !s.linkImageUrl.isEmpty()) showImageStatusFromUrl(s.linkImageUrl, s.linkTitle);
          else showTextStatus(s);
          if (s.linkUrl != null) { binding.tvCaption.setClickable(true);
              binding.tvCaption.setOnClickListener(v -> { pauseProgress(); startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(s.linkUrl))); }); }
      }
      private void showGifStatus(StatusItem s) {
          binding.ivStatus.setVisibility(View.VISIBLE);
          String url = s.gifUrl != null ? s.gifUrl : s.stickerUrl != null ? s.stickerUrl : s.mediaUrl;
          if (url != null) Glide.with(this).asGif().load(url)
              .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.DATA)
              .placeholder(android.R.drawable.screen_background_dark).into(binding.ivStatus);
          showCaption(s.caption); startProgress(4_000L);
      }
      @OptIn(markerClass = UnstableApi.class)
      private void showVideoStatus(StatusItem s) {
          binding.playerView.setVisibility(View.VISIBLE); binding.btnMute.setVisibility(View.VISIBLE);
          releasePlayer();
          ExoPlayer.Builder builder = new ExoPlayer.Builder(this);
          if (StatusVideoCacheManager.isInitialized()) {
              CacheDataSource.Factory cf = StatusVideoCacheManager.getCacheDataSourceFactory();
              ProgressiveMediaSource ms = new ProgressiveMediaSource.Factory(cf).createMediaSource(MediaItem.fromUri(Uri.parse(s.mediaUrl)));
              player = builder.build(); binding.playerView.setPlayer(player); player.setMediaSource(ms);
          } else {
              player = builder.build(); binding.playerView.setPlayer(player); player.setMediaItem(MediaItem.fromUri(Uri.parse(s.mediaUrl)));
          }
          player.setVolume(isMuted ? 0f : 1f);
          long estimated = s.durationSec > 0 ? Math.min(s.durationSec*1000L, 30_000L) : 15_000L;
          player.addListener(new Player.Listener() {
              @Override public void onPlaybackStateChanged(int state) {
                  if (state == Player.STATE_READY) {
                      long real = player.getDuration();
                      long dur = (real>0 && real!=Long.MIN_VALUE) ? Math.min(real,30_000L) : estimated;
                      stopProgress(); startProgress(dur);
                  } else if (state == Player.STATE_ENDED) { next(); }
              }
          });
          player.prepare(); player.setPlayWhenReady(true);
          startProgress(estimated); showCaption(s.caption);
      }

      // ── NEW: Poll renderer ────────────────────────────────────────────────
      private void showPollStatus(StatusItem s) {
          binding.flTextStatus.setVisibility(View.VISIBLE);
          binding.tvTextStatus.setVisibility(View.GONE);
          binding.llPollContainer.setVisibility(View.VISIBLE);
          binding.llQuestionBox.setVisibility(View.GONE);
          applyBackground(s);
          binding.tvPollQuestion.setText(s.pollQuestion != null ? s.pollQuestion : "");
          binding.llPollOptions.removeAllViews();
          boolean isOwner = myUid != null && myUid.equals(ownerUid);
          int totalVotes = s.getTotalPollVotes();
          int myVote = (s.pollVotes != null && myUid != null && s.pollVotes.containsKey(myUid))
                       ? s.pollVotes.get(myUid) : -1;
          boolean hasVoted = myVote >= 0 || isOwner;
          List<String> opts = s.pollOptions != null ? s.pollOptions : new ArrayList<>();
          LayoutInflater li = LayoutInflater.from(this);
          for (int i = 0; i < opts.size(); i++) {
              final int optIdx = i;
              View row = li.inflate(R.layout.item_poll_option, binding.llPollOptions, false);
              TextView tvOpt = row.findViewById(R.id.tv_poll_option);
              TextView tvPct = row.findViewById(R.id.tv_poll_pct);
              View fill    = row.findViewById(R.id.v_poll_fill);
              ImageView iv = row.findViewById(R.id.iv_voted);
              tvOpt.setText(opts.get(i));
              if (hasVoted) {
                  int votes = s.getPollVoteCount(i);
                  int pct = totalVotes > 0 ? (votes * 100 / totalVotes) : 0;
                  tvPct.setVisibility(View.VISIBLE); tvPct.setText(pct + "%");
                  android.view.ViewTreeObserver vto = fill.getViewTreeObserver();
                  vto.addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                      @Override public void onGlobalLayout() {
                          fill.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                          int w = (int)(row.getWidth() * pct / 100.0f);
                          ValueAnimator va = ValueAnimator.ofInt(0, w);
                          va.setDuration(600); va.setInterpolator(new DecelerateInterpolator());
                          va.addUpdateListener(a -> { fill.getLayoutParams().width = (int)a.getAnimatedValue(); fill.requestLayout(); });
                          va.start();
                      }
                  });
                  if (optIdx == myVote) iv.setVisibility(View.VISIBLE);
              }
              if (!hasVoted) {
                  row.setOnClickListener(v -> {
                      pauseProgress();
                      if (s.pollVotes == null) s.pollVotes = new HashMap<>();
                      s.pollVotes.put(myUid, optIdx);
                      FirebaseUtils.getStatusRef().child(ownerUid).child(s.id)
                          .child("pollVotes").child(myUid).setValue(optIdx);
                      showPollStatus(s); resumeProgress();
                  });
              }
              binding.llPollOptions.addView(row);
          }
          binding.tvPollTotal.setText(totalVotes + " vote" + (totalVotes != 1 ? "s" : ""));
          startProgress(hasVoted ? 6_000L : 30_000L);
      }

      // ── NEW: Question Box renderer ────────────────────────────────────────
      private void showQuestionBoxStatus(StatusItem s) {
          binding.flTextStatus.setVisibility(View.VISIBLE);
          binding.tvTextStatus.setVisibility(View.GONE);
          binding.llPollContainer.setVisibility(View.GONE);
          binding.llQuestionBox.setVisibility(View.VISIBLE);
          applyBackground(s);
          binding.tvQuestionPrompt.setText(s.questionBoxText != null ? s.questionBoxText : "Ask me anything");
          boolean isOwner = myUid != null && myUid.equals(ownerUid);
          if (isOwner) {
              binding.etQuestionAnswer.setVisibility(View.GONE);
              binding.btnSubmitAnswer.setVisibility(View.GONE);
          } else {
              boolean alreadyAnswered = s.questionBoxAnswers != null && myUid != null && s.questionBoxAnswers.containsKey(myUid);
              if (alreadyAnswered) {
                  binding.etQuestionAnswer.setEnabled(false);
                  binding.etQuestionAnswer.setText(s.questionBoxAnswers.get(myUid));
                  binding.btnSubmitAnswer.setText("Sent \u2713");
                  binding.btnSubmitAnswer.setEnabled(false);
              } else {
                  binding.btnSubmitAnswer.setOnClickListener(v -> {
                      String ans = binding.etQuestionAnswer.getText() != null
                              ? binding.etQuestionAnswer.getText().toString().trim() : "";
                      if (TextUtils.isEmpty(ans)) return;
                      FirebaseUtils.getStatusRef().child(ownerUid).child(s.id)
                          .child("questionBoxAnswers").child(myUid).setValue(ans);
                      binding.btnSubmitAnswer.setText("Sent \u2713");
                      binding.btnSubmitAnswer.setEnabled(false);
                      Toast.makeText(this, "Answer sent!", Toast.LENGTH_SHORT).show();
                      resumeProgress();
                  });
              }
          }
          startProgress(20_000L);
      }

      private void hideAllContent() {
          binding.flTextStatus.setVisibility(View.GONE);
          binding.ivStatus.setVisibility(View.GONE);
          binding.playerView.setVisibility(View.GONE);
          binding.tvCaption.setVisibility(View.GONE);
          binding.tvLocationTag.setVisibility(View.GONE);
          binding.llPollContainer.setVisibility(View.GONE);
          binding.llQuestionBox.setVisibility(View.GONE);
      }
      private void showCaption(String caption) {
          if (!TextUtils.isEmpty(caption)) {
              binding.tvCaption.setVisibility(View.VISIBLE);
              binding.tvCaption.setText(StatusMentionHelper.highlight(caption));
          }
      }
      private void showLocationTag(String location) {
          binding.tvLocationTag.setText("\uD83D\uDCCD " + location);
          binding.tvLocationTag.setVisibility(View.VISIBLE);
      }

      // ── NEW: Music strip ─────────────────────────────────────────────────
      private void updateMusicStrip(StatusItem s) {
          if (s.musicTitle != null && !s.musicTitle.isEmpty()) {
              String label = s.musicArtist != null ? s.musicTitle + " \u2022 " + s.musicArtist : s.musicTitle;
              binding.tvMusicLabel.setText(label);
              binding.llMusicStrip.setVisibility(View.VISIBLE);
          } else {
              binding.llMusicStrip.setVisibility(View.GONE);
          }
      }

      // ── NEW: Swipe-up hint ────────────────────────────────────────────────
      private void updateSwipeUpHint(StatusItem s) {
          boolean hasLink = !TextUtils.isEmpty(s.linkUrl)
                  && !"link".equals(s.type) && !"text".equals(s.type);
          if (hasLink) {
              binding.llSwipeUp.setVisibility(View.VISIBLE);
              // Bob animation on arrow
              ObjectAnimator bob = ObjectAnimator.ofFloat(binding.tvSwipeUpArrow, "translationY", 0f, -8f, 0f);
              bob.setDuration(900); bob.setRepeatCount(ValueAnimator.INFINITE);
              bob.setInterpolator(new AccelerateDecelerateInterpolator());
              bob.start();
              binding.tvSwipeUpArrow.setTag(bob);
          } else {
              binding.llSwipeUp.setVisibility(View.GONE);
              Object tag = binding.tvSwipeUpArrow.getTag();
              if (tag instanceof ObjectAnimator) ((ObjectAnimator)tag).cancel();
          }
      }

      // ── NEW: Privacy badge ────────────────────────────────────────────────
      private void updatePrivacyBadge(StatusItem s) {
          if (myUid != null && myUid.equals(ownerUid) && s.privacy != null) {
              String badge;
              switch (s.privacy) {
                  case "close_friends": badge = "\u2B50 Close Friends"; break;
                  case "contacts":      badge = "\uD83D\uDC65 Contacts"; break;
                  case "except":        badge = "\uD83D\uDEAB Except"; break;
                  case "only":          badge = "\uD83D\uDD12 Only"; break;
                  default:              badge = "\uD83C\uDF0D Everyone";
              }
              binding.tvPrivacyViewer.setText(badge);
              binding.tvPrivacyViewer.setVisibility(View.VISIBLE);
          } else {
              binding.tvPrivacyViewer.setVisibility(View.GONE);
          }
      }

      // ── Progress ──────────────────────────────────────────────────────────
      private void startProgress(long durationMs) {
          stopProgress(); paused = false; remainingMs = durationMs;
          runProgressTick(durationMs, durationMs);
      }
      private void runProgressTick(final long totalMs, final long remaining) {
          final long STEP = 50L;
          progressRunner = new Runnable() {
              long elapsed = totalMs - remaining;
              @Override public void run() {
                  if (paused) return;
                  elapsed += STEP;
                  int prog = (int) Math.min(1000L, (elapsed * 1000L) / totalMs);
                  if (idx < segmentBars.size()) segmentBars.get(idx).setProgress(prog);
                  if (elapsed >= totalMs) { next(); }
                  else { remainingMs = totalMs - elapsed; handler.postDelayed(this, STEP); }
              }
          };
          handler.postDelayed(progressRunner, STEP);
      }
      private void stopProgress() {
          if (progressRunner != null) { handler.removeCallbacks(progressRunner); progressRunner = null; }
      }
      private void pauseProgress() {
          if (paused) return; paused = true;
          if (player != null) player.setPlayWhenReady(false);
          stopProgress();
      }
      private void resumeProgress() {
          if (!paused) return; paused = false;
          if (player != null) player.setPlayWhenReady(true);
          if (idx < items.size()) {
              StatusItem s = items.get(idx);
              long total;
              if ("video".equals(s.type)||"reel_story".equals(s.type)||"reel_clip".equals(s.type)) {
                  long real = player!=null ? player.getDuration() : Long.MIN_VALUE;
                  total = (real>0&&real!=Long.MIN_VALUE) ? Math.min(real,30_000L)
                        : (s.durationSec>0 ? Math.min(s.durationSec*1000L,30_000L) : 15_000L);
              } else total = 5_000L;
              runProgressTick(total, remainingMs);
          }
      }

      // ── Navigation ────────────────────────────────────────────────────────
      private void next()     { releasePlayer(); stopProgress(); idx++; showCurrent(); }
      private void previous() { releasePlayer(); stopProgress(); idx = Math.max(0, idx-1); showCurrent(); }

      // ── Gestures (tap + double-tap + swipe) ──────────────────────────────
      private void setupGestures() {
          gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
              // Swipe down → close
              @Override public boolean onFling(MotionEvent e1, @NonNull MotionEvent e2, float vx, float vy) {
                  if (e1 == null) return false;
                  float dx = e2.getRawX() - e1.getRawX();
                  float dy = e2.getRawY() - e1.getRawY();
                  // Swipe down → close
                  if (dy > 120 && Math.abs(vy) > 100 && Math.abs(dy) > Math.abs(dx)) {
                      finishWithAnimation(); return true;
                  }
                  // Swipe up → open link (NEW)
                  if (dy < -120 && Math.abs(vy) > 100 && Math.abs(dy) > Math.abs(dx)) {
                      StatusItem current = idx < items.size() ? items.get(idx) : null;
                      if (current != null && !TextUtils.isEmpty(current.linkUrl)) {
                          pauseProgress();
                          startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(current.linkUrl)));
                          return true;
                      }
                  }
                  return false;
              }
              // NEW: Double-tap → ❤️ reaction
              @Override public boolean onDoubleTap(MotionEvent e) {
                  StatusItem current = idx < items.size() ? items.get(idx) : null;
                  if (current == null || myUid == null || myUid.equals(ownerUid)) return false;
                  // Fire heart reaction to Firebase
                  if (current.reactions == null) current.reactions = new HashMap<>();
                  current.reactions.put(myUid, "\u2764\uFE0F");
                  if (current.id != null)
                      FirebaseUtils.getStatusReactionRef(ownerUid, current.id, myUid).setValue("\u2764\uFE0F");
                  updateSeenByInfo(current);
                  // Float a big ❤️ at tap position
                  launchEmojiFloat("\u2764\uFE0F", (int)e.getX(), (int)e.getY(), 48);
                  return true;
              }
          });
      }
      private void setupTouchZones() {
          binding.touchLayer.setOnTouchListener((v, e) -> {
              gestureDetector.onTouchEvent(e);
              switch (e.getAction()) {
                  case MotionEvent.ACTION_DOWN: pauseProgress(); break;
                  case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                      resumeProgress();
                      float x = e.getX(), w = v.getWidth();
                      if (e.getEventTime() - e.getDownTime() < 200) {
                          if (x < w/3f) previous(); else next();
                      }
                      break;
              }
              return true;
          });
      }

      // ── NEW: Floating emoji animation ─────────────────────────────────────
      private void launchEmojiFloat(String emoji, int startX, int startY, int textSizeSp) {
          TextView tv = new TextView(this);
          tv.setText(emoji);
          tv.setTextSize(textSizeSp);
          FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                  FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
          tv.setLayoutParams(lp);
          binding.flEmojiOverlay.addView(tv);
          tv.post(() -> {
              tv.setX(startX - tv.getWidth() / 2f);
              tv.setY(startY - tv.getHeight() / 2f);
              // Scale in then float up
              tv.setScaleX(0.3f); tv.setScaleY(0.3f); tv.setAlpha(1f);
              tv.animate().scaleX(1.4f).scaleY(1.4f).setDuration(180).withEndAction(() ->
                  tv.animate().scaleX(1f).scaleY(1f).setDuration(100).withEndAction(() ->
                      tv.animate()
                          .translationYBy(-dpToPx(200))
                          .alpha(0f)
                          .setDuration(900)
                          .setInterpolator(new AccelerateInterpolator(0.6f))
                          .withEndAction(() -> binding.flEmojiOverlay.removeView(tv))
                          .start()
                  ).start()
              ).start();
          });
      }

      // ── Buttons ───────────────────────────────────────────────────────────
      private void setupCloseButton() {
          binding.btnCloseStatus.setOnClickListener(v -> finishWithAnimation());
      }
      private void setupReactionButton() {
          binding.btnReact.setOnClickListener(v -> {
              if (myUid != null && myUid.equals(ownerUid)) return;
              StatusItem current = idx < items.size() ? items.get(idx) : null;
              if (current == null) return;
              pauseProgress();
              StatusReactionBottomSheet.show(this, current, myUid, (emoji, removed) -> {
                  if (removed) { if (current.reactions != null) current.reactions.remove(myUid); }
                  else {
                      if (current.reactions == null) current.reactions = new HashMap<>();
                      current.reactions.put(myUid, emoji);
                      // Launch emoji float from button location
                      int[] loc = new int[2]; binding.btnReact.getLocationInWindow(loc);
                      launchEmojiFloat(emoji, loc[0] + binding.btnReact.getWidth()/2, loc[1], 32);
                  }
                  updateSeenByInfo(current);
                  resumeProgress();
              });
          });
      }
      private void setupReplyButton() {
          if (myUid != null && myUid.equals(ownerUid)) {
              binding.etReply.setVisibility(View.GONE); binding.btnSendReply.setVisibility(View.GONE); return;
          }
          binding.etReply.setOnClickListener(v -> {
              StatusItem current = idx < items.size() ? items.get(idx) : null;
              if (current == null || myUid == null) return;
              pauseProgress();
              StatusReplyBottomSheet.show(this, current, ownerName, myUid, ownerUid, msg -> resumeProgress());
          });
          binding.etReply.setOnFocusChangeListener((v, has) -> { if (has) pauseProgress(); else resumeProgress(); });
          binding.btnSendReply.setOnClickListener(v -> {
              String msg = binding.etReply.getText() != null ? binding.etReply.getText().toString().trim() : "";
              if (TextUtils.isEmpty(msg) || myUid == null || ownerUid == null) return;
              sendReplyToChat(ownerUid, msg);
              binding.etReply.setText(""); binding.etReply.clearFocus();
              resumeProgress();
              Toast.makeText(this, "Reply sent", Toast.LENGTH_SHORT).show();
          });
      }
      private void setupDownloadButton() {
          binding.btnDownload.setOnClickListener(v -> {
              StatusItem current = idx < items.size() ? items.get(idx) : null;
              if (current == null) return;
              if (!StatusDownloadHelper.hasPermission(this)) { StatusDownloadHelper.requestPermission(this); return; }
              StatusDownloadHelper.downloadStatus(this, current);
          });
      }
      private void setupForwardButton() {
          binding.btnForward.setOnClickListener(v -> {
              StatusItem current = idx < items.size() ? items.get(idx) : null;
              if (current == null || myUid == null) return;
              pauseProgress();
              StatusForwardBottomSheet.show(this, current, myUid, this::resumeProgress);
          });
      }
      private void setupMoreButton() {
          binding.btnMore.setOnClickListener(v -> {
              pauseProgress();
              if (myUid != null && myUid.equals(ownerUid)) showOwnerMoreMenu(); else showViewerMoreMenu();
          });
      }
      private void showOwnerMoreMenu() {
          StatusItem current = idx < items.size() ? items.get(idx) : null;
          String[] opts = {"Who viewed this", "Delete this status", "Archive status", "Add to Highlights", "Analytics", "Cancel"};
          new AlertDialog.Builder(this).setItems(opts, (d, w) -> {
              if (w == 0) {
                  if (current != null) StatusSeenByBottomSheet.show(this, current, this::resumeProgress);
                  else resumeProgress();
              } else if (w == 1 && current != null && current.id != null) {
                  String prev = current.thumbnailUrl != null ? current.thumbnailUrl : current.mediaUrl;
                  StatusDeleteConfirmBottomSheet.show(this, current.type, prev, () -> {
                      StatusSeenTracker.deleteStatus(ownerUid, current.id);
                      items.remove(idx);
                      Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
                      if (items.isEmpty()) { finish(); return; }
                      idx = Math.min(idx, items.size()-1);
                      buildSegmentBars(); stopProgress(); showCurrent();
                  });
              } else if (w == 2 && current != null) {
                  StatusHighlightManager.archiveStatus(ownerUid, current);
                  Toast.makeText(this, "Archived \u2713", Toast.LENGTH_SHORT).show(); resumeProgress();
              } else if (w == 3 && current != null) {
                  pauseProgress();
                  StatusAddToHighlightBottomSheet.show(this, ownerUid, current, a -> {
                      Toast.makeText(this, "Added to " + a + " \u2713", Toast.LENGTH_SHORT).show(); resumeProgress();
                  });
              } else if (w == 4 && current != null) {
                  pauseProgress(); StatusAnalyticsBottomSheet.show(this, current, this::resumeProgress);
              } else resumeProgress();
          }).setOnCancelListener(d -> resumeProgress()).show();
      }
      private void showViewerMoreMenu() {
          String muteLabel = StatusMuteManager.isMuted(this, ownerUid) ? "Unmute "+ownerName : "Mute "+ownerName;
          new AlertDialog.Builder(this).setItems(new String[]{muteLabel,"Download","Forward","Report","Cancel"}, (d,w) -> {
              if (w == 0) {
                  StatusMuteManager.toggle(this, ownerUid);
                  Toast.makeText(this, StatusMuteManager.isMuted(this, ownerUid) ? ownerName+" muted" : ownerName+" unmuted", Toast.LENGTH_SHORT).show();
                  finish();
              } else if (w == 1) { StatusItem cur = idx<items.size()?items.get(idx):null; if (cur!=null) StatusDownloadHelper.downloadStatus(this,cur); }
              else if (w == 2) { StatusItem cur=idx<items.size()?items.get(idx):null; if (cur!=null&&myUid!=null) StatusForwardBottomSheet.show(this,cur,myUid); }
              else if (w == 3) Toast.makeText(this,"Reported",Toast.LENGTH_SHORT).show();
              resumeProgress();
          }).setOnCancelListener(d -> resumeProgress()).show();
      }
      private void setupMuteButton() {
          binding.btnMute.setOnClickListener(v -> {
              isMuted = !isMuted;
              if (player != null) player.setVolume(isMuted ? 0f : 1f);
              binding.btnMute.setImageResource(isMuted ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
          });
      }

      // ── Seen-by info ──────────────────────────────────────────────────────
      private void updateSeenByInfo(StatusItem s) {
          if (myUid != null && myUid.equals(ownerUid)) {
              int count = s.getViewCount();
              String rxn = buildReactionSummary(s);
              binding.tvSeenBy.setVisibility(View.VISIBLE);
              binding.tvSeenBy.setText("\uD83D\uDC41 " + count + (rxn.isEmpty() ? "" : "  " + rxn));
              binding.tvSeenBy.setOnClickListener(v -> { pauseProgress(); StatusSeenByBottomSheet.show(this, s, this::resumeProgress); });
          } else {
              binding.tvSeenBy.setVisibility(View.GONE);
              if (s.hasReaction(myUid)) binding.btnReact.setContentDescription("React (" + s.getReaction(myUid) + ")");
          }
      }
      private String buildReactionSummary(StatusItem s) {
          if (s.reactions == null || s.reactions.isEmpty()) return "";
          Map<String, Integer> counts = new LinkedHashMap<>();
          for (String e : s.reactions.values()) counts.merge(e, 1, Integer::sum);
          StringBuilder sb = new StringBuilder();
          for (Map.Entry<String, Integer> e : counts.entrySet())
              sb.append(e.getKey()).append(e.getValue()>1 ? "\u00D7"+e.getValue() : "").append(" ");
          return sb.toString().trim();
      }

      // ── Header helpers ────────────────────────────────────────────────────
      private void updateHeaderTimestamp(StatusItem s) {
          if (s.timestamp != null) binding.tvTimestamp.setText(formatAgo(System.currentTimeMillis() - s.timestamp));
          else binding.tvTimestamp.setText("");
      }
      private void updateExpiryLabel(StatusItem s) {
          if (s.expiresAt != null) {
              long diff = s.expiresAt - System.currentTimeMillis();
              if (diff > 0) {
                  long h = diff / 3_600_000L;
                  binding.tvExpiryLabel.setText(h < 1 ? "Expires <1h" : "Expires in " + h + "h");
                  binding.tvExpiryLabel.setVisibility(View.VISIBLE);
              } else binding.tvExpiryLabel.setVisibility(View.GONE);
          } else binding.tvExpiryLabel.setVisibility(View.GONE);
      }

      // ── Animations ────────────────────────────────────────────────────────
      private void finishWithAnimation() {
          AlphaAnimation fade = new AlphaAnimation(1f, 0f); fade.setDuration(200);
          fade.setAnimationListener(new Animation.AnimationListener() {
              @Override public void onAnimationStart(Animation a) {}
              @Override public void onAnimationRepeat(Animation a) {}
              @Override public void onAnimationEnd(Animation a) { finish(); }
          });
          binding.getRoot().startAnimation(fade);
      }
      private void crossFadeIn() {
          binding.getRoot().setAlpha(0f);
          binding.getRoot().animate().alpha(1f).setDuration(150).start();
      }

      // ── Utilities ─────────────────────────────────────────────────────────
      private String formatAgo(long ms) {
          if (ms < 60_000) return "just now";
          if (ms < 3_600_000) return (ms/60_000)+"m ago";
          if (ms < 86_400_000) return (ms/3_600_000)+"h ago";
          return (ms/86_400_000)+"d ago";
      }
      private void applyFontStyle(TextView tv, String style) {
          if (style == null) return;
          switch (style) {
              case "bold":        tv.setTypeface(null, android.graphics.Typeface.BOLD); break;
              case "italic":      tv.setTypeface(null, android.graphics.Typeface.ITALIC); break;
              case "bold_italic": tv.setTypeface(null, android.graphics.Typeface.BOLD_ITALIC); break;
              default:            tv.setTypeface(null, android.graphics.Typeface.NORMAL);
          }
      }
      private void sendReplyToChat(String toUid, String msg) {
          String chatId = FirebaseUtils.getChatId(myUid, toUid);
          FirebaseUtils.getMessagesRef(chatId).push().setValue(new java.util.HashMap<String, Object>() {{
              put("sender", myUid); put("text", msg);
              put("timestamp", System.currentTimeMillis()); put("type", "text");
          }});
      }
      private int dpToPx(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }
      private void releasePlayer() { if (player != null) { player.release(); player = null; } }
  }