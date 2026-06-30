package com.callx.app.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * StatusLinkPreviewHelper — Fetch Open Graph metadata for link statuses.
 *
 * Scraping logic: LinkPreviewFetcher (core) ko delegate karta hai —
 * duplicate HTTP + HTML parsing yahan remove kiya gaya hai.
 *
 * Unique additions over core:
 *   - faviconUrl field (Google S2 favicon service)
 *   - containsUrl() convenience method
 *   - onError() callback
 */
public final class StatusLinkPreviewHelper {

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
        Pattern p = Pattern.compile(
            "https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+",
            Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        return m.find() ? m.group() : null;
    }

    public static boolean containsUrl(String text) {
        return extractUrl(text) != null;
    }

    /**
     * Fetch OG metadata asynchronously via LinkPreviewFetcher (core).
     * Callback fires on main thread.
     * Adds faviconUrl (Google S2 service) to the result.
     */
    public static void fetch(String urlStr, Callback cb) {
        if (urlStr == null || urlStr.isEmpty()) {
            cb.onError("URL is null or empty");
            return;
        }
        LinkPreviewFetcher.fetch(urlStr, new LinkPreviewFetcher.Callback() {
            @Override
            public void onResult(LinkPreviewFetcher.Result r) {
                LinkPreview p = new LinkPreview();
                p.url         = r.url;
                p.title       = r.title;
                p.description = r.description;
                p.imageUrl    = r.imageUrl;
                p.domain      = r.domain;
                p.faviconUrl  = "https://www.google.com/s2/favicons?domain="
                                + (r.domain != null ? r.domain : "") + "&sz=64";
                cb.onResult(p);
            }

            @Override
            public void onError(String errorUrl) {
                cb.onError("Failed to fetch preview for: " + errorUrl);
            }
        });
    }
}
