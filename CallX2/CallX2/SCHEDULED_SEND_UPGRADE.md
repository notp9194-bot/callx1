# Scheduled message send — `ChatScheduledSendController`

Adds WhatsApp/X-style "send later" to 1:1 chat: type a message, long-press
the send button, pick a date+time, and the message auto-sends at that
moment via WorkManager — even if the app is killed in the meantime.
Modeled directly on the X module's `XScheduledPostWorker` / scheduled-post
flow (`XComposeActivity#showSchedulePicker`).

## New pieces

- **`ScheduledMessage`** (`core/models`) — lightweight POJO for a queued
  send. Kept separate from `Message` on purpose: scheduling-only fields
  (`sendAt`, `createdAt`, `partnerUid`) would otherwise sit unused on
  every regular message. Text-only by design — media uploads aren't
  "queueable" the same safe way while offline.
- **`ScheduledMessageEntity` / `ScheduledMessageDao`** (`core/db`) — Room
  cache so the banner/manage list render instantly offline. New table
  `scheduled_messages`, Room DB bumped v13 → v14 (`MIGRATION_13_14`).
- **`ChatScheduledMessageWorker`** (`feature-chat/conversation/workers`)
  — `OneTimeWorkRequest`, same shape as `XScheduledPostWorker`: fetch the
  queued entry → write it into the live `messages/{chatId}` node → update
  contacts/unread/push-notify exactly like a normal send → delete the
  queue entry. Survives process death; retries on failure via
  `BackoffPolicy.EXPONENTIAL`.
- **`ChatScheduledSendController`** (`feature-chat/conversation/controllers`)
  — owns the date/time picker, writes the Firebase + Room queue entry,
  enqueues the worker, and renders the "⏱ Scheduled" banner
  (`ll_scheduled_banner` in `activity_chat.xml`) + a tap-to-manage dialog
  listing every pending entry with a per-row cancel button.
- **`FirebaseUtils#getScheduledMessagesRef(chatId)`** — new node
  `scheduledMessages/{chatId}/{scheduleId}`, fully separate from the live
  `messages/{chatId}` node so a queued message never shows up in the
  thread or unread counts until it actually fires.

## Trigger

Long-press the send button (`btnSend`) with text already typed → date
picker → time picker → confirmation toast. The input box clears exactly
like a normal send (typing status cleared, draft wiped) once scheduling
succeeds.

## Firebase rules

`firebase_rules/firebase_chat_rules.json` — new `scheduledMessages` block:
only the sender can read/write/cancel their own queued entries for a
given chat; `sendAt` must be in the future at write time.

## Not covered (by design, kept v1 scope tight)

- Group chat scheduling (`GroupChatActivity` doesn't wire this controller
  in yet — same gap as message editing).
- Media/poll scheduling — text only.
- Reply-to context on a scheduled message — dropped on purpose; the
  message being replied to could itself be edited/deleted before the
  scheduled send fires.
