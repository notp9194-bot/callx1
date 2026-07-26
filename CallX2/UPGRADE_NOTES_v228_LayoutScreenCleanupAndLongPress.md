# v228 — Choose Layout Screen: Remove Duplicate Media Grid, Add Long-Press Replace/Delete

## What changed

**1. Removed the redundant media-selection grid from "Choose Layout" (screen 2)**

`StatusLayoutAdjustActivity` used to embed a *second* full gallery grid +
"Recents ▾" dropdown below the preview — letting the user re-pick media
that "Start layout" (screen 1, `StatusLayoutPickerActivity`) already
picked. Pure duplication, and it squeezed the preview into a cramped fixed
220dp strip.

This screen is layout-only now:
- `activity_status_layout_adjust.xml` — removed the Recents row and the
  `rv_layout_media_grid` RecyclerView entirely; the preview now takes all
  the vertical space those used to occupy (was fixed 220dp, now
  `layout_weight="1"` filling everything above the style row), so
  pinch/pan photo adjustment finally has real room.
- `StatusLayoutAdjustActivity.java` — removed `setupRecentsDropdown()`,
  `setupGridRecycler()`, `onMediaItemToggled()`, `loadGalleryImages()`,
  the `ioExecutor`, and all their backing fields (`gridRecycler`,
  `rowRecents`, `tvRecentsTitle`, `galleryItems`, `gridAdapter`,
  `currentFilterKey`, `rootView`). Media selection lives only on screen 1.

**2. Long-press a photo/video in the preview → Replace or Delete**

Each filled cell can now be long-pressed for a menu:
- **Replace with Photo** — opens the system picker (`image/*`), swaps just
  that slot.
- **Replace with Video** — opens the system picker (`video/*`), swaps just
  that slot with a video.
- **Delete** — removes that slot entirely.

The empty-cell "+" tap (already existing) still opens the picker directly
to fill that slot — unchanged.

- `StatusLayoutPreviewView.java` — new `OnSlotLongPressListener` interface
  + `setOnSlotLongPressListener()`. Long-press detection is done with a
  `GestureDetector` fed the same touch-event stream as the existing
  pinch/pan `ScaleGestureDetector`, so a real drag/pinch naturally cancels
  the pending long-press (GestureDetector's own touch-slop check) instead
  of needing separate bookkeeping. Also added a small play-icon badge on
  cells holding a video, so video slots are visually distinguishable from
  photo ones in the preview.
- `StatusLayoutAdjustActivity.java` — `showSlotOptionsMenu()` shows the
  3-option dialog; reuses the existing `addMediaLauncher`/`addMediaSlotIndex`
  replace-in-place mechanism (already there for the "+" empty-slot flow),
  just launched with `"video/*"` for the video option instead of always
  `"image/*"`.

**3. Layout-picker result now correctly reports video slots**

Previously `finishWithResult()` hardcoded every slot's video flag to `0`
(images only), since the picker was photos-only. Now that "Replace with
Video" can put a video into any slot, that flag is read back from each
Uri's real MIME type instead (`isVideoUri()` — `ContentResolver.getType()`
starts with `"video/"`). This can't drift out of sync with *how* a video
ended up in a slot (original screen-1 pick vs. a later replace), since it's
checked fresh at finish time rather than tracked separately.

Screen 1 (`StatusLayoutPickerActivity`, "Start layout") is unchanged and
still photos-only for the initial gallery selection — this was specifically
about the Choose Layout screen.
