# Offline-first chat reopen upgrade

This upgrade completes the chat reopen path for both 1:1 and group chats:

- A bounded disk snapshot keeps the newest 20 messages for up to 50 recently
  used chats.
- Snapshots are account-scoped and are restored before Room/SQLCipher and
  Firebase initialization, so an app-killed reopen can paint the last-known
  chat state immediately.
- Room/Firebase remain the source of truth. The restored generation is
  reconciled silently by the existing Paging 3 and realtime listener paths.
- Firebase batches and Room reads refresh the disk snapshot in the background.
- The chat list warms visible rows plus an eight-row look-ahead while scrolling.
  Disk is checked first; Room is queried only when no snapshot exists.
- Logout, account switching, and account deletion clear both memory and the
  account's message snapshots so chat content cannot bleed between accounts.
- The first Room/Firebase window is also 20 messages, matching the snapshot
  exactly and avoiding a second visible list-building pass during reconcile.

No build or automated test was run, as requested. Build and device testing can
be performed from the delivered source archive.