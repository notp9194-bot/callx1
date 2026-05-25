package com.callx.app.models;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class XPoll {
    public String              tweetId;
    public List<String>        options    = new ArrayList<>();
    public Map<String, Long>   voteCounts = new HashMap<>();  // option → count
    public Map<String, String> userVotes  = new HashMap<>();  // uid → option
    public long                expiresAt;
    public boolean             expired;

    public long totalVotes() {
        long sum = 0;
        if (voteCounts != null) for (Long c : voteCounts.values()) if (c != null) sum += c;
        return sum;
    }

    public int percentFor(String option) {
        long total = totalVotes();
        if (total == 0 || voteCounts == null) return 0;
        Long c = voteCounts.get(option);
        return (int) Math.round((c != null ? c : 0) * 100.0 / total);
    }
}
