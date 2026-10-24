# v651 — Sticker polish: undo for add/copy/delete (5), toolbar icons + a11y (7), strings.xml (8)

Files changed
- core/.../stickers/StatusStickerOverlayView.java   (pre-removal state/index, removeWithAnimation(), reviveAt(), countdown restart)
- feature-status/.../compose/NewStatusActivity.java  (history engine, toolbar, toasts)
- feature-status/src/main/res/drawable/ic_sbar_*.xml (11 new vector icons)
- feature-status/src/main/res/values/strings_sticker_bar.xml (new)

5. Undo / redo now covers add, copy and delete
- One history (60 steps) with 3 step kinds: TRANSFORM (move/size/rotate, as before), ADD, REMOVE.
- Add (picker, prefill, copy button) -> undo removes it, redo puts the SAME view back (same z-index, same pose), so older
  move/size steps for that sticker stay valid.
- Delete (long-press, drag-to-trash, new toolbar delete button, text "Remove") -> undo brings it back where it was.
  After a drag-to-trash, it returns to where the drag STARTED, not onto the trash pill.
- Text layer: add / remove are steps too (one text layer rule respected). Countdown restarts on revive, music preview restarts.
- Music replace (new music sticker replaces the old one) is ONE grouped step: undo brings the old song back and removes the new.
- Music "Adjust / Mix" edit-in-place swaps without a history step; media swap / reset drops the text layer's history.
- Not covered: editing the text CONTENT / style, and the Lock flag.

7. Toolbar
- Always 2 rows now: row 1 = S / M / L + slider, row 2 = undo, redo, copy, front, back, lock, [adjust, mix | edit-text], delete.
  The old 3rd row (music / text) is merged into row 2 -> shorter on small screens (10 icons x 36dp max, fits 320dp).
- Emoji/text glyphs replaced by vector icons (ic_sbar_*), ripple background, lock icon switches lock/unlock (amber when locked).
- Every button has a contentDescription (lock button describes the action it will do); slider + S/M/L too.
- New delete button (locked stickers ignore it, same as long-press).

8. Strings
- All sticker toasts moved to strings_sticker_bar.xml (front/back, lock/unlock, music replaced / edit / update / preview errors).
  Text kept identical, so behaviour is unchanged; now translatable. New helper toast(int).
- Toolbar contentDescriptions are strings too.

Not compiled / run here (no Android SDK in this environment) -> please build + test on device:
 1) add -> undo -> redo; copy -> undo; long-press delete -> undo; drag-to-trash -> undo (returns to start spot)
 2) text: add, remove via dialog, undo / redo; music: replace, undo (old song returns + plays), redo
 3) lock a sticker: delete button dimmed + ignored; toolbar on a small / narrow device
