# v425 — Image + Voice Caption: chat-open bind / allocate / draw pass

Scope: only the "image with attached voice note (+ caption)" bubble path in `feature-chat`
and the shared helpers it touches. No behaviour or visual change intended.

## What was wasted on every chat open, and what changed

| # | Where | Problem | Fix |
|---|-------|---------|-----|
| 1 | `item_message_sent/received.xml` + `MessagePagingAdapter.VH` | Legacy voice badge (`fl_voice_on_image` + child LinearLayout/ImageView/TextView), caption scrim and caption TextView were inflated in **every** legacy holder, 5 `findViewById`s + a click listener each — although image+voice is Canvas-eligible (`isCanvasEligible`) so that overlay is a fallback that is normally never reached. | Moved into a `ViewStub` → new `layout_msg_voice_on_image.xml`. `ensureLegacyVoiceOverlay(h)` inflates on demand (same views, same geometry, same click behaviour) if a legacy holder ever really needs it. |
| 2 | `MediaRenderer` | 3 Paints + 2 Paths + 1 RectF for the voice badge were allocated in the constructor of **every** `MessageBubbleCanvasView` (text-only bubbles included). | Built lazily on first voice-caption draw (`ensureVoiceRes()`). |
| 3 | `MessageBubbleCanvasView.setVoiceCaption` | The "same state → skip invalidate" guard was dead: `bindMedia()` nulls `voiceUrl` right before it, so the guard always saw `null` and every bind paid an extra `invalidate()`. | `bindMedia()` parks the previous url/duration (`prevVoiceUrl/prevVoiceDuration`); `setVoiceCaption()` compares against them and just re-arms without a 2nd invalidate. |
| 4 | `MessageBubbleCanvasView` (text / image caption / album caption) | `MarkdownFormatter.format()` + `EmojiCompat.process()` ran on every bind of every row. | `formatBody()` + 256-entry LRU (only caches once EmojiCompat is READY, texts ≤ 2000 chars). Result is read-only (StaticLayout), so sharing is safe. |
| 5 | `MessagePagingAdapter.wireCaptionReadMore` | New capturing lambda on every image/caption/album bind. | Listener built once per VH; reads message id + owning adapter at click time (`h.canvasListenerOwner`), does not capture `this` (safe with the shared static RecycledViewPool). |
| 6 | `MessagePagingAdapter` image branch (received, not-yet-downloaded photo) | `MediaE2ECrypto.decryptEnvelopeForMessage` (decrypt + JSON parse) on the **main thread on every bind** just to read the ThumbHash. | `resolveEnvelopeBlurHash()` + LRU keyed by messageId (failures are not cached). |
| 7 | `bindVoiceOnImage` (legacy) | `String.format()` per bind. | Uses the existing cached `formatVoiceDuration()`. |

## Files changed
- `feature-chat/src/main/java/com/callx/app/conversation/MessagePagingAdapter.java`
- `feature-chat/src/main/java/com/callx/app/conversation/canvas/MessageBubbleCanvasView.java`
- `feature-chat/src/main/java/com/callx/app/conversation/canvas/MediaRenderer.java`
- `feature-chat/src/main/res/layout/item_message_sent.xml`
- `feature-chat/src/main/res/layout/item_message_received.xml`
- `feature-chat/src/main/res/layout/layout_msg_voice_on_image.xml` (new)

All inside `feature-chat` (per CONTRIBUTING rules — nothing copied into `app/`).

## Correction to the earlier analysis
The static Picture/RenderNode cache in `drawMediaWithOptionalCache()` is only used while an
*indeterminate download spinner* is active. During voice playback the bubble is simply
redrawn directly (about once a second, when the elapsed label changes) — cheap on a
hardware canvas, so no change was made there.

## Verify after building
1. Open a chat that has photo+voice(+caption) messages — badge, duration, caption look as before; tap play/pause/speed/save-audio.
2. Scroll fast up/down: badge must not stick on recycled bubbles that have no voice note.
3. Long caption → "Read more / Read less" still expands/collapses and keeps scroll anchor.
4. Open chat A, back, open chat B (shared RecycledViewPool): Read-more in B must expand B's rows.
5. Received, not-downloaded E2E photo shows its ThumbHash placeholder.

Note: built without a Gradle/Android SDK in the authoring environment — Java was only
syntax-checked (javac parse), XML validated. Please run a normal build.
