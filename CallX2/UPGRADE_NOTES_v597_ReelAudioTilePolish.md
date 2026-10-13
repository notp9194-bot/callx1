# v597 — Reel audio tile polish (right rail, btn_create_audio)

1. Late-arriving cover now uses the same loader as the first bind (`loadAudioTile`): same crop + rounded corners.
2. No owner-photo -> cover flash: while the sound cover is being fetched the tile shows a neutral placeholder;
   owner photo is only the last fallback (no cover / fetch failed / another reel already fetching).
3. Fetch failures are never cached (retry next bind); "sound has no cover" is remembered for 5 min only.
   Only non-empty covers go in `soundCoverCache`.
4. Removed wasted work on the hidden bottom-left disc (ticker is GONE): no infinite rotation animator,
   no Glide load for it.
5. Tile 28dp -> 36dp (SMALL 48dp decode tier), ripple background, 1dp padding so the cover fills the
   stroke, inset placeholder glyph, 48dp touch target via TouchDelegate, tick haptic on tap.
