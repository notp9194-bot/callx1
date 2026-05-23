package com.callx.app.models;

public class XDMMessage {
    public String id;
    public String senderUid;
    public String text;
    public String mediaUrl;
    public String mediaType;  // "image" | "video"
    public long   timestamp;
    public boolean seen;
}
