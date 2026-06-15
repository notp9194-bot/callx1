package com.callx.app.incoming;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.callx.app.calls.databinding.ActivityIncomingCallBinding;
import com.callx.app.call.CallActivity;
import com.callx.app.notes.AddNoteActivity;
import com.callx.app.services.IncomingRingService;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.PushNotify;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.CallLogEntity;
import com.callx.app.cache.StatusCacheManager;
import com.callx.app.models.StatusItem;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public class IncomingCallActivity extends AppCompatActivity {

    private ActivityIncomingCallBinding binding;
    private MediaPlayer ringtonePlayer;
    private PowerManager.WakeLock wakeLock;
    private String callId, fromUid, fromName, fromPhoto, fromThumb;
    private boolean isVideo, acted = false;
    private ValueEventListener statusListener;
    private final Handler autoRejectHandler = new Handler(Looper.getMainLooper());
    private Vibrator vibrator;
    private static final int AUTO_REJECT_MS = 60_000;
    private static final float SWIPE_THRESHOLD = 120f;

    private final Handler dotsHandler = new Handler(Looper.getMainLooper());
    private int dotCount = 0;
    private AnimatorSet rippleSet;
    private float swipeTouchStartY = 0f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        WindowInsetsControllerCompat ic =
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        ic.setAppearanceLightStatusBars(false);
        ic.setAppearanceLightNavigationBars(false);

        super.onCreate(savedInstanceState);
        binding = ActivityIncomingCallBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (km != null) km.requestDismissKeyguard(this, null);
        } else {
            getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        callId   = getIntent().getStringExtra(Constants.EXTRA_CALL_ID);
        fromUid  = getIntent().getStringExtra(Constants.EXTRA_PARTNER_UID);
        fromName = getIntent().getStringExtra(Constants.EXTRA_PARTNER_NAME);
        isVideo  = getIntent().getBooleanExtra(Constants.EXTRA_IS_VIDEO, false);
        if (callId   == null) callId   = getIntent().getStringExtra("callId");
        if (fromUid  == null) fromUid  = getIntent().getStringExtra("fromUid");
        if (fromName == null) fromName = getIntent().getStringExtra("fromName");
        if (!isVideo) isVideo = getIntent().getBooleanExtra("video", false);
        fromPhoto = getIntent().getStringExtra(Constants.EXTRA_PARTNER_PHOTO);
        if (fromPhoto == null) fromPhoto = getIntent().getStringExtra("partnerPhoto");
        fromThumb = getIntent().getStringExtra("partnerThumb");
        if (fromThumb == null) fromThumb = "";

        binding.tvCallerName.setText(fromName == null ? "Unknown" : fromName);
        binding.tvCallerSub.setText(isVideo ? "Incoming video call" : "Incoming voice call");
        if (binding.ivCallTypeIcon != null) {
            binding.ivCallTypeIcon.setImageResource(isVideo
                ? com.callx.app.calls.R.drawable.ic_video
                : com.callx.app.calls.R.drawable.ic_phone);
        }

        String avatarUrl = (fromPhoto != null && !fromPhoto.isEmpty()) ? fromPhoto : fromThumb;
        String bgUrl     = (fromPhoto != null && !fromPhoto.isEmpty()) ? fromPhoto : fromThumb;

        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            Glide.with(this)
                .load(avatarUrl)
                .placeholder(com.callx.app.calls.R.drawable.ic_person)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .circleCrop()
                .into(binding.ivCallerAvatar);
        }

        if (bgUrl != null && !bgUrl.isEmpty()) {
            binding.ivBgBlur.setAlpha(0f);
            Glide.with(this)
                .asBitmap()
                .load(bgUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .override(600, 1000)
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull Bitmap resource,
                                                Transition<? super Bitmap> transition) {
                        Bitmap blurred = blurBitmap(resource, 12f);
                        if (blurred != null && binding.ivBgBlur != null) {
                            binding.ivBgBlur.setImageBitmap(blurred);
                            binding.ivBgBlur.animate().alpha(0.80f).setDuration(600).start();
                        }
                    }
                    @Override public void onLoadCleared(android.graphics.drawable.Drawable p) {}
                });
        }

        acquireWakeLock();
        checkBlockAndProceed();
    }

    // ── Status strip: caller ka latest status dikhao ──────────────────────
    /**
     * Agar caller ka koi active status hai toh avatar ke niche ek small strip dikhao:
     * "📷 Posted a status 2h ago"
     * No interaction — sirf info.
     */
    private void checkCallerStatusStrip() {
        if (fromUid == null || fromUid.isEmpty()) return;
        if (binding.tvCallerStatusStrip == null) return;

        // 1. StatusCacheManager se pehle try karo (in-memory, no extra Firebase read)
        StatusCacheManager cache = StatusCacheManager.getInstance(getApplicationContext());
        List<StatusItem> cached = cache.getStatuses(fromUid);
        if (cached != null && !cached.isEmpty()) {
            showStatusStrip(cached);
            return;
        }

        // 2. Cache miss → direct Firebase se latest status fetch karo
        FirebaseUtils.db().getReference("statuses").child(fromUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    java.util.ArrayList<StatusItem> items = new java.util.ArrayList<>();
                    long now = System.currentTimeMillis();
                    long expiry24h = 24L * 60 * 60 * 1000;
                    for (DataSnapshot child : snap.getChildren()) {
                        try {
                            StatusItem item = child.getValue(StatusItem.class);
                            if (item == null) continue;
                            Long ts = item.timestamp;
                            if (ts == null) continue;
                            Boolean deleted = child.child("deleted").getValue(Boolean.class);
                            if (Boolean.TRUE.equals(deleted)) continue;
                            if ((now - ts) > expiry24h) continue;
                            items.add(item);
                        } catch (Exception ignored) {}
                    }
                    if (!items.isEmpty()) {
                        runOnUiThread(() -> showStatusStrip(items));
                    }
                }
                @Override
                public void onCancelled(@NonNull com.google.firebase.database.DatabaseError e) {}
            });
    }

    private void showStatusStrip(List<StatusItem> items) {
        if (binding.tvCallerStatusStrip == null || items == null || items.isEmpty()) return;

        // Latest status (highest timestamp)
        long latestTs = 0;
        for (StatusItem item : items) {
            if (item.timestamp != null && item.timestamp > latestTs) {
                latestTs = item.timestamp;
            }
        }
        if (latestTs == 0) return;

        String ago = formatStatusAgo(latestTs);
        binding.tvCallerStatusStrip.setText("📷 Posted a status " + ago);
        binding.tvCallerStatusStrip.setVisibility(android.view.View.VISIBLE);
        // Gentle fade-in
        binding.tvCallerStatusStrip.setAlpha(0f);
        binding.tvCallerStatusStrip.animate().alpha(1f).setDuration(400).start();
    }

    private String formatStatusAgo(long timestampMs) {
        long diff = System.currentTimeMillis() - timestampMs;
        long minutes = diff / 60_000;
        if (minutes < 1)  return "just now";
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24)   return hours + "h ago";
        return "today";
    }

    private Bitmap blurBitmap(Bitmap src, float radius) {
        try {
            Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), src.getConfig());
            RenderScript rs = RenderScript.create(this);
            Allocation inAlloc  = Allocation.createFromBitmap(rs, src);
            Allocation outAlloc = Allocation.createTyped(rs, inAlloc.getType());
            ScriptIntrinsicBlur blur = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
            blur.setRadius(radius);
            blur.setInput(inAlloc);
            blur.forEach(outAlloc);
            outAlloc.copyTo(out);
            rs.destroy();
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private void checkBlockAndProceed() {
        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid == null || myUid.isEmpty() || fromUid == null || fromUid.isEmpty()) {
            proceedWithCall(); return;
        }
        FirebaseUtils.getBlocksRef(myUid).child(fromUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (Boolean.TRUE.equals(snap.getValue(Boolean.class))) {
                        if (!acted) { acted = true; reject(); }
                    } else { proceedWithCall(); }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { proceedWithCall(); }
            });
    }

    private void proceedWithCall() {
        com.callx.app.utils.SecurityManager secMgr = new com.callx.app.utils.SecurityManager(this);
        if (secMgr.isSilenceUnknownCallers()) {
            String myUid = FirebaseUtils.getCurrentUid();
            if (myUid != null && fromUid != null) {
                FirebaseUtils.getContactsRef(myUid).child(fromUid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot snap) {
                            if (!snap.exists()) { if (!acted) { acted = true; reject(); } }
                            else { startCallUI(); }
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) { startCallUI(); }
                    });
                return;
            }
        }
        startCallUI();
    }

    private void startCallUI() {
        startVibration();
        startLoopingRingtone();
        startRippleAnimation();
        startRingingDotsAnimation();
        watchCallStatus();
        autoRejectHandler.postDelayed(() -> { if (!acted) reject(); }, AUTO_REJECT_MS);

        checkCallerStatusStrip();

        binding.btnAccept.setOnClickListener(v -> accept());
        binding.btnReject.setOnClickListener(v -> reject());

        if (binding.btnMessage != null) {
            binding.btnMessage.setOnClickListener(v -> showQuickReplySheet());
        }

        // ── NEW: Reject with Voice Note ────────────────────────────────────
        // Receiver call decline karte waqt voice note bhej sakta hai caller ko
        if (binding.btnRejectVoiceNote != null) {
            binding.btnRejectVoiceNote.setOnClickListener(v -> rejectWithNote(false));
        }

        // ── NEW: Reject with Video Note ────────────────────────────────────
        // Receiver call decline karte waqt video note bhej sakta hai caller ko
        if (binding.btnRejectVideoNote != null) {
            binding.btnRejectVideoNote.setOnClickListener(v -> rejectWithNote(true));
        }

        if (binding.swipeRail != null) {
            binding.swipeRail.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        swipeTouchStartY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dy = event.getRawY() - swipeTouchStartY;
                        float clamp = Math.max(-80f, Math.min(80f, dy));
                        if (binding.swipePhoneContainer != null) {
                            binding.swipePhoneContainer.setTranslationY(clamp);
                        }
                        if (binding.tvSwipeHintUp != null && binding.tvSwipeHintDown != null) {
                            if (dy < -20) {
                                binding.tvSwipeHintUp.setAlpha(1f);
                                binding.tvSwipeHintDown.setAlpha(0.25f);
                            } else if (dy > 20) {
                                binding.tvSwipeHintDown.setAlpha(1f);
                                binding.tvSwipeHintUp.setAlpha(0.25f);
                            } else {
                                binding.tvSwipeHintUp.setAlpha(0.7f);
                                binding.tvSwipeHintDown.setAlpha(0.7f);
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        float totalDy = event.getRawY() - swipeTouchStartY;
                        if (binding.swipePhoneContainer != null) {
                            binding.swipePhoneContainer.animate().translationY(0).setDuration(250).start();
                        }
                        if (binding.tvSwipeHintUp != null) binding.tvSwipeHintUp.setAlpha(0.7f);
                        if (binding.tvSwipeHintDown != null) binding.tvSwipeHintDown.setAlpha(0.7f);
                        if (totalDy < -SWIPE_THRESHOLD) accept();
                        else if (totalDy > SWIPE_THRESHOLD) reject();
                        return true;
                }
                return false;
            });
        }

        binding.btnQuickReply1.setOnClickListener(v -> sendQuickReply("Can't talk right now"));
        binding.btnQuickReply2.setOnClickListener(v -> sendQuickReply("I'll call you later"));
        binding.btnQuickReply3.setOnClickListener(v -> sendQuickReply("On my way!"));

        startSwipeHintPulse();
    }

    private void showQuickReplySheet() {
        if (binding.layoutQuickReplies != null) {
            binding.layoutQuickReplies.animate()
                .scaleX(1.05f).scaleY(1.05f).setDuration(150)
                .withEndAction(() -> binding.layoutQuickReplies.animate()
                    .scaleX(1f).scaleY(1f).setDuration(150).start())
                .start();
        }
        Toast.makeText(this, "Choose a quick reply above", Toast.LENGTH_SHORT).show();
    }

    // ── NEW: Reject with Note ──────────────────────────────────────────────
    /**
     * Receiver incoming call decline karta hai aur voice/video note bhejta hai caller ko.
     *
     * Flow:
     *  1. Call reject hoti hai (Firebase status = "rejected")
     *  2. Caller ko missed call notification jaati hai
     *  3. AddNoteActivity khulti hai — receiver yahan mic/camera se note record karta hai
     *  4. Note caller ko chat mein jaata hai (jaise outgoing call ka note receiver ko jaata hai)
     *
     * @param isVideoNote true = video note, false = voice note
     */
    private void rejectWithNote(boolean isVideoNote) {
        if (acted) return;
        acted = true;
        autoRejectHandler.removeCallbacksAndMessages(null);
        stopAll();

        // Firebase pe call reject karo
        if (callId != null && !callId.isEmpty()) {
            FirebaseUtils.db().getReference("activeCalls").child(callId)
                .child("status").setValue("rejected");
        }

        // Caller ko missed call notification bhejo
        String myUid  = FirebaseUtils.getCurrentUid();
        String myName = FirebaseUtils.getCurrentName();
        if (myUid != null && fromUid != null && !fromUid.isEmpty()) {
            PushNotify.notifyMissedCall(
                fromUid,   // caller ko bhejo
                myUid,     // hum receiver hain
                myName != null ? myName : "",
                callId != null ? callId : "",
                isVideo,
                fromPhoto != null ? fromPhoto : ""
            );
        }

        // Call log karo
        logMissedCall();

        // ── AddNoteActivity launch karo ──────────────────────────────────
        // partnerUid = caller (jisko note jaayega)
        // chatId = receiver aur caller ke UIDs se bana
        if (myUid != null && fromUid != null && !fromUid.isEmpty()) {
            String chatId = myUid.compareTo(fromUid) < 0
                ? myUid + "_" + fromUid
                : fromUid + "_" + myUid;
            try {
                Intent noteIntent = new Intent(this, AddNoteActivity.class);
                noteIntent.putExtra(AddNoteActivity.EXTRA_PARTNER_UID,   fromUid);
                noteIntent.putExtra(AddNoteActivity.EXTRA_PARTNER_NAME,  fromName != null ? fromName : "");
                noteIntent.putExtra(AddNoteActivity.EXTRA_PARTNER_PHOTO, fromPhoto != null ? fromPhoto : "");
                noteIntent.putExtra(AddNoteActivity.EXTRA_CHAT_ID,       chatId);
                noteIntent.putExtra(AddNoteActivity.EXTRA_IS_VIDEO,      isVideoNote);
                startActivity(noteIntent);
            } catch (Exception ex) {
                android.util.Log.w("IncomingCallActivity", "AddNoteActivity launch failed", ex);
            }
        }

        finish();
    }

    private void startSwipeHintPulse() {
        try {
            if (binding.tvSwipeHintUp == null || binding.tvSwipeHintDown == null) return;
            ObjectAnimator upPulse = ObjectAnimator.ofFloat(binding.tvSwipeHintUp, "translationY", 0f, -6f, 0f);
            upPulse.setDuration(1400); upPulse.setRepeatCount(ObjectAnimator.INFINITE);
            upPulse.setRepeatMode(ObjectAnimator.RESTART); upPulse.start();
            ObjectAnimator downPulse = ObjectAnimator.ofFloat(binding.tvSwipeHintDown, "translationY", 0f, 6f, 0f);
            downPulse.setDuration(1400); downPulse.setRepeatCount(ObjectAnimator.INFINITE);
            downPulse.setRepeatMode(ObjectAnimator.RESTART); downPulse.setStartDelay(700); downPulse.start();
        } catch (Exception ignored) {}
    }

    private void sendQuickReply(String message) {
        if (acted) return;
        acted = true;
        autoRejectHandler.removeCallbacksAndMessages(null);
        stopAll();
        if (callId != null && !callId.isEmpty())
            FirebaseUtils.db().getReference("activeCalls").child(callId).child("status").setValue("rejected");
        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid != null && fromUid != null && !fromUid.isEmpty()) {
            String chatId = myUid.compareTo(fromUid) < 0 ? myUid+"_"+fromUid : fromUid+"_"+myUid;
            Map<String, Object> msg = new HashMap<>();
            msg.put("senderId", myUid); msg.put("text", message);
            msg.put("timestamp", System.currentTimeMillis()); msg.put("type", "text"); msg.put("status", "sent");
            FirebaseUtils.db().getReference("chats").child(chatId).child("messages").push().setValue(msg);
        }
        Toast.makeText(this, "Replied: " + message, Toast.LENGTH_SHORT).show();
        logMissedCall(); finish();
    }

    private void accept() {
        if (acted) return; acted = true;
        autoRejectHandler.removeCallbacksAndMessages(null); stopAll();
        if (callId != null && !callId.isEmpty())
            FirebaseUtils.db().getReference("activeCalls").child(callId).child("status").setValue("accepted");
        Intent i = new Intent(this, CallActivity.class);
        i.putExtra("partnerUid",   fromUid);
        i.putExtra("partnerName",  fromName  != null ? fromName  : "");
        i.putExtra("partnerPhoto", fromPhoto != null ? fromPhoto : "");
        i.putExtra("partnerThumb", fromThumb != null ? fromThumb : "");
        i.putExtra("isCaller", false); i.putExtra("video", isVideo); i.putExtra("callId", callId);
        startActivity(i); finish();
    }

    private void reject() {
        if (acted) return; acted = true;
        autoRejectHandler.removeCallbacksAndMessages(null); stopAll();
        if (callId != null && !callId.isEmpty())
            FirebaseUtils.db().getReference("activeCalls").child(callId).child("status").setValue("rejected");
        logMissedCall(); finish();
    }

    private void stopAll() {
        stopVibration(); stopRingtone(); stopRippleAnimation();
        stopRingingDots(); stopIncomingRingService(); cancelRingNotification();
    }

    private void logMissedCall() {
        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid == null || fromUid == null || fromUid.isEmpty()) return;
        long ts = System.currentTimeMillis(); String media = isVideo ? "video" : "audio";
        Map<String, Object> m = new HashMap<>();
        m.put("partnerUid", fromUid); m.put("partnerName", fromName != null ? fromName : "");
        m.put("direction", "missed"); m.put("mediaType", media); m.put("timestamp", ts); m.put("duration", 0L);
        FirebaseUtils.getCallsRef(myUid).push().setValue(m);
        FirebaseUtils.getUserRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                String myName = snap.child("name").getValue(String.class);
                Map<String, Object> c = new HashMap<>();
                c.put("partnerUid", myUid); c.put("partnerName", myName != null ? myName : "");
                c.put("direction", "no_answer"); c.put("mediaType", media); c.put("timestamp", ts); c.put("duration", 0L);
                FirebaseUtils.getCallsRef(fromUid).push().setValue(c);
            }
            @Override public void onCancelled(DatabaseError e) {}
        });
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                CallLogEntity entity = new CallLogEntity();
                entity.id = java.util.UUID.randomUUID().toString();
                entity.partnerUid = fromUid; entity.partnerName = fromName != null ? fromName : "";
                entity.direction = "missed"; entity.mediaType = media; entity.timestamp = ts; entity.duration = 0L;
                AppDatabase.getInstance(getApplicationContext()).callLogDao().insertCallLog(entity);
            } catch (Exception ignored) {}
        });
    }

    private void watchCallStatus() {
        if (callId == null || callId.isEmpty()) return;
        statusListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot s) {
                String st = s.getValue(String.class);
                if ("ended".equals(st) || "cancelled".equals(st))
                    runOnUiThread(() -> { if (!acted) reject(); });
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        FirebaseUtils.db().getReference("activeCalls").child(callId).child("status").addValueEventListener(statusListener);
    }

    private void startRippleAnimation() {
        try {
            View r1 = binding.rippleRing1, r2 = binding.rippleRing2, r3 = binding.rippleRing3;
            if (r1 == null || r2 == null || r3 == null) return;
            rippleSet = new AnimatorSet();
            rippleSet.playTogether(makeRipplePulse(r1, 0), makeRipplePulse(r2, 500), makeRipplePulse(r3, 1000));
            rippleSet.start();
        } catch (Exception ignored) {}
    }

    private AnimatorSet makeRipplePulse(View v, long delay) {
        ObjectAnimator sx = ObjectAnimator.ofFloat(v, "scaleX", 0.82f, 1.18f, 0.82f);
        ObjectAnimator sy = ObjectAnimator.ofFloat(v, "scaleY", 0.82f, 1.18f, 0.82f);
        ObjectAnimator a  = ObjectAnimator.ofFloat(v, "alpha",  0.06f, 0.33f, 0.06f);
        for (ObjectAnimator oa : new ObjectAnimator[]{sx, sy, a}) {
            oa.setRepeatCount(ObjectAnimator.INFINITE); oa.setRepeatMode(ObjectAnimator.RESTART);
            oa.setInterpolator(new DecelerateInterpolator());
        }
        AnimatorSet s = new AnimatorSet(); s.playTogether(sx, sy, a);
        s.setDuration(2200); s.setStartDelay(delay); return s;
    }

    private void stopRippleAnimation() {
        try { if (rippleSet != null) { rippleSet.cancel(); rippleSet = null; } } catch (Exception ignored) {}
    }

    private void startRingingDotsAnimation() {
        dotsHandler.post(new Runnable() {
            @Override public void run() {
                if (acted) return;
                dotCount = (dotCount + 1) % 4;
                StringBuilder dots = new StringBuilder("Ringing");
                for (int i = 0; i < dotCount; i++) dots.append(".");
                try { binding.tvRingingStatus.setText(dots.toString()); } catch (Exception ignored) {}
                dotsHandler.postDelayed(this, 500);
            }
        });
    }

    private void stopRingingDots() { dotsHandler.removeCallbacksAndMessages(null); }

    private void startLoopingRingtone() {
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            ringtonePlayer = new MediaPlayer();
            ringtonePlayer.setDataSource(this, uri);
            ringtonePlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            ringtonePlayer.setLooping(true); ringtonePlayer.prepare(); ringtonePlayer.start();
        } catch (Exception ignored) {}
    }

    private void stopRingtone() {
        try { if (ringtonePlayer != null) { ringtonePlayer.stop(); ringtonePlayer.release(); ringtonePlayer = null; } }
        catch (Exception ignored) {}
    }

    private void startVibration() {
        try {
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            long[] pattern = {0, 400, 200, 400, 800};
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
            else vibrator.vibrate(pattern, 0);
        } catch (Exception ignored) {}
    }

    private void stopVibration() {
        try { if (vibrator != null) { vibrator.cancel(); vibrator = null; } } catch (Exception ignored) {}
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "callx:incoming_call");
            wakeLock.acquire(AUTO_REJECT_MS + 5_000L);
        } catch (Exception ignored) {}
    }

    private void stopIncomingRingService() {
        try { stopService(new Intent(this, IncomingRingService.class)); } catch (Exception ignored) {}
    }

    private void cancelRingNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(Constants.CALL_RING_NOTIF_ID);
        } catch (Exception ignored) {}
    }

    @Override protected void onDestroy() {
        autoRejectHandler.removeCallbacksAndMessages(null);
        stopVibration(); stopRingtone(); stopRippleAnimation(); stopRingingDots();
        if (wakeLock != null && wakeLock.isHeld()) try { wakeLock.release(); } catch (Exception ignored) {}
        if (statusListener != null && callId != null && !callId.isEmpty()) {
            try { FirebaseUtils.db().getReference("activeCalls").child(callId).child("status").removeEventListener(statusListener); }
            catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
