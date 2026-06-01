package com.callx.app.utils;

import android.util.Log;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * StatusLinkPreviewHelper — Fetch Open Graph metadata for link statuses.
 * Runs entirely on a background thread; delivers result via callback on caller thread.
 */
public final class StatusLinkPreviewHelper {

    private static final String TAG = "LinkPreview";

    public interface Callback {
        void onResult(LinkPreview preview);
        void onError(String error);
    }

    public static class LinkPreview {
        public String url;
        public String title;
        public String description;
        public String imageUrl;
        public String domain;
        public String faviconUrl;

        public boolean isValid() {
            return title != null && !title.isEmpty();
        }
    }

    /** Detect if a string contains a URL. */
    public static String extractUrl(String text) {
        if (text == null) return null;
        Pattern p = Pattern.compile("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        return m.find() ? m.group() : null;
    }

    public static boolean containsUrl(String text) { return extractUrl(text) != null; }

    /** Fetch OG metadata asynchronously. */
    public static void fetch(String urlStr, Callback cb) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)");
                conn.connect();

                java.io.InputStream is = conn.getInputStream();
                byte[] buf = new byte[32768];
                int n = is.read(buf);
                String html = n > 0 ? new String(buf, 0, n, "UTF-8") : "";
                conn.disconnect();

                LinkPreview preview = parse(urlStr, html);
                cb.onResult(preview);
            } catch (Exception e) {
                Log.w(TAG, "Fetch failed: " + e.getMessage());
                cb.onError(e.getMessage());
            }
        });
    }

    private static LinkPreview parse(String urlStr, String html) {
        LinkPreview p = new LinkPreview();
        p.url    = urlStr;
        p.domain = extractDomain(urlStr);
        p.faviconUrl = "https://www.google.com/s2/favicons?domain=" + p.domain + "&sz=64";
        p.title       = og(html, "og:title");
        p.description = og(html, "og:description");
        p.imageUrl    = og(html, "og:image");
        if (p.title == null)       p.title       = tag(html, "title");
        if (p.description == null) p.description = meta(html, "description");
        return p;
    }

    private static String og(String html, String prop) {
        Pattern pat = Pattern.compile(
            "<meta[^>]+property=[\"']" + Pattern.quote(prop) + "[\"'][^>]+content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
        Matcher m = pat.matcher(html);
        if (m.find()) return m.group(1).trim();
        pat = Pattern.compile(
            "<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']" + Pattern.quote(prop) + "[\"']",
            Pattern.CASE_INSENSITIVE);
        m = pat.matcher(html);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String tag(String html, String tagName) {
        Pattern pat = Pattern.compile("<" + tagName + "[^>]*>([^<]+)</" + tagName + ">", Pattern.CASE_INSENSITIVE);
        Matcher m = pat.matcher(html);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String meta(String html, String name) {
        Pattern pat = Pattern.compile(
            "<meta[^>]+name=[\"']" + name + "[\"'][^>]+content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
        Matcher m = pat.matcher(html);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String extractDomain(String url) {
        try {
            URL u = new URL(url);
            String host = u.getHost();
            if (host.startsWith("www.")) host = host.substring(4);
            return host;
        } catch (Exception e) { return url; }
    }
}
