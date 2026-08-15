# UPGRADE NOTES v260 — Countdown Sticker Expiry Push (Status)

## Problem
UPGRADE_NOTES_v209_CountdownStickerFullFlow.md shipped the full client-side
half of the ⏳ Countdown sticker's "🔔 Remind me" flow — subscribe/unsubscribe,
persistence, and notifying the poster that someone subscribed — but flagged
one piece as a "Backend TODO": actually notifying a subscribed *viewer* the
moment the countdown hits zero. That needs something watching the clock even
when nobody has the status screen open, and there's no cron/scheduler
available in a client-only Android app.

## Solution
Added `notifyExpiredCountdownStickers` to `functions/index.js` — a scheduled
Cloud Function (same `functions.pubsub.schedule(...)` pattern already used
by `cleanupExpiredPairingSessions` in this file) that runs every 5 minutes:

1. Scans `status/{ownerUid}/{statusId}` for a countdown sticker
   (`stickersJson` entry with `type: "countdown"`) whose `targetDate` has
   passed.
2. For each subscriber under that sticker's
   `stickerSubscribers/{stickerIndex}/{viewerUid}` who hasn't been notified
   yet, looks up their `users/{uid}/fcmToken` and sends one FCM push via
   `firebase-admin/messaging`.
3. Marks `notified: true` on that subscriber entry so the same viewer is
   never pushed twice, even across multiple 5-minute runs.

This is a push notification only — it does not send the quoted chat DM the
subscribe-time flow sends, since building that message requires the same
on-device E2E encryption the chat pipeline uses for every other message, and
an Admin-privileged backend function has no legitimate reason to hold that.

## Known limitations (documented in code, not silently swallowed)
- **Scale**: like `cleanupExpiredPairingSessions`, this walks the whole
  `status` node once per run — fine at current data volume (statuses
  self-expire after 24h already); a write-time index
  (`countdownExpiryQueue/{targetDateEpochDay}/...`) would be the fix if that
  ever becomes a real cost.
- **Timezone**: `targetDate` is a bare `"yyyy-MM-dd"` with no timezone
  attached (the composer never captured one, and the client itself parses it
  in the device's default timezone). This function treats it as UTC
  midnight — close enough to match the client within a few hours, but
  tightening it further means changing the sticker JSON shape itself
  (storing an explicit UTC epoch), which would touch the composer, the
  overlay view, and every already-posted countdown's stored JSON.

## Files changed
- `functions/index.js` — added `notifyExpiredCountdownStickers`.
- `functions/package.json` — description updated to mention the new function.

## Deploy
Same as the existing Linked Devices function:
```
cd functions && npm install
firebase deploy --only functions
```
