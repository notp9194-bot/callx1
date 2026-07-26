# v220 — Add Status: multiple stickers no longer overlap

## Bug
When a user added Music + Countdown + Quiz + Question (all 4 sticker
types) on one status, `NewStatusActivity.addStickerOverlay()` positioned
each new card at `topMargin = 16dp + (count * 12dp)` — a flat 12dp
increment regardless of the previous card's real height. Sticker cards
range from ~70dp (music) to 160dp+ (quiz, with its option rows), so with
only 12dp separating each one, all four ended up stacked almost entirely
on top of each other.

## Fix
`addStickerOverlay()` now remembers the real measured bottom edge
(`stickerStackBottomPx`) of the last sticker added, and places the next
one starting `gap (14dp)` below that — via `stickerView.post(...)` once
the card has actually been measured/laid out, since card height depends
on sticker type/content and isn't known before then. The first sticker
still starts near the top (16dp). Result: adding all four types now
spaces them apart down the story canvas instead of piling them up, and
each stays independently draggable afterward if the user wants to
reposition it.
