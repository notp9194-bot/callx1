package com.callx.app.models;

import com.google.firebase.database.IgnoreExtraProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * StatusHighlight — A named collection of statuses pinned to a user's profile.
 * Firebase node: statusHighlights/{ownerUid}/{highlightId}
 */
@IgnoreExtraProperties
public class StatusHighlight {
    public String       highlightId;
    public String       ownerUid;
    public String       title;          // "Vacation", "Birthday", etc.
    public String       coverUrl;       // thumbnail shown on profile
    public String       coverStatusId;  // which status to use as cover
    public List<String> statusIds;      // ordered list of status IDs
    public long         createdAt;
    public long         updatedAt;
    public int          itemCount;

    public StatusHighlight() {}

    public StatusHighlight(String ownerUid, String title) {
        this.ownerUid  = ownerUid;
        this.title     = title;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("highlightId", highlightId);
        m.put("ownerUid",    ownerUid);
        m.put("title",       title != null ? title : "");
        m.put("coverUrl",    coverUrl != null ? coverUrl : "");
        m.put("coverStatusId", coverStatusId != null ? coverStatusId : "");
        m.put("statusIds",   statusIds);
        m.put("createdAt",   createdAt);
        m.put("updatedAt",   updatedAt);
        m.put("itemCount",   itemCount);
        return m;
    }
}
