package com.callx.app.chat.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.chat.reply.ReplyStateManager;
import com.callx.app.models.Message;
import com.callx.app.conversation.ChatActivity;

/**
 * ReplyBarView — NO animation version for performance.
 * Show/hide is instant (no slide-up or fade).
 */
public class ReplyBarView extends LinearLayout {

    private TextView    tvSenderName;
    private TextView    tvContent;
    private ImageView   ivThumbnail;
    private ImageButton btnCancel;

    private Runnable onCancelListener;

    public ReplyBarView(Context context) {
        super(context);
        init(context);
    }

    public ReplyBarView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context ctx) {
        setOrientation(HORIZONTAL);
        setVisibility(GONE);
    }

    // PERF: matches THUMB_RGB565 used everywhere else in the chat module —
    // explicit disk cache (persists across chat re-opens) + RGB565 (no
    // wasted alpha byte on thumbnails that have none) instead of relying on
    // Glide's implicit defaults.
    private static final com.bumptech.glide.request.RequestOptions REPLY_THUMB_OPTS =
            new com.bumptech.glide.request.RequestOptions()
                    .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL);

    public void bind(Message message, ReplyStateManager stateManager, String currentUid) {
        if (message == null) { hide(); return; }
        if (tvSenderName != null) tvSenderName.setText(stateManager.getDisplaySenderName(currentUid));
        if (tvContent    != null) tvContent.setText(stateManager.getReplyPreviewText());
        if (ivThumbnail  != null) {
            String thumbUrl = stateManager.getReplyThumbnailUrl();
            if (thumbUrl != null && !thumbUrl.isEmpty()) {
                ivThumbnail.setVisibility(VISIBLE);
                .override(720, 720)
                Glide.with(getContext()).load(thumbUrl).apply(REPLY_THUMB_OPTS).centerCrop().override(720, 720).into(ivThumbnail);
            } else {
                ivThumbnail.setVisibility(GONE);
            }
        }
        show();
    }

    public void bindFields(String senderName, String contentText, @Nullable String thumbUrl) {
        if (tvSenderName != null) tvSenderName.setText(senderName != null ? senderName : "");
        if (tvContent    != null) tvContent.setText(contentText != null ? contentText : "");
        if (ivThumbnail  != null) {
            if (thumbUrl != null && !thumbUrl.isEmpty()) {
                ivThumbnail.setVisibility(VISIBLE);
                .override(720, 720)
                Glide.with(getContext()).load(thumbUrl).apply(REPLY_THUMB_OPTS).centerCrop().override(720, 720).into(ivThumbnail);
            } else {
                ivThumbnail.setVisibility(GONE);
            }
        }
        show();
    }

    public void setOnCancelListener(Runnable listener) {
        this.onCancelListener = listener;
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> {
                if (onCancelListener != null) onCancelListener.run();
            });
        }
    }

    public void show() {
        if (getVisibility() == VISIBLE) return;
        // Instant show — no animation
        setAlpha(1f);
        setTranslationY(0f);
        setVisibility(VISIBLE);
    }

    public void hide() {
        if (getVisibility() != VISIBLE) return;
        // Instant hide — no animation
        setVisibility(GONE);
    }

    public void attachViews(TextView senderName, TextView content,
                            ImageView thumbnail, ImageButton cancel) {
        this.tvSenderName = senderName;
        this.tvContent    = content;
        this.ivThumbnail  = thumbnail;
        this.btnCancel    = cancel;
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> {
                if (onCancelListener != null) onCancelListener.run();
            });
        }
    }
}
