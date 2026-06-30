package com.callx.app.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import java.util.HashMap;
import java.util.Map;

/**
 * XLinkPreviewHelper — Fetches OG meta tags from a URL and caches in Firebase.
 *
 * Scraping logic: LinkPreviewFetcher (core) ko delegate karta hai —
 * duplicate HTTP + regex parsing yahan remove kiya gaya hai.
 *
 * Usage:
 *   XLinkPreviewHelper.fetchPreview(context, url, preview -> { ... });
 */
public class XLinkPreviewHelper {

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
     * Fetch link preview — checks Firebase cache first, then HTTP via LinkPreviewFetcher (core).
     */
    public static void fetchPreview(Context ctx, String url, Callback callback) {
        if (url == null || url.isEmpty()) return;
        String key = XFirebaseUtils.urlToKey(url);

        // Check Firebase cache first
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
                // Cache miss — fetch from network via core LinkPreviewFetcher
                fetchFromNetwork(url, key, callback);
            }
            @Override public void onCancelled(DatabaseError e) {
                fetchFromNetwork(url, key, callback);
            }
        });
    }

    /**
     * Delegates scraping to LinkPreviewFetcher (core) and caches result in Firebase.
     */
    private static void fetchFromNetwork(String url, String key, Callback callback) {
        LinkPreviewFetcher.fetch(url, new LinkPreviewFetcher.Callback() {
            @Override
            public void onResult(LinkPreviewFetcher.Result r) {
                LinkPreview preview = new LinkPreview();
                preview.url         = r.url;
                preview.title       = r.title;
                preview.description = r.description;
                preview.imageUrl    = r.imageUrl;
                preview.domain      = r.domain;

                if (!preview.isEmpty()) {
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
            }

            @Override
            public void onError(String errorUrl) {
                // Silently ignore — no result delivered
            }
        });
    }
}
