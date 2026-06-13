package com.callx.app.feed;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
 * Advance Level v3 features:
 *  ✅ Ken Burns pan+zoom (ObjectAnimator after Glide load)
 *  ✅ Transition PageTransformers: fade / zoomOut / depth / none
 *  ✅ Photo filters via ColorMatrix: normal / warm / cool / vivid / bw
 *  ✅ setFilter() hot-swaps the filter live (notifyDataSetChanged)
 */
public class ReelPhotoSlideshowAdapter extends RecyclerView.Adapter<ReelPhotoSlideshowAdapter.PhotoVH> {

    private final List<String> photoUrls;
    private int    photoDurationMs = 3000;
    private String photoFilter     = "normal";

    public ReelPhotoSlideshowAdapter(List<String> photoUrls) {
        this.photoUrls = photoUrls;
    }

    public void setPhotoDurationMs(int ms) {
        this.photoDurationMs = ms;
    }

    /** Change the named color filter and rebind all visible pages. */
    public void setFilter(String filter) {
        this.photoFilter = (filter != null) ? filter : "normal";
        notifyDataSetChanged();
    }

    // ── RecyclerView.Adapter ─────────────────────────────────────────────────

    @NonNull
    @Override
    public PhotoVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_reel_photo_slide, parent, false);
        return new PhotoVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull PhotoVH holder, int position) {
        // Cancel any leftover Ken Burns animator from a recycled view
        Object oldTag = holder.ivPhoto.getTag();
        if (oldTag instanceof AnimatorSet) ((AnimatorSet) oldTag).cancel();
        holder.ivPhoto.setScaleX(1f);
        holder.ivPhoto.setScaleY(1f);
        holder.ivPhoto.setTranslationX(0f);
        holder.ivPhoto.setTranslationY(0f);

        // Apply color filter before image loads (instant feedback)
        holder.ivPhoto.setColorFilter(buildColorFilter(photoFilter));

        Glide.with(holder.ivPhoto.getContext())
                .load(photoUrls.get(position))
                .centerCrop()
                .placeholder(android.R.color.black)
                .listener(new RequestListener<android.graphics.drawable.Drawable>() {
                    @Override
                    public boolean onLoadFailed(GlideException e, Object model,
                                                Target<android.graphics.drawable.Drawable> t,
                                                boolean first) {
                        return false;
                    }
                    @Override
                    public boolean onResourceReady(android.graphics.drawable.Drawable res,
                                                   Object model,
                                                   Target<android.graphics.drawable.Drawable> t,
                                                   DataSource src, boolean first) {
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
        holder.ivPhoto.clearColorFilter();
    }

    @Override
    public int getItemCount() {
        return photoUrls != null ? photoUrls.size() : 0;
    }

    // ── Ken Burns pan+zoom ────────────────────────────────────────────────────

    private void startKenBurns(ImageView iv, long durationMs) {
        iv.setScaleX(1f); iv.setScaleY(1f);
        iv.setTranslationX(0f); iv.setTranslationY(0f);

        long seed = System.nanoTime();
        float dx = ((seed & 1L) == 0 ? 1f : -1f) * 22f;
        float dy = ((seed & 2L) == 0 ? 1f : -1f) * 14f;

        ObjectAnimator sX = ObjectAnimator.ofFloat(iv, "scaleX",      1.0f, 1.14f);
        ObjectAnimator sY = ObjectAnimator.ofFloat(iv, "scaleY",      1.0f, 1.14f);
        ObjectAnimator tX = ObjectAnimator.ofFloat(iv, "translationX", 0f,  dx);
        ObjectAnimator tY = ObjectAnimator.ofFloat(iv, "translationY", 0f,  dy);

        AnimatorSet set = new AnimatorSet();
        set.playTogether(sX, sY, tX, tY);
        set.setDuration(Math.max(durationMs + 600L, 3600L));
        set.setInterpolator(new AccelerateDecelerateInterpolator());
        set.start();
        iv.setTag(set);
    }

    // ── Color filter builder ─────────────────────────────────────────────────

    @Nullable
    private static ColorMatrixColorFilter buildColorFilter(String filter) {
        if (filter == null || "normal".equals(filter)) return null;
        ColorMatrix cm = new ColorMatrix();
        switch (filter) {
            case "warm":
                // Boost reds & warm tones, cool down blues
                cm.set(new float[]{
                    1.20f, 0f,    0f,    0f, 15f,
                    0f,    1.05f, 0f,    0f,  5f,
                    0f,    0f,    0.85f, 0f,-15f,
                    0f,    0f,    0f,    1f,  0f
                });
                break;
            case "cool":
                // Boost blues, tone down reds
                cm.set(new float[]{
                    0.85f, 0f, 0f,    0f,-15f,
                    0f,    1f, 0f,    0f,  0f,
                    0f,    0f, 1.20f, 0f, 20f,
                    0f,    0f, 0f,    1f,  0f
                });
                break;
            case "vivid":
                cm.setSaturation(1.9f);
                break;
            case "bw":
                cm.setSaturation(0f);
                break;
            default:
                return null;
        }
        return new ColorMatrixColorFilter(cm);
    }

    // ── PageTransformer factory ───────────────────────────────────────────────

    /** Returns the PageTransformer for the given transitionType, or null for "none". */
    @Nullable
    public static ViewPager2.PageTransformer getPageTransformer(String type) {
        if (type == null) return new FadePageTransformer();
        switch (type) {
            case "zoom":  return new ZoomOutPageTransformer();
            case "slide": return new DepthPageTransformer();
            case "none":  return null;
            default:      return new FadePageTransformer();
        }
    }

    // ── Fade ─────────────────────────────────────────────────────────────────

    public static class FadePageTransformer implements ViewPager2.PageTransformer {
        @Override
        public void transformPage(@NonNull View page, float pos) {
            if (pos < -1f || pos > 1f) {
                page.setAlpha(0f);
            } else {
                page.setAlpha(1f - Math.abs(pos));
                page.setTranslationX(-pos * page.getWidth());
            }
        }
    }

    // ── Zoom-out ─────────────────────────────────────────────────────────────

    public static class ZoomOutPageTransformer implements ViewPager2.PageTransformer {
        private static final float MIN_SCALE = 0.85f;
        private static final float MIN_ALPHA = 0.5f;
        @Override
        public void transformPage(@NonNull View page, float pos) {
            if (pos < -1f || pos > 1f) {
                page.setAlpha(0f);
            } else {
                float scale = Math.max(MIN_SCALE, 1f - Math.abs(pos) * (1f - MIN_SCALE));
                page.setScaleX(scale);
                page.setScaleY(scale);
                page.setAlpha(MIN_ALPHA + (scale - MIN_SCALE) / (1f - MIN_SCALE) * (1f - MIN_ALPHA));
            }
        }
    }

    // ── Depth ────────────────────────────────────────────────────────────────

    public static class DepthPageTransformer implements ViewPager2.PageTransformer {
        @Override
        public void transformPage(@NonNull View page, float pos) {
            if (pos < -1f) {
                page.setAlpha(0f);
            } else if (pos <= 0f) {
                page.setAlpha(1f); page.setTranslationX(0f);
                page.setScaleX(1f); page.setScaleY(1f);
            } else if (pos <= 1f) {
                page.setAlpha(1f - pos);
                page.setTranslationX(-page.getWidth() * pos);
                float s = 0.75f + (1f - pos) * 0.25f;
                page.setScaleX(s); page.setScaleY(s);
            } else {
                page.setAlpha(0f);
            }
        }
    }

    // ── ViewHolder ────────────────────────────────────────────────────────────

    static class PhotoVH extends RecyclerView.ViewHolder {
        final ImageView ivPhoto;
        PhotoVH(@NonNull View itemView) {
            super(itemView);
            ivPhoto = itemView.findViewById(R.id.iv_photo_slide);
        }
    }
}
