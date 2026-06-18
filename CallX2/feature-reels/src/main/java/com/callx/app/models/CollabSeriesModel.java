package com.callx.app.models;

import com.google.firebase.database.IgnoreExtraProperties;
import java.util.List;
import java.util.ArrayList;

@IgnoreExtraProperties
public class CollabSeriesModel {
    public String seriesId;
    public String title;
    public String description;
    public String coverThumb;
    public String creatorUid;
    public String creatorName;
    public List<String> collaboratorUids;
    public List<String> reelIds;
    public long createdAt;
    public long updatedAt;
    public int reelCount;
    public boolean isPublic;

    public CollabSeriesModel() {
        collaboratorUids = new ArrayList<>();
        reelIds          = new ArrayList<>();
    }

    public CollabSeriesModel(String title, String desc, String creatorUid, String creatorName) {
        this.title      = title;
        this.description= desc;
        this.creatorUid = creatorUid;
        this.creatorName= creatorName;
        this.isPublic   = true;
        this.createdAt  = System.currentTimeMillis();
        this.updatedAt  = System.currentTimeMillis();
        collaboratorUids = new ArrayList<>();
        reelIds          = new ArrayList<>();
    }
}
