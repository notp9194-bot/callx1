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

/**
 * Vertical snap-scroll feed for YouTube Shorts (videos ≤60s).
 *
 * FIX: Firebase RTDB mein boolean field pe orderByChild().equalTo(true) kaam
 * nahi karta reliably — instead globalFeedRef se saara data lo aur client-side
 * filter karo isShort==true ke liye.
 */
public class YouTubeShortsFragment extends Fragment {

    private RecyclerView        rvShorts;
    private YouTubeVideoAdapter adapter;
    private ValueEventListener  shortsListener;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        return inf.inflate(R.layout.fragment_youtube_shorts, parent, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);

        rvShorts = view.findViewById(R.id.rv_yt_shorts);

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
        // FIX: equalTo(true) on boolean doesn't work in RTDB — client-side filter karo
        shortsListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                List<YouTubeVideo> list = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) {
                    YouTubeVideo v = ds.getValue(YouTubeVideo.class);
                    if (v != null && v.isShort && "public".equals(v.visibility)
                            && v.videoUrl != null && !v.videoUrl.trim().isEmpty())
                        list.add(0, v);
                }
                adapter.setData(list);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        // globalFeedRef se lo — isShort field pe filter client-side
        YouTubeFirebaseUtils.globalFeedRef()
            .orderByChild("uploadedAt")
            .limitToLast(50)
            .addValueEventListener(shortsListener);
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        if (shortsListener != null)
            YouTubeFirebaseUtils.globalFeedRef().removeEventListener(shortsListener);
    }
}
