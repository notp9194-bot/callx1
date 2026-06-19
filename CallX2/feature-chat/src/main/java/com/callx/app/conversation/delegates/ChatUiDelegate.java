package com.callx.app.conversation.delegates;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.CycleInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.conversation.MessagePagingAdapter;
import com.callx.app.models.Message;
import com.callx.app.utils.ChatThemeManager;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

/**
 * ChatUiDelegate — Toolbar setup, social share buttons, pendulum + rubber-band animations,
 *                  multi-select bar, message info dialog, avatar zoom.
 */
public class ChatUiDelegate {

    public interface Callback {
        void runOnUiThread(Runnable r);
        void onCallClicked();
        void onVideoCallClicked();
    }

    private final Activity            activity;
    private final ActivityChatBinding binding;
    private final String              chatId;
    private final String              currentUid;
    private final String              partnerUid;
    private final String              partnerName;
    private final Callback            callback;

    private String partnerAvatarUrl;

    public ChatUiDelegate(Activity activity, ActivityChatBinding binding, String chatId,
                          String currentUid, String partnerUid, String partnerName, Callback callback) {
        this.activity    = activity;
        this.binding     = binding;
        this.chatId      = chatId;
        this.currentUid  = currentUid;
        this.partnerUid  = partnerUid;
        this.partnerName = partnerName;
        this.callback    = callback;
    }

    // ── Toolbar ───────────────────────────────────────────────────────────

    public void setupToolbar(androidx.appcompat.app.AppCompatActivity host,
                             String avatarUrl, String partnerThumbUrl) {
        this.partnerAvatarUrl = avatarUrl;
        if (binding.tvChatName != null) binding.tvChatName.setText(partnerName);
        if (binding.ivAvatar != null) {
            String url = avatarUrl != null && !avatarUrl.isEmpty() ? avatarUrl : partnerThumbUrl;
            if (url != null && !url.isEmpty())
                Glide.with(activity).load(url).circleCrop().into(binding.ivAvatar);
        }
        if (binding.btnCall != null)      binding.btnCall.setOnClickListener(v -> callback.onCallClicked());
        if (binding.btnVideoCall != null) binding.btnVideoCall.setOnClickListener(v -> callback.onVideoCallClicked());
        if (binding.ivAvatar != null)     binding.ivAvatar.setOnClickListener(v -> openAvatarZoom());
        ChatThemeManager.get(activity).applyToolbarColor(binding.toolbar);
    }

    public void watchPartnerAvatar() {
        if (partnerUid == null) return;
        FirebaseUtils.getUserRef(partnerUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                String url = s.child("photoUrl").getValue(String.class);
                if (url == null) url = s.child("profilePicUrl").getValue(String.class);
                if (url != null && !url.isEmpty() && binding.ivAvatar != null) {
                    partnerAvatarUrl = url;
                    final String furl = url;
                    activity.runOnUiThread(() -> Glide.with(activity).load(furl).circleCrop().into(binding.ivAvatar));
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
    }

    // ── Avatar Zoom ───────────────────────────────────────────────────────

    public void openAvatarZoom() {
        if (partnerAvatarUrl == null || partnerAvatarUrl.isEmpty()) {
            Toast.makeText(activity, "No profile photo", Toast.LENGTH_SHORT).show(); return;
        }
        try {
            Class<?> cls = Class.forName("com.callx.app.activities.AvatarZoomActivity");
            Intent i = new Intent(activity, cls);
            i.putExtra("imageUrl", partnerAvatarUrl); i.putExtra("name", partnerName);
            activity.startActivity(i);
        } catch (ClassNotFoundException e) {
            android.util.Log.e("ChatUiDelegate", "AvatarZoomActivity not found", e);
        }
    }

    public void openEditProfile() {
        try {
            Class<?> cls = Class.forName("com.callx.app.settings.EditProfileActivity");
            activity.startActivity(new Intent(activity, cls));
        } catch (ClassNotFoundException e) {
            android.util.Log.e("ChatUiDelegate", "EditProfileActivity not found", e);
        }
    }

    // ── Social share buttons ──────────────────────────────────────────────

    public void setupSocialButtons() {
        if (binding.btnShareReel == null) return;
        binding.btnShareReel.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_SEND); i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, "Check out this reel on CallX!");
            activity.startActivity(Intent.createChooser(i, "Share Reel"));
        });
        if (binding.btnShareX != null) {
            binding.btnShareX.setOnClickListener(v -> {
                Intent i = new Intent(Intent.ACTION_SEND); i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, "Shared from CallX on X");
                activity.startActivity(Intent.createChooser(i, "Share on X"));
            });
        }
        if (binding.btnShareYouTube != null) {
            binding.btnShareYouTube.setOnClickListener(v -> {
                Intent i = new Intent(Intent.ACTION_SEND); i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, "Shared from CallX to YouTube");
                activity.startActivity(Intent.createChooser(i, "Share on YouTube"));
            });
        }
    }

    // ── Pendulum animation ────────────────────────────────────────────────

    public RotateAnimation makePendulumAnim(float fromDeg, float toDeg, long durationMs) {
        RotateAnimation anim = new RotateAnimation(fromDeg, toDeg,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.0f);
        anim.setDuration(durationMs);
        anim.setRepeatCount(Animation.INFINITE);
        anim.setRepeatMode(Animation.RESTART);
        anim.setInterpolator(new CycleInterpolator(1));
        return anim;
    }

    // ── Rubber-band hang ──────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    public void setupRubberBandHang(LinearLayout container, View threadView,
                                    ImageButton button, float fromDeg, float toDeg, long durationMs) {
        final float MAX_PULL = 160f;
        final float[] startRawY = {0f};
        final boolean[] dragged = {false};
        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    startRawY[0] = event.getRawY(); dragged[0] = false;
                    container.clearAnimation(); container.setRotation(0f);
                    threadView.setPivotY(0f); threadView.setScaleY(1f); button.setTranslationY(0f);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    float dy = event.getRawY() - startRawY[0];
                    if (dy < 0f) dy = 0f; if (dy > 8f) dragged[0] = true;
                    float pull = MAX_PULL * (1f - (float) Math.exp(-dy / MAX_PULL));
                    threadView.setPivotY(0f);
                    float th = threadView.getHeight() > 0 ? threadView.getHeight() : 60f;
                    threadView.setScaleY(1f + pull / th); button.setTranslationY(pull);
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    if (!dragged[0]) {
                        button.performClick();
                        container.post(() -> container.startAnimation(makePendulumAnim(fromDeg, toDeg, durationMs)));
                        return true;
                    }
                    threadView.animate().scaleY(1f).setDuration(550)
                            .setInterpolator(new OvershootInterpolator(3.5f)).start();
                    button.animate().translationY(0f).setDuration(550)
                            .setInterpolator(new OvershootInterpolator(3.5f))
                            .withEndAction(() ->
                                    container.post(() -> container.startAnimation(makePendulumAnim(fromDeg, toDeg, durationMs))))
                            .start();
                    return true;
            }
            return false;
        });
    }

    // ── Multi-select bar ──────────────────────────────────────────────────

    public void showMultiSelectBar(Set<String> selectedIds, MessagePagingAdapter adapter) {
        if (binding.llMultiSelectBar == null) return;
        binding.llMultiSelectBar.setVisibility(View.VISIBLE);
        if (binding.tvMultiSelectCount != null)
            binding.tvMultiSelectCount.setText(selectedIds.size() + " selected");
    }

    public void hideMultiSelectBar() {
        if (binding.llMultiSelectBar == null) return;
        binding.llMultiSelectBar.setVisibility(View.GONE);
    }

    public void updateMultiSelectCount(int count) {
        if (binding.tvMultiSelectCount != null)
            binding.tvMultiSelectCount.setText(count + " selected");
    }

    // ── Message info dialog ───────────────────────────────────────────────

    public void showMessageInfoDialog(Message m) {
        if (m == null) return;
        String info = "Sent at: " + (m.timestamp > 0
                ? new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(new Date(m.timestamp))
                : "Unknown")
                + "\nStatus: " + (m.status != null ? m.status : "unknown");
        new android.app.AlertDialog.Builder(activity)
                .setTitle("Message Info").setMessage(info).setPositiveButton("OK", null).show();
    }

    // ── Utility ───────────────────────────────────────────────────────────

    public int dp(int dp) {
        return (int) (dp * activity.getResources().getDisplayMetrics().density);
    }
}
