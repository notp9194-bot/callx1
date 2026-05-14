package com.callx.app.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.*;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.reels.R;
import com.callx.app.models.ReelComment;
import com.callx.app.utils.FirebaseUtils;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.database.*;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ReelVideoReplyActivity — Record a video reply to a comment.
 *
 * Flow:
 *  1. Shows the original comment (avatar + text) at the top as a card
 *  2. Bottom half: live CameraX preview
 *  3. Tap record → records response video (max 60s)
 *  4. On finish → sends video reply to ReelEditorActivity, then uploaded
 *     as a new reel with "videoReplyTo" metadata referencing the comment
 *
 * Features:
 *  ✅ Original comment display card (avatar, username, text)
 *  ✅ CameraX recording (HD)
 *  ✅ Camera flip (front / back)
 *  ✅ Countdown timer + progress ring
 *  ✅ Max 60s recording
 *  ✅ On complete → opens ReelEditorActivity with reply metadata
 */
public class ReelVideoReplyActivity extends AppCompatActivity {

    public static final String EXTRA_REEL_ID    = "vr_reel_id";
    public static final String EXTRA_COMMENT_ID = "vr_comment_id";
    public static final String EXTRA_COMMENT_TEXT= "vr_comment_text";
    public static final String EXTRA_COMMENTER_NAME="vr_commenter_name";
    public static final String EXTRA_COMMENTER_PHOTO="vr_commenter_photo";

    private static final int REQ_PERMS  = 601;
    private static final int MAX_SEC    = 60;

    private de.hdodenhof.circleimageview.CircleImageView ivCommenterAvatar;
    private TextView      tvCommenterName, tvCommentText;
    private PreviewView   previewView;
    private ImageButton   btnClose, btnRecord, btnFlip;
    private ProgressBar   progressRecord;
    private TextView      tvTimer;

    private ProcessCameraProvider  cameraProvider;
    private VideoCapture<Recorder> videoCapture;
    private Recording              activeRecording;
    private ExecutorService        executor;

    private int     lensFacing  = CameraSelector.LENS_FACING_FRONT;
    private boolean isRecording = false;
    private CountDownTimer recordTimer;

    private String reelId, commentId, commentText, commenterName, commenterPhoto;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_video_reply);

        reelId         = getIntent().getStringExtra(EXTRA_REEL_ID);
        commentId      = getIntent().getStringExtra(EXTRA_COMMENT_ID);
        commentText    = getIntent().getStringExtra(EXTRA_COMMENT_TEXT);
        commenterName  = getIntent().getStringExtra(EXTRA_COMMENTER_NAME);
        commenterPhoto = getIntent().getStringExtra(EXTRA_COMMENTER_PHOTO);

        executor = Executors.newSingleThreadExecutor();
        bindViews();
        populateCommentCard();

        if (hasPermissions()) startCamera();
        else requestPermissions();
    }

    private void bindViews() {
        ivCommenterAvatar = findViewById(R.id.iv_vr_commenter_avatar);
        tvCommenterName   = findViewById(R.id.tv_vr_commenter_name);
        tvCommentText     = findViewById(R.id.tv_vr_comment_text);
        previewView       = findViewById(R.id.preview_vr_camera);
        btnClose          = findViewById(R.id.btn_vr_close);
        btnRecord         = findViewById(R.id.btn_vr_record);
        btnFlip           = findViewById(R.id.btn_vr_flip);
        progressRecord    = findViewById(R.id.progress_vr);
        tvTimer           = findViewById(R.id.tv_vr_timer);

        progressRecord.setMax(MAX_SEC);

        btnClose.setOnClickListener(v -> finish());
        btnRecord.setOnClickListener(v -> { if (isRecording) stopRecording(); else startRecording(); });
        btnFlip.setOnClickListener(v -> {
            lensFacing = lensFacing == CameraSelector.LENS_FACING_FRONT
                ? CameraSelector.LENS_FACING_BACK : CameraSelector.LENS_FACING_FRONT;
            startCamera();
        });
    }

    private void populateCommentCard() {
        tvCommenterName.setText(commenterName != null ? commenterName : "Someone");
        tvCommentText.setText(commentText != null ? commentText : "");
        if (commenterPhoto != null && !commenterPhoto.isEmpty()) {
            Glide.with(this).load(commenterPhoto)
                .apply(RequestOptions.circleCropTransform())
                .placeholder(R.drawable.ic_person)
                .into(ivCommenterAvatar);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
            ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                Toast.makeText(this, "Camera error", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;
        cameraProvider.unbindAll();
        CameraSelector selector = new CameraSelector.Builder()
            .requireLensFacing(lensFacing).build();
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        Recorder recorder = new Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD)).build();
        videoCapture = VideoCapture.withOutput(recorder);
        try {
            cameraProvider.bindToLifecycle(this, selector, preview, videoCapture);
        } catch (Exception e) {
            Toast.makeText(this, "Camera bind failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void startRecording() {
        if (videoCapture == null) return;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return;

        File out = new File(getCacheDir(), "vreply_" + System.currentTimeMillis() + ".mp4");
        FileOutputOptions opts = new FileOutputOptions.Builder(out).build();

        activeRecording = videoCapture.getOutput()
            .prepareRecording(this, opts)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this), event -> {
                if (event instanceof VideoRecordEvent.Start) {
                    isRecording = true;
                    runOnUiThread(() -> {
                        btnRecord.setImageResource(R.drawable.ic_pause);
                        startCountdown();
                    });
                } else if (event instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;
                    isRecording = false;
                    if (!fin.hasError()) {
                        runOnUiThread(() -> openEditor(out.getAbsolutePath()));
                    } else {
                        runOnUiThread(() ->
                            Toast.makeText(this, "Recording failed", Toast.LENGTH_SHORT).show());
                    }
                }
            });
    }

    private void stopRecording() {
        if (activeRecording != null) { activeRecording.stop(); activeRecording = null; }
        if (recordTimer != null)     { recordTimer.cancel(); recordTimer = null; }
        progressRecord.setProgress(0);
        tvTimer.setText("0:00");
        btnRecord.setImageResource(R.drawable.ic_camera);
        isRecording = false;
    }

    private void startCountdown() {
        final int[] elapsed = {0};
        recordTimer = new CountDownTimer(MAX_SEC * 1000L, 1000) {
            @Override public void onTick(long ms) {
                elapsed[0]++;
                progressRecord.setProgress(elapsed[0]);
                int rem = MAX_SEC - elapsed[0];
                tvTimer.setText(String.format("%d:%02d", rem / 60, rem % 60));
            }
            @Override public void onFinish() { stopRecording(); }
        }.start();
    }

    private void openEditor(String filePath) {
        Intent i = new Intent(this, ReelEditorActivity.class);
        i.putExtra(ReelEditorActivity.EXTRA_VIDEO_URI,    filePath);
        i.putExtra(ReelEditorActivity.EXTRA_IS_FILE_PATH, true);
        i.putExtra("is_video_reply",   true);
        i.putExtra("reply_reel_id",    reelId);
        i.putExtra("reply_comment_id", commentId);
        i.putExtra("reply_comment_text", commentText);
        i.putExtra("reply_commenter",  commenterName);
        startActivity(i);
        finish();
    }

    private boolean hasPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this,
            new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
            REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] p, @NonNull int[] g) {
        super.onRequestPermissionsResult(code, p, g);
        if (code == REQ_PERMS && hasPermissions()) startCamera();
        else Toast.makeText(this, "Camera & mic permission required", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        if (activeRecording != null) activeRecording.stop();
        if (recordTimer != null) recordTimer.cancel();
        executor.shutdown();
        super.onDestroy();
    }
}
