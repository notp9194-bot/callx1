package com.callx.app.models;

  /**
   * MESSAGE MODEL ADDITIONS — v2
   *
   * Add these fields to the existing Message.java model.
   * Copy-paste into Message.java after the existing pinned field.
   *
   * ┌─────────────────────────────────────────────────────────────┐
   * │  NEW FIELDS TO ADD IN Message.java                          │
   * └─────────────────────────────────────────────────────────────┘
   */
  public class Message_v2_additions {
      /*
       // ── Feature: Disappearing Messages ───────────────────────
       public Long expiresAt;   // Unix ms — 0 or null = never expires

       // ── Feature: Location Sharing ────────────────────────────
       public Double locationLat;
       public Double locationLng;
       public String locationName;

       // ── Feature: Polls ───────────────────────────────────────
       public String  pollQuestion;
       public Map<String, PollOption> pollOptions; // optId -> {text, votes}
       public Boolean pollMultiple;    // allow multiple votes
       public Boolean pollAnonymous;   // hide who voted

       // ── Feature: Sticker / GIF ───────────────────────────────
       // mediaUrl already exists — just use type="sticker" or type="gif"

       // ── Feature: Scheduled Message ───────────────────────────
       public Boolean scheduled;       // true if sent via scheduler
       public Long    scheduledFor;    // original scheduled timestamp

       // ── Feature: Mentions in group ───────────────────────────
       public List<String> mentionedUids; // UIDs mentioned in message

       // ── PollOption inner class ────────────────────────────────
       public static class PollOption {
           public String text;
           public Map<String, Boolean> votes; // uid -> true
           public PollOption() {}
       }
      */
  }