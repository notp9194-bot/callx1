package com.callx.app.chat.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.chat.reply.ReplyStateManager;
import com.callx.app.models.Message;

/**
 * ReplyBarView — Slide-up reply bar shown above the input field.
 *
 * Features:
 *   • Smooth 200ms slide-up animation on show
 *   • Rich content rendering: text / image thumbnail / video / audio / file
 *   • Color-coded sender name (brand_primary)
 *   • "You" for self messages
 *   • Cancel button fires onCancel callback
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

    /**
     * Bind the view to a message and a state manager.
     * Auto-shows with slide-up animation.
     */
    public void bind(Message message, ReplyStateManager stateManager, String currentUid) {
        if (message == null) { hide(); return; }

        // Sender name
        if (tvSenderName != null) {
            tvSenderName.setText(stateManager.getDisplaySenderName(currentUid));
        }

        // Content preview
        if (tvContent != null) {
            tvContent.setText(stateManager.getReplyPreviewText());
        }

        // Thumbnail (image / video)
        if (ivThumbnail != null) {
            String thumbUrl = stateManager.getReplyThumbnailUrl();
            if (thumbUrl != null && !thumbUrl.isEmpty()) {
                ivThumbnail.setVisibility(VISIBLE);
                Glide.with(getContext())
                        .load(thumbUrl)
                        .centerCrop()
                        .into(ivThumbnail);
            } else {
                ivThumbnail.setVisibility(GONE);
            }
        }

        show();
    }

    /** Convenience: bind from individual fields (used by ChatActivity direct binding). */
    public void bindFields(String senderName, String contentText, @Nullable String thumbUrl) {
        if (tvSenderName != null) tvSenderName.setText(senderName != null ? senderName : "");
        if (tvContent    != null) tvContent.setText(contentText != null ? contentText : "");
        if (ivThumbnail  != null) {
            if (thumbUrl != null && !thumbUrl.isEmpty()) {
                ivThumbnail.setVisibility(VISIBLE);
                Glide.with(getContext()).load(thumbUrl).centerCrop().into(ivThumbnail);
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
        setVisibility(VISIBLE);
        setAlpha(0f);
        setTranslationY(getHeight() > 0 ? getHeight() : 60);
        animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(200)
                .setInterpolator(new FastOutSlowInInterpolator())
                .start();
    }

    public void hide() {
        if (getVisibility() != VISIBLE) return;
        animate()
                .alpha(0f)
                .translationY(20f)
                .setDuration(150)
                .withEndAction(() -> setVisibility(GONE))
                .start();
    }

    /** Programmatically bind view references (called from ChatActivity). */
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
