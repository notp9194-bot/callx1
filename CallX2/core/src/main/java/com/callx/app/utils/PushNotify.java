package com.callx.app.utils;

import android.util.Log;
import okhttp3.*;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;

public class PushNotify {

    private static final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build();

    // ── 1:1 message / call notify ─────────────────────────────────────────

    public static void notifyUser(String toUid, String fromUid, String fromName,
                                  String type, String text) {
        try {
            JSONObject body = new JSONObject()
                .put("toUid",    toUid)
                .put("fromUid",  fromUid  == null ? "" : fromUid)
                .put("fromName", fromName == null ? "" : fromName)
                .put("type",     type)
                .put("text",     text == null ? "" : text);
            if (("call".equals(type) || "video_call".equals(type))
                    && text != null && !text.isEmpty()) {
                body.put("callId", text);
            }
            postAsync(Constants.SERVER_URL + "/notify", body);
        } catch (Exception e) {
            Log.w("PushNotify", "notifyUser err: " + e.getMessage());
        }
    }

    // ── Message notify ────────────────────────────────────────────────────

    public static void notifyMessage(String toUid, String fromUid, String fromName,
                                     String chatId, String messageId, String text,
                                     String type, String mediaUrl) {
        notifyMessage(toUid, fromUid, fromName, chatId, messageId, text, type, mediaUrl, false);
    }

    /**
     * @param isBroadcast  true when this message was delivered via a Broadcast
     *                     List (see BroadcastDeliveryWorker). The recipient
     *                     still can't see who else got it — only that THIS
     *                     message came from a broadcast, same as WhatsApp
     *                     showing a "Broadcast" tag on the notification/bubble.
     *                     Server: passed through as data.broadcast ("1"/"0")
     *                     in the FCM payload. Android: CallxMessagingService
     *                     .showMessage() reads it and prefixes the
     *                     notification subtext with "📢 Broadcast".
     */
    public static void notifyMessage(String toUid, String fromUid, String fromName,
                                     String chatId, String messageId, String text,
                                     String type, String mediaUrl, boolean isBroadcast) {
        try {
            JSONObject body = new JSONObject()
                .put("toUid",     toUid)
                .put("fromUid",   fromUid   == null ? "" : fromUid)
                .put("fromName",  fromName  == null ? "" : fromName)
                .put("type",      type      == null ? "message" : type)
                .put("text",      text      == null ? "" : text)
                .put("chatId",    chatId    == null ? "" : chatId)
                .put("messageId", messageId == null ? "" : messageId)
                .put("mediaUrl",  mediaUrl  == null ? "" : mediaUrl)
                .put("broadcast", isBroadcast);
            postAsync(Constants.SERVER_URL + "/notify", body);
        } catch (Exception e) {
            Log.w("PushNotify", "notifyMessage err: " + e.getMessage());
        }
    }

    public static void notifyTyping(String toUid, String fromUid, String fromName,
                                    String chatId) {
        notifyMessage(toUid, fromUid, fromName, chatId, "", "typing…", "typing", "");
    }

    // ── Group notify ──────────────────────────────────────────────────────

    public static void notifyGroup(String groupId, String fromUid, String fromName,
                                   String type, String text) {
        notifyGroupRich(groupId, fromUid, fromName, "", "", type, text, "");
    }

    public static void notifyGroupRich(String groupId, String fromUid, String fromName,
                                       String fromPhoto, String messageId,
                                       String type, String text, String mediaUrl) {
        try {
            JSONObject body = new JSONObject()
                .put("groupId",   groupId)
                .put("fromUid",   fromUid   == null ? "" : fromUid)
                .put("fromName",  fromName  == null ? "" : fromName)
                .put("fromPhoto", fromPhoto == null ? "" : fromPhoto)
                .put("messageId", messageId == null ? "" : messageId)
                .put("type",      type      == null ? "group_message" : type)
                .put("text",      text      == null ? "" : text)
                .put("mediaUrl",  mediaUrl  == null ? "" : mediaUrl);
            postAsync(Constants.SERVER_URL + "/notify/group", body);
        } catch (Exception e) {
            Log.w("PushNotify", "notifyGroupRich err: " + e.getMessage());
        }
    }

    // ── Status notify (production-grade) ──────────────────────────────────
    //
    // Sends a rich FCM data payload to all contacts. The server fans out to
    // each contact's FCM token. Includes:
    //   - sender photo URL → shown in BigPicture / large-icon notification
    //   - statusType       → "text" | "image" | "video"
    //   - mediaUrl         → allows the receiver to show an image preview
    //   - text             → status text or caption
    //
    // When the receiver's process is killed, FCM wakes it, CallxMessagingService
    // receives the payload, starts StatusBackgroundService with the extras, and
    // StatusNotificationHelper builds the rich notification.

    public static void notifyStatus(String fromUid, String fromName) {
        notifyStatusRich(fromUid, fromName, null, "text", null, null);
    }

    public static void notifyStatusRich(String fromUid, String fromName, String fromPhoto,
                                        String statusType, String text, String mediaUrl) {
        try {
            JSONObject body = new JSONObject()
                .put("fromUid",    fromUid    == null ? "" : fromUid)
                .put("fromName",   fromName   == null ? "" : fromName)
                .put("fromPhoto",  fromPhoto  == null ? "" : fromPhoto)
                .put("statusType", statusType == null ? "text" : statusType)
                .put("text",       text       == null ? "" : text)
                .put("mediaUrl",   mediaUrl   == null ? "" : mediaUrl);
            postAsync(Constants.SERVER_URL + "/notify/status", body);
        } catch (Exception e) {
            Log.w("PushNotify", "notifyStatus err: " + e.getMessage());
        }
    }

    // ── Special-request notify ────────────────────────────────────────────

    public static void notifySpecialRequest(String toUid, String fromUid,
                                            String fromName, String fromPhoto, String text) {
        try {
            JSONObject body = new JSONObject()
                .put("toUid",     toUid     == null ? "" : toUid)
                .put("fromUid",   fromUid   == null ? "" : fromUid)
                .put("fromName",  fromName  == null ? "" : fromName)
                .put("fromPhoto", fromPhoto == null ? "" : fromPhoto)
                .put("text",      text      == null ? "" : text)
                .put("type",      "special_request");
            postAsync(Constants.SERVER_URL + "/notify", body);
        } catch (Exception e) {
            Log.w("PushNotify", "notifySpecialRequest err: " + e.getMessage());
        }
    }

    // ── Perma-block notify ────────────────────────────────────────────────

    public static void notifyPermaBlock(String toUid, String fromUid, String fromName) {
        try {
            JSONObject body = new JSONObject()
                .put("toUid",    toUid    == null ? "" : toUid)
                .put("fromUid",  fromUid  == null ? "" : fromUid)
                .put("fromName", fromName == null ? "" : fromName)
                .put("type",     "perma_block");
            postAsync(Constants.SERVER_URL + "/notify", body);
        } catch (Exception e) {
            Log.w("PushNotify", "notifyPermaBlock err: " + e.getMessage());
        }
    }

    // ── Unblock notify ────────────────────────────────────────────────────

    public static void notifyUnblock(String toUid, String fromUid, String fromName) {
        try {
            JSONObject body = new JSONObject()
                .put("toUid",    toUid    == null ? "" : toUid)
                .put("fromUid",  fromUid  == null ? "" : fromUid)
                .put("fromName", fromName == null ? "" : fromName)
                .put("type",     "unblock_notify");
            postAsync(Constants.SERVER_URL + "/notify", body);
        } catch (Exception e) {
            Log.w("PushNotify", "notifyUnblock err: " + e.getMessage());
        }
    }

    // ── Group message notify (convenience overload) ───────────────────────

    /**
     * Send a group message push notification.
     *
     * @param groupId   Firebase group ID
     * @param fromUid   Sender UID
     * @param fromName  Sender display name
     * @param groupName Group display name (used as text context)
     * @param messageId Push key of the message
     * @param text      Message preview text
     * @param type      Message type ("text", "image", etc.)
     */
    public static void notifyGroupMessage(String groupId, String fromUid, String fromName,
                                          String groupName, String messageId,
                                          String text, String type) {
        notifyGroupRich(groupId, fromUid, fromName, "", messageId, type, text, "");
    }


    // ── Reel Notification: Like ───────────────────────────────────────────
    public static void notifyReelLike(String toUid, String fromUid, String fromName,
                                      String reelId, String reelThumb) {
        try {
            JSONObject body = new JSONObject()
                .put("toUid",     toUid     == null ? "" : toUid)
                .put("fromUid",   fromUid   == null ? "" : fromUid)
                .put("fromName",  fromName  == null ? "" : fromName)
                .put("reelId",    reelId    == null ? "" : reelId)
                .put("reelThumb", reelThumb == null ? "" : reelThumb)
                .put("type",      "like");
            postAsync(Constants.SERVER_URL + "/notify/reel", body);
        } catch (Exception e) { Log.w("PushNotify", "notifyReelLike err: " + e.getMessage()); }
    }

    // ── Reel Notification: Comment ────────────────────────────────────────
    public static void notifyReelComment(String toUid, String fromUid, String fromName,
                                         String reelId, String reelThumb,
                                         String commentId, String commentText) {
        try {
            JSONObject body = new JSONObject()
                .put("toUid",       toUid       == null ? "" : toUid)
                .put("fromUid",     fromUid     == null ? "" : fromUid)
                .put("fromName",    fromName    == null ? "" : fromName)
                .put("reelId",      reelId      == null ? "" : reelId)
                .put("reelThumb",   reelThumb   == null ? "" : reelThumb)
                .put("commentId",   commentId   == null ? "" : commentId)
                .put("commentText", commentText == null ? "" : commentText)
                .put("type",        "comment");
            postAsync(Constants.SERVER_URL + "/notify/reel", body);
        } catch (Exception e) { Log.w("PushNotify", "notifyReelComment err: " + e.getMessage()); }
    }

    // ── Reel Notification: Comment Like ───────────────────────────────────
    public static void notifyReelCommentLike(String toUid, String fromUid, String fromName,
                                             String reelId, String commentText) {
        try {
            JSONObject body = new JSONObject()
                .put("toUid",       toUid       == null ? "" : toUid)
                .put("fromUid",     fromUid     == null ? "" : fromUid)
                .put("fromName",    fromName    == null ? "" : fromName)
                .put("reelId",      reelId      == null ? "" : reelId)
                .put("commentText", commentText == null ? "" : commentText)
                .put("type",        "comment_like");
            postAsync(Constants.SERVER_URL + "/notify/reel", body);
        } catch (Exception e) { Log.w("PushNotify", "notifyReelCommentLike err: " + e.getMessage()); }
    }
    // ── Reel Notification: Repost ─────────────────────────────────────────
    /**
     * FIX: This method was called by ReelRepostWorker but was missing — causing
     * the worker to throw at runtime and enter infinite retry.
     * Sends FCM push to the original reel creator when their reel is reposted.
     */
    public static void notifyReelRepost(String toUid, String fromUid, String fromName,
                                        String reelId, String reelThumb) {
        try {
            JSONObject body = new JSONObject()
                .put("toUid",     toUid     == null ? "" : toUid)
                .put("fromUid",   fromUid   == null ? "" : fromUid)
                .put("fromName",  fromName  == null ? "" : fromName)
                .put("reelId",    reelId    == null ? "" : reelId)
                .put("reelThumb", reelThumb == null ? "" : reelThumb)
                .put("type",      "repost");
            postAsync(Constants.SERVER_URL + "/notify/reel", body);
        } catch (Exception e) {
            Log.w("PushNotify", "notifyReelRepost err: " + e.getMessage());
        }
    }



    // ── FIX-2: Missed call notify ─────────────────────────────────────────
    // Jab callee reject kare ya timeout ho — caller ko missed call notification bhejo.

    // BUG-2 FIX: callerPhoto parameter add kiya — missed call notification me avatar dikhane ke liye
    public static void notifyMissedCall(String toUid, String fromUid, String fromName,
                                        String callId, boolean isVideo) {
        notifyMissedCall(toUid, fromUid, fromName, callId, isVideo, "");
    }

    public static void notifyMissedCall(String toUid, String fromUid, String fromName,
                                        String callId, boolean isVideo, String callerPhoto) {
        try {
            JSONObject body = new JSONObject()
                .put("toUid",       toUid       == null ? "" : toUid)
                .put("fromUid",     fromUid     == null ? "" : fromUid)
                .put("fromName",    fromName    == null ? "" : fromName)
                .put("callerPhoto", callerPhoto == null ? "" : callerPhoto) // BUG-2 FIX: avatar
                .put("callerUid",   fromUid     == null ? "" : fromUid)     // legacy compat
                .put("callerName",  fromName    == null ? "" : fromName)    // legacy compat
                .put("callId",      callId      == null ? "" : callId)
                .put("isVideo",     isVideo)
                .put("type",        "missed_call");
            postAsync(Constants.SERVER_URL + "/notify", body);
        } catch (Exception e) {
            Log.w("PushNotify", "notifyMissedCall err: " + e.getMessage());
        }
    }

    // Missed group call — group ke saare members ko batao
    public static void notifyMissedGroupCall(String groupId, String fromUid, String fromName,
                                             String callId, boolean isVideo) {
        notifyMissedGroupCall(groupId, fromUid, fromName, callId, isVideo, "");
    }

    // FIX-GCALL-PHOTO: callerPhoto parameter add kiya — missed group call notification me avatar
    public static void notifyMissedGroupCall(String groupId, String fromUid, String fromName,
                                             String callId, boolean isVideo, String callerPhoto) {
        try {
            JSONObject body = new JSONObject()
                .put("groupId",     groupId     == null ? "" : groupId)
                .put("fromUid",     fromUid     == null ? "" : fromUid)
                .put("fromName",    fromName    == null ? "" : fromName)
                .put("callerPhoto", callerPhoto == null ? "" : callerPhoto) // FIX-GCALL-PHOTO
                .put("gcallCallerPhoto", callerPhoto == null ? "" : callerPhoto) // Constants.GCALL_FCM_CALLER_PHOTO
                .put("callId",      callId      == null ? "" : callId)
                .put("isVideo",     isVideo)
                .put("type",        "missed_group_call");
            postAsync(Constants.SERVER_URL + "/notify/group", body);
        } catch (Exception e) {
            Log.w("PushNotify", "notifyMissedGroupCall err: " + e.getMessage());
        }
    }

    // ── Group call notify ─────────────────────────────────────────────────

    public static void notifyGroupCall(String groupId, String fromUid, String fromName,
                                       String callId, boolean isVideo) {
        notifyGroupCall(groupId, fromUid, fromName, callId, isVideo, "");
    }

    // FIX-GCALL-PHOTO: callerPhoto parameter add kiya — incoming group call notification me avatar dikhane ke liye
    // GroupCallRingService reads Constants.GCALL_FCM_CALLER_PHOTO ("gcallCallerPhoto") from FCM data
    public static void notifyGroupCall(String groupId, String fromUid, String fromName,
                                       String callId, boolean isVideo, String callerPhoto) {
        try {
            JSONObject body = new JSONObject()
                .put("gcallGroupId",    groupId     == null ? "" : groupId)
                .put("gcallCallerUid",  fromUid     == null ? "" : fromUid)
                .put("gcallCallerName", fromName    == null ? "" : fromName)
                .put("gcallCallerPhoto", callerPhoto == null ? "" : callerPhoto)
                .put("gcallId",         callId      == null ? "" : callId)
                .put("gcallIsVideo",    isVideo)
                .put("type",            "group_call");
            postAsync(Constants.SERVER_URL + "/notify/group", body);
        } catch (Exception e) {
            Log.w("PushNotify", "notifyGroupCall err: " + e.getMessage());
        }
    }
      // ── Status Reaction notify ─────────────────────────────────────────────
      public static void notifyStatusReaction(String toUid, String fromUid, String fromName,
                                              String fromPhoto, String reaction,
                                              String ownerUid) {
          try {
              JSONObject body = new JSONObject()
                  .put("toUid",    toUid    != null ? toUid    : "")
                  .put("fromUid",  fromUid  != null ? fromUid  : "")
                  .put("fromName", fromName != null ? fromName : "")
                  .put("fromPhoto",fromPhoto!= null ? fromPhoto: "")
                  .put("reaction", reaction != null ? reaction : "❤️")
                  .put("ownerUid", ownerUid != null ? ownerUid : "")
                  .put("type", "status_reaction");
              postAsync(Constants.SERVER_URL + "/notify/status_reaction", body);
          } catch (Exception e) {
              Log.w("PushNotify", "notifyStatusReaction err: " + e.getMessage());
          }
      }

      // ── Status Reply notify ────────────────────────────────────────────────
      /**
       * Sends a background-killed-safe FCM push to the status owner when someone
       * replies to their status via StatusViewerActivity.
       *
       * Server endpoint: POST /notify  (same as chat messages — uses toUid routing)
       * FCM payload type: "status_reply"
       * On receive: CallxMessagingService.showStatusReply() builds rich notification
       *             and tapping it opens ChatActivity with the replier.
       *
       * @param toUid      status owner UID (who receives the notification)
       * @param fromUid    replier UID
       * @param fromName   replier display name
       * @param fromPhoto  replier avatar URL
       * @param replyText  the reply message text
       * @param chatId     deterministic chatId (sorted UIDs joined by "_")
       */
      public static void notifyStatusReply(String toUid, String fromUid, String fromName,
                                           String fromPhoto, String replyText, String chatId) {
          try {
              JSONObject body = new JSONObject()
                  .put("toUid",    toUid     != null ? toUid     : "")
                  .put("fromUid",  fromUid   != null ? fromUid   : "")
                  .put("fromName", fromName  != null ? fromName  : "")
                  .put("fromPhoto",fromPhoto != null ? fromPhoto : "")
                  .put("text",     replyText != null ? replyText : "")
                  .put("chatId",   chatId    != null ? chatId    : "")
                  .put("type",     "status_reply");
              postAsync(Constants.SERVER_URL + "/notify", body);
          } catch (Exception e) {
              Log.w("PushNotify", "notifyStatusReply err: " + e.getMessage());
          }
      }


      public static void notifyContactsOfNewUser(String newUid, String newName, String newPhoto) {
          try {
              JSONObject body = new JSONObject()
                  .put("newUid",   newUid   != null ? newUid   : "")
                  .put("newName",  newName  != null ? newName  : "")
                  .put("newPhoto", newPhoto != null ? newPhoto : "")
                  .put("type", "contact_join");
              postAsync(Constants.SERVER_URL + "/notify/contact_join", body);
          } catch (Exception e) {
              Log.w("PushNotify", "notifyContactsOfNewUser err: " + e.getMessage());
          }
      }

      // ── Group mention notify (high-priority) ──────────────────────────────
      public static void notifyGroupMention(String groupId, String toUid,
                                            String fromUid, String fromName,
                                            String fromPhoto, String messageText) {
          try {
              JSONObject body = new JSONObject()
                  .put("groupId",  groupId   != null ? groupId   : "")
                  .put("toUid",    toUid     != null ? toUid     : "")
                  .put("fromUid",  fromUid   != null ? fromUid   : "")
                  .put("fromName", fromName  != null ? fromName  : "")
                  .put("fromPhoto",fromPhoto != null ? fromPhoto : "")
                  .put("text",     messageText!= null ? messageText: "")
                  .put("isMention","true")
                  .put("type",     "group_message");
              postAsync(Constants.SERVER_URL + "/notify/group", body);
          } catch (Exception e) {
              Log.w("PushNotify", "notifyGroupMention err: " + e.getMessage());
          }
      }

      // ── Group member join notify ──────────────────────────────────────────
      public static void notifyGroupMemberJoined(String groupId, String groupName,
                                                 String newMemberName) {
          try {
              JSONObject body = new JSONObject()
                  .put("groupId",       groupId       != null ? groupId : "")
                  .put("groupName",     groupName     != null ? groupName : "")
                  .put("newMemberName", newMemberName != null ? newMemberName : "")
                  .put("type", "group_member_joined");
              postAsync(Constants.SERVER_URL + "/notify/group_join", body);
          } catch (Exception e) {
              Log.w("PushNotify", "notifyGroupMemberJoined err: " + e.getMessage());
          }
      }

      // ── Reel Notification: Product Tag Sale ────────────────────────────────
      public static void notifyReelProductSale(String toUid, String fromUid,
                                               String reelId, String productName,
                                               long saleAmount) {
          try {
              JSONObject body = new JSONObject()
                  .put("toUid",       toUid       != null ? toUid       : "")
                  .put("fromUid",     fromUid     != null ? fromUid     : "")
                  .put("reelId",      reelId      != null ? reelId      : "")
                  .put("productName", productName != null ? productName : "")
                  .put("saleAmount",  saleAmount)
                  .put("reel_notif_type", Constants.REEL_TYPE_PRODUCT_SALE);
              postAsync(Constants.SERVER_URL + "/notify/reel", body);
          } catch (Exception e) {
              Log.w("PushNotify", "notifyReelProductSale err: " + e.getMessage());
          }
      }

      // ── Reel Notification: Challenge Update ────────────────────────────────
      public static void notifyReelChallengeUpdate(String toUid, String challengeId,
                                                   String challengeName, String updateText) {
          try {
              JSONObject body = new JSONObject()
                  .put("toUid",          toUid          != null ? toUid          : "")
                  .put("challengeId",    challengeId    != null ? challengeId    : "")
                  .put("challenge_name", challengeName  != null ? challengeName  : "")
                  .put("update_text",    updateText     != null ? updateText     : "")
                  .put("reel_notif_type", Constants.REEL_TYPE_CHALLENGE_UPDATE);
              postAsync(Constants.SERVER_URL + "/notify/reel", body);
          } catch (Exception e) {
              Log.w("PushNotify", "notifyReelChallengeUpdate err: " + e.getMessage());
          }
      }

      // ── Reel Notification: Personalised Recommendation ─────────────────────
      public static void notifyReelRecommendation(String toUid, String reelId,
                                                  String reelThumb, String reason) {
          try {
              JSONObject body = new JSONObject()
                  .put("toUid",     toUid     != null ? toUid     : "")
                  .put("reelId",    reelId    != null ? reelId    : "")
                  .put("reelThumb", reelThumb != null ? reelThumb : "")
                  .put("reason",    reason    != null ? reason    : "")
                  .put("reel_notif_type", Constants.REEL_TYPE_RECOMMENDED);
              postAsync(Constants.SERVER_URL + "/notify/reel", body);
          } catch (Exception e) {
              Log.w("PushNotify", "notifyReelRecommendation err: " + e.getMessage());
          }
      }
  
    // ── X Feature Notifications (like, retweet, follow, reply, mention, dm…) ──
    //
    // Server: POST /notify/x
    // Android receiver: XFCMNotificationHandler.handle()  (via CallxMessagingService)
    // Background/killed-safe — FCM data-only payload, high priority.
    //
    // @param toUid          notification receiver UID
    // @param fromUid        actor UID (who liked / followed / etc.)
    // @param fromName       actor display name
    // @param fromPhoto      actor avatar URL (optional, server fetches fallback)
    // @param type           one of: like, retweet, reply, mention, quote, follow,
    //                       dm, poll_ended, list_added, space_started, close_friend_post
    // @param tweetId        target tweet ID (for like / retweet / reply / mention / quote)
    // @param conversationId DM conversation ID (for dm type)
    // @param otherUid       DM other-party UID (for dm type)
    // @param otherHandle    DM other-party @handle (for dm type)
    // @param otherPhoto     DM other-party avatar URL (for dm type)
    // @param preview        DM message preview text (for dm type)

    public static void notifyX(String toUid, String fromUid, String fromName, String fromPhoto,
                               String type, String tweetId,
                               String conversationId, String otherUid,
                               String otherHandle, String otherPhoto, String preview) {
        try {
            JSONObject body = new JSONObject()
                .put("toUid",          toUid          != null ? toUid          : "")
                .put("fromUid",        fromUid        != null ? fromUid        : "")
                .put("fromName",       fromName       != null ? fromName       : "")
                .put("fromPhoto",      fromPhoto      != null ? fromPhoto      : "")
                .put("type",           type           != null ? type           : "")
                .put("tweetId",        tweetId        != null ? tweetId        : "")
                .put("conversationId", conversationId != null ? conversationId : "")
                .put("otherUid",       otherUid       != null ? otherUid       : "")
                .put("otherHandle",    otherHandle    != null ? otherHandle    : "")
                .put("otherPhoto",     otherPhoto     != null ? otherPhoto     : "")
                .put("preview",        preview        != null ? preview        : "");
            postAsync(Constants.SERVER_URL + "/notify/x", body);
        } catch (Exception e) {
            Log.w("PushNotify", "notifyX err: " + e.getMessage());
        }
    }

    // Convenience overload — like / retweet / follow (no DM fields needed)
    public static void notifyX(String toUid, String fromUid, String fromName,
                               String fromPhoto, String type, String tweetId) {
        notifyX(toUid, fromUid, fromName, fromPhoto, type, tweetId,
                "", "", "", "", "");
    }

    // ── Reel Notification: Duet ──────────────────────────────────────────
    /**
     * Notify the original reel creator when someone duets their reel.
     *
     * Server: POST /notify/reel  {type: "duet", reel_notif_type routed by server}
     * Android receiver: CallxMessagingService → ReelFCMNotificationHandler TYPE_DUET
     * Background/killed-safe — FCM data-only, high priority.
     *
     * @param toUid      UID of the original reel creator (receives notification)
     * @param fromUid    UID of the user who made the duet
     * @param fromName   Display name of the duet creator
     * @param fromPhoto  Avatar URL of the duet creator (shown as largeIcon)
     * @param reelId     ID of the original reel that was dueted
     * @param reelThumb  Thumbnail URL of the original reel (shown in BigPicture)
     */
    public static void notifyReelDuet(String toUid, String fromUid, String fromName,
                                      String fromPhoto, String reelId, String reelThumb) {
        try {
            JSONObject body = new JSONObject()
                .put("toUid",      toUid     == null ? "" : toUid)
                .put("fromUid",    fromUid   == null ? "" : fromUid)
                .put("fromName",   fromName  == null ? "" : fromName)
                .put("fromPhoto",  fromPhoto == null ? "" : fromPhoto)
                .put("reelId",     reelId    == null ? "" : reelId)
                .put("reelThumb",  reelThumb == null ? "" : reelThumb)
                .put("type",       "duet");
            postAsync(Constants.SERVER_URL + "/notify/reel", body);
        } catch (Exception e) {
            Log.w("PushNotify", "notifyReelDuet err: " + e.getMessage());
        }
    }

    // ── Reel Notification: Stitch ─────────────────────────────────────────
    /**
     * Notify the original reel creator when someone stitches their reel.
     * Same routing as duet — server maps type: "stitch" → reel_notif_type: "stitch"
     * → ReelFCMNotificationHandler TYPE_STITCH.
     */
    public static void notifyReelStitch(String toUid, String fromUid, String fromName,
                                        String fromPhoto, String reelId, String reelThumb) {
        try {
            JSONObject body = new JSONObject()
                .put("toUid",      toUid     == null ? "" : toUid)
                .put("fromUid",    fromUid   == null ? "" : fromUid)
                .put("fromName",   fromName  == null ? "" : fromName)
                .put("fromPhoto",  fromPhoto == null ? "" : fromPhoto)
                .put("reelId",     reelId    == null ? "" : reelId)
                .put("reelThumb",  reelThumb == null ? "" : reelThumb)
                .put("type",       "stitch");
            postAsync(Constants.SERVER_URL + "/notify/reel", body);
        } catch (Exception e) {
            Log.w("PushNotify", "notifyReelStitch err: " + e.getMessage());
        }
    }

    // ── Reel Notification: Repost ─────────────────────────────────────────

    /**
     * View Once Viewed — silent push to SENDER when receiver opens the message.
     * Sender gets a heads-up notification: "Your secret message was viewed".
     *
     * This is a data-only FCM message (no system tray notification).
     * Android client's CallxMessagingService handles type="view_once_viewed"
     * and shows a quiet in-app snackbar / updates the chat bubble only.
     *
     * @param senderUid   UID of the person who SENT the view-once message
     * @param receiverUid UID of the person who just viewed it
     * @param receiverName display name of receiver (shown in notification)
     * @param chatId      chat room ID
     * @param messageId   message ID being acknowledged
     */
    public static void notifyViewOnceViewed(String senderUid, String receiverUid,
                                            String receiverName, String chatId,
                                            String messageId) {
        try {
            JSONObject body = new JSONObject()
                .put("toUid",       senderUid)
                .put("fromUid",     receiverUid  == null ? "" : receiverUid)
                .put("fromName",    receiverName == null ? "" : receiverName)
                .put("type",        "view_once_viewed")
                .put("chatId",      chatId       == null ? "" : chatId)
                .put("messageId",   messageId    == null ? "" : messageId)
                .put("text",        "")
                .put("force",       true); // bypass mute — sender always gets this
            postAsync(Constants.SERVER_URL + "/notify", body);
        } catch (Exception e) {
            Log.w("PushNotify", "notifyViewOnceViewed err: " + e.getMessage());
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private static void postAsync(String url, JSONObject body) {
        Request req = new Request.Builder()
            .url(url)
            .post(RequestBody.create(body.toString(),
                MediaType.parse("application/json")))
            .build();
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, java.io.IOException e) {
                Log.w("PushNotify", "POST fail to " + url + ": " + e.getMessage());
            }
            @Override public void onResponse(Call call, Response response) {
                if (response.body() != null) response.close();
            }
        });
    }
      // ── Duet Series episode notify ─────────────────────────────────────────
      /**
       * Notify a single subscriber that a new episode was posted in a Duet Series.
       *
       * Sent to Constants.SERVER_URL/notify/reel with type "duet_series_episode".
       * The server reads the subscriber's FCM token and sends:
       *   reel_notif_type: "duet_series_episode"
       * → CallxMessagingService → ReelFCMNotificationHandler → TYPE_DUET_SERIES_EPISODE
       */
      public static void notifyDuetSeriesEpisode(
              String toUid, String fromUid, String fromName, String fromPhoto,
              String reelId, String reelThumb,
              String seriesId, String seriesTitle, int episodeNumber) {
          try {
              JSONObject body = new JSONObject()
                  .put("toUid",          toUid)
                  .put("fromUid",        fromUid       != null ? fromUid       : "")
                  .put("fromName",       fromName      != null ? fromName      : "")
                  .put("fromPhoto",      fromPhoto     != null ? fromPhoto     : "")
                  .put("reel_id",        reelId        != null ? reelId        : "")
                  .put("reel_thumb",     reelThumb     != null ? reelThumb     : "")
                  .put("series_id",      seriesId      != null ? seriesId      : "")
                  .put("series_title",   seriesTitle   != null ? seriesTitle   : "")
                  .put("episode_number", episodeNumber)
                  .put("type",           "duet_series_episode");
              postAsync(Constants.SERVER_URL + "/notify/reel", body);
          } catch (Exception e) {
              Log.w("PushNotify", "notifyDuetSeriesEpisode err: " + e.getMessage());
          }
      }


    // ── Multi-Duet Invite Notification ────────────────────────────────────────
    /**
     * Notify a user that they have been invited to a Multi-Duet session.
     *
     * Server: POST /notify/reel  { type: "multi_duet_invite" }
     * → CallxMessagingService → ReelFCMNotificationHandler → TYPE_MULTI_DUET_INVITE
     *
     * @param toUid      UID of the invited participant
     * @param fromUid    UID of the host who sent the invite
     * @param fromName   Display name of the host
     * @param fromPhoto  Avatar URL of the host
     * @param reelId     Original reel ID for this session
     * @param sessionId  multi_duet_sessions/{sessionId} — used for deep-link
     * @param reelThumb  Thumbnail URL of the original reel (shown in notification)
     */
    public static void notifyMultiDuetInvite(String toUid, String fromUid, String fromName,
                                              String fromPhoto, String reelId,
                                              String sessionId, String reelThumb) {
        try {
            JSONObject body = new JSONObject()
                .put("toUid",      toUid      != null ? toUid      : "")
                .put("fromUid",    fromUid    != null ? fromUid    : "")
                .put("fromName",   fromName   != null ? fromName   : "")
                .put("fromPhoto",  fromPhoto  != null ? fromPhoto  : "")
                .put("reelId",     reelId     != null ? reelId     : "")
                .put("sessionId",  sessionId  != null ? sessionId  : "")
                .put("reelThumb",  reelThumb  != null ? reelThumb  : "")
                .put("type",       "multi_duet_invite");
            postAsync(Constants.SERVER_URL + "/notify/reel", body);
        } catch (Exception e) {
            Log.w("PushNotify", "notifyMultiDuetInvite err: " + e.getMessage());
        }
    }

      // ── Collab Repost Notifications ───────────────────────────────────────────

      /**
       * Sends FCM push to collaborator when User A invites them for a collab repost.
       * type = "collab_repost_invite"
       */
      public static void notifyCollabRepostInvite(
              String toUid, String fromUid, String fromName, String fromPhoto,
              String reelId, String reelThumb, String collabRepostId) {
          try {
              org.json.JSONObject body = new org.json.JSONObject()
                  .put("toUid",          safe(toUid))
                  .put("fromUid",        safe(fromUid))
                  .put("fromName",       safe(fromName))
                  .put("fromPhoto",      safe(fromPhoto))
                  .put("reelId",         safe(reelId))
                  .put("reelThumb",      safe(reelThumb))
                  .put("collabRepostId", safe(collabRepostId))
                  .put("type",           "collab_repost_invite");
              postAsync(Constants.SERVER_URL + "/notify/reel", body);
          } catch (Exception e) {
              android.util.Log.w("PushNotify", "notifyCollabRepostInvite err: " + e.getMessage());
          }
      }

      /**
       * Sends FCM push to initiator when collaborator accepts the collab repost invite.
       * type = "collab_repost_accepted"
       */
      public static void notifyCollabRepostAccepted(
              String toUid, String fromUid, String fromName, String fromPhoto,
              String reelId, String reelThumb, String collabRepostId, String newReelId) {
          try {
              org.json.JSONObject body = new org.json.JSONObject()
                  .put("toUid",          safe(toUid))
                  .put("fromUid",        safe(fromUid))
                  .put("fromName",       safe(fromName))
                  .put("fromPhoto",      safe(fromPhoto))
                  .put("reelId",         safe(reelId))
                  .put("reelThumb",      safe(reelThumb))
                  .put("collabRepostId", safe(collabRepostId))
                  .put("newReelId",      safe(newReelId))
                  .put("type",           "collab_repost_accepted");
              postAsync(Constants.SERVER_URL + "/notify/reel", body);
          } catch (Exception e) {
              android.util.Log.w("PushNotify", "notifyCollabRepostAccepted err: " + e.getMessage());
          }
      }

      /**
       * Sends FCM push to initiator when collaborator declines the collab repost invite.
       * type = "collab_repost_declined"
       */
      public static void notifyCollabRepostDeclined(
              String toUid, String fromUid, String fromName, String fromPhoto,
              String reelId, String reelThumb, String collabRepostId) {
          try {
              org.json.JSONObject body = new org.json.JSONObject()
                  .put("toUid",          safe(toUid))
                  .put("fromUid",        safe(fromUid))
                  .put("fromName",       safe(fromName))
                  .put("fromPhoto",      safe(fromPhoto))
                  .put("reelId",         safe(reelId))
                  .put("reelThumb",      safe(reelThumb))
                  .put("collabRepostId", safe(collabRepostId))
                  .put("type",           "collab_repost_declined");
              postAsync(Constants.SERVER_URL + "/notify/reel", body);
          } catch (Exception e) {
              android.util.Log.w("PushNotify", "notifyCollabRepostDeclined err: " + e.getMessage());
          }
      }

      private static String safe(String s) { return s != null ? s : ""; }

    // ── Broadcast List: delivery-complete confirmation ─────────────────────
    /**
     * Sent by BroadcastDeliveryWorker after a broadcast list fan-out finishes
     * (success OR failure). Covers the case where the sender's OTHER signed-in
     * devices (not the one running the WorkManager job) need to know delivery
     * finished — the primary device already got a local notification directly
     * from the worker with zero network dependency.
     *
     * Server: POST /notify/broadcast
     * Android receiver: CallxMessagingService → type="broadcast_message" →
     *                    BroadcastFCMHandler.handle()
     * Background/killed-safe — FCM data-only, high priority.
     *
     * @param senderUid    UID of the broadcast sender (receives this push)
     * @param listId       broadcast_lists/{senderUid}/{listId}
     * @param listName     display name of the broadcast list
     * @param delivered    number of recipients successfully delivered to
     * @param total        total recipient count for this list
     * @param skipped      number of recipients skipped (e.g. blocked sender)
     * @param status       "sent" | "failed"
     * @param msgType      "text" | "image" | "video" | "audio" | "file"
     * @param lastMessage  preview text of the message that was broadcast
     */
    public static void notifyBroadcastComplete(String senderUid, String listId, String listName,
                                                int delivered, int total, int skipped,
                                                String status, String msgType, String lastMessage) {
        try {
            JSONObject body = new JSONObject()
                .put("toUid",       safe(senderUid))
                .put("listId",      safe(listId))
                .put("listName",    listName    != null ? listName    : "Broadcast")
                .put("delivered",   delivered)
                .put("total",       total)
                .put("skipped",     skipped)
                .put("status",      status      != null ? status      : "sent")
                .put("msgType",     msgType     != null ? msgType     : "text")
                .put("lastMessage", lastMessage != null ? lastMessage : "");
            postAsync(Constants.SERVER_URL + "/notify/broadcast", body);
        } catch (Exception e) {
            Log.w("PushNotify", "notifyBroadcastComplete err: " + e.getMessage());
        }
    }
}