package com.callx.app.models;

import java.util.ArrayList;
import java.util.List;

public class YouTubePlaylist {
    public String playlistId;
    public String ownerUid;
    public String title;
    public String description;
    public String thumbnailUrl;   // auto = first video thumb
    public List<String> videoIds;
    public long   videoCount;
    public long   createdAt;
    public String visibility;     // "public" | "private" | "unlisted"

    public YouTubePlaylist() {}

    public YouTubePlaylist(String playlistId, String ownerUid, String title,
                           String description, String visibility) {
        this.playlistId  = playlistId;
        this.ownerUid    = ownerUid;
        this.title       = title;
        this.description = description;
        this.visibility  = visibility;
        this.videoIds    = new ArrayList<>();
        this.videoCount  = 0;
        this.createdAt   = System.currentTimeMillis();
    }
}
