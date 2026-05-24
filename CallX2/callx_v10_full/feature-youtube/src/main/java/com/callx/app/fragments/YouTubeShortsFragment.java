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
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.activities.YouTubePlayerActivity;
import com.callx.app.adapters.YouTubeVideoAdapter;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

/** Vertical snap-scroll feed for YouTube Shorts (videos ≤60s). */
public class YouTubeShortsFragment extends Fragment {

    private RecyclerView       rvShorts;
    private YouTubeVideoAdapter adapter;
    private ValueEventListener shortsListener;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        return inf.inflate(R.layout.fragment_youtube_shorts, parent, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);

        rvShorts = view.findViewById(R.id.rv_yt_shorts);

        // Vertical snap-scroll like TikTok / Instagram Reels
        LinearLayoutManager llm = new LinearLayoutManager(requireContext());
        rvShorts.setLayoutManager(llm);
        PagerSnapHelper snapHelper = new PagerSnapHelper();
        snapHelper.attachToRecyclerView(rvShorts);

        adapter = new YouTubeVideoAdapter(requireContext(), new ArrayList<>(), video ->
            startActivity(new Intent(requireContext(), YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));
        rvShorts.setAdapter(adapter);

        loadShorts();
    }

    private void loadShorts() {
        shortsListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                List<YouTubeVideo> list = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) {
                    YouTubeVideo v = ds.getValue(YouTubeVideo.class);
                    if (v != null && v.isShort && "public".equals(v.visibility))
                        list.add(0, v);
                }
                adapter.setData(list);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        YouTubeFirebaseUtils.globalFeedRef()
            .orderByChild("isShort").equalTo(true)
            .limitToLast(30)
            .addValueEventListener(shortsListener);
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        if (shortsListener != null)
            YouTubeFirebaseUtils.globalFeedRef().removeEventListener(shortsListener);
    }
}
