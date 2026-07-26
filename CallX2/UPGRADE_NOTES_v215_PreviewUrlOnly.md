# v215 — Status Music Sticker: Pass previewAudioUrl Only, Not Full-Quality soundUrl

## Change
`ReelTrendingAudioActivity` now returns a separate `RESULT_PREVIEW_AUDIO_URL`
("audio_preview_url") extra alongside the existing full-quality `RESULT_AUDIO_URL`
("audio_url"). `Audio.previewAudioUrl` is now read from Firebase (`sounds/`
and `musicLibrary/` — `previewAudioUrl` child) wherever `audioUrl` was read.

`StatusStickerPickerSheet.onActivityResult()` now stores **only**
`audio_preview_url` into the music sticker's `soundUrl` field — it no longer
reads `audio_url` at all. The full-quality URL is untouched everywhere else
(Reels' own composition/editing flow still gets `audio_url` as before, so
that path is unaffected).

## Note
Some tracks (mainly ones sourced from `musicLibrary` rather than `sounds/`)
don't have a `previewAudioUrl` in Firebase yet — for those, the sticker's
`soundUrl` will come back empty. That's a data-side gap (no preview was ever
generated for those entries), not a code bug — worth backfilling
`previewAudioUrl` for `musicLibrary` tracks server-side if this comes up.
