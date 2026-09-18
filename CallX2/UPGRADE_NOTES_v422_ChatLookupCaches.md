# v422 — Chat lookup and parsing hot-path optimization

## Scope

This upgrade targets the remaining small linear/parsing costs in the chat
message area. Rendering behavior and message ordering are unchanged.

## Changes

- Added a bounded 256-entry local-media availability LRU and an in-flight
  de-duplication set. Recycled binds no longer enqueue duplicate
  `ContentResolver` probes for the same local URI.
- Added an opportunistic message-id → adapter-position index in
  `MessagePagingAdapter`. Local-media refresh, upload-progress ticks, local
  reaction feedback, and `findMessageById()` now use the cached position when
  valid, validate stale positions, and only use a snapshot scan as a
  defensive miss path.
- Added a small attached-holder fast path for live row updates, which keeps
  the common visible-message case independent of the total loaded history
  size.
- Compiled share URL/username patterns once and added a bounded 64-entry
  first-URL result cache in `ChatActivity`.

## Validation

Build and automated tests were intentionally not run, as requested. The
project archive is ready for the user's own build/test pass.