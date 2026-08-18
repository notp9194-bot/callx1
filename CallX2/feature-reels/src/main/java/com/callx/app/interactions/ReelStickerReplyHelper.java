package com.callx.app.interactions;

import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * ReelStickerReplyHelper ── the Reels counterpart of feature-status's
 * StatusReplyBottomSheet#sendQuizAnswer / #sendCountdownSubscription /
 * #sendPollVote / #sendSliderResponse / #showForQuestion.
 *
 * A 🧠 Quiz / ⏳ Countdown / 🗳️ Poll / 🎚️ Slider / 💬 Question sticker attached
 * to a photo-slideshow reel (via StatusStickerPickerSheet in
 * ReelPhotoEditorActivity) uses the exact same StatusStickerOverlayView widget
 * Status does, so it draws and animates identically — but it previously had
 * no wiring at all on the viewing side: taps did nothing, votes were never
 * persisted, and the reel owner never found out someone answered. This class
 * gives it the same "vote persisted + DM sent to the owner" flow Status has,
 * just keyed by reelId instead of ownerUid+statusId (see the
 * FirebaseUtils#getReelSticker*Ref methods) and using the existing
 * reel→chat DM path ReelShareSheetFragment already established
 * (FirebaseUtils.getChatId + getMessagesRef + PushNotify.notifyMessage)
 * instead of feature-status's StatusItem-shaped messages/PushNotify.notifyStatusReply.
 */
public final class ReelStickerReplyHelper {

    private ReelStickerReplyHelper() {}

    /** "photoPosition_stickerIndex" — unique per sticker across all photos of one reel. */
    public static String stickerKey(int photoPosition, int stickerIndex) {
        return photoPosition + "_" + stickerIndex;
    }

    /**
     * Sticker key for a VIDEO reel — there's no photo index to combine with, so
     * this just prefixes the sticker's position in reel.stickerJson's array.
     * Kept in its own "v_" namespace so it can never collide with a photo-
     * slideshow reel's "0_0"-style keys even though both live under the same
     * FirebaseUtils.getReelSticker*Ref nodes (harmless either way since those
     * are already scoped per reelId, but this keeps the key's shape unambiguous
     * when debugging Firebase data).
     */
    public static String videoStickerKey(int stickerIndex) {
        return "v_" + stickerIndex;
    }

    // ── Question sticker: free-text reply ────────────────────────────────────
    public static void sendQuestionReply(String myUid, String ownerUid, String ownerName,
                                          String reelId, String reelThumb,
                                          String questionPrompt, String answerText) {
        if (myUid == null || ownerUid == null || reelId == null) return;
        if (myUid.equals(ownerUid)) return; // Owner cannot answer their own question sticker
        if (answerText == null || answerText.trim().isEmpty()) return;

        sendDm(myUid, ownerUid, ownerName, reelId, reelThumb,
                answerText.trim(),
                "\uD83D\uDCAC " + questionPrompt, // 💬
                "\uD83D\uDCAC Answered your question");
    }

    // ── Quiz sticker: tap-to-answer ───────────────────────────────────────────
    public static void sendQuizAnswer(String myUid, String ownerUid, String ownerName,
                                       String reelId, String reelThumb, String stickerKey,
                                       String question, String selectedOption,
                                       int selectedIndex, boolean isCorrect) {
        if (myUid == null || ownerUid == null || reelId == null) return;
        if (myUid.equals(ownerUid)) return; // Owner cannot answer their own quiz

        Map<String, Object> vote = new HashMap<>();
        vote.put("selectedIndex", selectedIndex);
        vote.put("correct",       isCorrect);
        vote.put("timestamp",     com.google.firebase.database.ServerValue.TIMESTAMP);
        FirebaseUtils.getReelStickerQuizVoteRef(reelId, stickerKey, myUid).setValue(vote);

        sendDm(myUid, ownerUid, ownerName, reelId, reelThumb,
                "\uD83E\uDDE0 Answered: " + selectedOption + (isCorrect ? " \u2713 Correct!" : " \u2717 Incorrect"),
                "\uD83E\uDDE0 " + question,
                "\uD83E\uDDE0 Answered your quiz: " + selectedOption);
    }

    // ── Countdown sticker: "🔔 Remind me" subscribe ──────────────────────────
    public static void sendCountdownSubscription(String myUid, String ownerUid, String ownerName,
                                                  String reelId, String reelThumb, String stickerKey,
                                                  String countdownLabel) {
        if (myUid == null || ownerUid == null || reelId == null) return;
        if (myUid.equals(ownerUid)) return; // Owner cannot subscribe to their own countdown

        Map<String, Object> sub = new HashMap<>();
        sub.put("timestamp", com.google.firebase.database.ServerValue.TIMESTAMP);
        FirebaseUtils.getReelStickerCountdownSubscriberRef(reelId, stickerKey, myUid).setValue(sub);

        sendDm(myUid, ownerUid, ownerName, reelId, reelThumb,
                "\uD83D\uDD14 Set a reminder for: " + countdownLabel,
                "\u23F3 " + countdownLabel, // ⏳
                "\uD83D\uDD14 Wants a reminder: " + countdownLabel);
    }

    /** Quietly removes the subscription — no DM, matching Status's unsubscribe behaviour. */
    public static void unsubscribeCountdown(String reelId, String stickerKey, String myUid) {
        if (reelId == null || myUid == null) return;
        FirebaseUtils.getReelStickerCountdownSubscriberRef(reelId, stickerKey, myUid).removeValue();
    }

    // ── Poll sticker: tap-to-vote ─────────────────────────────────────────────
    public static void sendPollVote(String myUid, String ownerUid, String ownerName,
                                     String reelId, String reelThumb, String stickerKey,
                                     String question, String selectedOption, String selectedText) {
        if (myUid == null || ownerUid == null || reelId == null) return;
        if (myUid.equals(ownerUid)) return; // Owner cannot vote on their own poll

        Map<String, Object> vote = new HashMap<>();
        vote.put("option",    selectedOption);
        vote.put("timestamp", com.google.firebase.database.ServerValue.TIMESTAMP);
        FirebaseUtils.getReelStickerPollVoteRef(reelId, stickerKey, myUid).setValue(vote);

        sendDm(myUid, ownerUid, ownerName, reelId, reelThumb,
                "\uD83D\uDDF3\uFE0F Voted: " + selectedText, // 🗳️
                "\uD83D\uDDF3\uFE0F " + question,
                "\uD83D\uDDF3\uFE0F Voted on your poll: " + selectedText);
    }

    /** Reads all votes on a reel's 🗳️ Poll sticker and returns the live A/B counts. */
    public static void readPollCounts(String reelId, String stickerKey,
                                       final PollCountsCallback callback) {
        FirebaseUtils.getReelStickerPollVotesRef(reelId, stickerKey)
                .addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                int countA = 0, countB = 0;
                for (DataSnapshot child : snap.getChildren()) {
                    String opt = child.child("option").getValue(String.class);
                    if ("A".equals(opt)) countA++;
                    else if ("B".equals(opt)) countB++;
                }
                callback.onCounts(countA, countB);
            }
            @Override public void onCancelled(DatabaseError e) { callback.onCounts(0, 0); }
        });
    }

    public interface PollCountsCallback { void onCounts(int countA, int countB); }

    // ── Slider sticker: release-to-submit ─────────────────────────────────────
    public static void sendSliderResponse(String myUid, String ownerUid, String ownerName,
                                           String reelId, String reelThumb, String stickerKey,
                                           String question, String emoji, int value) {
        if (myUid == null || ownerUid == null || reelId == null) return;
        if (myUid.equals(ownerUid)) return; // Owner cannot respond to their own slider

        Map<String, Object> resp = new HashMap<>();
        resp.put("value",     value);
        resp.put("timestamp", com.google.firebase.database.ServerValue.TIMESTAMP);
        FirebaseUtils.getReelStickerSliderResponseRef(reelId, stickerKey, myUid).setValue(resp);

        sendDm(myUid, ownerUid, ownerName, reelId, reelThumb,
                (emoji != null ? emoji : "\uD83D\uDE0D") + " Rated " + value + "%",
                "\uD83C\uDF9A\uFE0F " + question, // 🎚️
                "Rated your slider: " + value + "%");
    }

    /** Reads all responses on a reel's 🎚️ Slider sticker and returns the live average. */
    public static void readSliderAverage(String reelId, String stickerKey, final int myValue,
                                          final SliderAverageCallback callback) {
        FirebaseUtils.getReelStickerSliderResponsesRef(reelId, stickerKey)
                .addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                long total = 0; int count = 0;
                for (DataSnapshot child : snap.getChildren()) {
                    Long val = child.child("value").getValue(Long.class);
                    if (val != null) { total += val; count++; }
                }
                int avg = count > 0 ? Math.round(total / (float) count) : myValue;
                callback.onAverage(avg);
            }
            @Override public void onCancelled(DatabaseError e) { callback.onAverage(myValue); }
        });
    }

    public interface SliderAverageCallback { void onAverage(int avg); }

    // ── Shared DM sender ──────────────────────────────────────────────────────
    /**
     * Sends a quoted-reply chat DM to the reel owner — same node/shape
     * ChatRepository reads (mirrors ReelShareSheetFragment.onShareToContact's
     * message push), with a reply-quote box pointing back at the reel.
     */
    private static void sendDm(String myUid, String ownerUid, String ownerName,
                                String reelId, String reelThumb,
                                String bodyText, String replyQuoteText, String notifPreview) {
        String chatId = FirebaseUtils.getChatId(myUid, ownerUid);
        com.google.firebase.database.DatabaseReference msgRef =
                FirebaseUtils.getMessagesRef(chatId).push();
        String msgId = msgRef.getKey();
        if (msgId == null) return;

        Map<String, Object> msg = new HashMap<>();
        msg.put("id",                msgId);
        msg.put("senderId",          myUid);
        msg.put("text",              bodyText);
        msg.put("type",              "text");
        msg.put("timestamp",         com.google.firebase.database.ServerValue.TIMESTAMP);
        msg.put("seen",              false);
        msg.put("replyToType",       "reel");
        msg.put("replyToText",       replyQuoteText);
        msg.put("replyToSenderName", ownerName != null ? ownerName : "");
        msg.put("replyToId",         "reel_" + reelId);
        if (reelThumb != null && !reelThumb.isEmpty())
            msg.put("replyToMediaUrl", reelThumb);

        msgRef.setValue(msg).addOnSuccessListener(u -> {
            String myName = "";
            try { myName = FirebaseUtils.getCurrentName(); } catch (Exception ignored) {}
            if (myName == null) myName = "";
            try {
                com.callx.app.utils.PushNotify.notifyMessage(
                        ownerUid, myUid, myName, chatId, msgId, notifPreview, "text", "");
            } catch (Exception ignored) {}
        });
    }
}
