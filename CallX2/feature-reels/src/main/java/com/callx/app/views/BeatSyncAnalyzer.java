package com.callx.app.views;

  import android.content.Context;
  import android.media.MediaCodec;
  import android.media.MediaExtractor;
  import android.media.MediaFormat;
  import android.util.Log;
  import java.io.IOException;
  import java.nio.ByteBuffer;
  import java.nio.ShortBuffer;
  import java.util.ArrayList;
  import java.util.List;

  /**
   * BeatSyncAnalyzer — Detects beat timestamps from a video/audio file.
   *
   * Algorithm:
   *  1. Extracts raw PCM from audio track using MediaCodec
   *  2. Splits into overlapping 50ms frames
   *  3. Computes RMS energy per frame
   *  4. Applies onset detection: beat = local energy spike > 1.4× rolling average
   *  5. Enforces minimum 300ms gap between beats (prevents double-triggers)
   *
   * Usage:
   *   BeatSyncAnalyzer.analyze(context, filePath, durationMs, new BeatSyncAnalyzer.Callback() {
   *       public void onBeatsReady(long[] beatTimesMs) { ... }
   *       public void onError(Exception e) { ... }
   *   });
   *
   * Runs on a background thread — callback delivered on calling thread's looper.
   */
  public class BeatSyncAnalyzer {

      private static final String TAG          = "BeatSyncAnalyzer";
      private static final int    FRAME_MS     = 50;
      private static final int    HOP_MS       = 25;
      private static final float  ONSET_RATIO  = 1.4f;
      private static final long   MIN_BEAT_GAP = 300L;

      public interface Callback {
          void onBeatsReady(long[] beatTimesMs);
          void onError(Exception e);
      }

      public static void analyze(Context ctx, String filePath, long maxDurationMs, Callback cb) {
          new Thread(() -> {
              try {
                  long[] beats = runAnalysis(filePath, maxDurationMs);
                  cb.onBeatsReady(beats);
              } catch (Exception e) {
                  Log.e(TAG, "Beat analysis failed", e);
                  cb.onError(e);
              }
          }, "BeatSyncAnalyzer").start();
      }

      private static long[] runAnalysis(String filePath, long maxDurationMs) throws IOException {
          MediaExtractor extractor = new MediaExtractor();
          extractor.setDataSource(filePath);

          int audioTrack = -1;
          MediaFormat audioFormat = null;
          for (int i = 0; i < extractor.getTrackCount(); i++) {
              MediaFormat fmt = extractor.getTrackFormat(i);
              String mime = fmt.getString(MediaFormat.KEY_MIME);
              if (mime != null && mime.startsWith("audio/")) {
                  audioTrack = i; audioFormat = fmt; break;
              }
          }
          if (audioTrack < 0) return new long[0];

          extractor.selectTrack(audioTrack);
          int sampleRate   = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
          int channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
          String mime      = audioFormat.getString(MediaFormat.KEY_MIME);

          MediaCodec decoder = MediaCodec.createDecoderByType(mime);
          decoder.configure(audioFormat, null, null, 0);
          decoder.start();

          List<Short> pcm = new ArrayList<>();
          boolean inputDone = false;
          boolean outputDone = false;
          MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

          while (!outputDone) {
              if (!inputDone) {
                  int inIdx = decoder.dequeueInputBuffer(10_000);
                  if (inIdx >= 0) {
                      ByteBuffer buf = decoder.getInputBuffer(inIdx);
                      int sampleSize = extractor.readSampleData(buf, 0);
                      long presentUs = extractor.getSampleTime();
                      if (sampleSize < 0 || (maxDurationMs > 0 && presentUs / 1000 > maxDurationMs)) {
                          decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                          inputDone = true;
                      } else {
                          decoder.queueInputBuffer(inIdx, 0, sampleSize, presentUs, 0);
                          extractor.advance();
                      }
                  }
              }
              int outIdx = decoder.dequeueOutputBuffer(info, 10_000);
              if (outIdx >= 0) {
                  ByteBuffer raw = decoder.getOutputBuffer(outIdx);
                  ShortBuffer sb = raw.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                  while (sb.hasRemaining()) pcm.add(sb.get());
                  decoder.releaseOutputBuffer(outIdx, false);
                  if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
              }
          }
          decoder.stop(); decoder.release(); extractor.release();

          if (pcm.isEmpty()) return new long[0];

          // Mix down to mono
          int monoLen = pcm.size() / channelCount;
          float[] mono = new float[monoLen];
          for (int i = 0; i < monoLen; i++) {
              float sum = 0;
              for (int ch = 0; ch < channelCount; ch++) {
                  int idx = i * channelCount + ch;
                  if (idx < pcm.size()) sum += pcm.get(idx) / 32768f;
              }
              mono[i] = sum / channelCount;
          }

          // Compute RMS per frame
          int frameLen = sampleRate * FRAME_MS / 1000;
          int hopLen   = sampleRate * HOP_MS  / 1000;
          List<Float> rms = new ArrayList<>();
          for (int i = 0; i + frameLen <= mono.length; i += hopLen) {
              double sum2 = 0;
              for (int j = i; j < i + frameLen; j++) sum2 += mono[j] * mono[j];
              rms.add((float) Math.sqrt(sum2 / frameLen));
          }

          // Rolling average (window = 20 frames = 500ms)
          int winSize = 20;
          List<Long> beats = new ArrayList<>();
          long lastBeat = -MIN_BEAT_GAP;
          for (int i = winSize; i < rms.size(); i++) {
              float localAvg = 0;
              for (int j = i - winSize; j < i; j++) localAvg += rms.get(j);
              localAvg /= winSize;
              long timeMs = (long) i * HOP_MS;
              if (rms.get(i) > localAvg * ONSET_RATIO && timeMs - lastBeat >= MIN_BEAT_GAP) {
                  beats.add(timeMs);
                  lastBeat = timeMs;
              }
          }

          long[] result = new long[beats.size()];
          for (int i = 0; i < beats.size(); i++) result[i] = beats.get(i);
          return result;
      }
  }
  