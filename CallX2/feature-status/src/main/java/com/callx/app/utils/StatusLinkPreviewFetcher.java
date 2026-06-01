package com.callx.app.utils;

import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * StatusLinkPreviewFetcher — Fetches OG/meta tags for link-type statuses.
 *
 * Parses HTML <head> for:
 *   og:title, og:description, og:image, og:url, og:site_name
 *   twitter:title, twitter:description, twitter:image (fallback)
 *   <title> tag (last fallback for title)
 *   <link rel="icon"> (favicon fallback)
 *
 * Usage:
 *   StatusLinkPreviewFetcher.fetch(url, new StatusLinkPreviewFetcher.Callback() {
 *       @Override public void onResult(LinkPreview preview) {
 *           // preview.title, preview.description, preview.imageUrl, preview.faviconUrl
 *           // preview.domain — extracted from URL
 *       }
 *       @Override public void onError(String message) {}
 *   });
 *
 * Integration: called from NewStatusActivity when user pastes a URL into the text field.
 * The result populates StatusItem fields: linkTitle, linkDescription, linkFaviconUrl,
 * thumbnailUrl (= og:image), and type = "link".
 */
public final class StatusLinkPreviewFetcher {

    private static final String TAG          = "LinkPreviewFetcher";
    private static final int    TIMEOUT_MS   = 8_000;
    private static final int    MAX_BYTES    = 512 * 1024; // read at most 512 KB of HTML
    private static final ExecutorService BG  = Executors.newCachedThreadPool();

    private StatusLinkPreviewFetcher() {}

    // ── LinkPreview model ─────────────────────────────────────────────────

    public static class LinkPreview {
        public String url;
        public String title;
        public String description;
        public String imageUrl;
        public String faviconUrl;
        public String domain;
        public String siteName;

        public boolean isEmpty() {
            return (title == null || title.isEmpty())
                && (description == null || description.isEmpty())
                && (imageUrl == null || imageUrl.isEmpty());
        }
    }

    public interface Callback {
        void onResult(LinkPreview preview);
        void onError(String message);
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Fetch a link preview for the given URL.
     * Result fires on the calling thread's Looper (main thread if called from UI).
     */
    public static void fetch(String rawUrl, Callback cb) {
        if (rawUrl == null || rawUrl.isEmpty()) {
            if (cb != null) cb.onError("Empty URL");
            return;
        }
        final String url = normaliseUrl(rawUrl);
        BG.execute(() -> {
            try {
                String html = downloadHtml(url);
                if (html == null) {
                    fire(cb, null, "Failed to fetch page");
                    return;
                }
                LinkPreview preview = parseHtml(html, url);
                fire(cb, preview, null);
            } catch (Exception e) {
                Log.w(TAG, "fetch error: " + e.getMessage());
                fire(cb, null, e.getMessage());
            }
        });
    }

    // ── Internal: download HTML ───────────────────────────────────────────

    private static String downloadHtml(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (compatible; CallX/1.0; +https://callx.app)");
            conn.setInstanceFollowRedirects(true);
            conn.connect();

            int code = conn.getResponseCode();
            if (code >= 400) return null;

            String contentType = conn.getContentType();
            if (contentType != null && !contentType.contains("text/html")) return null;

            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            char[] buf = new char[4096];
            int read;
            int total = 0;
            while ((read = br.read(buf)) != -1) {
                sb.append(buf, 0, read);
                total += read * 2; // rough bytes
                if (total > MAX_BYTES) break; // stop reading after 512 KB
                // Stop early once we've passed </head>
                String partial = sb.toString().toLowerCase();
                if (partial.contains("</head>") || partial.contains("<body")) break;
            }
            br.close();
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "downloadHtml: " + e.getMessage());
            return null;
        }
    }

    // ── Internal: parse HTML for OG tags ─────────────────────────────────

    private static LinkPreview parseHtml(String html, String sourceUrl) {
        LinkPreview p = new LinkPreview();
        p.url    = sourceUrl;
        p.domain = extractDomain(sourceUrl);

        // og:title
        p.title = getMetaContent(html, "og:title");
        if (p.title == null) p.title = getMetaContent(html, "twitter:title");
        if (p.title == null) p.title = getTagContent(html, "<title", ">", "</title>");
        if (p.title != null) p.title = htmlDecode(p.title.trim());

        // og:description
        p.description = getMetaContent(html, "og:description");
        if (p.description == null) p.description = getMetaContent(html, "twitter:description");
        if (p.description == null) p.description = getMetaName(html, "description");
        if (p.description != null) p.description = htmlDecode(p.description.trim());

        // og:image
        p.imageUrl = getMetaContent(html, "og:image");
        if (p.imageUrl == null) p.imageUrl = getMetaContent(html, "twitter:image");
        if (p.imageUrl != null && p.imageUrl.startsWith("/")) {
            p.imageUrl = extractBase(sourceUrl) + p.imageUrl;
        }

        // og:site_name
        p.siteName = getMetaContent(html, "og:site_name");
        if (p.siteName == null) p.siteName = p.domain;

        // favicon
        p.faviconUrl = extractFavicon(html, extractBase(sourceUrl));

        return p;
    }

    // ── HTML helpers ──────────────────────────────────────────────────────

    private static String getMetaContent(String html, String property) {
        // Matches both property="og:xxx" content="..." and reversed order
        String[] patterns = {
            "property=[\"']" + Pattern.quote(property) + "[\"'][^>]*content=[\"']([^\"']*)[\"']",
            "content=[\"']([^\"']*)[\"'][^>]*property=[\"']" + Pattern.quote(property) + "[\"']",
            "name=[\"']" + Pattern.quote(property) + "[\"'][^>]*content=[\"']([^\"']*)[\"']",
            "content=[\"']([^\"']*)[\"'][^>]*name=[\"']" + Pattern.quote(property) + "[\"']"
        };
        for (String pat : patterns) {
            Matcher m = Pattern.compile(pat, Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(html);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private static String getMetaName(String html, String name) {
        return getMetaContent(html, name);
    }

    private static String getTagContent(String html, String openStart, String openEnd, String close) {
        try {
            int start = html.toLowerCase().indexOf(openStart.toLowerCase());
            if (start < 0) return null;
            int contentStart = html.indexOf(openEnd, start) + 1;
            int contentEnd   = html.toLowerCase().indexOf(close.toLowerCase(), contentStart);
            if (contentEnd < contentStart) return null;
            return html.substring(contentStart, contentEnd).trim();
        } catch (Exception e) { return null; }
    }

    private static String extractFavicon(String html, String base) {
        Matcher m = Pattern.compile(
            "<link[^>]*rel=[\"'](?:shortcut )?icon[\"'][^>]*href=[\"']([^\"']*)[\"']",
            Pattern.CASE_INSENSITIVE)
            .matcher(html);
        if (m.find()) {
            String href = m.group(1);
            if (href != null && href.startsWith("/")) href = base + href;
            return href;
        }
        return base + "/favicon.ico";
    }

    private static String extractDomain(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            String host = u.getHost();
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) { return url; }
    }

    private static String extractBase(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            return u.getProtocol() + "://" + u.getHost();
        } catch (Exception e) { return ""; }
    }

    private static String normaliseUrl(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "https://" + url;
        }
        return url;
    }

    private static String htmlDecode(String s) {
        if (s == null) return null;
        return s.replace("&amp;",  "&")
                .replace("&lt;",   "<")
                .replace("&gt;",   ">")
                .replace("&quot;", "\"")
                .replace("&#39;",  "'")
                .replace("&nbsp;", " ");
    }

    // ── Fire callback on main thread ──────────────────────────────────────

    private static void fire(Callback cb, LinkPreview preview, String error) {
        if (cb == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (error != null) cb.onError(error);
            else cb.onResult(preview);
        });
    }

    // ── URL detection helper (used by NewStatusActivity) ──────────────────

    private static final Pattern URL_PATTERN = Pattern.compile(
        "(https?://[\\w\\-]+(\\.[\\w\\-]+)+(/[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]*)?)",
        Pattern.CASE_INSENSITIVE);

    /**
     * Detect if text contains a URL. Returns the first URL found, or null.
     */
    public static String detectUrl(String text) {
        if (text == null) return null;
        Matcher m = URL_PATTERN.matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
