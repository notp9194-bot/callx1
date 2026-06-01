package com.callx.app.utils;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

/**
 * CallAudioFocusManager — Centralized AudioFocus lifecycle for 1:1 and group calls.
 *
 * Handles:
 *  - AUDIOFOCUS_GAIN (exclusive, long-lived) before WebRTC init
 *  - Abandon on call end so music/media apps resume automatically
 *  - Android O+ AudioFocusRequest API with VOICE_COMMUNICATION usage
 *  - AudioManager mode switching (MODE_IN_COMMUNICATION during call)
 *
 * Usage:
 *   manager.requestFocus();      // before WebRTC init
 *   manager.configureForCall(speakerOn);
 *   // …call runs…
 *   manager.abandonFocus();      // in endCall()
 *   manager.restoreAudio();      // in endCall()
 */
public class CallAudioFocusManager {

    private static final String TAG = "CallAudioFocusManager";

    private final AudioManager audioManager;
    private AudioFocusRequest  focusRequest; // API 26+
    private boolean            hasFocus = false;

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                Log.d(TAG, "Focus lost permanently");
                hasFocus = false;
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                Log.d(TAG, "Focus lost transiently");
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                Log.d(TAG, "Focus regained");
                hasFocus = true;
                break;
            default:
                break;
        }
    };

    public CallAudioFocusManager(Context context) {
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    /** Request exclusive audio focus for voice communication. */
    public boolean requestFocus() {
        if (audioManager == null) return false;
        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(focusListener)
                .build();
            result = audioManager.requestAudioFocus(focusRequest);
        } else {
            //noinspection deprecation
            result = audioManager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN);
        }
        hasFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        if (!hasFocus) Log.w(TAG, "AudioFocus not granted (result=" + result + ")");
        return hasFocus;
    }

    /** Set AudioManager mode and speakerphone for the call. */
    public void configureForCall(boolean speakerOn) {
        if (audioManager == null) return;
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.setSpeakerphoneOn(speakerOn);
    }

    /** Toggle speakerphone during call. */
    public void setSpeakerOn(boolean on) {
        if (audioManager != null) audioManager.setSpeakerphoneOn(on);
    }

    public boolean isSpeakerOn() {
        return audioManager != null && audioManager.isSpeakerphoneOn();
    }

    /** Abandon focus — call on call end so other apps resume. */
    public void abandonFocus() {
        if (audioManager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
            } else {
                //noinspection deprecation
                audioManager.abandonAudioFocus(focusListener);
            }
        } catch (Exception e) {
            Log.w(TAG, "abandonFocus: " + e.getMessage());
        }
        hasFocus = false;
    }

    /** Restore AudioManager to normal mode after call. */
    public void restoreAudio() {
        if (audioManager == null) return;
        try {
            audioManager.setSpeakerphoneOn(false);
            audioManager.setMode(AudioManager.MODE_NORMAL);
        } catch (Exception e) {
            Log.w(TAG, "restoreAudio: " + e.getMessage());
        }
    }

    public boolean hasFocus() { return hasFocus; }
}
