package com.callx.app.chat.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.models.Message;

/**
 * v814: shared binder for the reply preview bar (1:1 + group) — WhatsApp-style:
 * sender name, a small type icon + label ("Photo", "Audio (0:07)"), text up to 3 lines,
 * optional thumbnail flush on the right with the close button over it.
 *
 * v816 polish: all labels come from string resources (describe() now takes a Context), every
 * non-text type has an icon, the thumbnail has a placeholder + no cross-fade and is not reloaded
 * when the same url is re-bound (retarget flicker) and is cleared when there is no thumb, the
 * text column only reserves room for the close button when there is no thumbnail, and the
 * accent line + sender name take a per-sender colour.
 *
 * v817 perf (ported from the voice-recording bar work): child views come from the cached
 * references in {@link ReplyBarLayout} (no findViewById per bind), redundant setText /
 * setTextColor calls are skipped (each one triggers a relayout of the capsule), and very long
 * message text is cut before it reaches the TextView — a 4000-char message used to be measured
 * in full just to show 3 lines.
 */
public final class ReplyPreviewBinder {

    private ReplyPreviewBinder() { }

    /** Sender accent palette (readable on light + dark card). Own messages use the chat theme colour. */
    private static final int[] SENDER_COLORS = {
            0xFF00897B, 0xFF8E24AA, 0xFFEF6C00, 0xFF3949AB,
            0xFFD81B60, 0xFF1E88E5, 0xFF6D4C41, 0xFF00ACC1
    };

    public static final class Preview {
        public String text = "";
        @DrawableRes public int iconRes = 0;
    }

    /** Stable colour for a sender (same person -> same colour, every time). */
    public static int colorFor(@Nullable String senderKey) {
        if (senderKey == null || senderKey.isEmpty()) return SENDER_COLORS[0];
        return SENDER_COLORS[(senderKey.hashCode() & 0x7FFFFFFF) % SENDER_COLORS.length];
    }

    /** Text + type icon for a message (no gallery-item handling — caller overrides if needed). */
    @NonNull
    public static Preview describe(@NonNull Context ctx, @NonNull Message m) {
        Preview p = new Preview();
        if (Boolean.TRUE.equals(m.deleted)) { p.text = ctx.getString(R.string.chat_reply_unavailable); return p; }
        String type = m.type == null ? "" : m.type;
        boolean hasText = m.text != null && !m.text.isEmpty();
        switch (type) {
            case "image":
                p.iconRes = R.drawable.ic_photo_library;
                p.text = hasText ? m.text : ctx.getString(R.string.chat_reply_photo); return p;
            case "video":
                p.iconRes = R.drawable.ic_video;
                p.text = hasText ? m.text : ctx.getString(R.string.chat_reply_video) + dur(m.duration); return p;
            case "audio":
                p.iconRes = R.drawable.ic_audio;
                p.text = ctx.getString(R.string.chat_reply_audio) + dur(m.duration); return p;
            case "gif":
                p.iconRes = R.drawable.ic_gif;
                p.text = ctx.getString(R.string.chat_reply_gif); return p;
            case "file":
                p.iconRes = R.drawable.ic_file;
                p.text = m.fileName != null && !m.fileName.isEmpty()
                        ? m.fileName : ctx.getString(R.string.chat_reply_file); return p;
            case "sticker":
                p.iconRes = R.drawable.ic_sticker;
                p.text = ctx.getString(R.string.chat_reply_sticker); return p;
            case "location":
                p.iconRes = R.drawable.ic_location_pin;
                p.text = ctx.getString(R.string.chat_reply_location); return p;
            case "contact":
                p.iconRes = R.drawable.ic_person;
                p.text = m.contactName != null && !m.contactName.isEmpty()
                        ? ctx.getString(R.string.chat_reply_contact_named, m.contactName)
                        : ctx.getString(R.string.chat_reply_contact); return p;
            case "poll":
                p.iconRes = R.drawable.ic_poll;
                p.text = m.pollQuestion != null && !m.pollQuestion.isEmpty()
                        ? ctx.getString(R.string.chat_reply_poll_named, m.pollQuestion)
                        : ctx.getString(R.string.chat_reply_poll); return p;
            case "multi_media":
                p.iconRes = R.drawable.ic_photo_library;
                p.text = hasText ? m.text : ctx.getString(R.string.chat_reply_photos); return p;
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
        bind(ctx, bar, senderName, p, thumbUrl, 0);
    }

    /** @param accentColor colour for the accent line + sender name; 0 = leave as is. */
    public static void bind(@NonNull Context ctx, @NonNull View bar, @Nullable String senderName,
                            @NonNull Preview p, @Nullable String thumbUrl, int accentColor) {
        final ReplyBarLayout rb = bar instanceof ReplyBarLayout ? (ReplyBarLayout) bar : null;
        TextView name = rb != null ? rb.nameView() : bar.findViewById(R.id.tv_reply_bar_name);
        TextView text = rb != null ? rb.textView() : bar.findViewById(R.id.tv_reply_bar_text);
        ImageView icon = rb != null ? rb.typeView() : bar.findViewById(R.id.iv_reply_bar_type);
        ImageView thumb = rb != null ? rb.thumbView() : bar.findViewById(R.id.iv_reply_bar_thumb);
        ImageButton close = rb != null ? rb.closeView() : bar.findViewById(R.id.btn_cancel_reply);
        View texts = rb != null ? rb.textsView() : bar.findViewById(R.id.ll_reply_bar_texts);
        View accent = rb != null ? rb.accentView() : bar.findViewById(R.id.view_reply_accent);

        if (name != null) {
            setTextIfChanged(name, senderName != null ? senderName : "");
            if (accentColor != 0 && name.getCurrentTextColor() != accentColor) {
                name.setTextColor(accentColor);
            }
        }
        if (accent != null && accentColor != 0) accent.setBackgroundColor(accentColor);
        if (text != null) setTextIfChanged(text, clip(p.text));

        final int secondary = ContextCompat.getColor(ctx, R.color.text_secondary);
        if (icon != null) {
            if (p.iconRes != 0) {
                icon.setImageResource(p.iconRes);
                ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(secondary));
                icon.setVisibility(View.VISIBLE);
            } else {
                icon.setVisibility(View.GONE);
            }
        }

        boolean hasThumb = thumb != null && thumbUrl != null && !thumbUrl.isEmpty();
        if (thumb != null) {
            if (hasThumb) {
                thumb.setVisibility(View.VISIBLE);
                // Same url re-bound (retarget / re-show) -> keep the decoded image, no flicker.
                if (!thumbUrl.equals(thumb.getTag(R.id.iv_reply_bar_thumb))) {
                    thumb.setTag(R.id.iv_reply_bar_thumb, thumbUrl);
                    Glide.with(ctx).load(thumbUrl)
                            .placeholder(new ColorDrawable(0x22808080))
                            .centerCrop().override(192, 192).dontAnimate()
                            .into(thumb);
                }
            } else {
                thumb.setTag(R.id.iv_reply_bar_thumb, null);
                Glide.with(ctx).clear(thumb);   // cancel any in-flight load from the previous reply
                thumb.setImageDrawable(null);
                thumb.setVisibility(View.GONE);
            }
        }

        // The close button only overlaps the TEXT when there is no thumbnail; with a thumbnail it
        // sits on the image, so the text column can use the full width.
        if (texts != null) {
            float d = ctx.getResources().getDisplayMetrics().density;
            int endPad = (int) ((hasThumb ? 10f : 32f) * d);
            if (texts.getPaddingEnd() != endPad) {
                texts.setPaddingRelative(texts.getPaddingStart(), texts.getPaddingTop(),
                        endPad, texts.getPaddingBottom());
            }
        }

        // Close button: translucent dark disc + white X when it sits over the thumbnail,
        // plain muted X otherwise.
        if (close != null) {
            if (hasThumb) {
                close.setBackgroundResource(R.drawable.bg_chat_reply_close);
                ImageViewCompat.setImageTintList(close, ColorStateList.valueOf(0xFFFFFFFF));
            } else {
                close.setBackground(null);
                ImageViewCompat.setImageTintList(close, ColorStateList.valueOf(secondary));
            }
        }
    }

    /** Reply preview shows max 3 lines — never hand the TextView more than this. */
    private static final int MAX_PREVIEW_CHARS = 240;

    @NonNull
    private static String clip(@Nullable String t) {
        if (t == null) return "";
        if (t.length() <= MAX_PREVIEW_CHARS) return t;
        return t.substring(0, MAX_PREVIEW_CHARS).trim() + "\u2026";
    }

    /** setText() always requests a layout; skip it when nothing changed (e.g. retarget same text). */
    private static void setTextIfChanged(@NonNull TextView tv, @NonNull CharSequence next) {
        CharSequence cur = tv.getText();
        if (cur != null && cur.length() == next.length() && TextUtils.equals(cur, next)) return;
        tv.setText(next);
    }

    private static String dur(@Nullable Long ms) {
        if (ms == null || ms <= 0) return "";
        long s = Math.max(0, Math.round(ms / 1000.0));
        return String.format(java.util.Locale.US, " (%d:%02d)", s / 60, s % 60);
    }
}
