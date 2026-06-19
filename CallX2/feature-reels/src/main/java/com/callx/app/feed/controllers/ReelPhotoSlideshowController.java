package com.callx.app.feed.controllers;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.viewpager2.widget.ViewPager2;
import com.callx.app.models.ReelModel;
import com.callx.app.reels.R;
import com.callx.app.feed.ReelPhotoSlideshowAdapter;
import java.util.ArrayList;

/**
 * Manages photo slideshow mode: ViewPager2 setup, story-progress bar,
 * dot indicator, per-photo captions, pinch-to-zoom, and auto-advance timer.
 */
public class ReelPhotoSlideshowController {

    private final ReelPlayerDelegate delegate;

    // ── Owned views ───────────────────────────────────────────────────────
    private ViewPager2    vpPhotos;
    private LinearLayout  llStoryProgress;
    private TextView      btnPhotoStyle;
    private TextView      tvBpmBadge;
    private TextView      tvPhotoCounter;
    private TextView      tvPauseBadge;
    private TextView      tvCaptionOverlay;
    private LinearLayout  llDotIndicator;

    // ── Owned state ───────────────────────────────────────────────────────
    private boolean           isPhotoMode          = false;
    private boolean           photoSlideshowPaused = false;
    private ArrayList<String> photoUrls;
    private int               photoDurationMs      = 3000;
    private int               currentPhotoIndex    = 0;
    private float             photoScale           = 1f;

    private final Handler     photoHandler = new Handler(Looper.getMainLooper());
    private Runnable          photoAdvanceRunnable;
    private ObjectAnimator    storySegmentAnimator;
    private android.view.ScaleGestureDetector pinchDetector;

    public ReelPhotoSlideshowController(ReelPlayerDelegate delegate) {
        this.delegate = delegate;
    }

    // ── View binding ──────────────────────────────────────────────────────

    public void bindViews(View root) {
        vpPhotos        = root.findViewById(R.id.vp_photos);
        llStoryProgress = root.findViewById(R.id.ll_story_progress);
        tvPhotoCounter  = root.findViewById(R.id.tv_photo_counter);
        btnPhotoStyle   = root.findViewById(R.id.btn_photo_style);
        tvBpmBadge      = root.findViewById(R.id.tv_bpm_badge);
        tvPauseBadge    = root.findViewById(R.id.tv_pause_badge);
        tvCaptionOverlay = root.findViewById(R.id.tv_caption_overlay);
        llDotIndicator  = root.findViewById(R.id.ll_dot_indicator);
    }

    // ── Accessor ──────────────────────────────────────────────────────────

    public boolean isPhotoMode() { return isPhotoMode; }

    // ── Setup photo mode ─────────────────────────────────────────────────

    public void setupPhotoMode() {
        if (!delegate.isAdded() || delegate.getContext() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null || vpPhotos == null || llStoryProgress == null) return;

        photoUrls = reel.photoUrls != null
            ? new ArrayList<>(reel.photoUrls) : new ArrayList<>();
        if (photoUrls.isEmpty()) return;

        photoDurationMs   = reel.photoDurationMs > 0 ? reel.photoDurationMs : 3000;
        isPhotoMode       = true;
        currentPhotoIndex = 0;

        // Hide video player, show photo VP2
        View playerView = delegate.getFragment().getView() != null
            ? delegate.getFragment().getView().findViewById(R.id.player_view) : null;
        if (playerView != null) playerView.setVisibility(View.GONE);
        vpPhotos.setVisibility(View.VISIBLE);
        View progressVideo = delegate.getFragment().getView() != null
            ? delegate.getFragment().getView().findViewById(R.id.progress_video) : null;
        if (progressVideo != null) progressVideo.setVisibility(View.GONE);

        // Hide mute for photo-only reels
        View btnMute = delegate.getFragment().getView() != null
            ? delegate.getFragment().getView().findViewById(R.id.btn_mute) : null;
        if (btnMute != null && (reel.musicUrl == null || reel.musicUrl.isEmpty())) {
            btnMute.setVisibility(View.GONE);
        }

        // Adapter
        ReelPhotoSlideshowAdapter adapter = new ReelPhotoSlideshowAdapter(reel);
        adapter.setGlobalFilter(reel.photoFilter != null ? reel.photoFilter : "normal");
        vpPhotos.setAdapter(adapter);

        // Transition
        if (reel.transitionType == null || reel.transitionType.isEmpty()) reel.transitionType = "cube";
        if (reel.kenBurnsIntensity == null || reel.kenBurnsIntensity.isEmpty()) reel.kenBurnsIntensity = "cinematic";
        ViewPager2.PageTransformer transformer = ReelPhotoSlideshowAdapter.getPageTransformer(reel.transitionType);
        if (transformer != null) vpPhotos.setPageTransformer(transformer);

        // Story progress bar
        buildStoryProgress(photoUrls.size());
        llStoryProgress.setVisibility(View.VISIBLE);

        // Style picker
        if (btnPhotoStyle != null) {
            btnPhotoStyle.setVisibility(View.VISIBLE);
            btnPhotoStyle.setOnClickListener(ignored -> openTemplatePicker());
        }

        // BPM badge
        if (tvBpmBadge != null && reel.musicBpm > 0) {
            tvBpmBadge.setVisibility(View.VISIBLE);
            tvBpmBadge.setText(Math.round(reel.musicBpm) + " BPM");
        }

        // Dot indicator
        if (reel.showDotIndicator) buildDotIndicator(photoUrls.size());

        // Caption for first photo
        showCaptionForPhoto(0);

        // Pinch-to-zoom
        photoScale = 1f;
        pinchDetector = new android.view.ScaleGestureDetector(delegate.requireContext(),
            new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(android.view.ScaleGestureDetector det) {
                    photoScale = Math.max(1f, Math.min(photoScale * det.getScaleFactor(), 3.5f));
                    vpPhotos.setScaleX(photoScale);
                    vpPhotos.setScaleY(photoScale);
                    return true;
                }
            });

        // Photo counter
        if (tvPhotoCounter != null) {
            tvPhotoCounter.setVisibility(View.VISIBLE);
            tvPhotoCounter.setText("1 / " + photoUrls.size());
        }

        // Page change callback
        vpPhotos.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                currentPhotoIndex = position;
                if (photoScale != 1f) {
                    photoScale = 1f;
                    vpPhotos.setScaleX(1f);
                    vpPhotos.setScaleY(1f);
                }
                stopStorySegmentAnimation();
                animateStorySegment(position, photoDurationMs);
                if (tvPhotoCounter != null)
                    tvPhotoCounter.setText((position + 1) + " / " + photoUrls.size());
                updateDotIndicator(position);
                showCaptionForPhoto(position);
                stopPhotoSlideshow();
                startPhotoSlideshow();
            }
        });

        // Touch: tap-left/right nav + long-press pause
        GestureDetector gd = new GestureDetector(delegate.requireContext(),
            new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onSingleTapUp(MotionEvent e) {
                    if (vpPhotos == null || photoUrls == null) return false;
                    int screenW = delegate.requireContext().getResources().getDisplayMetrics().widthPixels;
                    float x = e.getRawX();
                    if (x < screenW * 0.35f) {
                        if (currentPhotoIndex > 0) {
                            currentPhotoIndex--;
                            vpPhotos.setCurrentItem(currentPhotoIndex, false);
                            stopPhotoSlideshow();
                            animateStorySegment(currentPhotoIndex, photoDurationMs);
                            if (tvPhotoCounter != null)
                                tvPhotoCounter.setText((currentPhotoIndex + 1) + " / " + photoUrls.size());
                            startPhotoSlideshow();
                        }
                    } else if (x > screenW * 0.65f) {
                        if (currentPhotoIndex < photoUrls.size() - 1) {
                            currentPhotoIndex++;
                            vpPhotos.setCurrentItem(currentPhotoIndex, false);
                            stopPhotoSlideshow();
                            animateStorySegment(currentPhotoIndex, photoDurationMs);
                            if (tvPhotoCounter != null)
                                tvPhotoCounter.setText((currentPhotoIndex + 1) + " / " + photoUrls.size());
                            startPhotoSlideshow();
                        }
                    }
                    return true;
                }

                @Override
                public void onLongPress(MotionEvent e) {
                    if (!photoSlideshowPaused) {
                        photoSlideshowPaused = true;
                        stopPhotoSlideshow();
                        if (storySegmentAnimator != null) storySegmentAnimator.pause();
                        if (tvPauseBadge != null) {
                            tvPauseBadge.setVisibility(View.VISIBLE);
                            tvPauseBadge.animate().alpha(1f).setDuration(150).start();
                        }
                    }
                }
            });

        vpPhotos.setOnTouchListener((v, event) -> {
            if (pinchDetector != null) pinchDetector.onTouchEvent(event);
            if (photoScale > 1.05f) {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    vpPhotos.animate().scaleX(1f).scaleY(1f).setDuration(200).start();
                    photoScale = 1f;
                }
                return true;
            }
            gd.onTouchEvent(event);
            if (event.getAction() == MotionEvent.ACTION_UP && photoSlideshowPaused) {
                photoSlideshowPaused = false;
                if (storySegmentAnimator != null) storySegmentAnimator.resume();
                startPhotoSlideshow();
                if (tvPauseBadge != null) {
                    tvPauseBadge.animate().alpha(0f).setDuration(200)
                        .withEndAction(() -> tvPauseBadge.setVisibility(View.GONE)).start();
                }
            }
            return false;
        });
    }

    // ── Story progress segments ───────────────────────────────────────────

    private void buildStoryProgress(int count) {
        if (llStoryProgress == null || !delegate.isAdded() || delegate.getContext() == null) return;
        llStoryProgress.removeAllViews();
        int marginPx = delegate.dpToPx(2);
        for (int i = 0; i < count; i++) {
            // Outer container (grey track)
            android.widget.FrameLayout track = new android.widget.FrameLayout(delegate.requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, delegate.dpToPx(2), 1f);
            lp.setMargins(marginPx, 0, marginPx, 0);
            track.setLayoutParams(lp);
            track.setBackgroundColor(0x55FFFFFF);

            // Inner fill (white progress)
            View fill = new View(delegate.requireContext());
            android.widget.FrameLayout.LayoutParams fp =
                new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            fill.setLayoutParams(fp);
            fill.setBackgroundColor(0xFFFFFFFF);
            fill.setScaleX(0f);
            fill.setPivotX(0f);
            track.addView(fill);
            llStoryProgress.addView(track);
        }
    }

    private View getStoryFill(int index) {
        if (llStoryProgress == null || index >= llStoryProgress.getChildCount()) return null;
        View track = llStoryProgress.getChildAt(index);
        if (!(track instanceof android.widget.FrameLayout)) return null;
        return ((android.widget.FrameLayout) track).getChildAt(0);
    }

    private void animateStorySegment(int index, int durationMs) {
        View fill = getStoryFill(index);
        if (fill == null) return;
        fill.setScaleX(0f);
        storySegmentAnimator = ObjectAnimator.ofFloat(fill, "scaleX", 0f, 1f);
        storySegmentAnimator.setDuration(durationMs);
        storySegmentAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        storySegmentAnimator.start();
        // Mark all previous segments as filled
        for (int i = 0; i < index; i++) {
            View f = getStoryFill(i);
            if (f != null) f.setScaleX(1f);
        }
        // Clear all future segments
        for (int i = index + 1; i < (llStoryProgress != null ? llStoryProgress.getChildCount() : 0); i++) {
            View f = getStoryFill(i);
            if (f != null) f.setScaleX(0f);
        }
    }

    private void stopStorySegmentAnimation() {
        if (storySegmentAnimator != null) {
            storySegmentAnimator.cancel();
            storySegmentAnimator = null;
        }
    }

    // ── Dot indicator ─────────────────────────────────────────────────────

    private void buildDotIndicator(int count) {
        if (llDotIndicator == null || !delegate.isAdded() || delegate.getContext() == null) return;
        llDotIndicator.removeAllViews();
        if (count <= 1) { llDotIndicator.setVisibility(View.GONE); return; }
        int dotPx    = delegate.dpToPx(7);
        int marginPx = delegate.dpToPx(4);
        for (int i = 0; i < count; i++) {
            View dot = new View(delegate.requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dotPx, dotPx);
            lp.setMargins(marginPx, 0, marginPx, 0);
            dot.setLayoutParams(lp);
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(i == 0 ? 0xFFFFFFFF : 0x66FFFFFF);
            dot.setBackground(gd);
            dot.setScaleX(i == 0 ? 1.4f : 1f);
            dot.setScaleY(i == 0 ? 1.4f : 1f);
            llDotIndicator.addView(dot);
        }
        llDotIndicator.setVisibility(View.VISIBLE);
    }

    private void updateDotIndicator(int active) {
        if (llDotIndicator == null) return;
        for (int i = 0; i < llDotIndicator.getChildCount(); i++) {
            View dot = llDotIndicator.getChildAt(i);
            if (dot.getBackground() instanceof GradientDrawable) {
                ((GradientDrawable) dot.getBackground()).setColor(i == active ? 0xFFFFFFFF : 0x66FFFFFF);
            }
            float scale = (i == active) ? 1.4f : 1f;
            dot.setScaleX(scale);
            dot.setScaleY(scale);
        }
    }

    // ── Per-photo caption ─────────────────────────────────────────────────

    private void showCaptionForPhoto(int index) {
        if (tvCaptionOverlay == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null) return;
        String caption = null;
        if (reel.photoCaptions != null && index < reel.photoCaptions.size()) {
            caption = reel.photoCaptions.get(index);
        }
        if (caption != null && !caption.isEmpty()) {
            tvCaptionOverlay.setVisibility(View.VISIBLE);
            tvCaptionOverlay.setText(caption);
        } else {
            tvCaptionOverlay.setVisibility(View.GONE);
        }
    }

    // ── Template picker ───────────────────────────────────────────────────

    private void openTemplatePicker() {
        if (!delegate.isAdded() || delegate.getActivity() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null) return;
        try {
            Class<?> cls = Class.forName("com.callx.app.editor.PhotoTemplatePickerActivity");
            Intent i = new Intent(delegate.getActivity(), cls);
            i.putExtra("reel_id", reel.reelId);
            delegate.getFragment().startActivity(i);
        } catch (ClassNotFoundException ignored) {}
    }

    // ── Slideshow timer ───────────────────────────────────────────────────

    public void startPhotoSlideshow() {
        if (!isPhotoMode || photoUrls == null || photoUrls.isEmpty()) return;
        if (photoSlideshowPaused) return;
        stopPhotoSlideshow();
        animateStorySegment(currentPhotoIndex, photoDurationMs);
        photoAdvanceRunnable = new Runnable() {
            @Override public void run() {
                if (!delegate.isAdded() || vpPhotos == null || photoUrls == null) return;
                if (currentPhotoIndex < photoUrls.size() - 1) {
                    currentPhotoIndex++;
                    vpPhotos.setCurrentItem(currentPhotoIndex, true);
                    if (tvPhotoCounter != null)
                        tvPhotoCounter.setText((currentPhotoIndex + 1) + " / " + photoUrls.size());
                    updateDotIndicator(currentPhotoIndex);
                    showCaptionForPhoto(currentPhotoIndex);
                    photoHandler.postDelayed(this, photoDurationMs);
                } else {
                    View lastFill = getStoryFill(photoUrls.size() - 1);
                    if (lastFill != null) lastFill.setScaleX(1f);
                    ReelModel reel = delegate.getReel();
                    if (reel != null && reel.autoLoop) {
                        photoHandler.postDelayed(() -> {
                            if (!delegate.isAdded() || vpPhotos == null || photoUrls == null) return;
                            currentPhotoIndex = 0;
                            vpPhotos.setCurrentItem(0, false);
                            buildStoryProgress(photoUrls.size());
                            if (llStoryProgress != null) llStoryProgress.setVisibility(View.VISIBLE);
                            if (tvPhotoCounter != null)
                                tvPhotoCounter.setText("1 / " + photoUrls.size());
                            updateDotIndicator(0);
                            showCaptionForPhoto(0);
                            startPhotoSlideshow();
                        }, 500);
                    } else {
                        photoHandler.postDelayed(() -> {
                            if (delegate.isAdded()) delegate.autoAdvance();
                        }, 400);
                    }
                }
            }
        };
        photoHandler.postDelayed(photoAdvanceRunnable, photoDurationMs);
    }

    public void stopPhotoSlideshow() {
        if (photoAdvanceRunnable != null) {
            photoHandler.removeCallbacks(photoAdvanceRunnable);
            photoAdvanceRunnable = null;
        }
        stopStorySegmentAnimation();
    }
}
