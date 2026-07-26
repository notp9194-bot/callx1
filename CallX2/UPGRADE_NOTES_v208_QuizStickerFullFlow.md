# UPGRADE NOTES v208 — Quiz Sticker Full Flow (Status)

## Problem
The 💬 Question sticker on Status had a complete flow: tap → answer sheet →
answer sent to the poster as a quoted DM → push notification. The 🧠 Quiz
sticker did not — it just rendered a static card with the correct answer
**already highlighted** (a spoiler) and wasn't tappable at all.

## Solution
Brought the Quiz sticker up to the same full flow as Question:

1. **No more spoiler.** Options now render neutral until the viewer taps one —
   the correct answer is no longer revealed up front.
2. **Tap to answer.** Viewers tap an option; it locks in immediately (green =
   correct, red = your wrong pick, others dim) — same ✓/✗ pattern as
   Instagram-style quiz stickers.
3. **One answer per viewer.** The pick is written to
   `status/{ownerUid}/{statusId}/stickerVotes/{stickerIndex}/{voterUid}` via
   the new `FirebaseUtils.getStatusQuizVoteRef(...)`. Reopening the status
   restores your locked-in answer instead of asking again.
4. **Owner gets notified.** Same as Question: the answer is sent to the
   poster as a quoted chat DM (`StatusReplyBottomSheet.sendQuizAnswer`) plus a
   push notification, via the same `messages/{chatId}/{msgId}` node every
   other status-reply path already uses.

## Files changed
- `feature-status/src/main/java/com/callx/app/stickers/StatusStickerOverlayView.java`
  — quiz options are now individually clickable `TextView`s tracked in
  `quizOptionViews`; added `getQuizQuestion()`, `getQuizOptions()`,
  `getQuizCorrectIndex()`, `isQuizAnswered()`,
  `setOnQuizOptionSelectedListener(...)`, and `revealQuizAnswer(int)`.
- `feature-status/src/main/java/com/callx/app/interactions/StatusReplyBottomSheet.java`
  — added `sendQuizAnswer(...)`, mirroring `sendQuestionReply`'s DM + notify
  path, plus persisting the vote.
- `feature-status/src/main/java/com/callx/app/viewer/StatusViewerActivity.java`
  — `renderStickers()` now wires the quiz sticker: restores a prior vote if
  one exists, otherwise wires the tap listener that reveals the answer,
  persists + DMs it, and resumes the story progress bar after a short delay
  so the viewer has time to see the reveal.
- `core/src/main/java/com/callx/app/utils/FirebaseUtils.java` — added
  `getStatusQuizVoteRef(ownerUid, statusId, stickerIndex, voterUid)`.

## Compatibility
- No new Gradle dependencies.
- No Firebase schema changes to existing nodes — only adds a new
  `stickerVotes` child under the existing `status/{ownerUid}/{statusId}` node.
- Existing quiz stickers already posted still render correctly; the
  `question`/`options`/`correct` fields in `stickersJson` are unchanged.
