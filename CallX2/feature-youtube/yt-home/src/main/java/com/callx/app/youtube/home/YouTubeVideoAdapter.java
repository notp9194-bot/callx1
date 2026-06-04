package com.callx.app.youtube.home;
/**
 * Backward-compat shim — the canonical adapter now lives in yt-core.
 * Import com.callx.app.youtube.core.adapters.YouTubeVideoAdapter directly.
 */
public class YouTubeVideoAdapter extends com.callx.app.youtube.core.adapters.YouTubeVideoAdapter {
    public YouTubeVideoAdapter(android.content.Context ctx, java.util.List<com.callx.app.youtube.core.models.YouTubeVideo> data) {
        super(ctx, data);
    }
}
