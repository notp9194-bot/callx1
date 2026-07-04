# Emoji Reaction — Background/Killed Notification System (v45-12)

## Problem
`ChatReactionController` (1:1) and `GroupChatActivity#sendReaction()` (group)
wrote emoji reactions straight to Firebase + Room, but never told the other
person. If their app was backgrounded or fully killed, they had **zero** way
of knowing someone reacted to their message — no push, no notification.

## Fix
Added a full FCM data-push pipeline for reactions, same background/killed-safe
pattern already used for messages, calls, and status reactions.

### Flow
```
tap emoji (add/change only, not remove)
   → PushNotify.notifyMessageReaction() / notifyGroupMessageReaction()
   → POST /notify   (server)
   → FCM data push → toUid = message's ORIGINAL AUTHOR
   → CallxMessagingService.handleMessageReaction() / handleGroupMessageReaction()
   → rich notification (channel: callx_message_reactions)
   → tap → opens ChatActivity / GroupChatActivity
```

Un-reacting (tapping the same emoji again to remove it) stays silent —
matches WhatsApp behavior.

Only the reacted-to message's **author** is notified — for group chat this
is a single targeted push via `/notify`, not a group-wide fan-out via
`/notify/group`, so the rest of the group isn't pinged for a reaction that
isn't about them.

## Files changed

**App (Android)**
- `core/.../utils/Constants.java` — new `CHANNEL_REACTIONS` channel id.
- `app/.../CallxApp.java` — registers the new notification channel.
- `core/.../utils/PushNotify.java` — `notifyMessageReaction()`,
  `notifyGroupMessageReaction()`.
- `feature-chat/.../controllers/ChatReactionController.java` — fires the
  notification from `toggleReaction()` (1:1).
- `feature-chat/.../group/GroupChatActivity.java` — fires the notification
  from `sendReaction()` (group).
- `app/.../services/CallxMessagingService.java` — dispatches
  `"message_reaction"` / `"group_message_reaction"` FCM types to new
  `handleMessageReaction()` / `handleGroupMessageReaction()` handlers that
  build and post the notification (works with app backgrounded OR killed).

**Server**
- `index.js` → `POST /notify` — now accepts `reaction`, `groupId`,
  `groupName` and passes them through in the FCM data payload for
  `message_reaction` / `group_message_reaction` types. Reuses all existing
  block/mute logic (blocked/muted senders' reactions are dropped/quiet the
  same way a normal message would be).

No new server endpoint was needed — reactions route through the same
generic `/notify` endpoint chat messages already use.
