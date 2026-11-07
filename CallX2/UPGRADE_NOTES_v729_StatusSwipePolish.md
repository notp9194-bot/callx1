# v729 — Seen-by sheet: story swipe polish (9 items)

File: `feature-status/.../interactions/StatusSeenByBottomSheet.java` + `strings_status_seenby.xml` (en, hi). No other module touched.

1. **Quick double swipe:** swipe state machine (OUT -> WAIT -> IN). A second swipe during the 110 ms slide-out only moves the target, the
   switch then jumps straight there (2 swipes = 2 stories). Nothing is `cancel()`led any more. Swipe during WAIT/IN switches again at once.
2. **No flash on switch:** the list stays hidden (alpha 0) after the switch until `apply()` painted the new rows (max 260 ms), then slides in.
   Skeleton / empty state now live inside the swiped container, so they slide in with it.
3. **TalkBack:** "Next story" / "Previous story" custom actions on the list and the title (only the ones that are possible).
   Counter has a polite live region ("Story 2 of 5").
4. **Counter + hint:** "2/5" at the right of the title (hidden for a single story). First time with 2+ stories: small nudge + "Swipe to switch stories" pill (once, pref `swipe_hint_shown`).
5. **Edge feedback:** haptic tick (CLOCK_TICK) when swiping past the first / last story.
6. **RTL:** swipe direction and slide direction mirrored with the layout direction.
7. **One filter rule on story change:** search text + reaction filter reset, search box closes, keyboard hides.
   Sort order and the "Show" filter stay (remembered setting, visible in the Sort pill).
8. **Swipe area:** works on empty state, skeleton and the search-limit hint too (they moved into the swipe container).
   The top carousel is NOT a swipe area on purpose: it scrolls horizontally by itself and would conflict.
9. **Carousel:** after a switch the selected card is scrolled until it is fully visible (LinearSmoothScroller SNAP_TO_ANY, started after the card resize layout).

## Verify (not run on a device / no gradle here, only brace-balance + read-through)
- Build: `./gradlew :feature-status:assembleDebug`
- Flick 2x quickly -> story moves by 2; flick 3x at the end -> tick, no skeleton flash on any switch.
- TalkBack: swipe up/down on title -> Actions -> Next story.
- Arabic/Urdu locale: swipe right = next.
- Search open + swipe -> search closes, keyboard hides, carousel returns.
