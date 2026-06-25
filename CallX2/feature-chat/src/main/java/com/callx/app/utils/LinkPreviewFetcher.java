package com.callx.app.utils;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

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
 * LinkPreviewFetcher — URL detect karo, OG tags fetch karo, cache karo.
 *
 * Special handling:
 *   • YouTube / youtu.be  → oEmbed API (no key needed, reliable title + thumbnail)
 *   • All other URLs      → raw HTML OG tag parse
 *
 * Usage:
 *   String url = LinkPreviewFetcher.extractFirstUrl(text);
 *   if (url != null) {
 *       LinkPreviewFetcher.fetch(url, result -> { /* bind to views *\/ });
 *   }
 */
public class LinkPreviewFetcher {

    public static final class Result {
        public final String url;
        public final String title;
        public final String domain;
        public final String imageUrl;
        public final String description;

        public Result(String url, String title, String domain,
                      String imageUrl, String description) {
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

    // ── URL detection ─────────────────────────────────────────────────────
    private static final Pattern URL_PATTERN = Pattern.compile(
            "https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+",
            Pattern.CASE_INSENSITIVE);

    // YouTube video ID patterns
    private static final Pattern YT_LONG  = Pattern.compile(
            "(?:youtube\\.com/watch\\?.*v=)([\\w\\-]{11})", Pattern.CASE_INSENSITIVE);
    private static final Pattern YT_SHORT = Pattern.compile(
            "(?:youtu\\.be/)([\\w\\-]{11})", Pattern.CASE_INSENSITIVE);

    private static final int MAX_CACHE      = 200;
    private static final int TIMEOUT_MS     = 10000;  // 10s — Instagram/WhatsApp slow
    private static final int MAX_HTML_BYTES = 131072; // 128 KB — OG tags sometimes deep

    private static final ExecutorService executor    = Executors.newFixedThreadPool(3);
    private static final Handler         mainHandler = new Handler(Looper.getMainLooper());

    @SuppressWarnings("serial")
    private static final Map<String, Result> cache =
            new LinkedHashMap<String, Result>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Result> e) {
                    return size() > MAX_CACHE;
                }
            };

    // FIX #4: In-flight deduplication — same URL pe concurrent multiple fetches prevent karo.
    // Without this, if 3 messages share the same URL and are bound in quick succession,
    // 3 identical HTTP requests fire in parallel. Now: first fetch goes out, subsequent
    // callers queue their callbacks and get notified when the first completes.
    private static final Map<String, java.util.List<Callback>> inFlight =
            new java.util.HashMap<>();

    private LinkPreviewFetcher() {}

    // ── Public API ────────────────────────────────────────────────────────

    /** Returns first HTTP/HTTPS URL in text, or null. */
    public static String extractFirstUrl(String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher m = URL_PATTERN.matcher(text);
        return m.find() ? m.group() : null;
    }

    /** Fetch preview async. Callback always fires on main thread.
     *  FIX #4: Multiple callers for the same URL share a single HTTP request. */
    public static void fetch(String url, Callback callback) {
        if (url == null || callback == null) return;
        synchronized (cache) {
            // Cache hit — deliver immediately
            Result cached = cache.get(url);
            if (cached != null) {
                mainHandler.post(() -> callback.onResult(cached));
                return;
            }
            // In-flight — queue this callback; the active fetch will notify all waiters
            if (inFlight.containsKey(url)) {
                inFlight.get(url).add(callback);
                return;
            }
            // New request — register in-flight list
            java.util.List<Callback> waiters = new java.util.ArrayList<>();
            waiters.add(callback);
            inFlight.put(url, waiters);
        }
        executor.execute(() -> {
            Result result = fetchSync(url);
            synchronized (cache) {
                if (result != null) cache.put(url, result);
                java.util.List<Callback> waiters = inFlight.remove(url);
                if (waiters == null) return;
                final Result r = result;
                mainHandler.post(() -> {
                    for (Callback cb : waiters) {
                        if (r != null) cb.onResult(r);
                        else           cb.onError(url);
                    }
                });
            }
        });
    }

    /** Returns cached result synchronously, or null if not yet fetched. Used by scroll guard. */
    public static Result getCached(String url) {
        if (url == null) return null;
        synchronized (cache) { return cache.get(url); }
    }

    public static void invalidate(String url) {
        synchronized (cache) { cache.remove(url); }
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private static Result fetchSync(String rawUrl) {
        // YouTube? Use oEmbed — much more reliable than OG parsing
        String ytId = extractYouTubeId(rawUrl);
        if (ytId != null) return fetchYouTubeOEmbed(rawUrl, ytId);
        return fetchGenericOG(rawUrl);
    }

    // ── YouTube oEmbed ────────────────────────────────────────────────────

    private static String extractYouTubeId(String url) {
        Matcher m = YT_SHORT.matcher(url);
        if (m.find()) return m.group(1);
        m = YT_LONG.matcher(url);
        if (m.find()) return m.group(1);
        return null;
    }

    /**
     * YouTube oEmbed JSON endpoint — free, no API key, always works.
     * Returns: title, author_name, thumbnail_url
     * Doc: https://oembed.com / https://www.youtube.com/oembed
     */
    private static Result fetchYouTubeOEmbed(String originalUrl, String videoId) {
        try {
            String oEmbedUrl = "https://www.youtube.com/oembed?url="
                    + java.net.URLEncoder.encode("https://www.youtube.com/watch?v=" + videoId, "UTF-8")
                    + "&format=json";
            URL url = new URL(oEmbedUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; CallX/1.0)");
            conn.connect();
            if (conn.getResponseCode() != 200) return null;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            conn.disconnect();

            JSONObject json = new JSONObject(sb.toString());
            String title     = json.optString("title", null);
            String author    = json.optString("author_name", null);
            String thumbUrl  = json.optString("thumbnail_url", null);

            if (title == null || title.isEmpty()) return null;

            // Use maxresdefault thumbnail for higher resolution
            String hiResThumb = "https://img.youtube.com/vi/" + videoId + "/hqdefault.jpg";

            String description = author != null ? "by " + author : null;
            return new Result(originalUrl, title, "youtube.com", hiResThumb, description);

        } catch (Exception e) {
            return null;
        }
    }

    // ── Generic OG tag fetch ──────────────────────────────────────────────

    private static Result fetchGenericOG(String rawUrl) {
        try {
            URL url = new URL(rawUrl);
            String domain = url.getHost();
            if (domain.startsWith("www.")) domain = domain.substring(4);

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
            conn.setRequestProperty("Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            conn.setRequestProperty("Accept-Encoding", "identity"); // no gzip — easier to parse
            conn.setInstanceFollowRedirects(true);
            conn.connect();

            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) return null;

            String contentType = conn.getContentType();
            if (contentType == null || !contentType.contains("text/html")) return null;

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

            String html        = sb.toString();
            String title       = extractMeta(html, "og:title");
            String image       = extractMeta(html, "og:image");
            String description = extractMeta(html, "og:description");

            // Fallback: twitter:image if og:image missing
            if (image == null || image.isEmpty()) {
                image = extractMeta(html, "twitter:image");
            }
            // Fallback: twitter:title
            if (title == null || title.isEmpty()) {
                title = extractMeta(html, "twitter:title");
            }
            if (title == null) title = extractTitle(html);
            if (title == null || title.isEmpty()) return null;

            // Handle relative image URLs
            if (image != null && !image.isEmpty() && image.startsWith("/")) {
                String scheme = url.getProtocol();
                String host   = url.getHost();
                image = scheme + "://" + host + image;
            }

            return new Result(rawUrl, title.trim(), domain, image, description);

        } catch (Exception e) {
            return null;
        }
    }

    // ── HTML parsing helpers ──────────────────────────────────────────────

    private static String extractMeta(String html, String property) {
        Pattern p = Pattern.compile(
                "<meta[^>]+property=[\"']" + Pattern.quote(property)
                + "[\"'][^>]+content=[\"']([^\"']*)[\"']",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = p.matcher(html);
        if (m.find()) return htmlDecode(m.group(1));

        p = Pattern.compile(
                "<meta[^>]+content=[\"']([^\"']*)[\"'][^>]+property=[\"']"
                + Pattern.quote(property) + "[\"']",
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
                .replace("&lt;",  "<")
                .replace("&gt;",  ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
    }
}
