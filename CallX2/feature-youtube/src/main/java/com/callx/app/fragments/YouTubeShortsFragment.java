package com.callx.app.fragments;

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
import com.callx.app.adapters.YouTubeShortsAdapter;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen immersive Shorts feed using YouTubeShortsAdapter.
 * Each item has an ExoPlayer, like/dislike/comment/share overlay buttons,
 * channel name, title, and progress bar — all within the card.
 */
public class YouTubeShortsFragment extends Fragment {

    private RecyclerView      rvShorts;
    private YouTubeShortsAdapter adapter;
    private ValueEventListener shortsListener;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup p,
                             @Nullable Bundle s) {
        return inf.inflate(R.layout.fragment_youtube_shorts, p, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);

        rvShorts = view.findViewById(R.id.rv_yt_shorts);
        adapter  = new YouTubeShortsAdapter(requireContext(), new ArrayList<>());

        LinearLayoutManager llm = new LinearLayoutManager(requireContext());
        rvShorts.setLayoutManager(llm);
        new PagerSnapHelper().attachToRecyclerView(rvShorts);
        rvShorts.setAdapter(adapter);

        rvShorts.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    int pos = ((LinearLayoutManager) rv.getLayoutManager())
                        .findFirstCompletelyVisibleItemPosition();
                    adapter.playAt(pos);
                }
            }
        });

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
                adapter.playAt(0);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        YouTubeFirebaseUtils.globalFeedRef()
            .orderByChild("isShort").equalTo(true)
            .limitToLast(50)
            .addValueEventListener(shortsListener);
    }

    @Override public void onPause() {
        super.onPause();
        adapter.pauseAll();
    }

    @Override public void onResume() {
        super.onResume();
        if (rvShorts != null) {
            int pos = ((LinearLayoutManager) rvShorts.getLayoutManager())
                .findFirstCompletelyVisibleItemPosition();
            adapter.playAt(pos);
        }
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        adapter.releaseAll();
        if (shortsListener != null)
            YouTubeFirebaseUtils.globalFeedRef().removeEventListener(shortsListener);
    }
}
