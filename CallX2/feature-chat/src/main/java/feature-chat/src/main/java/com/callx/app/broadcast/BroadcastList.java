package com.callx.app.broadcast;

  import java.util.Map;

  public class BroadcastList {
      public String id;
      public String name;
      public Map<String, Boolean> recipients;
      public long createdAt;
      public BroadcastList() {}
  }