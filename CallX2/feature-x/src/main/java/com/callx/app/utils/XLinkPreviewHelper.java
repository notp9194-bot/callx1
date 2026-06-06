package com.callx.app.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * XLinkPreviewHelper — Fetches OG meta tags from a URL and caches in Firebase.
 *
 * Usage:
 *   XLinkPreviewHelper.fetchPreview(context, url, preview -> { ... });
 */
public class XLinkPreviewHelper {

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static class LinkPreview {
        public String url;
        public String title;
        public String description;
        public String imageUrl;
        public String domain;

        public boolean isEmpty() {
            return (title == null || title.isEmpty()) && (imageUrl == null || imageUrl.isEmpty());
        }
    }

    public interface Callback {
        void onResult(LinkPreview preview);
    }

    /**
     * Fetch link preview — checks Firebase cache first, then HTTP.
     */
    public static void fetchPreview(Context ctx, String url, Callback callback) {
        if (url == null || url.isEmpty()) return;
        String key = XFirebaseUtils.urlToKey(url);

        // Check cache
        XFirebaseUtils.xLinkPreviewRef(key).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                if (snap.exists()) {
                    LinkPreview cached = new LinkPreview();
                    cached.url         = snap.child("url").getValue(String.class);
                    cached.title       = snap.child("title").getValue(String.class);
                    cached.description = snap.child("description").getValue(String.class);
                    cached.imageUrl    = snap.child("imageUrl").getValue(String.class);
                    cached.domain      = snap.child("domain").getValue(String.class);
                    if (!cached.isEmpty()) { MAIN.post(() -> callback.onResult(cached)); return; }
                }
                // Fetch from network
                fetchFromNetwork(url, key, callback);
            }
            @Override public void onCancelled(DatabaseError e) {
                fetchFromNetwork(url, key, callback);
            }
        });
    }

    private static void fetchFromNetwork(String url, String key, Callback callback) {
        EXECUTOR.submit(() -> {
            LinkPreview preview = scrape(url);
            if (preview != null && !preview.isEmpty()) {
                // Save to Firebase cache
                Map<String, Object> data = new HashMap<>();
                data.put("url",         preview.url         != null ? preview.url         : "");
                data.put("title",       preview.title       != null ? preview.title       : "");
                data.put("description", preview.description != null ? preview.description : "");
                data.put("imageUrl",    preview.imageUrl    != null ? preview.imageUrl    : "");
                data.put("domain",      preview.domain      != null ? preview.domain      : "");
                data.put("cachedAt",    System.currentTimeMillis());
                XFirebaseUtils.xLinkPreviewRef(key).setValue(data);
                MAIN.post(() -> callback.onResult(preview));
            }
        });
    }

    private static LinkPreview scrape(String rawUrl) {
        try {
            URL url = new URL(rawUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (compatible; CallXBot/1.0; +https://callx.app)");
            conn.setInstanceFollowRedirects(true);

            int status = conn.getResponseCode();
            if (status != 200) return null;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                int bytesRead = 0;
                // Only read <head> section (first 64KB)
                while ((line = br.readLine()) != null && bytesRead < 65536) {
                    sb.append(line);
                    bytesRead += line.length();
                    if (line.contains("</head>")) break;
                }
            }
            String html = sb.toString();

            LinkPreview p = new LinkPreview();
            p.url    = rawUrl;
            p.domain = url.getHost().replaceFirst("^www\\.", "");
            p.title       = ogMeta(html, "og:title");
            if (p.title == null) p.title = titleTag(html);
            p.description = ogMeta(html, "og:description");
            if (p.description == null) p.description = metaName(html, "description");
            p.imageUrl    = ogMeta(html, "og:image");
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    private static String ogMeta(String html, String property) {
        Pattern p = Pattern.compile(
            "<meta[^>]+property=[\"']" + Pattern.quote(property) + "[\"'][^>]+content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        if (m.find()) return m.group(1).trim();
        // Also try reversed order
        Pattern p2 = Pattern.compile(
            "<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']" + Pattern.quote(property) + "[\"']",
            Pattern.CASE_INSENSITIVE);
        Matcher m2 = p2.matcher(html);
        return m2.find() ? m2.group(1).trim() : null;
    }

    private static String metaName(String html, String name) {
        Pattern p = Pattern.compile(
            "<meta[^>]+name=[\"']" + Pattern.quote(name) + "[\"'][^>]+content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String titleTag(String html) {
        Pattern p = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        return m.find() ? m.group(1).trim() : null;
    }
}
