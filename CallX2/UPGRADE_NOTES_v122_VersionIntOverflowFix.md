# v122 — Found it: `version` field was `int`, server sends a 13-digit timestamp

The debug summary dialog (v120) worked exactly as designed and pinned
this down immediately — all 6 reactions failed identically with:

```
com.google.gson.JsonSyntaxException: java.lang.NumberFormatException:
Expected an int but was 1783818210007 at line 1 column 25 path $.version
```

## Root cause
Server (`index.js`): `version = Math.round(maxMtimeMs) + files.length` —
that's a millisecond epoch timestamp, e.g. `1783818210007` (13 digits).
`EmojiManifestModels.Manifest.version` was declared as `int`
(max ~2.1 billion, 10 digits). Gson threw `NumberFormatException` on
literally every single manifest parse attempt — which is why every one
of the 6 reactions failed with the exact same error. This was never
about the folder rename, the JSON schema, or the native validator — all
of those fixes were real and correct, but this single field-type bug sat
underneath all of them, silently killing every manifest fetch from the
very first server-side fix onward.

## Fix
- `EmojiManifestModels.Manifest.version`: `int` → `long`.
- `EmojiManifestRepository`: `prefs.edit().putInt(KEY_MANIFEST_VERSION, ...)`
  → `.putLong(...)` (had to follow the type change through; this value
  is written but never read back elsewhere, confirmed by search — no
  other call sites needed touching).

No server changes needed this time — the server's `version` field was
always correct; the client just couldn't hold it.
