package com.callx.app.chat.ui;

import android.content.Context;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.models.Message;

/**
 * v814: shared binder for the reply preview bar (1:1 + group) — WhatsApp-style:
 * sender name, a small type icon + label ("Photo", "Audio (0:07)"), text up to 3 lines,
 * optional thumbnail flush on the right with the close button over it.
 */
public final class ReplyPreviewBinder {

    private ReplyPreviewBinder() { }

    public static final class Preview {
        public String text = "";
        @DrawableRes public int iconRes = 0;
    }

    /** Text + type icon for a message (no gallery-item handling — caller overrides if needed). */
    @NonNull
    public static Preview describe(@NonNull Message m) {
        Preview p = new Preview();
        if (Boolean.TRUE.equals(m.deleted)) { p.text = "Original message unavailable"; return p; }
        String type = m.type == null ? "" : m.type;
        boolean hasText = m.text != null && !m.text.isEmpty();
        switch (type) {
            case "image":
                p.iconRes = R.drawable.ic_photo_library;
                p.text = hasText ? m.text : "Photo"; return p;
            case "video":
                p.iconRes = R.drawable.ic_video;
                p.text = hasText ? m.text : "Video" + dur(m.duration); return p;
            case "audio":
                p.iconRes = R.drawable.ic_audio;
                p.text = "Audio" + dur(m.duration); return p;
            case "gif":
                p.iconRes = R.drawable.ic_gif;
                p.text = "GIF"; return p;
            case "file":
                p.iconRes = R.drawable.ic_file;
                p.text = m.fileName != null && !m.fileName.isEmpty() ? m.fileName : "File"; return p;
            case "sticker": p.text = "Sticker"; return p;
            case "location": p.text = "Location"; return p;
            case "contact":
                p.text = m.contactName != null && !m.contactName.isEmpty()
                        ? "Contact: " + m.contactName : "Contact"; return p;
            case "poll":
                p.text = m.pollQuestion != null && !m.pollQuestion.isEmpty()
                        ? "Poll: " + m.pollQuestion : "Poll"; return p;
            case "multi_media":
                p.iconRes = R.drawable.ic_photo_library;
                p.text = hasText ? m.text : "Photos"; return p;
            default:
                p.text = hasText ? m.text : (type.isEmpty() ? "" : "[" + type + "]");
                return p;
        }
    }

    /** Thumbnail url for image / video messages (null otherwise). */
    @Nullable
    public static String thumbUrl(@NonNull Message m) {
        if ("image".equals(m.type)) return m.mediaUrl;
        if ("video".equals(m.type)) return m.thumbnailUrl;
        return null;
    }

    public static void bind(@NonNull Context ctx, @NonNull View bar, @Nullable String senderName,
                            @NonNull Preview p, @Nullable String thumbUrl) {
        TextView name = bar.findViewById(R.id.tv_reply_bar_name);
        TextView text = bar.findViewById(R.id.tv_reply_bar_text);
        ImageView icon = bar.findViewById(R.id.iv_reply_bar_type);
        ImageView thumb = bar.findViewById(R.id.iv_reply_bar_thumb);
        ImageButton close = bar.findViewById(R.id.btn_cancel_reply);

        if (name != null) name.setText(senderName != null ? senderName : "");
        if (text != null) text.setText(p.text);
        if (icon != null) {
            if (p.iconRes != 0) {
                icon.setImageResource(p.iconRes);
                androidx.core.widget.ImageViewCompat.setImageTintList(icon,
                        android.content.res.ColorStateList.valueOf(
                                androidx.core.content.ContextCompat.getColor(ctx, R.color.text_secondary)));
                icon.setVisibility(View.VISIBLE);
            } else {
                icon.setVisibility(View.GONE);
            }
        }
        boolean hasThumb = thumb != null && thumbUrl != null && !thumbUrl.isEmpty();
        if (thumb != null) {
            if (hasThumb) {
                thumb.setVisibility(View.VISIBLE);
                Glide.with(ctx).load(thumbUrl).centerCrop().override(192, 192).into(thumb);
            } else {
                thumb.setVisibility(View.GONE);
            }
        }
        // Close button: translucent dark disc + white X when it sits over the thumbnail,
        // plain muted X otherwise.
        if (close != null) {
            if (hasThumb) {
                close.setBackgroundResource(R.drawable.bg_chat_reply_close);
                androidx.core.widget.ImageViewCompat.setImageTintList(close,
                        android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
            } else {
                close.setBackground(null);
                androidx.core.widget.ImageViewCompat.setImageTintList(close,
                        android.content.res.ColorStateList.valueOf(
                                androidx.core.content.ContextCompat.getColor(ctx, R.color.text_secondary)));
            }
        }
    }

    private static String dur(@Nullable Long ms) {
        if (ms == null || ms <= 0) return "";
        long s = Math.max(0, Math.round(ms / 1000.0));
        return String.format(java.util.Locale.US, " (%d:%02d)", s / 60, s % 60);
    }
}
