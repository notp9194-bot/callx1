package com.callx.app.utils;

import android.content.Context;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.util.HashSet;
import java.util.Set;

/**
 * SCROLL-AHEAD MEDIA PRELOADER
 * ──────────────────────────────────────────────────────────────────────
 * Adaptive scroll-ahead media preloader. Fast scroll ke dauran, list mein
 * aage (ya peeche, scroll direction ke hisaab se) jo bounded number of items
 * abhi screen par nahi hain unki image Glide cache mein pehle se fetch karta
 * hai — lekin RAM, connection aur velocity ke hisaab se window chhoti/badi
 * hoti hai.
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

    /** Hard ceiling; the adaptive policy normally stays well below this. */
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
        return attach(context, recyclerView, preloadWidth, preloadHeight, MAX_PRELOAD, urlProvider);
    }

    /**
     * Full control overload. The supplied value is a hard ceiling, while the
     * live request count is selected by AdaptiveChatScrollPolicy.
     */
    public static RecyclerView.OnScrollListener attach(
            @NonNull Context context,
            @NonNull RecyclerView recyclerView,
            int preloadWidth,
            int preloadHeight,
            int maxPreload,
            @NonNull UrlProvider urlProvider) {

        final Context appContext = context.getApplicationContext();
        final AdaptiveChatScrollPolicy policy = new AdaptiveChatScrollPolicy(appContext);
        final Set<String> requestedUrls = new HashSet<>();
        final long[] lastSampleMs = {0L};
        final int[] lastDy = {0};
        final int[] velocityPxPerSecond = {0};

        RecyclerView.OnScrollListener preloader = new RecyclerView.OnScrollListener() {
            private int scrollState = RecyclerView.SCROLL_STATE_IDLE;

            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                long now = SystemClock.uptimeMillis();
                long elapsed = lastSampleMs[0] == 0L ? 0L : now - lastSampleMs[0];
                if (elapsed > 0L && elapsed <= 250L) {
                    velocityPxPerSecond[0] = (int) Math.min(12_000L,
                            Math.abs((long) dy) * 1000L / elapsed);
                }
                if (dy != 0) lastDy[0] = dy;
                lastSampleMs[0] = now;
                preloadAroundViewport(rv);
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                scrollState = newState;
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING
                        || newState == RecyclerView.SCROLL_STATE_IDLE) {
                    preloadAroundViewport(rv);
                }
            }

            private void preloadAroundViewport(@NonNull RecyclerView rv) {
                RecyclerView.LayoutManager raw = rv.getLayoutManager();
                if (!(raw instanceof androidx.recyclerview.widget.LinearLayoutManager)) return;
                androidx.recyclerview.widget.LinearLayoutManager lm =
                        (androidx.recyclerview.widget.LinearLayoutManager) raw;
                int first = lm.findFirstVisibleItemPosition();
                int last = lm.findLastVisibleItemPosition();
                if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return;

                int count = policy.mediaPreloadCount(
                        scrollState, velocityPxPerSecond[0], maxPreload);
                if (count <= 0 || rv.getAdapter() == null) return;
                int direction = lastDy[0] >= 0 ? 1 : -1;
                int itemCount = rv.getAdapter().getItemCount();
                int start = direction > 0 ? last + 1 : first - count;
                int end = direction > 0 ? last + count : first - 1;
                start = Math.max(0, Math.min(itemCount, start));
                end = Math.max(-1, Math.min(itemCount - 1, end));
                if (direction > 0) end = Math.min(end, start + count - 1);
                else start = Math.max(start, end - count + 1);

                for (int position = start; position <= end; position++) {
                    String url = urlProvider.getPreloadUrl(position);
                    if (url == null || url.isEmpty() || !requestedUrls.add(url)) continue;
                    Glide.with(appContext)
                            .load(url)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .override(preloadWidth, preloadHeight)
                            .preload();
                }

                // Avoid retaining every URL from a long chat. Re-requesting a
                // URL after this small rolling set is cheap because Glide
                // serves an existing cache hit without another download.
                if (requestedUrls.size() > 64) {
                    java.util.Iterator<String> iterator = requestedUrls.iterator();
                    while (requestedUrls.size() > 48 && iterator.hasNext()) {
                        iterator.next();
                        iterator.remove();
                    }
                }
            }
        };

        recyclerView.addOnScrollListener(preloader);
        return preloader;
    }
}
