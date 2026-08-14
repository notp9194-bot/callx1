# v259 — Add Status: Modern Four-Step Composer

## What changed

The Add Status screen now follows the same focused step-by-step interaction
pattern as the Reel upload screen:

| Step | Focus |
|---|---|
| 1 | Content — text, links, photos, videos, GIFs, stickers and captions |
| 2 | Style — background, font, alignment and avatar ring |
| 3 | Privacy — expiry, audience, close friends and sharing |
| 4 | Share — status preview, upload progress and Post Status |

## Compatibility

- Existing view IDs and `ActivityNewStatusBinding` fields are preserved.
- Existing camera, gallery, Reels camera, layout collage, stickers, compression,
  Cloudinary upload, Firebase save, scheduling and privacy logic are unchanged.
- The wizard moves the existing views into step containers at runtime instead of
  duplicating or replacing the post controls.
- Back navigation moves to the previous step before exiting the composer.

## Validation

The XML layout parses successfully and the Java source has balanced braces and
parentheses. A full Android build should still be run in Android Studio with
the project's configured SDK and Firebase/Cloudinary setup before release.