package com.callx.app.youtube.home;
/** Shim — canonical adapter is in yt-core. */
public class YouTubeVideoAdapter extends com.callx.app.youtube.core.adapters.YouTubeVideoAdapter {
    public YouTubeVideoAdapter(android.content.Context ctx,
                               java.util.List<com.callx.app.youtube.core.models.YouTubeVideo> data,
                               com.callx.app.youtube.core.adapters.YouTubeVideoAdapter.OnVideoClickListener listener) {
        super(ctx, data, listener);
    }
}
