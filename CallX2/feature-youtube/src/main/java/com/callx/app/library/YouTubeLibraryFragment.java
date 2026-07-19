package com.callx.app.library;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.bumptech.glide.Glide;
import com.callx.app.downloads.YouTubeDownloadsActivity;
import com.callx.app.models.YouTubePlaylist;
import com.callx.app.playlist.YouTubePlaylistActivity;
import com.callx.app.playlist.YouTubePlaylistCreateActivity;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

/**
 * YouTubeLibraryFragment — Full Library tab.
 * - Downloads, Watch Later, History, Liked Videos (shortcuts)
 * - Playlists section with user's playlists loaded from Firebase
 * - "New Playlist" quick create button
 */
public class YouTubeLibraryFragment extends Fragment {

    private LinearLayout llPlaylistsContainer;
    private String myUid;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        return inf.inflate(R.layout.fragment_youtube_library, parent, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        // ── Quick shortcuts ──────────────────────────────────────────────────
        view.findViewById(R.id.btn_yt_downloads).setOnClickListener(v ->
            startActivity(new Intent(requireContext(), YouTubeDownloadsActivity.class)));

        view.findViewById(R.id.btn_yt_watch_later).setOnClickListener(v ->
            startActivity(new Intent(requireContext(), YouTubeWatchLaterActivity.class)));

        view.findViewById(R.id.btn_yt_history).setOnClickListener(v ->
            startActivity(new Intent(requireContext(), YouTubeHistoryActivity.class)));

        view.findViewById(R.id.btn_yt_liked).setOnClickListener(v ->
            startActivity(new Intent(requireContext(), YouTubeLikedVideosActivity.class)));

        // ── Playlists ────────────────────────────────────────────────────────
        llPlaylistsContainer = view.findViewById(R.id.ll_yt_playlists_container);

        View btnNewPlaylist = view.findViewById(R.id.btn_yt_new_playlist);
        if (btnNewPlaylist != null) {
            btnNewPlaylist.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), YouTubePlaylistCreateActivity.class)));
        }

        if (!myUid.isEmpty()) loadPlaylists();
    }

    private void loadPlaylists() {
        YouTubeFirebaseUtils.playlistsRef(myUid)
            .orderByChild("createdAt")
            .addValueEventListener(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (llPlaylistsContainer == null || !isAdded()) return;
                    List<YouTubePlaylist> playlists = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) {
                        YouTubePlaylist pl = ds.getValue(YouTubePlaylist.class);
                        if (pl != null) playlists.add(0, pl); // newest first
                    }

                    View tvNoPlaylists = getView() != null
                        ? getView().findViewById(R.id.tv_yt_no_playlists) : null;

                    // Keep only playlist rows (remove old dynamic ones)
                    // Dynamic rows have tag "playlist_row"
                    List<View> toRemove = new ArrayList<>();
                    for (int i = 0; i < llPlaylistsContainer.getChildCount(); i++) {
                        View child = llPlaylistsContainer.getChildAt(i);
                        if ("playlist_row".equals(child.getTag())) toRemove.add(child);
                    }
                    for (View v : toRemove) llPlaylistsContainer.removeView(v);

                    if (playlists.isEmpty()) {
                        if (tvNoPlaylists != null) tvNoPlaylists.setVisibility(View.VISIBLE);
                        return;
                    }
                    if (tvNoPlaylists != null) tvNoPlaylists.setVisibility(View.GONE);

                    for (YouTubePlaylist pl : playlists) {
                        View row = buildPlaylistRow(pl);
                        row.setTag("playlist_row");
                        llPlaylistsContainer.addView(row);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private View buildPlaylistRow(YouTubePlaylist pl) {
        View row = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_yt_library_playlist, llPlaylistsContainer, false);

        ImageView ivThumb = row.findViewById(R.id.iv_yt_lib_playlist_thumb);
        TextView tvTitle  = row.findViewById(R.id.tv_yt_lib_playlist_title);
        TextView tvMeta   = row.findViewById(R.id.tv_yt_lib_playlist_meta);

        if (tvTitle != null) tvTitle.setText(pl.title != null ? pl.title : "Untitled Playlist");

        String visibility = pl.visibility != null ? capitalize(pl.visibility) : "Public";
        long count = pl.videoCount;
        if (tvMeta != null)
            tvMeta.setText(count + " video" + (count != 1 ? "s" : "") + "  •  " + visibility);

        if (ivThumb != null && pl.thumbnailUrl != null && !pl.thumbnailUrl.isEmpty()) {
            Glide.with(ivThumb.getContext()).load(pl.thumbnailUrl)
                .centerCrop().placeholder(R.drawable.ic_yt_placeholder).into(ivThumb);
        } else if (ivThumb != null) {
            ivThumb.setImageResource(R.drawable.ic_yt_placeholder);
        }

        row.setOnClickListener(v ->
            startActivity(new Intent(requireContext(), YouTubePlaylistActivity.class)
                .putExtra("playlist_id", pl.playlistId)
                .putExtra("owner_uid", myUid)));

        // Divider
        View div = new View(requireContext());
        div.setBackgroundColor(0xFF252525);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        div.setLayoutParams(lp);

        return row;
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    private int dp(int val) {
        return Math.round(val * requireContext().getResources().getDisplayMetrics().density);
    }
}
