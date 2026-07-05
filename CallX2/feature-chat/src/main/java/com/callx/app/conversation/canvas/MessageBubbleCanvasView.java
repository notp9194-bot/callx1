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

    private static final float CORNER_RADIUS_DP = 18f;
    private static final float TAIL_RADIUS_DP    = 4f;
    private static final float H_PADDING_DP      = 12f;
    private static final float V_PADDING_DP      = 8f;
    private static final float TEXT_SIZE_SP      = 15f;
    private static final float FOOTER_GAP_DP     = 4f;
    private static final float FOOTER_TEXT_SP    = 11f;
    private static final float TICK_GAP_DP       = 3f;
    private static final float TICK_SIZE_DP      = 12f;
    private static final float MAX_BUBBLE_WIDTH_FRACTION = 0.78f;

    // ── Reply-preview strip — mirrors ll_reply_preview in
    // item_message_sent/received.xml exactly (margins, sizes, colors) ──
    private static final float REPLY_MARGIN_DP       = 4f;   // outer margin around the whole strip
    private static final float REPLY_BAR_WIDTH_DP    = 3f;   // colored left bar
    private static final float REPLY_PADDING_H_DP    = 8f;   // inner horizontal padding (text column)
    private static final float REPLY_PADDING_V_DP    = 4f;   // inner vertical padding (text column)
    private static final float REPLY_MIN_HEIGHT_DP   = 36f;
    private static final float REPLY_CORNER_RADIUS_DP = 6f;
    private static final float REPLY_SENDER_SIZE_SP  = 11f;
    private static final float REPLY_TEXT_SIZE_SP    = 12f;
    private static final float REPLY_THUMB_SIZE_DP   = 44f;
    private static final float REPLY_THUMB_MARGIN_DP = 4f;
    private static final float REPLY_GAP_TO_MESSAGE_DP = 2f; // gap between reply strip and message text below it

    private static final int SENT_REPLY_BG     = 0x33000000;
    private static final int SENT_REPLY_BAR    = 0xFFFFD700;
    private static final int SENT_REPLY_SENDER = 0xFFFFD54F;
    private static final int SENT_REPLY_TEXT   = 0xDDFFFFFF;
    private static final int RECEIVED_REPLY_BG = 0x22000000;

    // ── Image media bubble — mirrors iv_image (180×180dp, centerCrop,
    // bg_media_card 12dp corner radius) in item_message_sent/received.xml ──
    private static final float MEDIA_SIZE_DP           = 180f;
    private static final float MEDIA_CORNER_RADIUS_DP  = 12f;
    private static final float MEDIA_MARGIN_DP         = 2f;   // gap between media edge and bubble edge
    private static final float MEDIA_CAPTION_GAP_DP    = 6f;   // gap between image and caption text
    private static final float MEDIA_PILL_PADDING_H_DP = 6f;
    private static final float MEDIA_PILL_PADDING_V_DP = 3f;
    private static final float MEDIA_PILL_MARGIN_DP    = 6f;   // pill inset from image's bottom-right corner
    private static final float MEDIA_PILL_CORNER_DP    = 8f;
    private static final int   MEDIA_PILL_BG           = 0x66000000; // translucent black, WhatsApp-style
    private static final int   MEDIA_PILL_TEXT         = 0xFFFFFFFF;
    private static final int   MEDIA_PLACEHOLDER_COLOR = 0xFFD9D9D9; // shown until Bitmap arrives — no theme lookup yet (see class doc)

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
    private static final float AUDIO_CONTENT_WIDTH_DP = 200f; // fixed row content width (excludes hPad)
    private static final float AUDIO_BTN_SIZE_DP       = 40f;
    private static final float AUDIO_ROW_GAP_DP        = 8f;  // button↔waveform and waveform↔duration gaps
    private static final float AUDIO_DUR_WIDTH_DP      = 36f;
    private static final float AUDIO_DUR_TEXT_SP       = 11f;
    private static final float AUDIO_WAVEFORM_HEIGHT_DP = 28f;
    private static final int   AUDIO_BAR_COUNT         = 28;
    private static final float AUDIO_BAR_GAP_RATIO     = 0.45f; // fraction of each bar's slot left as gap
    private static final int   AUDIO_BTN_BG_COLOR      = 0xFF008069; // matches circle_primary/brand_primary
    private static final int   AUDIO_BTN_ICON_COLOR    = 0xFFFFFFFF;
    private static final float AUDIO_PLAY_TRIANGLE_DP  = 14f;
    private static final float AUDIO_PAUSE_BAR_W_DP    = 3.5f;
    private static final float AUDIO_PAUSE_BAR_H_DP    = 14f;
    private static final float AUDIO_PAUSE_BAR_GAP_DP  = 4f;

    // ── Reaction badge — mirrors ll_reactions/tv_reactions in
    // item_message_sent/received.xml: a plain-emoji badge (no background
    // box) floating outside the bubble's bottom-end corner, pulled down by
    // a negative margin so it overlaps without covering the text/footer.
    // Same treatment for sent and received (both layouts constrain it
    // identically to the bubble's end/bottom). ──
    private static final float REACTIONS_TEXT_SP      = 28f;
    private static final float REACTIONS_MARGIN_END_DP = 4f;
    private static final float REACTIONS_OVERLAP_DP   = 14f; // how far the badge hangs below the bubble's bottom edge (matches layout_marginBottom="-14dp")
    private static final int   REACTIONS_SHADOW_COLOR = 0x66000000;

    // ── Pinned label — mirrors tv_pinned_label's text/size/color from
    // item_message_sent.xml ("📌 Pinned", 10sp, #E65100). That XML view was
    // never actually bound anywhere in MessagePagingAdapter (dead markup,
    // sent-layout-only, aligned to the parent row's end rather than the
    // bubble), so rather than reproduce that half-finished/sent-only
    // placement, this anchors the label to the BUBBLE's own top-end corner
    // — works symmetrically for sent and received alike. ──
    private static final float PINNED_LABEL_TEXT_SP = 10f;
    private static final int   PINNED_LABEL_COLOR   = 0xFFE65100;
    private static final String PINNED_LABEL_TEXT   = "📌 Pinned";
    private static final float PINNED_LABEL_GAP_DP  = 2f; // gap between label and bubble top
    private static final float GROUP_SENDER_TEXT_SP = 11f; // matches tv_sender_name in item_message_received.xml

    // ── Forwarded label — mirrors tv_forwarded's text/size/color/style from
    // item_message_received.xml ("↪ Forwarded from X", 11sp italic, #888888).
    // Stacked directly below the pinned-label/group-sender row (same
    // constraintTop_toBottomOf relationship the legacy layout uses). ──
    private static final float FORWARDED_LABEL_TEXT_SP = 11f;
    private static final int   FORWARDED_LABEL_COLOR   = 0xFF888888;

    // ── Deleted-message placeholder — mirrors bindMessage()'s
    // tvMessage.setAlpha(0.6f) treatment for "This message was deleted" /
    // "You deleted this message"; italic added for the same "ghost text"
    // effect most chat apps use. ──
    private static final int DELETED_TEXT_ALPHA = 153; // ~0.6 * 255

    // ── Disappearing-message countdown — mirrors tv_expiry's text/size/color
    // from item_message_sent/received.xml ("⏳ mm:ss", 9sp, gold), drawn in
    // the footer row just before the timestamp. ──
    private static final float EXPIRY_TEXT_SP = 9f;
    private static final int   EXPIRY_COLOR   = 0xFFFFCC00;

    // ── Media GROUP (multi-image/video grid) — mirrors MediaGroupLayoutHelper's
    // layout rules/constants exactly for visual parity, but is an entirely
    // separate mode from the single-image `isMedia` path above (own state,
    // own measure/draw/hit-test) so the already-working single-image path
    // can't regress. See MessagePagingAdapter.isCanvasEligible(): only
    // SENT groups of plain image/video items (no audio/file cells, no
    // per-item captions) reach this — received groups keep the manual
    // download-gate UI on the old path, same precedent as single images. ──
    private static final int GROUP_SINGLE_W    = 240;
    private static final int GROUP_SINGLE_H    = 200;
    private static final int GROUP_PAIR_CELL   = 118;
    private static final int GROUP_THREE_TOP_W = 240;
    private static final int GROUP_THREE_TOP_H = 140;
    private static final int GROUP_THREE_BOT   = 116;
    private static final int GROUP_GRID2_CELL  = 118;
    private static final int GROUP_GRID3_CELL  = 78;
    private static final int GROUP_GAP         = 2;
    private static final int GROUP_CORNER_R    = 4; // per-cell radius (matches buildCell's 4dp)
    private static final int GROUP_MAX_VISIBLE = 9; // 3x3 cap; beyond this, last cell shows "+N"
    private static final float GROUP_CAPTION_SCRIM_H_DP = 40f;
    private static final float GROUP_CAPTION_TEXT_SP    = 14f;
    private static final int   GROUP_PLAY_CIRCLE_DP      = 36;
    private static final int   GROUP_PLAY_TRIANGLE_DP    = 20;
    private static final int   GROUP_DURATION_TEXT_SP    = 10;
    // ── Per-item caption strip — mirrors MediaGroupLayoutHelper's per-cell
    // caption exactly: a 22dp gradient scrim pinned to the cell's bottom
    // edge, single-line ellipsized 10sp white text. When a video cell also
    // has a caption, its duration badge moves to the cell's top-end corner
    // instead (same conflict-avoidance the legacy helper uses). ──
    private static final float GROUP_ITEM_CAPTION_STRIP_H_DP = 22f;
    private static final float GROUP_ITEM_CAPTION_TEXT_SP    = 10f;
    private static final float GROUP_ITEM_CAPTION_MARGIN_DP  = 4f;
    private static final float GROUP_ITEM_CAPTION_BOTTOM_DP  = 2f;

    // ── Received-group manual download-gate — mirrors MediaGroupLayoutHelper's
    // addMasterDownloadOverlay()/buildCellDownloadOverlay() visuals exactly
    // (same colors/sizes), just Canvas-drawn instead of View-built. Only
    // reachable for RECEIVED groups — see setGroupDownloadGate(). ──
    private static final int   GROUP_GATE_SCRIM_COLOR      = 0x2E000000; // dims whole grid while gate is up
    private static final int   GROUP_GATE_PILL_BG          = 0x8A000000;
    private static final float GROUP_GATE_PILL_CORNER_DP   = 24f;
    private static final float GROUP_GATE_PILL_PAD_H_DP    = 14f;
    private static final float GROUP_GATE_PILL_PAD_V_DP    = 8f;
    private static final float GROUP_GATE_PILL_ICON_DP     = 18f;
    private static final float GROUP_GATE_PILL_ICON_GAP_DP = 8f;
    private static final float GROUP_GATE_PILL_TEXT_SP     = 13f;
    private static final int   GROUP_CELL_GATE_DIM_COLOR   = 0x40000000; // per-cell badge once master pill dismissed
    private static final int   GROUP_CELL_GATE_BADGE_BG    = 0x99000000;
    private static final float GROUP_CELL_GATE_BADGE_DP    = 26f;
    private static final float GROUP_CELL_GATE_ICON_DP     = 14f;

    // ── Live download-progress ring — shared by the single-media gate pill
    // and the per-cell group badge (see drawProgressRing()). Determinate
    // (percent 0-100): a clockwise arc from 12 o'clock. Indeterminate
    // (percent < 0, no progress events yet): a short arc that rotates on
    // wall-clock time via postInvalidateOnAnimation() — no Handler/
    // ValueAnimator bookkeeping needed, and every visible instance across
    // the RecyclerView stays in sync since they all read the same clock. ──
    private static final long  INDETERMINATE_PERIOD_MS = 900L;
    private static final float INDETERMINATE_SWEEP_DEG = 100f;

    // ── Single-media manual download gate — mirrors the legacy
    // fl_download_overlay/pb_download_spinner treatment for a RECEIVED
    // "image" message not yet cached locally: a dim scrim over the image
    // slot with a centered pill, idle "⬇ <label>" (tap to start) or a live
    // spinner/percentage while a download is in flight. Visually reuses the
    // GROUP_GATE_PILL_* constants above for parity with the group version. ──
    private static final int MEDIA_GATE_SCRIM_COLOR = 0x2E000000;

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
    private static final float REEL_CARD_WIDTH_DP        = 165f;
    private static final float REEL_CARD_HEIGHT_DP       = 237f;
    private static final float REEL_CORNER_RADIUS_DP     = 12f;
    private static final float REEL_TOP_GRADIENT_DP      = 60f;
    private static final float REEL_BOTTOM_GRADIENT_DP   = 80f;
    private static final float REEL_HEADER_PAD_H_DP      = 8f;
    private static final float REEL_HEADER_PAD_TOP_DP    = 7f;
    private static final float REEL_AVATAR_SIZE_DP       = 24f;
    private static final float REEL_AVATAR_TEXT_GAP_DP   = 5f;
    private static final float REEL_USERNAME_TEXT_SP     = 10f;
    private static final float REEL_PLAY_ICON_SP         = 28f;
    private static final int   REEL_PLAY_ICON_COLOR      = 0xDDFFFFFF;
    private static final float REEL_BOTTOM_PAD_H_DP      = 8f;
    private static final float REEL_BOTTOM_PAD_END_DP    = 36f; // room for the footer pill, matches legacy paddingEnd
    private static final float REEL_BOTTOM_PAD_BOTTOM_DP = 7f;
    private static final float REEL_CAPTION_TEXT_SP      = 10f;
    private static final float REEL_LABEL_TEXT_SP        = 9f;
    private static final float REEL_LABEL_GAP_TOP_DP     = 2f;
    private static final int   REEL_CAPTION_COLOR        = 0xEEFFFFFF;
    private static final int   REEL_LABEL_COLOR          = 0xCCFFFFFF;
    private static final String REEL_LABEL_TEXT          = "\u2B21  Reels";
    private static final String REEL_PLAY_GLYPH          = "\u25B6";
    private static final String REEL_DEFAULT_USERNAME    = "@callx_reel";
    private static final int   REEL_SHADOW_COLOR         = 0xAA000000;
    private static final int   REEL_CARD_BG_COLOR        = 0xFF1A1A1A; // matches thumbnail's #1A1A1A placeholder bg
    private static final int   REEL_AVATAR_PLACEHOLDER_COLOR = 0xFF3A3A3A;

    // ── Contact-share card — mirrors item_msg_contact.xml exactly: a
    // bubbleless 165dp-wide, wrap_content-height card (no chat-bubble
    // background, same precedent as the reel-share card) with a dark
    // (#1C1C1E) top section holding a circular avatar + name/phone, a
    // thin divider, and a "View Contact" action row below. No timestamp/
    // tick footer at all — item_msg_contact.xml has none, unlike the reel
    // card's always-shown pill. ──
    private static final float CONTACT_CARD_WIDTH_DP    = 165f;
    private static final float CONTACT_TOP_HEIGHT_DP    = 88f;
    private static final float CONTACT_DIVIDER_HEIGHT_DP = 0.5f;
    private static final float CONTACT_BUTTON_HEIGHT_DP = 32f;
    private static final float CONTACT_CORNER_RADIUS_DP = 12f;
    private static final float CONTACT_AVATAR_SIZE_DP   = 38f;
    private static final float CONTACT_PAD_H_DP         = 10f;
    private static final float CONTACT_TEXT_GAP_DP      = 8f;
    private static final float CONTACT_NAME_TEXT_SP     = 12f;
    private static final float CONTACT_PHONE_TEXT_SP    = 10f;
    private static final float CONTACT_PHONE_GAP_DP     = 2f;
    private static final float CONTACT_BUTTON_TEXT_SP   = 11f;
    private static final int   CONTACT_BG_COLOR         = 0xFF1C1C1E;
    private static final int   CONTACT_DIVIDER_COLOR    = 0x33FFFFFF;
    private static final int   CONTACT_NAME_COLOR       = 0xFFFFFFFF;
    private static final int   CONTACT_PHONE_COLOR      = 0xCCFFFFFF;
    private static final int   CONTACT_BUTTON_TEXT_COLOR = 0xFF4FC3F7;
    private static final int   CONTACT_AVATAR_PLACEHOLDER_COLOR = 0xFF3A3A3A;
    private static final int   CONTACT_TEXT_SHADOW_COLOR = 0xAA000000; // matches shadowColor on tvContactName/tvContactPhone
    private static final String CONTACT_DEFAULT_NAME     = "Contact";
    private static final String CONTACT_BUTTON_TEXT      = "View Contact";

    // ── Location-share card — mirrors item_msg_location.xml exactly: same
    // bubbleless 165dp-wide card shape family as the contact card, but with
    // a purple map-thumbnail header (Google Static Maps bitmap if supplied,
    // else a placeholder pin drawn straight on the Canvas), a translucent
    // purple address strip (up to 2 lines), and an "Open in Maps" action
    // row. No timestamp/tick footer, same precedent as the contact card. ──
    private static final float LOCATION_CARD_WIDTH_DP        = 165f;
    private static final float LOCATION_MAP_HEIGHT_DP        = 110f;
    private static final float LOCATION_DIVIDER_HEIGHT_DP    = 0.5f;
    private static final float LOCATION_BUTTON_HEIGHT_DP     = 32f;
    private static final float LOCATION_CORNER_RADIUS_DP     = 12f;
    private static final float LOCATION_PIN_SIZE_DP          = 28f;
    private static final float LOCATION_ADDRESS_PAD_H_DP     = 10f;
    private static final float LOCATION_ADDRESS_PAD_TOP_DP   = 6f;
    private static final float LOCATION_ADDRESS_PAD_BOTTOM_DP = 4f;
    private static final float LOCATION_ADDRESS_TEXT_SP      = 10f;
    private static final float LOCATION_BUTTON_TEXT_SP       = 11f;
    private static final int   LOCATION_MAP_BG_COLOR         = 0xFF4A148C;
    private static final int   LOCATION_PIN_COLOR            = 0xFFEF9A9A;
    private static final int   LOCATION_DIVIDER_COLOR        = 0xFF7B1FA2;
    private static final int   LOCATION_ADDRESS_BG_COLOR     = 0xFF6A1B9A;
    private static final int   LOCATION_ADDRESS_TEXT_COLOR   = 0xFFF3E5F5;
    private static final int   LOCATION_BUTTON_TEXT_COLOR    = 0xFFCE93D8;
    private static final String LOCATION_DEFAULT_ADDRESS     = "Location";
    private static final String LOCATION_BUTTON_TEXT         = "Open in Maps";

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
    private static final float LINK_PREVIEW_GAP_TOP_DP      = 6f; // gap between message text and the card
    private static final float LINK_PREVIEW_CORNER_DP       = 6f; // matches bg_reply_preview_sent/received's 6dp radius
    private static final float LINK_PREVIEW_THUMB_HEIGHT_DP = 120f;
    private static final float LINK_PREVIEW_PAD_H_DP        = 10f;
    private static final float LINK_PREVIEW_PAD_TOP_DP      = 8f;
    private static final float LINK_PREVIEW_PAD_BOTTOM_DP   = 8f;
    private static final float LINK_PREVIEW_DOMAIN_SP       = 10f;
    private static final float LINK_PREVIEW_TITLE_SP        = 13f;
    private static final float LINK_PREVIEW_TITLE_GAP_DP    = 2f; // gap between domain and title lines
    private static final int   LINK_PREVIEW_DOMAIN_COLOR    = 0xFFFFD54F;
    private static final int   LINK_PREVIEW_TITLE_COLOR     = 0xFFFFFFFF;
    private static final int   LINK_PREVIEW_THUMB_PLACEHOLDER_COLOR = 0xFF2A2A2A;

    public interface OnBubbleClickListener {

        void onBubbleClick();
        void onBubbleLongClick();
        /** Returns true if a link at (x,y) was hit and handled — caller should not treat as a normal click. */
        boolean onLinkClick(String url);
        /** Tapped the reply-preview strip — caller should scroll/jump to the quoted message. */
        void onReplyPreviewClick();
        /** Tapped the image itself (media bubbles only) — caller should open the full-screen media viewer. */
        void onImageClick();
        /** Tapped the single-media download gate pill (setMediaDownloadGate) while idle (not yet downloading) —
         *  caller should start the manual download and drive setMediaDownloadProgress() as it reports progress. */
        void onMediaDownloadClick();
        /** Tapped cell `index` inside a media-group grid (bindMediaGroup only) — caller should open the gallery viewer at that index. */
        void onMediaCellClick(int index);
        /** Tapped the master "Download N photos" pill on a RECEIVED group — caller should start every pending cell's download (view has already dismissed the pill locally). */
        void onGroupDownloadAllClick();
        /** Tapped an individual still-pending cell directly (gate already dismissed) — caller should start that one cell's download. */
        void onGroupCellDownloadClick(int index);
        /** Tapped the reaction badge — caller should open the reaction-details/picker sheet (same as ll_reactions' click listener on the legacy path). */
        void onReactionsClick();
        /** Tapped the play/pause button on an audio bubble (bindAudio only) — caller should toggle MediaPlayer playback for this message. */
        void onAudioPlayPauseClick();
        /** Dragged/tapped the waveform on an audio bubble (bindAudio only) — fraction is 0..1 of the track; caller should seek MediaPlayer to it. The view already updated its own progress bar optimistically. */
        void onAudioSeek(float fraction);
        /** Tapped the "View Contact" row on a contact card (bindContact only) — caller should open the system Contacts app / dialer for this contact's phone number, same as the legacy btnViewContact click listener. */
        void onContactViewClick();
        /** Tapped the "Open in Maps" row on a location card (bindLocation only) — caller should launch a maps app (or geo: intent) for this location's coordinates, same as the legacy btnOpenMaps click listener. */
        void onLocationOpenMapsClick();
    }

    /** Immutable per-cell descriptor for bindMediaGroup(); bitmaps are supplied
     *  later, one at a time, via setMediaGroupBitmap() as Glide decodes them. */
    public static final class GridItem {
        public final boolean isVideo;
        public final String duration; // e.g. "0:32"; null/empty if none or not a video
        public final String caption;  // per-item caption; null/empty for none — see setGroupCaptions doc
        public GridItem(boolean isVideo, @Nullable String duration) {
            this(isVideo, duration, null);
        }
        public GridItem(boolean isVideo, @Nullable String duration, @Nullable String caption) {
            this.isVideo = isVideo;
            this.duration = duration;
            this.caption = caption;
        }
    }

    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint footerPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bubbleRect = new RectF();

    private String messageText = "";
    private String footerTimeText = "";
    private boolean sent = false;
    private boolean read = false;
    private boolean delivered = false;

    private StaticLayout textLayout;
    private GradientDrawable bubbleDrawable;
    private int lastCacheKey = -1;

    private float density;
    private int bubbleLeft, bubbleTop;
    private float footerReserveWidth;

    // ── Reply-preview state ──────────────────────────────────────────
    private boolean hasReply = false;
    private String replySenderName = "";
    private String replyText = "";
    private Bitmap replyThumb;

    private final Paint replyBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint replyBarPaint = new Paint();
    private final TextPaint replySenderPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint replyTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Rect replyThumbSrcRect = new Rect();
    private final RectF replyThumbDstRect = new RectF();

    private StaticLayout replySenderLayout;
    private StaticLayout replyTextLayout;
    private final RectF replyBoxRect = new RectF();
    private int replyBoxHeight = 0;

    // ── Media (image) bubble state ───────────────────────────────────
    private boolean isMedia = false;
    private boolean mediaHasCaption = false;
    private Bitmap mediaBitmap;
    // Single "video" message reuses the whole isMedia/mediaRect/mediaBitmap
    // infrastructure (same fixed 180dp square, same footer pill) — this
    // flag just adds the play-glyph + duration-badge overlay on top,
    // mirroring the legacy fl_video/iv_video_thumb treatment. Never has a
    // caption (mediaHasCaption stays false for video, same as the legacy
    // "video" case never binding a caption). Reuses groupPlayCirclePaint/
    // groupPlayTrianglePaint/groupDurationTextPaint/groupDurationBgPaint/
    // groupPlayTrianglePath from the media-GROUP state below — same visual,
    // no need to duplicate paints/constants.
    private boolean isVideoMedia = false;
    private String videoDuration;
    private final Paint mediaBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint mediaPlaceholderPaint = new Paint();
    private final Paint mediaPillBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mediaPillTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mediaRect = new RectF();
    private final RectF mediaPillRect = new RectF();
    private final android.graphics.Matrix mediaShaderMatrix = new android.graphics.Matrix();

    // ── Audio (voice message) bubble state — entirely separate mode from
    // isMedia/isMediaGroup/isReelShare (see AUDIO_* constants doc above). ──
    private boolean isAudio = false;
    private float[] audioLevels = new float[0];
    private float audioProgress = 0f;   // 0..1 played fraction, drives waveform fill + seek
    private boolean audioPlaying = false;
    private String audioElapsedText = ""; // "m:ss" while playing; empty when idle (mirrors legacy tv_audio_dur, which never shows a total duration upfront)
    private final Paint audioBtnBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint audioBtnIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint audioWaveformIdlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint audioWaveformPlayedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint audioDurPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF audioBtnRect = new RectF();
    private final RectF audioWaveformRect = new RectF();
    private final android.graphics.Path audioPlayTrianglePath = new android.graphics.Path();

    // ── Single-media manual download gate state — see setMediaDownloadGate(). ──
    private boolean mediaGated = false;
    private boolean mediaDownloading = false;
    private int mediaDownloadProgress = -1; // -1 = indeterminate, 0-100 = live percent
    private String mediaDownloadLabel = "";
    private final Paint mediaGateScrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mediaGatePillBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mediaGatePillTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mediaGatePillIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mediaGatePillRect = new RectF();

    // ── Reaction badge state — independent of text/media/group mode; any
    // of the three can have reactions overlaid. ──
    private boolean hasReactions = false;
    private String reactionsText = "";
    private final TextPaint reactionsTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF reactionsRect = new RectF();

    // ── Pinned label state ────────────────────────────────────────────
    private boolean isPinned = false;
    private final TextPaint pinnedLabelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private int pinnedLabelHeight = 0;
    private float pinnedLabelWidth = 0;

    // ── Group-chat sender-name state — mirrors tv_sender_name in
    // item_message_received.xml. Only meaningful for received messages;
    // the sender AVATAR (iv_sender_avatar) is intentionally not modeled —
    // it's dead markup in the legacy layout too (never bound anywhere in
    // MessagePagingAdapter), so there's nothing functional to mirror. ──
    private boolean hasGroupSender = false;
    private String groupSenderName = "";
    private final TextPaint groupSenderPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private int groupSenderTextHeight = 0;
    private float groupSenderWidth = 0;

    // ── Forwarded-label state — stacks below the pinned-label/group-sender
    // row above the bubble (own row, own baseline; see onMeasure). ──
    private boolean hasForwarded = false;
    private String forwardedText = "";
    private final TextPaint forwardedPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private int forwardedTextHeight = 0;
    private float forwardedTextWidth = 0;
    // Baselines for all three "above the bubble" rows — computed once per
    // onMeasure() pass, consumed by the matching drawXxx() in onDraw() so
    // the draw path never has to re-derive vertical position from scratch.
    private float pinnedBaselineY = 0;
    private float groupSenderBaselineY = 0;
    private float forwardedBaselineY = 0;

    // ── Deleted-message placeholder style — only affects the plain-text
    // path (bind()); caller substitutes the placeholder string itself. ──
    private boolean isDeletedStyle = false;

    // ── Disappearing-message countdown state — an extra segment in the
    // footer row, drawn just before the timestamp/tick. ──
    private boolean hasExpiry = false;
    private String expiryText = "";
    private final TextPaint expiryPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    // ── Media GROUP (multi-image/video grid) state — entirely separate
    // from the single-image `isMedia` state above. ──
    private boolean isMediaGroup = false;
    private java.util.List<GridItem> groupItems = java.util.Collections.emptyList();
    private Bitmap[] groupBitmaps = new Bitmap[0];
    private final RectF[] groupRects = new RectF[GROUP_MAX_VISIBLE];
    private int groupVisibleCount = 0;
    private int groupRemaining = 0; // >0 only on the last visible cell when total > GROUP_MAX_VISIBLE
    private boolean groupHasCaption = false;
    private StaticLayout groupCaptionLayout;
    private final RectF groupContentRect = new RectF(); // whole grid area (no reply/caption)
    private final Paint groupCellBgPaint = new Paint();
    private final Paint groupBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint groupScrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groupMoreOverlayPaint = new Paint();
    private final TextPaint groupMoreTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groupPlayCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groupPlayTrianglePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint groupDurationTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groupDurationBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Matrix groupShaderMatrix = new android.graphics.Matrix();
    private final android.graphics.Path groupPlayTrianglePath = new android.graphics.Path();
    private final TextPaint groupCaptionTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint groupItemCaptionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groupItemCaptionScrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ── Received-group manual download-gate state — only ever set for
    // RECEIVED groups (see setGroupDownloadGate()); stays all-false/inert
    // for SENT groups and for received groups with nothing left to
    // download. Recomputed fresh on every bindMediaGroup() so a recycled
    // view never carries a stale gate from the previous message. ──
    private boolean groupGateActive = false;
    private int groupGatePendingCount = 0;
    private boolean[] groupCellPending = new boolean[0];
    private boolean[] groupCellDownloading = new boolean[0];
    private int[] groupCellProgress = new int[0]; // parallel to groupCellDownloading; -1 = indeterminate
    private final Paint groupGateScrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groupGatePillBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint groupGatePillTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groupGatePillIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groupCellGateDimPaint = new Paint();
    private final Paint groupCellGateBadgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groupCellGateIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF groupGatePillRect = new RectF();
    private final android.graphics.Path groupGateIconPath = new android.graphics.Path();

    // ── Reel-share card state — entirely separate mode from isMedia/
    // isMediaGroup (see REEL_* constants doc above). ──
    private boolean isReelShare = false;
    private String reelUsername = REEL_DEFAULT_USERNAME;
    private Bitmap reelThumbBitmap;
    private Bitmap reelAvatarBitmap;
    private boolean reelHasCaption = false;
    private String reelCaptionText = "";
    private StaticLayout reelCaptionLayout;
    private final RectF reelCardRect = new RectF();
    private final RectF reelAvatarRect = new RectF();
    private final Paint reelCardBgPaint = new Paint();
    private final Paint reelThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint reelAvatarPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint reelAvatarPlaceholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint reelUsernamePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint reelPlayIconPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint reelCaptionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint reelLabelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private GradientDrawable reelTopGradient;
    private GradientDrawable reelBottomGradient;
    private final android.graphics.Matrix reelShaderMatrix = new android.graphics.Matrix();
    private final android.graphics.Matrix reelAvatarShaderMatrix = new android.graphics.Matrix();

    // ── Contact-share card state — entirely separate mode from isMedia/
    // isMediaGroup/isReelShare/isAudio (see CONTACT_* constants doc above). ──
    private boolean isContact = false;
    private String contactName = "";
    private String contactPhone = "";
    private Bitmap contactAvatarBitmap;
    private final RectF contactCardRect = new RectF();
    private final RectF contactAvatarRect = new RectF();
    private final RectF contactButtonRect = new RectF(); // "View Contact" row — the only tappable sub-region
    private final Paint contactCardBgPaint = new Paint();
    private final Paint contactDividerPaint = new Paint();
    private final Paint contactAvatarPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint contactAvatarPlaceholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint contactNamePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint contactPhonePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint contactButtonTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Matrix contactAvatarShaderMatrix = new android.graphics.Matrix();

    private boolean isLocation = false;
    private String locationAddress = "";
    private Bitmap locationMapBitmap;
    private StaticLayout locationAddressLayout;
    private final RectF locationCardRect = new RectF();
    private final RectF locationMapRect = new RectF();
    private final RectF locationButtonRect = new RectF(); // "Open in Maps" row — the only tappable sub-region
    private final Paint locationMapBgPaint = new Paint();
    private final Paint locationMapBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint locationPinPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint locationDividerPaint = new Paint();
    private final Paint locationAddressBgPaint = new Paint();
    private final Paint locationButtonBgPaint = new Paint();
    private final TextPaint locationAddressTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint locationButtonTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path locationPinPath = new android.graphics.Path();
    private final android.graphics.Matrix locationMapShaderMatrix = new android.graphics.Matrix();

    // ── Link-preview card state — only meaningful alongside the
    // plain-text bind() mode (see LINK_PREVIEW_* constants doc above).
    // hasThumb is set upfront in setLinkPreview() (from whether
    // LinkPreviewFetcher.Result.imageUrl was non-empty) so the card's
    // reserved height is correct even before the Bitmap itself arrives
    // via setLinkPreviewThumbBitmap(). ──
    private boolean hasLinkPreview = false;
    private String linkPreviewUrl = "";
    private String linkTitle = "";
    private String linkDomain = "";
    private boolean linkHasThumb = false;
    private Bitmap linkThumbBitmap;
    private StaticLayout linkTitleLayout;
    private int linkCardHeight = 0;
    private final RectF linkCardRect = new RectF();
    private final RectF linkThumbRect = new RectF();
    private final Paint linkCardBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linkThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint linkThumbPlaceholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint linkDomainPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint linkTitlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Matrix linkThumbShaderMatrix = new android.graphics.Matrix();

    private OnBubbleClickListener clickListener;
    private final GestureDetector gestureDetector;


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

        reactionsTextPaint.setTextSize(spToPx(REACTIONS_TEXT_SP));
        reactionsTextPaint.setShadowLayer(2f * density, 0f, 1f * density, REACTIONS_SHADOW_COLOR);

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

        setWillNotDraw(false);

        gestureDetector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (clickListener != null) clickListener.onBubbleClick();
                return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                if (clickListener != null) clickListener.onBubbleLongClick();
            }
        });
    }

    public void setOnBubbleClickListener(OnBubbleClickListener l) {
        this.clickListener = l;
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

        textLayout = null; // recomputed in onMeasure
        requestLayout();
        invalidate();
    }

    /**
     * Bind this view to a single-image media message (matches iv_image in
     * item_message_sent/received.xml — fixed 180dp square, centerCrop,
     * 12dp rounded corners). Pass bitmap=null if the image hasn't finished
     * decoding yet; a placeholder box is drawn instead and setMediaBitmap()
     * can swap in the real bitmap later without a full rebind.
     *
     * @param caption optional caption text below the image; null/empty for
     *                a captionless image (timestamp/tick then overlay the
     *                image itself as a translucent pill, WhatsApp-style)
     */
    public void bindMedia(@Nullable Bitmap bitmap, @Nullable String caption, String timeText,
                           boolean isSent, boolean isRead, boolean isDelivered) {
        this.isMedia = true;
        this.isMediaGroup = false;
        this.isReelShare = false;
        this.isVideoMedia = false;
        this.isAudio = false;
        this.isContact = false;
        this.isLocation = false;
        this.videoDuration = null;
        this.mediaBitmap = bitmap;
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

        textLayout = null; // recomputed in onMeasure (only used if mediaHasCaption)
        requestLayout();
        invalidate();
    }

    /** Swap in a decoded Bitmap once an async (Glide) load finishes — no re-measure needed, same fixed image size. */
    public void setMediaBitmap(@Nullable Bitmap bitmap) {
        this.mediaBitmap = bitmap;
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
                          boolean isSent, boolean isRead, boolean isDelivered) {
        bindMedia(thumb, null, timeText, isSent, isRead, isDelivered);
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
        requestLayout();
        invalidate();
    }

    /** Toggles the play/pause glyph drawn inside the circle button — call whenever MediaPlayer actually starts/pauses/stops for this bubble. */
    public void setAudioPlaying(boolean playing) {
        if (this.audioPlaying == playing) return;
        this.audioPlaying = playing;
        invalidate();
    }

    /** Cheap path — called every playback tick (e.g. every 250ms). No layout/measure work, draw-only, same precedent as AudioWaveformView.setProgress(). */
    public void setAudioProgress(float fraction) {
        float clamped = Math.max(0f, Math.min(1f, fraction));
        if (clamped == audioProgress) return;
        audioProgress = clamped;
        invalidate();
    }

    /** Elapsed "m:ss" label shown next to the waveform while playing; pass "" to clear it back to idle (mirrors legacy tv_audio_dur, which never shows a total duration upfront — only the live elapsed time once playback starts). */
    public void setAudioElapsedText(@Nullable String text) {
        this.audioElapsedText = text != null ? text : "";
        invalidate();
    }

    /** Resets the bubble to its idle state (button back to ▶, progress to 0, elapsed label cleared) — call when playback stops/completes/errors, or right before rebinding a recycled holder to a different message. */
    public void resetAudioPlayback() {
        this.audioPlaying = false;
        this.audioProgress = 0f;
        this.audioElapsedText = "";
        invalidate();
    }

    /** Same bar-height generation AudioWaveformView.generateLevels() uses — a stable seed always produces the same "waveform" shape. */
    private static float[] generateAudioLevels(@Nullable String seed, int count) {
        long s = (seed == null || seed.isEmpty()) ? 0L : seed.hashCode();
        java.util.Random r = new java.util.Random(s);
        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            out[i] = 0.25f + r.nextFloat() * 0.7f;
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
        invalidate();
    }

    /** Updates just the live percentage while a download is already in flight (call on every onProgress tick). */
    public void setMediaDownloadProgress(int progressPercent) {
        this.mediaGated = true;
        this.mediaDownloading = true;
        this.mediaDownloadProgress = progressPercent;
        invalidate();
    }

    /** Dismisses the gate entirely — call once the real bitmap has been supplied via setMediaBitmap(). */
    public void clearMediaDownloadGate() {
        this.mediaGated = false;
        this.mediaDownloading = false;
        this.mediaDownloadProgress = -1;
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

        groupCaptionLayout = null; // recomputed in onMeasure
        textLayout = null;
        requestLayout();
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

        reelCaptionLayout = null; // recomputed in onMeasure
        requestLayout();
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
            reelCaptionLayout = null; // recomputed in onMeasure
            requestLayout();
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
        this.hasLinkPreview = false; // stale flag from a recycled view must not leak in here
        this.mediaGated = false;
        this.mediaDownloading = false;
        this.contactAvatarBitmap = avatar;
        this.contactName = (name != null && !name.isEmpty()) ? name : CONTACT_DEFAULT_NAME;
        this.contactPhone = phone != null ? phone : "";
        this.sent = isSent;

        requestLayout();
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
    public void bindLocation(@Nullable Bitmap mapThumb, @Nullable String address, boolean isSent) {
        this.isMedia = false;
        this.isMediaGroup = false;
        this.isReelShare = false;
        this.isVideoMedia = false;
        this.isAudio = false;
        this.isContact = false;
        this.isLocation = true;
        this.hasLinkPreview = false; // stale flag from a recycled view must not leak in here
        this.mediaGated = false;
        this.mediaDownloading = false;
        this.locationMapBitmap = mapThumb;
        this.locationAddress = (address != null && !address.isEmpty()) ? address : LOCATION_DEFAULT_ADDRESS;
        this.sent = isSent;

        locationAddressLayout = null; // recomputed in onMeasure
        requestLayout();
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
        replySenderLayout = null; // recomputed in onMeasure
        replyTextLayout = null;
        requestLayout();
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
        requestLayout();
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
        requestLayout();
        invalidate();
    }

    /** Call when a message has no reactions — clears any previous badge state so a recycled view doesn't show a stale reaction. */
    public void clearReactions() {
        this.hasReactions = false;
        this.reactionsText = "";
        requestLayout();
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
        requestLayout();
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
        requestLayout();
        invalidate();
    }

    /** Call for sent messages, or received messages outside a group chat — clears any stale sender-name state on a recycled view. */
    public void clearGroupSender() {
        this.hasGroupSender = false;
        this.groupSenderName = "";
        requestLayout();
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
        requestLayout();
        invalidate();
    }

    /** Call when a message wasn't forwarded — clears any stale label state on a recycled view. */
    public void clearForwarded() {
        setForwardedFrom(null);
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
        textLayout = null; // rebuild with the new typeface/alpha before the next draw
        requestLayout();
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
        requestLayout();
        invalidate();
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
        this.linkTitleLayout = null; // recomputed in onMeasure
        requestLayout();
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
        requestLayout();
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

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int parentWidth = MeasureSpec.getSize(widthMeasureSpec);
        int maxBubbleWidth = Math.round(parentWidth * MAX_BUBBLE_WIDTH_FRACTION);
        int hPad = Math.round(H_PADDING_DP * density);
        int vPad = Math.round(V_PADDING_DP * density);
        int footerHeight = Math.round(spToPx(FOOTER_TEXT_SP) + FOOTER_GAP_DP * density);
        int maxTextWidth = Math.max(1, maxBubbleWidth - hPad * 2);

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
            // ── Image bubble (fixed 180dp square) ──
            int mediaSize = Math.round(MEDIA_SIZE_DP * density);
            int captionWidth = 0;
            int captionBlockHeight = 0;

            if (mediaHasCaption) {
                float captionFooterReserve = footerPaint.measureText(footerTimeText)
                        + (sent ? (TICK_SIZE_DP + TICK_GAP_DP) * density : 0)
                        + FOOTER_GAP_DP * density;
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
                    Math.max(mediaSize, Math.max(replyBoxContentWidth, captionWidth)), maxTextWidth);

            // Captionless images get a tight vPad margin around the square;
            // captioned images get the same margin plus the caption+footer
            // block below (footer overlay is skipped when there's a caption
            // — see onDraw — matching the text-bubble footer placement).
            bubbleHeight = replyBoxHeight + replyGap + vPad + mediaSize + vPad + captionBlockHeight;

        } else if (isReelShare) {
            // ── Reel-share card (fixed 165×237dp, bubbleless) ── caption
            // is an overlay INSIDE the card (bottom gradient), not an
            // extra block below it, so bubbleHeight is just replyBox +
            // the card itself — no vPad, no footer row (the timestamp
            // pill floats over the card, same as a captionless image).
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

            // No chat-bubble padding around the card — bubbleContentWidth
            // is sized so bubbleWidth (= bubbleContentWidth + hPad*2)
            // comes out to exactly cardW once hPad is added back below.
            bubbleContentWidth = Math.max(cardW - hPad * 2, replyBoxContentWidth);
            bubbleHeight = replyBoxHeight + replyGap + cardH;

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

            textLayout = StaticLayout.Builder
                    .obtain(messageText, 0, messageText.length(), textPaint, maxTextWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1f)
                    .setIncludePad(false)
                    .build();

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
            int mediaSize = Math.round(MEDIA_SIZE_DP * density);
            float mediaTop = bubbleTop + replyBoxHeight + replyGap + vPad;
            mediaRect.set(bubbleLeft + hPad, mediaTop, bubbleLeft + hPad + mediaSize, mediaTop + mediaSize);

            if (!mediaHasCaption) {
                float pillPadH = MEDIA_PILL_PADDING_H_DP * density;
                float pillPadV = MEDIA_PILL_PADDING_V_DP * density;
                float pillMargin = MEDIA_PILL_MARGIN_DP * density;
                float pillTextW = mediaPillTextPaint.measureText(footerTimeText)
                        + (sent ? (TICK_SIZE_DP + TICK_GAP_DP) * density : 0);
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
            float cardTop = bubbleTop + replyBoxHeight + replyGap;
            // Card is always exactly cardW wide, left-aligned to the
            // bubble's own left edge — matches image/group's sent-right/
            // received-left alignment via bubbleLeft, regardless of
            // whether a reply box widened bubbleWidth beyond cardW.
            reelCardRect.set(bubbleLeft, cardTop, bubbleLeft + cardW, cardTop + cardH);

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
                    + (sent ? (TICK_SIZE_DP + TICK_GAP_DP) * density : 0);
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
                        + (sent ? (TICK_SIZE_DP + TICK_GAP_DP) * density : 0);
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

    private int dp(int v) {
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

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!isReelShare && !isContact && !isLocation) {
            if (bubbleDrawable == null) return;
            if (!isMedia && !isMediaGroup && !isAudio && textLayout == null) return;
        }

        if (!isReelShare && !isContact && !isLocation) {
            // Reel-share, contact, and location cards never draw the normal
            // chat-bubble background — the card itself (drawn in
            // drawReelShare/drawContact/drawLocation) is the entire visual,
            // matching layout_msg_reel_share.xml / item_msg_contact.xml /
            // item_msg_location.xml's bg=null on the outer bubble container
            // for these message types.
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

        int hPad = Math.round(H_PADDING_DP * density);
        int vPad = Math.round(V_PADDING_DP * density);

        if (hasReply) {
            drawReplyPreview(canvas);
        }

        if (isReelShare) {
            drawReelShare(canvas);
        } else if (isContact) {
            drawContact(canvas);
        } else if (isLocation) {
            drawLocation(canvas);
        } else if (isMediaGroup) {
            drawMediaGroup(canvas);
        } else if (isMedia) {
            drawMedia(canvas, hPad, vPad);
        } else if (isAudio) {
            drawAudio(canvas, hPad, vPad);
        } else {
            int replyGap = hasReply ? Math.round(REPLY_GAP_TO_MESSAGE_DP * density) : 0;
            canvas.save();
            canvas.translate(bubbleLeft + hPad, bubbleTop + replyBoxHeight + replyGap + vPad);
            textLayout.draw(canvas);
            canvas.restore();

            if (hasLinkPreview) {
                drawLinkPreview(canvas);
            }

            drawFooter(canvas, bubbleRect.bottom - vPad * 0.4f, bubbleRect.right - hPad);
        }

        if (hasReactions) {
            drawReactionsBadge(canvas);
        }
    }

    private void drawReactionsBadge(Canvas canvas) {
        float baselineY = reactionsRect.bottom - reactionsTextPaint.getFontMetrics().descent;
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
     * Draws the reel-share card — mirrors layout_msg_reel_share.xml:
     * rounded 165×237dp thumbnail (or #1A1A1A placeholder), top gradient +
     * avatar/username header, centered play glyph, bottom gradient +
     * caption + "⬡ Reels" label, and the timestamp/tick pill in the
     * bottom-end corner (always shown, same as ll_msg_footer there).
     */
    private void drawReelShare(Canvas canvas) {
        float r = REEL_CORNER_RADIUS_DP * density;

        // ── Thumbnail (or placeholder), clipped to the card's rounded shape ──
        if (reelThumbBitmap != null) {
            float scale = Math.max(reelCardRect.width() / reelThumbBitmap.getWidth(),
                    reelCardRect.height() / reelThumbBitmap.getHeight());
            float dx = reelCardRect.left - (reelThumbBitmap.getWidth() * scale - reelCardRect.width()) / 2f;
            float dy = reelCardRect.top - (reelThumbBitmap.getHeight() * scale - reelCardRect.height()) / 2f;
            reelShaderMatrix.reset();
            reelShaderMatrix.setScale(scale, scale);
            reelShaderMatrix.postTranslate(dx, dy);

            android.graphics.BitmapShader shader = new android.graphics.BitmapShader(
                    reelThumbBitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
            shader.setLocalMatrix(reelShaderMatrix);
            reelThumbPaint.setShader(shader);
            canvas.drawRoundRect(reelCardRect, r, r, reelThumbPaint);
        } else {
            canvas.drawRoundRect(reelCardRect, r, r, reelCardBgPaint);
        }

        // ── Top gradient (fades thumbnail so the header reads clearly) ──
        reelTopGradient.setBounds(
                (int) reelCardRect.left, (int) reelCardRect.top,
                (int) reelCardRect.right, (int) (reelCardRect.top + REEL_TOP_GRADIENT_DP * density));
        reelTopGradient.draw(canvas);

        // ── Bottom gradient (fades thumbnail so caption/label reads clearly) ──
        reelBottomGradient.setBounds(
                (int) reelCardRect.left, (int) (reelCardRect.bottom - REEL_BOTTOM_GRADIENT_DP * density),
                (int) reelCardRect.right, (int) reelCardRect.bottom);
        reelBottomGradient.draw(canvas);

        // ── Header: avatar + username ──
        if (reelAvatarBitmap != null) {
            float scale = Math.max(reelAvatarRect.width() / reelAvatarBitmap.getWidth(),
                    reelAvatarRect.height() / reelAvatarBitmap.getHeight());
            float dx = reelAvatarRect.left - (reelAvatarBitmap.getWidth() * scale - reelAvatarRect.width()) / 2f;
            float dy = reelAvatarRect.top - (reelAvatarBitmap.getHeight() * scale - reelAvatarRect.height()) / 2f;
            reelAvatarShaderMatrix.reset();
            reelAvatarShaderMatrix.setScale(scale, scale);
            reelAvatarShaderMatrix.postTranslate(dx, dy);

            android.graphics.BitmapShader shader = new android.graphics.BitmapShader(
                    reelAvatarBitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
            shader.setLocalMatrix(reelAvatarShaderMatrix);
            reelAvatarPaint.setShader(shader);
            canvas.drawOval(reelAvatarRect, reelAvatarPaint);
        } else {
            canvas.drawOval(reelAvatarRect, reelAvatarPlaceholderPaint);
        }

        float usernameX = reelAvatarRect.right + REEL_AVATAR_TEXT_GAP_DP * density;
        Paint.FontMetrics ufm = reelUsernamePaint.getFontMetrics();
        float usernameBaselineY = reelAvatarRect.centerY() - (ufm.ascent + ufm.descent) / 2f;
        float usernameMaxW = reelCardRect.right - REEL_HEADER_PAD_H_DP * density - usernameX;
        String usernameToDraw = TextUtils.ellipsize(reelUsername, reelUsernamePaint,
                Math.max(1, usernameMaxW), TextUtils.TruncateAt.END).toString();
        canvas.drawText(usernameToDraw, usernameX, usernameBaselineY, reelUsernamePaint);

        // ── Centered play glyph ──
        Paint.FontMetrics pfm = reelPlayIconPaint.getFontMetrics();
        float playBaselineY = reelCardRect.centerY() - (pfm.ascent + pfm.descent) / 2f;
        canvas.drawText(REEL_PLAY_GLYPH, reelCardRect.centerX(), playBaselineY, reelPlayIconPaint);

        // ── Bottom: caption + "⬡ Reels" label ──
        float bottomPadH = REEL_BOTTOM_PAD_H_DP * density;
        float bottomPadBottom = REEL_BOTTOM_PAD_BOTTOM_DP * density;
        Paint.FontMetrics lfm = reelLabelPaint.getFontMetrics();
        float labelHeight = lfm.descent - lfm.ascent;
        float labelBaselineY = reelCardRect.bottom - bottomPadBottom - lfm.descent;
        canvas.drawText(REEL_LABEL_TEXT, reelCardRect.left + bottomPadH, labelBaselineY, reelLabelPaint);

        if (reelHasCaption && reelCaptionLayout != null) {
            float captionBottom = labelBaselineY + lfm.ascent - REEL_LABEL_GAP_TOP_DP * density;
            canvas.save();
            canvas.translate(reelCardRect.left + bottomPadH, captionBottom - reelCaptionLayout.getHeight());
            reelCaptionLayout.draw(canvas);
            canvas.restore();
        }

        // ── Timestamp/tick pill — always shown, bottom-end corner ──
        float rr = MEDIA_PILL_CORNER_DP * density;
        canvas.drawRoundRect(mediaPillRect, rr, rr, mediaPillBgPaint);
        float pillPadH = MEDIA_PILL_PADDING_H_DP * density;
        float textBaselineY = mediaPillRect.bottom - (mediaPillRect.height()
                - (mediaPillTextPaint.descent() - mediaPillTextPaint.ascent())) / 2f
                - mediaPillTextPaint.descent();
        float tickReserve = sent ? (TICK_SIZE_DP + TICK_GAP_DP) * density : 0;
        canvas.drawText(footerTimeText,
                mediaPillRect.right - pillPadH - tickReserve - mediaPillTextPaint.measureText(footerTimeText),
                textBaselineY, mediaPillTextPaint);
        if (sent) {
            Paint saved = new Paint(tickPaint);
            tickPaint.setColor(MEDIA_PILL_TEXT);
            drawTick(canvas, mediaPillRect.right - pillPadH - TICK_SIZE_DP * density, textBaselineY);
            tickPaint.set(saved);
        }
    }

    /**
     * Draws the contact-share card — mirrors item_msg_contact.xml: one
     * rounded 165dp-wide card (single #1C1C1E background covers both the
     * "top section" and the "View Contact" row, since they share the same
     * color in the legacy layout — only the divider line is visually
     * distinct), a circular avatar (placeholder ic_person glyph if no
     * photo), name + phone stacked beside it, and the "View Contact"
     * label centered in its own row below a thin divider.
     */
    private void drawContact(Canvas canvas) {
        float r = CONTACT_CORNER_RADIUS_DP * density;
        canvas.save();
        // Clip to the card's rounded shape (android:clipToOutline on the
        // legacy ll_contact_card) so the flat divider/button-row rects
        // drawn below don't square off the bottom corners.
        android.graphics.Path clipPath = new android.graphics.Path();
        clipPath.addRoundRect(contactCardRect, r, r, android.graphics.Path.Direction.CW);
        canvas.clipPath(clipPath);

        canvas.drawRect(contactCardRect, contactCardBgPaint);

        // ── Avatar (photo or placeholder) ──
        if (contactAvatarBitmap != null) {
            float scale = Math.max(contactAvatarRect.width() / contactAvatarBitmap.getWidth(),
                    contactAvatarRect.height() / contactAvatarBitmap.getHeight());
            float dx = contactAvatarRect.left - (contactAvatarBitmap.getWidth() * scale - contactAvatarRect.width()) / 2f;
            float dy = contactAvatarRect.top - (contactAvatarBitmap.getHeight() * scale - contactAvatarRect.height()) / 2f;
            contactAvatarShaderMatrix.reset();
            contactAvatarShaderMatrix.setScale(scale, scale);
            contactAvatarShaderMatrix.postTranslate(dx, dy);

            android.graphics.BitmapShader shader = new android.graphics.BitmapShader(
                    contactAvatarBitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
            shader.setLocalMatrix(contactAvatarShaderMatrix);
            contactAvatarPaint.setShader(shader);
            canvas.drawOval(contactAvatarRect, contactAvatarPaint);
        } else {
            canvas.drawOval(contactAvatarRect, contactAvatarPlaceholderPaint);
        }

        // ── Name / phone column beside the avatar ──
        float textX = contactAvatarRect.right + CONTACT_TEXT_GAP_DP * density;
        float textMaxW = contactCardRect.right - CONTACT_PAD_H_DP * density - textX;
        String nameToDraw = TextUtils.ellipsize(contactName, contactNamePaint,
                Math.max(1, textMaxW), TextUtils.TruncateAt.END).toString();
        String phoneToDraw = TextUtils.ellipsize(contactPhone, contactPhonePaint,
                Math.max(1, textMaxW), TextUtils.TruncateAt.END).toString();

        Paint.FontMetrics nfm = contactNamePaint.getFontMetrics();
        Paint.FontMetrics phfm = contactPhonePaint.getFontMetrics();
        float nameH = nfm.descent - nfm.ascent;
        float phoneH = phfm.descent - phfm.ascent;
        boolean hasPhone = !phoneToDraw.isEmpty();
        float phoneGap = hasPhone ? CONTACT_PHONE_GAP_DP * density : 0;
        float blockH = nameH + (hasPhone ? phoneGap + phoneH : 0);
        float blockTop = contactAvatarRect.centerY() - blockH / 2f;

        canvas.drawText(nameToDraw, textX, blockTop - nfm.ascent, contactNamePaint);
        if (hasPhone) {
            float phoneBaselineY = blockTop + nameH + phoneGap - phfm.ascent;
            canvas.drawText(phoneToDraw, textX, phoneBaselineY, contactPhonePaint);
        }

        // ── Divider ──
        float dividerTop = contactCardRect.top + CONTACT_TOP_HEIGHT_DP * density;
        canvas.drawRect(contactCardRect.left, dividerTop, contactCardRect.right,
                dividerTop + CONTACT_DIVIDER_HEIGHT_DP * density, contactDividerPaint);

        // ── "View Contact" row ──
        Paint.FontMetrics bfm = contactButtonTextPaint.getFontMetrics();
        float btnBaselineY = contactButtonRect.centerY() - (bfm.ascent + bfm.descent) / 2f;
        canvas.drawText(CONTACT_BUTTON_TEXT, contactButtonRect.centerX(), btnBaselineY, contactButtonTextPaint);

        canvas.restore();
    }

    /**
     * Draws the location-share card — mirrors item_msg_location.xml: a
     * purple map-thumbnail header (Google Static Maps bitmap if supplied,
     * centerCrop-scaled the same way drawMedia()'s bitmap fill works;
     * else a placeholder pin drawn straight on the flat purple
     * background), a translucent purple address strip (up to 2 lines,
     * via StaticLayout), and a bottom "Open in Maps" row — same
     * rounded-card clip treatment as drawContact().
     */
    private void drawLocation(Canvas canvas) {
        float r = LOCATION_CORNER_RADIUS_DP * density;
        canvas.save();
        android.graphics.Path clipPath = new android.graphics.Path();
        clipPath.addRoundRect(locationCardRect, r, r, android.graphics.Path.Direction.CW);
        canvas.clipPath(clipPath);

        // ── Map header ──
        canvas.drawRect(locationMapRect, locationMapBgPaint);
        if (locationMapBitmap != null) {
            float scale = Math.max(locationMapRect.width() / locationMapBitmap.getWidth(),
                    locationMapRect.height() / locationMapBitmap.getHeight());
            float dx = locationMapRect.left - (locationMapBitmap.getWidth() * scale - locationMapRect.width()) / 2f;
            float dy = locationMapRect.top - (locationMapBitmap.getHeight() * scale - locationMapRect.height()) / 2f;
            locationMapShaderMatrix.reset();
            locationMapShaderMatrix.setScale(scale, scale);
            locationMapShaderMatrix.postTranslate(dx, dy);

            android.graphics.BitmapShader shader = new android.graphics.BitmapShader(
                    locationMapBitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
            shader.setLocalMatrix(locationMapShaderMatrix);
            locationMapBitmapPaint.setShader(shader);
            canvas.drawRect(locationMapRect, locationMapBitmapPaint);
        } else {
            // Placeholder pin — teardrop + circular head, matches
            // ic_location_pin's silhouette closely enough at this size.
            float pinSize = LOCATION_PIN_SIZE_DP * density;
            float cx = locationMapRect.centerX();
            float cy = locationMapRect.centerY() - pinSize * 0.15f;
            locationPinPath.reset();
            locationPinPath.addCircle(cx, cy, pinSize * 0.32f, android.graphics.Path.Direction.CW);
            locationPinPath.moveTo(cx - pinSize * 0.32f, cy + pinSize * 0.08f);
            locationPinPath.lineTo(cx, cy + pinSize * 0.62f);
            locationPinPath.lineTo(cx + pinSize * 0.32f, cy + pinSize * 0.08f);
            locationPinPath.close();
            canvas.drawPath(locationPinPath, locationPinPaint);
        }

        // ── Divider under the map ──
        float dividerH = LOCATION_DIVIDER_HEIGHT_DP * density;
        float divTop1 = locationMapRect.bottom;
        canvas.drawRect(locationCardRect.left, divTop1, locationCardRect.right, divTop1 + dividerH, locationDividerPaint);

        // ── Address strip ──
        float addrTop = divTop1 + dividerH;
        float addrBottom = locationButtonRect.top - dividerH;
        canvas.drawRect(locationCardRect.left, addrTop, locationCardRect.right, addrBottom, locationAddressBgPaint);
        if (locationAddressLayout != null) {
            canvas.save();
            canvas.translate(locationCardRect.left + LOCATION_ADDRESS_PAD_H_DP * density,
                    addrTop + LOCATION_ADDRESS_PAD_TOP_DP * density);
            locationAddressLayout.draw(canvas);
            canvas.restore();
        }

        // ── Divider above the button row ──
        canvas.drawRect(locationCardRect.left, addrBottom, locationCardRect.right, addrBottom + dividerH, locationDividerPaint);

        // ── "Open in Maps" row ──
        canvas.drawRect(locationButtonRect, locationButtonBgPaint);
        Paint.FontMetrics lbfm = locationButtonTextPaint.getFontMetrics();
        float locBtnBaselineY = locationButtonRect.centerY() - (lbfm.ascent + lbfm.descent) / 2f;
        canvas.drawText(LOCATION_BUTTON_TEXT, locationButtonRect.centerX(), locBtnBaselineY, locationButtonTextPaint);

        canvas.restore();
    }

    private void drawMedia(Canvas canvas, int hPad, int vPad) {
        float r = MEDIA_CORNER_RADIUS_DP * density;
        if (mediaBitmap != null) {
            // Rounded-corner centerCrop: scale a BitmapShader so the source
            // bitmap fills mediaRect exactly (matching ImageView's
            // centerCrop), then clip to a round rect with drawRoundRect —
            // avoids clipPath (which can force a software layer on some
            // Android versions) while still giving true rounded corners.
            float scale = Math.max(mediaRect.width() / mediaBitmap.getWidth(),
                    mediaRect.height() / mediaBitmap.getHeight());
            float dx = mediaRect.left - (mediaBitmap.getWidth() * scale - mediaRect.width()) / 2f;
            float dy = mediaRect.top - (mediaBitmap.getHeight() * scale - mediaRect.height()) / 2f;
            mediaShaderMatrix.reset();
            mediaShaderMatrix.setScale(scale, scale);
            mediaShaderMatrix.postTranslate(dx, dy);

            android.graphics.BitmapShader shader = new android.graphics.BitmapShader(
                    mediaBitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
            shader.setLocalMatrix(mediaShaderMatrix);
            mediaBitmapPaint.setShader(shader);
            canvas.drawRoundRect(mediaRect, r, r, mediaBitmapPaint);
        } else {
            // Not decoded yet — plain placeholder box, same rounded shape.
            canvas.drawRoundRect(mediaRect, r, r, mediaPlaceholderPaint);
        }

        if (isVideoMedia) {
            drawVideoPlayOverlay(canvas);
        }

        if (mediaGated) {
            // Manual-download gate covers the whole slot (idle pill or live
            // spinner/percentage) — same precedent as the group gate: while
            // it's up, the timestamp/tick pill below is skipped entirely
            // (nothing meaningful to show over an unfetched image yet).
            drawMediaDownloadGate(canvas);
            return;
        }

        if (mediaHasCaption && textLayout != null) {
            float captionTop = mediaRect.bottom + MEDIA_CAPTION_GAP_DP * density;
            canvas.save();
            canvas.translate(bubbleLeft + hPad, captionTop);
            textLayout.draw(canvas);
            canvas.restore();
            drawFooter(canvas, bubbleRect.bottom - vPad * 0.4f, bubbleRect.right - hPad);
        } else {
            // Captionless image: translucent timestamp/tick pill overlaid
            // on the image's bottom-right corner, WhatsApp-style.
            float rr = MEDIA_PILL_CORNER_DP * density;
            canvas.drawRoundRect(mediaPillRect, rr, rr, mediaPillBgPaint);
            float pillPadH = MEDIA_PILL_PADDING_H_DP * density;
            float textBaselineY = mediaPillRect.bottom - (mediaPillRect.height()
                    - (mediaPillTextPaint.descent() - mediaPillTextPaint.ascent())) / 2f
                    - mediaPillTextPaint.descent();
            float tickReserve = sent ? (TICK_SIZE_DP + TICK_GAP_DP) * density : 0;
            canvas.drawText(footerTimeText,
                    mediaPillRect.right - pillPadH - tickReserve - mediaPillTextPaint.measureText(footerTimeText),
                    textBaselineY, mediaPillTextPaint);
            if (sent) {
                // Reuse the same tick paint as the text-bubble footer, but
                // white (matches mediaPillTextPaint) so it reads on the pill.
                Paint saved = new Paint(tickPaint);
                tickPaint.setColor(MEDIA_PILL_TEXT);
                drawTick(canvas, mediaPillRect.right - pillPadH - TICK_SIZE_DP * density, textBaselineY);
                tickPaint.set(saved);
            }
        }
    }

    /**
     * Draws the play-circle+triangle glyph centered on mediaRect, plus a
     * duration badge in the bottom-left corner — mirrors the legacy
     * fl_video/iv_video_thumb treatment for a single "video" message.
     * Reuses groupPlayCirclePaint/groupPlayTrianglePaint/
     * groupDurationTextPaint/groupDurationBgPaint/groupPlayTrianglePath
     * from the media-GROUP video-cell overlay — identical visual, no
     * separate constants needed for the single-video case.
     */
    private void drawVideoPlayOverlay(Canvas canvas) {
        float cx = mediaRect.centerX(), cy = mediaRect.centerY();
        float circleR = (GROUP_PLAY_CIRCLE_DP * density) / 2f;
        canvas.drawCircle(cx, cy, circleR, groupPlayCirclePaint);

        float triR = (GROUP_PLAY_TRIANGLE_DP * density) / 2f;
        groupPlayTrianglePath.reset();
        groupPlayTrianglePath.moveTo(cx - triR * 0.5f, cy - triR * 0.8f);
        groupPlayTrianglePath.lineTo(cx - triR * 0.5f, cy + triR * 0.8f);
        groupPlayTrianglePath.lineTo(cx + triR * 0.9f, cy);
        groupPlayTrianglePath.close();
        canvas.drawPath(groupPlayTrianglePath, groupPlayTrianglePaint);

        if (videoDuration != null && !videoDuration.isEmpty()) {
            float durPadH = 3 * density, durPadV = 1 * density;
            float textW = groupDurationTextPaint.measureText(videoDuration);
            float textH = groupDurationTextPaint.descent() - groupDurationTextPaint.ascent();
            float left = mediaRect.left + 4 * density;
            float bottom = mediaRect.bottom - 4 * density;
            RectF durBg = new RectF(left, bottom - textH - durPadV * 2, left + textW + durPadH * 2, bottom);
            canvas.drawRoundRect(durBg, 3 * density, 3 * density, groupDurationBgPaint);
            float textBaseline = durBg.bottom - durPadV - groupDurationTextPaint.descent();
            canvas.drawText(videoDuration, durBg.left + durPadH, textBaseline, groupDurationTextPaint);
        }
    }

    /**
     * Draws the audio row — play/pause circle button, waveform track
     * (idle-colored bars, with the played fraction re-drawn in the
     * played color, clipped to audioProgress), elapsed-time label, and
     * finally the normal text-bubble footer (time/tick) below the whole
     * row, since an audio bubble is never captioned.
     */
    private void drawAudio(Canvas canvas, int hPad, int vPad) {
        // ── Play/pause button ──
        canvas.drawCircle(audioBtnRect.centerX(), audioBtnRect.centerY(),
                audioBtnRect.width() / 2f, audioBtnBgPaint);
        float cx = audioBtnRect.centerX(), cy = audioBtnRect.centerY();
        if (audioPlaying) {
            float barW = AUDIO_PAUSE_BAR_W_DP * density;
            float barH = AUDIO_PAUSE_BAR_H_DP * density;
            float gap = AUDIO_PAUSE_BAR_GAP_DP * density;
            canvas.drawRoundRect(cx - gap / 2f - barW, cy - barH / 2f, cx - gap / 2f, cy + barH / 2f,
                    barW / 3f, barW / 3f, audioBtnIconPaint);
            canvas.drawRoundRect(cx + gap / 2f, cy - barH / 2f, cx + gap / 2f + barW, cy + barH / 2f,
                    barW / 3f, barW / 3f, audioBtnIconPaint);
        } else {
            float triR = (AUDIO_PLAY_TRIANGLE_DP * density) / 2f;
            audioPlayTrianglePath.reset();
            audioPlayTrianglePath.moveTo(cx - triR * 0.5f, cy - triR * 0.85f);
            audioPlayTrianglePath.lineTo(cx - triR * 0.5f, cy + triR * 0.85f);
            audioPlayTrianglePath.lineTo(cx + triR * 0.95f, cy);
            audioPlayTrianglePath.close();
            canvas.drawPath(audioPlayTrianglePath, audioBtnIconPaint);
        }

        // ── Waveform ── same shape/placement logic as AudioWaveformView's
        // drawBars(): fixed bar count, rounded bars centered vertically in
        // the track, drawn once per onDraw (cheap enough — only the
        // currently-playing bubble redraws every 250ms tick).
        int n = audioLevels.length;
        if (n > 0) {
            float slot = audioWaveformRect.width() / n;
            float barWidth = slot * (1f - AUDIO_BAR_GAP_RATIO);
            float radius = barWidth / 2f;
            float centerY = audioWaveformRect.centerY();
            float trackH = audioWaveformRect.height();
            float playedRightEdge = audioWaveformRect.left + audioWaveformRect.width() * audioProgress;
            float x = audioWaveformRect.left;
            for (float lvl : audioLevels) {
                float barHeight = Math.max(barWidth, lvl * trackH);
                boolean played = (x + barWidth / 2f) <= playedRightEdge;
                canvas.drawRoundRect(x, centerY - barHeight / 2f, x + barWidth, centerY + barHeight / 2f,
                        radius, radius, played ? audioWaveformPlayedPaint : audioWaveformIdlePaint);
                x += slot;
            }
        }

        // ── Elapsed-time label ── right-aligned in its fixed slot just
        // past the waveform; empty (idle) until playback actually starts,
        // same as the legacy tv_audio_dur.
        if (!audioElapsedText.isEmpty()) {
            float baselineY = audioWaveformRect.centerY()
                    - (audioDurPaint.ascent() + audioDurPaint.descent()) / 2f;
            canvas.drawText(audioElapsedText, bubbleRect.right - hPad, baselineY, audioDurPaint);
        }

        drawFooter(canvas, bubbleRect.bottom - vPad * 0.4f, bubbleRect.right - hPad);
    }

    /**
     * Draws the single-media download gate: a dim scrim over mediaRect plus
     * a centered pill — idle "⬇ <label>" (tap to start) when !mediaDownloading,
     * or a live spinner/percentage ring while mediaDownloading is true. See
     * setMediaDownloadGate()/setMediaDownloadProgress().
     */
    private void drawMediaDownloadGate(Canvas canvas) {
        float r = MEDIA_CORNER_RADIUS_DP * density;
        canvas.drawRoundRect(mediaRect, r, r, mediaGateScrimPaint);

        String label = mediaDownloading
                ? (mediaDownloadProgress >= 0 ? mediaDownloadProgress + "%" : "")
                : (mediaDownloadLabel.isEmpty() ? "Photo" : mediaDownloadLabel);

        float iconSize = GROUP_GATE_PILL_ICON_DP * density;
        float iconGap = GROUP_GATE_PILL_ICON_GAP_DP * density;
        float padH = GROUP_GATE_PILL_PAD_H_DP * density;
        float padV = GROUP_GATE_PILL_PAD_V_DP * density;
        float textW = label.isEmpty() ? 0 : mediaGatePillTextPaint.measureText(label);
        float contentH = Math.max(iconSize, mediaGatePillTextPaint.descent() - mediaGatePillTextPaint.ascent());
        float pillW = padH * 2 + iconSize + (label.isEmpty() ? 0 : iconGap + textW);
        float pillH = padV * 2 + contentH;
        float cx = mediaRect.centerX(), cy = mediaRect.centerY();
        mediaGatePillRect.set(cx - pillW / 2f, cy - pillH / 2f, cx + pillW / 2f, cy + pillH / 2f);

        float pillR = GROUP_GATE_PILL_CORNER_DP * density;
        canvas.drawRoundRect(mediaGatePillRect, pillR, pillR, mediaGatePillBgPaint);

        float iconCx = mediaGatePillRect.left + padH + iconSize / 2f;
        float iconCy = mediaGatePillRect.centerY();

        if (mediaDownloading) {
            drawProgressRing(canvas, iconCx, iconCy, iconSize, mediaGatePillIconPaint, mediaDownloadProgress);
        } else {
            drawGateIcon(canvas, iconCx, iconCy, iconSize, mediaGatePillIconPaint);
        }

        if (!label.isEmpty()) {
            float textBaselineY = mediaGatePillRect.centerY()
                    - (mediaGatePillTextPaint.ascent() + mediaGatePillTextPaint.descent()) / 2f;
            canvas.drawText(label, iconCx + iconSize / 2f + iconGap, textBaselineY, mediaGatePillTextPaint);
        }
    }

    private void drawMediaGroup(Canvas canvas) {
        float cellR = GROUP_CORNER_R * density;
        for (int i = 0; i < groupVisibleCount; i++) {
            RectF rect = groupRects[i];
            boolean isLastOverlay = (i == groupVisibleCount - 1) && groupRemaining > 0;
            GridItem item = i < groupItems.size() ? groupItems.get(i) : null;
            Bitmap bmp = groupBitmaps[i];

            if (bmp != null) {
                float scale = Math.max(rect.width() / bmp.getWidth(), rect.height() / bmp.getHeight());
                float dx = rect.left - (bmp.getWidth() * scale - rect.width()) / 2f;
                float dy = rect.top - (bmp.getHeight() * scale - rect.height()) / 2f;
                groupShaderMatrix.reset();
                groupShaderMatrix.setScale(scale, scale);
                groupShaderMatrix.postTranslate(dx, dy);
                android.graphics.BitmapShader shader = new android.graphics.BitmapShader(
                        bmp, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
                shader.setLocalMatrix(groupShaderMatrix);
                groupBitmapPaint.setShader(shader);
                canvas.drawRoundRect(rect, cellR, cellR, groupBitmapPaint);
            } else {
                canvas.drawRoundRect(rect, cellR, cellR, groupCellBgPaint);
            }

            if (item != null && item.isVideo && !isLastOverlay) {
                float cx = rect.centerX(), cy = rect.centerY();
                float circleR = (GROUP_PLAY_CIRCLE_DP * density) / 2f;
                canvas.drawCircle(cx, cy, circleR, groupPlayCirclePaint);

                float triR = (GROUP_PLAY_TRIANGLE_DP * density) / 2f;
                groupPlayTrianglePath.reset();
                groupPlayTrianglePath.moveTo(cx - triR * 0.5f, cy - triR * 0.8f);
                groupPlayTrianglePath.lineTo(cx - triR * 0.5f, cy + triR * 0.8f);
                groupPlayTrianglePath.lineTo(cx + triR * 0.9f, cy);
                groupPlayTrianglePath.close();
                canvas.drawPath(groupPlayTrianglePath, groupPlayTrianglePaint);

                boolean hasItemCaption = item.caption != null && !item.caption.isEmpty();
                if (item.duration != null && !item.duration.isEmpty()) {
                    float durPadH = 3 * density, durPadV = 1 * density;
                    float textW = groupDurationTextPaint.measureText(item.duration);
                    float textH = groupDurationTextPaint.descent() - groupDurationTextPaint.ascent();
                    RectF durBg;
                    if (hasItemCaption) {
                        // Duration moves to the top-end corner so it doesn't
                        // collide with the caption strip pinned to the bottom
                        // (same conflict-avoidance MediaGroupLayoutHelper uses).
                        float right = rect.right - 4 * density;
                        float top = rect.top + 4 * density;
                        durBg = new RectF(right - textW - durPadH * 2, top, right, top + textH + durPadV * 2);
                    } else {
                        float left = rect.left + 4 * density;
                        float bottom = rect.bottom - 4 * density;
                        durBg = new RectF(left, bottom - textH - durPadV * 2, left + textW + durPadH * 2, bottom);
                    }
                    canvas.drawRoundRect(durBg, 3 * density, 3 * density, groupDurationBgPaint);
                    float textBaseline = hasItemCaption
                            ? durBg.top + durPadV - groupDurationTextPaint.ascent()
                            : durBg.bottom - durPadV - groupDurationTextPaint.descent();
                    canvas.drawText(item.duration, durBg.left + durPadH, textBaseline, groupDurationTextPaint);
                }
            }

            // Per-item caption: small gradient strip + single-line ellipsized
            // text pinned to this cell's bottom edge — mirrors
            // MediaGroupLayoutHelper's per-item caption exactly, just Canvas-
            // drawn. Skipped on the "+N" overflow cell (nothing legible fits
            // under the dark overlay+count already covering it).
            if (item != null && !isLastOverlay && item.caption != null && !item.caption.isEmpty()) {
                float stripH = GROUP_ITEM_CAPTION_STRIP_H_DP * density;
                float stripTop = rect.bottom - stripH;
                android.graphics.Shader grad = new android.graphics.LinearGradient(
                        0, stripTop, 0, rect.bottom, 0x00000000, 0x99000000, android.graphics.Shader.TileMode.CLAMP);
                groupItemCaptionScrimPaint.setShader(grad);
                canvas.drawRect(rect.left, stripTop, rect.right, rect.bottom, groupItemCaptionScrimPaint);

                float margin = GROUP_ITEM_CAPTION_MARGIN_DP * density;
                float maxTextW = rect.width() - margin * 2;
                CharSequence ellipsized = TextUtils.ellipsize(item.caption, groupItemCaptionPaint, maxTextW, TextUtils.TruncateAt.END);
                float baseline = rect.bottom - GROUP_ITEM_CAPTION_BOTTOM_DP * density - groupItemCaptionPaint.descent();
                canvas.drawText(ellipsized, 0, ellipsized.length(), rect.left + margin, baseline, groupItemCaptionPaint);
            }

            if (isLastOverlay) {
                canvas.drawRoundRect(rect, cellR, cellR, groupMoreOverlayPaint);
                canvas.drawText("+" + groupRemaining, rect.centerX(),
                        rect.centerY() - (groupMoreTextPaint.ascent() + groupMoreTextPaint.descent()) / 2f,
                        groupMoreTextPaint);
            }

            // Per-cell download badge — only drawn once the master gate
            // pill (below) has been dismissed; while the gate is up it
            // alone covers the whole grid, same as the old View-based
            // master overlay sitting on top of every per-cell overlay.
            if (!groupGateActive && !isLastOverlay
                    && i < groupCellPending.length && groupCellPending[i]) {
                canvas.drawRoundRect(rect, cellR, cellR, groupCellGateDimPaint);
                float cx = rect.centerX(), cy = rect.centerY();
                float badgeR = (GROUP_CELL_GATE_BADGE_DP * density) / 2f;
                canvas.drawCircle(cx, cy, badgeR, groupCellGateBadgeBgPaint);
                boolean downloading = i < groupCellDownloading.length && groupCellDownloading[i];
                if (downloading) {
                    int prog = i < groupCellProgress.length ? groupCellProgress[i] : -1;
                    drawProgressRing(canvas, cx, cy, GROUP_CELL_GATE_ICON_DP * density, groupCellGateIconPaint, prog);
                } else {
                    drawGateIcon(canvas, cx, cy, GROUP_CELL_GATE_ICON_DP * density, groupCellGateIconPaint);
                }
            }
        }

        if (groupGateActive) {
            // Master "Download N photos" pill — mirrors
            // MediaGroupLayoutHelper.addMasterDownloadOverlay(): a single
            // dim scrim over the whole grid with a centered pill, tap
            // anywhere in the grid to dismiss + start every pending cell.
            canvas.drawRect(groupContentRect, groupGateScrimPaint);

            String label = "Download " + groupGatePendingCount
                    + (groupGatePendingCount == 1 ? " photo" : " photos");
            float iconSize = GROUP_GATE_PILL_ICON_DP * density;
            float iconGap = GROUP_GATE_PILL_ICON_GAP_DP * density;
            float padH = GROUP_GATE_PILL_PAD_H_DP * density;
            float padV = GROUP_GATE_PILL_PAD_V_DP * density;
            float textW = groupGatePillTextPaint.measureText(label);
            float contentH = Math.max(iconSize, groupGatePillTextPaint.descent() - groupGatePillTextPaint.ascent());
            float pillW = padH * 2 + iconSize + iconGap + textW;
            float pillH = padV * 2 + contentH;
            float cx = groupContentRect.centerX(), cy = groupContentRect.centerY();
            groupGatePillRect.set(cx - pillW / 2f, cy - pillH / 2f, cx + pillW / 2f, cy + pillH / 2f);

            float pillR = GROUP_GATE_PILL_CORNER_DP * density;
            canvas.drawRoundRect(groupGatePillRect, pillR, pillR, groupGatePillBgPaint);

            float iconCx = groupGatePillRect.left + padH + iconSize / 2f;
            float iconCy = groupGatePillRect.centerY();
            drawGateIcon(canvas, iconCx, iconCy, iconSize, groupGatePillIconPaint);

            float textBaselineY = groupGatePillRect.centerY()
                    - (groupGatePillTextPaint.ascent() + groupGatePillTextPaint.descent()) / 2f;
            canvas.drawText(label, iconCx + iconSize / 2f + iconGap, textBaselineY, groupGatePillTextPaint);
        }

        if (groupHasCaption && groupCaptionLayout != null) {
            float scrimTop = groupContentRect.bottom - GROUP_CAPTION_SCRIM_H_DP * density;
            android.graphics.Shader grad = new android.graphics.LinearGradient(
                    0, scrimTop, 0, groupContentRect.bottom,
                    0x00000000, 0xAA000000, android.graphics.Shader.TileMode.CLAMP);
            groupScrimPaint.setShader(grad);
            canvas.drawRect(groupContentRect.left, scrimTop, groupContentRect.right, groupContentRect.bottom, groupScrimPaint);

            canvas.save();
            canvas.translate(groupContentRect.left + 4 * density,
                    groupContentRect.bottom - groupCaptionLayout.getHeight() - 4 * density);
            groupCaptionLayout.draw(canvas);
            canvas.restore();
        } else {
            // Captionless group: translucent timestamp/tick pill overlaid
            // on the grid's bottom-right corner — same treatment as the
            // single-image bubble's captionless pill.
            float rr = MEDIA_PILL_CORNER_DP * density;
            canvas.drawRoundRect(mediaPillRect, rr, rr, mediaPillBgPaint);
            float pillPadH = MEDIA_PILL_PADDING_H_DP * density;
            float textBaselineY = mediaPillRect.bottom - (mediaPillRect.height()
                    - (mediaPillTextPaint.descent() - mediaPillTextPaint.ascent())) / 2f
                    - mediaPillTextPaint.descent();
            float tickReserve = sent ? (TICK_SIZE_DP + TICK_GAP_DP) * density : 0;
            canvas.drawText(footerTimeText,
                    mediaPillRect.right - pillPadH - tickReserve - mediaPillTextPaint.measureText(footerTimeText),
                    textBaselineY, mediaPillTextPaint);
            if (sent) {
                drawTick(canvas, mediaPillRect.right - pillPadH - TICK_SIZE_DP * density, textBaselineY);
            }
        }
    }

    /** Extra width (text + trailing gap) the "⏳ mm:ss" countdown reserves in
     *  the footer row, ahead of the timestamp — 0 when there's no active expiry. */
    private float expiryReserveWidth() {
        return hasExpiry ? expiryPaint.measureText(expiryText) + FOOTER_GAP_DP * density : 0f;
    }

    /**
     * Draws the link-preview card computed in onMeasure — rounded card
     * background (same #33000000/#22000000 sent/received treatment as
     * the reply-preview strip), an optional top thumbnail band
     * (centerCrop bitmap, or a plain placeholder box while it's still
     * loading), then the domain row and bold title underneath. Card tap
     * handling lives in onTouchEvent (linkCardRect hit-test).
     */
    private void drawLinkPreview(Canvas canvas) {
        float r = LINK_PREVIEW_CORNER_DP * density;
        canvas.drawRoundRect(linkCardRect, r, r, linkCardBgPaint);

        float textLeft = linkCardRect.left + LINK_PREVIEW_PAD_H_DP * density;
        float textRight = linkCardRect.right - LINK_PREVIEW_PAD_H_DP * density;
        float cursorY;

        if (linkHasThumb) {
            if (linkThumbBitmap != null) {
                // Rounded-top-corner centerCrop, same BitmapShader technique
                // drawMedia() uses for the single-image bubble — clipped to
                // the card's own round-rect so only the top two corners
                // actually round (bottom corners are covered by the text
                // column below, so a full round-rect clip reads correctly).
                float scale = Math.max(linkThumbRect.width() / linkThumbBitmap.getWidth(),
                        linkThumbRect.height() / linkThumbBitmap.getHeight());
                float dx = linkThumbRect.left - (linkThumbBitmap.getWidth() * scale - linkThumbRect.width()) / 2f;
                float dy = linkThumbRect.top - (linkThumbBitmap.getHeight() * scale - linkThumbRect.height()) / 2f;
                linkThumbShaderMatrix.reset();
                linkThumbShaderMatrix.setScale(scale, scale);
                linkThumbShaderMatrix.postTranslate(dx, dy);

                android.graphics.BitmapShader shader = new android.graphics.BitmapShader(
                        linkThumbBitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
                shader.setLocalMatrix(linkThumbShaderMatrix);
                linkThumbPaint.setShader(shader);
                int saveCount = canvas.save();
                android.graphics.Path clip = new android.graphics.Path();
                clip.addRoundRect(linkCardRect, r, r, android.graphics.Path.Direction.CW);
                canvas.clipPath(clip);
                canvas.drawRect(linkThumbRect, linkThumbPaint);
                canvas.restoreToCount(saveCount);
            } else {
                // Not decoded yet — plain placeholder band, same rounded-top shape.
                int saveCount = canvas.save();
                android.graphics.Path clip = new android.graphics.Path();
                clip.addRoundRect(linkCardRect, r, r, android.graphics.Path.Direction.CW);
                canvas.clipPath(clip);
                canvas.drawRect(linkThumbRect, linkThumbPlaceholderPaint);
                canvas.restoreToCount(saveCount);
            }
            cursorY = linkThumbRect.bottom + LINK_PREVIEW_PAD_TOP_DP * density;
        } else {
            cursorY = linkCardRect.top + LINK_PREVIEW_PAD_TOP_DP * density;
        }

        if (!linkDomain.isEmpty()) {
            Paint.FontMetrics dfm = linkDomainPaint.getFontMetrics();
            float baselineY = cursorY - dfm.ascent;
            canvas.drawText(linkDomain.toUpperCase(java.util.Locale.getDefault()), textLeft, baselineY, linkDomainPaint);
            cursorY = baselineY + dfm.descent + LINK_PREVIEW_TITLE_GAP_DP * density;
        }

        if (linkTitleLayout != null) {
            canvas.save();
            canvas.translate(textLeft, cursorY);
            linkTitleLayout.draw(canvas);
            canvas.restore();
        }
    }

    private void drawFooter(Canvas canvas, float footerBaselineY, float footerRightX) {
        float timeX = footerRightX - footerPaint.measureText(footerTimeText)
                - (sent ? (TICK_SIZE_DP + TICK_GAP_DP) * density : 0);
        canvas.drawText(footerTimeText, timeX, footerBaselineY, footerPaint);

        if (hasExpiry) {
            canvas.drawText(expiryText, timeX - expiryReserveWidth(), footerBaselineY, expiryPaint);
        }

        if (sent) {
            drawTick(canvas, footerRightX - TICK_SIZE_DP * density, footerBaselineY);
        }
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

    private final RectF gateIconArcRect = new RectF();

    /**
     * Draws the IDLE download-gate glyph at (cx, cy) sized to `size`: a
     * simple download arrow (vertical stroke + arrowhead + tray). Used for
     * the "tap to download" state, before anything is in flight. Once a
     * download starts, drawProgressRing() takes over instead (live
     * spinner/percentage) — see setMediaDownloadGate()/setGroupCellProgress().
     */
    private void drawGateIcon(Canvas canvas, float cx, float cy, float size, Paint paint) {
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
    private void drawProgressRing(Canvas canvas, float cx, float cy, float size, Paint paint, int percent) {
        float r = size / 2f;
        gateIconArcRect.set(cx - r, cy - r, cx + r, cy + r);
        if (percent >= 0) {
            canvas.drawArc(gateIconArcRect, -90, 360f * (Math.min(percent, 100) / 100f), false, paint);
        } else {
            long now = android.os.SystemClock.uptimeMillis();
            float rotation = (now % INDETERMINATE_PERIOD_MS) / (float) INDETERMINATE_PERIOD_MS * 360f;
            canvas.drawArc(gateIconArcRect, rotation - 90, INDETERMINATE_SWEEP_DEG, false, paint);
            postInvalidateOnAnimation();
        }
    }

    private void drawTick(Canvas canvas, float x, float baselineY) {
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

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // NOTE (known simplification): reply-box/image hit-tests intercept
        // ACTION_UP directly without feeding it back through gestureDetector,
        // so GestureDetector's internal down/up bookkeeping is slightly out
        // of sync after such a tap. Harmless in practice (each tap is a
        // fresh down/up pair) but worth revisiting if double-tap-to-zoom or
        // similar multi-event gestures are added to this view later.
        if (hasReply && event.getActionMasked() == MotionEvent.ACTION_UP
                && replyBoxRect.contains(event.getX(), event.getY())) {
            if (clickListener != null) clickListener.onReplyPreviewClick();
            return true;
        }
        if (hasLinkPreview && event.getActionMasked() == MotionEvent.ACTION_UP
                && linkCardRect.contains(event.getX(), event.getY())) {
            // Whole card opens the link — mirrors the legacy
            // ll_link_preview.setOnClickListener (ACTION_VIEW browser intent).
            if (clickListener != null) clickListener.onLinkClick(linkPreviewUrl);
            return true;
        }
        if (hasReactions && event.getActionMasked() == MotionEvent.ACTION_UP
                && reactionsRect.contains(event.getX(), event.getY())) {
            if (clickListener != null) clickListener.onReactionsClick();
            return true;
        }
        if (isAudio) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP && audioBtnRect.contains(event.getX(), event.getY())) {
                if (clickListener != null) clickListener.onAudioPlayPauseClick();
                return true;
            }
            // Scrub the waveform like AudioWaveformView.onTouchEvent — live
            // drag, not just a tap-to-seek, so DOWN and MOVE both count.
            if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE)
                    && audioWaveformRect.contains(event.getX(), event.getY())) {
                float fraction = Math.max(0f, Math.min(1f,
                        (event.getX() - audioWaveformRect.left) / audioWaveformRect.width()));
                setAudioProgress(fraction);
                if (clickListener != null) clickListener.onAudioSeek(fraction);
                return true;
            }
            if (action == MotionEvent.ACTION_UP && audioWaveformRect.contains(event.getX(), event.getY())) {
                return true; // already handled by the DOWN/MOVE branch above
            }
        }
        if (isMedia && event.getActionMasked() == MotionEvent.ACTION_UP
                && mediaRect.contains(event.getX(), event.getY())) {
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
            if (clickListener != null) clickListener.onContactViewClick();
            return true;
        }
        if (isLocation && event.getActionMasked() == MotionEvent.ACTION_UP
                && locationButtonRect.contains(event.getX(), event.getY())) {
            // Only the "Open in Maps" row is clickable — mirrors the
            // legacy btnOpenMaps.setOnClickListener; the rest of the card
            // has no click listener in the legacy path either, just
            // long-press for the action sheet (handled below by
            // gestureDetector, same as every other mode).
            if (clickListener != null) clickListener.onLocationOpenMapsClick();
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
                    groupGateActive = false;
                    invalidate();
                    if (clickListener != null) clickListener.onGroupDownloadAllClick();
                    return true;
                }
            } else {
                for (int i = 0; i < groupVisibleCount; i++) {
                    if (groupRects[i].contains(x, y)) {
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

    private float spToPx(float sp) {
        return sp * getContext().getResources().getDisplayMetrics().scaledDensity;
    }
}
