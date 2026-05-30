package com.callx.app.models;

public class XTrendingTopic {
    public String  cleanTag;     // lowercase without #, safe for Firebase key
    public String  displayTag;   // original display form e.g. "#CallX"
    public long    count24h;     // posts in last 24 hours
    public long    countAll;     // lifetime post count
    public long    lastPostAt;   // timestamp of most recent post

    public XTrendingTopic() {}
    public XTrendingTopic(String cleanTag, String displayTag, long count24h, long countAll, long lastPostAt) {
        this.cleanTag   = cleanTag;
        this.displayTag = displayTag;
        this.count24h   = count24h;
        this.countAll   = countAll;
        this.lastPostAt = lastPostAt;
    }
}
