package com.callx.app.fragments;

  import android.content.Intent;
  import android.os.Bundle;
  import android.text.Editable;
  import android.text.TextWatcher;
  import android.view.LayoutInflater;
  import android.view.View;
  import android.view.ViewGroup;
  import android.widget.EditText;
  import androidx.annotation.NonNull;
  import androidx.annotation.Nullable;
  import androidx.fragment.app.Fragment;
  import androidx.recyclerview.widget.LinearLayoutManager;
  import androidx.recyclerview.widget.RecyclerView;
  import com.callx.app.activities.XHashtagActivity;
  import com.callx.app.adapters.XTweetAdapter;
  import com.callx.app.models.XTweet;
  import com.callx.app.utils.XFirebaseUtils;
  import com.callx.app.x.R;
  import com.google.firebase.database.*;
  import java.util.ArrayList;
  import java.util.Collections;
  import java.util.List;

  public class XExploreFragment extends Fragment implements XTweetAdapter.OnTweetActionListener {

      private RecyclerView rvTrending, rvResults;
      private EditText etSearch;
      private XTweetAdapter resultsAdapter;
      private ValueEventListener trendingListener;

      @Nullable @Override
      public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                               @Nullable Bundle savedInstanceState) {
          return inflater.inflate(R.layout.fragment_x_explore, container, false);
      }

      @Override
      public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
          super.onViewCreated(view, savedInstanceState);
          etSearch    = view.findViewById(R.id.et_x_search);
          rvTrending  = view.findViewById(R.id.rv_x_trending);
          rvResults   = view.findViewById(R.id.rv_x_search_results);

          resultsAdapter = new XTweetAdapter(requireContext(), this);
          rvResults.setLayoutManager(new LinearLayoutManager(requireContext()));
          rvResults.setAdapter(resultsAdapter);

          etSearch.addTextChangedListener(new TextWatcher() {
              @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
              @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                  if (s.length() > 0) {
                      rvTrending.setVisibility(View.GONE);
                      rvResults.setVisibility(View.VISIBLE);
                      searchTweets(s.toString().trim());
                  } else {
                      rvTrending.setVisibility(View.VISIBLE);
                      rvResults.setVisibility(View.GONE);
                  }
              }
              @Override public void afterTextChanged(Editable s) {}
          });

          loadTrending(view);
      }

      private void loadTrending(View view) {
          // Trending hashtags row
          RecyclerView rvHashtags = view.findViewById(R.id.rv_x_hashtags);
          if (rvHashtags != null) {
              rvHashtags.setLayoutManager(new LinearLayoutManager(requireContext(),
                  LinearLayoutManager.HORIZONTAL, false));
          }

          // Global trending tweets
          trendingListener = new ValueEventListener() {
              @Override public void onDataChange(@NonNull DataSnapshot snap) {
                  if (!isAdded()) return;
                  List<XTweet> list = new ArrayList<>();
                  for (DataSnapshot ds : snap.getChildren()) {
                      XTweet t = ds.getValue(XTweet.class);
                      if (t != null && !t.isDeleted) { t.id = ds.getKey(); list.add(t); }
                  }
                  Collections.sort(list, (a, b) -> Long.compare(b.viewCount + b.likeCount * 2, a.viewCount + a.likeCount * 2));
                  // Show top 30
                  if (list.size() > 30) list = list.subList(0, 30);
                  XTweetAdapter trendingAdapter = new XTweetAdapter(requireContext(), XExploreFragment.this);
                  rvTrending.setLayoutManager(new LinearLayoutManager(requireContext()));
                  rvTrending.setAdapter(trendingAdapter);
                  trendingAdapter.setTweets(list);
              }
              @Override public void onCancelled(@NonNull DatabaseError e) {}
          };
          XFirebaseUtils.globalFeedRef().limitToLast(100).addValueEventListener(trendingListener);
      }

      private void searchTweets(String query) {
          // Simple prefix search on tweet text
          XFirebaseUtils.tweetsRef()
              .orderByChild("text")
              .startAt(query).endAt(query + "\uf8ff")
              .limitToFirst(20)
              .get()
              .addOnSuccessListener(snap -> {
                  if (!isAdded()) return;
                  List<XTweet> list = new ArrayList<>();
                  for (DataSnapshot ds : snap.getChildren()) {
                      XTweet t = ds.getValue(XTweet.class);
                      if (t != null && !t.isDeleted) { t.id = ds.getKey(); list.add(t); }
                  }
                  resultsAdapter.setTweets(list);
              });
      }

      @Override public void onLike(XTweet tweet, boolean liked) {}
      @Override public void onRetweet(XTweet tweet, boolean retweeted) {}
      @Override public void onReply(XTweet tweet) {}
      @Override public void onBookmark(XTweet tweet) {}
      @Override public void onShare(XTweet tweet) {
          Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain");
          share.putExtra(Intent.EXTRA_TEXT, tweet.text + " — via CallX");
          startActivity(Intent.createChooser(share, "Share"));
      }
      @Override public void onMore(XTweet tweet, View anchor) {}

      @Override public void onDestroyView() {
          super.onDestroyView();
          if (trendingListener != null)
              XFirebaseUtils.globalFeedRef().removeEventListener(trendingListener);
      }
  }