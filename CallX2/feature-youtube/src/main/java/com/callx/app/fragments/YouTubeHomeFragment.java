package com.callx.app.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.callx.app.activities.YouTubePlayerActivity;
import com.callx.app.adapters.YouTubeVideoAdapter;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

public class YouTubeHomeFragment extends Fragment {

    private RecyclerView        rvFeed;
    private SwipeRefreshLayout  swipeRefresh;
    private YouTubeVideoAdapter adapter;
    private ValueEventListener  feedListener;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        return inf.inflate(R.layout.fragment_youtube_home, parent, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);

        rvFeed       = view.findViewById(R.id.rv_yt_home_feed);
        swipeRefresh = view.findViewById(R.id.srl_yt_home);

        adapter = new YouTubeVideoAdapter(requireContext(), new ArrayList<>(), video ->
            startActivity(new Intent(requireContext(), YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));
        rvFeed.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvFeed.setAdapter(adapter);

        swipeRefresh.setOnRefreshListener(() -> {
            detachFeedListener();
            loadFeed();
        });

        loadFeed();
    }

    private void loadFeed() {
        swipeRefresh.setRefreshing(true);
        feedListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                List<YouTubeVideo> list = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) {
                    YouTubeVideo v = ds.getValue(YouTubeVideo.class);
                    if (v != null && !v.isShort && "public".equals(v.visibility))
                        list.add(0, v);
                }
                adapter.setData(list);
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            }
        };
        YouTubeFirebaseUtils.globalFeedRef()
            .orderByChild("uploadedAt").limitToLast(30)
            .addValueEventListener(feedListener);
    }

    private void detachFeedListener() {
        if (feedListener != null)
            YouTubeFirebaseUtils.globalFeedRef().removeEventListener(feedListener);
        feedListener = null;
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        detachFeedListener();
    }
}
