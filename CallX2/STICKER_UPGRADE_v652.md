# v652 — Sticker polish 9: lock + selection persist in the draft

Files changed
- core/.../stickers/StatusStickerOverlayView.java (toJsonWithScale writes "locked", fromJson reads it)
- feature-status/.../compose/NewStatusActivity.java (draft save/restore of stickers, selection; lock stripped from posts/copies)

What persists now (draft = SharedPreferences "status_draft", written in onPause, same place as the caption text)
- Every sticker: pose, size, rotation, z-order and the LOCK flag.
- Which sticker was selected -> re-selected on reopen (toolbar shows again once its position is applied).
- Draft is cleared with the caption when the status is posted/scheduled. Fixed on the way: onPause used to re-save
  the draft right after a successful post (finish() -> onPause -> saveDraft); a draftCleared flag stops that.
- "locked" is composer-only: removed from the posted JSON and from copies (a copy starts unlocked).
- Not restored when the composer is opened from an Add Yours prefill (that sticker comes from the intent).

Not persisted (on purpose)
- Undo / redo history: restored stickers are the new baseline. History holds live views and steps like "delete"
  can't be replayed after the process dies; a half history would be confusing.
- Text layer content/position and the picked photo/video (the draft never stored them; only the caption text did).

Test on device: add 2-3 stickers, lock one, select another, home/kill app, reopen -> same layout, lock icon amber,
selection back, undo empty. Post -> reopen composer -> no draft stickers.
