package com.callx.app.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.activities.*;
import com.callx.app.adapters.YouTubeVideoAdapter;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Library tab:
 * - Continue Watching (resume progress videos)
 * - Watch Later
 * - History
 * - Liked Videos
 * - Playlists
 * - Downloads (coming soon)
 */
public class YouTubeLibraryFragment extends Fragment {

    private RecyclerView         rvContinue;
    private YouTubeVideoAdapter  continueAdapter;
    private String               myUid;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup p,
                             @Nullable Bundle s) {
        return inf.inflate(R.layout.fragment_youtube_library, p, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        // Quick navigation buttons
        setupNavButton(view, R.id.btn_yt_watch_later, YouTubeWatchLaterActivity.class);
        setupNavButton(view, R.id.btn_yt_history,     YouTubeHistoryActivity.class);
        setupNavButton(view, R.id.btn_yt_liked,       YouTubeLikedVideosActivity.class);
        setupNavButton(view, R.id.btn_yt_playlists,   YouTubePlaylistActivity.class);

        View btnDownloads = view.findViewById(R.id.btn_yt_downloads);
        if (btnDownloads != null) btnDownloads.setOnClickListener(v ->
            startActivity(new Intent(requireContext(), YouTubeDownloadsActivity.class)));

        // Continue Watching row
        rvContinue      = view.findViewById(R.id.rv_yt_continue_watching);
        View continueSection = view.findViewById(R.id.layout_yt_continue_watching);

        if (rvContinue != null) {
            continueAdapter = new YouTubeVideoAdapter(requireContext(), new ArrayList<>(), video ->
                startActivity(new Intent(requireContext(), YouTubePlayerActivity.class)
                    .putExtra("video_id", video.videoId)));
            rvContinue.setLayoutManager(
                new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
            rvContinue.setAdapter(continueAdapter);
            loadContinueWatching(continueSection);
        }

        // Creator Studio shortcut (for own channel)
        View btnCreator = view.findViewById(R.id.btn_yt_creator_studio);
        if (btnCreator != null) btnCreator.setOnClickListener(v ->
            startActivity(new Intent(requireContext(), YouTubeCreatorStudioActivity.class)));
    }

    private void setupNavButton(View root, int id, Class<?> cls) {
        View btn = root.findViewById(id);
        if (btn != null) btn.setOnClickListener(v ->
            startActivity(new Intent(requireContext(), cls)));
    }

    private void loadContinueWatching(View section) {
        if (myUid.isEmpty()) {
            if (section != null) section.setVisibility(View.GONE);
            return;
        }
        YouTubeFirebaseUtils.continueWatchingRef(myUid)
            .orderByValue().limitToLast(10)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<String> ids = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren())
                        if (ds.getKey() != null) ids.add(0, ds.getKey());
                    if (ids.isEmpty()) {
                        if (section != null) section.setVisibility(View.GONE);
                        return;
                    }
                    if (section != null) section.setVisibility(View.VISIBLE);
                    fetchContinueVideos(ids);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (section != null) section.setVisibility(View.GONE);
                }
            });
    }

    private void fetchContinueVideos(List<String> ids) {
        List<YouTubeVideo> videos = new ArrayList<>();
        final int[] count = {0};
        for (String id : ids) {
            YouTubeFirebaseUtils.videoRef(id)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        YouTubeVideo v = snap.getValue(YouTubeVideo.class);
                        if (v != null) videos.add(v);
                        if (++count[0] == ids.size())
                            continueAdapter.setData(videos);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        if (++count[0] == ids.size()) continueAdapter.setData(videos);
                    }
                });
        }
    }
}
