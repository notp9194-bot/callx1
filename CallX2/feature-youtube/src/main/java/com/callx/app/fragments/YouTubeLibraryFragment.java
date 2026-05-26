package com.callx.app.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.activities.YouTubeCreatorStudioActivity;
import com.callx.app.activities.YouTubeDownloadsActivity;
import com.callx.app.activities.YouTubeHistoryActivity;
import com.callx.app.activities.YouTubeLikedVideosActivity;
import com.callx.app.activities.YouTubePlayerActivity;
import com.callx.app.activities.YouTubePlaylistActivity;
import com.callx.app.activities.YouTubeWatchLaterActivity;
import com.callx.app.adapters.YouTubeVideoAdapter;
import com.callx.app.models.YouTubePlaylist;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Library tab — full production version.
 * Sections: Quick access, Your playlists, Continue watching.
 */
public class YouTubeLibraryFragment extends Fragment {

    private LinearLayout   llPlaylists;
    private RecyclerView   rvContinue;
    private YouTubeVideoAdapter continueAdapter;
    private TextView       tvPlaylistsEmpty;
    private String         myUid;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        return inf.inflate(R.layout.fragment_youtube_library, parent, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        // Quick access buttons
        view.findViewById(R.id.btn_yt_downloads).setOnClickListener(v ->
            startActivity(new Intent(requireContext(), YouTubeDownloadsActivity.class)));
        view.findViewById(R.id.btn_yt_watch_later).setOnClickListener(v ->
            startActivity(new Intent(requireContext(), YouTubeWatchLaterActivity.class)));
        view.findViewById(R.id.btn_yt_history).setOnClickListener(v ->
            startActivity(new Intent(requireContext(), YouTubeHistoryActivity.class)));
        view.findViewById(R.id.btn_yt_liked).setOnClickListener(v ->
            startActivity(new Intent(requireContext(), YouTubeLikedVideosActivity.class)));

        // Creator Studio button (for channel owners)
        View btnStudio = view.findViewById(R.id.btn_yt_creator_studio);
        if (btnStudio != null)
            btnStudio.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), YouTubeCreatorStudioActivity.class)));

        llPlaylists      = view.findViewById(R.id.ll_yt_playlists);
        tvPlaylistsEmpty = view.findViewById(R.id.tv_yt_playlists_empty);

        rvContinue = view.findViewById(R.id.rv_yt_continue_watching);
        if (rvContinue != null) {
            continueAdapter = new YouTubeVideoAdapter(requireActivity(), new ArrayList<>(), video ->
                startActivity(new Intent(requireContext(), YouTubePlayerActivity.class)
                    .putExtra("video_id", video.videoId)));
            rvContinue.setLayoutManager(new LinearLayoutManager(
                requireContext(), LinearLayoutManager.HORIZONTAL, false));
            rvContinue.setAdapter(continueAdapter);
        }

        if (!myUid.isEmpty()) {
            loadPlaylists();
            loadContinueWatching();
        }
    }

    private void loadPlaylists() {
        YouTubeFirebaseUtils.playlistsRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!isAdded() || llPlaylists == null) return;
                    llPlaylists.removeAllViews();
                    if (!snap.hasChildren()) {
                        if (tvPlaylistsEmpty != null) tvPlaylistsEmpty.setVisibility(View.VISIBLE);
                        return;
                    }
                    if (tvPlaylistsEmpty != null) tvPlaylistsEmpty.setVisibility(View.GONE);
                    for (DataSnapshot ds : snap.getChildren()) {
                        String playlistId = ds.getKey();
                        String title      = ds.child("title").getValue(String.class);
                        Long   count      = ds.child("videoCount").getValue(Long.class);
                        if (title == null) title = "Untitled Playlist";

                        View row = LayoutInflater.from(requireContext())
                            .inflate(R.layout.item_yt_playlist_row, llPlaylists, false);
                        TextView tvTitle = row.findViewById(R.id.tv_yt_playlist_row_title);
                        TextView tvCount = row.findViewById(R.id.tv_yt_playlist_row_count);
                        if (tvTitle != null) tvTitle.setText(title);
                        if (tvCount != null) tvCount.setText((count != null ? count : 0) + " videos");
                        final String pid = playlistId;
                        row.setOnClickListener(v ->
                            startActivity(new Intent(requireContext(), YouTubePlaylistActivity.class)
                                .putExtra("owner_uid", myUid)
                                .putExtra("playlist_id", pid)));
                        llPlaylists.addView(row);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void loadContinueWatching() {
        if (rvContinue == null) return;
        YouTubeFirebaseUtils.watchHistoryRef(myUid)
            .orderByValue().limitToLast(8)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<String> ids = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) ids.add(0, ds.getKey());
                    if (ids.isEmpty()) return;
                    List<YouTubeVideo> videos = new ArrayList<>();
                    final int[] c = {0};
                    for (String id : ids) {
                        YouTubeFirebaseUtils.videoRef(id).addListenerForSingleValueEvent(
                            new ValueEventListener() {
                                @Override public void onDataChange(@NonNull DataSnapshot vs) {
                                    YouTubeVideo v = vs.getValue(YouTubeVideo.class);
                                    if (v != null) videos.add(v);
                                    c[0]++;
                                    if (c[0] == ids.size()) continueAdapter.setData(videos);
                                }
                                @Override public void onCancelled(@NonNull DatabaseError e) {
                                    c[0]++;
                                    if (c[0] == ids.size()) continueAdapter.setData(videos);
                                }
                            });
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }
}
