package com.callx.app.models;

  import com.google.firebase.database.Exclude;
  import com.google.firebase.database.IgnoreExtraProperties;
  import java.util.*;

  /**
   * StatusItem v26 — Modern full-featured status model.
   *
   * NEW fields v26:
   *   pollQuestion     — poll heading text
   *   pollOptions      — List<String> of option labels
   *   pollVotes        — Map<viewerUid, optionIndex> live votes
   *   musicTitle/Artist/Url — background music for image/text
   *   questionBoxText  — for type="question_box": the ask prompt
   *   questionBoxVotes — Map<viewerUid, answerText>
   *   noteText         — short "note" shown in contact row (like IG Notes)
   */
  @IgnoreExtraProperties
  public class StatusItem {

      // ── Core identity ──────────────────────────────────────────────────
      public String  id;
      public String  ownerUid;
      public String  ownerName;
      public String  ownerPhoto;

      // ── Type ──────────────────────────────────────────────────────────
      /** "text","image","video","link","gif","sticker","reel_story","reel_clip","poll","question_box" */
      public String  type;

      // ── Content ───────────────────────────────────────────────────────
      public String  text;
      public String  caption;
      public String  mediaUrl;
      public String  thumbnailUrl;
      public int     mediaWidth;
      public int     mediaHeight;
      public int     durationSec;

      // ── Text style ────────────────────────────────────────────────────
      public String  bgColor;
      public String  bgColor2;
      public List<String> gradientColors;
      public String  fontStyle;
      public String  textColor;
      public float   textSize;
      public String  textAlign;

      // ── Timing ────────────────────────────────────────────────────────
      public Long    timestamp;
      public Long    expiresAt;
      public int     expiryHours;

      // ── State ─────────────────────────────────────────────────────────
      public Boolean deleted;

      // ── Privacy ───────────────────────────────────────────────────────
      public String       privacy;
      public List<String> privacyList;
      public boolean      isCloseFriends;

      // ── Seen / reactions / analytics ──────────────────────────────────
      public Map<String, Long>    seenBy;
      public Map<String, String>  reactions;
      public Map<String, Long>    viewDurations;
      public Map<String, String>  mentionNames;

      // ── Highlights / Archive ──────────────────────────────────────────
      public boolean isHighlighted;
      public String  highlightAlbumId;
      public String  highlightAlbumName;
      public boolean isArchived;
      public Long    archivedAt;

      // ── Stats ─────────────────────────────────────────────────────────
      public int forwardCount;

      // ── GIF / Sticker ─────────────────────────────────────────────────
      public String gifUrl;
      public String stickerUrl;

      // ── Link preview ──────────────────────────────────────────────────
      public String linkUrl;
      public String linkTitle;
      public String linkDescription;
      public String linkImageUrl;
      public String linkDomain;

      // ── Location ──────────────────────────────────────────────────────
      public String locationName;
      public double locationLat;
      public double locationLng;

      // ── NEW v26: Poll ──────────────────────────────────────────────────
      /** Poll question heading (used when type="poll") */
      public String        pollQuestion;
      /** Poll options list e.g. ["Yes","No","Maybe"] */
      public List<String>  pollOptions;
      /** Poll votes: Map<viewerUid, optionIndex> */
      public Map<String, Integer> pollVotes;

      // ── NEW v26: Music strip ───────────────────────────────────────────
      /** Background music shown as animated strip (image/text statuses) */
      public String musicTitle;
      public String musicArtist;
      public String musicUrl;

      // ── NEW v26: Question Box ─────────────────────────────────────────
      /** Prompt text for type="question_box" */
      public String questionBoxText;
      /** Submitted answers: Map<viewerUid, answerText> */
      public Map<String, String> questionBoxAnswers;

      // ── NEW v26: Notes ────────────────────────────────────────────────
      /** Short note shown in contact row header (Instagram Notes style) */
      public String noteText;

      // ── Legacy ────────────────────────────────────────────────────────
      @Deprecated public boolean closeFriendsOnly;

      // ── Computed helpers ──────────────────────────────────────────────

      @Exclude
      public int getViewCount() { return seenBy != null ? seenBy.size() : 0; }

      @Exclude
      public double getAvgViewDurationSec() {
          if (viewDurations == null || viewDurations.isEmpty()) return 0;
          long sum = 0;
          for (long d : viewDurations.values()) sum += d;
          return (double) sum / viewDurations.size() / 1000.0;
      }

      @Exclude
      public boolean hasReaction(String uid) {
          return uid != null && reactions != null && reactions.containsKey(uid);
      }

      @Exclude
      public String getReaction(String uid) {
          return (uid == null || reactions == null) ? null : reactions.get(uid);
      }

      @Exclude
      public int getReactionCount(String emoji) {
          if (emoji == null || reactions == null) return 0;
          int count = 0;
          for (String e : reactions.values()) if (emoji.equals(e)) count++;
          return count;
      }

      @Exclude
      public int getTotalReactionCount() { return reactions != null ? reactions.size() : 0; }

      /** NEW v26: Get vote count for a specific poll option index */
      @Exclude
      public int getPollVoteCount(int optionIndex) {
          if (pollVotes == null) return 0;
          int count = 0;
          for (Integer v : pollVotes.values()) if (v != null && v == optionIndex) count++;
          return count;
      }

      /** NEW v26: Total poll votes */
      @Exclude
      public int getTotalPollVotes() { return pollVotes != null ? pollVotes.size() : 0; }

      @Exclude
      public String getExpiryLabel() {
          if (expiresAt == null) return "";
          long diff = expiresAt - System.currentTimeMillis();
          if (diff <= 0) return "Expired";
          long hours = diff / 3_600_000L;
          long mins  = (diff % 3_600_000L) / 60_000L;
          if (hours >= 24) return "Expires in " + (hours / 24) + "d";
          if (hours >= 1)  return "Expires in " + hours + "h";
          return "Expires in " + mins + "m";
      }

      @Exclude
      public Map<String, Object> toMap() {
          Map<String, Object> m = new HashMap<>();
          if (id != null)               m.put("id", id);
          if (ownerUid != null)         m.put("ownerUid", ownerUid);
          if (ownerName != null)        m.put("ownerName", ownerName);
          if (ownerPhoto != null)       m.put("ownerPhoto", ownerPhoto);
          if (type != null)             m.put("type", type);
          if (text != null)             m.put("text", text);
          if (caption != null)          m.put("caption", caption);
          if (mediaUrl != null)         m.put("mediaUrl", mediaUrl);
          if (thumbnailUrl != null)     m.put("thumbnailUrl", thumbnailUrl);
          if (bgColor != null)          m.put("bgColor", bgColor);
          if (bgColor2 != null)         m.put("bgColor2", bgColor2);
          if (fontStyle != null)        m.put("fontStyle", fontStyle);
          if (textColor != null)        m.put("textColor", textColor);
          if (textSize != 0)            m.put("textSize", textSize);
          if (textAlign != null)        m.put("textAlign", textAlign);
          if (timestamp != null)        m.put("timestamp", timestamp);
          if (expiresAt != null)        m.put("expiresAt", expiresAt);
          if (privacy != null)          m.put("privacy", privacy);
          m.put("isCloseFriends",       isCloseFriends);
          if (seenBy != null)           m.put("seenBy", seenBy);
          if (reactions != null)        m.put("reactions", reactions);
          if (pollQuestion != null)     m.put("pollQuestion", pollQuestion);
          if (pollOptions != null)      m.put("pollOptions", pollOptions);
          if (pollVotes != null)        m.put("pollVotes", pollVotes);
          if (musicTitle != null)       m.put("musicTitle", musicTitle);
          if (musicArtist != null)      m.put("musicArtist", musicArtist);
          if (musicUrl != null)         m.put("musicUrl", musicUrl);
          if (questionBoxText != null)  m.put("questionBoxText", questionBoxText);
          if (questionBoxAnswers != null) m.put("questionBoxAnswers", questionBoxAnswers);
          if (noteText != null)         m.put("noteText", noteText);
          if (linkUrl != null)          m.put("linkUrl", linkUrl);
          if (linkTitle != null)        m.put("linkTitle", linkTitle);
          if (linkImageUrl != null)     m.put("linkImageUrl", linkImageUrl);
          if (locationName != null)     m.put("locationName", locationName);
          m.put("isHighlighted",        isHighlighted);
          m.put("isArchived",           isArchived);
          if (archivedAt != null)       m.put("archivedAt", archivedAt);
          return m;
      }
  }