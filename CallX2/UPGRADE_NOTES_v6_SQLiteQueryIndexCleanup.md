# Optimization 6 — SQLite query/index cleanup

## Room schema/index changes

- Database version bumped from **71 to 72** with a non-destructive migration.
- Added `(chatId, timestamp, id)` for compound keyset paging. The `id`
  tie-breaker is now covered by the index for same-millisecond messages.
- Added `(chatId, senderId, status, timestamp)` for unread-receipt and outgoing
  tick lookups.
- Added `(chatId, type, timestamp)` for the media/links/docs gallery query.

## Query changes

- The media/links/docs screen now asks SQLite to exclude deleted rows and
  irrelevant text-only rows before Java URL matching, instead of loading up to
  5,000 messages and filtering all of them in memory.
- Starred messages are fetched with the active `chatId` directly instead of
  loading every starred message from every chat and discarding the rest in
  Java.
- Decryption self-heal lookups now select only matching message IDs instead
  of materializing full `MessageEntity` rows whose only used field was `id`.

No Gradle build, APK build, emulator test, or automated app test was run.