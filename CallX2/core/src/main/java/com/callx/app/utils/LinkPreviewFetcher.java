package com.callx.app.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LinkPreviewFetcher — Fetches OG/meta tags from a URL for link preview cards.
 *
 * Usage:
 *   LinkPreviewFetcher.fetch(context, "https://example.com", result -> {
 *       if (result != null) showLinkPreview(result);
 *   });
 */
public class LinkPreviewFetcher {

    public static class LinkPreview {
        public String url;
        public String title;
        public String description;
        public String imageUrl;
        public String siteName;

        public boolean isEmpty() {
            return (title == null || title.isEmpty()) &&
                   (description == null || description.isEmpty()) &&
                   (imageUrl == null || imageUrl.isEmpty());
        }
    }

    public interface Callback {
        void onResult(LinkPreview preview);
    }

    private static final ExecutorService executor = Executors.newFixedThreadPool(2);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Regex to extract URLs from text
    private static final Pattern URL_PATTERN = Pattern.compile(
        "https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+",
        Pattern.CASE_INSENSITIVE
    );

    public static String extractFirstUrl(String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher m = URL_PATTERN.matcher(text);
        return m.find() ? m.group() : null;
    }

    public static void fetch(Context ctx, String urlStr, Callback callback) {
        executor.execute(() -> {
            LinkPreview preview = fetchSync(urlStr);
            mainHandler.post(() -> callback.onResult(preview));
        });
    }

    private static LinkPreview fetchSync(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Android) AppleWebKit/537.36");
            conn.connect();
            if (conn.getResponseCode() != 200) return null;

            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "UTF-8"));
            String line;
            int lineCount = 0;
            while ((line = reader.readLine()) != null && lineCount++ < 200) {
                sb.append(line);
                if (line.contains("</head>")) break;
            }
            reader.close();
            conn.disconnect();

            String html = sb.toString();
            LinkPreview preview = new LinkPreview();
            preview.url = urlStr;

            // Extract OG tags first, then fall back to <title> / <meta description>
            preview.title       = extractOgOrMeta(html, "og:title",       "title");
            preview.description = extractOgOrMeta(html, "og:description", "description");
            preview.imageUrl    = extractOgTag  (html, "og:image");
            preview.siteName    = extractOgTag  (html, "og:site_name");

            if (preview.title == null || preview.title.isEmpty()) {
                preview.title = extractTag(html, "title");
            }

            if (preview.isEmpty()) return null;
            return preview;

        } catch (Exception e) {
            return null;
        }
    }

    private static String extractOgTag(String html, String property) {
        // <meta property="og:title" content="..." />  or  content first
        Pattern p = Pattern.compile(
            "<meta[^>]+property=[\"']" + Pattern.quote(property) + "[\"'][^>]+content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        if (m.find()) return decode(m.group(1));

        Pattern p2 = Pattern.compile(
            "<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']" + Pattern.quote(property) + "[\"']",
            Pattern.CASE_INSENSITIVE);
        Matcher m2 = p2.matcher(html);
        return m2.find() ? decode(m2.group(1)) : null;
    }

    private static String extractOgOrMeta(String html, String ogProperty, String metaName) {
        String val = extractOgTag(html, ogProperty);
        if (val != null) return val;
        Pattern p = Pattern.compile(
            "<meta[^>]+name=[\"']" + Pattern.quote(metaName) + "[\"'][^>]+content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        return m.find() ? decode(m.group(1)) : null;
    }

    private static String extractTag(String html, String tag) {
        Pattern p = Pattern.compile("<" + tag + "[^>]*>([^<]+)</" + tag + ">",
            Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        return m.find() ? decode(m.group(1).trim()) : null;
    }

    private static String decode(String s) {
        if (s == null) return null;
        return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .trim();
    }
}
