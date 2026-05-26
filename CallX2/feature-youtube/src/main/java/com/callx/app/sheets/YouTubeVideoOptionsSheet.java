package com.callx.app.sheets;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.bumptech.glide.Glide;
import com.callx.app.activities.YouTubeChannelActivity;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeDownloadManager;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ServerValue;

/**
 * Video 3-dot menu bottom sheet.
 *
 * Options (all users):
 *  - Save to Watch Later
 *  - Save to Playlist     ← NEW
 *  - Download
 *  - Share
 *  - View Channel
 *  - Not Interested
 *  - Report
 *
 * Options (video owner only):
 *  - Delete video
 */
public class YouTubeVideoOptionsSheet extends BottomSheetDialogFragment {

    private static final String ARG_VIDEO_ID     = "video_id";
    private static final String ARG_TITLE        = "title";
    private static final String ARG_CHANNEL      = "channel";
    private static final String ARG_THUMB        = "thumb";
    private static final String ARG_UPLOADER_UID = "uploader_uid";
    private static final String ARG_VIDEO_URL    = "video_url";

    private String  videoId, title, channel, thumbUrl, uploaderUid, videoUrl;
    private String  myUid = "";
    private OptionsCallback callback;

    public interface OptionsCallback {
        void onNotInterested(String videoId);
        void onVideoDeleted(String videoId);
    }

    public static YouTubeVideoOptionsSheet newInstance(YouTubeVideo video) {
        YouTubeVideoOptionsSheet s = new YouTubeVideoOptionsSheet();
        Bundle b = new Bundle();
        b.putString(ARG_VIDEO_ID,     video.videoId);
        b.putString(ARG_TITLE,        video.title);
        b.putString(ARG_CHANNEL,      video.uploaderName);
        b.putString(ARG_THUMB,        video.thumbnailUrl);
        b.putString(ARG_UPLOADER_UID, video.uploaderUid);
        b.putString(ARG_VIDEO_URL,    video.videoUrl);
        s.setArguments(b);
        return s;
    }

    public void setCallback(OptionsCallback cb) { this.callback = cb; }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        return inf.inflate(R.layout.bottom_sheet_yt_video_options, parent, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);

        Bundle args = getArguments();
        if (args == null) { dismiss(); return; }
        videoId     = args.getString(ARG_VIDEO_ID);
        title       = args.getString(ARG_TITLE);
        channel     = args.getString(ARG_CHANNEL);
        thumbUrl    = args.getString(ARG_THUMB);
        uploaderUid = args.getString(ARG_UPLOADER_UID);
        videoUrl    = args.getString(ARG_VIDEO_URL);

        var user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) myUid = user.getUid();

        // Header
        ImageView ivThumb = view.findViewById(R.id.iv_yt_sheet_thumb);
        TextView  tvTitle = view.findViewById(R.id.tv_yt_sheet_title);
        TextView  tvCh    = view.findViewById(R.id.tv_yt_sheet_channel);
        if (ivThumb != null) Glide.with(this).load(thumbUrl).centerCrop().into(ivThumb);
        if (tvTitle != null) tvTitle.setText(title);
        if (tvCh    != null) tvCh.setText(channel);

        // Watch Later
        bindOption(view, R.id.btn_yt_option_watch_later, () -> {
            if (myUid.isEmpty()) return;
            YouTubeFirebaseUtils.watchLaterRef(myUid).child(videoId)
                .setValue(System.currentTimeMillis());
            YouTubeFirebaseUtils.videoRef(videoId).child("savedCount")
                .setValue(ServerValue.increment(1));
            toast("Saved to Watch Later");
        });

        // Save to Playlist
        bindOption(view, R.id.btn_yt_option_playlist, () -> {
            if (myUid.isEmpty()) { toast("Sign in first"); return; }
            YouTubeSaveToPlaylistSheet.newInstance(videoId, title)
                .show(getParentFragmentManager(), "save_playlist");
            dismiss();
        });

        // Download
        bindOption(view, R.id.btn_yt_option_download, () -> {
            if (videoUrl == null || videoUrl.isEmpty()) { toast("URL not available"); return; }
            com.callx.app.models.YouTubeVideo dlVideo = new com.callx.app.models.YouTubeVideo();
            dlVideo.videoId = videoId; dlVideo.title = title;
            dlVideo.videoUrl = videoUrl; dlVideo.thumbnailUrl = thumbUrl;
            YouTubeDownloadManager.startDownload(requireContext(), dlVideo,
                new YouTubeDownloadManager.DownloadCallback() {
                    @Override public void onStarted() {
                        requireActivity().runOnUiThread(() -> toast("Download started…"));
                    }
                    @Override public void onProgress(int percent) {}
                    @Override public void onCompleted(String localPath) {
                        requireActivity().runOnUiThread(() -> toast("Download complete"));
                    }
                    @Override public void onAlreadyDownloaded(String localPath) {
                        requireActivity().runOnUiThread(() -> toast("Already downloaded"));
                    }
                    @Override public void onError(String error) {
                        requireActivity().runOnUiThread(() -> toast("Download failed"));
                    }
                });
        });

        // Share
        bindOption(view, R.id.btn_yt_option_share, () -> {
            String msg = (title != null ? title : "") + "\nhttps://callx.app/watch?v=" + videoId;
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, msg);
            startActivity(Intent.createChooser(i, "Share video"));
            YouTubeFirebaseUtils.videoRef(videoId).child("shareCount")
                .setValue(ServerValue.increment(1));
            dismiss();
        });

        // View Channel
        bindOption(view, R.id.btn_yt_option_channel, () -> {
            if (uploaderUid == null || uploaderUid.isEmpty()) return;
            startActivity(new Intent(requireContext(), YouTubeChannelActivity.class)
                .putExtra("uid", uploaderUid));
            dismiss();
        });

        // Not Interested
        bindOption(view, R.id.btn_yt_option_not_interested, () -> {
            if (!myUid.isEmpty())
                YouTubeFirebaseUtils.notInterestedRef(myUid, videoId).setValue(true);
            if (callback != null) callback.onNotInterested(videoId);
            dismiss();
        });

        // Report
        bindOption(view, R.id.btn_yt_option_report, () -> {
            if (!myUid.isEmpty())
                YouTubeFirebaseUtils.reportsRef(videoId, myUid).setValue(true);
            toast("Video reported");
            dismiss();
        });

        // Delete (owner only)
        View rowDelete = view.findViewById(R.id.btn_yt_option_delete);
        if (rowDelete != null) {
            boolean isOwner = myUid.equals(uploaderUid) && !myUid.isEmpty();
            rowDelete.setVisibility(isOwner ? View.VISIBLE : View.GONE);
            if (isOwner) {
                rowDelete.setOnClickListener(v -> new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Delete Video")
                    .setMessage("This will permanently delete the video and all its data.")
                    .setPositiveButton("Delete", (d, w) -> deleteVideo())
                    .setNegativeButton("Cancel", null)
                    .show());
            }
        }
    }

    private void bindOption(View root, int rowId, Runnable action) {
        View row = root.findViewById(rowId);
        if (row != null) row.setOnClickListener(v -> action.run());
    }

    private void deleteVideo() {
        YouTubeFirebaseUtils.videoRef(videoId).removeValue()
            .addOnSuccessListener(v2 -> {
                YouTubeFirebaseUtils.globalFeedRef().child(videoId).removeValue();
                if (uploaderUid != null && !uploaderUid.isEmpty())
                    YouTubeFirebaseUtils.userVideosRef(uploaderUid).child(videoId).removeValue();
                YouTubeFirebaseUtils.commentsRef(videoId).removeValue();
                if (callback != null) callback.onVideoDeleted(videoId);
                toast("Video deleted");
                dismiss();
            })
            .addOnFailureListener(e -> toast("Delete failed: " + e.getMessage()));
    }

    private void toast(String msg) {
        if (!isAdded()) return;
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
