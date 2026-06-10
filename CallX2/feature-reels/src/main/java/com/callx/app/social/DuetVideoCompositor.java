package com.callx.app.social;

import android.util.Log;

/**
 * DuetVideoCompositor — stub that always returns false so the caller
 * falls back to the raw camera file.
 *
 * Real side-by-side compositing requires ffmpeg-kit or a native pipeline.
 * Until that dependency is resolved, duets upload as the camera recording
 * with duet metadata (duetOf, duetOfOwnerUid) saved to Firebase, which is
 * enough for the feed to render them as duets.
 */
public class DuetVideoCompositor {

    private static final String TAG = "DuetVideoCompositor";

    public boolean composite(String cameraPath, String originalUrl,
                             String outputPath, int layoutMode, float originalVolume) {
        Log.d(TAG, "composite() — compositor not available, caller will use camera file");
        return false;
    }
}
