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
import com.callx.app.activities.StatusViewerActivity;
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
            Glide.with(ctx).load(url).centerCrop().into(thumb);
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
    private static String getPreviewText(StatusItem item) {
        if (item == null) return "Status";
        if ("image".equals(item.type))  return "📷 Photo status";
        if ("video".equals(item.type))  return "🎥 Video status";
        if ("gif".equals(item.type))    return "GIF";
        if ("link".equals(item.type))   return "🔗 " + (item.linkTitle != null ? item.linkTitle : item.linkUrl);
        if (item.text != null && !item.text.isEmpty()) return item.text;
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
        msg.put("replyToSenderName",   ownerName != null ? ownerName : "Status");
        msg.put("replyToId",           "status_" + (item.statusId != null ? item.statusId : "unknown"));
        if (item.thumbnailUrl != null)
            msg.put("replyToMediaUrl", item.thumbnailUrl);
        else if ("image".equals(item.type) && item.mediaUrl != null)
            msg.put("replyToMediaUrl", item.mediaUrl);
        FirebaseUtils.db()
            .getReference("chats").child(chatId).child("messages").child(msgId)
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
                        }
                        @Override public void onCancelled(DatabaseError e) {}
                    }));
    }
    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}