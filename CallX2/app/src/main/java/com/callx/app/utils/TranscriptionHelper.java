package com.callx.app.utils;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Feature 12: Voice Message Transcription
 *
 * Uses Android's built-in SpeechRecognizer to transcribe audio.
 * For downloaded audio files, we play + pass to the recognizer.
 * The recognizer works best with live microphone input;
 * for file-based transcription a cloud STT endpoint would be needed.
 *
 * This implementation provides:
 *  A) Live transcription helper (used during recording).
 *  B) A stub for cloud-based transcription of saved audio files
 *     (POST /transcribe endpoint — plug in Whisper or Google STT).
 */
public class TranscriptionHelper {

    private static final String TAG = "Transcription";

    public interface TranscriptCallback {
        void onTranscript(String text);
        void onError(String reason);
    }

    // ── A) Live transcription (during recording) ───────────────────────────

    private final SpeechRecognizer recognizer;
    private TranscriptCallback liveCallback;

    public TranscriptionHelper(Context ctx) {
        if (SpeechRecognizer.isRecognitionAvailable(ctx)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(ctx);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onResults(Bundle results) {
                    ArrayList<String> r = results.getStringArrayList(
                            android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                    if (r != null && !r.isEmpty() && liveCallback != null)
                        liveCallback.onTranscript(r.get(0));
                }
                @Override public void onError(int error) {
                    if (liveCallback != null)
                        liveCallback.onError("Recognition error: " + error);
                }
                @Override public void onReadyForSpeech(Bundle p) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float v) {}
                @Override public void onBufferReceived(byte[] b) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onPartialResults(Bundle b) {}
                @Override public void onEvent(int t, Bundle b) {}
            });
        } else {
            recognizer = null;
        }
    }

    public void startLiveTranscription(TranscriptCallback cb) {
        if (recognizer == null) { cb.onError("Speech recognition not available"); return; }
        liveCallback = cb;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                   RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizer.startListening(i);
    }

    public void stopListening() {
        if (recognizer != null) recognizer.stopListening();
    }

    public void destroy() {
        if (recognizer != null) recognizer.destroy();
    }

    // ── B) Cloud / file-based transcription ───────────────────────────────

    /**
     * Transcribe a remote audio URL using a cloud STT service.
     * Replace the POST URL with your Whisper / Google STT endpoint.
     * Runs on AsyncTask to avoid blocking the main thread.
     */
    public static void transcribeUrl(String audioUrl, TranscriptCallback cb) {
        new android.os.AsyncTask<Void, Void, String>() {
            @Override protected String doInBackground(Void... v) {
                try {
                    java.net.URL url = new java.net.URL(
                            Constants.SERVER_URL + "/api/transcribe");
                    java.net.HttpURLConnection conn =
                            (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(30000);
                    String body = "{\"audioUrl\":\"" + audioUrl + "\"}";
                    conn.getOutputStream().write(body.getBytes("UTF-8"));
                    if (conn.getResponseCode() == 200) {
                        java.io.BufferedReader br = new java.io.BufferedReader(
                                new java.io.InputStreamReader(conn.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                        // Parse simple JSON: {"transcript":"..."}
                        String resp = sb.toString();
                        int s = resp.indexOf("\"transcript\":\"") + 14;
                        int e = resp.indexOf("\"", s);
                        if (s > 13 && e > s) return resp.substring(s, e);
                    }
                    conn.disconnect();
                } catch (Exception e) {
                    Log.e(TAG, "Cloud transcription failed", e);
                }
                return null;
            }
            @Override protected void onPostExecute(String t) {
                if (t != null) cb.onTranscript(t);
                else           cb.onError("Transcription failed");
            }
        }.execute();
    }
}
