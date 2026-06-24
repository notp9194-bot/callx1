package com.callx.app.feed;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.reels.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * OriginalAudioOptionsSheet
 * ─────────────────────────
 * Shown when user taps the music disc / ticker on the reel player.
 *
 * UI (mirrors Duet volume panel style):
 *   • Music cover art + title + artist label
 *   • Original Audio volume slider (0–100%) — live-adjusts reel playback volume
 *   • [Use in Camera]  — opens ReelCameraActivity with this audio pre-selected
 *   • [Use in Gallery] — opens gallery picker; selected video opens ReelEditorActivity
 *                        with this audio pre-selected (so AudioMixer has it ready)
 */
public class OriginalAudioOptionsSheet extends BottomSheetDialogFragment {

    public static final String TAG = "OriginalAudioOptionsSheet";

    private static final String ARG_MUSIC_NAME      = "music_name";
    private static final String ARG_MUSIC_ARTIST    = "music_artist";
    private static final String ARG_MUSIC_COVER_URL = "music_cover_url";
    private static final String ARG_AUDIO_URL       = "audio_url";
    private static final String ARG_REEL_ID         = "reel_id";
    private static final String ARG_OWNER_NAME      = "owner_name";

    // ── Listener ──────────────────────────────────────────────────────────

    public interface Listener {
        /** Called when user drags volume slider. 0.0–1.0 */
        void onVolumeChanged(float volume);
        /** "Use in Camera" tapped — open camera with this audio pre-selected */
        void onUseInCamera(String audioUrl, String soundTitle, String soundId);
        /** "Use in Gallery" tapped — open gallery; editor will pre-select this audio */
        void onUseInGallery(String audioUrl, String soundTitle, String soundId);
    }

    private Listener listener;

    public void setListener(Listener l) { this.listener = l; }

    // ── Factory ───────────────────────────────────────────────────────────

    public static OriginalAudioOptionsSheet newInstance(
            String musicName, String musicArtist, String musicCoverUrl,
            String audioUrl, String reelId, String ownerName) {

        OriginalAudioOptionsSheet sheet = new OriginalAudioOptionsSheet();
        Bundle args = new Bundle();
        args.putString(ARG_MUSIC_NAME,      musicName      != null ? musicName      : "Original Audio");
        args.putString(ARG_MUSIC_ARTIST,    musicArtist    != null ? musicArtist    : "");
        args.putString(ARG_MUSIC_COVER_URL, musicCoverUrl  != null ? musicCoverUrl  : "");
        args.putString(ARG_AUDIO_URL,       audioUrl       != null ? audioUrl       : "");
        args.putString(ARG_REEL_ID,         reelId         != null ? reelId         : "");
        args.putString(ARG_OWNER_NAME,      ownerName      != null ? ownerName      : "");
        sheet.setArguments(args);
        return sheet;
    }

    // ── Inflate ───────────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_original_audio_options, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View root, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(root, savedInstanceState);

        Bundle args      = getArguments();
        String musicName = args != null ? args.getString(ARG_MUSIC_NAME,      "Original Audio") : "Original Audio";
        String artist    = args != null ? args.getString(ARG_MUSIC_ARTIST,    "")               : "";
        String coverUrl  = args != null ? args.getString(ARG_MUSIC_COVER_URL, "")               : "";
        String audioUrl  = args != null ? args.getString(ARG_AUDIO_URL,       "")               : "";
        String reelId    = args != null ? args.getString(ARG_REEL_ID,         "")               : "";
        String ownerName = args != null ? args.getString(ARG_OWNER_NAME,      "")               : "";

        // Sound title shown in camera/editor = "Original audio · @ownerName"
        final String soundTitle = musicName + (ownerName.isEmpty() ? "" : " · @" + ownerName);
        // Sound ID = reelId (used as soundId for pre-selection in editor/camera)
        final String soundId    = reelId;
        final String finalAudioUrl = audioUrl;

        // ── Views ─────────────────────────────────────────────────────────
        ImageView ivCover    = root.findViewById(R.id.iv_audio_cover);
        TextView  tvTitle    = root.findViewById(R.id.tv_audio_title);
        TextView  tvArtist   = root.findViewById(R.id.tv_audio_artist);
        SeekBar   seekVolume = root.findViewById(R.id.seek_audio_volume);
        TextView  tvVolLabel = root.findViewById(R.id.tv_audio_volume_label);
        View      btnCamera  = root.findViewById(R.id.btn_use_in_camera);
        View      btnGallery = root.findViewById(R.id.btn_use_in_gallery);

        // ── Populate ──────────────────────────────────────────────────────
        if (tvTitle  != null) tvTitle.setText(musicName);
        if (tvArtist != null) {
            String artistLine = artist.isEmpty()
                ? (ownerName.isEmpty() ? "" : "@" + ownerName)
                : artist;
            if (artistLine.isEmpty()) {
                tvArtist.setVisibility(View.GONE);
            } else {
                tvArtist.setText(artistLine);
                tvArtist.setVisibility(View.VISIBLE);
            }
        }
        if (ivCover != null) {
            if (!coverUrl.isEmpty()) {
                Glide.with(this)
                    .load(coverUrl)
                    .apply(new RequestOptions().circleCrop().placeholder(R.drawable.ic_music_disc))
                    .into(ivCover);
            } else {
                ivCover.setImageResource(R.drawable.ic_music_disc);
            }
        }

        // ── Volume slider (duet-style, live preview) ──────────────────────
        if (seekVolume != null) {
            seekVolume.setMax(100);
            seekVolume.setProgress(100);
            if (tvVolLabel != null) tvVolLabel.setText("Original audio: 100%");

            seekVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    if (tvVolLabel != null)
                        tvVolLabel.setText("Original audio: " + progress + "%");
                    if (fromUser && listener != null)
                        listener.onVolumeChanged(progress / 100f);
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
        }

        // ── Use in Camera ─────────────────────────────────────────────────
        if (btnCamera != null) {
            btnCamera.setOnClickListener(v -> {
                dismiss();
                if (listener != null)
                    listener.onUseInCamera(finalAudioUrl, soundTitle, soundId);
            });
        }

        // ── Use in Gallery ────────────────────────────────────────────────
        if (btnGallery != null) {
            btnGallery.setOnClickListener(v -> {
                dismiss();
                if (listener != null)
                    listener.onUseInGallery(finalAudioUrl, soundTitle, soundId);
            });
        }
    }
}
