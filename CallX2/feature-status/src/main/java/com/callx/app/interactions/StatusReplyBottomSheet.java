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
 * v511 SPLIT (Instagram-style, two clearly separate paths):
 *  - PRIVATE: {@link #sendPrivateReply} — chat DM only (messages/{chatId}) + push.
 *    Used by the "Send message" pill in StatusViewerActivity. Never public.
 *  - PUBLIC:  {@link #showPublicComment} / {@link #postPublicReply} — writes ONLY to
 *    status/{ownerUid}/{statusId}/replies (visible to every viewer) + push to owner.
 *    No chat DM is created.
 */
public class StatusReplyBottomSheet {
    // Same limits as reels comments (ReelCommentFragment): hard cap 300, counter appears at 250, warns at 270.
    private static final int MAX_COMMENT_LENGTH = 300;
    private static final int COUNTER_SHOW_AT    = MAX_COMMENT_LENGTH - 50;
    private static final int COUNTER_WARN_AT    = MAX_COMMENT_LENGTH - 30;
    /** Result of {@link #postPublicReply}: acked by server / written locally but no ack yet (offline) / failed. */
    public static final int RESULT_POSTED = 0;
    public static final int RESULT_QUEUED = 1;
    public static final int RESULT_FAILED = 2;
    public interface PostCallback { void onResult(int result); }
    private static final long POST_ACK_TIMEOUT_MS = 8000;
    public interface OnReplySentListener {
        void onSent(String message);
    }
    /** PUBLIC comment sheet. Returns the dialog so callers can hook dismiss (e.g. resume progress). */
    public static BottomSheetDialog showPublicComment(Context ctx, StatusItem item, String ownerName,
                            String myUid, String ownerUid,
                            OnReplySentListener listener) {
        return showPublicComment(ctx, item, ownerName, myUid, ownerUid, listener, "");
    }

    /**
     * v514 prewarm: call while a status plays. Loads my own profile into the cache (so Post never waits on
     * a network read) and pre-decodes my avatar at the exact tier/options the compose sheet uses.
     */
    public static void prewarm(Context ctx, String myUid) {
        if (ctx == null || myUid == null) return;
        final Context app = ctx.getApplicationContext();
        withMyProfile(myUid, (name, photo) -> {
            if (photo == null || photo.isEmpty()) return;
            com.callx.app.utils.AvatarSizeTier t = com.callx.app.utils.AvatarSizeTier.TINY;
            Glide.with(app)
                .load(com.callx.app.utils.AvatarUrlBuilder.buildResponsive(app, photo, t))
                .apply(new com.bumptech.glide.request.RequestOptions()
                        .override(com.callx.app.utils.AvatarUrlBuilder.tierPx(app, t))
                        .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                        .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.RESOURCE))
                .preload();
        });
    }

    private static BottomSheetDialog showPublicComment(Context ctx, StatusItem item, String ownerName,
                            String myUid, String ownerUid,
                            OnReplySentListener listener, String draft) {
        if (item == null || myUid == null || ownerUid == null) return null;
        if (myUid.equals(ownerUid)) return null; // Owner cannot reply to own status
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
        previewBg.setColor(themeColor(ctx, android.R.attr.textColorPrimary, Color.BLACK) & 0x00FFFFFF | 0x14000000);
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
            // Decode at the view's real size (52dp), not 480x853 (~6x less bitmap memory).
            final int thumbPx = dp(ctx, 52);
            Glide.with(ctx).load(url).centerCrop().override(thumbPx, thumbPx).into(thumb);
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
            tbg.setColor(themeColor(ctx, android.R.attr.textColorPrimary, Color.BLACK) & 0x00FFFFFF | 0x1F000000);
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
        tvTo.setText("Public comment on " + (ownerName != null ? ownerName : "status"));
        tvTo.setTextSize(12);
        tvTo.setTextColor(themeColor(ctx, android.R.attr.colorAccent, Color.parseColor("#6200EE")));
        tvTo.setTypeface(null, android.graphics.Typeface.BOLD);
        previewInfo.addView(tvTo);
        TextView tvPreview = new TextView(ctx);
        String previewText = getPreviewText(item);
        tvPreview.setText(previewText);
        tvPreview.setTextSize(13);
        tvPreview.setTextColor(themeColor(ctx, android.R.attr.textColorPrimary, Color.DKGRAY));
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
        // Privacy cue — makes it obvious this is NOT a private message
        TextView tvPrivacy = new TextView(ctx);
        tvPrivacy.setText("\uD83C\uDF10 Everyone who views this status can see this comment"); // 🌐
        tvPrivacy.setTextSize(12);
        tvPrivacy.setTextColor(Color.parseColor("#B26A00"));
        LinearLayout.LayoutParams privLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        privLp.bottomMargin = dp(ctx, 8);
        root.addView(tvPrivacy, privLp);
        // Reply input row
        LinearLayout inputRow = new LinearLayout(ctx);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        // v511: my own avatar next to the input (same 32dp as reels comment box / comment rows)
        de.hdodenhof.circleimageview.CircleImageView myAvatar =
                new de.hdodenhof.circleimageview.CircleImageView(ctx);
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(dp(ctx, 32), dp(ctx, 32));
        avLp.setMarginEnd(dp(ctx, 10));
        myAvatar.setLayoutParams(avLp);
        myAvatar.setImageResource(com.callx.app.status.R.drawable.ic_person);
        inputRow.addView(myAvatar);
        withMyProfile(myUid, (name, photo) -> {
            if (photo != null && !photo.isEmpty() && sheet.isShowing())
                Glide.with(ctx)
                    .load(com.callx.app.utils.AvatarUrlBuilder.buildResponsive(
                            ctx, photo, com.callx.app.utils.AvatarSizeTier.TINY))
                    .apply(new com.bumptech.glide.request.RequestOptions()
                            .override(com.callx.app.utils.AvatarUrlBuilder.tierPx(
                                    ctx, com.callx.app.utils.AvatarSizeTier.TINY))
                            .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.RESOURCE))
                    .into(myAvatar);
        });
        EditText et = new EditText(ctx);
        et.setHint("Add a public comment…");
        et.setMaxLines(3);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        et.setLayoutParams(etLp);
        // v511: hard cap (typing AND paste) with a toast, like reels comments.
        et.setFilters(new android.text.InputFilter[]{
            new android.text.InputFilter.LengthFilter(MAX_COMMENT_LENGTH) {
                @Override public CharSequence filter(CharSequence src, int st, int en,
                                                     android.text.Spanned d, int ds, int de) {
                    CharSequence out = super.filter(src, st, en, d, ds, de);
                    if (out != null) Toast.makeText(ctx,
                            "Max " + MAX_COMMENT_LENGTH + " characters", Toast.LENGTH_SHORT).show();
                    return out;
                }
            }});
        inputRow.addView(et);
        final TextView counter = new TextView(ctx);
        counter.setTextSize(11);
        counter.setGravity(android.view.Gravity.END);
        counter.setVisibility(View.GONE);
        Button sendBtn = new Button(ctx);
        sendBtn.setText("Post");
        sendBtn.setAllCaps(false);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(ctx, 44));
        btnLp.setMarginStart(dp(ctx, 8));
        sendBtn.setLayoutParams(btnLp);
        // v511: Post is disabled (dimmed) until there is text — no error popup needed.
        sendBtn.setEnabled(false);
        sendBtn.setAlpha(0.4f);
        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                boolean has = e != null && e.toString().trim().length() > 0;
                sendBtn.setEnabled(has);
                sendBtn.setAlpha(has ? 1f : 0.4f);
                int len = e != null ? e.length() : 0;
                if (len >= COUNTER_SHOW_AT) {
                    counter.setVisibility(View.VISIBLE);
                    counter.setText(len + "/" + MAX_COMMENT_LENGTH);
                    counter.setTextColor(len >= COUNTER_WARN_AT ? Color.parseColor("#E53935")
                            : themeColor(ctx, android.R.attr.textColorSecondary, Color.GRAY));
                } else {
                    counter.setVisibility(View.GONE);
                }
            }
        });
        sendBtn.setOnClickListener(v -> {
            String msg = et.getText() != null ? et.getText().toString().trim() : "";
            if (msg.isEmpty()) return;
            // v514 OPTIMISTIC CLOSE: the sheet closes the moment Post is tapped (Firebase's local event puts the
            // comment in the list instantly). Failure is reported afterwards: toast + the sheet reopens with the
            // typed text so nothing is lost. (Trade-off vs v511's ack-wait: faster, but the result arrives late.)
            sheet.dismiss();
            if (listener != null) listener.onSent(msg);
            postPublicReply(myUid, ownerUid, item, msg, null, res -> {
                if (res == RESULT_FAILED) {
                    Toast.makeText(ctx, "Couldn't post comment. Tap Post to retry", Toast.LENGTH_SHORT).show();
                    if (ctx instanceof android.app.Activity
                            && !((android.app.Activity) ctx).isFinishing()
                            && !((android.app.Activity) ctx).isDestroyed()) {
                        BottomSheetDialog again = showPublicComment(ctx, item, ownerName, myUid, ownerUid,
                                listener, msg);
                        if (again != null && listener != null) again.setOnDismissListener(x -> listener.onSent(""));
                    }
                    return;
                }
                if (res == RESULT_QUEUED)
                    Toast.makeText(ctx, "No connection — will post when you're back online", Toast.LENGTH_SHORT).show();
            });
        });
        inputRow.addView(sendBtn);
        if (draft != null && !draft.isEmpty()) { et.setText(draft); et.setSelection(et.length()); } // retry after failed post
        root.addView(inputRow);
        root.addView(counter);
        sheet.setContentView(root);
        if (sheet.getWindow() != null) // v511: resize with the keyboard so Post/counter stay visible
            sheet.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
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
        return sheet;
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
    /**
     * PRIVATE reply: chat DM only (messages/{chatId}/{msgId}) quoting the status,
     * plus a push to the owner. Does NOT touch the public replies node.
     * Single send path — used by the inline "Send message" pill.
     */
    public static void sendPrivateReply(String myUid, String ownerUid, String ownerName,
                                        StatusItem item, String message) {
        if (item == null || myUid == null || ownerUid == null || message == null) return;
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
        embedReplyThumbnail(item, msg, () -> FirebaseUtils.db()
            .getReference("messages").child(chatId).child(msgId)
            .setValue(msg)
            .addOnSuccessListener(u ->
                withMyProfile(myUid, (name, photo) -> notifyOwner(ownerUid, myUid, name, photo, message, chatId))));
    }

    /**
     * PUBLIC comment: written ONLY to status/{ownerUid}/{statusId}/replies, which every
     * viewer sees (bottom-left overlay in StatusViewerActivity). No chat DM is created.
     * The owner gets a push; if this is a reply to someone's comment, that commenter does too.
     *
     * The callback reports the real outcome: RESULT_POSTED (server ack), RESULT_QUEUED (write issued
     * locally but no ack within 8s — typically offline, Firebase will send it when back online) or
     * RESULT_FAILED (nothing was written: profile unavailable or write rejected).
     */
    public static void postPublicReply(String myUid, String ownerUid, StatusItem item, String message,
                                       String replyToUid, PostCallback cb) {
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        final boolean[] done = {false};
        final boolean[] writeIssued = {false};
        final Runnable[] timeout = new Runnable[1];
        final Settle settle = res -> {
            if (done[0]) return;
            done[0] = true;
            if (timeout[0] != null) main.removeCallbacks(timeout[0]);
            if (cb != null) cb.onResult(res);
        };
        if (item == null || myUid == null || ownerUid == null || message == null
                || item.id == null || item.id.isEmpty()) {
            settle.accept(RESULT_FAILED);
            return;
        }
        timeout[0] = () -> settle.accept(writeIssued[0] ? RESULT_QUEUED : RESULT_FAILED);
        main.postDelayed(timeout[0], POST_ACK_TIMEOUT_MS);

        final ProfileCb doPost = new ProfileCb() {
            @Override public void onProfile(String name, String photo) {
                if (done[0]) return;
                String replyId = FirebaseUtils.getStatusRepliesRef(ownerUid, item.id).push().getKey();
                if (replyId == null) { settle.accept(RESULT_FAILED); return; }
                Map<String, Object> preview = new HashMap<>();
                preview.put("uid",       myUid);
                preview.put("name",      name);
                preview.put("avatarUrl", photo);
                preview.put("text",      message);
                preview.put("timestamp", ServerValue.TIMESTAMP);
                writeIssued[0] = true;
                FirebaseUtils.getStatusRepliesRef(ownerUid, item.id)
                        .child(replyId).setValue(preview)
                        .addOnSuccessListener(u -> {
                            // Notify even if the UI already settled as QUEUED (late ack).
                            if (!myUid.equals(ownerUid))
                                notifyUser(ownerUid, myUid, name, photo, "\uD83D\uDCAC Commented: " + message);
                            if (replyToUid != null && !replyToUid.equals(myUid) && !replyToUid.equals(ownerUid))
                                notifyUser(replyToUid, myUid, name, photo, "\u21A9\uFE0F Replied to your comment: " + message);
                            settle.accept(RESULT_POSTED);
                        })
                        .addOnFailureListener(err -> {
                            android.util.Log.e("StatusReply",
                                "Failed to post public comment on status " + item.id
                                    + ": " + err.getMessage(), err);
                            settle.accept(RESULT_FAILED);
                        });
            }
            @Override public void onError() { settle.accept(RESULT_FAILED); }
        };
        // Use the cached profile (filled when the compose sheet opened) so posting doesn't depend on a
        // network read; refresh it in the background.
        if (myUid.equals(cacheUid) && cacheName != null) {
            doPost.onProfile(cacheName, cachePhoto != null ? cachePhoto : "");
            withMyProfile(myUid, (n, ph) -> {});
        } else {
            withMyProfile(myUid, doPost);
        }
    }

    private interface Settle { void accept(int result); }

    private interface ProfileCb {
        void onProfile(String name, String photo);
        default void onError() {}
    }

    // Own profile cache (process lifetime) — name/avatar for comment rows.
    private static String cacheUid, cacheName, cachePhoto;

    private static void withMyProfile(String myUid, ProfileCb cb) {
        FirebaseUtils.db().getReference("users").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    String name  = snap.child("name").getValue(String.class);
                    String photo = snap.child("thumbUrl").getValue(String.class);
                    if (photo == null) photo = snap.child("photoUrl").getValue(String.class);
                    String n = name != null ? name : "Someone";
                    String ph = photo != null ? photo : "";
                    cacheUid = myUid; cacheName = n; cachePhoto = ph;
                    cb.onProfile(n, ph);
                }
                @Override public void onCancelled(DatabaseError e) { cb.onError(); }
            });
    }

    /** Push to {@code targetUid}; chatId is the 1:1 chat between me and that user (notification tap target). */
    private static void notifyUser(String targetUid, String myUid, String name, String photo, String text) {
        String chatId = myUid.compareTo(targetUid) < 0
                ? myUid + "_" + targetUid : targetUid + "_" + myUid;
        notifyOwner(targetUid, myUid, name, photo, text, chatId);
    }
    private static void notifyOwner(String ownerUid, String myUid, String name, String photo,
                                    String text, String chatId) {
        try {
            com.callx.app.utils.PushNotify.notifyStatusReply(
                    ownerUid, myUid, name, photo, text, chatId);
        } catch (Exception ignored) {}
    }
    /** Resolves a theme color (light/dark aware) instead of hardcoding light-mode hex values. */
    private static int themeColor(Context ctx, int attr, int fallback) {
        android.content.res.TypedArray a = ctx.getTheme().obtainStyledAttributes(new int[]{attr});
        try { return a.getColor(0, fallback); } finally { a.recycle(); }
    }
    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    // ── WhatsApp-level embedded reply thumbnail ───────────────────────────────
    /**
     * Every status-reply chat message used to only store a pointer back to the
     * status's live Firebase/Cloudinary URL (replyToMediaUrl) — so once that
     * status expired, got deleted, or moved into a Highlight, the quoted
     * thumbnail in the already-sent chat bubble depended on whatever happened
     * to that URL next. WhatsApp's story-reply bubbles don't have this problem:
     * the reply is a self-contained message with its own copy of the thumbnail
     * from the moment it's sent, so it keeps rendering no matter what happens
     * to the original story afterwards.
     *
     * This mirrors that: downloads the status's media once, downsamples +
     * center-crops it to a small square JPEG, and embeds those bytes directly
     * into the message as Base64 (replyToThumbBase64). replyToMediaUrl is
     * still written too, purely as a fallback for older clients that don't
     * understand replyToThumbBase64 yet, or if the download below fails.
     *
     * Runs the network+decode work off the calling thread and invokes
     * {@code thenSend} — which does the actual message setValue()/notify —
     * once the message map is ready, whether or not the embed succeeded.
     */
    private static void embedReplyThumbnail(StatusItem item, Map<String, Object> msg, Runnable thenSend) {
        String url = item.thumbnailUrl != null ? item.thumbnailUrl
                : ("image".equals(item.type) && item.mediaUrl != null ? item.mediaUrl : null);
        if (url == null || url.isEmpty()) {
            thenSend.run();
            return;
        }
        msg.put("replyToMediaUrl", url); // fallback / older clients, or if the embed below fails
        // Download/crop/compress logic lives in the shared ThumbnailEmbedder
        // (core) — also used by StatusSeenTracker and ReelSeenTracker now.
        com.callx.app.utils.ThumbnailEmbedder.embed(url, base64 -> {
            if (base64 != null) msg.put("replyToThumbBase64", base64);
            thenSend.run();
        });
    }

    // ── Question-sticker tap-to-answer ──────────────────────────────────────
    /**
     * Shown when a viewer taps a 💬 Question sticker on someone's status
     * (see StatusStickerOverlayView / StatusViewerActivity's sticker overlay).
     * Same underlying send path as {@link #sendPrivateReply} (private DM), just a focused UI that
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
        tvQuestion.setTextColor(themeColor(ctx, android.R.attr.textColorPrimary, Color.parseColor("#212121")));
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
        embedReplyThumbnail(item, msg, () -> FirebaseUtils.db()
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
                    })));
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
        embedReplyThumbnail(item, msg, () -> FirebaseUtils.db()
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
                    })));
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
        embedReplyThumbnail(item, msg, () -> FirebaseUtils.db()
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
                    })));
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
        embedReplyThumbnail(item, msg, () -> FirebaseUtils.db()
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
                    })));
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
        embedReplyThumbnail(item, msg, () -> FirebaseUtils.db()
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
                    })));
    }
}