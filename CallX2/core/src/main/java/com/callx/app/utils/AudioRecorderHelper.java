package com.callx.app.utils;
import android.content.Context;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.IOException;
public class AudioRecorderHelper {
    private MediaRecorder recorder;
    private File outFile;
    private long startedAt;
    public boolean start(Context ctx) {
        try {
            File dir = new File(ctx.getCacheDir(), "voice");
            if (!dir.exists()) dir.mkdirs();
            outFile = new File(dir, "vm_" + System.currentTimeMillis() + ".m4a");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                recorder = new MediaRecorder(ctx);
            } else {
                //noinspection deprecation
                recorder = new MediaRecorder();
            }
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(64000);
            recorder.setAudioSamplingRate(44100);
            recorder.setOutputFile(outFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            startedAt = System.currentTimeMillis();
            return true;
        } catch (IOException | RuntimeException e) {
            cleanup();
            return false;
        }
    }
    public Uri stop(Context ctx) {
        if (recorder == null || outFile == null) return null;
        try { recorder.stop(); } catch (Exception ignored) {}
        try { recorder.release(); } catch (Exception ignored) {}
        recorder = null;
        if (!outFile.exists() || outFile.length() == 0) return null;
        return FileProvider.getUriForFile(ctx,
            "com.callx.app.fileprovider", outFile);
    }
    public long getDuration() {
        return System.currentTimeMillis() - startedAt;
    }
    public void cancel() { cleanup(); }
    private void cleanup() {
        try { if (recorder != null) recorder.release(); } catch (Exception ignored) {}
        recorder = null;
        if (outFile != null && outFile.exists()) outFile.delete();
        outFile = null;
    }
}
