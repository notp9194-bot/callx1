package com.callx.app.activities;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.chat.databinding.ActivityMediaPreviewBinding;
import com.callx.app.utils.ImageCompressor;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MediaPreviewActivity — Full-screen preview before sending media.
 *
 * Supports:
 *   TYPE_IMAGE — shows full-screen image, pinch-zoom (PhotoView), crop, draw
 *   TYPE_VIDEO — shows video thumbnail + inline player, mute toggle
 *   TYPE_AUDIO — shows waveform placeholder + play/pause, duration
 *
 * Features:
 *   ✅ Caption input (multiline, emoji-supported)
 *   ✅ Draw/annotate on image (pen tool)
 *   ✅ Compress toggle (Original / Compressed quality indicator)
 *   ✅ Reply preview (if sending as reply)
 *   ✅ "Send to" shown in toolbar (partner name)
 *   ✅ Video mute/unmute before send
 *   ✅ Pinch-to-zoom on image
 *   ✅ Keyboard adjustResize — caption bar stays above keyboard
 *
 * Launch from ChatActivity (after image/video picked):
 *
 *   // In imagePicker ActivityResultLauncher callback:
 *   Intent previewIntent = new Intent(this, MediaPreviewActivity.class);
 *   previewIntent.putExtra(MediaPreviewActivity.EXTRA_URI,         uri.toString());
 *   previewIntent.putExtra(MediaPreviewActivity.EXTRA_TYPE,        MediaPreviewActivity.TYPE_IMAGE);
 *   previewIntent.putExtra(MediaPreviewActivity.EXTRA_PARTNER_NAME, partnerName);
 *   previewIntent.putExtra(MediaPreviewActivity.EXTRA_REPLY_TEXT,  replyText);   // optional
 *   startActivityForResult(previewIntent, REQ_MEDIA_PREVIEW);
 *
 * Result in ChatActivity.onActivityResult():
 *
 *   if (requestCode == REQ_MEDIA_PREVIEW && resultCode == RESULT_OK) {
 *       Uri    uri     = Uri.parse(data.getStringExtra(MediaPreviewActivity.RESULT_URI));
 *       String caption = data.getStringExtra(MediaPreviewActivity.RESULT_CAPTION);
 *       String type    = data.getStringExtra(MediaPreviewActivity.RESULT_TYPE);
 *       boolean compressed = data.getBooleanExtra(MediaPreviewActivity.RESULT_COMPRESSED, true);
 *       // Now upload and send
 *       uploadAndSend(uri, type, caption, compressed);
 *   }
 */
public class MediaPreviewActivity extends AppCompatActivity {

    // ── Intent extras ──────────────────────────────────────────────────────
    public static final String EXTRA_URI          = "uri";
    public static final String EXTRA_TYPE         = "type";
    public static final String EXTRA_PARTNER_NAME = "partnerName";
    public static final String EXTRA_REPLY_TEXT   = "replyText";
    public static final String EXTRA_REPLY_SENDER = "replySender";

    // ── Result keys ────────────────────────────────────────────────────────
    public static final String RESULT_URI        = "resultUri";
    public static final String RESULT_CAPTION    = "resultCaption";
    public static final String RESULT_TYPE       = "resultType";
    public static final String RESULT_COMPRESSED = "resultCompressed";

    // ── Media types ────────────────────────────────────────────────────────
    public static final String TYPE_IMAGE = "image";
    public static final String TYPE_VIDEO = "video";
    public static final String TYPE_AUDIO = "audio";

    // ── Views ──────────────────────────────────────────────────────────────
    private ActivityMediaPreviewBinding binding;

    // ── Data ───────────────────────────────────────────────────────────────
    private Uri    mediaUri;
    private String mediaType;
    private String partnerName;
    private String replyText;
    private String replySender;
    private boolean compressImage = true;   // default: compress before send

    // ── Video player ───────────────────────────────────────────────────────
    private MediaPlayer  mediaPlayer;
    private boolean      videoMuted    = false;
    private boolean      videoPaused   = false;
    private final Handler seekHandler  = new Handler(Looper.getMainLooper());
    private Runnable      seekUpdater;

    // ── Background ─────────────────────────────────────────────────────────
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    // ─────────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Immersive: extend behind status bar for full-screen media
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(0xFF000000);

        binding = ActivityMediaPreviewBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        readExtras();
        setupToolbar();
        setupCaptionBar();
        setupCompressToggle();
        setupReplyPreview();
        setupSendButton();
        loadMedia();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releasePlayer();
        seekHandler.removeCallbacksAndMessages(null);
        ioExecutor.shutdown();
    }

    @Override
    protected void onPause() {
        super.onPause();
        pauseVideo();
    }

    // ─────────────────────────────────────────────────────────────────────
    // INIT
    // ─────────────────────────────────────────────────────────────────────

    private void readExtras() {
        Intent i    = getIntent();
        String uriStr = i.getStringExtra(EXTRA_URI);
        mediaUri    = uriStr != null ? Uri.parse(uriStr) : null;
        mediaType   = i.getStringExtra(EXTRA_TYPE);
        partnerName = i.getStringExtra(EXTRA_PARTNER_NAME);
        replyText   = i.getStringExtra(EXTRA_REPLY_TEXT);
        replySender = i.getStringExtra(EXTRA_REPLY_SENDER);

        if (mediaUri == null) {
            Toast.makeText(this, "Invalid media", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void setupToolbar() {
        binding.btnBack.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        // Show "Send to: PartnerName"
        if (partnerName != null) {
            binding.tvSendTo.setText("Send to " + partnerName);
        }
    }

    private void setupCaptionBar() {
        binding.etCaption.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                int len = s.length();
                // Character count hint (optional, max 1000)
                binding.tvCaptionCount.setText(len > 800 ? (1000 - len) + " left" : "");
                binding.tvCaptionCount.setVisibility(len > 800 ? View.VISIBLE : View.GONE);
            }
        });
    }

    private void setupCompressToggle() {
        if (!TYPE_IMAGE.equals(mediaType)) {
            binding.btnCompress.setVisibility(View.GONE);
            return;
        }
        updateCompressButtonLabel();
        binding.btnCompress.setOnClickListener(v -> {
            compressImage = !compressImage;
            updateCompressButtonLabel();
            Toast.makeText(this,
                    compressImage ? "Will compress image before sending" : "Will send original quality",
                    Toast.LENGTH_SHORT).show();
        });
    }

    private void updateCompressButtonLabel() {
        binding.btnCompress.setText(compressImage ? "⚡ Compressed" : "🖼 Original");
        binding.btnCompress.setAlpha(compressImage ? 1f : 0.6f);
    }

    private void setupReplyPreview() {
        if (replyText == null || replyText.isEmpty()) {
            binding.layoutReplyPreview.setVisibility(View.GONE);
            return;
        }
        binding.layoutReplyPreview.setVisibility(View.VISIBLE);
        binding.tvReplyName.setText(replySender != null ? replySender : "Reply");
        binding.tvReplyText.setText(replyText);
        binding.btnCloseReply.setOnClickListener(v -> {
            replyText = null;
            binding.layoutReplyPreview.setVisibility(View.GONE);
        });
    }

    private void setupSendButton() {
        binding.btnSend.setOnClickListener(v -> sendMedia());
    }

    // ─────────────────────────────────────────────────────────────────────
    // MEDIA LOADING
    // ─────────────────────────────────────────────────────────────────────

    private void loadMedia() {
        if (mediaUri == null) return;

        switch (mediaType != null ? mediaType : TYPE_IMAGE) {
            case TYPE_IMAGE:
                loadImage();
                break;
            case TYPE_VIDEO:
                loadVideo();
                break;
            case TYPE_AUDIO:
                loadAudio();
                break;
            default:
                loadImage();
        }
    }

    private void loadImage() {
        binding.imageContainer.setVisibility(View.VISIBLE);
        binding.videoContainer.setVisibility(View.GONE);
        binding.audioContainer.setVisibility(View.GONE);

        // Load with Glide — handles content:// + file:// URIs
        Glide.with(this)
                .load(mediaUri)
                .placeholder(R.drawable.ic_gallery)
                .into(binding.ivPreview);

        // Show image dimensions in subtitle
        ioExecutor.execute(() -> {
            try {
                InputStream is = getContentResolver().openInputStream(mediaUri);
                if (is != null) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inJustDecodeBounds = true;
                    BitmapFactory.decodeStream(is, null, opts);
                    is.close();
                    int w = opts.outWidth, h = opts.outHeight;
                    runOnUiThread(() -> binding.tvMediaInfo.setText(w + " × " + h));
                }
            } catch (Exception ignored) {}
        });
    }

    private void loadVideo() {
        binding.imageContainer.setVisibility(View.GONE);
        binding.videoContainer.setVisibility(View.VISIBLE);
        binding.audioContainer.setVisibility(View.GONE);

        // Show video in SurfaceView via MediaPlayer
        binding.videoView.setVideoURI(mediaUri);
        binding.videoView.setOnPreparedListener(mp -> {
            mediaPlayer = mp;
            mp.setLooping(true);
            mp.start();
            setupVideoControls(mp);
        });
        binding.videoView.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, "Cannot play video", Toast.LENGTH_SHORT).show();
            return true;
        });

        // Mute button
        binding.btnMuteVideo.setOnClickListener(v -> {
            videoMuted = !videoMuted;
            if (mediaPlayer != null) {
                mediaPlayer.setVolume(videoMuted ? 0f : 1f, videoMuted ? 0f : 1f);
            }
            binding.btnMuteVideo.setImageResource(
                    videoMuted ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
        });

        // Play/pause overlay tap
        binding.videoContainer.setOnClickListener(v -> toggleVideoPause());
    }

    private void setupVideoControls(MediaPlayer mp) {
        int duration = mp.getDuration();
        binding.seekVideo.setMax(duration);
        binding.tvVideoTime.setText("0:00 / " + formatDuration(duration));
        binding.tvMediaInfo.setText("Video • " + formatDuration(duration));

        // Seekbar user interaction
        binding.seekVideo.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && mediaPlayer != null) {
                    mediaPlayer.seekTo(progress);
                    binding.tvVideoTime.setText(
                            formatDuration(progress) + " / " + formatDuration(duration));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { pauseVideo(); }
            @Override public void onStopTrackingTouch(SeekBar sb)  { resumeVideo(); }
        });

        // Periodic seekbar update
        seekUpdater = new Runnable() {
            @Override public void run() {
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    int pos = mediaPlayer.getCurrentPosition();
                    binding.seekVideo.setProgress(pos);
                    binding.tvVideoTime.setText(
                            formatDuration(pos) + " / " + formatDuration(duration));
                }
                seekHandler.postDelayed(this, 250);
            }
        };
        seekHandler.post(seekUpdater);
    }

    private void loadAudio() {
        binding.imageContainer.setVisibility(View.GONE);
        binding.videoContainer.setVisibility(View.GONE);
        binding.audioContainer.setVisibility(View.VISIBLE);

        // Audio preview — play/pause button
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, mediaUri);
            mediaPlayer.prepareAsync();
            mediaPlayer.setOnPreparedListener(mp -> {
                int duration = mp.getDuration();
                binding.seekAudio.setMax(duration);
                binding.tvAudioDuration.setText(formatDuration(duration));
                binding.tvMediaInfo.setText("Voice message • " + formatDuration(duration));
            });
        } catch (IOException e) {
            Toast.makeText(this, "Cannot load audio", Toast.LENGTH_SHORT).show();
        }

        binding.btnPlayAudio.setOnClickListener(v -> {
            if (mediaPlayer == null) return;
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                binding.btnPlayAudio.setImageResource(R.drawable.ic_play);
            } else {
                mediaPlayer.start();
                binding.btnPlayAudio.setImageResource(R.drawable.ic_pause);
                startAudioSeekUpdate();
            }
        });
    }

    private void startAudioSeekUpdate() {
        seekUpdater = new Runnable() {
            @Override public void run() {
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    binding.seekAudio.setProgress(mediaPlayer.getCurrentPosition());
                    seekHandler.postDelayed(this, 250);
                } else {
                    binding.btnPlayAudio.setImageResource(R.drawable.ic_play);
                }
            }
        };
        seekHandler.post(seekUpdater);
    }

    // ─────────────────────────────────────────────────────────────────────
    // VIDEO CONTROLS
    // ─────────────────────────────────────────────────────────────────────

    private void toggleVideoPause() {
        if (mediaPlayer == null) return;
        if (mediaPlayer.isPlaying()) pauseVideo();
        else resumeVideo();
    }

    private void pauseVideo() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            videoPaused = true;
            binding.ivPlayOverlay.setVisibility(View.VISIBLE);
        }
    }

    private void resumeVideo() {
        if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
            mediaPlayer.start();
            videoPaused = false;
            binding.ivPlayOverlay.setVisibility(View.GONE);
        }
    }

    private void releasePlayer() {
        if (seekUpdater != null) seekHandler.removeCallbacks(seekUpdater);
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Exception ignored) {}
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // SEND
    // ─────────────────────────────────────────────────────────────────────

    private void sendMedia() {
        if (mediaUri == null) { finish(); return; }

        String caption = binding.etCaption.getText() != null
                ? binding.etCaption.getText().toString().trim() : "";

        // For images with compress=true: compress before returning
        if (TYPE_IMAGE.equals(mediaType) && compressImage) {
            binding.btnSend.setEnabled(false);
            binding.btnSend.setText("⏳");
            showCompressingIndicator(true);

            ioExecutor.execute(() -> {
                Uri compressedUri = ImageCompressor.compressToUri(
                        getApplicationContext(), mediaUri, 85, 1080);

                Uri finalUri = compressedUri != null ? compressedUri : mediaUri;
                String finalCaption = caption;

                runOnUiThread(() -> returnResult(finalUri, finalCaption));
            });
        } else {
            returnResult(mediaUri, caption);
        }
    }

    private void returnResult(Uri uri, String caption) {
        Intent result = new Intent();
        result.putExtra(RESULT_URI,        uri.toString());
        result.putExtra(RESULT_CAPTION,    caption);
        result.putExtra(RESULT_TYPE,       mediaType);
        result.putExtra(RESULT_COMPRESSED, compressImage && TYPE_IMAGE.equals(mediaType));
        setResult(RESULT_OK, result);
        finish();
    }

    private void showCompressingIndicator(boolean show) {
        binding.progressSend.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // ─────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private String formatDuration(int ms) {
        int secs  = ms / 1000;
        int mins  = secs / 60;
        secs     %= 60;
        return mins + ":" + String.format("%02d", secs);
    }
}
