package com.callx.app.models;

import com.google.firebase.database.IgnoreExtraProperties;

@IgnoreExtraProperties
public class MusicTrack {
    public String  trackId;
    public String  name;
    public String  title;
    public String  artist;
    public String  genre;
    public String  mood;
    public String  language;
    public String  audioUrl;
    public String  coverUrl;
    public int     durationSec;
    public long    durationMs;
    public long    usageCount;
    public long    totalSaves;
    public long    trendingRank;
    public int     bpm;
    public boolean isOriginalSound;
    public boolean isVerified;
    public int     startSec;
    public String  uploadedByUid;
    public String  uploadedByName;
    public long    addedAt;

    public MusicTrack() {}

    public String getDisplayTitle() {
        if (title != null && !title.isEmpty()) return title;
        if (name  != null && !name.isEmpty())  return name;
        return "Unknown Sound";
    }

    public long getDurationMs() {
        if (durationMs > 0) return durationMs;
        return (long) durationSec * 1000L;
    }
}
