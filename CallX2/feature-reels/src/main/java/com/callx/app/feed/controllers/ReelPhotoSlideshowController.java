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
import android.view.animation.DecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import com.callx.app.models.ReelModel;
import com.callx.app.reels.R;
import com.callx.app.feed.ReelPhotoSlideshowAdapter;
import com.callx.app.feed.ReelsFragment;
import androidx.fragment.app.Fragment;
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
    private TextView      btnPhotoStyle; // ViewStub-backed (stub_photo_style)
    private TextView      tvBpmBadge;    // ViewStub-backed (stub_bpm_badge)
    private TextView      tvPhotoCounter;
    private TextView      tvPauseBadge;
    private TextView      tvCaptionOverlay;
    private LinearLayout  llDotIndicator;
    private View          rootView; // kept for lazy ViewStub inflation

    // ── Owned state ───────────────────────────────────────────────────────
    private boolean           isPhotoMode          = false;
    private boolean           photoSlideshowPaused = false;
    private ArrayList<String> photoUrls;
    private int               photoDurationMs      = 3000;
    private int               currentPhotoIndex    = 0;
    private float             photoScale           = 1f;

    // ── Instagram-level touch tracking ────────────────────────────────────
    private float             touchDownX           = 0f;
    private float             touchDownY           = 0f;

    private final Handler     photoHandler = new Handler(Looper.getMainLooper());
    private Runnable          photoAdvanceRunnable;
    private ObjectAnimator    storySegmentAnimator;
    private android.view.ScaleGestureDetector pinchDetector;
    private ReelPhotoSlideshowAdapter photoAdapter;

    public ReelPhotoSlideshowController(ReelPlayerDelegate delegate) {
        this.delegate = delegate;
    }

    // ── View binding ──────────────────────────────────────────────────────

    public void bindViews(View root) {
        rootView        = root;
        vpPhotos        = root.findViewById(R.id.vp_photos);
        llStoryProgress = root.findViewById(R.id.ll_story_progress);
        tvPhotoCounter  = root.findViewById(R.id.tv_photo_counter);
        // btnPhotoStyle / tvBpmBadge are ViewStub-backed — NOT inflated here.
        // Only photo-slideshow reels need them, so they're lazily inflated in
        // bindReel() below instead of on every reel bind.
        tvPauseBadge    = root.findViewById(R.id.tv_pause_badge);
        tvCaptionOverlay = root.findViewById(R.id.tv_caption_overlay);
        llDotIndicator  = root.findViewById(R.id.ll_dot_indicator);
    }

    // ── Accessor ──────────────────────────────────────────────────────────

    public boolean isPhotoMode() { return isPhotoMode; }

    /** True when the current reel is a photo slideshow with 2+ photos —
     *  i.e. when left/right swipe actually has something to navigate,
     *  which is exactly when the outer tab pager's swipe must stay locked. */
    public boolean hasMultiplePhotos() {
        return isPhotoMode && photoUrls != null && photoUrls.size() > 1;
    }

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

        // Hide mute button for ALL photo reels (music or no music) — top-left
        // mute toggle isn't needed here, keeps the top row clean for photo mode.
        View btnMute = delegate.getFragment().getView() != null
            ? delegate.getFragment().getView().findViewById(R.id.btn_mute) : null;
        if (btnMute != null) {
            btnMute.setVisibility(View.GONE);
        }

        // Adapter
        ReelPhotoSlideshowAdapter adapter = new ReelPhotoSlideshowAdapter(reel);
        adapter.setGlobalFilter(reel.photoFilter != null ? reel.photoFilter : "normal");
        // Sticker tap-to-zoom gate reuses this same pause/resume path as long-press.
        adapter.setPhotoInteractionListener(new ReelPhotoSlideshowAdapter.PhotoInteractionListener() {
            @Override public void onLongPressStateChanged(boolean isPaused) {
                if (isPaused) {
                    photoSlideshowPaused = true;
                    stopPhotoSlideshow();
                    if (storySegmentAnimator != null) storySegmentAnimator.pause();
                    if (tvPauseBadge != null) tvPauseBadge.setVisibility(View.GONE); // sticker zoom has its own scrim; skip the ⏸ badge
                } else {
                    photoSlideshowPaused = false;
                    if (storySegmentAnimator != null) storySegmentAnimator.resume();
                    startPhotoSlideshow();
                }
            }
            @Override public void onPhotoSwipedByUser(int newIndex) {}
            @Override public void onAutoAdvanceTick(int fromIndex) {}
        });
        vpPhotos.setAdapter(adapter);
        photoAdapter = adapter;

        // ── Instagram-level swipe sensitivity ─────────────────────────────
        // Reduce the touch slop on ViewPager2's internal RecyclerView so that
        // even a very short finger movement is recognised as a photo swipe —
        // identical to how Instagram's carousel behaves.
        try {
            android.view.View innerRv = vpPhotos.getChildAt(0);
            if (innerRv instanceof androidx.recyclerview.widget.RecyclerView) {
                java.lang.reflect.Field slopField =
                    androidx.recyclerview.widget.RecyclerView.class.getDeclaredField("mTouchSlop");
                slopField.setAccessible(true);
                int currentSlop = (int) slopField.get(innerRv);
                // Half the system slop — matches Instagram carousel sensitivity
                slopField.set(innerRv, Math.max(1, currentSlop / 2));
            }
        } catch (Exception ignored) {}

        // Transition
        if (reel.transitionType == null || reel.transitionType.isEmpty()) reel.transitionType = "cube";
        if (reel.kenBurnsIntensity == null || reel.kenBurnsIntensity.isEmpty()) reel.kenBurnsIntensity = "cinematic";
        ViewPager2.PageTransformer transformer = ReelPhotoSlideshowAdapter.getPageTransformer(reel.transitionType);
        if (transformer != null) vpPhotos.setPageTransformer(transformer);

        // Story progress bar
        buildStoryProgress(photoUrls.size());
        llStoryProgress.setVisibility(View.VISIBLE);

        // Style picker — inflate stub_photo_style on first need
        if (btnPhotoStyle == null && rootView != null) {
            View stub = rootView.findViewById(R.id.stub_photo_style);
            if (stub instanceof android.view.ViewStub) {
                btnPhotoStyle = (TextView) ((android.view.ViewStub) stub).inflate();
            }
        }
        if (btnPhotoStyle != null) {
            btnPhotoStyle.setVisibility(View.VISIBLE);
            btnPhotoStyle.setOnClickListener(ignored -> openTemplatePicker());
        }

        // BPM badge — inflate stub_bpm_badge only when there's actually a BPM to show
        if (reel.musicBpm > 0) {
            if (tvBpmBadge == null && rootView != null) {
                View stub = rootView.findViewById(R.id.stub_bpm_badge);
                if (stub instanceof android.view.ViewStub) {
                    tvBpmBadge = (TextView) ((android.view.ViewStub) stub).inflate();
                }
            }
            if (tvBpmBadge != null) {
                tvBpmBadge.setVisibility(View.VISIBLE);
                tvBpmBadge.setText(Math.round(reel.musicBpm) + " BPM");
            }
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
                    } else {
                        // ── Center tap = play/pause, same as video reels ────────
                        // Video reels toggle play/pause on a center single-tap via
                        // togglePlayPause(), which flips isPlaying and forwards that
                        // to ReelsFragment.onReelPlaybackStateChanged() — that's what
                        // hides/shows the bottom nav + top bar. Photo reels had no
                        // equivalent center-tap action, so that bridge never fired
                        // and the bottom nav stayed put during photo playback.
                        // Mirror the same toggle + notify here.
                        togglePhotoPlayPause();
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

            // ── Instagram-level parent interception control ────────────────
            // Fix: when a photo reel has multiple photos, left/right swipes
            // must be owned by the inner ViewPager2 (vpPhotos), NOT by the
            // parent tab ViewPager2.  We call requestDisallowInterceptTouchEvent
            // so the parent never gets a chance to steal horizontal drags and
            // change tabs mid-swipe.
            final int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    touchDownX = event.getRawX();
                    touchDownY = event.getRawY();
                    // Immediately claim the gesture when there are multiple
                    // photos — Instagram does this so the very first pixel of
                    // horizontal movement starts the photo swipe, not a tab change.
                    if (photoUrls != null && photoUrls.size() > 1) {
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    break;

                case MotionEvent.ACTION_MOVE: {
                    float dx = Math.abs(event.getRawX() - touchDownX);
                    float dy = Math.abs(event.getRawY() - touchDownY);
                    if (dx >= dy) {
                        // Horizontal intent — keep blocking parent so photos swipe
                        if (photoUrls != null && photoUrls.size() > 1) {
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                    } else if (dy > dx * 1.5f) {
                        // Clearly vertical — give control back so reel
                        // up/down feed scroll still works normally
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    break;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    break;
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

    /**
     * Center-tap play/pause for photo reels — mirrors video reels'
     * togglePlayPause(). Pauses/resumes the slideshow auto-advance + story
     * segment animation, shows the same ⏸ badge used by long-press, and
     * forwards the state to ReelsFragment.onReelPlaybackStateChanged() so
     * the bottom nav / top bar hide-on-play behavior works identically to
     * video reels instead of only reacting to ExoPlayer's own callback.
     */
    private void togglePhotoPlayPause() {
        photoSlideshowPaused = !photoSlideshowPaused;

        if (photoSlideshowPaused) {
            stopPhotoSlideshow();
            if (storySegmentAnimator != null) storySegmentAnimator.pause();
            if (tvPauseBadge != null) {
                tvPauseBadge.setVisibility(View.VISIBLE);
                tvPauseBadge.animate().alpha(1f).setDuration(150).start();
            }
        } else {
            if (storySegmentAnimator != null) storySegmentAnimator.resume();
            startPhotoSlideshow();
            if (tvPauseBadge != null) {
                tvPauseBadge.animate().alpha(0f).setDuration(200)
                    .withEndAction(() -> tvPauseBadge.setVisibility(View.GONE)).start();
            }
        }

        // Same visibility bridge ReelPlayerController uses for video —
        // isPlaying == !photoSlideshowPaused.
        if (delegate.isCurrentlyVisible() && !delegate.isDocked()) {
            Fragment parent = delegate.getParentFragment();
            if (parent instanceof ReelsFragment) {
                ((ReelsFragment) parent).onReelPlaybackStateChanged(!photoSlideshowPaused);
            }
        }
    }

    private void buildStoryProgress(int count) {
        if (llStoryProgress == null || !delegate.isAdded() || delegate.getContext() == null) return;
        llStoryProgress.removeAllViews();
        // Instagram-style: 4dp height, 2dp corner radius, 2dp gap between segments
        int marginPx = delegate.dpToPx(2);
        for (int i = 0; i < count; i++) {
            // Outer track — semi-transparent white rounded pill
            android.widget.FrameLayout track = new android.widget.FrameLayout(delegate.requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, delegate.dpToPx(4), 1f);
            lp.setMargins(marginPx, 0, marginPx, 0);
            track.setLayoutParams(lp);
            GradientDrawable trackBg = new GradientDrawable();
            trackBg.setShape(GradientDrawable.RECTANGLE);
            trackBg.setCornerRadius(delegate.dpToPx(2));
            trackBg.setColor(0x50FFFFFF);
            track.setBackground(trackBg);
            track.setClipToOutline(true);

            // Inner fill — solid white rounded pill
            View fill = new View(delegate.requireContext());
            android.widget.FrameLayout.LayoutParams fp =
                new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            fill.setLayoutParams(fp);
            GradientDrawable fillBg = new GradientDrawable();
            fillBg.setShape(GradientDrawable.RECTANGLE);
            fillBg.setCornerRadius(delegate.dpToPx(2));
            fillBg.setColor(0xFFFFFFFF);
            fill.setBackground(fillBg);
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
        // Instagram-style: active = wide white pill (20x8dp), inactive = circle (8x8dp)
        int circlePx = delegate.dpToPx(8);
        int pillW    = delegate.dpToPx(20);
        int marginPx = delegate.dpToPx(3);
        for (int i = 0; i < count; i++) {
            View dot = new View(delegate.requireContext());
            boolean isActive = (i == 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                isActive ? pillW : circlePx, circlePx);
            lp.setMargins(marginPx, 0, marginPx, 0);
            dot.setLayoutParams(lp);
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.RECTANGLE);
            gd.setCornerRadius(circlePx / 2f);
            gd.setColor(isActive ? 0xFFFFFFFF : 0x66FFFFFF);
            dot.setBackground(gd);
            llDotIndicator.addView(dot);
        }
        llDotIndicator.setVisibility(View.VISIBLE);
    }

    private void updateDotIndicator(int active) {
        if (llDotIndicator == null) return;
        int circlePx = delegate.dpToPx(8);
        int pillW    = delegate.dpToPx(20);
        int marginPx = delegate.dpToPx(3);
        for (int i = 0; i < llDotIndicator.getChildCount(); i++) {
            View dot = llDotIndicator.getChildAt(i);
            boolean isActive = (i == active);
            // Animate width transition: pill -> circle -> pill
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) dot.getLayoutParams();
            int targetW = isActive ? pillW : circlePx;
            if (lp.width != targetW) {
                lp.width = targetW;
                dot.setLayoutParams(lp);
            }
            if (dot.getBackground() instanceof GradientDrawable) {
                GradientDrawable gd = (GradientDrawable) dot.getBackground();
                gd.setColor(isActive ? 0xFFFFFFFF : 0x66FFFFFF);
            }
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
        // Same guard as ReelUiController — photoCaptions come straight off the
        // Firebase POJO (reflection-set, bypasses ReelModel's constructor
        // truncation), so cap here right before it hits the TextView.
        caption = com.callx.app.models.ReelModel.safeCaption(caption);
        if (caption != null && !caption.isEmpty()) {
            tvCaptionOverlay.setText(caption);
            if (tvCaptionOverlay.getVisibility() != View.VISIBLE) {
                tvCaptionOverlay.setVisibility(View.VISIBLE);
                tvCaptionOverlay.setAlpha(0f);
                tvCaptionOverlay.setTranslationY(20f * delegate.requireContext().getResources().getDisplayMetrics().density);
            }
            // Instagram-style slide-up + fade-in with decelerate interpolator
            tvCaptionOverlay.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(280)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                .start();
        } else {
            if (tvCaptionOverlay.getVisibility() == View.VISIBLE) {
                tvCaptionOverlay.animate()
                    .alpha(0f)
                    .translationY(12f * delegate.requireContext().getResources().getDisplayMetrics().density)
                    .setDuration(180)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(() -> tvCaptionOverlay.setVisibility(View.GONE))
                    .start();
            }
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

    /** Called by the fragment's onResume() — shrinks back a sticker left zoomed
     *  when the viewer navigated off to an external profile/hashtag/link/sheet. */
    public void settleAnyZoomedSticker() {
        if (photoAdapter != null) photoAdapter.settleAnyZoomedSticker();
    }

    public void stopPhotoSlideshow() {
        if (photoAdvanceRunnable != null) {
            photoHandler.removeCallbacks(photoAdvanceRunnable);
            photoAdvanceRunnable = null;
        }
        stopStorySegmentAnimation();
    }
}
