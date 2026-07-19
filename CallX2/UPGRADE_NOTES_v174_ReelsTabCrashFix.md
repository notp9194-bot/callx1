# v174 — Reels Tab Crash Fix (StringIndexOutOfBoundsException)

Root cause found via the v173 crash-capture screen. Trace pointed exactly
at `ReelPredictivePreloader.resolveCategory()`.

## The bug

```java
return "music_" + reel.musicName.toLowerCase(Locale.US)
    .replaceAll("[^a-z0-9]", "")
    .substring(0, Math.min(20, reel.musicName.length()));
```

`replaceAll("[^a-z0-9]", "")` strips spaces/punctuation from the music
name, so the resulting string is often **shorter** than the original
`musicName`. But the substring bound used `reel.musicName.length()` —
the *original*, uncleaned string's length — not the cleaned string's
actual length. Any music name with enough non-alphanumeric characters
(spaces, `-`, `'`, etc.) made `Math.min(20, reel.musicName.length())`
land past the end of the cleaned string → `StringIndexOutOfBoundsException`,
crashing every time the ViewPager2 in Reels laid out a page with such a
reel in the predictive-preload window (`onPageSelected` → `preloadSmartFrom`
→ `predictScore` → `resolveCategory`).

## The fix

Compute the substring bound from the **cleaned** string's own length:

```java
String cleaned = reel.musicName.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
return "music_" + cleaned.substring(0, Math.min(20, cleaned.length()));
```

Checked the rest of the codebase for the same pattern
(`.substring(0, Math.min(...))`) — the other 3 occurrences
(`BiometricLoginManager`, `XDMConversationActivity`, `XHomeFragment`)
all already correctly measure the length of the string they're slicing,
so no other instances of this bug exist.
