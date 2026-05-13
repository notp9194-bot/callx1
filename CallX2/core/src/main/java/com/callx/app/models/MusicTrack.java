package com.callx.app.models;

import com.google.firebase.database.IgnoreExtraProperties;

@IgnoreExtraProperties
public class MusicTrack {
    public String trackId;
    public String name;
    public String artist;
    public String genre;
    public String audioUrl;
    public String coverUrl;
    public int    durationSec;
    public long   usageCount;

    public MusicTrack() {}
}
