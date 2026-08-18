package com.callx.app.utils;

/**
 * Marks the CURRENT app process's start time — used to scope the
 * "Just watched" reels-grid overlay to the current session instead of a
 * fixed calendar window (see UserReelsActivity#loadWatchedReelIds() in
 * feature-reels).
 *
 * Lives in :core (not :app) because feature modules only depend on :core in
 * this project's modular architecture, and this needs to be readable from
 * feature-reels. CallxApp#onCreate() (the :app module, which does depend on
 * :core) touches getSessionStartMs() first thing on cold start specifically
 * to force this class to load — and therefore this field to initialize —
 * right at process start, rather than lazily whenever a reel first happens
 * to be watched.
 */
public final class AppSessionTracker {

    private static final long SESSION_START_MS = System.currentTimeMillis();

    private AppSessionTracker() {}

    public static long getSessionStartMs() {
        return SESSION_START_MS;
    }
}
