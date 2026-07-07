package com.callx.app.conversation.canvas;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.callx.app.utils.ChatThemeManager;

/**
 * MessageBubbleCanvasView — Phase 1 of the Telegram-style rendering rewrite.
 *
 * WHY THIS EXISTS
 * ────────────────
 * Every item_message_sent/received.xml bind currently pays Android's normal
 * View cost even after all the RecyclerView-level tuning (Paging3, DiffUtil,
 * RecycledViewPool, item-view cache, prefetch, etc.):
 *   • inflate (first bind of a fresh holder)
 *   • measure + layout of the whole ConstraintLayout > LinearLayout(bubble)
 *     > TextView/TextView(footer) subtree, on every single bind
 *   • a TextView draw path (its own internal layout cache, but still a full
 *     View in the hierarchy with its own dispatch overhead)
 *
 * Telegram's message list never pays any of that: a message row is ONE
 * custom View whose onDraw() paints the bubble background, the message
 * text (via a plain StaticLayout — no child TextView at all), and the
 * timestamp/tick directly onto the Canvas. There is nothing to inflate and
 * nothing to measure-and-layout for the two most expensive parts (the bubble
 * shape and the text block) — they're computed once in onMeasure() and then
 * just drawn.
 *
 * SCOPE OF THIS FILE
 * ──────────────────
 * This view handles:
 *   • plain-text bubbles — sender's own text + timestamp + read/delivered tick
 *   • the reply-preview strip (quoted sender name + quoted text + optional
 *     thumbnail, matching ll_reply_preview in item_message_sent/received.xml)
 *   • a single fixed-size (180dp square) IMAGE bubble with optional caption,
 *     matching iv_image in item_message_sent/received.xml — rounded corners,
 *     centerCrop-equivalent scaling, optional caption text below the image,
 *     and (for captionless images) a translucent timestamp/tick pill
 *     overlaid on the image itself, WhatsApp-style.
 *   • a multi-image/video grid (bindMediaGroup, both SENT and RECEIVED) —
 *     mirrors MediaGroupLayoutHelper's 1/2/3/4/5-9/10+ layout rules, video
 *     play-icon + duration badge, "+N" overflow on the last cell, and a
 *     group caption scrim overlapping the grid's bottom edge. See
 *     isCanvasEligible() in MessagePagingAdapter for exactly what qualifies
 *     (plain image/video cells only, no per-item captions).
 *   • for RECEIVED groups specifically: the same manual download-gate
 *     treatment as MediaGroupLayoutHelper's old grid — a full-grid dim
 *     scrim + centered "Download N photos" pill while any image cell is
 *     still un-cached (setGroupDownloadGate), and a small per-cell
 *     dim+icon badge for an individual cell's in-flight download
 *     (setGroupCellDownloading/markGroupCellDownloaded) once the master
 *     pill has been dismissed. Video cells never gate (they stream
 *     directly, same precedent as the old helper).
 *   • a reaction badge (setReactions/clearReactions) — mirrors ll_reactions/
 *     tv_reactions: plain-emoji text floating over the bubble's bottom-end
 *     corner, no background box. Works alongside any of the modes above.
 *   • a pinned label (setPinned) — "📌 Pinned" text above the bubble's
 *     top-end corner, works for sent and received alike.
 *   • a group-chat sender-name row (setGroupSender/clearGroupSender) —
 *     mirrors tv_sender_name (11sp bold, brand_primary) above the bubble's
 *     top-start corner, received messages only. The sender AVATAR
 *     (iv_sender_avatar) is NOT modeled — it's dead markup in the legacy
 *     layout too, never actually bound anywhere. This same row is also
 *     used for the 📢 broadcast badge (either "📢 Broadcast" in a 1:1
 *     chat, or a "📢 " prefix on the group sender name) — the caller
 *     (MessagePagingAdapter) composes whichever string applies and passes
 *     it through setGroupSender(), same as the legacy tv_sender_name path.
 *   • a forwarded-message label (setForwardedFrom/clearForwarded) — mirrors
 *     tv_forwarded ("↪ Forwarded from X", 11sp italic, #888888), stacked
 *     directly below the pinned-label/group-sender row, same corner.
 *   • a deleted-message placeholder (setDeletedStyle) — once the caller
 *     substitutes "This message was deleted"/"You deleted this message"
 *     as the bind() text, this switches the text paint italic + 60% alpha
 *     to match bindMessage()'s tvMessage.setAlpha(0.6f) treatment.
 *   • a disappearing-message countdown (setExpiryText/clearExpiry) — mirrors
 *     tv_expiry ("⏳ mm:ss", 9sp, gold) inside the footer row, just before
 *     the timestamp. The caller re-calls this once a second via the shared
 *     ExpiryTickManager, same cadence as the legacy TextView path.
 *
 * It intentionally does NOT (yet) handle:
 *   • audio/file cells inside a media group, or per-item captions
 *   • GIF, file, or poll bubbles (contact and location are now modeled — see below)
 *   • long-press action menu (long-press itself is wired — see
 *     OnBubbleClickListener.onBubbleLongClick — the menu it opens is the
 *     caller's job)
 *
 * It DOES now also handle:
 *   • a live download-progress overlay for a RECEIVED single-image bubble
 *     not yet cached locally — setMediaDownloadGate()/setMediaDownloadProgress()
 *     draw a dim scrim + centered pill (idle "⬇ <label>" or a real
 *     spinner/percentage ring while in flight), mirroring the legacy
 *     fl_download_overlay/pb_download_spinner treatment. The same live
 *     ring (drawProgressRing) also now drives the media-GROUP per-cell
 *     badge (setGroupCellProgress) instead of the old static partial-arc.
 *   • swipe-to-reply gesture — a horizontal drag past a threshold reveals a
 *     reply glyph and reports the gesture back via
 *     OnBubbleClickListener.onSwipeToReply(), same trigger point as a
 *     long-press → "Reply" menu action, just via a gesture instead.
 *   • a reel-share card (bindReelShare) — mirrors layout_msg_reel_share.xml
 *     exactly: a bubbleless 165×237dp Instagram-style card (no chat-bubble
 *     background at all — the only mode this view has that skips
 *     bubbleDrawable entirely), with an avatar+username header over a top
 *     gradient, a centered play glyph, a caption + "⬡ Reels" label over a
 *     bottom gradient, and the usual timestamp/tick pill in the bottom-end
 *     corner (always shown here, unlike the image bubble's caption-gated
 *     version). Tapping anywhere on the card fires onImageClick(), same as
 *     the legacy ll_reel_share click listener.
 *   • a single "video" message (bindVideo) — reuses bindMedia()'s exact
 *     180dp-square slot/mediaRect/footer-pill machinery, just with a
 *     play-circle+triangle glyph centered on the thumbnail and a duration
 *     badge in the bottom-left corner (drawVideoPlayOverlay), mirroring
 *     the legacy fl_video/iv_video_thumb bubble. Never has a caption, same
 *     as that legacy case. Tapping the thumbnail fires onImageClick(),
 *     same trigger point as tapping an image bubble.
 *   • a link-preview card (setLinkPreview/setLinkPreviewThumbBitmap/
 *     clearLinkPreview) — mirrors layout_msg_link_preview.xml
 *     (stub_link_preview): optional OG-image thumbnail, domain label,
 *     and bold 2-line title, stacked below the message text. Only
 *     meaningful for the plain-text bind() mode. Tapping the card fires
 *     onLinkClick(url), same as the legacy ll_link_preview click
 *     listener that opens the URL in a browser.
 *   • a contact-share card (bindContact) — mirrors item_msg_contact.xml:
 *     a bubbleless 165dp-wide card, circular avatar (or ic_person
 *     placeholder) + name/phone on a dark #1C1C1E top section, a thin
 *     divider, and a "View Contact" action row below. No timestamp/tick
 *     footer at all (the legacy layout has none for this type). Only the
 *     "View Contact" row is tappable (onContactViewClick()) — the rest of
 *     the card has no click listener in the legacy path either, just
 *     long-press for the action sheet, same as every other mode here.
 *   • a location-share card (bindLocation) — mirrors item_msg_location.xml:
 *     a bubbleless 165dp-wide card, same shape family as the contact card —
 *     a purple map-thumbnail header (Google Static Maps bitmap if supplied,
 *     else a placeholder pin drawn straight on the Canvas), a translucent
 *     purple address strip (up to 2 lines), and an "Open in Maps" action
 *     row. No timestamp/tick footer at all (matches item_msg_location.xml
 *     having none). Only the "Open in Maps" row is tappable
 *     (onLocationOpenMapsClick()) — same precedent as bindContact()'s
 *     "View Contact" row.
 *
 * Those all still render through the existing item_message_sent/received.xml
 * + MessagePagingAdapter path.
 *
 * HOW TO WIRE THIS IN
 * ────────────────────
 * This class is intentionally standalone (not yet plugged into
 * MessagePagingAdapter). To use it for a message, MessagePagingAdapter would
 * need a new view-type branch that, for messages whose shape this view
 * covers (plain text or single-image, with or without a reply, no
 * forward/pin/reactions/multi-image/video/etc.), inflates/reuses a
 * MessageBubbleCanvasView instead of item_message_sent/received.xml, and
 * falls back to the existing ViewHolder path for every other message shape.
 * Per bind: call bind(...) OR bindMedia(...) first (whichever matches the
 * message), then EITHER setReply(sender, text, thumb) for replies OR
 * clearReply() for non-replies — always call one of the two, every bind,
 * since this view is reused across scroll and a recycled instance still
 * holds whatever state the previous message left in it. For images loaded
 * asynchronously (Glide), call setMediaBitmap(bitmap) once decoded instead
 * of a full rebind.
 * That branch is the next real step — ask when ready and it can be added
 * as its own reviewable change.
 */
public class MessageBubbleCanvasView extends View {

    static final float CORNER_RADIUS_DP = 18f;
    static final float TAIL_RADIUS_DP    = 4f;
    static final float H_PADDING_DP      = 12f;
    static final float V_PADDING_DP      = 8f;
    static final float TEXT_SIZE_SP      = 15f;
    static final float FOOTER_GAP_DP     = 4f;
    static final float FOOTER_TEXT_SP    = 11f;
    static final float TICK_GAP_DP       = 3f;
    static final float TICK_SIZE_DP      = 12f;
    static final float MAX_BUBBLE_WIDTH_FRACTION = 0.78f;

    // ── Reply-preview strip — mirrors ll_reply_preview in
    // item_message_sent/received.xml exactly (margins, sizes, colors) ──
    static final float REPLY_MARGIN_DP       = 4f;   // outer margin around the whole strip
    static final float REPLY_BAR_WIDTH_DP    = 3f;   // colored left bar
    static final float REPLY_PADDING_H_DP    = 8f;   // inner horizontal padding (text column)
    static final float REPLY_PADDING_V_DP    = 4f;   // inner vertical padding (text column)
    static final float REPLY_MIN_HEIGHT_DP   = 36f;
    static final float REPLY_CORNER_RADIUS_DP = 6f;
    static final float REPLY_SENDER_SIZE_SP  = 11f;
    static final float REPLY_TEXT_SIZE_SP    = 12f;
    static final float REPLY_THUMB_SIZE_DP   = 44f;
    static final float REPLY_THUMB_MARGIN_DP = 4f;
    static final float REPLY_GAP_TO_MESSAGE_DP = 2f; // gap between reply strip and message text below it

    static final int SENT_REPLY_BG     = 0x33000000;
    static final int SENT_REPLY_BAR    = 0xFFFFD700;
    static final int SENT_REPLY_SENDER = 0xFFFFD54F;
    static final int SENT_REPLY_TEXT   = 0xDDFFFFFF;
    static final int RECEIVED_REPLY_BG = 0x22000000;

    // ── Image media bubble — mirrors iv_image (bg_media_card 12dp corner
    // radius) in item_message_sent/received.xml. As of the WhatsApp/
    // Telegram-style sizing fix, the slot is no longer a fixed square: its
    // width/height are derived from the real image's aspect ratio (see
    // mediaAspectRatio + computeMediaSize()) and only clamped between
    // MEDIA_MIN_*_DP/MEDIA_MAX_*_DP. The real ratio is now known
    // synchronously for every message carrying Message.mediaWidth/
    // mediaHeight (captured once at send time — see ChatMediaController /
    // ImageCompressor / VideoCompressor), so the vast majority of binds —
    // including a message's very first-ever appearance — size correctly on
    // the first layout pass with zero placeholder flash. Only messages
    // sent before that metadata existed, whose image also isn't already in
    // MEDIA_ASPECT_CACHE, fall back to FALLBACK_ASPECT_RATIO (4:3, see
    // computeMediaSize()) for one layout pass until setMediaBitmap()
    // supplies the real decoded Bitmap. MEDIA_SIZE_DP is no longer read by
    // computeMediaSize() and is kept only for reference/back-compat.
    static final float MEDIA_SIZE_DP           = 180f;
    static final float MEDIA_MAX_WIDTH_DP      = 260f;  // WhatsApp-style cap so a wide landscape photo doesn't swallow the whole row
    static final float MEDIA_MIN_WIDTH_DP      = 120f;  // floor for a very tall/narrow portrait photo
    static final float MEDIA_MAX_HEIGHT_DP     = 300f;  // cap for a very tall portrait photo
    static final float MEDIA_MIN_HEIGHT_DP     = 120f;  // floor for a very wide/short landscape photo
    static final float MEDIA_CORNER_RADIUS_DP  = 12f;
    static final float MEDIA_MARGIN_DP         = 2f;   // gap between media edge and bubble edge
    static final float MEDIA_CAPTION_GAP_DP    = 6f;   // gap between image and caption text
    static final float MEDIA_PILL_PADDING_H_DP = 6f;
    static final float MEDIA_PILL_PADDING_V_DP = 3f;
    static final float MEDIA_PILL_MARGIN_DP    = 6f;   // pill inset from image's bottom-right corner
    static final float MEDIA_PILL_CORNER_DP    = 8f;
    static final int   MEDIA_PILL_BG           = 0x66000000; // translucent black, WhatsApp-style
    static final int   MEDIA_PILL_TEXT         = 0xFFFFFFFF;
    static final int   MEDIA_PLACEHOLDER_COLOR = 0xFFD9D9D9; // shown until Bitmap arrives — no theme lookup yet (see class doc)

    // ── Audio (voice message) bubble — mirrors layout_msg_audio.xml
    // exactly: a 40dp circular play/pause button (circle_primary bg,
    // white glyph), a flexible waveform track, and a small elapsed-time
    // label, all in one row; the usual timestamp/tick footer sits below
    // the row (like the plain-text bubble's footer — an audio bubble is
    // never captioned, so there's no pill-overlay mode like the image
    // bubble has). The "waveform" itself is generated once from a stable
    // seed (the audio URL) exactly like AudioWaveformView's placeholder
    // bars — no real PCM amplitude extraction — so the same voice note
    // always renders the same bar shape across rebinds/recycling. ──
    static final float AUDIO_CONTENT_WIDTH_DP = 200f; // fixed row content width (excludes hPad)
    static final float AUDIO_BTN_SIZE_DP       = 40f;
    static final float AUDIO_ROW_GAP_DP        = 8f;  // button↔waveform and waveform↔duration gaps
    static final float AUDIO_DUR_WIDTH_DP      = 36f;
    static final float AUDIO_DUR_TEXT_SP       = 11f;
    static final float AUDIO_WAVEFORM_HEIGHT_DP = 28f;
    static final int   AUDIO_BAR_COUNT         = 28;
    static final float AUDIO_BAR_GAP_RATIO     = 0.45f; // fraction of each bar's slot left as gap
    static final int   AUDIO_BTN_BG_COLOR      = 0xFF008069; // matches circle_primary/brand_primary
    static final int   AUDIO_BTN_ICON_COLOR    = 0xFFFFFFFF;
    static final float AUDIO_PLAY_TRIANGLE_DP  = 14f;
    static final float AUDIO_PAUSE_BAR_W_DP    = 3.5f;
    static final float AUDIO_PAUSE_BAR_H_DP    = 14f;
    static final float AUDIO_PAUSE_BAR_GAP_DP  = 4f;

    // ── Reaction badge — mirrors ll_reactions/tv_reactions in
    // item_message_sent/received.xml: a plain-emoji badge (no background
    // box) floating outside the bubble's bottom-end corner, pulled down by
    // a negative margin so it overlaps without covering the text/footer.
    // Same treatment for sent and received (both layouts constrain it
    // identically to the bubble's end/bottom). ──
    static final float REACTIONS_TEXT_SP      = 28f;
    static final float REACTIONS_MARGIN_END_DP = 4f;
    static final float REACTIONS_OVERLAP_DP   = 14f; // how far the badge hangs below the bubble's bottom edge (matches layout_marginBottom="-14dp")
    static final int   REACTIONS_SHADOW_COLOR = 0x66000000;

    // ── Pinned label — mirrors tv_pinned_label's text/size/color from
    // item_message_sent.xml ("📌 Pinned", 10sp, #E65100). That XML view was
    // never actually bound anywhere in MessagePagingAdapter (dead markup,
    // sent-layout-only, aligned to the parent row's end rather than the
    // bubble), so rather than reproduce that half-finished/sent-only
    // placement, this anchors the label to the BUBBLE's own top-end corner
    // — works symmetrically for sent and received alike. ──
    static final float PINNED_LABEL_TEXT_SP = 10f;
    static final int   PINNED_LABEL_COLOR   = 0xFFE65100;
    static final String PINNED_LABEL_TEXT   = "📌 Pinned";
    static final float PINNED_LABEL_GAP_DP  = 2f; // gap between label and bubble top
    static final float GROUP_SENDER_TEXT_SP = 11f; // matches tv_sender_name in item_message_received.xml

    // ── Forwarded label — mirrors tv_forwarded's text/size/color/style from
    // item_message_received.xml ("↪ Forwarded from X", 11sp italic, #888888).
    // Stacked directly below the pinned-label/group-sender row (same
    // constraintTop_toBottomOf relationship the legacy layout uses). ──
    static final float FORWARDED_LABEL_TEXT_SP = 11f;
    static final int   FORWARDED_LABEL_COLOR   = 0xFF888888;

    // ── Deleted-message placeholder — mirrors bindMessage()'s
    // tvMessage.setAlpha(0.6f) treatment for "This message was deleted" /
    // "You deleted this message"; italic added for the same "ghost text"
    // effect most chat apps use. ──
    static final int DELETED_TEXT_ALPHA = 153; // ~0.6 * 255

    // ── Disappearing-message countdown — mirrors tv_expiry's text/size/color
    // from item_message_sent/received.xml ("⏳ mm:ss", 9sp, gold), drawn in
    // the footer row just before the timestamp. ──
    static final float EXPIRY_TEXT_SP = 9f;
    static final int   EXPIRY_COLOR   = 0xFFFFCC00;
    static final float EXPIRY_GAP_DP  = 4f;

    // ── Media GROUP (multi-image/video grid) — mirrors MediaGroupLayoutHelper's
    // layout rules/constants exactly for visual parity, but is an entirely
    // separate mode from the single-image `isMedia` path above (own state,
    // own measure/draw/hit-test) so the already-working single-image path
    // can't regress. See MessagePagingAdapter.isCanvasEligible(): only
    // SENT groups of plain image/video items (no audio/file cells, no
    // per-item captions) reach this — received groups keep the manual
    // download-gate UI on the old path, same precedent as single images. ──
    static final int GROUP_SINGLE_W    = 240;
    static final int GROUP_SINGLE_H    = 200;
    static final int GROUP_PAIR_CELL   = 118;
    static final int GROUP_THREE_TOP_W = 240;
    static final int GROUP_THREE_TOP_H = 140;
    static final int GROUP_THREE_BOT   = 116;
    static final int GROUP_GRID2_CELL  = 118;
    static final int GROUP_GRID3_CELL  = 78;
    static final int GROUP_GAP         = 2;
    static final int GROUP_CORNER_R    = 4; // per-cell radius (matches buildCell's 4dp)
    static final int GROUP_MAX_VISIBLE = 9; // 3x3 cap; beyond this, last cell shows "+N"
    static final float GROUP_CAPTION_SCRIM_H_DP = 40f;
    static final float GROUP_CAPTION_TEXT_SP    = 14f;
    static final int   GROUP_PLAY_CIRCLE_DP      = 36;
    static final int   GROUP_PLAY_TRIANGLE_DP    = 20;
    static final int   GROUP_DURATION_TEXT_SP    = 10;
    // ── Per-item caption strip — mirrors MediaGroupLayoutHelper's per-cell
    // caption exactly: a 22dp gradient scrim pinned to the cell's bottom
    // edge, single-line ellipsized 10sp white text. When a video cell also
    // has a caption, its duration badge moves to the cell's top-end corner
    // instead (same conflict-avoidance the legacy helper uses). ──
    static final float GROUP_ITEM_CAPTION_STRIP_H_DP = 22f;
    static final float GROUP_ITEM_CAPTION_TEXT_SP    = 10f;
    static final float GROUP_ITEM_CAPTION_MARGIN_DP  = 4f;
    static final float GROUP_ITEM_CAPTION_BOTTOM_DP  = 2f;

    // ── Received-group manual download-gate — mirrors MediaGroupLayoutHelper's
    // addMasterDownloadOverlay()/buildCellDownloadOverlay() visuals exactly
    // (same colors/sizes), just Canvas-drawn instead of View-built. Only
    // reachable for RECEIVED groups — see setGroupDownloadGate(). ──
    static final int   GROUP_GATE_SCRIM_COLOR      = 0x2E000000; // dims whole grid while gate is up
    static final int   GROUP_GATE_PILL_BG          = 0x8A000000;
    static final float GROUP_GATE_PILL_CORNER_DP   = 24f;
    static final float GROUP_GATE_PILL_PAD_H_DP    = 14f;
    static final float GROUP_GATE_PILL_PAD_V_DP    = 8f;
    static final float GROUP_GATE_PILL_ICON_DP     = 18f;
    static final float GROUP_GATE_PILL_ICON_GAP_DP = 8f;
    static final float GROUP_GATE_PILL_TEXT_SP     = 13f;
    static final int   GROUP_CELL_GATE_DIM_COLOR   = 0x40000000; // per-cell badge once master pill dismissed
    static final int   GROUP_CELL_GATE_BADGE_BG    = 0x99000000;
    static final float GROUP_CELL_GATE_BADGE_DP    = 26f;
    static final float GROUP_CELL_GATE_ICON_DP     = 14f;

    // ── Live download-progress ring — shared by the single-media gate pill
    // and the per-cell group badge (see drawProgressRing()). Determinate
    // (percent 0-100): a clockwise arc from 12 o'clock. Indeterminate
    // (percent < 0, no progress events yet): a short arc that rotates on
    // wall-clock time via postInvalidateOnAnimation() — no Handler/
    // ValueAnimator bookkeeping needed, and every visible instance across
    // the RecyclerView stays in sync since they all read the same clock. ──
    static final long  INDETERMINATE_PERIOD_MS = 900L;
    static final float INDETERMINATE_SWEEP_DEG = 100f;

    // ── Single-media manual download gate — mirrors the legacy
    // fl_download_overlay/pb_download_spinner treatment for a RECEIVED
    // "image" message not yet cached locally: a dim scrim over the image
    // slot with a centered pill, idle "⬇ <label>" (tap to start) or a live
    // spinner/percentage while a download is in flight. Visually reuses the
    // GROUP_GATE_PILL_* constants above for parity with the group version. ──
    static final int MEDIA_GATE_SCRIM_COLOR = 0x2E000000;

    // ── Reel-share card — mirrors layout_msg_reel_share.xml exactly: a
    // bubbleless 165×237dp Instagram-style reel card (NOT the normal chat
    // bubble shape — no bubbleDrawable is drawn for this mode at all, just
    // the card itself), with a header (avatar+username) over a top
    // gradient, a centered play glyph, and a caption + "⬡ Reels" label
    // over a bottom gradient. The timestamp/tick pill reuses the same
    // translucent-pill treatment as a captionless image
    // (MEDIA_PILL_*/mediaPillRect), pinned to the card's bottom-end corner
    // exactly like ll_msg_footer's bottom|end FrameLayout gravity in the
    // legacy layout — always shown regardless of caption presence, since
    // the caption is a separate overlay inside the bottom gradient. ──
    static final float REEL_CARD_WIDTH_DP        = 165f;
    static final float REEL_CARD_HEIGHT_DP       = 237f;
    static final float REEL_CORNER_RADIUS_DP     = 12f;
    static final float REEL_TOP_GRADIENT_DP      = 60f;
    static final float REEL_BOTTOM_GRADIENT_DP   = 80f;
    static final float REEL_HEADER_PAD_H_DP      = 8f;
    static final float REEL_HEADER_PAD_TOP_DP    = 7f;
    static final float REEL_AVATAR_SIZE_DP       = 24f;
    static final float REEL_AVATAR_TEXT_GAP_DP   = 5f;
    static final float REEL_USERNAME_TEXT_SP     = 10f;
    static final float REEL_PLAY_ICON_SP         = 28f;
    static final int   REEL_PLAY_ICON_COLOR      = 0xDDFFFFFF;
    static final float REEL_BOTTOM_PAD_H_DP      = 8f;
    static final float REEL_BOTTOM_PAD_END_DP    = 36f; // room for the footer pill, matches legacy paddingEnd
    static final float REEL_BOTTOM_PAD_BOTTOM_DP = 7f;
    static final float REEL_CAPTION_TEXT_SP      = 10f;
    static final float REEL_LABEL_TEXT_SP        = 9f;
    static final float REEL_LABEL_GAP_TOP_DP     = 2f;
    static final int   REEL_CAPTION_COLOR        = 0xEEFFFFFF;
    static final int   REEL_LABEL_COLOR          = 0xCCFFFFFF;
    static final String REEL_LABEL_TEXT          = "\u2B21  Reels";
    static final String REEL_PLAY_GLYPH          = "\u25B6";
    static final String REEL_DEFAULT_USERNAME    = "@callx_reel";
    static final int   REEL_SHADOW_COLOR         = 0xAA000000;
    static final int   REEL_CARD_BG_COLOR        = 0xFF1A1A1A; // matches thumbnail's #1A1A1A placeholder bg
    static final int   REEL_AVATAR_PLACEHOLDER_COLOR = 0xFF3A3A3A;

    // ── Contact-share card — mirrors item_msg_contact.xml exactly: a
    // bubbleless 165dp-wide, wrap_content-height card (no chat-bubble
    // background, same precedent as the reel-share card) with a dark
    // (#1C1C1E) top section holding a circular avatar + name/phone, a
    // thin divider, and a "View Contact" action row below. No timestamp/
    // tick footer at all — item_msg_contact.xml has none, unlike the reel
    // card's always-shown pill. ──
    static final float CONTACT_CARD_WIDTH_DP    = 165f;
    static final float CONTACT_TOP_HEIGHT_DP    = 88f;
    static final float CONTACT_DIVIDER_HEIGHT_DP = 0.5f;
    static final float CONTACT_BUTTON_HEIGHT_DP = 32f;
    static final float CONTACT_CORNER_RADIUS_DP = 12f;
    static final float CONTACT_AVATAR_SIZE_DP   = 38f;
    static final float CONTACT_PAD_H_DP         = 10f;
    static final float CONTACT_TEXT_GAP_DP      = 8f;
    static final float CONTACT_NAME_TEXT_SP     = 12f;
    static final float CONTACT_PHONE_TEXT_SP    = 10f;
    static final float CONTACT_PHONE_GAP_DP     = 2f;
    static final float CONTACT_BUTTON_TEXT_SP   = 11f;
    static final int   CONTACT_BG_COLOR         = 0xFF1C1C1E;
    static final int   CONTACT_DIVIDER_COLOR    = 0x33FFFFFF;
    static final int   CONTACT_NAME_COLOR       = 0xFFFFFFFF;
    static final int   CONTACT_PHONE_COLOR      = 0xCCFFFFFF;
    static final int   CONTACT_BUTTON_TEXT_COLOR = 0xFF4FC3F7;
    static final int   CONTACT_AVATAR_PLACEHOLDER_COLOR = 0xFF3A3A3A;
    static final int   CONTACT_TEXT_SHADOW_COLOR = 0xAA000000; // matches shadowColor on tvContactName/tvContactPhone
    static final String CONTACT_DEFAULT_NAME     = "Contact";
    static final String CONTACT_BUTTON_TEXT      = "View Contact";

    // ── Location-share card — mirrors item_msg_location.xml exactly: same
    // bubbleless 165dp-wide card shape family as the contact card, but with
    // a purple map-thumbnail header (Google Static Maps bitmap if supplied,
    // else a placeholder pin drawn straight on the Canvas), a translucent
    // purple address strip (up to 2 lines), and an "Open in Maps" action
    // row. No timestamp/tick footer, same precedent as the contact card. ──
    static final float LOCATION_CARD_WIDTH_DP        = 165f;
    static final float LOCATION_MAP_HEIGHT_DP        = 110f;
    static final float LOCATION_DIVIDER_HEIGHT_DP    = 0.5f;
    static final float LOCATION_BUTTON_HEIGHT_DP     = 32f;
    static final float LOCATION_CORNER_RADIUS_DP     = 12f;
    static final float LOCATION_PIN_SIZE_DP          = 28f;
    static final float LOCATION_ADDRESS_PAD_H_DP     = 10f;
    static final float LOCATION_ADDRESS_PAD_TOP_DP   = 6f;
    static final float LOCATION_ADDRESS_PAD_BOTTOM_DP = 4f;
    static final float LOCATION_ADDRESS_TEXT_SP      = 10f;
    static final float LOCATION_BUTTON_TEXT_SP       = 11f;
    static final int   LOCATION_MAP_BG_COLOR         = 0xFF4A148C;
    static final int   LOCATION_PIN_COLOR            = 0xFFEF9A9A;
    static final int   LOCATION_DIVIDER_COLOR        = 0xFF7B1FA2;
    static final int   LOCATION_ADDRESS_BG_COLOR     = 0xFF6A1B9A;
    static final int   LOCATION_ADDRESS_TEXT_COLOR   = 0xFFF3E5F5;
    static final int   LOCATION_BUTTON_TEXT_COLOR    = 0xFFCE93D8;
    static final String LOCATION_DEFAULT_ADDRESS     = "Location";
    static final String LOCATION_BUTTON_TEXT         = "Open in Maps";

    // ── View-once bubbles — mirrors item_view_once_bubble.xml /
    // item_view_once_sent_waiting.xml / item_view_once_expired.xml: a
    // normal chat-bubble-shaped solid-color card (reuses the standard
    // bubbleDrawable auto-draw path, just with a per-state colour instead
    // of the sent/received theme colour), icon glyph + label/sublabel
    // stacked start-aligned, and a small timestamp bottom-end. Three
    // variants share this one mode via viewOnceVariant. ──
    public static final int VIEW_ONCE_RECEIVED = 0; // receiver, not yet opened — "🔒 View Once / Tap to open"
    public static final int VIEW_ONCE_WAITING  = 1; // sender, receiver hasn't opened yet — "🔒 Waiting to be opened"
    public static final int VIEW_ONCE_EXPIRED  = 2; // opened/expired/removed — "👁 Opened|Expired|Removed"
    static final float VO_CORNER_RADIUS_DP     = 16f;
    static final float VO_WIDTH_RECEIVED_DP    = 200f;
    static final float VO_WIDTH_WAITING_DP     = 200f;
    static final float VO_WIDTH_EXPIRED_DP     = 220f;
    static final float VO_PAD_RECEIVED_DP      = 12f;
    static final float VO_PAD_WAITING_DP       = 10f;
    static final float VO_PAD_EXPIRED_DP       = 10f;
    static final float VO_ICON_TEXT_SP         = 22f;
    static final float VO_ICON_WAITING_SP      = 18f;
    static final float VO_ICON_EXPIRED_SP      = 18f;
    static final float VO_LABEL_TEXT_SP        = 15f;
    static final float VO_SUBLABEL_TEXT_SP     = 12f;
    static final float VO_WAITING_LABEL_SP     = 13f;
    static final float VO_EXPIRED_LABEL_SP     = 14f;
    static final float VO_OPENED_AT_SP         = 10f;
    static final float VO_TIME_SP              = 9f;
    static final float VO_ICON_TEXT_GAP_DP     = 10f;
    static final float VO_LABEL_SUBLABEL_GAP_DP = 2f;
    static final float VO_ROW_GAP_TOP_DP       = 8f;
    static final float VO_OPENED_AT_GAP_DP     = 2f;
    static final int   VO_COLOR_RECEIVED       = 0xFF00897B;
    static final int   VO_COLOR_WAITING        = 0xFF1A5C4A;
    static final int   VO_COLOR_EXPIRED        = 0xFF555555;
    static final int   VO_LABEL_COLOR          = 0xFFFFFFFF;
    static final int   VO_SUBLABEL_COLOR       = 0xCCFFFFFF;
    static final int   VO_WAITING_LABEL_COLOR  = 0xCCFFFFFF;
    static final int   VO_EXPIRED_LABEL_COLOR  = 0x99FFFFFF;
    static final int   VO_OPENED_AT_COLOR      = 0x77FFFFFF;
    static final int   VO_TIME_COLOR           = 0xAAFFFFFF;
    static final int   VO_TIME_WAITING_COLOR   = 0x88FFFFFF;
    static final String VO_LOCK_GLYPH          = "\uD83D\uDD12"; // 🔒
    static final String VO_EYE_GLYPH           = "\uD83D\uDC41"; // 👁
    static final String VO_LABEL_TEXT          = "View Once";
    static final String VO_WAITING_LABEL_TEXT  = "Waiting to be opened";

    // ── "Watched your reel" / "Seen your status" system bubbles — mirrors
    // item_reel_seen_bubble.xml / item_status_seen_bubble.xml: unlike every
    // other mode this view supports, these have a circular avatar sitting
    // OUTSIDE/left of the bubble itself (not a card-internal avatar like
    // reelAvatarBitmap/contactAvatarBitmap), always left-aligned (these
    // system rows only ever render on the "received" side — the reel/
    // status owner's side). The bubble that follows is a normal rounded
    // card (own solid colour, not the sent/received theme) containing an
    // optional thumbnail + play/eye overlay, an icon+label row, an
    // optional sender name (groups), and a time line. ──
    static final float SEEN_AVATAR_SIZE_DP       = 36f;
    static final float SEEN_AVATAR_GAP_DP        = 8f;
    static final float SEEN_CARD_CORNER_DP       = 14f;
    static final float SEEN_CARD_PAD_H_DP        = 12f;
    static final float SEEN_CARD_PAD_END_DP      = 14f;
    static final float SEEN_CARD_PAD_TOP_DP      = 8f;
    static final float SEEN_CARD_PAD_BOTTOM_DP   = 6f;
    static final float SEEN_CARD_MIN_WIDTH_DP    = 160f;
    static final float SEEN_THUMB_W_DP           = 120f;
    static final float SEEN_THUMB_H_DP           = 80f;
    static final float SEEN_THUMB_MARGIN_BOTTOM_DP = 6f;
    static final float SEEN_OVERLAY_ICON_SP      = 22f;
    static final float SEEN_ICON_TEXT_SP         = 14f;
    static final float SEEN_ICON_LABEL_GAP_DP    = 5f;
    static final float SEEN_LABEL_TEXT_SP        = 14f;
    static final float SEEN_NAME_TEXT_SP         = 11f;
    static final float SEEN_NAME_GAP_TOP_DP      = 1f;
    static final float SEEN_TIME_TEXT_SP         = 10f;
    static final float SEEN_TIME_GAP_TOP_DP      = 3f;
    static final int   SEEN_REEL_BG_COLOR        = 0xFF7F1D1D;
    static final int   SEEN_STATUS_BG_COLOR      = 0xFF4C1D95;

    // ── Call-entry pill — mirrors item_call_entry_bubble.xml: a small
    // rounded pill (same solid purple as the seen-bubble card, 14dp
    // corner) holding an icon glyph + italic-free label + " • " dot +
    // timestamp all on one row, no reply/reactions/pinned/footer-tick —
    // it's a plain system row, aligned to the caller's side via the
    // normal bubbleLeft/sent convention (sent = "I placed this call"). ──
    static final float CALL_ENTRY_PAD_H_DP          = 16f;
    static final float CALL_ENTRY_PAD_V_DP           = 7f;
    static final float CALL_ENTRY_MIN_WIDTH_DP       = 180f;
    static final float CALL_ENTRY_ICON_LABEL_GAP_DP  = 6f;
    static final float CALL_ENTRY_ICON_SP            = 15f;
    static final float CALL_ENTRY_LABEL_SP           = 13f;
    static final float CALL_ENTRY_DOT_SP             = 10f;
    static final float CALL_ENTRY_TIME_SP            = 10f;
    static final String CALL_ENTRY_DOT_TEXT          = "  \u2022  ";
    static final int   CALL_ENTRY_TIME_COLOR         = 0xFF94A3B8; // text_muted
    static final int   SEEN_THUMB_BG_COLOR       = 0xFF2A2A2A;
    static final int   SEEN_LABEL_COLOR          = 0xFFFFFFFF;
    static final int   SEEN_NAME_COLOR           = 0xFF4FC3F7; // brand_primary-ish accent
    static final int   SEEN_TIME_COLOR           = 0xB3CCCCCC;
    static final int   SEEN_AVATAR_PLACEHOLDER_COLOR = 0xFF3A3A3A;
    static final int   SEEN_OVERLAY_ICON_COLOR   = 0xFFFFFFFF;
    static final String SEEN_REEL_ICON_GLYPH     = "\uD83C\uDFAC"; // 🎬
    static final String SEEN_STATUS_ICON_GLYPH   = "\uD83D\uDC41"; // 👁
    static final String SEEN_REEL_LABEL_TEXT     = "Watched your reel";
    static final String SEEN_STATUS_LABEL_TEXT   = "Seen your status";
    static final String SEEN_REEL_PLAY_GLYPH     = "\u25B6";
    static final String SEEN_STATUS_EYE_GLYPH    = "\uD83D\uDC41"; // 👁

    // ── Poll-card — mirrors layout_msg_poll.xml exactly: a dark card
    // rendered inside the normal bubble background. Header row (poll icon
    // + "POLL" label + open/closed chip), question StaticLayout, subtitle,
    // N option rows (fill bar + radio icon + text + pct), total-votes footer. ──
    static final float POLL_CARD_WIDTH_DP         = 220f;
    static final float POLL_PADDING_H_DP          = 12f;
    static final float POLL_PADDING_TOP_DP        = 10f;
    static final float POLL_PADDING_BOTTOM_DP     = 10f;
    static final float POLL_HEADER_ICON_SIZE_DP   = 15f;
    static final float POLL_HEADER_ICON_MARGIN_DP = 6f;
    static final float POLL_HEADER_TEXT_SP        = 10f;
    static final float POLL_CHIP_PAD_H_DP         = 6f;
    static final float POLL_CHIP_PAD_V_DP         = 2f;
    static final float POLL_CHIP_CORNER_DP        = 9f;
    static final float POLL_CHIP_TEXT_SP          = 9f;
    static final float POLL_QUESTION_TEXT_SP      = 13.5f;
    static final float POLL_QUESTION_GAP_DP       = 6f;
    static final float POLL_SUBTITLE_TEXT_SP      = 10f;
    static final float POLL_SUBTITLE_GAP_DP       = 2f;
    static final float POLL_OPTION_GAP_DP         = 5f;
    static final float POLL_OPTION_PAD_H_DP       = 9f;
    static final float POLL_OPTION_PAD_V_DP       = 8f;
    static final float POLL_OPTION_CORNER_DP      = 10f;
    static final float POLL_OPTION_ICON_SIZE_DP   = 14f;
    static final float POLL_OPTION_ICON_MARGIN_DP = 7f;
    static final float POLL_OPTION_TEXT_SP        = 12.5f;
    static final float POLL_OPTION_PCT_SP         = 11f;
    static final float POLL_OPTIONS_GAP_DP        = 8f;
    static final float POLL_FOOTER_GAP_DP         = 8f;
    static final float POLL_FOOTER_TEXT_SP        = 10f;
    static final int   POLL_HEADER_ICON_COLOR     = 0xB3FFFFFF;
    static final int   POLL_CHIP_NEUTRAL_BG       = 0x26FFFFFF;
    static final int   POLL_CHIP_CLOSED_BG        = 0x40EF4444;
    static final int   POLL_CHIP_TEXT_COLOR       = 0xFFFFFFFF;
    static final int   POLL_QUESTION_COLOR        = 0xFFFFFFFF;
    static final int   POLL_SUBTITLE_COLOR        = 0x99FFFFFF;
    static final int   POLL_OPTION_BG             = 0x1AFFFFFF;
    static final int   POLL_OPTION_VOTED_BG       = 0x1F5B5BF6;
    static final int   POLL_OPTION_FILL_COLOR     = 0x3D5B5BF6;
    static final int   POLL_OPTION_FILL_LEADER    = 0x5C5B5BF6;
    static final int   POLL_OPTION_FILL_NEUTRAL   = 0x26FFFFFF;
    static final int   POLL_OPTION_TEXT_COLOR     = 0xFFFFFFFF;
    static final int   POLL_OPTION_PCT_COLOR      = 0xCCFFFFFF;
    static final int   POLL_FOOTER_TEXT_COLOR     = 0x99FFFFFF;
    static final int   POLL_STROKE_COLOR          = 0x1FFFFFFF;
    static final int   POLL_VOTED_STROKE_COLOR    = 0xFF4CAF50;
    static final float POLL_STROKE_WIDTH_DP        = 1.0f;


    // ── Link-preview card — mirrors layout_msg_link_preview.xml
    // (stub_link_preview): optional OG-image thumbnail (match-width ×
    // 120dp, centerCrop) on top, then a padded text column with the
    // domain (10sp, caps, gold) and a bold 2-line title below it. Only
    // meaningful alongside the plain-text bind() mode — stacks directly
    // below the message text, above the footer row (see onMeasure's
    // plain-text branch and drawLinkPreview). Unlike the legacy
    // ViewStub's fixed 280dp (@dimen/msg_bubble_max_width) width, the
    // card here is sized to this view's own maxTextWidth column so it
    // never forces the bubble wider than the screen actually allows. ──
    static final float LINK_PREVIEW_GAP_TOP_DP      = 6f; // gap between message text and the card
    static final float LINK_PREVIEW_CORNER_DP       = 6f; // matches bg_reply_preview_sent/received's 6dp radius
    static final float LINK_PREVIEW_THUMB_HEIGHT_DP = 120f;
    static final float LINK_PREVIEW_PAD_H_DP        = 10f;
    static final float LINK_PREVIEW_PAD_TOP_DP      = 8f;
    static final float LINK_PREVIEW_PAD_BOTTOM_DP   = 8f;
    static final float LINK_PREVIEW_DOMAIN_SP       = 10f;
    static final float LINK_PREVIEW_TITLE_SP        = 13f;
    static final float LINK_PREVIEW_TITLE_GAP_DP    = 2f; // gap between domain and title lines
    static final int   LINK_PREVIEW_DOMAIN_COLOR    = 0xFFFFD54F;
    static final int   LINK_PREVIEW_TITLE_COLOR     = 0xFFFFFFFF;
    static final int   LINK_PREVIEW_THUMB_PLACEHOLDER_COLOR = 0xFF2A2A2A;

    // OnBubbleClickListener and GridItem now live in their own files
    // (OnBubbleClickListener.java / GridItem.java, same package) — feature-
    // based file split, no behavior change.

    final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint footerPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF bubbleRect = new RectF();

    // ── In-chat search highlight (ChatSearchController) ─────────────────
    // Drawn straight on this Canvas behind the message glyphs — no child
    // TextView, no Spannable, no extra measure/layout pass. Set via
    // setSearchHighlight() from MessagePagingAdapter's PAYLOAD_SEARCH fast
    // path, which only touches currently-bound (visible) holders and just
    // calls invalidate() here, never notifyDataSetChanged()/requestLayout()
    // — so typing in the search box costs a few repaints of on-screen
    // bubbles, not a rebind of the whole chat or a scroll-position jump.
    private String searchHighlightQuery = null;
    private final Paint searchHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path searchHighlightPathScratch = new android.graphics.Path();
    {
        searchHighlightPaint.setColor(0xFFFFEB3B); // same yellow as the old TextView BackgroundColorSpan
        searchHighlightPaint.setStyle(Paint.Style.FILL);
    }

    /**
     * Sets (or clears, with null/empty) the search query whose occurrences
     * in this bubble's plain text should be painted with a yellow highlight
     * behind the glyphs. No-ops (skips the invalidate()) when the query
     * hasn't actually changed, so re-binding an already-highlighted row
     * during Prev/Next navigation doesn't repaint it for nothing.
     */
    public void setSearchHighlight(@Nullable String query) {
        String norm = (query != null && !query.isEmpty()) ? query : null;
        if (java.util.Objects.equals(norm, searchHighlightQuery)) return;
        searchHighlightQuery = norm;
        invalidate();
    }

    /**
     * Paints one yellow rectangle (per line, for wrapped matches) behind
     * every occurrence of the active search query in {@code messageText}.
     * Uses {@link StaticLayout#getSelectionPath} against the already-built
     * {@link #textLayout} — the same primitive TextView itself uses to draw
     * text selection — so a match that wraps across two lines gets two
     * correctly-split rectangles for free, no manual line math needed.
     * MUST be called from inside the same canvas.translate() the text
     * itself draws in, and BEFORE textLayout.draw(canvas), so the glyphs
     * paint on top of the highlight instead of being covered by it.
     */
    private void drawSearchHighlight(Canvas canvas) {
        if (searchHighlightQuery == null || textLayout == null || messageText.isEmpty()) return;
        String lq = searchHighlightQuery.toLowerCase(java.util.Locale.getDefault());
        String lt = messageText.toLowerCase(java.util.Locale.getDefault());
        int idx = 0;
        while ((idx = lt.indexOf(lq, idx)) != -1) {
            int end = idx + lq.length();
            searchHighlightPathScratch.reset();
            textLayout.getSelectionPath(idx, end, searchHighlightPathScratch);
            canvas.drawPath(searchHighlightPathScratch, searchHighlightPaint);
            idx = end;
        }
    }

    // ── PERF: background-precomputed plain-text StaticLayout cache ─────
    //
    // ChatActivity#entityToModel() runs on ioExecutor (see
    // PagingDataTransforms.map(...) in attachPagerWithKey()) — i.e. off
    // the UI thread, before a message ever reaches the adapter. That's a
    // free window to build a plain-text message's StaticLayout ahead of
    // time so onMeasure() below can skip StaticLayout.Builder...build()
    // (the actual text-shaping/line-breaking cost) during a fast fling
    // and just reuse the cached result — a plain synchronized HashMap
    // lookup instead.
    //
    // SAFETY — this deliberately avoids the exact bug class described in
    // MessagePagingAdapter's `asyncTextEnabled` javadoc (a swapped-in
    // layout disagreeing with what was already on screen): the cache is
    // only ever populated BEFORE a message reaches the adapter, never
    // after a view has been bound/shown, and onMeasure() makes exactly
    // one synchronous choice per bind — cache hit or a fresh build — with
    // no second pass, ever. The key includes the exact target width, so
    // a stale/mismatched entry (e.g. the screen rotated between
    // precompute and bind) is simply a cache miss, which falls back to
    // the identical StaticLayout.Builder call this code has always used.
    // Worst case is "no speedup," never wrong content.
    //
    // The cache doesn't key on message ID at all — two different messages
    // with identical text and identical width legitimately produce the
    // identical StaticLayout, so keying purely on (text, width) is both
    // simpler and correct, and sidesteps ever having to thread a message
    // ID through bind()'s signature.
    //
    // Each entry owns its own dedicated TextPaint (built fresh, never the
    // view's shared per-instance `textPaint` field, which elsewhere in
    // this class doubles as scratch space for file-card name/meta
    // measurement and the deleted-message italic override) so a cached
    // layout's appearance can never be affected by an unrelated Paint
    // mutation happening on some other view or some other code path.
    // Only plain, non-deleted text bubbles use this cache.
    private static final int TEXT_LAYOUT_CACHE_CAPACITY = 150;
    private static final Object sTextLayoutCacheLock = new Object();
    private static final java.util.LinkedHashMap<String, CachedTextLayout> sTextLayoutCache =
            new java.util.LinkedHashMap<String, CachedTextLayout>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, CachedTextLayout> eldest) {
                    return size() > TEXT_LAYOUT_CACHE_CAPACITY;
                }
            };

    private static final class CachedTextLayout {
        final StaticLayout layout;
        CachedTextLayout(StaticLayout layout) { this.layout = layout; }
    }

    // Self-calibrating: updated from the real onMeasure() below every time
    // any canvas bubble is actually measured, so background precompute
    // always targets the width real bubbles are really being built at —
    // no guessing at padding/margin math from raw display metrics. Stays
    // -1 (precompute disabled) until at least one real bubble has been
    // measured this process.
    private static volatile int sLastKnownMaxTextWidth = -1;

    /**
     * Call off the UI thread — see the cache javadoc above. Safe to call
     * for every message unconditionally; it no-ops (and never throws
     * outward) for anything not eligible.
     */
    public static void precomputeTextLayoutIfPossible(String text, boolean deleted) {
        if (deleted || text == null || text.isEmpty()) return;
        int width = sLastKnownMaxTextWidth;
        if (width <= 0) return; // no real bubble measured yet this session
        String key = text.length() + "_" + text.hashCode() + "_" + width;
        synchronized (sTextLayoutCacheLock) {
            if (sTextLayoutCache.containsKey(key)) return;
        }
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(sp2pxStatic(TEXT_SIZE_SP));
        StaticLayout layout;
        try {
            layout = StaticLayout.Builder
                    .obtain(text, 0, text.length(), paint, width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1f)
                    .setIncludePad(false)
                    .build();
        } catch (Exception ex) {
            // Never let a background precompute failure affect anything —
            // onMeasure() below simply won't find a cache entry and will
            // build synchronously exactly as before.
            return;
        }
        synchronized (sTextLayoutCacheLock) {
            sTextLayoutCache.put(key, new CachedTextLayout(layout));
        }
    }

    private static float sp2pxStatic(float sp) {
        return sp * android.content.res.Resources.getSystem().getDisplayMetrics().scaledDensity;
    }

    // ── PERF: same idea as the plain-text cache above, for poll option
    // labels. Even lower-risk than the text-bubble case: pollOptionTextPaint
    // uses fixed, message-direction-independent colors (POLL_OPTION_TEXT_COLOR),
    // so a cached layout's appearance never needs any per-bind color fixup —
    // it's correct as built. The vote percentage/count next to each option
    // is drawn separately from this cached label layout, so caching the
    // label text can never show a stale vote count. ──
    private static final int POLL_OPTION_LAYOUT_CACHE_CAPACITY = 200;
    private static final Object sPollOptionLayoutCacheLock = new Object();
    private static final java.util.LinkedHashMap<String, StaticLayout> sPollOptionLayoutCache =
            new java.util.LinkedHashMap<String, StaticLayout>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, StaticLayout> eldest) {
                    return size() > POLL_OPTION_LAYOUT_CACHE_CAPACITY;
                }
            };
    private static volatile int sLastKnownPollOptionWidth = -1;

    /** Call off the UI thread, same contract as precomputeTextLayoutIfPossible(). */
    public static void precomputePollOptionLayoutIfPossible(String optionText) {
        if (optionText == null || optionText.isEmpty()) return;
        int width = sLastKnownPollOptionWidth;
        if (width <= 0) return;
        String key = optionText.length() + "_" + optionText.hashCode() + "_" + width;
        synchronized (sPollOptionLayoutCacheLock) {
            if (sPollOptionLayoutCache.containsKey(key)) return;
        }
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(POLL_OPTION_TEXT_COLOR);
        paint.setTextSize(sp2pxStatic(POLL_OPTION_TEXT_SP));
        StaticLayout layout;
        try {
            layout = StaticLayout.Builder
                    .obtain(optionText, 0, optionText.length(), paint, width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setIncludePad(false)
                    .build();
        } catch (Exception ex) {
            return;
        }
        synchronized (sPollOptionLayoutCacheLock) {
            sPollOptionLayoutCache.put(key, layout);
        }
    }

    String messageText = "";
    String footerTimeText = "";
    boolean sent = false;
    boolean read = false;
    boolean delivered = false;
    // ── "✏️ edited" tag hit-testing ──────────────────────────────────
    // footerTimeText already has the "  ✏️ edited" suffix baked in by the
    // caller when applicable; this flag just gates whether the footer's
    // bounding box (computed fresh in drawFooter() every draw) should be
    // treated as a tappable region in onTouchEvent.
    boolean isEdited = false;
    final RectF footerTextRect = new RectF();

    StaticLayout textLayout;
    GradientDrawable bubbleDrawable;
    int lastCacheKey = -1;

    // ── Perf gap #5: requestLayout() skip-if-unchanged ──────────────────
    // Every bind*/set* method used to call requestLayout() unconditionally,
    // even when the new content measures to the exact same bubble size as
    // what's already laid out (e.g. bindMediaGroup() rebinding the same
    // grid, bindPoll() only refreshing vote counts, or setExpiryText()'s
    // once-a-second countdown tick where the "mm:ss" text's pixel width
    // rarely changes). That forced a full RecyclerView measure/layout pass
    // on every single tick/rebind. lastSizeSignature caches a compact key
    // of exactly the inputs onMeasure() actually reads to size the bubble
    // (see computeSizeSignature()) so requestLayoutIfSizeChanged() can
    // skip the requestLayout() call — and the measure pass it triggers —
    // whenever that key hasn't moved, while still always invalidate()-ing
    // so the new content actually draws. null means "never measured yet",
    // which always forces the first layout pass.
    String lastSizeSignature = null;

    float density;
    int bubbleLeft, bubbleTop;
    float footerReserveWidth;

    // ── Reply-preview state ──────────────────────────────────────────
    boolean hasReply = false;
    String replySenderName = "";
    String replyText = "";
    Bitmap replyThumb;

    final Paint replyBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint replyBarPaint = new Paint();
    final TextPaint replySenderPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint replyTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final Rect replyThumbSrcRect = new Rect();
    final RectF replyThumbDstRect = new RectF();

    StaticLayout replySenderLayout;
    StaticLayout replyTextLayout;
    final RectF replyBoxRect = new RectF();
    int replyBoxHeight = 0;

    // ── Media (image) bubble state ───────────────────────────────────
    boolean isMedia = false;
    boolean mediaHasCaption = false;
    Bitmap mediaBitmap;
    // Single "video" message reuses the whole isMedia/mediaRect/mediaBitmap
    // infrastructure (same fixed 180dp square, same footer pill) — this
    // flag just adds the play-glyph + duration-badge overlay on top,
    // mirroring the legacy fl_video/iv_video_thumb treatment. Never has a
    // caption (mediaHasCaption stays false for video, same as the legacy
    // "video" case never binding a caption). Reuses groupPlayCirclePaint/
    // groupPlayTrianglePaint/groupDurationTextPaint/groupDurationBgPaint/
    // groupPlayTrianglePath from the media-GROUP state below — same visual,
    // no need to duplicate paints/constants.
    boolean isVideoMedia = false;
    String videoDuration;
    final Paint mediaBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    final Paint mediaPlaceholderPaint = new Paint();
    final Paint mediaPillBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint mediaPillTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final RectF mediaRect = new RectF();
    final RectF mediaPillRect = new RectF();
    final android.graphics.Matrix mediaShaderMatrix = new android.graphics.Matrix();
    // Real image width/height ratio (width / height) of the currently bound
    // media bitmap, used to size mediaRect like WhatsApp/Telegram instead of
    // a fixed 180dp square. 0f means "unknown yet" (bindMedia() always
    // resets this — a recycled view must never carry over the previous
    // message's ratio) — onMeasure()/layout fall back to the legacy square
    // (MEDIA_SIZE_DP) until setMediaBitmap() supplies the first decoded
    // Bitmap for this bind and computes the real ratio from it.
    float mediaAspectRatio = 0f;
    // Reused scratch output for computeMediaSize() — {width, height} in
    // px — avoids allocating a new int[2] on every onMeasure()/layout pass.
    private final int[] mediaSizeOut = new int[2];
    // Process-wide cache of already-decoded images' aspect ratios, keyed by
    // the same URL/File-path string the adapter uses to load the media.
    // Without this, every RecyclerView rebind (fast scrolling constantly
    // recycles+rebinds views) would reset mediaAspectRatio to 0f/"unknown"
    // in bindMedia(), size the bubble as the square placeholder for one
    // frame, then relayout to the real size the moment Glide's (memory-
    // cache-hit, effectively instant) callback fires — two layout passes
    // per bind, which is what caused the scroll jank/"blinking" and the
    // pop on send/receive after the aspect-ratio bubble-sizing change.
    // Caching the ratio lets bindMedia() restore it immediately for any
    // image already seen once, so the common rebind case needs zero extra
    // layout passes; only a message's very first-ever appearance still
    // pays the one-time square→real transition.
    private static final android.util.LruCache<String, Float> MEDIA_ASPECT_CACHE =
            new android.util.LruCache<>(1000);
    /**
     * Lets the adapter's Glide callback record a decoded image/video-thumb's
     * aspect ratio into MEDIA_ASPECT_CACHE even when the ViewHolder that
     * started the load has since been recycled/rebound to a different
     * message (canvasBindToken mismatch) — call this UNCONDITIONALLY from
     * onResourceReady, before the token check that guards setMediaBitmap().
     *
     * Without this, a decode that finishes after its view was recycled
     * (common during fast scrolling, since Glide's CustomTarget isn't tied
     * to a View and keeps running after rebind) was being silently
     * discarded — including the cache write — because the old code only
     * cached the ratio inside setMediaBitmap(), which itself only runs
     * when the token still matches. The next time that same image scrolled
     * back into view, its ratio was "unknown" all over again, so the
     * square placeholder flashed before correcting itself — every single
     * time, not just on first-ever view. Decoupling the cache write from
     * the token check means any successful decode — regardless of which
     * view happens to still want it — permanently records the ratio, so a
     * later bind of the same URL/key sizes correctly on its very first
     * layout pass.
     */
    public static void cacheAspectRatio(@Nullable String key, float ratio) {
        if (key != null && ratio > 0f) {
            MEDIA_ASPECT_CACHE.put(key, ratio);
        }
    }
    // Cache key for the media currently bound to this view (mediaUrl for
    // images, video-thumbnail URL for videos) — set by bindMedia()/
    // bindVideo(), read by setMediaBitmap() to know where to store the
    // ratio once decoded. Null when unknown (caller passed no key) —
    // setMediaBitmap() simply skips the cache write in that case.
    private String mediaAspectKey;
    // Reused by drawCornerExpiryPill() below — avoids a `new RectF()` on
    // every draw() for cards (contact/location) that show the floating
    // expiry badge instead of a regular footer.
    final RectF cornerExpiryPillRect = new RectF();

    // ── Audio (voice message) bubble state — entirely separate mode from
    // isMedia/isMediaGroup/isReelShare (see AUDIO_* constants doc above). ──
    boolean isAudio = false;
    float[] audioLevels = new float[0];
    float audioProgress = 0f;   // 0..1 played fraction, drives waveform fill + seek
    boolean audioPlaying = false;
    String audioElapsedText = ""; // "m:ss" while playing; empty when idle (mirrors legacy tv_audio_dur, which never shows a total duration upfront)
    final Paint audioBtnBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint audioBtnIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint audioWaveformIdlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint audioWaveformPlayedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint audioDurPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final RectF audioBtnRect = new RectF();
    final RectF audioWaveformRect = new RectF();
    final android.graphics.Path audioPlayTrianglePath = new android.graphics.Path();
    // Reused int Rect for invalidateAudioRow()/invalidateExpiryRegion() below
    // — dirty-region invalidate() needs an android.graphics.Rect (int), not
    // the RectF (float) types the rest of this view uses, and a fresh one
    // per tick would be its own per-frame allocation.
    private final Rect audioDirtyRect = new Rect();
    private final Rect expiryDirtyRect = new Rect();

    // ── Single-media manual download gate state — see setMediaDownloadGate(). ──
    boolean mediaGated = false;
    boolean mediaDownloading = false;
    int mediaDownloadProgress = -1; // -1 = indeterminate, 0-100 = live percent
    String mediaDownloadLabel = "";
    final Paint mediaGateScrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint mediaGatePillBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint mediaGatePillTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final Paint mediaGatePillIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF mediaGatePillRect = new RectF();

    // ── Reaction badge state — independent of text/media/group mode; any
    // of the three can have reactions overlaid. ──
    boolean hasReactions = false;
    String reactionsText = "";
    final TextPaint reactionsTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final RectF reactionsRect = new RectF();
    // PERF ADV: cached FontMetrics — getFontMetrics() allocates a new object
    // every call.  Since reactionsTextPaint's size/style never changes after
    // init, we compute once and reuse.  Nulled out only on text-size change.
    private android.graphics.Paint.FontMetrics reactionsTextFM = null;

    // ── Pinned label state ────────────────────────────────────────────
    boolean isPinned = false;
    final TextPaint pinnedLabelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    int pinnedLabelHeight = 0;
    float pinnedLabelWidth = 0;

    // ── Group-chat sender-name state — mirrors tv_sender_name in
    // item_message_received.xml. Only meaningful for received messages;
    // the sender AVATAR (iv_sender_avatar) is intentionally not modeled —
    // it's dead markup in the legacy layout too (never bound anywhere in
    // MessagePagingAdapter), so there's nothing functional to mirror. ──
    boolean hasGroupSender = false;
    String groupSenderName = "";
    final TextPaint groupSenderPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    int groupSenderTextHeight = 0;
    float groupSenderWidth = 0;

    // ── Forwarded-label state — stacks below the pinned-label/group-sender
    // row above the bubble (own row, own baseline; see onMeasure). ──
    boolean hasForwarded = false;
    String forwardedText = "";
    final TextPaint forwardedPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    int forwardedTextHeight = 0;
    float forwardedTextWidth = 0;
    // Baselines for all three "above the bubble" rows — computed once per
    // onMeasure() pass, consumed by the matching drawXxx() in onDraw() so
    // the draw path never has to re-derive vertical position from scratch.
    float pinnedBaselineY = 0;
    float groupSenderBaselineY = 0;
    float forwardedBaselineY = 0;

    // ── Quick-forward icon button — mirrors legacy btn_quick_forward
    // (item_message_sent/received.xml): a small circular tap target that
    // sits just OUTSIDE the bubble — to the LEFT of a sent bubble, to the
    // RIGHT of a received one — vertically centered against it. Adapter
    // decides visibility (media/link messages only, same rule as the
    // legacy btnQuickForward.setVisibility() check) via
    // setQuickForwardVisible(); position is recomputed every onMeasure()
    // pass from bubbleRect/bubbleLeft, same as every other overlay here. ──
    boolean showForwardBtn = false;
    final RectF forwardBtnRect = new RectF();
    static final float FORWARD_BTN_SIZE_DP   = 30f;
    static final float FORWARD_BTN_MARGIN_DP = 2f;
    final Paint forwardBtnBgPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint forwardBtnIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final android.graphics.Path forwardIconPath = new android.graphics.Path();


    // ── Deleted-message placeholder style — only affects the plain-text
    // path (bind()); caller substitutes the placeholder string itself. ──
    boolean isDeletedStyle = false;

    // ── Disappearing-message countdown state — an extra segment in the
    // footer row, drawn just before the timestamp/tick. ──
    boolean hasExpiry = false;
    String expiryText = "";
    final TextPaint expiryPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    // ── Media GROUP (multi-image/video grid) state — entirely separate
    // from the single-image `isMedia` state above. ──
    boolean isMediaGroup = false;
    java.util.List<GridItem> groupItems = java.util.Collections.emptyList();
    Bitmap[] groupBitmaps = new Bitmap[0];
    final RectF[] groupRects = new RectF[GROUP_MAX_VISIBLE];
    int groupVisibleCount = 0;
    int groupRemaining = 0; // >0 only on the last visible cell when total > GROUP_MAX_VISIBLE
    boolean groupHasCaption = false;
    StaticLayout groupCaptionLayout;
    final RectF groupContentRect = new RectF(); // whole grid area (no reply/caption)
    final Paint groupCellBgPaint = new Paint();
    final Paint groupBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    final Paint groupScrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint groupMoreOverlayPaint = new Paint();
    final TextPaint groupMoreTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final Paint groupPlayCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint groupPlayTrianglePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint groupDurationTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final Paint groupDurationBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final android.graphics.Matrix groupShaderMatrix = new android.graphics.Matrix();
    final android.graphics.Path groupPlayTrianglePath = new android.graphics.Path();
    final TextPaint groupCaptionTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint groupItemCaptionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final Paint groupItemCaptionScrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ── Mixed-group audio/file cell paints — no thumbnail for these cells,
    // just a dark placeholder + glyph + filename/duration label, mirroring
    // MediaGroupLayoutHelper.buildCell()'s isAudio||isFile branch. ──
    final Paint groupFileCellBgPaint = new Paint();
    final Paint groupFileGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint groupFileLabelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    // ── Received-group manual download-gate state — only ever set for
    // RECEIVED groups (see setGroupDownloadGate()); stays all-false/inert
    // for SENT groups and for received groups with nothing left to
    // download. Recomputed fresh on every bindMediaGroup() so a recycled
    // view never carries a stale gate from the previous message. ──
    boolean groupGateActive = false;
    int groupGatePendingCount = 0;
    boolean[] groupCellPending = new boolean[0];
    boolean[] groupCellDownloading = new boolean[0];
    int[] groupCellProgress = new int[0]; // parallel to groupCellDownloading; -1 = indeterminate
    final Paint groupGateScrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint groupGatePillBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint groupGatePillTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final Paint groupGatePillIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint groupCellGateDimPaint = new Paint();
    final Paint groupCellGateBadgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint groupCellGateIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF groupGatePillRect = new RectF();
    final android.graphics.Path groupGateIconPath = new android.graphics.Path();

    // ── Reel-share card state — entirely separate mode from isMedia/
    // isMediaGroup (see REEL_* constants doc above). ──
    boolean isReelShare = false;
    String reelUsername = REEL_DEFAULT_USERNAME;
    Bitmap reelThumbBitmap;
    Bitmap reelAvatarBitmap;
    boolean reelHasCaption = false;
    String reelCaptionText = "";
    StaticLayout reelCaptionLayout;
    final RectF reelCardRect = new RectF();
    final RectF reelAvatarRect = new RectF();
    final Paint reelCardBgPaint = new Paint();
    final Paint reelThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    final Paint reelAvatarPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    final Paint reelAvatarPlaceholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint reelUsernamePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint reelPlayIconPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint reelCaptionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint reelLabelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    GradientDrawable reelTopGradient;
    GradientDrawable reelBottomGradient;
    final android.graphics.Matrix reelShaderMatrix = new android.graphics.Matrix();
    final android.graphics.Matrix reelAvatarShaderMatrix = new android.graphics.Matrix();

    // ── Contact-share card state — entirely separate mode from isMedia/
    // isMediaGroup/isReelShare/isAudio (see CONTACT_* constants doc above). ──
    boolean isContact = false;
    String contactName = "";
    String contactPhone = "";
    Bitmap contactAvatarBitmap;
    final RectF contactCardRect = new RectF();
    final RectF contactAvatarRect = new RectF();
    final RectF contactButtonRect = new RectF(); // "View Contact" row — the only tappable sub-region
    final Paint contactCardBgPaint = new Paint();
    final Paint contactDividerPaint = new Paint();
    final Paint contactAvatarPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    final Paint contactAvatarPlaceholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint contactNamePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint contactPhonePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint contactButtonTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final android.graphics.Matrix contactAvatarShaderMatrix = new android.graphics.Matrix();

    boolean isLocation = false;
    String locationAddress = "";
    Bitmap locationMapBitmap;
    StaticLayout locationAddressLayout;
    final RectF locationCardRect = new RectF();
    final RectF locationMapRect = new RectF();
    final RectF locationButtonRect = new RectF(); // "Open in Maps" row — the only tappable sub-region
    final Paint locationMapBgPaint = new Paint();
    final Paint locationMapBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    final Paint locationPinPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint locationDividerPaint = new Paint();
    final Paint locationAddressBgPaint = new Paint();
    final Paint locationButtonBgPaint = new Paint();
    final TextPaint locationAddressTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint locationButtonTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final android.graphics.Path locationPinPath = new android.graphics.Path();
    final android.graphics.Matrix locationMapShaderMatrix = new android.graphics.Matrix();

    // ── Poll card state ─────────────────────────────────────────────────────
    boolean isPoll        = false;
    String   pollQuestion  = "";
    boolean  pollClosed    = false;
    boolean  pollMultiChoice = false;
    String[] pollOptions   = new String[0];
    int[]    pollCounts    = new int[0];
    boolean[] pollMyVote   = new boolean[0];
    boolean[] pollIsLeader = new boolean[0];
    int      pollTotal     = 0;
    StaticLayout   pollQuestionLayout;
    StaticLayout[] pollOptionLayouts = new StaticLayout[0];
    float[]        pollFillWidths    = new float[0];
    final java.util.ArrayList<RectF> pollOptionRects = new java.util.ArrayList<>();
    float pollHeaderRowH  = 0f;
    float pollSubtitleH   = 0f;
    float pollTotalCardH  = 0f;
    // PERF: scratch objects reused every PollRenderer.draw() call instead of
    // `new RectF()`/`new Path()` per option per frame — draw() runs on every
    // onDraw(), so these used to allocate on every scroll frame a poll
    // bubble was visible for. See PollRenderer.draw()'s option-row loop.
    final RectF pollFillRectScratch = new RectF();
    final android.graphics.Path pollFillClipPath = new android.graphics.Path();
    // Paints (initialised in constructor)
    final Paint     pollOptionBgPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint     pollFillPaint        = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint     pollStrokePaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint pollHeaderLabelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint pollChipPaint        = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint pollQuestionPaint    = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint pollSubtitlePaint    = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint pollOptionTextPaint  = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint pollOptionPctPaint   = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint pollFooterPaint      = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    // ── GIF bubble state — reuses the isMedia/mediaRect/mediaBitmap
    // infrastructure entirely (same 180dp square, same download-gate overlay,
    // same timestamp pill) with just a "GIF" badge pill drawn in the top-start
    // corner of the thumbnail to signal the content is animated. ──
    boolean isGifBubble = false;
    static final String GIF_BADGE_TEXT = "GIF";

    // ── File bubble state — card-style bubble (240dp wide): left icon circle,
    // centre name+size row, right download/open action button, footer below. ──
    boolean isFileBubble        = false;
    String  fileNameText        = "";
    String  fileSizeMimeText    = "";
    int     fileIconColor       = 0xFF607D8B;
    boolean fileIsCached        = false;
    boolean fileIsDownloading   = false;
    int     fileDownloadPercent = 0;
    float   fileCardHeight      = 0f;
    final RectF fileActionRect  = new RectF(); // tap target for ⬇/⬗ button
    static final float FILE_CARD_W_DP     = 240f;
    static final float FILE_ICON_COL_DP   = 52f;
    static final float FILE_ACTION_COL_DP = 44f;
    static final float FILE_ROW_PAD_DP    = 10f;


    // ── Link-preview card state — only meaningful alongside the
    // plain-text bind() mode (see LINK_PREVIEW_* constants doc above).
    // hasThumb is set upfront in setLinkPreview() (from whether
    // LinkPreviewFetcher.Result.imageUrl was non-empty) so the card's
    // reserved height is correct even before the Bitmap itself arrives
    // via setLinkPreviewThumbBitmap(). ──
    // ── View-once bubble state (Feature 13's 3 special layouts) ──
    boolean isViewOnce = false;
    int viewOnceVariant = VIEW_ONCE_RECEIVED;
    String viewOnceSublabel = "";
    String viewOnceExpiredLabel = "Opened";
    String viewOnceOpenedAtText = "";
    boolean viewOnceShowOpenedAt = false;
    final RectF viewOnceCardRect = new RectF();
    final Paint viewOnceBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint viewOnceIconPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint viewOnceLabelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint viewOnceSublabelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint viewOnceTimePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    // ── Reel-seen / Status-seen "watched/seen" system bubbles ──
    boolean isSeenBubble = false;
    boolean isCallEntry = false;
    String callEntryIcon = "";
    String callEntryLabel = "";
    int callEntryLabelColor = 0xFFFFFFFF;
    String callEntryTime = "";
    final RectF callEntryPillRect = new RectF();
    final Paint callEntryBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint callEntryIconPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint callEntryLabelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint callEntryDotPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint callEntryTimePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    boolean seenIsReel = false; // true = reel_seen (🎬), false = status_seen (👁)
    Bitmap seenAvatarBitmap;
    Bitmap seenThumbBitmap;
    boolean seenHasThumb = false;
    String seenName = "";
    boolean seenHasName = false;
    final RectF seenAvatarRect = new RectF();
    final RectF seenCardRect = new RectF();
    final RectF seenThumbRect = new RectF();
    final Paint seenCardBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint seenAvatarPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    final Paint seenAvatarPlaceholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint seenThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    final Paint seenThumbBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint seenOverlayIconPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint seenIconPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint seenLabelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint seenNamePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint seenTimePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final android.graphics.Matrix seenAvatarShaderMatrix = new android.graphics.Matrix();
    final android.graphics.Matrix seenThumbShaderMatrix = new android.graphics.Matrix();

    boolean hasLinkPreview = false;
    String linkPreviewUrl = "";
    String linkTitle = "";
    String linkDomain = "";
    boolean linkHasThumb = false;
    Bitmap linkThumbBitmap;
    StaticLayout linkTitleLayout;
    int linkCardHeight = 0;
    final RectF linkCardRect = new RectF();
    final RectF linkThumbRect = new RectF();
    final Paint linkCardBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint linkThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    final Paint linkThumbPlaceholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint linkDomainPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint linkTitlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final android.graphics.Matrix linkThumbShaderMatrix = new android.graphics.Matrix();

    OnBubbleClickListener clickListener;
    final GestureDetector gestureDetector;

    // ── Per-feature draw() renderers (feature-based file split) — bind/
    // measure/touch logic stays on this host view; each renderer only
    // owns the drawXxx(Canvas) body that used to live here directly. ──
    private final ReelShareRenderer reelShareRenderer = new ReelShareRenderer(this);
    private final ContactRenderer contactRenderer = new ContactRenderer(this);
    private final LocationRenderer locationRenderer = new LocationRenderer(this);
    private final ViewOnceRenderer viewOnceRenderer = new ViewOnceRenderer(this);
    private final SeenBubbleRenderer seenBubbleRenderer = new SeenBubbleRenderer(this);
    private final PollRenderer pollRenderer = new PollRenderer(this);
    private final MediaGroupRenderer mediaGroupRenderer = new MediaGroupRenderer(this);
    private final MediaRenderer mediaRenderer = new MediaRenderer(this);
    private final AudioRenderer audioRenderer = new AudioRenderer(this);
    private final FileBubbleRenderer fileBubbleRenderer = new FileBubbleRenderer(this);
    private final LinkPreviewRenderer linkPreviewRenderer = new LinkPreviewRenderer(this);

    // ── PERF: Picture-based static-content cache — scoped narrowly to the
    // one confirmed repeated-full-redraw case: a single-image/video
    // bubble's indeterminate download/upload spinner (mediaGated &&
    // mediaDownloading && mediaDownloadProgress < 0), which calls
    // postInvalidateOnAnimation() every frame via drawProgressRing(). See
    // v108/v109's upgrade notes for the investigation that led here —
    // MediaGroupRenderer's per-cell shaders were already cached, and the
    // remaining full-bubble redraw during that spinner loop is the actual
    // cost left to cut.
    //
    // Deliberately NOT applied outside that state: RecyclerView doesn't
    // call onDraw() repeatedly for a bubble with no pending animation on
    // its own, so there's nothing to gain — or risk — from caching there.
    // Determinate progress (a real 0-100% value) is excluded from the
    // cache entirely and always draws directly, since it only changes on
    // genuine progress events rather than every frame.
    //
    // staticPictureDirty defaults true (correct-content-not-yet-cached) and
    // is explicitly set at every bind*()/setter call site that can change
    // what this cache would capture (bindMedia, setMediaBitmap,
    // setMediaDownloadGate, setMediaDownloadProgress,
    // clearMediaDownloadGate) — cheap enough to over-call everywhere in
    // doubt. It's also reset to true every time drawMediaWithOptionalCache()
    // runs in the *non*-cached branch, so the very next indeterminate-spinner
    // episode always starts from a fresh capture rather than a Picture left
    // over from whatever the view last showed before recycling.
    private android.graphics.Picture cachedMediaPicture;
    private boolean staticPictureDirty = true;
    private int cachedMediaPictureWidth = -1, cachedMediaPictureHeight = -1;

    // ── PERF #5: Full-bubble Picture cache ────────────────────────────────
    // Extends the narrow cachedMediaPicture above to cover the ENTIRE bubble
    // draw (background drawable, group sender, reply preview, text, media,
    // footer, reactions badge).  Saves the cost of all those draw calls on
    // every RecyclerView scroll frame.
    //
    // Design:
    //   • fullBubbleDirty starts true and is reset to true by the overridden
    //     invalidate() / postInvalidateOnAnimation() below, so any content
    //     change from ANY setter or bind*() automatically invalidates the
    //     cache without per-method dirty flags.
    //   • The cache is BYPASSED (draw directly) for two animation cases:
    //       – indeterminate download/upload spinner (already handled by the
    //         narrower cachedMediaPicture inside drawMediaWithOptionalCache)
    //       – audio playback (waveform progress animates ~60fps)
    //     For every other bubble state the cache hit path is a single
    //     canvas.drawPicture() call — essentially free.
    //   • Size-change detection: if the view is relaid out while the same
    //     message is bound, the recorded Picture is at the old size and must
    //     be discarded (same guard as cachedMediaPicture).
    private android.graphics.Picture fullBubblePicture;
    private boolean                   fullBubbleDirty    = true;
    private int                       fullBubblePictureW = -1;
    private int                       fullBubblePictureH = -1;

    /**
     * Draws a single-image/video bubble, using a cached Picture for
     * everything except the live spinner ring while (and only while) the
     * indeterminate download/upload spinner is actively animating. See the
     * field javadoc above for the full invalidation contract.
     */
    private void drawMediaWithOptionalCache(Canvas canvas, int hPad, int vPad) {
        boolean indeterminateSpinnerActive =
                mediaGated && mediaDownloading && mediaDownloadProgress < 0;
        if (!indeterminateSpinnerActive) {
            mediaRenderer.draw(canvas, hPad, vPad);
            // Next indeterminate-spinner episode (this bind or a future
            // one, same or different message after recycling) must always
            // start from a fresh capture, never reuse whatever was cached
            // before this non-cached draw happened.
            staticPictureDirty = true;
            return;
        }
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) {
            // Not laid out yet — fall back to a direct draw rather than
            // recording a zero-size Picture.
            mediaRenderer.draw(canvas, hPad, vPad, true);
            mediaRenderer.drawIndeterminateSpinnerOnly(canvas);
            return;
        }
        // Extra safety net beyond the explicit dirty-flag call sites: if
        // this view's own size changed since the Picture was recorded
        // (e.g. an unrelated relayout mid-download), that's also grounds
        // to rebuild — a Picture recorded at the old size would either
        // clip or leave a stale border at the new size.
        boolean sizeChanged = w != cachedMediaPictureWidth || h != cachedMediaPictureHeight;
        if (staticPictureDirty || cachedMediaPicture == null || sizeChanged) {
            if (cachedMediaPicture == null) cachedMediaPicture = new android.graphics.Picture();
            Canvas pictureCanvas = cachedMediaPicture.beginRecording(w, h);
            mediaRenderer.draw(pictureCanvas, hPad, vPad, true /* spinnerHandledSeparately */);
            cachedMediaPicture.endRecording();
            staticPictureDirty = false;
            cachedMediaPictureWidth = w;
            cachedMediaPictureHeight = h;
        }
        canvas.drawPicture(cachedMediaPicture);
        mediaRenderer.drawIndeterminateSpinnerOnly(canvas);
    }

    public MessageBubbleCanvasView(Context ctx) {
        this(ctx, null);
    }

    public MessageBubbleCanvasView(Context ctx, @Nullable android.util.AttributeSet attrs) {
        super(ctx, attrs);
        density = ctx.getResources().getDisplayMetrics().density;
        textPaint.setTextSize(spToPx(TEXT_SIZE_SP));
        footerPaint.setTextSize(spToPx(FOOTER_TEXT_SP));
        footerPaint.setAlpha(180);
        replySenderPaint.setTextSize(spToPx(REPLY_SENDER_SIZE_SP));
        replySenderPaint.setFakeBoldText(true);
        replyTextPaint.setTextSize(spToPx(REPLY_TEXT_SIZE_SP));
        mediaPlaceholderPaint.setColor(MEDIA_PLACEHOLDER_COLOR);
        mediaPillBgPaint.setColor(MEDIA_PILL_BG);
        mediaPillTextPaint.setColor(MEDIA_PILL_TEXT);
        mediaPillTextPaint.setTextSize(spToPx(FOOTER_TEXT_SP));

        // Quick-forward icon button — faint circular tap-target background
        // (mirrors selectableItemBackgroundBorderless's ripple footprint,
        // just statically drawn since Canvas has no ripple drawable) +
        // stroked double-chevron glyph, same visual family as the legacy
        // ic_forward_msg drawable.
        forwardBtnBgPaint.setColor(0x14000000);
        forwardBtnIconPaint.setStyle(Paint.Style.STROKE);
        forwardBtnIconPaint.setStrokeWidth(1.6f * density);
        forwardBtnIconPaint.setStrokeCap(Paint.Cap.ROUND);
        forwardBtnIconPaint.setStrokeJoin(Paint.Join.ROUND);
        forwardBtnIconPaint.setColor(0xFF757575);


        reactionsTextPaint.setTextSize(spToPx(REACTIONS_TEXT_SP));
        reactionsTextPaint.setShadowLayer(2f * density, 0f, 1f * density, REACTIONS_SHADOW_COLOR);
        reactionsTextFM = null; // PERF ADV: invalidate cached FM after size change

        pinnedLabelPaint.setTextSize(spToPx(PINNED_LABEL_TEXT_SP));
        pinnedLabelPaint.setColor(PINNED_LABEL_COLOR);

        groupSenderPaint.setTextSize(spToPx(GROUP_SENDER_TEXT_SP));
        groupSenderPaint.setFakeBoldText(true);

        forwardedPaint.setTextSize(spToPx(FORWARDED_LABEL_TEXT_SP));
        forwardedPaint.setColor(FORWARDED_LABEL_COLOR);
        forwardedPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));

        expiryPaint.setTextSize(spToPx(EXPIRY_TEXT_SP));
        expiryPaint.setColor(EXPIRY_COLOR);

        groupCellBgPaint.setColor(Color.DKGRAY);
        groupMoreOverlayPaint.setColor(0xBB000000);
        groupMoreTextPaint.setColor(Color.WHITE);
        groupMoreTextPaint.setFakeBoldText(true);
        groupMoreTextPaint.setTextSize(22f * density);
        groupMoreTextPaint.setTextAlign(Paint.Align.CENTER);
        groupPlayCirclePaint.setColor(0x99000000);
        groupPlayTrianglePaint.setColor(Color.WHITE);
        groupDurationTextPaint.setColor(Color.WHITE);
        groupDurationTextPaint.setFakeBoldText(true);
        groupDurationTextPaint.setTextSize(GROUP_DURATION_TEXT_SP * density);
        groupDurationBgPaint.setColor(0x99000000);
        groupCaptionTextPaint.setColor(Color.WHITE);
        groupCaptionTextPaint.setTextSize(GROUP_CAPTION_TEXT_SP * density);
        groupItemCaptionPaint.setColor(Color.WHITE);
        groupItemCaptionPaint.setTextSize(GROUP_ITEM_CAPTION_TEXT_SP * density);
        groupFileCellBgPaint.setColor(0xFF2C2C2C); // matches MediaGroupLayoutHelper's isAudio||isFile cell background
        groupFileGlyphPaint.setColor(Color.WHITE);
        groupFileGlyphPaint.setStyle(Paint.Style.FILL);
        groupFileLabelPaint.setColor(Color.WHITE);
        groupFileLabelPaint.setTextSize(9f * density); // sp≈dp here, matches legacy tvLabel.setTextSize(9f)
        groupFileLabelPaint.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < groupRects.length; i++) groupRects[i] = new RectF();

        groupGateScrimPaint.setColor(GROUP_GATE_SCRIM_COLOR);
        groupGatePillBgPaint.setColor(GROUP_GATE_PILL_BG);
        groupGatePillTextPaint.setColor(Color.WHITE);
        groupGatePillTextPaint.setFakeBoldText(true);
        groupGatePillTextPaint.setTextSize(GROUP_GATE_PILL_TEXT_SP * density);
        groupGatePillIconPaint.setColor(Color.WHITE);
        groupGatePillIconPaint.setStyle(Paint.Style.STROKE);
        groupGatePillIconPaint.setStrokeWidth(1.6f * density);
        groupGatePillIconPaint.setStrokeCap(Paint.Cap.ROUND);
        groupGatePillIconPaint.setStrokeJoin(Paint.Join.ROUND);
        groupCellGateDimPaint.setColor(GROUP_CELL_GATE_DIM_COLOR);
        groupCellGateBadgeBgPaint.setColor(GROUP_CELL_GATE_BADGE_BG);
        groupCellGateIconPaint.setColor(Color.WHITE);
        groupCellGateIconPaint.setStyle(Paint.Style.STROKE);
        groupCellGateIconPaint.setStrokeWidth(1.4f * density);
        groupCellGateIconPaint.setStrokeCap(Paint.Cap.ROUND);
        groupCellGateIconPaint.setStrokeJoin(Paint.Join.ROUND);

        mediaGateScrimPaint.setColor(MEDIA_GATE_SCRIM_COLOR);
        mediaGatePillBgPaint.setColor(GROUP_GATE_PILL_BG);
        mediaGatePillTextPaint.setColor(Color.WHITE);
        mediaGatePillTextPaint.setFakeBoldText(true);
        mediaGatePillTextPaint.setTextSize(GROUP_GATE_PILL_TEXT_SP * density);
        mediaGatePillIconPaint.setColor(Color.WHITE);
        mediaGatePillIconPaint.setStyle(Paint.Style.STROKE);
        mediaGatePillIconPaint.setStrokeWidth(1.8f * density);
        mediaGatePillIconPaint.setStrokeCap(Paint.Cap.ROUND);
        mediaGatePillIconPaint.setStrokeJoin(Paint.Join.ROUND);

        reelCardBgPaint.setColor(REEL_CARD_BG_COLOR);
        reelAvatarPlaceholderPaint.setColor(REEL_AVATAR_PLACEHOLDER_COLOR);
        reelUsernamePaint.setColor(Color.WHITE);
        reelUsernamePaint.setFakeBoldText(true);
        reelUsernamePaint.setTextSize(REEL_USERNAME_TEXT_SP * density);
        reelUsernamePaint.setShadowLayer(3f * density, 0f, 1f * density, REEL_SHADOW_COLOR);
        reelPlayIconPaint.setColor(REEL_PLAY_ICON_COLOR);
        reelPlayIconPaint.setTextSize(REEL_PLAY_ICON_SP * density);
        reelPlayIconPaint.setTextAlign(Paint.Align.CENTER);
        reelPlayIconPaint.setShadowLayer(6f * density, 0f, 1f * density, REEL_SHADOW_COLOR);
        reelCaptionPaint.setColor(REEL_CAPTION_COLOR);
        reelCaptionPaint.setTextSize(REEL_CAPTION_TEXT_SP * density);
        reelCaptionPaint.setShadowLayer(3f * density, 0f, 1f * density, REEL_SHADOW_COLOR);
        reelLabelPaint.setColor(REEL_LABEL_COLOR);
        reelLabelPaint.setFakeBoldText(true);
        reelLabelPaint.setTextSize(REEL_LABEL_TEXT_SP * density);
        reelLabelPaint.setShadowLayer(3f * density, 0f, 1f * density, REEL_SHADOW_COLOR);
        // Gradients mirror gradient_reel_top/gradient_reel_bottom exactly
        // (dark → transparent at top, transparent → dark at bottom) so the
        // header/caption overlays stay readable over any thumbnail.
        reelTopGradient = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x99000000, 0x00000000});
        reelBottomGradient = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x99000000});

        contactCardBgPaint.setColor(CONTACT_BG_COLOR);
        contactDividerPaint.setColor(CONTACT_DIVIDER_COLOR);
        contactAvatarPlaceholderPaint.setColor(CONTACT_AVATAR_PLACEHOLDER_COLOR);
        contactNamePaint.setColor(CONTACT_NAME_COLOR);
        contactNamePaint.setFakeBoldText(true);
        contactNamePaint.setTextSize(CONTACT_NAME_TEXT_SP * density);
        contactNamePaint.setShadowLayer(3f * density, 0f, 1f * density, CONTACT_TEXT_SHADOW_COLOR);
        contactPhonePaint.setColor(CONTACT_PHONE_COLOR);
        contactPhonePaint.setTextSize(CONTACT_PHONE_TEXT_SP * density);
        contactPhonePaint.setShadowLayer(3f * density, 0f, 1f * density, CONTACT_TEXT_SHADOW_COLOR);
        contactButtonTextPaint.setColor(CONTACT_BUTTON_TEXT_COLOR);
        contactButtonTextPaint.setFakeBoldText(true);
        contactButtonTextPaint.setTextSize(CONTACT_BUTTON_TEXT_SP * density);
        contactButtonTextPaint.setTextAlign(Paint.Align.CENTER);

        locationMapBgPaint.setColor(LOCATION_MAP_BG_COLOR);
        locationPinPaint.setColor(LOCATION_PIN_COLOR);
        locationDividerPaint.setColor(LOCATION_DIVIDER_COLOR);
        locationAddressBgPaint.setColor(LOCATION_ADDRESS_BG_COLOR);
        locationButtonBgPaint.setColor(LOCATION_ADDRESS_BG_COLOR); // same #6A1B9A bg as the address strip, matches the legacy layout
        locationAddressTextPaint.setColor(LOCATION_ADDRESS_TEXT_COLOR);
        locationAddressTextPaint.setTextSize(LOCATION_ADDRESS_TEXT_SP * density);
        locationButtonTextPaint.setColor(LOCATION_BUTTON_TEXT_COLOR);
        locationButtonTextPaint.setFakeBoldText(true);
        locationButtonTextPaint.setTextSize(LOCATION_BUTTON_TEXT_SP * density);
        locationButtonTextPaint.setTextAlign(Paint.Align.CENTER);

        linkThumbPlaceholderPaint.setColor(LINK_PREVIEW_THUMB_PLACEHOLDER_COLOR);
        linkDomainPaint.setColor(LINK_PREVIEW_DOMAIN_COLOR);
        linkDomainPaint.setTextSize(LINK_PREVIEW_DOMAIN_SP * density);
        linkTitlePaint.setColor(LINK_PREVIEW_TITLE_COLOR);
        linkTitlePaint.setTextSize(LINK_PREVIEW_TITLE_SP * density);
        linkTitlePaint.setFakeBoldText(true);

        audioBtnBgPaint.setColor(AUDIO_BTN_BG_COLOR);
        audioBtnIconPaint.setColor(AUDIO_BTN_ICON_COLOR);
        audioBtnIconPaint.setStyle(Paint.Style.FILL);
        audioDurPaint.setTextSize(AUDIO_DUR_TEXT_SP * density);
        audioDurPaint.setTextAlign(Paint.Align.RIGHT);

        // ── Poll card paints ──
        pollOptionBgPaint.setStyle(Paint.Style.FILL);
        pollFillPaint.setStyle(Paint.Style.FILL);
        pollStrokePaint.setStyle(Paint.Style.STROKE);
        pollStrokePaint.setStrokeWidth(POLL_STROKE_WIDTH_DP * density);
        pollHeaderLabelPaint.setColor(POLL_HEADER_ICON_COLOR);
        pollHeaderLabelPaint.setTextSize(POLL_HEADER_TEXT_SP * density);
        pollChipPaint.setColor(POLL_CHIP_TEXT_COLOR);
        pollChipPaint.setTextSize(POLL_CHIP_TEXT_SP * density);
        pollChipPaint.setFakeBoldText(true);
        pollQuestionPaint.setColor(POLL_QUESTION_COLOR);
        pollQuestionPaint.setTextSize(spToPx(POLL_QUESTION_TEXT_SP));
        pollQuestionPaint.setFakeBoldText(true);
        pollSubtitlePaint.setColor(POLL_SUBTITLE_COLOR);
        pollSubtitlePaint.setTextSize(POLL_SUBTITLE_TEXT_SP * density);
        pollOptionTextPaint.setColor(POLL_OPTION_TEXT_COLOR);
        pollOptionTextPaint.setTextSize(spToPx(POLL_OPTION_TEXT_SP));
        pollOptionPctPaint.setColor(POLL_OPTION_PCT_COLOR);
        pollOptionPctPaint.setTextSize(POLL_OPTION_PCT_SP * density);
        pollOptionPctPaint.setFakeBoldText(true);
        pollFooterPaint.setColor(POLL_FOOTER_TEXT_COLOR);
        pollFooterPaint.setTextSize(POLL_FOOTER_TEXT_SP * density);

        // ── View-once bubble paints ──
        viewOnceIconPaint.setTextSize(VO_ICON_TEXT_SP * density);
        viewOnceLabelPaint.setColor(VO_LABEL_COLOR);
        viewOnceLabelPaint.setFakeBoldText(true);
        viewOnceLabelPaint.setTextSize(VO_LABEL_TEXT_SP * density);
        viewOnceSublabelPaint.setColor(VO_SUBLABEL_COLOR);
        viewOnceSublabelPaint.setTextSize(VO_SUBLABEL_TEXT_SP * density);
        viewOnceTimePaint.setColor(VO_TIME_COLOR);
        viewOnceTimePaint.setTextSize(VO_TIME_SP * density);
        viewOnceTimePaint.setTextAlign(Paint.Align.RIGHT);

        // ── Reel-seen / Status-seen paints ──
        seenAvatarPlaceholderPaint.setColor(SEEN_AVATAR_PLACEHOLDER_COLOR);
        seenThumbBgPaint.setColor(SEEN_THUMB_BG_COLOR);
        seenOverlayIconPaint.setColor(SEEN_OVERLAY_ICON_COLOR);
        seenOverlayIconPaint.setTextSize(SEEN_OVERLAY_ICON_SP * density);
        seenOverlayIconPaint.setTextAlign(Paint.Align.CENTER);
        seenIconPaint.setTextSize(SEEN_ICON_TEXT_SP * density);
        seenLabelPaint.setColor(SEEN_LABEL_COLOR);
        seenLabelPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
        seenLabelPaint.setTextSize(SEEN_LABEL_TEXT_SP * density);
        seenNamePaint.setColor(SEEN_NAME_COLOR);
        seenNamePaint.setFakeBoldText(true);
        seenNamePaint.setTextSize(SEEN_NAME_TEXT_SP * density);
        seenTimePaint.setColor(SEEN_TIME_COLOR);
        seenTimePaint.setTextSize(SEEN_TIME_TEXT_SP * density);

        // ── Call-entry pill paints ──
        callEntryBgPaint.setColor(SEEN_STATUS_BG_COLOR);
        callEntryIconPaint.setTextSize(CALL_ENTRY_ICON_SP * density);
        callEntryLabelPaint.setTextSize(CALL_ENTRY_LABEL_SP * density);
        callEntryDotPaint.setColor(CALL_ENTRY_TIME_COLOR);
        callEntryDotPaint.setTextSize(CALL_ENTRY_DOT_SP * density);
        callEntryTimePaint.setColor(CALL_ENTRY_TIME_COLOR);
        callEntryTimePaint.setTextSize(CALL_ENTRY_TIME_SP * density);

        setWillNotDraw(false);

        gestureDetector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            // CRITICAL: must return true here. SimpleOnGestureListener's
            // default onDown() returns false, which means this view's
            // onTouchEvent(ACTION_DOWN) returns false (view isn't clickable
            // either), so Android never treats this view as the target for
            // the rest of that touch gesture — the follow-up ACTION_MOVE /
            // ACTION_UP / ACTION_CANCEL never reach onTouchEvent here.
            // GestureDetector still schedules its internal long-press
            // timer on ACTION_DOWN regardless, but since it never gets the
            // MOVE/UP/CANCEL needed to cancel that timer, onLongPress()
            // fires ~500ms after ANY touch — including the start of a
            // scroll — which is exactly the "touch/scroll selects the
            // message" bug. Returning true here makes this view the real
            // touch target so a real scroll (parent intercept -> CANCEL)
            // or a quick tap (UP) properly cancels the pending long press.
            @Override public boolean onDown(MotionEvent e) {
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (clickListener != null) clickListener.onBubbleClick();
                return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                if (clickListener != null) clickListener.onBubbleLongClick();
            }
        });
        // Belt-and-suspenders: mark the view as (long-)clickable so its
        // default onTouchEvent/accessibility behavior lines up with the
        // fact that it now genuinely handles click + long-click gestures.
        setClickable(true);
        setLongClickable(true);
    }

    public void setOnBubbleClickListener(OnBubbleClickListener l) {
        this.clickListener = l;
    }

    public OnBubbleClickListener getOnBubbleClickListener() {
        return this.clickListener;
    }

    /** Marks whether the currently-bound message is edited — caller's
     *  footerTimeText should already contain the "  ✏️ edited" suffix;
     *  this just tells onTouchEvent whether the footer's hit-rect
     *  (computed in drawFooter()) should respond to taps. */
    public void setEdited(boolean edited) {
        this.isEdited = edited;
    }

    /**
     * Bind this view to a message. Call from the adapter's onBindViewHolder
     * in place of the old setText()/Glide/etc. calls for the plain-text case.
     */
    public void bind(String text, String timeText, boolean isSent, boolean isRead, boolean isDelivered) {
        this.isMedia = false;
        this.isMediaGroup = false;
        this.isReelShare = false;
        this.isVideoMedia = false;
        this.isAudio = false;
        this.isContact = false;
        this.isLocation = false;
        this.isPoll = false;
        this.isViewOnce = false;
        this.isSeenBubble = false;
        this.mediaBitmap = null;
        this.messageText = text != null ? text : "";
        this.footerTimeText = timeText != null ? timeText : "";
        this.sent = isSent;
        this.read = isRead;
        this.delivered = isDelivered;

        Context ctx = getContext();
        textPaint.setColor(ChatThemeManager.get(ctx).getTextColor(ctx, sent));
        footerPaint.setColor(textPaint.getColor());
        tickPaint.setColor(ChatThemeManager.get(ctx).getTickColor(read));

        // Bubble drawable cache — same 4-combo cache key scheme as
        // ChatThemeManager's View-based bubbles (sent<<1 | hasReply).
        int cacheKey = (sent ? 1 : 0) << 1 | (hasReply ? 1 : 0);
        if (cacheKey != lastCacheKey || bubbleDrawable == null) {
            bubbleDrawable = buildBubbleDrawable(ctx, sent);
            lastCacheKey = cacheKey;
        }
        resolveReplyColors(ctx);

        // Only drop the cached StaticLayout when a relayout is actually
        // about to run — it's onMeasure() that rebuilds it, so nulling it
        // unconditionally while requestLayoutIfSizeChanged() skips the
        // relayout would leave onDraw() drawing a stale null and the
        // bubble would render blank until something else forces a layout.
        if (requestLayoutIfSizeChanged()) {
            textLayout = null;
        }
        invalidate();
    }

    /**
     * Bind this view to a single-image media message (matches iv_image in
     * item_message_sent/received.xml — fixed 180dp square, centerCrop,
     * 12dp rounded corners). Pass bitmap=null if the image hasn't finished
     * decoding yet; a placeholder box is drawn instead and setMediaBitmap()
     * can swap in the real bitmap later without a full rebind.
     *
     * @param caption  optional caption text below the image; null/empty for
     *                 a captionless image (timestamp/tick then overlay the
     *                 image itself as a translucent pill, WhatsApp-style)
     * @param aspectKey stable identifier for this image (the same URL/File
     *                  path the caller loads via Glide) used to look up a
     *                  previously-decoded aspect ratio in MEDIA_ASPECT_CACHE
     *                  so a rebind of an already-seen image (e.g. scrolling
     *                  back over it) sizes correctly on the very first
     *                  layout pass instead of flashing the square
     *                  placeholder first — pass null if no stable key is
     *                  available (falls back to the old placeholder→real
     *                  transition for this bind).
     * @param knownAspectRatio width/height of the media as known from the
     *                  Message's own mediaWidth/mediaHeight metadata
     *                  (captured once at send time — see
     *                  ChatMediaController), or 0f if unknown. This is the
     *                  highest-priority source: unlike a Bitmap or the
     *                  MEDIA_ASPECT_CACHE (both only known AFTER Glide
     *                  decodes something), it's available synchronously for
     *                  every message that carries it — including images
     *                  seen for the very first time during normal
     *                  scroll-down — so the bubble is sized correctly on
     *                  its first-ever layout pass with zero placeholder
     *                  flash. Messages sent before this metadata existed
     *                  pass 0f here and fall back to the bitmap/cache path.
     */
    public void bindMedia(@Nullable Bitmap bitmap, @Nullable String caption, String timeText,
                           boolean isSent, boolean isRead, boolean isDelivered,
                           @Nullable String aspectKey, float knownAspectRatio) {
        this.isMedia = true;
        // PERF: a recycled view must never replay a Picture cached for
        // whatever message it showed before this bind — see the field
        // javadoc near cachedMediaPicture.
        this.staticPictureDirty = true;
        this.isMediaGroup = false;
        this.isReelShare = false;
        this.isVideoMedia = false;
        this.isAudio = false;
        this.isContact = false;
        this.isLocation = false;
        this.isPoll = false;
        this.isViewOnce = false;
        this.isSeenBubble = false;
        this.videoDuration = null;
        this.mediaBitmap = bitmap;
        this.mediaAspectKey = aspectKey;
        // A recycled view must never keep the previous message's aspect
        // ratio — reset every bind, then immediately try to restore it
        // from three sources, in priority order: (1) a Bitmap already
        // available at bind time, (2) the process-wide MEDIA_ASPECT_CACHE
        // if this exact image was decoded before (the common case while
        // scrolling — RecyclerView constantly recycles/rebinds views for
        // images Glide already has in memory cache, and without this the
        // bubble would flash the square placeholder on every single
        // rebind), or (3) 0f/"unknown" if this is truly the first time
        // this image is being shown, in which case setMediaBitmap() will
        // still do the one-time placeholder→real relayout once decoded.
        Float cachedRatio = aspectKey != null ? MEDIA_ASPECT_CACHE.get(aspectKey) : null;
        if (knownAspectRatio > 0f) {
            // Highest priority: dimensions carried on the Message itself
            // (see javadoc above) — known before any decode happens, so
            // this is correct on the very first measure/layout pass.
            this.mediaAspectRatio = knownAspectRatio;
            if (aspectKey != null) {
                MEDIA_ASPECT_CACHE.put(aspectKey, knownAspectRatio);
            }
        } else if (bitmap != null && bitmap.getHeight() > 0) {
            this.mediaAspectRatio = (float) bitmap.getWidth() / bitmap.getHeight();
        } else if (cachedRatio != null) {
            this.mediaAspectRatio = cachedRatio;
        } else {
            this.mediaAspectRatio = 0f;
        }
        this.hasLinkPreview = false; // link-preview card is text-mode-only; a stale flag from a recycled view must not leak in here
        // Cleared fresh on every bind — a recycled holder must not carry a
        // stale gate from whatever RECEIVED image the view last showed;
        // the caller re-arms it with setMediaDownloadGate() right after this
        // call if the new message actually needs a manual download.
        this.mediaGated = false;
        this.mediaDownloading = false;
        this.mediaDownloadProgress = -1;
        this.messageText = caption != null ? caption : "";
        this.mediaHasCaption = !this.messageText.isEmpty();
        this.footerTimeText = timeText != null ? timeText : "";
        this.sent = isSent;
        this.read = isRead;
        this.delivered = isDelivered;

        Context ctx = getContext();
        textPaint.setColor(ChatThemeManager.get(ctx).getTextColor(ctx, sent));
        footerPaint.setColor(textPaint.getColor());
        tickPaint.setColor(ChatThemeManager.get(ctx).getTickColor(read));

        int cacheKey = (sent ? 1 : 0) << 1 | (hasReply ? 1 : 0);
        if (cacheKey != lastCacheKey || bubbleDrawable == null) {
            bubbleDrawable = buildBubbleDrawable(ctx, sent);
            lastCacheKey = cacheKey;
        }
        resolveReplyColors(ctx);

        if (requestLayoutIfSizeChanged()) {
            textLayout = null; // recomputed in onMeasure (only used if mediaHasCaption)
        }
        invalidate();
    }

    /**
     * Swap in a decoded Bitmap once an async (Glide) load finishes. Unlike
     * the old fixed-square slot, the bubble's size now depends on the
     * image's real aspect ratio, so this recomputes mediaAspectRatio from
     * the bitmap and — if that's the first time this bind learns the real
     * ratio (it was still the 0f/"unknown" placeholder) — requests a
     * relayout so the bubble grows/shrinks from the square placeholder to
     * its correct WhatsApp-style width/height. A null bitmap (load
     * cleared/cancelled) intentionally leaves mediaAspectRatio alone so a
     * recycled cell doesn't snap back to the square placeholder while a
     * new load for the same rebind is still in flight.
     */
    public void setMediaBitmap(@Nullable Bitmap bitmap) {
        this.mediaBitmap = bitmap;
        this.staticPictureDirty = true;
        if (bitmap != null && bitmap.getHeight() > 0) {
            boolean hadKnownRatio = mediaAspectRatio > 0f;
            mediaAspectRatio = (float) bitmap.getWidth() / bitmap.getHeight();
            // Remember it for next time (scroll-back, or another cell that
            // happens to share this exact URL) so future bindMedia()/
            // bindVideo() calls can restore it synchronously and skip the
            // square-placeholder flash entirely — see MEDIA_ASPECT_CACHE doc.
            if (mediaAspectKey != null) {
                MEDIA_ASPECT_CACHE.put(mediaAspectKey, mediaAspectRatio);
            }
            if (!hadKnownRatio) {
                requestLayoutIfSizeChanged();
            }
        }
        invalidate();
    }

    /**
     * Bind this view to a single "video" message — reuses the exact same
     * 180dp-square slot as bindMedia() (mediaRect/mediaBitmap/footer pill),
     * just with the play-glyph + duration-badge overlay drawn on top
     * (drawMedia()) and no caption support, mirroring the legacy
     * fl_video/iv_video_thumb case which never binds a caption either.
     * Pass thumb=null if not yet decoded; setMediaBitmap() swaps it in
     * later exactly as it does for bindMedia(). Video never shows the
     * manual download gate — same "streams directly" precedent as video
     * cells inside a media group (see setMediaDownloadGate() doc).
     */
    public void bindVideo(@Nullable Bitmap thumb, @Nullable String duration, String timeText,
                          boolean isSent, boolean isRead, boolean isDelivered,
                          @Nullable String aspectKey, float knownAspectRatio) {
        bindMedia(thumb, null, timeText, isSent, isRead, isDelivered, aspectKey, knownAspectRatio);
        this.isVideoMedia = true;
        this.videoDuration = duration;
        invalidate();
    }

    /**
     * Bind this view to an "audio" (voice message) message — mirrors
     * layout_msg_audio.xml: a play/pause circle button, a waveform track,
     * and an elapsed-time label, all inside the normal chat-bubble shape
     * (unlike the image/reel/group modes, an audio bubble is never
     * captioned so it just falls back to the same footer row a plain-text
     * bubble uses). `seed` should be something stable per message (the
     * audio URL works well) so the waveform's bar heights stay identical
     * across rebinds/recycling — mirrors AudioWaveformView.setSeed().
     * Always starts idle (not playing, 0 progress, no elapsed label);
     * the caller drives setAudioPlaying()/setAudioProgress()/
     * setAudioElapsedText() as MediaPlayer reports state, exactly as it
     * used to push into btnPlayPause/seekAudio/tvAudioDur.
     */
    public void bindAudio(@Nullable String seed, String timeText,
                           boolean isSent, boolean isRead, boolean isDelivered) {
        this.isMedia = false;
        this.isMediaGroup = false;
        this.isReelShare = false;
        this.isVideoMedia = false;
        this.isAudio = true;
        this.isContact = false;
        this.isLocation = false;
        this.isPoll = false;
        this.isViewOnce = false;
        this.isSeenBubble = false;
        this.videoDuration = null;
        this.mediaBitmap = null;
        this.hasLinkPreview = false; // link-preview card is text-mode-only; a stale flag from a recycled view must not leak in here
        this.messageText = "";
        this.footerTimeText = timeText != null ? timeText : "";
        this.sent = isSent;
        this.read = isRead;
        this.delivered = isDelivered;
        this.audioLevels = generateAudioLevels(seed, AUDIO_BAR_COUNT);
        this.audioProgress = 0f;
        this.audioPlaying = false;
        this.audioElapsedText = "";

        Context ctx = getContext();
        textPaint.setColor(ChatThemeManager.get(ctx).getTextColor(ctx, sent));
        footerPaint.setColor(textPaint.getColor());
        tickPaint.setColor(ChatThemeManager.get(ctx).getTickColor(read));
        // Waveform colors derive from the bubble's own text color so they
        // stay readable on both a colored sent bubble and a light received
        // one (the legacy AudioWaveformView hardcoded white-based colors,
        // which only worked on the sent/dark side — this fixes that).
        audioWaveformIdlePaint.setColor(textPaint.getColor());
        audioWaveformIdlePaint.setAlpha(90);
        audioWaveformPlayedPaint.setColor(textPaint.getColor());
        audioWaveformPlayedPaint.setAlpha(255);
        audioDurPaint.setColor(textPaint.getColor());
        audioDurPaint.setAlpha(180);

        int cacheKey = (sent ? 1 : 0) << 1 | (hasReply ? 1 : 0);
        if (cacheKey != lastCacheKey || bubbleDrawable == null) {
            bubbleDrawable = buildBubbleDrawable(ctx, sent);
            lastCacheKey = cacheKey;
        }
        resolveReplyColors(ctx);

        textLayout = null;
        requestLayoutIfSizeChanged();
        invalidate();
    }

    /** Toggles the play/pause glyph drawn inside the circle button — call whenever MediaPlayer actually starts/pauses/stops for this bubble. */
    public void setAudioPlaying(boolean playing) {
        if (this.audioPlaying == playing) return;
        this.audioPlaying = playing;
        invalidateAudioRow();
    }

    /** Cheap path — called every playback tick (e.g. every 250ms). No layout/measure work, draw-only, same precedent as AudioWaveformView.setProgress(). */
    public void setAudioProgress(float fraction) {
        float clamped = Math.max(0f, Math.min(1f, fraction));
        if (clamped == audioProgress) return;
        audioProgress = clamped;
        invalidateAudioRow();
    }

    /** Elapsed "m:ss" label shown next to the waveform while playing; pass "" to clear it back to idle (mirrors legacy tv_audio_dur, which never shows a total duration upfront — only the live elapsed time once playback starts). */
    public void setAudioElapsedText(@Nullable String text) {
        this.audioElapsedText = text != null ? text : "";
        invalidateAudioRow();
    }

    /** Resets the bubble to its idle state (button back to ▶, progress to 0, elapsed label cleared) — call when playback stops/completes/errors, or right before rebinding a recycled holder to a different message. */
    public void resetAudioPlayback() {
        this.audioPlaying = false;
        this.audioProgress = 0f;
        this.audioElapsedText = "";
        invalidateAudioRow();
    }

    /**
     * Dirty-region invalidate for audio playback ticks (progress/elapsed
     * text/play-pause icon) — these fire every ~250ms while a voice message
     * plays, but only the button+waveform+elapsed-label row actually
     * changes; the reply preview, footer/tick row, and any other bubbles in
     * the RecyclerView don't. A plain invalidate() re-draws the whole view
     * every tick; invalidate(Rect) lets the framework skip everything
     * outside that band. audioBtnRect/audioWaveformRect are only valid once
     * a layout pass has run (set in onMeasure), so this falls back to a
     * full invalidate() the first time (e.g. right after bindAudio(), before
     * that first layout has happened).
     */
    private void invalidateAudioRow() {
        if (audioBtnRect.isEmpty() && audioWaveformRect.isEmpty()) {
            invalidate();
            return;
        }
        float pad = 4f * density; // covers button/waveform stroke + AA bleed at the rect edges
        float left = Math.min(audioBtnRect.left, audioWaveformRect.left) - pad;
        float top = Math.min(audioBtnRect.top, audioWaveformRect.top) - pad;
        // Right edge extends to the bubble's own right padding, not just
        // audioWaveformRect.right, since the elapsed-time label draws past
        // it, right up against the bubble's right inset.
        float right = bubbleRect.right + pad;
        float bottom = Math.max(audioBtnRect.bottom, audioWaveformRect.bottom) + pad;
        audioDirtyRect.set((int) left, (int) top, (int) Math.ceil(right), (int) Math.ceil(bottom));
        invalidate(audioDirtyRect);
    }

    // ── PERF: audio-waveform bar-height cache ───────────────────────────
    // generateAudioLevels() is deterministic (same seed → same bars), so a
    // voice-message bubble scrolled off-screen and back used to redo the
    // exact same Random-loop every single bindAudio() for no reason. Same
    // pattern as sTextLayoutCache/sPollOptionLayoutCache above: a capped
    // LinkedHashMap keyed on (seed, count) so a different bar-count request
    // can never collide with a cached array built for another count.
    private static final int AUDIO_LEVELS_CACHE_CAPACITY = 120;
    private static final Object sAudioLevelsCacheLock = new Object();
    private static final java.util.LinkedHashMap<String, float[]> sAudioLevelsCache =
            new java.util.LinkedHashMap<String, float[]>(48, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, float[]> eldest) {
                    return size() > AUDIO_LEVELS_CACHE_CAPACITY;
                }
            };

    /** Same bar-height generation AudioWaveformView.generateLevels() uses — a stable seed always produces the same "waveform" shape. Cached by (seed, count) so scroll-back reuses the array instead of regenerating it. */
    private static float[] generateAudioLevels(@Nullable String seed, int count) {
        String key = (seed == null ? "" : seed) + "_" + count;
        synchronized (sAudioLevelsCacheLock) {
            float[] hit = sAudioLevelsCache.get(key);
            if (hit != null) return hit;
        }
        long s = (seed == null || seed.isEmpty()) ? 0L : seed.hashCode();
        java.util.Random r = new java.util.Random(s);
        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            out[i] = 0.25f + r.nextFloat() * 0.7f;
        }
        synchronized (sAudioLevelsCacheLock) {
            sAudioLevelsCache.put(key, out);
        }
        return out;
    }

    /**
     * Arms (or updates) the manual download gate over a single-image bubble —
     * mirrors the legacy fl_download_overlay/pb_download_spinner treatment
     * for a RECEIVED "image" not yet cached locally. Call once right after
     * bindMedia() for a message that still needs downloading; skip entirely
     * (or call clearMediaDownloadGate()) for sent/already-cached images.
     *
     * @param downloading true once the download is actually in flight (shows
     *                     the spinner/percentage ring); false for the idle
     *                     "tap to download" pill state.
     * @param progressPercent 0-100 for a live percentage, or -1 for an
     *                        indeterminate spinner (e.g. before the first
     *                        progress callback, or while only fetching size).
     * @param idleLabel text shown next to the idle icon (e.g. a file-size
     *                  string or "Photo"/"Tap to retry"); ignored while downloading.
     */
    public void setMediaDownloadGate(boolean downloading, int progressPercent, @Nullable String idleLabel) {
        this.mediaGated = true;
        this.mediaDownloading = downloading;
        this.mediaDownloadProgress = progressPercent;
        this.mediaDownloadLabel = idleLabel != null ? idleLabel : "";
        this.staticPictureDirty = true;
        invalidate();
    }

    /** Updates just the live percentage while a download is already in flight (call on every onProgress tick). */
    public void setMediaDownloadProgress(int progressPercent) {
        this.mediaGated = true;
        this.mediaDownloading = true;
        this.mediaDownloadProgress = progressPercent;
        this.staticPictureDirty = true;
        invalidate();
    }

    /** Dismisses the gate entirely — call once the real bitmap has been supplied via setMediaBitmap(). */
    public void clearMediaDownloadGate() {
        this.mediaGated = false;
        this.mediaDownloading = false;
        this.mediaDownloadProgress = -1;
        this.staticPictureDirty = true;
        invalidate();
    }

    /**
     * Bind this view to a SENT multi-image/video group (matches ll_media_group
     * + MediaGroupLayoutHelper's 1/2/3/4/5-9/10+ grid rules). Pass bitmap=null
     * per-cell placeholders first (thumbnails still loading); call
     * setMediaGroupBitmap(index, bitmap) as each Glide load finishes.
     *
     * Deliberately conservative like bindMedia(): only image/video cells, no
     * per-item captions, no manual-download gate — see isCanvasEligible() in
     * MessagePagingAdapter for exactly what qualifies.
     */
    public void bindMediaGroup(java.util.List<GridItem> items, @Nullable String caption, String timeText,
                                boolean isSent, boolean isRead, boolean isDelivered) {
        this.isMedia = false;
        this.isMediaGroup = true;
        this.isReelShare = false;
        this.isVideoMedia = false;
        this.isAudio = false;
        this.isContact = false;
        this.isLocation = false;
        this.isPoll = false;
        this.isViewOnce = false;
        this.isSeenBubble = false;
        this.videoDuration = null;
        this.hasLinkPreview = false; // link-preview card is text-mode-only; a stale flag from a recycled view must not leak in here
        this.groupItems = items != null ? items : java.util.Collections.emptyList();
        int total = this.groupItems.size();
        this.groupVisibleCount = Math.min(total, GROUP_MAX_VISIBLE);
        this.groupRemaining = total - GROUP_MAX_VISIBLE; // used only if > 0
        this.groupBitmaps = new Bitmap[groupVisibleCount];
        // Gate state is cleared here and only re-armed by an explicit
        // setGroupDownloadGate() call right after bind — for SENT groups
        // (or a RECEIVED group with nothing left to fetch) that call is
        // simply never made / made with an all-false array, so the gate
        // stays inert instead of showing a stale pill from a recycled view.
        this.groupGateActive = false;
        this.groupGatePendingCount = 0;
        this.groupCellPending = new boolean[groupVisibleCount];
        this.groupCellDownloading = new boolean[groupVisibleCount];
        this.groupCellProgress = new int[groupVisibleCount];
        java.util.Arrays.fill(this.groupCellProgress, -1);
        this.groupHasCaption = caption != null && !caption.isEmpty();
        this.messageText = groupHasCaption ? caption : "";
        this.footerTimeText = timeText != null ? timeText : "";
        this.sent = isSent;
        this.read = isRead;
        this.delivered = isDelivered;

        Context ctx = getContext();
        textPaint.setColor(ChatThemeManager.get(ctx).getTextColor(ctx, sent));
        footerPaint.setColor(textPaint.getColor());
        tickPaint.setColor(ChatThemeManager.get(ctx).getTickColor(read));

        int cacheKey = (sent ? 1 : 0) << 1 | (hasReply ? 1 : 0);
        if (cacheKey != lastCacheKey || bubbleDrawable == null) {
            bubbleDrawable = buildBubbleDrawable(ctx, sent);
            lastCacheKey = cacheKey;
        }
        resolveReplyColors(ctx);

        textLayout = null; // not read in media-group mode, safe to clear unconditionally
        if (requestLayoutIfSizeChanged()) {
            groupCaptionLayout = null; // recomputed in onMeasure
        }
        invalidate();
    }

    /** Swap in a decoded per-cell thumbnail once its Glide load finishes — no re-measure needed. */
    /**
     * Bind this view to a "reel_share"/"reel_link" message — mirrors
     * layout_msg_reel_share.xml exactly: a bubbleless 165×237dp card (no
     * chat-bubble background at all, unlike every other mode this view
     * supports), with a header (avatar+username) over a top gradient, a
     * centered play glyph, and a caption + "⬡ Reels" label over a bottom
     * gradient. Pass bitmaps=null if not yet decoded — setReelShareThumbBitmap/
     * setReelShareAvatarBitmap swap them in later (Glide callbacks) without a
     * full rebind, same precedent as setMediaBitmap(). username is the raw
     * handle without "@" (this method adds it); pass null/empty to fall back
     * to REEL_DEFAULT_USERNAME, same as the legacy ViewHolder path.
     */
    public void bindReelShare(@Nullable Bitmap thumb, @Nullable Bitmap avatar,
                               @Nullable String username, @Nullable String caption,
                               String timeText, boolean isSent, boolean isRead, boolean isDelivered) {
        this.isMedia = false;
        this.isMediaGroup = false;
        this.isReelShare = true;
        this.isVideoMedia = false;
        this.isAudio = false;
        this.isContact = false;
        this.isLocation = false;
        this.isPoll = false;
        this.isViewOnce = false;
        this.isSeenBubble = false;
        this.videoDuration = null;
        this.hasLinkPreview = false; // link-preview card is text-mode-only; a stale flag from a recycled view must not leak in here
        this.reelThumbBitmap = thumb;
        this.reelAvatarBitmap = avatar;
        this.reelUsername = (username != null && !username.isEmpty()) ? "@" + username : REEL_DEFAULT_USERNAME;
        this.reelCaptionText = caption != null ? caption : "";
        this.reelHasCaption = !this.reelCaptionText.isEmpty();
        this.footerTimeText = timeText != null ? timeText : "";
        this.sent = isSent;
        this.read = isRead;
        this.delivered = isDelivered;
        // Reel cards never show the manual single-media download gate —
        // full-res video streams directly once tapped, same precedent as
        // video cells inside a media group never gating.
        this.mediaGated = false;
        this.mediaDownloading = false;

        Context ctx = getContext();
        tickPaint.setColor(ChatThemeManager.get(ctx).getTickColor(read));

        // Reel-share now sits inside a normal chat bubble (same cache
        // scheme as the text/media bind methods) instead of floating
        // bubbleless.
        int cacheKey = (sent ? 1 : 0) << 1 | (hasReply ? 1 : 0);
        if (cacheKey != lastCacheKey || bubbleDrawable == null) {
            bubbleDrawable = buildBubbleDrawable(ctx, sent);
            lastCacheKey = cacheKey;
        }

        if (requestLayoutIfSizeChanged()) {
            reelCaptionLayout = null; // recomputed in onMeasure
        }
        invalidate();
    }

    /** Swap in a decoded reel-thumbnail Bitmap once Glide finishes — no re-measure needed, same fixed card size. */
    public void setReelShareThumbBitmap(@Nullable Bitmap bitmap) {
        this.reelThumbBitmap = bitmap;
        invalidate();
    }

    /** Swap in a decoded reel-owner avatar Bitmap once Glide (or the Firebase fallback) resolves it. */
    public void setReelShareAvatarBitmap(@Nullable Bitmap bitmap) {
        this.reelAvatarBitmap = bitmap;
        invalidate();
    }

    /**
     * Updates the username shown in the header — mirrors the legacy path's
     * async Firebase "reels/{id}" fallback resolving a username after the
     * initial bind. No-op for null/empty (keeps whatever's already shown).
     */
    public void setReelShareUsername(@Nullable String username) {
        if (username != null && !username.isEmpty()) {
            this.reelUsername = "@" + username;
            invalidate();
        }
    }

    /**
     * Updates the caption shown in the bottom overlay — mirrors the legacy
     * path's async Firebase fallback resolving a caption after the initial
     * bind. Only applies if this bind didn't already have one (matches the
     * legacy tv_reelShareCaption's "only fill in if still empty" guard).
     */
    public void setReelShareCaption(@Nullable String caption) {
        if (caption != null && !caption.isEmpty() && !reelHasCaption) {
            this.reelCaptionText = caption;
            this.reelHasCaption = true;
            if (requestLayoutIfSizeChanged()) {
                reelCaptionLayout = null; // recomputed in onMeasure
            }
            invalidate();
        }
    }

    /**
     * Binds a contact-share card — mirrors item_msg_contact.xml exactly:
     * a bubbleless 165dp-wide card, avatar + name/phone on a dark top
     * section, and a "View Contact" action row below. No timestamp/tick
     * footer (the legacy layout has none for this type), no caption, no
     * reply-preview interplay beyond the usual reply strip above it.
     *
     * @param avatar  decoded contact photo (contactPhotoUrl), or null for
     *                the placeholder ic_person glyph — pass a fresh
     *                Bitmap via setContactAvatarBitmap() once Glide
     *                resolves it, same async pattern as bindMedia().
     */
    public void bindContact(@Nullable Bitmap avatar, @Nullable String name, @Nullable String phone,
                             boolean isSent) {
        this.isMedia = false;
        this.isMediaGroup = false;
        this.isReelShare = false;
        this.isVideoMedia = false;
        this.isAudio = false;
        this.isContact = true;
        this.isLocation = false;
        this.isPoll = false;
        this.isViewOnce = false;
        this.isSeenBubble = false;
        this.hasLinkPreview = false; // stale flag from a recycled view must not leak in here
        this.mediaGated = false;
        this.mediaDownloading = false;
        this.contactAvatarBitmap = avatar;
        this.contactName = (name != null && !name.isEmpty()) ? name : CONTACT_DEFAULT_NAME;
        this.contactPhone = phone != null ? phone : "";
        this.sent = isSent;

        requestLayoutIfSizeChanged();
        invalidate();
    }

    /** Swap in a decoded contact-photo Bitmap once Glide finishes — no re-measure needed, same fixed card size. */
    public void setContactAvatarBitmap(@Nullable Bitmap bitmap) {
        this.contactAvatarBitmap = bitmap;
        invalidate();
    }

    /**
     * Binds a location-share card — mirrors item_msg_location.xml exactly:
     * a bubbleless 165dp-wide card (same shape family as bindContact()),
     * a purple map-thumbnail header, a translucent purple address strip
     * (up to 2 lines), and an "Open in Maps" action row. No timestamp/
     * tick footer (the legacy layout has none for this type), no caption,
     * no reply-preview interplay beyond the usual reply strip above it.
     *
     * @param mapThumb decoded Google Static Maps bitmap, or null to draw
     *                 the placeholder pin on the plain purple header —
     *                 pass a fresh Bitmap via setLocationMapBitmap() once
     *                 Glide resolves it (only when a Maps API key is
     *                 configured, same as ChatLocationShareController.
     *                 bindBubble's ivMap.setImageResource() fallback),
     *                 same async pattern as bindContact()/bindMedia().
     * @param address  formatted address string, or a "lat, lng" fallback —
     *                 caller does the same formatting
     *                 ChatLocationShareController.bindBubble() does.
     */
    /**
     * Binds one of the 3 "View Once" (Feature 13) bubble variants — mirrors
     * item_view_once_bubble.xml (VIEW_ONCE_RECEIVED), item_view_once_sent_waiting.xml
     * (VIEW_ONCE_WAITING), and item_view_once_expired.xml (VIEW_ONCE_EXPIRED). Reuses
     * the standard chat-bubble background shape (rounded rect) but with its own
     * per-variant solid colour instead of the sent/received theme colour, so it does
     * NOT go through buildBubbleDrawable()/bubbleDrawable at all — drawViewOnce()
     * paints its own background directly, same precedent as the contact/location cards.
     *
     * @param variant       one of VIEW_ONCE_RECEIVED / VIEW_ONCE_WAITING / VIEW_ONCE_EXPIRED
     * @param sublabel      only used for VIEW_ONCE_RECEIVED — the media-type hint under "View Once"
     * @param expiredLabel  only used for VIEW_ONCE_EXPIRED — "Opened" / "Expired" / "Removed"
     * @param openedAtText  only used for VIEW_ONCE_EXPIRED — e.g. "Opened · 3:45 PM"; pass null/empty when not shown
     * @param showOpenedAt  only used for VIEW_ONCE_EXPIRED — whether the openedAtText line is visible (sender + normal "Opened" state only)
     * @param isSent        VIEW_ONCE_WAITING is always sent-side (right-aligned, 200dp); RECEIVED/EXPIRED are always received-side (left-aligned)
     */
    public void bindViewOnce(int variant, @Nullable String sublabel, @Nullable String expiredLabel,
                              @Nullable String openedAtText, boolean showOpenedAt,
                              String timeText, boolean isSent) {
        this.isMedia = false;
        this.isMediaGroup = false;
        this.isReelShare = false;
        this.isVideoMedia = false;
        this.isAudio = false;
        this.isContact = false;
        this.isLocation = false;
        this.isPoll = false;
        this.isGifBubble = false;
        this.isFileBubble = false;
        this.hasLinkPreview = false;
        this.mediaGated = false;
        this.mediaDownloading = false;
        this.isViewOnce = true;
        this.isSeenBubble = false;
        this.viewOnceVariant = variant;
        this.viewOnceSublabel = sublabel != null ? sublabel : "";
        this.viewOnceExpiredLabel = (expiredLabel != null && !expiredLabel.isEmpty()) ? expiredLabel : "Opened";
        this.viewOnceOpenedAtText = openedAtText != null ? openedAtText : "";
        this.viewOnceShowOpenedAt = showOpenedAt;
        this.footerTimeText = timeText != null ? timeText : "";
        this.sent = isSent;

        requestLayoutIfSizeChanged();
        invalidate();
    }

    /**
     * Binds a "watched your reel" (isReel=true) or "seen your status" (isReel=false)
     * system bubble — mirrors item_reel_seen_bubble.xml / item_status_seen_bubble.xml.
     * These always render on the "received" (left) side, never sent. Pass avatar/thumb
     * as null if not yet decoded — setSeenAvatarBitmap()/setSeenThumbBitmap() swap them
     * in later (Glide callbacks) without a full rebind, same precedent as bindReelShare.
     *
     * @param isReel      true = reel_seen bubble (🎬 clapperboard + play overlay), false = status_seen (👁 eye overlay)
     * @param hasThumb    whether a thumbnail URL exists at all — sizes the reserved thumb area upfront
     *                    (matches fl_reel_seen_thumb/fl_status_seen_thumb's GONE-until-loaded visibility)
     * @param senderName  optional sender name shown in groups; null/empty to hide (tv_..._name GONE)
     */
    public void bindSeenBubble(boolean isReel, @Nullable Bitmap avatar, @Nullable Bitmap thumb,
                                boolean hasThumb, @Nullable String senderName, String timeText) {
        this.isMedia = false;
        this.isMediaGroup = false;
        this.isReelShare = false;
        this.isVideoMedia = false;
        this.isAudio = false;
        this.isContact = false;
        this.isLocation = false;
        this.isPoll = false;
        this.isGifBubble = false;
        this.isFileBubble = false;
        this.hasLinkPreview = false;
        this.mediaGated = false;
        this.mediaDownloading = false;
        this.isViewOnce = false;
        this.isSeenBubble = true;
        this.seenIsReel = isReel;
        this.seenAvatarBitmap = avatar;
        this.seenThumbBitmap = thumb;
        this.seenHasThumb = hasThumb;
        this.seenName = senderName != null ? senderName : "";
        this.seenHasName = !this.seenName.isEmpty();
        this.footerTimeText = timeText != null ? timeText : "";
        this.sent = false;

        requestLayoutIfSizeChanged();
        invalidate();
    }

    /** Swap in a decoded avatar Bitmap once Glide finishes — no re-measure needed (bindSeenBubble only). */
    public void setSeenAvatarBitmap(@Nullable Bitmap bitmap) {
        this.seenAvatarBitmap = bitmap;
        invalidate();
    }

    /** Swap in a decoded reel/status thumbnail Bitmap once Glide finishes (bindSeenBubble only). */
    public void setSeenThumbBitmap(@Nullable Bitmap bitmap) {
        this.seenThumbBitmap = bitmap;
        invalidate();
    }

    /**
     * Binds the "call log" system row — mirrors item_call_entry_bubble.xml:
     * a small rounded pill (icon + label + " • " dot + time, single row,
     * no reply/reactions/pinned/tick-footer) aligned to the caller's side.
     * Caller (adapter) computes the emoji/label text/color exactly as the
     * legacy bindCallEntryBubble() did — this method just measures/draws
     * whatever strings it's given.
     *
     * @param icon        "📞" or "📹"
     * @param label       e.g. "Audio call • 2:30" / "Missed video call"
     * @param labelColor  0xFFFFFFFF normally, red for missed/no-answer
     * @param timeText    formatted "h:mm a" timestamp
     * @param iAmCaller   true = pill aligns to the right (I placed the call)
     */
    public void bindCallEntry(@Nullable String icon, @Nullable String label,
                               int labelColor, @Nullable String timeText, boolean iAmCaller) {
        this.isMedia = false;
        this.isMediaGroup = false;
        this.isReelShare = false;
        this.isVideoMedia = false;
        this.isAudio = false;
        this.isContact = false;
        this.isLocation = false;
        this.isPoll = false;
        this.isGifBubble = false;
        this.isFileBubble = false;
        this.isViewOnce = false;
        this.isSeenBubble = false;
        this.hasLinkPreview = false;
        this.mediaGated = false;
        this.mediaDownloading = false;
        this.isCallEntry = true;
        this.callEntryIcon = icon != null ? icon : "";
        this.callEntryLabel = label != null ? label : "";
        this.callEntryLabelColor = labelColor;
        this.callEntryTime = timeText != null ? timeText : "";
        this.sent = iAmCaller;

        requestLayoutIfSizeChanged();
        invalidate();
    }

    public void bindLocation(@Nullable Bitmap mapThumb, @Nullable String address, boolean isSent) {
        this.isMedia = false;
        this.isMediaGroup = false;
        this.isReelShare = false;
        this.isVideoMedia = false;
        this.isAudio = false;
        this.isContact = false;
        this.isLocation = true;
        this.isPoll = false;
        this.isViewOnce = false;
        this.isSeenBubble = false;
        this.hasLinkPreview = false; // stale flag from a recycled view must not leak in here
        this.mediaGated = false;
        this.mediaDownloading = false;
        this.locationMapBitmap = mapThumb;
        this.locationAddress = (address != null && !address.isEmpty()) ? address : LOCATION_DEFAULT_ADDRESS;
        this.sent = isSent;

        if (requestLayoutIfSizeChanged()) {
            locationAddressLayout = null; // recomputed in onMeasure
        }
        invalidate();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GIF BUBBLE — bindGif / setGifBitmap / resetGif
    // Reuses the single-image layout path entirely (same 180dp slot, same
    // download-gate overlay) and adds a "GIF" badge pill in the top-start
    // corner of the thumbnail, matching WhatsApp/Telegram's GIF treatment.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Binds a GIF message bubble. Delegates to bindMedia() for layout/draw;
     * sets isGifBubble so drawMedia() adds the "GIF" badge.
     */
    public void bindGif(@Nullable String gifUrl, String timeText, boolean isSent, boolean isRead, boolean isDelivered) {
        isGifBubble = true;
        isFileBubble = false;
        bindMedia(null, null, timeText != null ? timeText : "", isSent, isRead, isDelivered, gifUrl, 0f);
    }

    /** Swaps in the decoded GIF first-frame bitmap. Same pattern as setMediaBitmap(). */
    public void setGifBitmap(@Nullable Bitmap bmp) {
        isGifBubble = true;
        setMediaBitmap(bmp);
    }

    /** Resets GIF state. Call from onViewRecycled(). */
    public void resetGif() {
        isGifBubble = false;
        clearMediaDownloadGate();
        setMediaBitmap(null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FILE BUBBLE — bindFile / setFileDownloadState / setFileCached / clearFileBubble
    // Card-style bubble: left file-type icon circle, centre name+size row,
    // right download/open action button, standard footer below.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Binds a file message bubble.
     *
     * @param fileName      Display file name, e.g. "report.pdf".
     * @param mimeType      MIME type string — drives icon color and label.
     * @param formattedSize Human-readable size, e.g. "12.4 MB". Pass "" if unknown.
     * @param isCached      True if the file is already on disk (shows Open icon ⬗).
     * @param isSent        True for outgoing bubbles.
     * @param isRead        True if read (double-blue tick).
     * @param isDelivered   True if delivered but unread (double-grey tick).
     */
    public void bindFile(@Nullable String fileName, @Nullable String mimeType,
                         @Nullable String formattedSize, boolean isCached,
                         boolean isSent, boolean isRead, boolean isDelivered) {
        this.isMedia        = false;
        this.isMediaGroup   = false;
        this.isReelShare    = false;
        this.isVideoMedia   = false;
        this.isAudio        = false;
        this.isContact      = false;
        this.isLocation     = false;
        this.isPoll         = false;
        this.isViewOnce     = false;
        this.isSeenBubble   = false;
        this.isGifBubble    = false;
        this.isFileBubble   = true;
        this.hasLinkPreview = false;
        this.mediaGated     = false;
        this.mediaDownloading = false;

        this.fileIsCached        = isCached;
        this.fileIsDownloading   = false;
        this.fileDownloadPercent = 0;
        this.fileNameText        = (fileName != null && !fileName.isEmpty()) ? fileName : "File";
        this.fileIconColor       = resolveFileIconColor(mimeType);
        String mimeLabel         = resolveFileMimeLabel(mimeType);
        this.fileSizeMimeText    = (formattedSize != null && !formattedSize.isEmpty())
                ? formattedSize + " · " + mimeLabel : mimeLabel;

        this.sent        = isSent;
        this.read        = isRead;
        this.delivered   = isDelivered;
        this.footerTimeText = "";  // set separately via bind() footer — caller must call setFooterTime() after bindFile() if needed; or we use the existing footerTimeText field

        Context ctx = getContext();
        int cacheKey = (sent ? 1 : 0) << 1 | (hasReply ? 1 : 0);
        if (cacheKey != lastCacheKey || bubbleDrawable == null) {
            bubbleDrawable = buildBubbleDrawable(ctx, sent);
            lastCacheKey = cacheKey;
        }

        requestLayoutIfSizeChanged();
        invalidate();
    }

    /** Updates the download-progress state on a file bubble. Call from adapter's download callback. */
    public void setFileDownloadState(boolean downloading, int percent) {
        this.fileIsDownloading   = downloading;
        this.fileDownloadPercent = percent;
        invalidate();
    }

    /** Marks the file as cached (download complete). Redraws action button as ⬗. */
    public void setFileCached(boolean cached) {
        this.fileIsCached      = cached;
        this.fileIsDownloading = false;
        invalidate();
    }

    /** Resets all file-bubble state. Call from onViewRecycled(). */
    public void clearFileBubble() {
        isFileBubble        = false;
        fileNameText        = "";
        fileSizeMimeText    = "";
        fileIconColor       = 0xFF607D8B;
        fileIsCached        = false;
        fileIsDownloading   = false;
        fileDownloadPercent = 0;
    }

    private static int resolveFileIconColor(@Nullable String mime) {
        if (mime == null) return 0xFF607D8B;
        if (mime.equals("application/pdf"))                            return 0xFFE53935;
        if (mime.startsWith("audio/"))                                 return 0xFFFB8C00;
        if (mime.startsWith("video/"))                                 return 0xFF8E24AA;
        if (mime.startsWith("image/"))                                 return 0xFF039BE5;
        if (mime.contains("zip") || mime.contains("rar")
                || mime.contains("7z") || mime.contains("tar"))        return 0xFF546E7A;
        if (mime.contains("wordprocessing") || mime.contains("msword")) return 0xFF1E88E5;
        if (mime.contains("spreadsheet") || mime.contains("excel"))    return 0xFF43A047;
        if (mime.contains("presentation") || mime.contains("powerpoint")) return 0xFFFB8C00;
        return 0xFF607D8B;
    }

    private static String resolveFileMimeLabel(@Nullable String mime) {
        if (mime == null) return "File";
        if (mime.equals("application/pdf"))                            return "PDF";
        if (mime.startsWith("audio/"))                                 return "Audio";
        if (mime.startsWith("video/"))                                 return "Video";
        if (mime.startsWith("image/"))                                 return "Image";
        if (mime.contains("zip") || mime.contains("rar")
                || mime.contains("7z") || mime.contains("tar"))        return "Archive";
        if (mime.contains("wordprocessing") || mime.contains("msword")) return "Word";
        if (mime.contains("spreadsheet") || mime.contains("excel"))    return "Excel";
        if (mime.contains("presentation") || mime.contains("powerpoint")) return "PPT";
        return "File";
    }


    /**
     * Bind this view to a poll message. Call from bindCanvasMessage() in the
     * adapter in place of the old ensurePollInflated / bindPoll(VH, ...) path.
     *
     * @param question    poll question text
     * @param options     ordered list of option strings
     * @param counts      per-option vote count (same length as options)
     * @param myVote      per-option whether the current user voted for it
     * @param total       total number of distinct voters
     * @param closed      true = poll is closed, show "Closed" chip
     * @param multiChoice true = multi-select poll
     * @param isSent      true = sent bubble, false = received
     * @param timeText    formatted timestamp + tick string for the footer
     * @param isRead      read-tick colour
     * @param isDelivered delivered-tick colour
     */
    public void bindPoll(
            @Nullable String question,
            @Nullable java.util.List<String> options,
            int[] counts,
            boolean[] myVote,
            int total,
            boolean closed,
            boolean multiChoice,
            boolean isSent,
            String timeText,
            boolean isRead,
            boolean isDelivered) {
        this.isMedia       = false;
        this.isMediaGroup  = false;
        this.isReelShare   = false;
        this.isVideoMedia  = false;
        this.isAudio       = false;
        this.isContact     = false;
        this.isLocation    = false;
        this.isPoll        = true;
        this.isViewOnce    = false;
        this.isSeenBubble  = false;
        this.hasLinkPreview = false;
        this.mediaBitmap   = null;
        this.messageText   = "";
        this.footerTimeText = timeText != null ? timeText : "";
        this.sent          = isSent;
        this.read          = isRead;
        this.delivered     = isDelivered;

        this.pollQuestion   = question != null ? question : "";
        this.pollClosed     = closed;
        this.pollMultiChoice = multiChoice;
        this.pollTotal      = total;

        int n = options != null ? options.size() : 0;
        this.pollOptions  = new String[n];
        this.pollCounts   = counts  != null && counts.length  >= n ? counts  : new int[n];
        this.pollMyVote   = myVote  != null && myVote.length  >= n ? myVote  : new boolean[n];
        this.pollIsLeader = new boolean[n];
        this.pollFillWidths = new float[n];
        for (int i = 0; i < n; i++) {
            this.pollOptions[i] = options.get(i) != null ? options.get(i) : "";
        }

        // Leader detection (same logic as bindPoll(VH) in adapter)
        int maxCount = 0;
        for (int c : this.pollCounts) if (c > maxCount) maxCount = c;
        if (maxCount > 0) {
            int countAtMax = 0;
            for (int c : this.pollCounts) if (c == maxCount) countAtMax++;
            boolean clearLeader = countAtMax < n;
            for (int i = 0; i < n; i++) {
                this.pollIsLeader[i] = clearLeader && this.pollCounts[i] == maxCount;
            }
        }

        Context ctx = getContext();
        textPaint.setColor(ChatThemeManager.get(ctx).getTextColor(ctx, sent));
        footerPaint.setColor(textPaint.getColor());
        tickPaint.setColor(ChatThemeManager.get(ctx).getTickColor(read));

        // Bubble drawable cache (poll uses normal bubble bg)
        int cacheKey = (sent ? 1 : 0) << 1 | (hasReply ? 1 : 0);
        if (cacheKey != lastCacheKey || bubbleDrawable == null) {
            bubbleDrawable = buildBubbleDrawable(ctx, sent);
            lastCacheKey = cacheKey;
        }
        resolveReplyColors(ctx);

        if (requestLayoutIfSizeChanged()) {
            // Question/options text (or something else size-relevant)
            // changed — onMeasure() is about to rebuild both from scratch.
            pollQuestionLayout = null;
            this.pollOptionLayouts = new StaticLayout[n];
        } else if (pollOptionLayouts == null || pollOptionLayouts.length != n) {
            // Shouldn't happen (options count is part of the size
            // signature), but guard against a stale/mismatched array
            // rather than risk an ArrayIndexOutOfBounds in onDraw.
            pollOptionLayouts = new StaticLayout[n];
            requestLayout();
        }
        invalidate();
    }

    /** Swap in a decoded Google Static Maps Bitmap once Glide finishes — no re-measure needed, same fixed card size. */
    public void setLocationMapBitmap(@Nullable Bitmap bitmap) {
        this.locationMapBitmap = bitmap;
        invalidate();
    }

    public void setMediaGroupBitmap(int index, @Nullable Bitmap bitmap) {
        if (index < 0 || index >= groupBitmaps.length) return;
        groupBitmaps[index] = bitmap;
        invalidate();
    }

    /**
     * Arms (or clears) the RECEIVED-group manual download-gate. Call once,
     * right after bindMediaGroup(), with one boolean per visible cell:
     * true = that cell is an un-cached image still needing a manual
     * download, false = already local (or a video/audio/file cell, which
     * never gates). Pass an all-false array (or simply never call this)
     * for SENT groups — the gate only ever applies to received media.
     *
     * While any entry is true, the whole grid shows a single dimmed
     * overlay with a centered "Download N photos" pill (mirrors
     * MediaGroupLayoutHelper's addMasterDownloadOverlay) that swallows
     * every tap in the grid until the person taps it — same as the old
     * View-based master overlay blocking the per-cell overlays beneath it.
     */
    public void setGroupDownloadGate(@Nullable boolean[] cellNeedsDownload) {
        int n = groupVisibleCount;
        this.groupCellPending = new boolean[n];
        this.groupCellDownloading = new boolean[n];
        this.groupCellProgress = new int[n];
        java.util.Arrays.fill(this.groupCellProgress, -1);
        int pending = 0;
        if (cellNeedsDownload != null) {
            for (int i = 0; i < n && i < cellNeedsDownload.length; i++) {
                groupCellPending[i] = cellNeedsDownload[i];
                if (cellNeedsDownload[i]) pending++;
            }
        }
        this.groupGatePendingCount = pending;
        this.groupGateActive = pending > 0;
        invalidate();
    }

    /** Marks one cell as actively downloading (or not) — draws a small dim+icon
     *  badge on just that cell once the master gate has been dismissed. Resets
     *  its progress to indeterminate; call setGroupCellProgress() as real
     *  percentages start arriving. */
    public void setGroupCellDownloading(int index, boolean downloading) {
        if (index < 0 || index >= groupCellDownloading.length) return;
        groupCellDownloading[index] = downloading;
        if (downloading && index < groupCellProgress.length) groupCellProgress[index] = -1;
        invalidate();
    }

    /** Updates the live download percentage for one in-flight group cell (call on every onProgress
     *  tick); pass -1 for an indeterminate spinner. Implies the cell is downloading. */
    public void setGroupCellProgress(int index, int percent) {
        if (index < 0 || index >= groupCellDownloading.length) return;
        groupCellDownloading[index] = true;
        if (index < groupCellProgress.length) groupCellProgress[index] = percent;
        invalidate();
    }

    /** Call once a cell's manual download finishes and its full-res bitmap has
     *  been supplied via setMediaGroupBitmap() — clears its pending/downloading
     *  badge so the cell renders as a plain image again. */
    public void markGroupCellDownloaded(int index) {
        if (index < 0 || index >= groupCellPending.length) return;
        groupCellPending[index] = false;
        if (index < groupCellDownloading.length) groupCellDownloading[index] = false;
        if (index < groupCellProgress.length) groupCellProgress[index] = -1;
        invalidate();
    }

    /**
     * Attach a reply-preview strip (quoted sender + quoted text + optional
     * thumbnail) to this bubble. Call AFTER bind() (bind() must have already
     * set `sent`, since reply-strip colors differ for sent vs received —
     * matching bg_reply_preview_sent/received.xml + the tv_reply_sender/
     * tv_reply_text color attrs in the two item_message_*.xml layouts).
     *
     * @param senderName quoted message's sender display name (1 line, ellipsized)
     * @param text       quoted message's text/caption (up to 2 lines, ellipsized)
     * @param thumb      optional small bitmap (e.g. Glide .asBitmap() result already
     *                   decoded by the caller) shown at 44dp on the right, same as
     *                   iv_reply_thumb. Pass null for text-only replies.
     */
    public void setReply(String senderName, String text, @Nullable Bitmap thumb) {
        this.hasReply = true;
        this.replySenderName = senderName != null ? senderName : "";
        this.replyText = text != null ? text : "";
        this.replyThumb = thumb;
        resolveReplyColors(getContext());
        // Bubble corner treatment doesn't actually depend on hasReply in this
        // view (unlike the legacy bg_reply_preview backgrounds, the outer
        // bubble shape is unaffected), but keep cache key in sync in case
        // that changes later.
        int cacheKey = (sent ? 1 : 0) << 1 | 1;
        if (cacheKey != lastCacheKey) {
            bubbleDrawable = buildBubbleDrawable(getContext(), sent);
            lastCacheKey = cacheKey;
        }
        if (requestLayoutIfSizeChanged()) {
            replySenderLayout = null; // recomputed in onMeasure
            replyTextLayout = null;
        }
        invalidate();
    }

    /** Call when a message has no reply — clears any previous reply-strip state so a recycled view doesn't show stale data. */
    public void clearReply() {
        this.hasReply = false;
        this.replySenderName = "";
        this.replyText = "";
        this.replyThumb = null;
        this.replySenderLayout = null;
        this.replyTextLayout = null;
        this.replyBoxHeight = 0;
        requestLayoutIfSizeChanged();
        invalidate();
    }

    /**
     * Attach a reaction badge to this bubble — mirrors ll_reactions/
     * tv_reactions in item_message_sent/received.xml: plain emoji text
     * (e.g. "😂2 👍"), no background box, floating over the bubble's
     * bottom-end corner. Works the same regardless of bind()/bindMedia()/
     * bindMediaGroup() mode. Call setReactions(text) when the message has
     * reactions, clearReactions() otherwise — always call one of the two,
     * every bind, since a recycled view holds whatever the previous
     * message left in it.
     *
     * @param text already-formatted reaction summary (caller does the
     *             emoji-counting/truncation, same as bindReactionsOnly()
     *             does for the legacy tv_reactions TextView); null/empty
     *             is treated the same as clearReactions().
     */
    public void setReactions(@Nullable String text) {
        this.hasReactions = text != null && !text.isEmpty();
        this.reactionsText = text != null ? text : "";
        requestLayoutIfSizeChanged();
        invalidate();
    }

    /** Call when a message has no reactions — clears any previous badge state so a recycled view doesn't show a stale reaction. */
    public void clearReactions() {
        this.hasReactions = false;
        this.reactionsText = "";
        requestLayoutIfSizeChanged();
        invalidate();
    }

    /**
     * Show/hide the "📌 Pinned" label above the bubble's top-end corner.
     * Works alongside any bind mode (text/media/group) and with or without
     * a reply/reactions. Always call this every bind — true or false —
     * since a recycled view holds whatever the previous message left in it.
     */
    public void setPinned(boolean pinned) {
        this.isPinned = pinned;
        requestLayoutIfSizeChanged();
        invalidate();
    }

    /**
     * Show the sender-name row above the bubble's top-start corner, for a
     * received message in a group chat — mirrors tv_sender_name (11sp,
     * bold, brand_primary). Caller (MessagePagingAdapter) only calls this
     * for `!sent && isGroup` messages, same gate the legacy path uses;
     * this view itself doesn't re-check sent/group, it just draws
     * whatever name it's given.
     *
     * @param name display name; null/empty is treated as clearGroupSender().
     */
    public void setGroupSender(@Nullable String name) {
        this.hasGroupSender = name != null && !name.isEmpty();
        this.groupSenderName = name != null ? name : "";
        if (hasGroupSender) {
            groupSenderPaint.setColor(androidx.core.content.ContextCompat.getColor(
                    getContext(), com.callx.app.core.R.color.brand_primary));
        }
        requestLayoutIfSizeChanged();
        invalidate();
    }

    /** Call for sent messages, or received messages outside a group chat — clears any stale sender-name state on a recycled view. */
    public void clearGroupSender() {
        this.hasGroupSender = false;
        this.groupSenderName = "";
        requestLayoutIfSizeChanged();
        invalidate();
    }

    /**
     * Show the italic "↪ Forwarded from X" label, stacked directly below
     * the pinned-label/group-sender row (mirrors tv_forwarded's
     * constraintTop_toBottomOf tv_sender_name in item_message_received.xml —
     * same top-start corner, works for sent and received alike since this
     * view anchors it to the bubble's own left edge rather than a shared
     * avatar column). Always call this every bind (or clearForwarded()) —
     * a recycled view otherwise keeps the previous message's label.
     *
     * @param originalSenderName original sender's display name; null/empty
     *                           is treated as clearForwarded().
     */
    public void setForwardedFrom(@Nullable String originalSenderName) {
        boolean fwd = originalSenderName != null && !originalSenderName.isEmpty();
        this.hasForwarded = fwd;
        this.forwardedText = fwd ? ("\u21AA Forwarded from " + originalSenderName) : "";
        requestLayoutIfSizeChanged();
        invalidate();
    }

    /** Call when a message wasn't forwarded — clears any stale label state on a recycled view. */
    public void clearForwarded() {
        setForwardedFrom(null);
    }

    /**
     * Shows/hides the quick-forward icon button that sits just outside the
     * bubble (see forwardBtnRect's field doc). Adapter should pass the same
     * "media/link message" condition the legacy btnQuickForward.setVisibility()
     * check used — this method only toggles/repositions, it doesn't decide
     * eligibility itself. Position is recomputed from bubbleRect on the
     * very next onMeasure() pass, same as every other overlay in this view.
     * Always call this every bind (true or false) — a recycled view
     * otherwise keeps the previous message's button state.
     */
    public void setQuickForwardVisible(boolean visible) {
        // Always invalidate (not just on a boolean flip) — this is a fresh
        // bind for a (possibly) different message, and the button's
        // position depends on bubbleRect, which can move even when
        // showForwardBtn itself stays true across binds.
        this.showForwardBtn = visible;
        invalidate();
    }

    /**
     * Switch the plain-text bubble into the deleted-message placeholder
     * look — italic + 60% alpha, matching bindMessage()'s
     * tvMessage.setAlpha(0.6f) treatment for "This message was deleted" /
     * "You deleted this message". Call bind() with the already-substituted
     * placeholder text FIRST, then this every time (true or false) — a
     * recycled view otherwise keeps the previous message's italic/alpha
     * state. Only meaningful for the plain-text bind() path: a deleted
     * message never reaches bindMedia()/bindMediaGroup() (see
     * isCanvasEligible() in MessagePagingAdapter).
     */
    public void setDeletedStyle(boolean deleted) {
        this.isDeletedStyle = deleted;
        textPaint.setTypeface(deleted ? Typeface.create(Typeface.DEFAULT, Typeface.ITALIC) : Typeface.DEFAULT);
        textPaint.setAlpha(deleted ? DELETED_TEXT_ALPHA : 255);
        if (requestLayoutIfSizeChanged()) {
            textLayout = null; // rebuild with the new typeface/alpha before the next draw
        }
        invalidate();
    }

    /**
     * Show/update the "⏳ mm:ss" disappearing-message countdown in the
     * footer row, just before the timestamp — mirrors tv_expiry in
     * item_message_sent/received.xml's ll_msg_footer. Caller
     * (MessagePagingAdapter) re-calls this once a second via the shared
     * ExpiryTickManager while the message has an active expiresAt, and
     * clearExpiry() once it fires (or immediately, for a message that
     * never had one). Already-formatted text (caller does the mm:ss
     * formatting, same as formatRemaining() does for the legacy TextView).
     */
    public void setExpiryText(@Nullable String text) {
        this.hasExpiry = text != null && !text.isEmpty();
        this.expiryText = text != null ? text : "";
        requestLayoutIfSizeChanged();
        invalidateExpiryRegion();
    }

    /**
     * Dirty-region invalidate for the once-a-second expiry countdown tick.
     * The "⏳ mm:ss" pill lands in one of three spots depending on bubble
     * type — the regular footer row (text/audio/file/poll/etc.), the
     * captionless media/media-group/reel corner pill (mediaPillRect), or
     * the floating corner badge on contact/location cards
     * (drawCornerExpiryPill's anchor) — so rather than branch on every
     * bubble-type flag here, this unions all three candidate bands. Only
     * one is ever actually populated/relevant for the current bind; the
     * others are empty/stale rects left over from a different bind and cost
     * nothing to include. The result is still far smaller than the full
     * view (which may also contain a large media bitmap, an album grid, or
     * a multi-line caption that isn't changing on this tick).
     *
     * Note: setExpiryText() also calls requestLayout() right before this,
     * because the countdown text's width feeds into the footer's reserved
     * width — the rects read below are last frame's (pre-layout) positions,
     * so the padding here is intentionally generous to absorb the few
     * pixels a digit-count change (e.g. "1:00" → "0:59") can shift things by.
     */
    private void invalidateExpiryRegion() {
        float pad = 16f * density;
        float footerBandH = spToPx(FOOTER_TEXT_SP) + FOOTER_GAP_DP * density;
        float left = bubbleRect.left;
        float top = bubbleRect.bottom - footerBandH - pad;
        float right = bubbleRect.right + pad;
        float bottom = bubbleRect.bottom + pad;
        if (!mediaPillRect.isEmpty()) {
            left = Math.min(left, mediaPillRect.left - pad);
            top = Math.min(top, mediaPillRect.top - pad);
            right = Math.max(right, mediaPillRect.right + pad);
            bottom = Math.max(bottom, mediaPillRect.bottom + pad);
        }
        if (isContact) {
            top = Math.min(top, contactCardRect.top - pad);
        }
        if (isLocation) {
            top = Math.min(top, locationMapRect.top - pad);
        }
        expiryDirtyRect.set((int) left, (int) top, (int) Math.ceil(right), (int) Math.ceil(bottom));
        invalidate(expiryDirtyRect);
    }

    /** Call once the countdown finishes, or for a message with no expiry at all. */
    public void clearExpiry() {
        setExpiryText(null);
    }

    /**
     * Show a link-preview card below the message text — mirrors
     * layout_msg_link_preview.xml (stub_link_preview): domain label,
     * bold title (max 2 lines), and an optional OG-image thumbnail on
     * top. Only meaningful alongside the plain-text bind() mode (mirrors
     * LinkPreviewFetcher only ever being invoked for text messages in
     * the legacy adapter). Caller detects the URL via
     * LinkPreviewFetcher.extractFirstUrl(text), fetches async via
     * LinkPreviewFetcher.fetch() exactly as before, and pushes the
     * result through this setter instead of the ll_link_preview View
     * group. Known simplification vs. the legacy path: the card only
     * appears once the fetch resolves (no reserved "loading" space) —
     * call clearLinkPreview() up front and this only from onResult().
     *
     * @param url      the resolved preview URL (LinkPreviewFetcher.Result.url);
     *                 also used as the click-target and as this view's own
     *                 staleness-guard tag by the caller.
     * @param title    OG title; required in practice (LinkPreviewFetcher never
     *                 returns a Result with an empty title) but null-safe here.
     * @param domain   bare hostname (e.g. "youtube.com"); null/empty just
     *                 skips that row.
     * @param hasThumb whether Result.imageUrl was non-empty — reserves the
     *                 120dp thumbnail band up front so the card doesn't
     *                 resize again once setLinkPreviewThumbBitmap() lands.
     */
    public void setLinkPreview(String url, @Nullable String title, @Nullable String domain, boolean hasThumb) {
        this.hasLinkPreview = url != null && !url.isEmpty();
        this.linkPreviewUrl = url != null ? url : "";
        this.linkTitle = title != null ? title : "";
        this.linkDomain = domain != null ? domain : "";
        this.linkHasThumb = hasThumb;
        this.linkThumbBitmap = null; // any bitmap from a previously-bound URL no longer applies
        if (requestLayoutIfSizeChanged()) {
            this.linkTitleLayout = null; // recomputed in onMeasure
        }
        invalidate();
    }

    /** Swap in the OG-image thumbnail once Glide decodes it — no full rebind needed. Pass null while still loading / on load failure (placeholder box stays up). */
    public void setLinkPreviewThumbBitmap(@Nullable Bitmap bitmap) {
        this.linkThumbBitmap = bitmap;
        invalidate();
    }

    /** Call when the message has no link, the URL has no OG data, or the fetch failed — clears any stale card state on a recycled view. */
    public void clearLinkPreview() {
        this.hasLinkPreview = false;
        this.linkPreviewUrl = "";
        this.linkTitle = "";
        this.linkDomain = "";
        this.linkHasThumb = false;
        this.linkThumbBitmap = null;
        this.linkTitleLayout = null;
        this.linkCardHeight = 0;
        requestLayoutIfSizeChanged();
        invalidate();
    }

    private void resolveReplyColors(Context ctx) {
        if (sent) {
            replyBgPaint.setColor(SENT_REPLY_BG);
            replyBarPaint.setColor(SENT_REPLY_BAR);
            replySenderPaint.setColor(SENT_REPLY_SENDER);
            replyTextPaint.setColor(SENT_REPLY_TEXT);
            // bg_reply_preview_sent — same #33000000 6dp-radius card background the
            // link-preview card reuses (see LINK_PREVIEW_* constants doc).
            linkCardBgPaint.setColor(SENT_REPLY_BG);
        } else {
            replyBgPaint.setColor(RECEIVED_REPLY_BG);
            int brand = androidx.core.content.ContextCompat.getColor(ctx, com.callx.app.core.R.color.brand_primary);
            replyBarPaint.setColor(brand);
            replySenderPaint.setColor(brand);
            replyTextPaint.setColor(androidx.core.content.ContextCompat.getColor(ctx, com.callx.app.core.R.color.bubble_received_text));
            // bg_reply_preview_received — same #22000000 card background.
            linkCardBgPaint.setColor(RECEIVED_REPLY_BG);
        }
    }

    private GradientDrawable buildBubbleDrawable(Context ctx, boolean sent) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(androidx.core.content.ContextCompat.getColor(ctx, sent
                ? com.callx.app.core.R.color.bubble_sent
                : com.callx.app.core.R.color.bubble_received));
        float r = CORNER_RADIUS_DP * density;
        float tail = TAIL_RADIUS_DP * density;
        if (sent) {
            gd.setCornerRadii(new float[]{r, r, r, r, tail, tail, r, r});
        } else {
            gd.setCornerRadii(new float[]{tail, tail, r, r, r, r, r, r});
        }
        return gd;
    }

    /**
     * Computes the image-media slot's width/height in px, WhatsApp/
     * Telegram-style: derived from the real image's aspect ratio
     * (mediaAspectRatio) instead of a fixed square, then clamped to
     * MEDIA_MIN_WIDTH_DP..min(MEDIA_MAX_WIDTH_DP, maxWidthPx) and
     * MEDIA_MIN_HEIGHT_DP..MEDIA_MAX_HEIGHT_DP. While the ratio is still
     * unknown (mediaAspectRatio <= 0f — bitmap not decoded yet for this
     * bind), falls back to the legacy MEDIA_SIZE_DP square so the
     * placeholder box looks exactly like it always did.
     *
     * @param maxWidthPx the bubble's own available content width (same cap
     *                   text/caption content already respects) — a very
     *                   wide landscape photo must not force the bubble
     *                   wider than the chat column allows.
     * @param outWH      {width, height} written in px.
     */
    // Fallback aspect for the rare case where mediaAspectRatio is truly
    // unknown (a message sent before mediaWidth/mediaHeight metadata
    // existed, AND this exact URL has never been decoded before in this
    // process — so MEDIA_ASPECT_CACHE has nothing either). 4:3 is a much
    // closer average-photo guess than a hard 1:1 square, so even this
    // last-resort placeholder doesn't look like a generic box. Once the
    // real bitmap decodes, setMediaBitmap() relayouts to the true ratio
    // exactly as before — this only changes what the one-time fallback
    // looks like, not the underlying flow.
    private static final float FALLBACK_ASPECT_RATIO = 4f / 3f;

    private void computeMediaSize(int maxWidthPx, int[] outWH) {
        float ratio = mediaAspectRatio > 0f ? mediaAspectRatio : FALLBACK_ASPECT_RATIO;
        int maxW = Math.min(Math.round(MEDIA_MAX_WIDTH_DP * density), Math.max(1, maxWidthPx));
        int minW = Math.min(Math.round(MEDIA_MIN_WIDTH_DP * density), maxW);
        int minH = Math.round(MEDIA_MIN_HEIGHT_DP * density);
        int maxH = Math.round(MEDIA_MAX_HEIGHT_DP * density);

        int w, h;
        if (ratio >= 1f) {
            // Landscape or square — lead with width, capped at maxW.
            w = maxW;
            h = Math.round(w / ratio);
            if (h < minH) {
                h = minH;
                w = Math.round(h * ratio);
            }
        } else {
            // Portrait — lead with height, capped at maxH.
            h = maxH;
            w = Math.round(h * ratio);
            if (w < minW) {
                w = minW;
                h = Math.round(w / ratio);
            }
        }
        outWH[0] = Math.max(minW, Math.min(maxW, w));
        outWH[1] = Math.max(minH, Math.min(maxH, h));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int parentWidth = MeasureSpec.getSize(widthMeasureSpec);
        int maxBubbleWidth = Math.round(parentWidth * MAX_BUBBLE_WIDTH_FRACTION);
        int hPad = Math.round(H_PADDING_DP * density);
        int vPad = Math.round(V_PADDING_DP * density);
        int footerHeight = Math.round(spToPx(FOOTER_TEXT_SP) + FOOTER_GAP_DP * density);
        int maxTextWidth = Math.max(1, maxBubbleWidth - hPad * 2);
        // PERF: keep the background-precompute cache's target width in sync
        // with what real bubbles are actually being measured at — see the
        // cache javadoc near the textPaint fields above.
        sLastKnownMaxTextWidth = maxTextWidth;

        // ── Pinned label / group sender-name (+ broadcast badge, which
        // reuses the same row via setGroupSender) / forwarded label — all
        // sit above the bubble entirely, so they shift bubbleTop down
        // rather than participating in bubbleHeight. Row 1 (pinned at
        // top-end, sender-name at top-start) shares one reserved band
        // sized to whichever of the two is taller; forwarded stacks in
        // its own row directly below row 1 — mirrors tv_forwarded's
        // constraintTop_toBottomOf tv_sender_name in the legacy layout. ──
        int labelGap = Math.round(PINNED_LABEL_GAP_DP * density);
        int row1Height = 0;
        if (isPinned) {
            Paint.FontMetrics pfm = pinnedLabelPaint.getFontMetrics();
            pinnedLabelHeight = Math.round(pfm.descent - pfm.ascent);
            pinnedLabelWidth = pinnedLabelPaint.measureText(PINNED_LABEL_TEXT);
            row1Height = Math.max(row1Height, pinnedLabelHeight);
        } else {
            pinnedLabelHeight = 0;
            pinnedLabelWidth = 0;
        }
        if (hasGroupSender) {
            Paint.FontMetrics gfm = groupSenderPaint.getFontMetrics();
            groupSenderTextHeight = Math.round(gfm.descent - gfm.ascent);
            groupSenderWidth = groupSenderPaint.measureText(groupSenderName);
            row1Height = Math.max(row1Height, groupSenderTextHeight);
        } else {
            groupSenderTextHeight = 0;
            groupSenderWidth = 0;
        }

        int aboveExtra = 0;
        if (row1Height > 0) {
            pinnedBaselineY = row1Height - pinnedLabelPaint.getFontMetrics().descent;
            groupSenderBaselineY = row1Height - groupSenderPaint.getFontMetrics().descent;
            aboveExtra = row1Height + labelGap;
        }

        if (hasForwarded) {
            Paint.FontMetrics ffm = forwardedPaint.getFontMetrics();
            forwardedTextHeight = Math.round(ffm.descent - ffm.ascent);
            forwardedTextWidth = forwardedPaint.measureText(forwardedText);
            forwardedBaselineY = aboveExtra + forwardedTextHeight - ffm.descent;
            aboveExtra += forwardedTextHeight + labelGap;
        } else {
            forwardedTextHeight = 0;
            forwardedTextWidth = 0;
        }
        int pinnedTopExtra = aboveExtra;

        // ── Reply-preview box — measured the same way regardless of
        // isMedia; a reply strip can sit above either a text or an image
        // bubble. ──
        int replyBoxContentWidth = 0;
        if (hasReply) {
            int replyMargin = Math.round(REPLY_MARGIN_DP * density);
            int replyBar = Math.round(REPLY_BAR_WIDTH_DP * density);
            int replyPadH = Math.round(REPLY_PADDING_H_DP * density);
            int replyPadV = Math.round(REPLY_PADDING_V_DP * density);
            int replyThumbSize = replyThumb != null ? Math.round(REPLY_THUMB_SIZE_DP * density) : 0;
            int replyThumbMargin = replyThumb != null ? Math.round(REPLY_THUMB_MARGIN_DP * density) : 0;

            int replyTextColMaxWidth = Math.max(1, maxTextWidth - replyBar - replyPadH * 2
                    - replyThumbSize - replyThumbMargin * 2);

            replySenderLayout = StaticLayout.Builder
                    .obtain(replySenderName, 0, replySenderName.length(), replySenderPaint, replyTextColMaxWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setMaxLines(1)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setIncludePad(false)
                    .build();
            replyTextLayout = StaticLayout.Builder
                    .obtain(replyText, 0, replyText.length(), replyTextPaint, replyTextColMaxWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setIncludePad(false)
                    .build();

            int replyTextColWidth = Math.max(maxLineWidth(replySenderLayout), maxLineWidth(replyTextLayout));
            int replyTextColHeight = replySenderLayout.getHeight() + replyTextLayout.getHeight();

            int replyInnerHeight = Math.max(replyTextColHeight + replyPadV * 2, replyThumbSize);
            replyBoxHeight = Math.max(Math.round(REPLY_MIN_HEIGHT_DP * density), replyInnerHeight) + replyMargin * 2;
            replyBoxContentWidth = replyBar + replyTextColWidth + replyPadH * 2
                    + (replyThumb != null ? replyThumbSize + replyThumbMargin * 2 : 0);
        } else {
            replyBoxHeight = 0;
        }
        int replyGap = hasReply ? Math.round(REPLY_GAP_TO_MESSAGE_DP * density) : 0;

        int bubbleContentWidth;
        int bubbleHeight;

        if (isMedia) {
            // ── Image bubble — WhatsApp/Telegram-style dynamic size: the
            // slot's width/height come from the real image's aspect ratio
            // (clamped between MEDIA_MIN_*_DP/MEDIA_MAX_*_DP) instead of a
            // fixed 180dp square, so a portrait photo gets a tall bubble, a
            // landscape photo gets a wide one, and neither is centerCrop'd
            // away — see computeMediaSize(). ──
            computeMediaSize(maxTextWidth, mediaSizeOut);
            int mediaW = mediaSizeOut[0];
            int mediaH = mediaSizeOut[1];
            int captionWidth = 0;
            int captionBlockHeight = 0;

            if (mediaHasCaption) {
                float captionFooterReserve = footerPaint.measureText(footerTimeText)
                        + (sent ? (TICK_SIZE_DP + TICK_GAP_DP) * density : 0)
                        + FOOTER_GAP_DP * density
                        + expiryReserveWidth();
                textLayout = StaticLayout.Builder
                        .obtain(messageText, 0, messageText.length(), textPaint, maxTextWidth)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setLineSpacing(0f, 1f)
                        .setIncludePad(false)
                        .build();
                captionWidth = maxLineWidth(textLayout);
                int lastLineW = (int) Math.ceil(textLayout.getLineWidth(textLayout.getLineCount() - 1));
                captionWidth = Math.max(captionWidth, (int) (lastLineW + captionFooterReserve));
                captionBlockHeight = Math.round(MEDIA_CAPTION_GAP_DP * density)
                        + textLayout.getHeight() + vPad + footerHeight;
                footerReserveWidth = captionFooterReserve;
            } else {
                textLayout = null;
            }

            bubbleContentWidth = Math.min(
                    Math.max(mediaW, Math.max(replyBoxContentWidth, captionWidth)), maxTextWidth);

            // Captionless images get a tight vPad margin around the image;
            // captioned images get the same margin plus the caption+footer
            // block below (footer overlay is skipped when there's a caption
            // — see onDraw — matching the text-bubble footer placement).
            bubbleHeight = replyBoxHeight + replyGap + vPad + mediaH + vPad + captionBlockHeight;

        } else if (isReelShare) {
            // ── Reel-share card, now inside a normal chat bubble ──
            // caption is an overlay INSIDE the card (bottom gradient), so
            // bubbleHeight is replyBox + real vPad top/bottom + the card —
            // same padding scheme as image/video, unlike the old
            // exact-fit-to-card sizing.
            int cardW = Math.round(REEL_CARD_WIDTH_DP * density);
            int cardH = Math.round(REEL_CARD_HEIGHT_DP * density);

            if (reelHasCaption) {
                int captionMaxW = Math.max(1, cardW
                        - Math.round((REEL_BOTTOM_PAD_H_DP + REEL_BOTTOM_PAD_END_DP) * density));
                reelCaptionLayout = StaticLayout.Builder
                        .obtain(reelCaptionText, 0, reelCaptionText.length(), reelCaptionPaint, captionMaxW)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setMaxLines(2)
                        .setEllipsize(TextUtils.TruncateAt.END)
                        .setIncludePad(false)
                        .build();
            } else {
                reelCaptionLayout = null;
            }

            bubbleContentWidth = Math.max(cardW, replyBoxContentWidth);
            bubbleHeight = replyBoxHeight + replyGap + vPad + cardH + vPad;

        } else if (isContact) {
            // ── Contact-share card (fixed 165dp wide, wrap_content height) ──
            // Height = top section + divider + button row, exactly like
            // item_msg_contact.xml's stacked LinearLayout — no vPad, no
            // footer row (no timestamp/tick at all for this type).
            int cardW = Math.round(CONTACT_CARD_WIDTH_DP * density);
            int cardH = Math.round((CONTACT_TOP_HEIGHT_DP + CONTACT_DIVIDER_HEIGHT_DP
                    + CONTACT_BUTTON_HEIGHT_DP) * density);

            bubbleContentWidth = Math.max(cardW - hPad * 2, replyBoxContentWidth);
            bubbleHeight = replyBoxHeight + replyGap + cardH;

        } else if (isLocation) {
            // ── Location-share card (fixed 165dp wide, wrap_content height) ──
            // Height = map header + divider + address strip (up to 2
            // lines) + divider + button row, exactly like
            // item_msg_location.xml's stacked LinearLayout — no vPad, no
            // footer row (no timestamp/tick at all for this type, same
            // as the contact card).
            int cardW = Math.round(LOCATION_CARD_WIDTH_DP * density);
            float mapH = LOCATION_MAP_HEIGHT_DP * density;
            float dividerH = LOCATION_DIVIDER_HEIGHT_DP * density;
            float btnH = LOCATION_BUTTON_HEIGHT_DP * density;
            float addrPadH = LOCATION_ADDRESS_PAD_H_DP * density;
            int addrMaxW = Math.max(1, cardW - Math.round(addrPadH * 2));
            locationAddressLayout = StaticLayout.Builder
                    .obtain(locationAddress, 0, locationAddress.length(), locationAddressTextPaint, addrMaxW)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setIncludePad(false)
                    .build();
            float addrBlockH = LOCATION_ADDRESS_PAD_TOP_DP * density + locationAddressLayout.getHeight()
                    + LOCATION_ADDRESS_PAD_BOTTOM_DP * density;
            int cardH = Math.round(mapH + dividerH + addrBlockH + dividerH + btnH);

            bubbleContentWidth = Math.max(cardW - hPad * 2, replyBoxContentWidth);
            bubbleHeight = replyBoxHeight + replyGap + cardH;

        } else if (isViewOnce) {
            // ── View-once card — bubbleless, own solid colour, sized per
            // variant to roughly match the legacy fixed-width layouts. ──
            float cardW;
            float pad;
            switch (viewOnceVariant) {
                case VIEW_ONCE_WAITING:
                    cardW = VO_WIDTH_WAITING_DP * density;
                    pad = VO_PAD_WAITING_DP * density;
                    viewOnceIconPaint.setTextSize(VO_ICON_WAITING_SP * density);
                    viewOnceLabelPaint.setTextSize(VO_WAITING_LABEL_SP * density);
                    viewOnceLabelPaint.setColor(VO_WAITING_LABEL_COLOR);
                    viewOnceTimePaint.setColor(VO_TIME_WAITING_COLOR);
                    break;
                case VIEW_ONCE_EXPIRED:
                    cardW = VO_WIDTH_EXPIRED_DP * density;
                    pad = VO_PAD_EXPIRED_DP * density;
                    viewOnceIconPaint.setTextSize(VO_ICON_EXPIRED_SP * density);
                    viewOnceLabelPaint.setTextSize(VO_EXPIRED_LABEL_SP * density);
                    viewOnceLabelPaint.setColor(VO_EXPIRED_LABEL_COLOR);
                    viewOnceTimePaint.setColor(VO_TIME_COLOR);
                    break;
                default:
                    cardW = VO_WIDTH_RECEIVED_DP * density;
                    pad = VO_PAD_RECEIVED_DP * density;
                    viewOnceIconPaint.setTextSize(VO_ICON_TEXT_SP * density);
                    viewOnceLabelPaint.setTextSize(VO_LABEL_TEXT_SP * density);
                    viewOnceLabelPaint.setColor(VO_LABEL_COLOR);
                    viewOnceTimePaint.setColor(VO_TIME_COLOR);
                    break;
            }

            Paint.FontMetrics iconFm = viewOnceIconPaint.getFontMetrics();
            float iconH = iconFm.descent - iconFm.ascent;
            Paint.FontMetrics lfm = viewOnceLabelPaint.getFontMetrics();
            float labelH = lfm.descent - lfm.ascent;
            float rowH;
            if (viewOnceVariant == VIEW_ONCE_RECEIVED) {
                Paint.FontMetrics sfm = viewOnceSublabelPaint.getFontMetrics();
                float textColH = labelH + VO_LABEL_SUBLABEL_GAP_DP * density + (sfm.descent - sfm.ascent);
                rowH = Math.max(iconH, textColH);
            } else {
                rowH = Math.max(iconH, labelH);
            }

            float openedAtH = 0f;
            if (viewOnceVariant == VIEW_ONCE_EXPIRED && viewOnceShowOpenedAt && !viewOnceOpenedAtText.isEmpty()) {
                openedAtH = VO_OPENED_AT_GAP_DP * density + VO_OPENED_AT_SP * density * 1.3f;
            }

            Paint.FontMetrics tfm = viewOnceTimePaint.getFontMetrics();
            float timeH = tfm.descent - tfm.ascent;
            float cardH = pad * 2 + rowH + openedAtH + VO_ROW_GAP_TOP_DP * density + timeH;

            bubbleContentWidth = Math.max(Math.round(cardW) - hPad * 2, replyBoxContentWidth);
            bubbleHeight = replyBoxHeight + replyGap + Math.round(cardH);

        } else if (isSeenBubble) {
            // ── Reel-seen / status-seen card — a 36dp circular avatar sits
            // OUTSIDE the card, to its left; the card itself is a small
            // bubbleless rounded rect (own solid colour) with an optional
            // thumbnail, an icon+italic-label row, an optional sender
            // name, and a small time line. Always received-side. ──
            float avatarSize = SEEN_AVATAR_SIZE_DP * density;
            float avatarGap = SEEN_AVATAR_GAP_DP * density;
            float padH = SEEN_CARD_PAD_H_DP * density;
            float padEnd = SEEN_CARD_PAD_END_DP * density;
            float padTop = SEEN_CARD_PAD_TOP_DP * density;
            float padBottom = SEEN_CARD_PAD_BOTTOM_DP * density;
            float thumbW = SEEN_THUMB_W_DP * density;
            float thumbH = SEEN_THUMB_H_DP * density;
            float minCardW = SEEN_CARD_MIN_WIDTH_DP * density;

            float cardW = seenHasThumb ? Math.max(minCardW, thumbW + padH + padEnd) : minCardW;

            Paint.FontMetrics ifm = seenIconPaint.getFontMetrics();
            Paint.FontMetrics slfm = seenLabelPaint.getFontMetrics();
            float iconLabelRowH = Math.max(ifm.descent - ifm.ascent, slfm.descent - slfm.ascent);

            float nameH = 0f;
            if (seenHasName) {
                Paint.FontMetrics nfm2 = seenNamePaint.getFontMetrics();
                nameH = SEEN_NAME_GAP_TOP_DP * density + (nfm2.descent - nfm2.ascent);
            }
            Paint.FontMetrics stfm = seenTimePaint.getFontMetrics();
            float timeRowH = SEEN_TIME_GAP_TOP_DP * density + (stfm.descent - stfm.ascent);

            float thumbBlockH = seenHasThumb ? thumbH + SEEN_THUMB_MARGIN_BOTTOM_DP * density : 0f;
            float cardH = padTop + thumbBlockH + iconLabelRowH + nameH + timeRowH + padBottom;

            bubbleContentWidth = Math.max(Math.round(avatarSize + avatarGap + cardW) - hPad * 2, replyBoxContentWidth);
            bubbleHeight = replyBoxHeight + replyGap + Math.round(Math.max(avatarSize, cardH));

        } else if (isCallEntry) {
            // ── Call-entry pill — single row (icon + label + dot + time),
            // no reply box, no vPad (the RecyclerView item margin already
            // supplies the 4dp gap item_call_entry_bubble.xml's own
            // paddingTop/Bottom gave it). Width = padH*2 + row content,
            // floored at CALL_ENTRY_MIN_WIDTH_DP same as the legacy pill's
            // android:minWidth="180dp". ──
            callEntryLabelPaint.setColor(callEntryLabelColor);
            float padH = CALL_ENTRY_PAD_H_DP * density;
            float padV = CALL_ENTRY_PAD_V_DP * density;
            float gap  = CALL_ENTRY_ICON_LABEL_GAP_DP * density;
            float iconW  = callEntryIconPaint.measureText(callEntryIcon);
            float labelW = callEntryLabelPaint.measureText(callEntryLabel);
            float dotW   = callEntryDotPaint.measureText(CALL_ENTRY_DOT_TEXT);
            float timeW  = callEntryTimePaint.measureText(callEntryTime);
            float rowContentW = iconW + gap + labelW + dotW + timeW;

            Paint.FontMetrics cifm = callEntryIconPaint.getFontMetrics();
            Paint.FontMetrics clfm = callEntryLabelPaint.getFontMetrics();
            Paint.FontMetrics cdfm = callEntryDotPaint.getFontMetrics();
            Paint.FontMetrics ctfm = callEntryTimePaint.getFontMetrics();
            float rowH = Math.max(Math.max(cifm.descent - cifm.ascent, clfm.descent - clfm.ascent),
                    Math.max(cdfm.descent - cdfm.ascent, ctfm.descent - ctfm.ascent));

            int pillWidth = Math.round(Math.max(CALL_ENTRY_MIN_WIDTH_DP * density, rowContentW + padH * 2));
            int pillHeight = Math.round(rowH + padV * 2);

            bubbleContentWidth = pillWidth - hPad * 2;
            bubbleHeight = pillHeight;

        } else if (isPoll) {
            // ── Poll card — uses normal bubble bg (unlike contact/location).
            // Width = POLL_CARD_WIDTH_DP; height = padding + header row +
            // question + subtitle + options + footer + padding + timestamp row.
            float padH   = POLL_PADDING_H_DP * density;
            float padTop = POLL_PADDING_TOP_DP * density;
            float padBot = POLL_PADDING_BOTTOM_DP * density;
            int cardW    = Math.round(POLL_CARD_WIDTH_DP * density);
            int innerW   = Math.max(1, cardW - Math.round(padH * 2));

            // Header row height (icon + label text, single line)
            Paint.FontMetrics hfm = pollHeaderLabelPaint.getFontMetrics();
            float headerTxtH = hfm.descent - hfm.ascent;
            pollHeaderRowH = Math.max(POLL_HEADER_ICON_SIZE_DP * density, headerTxtH);

            // Question layout
            pollQuestionPaint.setTextSize(spToPx(POLL_QUESTION_TEXT_SP));
            pollQuestionLayout = StaticLayout.Builder
                    .obtain(pollQuestion, 0, pollQuestion.length(), pollQuestionPaint, innerW)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setMaxLines(3)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setIncludePad(false)
                    .build();

            // Subtitle line height
            Paint.FontMetrics sfm = pollSubtitlePaint.getFontMetrics();
            pollSubtitleH = sfm.descent - sfm.ascent;

            // Option text layouts
            int n = pollOptions.length;
            pollOptionLayouts = new StaticLayout[n];
            float iconSz   = POLL_OPTION_ICON_SIZE_DP * density;
            float iconMar  = POLL_OPTION_ICON_MARGIN_DP * density;
            float pctW     = pollOptionPctPaint.measureText("100%") + iconMar;
            int optTextW   = Math.max(1, innerW - Math.round(POLL_OPTION_PAD_H_DP * density * 2 + iconSz + iconMar + pctW));
            // PERF: keep the poll-option precompute cache's target width in
            // sync with what's actually being measured — see the cache
            // javadoc above.
            sLastKnownPollOptionWidth = optTextW;
            for (int i = 0; i < n; i++) {
                StaticLayout cachedOption;
                String optKey = pollOptions[i].length() + "_" + pollOptions[i].hashCode() + "_" + optTextW;
                synchronized (sPollOptionLayoutCacheLock) {
                    cachedOption = sPollOptionLayoutCache.get(optKey);
                }
                if (cachedOption != null) {
                    pollOptionLayouts[i] = cachedOption;
                } else {
                    pollOptionLayouts[i] = StaticLayout.Builder
                            .obtain(pollOptions[i], 0, pollOptions[i].length(), pollOptionTextPaint, optTextW)
                            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                            .setMaxLines(2)
                            .setEllipsize(TextUtils.TruncateAt.END)
                            .setIncludePad(false)
                            .build();
                }
            }

            // Total height of option rows
            float optPadV = POLL_OPTION_PAD_V_DP * density;
            float optGap  = POLL_OPTION_GAP_DP * density;
            float optionsH = 0f;
            for (int i = 0; i < n; i++) {
                float rowH = Math.max(pollOptionLayouts[i].getHeight(), iconSz) + optPadV * 2;
                optionsH += rowH;
                if (i < n - 1) optionsH += optGap;
            }

            // Footer text height
            Paint.FontMetrics ffm = pollFooterPaint.getFontMetrics();
            float footerTxtH = ffm.descent - ffm.ascent;

            // Footer (timestamp) reserved width/height
            footerReserveWidth = footerPaint.measureText(footerTimeText)
                    + (sent ? (TICK_SIZE_DP + TICK_GAP_DP) * density : 0)
                    + FOOTER_GAP_DP * density
                    + expiryReserveWidth();
            int footerRowH = Math.round(spToPx(FOOTER_TEXT_SP) + FOOTER_GAP_DP * density);

            pollTotalCardH = padTop
                    + pollHeaderRowH
                    + POLL_QUESTION_GAP_DP * density
                    + pollQuestionLayout.getHeight()
                    + POLL_SUBTITLE_GAP_DP * density
                    + pollSubtitleH
                    + POLL_OPTIONS_GAP_DP * density
                    + optionsH
                    + POLL_FOOTER_GAP_DP * density
                    + footerTxtH
                    + padBot;

            bubbleContentWidth = Math.min(Math.max(cardW - Math.round(padH * 2), replyBoxContentWidth), maxTextWidth);
            bubbleHeight = replyBoxHeight + replyGap + vPad
                    + Math.round(pollTotalCardH)
                    + vPad + footerRowH;


        } else if (isMediaGroup) {
            // ── Media-group grid (multi-image/video) ──
            int[] dims = computeGroupGridDims(groupVisibleCount);
            int gridW = dims[0];
            int gridH = dims[1];

            if (groupHasCaption) {
                groupCaptionLayout = StaticLayout.Builder
                        .obtain(messageText, 0, messageText.length(), groupCaptionTextPaint,
                                Math.max(1, Math.min(gridW - dp(8), maxTextWidth)))
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setMaxLines(3)
                        .setEllipsize(TextUtils.TruncateAt.END)
                        .setIncludePad(false)
                        .build();
            } else {
                groupCaptionLayout = null;
            }

            bubbleContentWidth = Math.min(Math.max(gridW, replyBoxContentWidth), maxTextWidth);
            bubbleHeight = replyBoxHeight + replyGap + vPad + gridH + vPad;

        } else if (isFileBubble) {
            // ── File card (fixed 240dp wide) ─────────────────────────────────
            int cardW = Math.round(FILE_CARD_W_DP * density);
            textPaint.setTextSize(spToPx(13f));
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            float nameLineH = textPaint.getFontSpacing();
            textPaint.setTextSize(spToPx(10f));
            textPaint.setTypeface(Typeface.DEFAULT);
            float metaLineH = textPaint.getFontSpacing();
            float rowPad  = FILE_ROW_PAD_DP * density;
            float iconH   = FILE_ICON_COL_DP * density;
            float contentH = Math.max(iconH, rowPad + nameLineH + 2f * density + metaLineH + rowPad);
            fileCardHeight  = contentH;
            footerReserveWidth = footerPaint.measureText(footerTimeText)
                    + (sent ? (TICK_SIZE_DP + TICK_GAP_DP) * density : 0)
                    + FOOTER_GAP_DP * density
                    + expiryReserveWidth();
            int footerH  = Math.round(spToPx(FOOTER_TEXT_SP) + FOOTER_GAP_DP * density);
            bubbleContentWidth = Math.min(Math.max(cardW, replyBoxContentWidth), maxTextWidth);
            bubbleHeight = replyBoxHeight + replyGap + vPad + Math.round(contentH) + footerH;

        } else if (isAudio) {
            // ── Audio row (fixed-width, like the legacy ll_audio row) —
            // no caption, so bubbleHeight is just the row itself plus the
            // normal text-bubble footer below it. ──
            footerReserveWidth = footerPaint.measureText(footerTimeText)
                    + (sent ? (TICK_SIZE_DP + TICK_GAP_DP) * density : 0)
                    + FOOTER_GAP_DP * density
                    + expiryReserveWidth();
            int audioRowWidth = Math.round(AUDIO_CONTENT_WIDTH_DP * density);
            int audioRowHeight = Math.round(AUDIO_BTN_SIZE_DP * density);
            bubbleContentWidth = Math.min(
                    Math.max(audioRowWidth, Math.max(replyBoxContentWidth, (int) footerReserveWidth)),
                    maxTextWidth);
            bubbleHeight = replyBoxHeight + replyGap + vPad + audioRowHeight + vPad + footerHeight;

        } else {
            // ── Plain text bubble ──
            footerReserveWidth = footerPaint.measureText(footerTimeText)
                    + (sent ? (TICK_SIZE_DP + TICK_GAP_DP) * density : 0)
                    + FOOTER_GAP_DP * density
                    + expiryReserveWidth();

            // PERF: try the background-precomputed cache first (see its
            // javadoc near the textPaint fields) — skips the
            // StaticLayout.Builder...build() line-breaking cost entirely
            // on a hit. Never used for the deleted-message italic style;
            // that path always builds fresh, exactly as before.
            CachedTextLayout cachedLayout = null;
            if (!isDeletedStyle && !messageText.isEmpty()) {
                String cacheKey = messageText.length() + "_" + messageText.hashCode()
                        + "_" + maxTextWidth;
                synchronized (sTextLayoutCacheLock) {
                    cachedLayout = sTextLayoutCache.get(cacheKey);
                }
            }
            if (cachedLayout != null) {
                textLayout = cachedLayout.layout;
                // The cached layout owns its own dedicated TextPaint (never
                // the shared per-instance `textPaint` field), so it can't
                // have picked up this message's actual color yet — apply it
                // once here, before the very first draw. Layout.draw() reads
                // color live from this same paint reference every frame, so
                // this one assignment is all that's needed for the lifetime
                // of this bind.
                textLayout.getPaint().setColor(textPaint.getColor());
            } else {
                textLayout = StaticLayout.Builder
                        .obtain(messageText, 0, messageText.length(), textPaint, maxTextWidth)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setLineSpacing(0f, 1f)
                        .setIncludePad(false)
                        .build();
            }

            int textWidth = maxLineWidth(textLayout);
            int lastLineWidth = (int) Math.ceil(textLayout.getLineWidth(textLayout.getLineCount() - 1));
            int neededWidth = Math.max(textWidth, (int) (lastLineWidth + footerReserveWidth));

            // ── Link-preview card — stacks below the text, above the
            // footer. Sized to the full maxTextWidth column (like the
            // legacy stub's fixed 280dp width), so a bubble with a link
            // preview always grows to that width regardless of how short
            // the message text itself is. ──
            int linkGapTop = 0;
            linkCardHeight = 0;
            if (hasLinkPreview) {
                linkGapTop = Math.round(LINK_PREVIEW_GAP_TOP_DP * density);
                int cardPadH = Math.round(LINK_PREVIEW_PAD_H_DP * density);
                int cardPadTop = Math.round(LINK_PREVIEW_PAD_TOP_DP * density);
                int cardPadBottom = Math.round(LINK_PREVIEW_PAD_BOTTOM_DP * density);
                int titleMaxWidth = Math.max(1, maxTextWidth - cardPadH * 2);
                String titleSrc = !linkTitle.isEmpty() ? linkTitle : linkPreviewUrl;
                linkTitleLayout = StaticLayout.Builder
                        .obtain(titleSrc, 0, titleSrc.length(), linkTitlePaint, titleMaxWidth)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setMaxLines(2)
                        .setEllipsize(TextUtils.TruncateAt.END)
                        .setIncludePad(false)
                        .build();
                int domainHeight = 0;
                int domainGap = 0;
                if (!linkDomain.isEmpty()) {
                    Paint.FontMetrics dfm = linkDomainPaint.getFontMetrics();
                    domainHeight = Math.round(dfm.descent - dfm.ascent);
                    domainGap = Math.round(LINK_PREVIEW_TITLE_GAP_DP * density);
                }
                int thumbHeight = linkHasThumb ? Math.round(LINK_PREVIEW_THUMB_HEIGHT_DP * density) : 0;
                linkCardHeight = thumbHeight + cardPadTop + domainHeight + domainGap
                        + linkTitleLayout.getHeight() + cardPadBottom;
            } else {
                linkTitleLayout = null;
            }
            int linkCardContentWidth = hasLinkPreview ? maxTextWidth : 0;

            bubbleContentWidth = Math.min(
                    Math.max(Math.max(neededWidth, replyBoxContentWidth), linkCardContentWidth), maxTextWidth);
            bubbleHeight = replyBoxHeight + replyGap + textLayout.getHeight()
                    + linkGapTop + linkCardHeight + vPad * 2 + footerHeight;
        }

        int bubbleWidth = bubbleContentWidth + hPad * 2;

        bubbleLeft = sent ? (parentWidth - bubbleWidth) : 0;
        bubbleTop = pinnedTopExtra;
        bubbleRect.set(bubbleLeft, bubbleTop, bubbleLeft + bubbleWidth, bubbleTop + bubbleHeight);

        if (isCallEntry) {
            callEntryPillRect.set(bubbleRect);
        }

        if (hasReply) {
            int replyMargin = Math.round(REPLY_MARGIN_DP * density);
            replyBoxRect.set(
                    bubbleLeft + replyMargin,
                    bubbleTop + replyMargin,
                    bubbleLeft + bubbleWidth - replyMargin,
                    bubbleTop + replyBoxHeight - replyMargin);
        }

        if (isAudio) {
            float rowTop = bubbleTop + replyBoxHeight + replyGap + vPad;
            float btnSize = AUDIO_BTN_SIZE_DP * density;
            float rowGap = AUDIO_ROW_GAP_DP * density;
            float durWidth = AUDIO_DUR_WIDTH_DP * density;
            audioBtnRect.set(bubbleLeft + hPad, rowTop, bubbleLeft + hPad + btnSize, rowTop + btnSize);
            float waveformTop = rowTop + (btnSize - AUDIO_WAVEFORM_HEIGHT_DP * density) / 2f;
            audioWaveformRect.set(
                    audioBtnRect.right + rowGap,
                    waveformTop,
                    bubbleLeft + bubbleWidth - hPad - rowGap - durWidth,
                    waveformTop + AUDIO_WAVEFORM_HEIGHT_DP * density);
        }

        if (isMedia) {
            // Must mirror onMeasure()'s computeMediaSize() call exactly
            // (same maxWidthPx input) so mediaRect's size here matches the
            // width/height the bubble was actually measured for above.
            computeMediaSize(bubbleContentWidth, mediaSizeOut);
            int mediaW = mediaSizeOut[0];
            int mediaH = mediaSizeOut[1];
            float mediaTop = bubbleTop + replyBoxHeight + replyGap + vPad;
            mediaRect.set(bubbleLeft + hPad, mediaTop, bubbleLeft + hPad + mediaW, mediaTop + mediaH);

            if (!mediaHasCaption) {
                float pillPadH = MEDIA_PILL_PADDING_H_DP * density;
                float pillPadV = MEDIA_PILL_PADDING_V_DP * density;
                float pillMargin = MEDIA_PILL_MARGIN_DP * density;
                float pillTextW = mediaPillTextPaint.measureText(footerTimeText)
                        + (sent ? (TICK_SIZE_DP + TICK_GAP_DP) * density : 0)
                        + expiryReserveWidth();
                float pillH = spToPx(FOOTER_TEXT_SP) + pillPadV * 2;
                mediaPillRect.set(
                        mediaRect.right - pillMargin - pillTextW - pillPadH * 2,
                        mediaRect.bottom - pillMargin - pillH,
                        mediaRect.right - pillMargin,
                        mediaRect.bottom - pillMargin);
            }
        }

        if (isReelShare) {
            int cardW = Math.round(REEL_CARD_WIDTH_DP * density);
            int cardH = Math.round(REEL_CARD_HEIGHT_DP * density);
            float cardTop = bubbleTop + replyBoxHeight + replyGap + vPad;
            // Card now sits inset inside the bubble by the normal hPad/vPad,
            // same as image/media — bubbleDrawable shows as a frame around it.
            reelCardRect.set(bubbleLeft + hPad, cardTop, bubbleLeft + hPad + cardW, cardTop + cardH);

            float avatarSize = REEL_AVATAR_SIZE_DP * density;
            float headerPadH = REEL_HEADER_PAD_H_DP * density;
            float headerPadTop = REEL_HEADER_PAD_TOP_DP * density;
            reelAvatarRect.set(
                    reelCardRect.left + headerPadH, reelCardRect.top + headerPadTop,
                    reelCardRect.left + headerPadH + avatarSize, reelCardRect.top + headerPadTop + avatarSize);

            // Timestamp/tick pill — always shown (unlike the image path's
            // "!mediaHasCaption" gate), since the caption here is a
            // separate bottom-gradient overlay, not a below-the-card block.
            float pillPadH = MEDIA_PILL_PADDING_H_DP * density;
            float pillPadV = MEDIA_PILL_PADDING_V_DP * density;
            float pillMargin = MEDIA_PILL_MARGIN_DP * density;
            float pillTextW = mediaPillTextPaint.measureText(footerTimeText)
                    + (sent ? (TICK_SIZE_DP + TICK_GAP_DP) * density : 0)
                    + expiryReserveWidth();
            float pillH = spToPx(FOOTER_TEXT_SP) + pillPadV * 2;
            mediaPillRect.set(
                    reelCardRect.right - pillMargin - pillTextW - pillPadH * 2,
                    reelCardRect.bottom - pillMargin - pillH,
                    reelCardRect.right - pillMargin,
                    reelCardRect.bottom - pillMargin);
        }

        if (isContact) {
            int cardW = Math.round(CONTACT_CARD_WIDTH_DP * density);
            float topH = CONTACT_TOP_HEIGHT_DP * density;
            float dividerH = CONTACT_DIVIDER_HEIGHT_DP * density;
            float btnH = CONTACT_BUTTON_HEIGHT_DP * density;
            float cardTop = bubbleTop + replyBoxHeight + replyGap;
            // Left-aligned to the bubble's own left edge, same precedent
            // as the reel-share card (regardless of a reply box widening
            // bubbleWidth beyond cardW).
            contactCardRect.set(bubbleLeft, cardTop, bubbleLeft + cardW, cardTop + topH + dividerH + btnH);

            float avatarSize = CONTACT_AVATAR_SIZE_DP * density;
            float padH = CONTACT_PAD_H_DP * density;
            float avatarTop = contactCardRect.top + (topH - avatarSize) / 2f;
            contactAvatarRect.set(
                    contactCardRect.left + padH, avatarTop,
                    contactCardRect.left + padH + avatarSize, avatarTop + avatarSize);

            // "View Contact" row sits directly below the divider, full
            // card width — the only tappable sub-region (see onTouchEvent).
            float btnTop = contactCardRect.top + topH + dividerH;
            contactButtonRect.set(contactCardRect.left, btnTop, contactCardRect.right, btnTop + btnH);
        }

        if (isLocation) {
            int cardW = Math.round(LOCATION_CARD_WIDTH_DP * density);
            float mapH = LOCATION_MAP_HEIGHT_DP * density;
            float dividerH = LOCATION_DIVIDER_HEIGHT_DP * density;
            float btnH = LOCATION_BUTTON_HEIGHT_DP * density;
            float addrBlockH = locationAddressLayout != null
                    ? LOCATION_ADDRESS_PAD_TOP_DP * density + locationAddressLayout.getHeight()
                            + LOCATION_ADDRESS_PAD_BOTTOM_DP * density
                    : 0f;
            float cardTop = bubbleTop + replyBoxHeight + replyGap;
            // Left-aligned to the bubble's own left edge, same precedent
            // as the contact/reel-share cards.
            locationCardRect.set(bubbleLeft, cardTop, bubbleLeft + cardW,
                    cardTop + mapH + dividerH + addrBlockH + dividerH + btnH);

            locationMapRect.set(locationCardRect.left, locationCardRect.top,
                    locationCardRect.right, locationCardRect.top + mapH);

            // "Open in Maps" row sits at the very bottom of the card, full
            // card width — the only tappable sub-region (see
            // onTouchEvent), mirrors btnOpenMaps in item_msg_location.xml.
            float locBtnTop = locationCardRect.bottom - btnH;
            locationButtonRect.set(locationCardRect.left, locBtnTop, locationCardRect.right, locationCardRect.bottom);
        }

        if (isViewOnce) {
            float cardTop = bubbleTop + replyBoxHeight + replyGap;
            viewOnceCardRect.set(bubbleLeft, cardTop, bubbleLeft + bubbleWidth, bubbleTop + bubbleHeight);
        }

        if (isSeenBubble) {
            float avatarSize = SEEN_AVATAR_SIZE_DP * density;
            float avatarGap = SEEN_AVATAR_GAP_DP * density;
            float rowTop = bubbleTop + replyBoxHeight + replyGap;
            float rowH = bubbleHeight - replyBoxHeight - replyGap;

            seenAvatarRect.set(bubbleLeft, rowTop, bubbleLeft + avatarSize, rowTop + avatarSize);
            seenCardRect.set(bubbleLeft + avatarSize + avatarGap, rowTop,
                    bubbleLeft + bubbleWidth, rowTop + rowH);

            if (seenHasThumb) {
                float padH = SEEN_CARD_PAD_H_DP * density;
                float padTop = SEEN_CARD_PAD_TOP_DP * density;
                float thumbW = SEEN_THUMB_W_DP * density;
                float thumbH = SEEN_THUMB_H_DP * density;
                seenThumbRect.set(seenCardRect.left + padH, seenCardRect.top + padTop,
                        seenCardRect.left + padH + thumbW, seenCardRect.top + padTop + thumbH);
            } else {
                seenThumbRect.setEmpty();
            }
        }

        if (isMediaGroup) {
            float gridTop = bubbleTop + replyBoxHeight + replyGap + vPad;
            layoutGroupCells(bubbleLeft + hPad, gridTop);
            groupContentRect.set(bubbleLeft + hPad, gridTop,
                    bubbleLeft + bubbleWidth - hPad, bubbleTop + bubbleHeight - vPad);

            if (!groupHasCaption) {
                float pillPadH = MEDIA_PILL_PADDING_H_DP * density;
                float pillPadV = MEDIA_PILL_PADDING_V_DP * density;
                float pillMargin = MEDIA_PILL_MARGIN_DP * density;
                float pillTextW = mediaPillTextPaint.measureText(footerTimeText)
                        + (sent ? (TICK_SIZE_DP + TICK_GAP_DP) * density : 0)
                        + expiryReserveWidth();
                float pillH = spToPx(FOOTER_TEXT_SP) + pillPadV * 2;
                mediaPillRect.set(
                        groupContentRect.right - pillMargin - pillTextW - pillPadH * 2,
                        groupContentRect.bottom - pillMargin - pillH,
                        groupContentRect.right - pillMargin,
                        groupContentRect.bottom - pillMargin);
            }
        }

        if (hasLinkPreview && !isMedia && !isMediaGroup && !isReelShare && !isContact && !isLocation && textLayout != null) {
            int linkGapTop = Math.round(LINK_PREVIEW_GAP_TOP_DP * density);
            float cardTop = bubbleTop + replyBoxHeight + replyGap + textLayout.getHeight() + linkGapTop;
            linkCardRect.set(bubbleLeft + hPad, cardTop,
                    bubbleLeft + bubbleWidth - hPad, cardTop + linkCardHeight);
            if (linkHasThumb) {
                float thumbH = LINK_PREVIEW_THUMB_HEIGHT_DP * density;
                linkThumbRect.set(linkCardRect.left, linkCardRect.top,
                        linkCardRect.right, linkCardRect.top + thumbH);
            } else {
                linkThumbRect.setEmpty();
            }
        } else {
            linkCardRect.setEmpty();
            linkThumbRect.setEmpty();
        }

        // ── Reaction badge — floats past the bubble's bottom edge, so the
        // view's total height must grow to fit it or it'd be clipped by
        // this View's own canvas bounds (unlike the old ConstraintLayout,
        // which just grew wrap_content to fit an overlapping sibling). ──
        int totalHeight = bubbleTop + bubbleHeight;
        if (hasReactions) {
            float marginEnd = REACTIONS_MARGIN_END_DP * density;
            float overlap = REACTIONS_OVERLAP_DP * density;
            Paint.FontMetrics fm = reactionsTextPaint.getFontMetrics();
            float badgeW = reactionsTextPaint.measureText(reactionsText);
            float badgeH = fm.descent - fm.ascent;
            float right = bubbleRect.right - marginEnd;
            float bottom = bubbleRect.bottom + overlap;
            reactionsRect.set(right - badgeW, bottom - badgeH, right, bottom);
            totalHeight = Math.max(totalHeight, Math.round(bottom));
        } else {
            reactionsRect.setEmpty();
        }

        setMeasuredDimension(parentWidth, totalHeight);
    }

    int dp(int v) {
        return Math.round(v * density);
    }

    /**
     * Grid outer dimensions [width, height] in px for `n` visible cells,
     * following MediaGroupLayoutHelper's exact rules: 1 → wide single card,
     * 2 → side-by-side pair, 3 → wide top + 2 below, 4 → 2x2, 5-9 → 3x3
     * (rows may be partial; each row still reserves full 3-column width —
     * a deliberate simplification vs. the old WRAP_CONTENT-per-row helper,
     * traded for simpler/robust hit-testing in a Canvas-drawn grid).
     */
    private int[] computeGroupGridDims(int n) {
        int gap = dp(GROUP_GAP);
        if (n <= 0) return new int[]{0, 0};
        if (n == 1) return new int[]{dp(GROUP_SINGLE_W), dp(GROUP_SINGLE_H)};
        if (n == 2) return new int[]{dp(GROUP_PAIR_CELL) * 2 + gap, dp(GROUP_PAIR_CELL)};
        if (n == 3) return new int[]{dp(GROUP_THREE_TOP_W), dp(GROUP_THREE_TOP_H) + gap + dp(GROUP_THREE_BOT)};
        if (n == 4) return new int[]{dp(GROUP_GRID2_CELL) * 2 + gap, dp(GROUP_GRID2_CELL) * 2 + gap};
        int rows = (int) Math.ceil(n / 3f);
        return new int[]{dp(GROUP_GRID3_CELL) * 3 + gap * 2, dp(GROUP_GRID3_CELL) * rows + gap * (rows - 1)};
    }

    /** Fills groupRects[0..groupVisibleCount) with absolute on-screen cell
     *  positions, anchored at (originX, originY) = top-left of the grid area. */
    private void layoutGroupCells(float originX, float originY) {
        int n = groupVisibleCount;
        int gap = dp(GROUP_GAP);
        if (n <= 0) return;
        if (n == 1) {
            groupRects[0].set(originX, originY, originX + dp(GROUP_SINGLE_W), originY + dp(GROUP_SINGLE_H));
        } else if (n == 2) {
            int c = dp(GROUP_PAIR_CELL);
            groupRects[0].set(originX, originY, originX + c, originY + c);
            groupRects[1].set(originX + c + gap, originY, originX + c + gap + c, originY + c);
        } else if (n == 3) {
            int tw = dp(GROUP_THREE_TOP_W), th = dp(GROUP_THREE_TOP_H), bc = dp(GROUP_THREE_BOT);
            groupRects[0].set(originX, originY, originX + tw, originY + th);
            float rowTop = originY + th + gap;
            groupRects[1].set(originX, rowTop, originX + bc, rowTop + bc);
            groupRects[2].set(originX + bc + gap, rowTop, originX + bc + gap + bc, rowTop + bc);
        } else if (n == 4) {
            int c = dp(GROUP_GRID2_CELL);
            for (int i = 0; i < 4; i++) {
                int row = i / 2, col = i % 2;
                float left = originX + col * (c + gap);
                float top  = originY + row * (c + gap);
                groupRects[i].set(left, top, left + c, top + c);
            }
        } else {
            int c = dp(GROUP_GRID3_CELL);
            for (int i = 0; i < n; i++) {
                int row = i / 3, col = i % 3;
                float left = originX + col * (c + gap);
                float top  = originY + row * (c + gap);
                groupRects[i].set(left, top, left + c, top + c);
            }
        }
    }

    private static int maxLineWidth(StaticLayout layout) {
        int w = 0;
        for (int i = 0; i < layout.getLineCount(); i++) {
            w = Math.max(w, (int) Math.ceil(layout.getLineWidth(i)));
        }
        return w;
    }

    // ── PERF #5: Intercept ALL invalidation paths so the full-bubble cache
    // is marked stale automatically by any content-changing setter or bind.
    // No per-method dirty flags needed — everything routes through here.
    @Override
    public void invalidate() {
        fullBubbleDirty = true;
        super.invalidate();
    }
    @Override
    public void postInvalidateOnAnimation() {
        fullBubbleDirty = true;
        super.postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // Guard: content we need to draw must exist
        if (!isReelShare && !isContact && !isLocation && !isFileBubble && !isViewOnce && !isSeenBubble && !isCallEntry) {
            if (bubbleDrawable == null) return;
            if (!isMedia && !isMediaGroup && !isAudio && !isPoll && textLayout == null) return;
        }

        // PERF #5: Full-bubble Picture cache.
        // Bypass for animation cases that redraw every frame:
        //   • indeterminate download/upload spinner (handled inside
        //     drawMediaWithOptionalCache via the nested cachedMediaPicture)
        //   • audio waveform progress bar (~60fps redraws during playback)
        boolean indeterminate = isMedia && mediaGated && mediaDownloading && mediaDownloadProgress < 0;
        boolean skipFullCache = isAudio || indeterminate;

        int w = getWidth(), h = getHeight();
        if (!skipFullCache && w > 0 && h > 0) {
            if (!fullBubbleDirty && fullBubblePicture != null
                    && w == fullBubblePictureW && h == fullBubblePictureH) {
                // Cache hit: replay the recorded Picture — ~0 CPU cost
                canvas.drawPicture(fullBubblePicture);
                return;
            }
            // Cache miss or stale: record fresh
            if (fullBubblePicture == null) fullBubblePicture = new android.graphics.Picture();
            Canvas pc = fullBubblePicture.beginRecording(w, h);
            drawBubbleContent(pc);
            fullBubblePicture.endRecording();
            fullBubbleDirty    = false;
            fullBubblePictureW = w;
            fullBubblePictureH = h;
            canvas.drawPicture(fullBubblePicture);
            return;
        }
        // Animated path — draw directly, no caching
        drawBubbleContent(canvas);
    }

    /** All bubble drawing logic, called from onDraw. Extracted so we can draw
     *  into either a Picture-recording canvas or the real canvas. */
    private void drawBubbleContent(Canvas canvas) {
        if (!isContact && !isLocation && !isViewOnce && !isSeenBubble && !isCallEntry) {
            // Contact and location cards are bubbleless again (matches
            // WhatsApp/Instagram — the card itself is the full visual,
            // an extra bubble frame just adds wasted padding). View-once
            // (own per-variant solid colour, self-painted), seen-
            // notification, and call-entry stay bubbleless too — system-
            // style pills/cards, not regular message content.
            // Reel-share and file still draw the normal bubble behind them
            // (file was a genuine missing-background bug fix).
            bubbleDrawable.setBounds(
                    (int) bubbleRect.left, (int) bubbleRect.top,
                    (int) bubbleRect.right, (int) bubbleRect.bottom);
            bubbleDrawable.draw(canvas);
        }

        if (isPinned) {
            drawPinnedLabel(canvas);
        }

        if (hasGroupSender) {
            drawGroupSenderName(canvas);
        }

        if (hasForwarded) {
            drawForwardedLabel(canvas);
        }

        if (showForwardBtn) {
            drawForwardButton(canvas);
        }

        int hPad = Math.round(H_PADDING_DP * density);
        int vPad = Math.round(V_PADDING_DP * density);

        if (hasReply) {
            drawReplyPreview(canvas);
        }

        if (isReelShare) {
            reelShareRenderer.draw(canvas);
        } else if (isContact) {
            contactRenderer.draw(canvas);
        } else if (isLocation) {
            locationRenderer.draw(canvas);
        } else if (isViewOnce) {
            viewOnceRenderer.draw(canvas);
        } else if (isSeenBubble) {
            seenBubbleRenderer.draw(canvas);
        } else if (isCallEntry) {
            drawCallEntry(canvas);
        } else if (isPoll) {
            pollRenderer.draw(canvas);
        } else if (isMediaGroup) {
            mediaGroupRenderer.draw(canvas);
        } else if (isMedia) {
            drawMediaWithOptionalCache(canvas, hPad, vPad);
        } else if (isAudio) {
            audioRenderer.draw(canvas, hPad, vPad);
        } else if (isFileBubble) {
            fileBubbleRenderer.draw(canvas);
        } else {
            int replyGap = hasReply ? Math.round(REPLY_GAP_TO_MESSAGE_DP * density) : 0;
            canvas.save();
            canvas.translate(bubbleLeft + hPad, bubbleTop + replyBoxHeight + replyGap + vPad);
            drawSearchHighlight(canvas);
            textLayout.draw(canvas);
            canvas.restore();

            if (hasLinkPreview) {
                linkPreviewRenderer.draw(canvas);
            }

            drawFooter(canvas, bubbleRect.bottom - vPad * 0.4f, bubbleRect.right - hPad);
        }

        if (hasReactions) {
            drawReactionsBadge(canvas);
        }
    }

    private void drawReactionsBadge(Canvas canvas) {
        // PERF ADV: reuse cached FontMetrics — getFontMetrics() allocates a new
        // object on every call, adding GC pressure at 60fps during scroll.
        if (reactionsTextFM == null) reactionsTextFM = reactionsTextPaint.getFontMetrics();
        float baselineY = reactionsRect.bottom - reactionsTextFM.descent;
        canvas.drawText(reactionsText, reactionsRect.left, baselineY, reactionsTextPaint);
    }

    private void drawPinnedLabel(Canvas canvas) {
        // Sits in the [0, bubbleTop) strip reserved above the bubble in
        // onMeasure, right-aligned to the bubble's own right edge (works
        // for sent AND received, since each bubble's own right edge is
        // used rather than the parent row's right edge).
        canvas.drawText(PINNED_LABEL_TEXT, bubbleRect.right - pinnedLabelWidth, pinnedBaselineY, pinnedLabelPaint);
    }

    private void drawGroupSenderName(Canvas canvas) {
        // Same reserved row as the pinned label, but left-aligned to the
        // bubble's own left edge (opposite corner) — received bubbles
        // start at bubbleLeft=0, so this sits at the row's start, matching
        // tv_sender_name's constraintStart_toEndOf the (unused) avatar.
        // Also used for the 📢 broadcast badge — the caller composes
        // whichever string applies (see setGroupSender()'s doc).
        canvas.drawText(groupSenderName, bubbleRect.left, groupSenderBaselineY, groupSenderPaint);
    }

    private void drawForwardedLabel(Canvas canvas) {
        // Own row directly below row 1 (pinned/group-sender), left-aligned
        // to the bubble's own left edge — mirrors tv_forwarded's
        // constraintTop_toBottomOf tv_sender_name + constraintStart_toEndOf
        // the (unused) avatar in item_message_received.xml.
        canvas.drawText(forwardedText, bubbleRect.left, forwardedBaselineY, forwardedPaint);
    }

    /**
     * Draws the quick-forward icon button in the gutter just outside the
     * bubble — LEFT of a sent bubble, RIGHT of a received one, vertically
     * centered against bubbleRect — mirroring legacy btn_quick_forward's
     * layout_constraintEnd_toStartOf/layout_constraintStart_toEndOf
     * @id/ll_bubble + top/bottom-constrained-to-ll_bubble centering.
     * Recomputed from the current bubbleRect on every draw (rather than
     * only in onMeasure) so it always tracks the latest completed layout
     * pass regardless of whether THIS particular bind triggered a fresh
     * relayout (see requestLayoutIfSizeChanged()'s doc — a same-size
     * rebind skips onMeasure entirely, and this button's visibility can
     * change independently of bubble size).
     */
    private void drawForwardButton(Canvas canvas) {
        float btnSize = FORWARD_BTN_SIZE_DP * density;
        float btnMargin = FORWARD_BTN_MARGIN_DP * density;
        float cy = bubbleRect.top + bubbleRect.height() / 2f;
        if (sent) {
            float right = bubbleRect.left - btnMargin;
            forwardBtnRect.set(right - btnSize, cy - btnSize / 2f, right, cy + btnSize / 2f);
        } else {
            float left = bubbleRect.right + btnMargin;
            forwardBtnRect.set(left, cy - btnSize / 2f, left + btnSize, cy + btnSize / 2f);
        }

        float cx = forwardBtnRect.centerX();
        float r = forwardBtnRect.width() / 2f;
        canvas.drawCircle(cx, cy, r, forwardBtnBgPaint);

        // Double-chevron "forward" glyph (»), same visual family as the
        // legacy ic_forward_msg drawable — always points the same
        // direction regardless of which side the button sits on, since
        // forwarding isn't spatially tied to sent/received.
        float w = r * 0.9f, h = r * 0.95f;
        forwardIconPath.reset();
        forwardIconPath.moveTo(cx - w * 0.6f, cy - h * 0.5f);
        forwardIconPath.lineTo(cx - w * 0.1f, cy);
        forwardIconPath.lineTo(cx - w * 0.6f, cy + h * 0.5f);
        forwardIconPath.moveTo(cx, cy - h * 0.5f);
        forwardIconPath.lineTo(cx + w * 0.5f, cy);
        forwardIconPath.lineTo(cx, cy + h * 0.5f);
        canvas.drawPath(forwardIconPath, forwardBtnIconPaint);
    }

    // Width to reserve in the footer row for the "⏳ mm:ss" expiry countdown
    // text (measured + a small gap), drawn just to the left of the
    // timestamp. Returns 0 when there's no expiry to show so callers can
    // add it unconditionally without an extra hasExpiry check.
    float expiryReserveWidth() {
        if (!hasExpiry || expiryText == null || expiryText.isEmpty()) return 0f;
        return expiryPaint.measureText(expiryText) + EXPIRY_GAP_DP * density;
    }

    /**
     * Builds a compact key from exactly the inputs onMeasure() reads to
     * size the bubble (bubbleContentWidth/bubbleHeight) plus the couple of
     * measure-time position rects (mediaPillRect for a captionless
     * media/media-group/reel corner pill) that don't get a chance to
     * recompute unless onMeasure() actually runs. Two binds/updates that
     * produce the same key are guaranteed to lay out identically, so
     * requestLayoutIfSizeChanged() can safely skip requestLayout() between
     * them.
     *
     * Deliberately mirrors the isMedia/isReelShare/.../else if-chain in
     * onMeasure() — each branch includes only the fields that branch
     * actually reads. Per-tick/per-vote fields that never affect layout
     * (audioProgress, pollCounts/pollMyVote/pollIsLeader, mediaDownload*,
     * etc.) are intentionally left out so e.g. a poll vote or an audio
     * playback tick doesn't churn this key.
     *
     * The footer/expiry reserve is folded in as a *rounded pixel width*
     * rather than the raw text — "0:59" -> "0:58" re-measures to the same
     * width almost always (tabular digits), so this stays stable tick to
     * tick and only actually changes (forcing a relayout) on the rarer
     * digit-count flip, e.g. "9:59" -> "10:00".
     */
    private String computeSizeSignature() {
        float footerReserve = footerPaint.measureText(footerTimeText)
                + (sent ? (TICK_SIZE_DP + TICK_GAP_DP) * density : 0)
                + FOOTER_GAP_DP * density
                + expiryReserveWidth();

        StringBuilder sb = new StringBuilder(96);
        sb.append(sent ? '1' : '0').append(isPinned ? 'P' : '_');
        if (hasGroupSender) sb.append("|G").append(groupSenderName);
        if (hasForwarded) sb.append("|F").append(forwardedText);
        if (hasReply) {
            sb.append("|R").append(replySenderName).append('\u0001').append(replyText)
                    .append('\u0001').append(replyThumb != null ? '1' : '0');
        }
        sb.append("|fr").append(Math.round(footerReserve));
        // Reaction badge floats past the bubble's bottom edge and grows
        // the view's total measured height to fit it (see the
        // "totalHeight"/setMeasuredDimension tail of onMeasure()) — not
        // just a draw-time overlay, so it must be part of the key.
        if (hasReactions) sb.append("|X").append(reactionsText);

        if (isMedia) {
            // mediaAspectRatio must be part of the key: it starts at 0f
            // (unknown, square placeholder) and setMediaBitmap() updates it
            // in place once the real Bitmap decodes, without any other
            // field in this signature changing — omitting it here would
            // make requestLayoutIfSizeChanged() wrongly think nothing
            // changed and skip the relayout that grows/shrinks the bubble
            // from the placeholder square to its real WhatsApp-style size.
            sb.append("|M").append(mediaHasCaption ? messageText : "")
                    .append('\u0001').append(Math.round(mediaAspectRatio * 1000));
        } else if (isReelShare) {
            sb.append("|RE").append(reelHasCaption ? reelCaptionText : "");
        } else if (isContact) {
            sb.append("|C");
        } else if (isLocation) {
            sb.append("|L").append(locationAddress);
        } else if (isViewOnce) {
            sb.append("|V").append(viewOnceVariant).append('\u0001')
                    .append(viewOnceShowOpenedAt ? '1' : '0').append('\u0001').append(viewOnceOpenedAtText);
        } else if (isSeenBubble) {
            sb.append("|S").append(seenHasThumb ? '1' : '0').append(seenHasName ? '1' : '0');
        } else if (isCallEntry) {
            sb.append("|CE").append(callEntryIcon).append('\u0001')
                    .append(callEntryLabel).append('\u0001').append(callEntryTime);
        } else if (isPoll) {
            sb.append("|PL").append(pollQuestion);
            if (pollOptions != null) {
                for (String opt : pollOptions) sb.append('\u0001').append(opt);
            }
        } else if (isMediaGroup) {
            sb.append("|MG").append(groupVisibleCount).append('\u0001')
                    .append(groupHasCaption ? messageText : "");
        } else if (isFileBubble) {
            sb.append("|FB");
        } else if (isAudio) {
            sb.append("|A");
        } else {
            // isDeletedStyle switches textPaint to an italic typeface (see
            // setDeletedStyle()), which re-measures the same messageText
            // to a slightly different width, so it has to be part of the
            // key even though the string itself didn't change.
            sb.append("|T").append(isDeletedStyle ? '1' : '0').append(messageText);
            if (hasLinkPreview) {
                sb.append("|LP").append(linkTitle).append('\u0001')
                        .append(linkPreviewUrl).append('\u0001')
                        .append(linkDomain).append('\u0001').append(linkHasThumb ? '1' : '0');
            }
        }
        return sb.toString();
    }

    /**
     * Replaces a bare requestLayout() call in every bind()/set() method:
     * only actually requests a new measure/layout pass when the bubble's
     * size-relevant content has changed since the last one (see
     * computeSizeSignature()). Callers still invalidate() unconditionally
     * right after this so the new content — even content that doesn't
     * change the bubble's size, like a poll vote or a same-width expiry
     * tick — still gets drawn.
     *
     * Returns whether a relayout was actually requested. Callers MUST use
     * this to decide whether it's safe to null out a cached StaticLayout
     * field (textLayout, replySenderLayout, pollQuestionLayout, etc.) —
     * those are only rebuilt inside onMeasure(), so nulling one while this
     * returns false would leave onDraw() dereferencing a stale null on a
     * bubble whose measure pass never comes (this was gap #5's regression:
     * messages intermittently rendering blank on a same-size rebind).
     */
    private boolean requestLayoutIfSizeChanged() {
        String sig = computeSizeSignature();
        boolean changed = lastSizeSignature == null || getMeasuredWidth() == 0 || !lastSizeSignature.equals(sig);
        if (changed) {
            requestLayout();
        }
        lastSizeSignature = sig;
        return changed;
    }

    void drawFooter(Canvas canvas, float footerBaselineY, float footerRightX) {
        float timeX = footerRightX - footerPaint.measureText(footerTimeText)
                - (sent ? (TICK_SIZE_DP + TICK_GAP_DP) * density : 0);
        canvas.drawText(footerTimeText, timeX, footerBaselineY, footerPaint);

        // Record the footer text's bounding box so onTouchEvent can hit-test
        // taps on the "✏️ edited" tag (baked into footerTimeText by the
        // caller) — recomputed on every draw so it always tracks the
        // footer's actual on-screen position for this bind.
        Paint.FontMetrics footerFm = footerPaint.getFontMetrics();
        footerTextRect.set(timeX, footerBaselineY + footerFm.ascent,
                timeX + footerPaint.measureText(footerTimeText), footerBaselineY + footerFm.descent);

        if (hasExpiry) {
            canvas.drawText(expiryText, timeX - expiryReserveWidth(), footerBaselineY, expiryPaint);
        }

        if (sent) {
            drawTick(canvas, footerRightX - TICK_SIZE_DP * density, footerBaselineY);
        }
    }

    /**
     * Small floating "⏳ mm:ss" badge for cards that have no regular
     * timestamp/tick footer row (contact/location) — pinned inside the
     * given rect's top-end corner with a small inset, dark rounded
     * background + expiryPaint text, same countdown string setExpiryText()
     * already feeds every other footer/pill. No-op when there's nothing to
     * show, so callers can call this unconditionally right after
     * canvas.restore() when hasExpiry is true.
     */
    void drawCornerExpiryPill(Canvas canvas, RectF anchorRect) {
        if (!hasExpiry || expiryText == null || expiryText.isEmpty()) return;
        float padH = 6f * density, padV = 3f * density, inset = 6f * density;
        Paint.FontMetrics efm = expiryPaint.getFontMetrics();
        float textW = expiryPaint.measureText(expiryText);
        float pillH = (efm.descent - efm.ascent) + padV * 2;
        float pillW = textW + padH * 2;
        float right = anchorRect.right - inset;
        float top = anchorRect.top + inset;
        cornerExpiryPillRect.set(right - pillW, top, right, top + pillH);
        canvas.drawRoundRect(cornerExpiryPillRect, pillH / 2f, pillH / 2f, mediaPillBgPaint);
        float baseline = cornerExpiryPillRect.centerY() - (efm.ascent + efm.descent) / 2f;
        canvas.drawText(expiryText, cornerExpiryPillRect.left + padH, baseline, expiryPaint);
    }

    private void drawCallEntry(Canvas canvas) {
        float r = SEEN_CARD_CORNER_DP * density;
        canvas.drawRoundRect(callEntryPillRect, r, r, callEntryBgPaint);

        float padH = CALL_ENTRY_PAD_H_DP * density;
        float gap  = CALL_ENTRY_ICON_LABEL_GAP_DP * density;
        float left = callEntryPillRect.left + padH;

        Paint.FontMetrics cifm = callEntryIconPaint.getFontMetrics();
        Paint.FontMetrics clfm = callEntryLabelPaint.getFontMetrics();
        Paint.FontMetrics cdfm = callEntryDotPaint.getFontMetrics();
        Paint.FontMetrics ctfm = callEntryTimePaint.getFontMetrics();
        float rowCenterY = callEntryPillRect.centerY();

        float x = left;
        canvas.drawText(callEntryIcon, x, rowCenterY - (cifm.ascent + cifm.descent) / 2f, callEntryIconPaint);
        x += callEntryIconPaint.measureText(callEntryIcon) + gap;
        canvas.drawText(callEntryLabel, x, rowCenterY - (clfm.ascent + clfm.descent) / 2f, callEntryLabelPaint);
        x += callEntryLabelPaint.measureText(callEntryLabel);
        canvas.drawText(CALL_ENTRY_DOT_TEXT, x, rowCenterY - (cdfm.ascent + cdfm.descent) / 2f, callEntryDotPaint);
        x += callEntryDotPaint.measureText(CALL_ENTRY_DOT_TEXT);
        canvas.drawText(callEntryTime, x, rowCenterY - (ctfm.ascent + ctfm.descent) / 2f, callEntryTimePaint);
    }

    private void drawReplyPreview(Canvas canvas) {
        float r = REPLY_CORNER_RADIUS_DP * density;
        canvas.drawRoundRect(replyBoxRect, r, r, replyBgPaint);

        int replyBar = Math.round(REPLY_BAR_WIDTH_DP * density);
        canvas.drawRect(replyBoxRect.left, replyBoxRect.top,
                replyBoxRect.left + replyBar, replyBoxRect.bottom, replyBarPaint);

        int replyPadH = Math.round(REPLY_PADDING_H_DP * density);
        int replyPadV = Math.round(REPLY_PADDING_V_DP * density);
        float textColLeft = replyBoxRect.left + replyBar + replyPadH;
        float textColTop = replyBoxRect.top
                + (replyBoxRect.height() - (replySenderLayout.getHeight() + replyTextLayout.getHeight())) / 2f;

        canvas.save();
        canvas.translate(textColLeft, Math.max(textColTop, replyBoxRect.top + replyPadV));
        if (replySenderLayout != null) {
            replySenderLayout.draw(canvas);
            canvas.translate(0, replySenderLayout.getHeight());
        }
        if (replyTextLayout != null) {
            replyTextLayout.draw(canvas);
        }
        canvas.restore();

        if (replyThumb != null) {
            int thumbSize = Math.round(REPLY_THUMB_SIZE_DP * density);
            int thumbMargin = Math.round(REPLY_THUMB_MARGIN_DP * density);
            float thumbLeft = replyBoxRect.right - thumbMargin - thumbSize;
            float thumbTop = replyBoxRect.top + (replyBoxRect.height() - thumbSize) / 2f;
            replyThumbSrcRect.set(0, 0, replyThumb.getWidth(), replyThumb.getHeight());
            replyThumbDstRect.set(thumbLeft, thumbTop, thumbLeft + thumbSize, thumbTop + thumbSize);
            canvas.drawBitmap(replyThumb, replyThumbSrcRect, replyThumbDstRect, null);
        }
    }

    final RectF gateIconArcRect = new RectF();

    /**
     * Draws the IDLE download-gate glyph at (cx, cy) sized to `size`: a
     * simple download arrow (vertical stroke + arrowhead + tray). Used for
     * the "tap to download" state, before anything is in flight. Once a
     * download starts, drawProgressRing() takes over instead (live
     * spinner/percentage) — see setMediaDownloadGate()/setGroupCellProgress().
     */
    void drawGateIcon(Canvas canvas, float cx, float cy, float size, Paint paint) {
        float r = size / 2f;
        float shaftTop = cy - r;
        float shaftBottom = cy + r * 0.25f;
        canvas.drawLine(cx, shaftTop, cx, shaftBottom, paint);
        canvas.drawLine(cx - r * 0.5f, shaftBottom - r * 0.5f, cx, shaftBottom, paint);
        canvas.drawLine(cx + r * 0.5f, shaftBottom - r * 0.5f, cx, shaftBottom, paint);
        canvas.drawLine(cx - r, cy + r, cx + r, cy + r, paint);
    }

    /**
     * Live download-progress ring — replaces the old static partial-arc
     * "spinner" with one driven by real progress. percent >= 0 draws a
     * determinate clockwise arc from 12 o'clock (e.g. 42% ⇒ ~151° swept);
     * percent < 0 (no progress reported yet) draws a short arc that spins
     * continuously based on wall-clock time, requesting the next frame via
     * postInvalidateOnAnimation() — the standard technique for an
     * indeterminate spinner on a custom View without a Handler/ValueAnimator.
     */
    /**
     * PERF: the indeterminate spinner's postInvalidateOnAnimation() forces a
     * *full* onDraw() re-execution of the whole bubble (grid cells, borders,
     * captions — everything, not just this arc) at up to 60fps for as long
     * as a download/upload's progress is unknown. Investigated caching this
     * bubble's static content to a Picture so only the arc redraws live, but
     * MediaGroupRenderer/MediaRenderer/FileBubbleRenderer would each need
     * splitting into "static part" + "spinner part" — a real refactor across
     * three renderer classes that draw the spinner interleaved with their
     * normal content, not as a separable last step. That's not something to
     * do blind without a build to verify against, and the most expensive
     * piece per redraw (BitmapShader/gradient construction) is already
     * cached at the renderer level per the class javadocs, so the remaining
     * win is smaller than a full cache would suggest.
     *
     * What's safe and still real: a spinner doesn't need 60 redraws/sec to
     * read as smooth — 30fps is visually indistinguishable for a simple
     * rotating arc. This field throttles the full-bubble invalidate to
     * ~30fps, halving how often every other draw call in the bubble
     * (shaders, captions, borders, all of it) gets re-issued while a
     * download/upload of unknown progress is in flight. Per-instance (not
     * static) since each bubble's spinner phase is independent.
     */
    private long lastIndeterminateInvalidateUptimeMs = 0L;
    private static final long INDETERMINATE_INVALIDATE_MIN_INTERVAL_MS = 32L; // ~30fps

    void drawProgressRing(Canvas canvas, float cx, float cy, float size, Paint paint, int percent) {
        float r = size / 2f;
        gateIconArcRect.set(cx - r, cy - r, cx + r, cy + r);
        if (percent >= 0) {
            canvas.drawArc(gateIconArcRect, -90, 360f * (Math.min(percent, 100) / 100f), false, paint);
        } else {
            long now = android.os.SystemClock.uptimeMillis();
            float rotation = (now % INDETERMINATE_PERIOD_MS) / (float) INDETERMINATE_PERIOD_MS * 360f;
            canvas.drawArc(gateIconArcRect, rotation - 90, INDETERMINATE_SWEEP_DEG, false, paint);
            long elapsed = now - lastIndeterminateInvalidateUptimeMs;
            if (elapsed >= INDETERMINATE_INVALIDATE_MIN_INTERVAL_MS) {
                lastIndeterminateInvalidateUptimeMs = now;
                postInvalidateOnAnimation();
            } else {
                // Not enough time has passed since the last full-bubble
                // redraw — schedule the next one for exactly when the
                // throttle window ends, instead of every vsync (~16ms).
                postInvalidateDelayed(INDETERMINATE_INVALIDATE_MIN_INTERVAL_MS - elapsed);
            }
        }
    }

    void drawTick(Canvas canvas, float x, float baselineY) {
        // Simple two-stroke check mark; double check mark for delivered/read.
        float size = TICK_SIZE_DP * density;
        float y = baselineY - size * 0.4f;
        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeWidth(density * 1.2f);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);

        drawSingleTick(canvas, x, y, size);
        if (delivered || read) {
            drawSingleTick(canvas, x + size * 0.35f, y, size);
        }
    }

    private void drawSingleTick(Canvas canvas, float x, float y, float size) {
        canvas.drawLine(x, y + size * 0.5f, x + size * 0.35f, y + size * 0.8f, tickPaint);
        canvas.drawLine(x + size * 0.35f, y + size * 0.8f, x + size, y + size * 0.1f, tickPaint);
    }

    /**
     * The rect-specific branches below (reply box, link card, reactions,
     * audio buttons, file/gif/media taps, poll options, contact/location
     * buttons, media-group cells) intercept and consume a specific
     * MotionEvent directly, WITHOUT ever feeding it to gestureDetector.
     * The matching ACTION_DOWN for that same tap, though, almost always
     * DID reach gestureDetector first (none of these branches match on
     * DOWN, only UP — the lone exception being the audio-waveform
     * scrub), which means gestureDetector already scheduled its internal
     * long-press timer. If the ACTION_UP that follows is swallowed here
     * instead of reaching gestureDetector, that timer is never cancelled
     * and onLongPress() fires ~500ms later — ON TOP OF the click action
     * we just fired — which is exactly the "select fires along with the
     * click on non-text bubbles" bug. Feeding a synthetic ACTION_CANCEL
     * into gestureDetector resets its internal state and kills that
     * pending timer without triggering any click/tap callback itself.
     */
    private void cancelPendingLongPress(MotionEvent source) {
        MotionEvent cancel = MotionEvent.obtain(source);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        gestureDetector.onTouchEvent(cancel);
        cancel.recycle();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (showForwardBtn && event.getActionMasked() == MotionEvent.ACTION_UP
                && forwardBtnRect.contains(event.getX(), event.getY())) {
            cancelPendingLongPress(event);
            if (clickListener != null) clickListener.onForwardClick();
            return true;
        }
        if (isEdited && event.getActionMasked() == MotionEvent.ACTION_UP
                && footerTextRect.contains(event.getX(), event.getY())) {
            // Tapped the "✏️ edited" tag inside the footer timestamp —
            // mirrors the legacy tv_time click listener that opened the
            // edit-history sheet.
            cancelPendingLongPress(event);
            if (clickListener != null) clickListener.onEditedTagClick();
            return true;
        }
        if (hasReply && event.getActionMasked() == MotionEvent.ACTION_UP
                && replyBoxRect.contains(event.getX(), event.getY())) {
            cancelPendingLongPress(event);
            if (clickListener != null) clickListener.onReplyPreviewClick();
            return true;
        }
        if (hasLinkPreview && event.getActionMasked() == MotionEvent.ACTION_UP
                && linkCardRect.contains(event.getX(), event.getY())) {
            // Whole card opens the link — mirrors the legacy
            // ll_link_preview.setOnClickListener (ACTION_VIEW browser intent).
            cancelPendingLongPress(event);
            if (clickListener != null) clickListener.onLinkClick(linkPreviewUrl);
            return true;
        }
        if (hasReactions && event.getActionMasked() == MotionEvent.ACTION_UP
                && reactionsRect.contains(event.getX(), event.getY())) {
            cancelPendingLongPress(event);
            if (clickListener != null) clickListener.onReactionsClick();
            return true;
        }
        if (isAudio) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP && audioBtnRect.contains(event.getX(), event.getY())) {
                cancelPendingLongPress(event);
                if (clickListener != null) clickListener.onAudioPlayPauseClick();
                return true;
            }
            // Scrub the waveform like AudioWaveformView.onTouchEvent — live
            // drag, not just a tap-to-seek, so DOWN and MOVE both count.
            if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE)
                    && audioWaveformRect.contains(event.getX(), event.getY())) {
                cancelPendingLongPress(event);
                float fraction = Math.max(0f, Math.min(1f,
                        (event.getX() - audioWaveformRect.left) / audioWaveformRect.width()));
                setAudioProgress(fraction);
                if (clickListener != null) clickListener.onAudioSeek(fraction);
                return true;
            }
            if (action == MotionEvent.ACTION_UP && audioWaveformRect.contains(event.getX(), event.getY())) {
                cancelPendingLongPress(event);
                return true; // already handled by the DOWN/MOVE branch above
            }
        }
        // ── File bubble action-button tap ────────────────────────────────────
        if (isFileBubble && event.getActionMasked() == MotionEvent.ACTION_UP
                && fileActionRect.contains(event.getX(), event.getY())) {
            cancelPendingLongPress(event);
            if (clickListener != null) {
                if (fileIsCached) {
                    clickListener.onFileOpenClick();
                } else if (!fileIsDownloading) {
                    clickListener.onFileDownloadClick();
                }
            }
            return true;
        }
        // ── GIF tap — whole image opens the GIF viewer ───────────────────────
        if (isGifBubble && !mediaGated && event.getActionMasked() == MotionEvent.ACTION_UP
                && mediaRect.contains(event.getX(), event.getY())) {
            cancelPendingLongPress(event);
            if (clickListener != null) clickListener.onGifClick();
            return true;
        }
        if (isMedia && event.getActionMasked() == MotionEvent.ACTION_UP
                && mediaRect.contains(event.getX(), event.getY())) {
            cancelPendingLongPress(event);
            if (mediaGated) {
                // Idle pill → start the download; already-in-flight → swallow
                // the tap (same "ignore while downloading" precedent the
                // group-cell gate uses).
                if (!mediaDownloading && clickListener != null) clickListener.onMediaDownloadClick();
            } else if (clickListener != null) {
                clickListener.onImageClick();
            }
            return true;
        }
        if (isReelShare && event.getActionMasked() == MotionEvent.ACTION_UP
                && reelCardRect.contains(event.getX(), event.getY())) {
            // Whole card opens the reel — mirrors the legacy
            // ll_reel_share.setOnClickListener; there's no separate
            // download-gate mode for reel cards, so this always fires.
            cancelPendingLongPress(event);
            if (clickListener != null) clickListener.onImageClick();
            return true;
        }
        if (isContact && event.getActionMasked() == MotionEvent.ACTION_UP
                && contactButtonRect.contains(event.getX(), event.getY())) {
            // Only the "View Contact" row is clickable — mirrors the
            // legacy btnViewContact.setOnClickListener; the rest of the
            // card (ll_contact_card itself) has no click listener in the
            // legacy path either, just long-press for the action sheet
            // (handled below by gestureDetector, same as every other mode).
            cancelPendingLongPress(event);
            if (clickListener != null) clickListener.onContactViewClick();
            return true;
        }
        if (isPoll && event.getActionMasked() == MotionEvent.ACTION_UP) {
            float ex = event.getX(), ey = event.getY();
            for (int i = 0; i < pollOptionRects.size(); i++) {
                if (pollOptionRects.get(i).contains(ex, ey)) {
                    cancelPendingLongPress(event);
                    if (clickListener != null) clickListener.onPollOptionClick(i);
                    return true;
                }
            }
        }
        if (isLocation && event.getActionMasked() == MotionEvent.ACTION_UP
                && locationButtonRect.contains(event.getX(), event.getY())) {
            // Only the "Open in Maps" row is clickable — mirrors the
            // legacy btnOpenMaps.setOnClickListener; the rest of the card
            // has no click listener in the legacy path either, just
            // long-press for the action sheet (handled below by
            // gestureDetector, same as every other mode).
            cancelPendingLongPress(event);
            if (clickListener != null) clickListener.onLocationOpenMapsClick();
            return true;
        }
        if (isViewOnce && event.getActionMasked() == MotionEvent.ACTION_UP
                && viewOnceCardRect.contains(event.getX(), event.getY())) {
            // Whole card is clickable for every variant — mirrors the
            // legacy ll_bubble.setOnClickListener; the caller decides
            // (via onViewOnceClick()) whether tapping WAITING/EXPIRED
            // should actually do anything (legacy left those listeners
            // null / a no-op toast).
            cancelPendingLongPress(event);
            if (clickListener != null) clickListener.onViewOnceClick();
            return true;
        }
        if (isSeenBubble && event.getActionMasked() == MotionEvent.ACTION_UP
                && (seenCardRect.contains(event.getX(), event.getY())
                    || seenAvatarRect.contains(event.getX(), event.getY()))) {
            // Whole card (and the avatar) open the reel/status viewer —
            // mirrors the legacy ll_bubble / fl_reel_seen_thumb /
            // fl_status_seen_thumb click listeners, which all fire the
            // same action.
            cancelPendingLongPress(event);
            if (clickListener != null) clickListener.onSeenBubbleClick();
            return true;
        }
        if (isMediaGroup && event.getActionMasked() == MotionEvent.ACTION_UP) {
            float x = event.getX(), y = event.getY();
            if (groupGateActive) {
                // Master pill swallows every tap in the grid area, exactly
                // like the old View-based overlay sitting on top of the
                // whole wrapper. Dismiss it locally right away (same
                // synchronous removeView() the old helper did) — the
                // adapter's onGroupDownloadAllClick() then kicks off each
                // pending cell's actual download.
                if (groupContentRect.contains(x, y)) {
                    cancelPendingLongPress(event);
                    groupGateActive = false;
                    invalidate();
                    if (clickListener != null) clickListener.onGroupDownloadAllClick();
                    return true;
                }
            } else {
                for (int i = 0; i < groupVisibleCount; i++) {
                    if (groupRects[i].contains(x, y)) {
                        cancelPendingLongPress(event);
                        boolean pending = i < groupCellPending.length && groupCellPending[i];
                        boolean downloading = i < groupCellDownloading.length && groupCellDownloading[i];
                        if (pending && !downloading) {
                            if (clickListener != null) clickListener.onGroupCellDownloadClick(i);
                        } else if (!pending) {
                            if (clickListener != null) clickListener.onMediaCellClick(i);
                        }
                        // pending && downloading → ignore tap, matches the
                        // old per-cell overlay disabling clicks in-flight.
                        return true;
                    }
                }
            }
        }
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
    }

    float spToPx(float sp) {
        return sp * getContext().getResources().getDisplayMetrics().scaledDensity;
    }
}
