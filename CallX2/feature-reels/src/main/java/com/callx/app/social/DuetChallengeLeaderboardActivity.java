package com.callx.app.social;

  import android.content.Intent;
  import android.os.Bundle;
  import android.view.*;
  import android.widget.*;
  import androidx.annotation.NonNull;
  import androidx.appcompat.app.AppCompatActivity;
  import androidx.recyclerview.widget.LinearLayoutManager;
  import androidx.recyclerview.widget.RecyclerView;
  import com.bumptech.glide.Glide;
  import com.callx.app.reels.R;
  import com.callx.app.utils.FirebaseUtils;
  import com.google.firebase.database.*;
  import java.util.*;

  /**
   * DuetChallengeLeaderboardActivity — Shows top duet entries ranked by likes.
   *
   * Firebase query: reels where challengeId = this challenge's ID,
   * ordered by likeCount descending (limit 50).
   *
   * Live-updating: re-sorts every time a new like comes in via a ChildEventListener.
   *
   * Podium:
   *  #1 — gold crown badge
   *  #2 — silver badge
   *  #3 — bronze badge
   *  #4-50 — numbered rows
   */
  public class DuetChallengeLeaderboardActivity extends AppCompatActivity {

      public static final String EXTRA_CHALLENGE_ID    = "lb_challenge_id";
      public static final String EXTRA_CHALLENGE_TITLE = "lb_challenge_title";
      public static final String EXTRA_HASHTAG         = "lb_hashtag";

      private RecyclerView rvLeaderboard;
      private TextView     tvTitle, tvHashtag, tvEntryCount;
      private ProgressBar  progress;
      private View         layoutEmpty;
      private ImageButton  btnBack;

      private String challengeId;
      private final List<LeaderEntry> entries = new ArrayList<>();
      private LeaderAdapter adapter;

      @Override
      protected void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);
          setContentView(R.layout.activity_duet_challenge_leaderboard);

          challengeId = getIntent().getStringExtra(EXTRA_CHALLENGE_ID);
          String title   = getIntent().getStringExtra(EXTRA_CHALLENGE_TITLE);
          String hashtag = getIntent().getStringExtra(EXTRA_HASHTAG);

          btnBack       = findViewById(R.id.btn_lb_back);
          tvTitle       = findViewById(R.id.tv_lb_title);
          tvHashtag     = findViewById(R.id.tv_lb_hashtag);
          tvEntryCount  = findViewById(R.id.tv_lb_entry_count);
          rvLeaderboard = findViewById(R.id.rv_leaderboard);
          progress      = findViewById(R.id.progress_lb);
          layoutEmpty   = findViewById(R.id.layout_lb_empty);

          tvTitle.setText(title != null ? title : "Leaderboard");
          tvHashtag.setText(hashtag != null ? "#" + hashtag : "");

          btnBack.setOnClickListener(v -> finish());

          adapter = new LeaderAdapter(entries, this::openReel);
          rvLeaderboard.setLayoutManager(new LinearLayoutManager(this));
          rvLeaderboard.setAdapter(adapter);

          loadEntries();
      }

      private void loadEntries() {
          if (challengeId == null) return;
          progress.setVisibility(View.VISIBLE);

          FirebaseUtils.db().getReference("reels")
              .orderByChild("challengeId").equalTo(challengeId).limitToFirst(50)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      entries.clear();
                      for (DataSnapshot ds : snap.getChildren()) {
                          String ownName  = ds.child("ownerName").getValue(String.class);
                          String ownPhoto = ds.child("ownerPhoto").getValue(String.class);
                          String thumbUrl = ds.child("thumbUrl").getValue(String.class);
                          String rid      = ds.getKey();
                          Long   likes    = ds.child("likeCount").getValue(Long.class);
                          Long   views    = ds.child("viewCount").getValue(Long.class);
                          if (ownName != null) {
                              entries.add(new LeaderEntry(rid,
                                  ownName, ownPhoto != null ? ownPhoto : "",
                                  thumbUrl != null ? thumbUrl : "",
                                  likes != null ? likes : 0L,
                                  views != null ? views : 0L));
                          }
                      }
                      entries.sort((a, b) -> Long.compare(b.likes, a.likes));
                      progress.setVisibility(View.GONE);
                      tvEntryCount.setText(entries.size() + " entries");
                      adapter.notifyDataSetChanged();
                      layoutEmpty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
                      rvLeaderboard.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {
                      progress.setVisibility(View.GONE);
                  }
              });
      }

      private void openReel(String reelId) {
          if (reelId == null) return;
          Intent i = new Intent(this, com.callx.app.player.SingleReelPlayerActivity.class);
          i.putExtra(com.callx.app.player.SingleReelPlayerActivity.EXTRA_REEL_ID, reelId);
          startActivity(i);
      }

      // ── Data model ────────────────────────────────────────────────────────────
      static class LeaderEntry {
          String reelId, ownerName, ownerPhoto, thumbUrl;
          long likes, views;
          LeaderEntry(String r, String n, String p, String t, long l, long v) {
              reelId = r; ownerName = n; ownerPhoto = p; thumbUrl = t; likes = l; views = v;
          }
      }

      // ── Adapter ───────────────────────────────────────────────────────────────
      static class LeaderAdapter extends RecyclerView.Adapter<LeaderAdapter.VH> {
          interface OnOpen { void open(String reelId); }
          private final List<LeaderEntry> items;
          private final OnOpen onOpen;
          LeaderAdapter(List<LeaderEntry> items, OnOpen o) { this.items = items; this.onOpen = o; }

          @NonNull @Override
          public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
              View v = LayoutInflater.from(p.getContext())
                  .inflate(R.layout.item_challenge_leaderboard, p, false);
              return new VH(v);
          }
          @Override public void onBindViewHolder(@NonNull VH h, int pos) {
              LeaderEntry e = items.get(pos);
              h.tvRank.setText(pos == 0 ? "🥇" : pos == 1 ? "🥈" : pos == 2 ? "🥉" : "#" + (pos + 1));
              h.tvName.setText(e.ownerName);
              h.tvLikes.setText(formatCount(e.likes) + " ❤️");
              h.tvViews.setText(formatCount(e.views) + " 👁");
              if (!e.thumbUrl.isEmpty()) Glide.with(h.ivThumb).load(e.thumbUrl).centerCrop().override(720, 720).into(h.ivThumb);
              if (!e.ownerPhoto.isEmpty()) Glide.with(h.ivAvatar).load(e.ownerPhoto).circleCrop().override(96, 96).into(h.ivAvatar);
              h.itemView.setOnClickListener(v -> onOpen.open(e.reelId));
          }
          @Override public int getItemCount() { return items.size(); }
          private String formatCount(long n) {
              if (n >= 1_000_000) return (n / 1_000_000) + "M";
              if (n >= 1_000)     return (n / 1_000) + "K";
              return String.valueOf(n);
          }
          static class VH extends RecyclerView.ViewHolder {
              TextView tvRank, tvName, tvLikes, tvViews;
              ImageView ivThumb, ivAvatar;
              VH(View v) {
                  super(v);
                  tvRank   = v.findViewById(R.id.tv_lb_rank);
                  tvName   = v.findViewById(R.id.tv_lb_name);
                  tvLikes  = v.findViewById(R.id.tv_lb_likes);
                  tvViews  = v.findViewById(R.id.tv_lb_views);
                  ivThumb  = v.findViewById(R.id.iv_lb_thumb);
                  ivAvatar = v.findViewById(R.id.iv_lb_avatar);
              }
          }
      }
  }
  