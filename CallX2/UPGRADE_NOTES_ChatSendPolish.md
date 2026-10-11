# Chat send polish

Applied to the CallX2 source archive:

- Text Send stays available offline in direct and group chats. Messages use
  the existing local-first Room/outbox path and retry when connectivity returns;
  voice recording remains disabled while offline.
- The status-only message refresh now renders `pending` with the same clock
  indicator as the full bind, instead of briefly showing a sent checkmark.
- A visible schedule-message clock action appears beside Send while composing
  in direct and group chats. The existing long-press-on-Send shortcut remains.

Per request, no build or tests were run.
