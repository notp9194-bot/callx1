package com.callx.app.channel;

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
 * ChannelLinkPreviewHelper — fetches real Open Graph metadata from URLs.
 *
 * Reads og:title, og:description, og:image from the HTML <head>.
 * Falls back to <title> tag if og:title is missing.
 * Non-blocking: runs on a background thread, delivers on main thread.
 *
 * Used by ChannelPostComposerActivity to show a live link card preview.
 */
public class ChannelLinkPreviewHelper {

    public interface LinkPreviewCallback {
        void onSuccess(LinkPreview preview);
        void onError(String reason);
    }

    public static class LinkPreview {
        public final String url;
        public final String title;
        public final String description;
        public final String imageUrl;
        public final String domain;

        public LinkPreview(String url, String title, String description,
                           String imageUrl, String domain) {
            this.url         = url;
            this.title       = title;
            this.description = description;
            this.imageUrl    = imageUrl;
            this.domain      = domain;
        }
    }

    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final Pattern OG_TITLE   = patternFor("og:title");
    private static final Pattern OG_DESC    = patternFor("og:description");
    private static final Pattern OG_IMAGE   = patternFor("og:image");
    private static final Pattern TITLE_TAG  = Pattern.compile(
            "<title[^>]*>([^<]{1,200})</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern META_CHARSET = Pattern.compile(
            "charset=[\"']?([A-Za-z0-9-]+)[\"']?", Pattern.CASE_INSENSITIVE);

    /** Fetch a link preview asynchronously. Callback on main thread. */
    public static void fetch(String rawUrl, LinkPreviewCallback callback) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            mainHandler.post(() -> callback.onError("Empty URL"));
            return;
        }
        final String url = rawUrl.startsWith("http") ? rawUrl : "https://" + rawUrl;

        executor.execute(() -> {
            try {
                String domain = extractDomain(url);
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (compatible; CallXBot/1.0)");
                conn.setInstanceFollowRedirects(true);
                conn.connect();

                int status = conn.getResponseCode();
                if (status < 200 || status >= 400) {
                    mainHandler.post(() -> callback.onError("HTTP " + status));
                    return;
                }

                String contentType = conn.getContentType();
                if (contentType != null && !contentType.contains("html")) {
                    // Non-HTML resource — return minimal preview
                    LinkPreview preview = new LinkPreview(url, domain, "", null, domain);
                    mainHandler.post(() -> callback.onSuccess(preview));
                    conn.disconnect();
                    return;
                }

                // Read up to first 16KB (enough for <head>)
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()), 16384);
                StringBuilder sb = new StringBuilder();
                String line;
                int bytesRead = 0;
                while ((line = reader.readLine()) != null && bytesRead < 16000) {
                    sb.append(line).append('\n');
                    bytesRead += line.length();
                    // Stop once we're past </head>
                    if (line.toLowerCase().contains("</head>")) break;
                }
                reader.close();
                conn.disconnect();

                String html = sb.toString();

                String title       = extractOg(OG_TITLE, html);
                if (title == null || title.isEmpty()) title = extractTag(TITLE_TAG, html);
                String description = extractOg(OG_DESC, html);
                String imageUrl    = extractOg(OG_IMAGE, html);
                if (title == null) title = domain;

                // Resolve relative imageUrl
                if (imageUrl != null && !imageUrl.isEmpty() && imageUrl.startsWith("/")) {
                    try {
                        URL base = new URL(url);
                        imageUrl = base.getProtocol() + "://" + base.getHost() + imageUrl;
                    } catch (Exception ignored) {}
                }

                final LinkPreview preview = new LinkPreview(
                        url,
                        title.trim(),
                        description != null ? description.trim() : "",
                        imageUrl,
                        domain);
                mainHandler.post(() -> callback.onSuccess(preview));

            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private static Pattern patternFor(String property) {
        return Pattern.compile(
                "<meta[^>]+property=[\"']?" + Pattern.quote(property) +
                "[\"']?[^>]+content=[\"']([^\"']{1,500})[\"']",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }

    private static String extractOg(Pattern p, String html) {
        // Try property=... content=... order
        Matcher m = p.matcher(html);
        if (m.find()) return htmlDecode(m.group(1));

        // Try content=... property=... order
        Pattern reversed = Pattern.compile(
                "<meta[^>]+content=[\"']([^\"']{1,500})[\"'][^>]+" +
                        Pattern.quote(p.pattern().split("property=")[1].split("\\[")[0]),
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        m = reversed.matcher(html);
        if (m.find()) return htmlDecode(m.group(1));
        return null;
    }

    private static String extractTag(Pattern p, String html) {
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
                .replace("&nbsp;", " ")
                .trim();
    }

    private static String extractDomain(String url) {
        try { return new URL(url).getHost().replaceAll("^www\\.", ""); }
        catch (Exception e) { return url; }
    }
}
