# v120 — Debug summary dialog: shows exact status of all 6 reactions

Crash is fixed (confirmed — no AlertDialog, no repro). Now the reactions
are still silently falling back to unicode with no visible reason. This
pass adds a one-shot summary dialog so the reason is obvious immediately,
no restart needed.

## What's new
Every long-press now (DEBUG builds only) tracks what happened to each of
the 6 quick reactions — cached-and-shown, downloaded-fresh, or exactly
which step rejected it (network/manifest/sha256/validator/native-reject)
— and once all 6 have resolved, shows one AlertDialog:

```
Reaction animation status:

heart: OK: downloaded fresh & swapped in live
thumb: not downloaded: manifest fetch failed (network/server error)
laugh: downloaded but REJECTED by validator: an animated keyframe is missing e/i/o/s
...
```

## Bug fixed along the way
The first attempt at this referenced `com.callx.app.BuildConfig.DEBUG`
from inside the `feature-chat` module — wrong package (that class
belongs to the `app` module, which `feature-chat` doesn't even depend
on) and `feature-chat`'s `build.gradle` didn't have BuildConfig
generation turned on in the first place. Fixed: `feature-chat/build.gradle`
now has `buildConfig true`, and the reference is
`com.callx.app.chat.BuildConfig.DEBUG` (feature-chat's own namespace).

## What to do
Install this build, long-press a message. The summary dialog should pop
up within a second or two of the sheet opening (once all 6 background
lookups resolve). Screenshot/copy that dialog's text back — it'll say
exactly why each one isn't animating.
