package com.callx.app.cache;

import android.content.Context;
import android.util.Log;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.models.XTweet;

import java.util.List;

/**
 * XTweetImagePreloader — Glide se agle tweets ke images/thumbnails preload karta hai.
 *
 * Reels ke ReelThumbnailPreloader ki tarah kaam karta hai, par X feed ke liye:
 *   User tweet N dekh raha hai → hum Glide se tweet N+1..N+5 ke:
 *     - Author profile photos (avatars)
 *     - Tweet media images / video thumbnails
 *   ...RAM + disk mein cache kar lete hain.
 *
 *   Jab user scroll karta hai → images/avatars instantly dikhte hain, koi flicker nahi.
 *
 * Usage (XHomeFragment mein):
 *   // Field:
 *   private XTweetImagePreloader imagePreloader;
 *
 *   // onViewCreated ke baad:
 *   imagePreloader = new XTweetImagePreloader(requireContext());
 *
 *   // RecyclerView scroll listener ya adapter bind mein:
 *   imagePreloader.preloadFrom(currentList, firstVisiblePosition);
 *
 * Note: XTweetMediaPreloader video ke liye hai; yeh class images/thumbs ke liye hai.
 */
public class XTweetImagePreloader {

    private static final String TAG           = "XTweetImagePreloader";
    private static final int    PRELOAD_COUNT = 5; // Agle 5 tweets ke images preload

    private final Context        mContext;
    private final RequestOptions mAvatarOptions;
    private final RequestOptions mMediaOptions;

    public XTweetImagePreloader(Context context) {
        mContext = context.getApplicationContext();

        // Avatar (profile photo) options — round crop, thumbnail size
        mAvatarOptions = new RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .circleCrop()
            .override(96, 96);

        // Tweet media image / video thumbnail options
        mMediaOptions = new RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .centerCrop()
            .override(720, 480);
    }

    /**
     * Position + 1 se PRELOAD_COUNT tak ke tweets ke images preload karta hai.
     *
     * @param tweets   Adapter ki current list
     * @param position Abhi dikhayi ja rahi tweet ka position
     */
    public void preloadFrom(List<XTweet> tweets, int position) {
        if (tweets == null || tweets.isEmpty()) return;

        for (int i = position + 1; i <= position + PRELOAD_COUNT && i < tweets.size(); i++) {
            XTweet tweet = tweets.get(i);
            if (tweet == null) continue;

            // 1. Author avatar preload
            if (tweet.authorThumbUrl != null && !tweet.authorThumbUrl.isEmpty()) {
                Glide.with(mContext)
                    .load(tweet.authorThumbUrl)
                    .apply(mAvatarOptions)
                    .preload();
                Log.v(TAG, "Preloading avatar for tweet[" + i + "]: " + tweet.id);
            }

            // 2. Tweet media image ya video thumbnail preload
            // Video ke liye thumbnailUrl, image ke liye mediaUrl
            String imageToPreload = null;
            if ("video".equals(tweet.mediaType) && tweet.thumbnailUrl != null && !tweet.thumbnailUrl.isEmpty()) {
                imageToPreload = tweet.thumbnailUrl;
            } else if ("image".equals(tweet.mediaType) && tweet.mediaUrl != null && !tweet.mediaUrl.isEmpty()) {
                imageToPreload = tweet.mediaUrl;
            }

            if (imageToPreload != null) {
                Glide.with(mContext)
                    .load(imageToPreload)
                    .apply(mMediaOptions)
                    .preload();
                Log.v(TAG, "Preloading media for tweet[" + i + "]: " + tweet.id);
            }
        }
    }
}
