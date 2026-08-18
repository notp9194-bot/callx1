package com.callx.app.social;

  import android.content.Intent;
  import android.os.Bundle;
  import android.view.View;
  import android.widget.*;
  import androidx.annotation.NonNull;
  import androidx.appcompat.app.AppCompatActivity;
  import androidx.recyclerview.widget.LinearLayoutManager;
  import androidx.recyclerview.widget.RecyclerView;

  import com.bumptech.glide.Glide;
  import com.callx.app.models.DuetSeriesModel;
  import com.callx.app.models.ReelModel;
  import com.callx.app.player.SingleReelPlayerActivity;
  import com.callx.app.reels.R;
  import com.callx.app.utils.Constants;
  import com.callx.app.utils.FirebaseUtils;
  import com.callx.app.workers.DuetSeriesNotificationWorker;
  import com.google.firebase.database.*;

  import java.util.ArrayList;
  import java.util.Collections;
  import java.util.Comparator;
  import java.util.List;

  /**
   * DuetSeriesActivity — View a Duet Series: episodes list + Subscribe/Unsubscribe.
   *
   * Accepts:
   *   EXTRA_SERIES_ID    → String (required)
   *   EXTRA_CREATOR_UID  → String (optional, for deep-link from notification)
   *
   * Firebase reads:
   *   duetSeries/{seriesId}              → DuetSeriesModel (header info)
   *   reels (orderByChild seriesId)      → episode ReelModels
   *   duetSeriesSubscriptions/{seriesId}/{myUid} → true/null (subscription state)
   */
  public class DuetSeriesActivity extends AppCompatActivity {

      public static final String EXTRA_SERIES_ID   = "series_id";
      public static final String EXTRA_CREATOR_UID = "creator_uid";

      private ImageView    ivSeriesCover;
      private TextView     tvSeriesTitle, tvSeriesDesc, tvEpisodeCount,
                           tvSubscriberCount, btnSubscribe, tvCreatorName;
      private RecyclerView rvEpisodes;
      private ProgressBar  progressLoading;
      private View         layoutEmpty;

      private DuetSeriesEpisodeAdapter adapter;
      private String seriesId, myUid;
      private boolean isSubscribed = false;
      private int subscriberCount  = 0;

      @Override
      protected void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);
          setContentView(R.layout.activity_duet_series);

          seriesId = getIntent().getStringExtra(EXTRA_SERIES_ID);
          myUid    = FirebaseUtils.getCurrentUid();

          if (seriesId == null || seriesId.isEmpty()) { finish(); return; }

          bindViews();
          setupRecycler();
          loadSeriesHeader();
          loadEpisodes();
          checkSubscription();

          btnSubscribe.setOnClickListener(v -> toggleSubscription());
          findViewById(R.id.btn_series_back).setOnClickListener(v -> onBackPressed());
      }

      private void bindViews() {
          ivSeriesCover      = findViewById(R.id.iv_series_cover);
          tvSeriesTitle      = findViewById(R.id.tv_series_title);
          tvSeriesDesc       = findViewById(R.id.tv_series_description);
          tvEpisodeCount     = findViewById(R.id.tv_episode_count);
          tvSubscriberCount  = findViewById(R.id.tv_subscriber_count);
          tvCreatorName      = findViewById(R.id.tv_series_creator);
          btnSubscribe       = findViewById(R.id.btn_subscribe);
          rvEpisodes         = findViewById(R.id.rv_series_episodes);
          progressLoading    = findViewById(R.id.progress_series_loading);
          layoutEmpty        = findViewById(R.id.layout_series_empty);
      }

      private void setupRecycler() {
          adapter = new DuetSeriesEpisodeAdapter(this);
          rvEpisodes.setLayoutManager(new LinearLayoutManager(this));
          rvEpisodes.setAdapter(adapter);
          adapter.setOnEpisodeClickListener((reel, pos) -> {
              Intent i = new Intent(this, SingleReelPlayerActivity.class);
              i.putExtra("reel_id", reel.reelId);
              startActivity(i);
          });
      }

      private void loadSeriesHeader() {
          FirebaseDatabase.getInstance(Constants.DB_URL)
              .getReference("duetSeries").child(seriesId)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (isFinishing() || isDestroyed()) return;
                      DuetSeriesModel series = snap.getValue(DuetSeriesModel.class);
                      if (series == null) { finish(); return; }

                      tvSeriesTitle.setText(series.title);
                      tvSeriesDesc.setText(series.description != null ? series.description : "");
                      tvCreatorName.setText("by " + series.creatorName);
                      subscriberCount = series.subscriberCount;
                      tvSubscriberCount.setText(series.subscriberCount + " subscribers");
                      tvEpisodeCount.setText(series.episodeCount + " episodes");

                      if (series.coverThumbUrl != null && !series.coverThumbUrl.isEmpty()) {
                          Glide.with(DuetSeriesActivity.this)
                               .load(series.coverThumbUrl)
                               .centerCrop()
                               .override(720, 720)
                               .into(ivSeriesCover);
                      }
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {}
              });
      }

      private void loadEpisodes() {
          progressLoading.setVisibility(View.VISIBLE);
          FirebaseDatabase.getInstance(Constants.DB_URL)
              .getReference("reels")
              .orderByChild("seriesId")
              .equalTo(seriesId)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (isFinishing() || isDestroyed()) return;
                      progressLoading.setVisibility(View.GONE);
                      List<ReelModel> eps = new ArrayList<>();
                      for (DataSnapshot s : snap.getChildren()) {
                          ReelModel r = s.getValue(ReelModel.class);
                          if (r != null) eps.add(r);
                      }
                      // Sort ascending by episode number
                      Collections.sort(eps, (a, b) ->
                          Integer.compare(a.seriesEpisodeNumber, b.seriesEpisodeNumber));
                      adapter.setEpisodes(eps);
                      layoutEmpty.setVisibility(eps.isEmpty() ? View.VISIBLE : View.GONE);
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {
                      if (isFinishing() || isDestroyed()) return;
                      progressLoading.setVisibility(View.GONE);
                  }
              });
      }

      private void checkSubscription() {
          if (myUid.isEmpty()) return;
          FirebaseDatabase.getInstance(Constants.DB_URL)
              .getReference("duetSeriesSubscriptions")
              .child(seriesId).child(myUid)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (isFinishing() || isDestroyed()) return;
                      isSubscribed = snap.exists();
                      updateSubscribeButton();
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {}
              });
      }

      private void toggleSubscription() {
          if (myUid.isEmpty()) {
              Toast.makeText(this, "Login required to subscribe", Toast.LENGTH_SHORT).show();
              return;
          }
          DatabaseReference db = FirebaseDatabase.getInstance(Constants.DB_URL).getReference();
          DatabaseReference subRef = db.child("duetSeriesSubscriptions").child(seriesId).child(myUid);
          DatabaseReference userRef = db.child("userSubscribedSeries").child(myUid).child(seriesId);
          DatabaseReference countRef = db.child("duetSeries").child(seriesId).child("subscriberCount");

          if (isSubscribed) {
              // Unsubscribe
              subRef.removeValue();
              userRef.removeValue();
              countRef.setValue(Math.max(0, subscriberCount - 1));
              isSubscribed = false;
              subscriberCount = Math.max(0, subscriberCount - 1);
          } else {
              // Subscribe
              subRef.setValue(true);
              userRef.setValue(true);
              countRef.setValue(subscriberCount + 1);
              isSubscribed = true;
              subscriberCount++;
              Toast.makeText(this, "Subscribed! You'll get notified for new episodes", Toast.LENGTH_SHORT).show();
          }
          tvSubscriberCount.setText(subscriberCount + " subscribers");
          updateSubscribeButton();
      }

      private void updateSubscribeButton() {
          if (isSubscribed) {
              btnSubscribe.setText("Subscribed ✓");
              btnSubscribe.setAlpha(0.7f);
          } else {
              btnSubscribe.setText("Subscribe");
              btnSubscribe.setAlpha(1f);
          }
      }
  }
  