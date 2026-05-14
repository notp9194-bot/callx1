package com.callx.app.cache;

import android.content.Context;
import android.util.Log;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.models.ReelModel;

import java.util.List;

/**
 * ReelThumbnailPreloader — Glide se agle reels ke thumbnails preload karta hai.
 *
 * Kaam kaise karta hai:
 *   User reel N dekh raha hai → hum Glide se reel N+1..N+5 ke thumbnails
 *   RAM + disk mein cache kar lete hain.
 *
 *   Jab ReelPlayerFragment open hoga → thumbnail instantly dikhegi,
 *   loading spinner nahi aayega.
 *
 * Usage (ReelsFragment mein):
 *   // Field:
 *   private ReelThumbnailPreloader thumbPreloader;
 *
 *   // onCreateView ke baad:
 *   thumbPreloader = new ReelThumbnailPreloader(requireContext());
 *
 *   // onPageSelected mein:
 *   thumbPreloader.preloadFrom(currentList, position);
 */
public class ReelThumbnailPreloader {

    private static final String TAG           = "ThumbPreloader";
    private static final int    PRELOAD_COUNT = 5; // Agle 5 thumbnails preload

    private final Context        mContext;
    private final RequestOptions mOptions;

    public ReelThumbnailPreloader(Context context) {
        mContext = context.getApplicationContext();
        mOptions = new RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.ALL) // RAM + Disk dono mein cache
            .centerCrop()
            .override(720, 1280); // Reel aspect ratio
    }

    /**
     * Position + 1 se PRELOAD_COUNT tak ke thumbnails preload karta hai.
     *
     * @param reels    Adapter ki current list
     * @param position Abhi dikhayi ja rahi reel ka position
     */
    public void preloadFrom(List<ReelModel> reels, int position) {
        if (reels == null || reels.isEmpty()) return;

        for (int i = position + 1; i <= position + PRELOAD_COUNT && i < reels.size(); i++) {
            ReelModel reel = reels.get(i);
            if (reel == null || reel.thumbUrl == null || reel.thumbUrl.isEmpty()) continue;

            // Glide ka preload() — bas cache karta hai, koi ImageView nahi chahiye
            Glide.with(mContext)
                .load(reel.thumbUrl)
                .apply(mOptions)
                .preload();

            Log.v(TAG, "Preloading thumb for reel[" + i + "]: " + reel.reelId);
        }
    }
}
