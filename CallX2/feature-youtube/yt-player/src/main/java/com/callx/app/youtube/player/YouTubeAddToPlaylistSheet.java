package com.callx.app.youtube.player;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.callx.app.youtube.core.models.YouTubePlaylist;
import com.callx.app.youtube.core.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.player.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

/**
 * YouTubeAddToPlaylistSheet — video ko playlist mein add karna
 * - Existing playlists checkbox list
 * - "+ Nayi Playlist Banao" button
 */
public class YouTubeAddToPlaylistSheet extends BottomSheetDialogFragment {

    private static final String ARG_VIDEO_ID = "video_id";

    private String videoId, myUid;
    private LinearLayout llPlaylists;
    private final List<String> selectedPlaylistIds = new ArrayList<>();

    public static YouTubeAddToPlaylistSheet newInstance(String videoId) {
        YouTubeAddToPlaylistSheet sheet = new YouTubeAddToPlaylistSheet();
        Bundle b = new Bundle();
        b.putString(ARG_VIDEO_ID, videoId);
        sheet.setArguments(b);
        return sheet;
    }

    @Override public void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        videoId = getArguments() != null ? getArguments().getString(ARG_VIDEO_ID, "") : "";
        myUid   = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        return inf.inflate(R.layout.bottom_sheet_yt_add_to_playlist, parent, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        llPlaylists = view.findViewById(R.id.ll_yt_playlist_list);

        View btnClose = view.findViewById(R.id.btn_atpl_close);
        if (btnClose != null) btnClose.setOnClickListener(v -> dismiss());

        View btnNew = view.findViewById(R.id.btn_atpl_new_playlist);
        if (btnNew != null) btnNew.setOnClickListener(v -> {
            dismiss();
            startActivity(new Intent(requireContext(), YouTubePlaylistCreateActivity.class)
                .putExtra("video_id", videoId));
        });

        View btnSave = view.findViewById(R.id.btn_atpl_save);
        if (btnSave != null) btnSave.setOnClickListener(v -> saveToPlaylists());

        loadPlaylists();
    }

    private void loadPlaylists() {
        if (myUid.isEmpty()) return;
        // Use YouTubeFirebaseUtils.playlistsRef — no DB_URL needed
        YouTubeFirebaseUtils.playlistsRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (llPlaylists == null || !isAdded()) return;
                    llPlaylists.removeAllViews();
                    for (DataSnapshot ds : snap.getChildren()) {
                        YouTubePlaylist pl = ds.getValue(YouTubePlaylist.class);
                        if (pl == null || pl.playlistId == null) continue;
                        String plId = pl.playlistId;

                        View item = LayoutInflater.from(requireContext())
                            .inflate(R.layout.item_yt_playlist_select, llPlaylists, false);
                        TextView tvTitle = item.findViewById(R.id.tv_playlist_select_title);
                        CheckBox cb      = item.findViewById(R.id.cb_playlist_select);
                        if (tvTitle != null) tvTitle.setText(pl.title);

                        // Pre-check if video already in playlist
                        YouTubeFirebaseUtils.playlistVideosRef(myUid, plId).child(videoId)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(@NonNull DataSnapshot s) {
                                    if (s.exists() && cb != null) {
                                        cb.setChecked(true);
                                        if (!selectedPlaylistIds.contains(plId))
                                            selectedPlaylistIds.add(plId);
                                    }
                                }
                                @Override public void onCancelled(@NonNull DatabaseError e) {}
                            });

                        if (cb != null) cb.setOnCheckedChangeListener((v, checked) -> {
                            if (checked) { if (!selectedPlaylistIds.contains(plId)) selectedPlaylistIds.add(plId); }
                            else selectedPlaylistIds.remove(plId);
                        });
                        item.setOnClickListener(v -> { if (cb != null) cb.setChecked(!cb.isChecked()); });
                        llPlaylists.addView(item);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void saveToPlaylists() {
        if (selectedPlaylistIds.isEmpty()) { dismiss(); return; }
        for (String plId : selectedPlaylistIds) {
            YouTubeFirebaseUtils.playlistVideosRef(myUid, plId)
                .child(videoId).setValue(System.currentTimeMillis());
            YouTubeFirebaseUtils.playlistRef(myUid, plId).child("videoCount")
                .runTransaction(new Transaction.Handler() {
                    @NonNull @Override public Transaction.Result doTransaction(@NonNull MutableData d) {
                        Long c = d.getValue(Long.class); d.setValue(c == null ? 1 : c + 1); return Transaction.success(d);
                    }
                    @Override public void onComplete(@Nullable DatabaseError e, boolean b, @Nullable DataSnapshot s) {}
                });
        }
        Toast.makeText(requireContext(), "Playlist mein save ho gaya!", Toast.LENGTH_SHORT).show();
        dismiss();
    }
}
