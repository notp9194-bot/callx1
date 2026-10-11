package com.callx.app.viewer;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * Audio-focus helper for the status viewer's music-sticker MediaPlayer.
 *
 * WHY: without focus handling the sticker music played ON TOP of Spotify /
 * YouTube / a call, and never paused when another app started playing.
 * Now: we ask for a TRANSIENT focus (a status is a short thing — the other
 * app's music resumes by itself once we abandon), pause on loss, resume when
 * focus comes back and duck when asked to.
 *
 * Shared by the status VIEWER (music sticker autoplay) and the status COMPOSER
 * (music sticker preview). NOTE: feature-status has no dependency on feature-reels, so this is a small
 * standalone copy of the same pattern as ReelAudioFocusHelper.
 *
 * IMPORTANT (javac): the focus-change callback is a lambda that touches the
 * final field `listener`. It must NOT be a field initializer (javac then says
 * "variable listener might not have been initialized") — it is built in the
 * constructor AFTER `listener` is assigned.
 */
public final class StatusAudioFocusHelper {

    public interface Listener {
        /** Focus lost (call / other app took over) — pause the music. */
        void onFocusLost();
        /** Transient loss ended — resume the music. */
        void onFocusRegained();
        /** Another app asked us to duck (lower volume) or restore. */
        void onDuck(boolean ducked);
    }

    private final AudioManager am;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager.OnAudioFocusChangeListener changeListener;
    private AudioFocusRequest request; // API 26+
    private boolean held = false;
    private boolean lostTransiently = false;

    public StatusAudioFocusHelper(Context ctx, Listener listener) {
        this.am = (AudioManager) ctx.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        this.listener = listener;
        // listener is assigned above -> safe to build the lambda now
        this.changeListener = change -> {
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
    }

    /**
     * @return true if focus is held, or can't be managed (AudioManager missing /
     *         threw) so playback shouldn't be blocked. false = focus was refused
     *         (e.g. a phone call is active) → caller should NOT start playing.
     */
    public boolean request() {
        if (held) return true;
        if (am == null) return true;
        int result;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (request == null) {
                    AudioAttributes attrs = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build();
                    request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .setAudioAttributes(attrs)
                            .setOnAudioFocusChangeListener(changeListener, main)
                            .build();
                }
                result = am.requestAudioFocus(request);
            } else {
                result = am.requestAudioFocus(changeListener,
                        AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            }
        } catch (Exception e) {
            return true;
        }
        held = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        lostTransiently = false;
        return held;
    }

    /** Gives focus back so the other app's music can resume. Safe to call anytime. */
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
