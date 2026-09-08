package com.callx.app.editor;

/**
 * One clip inside the multi-clip timeline (ReelMultiClipTimelineActivity).
 * durationMs is the clip's full original duration; trimStartMs/trimEndMs
 * mark the portion of it that will actually play in the merged reel.
 */
public class TimelineClip {
    public String  uriOrPath;
    public boolean isFilePath;
    public long    durationMs;
    public long    trimStartMs;
    public long    trimEndMs;

    public TimelineClip(String uriOrPath, boolean isFilePath, long durationMs) {
        this.uriOrPath  = uriOrPath;
        this.isFilePath = isFilePath;
        this.durationMs = Math.max(200, durationMs);
        this.trimStartMs = 0;
        this.trimEndMs   = this.durationMs;
    }

    public long trimmedDurationMs() {
        return Math.max(100, trimEndMs - trimStartMs);
    }

    public android.net.Uri toUri() {
        return isFilePath ? android.net.Uri.fromFile(new java.io.File(uriOrPath)) : android.net.Uri.parse(uriOrPath);
    }
}
