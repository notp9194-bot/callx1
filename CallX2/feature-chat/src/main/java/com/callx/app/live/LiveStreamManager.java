package com.callx.app.live;

import android.app.Application;
import android.view.TextureView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import im.zego.zegoexpress.ZegoExpressEngine;
import im.zego.zegoexpress.callback.IZegoEventHandler;
import im.zego.zegoexpress.constants.ZegoScenario;
import im.zego.zegoexpress.constants.ZegoViewMode;
import im.zego.zegoexpress.entity.ZegoCanvas;
import im.zego.zegoexpress.entity.ZegoEngineProfile;
import im.zego.zegoexpress.entity.ZegoUser;

/**
 * LiveStreamManager — ZegoCloud Express Engine wrapper.
 *
 * HOST flow:
 *   1. init(app)
 *   2. loginRoom(liveId, myUid, myName)
 *   3. startPreview(localTextureView)
 *   4. startPublishing(streamId)
 *
 * VIEWER flow:
 *   1. init(app)
 *   2. loginRoom(liveId, myUid, myName)
 *   3. setEventHandler(...) -> on onRoomStreamUpdate ADD, call startPlaying(streamId, remoteTextureView)
 *
 * CLEANUP (both):
 *   stopPreview() / stopPublishing() / stopPlaying(streamId) / logoutRoom() / destroy()
 */
public class LiveStreamManager {

    private static LiveStreamManager instance;
    private ZegoExpressEngine engine;
    private boolean initialized = false;

    private LiveStreamManager() {}

    public static synchronized LiveStreamManager getInstance() {
        if (instance == null) instance = new LiveStreamManager();
        return instance;
    }

    /** Call once — Application context. */
    public void init(@NonNull Application app, @Nullable IZegoEventHandler handler) {
        if (initialized) return;

        if (ZegoConfig.APP_ID == 0L) {
            // Placeholder not configured yet — skip init, callers should check isReady()
            return;
        }

        ZegoEngineProfile profile = new ZegoEngineProfile();
        profile.appID = ZegoConfig.APP_ID;
        profile.appSign = ZegoConfig.APP_SIGN;
        profile.scenario = ZegoScenario.BROADCAST;
        profile.application = app;

        engine = ZegoExpressEngine.createEngine(profile, handler);
        initialized = true;
    }

    /** True only if AppID/AppSign are filled and engine created. */
    public boolean isReady() {
        return initialized && engine != null && ZegoConfig.APP_ID != 0L;
    }

    public void loginRoom(@NonNull String roomId, @NonNull String userId, @NonNull String userName) {
        if (!isReady()) return;
        ZegoUser user = new ZegoUser(userId, userName);
        engine.loginRoom(roomId, user);
    }

    public void logoutRoom(@NonNull String roomId) {
        if (!isReady()) return;
        engine.logoutRoom(roomId);
    }

    // ── Host: preview + publish ─────────────────────────────────────────

    public void startPreview(@NonNull TextureView localView) {
        if (!isReady()) return;
        ZegoCanvas canvas = new ZegoCanvas(localView);
        canvas.viewMode = ZegoViewMode.ASPECT_FILL;
        engine.startPreview(canvas);
    }

    public void stopPreview() {
        if (!isReady()) return;
        engine.stopPreview();
    }

    public void startPublishing(@NonNull String streamId) {
        if (!isReady()) return;
        engine.startPublishingStream(streamId);
    }

    public void stopPublishing() {
        if (!isReady()) return;
        engine.stopPublishingStream();
    }

    /** Front/back camera switch — host only. */
    public void useFrontCamera(boolean front) {
        if (!isReady()) return;
        engine.useFrontCamera(front);
    }

    // ── Viewer: play remote stream ──────────────────────────────────────

    public void startPlaying(@NonNull String streamId, @NonNull TextureView remoteView) {
        if (!isReady()) return;
        ZegoCanvas canvas = new ZegoCanvas(remoteView);
        canvas.viewMode = ZegoViewMode.ASPECT_FILL;
        engine.startPlayingStream(streamId, canvas);
    }

    public void stopPlaying(@NonNull String streamId) {
        if (!isReady()) return;
        engine.stopPlayingStream(streamId);
    }

    public void destroy() {
        if (engine != null) {
            ZegoExpressEngine.destroyEngine(null);
            engine = null;
        }
        initialized = false;
    }
}
