package com.callx.app.utils;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.ListPreloader;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.util.Collections;
import java.util.List;

/**
 * SCROLL-AHEAD MEDIA PRELOADER
 * ──────────────────────────────────────────────────────────────────────
 * Reusable wrapper around Glide's RecyclerViewPreloader. Fast scroll ke
 * dauran, list mein aage (ya peeche, scroll direction ke hisaab se) jo
 * ~N items abhi screen par nahi hain unki image Glide disk/memory cache
 * mein pehle se hi fetch kar leta hai — taaki jab wo item screen par
 * aaye, image turant dikhe, blank/late-load na ho.
 *
 * Kisi bhi RecyclerView + adapter ke saath kaam karta hai (PagingDataAdapter
 * ho ya normal list-backed adapter) — bas ek chhota callback chahiye jo
 * position se preload-URL nikaal ke de. Adapter-specific bounds-checking
 * (getItemCount / peek) caller ke callback ke andar hoti hai, isliye yeh
 * helper kisi ek adapter type se bandha nahi hai.
 *
 * USAGE (adapter set hone ke turant baad, ChatActivity / GroupChatActivity
 * jaisi jagah jahan bhi image-heavy RecyclerView ho):
 *
 *   ChatMediaPreloader.attach(this, binding.rvMessages, 240, 240, position -> {
 *       Message m = pagingAdapter.peek(position);
 *       if (m == null) return null;
 *       if ("image".equals(m.type) || "gif".equals(m.type) || "video".equals(m.type)) {
 *           return (m.thumbnailUrl != null && !m.thumbnailUrl.isEmpty())
 *                   ? m.thumbnailUrl : m.mediaUrl;
 *       }
 *       return null;
 *   });
 *
 * Width/height wahi do jo actual bind() mein sabse pehle use hota hai
 * (yahan thumbnail size) — taaki preload aur actual load same Glide
 * cache-key size use karein aur cache hit ho, dobara download na ho.
 */
public final class ChatMediaPreloader {

    private ChatMediaPreloader() {
        // no instances
    }

    /** Kitne aage/peeche items preload karne hain — zyada = zyada bandwidth, kam = kam benefit. */
    private static final int MAX_PRELOAD = 8;

    /** Position se preload-URL nikaalne wala callback. Null/empty return karo agar us position pe media nahi hai. */
    public interface UrlProvider {
        @Nullable String getPreloadUrl(int position);
    }

    /**
     * Default preload size (thumbnail-jaisa) ke saath attach karta hai.
     *
     * @return attached OnScrollListener — activity ke onDestroy mein
     *         explicitly remove karna zaroori nahi (RecyclerView khud hi
     *         GC ho jaata hai), lekin agar screen baar-baar recreate hoti
     *         ho aur wahi RecyclerView instance reuse ho, toh caller chahe
     *         to {@code recyclerView.removeOnScrollListener(result)} kar
     *         sakta hai.
     */
    public static RecyclerView.OnScrollListener attach(
            @NonNull Context context,
            @NonNull RecyclerView recyclerView,
            @NonNull UrlProvider urlProvider) {
        return attach(context, recyclerView, 240, 240, urlProvider);
    }

    /**
     * Custom preload width/height ke saath attach karta hai.
     */
    public static RecyclerView.OnScrollListener attach(
            @NonNull Context context,
            @NonNull RecyclerView recyclerView,
            int preloadWidth,
            int preloadHeight,
            @NonNull UrlProvider urlProvider) {

        // NOTE: Glide's recyclerview-integration artifact does NOT ship a
        // ready-made "FixedPreloadSizeProvider" class — only ViewPreloadSizeProvider
        // (which measures an actual target view). Since we always want a fixed
        // thumbnail size, we implement PreloadSizeProvider ourselves — it's a
        // one-method interface, trivial to satisfy.
        final int[] fixedSize = new int[]{preloadWidth, preloadHeight};
        ListPreloader.PreloadSizeProvider<String> sizeProvider =
                (item, adapterPosition, perItemPosition) -> fixedSize;

        ListPreloader.PreloadModelProvider<String> modelProvider =
                new ListPreloader.PreloadModelProvider<String>() {
                    @NonNull
                    @Override
                    public List<String> getPreloadItems(int position) {
                        String url = urlProvider.getPreloadUrl(position);
                        if (url == null || url.isEmpty()) {
                            return Collections.emptyList();
                        }
                        return Collections.singletonList(url);
                    }

                    @Nullable
                    @Override
                    public RequestBuilder<?> getPreloadRequestBuilder(@NonNull String url) {
                        return Glide.with(context)
                                .load(url)
                                .diskCacheStrategy(DiskCacheStrategy.ALL);
                    }
                };

        RecyclerViewPreloader<String> preloader = new RecyclerViewPreloader<>(
                Glide.with(context), modelProvider, sizeProvider, MAX_PRELOAD);

        recyclerView.addOnScrollListener(preloader);
        return preloader;
    }
}
