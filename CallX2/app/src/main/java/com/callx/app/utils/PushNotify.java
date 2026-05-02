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
        try {
            JSONObject body = new JSONObject()
                .put("toUid",     toUid)
                .put("fromUid",   fromUid   == null ? "" : fromUid)
                .put("fromName",  fromName  == null ? "" : fromName)
                .put("type",      type      == null ? "message" : type)
                .put("text",      text      == null ? "" : text)
                .put("chatId",    chatId    == null ? "" : chatId)
                .put("messageId", messageId == null ? "" : messageId)
                .put("mediaUrl",  mediaUrl  == null ? "" : mediaUrl);
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
                                            String fromName, String text) {
        try {
            JSONObject body = new JSONObject()
                .put("toUid",    toUid    == null ? "" : toUid)
                .put("fromUid",  fromUid  == null ? "" : fromUid)
                .put("fromName", fromName == null ? "" : fromName)
                .put("text",     text     == null ? "" : text)
                .put("type",     "special_request");
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

    // ── Group call notify ─────────────────────────────────────────────────

    public static void notifyGroupCall(String groupId, String fromUid, String fromName,
                                       String callId, boolean isVideo) {
        try {
            JSONObject body = new JSONObject()
                .put("groupId",  groupId  == null ? "" : groupId)
                .put("fromUid",  fromUid  == null ? "" : fromUid)
                .put("fromName", fromName == null ? "" : fromName)
                .put("callId",   callId   == null ? "" : callId)
                .put("isVideo",  isVideo)
                .put("type",     "group_call");
            postAsync(Constants.SERVER_URL + "/notify/group", body);
        } catch (Exception e) {
            Log.w("PushNotify", "notifyGroupCall err: " + e.getMessage());
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
}
