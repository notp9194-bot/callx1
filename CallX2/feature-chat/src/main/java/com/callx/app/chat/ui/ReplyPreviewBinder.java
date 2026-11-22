package com.callx.app.chat.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
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
import com.bumptech.glide.RequestBuilder;
import com.callx.app.chat.R;
import com.callx.app.models.Message;
import com.callx.app.utils.MediaCache;
import com.callx.app.utils.ThumbHashPlaceholder;

import java.io.File;

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
 *
 * v820 (thumbnail decode fix): the reply-bar thumb is now decoded from the BEST AVAILABLE source
 * at the size it is actually drawn, not from message.mediaUrl at a hard-coded 192x192:
 *   1. own local original (sent from this phone)  -> no network, works for pending uploads too
 *   2. MediaCache file (already downloaded, and already DECRYPTED for media-E2E messages)
 *   3. plain remote url (legacy thumbnailUrl preferred over the full-size mediaUrl)
 * and the decode target = the thumb view's real pixel size (64dp * density x bar height).
 * Before: received E2E images/videos had a ciphertext url -> Glide could not decode -> grey box
 * forever; own just-sent media had no url yet -> no thumb; 192px was blurry on xxxhdpi and a
 * 9x over-decode on mdpi.
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
        /** Thumbnail to show on the right (image / video / gallery item); null = none. */
        @Nullable public String thumbUrl;
        /**
         * v820: what Glide should actually decode — a {@link File} (downloaded/decrypted cache or
         * own local original) or a {@link Uri}. null = just load {@link #thumbUrl} as a plain url.
         * When set, {@link #thumbUrl} is a stable identity key for it (path/url), so the
         * "same thumb re-bound -> no reload" check and the has-thumb check keep working.
         */
        @Nullable public Object thumbSource;
        /** v820: plain url tried if {@link #thumbSource} fails (e.g. own local file was deleted). */
        @Nullable public String thumbFallbackUrl;
        /** v820: plaintext ThumbHash shown while decoding (same 32x32 key the bubble already cached). */
        @Nullable public String thumbHash;
    }

    /** Stable colour for a sender (same person -> same colour, every time). */
    public static int colorFor(@Nullable String senderKey) {
        if (senderKey == null || senderKey.isEmpty()) return SENDER_COLORS[0];
        return SENDER_COLORS[(senderKey.hashCode() & 0x7FFFFFFF) % SENDER_COLORS.length];
    }

    /**
     * v819: shared by 1:1 + group. When the reply was triggered from inside the grouped-media
     * gallery on a specific item, quote THAT item (its caption / photo-or-video label + thumb)
     * instead of the generic group label.
     */
    @NonNull
    public static Preview describe(@NonNull Context ctx, @NonNull Message m, int galleryItemIndex) {
        Preview p = describe(ctx, m);
        if (galleryItemIndex < 0 || !"multi_media".equals(m.type)
                || m.mediaItems == null || galleryItemIndex >= m.mediaItems.size()) {
            return p;
        }
        java.util.Map<String, Object> item = m.mediaItems.get(galleryItemIndex);
        if (item == null) return p;
        Object caption = item.get("caption");
        boolean isVideo = "video".equals(item.get("mediaType"));
        boolean hasCap = caption instanceof String && !((String) caption).isEmpty();
        p.text = hasCap ? (String) caption
                : ctx.getString(isVideo ? R.string.chat_reply_video : R.string.chat_reply_photo);
        p.iconRes = isVideo ? R.drawable.ic_video : R.drawable.ic_photo_library;
        // v820: the item has its own plain url — drop the message-level thumb source/placeholder.
        p.thumbSource = null; p.thumbFallbackUrl = null; p.thumbHash = null;
        Object u = item.get("thumbUrl");
        Object u2 = item.get("url");
        p.thumbUrl = (u instanceof String && !((String) u).isEmpty()) ? (String) u
                : (u2 instanceof String ? (String) u2 : null);
        return p;
    }

    /** Text + type icon for a message (no gallery-item handling — see the 3-arg overload). */
    @NonNull
    public static Preview describe(@NonNull Context ctx, @NonNull Message m) {
        Preview p = new Preview();
        p.thumbUrl = thumbUrl(m);
        resolveThumb(ctx, m, p);
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

    /**
     * v820: picks the best decodable source for an image / video message (see class doc).
     * Runs on the main thread at swipe time, so it only does the same single File.exists() stat the
     * bubble's own getCachedFileFast() does — no decrypt, no network, no decode here.
     */
    private static void resolveThumb(@NonNull Context ctx, @NonNull Message m, @NonNull Preview p) {
        final boolean isImage = "image".equals(m.type);
        final boolean isVideo = "video".equals(m.type);
        if (!isImage && !isVideo) return;
        if (Boolean.TRUE.equals(m.deleted)) { p.thumbUrl = null; return; }

        final boolean e2e = m.mediaKeyEnc != null && !m.mediaKeyEnc.isEmpty();
        // Plain-text remote url, ONLY when it is really an image the CDN serves in the clear.
        // E2E media (mediaUrl / thumbnailUrl are ciphertext blobs) must never be handed to Glide.
        String plainRemote = null;
        if (!e2e) {
            plainRemote = isVideo ? m.thumbnailUrl
                    : (notEmpty(m.thumbnailUrl) ? m.thumbnailUrl : m.mediaUrl);   // small legacy thumb beats full-size
            if (!notEmpty(plainRemote)) plainRemote = null;
        }
        p.thumbFallbackUrl = plainRemote;
        if (notEmpty(m.blurHash) && !e2e) p.thumbHash = m.blurHash;   // E2E: hash lives inside the encrypted envelope

        // 1) Own local original (sent from this phone, file still there). Video -> Glide pulls a frame.
        if (notEmpty(m.mediaLocalPath)) {
            final String lp = m.mediaLocalPath;
            p.thumbSource = lp.startsWith("/") ? (Object) new File(lp) : Uri.parse(lp);
            p.thumbUrl = lp;
            return;
        }
        // 2) Already-downloaded (and, for E2E, already decrypted) copy in MediaCache.
        String cacheKeyUrl = isVideo ? m.thumbnailUrl : m.mediaUrl;
        File cached = notEmpty(cacheKeyUrl) ? MediaCache.getCached(ctx, cacheKeyUrl) : null;
        if (cached == null && isVideo && notEmpty(m.mediaUrl)) {
            cached = MediaCache.getCached(ctx, m.mediaUrl);   // no poster, but the video itself is local
        }
        if (cached != null) {
            p.thumbSource = cached;
            p.thumbUrl = cached.getAbsolutePath();
            return;
        }
        // 3) Plain remote url (non-E2E) — or nothing decodable (E2E not downloaded yet): no thumb tile.
        p.thumbSource = null;
        p.thumbUrl = plainRemote;
    }

    private static boolean notEmpty(@Nullable String s) { return s != null && !s.isEmpty(); }

    /** Thumb tile is 64dp wide (layout_chat_reply_preview.xml); the card is 48dp..~88dp tall (3 text lines). */
    private static final int THUMB_W_DP = 64;
    private static final int THUMB_MAX_H_DP = 88;

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
                // Same source re-bound (retarget / re-show) -> keep the decoded image, no flicker.
                if (!thumbUrl.equals(thumb.getTag(R.id.iv_reply_bar_thumb))) {
                    thumb.setTag(R.id.iv_reply_bar_thumb, thumbUrl);
                    loadThumb(ctx, thumb, p, thumbUrl);
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

    /**
     * v820: decode the thumb at the size it is really drawn. Glide's default (no override) would wait
     * for the view's first layout, which never happens while the bar is still GONE during bind() —
     * so the decode would start a frame late, after the bar animation began. Instead the target is
     * computed here: exact width (64dp), and the real height once the view has been laid out, else
     * the 3-line maximum. Glide centerCrop at exactly that aspect -> the ImageView's own centerCrop
     * is a no-op or a <=1.3x scale, never a blurry upscale.
     */
    private static void loadThumb(@NonNull Context ctx, @NonNull ImageView thumb,
                                  @NonNull Preview p, @NonNull String thumbUrl) {
        final float d = ctx.getResources().getDisplayMetrics().density;
        final int w = thumb.getWidth() > 0 ? thumb.getWidth() : Math.round(THUMB_W_DP * d);
        final int h = thumb.getHeight() > 0 ? thumb.getHeight() : Math.round(THUMB_MAX_H_DP * d);

        Drawable ph = null;
        if (p.thumbHash != null) {
            // Same (hash,32,32) key MessagePagingAdapter used for the bubble -> normally an L1 hit.
            Bitmap hb = ThumbHashPlaceholder.get(p.thumbHash, 32, 32);
            if (hb != null) ph = new BitmapDrawable(ctx.getResources(), hb);
        }
        if (ph == null) ph = new ColorDrawable(0x22808080);

        final Object src = (p.thumbSource != null && thumbUrl.equals(p.thumbUrl)) ? p.thumbSource : thumbUrl;
        RequestBuilder<Drawable> rb = Glide.with(ctx).load(src)
                .placeholder(ph).centerCrop().override(w, h).dontAnimate();
        // Local original gone / unreadable -> fall back to the plain remote url (non-E2E only).
        if (src != thumbUrl && p.thumbFallbackUrl != null) {
            rb = rb.error(Glide.with(ctx).load(p.thumbFallbackUrl)
                    .centerCrop().override(w, h).dontAnimate());
        }
        rb.into(thumb);
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
