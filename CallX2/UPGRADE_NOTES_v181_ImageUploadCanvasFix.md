# v181 — Image Upload Canvas Spinner Fix (WhatsApp-style)

## Bug

Sent-image upload progress spinner and tap-to-retry gate were not showing
during upload — only a gray placeholder box appeared instead.

## Root Cause

`Message.mediaLocalPath` is declared `transient` on the model (so Firebase
serialization ignores it — correct). It IS stored in the Room `MessageEntity`
column. But when the paging layer converts `MessageEntity → Message` it was
**never copied** across.

Two places were affected:

| File | Role |
|------|------|
| `core/.../utils/MessageEntityMapper.toModel()` | Used by 1:1 `ChatActivity.entityToModel()` — the canonical mapper |
| `feature-chat/.../group/GroupChatActivity.entityToModel()` | Local duplicate for group chats — not using the mapper |

Because `m.mediaLocalPath` was always `null` after a Room round-trip, the
adapter's `localPendingMedia` guard:

```java
boolean localPendingMedia = sent
        && m.mediaLocalPath != null && !m.mediaLocalPath.isEmpty()
        && (fullUrl == null || fullUrl.isEmpty());
```

always evaluated `false`, so the code fell through to the "already-uploaded"
path: `clearMediaDownloadGate()` + Glide load from a null URL. Glide's
`onResourceReady` never fired, `mediaBitmap` stayed null, and `mediaGated`
stayed `false` → gray placeholder, no spinner.

## Fix

Added one line to each mapper:

```java
m.mediaLocalPath = e.mediaLocalPath;
```

### `core/src/main/java/com/callx/app/utils/MessageEntityMapper.java`
After the existing `m.mediaWidth / m.mediaHeight` fix block.

### `feature-chat/.../group/GroupChatActivity.java` — `entityToModel()`
After the location fields block, before the PERF precompute block.

## Result

After the fix the full WhatsApp-style local-first flow works end-to-end:

1. User picks an image → local bubble appears instantly with the real image
   thumbnail (from `mediaLocalPath`), correct aspect ratio.
2. Canvas draws the upload gate on top: indeterminate spinner until the first
   `onProgress` tick, then a live `N%` ring as Cloudinary progresses.
3. On failure the gate flips to "Tap to retry" idle pill.
4. On success `mediaUrl` is set, `mediaLocalPath` is kept for local-first
   render on subsequent scrolls (no network, full quality).

No schema migration needed — `mediaLocalPath` was already persisted in Room;
only the read-path mapping was missing.
