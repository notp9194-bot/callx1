package com.callx.app.interactions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.util.*;

/**
 * StatusRepliesBottomSheet v3 — PUBLIC comments list for a status (Instagram-style).
 *
 * v511 behavior:
 *  - LIVE: listens on status/{ownerUid}/{statusId}/replies while open, so new comments,
 *    deletions and the viewer's own just-posted comment appear immediately (no stale snapshot).
 *  - PUBLIC reply: "Reply" on a comment posts a new PUBLIC comment prefixed "@name"
 *    (via StatusReplyBottomSheet#postPublicReply). It never creates a private chat DM.
 *  - Tapping a row opens the private chat with that commenter ONLY for the status owner.
 *    Other viewers can't DM strangers from here.
 *  - Delete: the status owner can delete any comment; a commenter can delete their OWN comment
 *    (matches firebase rules: replies/$id write allowed for data.child('uid') == auth.uid).
 *    Long-press, plus a visible "Delete" action on own comments.
 *  - Draft is kept across live refreshes (open inline reply box + typed text survive).
 */
public class StatusRepliesBottomSheet {
    private static final int MAX_COMMENT_LENGTH = 300; // same cap as the compose sheet / reels comments

    /** Per-sheet UI state so a live refresh doesn't wipe an in-progress inline reply. */
    private static class State {
        String openKey = null;
        String draft = "";
        ScrollView scroll; // used to scroll the inline reply box above the keyboard
    }

    public static void show(Context ctx, String ownerUid, String myUid, StatusItem item, Runnable onDismiss) {
        boolean isOwner = myUid != null && myUid.equals(ownerUid);
        BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        ScrollView scroll = new ScrollView(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 24));
        TextView header = new TextView(ctx);
        header.setTextSize(17);
        header.setTextColor(themeColor(ctx, android.R.attr.textColorPrimary, Color.BLACK));
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        header.setPadding(0, dp(ctx, 8), 0, dp(ctx, 8));
        root.addView(header);
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        scroll.addView(root);
        sheet.setContentView(scroll);

        final State state = new State();
        state.scroll = scroll;
        // 1) Seed instantly from what the viewer already has, 2) then go live.
        Map<String, StatusItem.ReplyPreview> seed = item.replies != null ? item.replies : new LinkedHashMap<>();
        render(ctx, list, header, seed, sheet, myUid, ownerUid, item, isOwner, state);

        final DatabaseReference ref = (item.id != null && !item.id.isEmpty() && ownerUid != null)
                ? FirebaseUtils.getStatusRepliesRef(ownerUid, item.id) : null;
        final ValueEventListener live = ref == null ? null : new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                Map<String, StatusItem.ReplyPreview> map = new LinkedHashMap<>();
                for (DataSnapshot c : snap.getChildren()) {
                    StatusItem.ReplyPreview r = c.getValue(StatusItem.ReplyPreview.class);
                    if (r != null) map.put(c.getKey(), r);
                }
                item.replies = map; // keep the shared StatusItem in sync
                render(ctx, list, header, map, sheet, myUid, ownerUid, item, isOwner, state);
            }
            @Override public void onCancelled(DatabaseError e) {
                android.util.Log.e("StatusReplies", "Live comments cancelled: " + e.getMessage());
            }
        };
        if (ref != null) ref.addValueEventListener(live);
        sheet.setOnDismissListener(d -> {
            if (ref != null && live != null) ref.removeEventListener(live);
            if (onDismiss != null) onDismiss.run();
        });
        // v511: keyboard — resize the sheet instead of letting the IME cover the inline reply box,
        // and open fully expanded (no half-collapsed state fighting the keyboard).
        if (sheet.getWindow() != null)
            sheet.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        sheet.setOnShowListener(d -> {
            View parentView = (View) scroll.getParent();
            if (parentView != null) {
                com.google.android.material.bottomsheet.BottomSheetBehavior<View> b =
                        com.google.android.material.bottomsheet.BottomSheetBehavior.from(parentView);
                b.setSkipCollapsed(true);
                b.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
            }
        });
        sheet.show();
    }

    private static void render(Context ctx, LinearLayout list, TextView header,
                               Map<String, StatusItem.ReplyPreview> map, BottomSheetDialog sheet,
                               String myUid, String ownerUid, StatusItem item, boolean isOwner, State state) {
        List<Map.Entry<String, StatusItem.ReplyPreview>> replies = new ArrayList<>(map.entrySet());
        Collections.sort(replies, (a, b) -> {
            long ta = a.getValue().timestamp != null ? a.getValue().timestamp : 0;
            long tb = b.getValue().timestamp != null ? b.getValue().timestamp : 0;
            return Long.compare(tb, ta); // newest first
        });
        list.removeAllViews();
        header.setText("Comments (" + replies.size() + ")");
        if (replies.isEmpty()) { addEmptyState(ctx, list, isOwner); return; }
        for (Map.Entry<String, StatusItem.ReplyPreview> e : replies) {
            addReplyRow(ctx, list, e.getKey(), e.getValue(), sheet, myUid, ownerUid, item, isOwner, state);
        }
    }
    /** Empty state shown in the sheet itself (e.g. owner deleted the last comment). */
    private static void addEmptyState(Context ctx, LinearLayout parent, boolean isOwner) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, dp(ctx, 32), 0, dp(ctx, 32));
        TextView icon = new TextView(ctx);
        icon.setText("\uD83D\uDCAC"); // 💬
        icon.setTextSize(32);
        icon.setGravity(Gravity.CENTER);
        box.addView(icon);
        TextView title = new TextView(ctx);
        title.setText("No comments yet");
        title.setTextSize(15);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        title.setTextColor(themeColor(ctx, android.R.attr.textColorPrimary, Color.BLACK));
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tLp.topMargin = dp(ctx, 8);
        box.addView(title, tLp);
        TextView sub = new TextView(ctx);
        sub.setText(isOwner ? "Comments on your status will show up here"
                            : "Be the first to comment");
        sub.setTextSize(13);
        sub.setTextColor(themeColor(ctx, android.R.attr.textColorSecondary, Color.GRAY));
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(ctx, 2);
        box.addView(sub, sLp);
        parent.addView(box);
    }
    /** Resolves a theme text color (light/dark aware) instead of hardcoding Color.DKGRAY/GRAY. */
    private static int themeColor(Context ctx, int attr, int fallback) {
        android.content.res.TypedArray a = ctx.getTheme().obtainStyledAttributes(new int[]{attr});
        try { return a.getColor(0, fallback); } finally { a.recycle(); }
    }
    /** After the IME resizes the sheet, bring the inline reply box fully on screen. */
    private static void scrollInlineIntoView(State state, View inlineRow) {
        inlineRow.postDelayed(() -> {
            if (state.scroll == null || !inlineRow.isShown()) return;
            android.graphics.Rect r = new android.graphics.Rect(0, 0, inlineRow.getWidth(), inlineRow.getHeight());
            inlineRow.requestRectangleOnScreen(r, false);
        }, 250); // wait for the keyboard animation / resize
    }
    private static TextView actionText(Context ctx, String label) {
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextSize(12);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        t.setTextColor(themeColor(ctx, android.R.attr.textColorSecondary, Color.GRAY));
        t.setPadding(0, dp(ctx, 6), dp(ctx, 16), dp(ctx, 2));
        return t;
    }
    private static void addReplyRow(Context ctx, LinearLayout parent, String replyKey, StatusItem.ReplyPreview r,
                                     BottomSheetDialog sheet, String myUid, String ownerUid,
                                     StatusItem item, boolean isOwner, State state) {
        final boolean isMine    = myUid != null && r.uid != null && myUid.equals(r.uid);
        final boolean canDelete = isOwner || isMine;
        final boolean canReply  = myUid != null && r.uid != null && !isMine;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP); // reels: avatar top-aligned with the name line
        row.setPadding(0, dp(ctx, 10), 0, dp(ctx, 10));
        de.hdodenhof.circleimageview.CircleImageView avatar =
                new de.hdodenhof.circleimageview.CircleImageView(ctx);
        int size = dp(ctx, 32); // 32dp = reels comment avatar (item_reel_comment.xml iv_avatar)
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(size, size);
        avLp.topMargin = dp(ctx, 2);
        avatar.setLayoutParams(avLp);
        if (r.avatarUrl != null && !r.avatarUrl.isEmpty()) {
            Glide.with(ctx).load(r.avatarUrl).into(avatar);
        } else {
            avatar.setImageResource(com.callx.app.status.R.drawable.ic_person);
        }
        avatar.setContentDescription((r.name != null && !r.name.isEmpty() ? r.name : "User") + "'s profile photo");
        // v511: avatar / name open that user's profile (same as reels comments)
        final View.OnClickListener onProfileClick = v -> {
            if (openProfile(ctx, r)) sheet.dismiss();
        };
        avatar.setOnClickListener(onProfileClick);
        row.addView(avatar);
        LinearLayout info = new LinearLayout(ctx);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(ctx, 10), 0, 0, 0);
        info.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        // Reels layout: name (13sp medium) + time (11sp, 8dp gap) on ONE line, body 13sp.
        LinearLayout nameRow = new LinearLayout(ctx);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView tvName = new TextView(ctx);
        tvName.setText(r.name != null ? r.name : "Someone");
        tvName.setTextSize(13);
        tvName.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        tvName.setTextColor(themeColor(ctx, android.R.attr.textColorPrimary, Color.BLACK));
        tvName.setSingleLine(true);
        tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvName.setOnClickListener(onProfileClick);
        nameRow.addView(tvName);
        if (r.timestamp != null && r.timestamp > 0) {
            TextView tvTime = new TextView(ctx);
            // Same format as reels comments: "now", "5 min. ago", "3 hr. ago", "2 days ago"
            tvTime.setText(android.text.format.DateUtils.getRelativeTimeSpanString(
                    r.timestamp, System.currentTimeMillis(),
                    android.text.format.DateUtils.MINUTE_IN_MILLIS,
                    android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE));
            tvTime.setTextSize(11);
            tvTime.setTextColor(themeColor(ctx, android.R.attr.textColorSecondary, Color.GRAY));
            LinearLayout.LayoutParams tmLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tmLp.setMarginStart(dp(ctx, 8));
            nameRow.addView(tvTime, tmLp);
        }
        info.addView(nameRow);
        TextView tvText = new TextView(ctx);
        tvText.setText(r.text != null ? r.text : "");
        tvText.setTextSize(13);
        tvText.setTextColor(themeColor(ctx, android.R.attr.textColorPrimary, Color.DKGRAY));
        LinearLayout.LayoutParams txLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        txLp.topMargin = dp(ctx, 3);
        info.addView(tvText, txLp); // full comment text (no 2-line cut)

        // Action row: Reply (PUBLIC, others' comments) / Delete (owner, or my own comment)
        LinearLayout actions = new LinearLayout(ctx);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView tvReplyToggle = canReply ? actionText(ctx, "Reply") : null;
        TextView tvDelete      = canDelete ? actionText(ctx, "Delete") : null;
        if (tvReplyToggle != null) actions.addView(tvReplyToggle);
        if (tvDelete != null)      actions.addView(tvDelete);
        if (actions.getChildCount() > 0) info.addView(actions);

        if (canReply) {
            final String nm = (r.name != null && !r.name.isEmpty()) ? r.name : "someone";
            final String prefix = "@" + nm + " ";
            LinearLayout inlineRow = new LinearLayout(ctx);
            inlineRow.setOrientation(LinearLayout.HORIZONTAL);
            inlineRow.setGravity(Gravity.CENTER_VERTICAL);
            inlineRow.setPadding(0, dp(ctx, 6), 0, 0);
            inlineRow.setVisibility(View.GONE);
            EditText et = new EditText(ctx);
            et.setHint("Reply publicly to " + nm + "…");
            et.setTextSize(13);
            et.setTextColor(themeColor(ctx, android.R.attr.textColorPrimary, Color.BLACK));
            et.setHintTextColor(themeColor(ctx, android.R.attr.textColorSecondary, Color.GRAY));
            et.setMaxLines(3);
            et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            // The "@name " prefix is added on send, so leave room for it inside the 300-char cap.
            et.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(Math.max(1, MAX_COMMENT_LENGTH - prefix.length()))});
            et.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
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

            // Restore an open inline reply across live refreshes.
            if (replyKey.equals(state.openKey)) {
                inlineRow.setVisibility(View.VISIBLE);
                et.setText(state.draft);
                et.setSelection(et.getText().length());
                et.post(et::requestFocus);
                scrollInlineIntoView(state, inlineRow);
            }
            et.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
                @Override public void onTextChanged(CharSequence c, int a, int b, int d) {}
                @Override public void afterTextChanged(android.text.Editable e) {
                    if (replyKey.equals(state.openKey)) state.draft = e != null ? e.toString() : "";
                }
            });
            tvReplyToggle.setOnClickListener(v -> {
                boolean opening = inlineRow.getVisibility() != View.VISIBLE;
                inlineRow.setVisibility(opening ? View.VISIBLE : View.GONE);
                state.openKey = opening ? replyKey : null;
                state.draft = opening ? (et.getText() != null ? et.getText().toString() : "") : "";
                if (opening) {
                    et.requestFocus();
                    android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                            ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                    scrollInlineIntoView(state, inlineRow);
                }
            });
            sendBtn.setOnClickListener(v -> {
                String msg = et.getText() != null ? et.getText().toString().trim() : "";
                if (msg.isEmpty()) return;
                sendBtn.setEnabled(false);
                sendBtn.setAlpha(0.4f);
                et.setEnabled(false);
                // PUBLIC reply: a new comment "@name ..." — no private DM is created.
                StatusReplyBottomSheet.postPublicReply(myUid, ownerUid, item, prefix + msg, r.uid, res -> {
                    sendBtn.setEnabled(true);
                    sendBtn.setAlpha(1f);
                    et.setEnabled(true);
                    if (res == StatusReplyBottomSheet.RESULT_FAILED) {
                        Toast.makeText(ctx, "Couldn't post reply. Tap send to retry", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Toast.makeText(ctx, res == StatusReplyBottomSheet.RESULT_POSTED
                            ? "Reply posted ✓" : "No connection — will post when you're back online",
                            Toast.LENGTH_SHORT).show();
                    state.openKey = null;
                    state.draft = "";
                    et.setText("");
                    inlineRow.setVisibility(View.GONE);
                });
            });
        }
        row.addView(info);
        // Private chat with the commenter: status OWNER only (viewers can't DM strangers from a public list).
        if (isOwner) {
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> {
                openChatWith(ctx, r);
                sheet.dismiss();
            });
        }
        View divider = new View(ctx);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        divider.setBackgroundColor(themeColor(ctx, android.R.attr.textColorSecondary, Color.GRAY) & 0x00FFFFFF | 0x22000000);
        final Runnable doDelete = canDelete
                ? () -> showDeleteConfirm(ctx, ownerUid, item.id, r, replyKey, isMine) : null;
        if (tvDelete != null && doDelete != null) tvDelete.setOnClickListener(v -> doDelete.run());
        // Long-press menu: Copy for everyone, Delete when allowed (owner / own comment).
        row.setOnLongClickListener(v -> {
            final String[] labels = doDelete != null ? new String[]{"Copy", "Delete"} : new String[]{"Copy"};
            new android.app.AlertDialog.Builder(ctx)
                .setItems(labels, (dlg, which) -> {
                    if (which == 0) {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                    "comment", r.text != null ? r.text : ""));
                            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show();
                        }
                    } else if (doDelete != null) {
                        doDelete.run();
                    }
                })
                .show();
            return true;
        });
        parent.addView(row);
        parent.addView(divider);
    }
    /** Confirms, then removes this one comment from the public
     *  status/{ownerUid}/{statusId}/replies/{replyKey} node. The live listener refreshes the list. */
    private static void showDeleteConfirm(Context ctx, String ownerUid, String statusId, StatusItem.ReplyPreview r,
                                           String replyKey, boolean isMine) {
        if (replyKey == null || replyKey.isEmpty()) return;
        new android.app.AlertDialog.Builder(ctx)
            .setTitle(isMine ? "Delete your comment?" : "Delete comment?")
            .setMessage(isMine
                    ? "This can't be undone."
                    : "Remove " + (r.name != null ? r.name : "this") + "'s comment from your story. This can't be undone.")
            .setPositiveButton("Delete", (d, w) -> {
                if (statusId == null || statusId.isEmpty()) return;
                FirebaseUtils.getStatusRepliesRef(ownerUid, statusId).child(replyKey)
                    .removeValue()
                    .addOnSuccessListener(u ->
                        Toast.makeText(ctx, "Comment deleted", Toast.LENGTH_SHORT).show())
                    .addOnFailureListener(e ->
                        Toast.makeText(ctx, "Couldn't delete comment", Toast.LENGTH_SHORT).show());
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
    /** Opens the commenter's profile. feature-status has no compile-time dependency on the app module,
     *  so it goes by class name (same pattern as reels MentionSpanUtils.navigateToProfile). */
    private static boolean openProfile(Context ctx, StatusItem.ReplyPreview r) {
        if (r.uid == null || r.uid.isEmpty()) return false;
        try {
            Intent i = new Intent();
            i.setClassName(ctx, "com.callx.app.activities.UserProfileActivity");
            i.putExtra("uid",   r.uid);
            i.putExtra("name",  r.name != null ? r.name : "");
            i.putExtra("photo", r.avatarUrl != null ? r.avatarUrl : "");
            if (!(ctx instanceof android.app.Activity))
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
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
