# UPGRADE NOTES v209 — Countdown Sticker Full Flow (Status)

## Problem
The ⏳ Countdown sticker on Status was purely decorative — a live-ticking
card with no way for a viewer to do anything with it. Question and Quiz
stickers both had a full tap → persist → notify-the-poster flow; Countdown
had none of that.

## Solution
Added a "🔔 Remind me" row to the countdown card:

1. **Tap to subscribe.** Viewers tap the row to toggle a reminder
   subscription — bell fills in (🔔) and the label flips to "Reminder set ✓".
2. **Persisted per viewer.** Stored at
   `status/{ownerUid}/{statusId}/stickerSubscribers/{stickerIndex}/{viewerUid}`
   via the new `FirebaseUtils.getStatusCountdownSubscriberRef(...)`. Reopening
   the status restores the subscribed state.
3. **Owner gets notified on subscribe.** Same DM + push pattern as
   Question/Quiz — `StatusReplyBottomSheet.sendCountdownSubscription(...)`
   sends a quoted chat message and a push notification to the poster.
4. **Unsubscribing is quiet.** Untapping just deletes the Firebase ref
   directly — no DM is sent, so the poster isn't pinged every time someone
   changes their mind.
5. **Expiry state.** Once the countdown hits zero (either already expired
   when the viewer opens it, or ticking down live), the row locks into
   "⏰ Countdown ended" and stops responding to taps.

## Backend TODO (same caveat as the original interactive-stickers notes)
Actually *notifying* a subscribed viewer at the moment the countdown hits
zero needs a server-side scheduled trigger (e.g. a Cloud Function reading
`stickerSubscribers` and firing pushes when `targetDate` arrives) — there's
no cron/scheduler in this client-only codebase to do that. What's shipped
here is the full client-side flow: subscribe/unsubscribe, persistence, and
notifying the poster that someone subscribed.

## Files changed
- `feature-status/src/main/java/com/callx/app/stickers/StatusStickerOverlayView.java`
  — added the subscribe row UI, `getCountdownLabel()`, `isCountdownSubscribed()`,
  `isCountdownExpired()`, `setOnCountdownSubscribeToggleListener(...)`, and
  `setCountdownSubscribed(...)`; `startCountdown()`'s expiry paths now flip
  the row into its locked "ended" state.
- `feature-status/src/main/java/com/callx/app/interactions/StatusReplyBottomSheet.java`
  — added `sendCountdownSubscription(...)`.
- `feature-status/src/main/java/com/callx/app/viewer/StatusViewerActivity.java`
  — `renderStickers()` now wires the countdown sticker: restores a prior
  subscription if one exists, otherwise wires the toggle listener.
- `core/src/main/java/com/callx/app/utils/FirebaseUtils.java` — added
  `getStatusCountdownSubscriberRef(ownerUid, statusId, stickerIndex, viewerUid)`.

## Compatibility
- No new Gradle dependencies.
- Only adds a new `stickerSubscribers` child under the existing
  `status/{ownerUid}/{statusId}` node — no changes to existing nodes.
- Existing countdown stickers already posted render and tick down exactly as
  before, with the new subscribe row added underneath.
