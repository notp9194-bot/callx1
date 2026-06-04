package com.callx.app.youtube.core.navigator;

import android.app.Activity;
import android.content.Context;
import com.callx.app.youtube.core.models.YouTubeVideo;
import com.callx.app.youtube.core.models.YouTubeChannel;

/**
 * YTNavigator — Cross-module navigation contract.
 *
 * Each yt-* module that launches another module's screen uses this interface.
 * The :app module provides a concrete implementation (YTNavigatorImpl) that
 * knows all module Activities.  Inject via DI or pass through Application class.
 *
 * This avoids direct class references between sibling modules.
 */
public interface YTNavigator {
    /** Open full-screen player for a video */
    void openPlayer(Context ctx, String videoId);

    /** Open player and scroll straight to comments */
    void openComments(Context ctx, String videoId);

    /** Open a channel's profile page */
    void openChannel(Context ctx, String channelUid);

    /** Open the upload flow */
    void openUpload(Activity activity);

    /** Open the search screen */
    void openSearch(Context ctx);

    /** Open the YouTube settings hub */
    void openSettings(Context ctx);

    /** Open the Shorts feed at a given position */
    void openShorts(Context ctx, int startPosition);

    /** Open the library (downloads, playlists, history...) */
    void openLibrary(Context ctx);
}
