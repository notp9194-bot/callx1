# v236 — Hide Status feature (WhatsApp-style)

## What's new
- **Hide a contact's status**: long-press a status card in the Status tab
  carousel → context menu now has a **Hide {name}** option (alongside
  Mute/Unmute and Close Friends) → confirms with a "Hide {name}'s statuses?"
  alert dialog. On confirm, that contact's status disappears from the main
  carousel and the Muted section immediately.
- **Hidden tile**: whenever 1+ contacts are hidden, a trailing "Hidden" card
  (eye-off icon) is appended to the end of the Status carousel.
- **Hidden updates screen**: tapping the Hidden tile opens a new screen
  listing every hidden contact (avatar, name, last-update time). Tap a row to
  view that contact's status normally; long-press a row to unhide (with its
  own confirm dialog), after which it reappears in the normal carousel.

## New files
- `utils/StatusHideManager.java` — SharedPreferences-backed hidden-uid set,
  same pattern as the existing `StatusMuteManager`.
- `hidden/StatusHiddenUpdatesActivity.java` + `activity_status_hidden_updates.xml`
  + `item_status_hidden_row.xml` — the "Hidden updates" list screen.

## Changed files
- `StatusListAdapter.java` — `CardItem` gained an `isHiddenTile` flag +
  `CardItem.hiddenTile()` factory; `update()` gained an `int hiddenCount`
  overload that appends the tile to the carousel; `StatusCardAdapter` renders
  the tile specially (eye-off icon, no ring/avatar/badge) and routes its tap
  to a new `setOnHiddenCardClickListener(Runnable)`.
- `StatusFragment.java` — `rebuildStatusAdapter()` now also splits out a
  `hidden` list (checked before muted/unseen/seen — hidden always wins) and
  passes `hidden.size()` into the adapter; `showContactContextMenu()` gained
  the "Hide" option; new `confirmHideContact()` / `openHiddenUpdates()`
  helpers.
- `AndroidManifest.xml` — registered `StatusHiddenUpdatesActivity`.

## Design notes
- Hide and Mute are intentionally separate, independent local states (own
  SharedPreferences store) — a contact can be muted, hidden, or neither, but
  hidden always takes priority over muted for section placement.
- The Hidden updates screen receives its data via Intent extras (parallel
  String/long arrays) rather than re-querying Firebase, since StatusFragment
  already holds this in memory from its own listeners — keeps the new screen
  a simple local view instead of a duplicate data-loading path.
