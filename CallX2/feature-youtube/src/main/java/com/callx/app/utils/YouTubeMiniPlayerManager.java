package com.callx.app.utils;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import com.bumptech.glide.Glide;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.youtube.R;
import de.hdodenhof.circleimageview.CircleImageView;

/**
 * YouTubeMiniPlayerManager
 *
 * YouTube jaisa mini player — jab video player se back karo
 * tab bottom mein ek chhota floating player dikhta hai.
 *
 * Usage (MainActivity ya YouTubeActivity mein):
 *   YouTubeMiniPlayerManager.getInstance().show(rootView, video);
 *   YouTubeMiniPlayerManager.getInstance().dismiss();
 *   YouTubeMiniPlayerManager.getInstance().release();
 *
 * YouTubePlayerActivity mein — onBackPressed override karke mini player show karo.
 */
public class YouTubeMiniPlayerManager {

    private static YouTubeMiniPlayerManager instance;
    public static YouTubeMiniPlayerManager getInstance() {
        if (instance == null) instance = new YouTubeMiniPlayerManager();
        return instance;
    }

    private View miniPlayerView;
    private ExoPlayer miniPlayer;
    private ViewGroup rootContainer;
    private YouTubeVideo currentVideo;
    private OnMiniPlayerClickListener clickListener;

    public interface OnMiniPlayerClickListener {
        void onExpand(YouTubeVideo video);
    }

    public void setOnClickListener(OnMiniPlayerClickListener l) { this.clickListener = l; }

    public boolean isShowing() { return miniPlayerView != null && miniPlayerView.getVisibility() == View.VISIBLE; }

    public YouTubeVideo getCurrentVideo() { return currentVideo; }

    /**
     * Mini player dikhao
     * @param root  Activity ka root ViewGroup (e.g. FrameLayout)
     * @param video Video jo play hona chahiye
     */
    public void show(ViewGroup root, YouTubeVideo video) {
        Context ctx = root.getContext();
        this.rootContainer = root;
        this.currentVideo  = video;

        // Purana dismiss karo
        if (miniPlayerView != null) {
            rootContainer.removeView(miniPlayerView);
            miniPlayerView = null;
        }
        if (miniPlayer != null) { miniPlayer.release(); miniPlayer = null; }

        miniPlayerView = LayoutInflater.from(ctx)
            .inflate(R.layout.layout_yt_mini_player, root, false);

        PlayerView pv         = miniPlayerView.findViewById(R.id.pv_mini_player);
        TextView tvTitle      = miniPlayerView.findViewById(R.id.tv_mini_title);
        TextView tvChannel    = miniPlayerView.findViewById(R.id.tv_mini_channel);
        ImageButton btnPlay   = miniPlayerView.findViewById(R.id.btn_mini_play_pause);
        ImageButton btnClose  = miniPlayerView.findViewById(R.id.btn_mini_close);

        tvTitle.setText(video.title != null ? video.title : "");
        tvChannel.setText(video.uploaderName != null ? video.uploaderName : "");

        // ExoPlayer setup
        miniPlayer = new ExoPlayer.Builder(ctx).build();
        pv.setPlayer(miniPlayer);
        pv.setUseController(false);

        String url = video.videoUrl != null ? video.videoUrl : "";
        if (url.contains("cloudinary.com") && !url.contains("f_mp4"))
            url = url.replaceFirst("/upload/", "/upload/f_mp4/");

        miniPlayer.setMediaItem(MediaItem.fromUri(url));
        miniPlayer.prepare();
        miniPlayer.play();

        // Play/Pause toggle
        miniPlayer.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean isPlaying) {
                btnPlay.setImageResource(isPlaying
                    ? R.drawable.ic_yt_more : R.drawable.ic_yt_play);
            }
        });

        btnPlay.setOnClickListener(v -> {
            if (miniPlayer.isPlaying()) miniPlayer.pause();
            else miniPlayer.play();
        });

        btnClose.setOnClickListener(v -> dismiss());

        // Tap on player → expand to full screen
        miniPlayerView.setOnClickListener(v -> {
            if (clickListener != null) clickListener.onExpand(currentVideo);
        });

        // Bottom mein fix karo
        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0);
        root.addView(miniPlayerView, lp);

        // Animate up
        miniPlayerView.setTranslationY(250f);
        miniPlayerView.setVisibility(View.VISIBLE);
        miniPlayerView.animate().translationY(0f).setDuration(300).start();
    }

    public void dismiss() {
        if (miniPlayerView != null) {
            miniPlayerView.animate().translationY(300f).setDuration(250)
                .withEndAction(() -> {
                    if (rootContainer != null) rootContainer.removeView(miniPlayerView);
                    miniPlayerView = null;
                }).start();
        }
        if (miniPlayer != null) { miniPlayer.pause(); miniPlayer.release(); miniPlayer = null; }
        currentVideo = null;
    }

    public void release() {
        if (miniPlayer != null) { miniPlayer.release(); miniPlayer = null; }
        if (miniPlayerView != null && rootContainer != null)
            rootContainer.removeView(miniPlayerView);
        miniPlayerView = null;
        currentVideo = null;
    }

    public void pause()  { if (miniPlayer != null) miniPlayer.pause(); }
    public void resume() { if (miniPlayer != null) miniPlayer.play();  }
}
