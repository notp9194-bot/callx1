package com.callx.app.youtube.core.navigator;

/**
 * YTNavigatorProvider — Singleton holder for the YTNavigator implementation.
 *
 * Usage:
 *   // In Application.onCreate():
 *   YTNavigatorProvider.set(new YTNavigatorImpl(this));
 *
 *   // In any yt-* module:
 *   YTNavigatorProvider.get().openPlayer(context, videoId);
 */
public class YTNavigatorProvider {
    private static YTNavigator instance;

    public static void set(YTNavigator navigator) {
        instance = navigator;
    }

    public static YTNavigator get() {
        if (instance == null)
            throw new IllegalStateException(
                "YTNavigatorProvider not initialized. Call YTNavigatorProvider.set() in Application.onCreate()");
        return instance;
    }
}
