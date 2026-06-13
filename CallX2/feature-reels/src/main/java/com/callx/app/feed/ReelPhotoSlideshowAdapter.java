package com.callx.app.feed;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.callx.app.reels.R;

import java.util.List;

/**
 * ReelPhotoSlideshowAdapter — drives the photo ViewPager2 inside ReelPlayerFragment.
 *
 * Advanced features v2:
 *  ✅ Ken Burns pan+zoom effect on each photo after it loads
 *  ✅ Supports transitionType: "fade" | "slide" | "zoom" | "none"
 *  ✅ Static PageTransformer factory: getPageTransformer(type)
 */
public class ReelPhotoSlideshowAdapter extends RecyclerView.Adapter<ReelPhotoSlideshowAdapter.PhotoVH> {

    private final List<String> photoUrls;
    private int photoDurationMs = 3000;

    public ReelPhotoSlideshowAdapter(List<String> photoUrls) {
        this.photoUrls = photoUrls;
    }

    public void setPhotoDurationMs(int ms) {
        this.photoDurationMs = ms;
    }

    @NonNull
    @Override
    public PhotoVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_reel_photo_slide, parent, false);
        return new PhotoVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull PhotoVH holder, int position) {
        // Reset any previous Ken Burns animation
        holder.ivPhoto.clearAnimation();
        holder.ivPhoto.setScaleX(1f);
        holder.ivPhoto.setScaleY(1f);
        holder.ivPhoto.setTranslationX(0f);
        holder.ivPhoto.setTranslationY(0f);

        String url = photoUrls.get(position);
        Glide.with(holder.ivPhoto.getContext())
                .load(url)
                .centerCrop()
                .placeholder(android.R.color.black)
                .listener(new RequestListener<android.graphics.drawable.Drawable>() {
                    @Override
                    public boolean onLoadFailed(GlideException e, Object model,
                                                Target<android.graphics.drawable.Drawable> target,
                                                boolean isFirstResource) {
                        return false;
                    }
                    @Override
                    public boolean onResourceReady(android.graphics.drawable.Drawable resource,
                                                   Object model,
                                                   Target<android.graphics.drawable.Drawable> target,
                                                   DataSource dataSource, boolean isFirstResource) {
                        startKenBurns(holder.ivPhoto, photoDurationMs);
                        return false;
                    }
                })
                .into(holder.ivPhoto);
    }

    @Override
    public void onViewRecycled(@NonNull PhotoVH holder) {
        super.onViewRecycled(holder);
        Object tag = holder.ivPhoto.getTag();
        if (tag instanceof AnimatorSet) ((AnimatorSet) tag).cancel();
        holder.ivPhoto.setScaleX(1f);
        holder.ivPhoto.setScaleY(1f);
        holder.ivPhoto.setTranslationX(0f);
        holder.ivPhoto.setTranslationY(0f);
    }

    @Override
    public int getItemCount() {
        return photoUrls != null ? photoUrls.size() : 0;
    }

    // ── Ken Burns ──────────────────────────────────────────────────────────

    private void startKenBurns(ImageView iv, long durationMs) {
        iv.setScaleX(1f);
        iv.setScaleY(1f);
        iv.setTranslationX(0f);
        iv.setTranslationY(0f);

        // Alternating direction per invocation (pseudo-random from time)
        long seed = System.nanoTime();
        float dx = ((seed & 1) == 0 ? 1f : -1f) * 22f;
        float dy = ((seed & 2) == 0 ? 1f : -1f) * 14f;
        float scale = 1.14f;

        ObjectAnimator sX = ObjectAnimator.ofFloat(iv, "scaleX",    1.0f,  scale);
        ObjectAnimator sY = ObjectAnimator.ofFloat(iv, "scaleY",    1.0f,  scale);
        ObjectAnimator tX = ObjectAnimator.ofFloat(iv, "translationX", 0f, dx);
        ObjectAnimator tY = ObjectAnimator.ofFloat(iv, "translationY", 0f, dy);

        AnimatorSet set = new AnimatorSet();
        set.playTogether(sX, sY, tX, tY);
        set.setDuration(Math.max(durationMs + 600L, 3600L));
        set.setInterpolator(new AccelerateDecelerateInterpolator());
        set.start();
        iv.setTag(set);
    }

    // ── PageTransformer factory ────────────────────────────────────────────

    /**
     * Returns the correct ViewPager2.PageTransformer for the given transitionType string.
     * Pass to vpPhotos.setPageTransformer().
     * Returns null for "none" (default VP2 slide animation).
     */
    public static ViewPager2.PageTransformer getPageTransformer(String type) {
        if (type == null) return new FadePageTransformer();
        switch (type) {
            case "zoom":  return new ZoomOutPageTransformer();
            case "slide": return new DepthPageTransformer();
            case "none":  return null;
            default:      return new FadePageTransformer();
        }
    }

    // ── Fade transformer ───────────────────────────────────────────────────

    public static class FadePageTransformer implements ViewPager2.PageTransformer {
        @Override
        public void transformPage(@NonNull View page, float position) {
            if (position < -1f || position > 1f) {
                page.setAlpha(0f);
            } else {
                float alpha = 1f - Math.abs(position);
                page.setAlpha(alpha);
                page.setTranslationX(-position * page.getWidth());
            }
        }
    }

    // ── Zoom-out transformer (Instagram carousel style) ────────────────────

    public static class ZoomOutPageTransformer implements ViewPager2.PageTransformer {
        private static final float MIN_SCALE = 0.85f;
        private static final float MIN_ALPHA = 0.5f;
        @Override
        public void transformPage(@NonNull View page, float position) {
            if (position < -1f || position > 1f) {
                page.setAlpha(0f);
            } else {
                float scale  = Math.max(MIN_SCALE, 1f - Math.abs(position) * (1f - MIN_SCALE));
                page.setScaleX(scale);
                page.setScaleY(scale);
                float alpha = MIN_ALPHA + (scale - MIN_SCALE) / (1f - MIN_SCALE) * (1f - MIN_ALPHA);
                page.setAlpha(alpha);
            }
        }
    }

    // ── Depth transformer (slide-out / zoom-in) ────────────────────────────

    public static class DepthPageTransformer implements ViewPager2.PageTransformer {
        @Override
        public void transformPage(@NonNull View page, float position) {
            if (position < -1f) {
                page.setAlpha(0f);
            } else if (position <= 0f) {
                page.setAlpha(1f);
                page.setTranslationX(0f);
                page.setScaleX(1f);
                page.setScaleY(1f);
            } else if (position <= 1f) {
                page.setAlpha(1f - position);
                page.setTranslationX(-page.getWidth() * position);
                float s = 0.75f + (1f - position) * 0.25f;
                page.setScaleX(s);
                page.setScaleY(s);
            } else {
                page.setAlpha(0f);
            }
        }
    }

    // ── ViewHolder ─────────────────────────────────────────────────────────

    static class PhotoVH extends RecyclerView.ViewHolder {
        final ImageView ivPhoto;
        PhotoVH(@NonNull View itemView) {
            super(itemView);
            ivPhoto = itemView.findViewById(R.id.iv_photo_slide);
        }
    }
}
