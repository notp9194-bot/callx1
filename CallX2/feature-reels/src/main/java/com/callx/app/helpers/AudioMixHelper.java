package com.callx.app.helpers;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.callx.app.utils.Constants;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * AudioMixHelper v2 — SERVER-SIDE AUDIO MIXING (Mobile Pe CPU Nahi Lagta)
 *
 * PEHLE (v1): Mobile pe MediaCodec + MediaMuxer se PCM decode → mix → re-encode AAC
 *             → Heavy DSP operations → Mobile garam, battery drain, 30-60 sec lag
 *
 * AB (v2):    Server pe /audio/mix endpoint pe video + music URL bhejo
 *             → Server FFmpeg se mix karta hai
 *             → Mobile sirf output file download karta hai
 *
 * Server Endpoint: POST /audio/mix
 * Input:  video file (multipart) + music_url + volumes
 * Output: JSON { output_url, output_path }
 *
 * Mobile CPU: ~2% (sirf upload + download)
 * Mobile Heat: Almost zero (DSP kuch nahi hota mobile pe)
 */
public class AudioMixHelper {

    private static final String TAG         = "AudioMixHelper";
    private static final String MIX_ENDPOINT = Constants.SERVER_URL + "/audio/mix";

    public interface MixCallback {
        void onProgress(int percent);
        void onSuccess(String outputPath);  // local temp file path (downloaded from server)
        void onError(Exception e);
    }

    private static final ExecutorService executor    = Executors.newSingleThreadExecutor();
    private static final Handler         mainHandler = new Handler(Looper.getMainLooper());

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
        .connectTimeout(30,  TimeUnit.SECONDS)
        .readTimeout(300,    TimeUnit.SECONDS)
        .writeTimeout(300,   TimeUnit.SECONDS)
        .build();

    /**
     * Main entry point — audio mixing SERVER PE karo.
     *
     * @param context       App context
     * @param videoPath     Recorded video path (mic audio ke saath)
     * @param musicUrl      Background music URL (nullable = sirf mic)
     * @param voiceoverPath Voiceover file path (nullable = skip)
     * @param micVol        Mic volume 0.0–1.0
     * @param musicVol      Music volume 0.0–1.0
     * @param voiceoverVol  Voiceover volume 0.0–1.0
     * @param callback      Result callback (main thread pe)
     */
    public static void mixAndExport(
            Context context,
            String videoPath,
            String musicUrl,
            String voiceoverPath,
            float micVol,
            float musicVol,
            float voiceoverVol,
            MixCallback callback) {

        executor.execute(() -> {
            File tempOutput = null;
            try {
                mainHandler.post(() -> callback.onProgress(5));

                // Step 1: Video file send karo server pe
                File videoFile = new File(videoPath);
                if (!videoFile.exists()) {
                    throw new IOException("Video file nahi mili: " + videoPath);
                }

                // Step 2: Voiceover bhi bhejo agar hai
                MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("video", videoFile.getName(),
                        RequestBody.create(videoFile, MediaType.parse("video/mp4")))
                    .addFormDataPart("mic_vol",   String.valueOf(micVol))
                    .addFormDataPart("music_vol", String.valueOf(musicVol));

                if (musicUrl != null && !musicUrl.isEmpty()) {
                    bodyBuilder.addFormDataPart("music_url", musicUrl);
                }

                if (voiceoverPath != null && !voiceoverPath.isEmpty()) {
                    File voiceFile = new File(voiceoverPath);
                    if (voiceFile.exists()) {
                        bodyBuilder.addFormDataPart("voiceover_vol", String.valueOf(voiceoverVol));
                        bodyBuilder.addFormDataPart("voiceover", voiceFile.getName(),
                            RequestBody.create(voiceFile, MediaType.parse("audio/aac")));
                    }
                }

                mainHandler.post(() -> callback.onProgress(15));

                Request mixReq = new Request.Builder()
                    .url(MIX_ENDPOINT)
                    .post(bodyBuilder.build())
                    .build();

                mainHandler.post(() -> callback.onProgress(20));

                Response mixRes = HTTP.newCall(mixReq).execute();
                String mixBody  = mixRes.body() != null ? mixRes.body().string() : "";
                mixRes.close();

                if (!mixRes.isSuccessful()) {
                    throw new IOException("Server mix failed " + mixRes.code() + ": " + mixBody);
                }

                mainHandler.post(() -> callback.onProgress(80));

                // Step 3: Server response — output URL ya direct file
                JSONObject resp = new JSONObject(mixBody);

                String outputUrl = resp.optString("output_url", "");

                if (!outputUrl.isEmpty()) {
                    // Server ne Cloudinary pe upload kiya — download karo local pe
                    tempOutput = downloadToTemp(context, outputUrl);
                } else if (resp.has("output_path")) {
                    // Server ne local path diya (render.com temp storage)
                    // Seedha URL se download karo
                    String serverPath = resp.getString("output_path");
                    String downloadUrl = Constants.SERVER_URL + "/audio/download?path=" + serverPath;
                    tempOutput = downloadToTemp(context, downloadUrl);
                } else {
                    throw new IOException("Server se output URL nahi mila");
                }

                mainHandler.post(() -> callback.onProgress(95));

                final String finalPath = tempOutput.getAbsolutePath();
                mainHandler.post(() -> {
                    callback.onProgress(100);
                    callback.onSuccess(finalPath);
                });

            } catch (Exception e) {
                Log.e(TAG, "Server audio mix failed", e);
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    /**
     * URL se file download karo local temp mein.
     * Iska use output video download ke liye hota hai.
     */
    private static File downloadToTemp(Context ctx, String url) throws IOException {
        File temp = new File(ctx.getCacheDir(),
            "mixed_" + UUID.randomUUID().toString().substring(0, 8) + ".mp4");

        Request req = new Request.Builder().url(url).get().build();
        Response res = HTTP.newCall(req).execute();

        if (!res.isSuccessful() || res.body() == null) {
            res.close();
            throw new IOException("Download failed: " + res.code());
        }

        try (InputStream in = res.body().byteStream();
             FileOutputStream out = new FileOutputStream(temp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally {
            res.close();
        }
        return temp;
    }
}
