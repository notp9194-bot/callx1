package com.callx.app.sheets;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.callx.app.models.YouTubePlaylist;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Save video to one of user's existing playlists OR create a new one.
 * Shows each playlist with a checkbox; checks the ones already containing the video.
 */
public class YouTubeSaveToPlaylistSheet extends BottomSheetDialogFragment {

    private static final String ARG_VIDEO_ID = "video_id";
    private static final String ARG_TITLE    = "video_title";

    private String videoId, videoTitle, myUid;
    private LinearLayout llPlaylists;
    private View         btnNewPlaylist, btnCreateConfirm;
    private View         vNewPlaylistForm;
    private EditText     etNewPlaylistTitle;
    private RadioGroup   rgPrivacy;

    public static YouTubeSaveToPlaylistSheet newInstance(String videoId, String videoTitle) {
        YouTubeSaveToPlaylistSheet s = new YouTubeSaveToPlaylistSheet();
        Bundle b = new Bundle();
        b.putString(ARG_VIDEO_ID, videoId);
        b.putString(ARG_TITLE, videoTitle);
        s.setArguments(b);
        return s;
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        return inf.inflate(R.layout.sheet_youtube_save_to_playlist, parent, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);

        if (getArguments() == null) { dismiss(); return; }
        videoId    = getArguments().getString(ARG_VIDEO_ID);
        videoTitle = getArguments().getString(ARG_TITLE);
        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
        if (myUid.isEmpty()) { dismiss(); return; }

        llPlaylists       = view.findViewById(R.id.ll_yt_playlists_list);
        btnNewPlaylist    = view.findViewById(R.id.btn_yt_new_playlist);
        vNewPlaylistForm  = view.findViewById(R.id.v_yt_new_playlist_form);
        etNewPlaylistTitle= view.findViewById(R.id.et_yt_new_playlist_name);
        rgPrivacy         = view.findViewById(R.id.rg_yt_playlist_privacy);
        btnCreateConfirm  = view.findViewById(R.id.btn_yt_create_playlist_confirm);

        if (btnNewPlaylist != null)
            btnNewPlaylist.setOnClickListener(v -> {
                if (vNewPlaylistForm != null)
                    vNewPlaylistForm.setVisibility(
                        vNewPlaylistForm.getVisibility() == View.VISIBLE
                            ? View.GONE : View.VISIBLE);
            });

        if (btnCreateConfirm != null)
            btnCreateConfirm.setOnClickListener(v -> createAndSave());

        loadPlaylists();
    }

    private void loadPlaylists() {
        if (llPlaylists == null) return;
        YouTubeFirebaseUtils.playlistsRef(myUid).addListenerForSingleValueEvent(
            new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!isAdded()) return;
                    llPlaylists.removeAllViews();
                    if (!snap.hasChildren()) {
                        TextView empty = new TextView(requireContext());
                        empty.setText("No playlists yet. Create one below.");
                        empty.setPadding(dp(16), dp(8), dp(16), dp(8));
                        llPlaylists.addView(empty);
                        return;
                    }
                    for (DataSnapshot ds : snap.getChildren()) {
                        String pid   = ds.getKey();
                        String title = ds.child("title").getValue(String.class);
                        if (title == null) title = "Untitled";
                        CheckBox cb = new CheckBox(requireContext());
                        cb.setText(title);
                        cb.setPadding(dp(16), dp(8), dp(16), dp(8));
                        final String playlistId = pid;
                        // Check if video is already in this playlist
                        YouTubeFirebaseUtils.playlistVideosRef(myUid, pid).child(videoId)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(@NonNull DataSnapshot vs) {
                                    if (!isAdded()) return;
                                    cb.setChecked(vs.exists());
                                    cb.setOnCheckedChangeListener((btn, checked) -> {
                                        if (checked) addToPlaylist(playlistId);
                                        else removeFromPlaylist(playlistId);
                                    });
                                }
                                @Override public void onCancelled(@NonNull DatabaseError e) {}
                            });
                        llPlaylists.addView(cb);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void addToPlaylist(String playlistId) {
        YouTubeFirebaseUtils.playlistVideosRef(myUid, playlistId)
            .child(videoId).setValue(System.currentTimeMillis());
        YouTubeFirebaseUtils.playlistRef(myUid, playlistId)
            .child("videoCount").setValue(ServerValue.increment(1));
        YouTubeFirebaseUtils.videoRef(videoId).child("savedCount").setValue(ServerValue.increment(1));
        Toast.makeText(requireContext(), "Saved to playlist", Toast.LENGTH_SHORT).show();
    }

    private void removeFromPlaylist(String playlistId) {
        YouTubeFirebaseUtils.playlistVideosRef(myUid, playlistId).child(videoId).removeValue();
        YouTubeFirebaseUtils.playlistRef(myUid, playlistId)
            .child("videoCount").setValue(ServerValue.increment(-1));
        YouTubeFirebaseUtils.videoRef(videoId).child("savedCount").setValue(ServerValue.increment(-1));
    }

    private void createAndSave() {
        if (etNewPlaylistTitle == null) return;
        String title = etNewPlaylistTitle.getText().toString().trim();
        if (title.isEmpty()) { etNewPlaylistTitle.setError("Enter a title"); return; }

        int checkedId = rgPrivacy != null ? rgPrivacy.getCheckedRadioButtonId() : -1;
        String privacy = "private";
        if (checkedId != -1) {
            RadioButton rb = rgPrivacy != null ? rgPrivacy.findViewById(checkedId) : null;
            if (rb != null && "Public".equals(rb.getText().toString())) privacy = "public";
        }

        String pid = YouTubeFirebaseUtils.playlistsRef(myUid).push().getKey();
        if (pid == null) return;

        Map<String, Object> playlist = new HashMap<>();
        playlist.put("playlistId", pid);
        playlist.put("title", title);
        playlist.put("privacy", privacy);
        playlist.put("createdAt", System.currentTimeMillis());
        playlist.put("videoCount", 1);
        playlist.put("ownerUid", myUid);

        YouTubeFirebaseUtils.playlistRef(myUid, pid).setValue(playlist)
            .addOnSuccessListener(v2 -> {
                addToPlaylist(pid);
                if (vNewPlaylistForm != null) vNewPlaylistForm.setVisibility(View.GONE);
                etNewPlaylistTitle.setText("");
                loadPlaylists();
            });
    }

    private int dp(int d) {
        return (int) (d * requireContext().getResources().getDisplayMetrics().density);
    }
}
