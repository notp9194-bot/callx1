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
import com.callx.app.activities.YouTubePlayerActivity;
import com.callx.app.adapters.YouTubeVideoAdapter;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

/** Explore/Trending tab — shows videos sorted by view count. */
public class YouTubeExploreFragment extends Fragment {

    private RecyclerView       rvTrending;
    private YouTubeVideoAdapter adapter;
    private ValueEventListener trendingListener;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        return inf.inflate(R.layout.fragment_youtube_explore, parent, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);

        rvTrending = view.findViewById(R.id.rv_yt_trending);
        adapter = new YouTubeVideoAdapter(requireActivity(), new ArrayList<>(), video ->
            startActivity(new Intent(requireContext(), YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));
        rvTrending.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvTrending.setAdapter(adapter);

        loadTrending();
    }

    private void loadTrending() {
        trendingListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                List<YouTubeVideo> list = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) {
                    YouTubeVideo v = ds.getValue(YouTubeVideo.class);
                    if (v != null && "public".equals(v.visibility)) list.add(v);
                }
                list.sort((a, b) -> Long.compare(b.viewCount, a.viewCount));
                adapter.setData(list);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        YouTubeFirebaseUtils.globalFeedRef()
            .orderByChild("viewCount").limitToLast(30)
            .addValueEventListener(trendingListener);
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        if (trendingListener != null)
            YouTubeFirebaseUtils.globalFeedRef().removeEventListener(trendingListener);
    }
}
