package com.callx.app.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.callx.app.calls.databinding.ActivityAddNoteBinding;
import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.PushNotify;
import com.callx.app.utils.VoiceRecorder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.database.DatabaseReference;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * AddNoteActivity — shown to caller after a missed/unanswered call.
 * Audio call  → mic recorder UI  → sends "audio" message to chat.
 * Video call  → camera intent    → sends "video" message to chat.
 *
 * Extras required:
 *   EXTRA_PARTNER_UID   (String) partner's uid
 *   EXTRA_PARTNER_NAME  (String) partner's display name
 *   EXTRA_PARTNER_PHOTO (String) partner's avatar URL
 *   EXTRA_CHAT_ID       (String) chatId (uid1_uid2)
 *   EXTRA_IS_VIDEO      (boolean) true → video note, false → audio note
 */
public class AddNoteActivity extends AppCompatActivity {

    public static final String EXTRA_PARTNER_UID   = "partnerUid";
    public static final String EXTRA_PARTNER_NAME  = "partnerName";
    public static final String EXTRA_PARTNER_PHOTO = "partnerPhoto";
    public static final String EXTRA_CHAT_ID        = "chatId";
    public static final String EXTRA_IS_VIDEO       = "isVideoNote";

    private static final int RC_VIDEO_CAPTURE = 101;

    private ActivityAddNoteBinding binding;
    private boolean isVideo;
    private String partnerUid, partnerName, chatId;

    // Audio recording
    private VoiceRecorder voiceRecorder;
    private boolean isRecording = false;
    private Uri recordedAudioUri;
    private Uri capturedVideoUri;

    // Timer
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private Runnable timerRunnable;
    private long recordingStartMs;
    private static final long MAX_AUDIO_DURATION_MS = 120_000L; // 2 min max

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAddNoteBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        partnerUid  = getIntent().getStringExtra(EXTRA_PARTNER_UID);
        partnerName = getIntent().getStringExtra(EXTRA_PARTNER_NAME);
        chatId      = getIntent().getStringExtra(EXTRA_CHAT_ID);
        isVideo     = getIntent().getBooleanExtra(EXTRA_IS_VIDEO, false);
        String partnerPhoto = getIntent().getStringExtra(EXTRA_PARTNER_PHOTO);

        // Populate UI
        binding.tvNotePartnerName.setText(partnerName != null ? partnerName : "");
        if (partnerPhoto != null && !partnerPhoto.isEmpty()) {
            Glide.with(this).load(partnerPhoto).circleCrop()
                .placeholder(com.callx.app.calls.R.drawable.ic_person)
                .into(binding.ivNotePartnerAvatar);
        }

        if (isVideo) {
            binding.ivNoteTypeIcon.setImageResource(com.callx.app.calls.R.drawable.ic_video);
            binding.fabNoteRecord.setImageResource(com.callx.app.calls.R.drawable.ic_video);
            binding.tvNoteStatus.setText("Tap to record a video note");
        } else {
            binding.ivNoteTypeIcon.setImageResource(com.callx.app.calls.R.drawable.ic_mic);
            binding.fabNoteRecord.setImageResource(com.callx.app.calls.R.drawable.ic_mic);
            binding.tvNoteStatus.setText("Tap mic to start recording");
        }

        binding.fabNoteRecord.setOnClickListener(v -> {
            if (isVideo) {
                launchVideoCapture();
            } else {
                toggleAudioRecording();
            }
        });

        binding.btnNoteSkip.setOnClickListener(v -> finish());

        binding.btnNoteSend.setEnabled(false);
        binding.btnNoteSend.setOnClickListener(v -> sendNote());
    }

    // ── Audio recording toggle ─────────────────────────────────────────────

    private void toggleAudioRecording() {
        if (!isRecording) {
            startAudioRecording();
        } else {
            stopAudioRecording();
        }
    }

    private void startAudioRecording() {
        voiceRecorder = new VoiceRecorder();
        boolean started = voiceRecorder.start(this);
        if (!started) {
            Toast.makeText(this, "Mic unavailable", Toast.LENGTH_SHORT).show();
            return;
        }
        isRecording = true;
        recordingStartMs = System.currentTimeMillis();
        binding.fabNoteRecord.setImageResource(android.R.drawable.ic_media_pause);
        binding.tvNoteStatus.setText("Recording… tap to stop");
        binding.tvNoteTimer.setVisibility(View.VISIBLE);
        binding.btnNoteSend.setEnabled(false);

        timerRunnable = new Runnable() {
            @Override public void run() {
                long elapsed = System.currentTimeMillis() - recordingStartMs;
                long sec = elapsed / 1000;
                binding.tvNoteTimer.setText(String.format("%d:%02d", sec / 60, sec % 60));
                if (elapsed >= MAX_AUDIO_DURATION_MS) {
                    stopAudioRecording();
                } else {
                    timerHandler.postDelayed(this, 500);
                }
            }
        };
        timerHandler.post(timerRunnable);
    }

    private void stopAudioRecording() {
        if (timerRunnable != null) timerHandler.removeCallbacks(timerRunnable);
        isRecording = false;
        if (voiceRecorder == null) return;
        Uri uri = voiceRecorder.stop(this);
        voiceRecorder = null;
        if (uri == null) {
            binding.tvNoteStatus.setText("Recording too short — try again");
            binding.fabNoteRecord.setImageResource(com.callx.app.calls.R.drawable.ic_mic);
            binding.tvNoteTimer.setVisibility(View.INVISIBLE);
            return;
        }
        recordedAudioUri = uri;
        binding.fabNoteRecord.setImageResource(com.callx.app.calls.R.drawable.ic_mic);
        binding.tvNoteStatus.setText("Ready to send");
        binding.btnNoteSend.setEnabled(true);
    }

    // ── Video capture ──────────────────────────────────────────────────────

    private void launchVideoCapture() {
        Intent intent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_DURATION_LIMIT, 120); // 2 min max
        intent.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 0);    // low quality for faster upload
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(intent, RC_VIDEO_CAPTURE);
        } else {
            Toast.makeText(this, "Camera app not available", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_VIDEO_CAPTURE && resultCode == RESULT_OK && data != null) {
            capturedVideoUri = data.getData();
            if (capturedVideoUri != null) {
                binding.tvNoteStatus.setText("Video ready — tap Send");
                binding.btnNoteSend.setEnabled(true);
            }
        }
    }

    // ── Upload and send ────────────────────────────────────────────────────

    private void sendNote() {
        binding.btnNoteSend.setEnabled(false);
        binding.btnNoteSkip.setEnabled(false);
        binding.fabNoteRecord.setEnabled(false);
        binding.tvNoteStatus.setText("Uploading…");

        Uri uriToUpload = isVideo ? capturedVideoUri : recordedAudioUri;
        if (uriToUpload == null) {
            Toast.makeText(this, "Nothing recorded", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        String folder    = isVideo ? "callx/video_notes" : "callx/audio_notes";
        String resType   = isVideo ? "video" : "raw";
        String msgType   = isVideo ? "video" : "audio";

        CloudinaryUploader.upload(this, uriToUpload, folder, resType,
            new CloudinaryUploader.UploadCallback() {
                @Override public void onSuccess(CloudinaryUploader.Result result) {
                    runOnUiThread(() -> {
                        pushNoteMessage(result.secureUrl, result.durationMs, msgType);
                    });
                }
                @Override public void onError(String message) {
                    runOnUiThread(() -> {
                        binding.tvNoteStatus.setText("Upload failed — try again");
                        binding.btnNoteSend.setEnabled(true);
                        binding.btnNoteSkip.setEnabled(true);
                        binding.fabNoteRecord.setEnabled(true);
                        Toast.makeText(AddNoteActivity.this, message, Toast.LENGTH_SHORT).show();
                    });
                }
            });
    }

    private void pushNoteMessage(String mediaUrl, Long durationMs, String msgType) {
        if (chatId == null || chatId.isEmpty() || partnerUid == null) { finish(); return; }

        String myUid  = FirebaseUtils.getCurrentUid();
        String myName = FirebaseUtils.getCurrentName();
        if (myUid == null) { finish(); return; }

        DatabaseReference messagesRef = FirebaseUtils.db()
            .getReference("messages").child(chatId);
        String key = messagesRef.push().getKey();
        if (key == null) { finish(); return; }

        Map<String, Object> msg = new HashMap<>();
        msg.put("id",         key);
        msg.put("messageId",  key);
        msg.put("senderId",   myUid);
        msg.put("senderName", myName != null ? myName : "");
        msg.put("type",       msgType);
        msg.put("mediaUrl",   mediaUrl);
        msg.put("duration",   durationMs != null ? durationMs : 0L);
        msg.put("timestamp",  System.currentTimeMillis());
        msg.put("status",     "sent");

        // ── FCM push — background/killed state notification ──────────────────
        // Firebase DB me sirf data save karne se foreground me ChatActivity
        // real-time listener notification dikhata hai, lekin background/killed
        // me partner ko koi notification nahi aati.
        // PushNotify.notifyMessage() server ko FCM data payload bhejta hai jo
        // CallxMessagingService.showMessage() trigger karta hai — teeno states me.
        final String previewText = isVideo ? "📹 Video note" : "🎤 Voice note";
        final String pushKey = key;
        PushNotify.notifyMessage(
            partnerUid,          // toUid — partner ko notify karo
            myUid,               // fromUid — sender
            myName != null ? myName : "",  // fromName
            chatId,              // chatId
            pushKey,             // messageId
            previewText,         // text preview (shown in notification)
            msgType,             // type: "audio" ya "video"
            mediaUrl             // mediaUrl (Cloudinary URL)
        );
        // ─────────────────────────────────────────────────────────────────────

        messagesRef.child(key).setValue(msg)
            .addOnCompleteListener(task -> finish());

        // Update lastMessage preview for both contacts
        long ts = System.currentTimeMillis();
        String preview = isVideo ? "📹 Video note" : "🎤 Voice note";

        Map<String, Object> upd = new HashMap<>();
        upd.put("lastMessage", preview);
        upd.put("lastTs", ts);
        FirebaseUtils.getContactsRef(myUid).child(partnerUid).updateChildren(upd);
        FirebaseUtils.getContactsRef(partnerUid).child(myUid).updateChildren(upd);
        // increment unread for partner
        FirebaseUtils.getContactsRef(partnerUid).child(myUid).child("unread")
            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                @Override public void onDataChange(com.google.firebase.database.DataSnapshot s) {
                    Long cur = s.getValue(Long.class);
                    s.getRef().setValue((cur != null ? cur : 0) + 1);
                }
                @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {}
            });
    }

    @Override
    protected void onDestroy() {
        if (timerRunnable != null) timerHandler.removeCallbacks(timerRunnable);
        if (voiceRecorder != null) { voiceRecorder.cancel(); voiceRecorder = null; }
        super.onDestroy();
    }
}
