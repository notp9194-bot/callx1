package com.callx.app.models;

  import java.util.ArrayList;
  import java.util.HashMap;
  import java.util.List;
  import java.util.Map;

  public class XPoll {
      public String tweetId;
      public List<String>            options     = new ArrayList<>();
      public Map<String, Long>       voteCounts  = new HashMap<>();
      public Map<String, String>     userVotes   = new HashMap<>();   // uid → option
      public long                    expiresAt;
      public boolean                 expired;

      public long totalVotes() {
          long t = 0;
          for (long v : voteCounts.values()) t += v;
          return t;
      }

      public int percentFor(String option) {
          long total = totalVotes();
          if (total == 0) return 0;
          Long count = voteCounts.get(option);
          return count == null ? 0 : (int)((count * 100) / total);
      }
  }