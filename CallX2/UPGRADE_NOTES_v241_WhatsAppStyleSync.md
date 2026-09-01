# WhatsApp-style chat sync upgrade

This source snapshot adds the following cache/sync changes:

- Durable per-chat Firebase cursors stored in Room as `(timestamp, messageId)`.
- Inclusive Firebase queries with client-side compound-cursor filtering so
  equal-millisecond messages are not skipped.
- Old-history Firebase pagination now uses the same compound cursor as Room.
- One in-flight delta request per chat; concurrent preload, recovery, and
  WorkManager requests are coalesced.
- Chat list row binding now warms the local cache only. The opened chat's
  realtime listener remains the single live sync path.
- Network recovery no longer launches a second pull while that listener is
  active.

Room schema version is migrated from 53 to 54. This upgrade was source-only;
the Android project was not built or tested in this environment, as requested.