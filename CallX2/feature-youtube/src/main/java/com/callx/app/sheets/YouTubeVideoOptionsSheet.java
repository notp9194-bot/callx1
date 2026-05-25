package com.callx.app.sheets;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.bumptech.glide.Glide;
import com.callx.app.activities.YouTubeChannelActivity;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.firebase.auth.FirebaseAuth;

/**
 * YouTubeVideoOptionsSheet
 * 3-dot menu bottom sheet for any video card in the YouTube section.
 *
 * Options:
 *  - Watch Later me Save karo
 *  - Playlist me add karo
 *  - Share karo
 *  - Channel dekho
 *  - Pasand nahi
 *  - Report karo
 */
public class YouTubeVideoOptionsSheet extends BottomSheetDialogFragment {

    private static final String ARG_VIDEO_ID        = "video_id";
    private static final String ARG_TITLE           = "title";
    private static final String ARG_CHANNEL         = "channel";
    private static final String ARG_THUMB           = "thumb";
    private static final String ARG_UPLOADER_UID    = "uploader_uid";

    private String videoId, title, channel, thumbUrl, uploaderUid;
    private String myUid = "";

    // Callback for things like "remove from feed" if caller wants to act
    public interface OptionsCallback {
        void onNotInterested(String videoId);
    }

    private OptionsCallback callback;

    public static YouTubeVideoOptionsSheet newInstance(YouTubeVideo video) {
        YouTubeVideoOptionsSheet sheet = new YouTubeVideoOptionsSheet();
        Bundle args = new Bundle();
        args.putString(ARG_VIDEO_ID,     video.videoId);
        args.putString(ARG_TITLE,        video.title);
        args.putString(ARG_CHANNEL,      video.uploaderName);
        args.putString(ARG_THUMB,        video.thumbnailUrl);
        args.putString(ARG_UPLOADER_UID, video.uploaderUid);
        sheet.setArguments(args);
        return sheet;
    }

    public void setCallback(OptionsCallback cb) {
        this.callback = cb;
    }

    @Override public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, R.style.YtBottomSheetStyle);
        if (getArguments() != null) {
            videoId     = getArguments().getString(ARG_VIDEO_ID, "");
            title       = getArguments().getString(ARG_TITLE, "");
            channel     = getArguments().getString(ARG_CHANNEL, "");
            thumbUrl    = getArguments().getString(ARG_THUMB, "");
            uploaderUid = getArguments().getString(ARG_UPLOADER_UID, "");
        }
        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_yt_video_options, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Preview
        ImageView ivThumb = view.findViewById(R.id.iv_yt_sheet_thumb);
        TextView  tvTitle = view.findViewById(R.id.tv_yt_sheet_title);
        TextView  tvChan  = view.findViewById(R.id.tv_yt_sheet_channel);

        tvTitle.setText(title);
        tvChan.setText(channel);
        if (thumbUrl != null && !thumbUrl.isEmpty())
            Glide.with(this).load(thumbUrl).centerCrop().into(ivThumb);

        // Watch Later
        view.findViewById(R.id.btn_yt_option_watch_later).setOnClickListener(v -> {
            addToWatchLater();
            dismiss();
        });

        // Playlist
        view.findViewById(R.id.btn_yt_option_playlist).setOnClickListener(v -> {
            addToPlaylist();
            dismiss();
        });

        // Share
        view.findViewById(R.id.btn_yt_option_share).setOnClickListener(v -> {
            shareVideo();
            dismiss();
        });

        // Channel
        view.findViewById(R.id.btn_yt_option_channel).setOnClickListener(v -> {
            goToChannel();
            dismiss();
        });

        // Not Interested
        view.findViewById(R.id.btn_yt_option_not_interested).setOnClickListener(v -> {
            markNotInterested();
            dismiss();
        });

        // Report
        view.findViewById(R.id.btn_yt_option_report).setOnClickListener(v -> {
            reportVideo();
            dismiss();
        });
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void addToWatchLater() {
        if (myUid.isEmpty()) {
            toast("⚠️ Watch Later ke liye login karo");
            return;
        }
        YouTubeFirebaseUtils.watchLaterRef(myUid).child(videoId)
            .setValue(System.currentTimeMillis())
            .addOnSuccessListener(v -> toast("✅ Watch Later me save ho gaya"))
            .addOnFailureListener(e -> toast("❌ Save nahi hua: " + e.getMessage()));
    }

    private void addToPlaylist() {
        if (myUid.isEmpty()) {
            toast("⚠️ Playlist ke liye login karo");
            return;
        }
        // Save under user's "liked_videos" playlist for now
        YouTubeFirebaseUtils.likedVideosRef(myUid).child(videoId)
            .setValue(System.currentTimeMillis())
            .addOnSuccessListener(v -> toast("✅ Playlist me add ho gaya"))
            .addOnFailureListener(e -> toast("❌ Add nahi hua"));
    }

    private void shareVideo() {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, title);
        share.putExtra(Intent.EXTRA_TEXT,
            "Ye video dekho CallX YouTube pe: callx://youtube/video/" + videoId
            + "\n\n" + title + "\n— " + channel);
        if (getActivity() != null)
            startActivity(Intent.createChooser(share, "Share karo"));
    }

    private void goToChannel() {
        if (uploaderUid == null || uploaderUid.isEmpty()) {
            toast("Channel info nahi mili");
            return;
        }
        if (getActivity() != null) {
            startActivity(new Intent(getActivity(), YouTubeChannelActivity.class)
                .putExtra("uid", uploaderUid));
        }
    }

    private void markNotInterested() {
        // Save in Firebase so feed can filter later
        if (!myUid.isEmpty()) {
            YouTubeFirebaseUtils.notInterestedRef(myUid, videoId)
                .setValue(System.currentTimeMillis());
        }
        toast("Ye video feed me nahi dikhega");
        if (callback != null) callback.onNotInterested(videoId);
    }

    private void reportVideo() {
        if (myUid.isEmpty()) {
            toast("⚠️ Report ke liye login karo");
            return;
        }
        YouTubeFirebaseUtils.reportsRef(videoId, myUid)
            .setValue(System.currentTimeMillis())
            .addOnSuccessListener(v -> toast("✅ Report submit ho gaya — shukriya"))
            .addOnFailureListener(e -> toast("❌ Report nahi ho saka"));
    }

    private void toast(String msg) {
        if (getContext() != null)
            Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
