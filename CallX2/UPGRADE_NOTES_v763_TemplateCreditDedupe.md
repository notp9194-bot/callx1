# v763 — "Template by @owner" pill duplicate fix

## Bug
A status made from a template showed TWO "Template by @owner" pills:
1. the auto credit **mention sticker** (v749, `NewStatusActivity.addTemplateCreditSticker`), and
2. the viewer's **template chip** (v740, `StatusViewerActivity.renderTemplateChip`, "✨ Template by @name").

## Fix
- `StatusViewerActivity`: new `hasTemplateCreditSticker(stickersJson)`; `renderTemplateChip` now returns early for the
  "made FROM a template" chip when the status already carries the credit sticker. The chip stays as a fallback
  (old statuses without the sticker, or the author deleted the sticker) so credit is never lost and never doubled.
  The "Use this template" / "Your template" chip (statuses that ARE templates) is unchanged.
- `NewStatusActivity` (template restore loop): poster's own old "Template by" mention sticker is no longer restored,
  so a template-of-a-template does not stack the previous credit under the fresh one (`addTemplateCreditSticker` adds exactly one).

## Notes
- Not compiled / run on device.
- Already-published statuses that have the sticker will stop showing the chip immediately (viewer-side check, no data migration).

## Template text fixes (same version)
- **Placeholder text deleted → Post locked forever**: `tplTextPending` is now cleared when the viewer deletes/clears the
  placeholder text sticker (`clearTemplateTextPending()` in `removeEditorTextOverlayView(record=true)` and
  `onStickerRemovedFromCanvas`). Editor resets (`record=false`) don't touch it.
- **Editable poster text posted unknowingly**: on Post, if the poster's editable text (tm=0) is still exactly as restored,
  a one-time dialog "Owner ka text hai — Aise hi post / Text badlo" appears (`confirmPosterTextBeforePost()`,
  fields `tplRestoredText`, `tplTextPostConfirmed`). Locked / placeholder modes unchanged.
- Not compiled / run on device.

## Status viewer polish (same version)
- **Credit sticker tap (item 2)**: tapping the "Template by @owner" sticker on a status made from a template now opens a small
  chooser — "Use this template" (→ original template composer) or "View @owner's profile". Plain mention stickers still go straight
  to the profile. New `StatusStickerOverlayView.isTemplateCreditMention()` (+ `mentionPrefix` field);
  `StatusViewerActivity.showTemplateCreditChooser()`.
- **Strings (item 3)**: every remaining hardcoded user-visible string in `StatusViewerActivity` (owner/viewer/highlight menus,
  rename/delete highlight dialogs, original-audio dialog, template chip labels, reaction-failed toast, "Template ab available nahi
  hai" / "Template load nahi hua" Hinglish toasts, fallbacks "Status"/"Highlight") moved to `strings_status_viewer.xml`, English only.
  "Cancel" uses `android.R.string.cancel`. No `values-hi` file for the viewer strings exists, so Hindi falls back to English.
- **Template chip (item 4)**: sizes/colour moved to `res/values/viewer_template_chip_dimens.xml`; the chip is kept invisible until its
  caption-relative position is applied, then fades in (120 ms) — no more jump from the default height. Position failures are logged.
- **Credit sticker placement (item 5)**: checked, no code change. The viewer maps `cxRatio/cyRatio` into the same 9:16 box as the editor;
  in the reported screenshot the sticker centre is at x≈0.40 (editor default anchor is 0.5), i.e. the author had moved it.
- **Swallowed exceptions (item 6)**: 27 silent `catch (Exception ignored) {}` blocks that guard real logic now call
  `logIgnored("<method>", e)` (one `Log.w`, no crash-report spam). Trivial one-liners (player release / volume / pause / parse) and the
  per-frame `getOutline` callback stay silent on purpose. File split (6.2k-line Activity) NOT done: no compiler here, too risky blind.
- Not compiled / run on device.
