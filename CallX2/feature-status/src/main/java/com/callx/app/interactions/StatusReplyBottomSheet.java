package com.callx.app.interactions;
import android.content.Context;
import android.graphics.Color;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.util.HashMap;
import java.util.Map;
import com.callx.app.viewer.StatusViewerActivity;
/**
 * StatusReplyBottomSheet v25 — Full reply sheet with status preview thumbnail.
 * FIX: Was missing entirely — only inline EditText existed in StatusViewerActivity.
 *      Now a dedicated BottomSheet that pauses the status and gives a focused reply UI.
 * NEW: Shows the status being replied to (thumbnail/text preview).
 * NEW: Keyboard auto-shows on open.
 * NEW: Sends reply as chat message with replyToType metadata.
 * NEW: Sends push notification to status owner.
 */
public class StatusReplyBottomSheet {
    public interface OnReplySentListener {
        void onSent(String message);
    }
    public static void show(Context ctx, StatusItem item, String ownerName,
                            String myUid, String ownerUid,
                            OnReplySentListener listener) {
        if (item == null || myUid == null || ownerUid == null) return;
        if (myUid.equals(ownerUid)) return; // Owner cannot reply to own status
        BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 24));
        // Status preview card
        LinearLayout preview = new LinearLayout(ctx);
        preview.setOrientation(LinearLayout.HORIZONTAL);
        preview.setGravity(android.view.Gravity.CENTER_VERTICAL);
        preview.setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12));
        preview.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        android.graphics.drawable.GradientDrawable previewBg =
            new android.graphics.drawable.GradientDrawable();
        previewBg.setCornerRadius(dp(ctx, 12));
        previewBg.setColor(Color.parseColor("#F5F5F5"));
        preview.setBackground(previewBg);
        // Thumbnail or type icon
        if (("image".equals(item.type) || "video".equals(item.type))
                && (item.thumbnailUrl != null || item.mediaUrl != null)) {
            ImageView thumb = new ImageView(ctx);
            int sz = dp(ctx, 52);
            thumb.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            android.graphics.drawable.GradientDrawable thumbBg =
                new android.graphics.drawable.GradientDrawable();
            thumbBg.setCornerRadius(dp(ctx, 8));
            thumbBg.setColor(Color.DKGRAY);
            thumb.setBackground(thumbBg);
            String url = item.thumbnailUrl != null ? item.thumbnailUrl : item.mediaUrl;
            Glide.with(ctx).load(url).centerCrop().override(480, 853).into(thumb);
            preview.addView(thumb);
        } else {
            TextView typeIcon = new TextView(ctx);
            typeIcon.setText("text".equals(item.type) ? "💬" : "link".equals(item.type) ? "🔗" : "📷");
            typeIcon.setTextSize(26);
            int sz = dp(ctx, 52);
            typeIcon.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
            typeIcon.setGravity(android.view.Gravity.CENTER);
            android.graphics.drawable.GradientDrawable tbg =
                new android.graphics.drawable.GradientDrawable();
            tbg.setCornerRadius(dp(ctx, 8));
            tbg.setColor(Color.parseColor("#EDE7F6"));
            typeIcon.setBackground(tbg);
            preview.addView(typeIcon);
        }
        // Preview text
        LinearLayout previewInfo = new LinearLayout(ctx);
        previewInfo.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        infoLp.setMarginStart(dp(ctx, 12));
        previewInfo.setLayoutParams(infoLp);
        TextView tvTo = new TextView(ctx);
        tvTo.setText("Replying to " + (ownerName != null ? ownerName : "status"));
        tvTo.setTextSize(12);
        tvTo.setTextColor(Color.parseColor("#6200EE"));
        tvTo.setTypeface(null, android.graphics.Typeface.BOLD);
        previewInfo.addView(tvTo);
        TextView tvPreview = new TextView(ctx);
        String previewText = getPreviewText(item);
        tvPreview.setText(previewText);
        tvPreview.setTextSize(13);
        tvPreview.setTextColor(Color.DKGRAY);
        tvPreview.setMaxLines(2);
        tvPreview.setEllipsize(android.text.TextUtils.TruncateAt.END);
        previewInfo.addView(tvPreview);
        preview.addView(previewInfo);
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        previewLp.bottomMargin = dp(ctx, 16);
        preview.setLayoutParams(previewLp);
        root.addView(preview);
        // Reply input row
        LinearLayout inputRow = new LinearLayout(ctx);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        EditText et = new EditText(ctx);
        et.setHint("Write a reply…");
        et.setMaxLines(3);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        et.setLayoutParams(etLp);
        inputRow.addView(et);
        ImageButton sendBtn = new ImageButton(ctx);
        sendBtn.setImageResource(android.R.drawable.ic_menu_send);
        int btnSz = dp(ctx, 48);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(btnSz, btnSz);
        btnLp.setMarginStart(dp(ctx, 8));
        sendBtn.setLayoutParams(btnLp);
        sendBtn.setBackground(null);
        sendBtn.setOnClickListener(v -> {
            String msg = et.getText() != null ? et.getText().toString().trim() : "";
            if (msg.isEmpty()) { et.setError("Enter a message"); return; }
            sendReply(myUid, ownerUid, ownerName, item, msg);
            if (listener != null) listener.onSent(msg);
            Toast.makeText(ctx, "Reply sent ✓", Toast.LENGTH_SHORT).show();
            sheet.dismiss();
        });
        inputRow.addView(sendBtn);
        root.addView(inputRow);
        sheet.setContentView(root);
        // Expand fully and show keyboard
        sheet.setOnShowListener(d -> {
            BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(
                    (View) root.getParent());
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            et.requestFocus();
            InputMethodManager imm = (InputMethodManager)
                    ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null)
                imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
        });
        sheet.show();
    }
    /**
     * Label shown in the quote box's sender-name slot for a status
     * reply/reaction chat bubble. Deliberately different from how a normal
     * quoted-message reply looks (which just shows the sender's bare name)
     * so a glance at the chat makes clear this bubble refers to a status,
     * not a reply to another chat message — matches the "you can tell at a
     * glance" affordance WhatsApp's story-reply bubbles have.
     */
    public static String statusReplyLabel(String ownerName) {
        if (ownerName == null || ownerName.isEmpty()) return "\uD83D\uDCF7 Status"; // 📷
        return "\uD83D\uDCF7 " + ownerName + "'s Status"; // 📷
    }
    public static String getPreviewText(StatusItem item) {
        if (item == null) return "Status";
        if ("image".equals(item.type))  return "📷 Photo status";
        if ("video".equals(item.type))  return "🎥 Video status";
        if ("gif".equals(item.type))    return "GIF";
        if ("link".equals(item.type))   return "🔗 " + (item.linkTitle != null ? item.linkTitle : item.linkUrl);
        if (item.caption != null && !item.caption.isEmpty()) return item.caption;
        if (item.text    != null && !item.text.isEmpty())    return item.text;
        return "Status";
    }
    private static void sendReply(String myUid, String ownerUid, String ownerName,
                                   StatusItem item, String message) {
        String chatId = myUid.compareTo(ownerUid) < 0
                ? myUid + "_" + ownerUid : ownerUid + "_" + myUid;
        String msgId = FirebaseUtils.db().getReference().push().getKey();
        if (msgId == null) return;
        Map<String, Object> msg = new HashMap<>();
        msg.put("id",                  msgId);
        msg.put("senderId",            myUid);
        msg.put("text",                message);
        msg.put("type",                "text");
        msg.put("timestamp",           com.google.firebase.database.ServerValue.TIMESTAMP);
        msg.put("seen",                false);
        msg.put("replyToType",         item.type != null ? item.type : "text");
        msg.put("replyToText",         getPreviewText(item));
        msg.put("replyToSenderName",   statusReplyLabel(ownerName));
        msg.put("replyToId",           "status_" + (item.id != null ? item.id : "unknown"));
        if (item.thumbnailUrl != null)
            msg.put("replyToMediaUrl", item.thumbnailUrl);
        else if ("image".equals(item.type) && item.mediaUrl != null)
            msg.put("replyToMediaUrl", item.mediaUrl);
        FirebaseUtils.db()
            // BUG FIX: was writing to "chats/{chatId}/messages/{msgId}", but
            // ChatRepository (and every other send path in the app) reads/
            // listens on "messages/{chatId}/{msgId}" — a completely
            // different node. That mismatch meant a status reply silently
            // vanished into a path nobody ever read: it never appeared in
            // the sender's own chat thread, and — more importantly — the
            // status owner never saw it either. Writing to the correct
            // node is what actually makes it show up like a normal WhatsApp
            // reply-to-status chat bubble on both ends.
            .getReference("messages").child(chatId).child(msgId)
            .setValue(msg)
            .addOnSuccessListener(u ->
                FirebaseUtils.db().getReference("users").child(myUid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(DataSnapshot snap) {
                            String name  = snap.child("name").getValue(String.class);
                            String photo = snap.child("thumbUrl").getValue(String.class);
                            if (photo == null) photo = snap.child("photoUrl").getValue(String.class);
                            try {
                                com.callx.app.utils.PushNotify.notifyStatusReply(
                                        ownerUid, myUid,
                                        name != null ? name : "Someone",
                                        photo != null ? photo : "",
                                        message, chatId);
                            } catch (Exception ignored) {}
                            // Instagram-style: also publish a public preview on the
                            // status node itself so the OWNER sees this commenter's
                            // avatar + text as a bottom-left overlay next time they
                            // view this status (StatusViewerActivity), on top of the
                            // private chat DM above. See FirebaseUtils#getStatusRepliesRef.
                            if (item.id != null && !item.id.isEmpty()) {
                                String replyId = FirebaseUtils.getStatusRepliesRef(ownerUid, item.id)
                                        .push().getKey();
                                if (replyId != null) {
                                    Map<String, Object> preview = new HashMap<>();
                                    preview.put("uid",       myUid);
                                    preview.put("name",      name != null ? name : "Someone");
                                    preview.put("avatarUrl", photo != null ? photo : "");
                                    preview.put("text",      message);
                                    preview.put("timestamp", ServerValue.TIMESTAMP);
                                    FirebaseUtils.getStatusRepliesRef(ownerUid, item.id)
                                            .child(replyId).setValue(preview)
                                            .addOnFailureListener(err ->
                                                android.util.Log.e("StatusReply",
                                                    "Failed to publish public reply preview for status "
                                                        + item.id + ": " + err.getMessage(), err));
                                }
                            }
                        }
                        @Override public void onCancelled(DatabaseError e) {}
                    }));
    }
    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    // ── Question-sticker tap-to-answer ──────────────────────────────────────
    /**
     * Shown when a viewer taps a 💬 Question sticker on someone's status
     * (see StatusStickerOverlayView / StatusViewerActivity's sticker overlay).
     * Same underlying send path as {@link #show}, just a focused UI that
     * leads with the actual question instead of a generic status preview,
     * and tags the resulting chat bubble as an answer to that question.
     */
    public static void showForQuestion(Context ctx, StatusItem item, String ownerName,
                                        String myUid, String ownerUid, String questionPrompt,
                                        OnReplySentListener listener) {
        if (item == null || myUid == null || ownerUid == null) return;
        if (myUid.equals(ownerUid)) return; // Owner cannot answer their own question

        final String prompt = (questionPrompt == null || questionPrompt.isEmpty())
                ? "Ask me anything!" : questionPrompt;

        BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 24));

        TextView tvTo = new TextView(ctx);
        tvTo.setText("Answering " + (ownerName != null ? ownerName : "status") + "'s question");
        tvTo.setTextSize(12);
        tvTo.setTextColor(Color.parseColor("#00C897"));
        tvTo.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(tvTo);

        TextView tvQuestion = new TextView(ctx);
        tvQuestion.setText("\uD83D\uDCAC " + prompt); // 💬
        tvQuestion.setTextSize(15);
        tvQuestion.setTextColor(Color.parseColor("#212121"));
        tvQuestion.setMaxLines(3);
        LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        qLp.topMargin = dp(ctx, 4);
        qLp.bottomMargin = dp(ctx, 16);
        root.addView(tvQuestion, qLp);

        LinearLayout inputRow = new LinearLayout(ctx);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        EditText et = new EditText(ctx);
        et.setHint("Type your answer…");
        et.setMaxLines(3);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        et.setLayoutParams(etLp);
        inputRow.addView(et);

        ImageButton sendBtn = new ImageButton(ctx);
        sendBtn.setImageResource(android.R.drawable.ic_menu_send);
        int btnSz = dp(ctx, 48);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(btnSz, btnSz);
        btnLp.setMarginStart(dp(ctx, 8));
        sendBtn.setLayoutParams(btnLp);
        sendBtn.setBackground(null);
        sendBtn.setOnClickListener(v -> {
            String answer = et.getText() != null ? et.getText().toString().trim() : "";
            if (answer.isEmpty()) { et.setError("Type an answer"); return; }
            sendQuestionReply(myUid, ownerUid, ownerName, item, prompt, answer);
            if (listener != null) listener.onSent(answer);
            Toast.makeText(ctx, "Answer sent ✓", Toast.LENGTH_SHORT).show();
            sheet.dismiss();
        });
        inputRow.addView(sendBtn);
        root.addView(inputRow);

        sheet.setContentView(root);
        sheet.setOnShowListener(d -> {
            BottomSheetBehavior<View> behavior = BottomSheetBehavior.from((View) root.getParent());
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            et.requestFocus();
            InputMethodManager imm = (InputMethodManager)
                    ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null)
                imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
        });
        sheet.show();
    }

    private static void sendQuestionReply(String myUid, String ownerUid, String ownerName,
                                           StatusItem item, String questionPrompt, String answer) {
        String chatId = myUid.compareTo(ownerUid) < 0
                ? myUid + "_" + ownerUid : ownerUid + "_" + myUid;
        String msgId = FirebaseUtils.db().getReference().push().getKey();
        if (msgId == null) return;
        Map<String, Object> msg = new HashMap<>();
        msg.put("id",                  msgId);
        msg.put("senderId",            myUid);
        msg.put("text",                answer);
        msg.put("type",                "text");
        msg.put("timestamp",           com.google.firebase.database.ServerValue.TIMESTAMP);
        msg.put("seen",                false);
        // Quote box shows the question itself (not the status's generic
        // preview text) so it's obvious in-chat this is an answer, not a
        // regular status reply — same quote-bubble fields the chat's
        // ReplyDataMapper/MessageBubbleCanvasView already renders for any
        // replyToType, so no chat-side changes needed for this to display.
        msg.put("replyToType",         item.type != null ? item.type : "text");
        msg.put("replyToText",         "\uD83D\uDCAC " + questionPrompt); // 💬
        msg.put("replyToSenderName",   statusReplyLabel(ownerName));
        msg.put("replyToId",           "status_" + (item.id != null ? item.id : "unknown"));
        if (item.thumbnailUrl != null)
            msg.put("replyToMediaUrl", item.thumbnailUrl);
        else if ("image".equals(item.type) && item.mediaUrl != null)
            msg.put("replyToMediaUrl", item.mediaUrl);
        FirebaseUtils.db()
            .getReference("messages").child(chatId).child(msgId)
            .setValue(msg)
            .addOnSuccessListener(u ->
                FirebaseUtils.db().getReference("users").child(myUid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(DataSnapshot snap) {
                            String name  = snap.child("name").getValue(String.class);
                            String photo = snap.child("thumbUrl").getValue(String.class);
                            if (photo == null) photo = snap.child("photoUrl").getValue(String.class);
                            try {
                                com.callx.app.utils.PushNotify.notifyStatusReply(
                                        ownerUid, myUid,
                                        name != null ? name : "Someone",
                                        photo != null ? photo : "",
                                        "\uD83D\uDCAC Answered: " + answer, chatId);
                            } catch (Exception ignored) {}
                        }
                        @Override public void onCancelled(DatabaseError e) {}
                    }));
    }

    // ── Quiz-sticker tap-to-answer ───────────────────────────────────────────
    /**
     * Called when a viewer taps an option on a 🧠 Quiz sticker (see
     * StatusStickerOverlayView#setOnQuizOptionSelectedListener /
     * StatusViewerActivity's sticker overlay). Same underlying DM + notify
     * path as {@link #showForQuestion}'s answer flow, plus persists the vote
     * under FirebaseUtils.getStatusQuizVoteRef so re-opening the status
     * restores the viewer's locked-in answer instead of asking again.
     */
    public static void sendQuizAnswer(String myUid, String ownerUid, String ownerName,
                                       StatusItem item, int stickerIndex, String question,
                                       String selectedOption, int selectedIndex, boolean isCorrect) {
        if (item == null || myUid == null || ownerUid == null) return;
        if (myUid.equals(ownerUid)) return; // Owner cannot answer their own quiz

        // 1) Persist the vote so this quiz sticker only asks once per viewer.
        Map<String, Object> vote = new HashMap<>();
        vote.put("selectedIndex", selectedIndex);
        vote.put("correct",       isCorrect);
        vote.put("timestamp",     com.google.firebase.database.ServerValue.TIMESTAMP);
        FirebaseUtils.getStatusQuizVoteRef(ownerUid, item.id != null ? item.id : "unknown",
                stickerIndex, myUid).setValue(vote);

        // 2) Send the answer to the owner as a quoted chat DM — same node/shape
        //    ChatRepository reads, same as every other status-reply path.
        String chatId = myUid.compareTo(ownerUid) < 0
                ? myUid + "_" + ownerUid : ownerUid + "_" + myUid;
        String msgId = FirebaseUtils.db().getReference().push().getKey();
        if (msgId == null) return;
        Map<String, Object> msg = new HashMap<>();
        msg.put("id",                  msgId);
        msg.put("senderId",            myUid);
        msg.put("text",                "\uD83E\uDDE0 Answered: " + selectedOption
                                         + (isCorrect ? " \u2713 Correct!" : " \u2717 Incorrect"));
        msg.put("type",                "text");
        msg.put("timestamp",           com.google.firebase.database.ServerValue.TIMESTAMP);
        msg.put("seen",                false);
        msg.put("replyToType",         item.type != null ? item.type : "text");
        msg.put("replyToText",         "\uD83E\uDDE0 " + question); // 🧠
        msg.put("replyToSenderName",   statusReplyLabel(ownerName));
        msg.put("replyToId",           "status_" + (item.id != null ? item.id : "unknown"));
        if (item.thumbnailUrl != null)
            msg.put("replyToMediaUrl", item.thumbnailUrl);
        else if ("image".equals(item.type) && item.mediaUrl != null)
            msg.put("replyToMediaUrl", item.mediaUrl);

        FirebaseUtils.db()
            .getReference("messages").child(chatId).child(msgId)
            .setValue(msg)
            .addOnSuccessListener(u ->
                FirebaseUtils.db().getReference("users").child(myUid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(DataSnapshot snap) {
                            String name  = snap.child("name").getValue(String.class);
                            String photo = snap.child("thumbUrl").getValue(String.class);
                            if (photo == null) photo = snap.child("photoUrl").getValue(String.class);
                            try {
                                com.callx.app.utils.PushNotify.notifyStatusReply(
                                        ownerUid, myUid,
                                        name != null ? name : "Someone",
                                        photo != null ? photo : "",
                                        "\uD83E\uDDE0 Answered your quiz: " + selectedOption, chatId);
                            } catch (Exception ignored) {}
                        }
                        @Override public void onCancelled(DatabaseError e) {}
                    }));
    }

    // ── Countdown "Remind me" subscribe ──────────────────────────────────────
    /**
     * Called when a viewer subscribes to a ⏳ Countdown sticker's reminder (see
     * StatusStickerOverlayView#setOnCountdownSubscribeToggleListener /
     * StatusViewerActivity's sticker overlay). Same DM + notify path as the
     * question/quiz flows, plus persists the subscription under
     * FirebaseUtils.getStatusCountdownSubscriberRef. Unsubscribing is a plain
     * ref delete handled directly by the caller — no DM is sent for that, to
     * avoid spamming the poster every time a viewer changes their mind.
     */
    public static void sendCountdownSubscription(String myUid, String ownerUid, String ownerName,
                                                  StatusItem item, int stickerIndex, String countdownLabel) {
        if (item == null || myUid == null || ownerUid == null) return;
        if (myUid.equals(ownerUid)) return; // Owner cannot subscribe to their own countdown

        final String label = (countdownLabel == null || countdownLabel.isEmpty())
                ? "Countdown" : countdownLabel;

        // 1) Persist the subscription so re-opening the status restores the bell state.
        Map<String, Object> sub = new HashMap<>();
        sub.put("subscribed", true);
        sub.put("timestamp",  com.google.firebase.database.ServerValue.TIMESTAMP);
        FirebaseUtils.getStatusCountdownSubscriberRef(ownerUid, item.id != null ? item.id : "unknown",
                stickerIndex, myUid).setValue(sub);

        // 2) Let the poster know, as a quoted chat DM — same node/shape as every other status-reply path.
        String chatId = myUid.compareTo(ownerUid) < 0
                ? myUid + "_" + ownerUid : ownerUid + "_" + myUid;
        String msgId = FirebaseUtils.db().getReference().push().getKey();
        if (msgId == null) return;
        Map<String, Object> msg = new HashMap<>();
        msg.put("id",                  msgId);
        msg.put("senderId",            myUid);
        msg.put("text",                "\uD83D\uDD14 Set a reminder for: " + label);
        msg.put("type",                "text");
        msg.put("timestamp",           com.google.firebase.database.ServerValue.TIMESTAMP);
        msg.put("seen",                false);
        msg.put("replyToType",         item.type != null ? item.type : "text");
        msg.put("replyToText",         "\u23F0 " + label); // ⏰
        msg.put("replyToSenderName",   statusReplyLabel(ownerName));
        msg.put("replyToId",           "status_" + (item.id != null ? item.id : "unknown"));
        if (item.thumbnailUrl != null)
            msg.put("replyToMediaUrl", item.thumbnailUrl);
        else if ("image".equals(item.type) && item.mediaUrl != null)
            msg.put("replyToMediaUrl", item.mediaUrl);

        FirebaseUtils.db()
            .getReference("messages").child(chatId).child(msgId)
            .setValue(msg)
            .addOnSuccessListener(u ->
                FirebaseUtils.db().getReference("users").child(myUid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(DataSnapshot snap) {
                            String name  = snap.child("name").getValue(String.class);
                            String photo = snap.child("thumbUrl").getValue(String.class);
                            if (photo == null) photo = snap.child("photoUrl").getValue(String.class);
                            try {
                                com.callx.app.utils.PushNotify.notifyStatusReply(
                                        ownerUid, myUid,
                                        name != null ? name : "Someone",
                                        photo != null ? photo : "",
                                        "\uD83D\uDD14 Wants a reminder about: " + label, chatId);
                            } catch (Exception ignored) {}
                        }
                        @Override public void onCancelled(DatabaseError e) {}
                    }));
    }

    // ── Poll-sticker tap-to-vote ──────────────────────────────────────────────
    /**
     * Called when a viewer taps an option on a 🗳️ Poll sticker (see
     * StatusStickerOverlayView#setOnPollOptionSelectedListener / StatusViewerActivity's
     * sticker overlay). Same DM + notify path as the quiz/countdown flows, plus persists
     * the vote under FirebaseUtils.getStatusPollVoteRef so re-opening the status restores
     * this viewer's locked-in choice instead of asking again.
     */
    public static void sendPollVote(String myUid, String ownerUid, String ownerName,
                                     StatusItem item, int stickerIndex, String question,
                                     String selectedOption, String selectedText) {
        if (item == null || myUid == null || ownerUid == null) return;
        if (myUid.equals(ownerUid)) return; // Owner cannot vote on their own poll

        // 1) Persist the vote so this poll sticker only asks once per viewer.
        Map<String, Object> vote = new HashMap<>();
        vote.put("option",    selectedOption); // "A" | "B"
        vote.put("timestamp", com.google.firebase.database.ServerValue.TIMESTAMP);
        FirebaseUtils.getStatusPollVoteRef(ownerUid, item.id != null ? item.id : "unknown",
                stickerIndex, myUid).setValue(vote);

        // 2) Send the vote to the owner as a quoted chat DM.
        String chatId = myUid.compareTo(ownerUid) < 0
                ? myUid + "_" + ownerUid : ownerUid + "_" + myUid;
        String msgId = FirebaseUtils.db().getReference().push().getKey();
        if (msgId == null) return;
        Map<String, Object> msg = new HashMap<>();
        msg.put("id",                  msgId);
        msg.put("senderId",            myUid);
        msg.put("text",                "\uD83D\uDDF3\uFE0F Voted: " + selectedText); // 🗳️
        msg.put("type",                "text");
        msg.put("timestamp",           com.google.firebase.database.ServerValue.TIMESTAMP);
        msg.put("seen",                false);
        msg.put("replyToType",         item.type != null ? item.type : "text");
        msg.put("replyToText",         "\uD83D\uDDF3\uFE0F " + question);
        msg.put("replyToSenderName",   statusReplyLabel(ownerName));
        msg.put("replyToId",           "status_" + (item.id != null ? item.id : "unknown"));
        if (item.thumbnailUrl != null)
            msg.put("replyToMediaUrl", item.thumbnailUrl);
        else if ("image".equals(item.type) && item.mediaUrl != null)
            msg.put("replyToMediaUrl", item.mediaUrl);

        FirebaseUtils.db()
            .getReference("messages").child(chatId).child(msgId)
            .setValue(msg)
            .addOnSuccessListener(u ->
                FirebaseUtils.db().getReference("users").child(myUid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(DataSnapshot snap) {
                            String name  = snap.child("name").getValue(String.class);
                            String photo = snap.child("thumbUrl").getValue(String.class);
                            if (photo == null) photo = snap.child("photoUrl").getValue(String.class);
                            try {
                                com.callx.app.utils.PushNotify.notifyStatusReply(
                                        ownerUid, myUid,
                                        name != null ? name : "Someone",
                                        photo != null ? photo : "",
                                        "\uD83D\uDDF3\uFE0F Voted on your poll: " + selectedText, chatId);
                            } catch (Exception ignored) {}
                        }
                        @Override public void onCancelled(DatabaseError e) {}
                    }));
    }

    // ── Slider-sticker drag-to-submit ─────────────────────────────────────────
    /**
     * Called when a viewer releases the thumb on a 🎚️ Slider sticker (see
     * StatusStickerOverlayView#setOnSliderValueSubmittedListener / StatusViewerActivity's
     * sticker overlay). Same DM + notify path as the poll/quiz flows, plus persists the
     * response under FirebaseUtils.getStatusSliderResponseRef so re-opening the status
     * restores this viewer's locked-in value instead of asking again.
     */
    public static void sendSliderResponse(String myUid, String ownerUid, String ownerName,
                                           StatusItem item, int stickerIndex, String question,
                                           String emoji, int value) {
        if (item == null || myUid == null || ownerUid == null) return;
        if (myUid.equals(ownerUid)) return; // Owner cannot respond to their own slider

        // 1) Persist the response so this slider sticker only asks once per viewer.
        Map<String, Object> resp = new HashMap<>();
        resp.put("value",     value);
        resp.put("timestamp", com.google.firebase.database.ServerValue.TIMESTAMP);
        FirebaseUtils.getStatusSliderResponseRef(ownerUid, item.id != null ? item.id : "unknown",
                stickerIndex, myUid).setValue(resp);

        // 2) Send the rating to the owner as a quoted chat DM.
        String chatId = myUid.compareTo(ownerUid) < 0
                ? myUid + "_" + ownerUid : ownerUid + "_" + myUid;
        String msgId = FirebaseUtils.db().getReference().push().getKey();
        if (msgId == null) return;
        Map<String, Object> msg = new HashMap<>();
        msg.put("id",                  msgId);
        msg.put("senderId",            myUid);
        msg.put("text",                "\uD83C\uDF9A\uFE0F Rated " + emoji + " " + value + "%");
        msg.put("type",                "text");
        msg.put("timestamp",           com.google.firebase.database.ServerValue.TIMESTAMP);
        msg.put("seen",                false);
        msg.put("replyToType",         item.type != null ? item.type : "text");
        msg.put("replyToText",         "\uD83C\uDF9A\uFE0F " + question);
        msg.put("replyToSenderName",   statusReplyLabel(ownerName));
        msg.put("replyToId",           "status_" + (item.id != null ? item.id : "unknown"));
        if (item.thumbnailUrl != null)
            msg.put("replyToMediaUrl", item.thumbnailUrl);
        else if ("image".equals(item.type) && item.mediaUrl != null)
            msg.put("replyToMediaUrl", item.mediaUrl);

        FirebaseUtils.db()
            .getReference("messages").child(chatId).child(msgId)
            .setValue(msg)
            .addOnSuccessListener(u ->
                FirebaseUtils.db().getReference("users").child(myUid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(DataSnapshot snap) {
                            String name  = snap.child("name").getValue(String.class);
                            String photo = snap.child("thumbUrl").getValue(String.class);
                            if (photo == null) photo = snap.child("photoUrl").getValue(String.class);
                            try {
                                com.callx.app.utils.PushNotify.notifyStatusReply(
                                        ownerUid, myUid,
                                        name != null ? name : "Someone",
                                        photo != null ? photo : "",
                                        "\uD83C\uDF9A\uFE0F Rated your slider: " + emoji + " " + value + "%", chatId);
                            } catch (Exception ignored) {}
                        }
                        @Override public void onCancelled(DatabaseError e) {}
                    }));
    }
}