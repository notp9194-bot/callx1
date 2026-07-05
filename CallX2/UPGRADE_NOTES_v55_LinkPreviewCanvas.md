# v55 — Link-Preview Canvas Bubbles

Closes the last gap called out in `MessageBubbleCanvasView`'s class doc:
plain-text messages containing a URL now render their link-preview card
(thumbnail + domain + title) inside Canvas too, instead of silently
dropping it (`bindCanvasMessage()`'s text branch never touched
`ll_link_preview` before this).

## What changed

### `feature-chat/.../conversation/canvas/MessageBubbleCanvasView.java`
- New `LINK_PREVIEW_*` constants — mirror `layout_msg_link_preview.xml`
  (`stub_link_preview`): 120dp thumbnail band, 10sp caps gold domain
  label, bold 13sp 2-line title, `bg_reply_preview_sent/received`'s
  `#33000000`/`#22000000` 6dp-radius card background (reused straight
  off `resolveReplyColors()`, same colors the reply strip already uses).
- `setLinkPreview(url, title, domain, hasThumb)` / `clearLinkPreview()`
  — analogous to `setReply()`/`clearReply()`; only meaningful alongside
  the plain-text `bind()` mode. `hasThumb` is decided up front (from
  `LinkPreviewFetcher.Result.imageUrl` being non-empty) so the card's
  height is correct before the bitmap itself arrives.
- `setLinkPreviewThumbBitmap(bitmap)` — swaps in the decoded OG image
  without a full rebind, same pattern as `setMediaBitmap()`.
- `onMeasure`'s plain-text branch now reserves space for the card below
  the text (own `StaticLayout` for the title, same `maxTextWidth`
  column the text/reply-box already use — unlike the legacy ViewStub's
  fixed 280dp width). `bindMedia()`/`bindMediaGroup()`/`bindReelShare()`
  each reset `hasLinkPreview = false` so a recycled holder can't leak a
  stale card into a non-text bubble.
- `drawLinkPreview()` — rounded card background, `BitmapShader`
  centerCrop for the thumbnail (same technique `drawMedia()` uses),
  domain row, then the title `StaticLayout`.
- `onTouchEvent` — whole-card tap fires the (previously declared but
  unused) `OnBubbleClickListener.onLinkClick(url)`.
- Class doc updated: link-preview moved from "does NOT yet handle" to
  the "DOES now also handle" list.

### `feature-chat/.../conversation/MessagePagingAdapter.java`
- `bindCanvasMessage()`'s plain-text branch now runs the exact same
  `LinkPreviewFetcher.extractFirstUrl()` → `fetch()` flow the legacy
  path uses, just targeting `setLinkPreview()`/
  `setLinkPreviewThumbBitmap()` instead of `TextView`/`ImageView`
  calls. `cv`'s own View tag doubles as the staleness guard (mirrors
  `h.llLinkPreview.getTag()`), since the canvas view has no ViewStub
  children to tag instead.
- `onLinkClick(url)` (previously a no-op stub) now opens the URL in the
  browser — mirrors the legacy `ll_link_preview` click listener.
- No `isCanvasEligible()` change needed — link previews are a sub-
  feature of the already-eligible `"text"` type, not a separate
  message type.

## Known gaps / deliberately out of scope this pass
- Card only appears once the fetch resolves — no reserved "loading"
  placeholder space like the legacy path's `INVISIBLE` card (a cache
  hit is effectively instant either way, so this rarely causes a
  visible jump in practice).
- Tap-on-a-URL-**span**-inside-the-message-text itself (Linkify
  equivalent) is still not modeled — only the whole-card tap is wired
  to `onLinkClick()` right now.

## Status of the Canvas migration
Old View-based path (`item_message_sent/received.xml`) is now used
**only** for: audio, gif, file, poll, contact, location. Every other
message shape — text (with or without a link preview), image,
multi_media, reel_share, video — renders through
`MessageBubbleCanvasView`.
