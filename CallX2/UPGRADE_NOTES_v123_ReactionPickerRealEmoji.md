# v123 — Reaction picker: colored circles bug fixed

## The bug
Long-press → reaction row was showing 6 flat-colored circles (pink, blue,
yellow, orange, blue, red) instead of ❤️👍😂😮😢😡. Tapping a circle still
applied the *correct* emoji to the message (because `onReact()` always used
`reaction.unicode`, not whatever the slot displayed) — so the picker preview
and the applied reaction were out of sync, which is what looked like "old
static emoji apply hote hain on click."

## Root cause
The reaction row tries to play an RLottie animation per slot, loaded from
`LottieAssetCache` (populated by `EmojiPackDownloadWorker` from the server's
`emoji-assets/*.json` manifest). That pipeline (download → validate →
native `loadFromFile()` → live swap-in) was working correctly end-to-end —
the DEBUG summary dialog confirmed every slot resolved "OK: shown from
cache, animated" or "OK: downloaded fresh & swapped in live".

The problem was the *content* of those files, not the pipeline. Every file
in `emoji-assets/` (`heart.json`, `thumb.json`, `laugh.json`, `wow.json`,
`sad.json`, `angry.json`) is a single-layer, single-shape placeholder — one
filled ellipse, each a different flat color:

| file       | fill color (Lottie `c.k`) | what it renders as |
|------------|---------------------------|---------------------|
| heart.json | `0.91, 0.12, 0.39`        | pink circle |
| thumb.json | `0.2, 0.6, 0.86`          | blue circle |
| laugh.json | `0.95, 0.77, 0.06`        | yellow circle |
| wow.json   | `0.95, 0.55, 0.1`         | orange circle |
| sad.json   | `0.2, 0.55, 0.75`         | blue circle |
| angry.json | `0.8, 0.15, 0.15`         | red circle |

These are structurally valid Lottie JSON (`LottieJsonValidator.isSafeToLoad`
correctly passes them), so they were never rejected — they're just not real
emoji artwork. Nobody ever swapped in the actual animated stickers.

## Fix
`MessagePagingAdapter#showActionBottomSheetInner` no longer tries to render
RLottie animations for the quick-reaction row at all. Each slot now always
shows the real unicode glyph (`ReactionEmojiCatalog.Entry.unicode`) — the
exact same value that gets stored in `reactions/{uid}` and shown on the
bubble — with a Telegram-style entrance/tap animation instead of a fake
sticker:

- **Pop-in on open**: slots scale in 0 → 1 with a slight overshoot,
  staggered ~30ms apart left to right (`OvershootInterpolator`).
- **Punch on tap**: the tapped slot scales up ~35% over 90ms before
  `onReact()` fires and the sheet dismisses (`DecelerateInterpolator`).

Because the picker now always renders what will actually be applied, the
mismatch is structurally impossible — there's no cache/download/validate
path left to disagree with the stored value.

`LottieAssetCache`, `EmojiPackDownloadWorker`, `LottieJsonValidator`, and
`RLottieViewWrapper` are untouched — they still back the empty-chat-state
animation. If real animated emoji Lottie/TGS artwork is supplied later, the
quick-reaction loop can be pointed back at them, but content should be
checked for "is this actually the right emoji," not just "is this valid
Lottie JSON" (a colored dot passes that check too).

## What to actually fix the animation (optional, follow-up)
The picker will look great as-is (real emoji + bounce), but if animated
reaction stickers are still wanted Telegram-style, `emoji-assets/*.json` on
the server need to be replaced with real emoji Lottie animations (e.g.
sourced from an actual animated emoji set), not placeholder dots. Nothing
on the Android side needs to change to support that later — just correct
content behind the same `EmojiPackDownloadWorker` / cache-key contract.
