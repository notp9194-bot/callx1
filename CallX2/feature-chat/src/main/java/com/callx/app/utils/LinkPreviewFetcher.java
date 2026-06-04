package com.callx.app.utils;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * POLISH: LinkPreviewFetcher — URL detect karo, OG tags fetch karo, cache karo.
 *
 * Usage:
 *   String url = LinkPreviewFetcher.extractFirstUrl(text);
 *   if (url != null) {
 *       LinkPreviewFetcher.fetch(url, result -> { /* bind to views *\/ });
 *   }
 *
 * Thread safety: fetch() is always called on a background thread; callback fires on main thread.
 * Cache: LRU in-memory, max 200 entries (cleared on process restart).
 */
public class LinkPreviewFetcher {

    public static final class Result {
        public final String url;
        public final String title;
        public final String domain;
        public final String imageUrl;
        public final String description;

        public Result(String url, String title, String domain, String imageUrl, String description) {
            this.url         = url;
            this.title       = title;
            this.domain      = domain;
            this.imageUrl    = imageUrl;
            this.description = description;
        }
    }

    public interface Callback {
        void onResult(Result result);
        void onError(String url);
    }

    private static final Pattern URL_PATTERN = Pattern.compile(
            "https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+",
            Pattern.CASE_INSENSITIVE);

    private static final int MAX_CACHE = 200;
    private static final int TIMEOUT_MS = 6000;
    private static final int MAX_HTML_BYTES = 65536; // 64 KB

    private static final ExecutorService executor = Executors.newFixedThreadPool(3);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    // LRU cache — access-ordered LinkedHashMap
    @SuppressWarnings("serial")
    private static final Map<String, Result> cache = new LinkedHashMap<String, Result>(
            16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Result> e) {
            return size() > MAX_CACHE;
        }
    };

    private LinkPreviewFetcher() {}

    /** Returns the first HTTP/HTTPS URL found in text, or null. */
    public static String extractFirstUrl(String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher m = URL_PATTERN.matcher(text);
        return m.find() ? m.group() : null;
    }

    /**
     * Fetch link preview for url. If cached, callback fires immediately (still on main thread).
     * Safe to call from any thread.
     */
    public static void fetch(String url, Callback callback) {
        if (url == null || callback == null) return;

        // Check cache first
        synchronized (cache) {
            Result cached = cache.get(url);
            if (cached != null) {
                mainHandler.post(() -> callback.onResult(cached));
                return;
            }
        }

        executor.execute(() -> {
            Result result = fetchSync(url);
            synchronized (cache) {
                if (result != null) cache.put(url, result);
            }
            if (result != null) {
                mainHandler.post(() -> callback.onResult(result));
            } else {
                mainHandler.post(() -> callback.onError(url));
            }
        });
    }

    /** Synchronous fetch + parse — runs on background thread. */
    private static Result fetchSync(String rawUrl) {
        try {
            URL url = new URL(rawUrl);
            String domain = url.getHost();
            if (domain.startsWith("www.")) domain = domain.substring(4);

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; CallX/1.0)");
            conn.setInstanceFollowRedirects(true);
            conn.connect();

            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) return null;

            String contentType = conn.getContentType();
            if (contentType == null || !contentType.contains("text/html")) return null;

            // Read first MAX_HTML_BYTES only (meta tags are near the top)
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                char[] buf = new char[4096];
                int total = 0, n;
                while ((n = reader.read(buf)) != -1 && total < MAX_HTML_BYTES) {
                    sb.append(buf, 0, n);
                    total += n;
                }
            }
            conn.disconnect();

            String html = sb.toString();
            String title       = extractMeta(html, "og:title");
            String image       = extractMeta(html, "og:image");
            String description = extractMeta(html, "og:description");

            if (title == null) title = extractTitle(html);
            if (title == null || title.isEmpty()) return null; // nothing useful

            return new Result(rawUrl, title.trim(), domain, image, description);

        } catch (Exception e) {
            return null;
        }
    }

    /** Extract og:XXXX or name=XXXX meta content. */
    private static String extractMeta(String html, String property) {
        // og:property pattern: <meta property="og:title" content="..." />
        Pattern p = Pattern.compile(
                "<meta[^>]+property=[\"']" + Pattern.quote(property) +
                "[\"'][^>]+content=[\"']([^\"']*)[\"']",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = p.matcher(html);
        if (m.find()) return htmlDecode(m.group(1));

        // reversed attribute order
        p = Pattern.compile(
                "<meta[^>]+content=[\"']([^\"']*)[\"'][^>]+property=[\"']" +
                Pattern.quote(property) + "[\"']",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        m = p.matcher(html);
        if (m.find()) return htmlDecode(m.group(1));

        return null;
    }

    private static String extractTitle(String html) {
        Pattern p = Pattern.compile("<title[^>]*>([^<]+)</title>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = p.matcher(html);
        return m.find() ? htmlDecode(m.group(1)) : null;
    }

    private static String htmlDecode(String s) {
        if (s == null) return null;
        return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
    }

    /** Invalidate a single cache entry (e.g., on network error). */
    public static void invalidate(String url) {
        synchronized (cache) { cache.remove(url); }
    }
}
