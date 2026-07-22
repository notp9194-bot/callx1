# v171 — Fix: AbstractMethodError crash on ParcelableSpan.getSpanTypeIdInternal()

## Crash
```
java.lang.AbstractMethodError: abstract method
"int android.text.ParcelableSpan.getSpanTypeIdInternal()"
on receiver AdvancedRichTextController$LetterSpacingSpan
  at android.text.TextUtils.writeToParcel
  at android.view.inputmethod.SurroundingText.copyWithParcelableSpans
  at android.widget.TextView.onCreateInputConnection
  ...
```

Triggered on window focus gain (IME asking for surrounding text) whenever a
`LetterSpacingSpan` was present in the EditText's Editable — i.e. right after
using the letter-spacing rich-text tool and then tapping back into the input.

## Root cause
`LetterSpacingSpan` declared `implements android.text.ParcelableSpan` and
overrode the public `getSpanTypeId()` method. On modern Android
(observed API 34+), the framework's parceling path (used when building
`SurroundingText` for the IME) calls a **hidden** method,
`getSpanTypeIdInternal()`, that only framework/system span classes can
implement. Third-party app code cannot satisfy this hidden abstract method,
so any custom class implementing `ParcelableSpan` throws `AbstractMethodError`
the moment the framework tries to parcel it.

## Fix
`LetterSpacingSpan` (in `AdvancedRichTextController.java`) no longer
implements `ParcelableSpan`. It was never actually parceled across a
process boundary — it only lives inside the local EditText's Editable — so
`ParcelableSpan` support wasn't needed in the first place. Removed the
interface and the now-unused `getSpanTypeId()`, `describeContents()`, and
`writeToParcel()` overrides. `updateMeasureState()` / `updateDrawState()`
(the actual letter-spacing behavior) are unchanged.

## Files changed
- `feature-chat/src/main/java/com/callx/app/conversation/AdvancedRichTextController.java`

## Verify
1. Open a chat, expand the rich-text toolbar, apply letter spacing to some
   selected text.
2. Rotate the screen or switch apps and back (forces window focus loss/gain)
   — or just tap out of the EditText and back in.
3. No crash; letter spacing renders correctly; IME opens normally.

## General rule of thumb
Don't implement `android.text.ParcelableSpan` (or `android.text.ParcelSpan`)
on custom spans unless you truly need to send them across processes (rare
in a chat EditText). Plain `CharacterStyle`/`MetricAffectingSpan` subclasses
are safe and are what nearly every custom span in this codebase should use.
