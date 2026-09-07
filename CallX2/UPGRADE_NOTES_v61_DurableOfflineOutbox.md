# v61 — Durable offline chat outbox

This revision adds a Room-backed write-ahead queue on top of the existing
Room/Firebase offline-first architecture. The optimistic `messages` row is
still the UI source of truth; `outbox_operations` is the durable record of
what must eventually reach Firebase or Cloudinary.

## Included

- `OutboxOperationEntity` and `OutboxOperationDao`
- Room migration `60 → 61`
- `OutboxSyncWorker`, constrained to a connected network
- Deterministic operation ids for send, media upload, edit, and delete-for-everyone
- Exponential retry with persisted attempt count, next-attempt time, and last error
- Recovery of operations interrupted while the process was killed
- Manual retry resets the persisted backoff
- Offline image, video, audio, and file queueing in 1:1 chat
- Offline group text/media queueing
- Voice-caption local-path retention until its upload completes
- E2EE wire-copy retention so an offline queued message is not reconstructed
  from plaintext at retry time
- Merge rules preventing stale Firebase callbacks from resurrecting tombstones,
  rolling back newer edits, or regressing delivered/read ticks
- Startup scheduling and legacy media-worker race protection

## Important implementation notes

The app is intentionally not built or tested in this upgrade, per the
delivery request. Build, migration validation, Firebase rules validation, and
device/offline lifecycle testing should be performed by the project owner.

The existing Firebase persistence remains useful as a transport cache, but
the new Room outbox is the source of durable mutation intent. Firebase
message ids are preserved across retries, so a retry updates the same node
instead of creating another message.