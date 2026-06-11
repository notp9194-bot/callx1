package com.callx.app.views;

  import android.animation.ValueAnimator;
  import android.content.Context;
  import android.graphics.*;
  import android.util.AttributeSet;
  import android.view.View;
  import android.view.animation.OvershootInterpolator;
  import java.util.Arrays;

  /**
   * BeatSyncOverlayView — Visualises beat markers over the DuetReelActivity recording screen.
   *
   * Shows:
   *  • A horizontal timeline at the bottom with tick marks at each beat position
   *  • A "pulse ring" animation centred on the next upcoming beat tick
   *  • The current playback cursor (vertical line) moves left-to-right with ExoPlayer position
   *  • On beat hit: brief flash + haptic (caller must call onBeat() at the right time)
   *
   * Integration in DuetReelActivity:
   *  1. Add <com.callx.app.views.BeatSyncOverlayView> on top of the duet layout
   *  2. After BeatSyncAnalyzer delivers beats, call beatOverlay.setBeats(beatTimesMs, durationMs)
   *  3. Every 100ms (Handler), call beatOverlay.setPosition(exoPlayer.getCurrentPosition())
   *  4. Listen for beatOverlay.setOnBeatListener() to trigger haptic feedback
   */
  public class BeatSyncOverlayView extends View {

      public interface OnBeatListener { void onBeat(long beatTimeMs); }

      private long[]   beatTimes   = new long[0];
      private long     duration    = 1L;
      private long     currentPos  = 0L;

      private final Paint beatPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
      private final Paint cursorPaint= new Paint(Paint.ANTI_ALIAS_FLAG);
      private final Paint flashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
      private final Paint bgPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
      private final RectF timelineRect = new RectF();

      private float flashAlpha  = 0f;
      private float pulseRadius = 0f;
      private int   nextBeatIdx = 0;

      private OnBeatListener beatListener;

      public BeatSyncOverlayView(Context ctx) { super(ctx); init(); }
      public BeatSyncOverlayView(Context ctx, AttributeSet a) { super(ctx, a); init(); }

      private void init() {
          beatPaint.setColor(0xFFFFD700);  // gold ticks
          beatPaint.setStrokeWidth(4f);
          beatPaint.setStyle(Paint.Style.FILL_AND_STROKE);

          cursorPaint.setColor(0xFFFFFFFF);
          cursorPaint.setStrokeWidth(3f);
          cursorPaint.setAlpha(200);

          flashPaint.setColor(0xFFFFD700);
          flashPaint.setStyle(Paint.Style.STROKE);
          flashPaint.setStrokeWidth(6f);

          bgPaint.setColor(0x88000000);
      }

      public void setBeats(long[] beatTimesMs, long durationMs) {
          this.beatTimes = beatTimesMs != null ? beatTimesMs : new long[0];
          this.duration  = durationMs > 0 ? durationMs : 1L;
          nextBeatIdx    = 0;
          invalidate();
      }

      public void setPosition(long posMs) {
          this.currentPos = posMs;
          checkBeatTrigger(posMs);
          invalidate();
      }

      public void setOnBeatListener(OnBeatListener l) { this.beatListener = l; }

      private void checkBeatTrigger(long posMs) {
          if (beatTimes.length == 0) return;
          while (nextBeatIdx < beatTimes.length && posMs >= beatTimes[nextBeatIdx] - 40) {
              long beatTime = beatTimes[nextBeatIdx];
              if (Math.abs(posMs - beatTime) < 80) {
                  triggerBeatAnimation();
                  if (beatListener != null) beatListener.onBeat(beatTime);
              }
              nextBeatIdx++;
          }
      }

      private void triggerBeatAnimation() {
          ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
          anim.setDuration(400);
          anim.setInterpolator(new OvershootInterpolator());
          anim.addUpdateListener(a -> {
              float f = (float) a.getAnimatedValue();
              flashAlpha  = f < 0.5f ? f * 2f : 2f - f * 2f;
              pulseRadius = f * 60f;
              invalidate();
          });
          anim.start();
      }

      @Override
      protected void onDraw(Canvas canvas) {
          super.onDraw(canvas);
          int w = getWidth(), h = getHeight();
          int tlH = 48; // timeline height px

          // Timeline background
          timelineRect.set(0, h - tlH, w, h);
          canvas.drawRect(timelineRect, bgPaint);

          if (duration <= 0) return;

          // Beat ticks
          for (long t : beatTimes) {
              float x = t / (float) duration * w;
              canvas.drawCircle(x, h - tlH / 2f, 5f, beatPaint);
          }

          // Cursor
          float cx = currentPos / (float) duration * w;
          canvas.drawLine(cx, h - tlH, cx, h, cursorPaint);

          // Pulse ring at next beat
          if (nextBeatIdx < beatTimes.length) {
              float nx = beatTimes[nextBeatIdx] / (float) duration * w;
              flashPaint.setAlpha((int)(flashAlpha * 200));
              canvas.drawCircle(nx, h - tlH / 2f, 10f + pulseRadius, flashPaint);
          }
      }
  }
  