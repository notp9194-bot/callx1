package com.callx.app.interactions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * StatusRepliesBottomSheet v2 — Instagram-style "who commented on your story" list.
 *
 * Companion to the bottom-left latest-reply overlay in StatusViewerActivity: that
 * overlay only ever shows up to 3 recent commenters, so tapping it opens this
 * sheet with every reply on the status (newest first).
 *
 * Each row has two ways to act on a comment:
 *  - Tapping the row itself jumps into the full chat DM with that commenter
 *    (unchanged from v1).
 *  - NEW: tapping "Reply" expands an inline reply box right under that comment —
 *    type + send without ever leaving this sheet / opening ChatActivity. The
 *    message is still delivered as a normal quoted chat DM to the commenter
 *    (same messages/{chatId}/{msgId} path StatusReplyBottomSheet#sendReply uses),
 *    just quoting THIS comment instead of the status itself, so it reads in
 *    chat as "replying to {name}'s comment: {their text}".
 *
 * Data source: StatusItem#replies (status/{ownerUid}/{statusId}/replies), the
 * public preview each reply is mirrored into by StatusReplyBottomSheet#sendReply
 * alongside the private chat DM it already sends.
 *
 * NEW: Owner delete/hide — this sheet only ever opens for the story owner (see
 * StatusViewerActivity's owner-only bottom-left overlay), so every row here is
 * long-press-able: a confirm dialog removes that one reply from the public
 * status/{ownerUid}/{statusId}/replies/{pushId} node via
 * FirebaseUtils#getStatusRepliesRef, the live listener in StatusViewerActivity
 * picks the removal up, and the row is pulled out of this sheet immediately
 * (no full re-show / re-query needed).
 */
public class StatusRepliesBottomSheet {
    public static void show(Context ctx, String ownerUid, String myUid, StatusItem item, Runnable onDismiss) {
        if (item.replies == null || item.replies.isEmpty()) {
            Toast.makeText(ctx, "No replies yet", Toast.LENGTH_SHORT).show();
            if (onDismiss != null) onDismiss.run();
            return;
        }
        List<Map.Entry<String, StatusItem.ReplyPreview>> replies = new ArrayList<>(item.replies.entrySet());
        Collections.sort(replies, (a, b) -> {
            long ta = a.getValue().timestamp != null ? a.getValue().timestamp : 0;
            long tb = b.getValue().timestamp != null ? b.getValue().timestamp : 0;
            return Long.compare(tb, ta); // newest first
        });
        boolean isOwner = myUid != null && myUid.equals(ownerUid);
        BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        ScrollView scroll = new ScrollView(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 24));
        TextView header = new TextView(ctx);
        header.setText("Replies (" + replies.size() + ")");
        header.setTextSize(17);
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        header.setPadding(0, dp(ctx, 8), 0, dp(ctx, 8));
        root.addView(header);
        SimpleDateFormat fmt = new SimpleDateFormat("hh:mm a", Locale.getDefault());
        for (Map.Entry<String, StatusItem.ReplyPreview> e : replies) {
            addReplyRow(ctx, root, e.getKey(), e.getValue(), fmt, sheet, myUid, ownerUid, item.id, isOwner, header);
        }
        scroll.addView(root);
        sheet.setContentView(scroll);
        if (onDismiss != null) sheet.setOnDismissListener(d -> onDismiss.run());
        sheet.show();
    }
    private static void addReplyRow(Context ctx, LinearLayout parent, String replyKey, StatusItem.ReplyPreview r,
                                     SimpleDateFormat fmt, BottomSheetDialog sheet, String myUid,
                                     String ownerUid, String statusId, boolean isOwner, TextView header) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(ctx, 10), 0, dp(ctx, 10));
        row.setClickable(true);
        row.setFocusable(true);
        de.hdodenhof.circleimageview.CircleImageView avatar =
                new de.hdodenhof.circleimageview.CircleImageView(ctx);
        int size = dp(ctx, 44);
        avatar.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        if (r.avatarUrl != null && !r.avatarUrl.isEmpty()) {
            Glide.with(ctx).load(r.avatarUrl).into(avatar);
        } else {
            avatar.setImageResource(com.callx.app.status.R.drawable.ic_person);
        }
        row.addView(avatar);
        LinearLayout info = new LinearLayout(ctx);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(ctx, 12), 0, 0, 0);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        info.setLayoutParams(infoLp);
        TextView tvName = new TextView(ctx);
        tvName.setText(r.name != null ? r.name : "Someone");
        tvName.setTextSize(15);
        tvName.setTypeface(null, android.graphics.Typeface.BOLD);
        info.addView(tvName);
        TextView tvText = new TextView(ctx);
        tvText.setText(r.text != null ? r.text : "");
        tvText.setTextSize(13);
        tvText.setTextColor(Color.DKGRAY);
        tvText.setMaxLines(2);
        tvText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(tvText);
        if (r.timestamp != null && r.timestamp > 0) {
            TextView tvTime = new TextView(ctx);
            tvTime.setText(fmt.format(new Date(r.timestamp)));
            tvTime.setTextSize(11);
            tvTime.setTextColor(Color.GRAY);
            info.addView(tvTime);
        }
        // NEW: inline "Reply" affordance — hidden if this is my own comment
        // (can't reply to yourself) or myUid isn't known.
        boolean canReply = myUid != null && r.uid != null && !myUid.equals(r.uid);
        if (canReply) {
            TextView tvReplyToggle = new TextView(ctx);
            tvReplyToggle.setText("Reply");
            tvReplyToggle.setTextSize(12);
            tvReplyToggle.setTypeface(null, android.graphics.Typeface.BOLD);
            tvReplyToggle.setTextColor(Color.parseColor("#3897F0"));
            tvReplyToggle.setPadding(0, dp(ctx, 4), 0, 0);
            info.addView(tvReplyToggle);

            LinearLayout inlineRow = new LinearLayout(ctx);
            inlineRow.setOrientation(LinearLayout.HORIZONTAL);
            inlineRow.setGravity(Gravity.CENTER_VERTICAL);
            inlineRow.setPadding(0, dp(ctx, 6), 0, 0);
            inlineRow.setVisibility(View.GONE);
            EditText et = new EditText(ctx);
            et.setHint("Reply to " + (r.name != null ? r.name : "them") + "…");
            et.setTextSize(13);
            et.setMaxLines(3);
            et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            et.setLayoutParams(etLp);
            inlineRow.addView(et);
            ImageButton sendBtn = new ImageButton(ctx);
            sendBtn.setImageResource(android.R.drawable.ic_menu_send);
            int btnSz = dp(ctx, 36);
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(btnSz, btnSz);
            btnLp.setMarginStart(dp(ctx, 6));
            sendBtn.setLayoutParams(btnLp);
            sendBtn.setBackground(null);
            inlineRow.addView(sendBtn);
            info.addView(inlineRow);

            tvReplyToggle.setOnClickListener(v -> {
                boolean opening = inlineRow.getVisibility() != View.VISIBLE;
                inlineRow.setVisibility(opening ? View.VISIBLE : View.GONE);
                if (opening) {
                    et.requestFocus();
                    InputMethodManager imm = (InputMethodManager)
                            ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
                }
            });
            sendBtn.setOnClickListener(v -> {
                String msg = et.getText() != null ? et.getText().toString().trim() : "";
                if (msg.isEmpty()) return;
                sendInlineReply(myUid, r, msg);
                Toast.makeText(ctx, "Reply sent ✓", Toast.LENGTH_SHORT).show();
                et.setText("");
                inlineRow.setVisibility(View.GONE);
            });
        }
        row.addView(info);
        row.setOnClickListener(v -> {
            openChatWith(ctx, r);
            sheet.dismiss();
        });
        View divider = new View(ctx);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        divider.setBackgroundColor(Color.parseColor("#11000000"));
        // NEW: owner-only long-press → delete/hide this reply.
        if (isOwner) {
            row.setOnLongClickListener(v -> {
                showDeleteConfirm(ctx, ownerUid, statusId, r, replyKey, () -> {
                    parent.removeView(row);
                    parent.removeView(divider);
                    int remaining = Math.max(0, currentReplyRowCount(parent));
                    header.setText("Replies (" + remaining + ")");
                });
                return true;
            });
        }
        parent.addView(row);
        parent.addView(divider);
    }
    /** Counts remaining reply rows so the header count stays accurate after a delete
     *  (header itself is child 0, so subtract it; rows/dividers alternate 2 children each). */
    private static int currentReplyRowCount(LinearLayout parent) {
        int children = parent.getChildCount() - 1; // minus header
        return Math.max(0, children / 2);
    }
    /** Confirms, then removes this one reply from the public
     *  status/{ownerUid}/{statusId}/replies/{replyKey} node — owner-only "delete/hide". */
    private static void showDeleteConfirm(Context ctx, String ownerUid, String statusId, StatusItem.ReplyPreview r,
                                           String replyKey, Runnable onDeleted) {
        if (replyKey == null || replyKey.isEmpty()) return;
        new android.app.AlertDialog.Builder(ctx)
            .setTitle("Delete reply?")
            .setMessage("Remove " + (r.name != null ? r.name : "this") + "'s reply from your story. This can't be undone.")
            .setPositiveButton("Delete", (d, w) -> {
                if (statusId == null || statusId.isEmpty()) return;
                FirebaseUtils.getStatusRepliesRef(ownerUid, statusId).child(replyKey)
                    .removeValue()
                    .addOnSuccessListener(u -> {
                        Toast.makeText(ctx, "Reply deleted", Toast.LENGTH_SHORT).show();
                        onDeleted.run();
                    })
                    .addOnFailureListener(e ->
                        Toast.makeText(ctx, "Couldn't delete reply", Toast.LENGTH_SHORT).show());
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
    /**
     * Sends a reply-to-a-comment as a normal quoted chat DM straight to that
     * commenter — same messages/{chatId}/{msgId} path and replyTo* quote-bubble
     * fields StatusReplyBottomSheet#sendReply uses for replying to a status,
     * just quoting the COMMENT (r.text/r.name) instead of the status itself.
     * Fires from this sheet directly; never opens ChatActivity.
     */
    private static void sendInlineReply(String myUid, StatusItem.ReplyPreview r, String message) {
        if (myUid == null || r.uid == null) return;
        String targetUid = r.uid;
        String chatId = myUid.compareTo(targetUid) < 0
                ? myUid + "_" + targetUid : targetUid + "_" + myUid;
        String msgId = FirebaseUtils.db().getReference().push().getKey();
        if (msgId == null) return;
        Map<String, Object> msg = new HashMap<>();
        msg.put("id",                msgId);
        msg.put("senderId",          myUid);
        msg.put("text",              message);
        msg.put("type",              "text");
        msg.put("timestamp",         ServerValue.TIMESTAMP);
        msg.put("seen",              false);
        msg.put("replyToType",       "text");
        msg.put("replyToText",       r.text != null ? r.text : "");
        msg.put("replyToSenderName", "\uD83D\uDCAC " + (r.name != null ? r.name : "their") + "'s comment"); // 💬
        msg.put("replyToId",         "statusComment_" + targetUid + "_" + (r.timestamp != null ? r.timestamp : 0));
        if (r.avatarUrl != null && !r.avatarUrl.isEmpty())
            msg.put("replyToMediaUrl", r.avatarUrl);
        FirebaseUtils.db().getReference("messages").child(chatId).child(msgId)
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
                                        targetUid, myUid,
                                        name != null ? name : "Someone",
                                        photo != null ? photo : "",
                                        message, chatId);
                            } catch (Exception ignored) {}
                        }
                        @Override public void onCancelled(DatabaseError e) {}
                    }));
    }
    /** Jumps straight into the DM thread with this commenter — the same chat the
     *  reply was already delivered into as a quoted status-reply bubble. */
    private static void openChatWith(Context ctx, StatusItem.ReplyPreview r) {
        if (r.uid == null) return;
        Intent i = new Intent(ctx, com.callx.app.conversation.ChatActivity.class);
        i.putExtra("partnerUid",   r.uid);
        i.putExtra("partnerName",  r.name != null ? r.name : "");
        i.putExtra("partnerPhoto", r.avatarUrl != null ? r.avatarUrl : "");
        i.putExtra("partnerThumb", r.avatarUrl != null ? r.avatarUrl : "");
        if (!(ctx instanceof android.app.Activity))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }
    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
