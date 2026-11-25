# v860 - File / link-preview re-record guards (MessageBubbleCanvasView)

- setFileDownloadState: no-op when downloading flag and percent are unchanged.
- setFileCached: no-op when already cached and not downloading.
- setLinkPreview: no-op when the same card (url/title/domain/hasThumb) is already showing; keeps decoded thumb.
- clearLinkPreview: no-op when already fully clear.

Note: a full text bind() still resets hasLinkPreview and invalidates once, so link-preview gain is mostly on
payload-driven rebinds; the file guards cover repeated progress/cached notifications.
Verify: trace `Bubble.record` while a file bubble gets repeated cached/idle notifications -> no extra records.
