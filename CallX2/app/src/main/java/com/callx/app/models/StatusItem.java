package com.callx.app.models;
public class StatusItem {
    public String id;
    public String ownerUid;
    public String ownerName;
    public String ownerPhoto;
    public String type;       // text | image | video
    public String text;
    public String mediaUrl;
    public Long timestamp;
    public Long expiresAt;
    public StatusItem() {}
}
