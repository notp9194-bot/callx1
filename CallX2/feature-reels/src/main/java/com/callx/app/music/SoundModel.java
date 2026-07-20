package com.callx.app.music;

import com.google.firebase.database.IgnoreExtraProperties;

@IgnoreExtraProperties
public class SoundModel {
    public String soundId;
    public String title;
    public String artist;
    public String audioUrl;
    public String coverUrl;
    public String genre;
    public long durationMs;
    public long usageCount;
    public long createdAt;
    public int bpm;
    public boolean isTrending;
    public boolean isOriginalAudio;
    
    // Original Creator Info
    public String originalCreatorUid;
    public String originalCreatorName;
    public String originalCreatorAvatar;
    public String originalCreatorHandle;

    public SoundModel() {}
}
