package com.callx.app.utils.voice;

import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

/**
 * VoiceSpeedController — Playback speed control for voice messages.
 *
 * Supported speeds: 0.5x, 1.0x, 1.5x, 2.0x
 * Cycles through speeds on button tap (same as WhatsApp/Telegram).
 * Requires API 23+ (Android 6.0+).
 *
 * Usage in MessageAdapter/MessagePagingAdapter:
 *   VoiceSpeedController controller = new VoiceSpeedController(tvSpeedBtn);
 *   playBtn.setOnClickListener(v -> {
 *       if (player.isPlaying()) player.pause();
 *       else { controller.apply(player); player.start(); }
 *   });
 *   tvSpeedBtn.setOnClickListener(v -> {
 *       controller.cycleSpeed();
 *       if (player.isPlaying()) controller.apply(player);
 *   });
 */
public class VoiceSpeedController {

    private static final String TAG = "VoiceSpeedCtrl";

    public static final float SPEED_HALF     = 0.5f;
    public static final float SPEED_NORMAL   = 1.0f;
    public static final float SPEED_FAST     = 1.5f;
    public static final float SPEED_FASTEST  = 2.0f;

    private static final float[] SPEEDS = {
        SPEED_NORMAL, SPEED_FAST, SPEED_FASTEST, SPEED_HALF
    };

    private int currentIndex = 0;
    private final TextView speedBtn;

    public VoiceSpeedController(TextView speedBtn) {
        this.speedBtn = speedBtn;
        updateLabel();
    }

    /** Cycle to next speed (call on button tap). */
    public void cycleSpeed() {
        currentIndex = (currentIndex + 1) % SPEEDS.length;
        updateLabel();
        Log.d(TAG, "Speed changed to " + getCurrentSpeed() + "x");
    }

    /** Apply current speed to a playing MediaPlayer. */
    public void apply(MediaPlayer player) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (player == null) return;
        try {
            PlaybackParams params = player.getPlaybackParams();
            params.setSpeed(getCurrentSpeed());
            player.setPlaybackParams(params);
        } catch (Exception e) {
            Log.w(TAG, "Failed to set speed: " + e.getMessage());
        }
    }

    /** Get current speed value. */
    public float getCurrentSpeed() {
        return SPEEDS[currentIndex];
    }

    /** Reset to normal speed. */
    public void reset() {
        currentIndex = 0;
        updateLabel();
    }

    private void updateLabel() {
        if (speedBtn == null) return;
        float s = getCurrentSpeed();
        String label;
        if (s == SPEED_HALF)    label = "0.5×";
        else if (s == SPEED_NORMAL)  label = "1×";
        else if (s == SPEED_FAST)    label = "1.5×";
        else                         label = "2×";
        speedBtn.setText(label);
    }
}
