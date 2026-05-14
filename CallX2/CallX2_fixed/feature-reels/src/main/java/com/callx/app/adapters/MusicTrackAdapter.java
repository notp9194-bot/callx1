package com.callx.app.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.reels.R;
import com.callx.app.models.MusicTrack;

import java.util.List;

public class MusicTrackAdapter
        extends RecyclerView.Adapter<MusicTrackAdapter.TrackVH> {

    public interface OnTrackActionListener {
        void onPreviewToggle(MusicTrack track, int position);
        void onSelect(MusicTrack track);
    }

    private final List<MusicTrack>       tracks;
    private final OnTrackActionListener  listener;
    private int                          playingPosition = -1;

    public MusicTrackAdapter(List<MusicTrack> tracks, OnTrackActionListener listener) {
        this.tracks   = tracks;
        this.listener = listener;
    }

    public void setPlayingPosition(int pos) { this.playingPosition = pos; }

    @NonNull
    @Override
    public TrackVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_music_track, parent, false);
        return new TrackVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull TrackVH h, int pos) {
        MusicTrack track = tracks.get(pos);
        h.tvName.setText(track.name);
        h.tvArtist.setText(track.artist != null ? track.artist : "");
        h.tvGenre.setText(track.genre  != null ? track.genre  : "");

        boolean isPlaying = pos == playingPosition;
        h.btnPreview.setImageResource(
            isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);

        h.btnPreview.setOnClickListener(v ->
            listener.onPreviewToggle(track, h.getAdapterPosition()));
        h.btnSelect.setOnClickListener(v ->
            listener.onSelect(track));
        h.itemView.setOnClickListener(v ->
            listener.onPreviewToggle(track, h.getAdapterPosition()));
    }

    @Override public int getItemCount() { return tracks.size(); }

    static class TrackVH extends RecyclerView.ViewHolder {
        TextView    tvName, tvArtist, tvGenre;
        ImageButton btnPreview, btnSelect;

        TrackVH(View v) {
            super(v);
            tvName     = v.findViewById(R.id.tv_music_name);
            tvArtist   = v.findViewById(R.id.tv_music_artist);
            tvGenre    = v.findViewById(R.id.tv_music_genre);
            btnPreview = v.findViewById(R.id.btn_music_preview);
            btnSelect  = v.findViewById(R.id.btn_music_select);
        }
    }
}
