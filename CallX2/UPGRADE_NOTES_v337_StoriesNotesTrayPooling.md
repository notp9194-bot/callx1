# v337 — Stories tray + Notes tray: view pooling

File touched: `feature-reels/src/main/java/com/callx/app/feed/HomeFragment.java`

## What changed
Applied the same "cache the View, refresh the data" rule used for the
header rails (v334 Suggested Creators, Trending/Friends Activity/Continue
Watching) and the inline feed rows (post action bar, Suggested
Creators/Reels/Sponsored) to the last two fresh-rebuild surfaces on Home:

### Stories tray (`containerStories`)
- New `storyRowPool` (`List<View>`) — one physical `item_home_story` View
  per tray slot (index 0 stays the separate "Add Story" button, untouched),
  built once via `obtainStoryRowView(i)` and reused for every future
  `loadStories()` refresh's i-th story.
- New `StoryRowTag` stored via `storyView.setTag(...)` — holds the CURRENT
  `StoryEntry` for that slot plus its cached child-view refs (avatar,
  name, seen-ring, gradient-ring). The row's click listener (opens
  `StatusViewerActivity`) is registered **once**, at inflate time, and
  reads uid/name off this tag at click time — same allocation-free
  pattern `TrendingCardTag`/`SuggestedCreatorCardTag` use — instead of
  capturing a specific `StoryEntry` in a per-inflate closure.
- `addStoryView()` is now `bindStoryRow()`: obtains the pooled row for
  that index and only updates text/ring-visuals/avatar-bind/tag on it —
  no `LayoutInflater.inflate()` per story, per refresh.
- `clearStoriesKeepAddButton()` (which used to `removeViewAt()` every
  story row before each `loadStories()`, specifically to avoid
  duplicating rows since the old `addStoryView()` only ever appended) is
  gone. `bindStoryRow()` now overwrites pool slots by index instead of
  appending, so `refreshStoryRow()` (onResume / story-ring observer) just
  calls `loadStories()` directly — no clear-first step, no destroyed pool.
- New `hideExtraStoryRows(activeCount)` hides (not removes) any pooled
  rows left over from a longer previous story list, cancelling their
  avatar load via `HomeStoryAvatarBinder#cancel` first — same tail-hide
  rule `renderTrending()` uses for `trendingCardPool`.
- `clearAllSections()` no longer touches `containerStories` at all — last
  session's stories stay visible during a pull-to-refresh instead of the
  tray going blank until the new fetch resolves, same as the other rails.

### Notes tray (`containerNotes`)
- New `noteBubblePool` (`List<TextView>`) — one bubble `TextView` per pool
  slot, built once via `obtainNoteBubble(i)` (static styling / rounded
  background / layout params / click listener set up exactly once) and
  reused for every future `loadNotes()` refresh's i-th note.
- The click listener is registered once per pooled bubble and reads the
  bubble's CURRENTLY-tagged `NoteEntry` (`bubble.getTag()`) at click time,
  instead of a fresh listener capturing a specific `NoteEntry` on every
  `addNoteBubble()` call.
- `addNoteBubble()` is now `bindNoteBubble()`: only updates a pooled
  bubble's text + tag, no `new TextView(...)` per note per refresh.
- `loadNotes()`/`finishNotesRead()` no longer `removeAllViews()` the tray
  up front or on every resolve — pooled bubbles beyond the current note
  count are hidden (`GONE`), not destroyed, same as the Stories tray.

## Not touched
Post action bar, inline Suggested Creators/Reels/Sponsored rows, and the
Trending/Friends Activity/Continue Watching header rails — already pooled
(v323/v333/v334). New Posts banner pill, loading spinner, empty-state
text, and load-more footer spinner — left as-is, too lightweight for
pooling to matter.
