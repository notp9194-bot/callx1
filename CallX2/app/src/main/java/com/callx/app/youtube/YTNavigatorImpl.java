package com.callx.app.youtube;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import com.callx.app.youtube.core.navigator.YTNavigator;
import com.callx.app.youtube.core.utils.YTConstants;
import com.callx.app.youtube.home.YouTubeActivity;
import com.callx.app.youtube.player.YouTubePlayerActivity;
import com.callx.app.youtube.comments.YouTubeCommentsActivity;
import com.callx.app.youtube.channel.YouTubeChannelActivity;
import com.callx.app.youtube.upload.YouTubeUploadActivity;
import com.callx.app.youtube.search.YouTubeSearchActivity;
import com.callx.app.youtube.settings.YouTubeSettingsActivity;
import com.callx.app.youtube.shorts.YouTubeShortsFragment;

/**
 * YTNavigatorImpl — Concrete implementation of YTNavigator.
 *
 * Lives in the :app module — the only module that knows all sub-modules.
 * Register in CallxApp.onCreate():
 *   YTNavigatorProvider.set(new YTNavigatorImpl());
 *
 * Sub-modules call YTNavigatorProvider.get().openXxx() without importing
 * each other's Activity classes directly.
 */
public class YTNavigatorImpl implements YTNavigator {

    @Override
    public void openPlayer(Context ctx, String videoId) {
        ctx.startActivity(new Intent(ctx, YouTubePlayerActivity.class)
            .putExtra(YTConstants.EXTRA_VIDEO_ID, videoId));
    }

    @Override
    public void openComments(Context ctx, String videoId) {
        ctx.startActivity(new Intent(ctx, YouTubeCommentsActivity.class)
            .putExtra(YTConstants.EXTRA_VIDEO_ID, videoId)
            .putExtra(YTConstants.EXTRA_OPEN_COMMENTS, true));
    }

    @Override
    public void openChannel(Context ctx, String channelUid) {
        ctx.startActivity(new Intent(ctx, YouTubeChannelActivity.class)
            .putExtra(YTConstants.EXTRA_CHANNEL_UID, channelUid));
    }

    @Override
    public void openUpload(Activity activity) {
        activity.startActivity(new Intent(activity, YouTubeUploadActivity.class));
    }

    @Override
    public void openSearch(Context ctx) {
        ctx.startActivity(new Intent(ctx, YouTubeSearchActivity.class));
    }

    @Override
    public void openSettings(Context ctx) {
        ctx.startActivity(new Intent(ctx, YouTubeSettingsActivity.class));
    }

    @Override
    public void openShorts(Context ctx, int startPosition) {
        ctx.startActivity(new Intent(ctx, YouTubeActivity.class)
            .putExtra("open_tab", "shorts")
            .putExtra(YTConstants.EXTRA_SHORTS_POS, startPosition));
    }

    @Override
    public void openLibrary(Context ctx) {
        ctx.startActivity(new Intent(ctx, YouTubeActivity.class)
            .putExtra("open_tab", "library"));
    }
}
