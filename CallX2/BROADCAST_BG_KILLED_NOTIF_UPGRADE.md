# Broadcast List — Background/Killed Notification Upgrade

## Problem
`BroadcastFCMHandler` (type `"broadcast_message"`) existed but **nothing ever
sent that push** — it was dead code. Recipients already got their message via
the normal 1-on-1 chat notification pipeline (fine, and privacy-correct —
same as WhatsApp Broadcast, recipients can't tell it came from a list). But
the **sender** had zero feedback once they backgrounded/killed the app right
after hitting Send — `BroadcastDeliveryWorker` (WorkManager) kept working
correctly in the background, but there was no notification telling the
sender it finished (or failed).

## Fix

### App (zip)
1. **`BroadcastDeliveryWorker.java`**
   - After a fan-out finishes (success, partial, or failure — including the
     "no recipients" and "max retries exhausted" edge cases), the worker now:
     a. Posts a **local notification directly** (`showLocalCompletionNotification`)
        — zero network dependency, fires the instant `doWork()` completes,
        works identically whether the app is foreground/background/killed
        since WorkManager runs independent of any Activity/UI.
     b. Calls `PushNotify.notifyBroadcastComplete(...)` so the sender's
        **other signed-in devices** also learn delivery finished.
   - Tapping either notification opens `BroadcastChatActivity` for that list.

2. **`PushNotify.java`**
   - New `notifyBroadcastComplete(senderUid, listId, listName, delivered,
     total, skipped, status, msgType, lastMessage)` → `POST /notify/broadcast`.

3. **`BroadcastFCMHandler.java`** (rewritten)
   - Now actually consumes the payload sent by the new server route:
     `list_id`, `list_name`, `delivered`, `total`, `skipped`, `status`.
   - Shows "📢 Broadcast Sent — X/Y ko delivered" or "⚠️ Broadcast Failed".
   - Tap intent now opens `BroadcastChatActivity` (was previously wired to
     open `MainActivity`/chat with recipient-message-style fields that were
     never actually populated by any sender).
   - `ensureChannel()` made package-visible so `BroadcastDeliveryWorker` can
     reuse the same `callx_broadcast` channel for its local notification.

4. **`CallxMessagingService.java`**
   - Comment updated to accurately describe the now-functional
     `"broadcast_message"` routing (was previously documented but unwired).

### Server (`index.js`)
- New route: **`POST /notify/broadcast`**
  - Body: `toUid, listId, listName, delivered, total, skipped, status, msgType, lastMessage`
  - Fetches `toUid`'s `fcmToken`, sends a high-priority (`android.priority: "high"`,
    24h TTL) FCM data message with `type: "broadcast_message"`.
  - Self-notify (sender → sender's other devices) — no block/mute checks
    needed, mirrors the style of `/notify/status` and `/notify/status_reaction`.

## Result
- Recipient side: unchanged — already background/killed-safe (reuses the
  same high-priority data-only FCM pipeline as regular chat messages).
- Sender side: now gets a guaranteed delivery-confirmation notification the
  moment the broadcast finishes sending, **even if the app was backgrounded
  or fully killed** right after hitting Send — via a local notification on
  the sending device plus an FCM push for any other logged-in device.
