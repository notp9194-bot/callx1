package com.callx.app.models;
public class CallLog {
    public String id;
    public String partnerUid;
    public String partnerName;
    public String direction;   // incoming | outgoing | missed
    public String mediaType;   // audio | video
    public Long timestamp;
    public Long duration;
    public CallLog() {}
}
