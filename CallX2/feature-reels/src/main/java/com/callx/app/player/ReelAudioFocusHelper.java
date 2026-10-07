package com.callx.app.player;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * Audio-focus helper for the photo-reel background MediaPlayer (video reels
 * rely on ExoPlayer's built-in focus handling, see {@link ReelAudioPolicy}).
 * Works on API 23+ (AudioFocusRequest on 26+, legacy call below that).
 */
public final class ReelAudioFocusHelper {

    public interface Listener {
        /** Focus lost (call, other app's playback) — pause the audio. */
        void onFocusLost();
        /** Transient loss ended — resume the audio. */
        void onFocusRegained();
        /** Other app asked us to duck (lower volume) or restore. */
        void onDuck(boolean ducked);
    }

    private final AudioManager am;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private AudioFocusRequest request; // API 26+
    private boolean held = false;
    private boolean lostTransiently = false;

    private final AudioManager.OnAudioFocusChangeListener changeListener = change -> {
        switch (change) {
            case AudioManager.AUDIOFOCUS_GAIN:
                held = true;
                if (lostTransiently) {
                    lostTransiently = false;
                    this.listener.onFocusRegained();
                }
                this.listener.onDuck(false);
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                abandon();
                this.listener.onFocusLost();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                lostTransiently = true;
                this.listener.onFocusLost();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                this.listener.onDuck(true);
                break;
            default:
                break;
        }
    };

    public ReelAudioFocusHelper(Context ctx, Listener listener) {
        this.am = (AudioManager) ctx.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        this.listener = listener;
    }

    /** @return true if focus is held (or can't be managed, so playback shouldn't be blocked). */
    public boolean request() {
        if (held) return true;
        if (am == null) return true;
        int result;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (request == null) {
                    AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build();
                    request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attrs)
                        .setOnAudioFocusChangeListener(changeListener, main)
                        .build();
                }
                result = am.requestAudioFocus(request);
            } else {
                result = am.requestAudioFocus(changeListener,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            }
        } catch (Exception e) {
            return true;
        }
        held = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        lostTransiently = false;
        return held;
    }

    public void abandon() {
        held = false;
        lostTransiently = false;
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (request != null) am.abandonAudioFocusRequest(request);
                request = null;
            } else {
                am.abandonAudioFocus(changeListener);
            }
        } catch (Exception ignored) {}
    }
}
