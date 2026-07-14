# v167 — Fix: only Crop survived Send; rotate/filter/stickers/text/draw
# disappeared after sending, even though they looked correct in the editor

## The bug

In `MediaEditActivity`, adding a filter, sticker, text, or freehand drawing
(without also rotating the photo) looked completely correct in the editor
preview — but the photo that actually arrived in the chat after hitting
Send had none of it. Only **Crop** reliably survived, because it's baked
by a separate activity (`ChatImageCropActivity`) whose output file is used
directly.

## Root cause

`decodeSampledBitmap()` decoded the source photo via
`BitmapFactory.decodeStream()` **without** `inMutable = true`, so the
resulting `Bitmap` was **immutable**.

`bakeBitmap()` then does:

```java
Bitmap out = Bitmap.createBitmap(base, 0, 0, base.getWidth(), base.getHeight(), m, true);
...
Canvas canvas = new Canvas(out);
```

When `st.rotationDeg == 0` — i.e. the user filtered/stickered/drew
**without** also rotating, which is the common case — `m` is an identity
matrix, and Android's `Bitmap.createBitmap()` has a documented shortcut:
if the requested region + matrix don't actually change anything, it
returns the **same source bitmap** instead of allocating a new one. So
`out` was the same immutable bitmap that came out of `decodeSampledBitmap()`.

Handing an immutable bitmap to `new Canvas(out)` throws:

```
IllegalStateException: Immutable bitmap passed to Canvas constructor
```

That exception was caught by `bakeBitmap()`'s own try/catch, which logged
it and returned `null`. Back in `bakeAndSend()`:

```java
Bitmap baked = bakeBitmap(st);
if (baked != null) { ... }
else { resultUris.add(st.uri.toString()); }   // ← original, un-edited file
```

— so every edit silently fell back to sending the **original, unedited**
file. (When rotation *was* non-zero, `createBitmap()` always allocates a
genuinely new — mutable — bitmap, so that combination happened to work,
which is why the bug wasn't 100% consistent.)

## Fix

- `decodeSampledBitmap()`: added `b.inMutable = true;` to the real decode
  pass, so the bitmap coming out of it is always mutable, regardless of
  whether `Bitmap.createBitmap()` ends up returning it as-is or not.
- `bakeBitmap()`: added a defensive check right before `new Canvas(out)` —
  if the bitmap somehow still isn't mutable + `ARGB_8888` (any future code
  path, OEM decoder quirk, etc.), it's converted via `.copy(ARGB_8888, true)`
  first. Belt-and-suspenders on top of the real fix above.

## Files touched

- `feature-chat/.../conversation/controllers/MediaEditActivity.java` —
  `decodeSampledBitmap()` (`inMutable = true`), `bakeBitmap()` (defensive
  mutable/ARGB_8888 guard before `new Canvas(...)`).

## Not verified by build

Same caveat as v160/v165/v166 — no Java/Android SDK toolchain in this
environment. Please build and sanity-check: apply a filter only (no
rotate) → Send → confirm the filter is actually on the received photo;
same for sticker-only, text-only, and draw-only, plus a combo with crop.
