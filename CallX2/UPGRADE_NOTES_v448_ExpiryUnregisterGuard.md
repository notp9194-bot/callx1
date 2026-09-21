# v448 — ExpiryTickManager.unregister guard (per-bind ConcurrentHashMap.remove skipped)

Before: every bind (canvas + legacy) and every onViewRecycled called `ExpiryTickManager.unregister(h)` (a ConcurrentHashMap.remove) —
even though almost no row is a disappearing message.

Call-site audit (only 3 unregister + 2 register sites exist in the whole codebase, all in MessagePagingAdapter):
canvas bind, legacy bind, onViewRecycled / canvas bind register, legacy bind register.

Now: `VH.expiryRegistered` — set true right before each `register()`, cleared by the new `expiryUnregister(h)` helper and by
both listeners' `onFinish()`. All three unregister sites use the helper.
Safety: the flag is a SUPERSET of "manager holds an entry" (manager may drop an entry itself on finish; onFinish also clears the flag,
and a stale true only costs one no-op remove). The dangerous state — flag false while registered — cannot occur: every register() sets it first,
and only our own unregister/onFinish clear it. Holders are pooled across adapters via the shared RecycledViewPool; the flag lives on the VH
so it travels with it, and onViewRecycled still unregisters before the holder re-enters the pool.

File: MessagePagingAdapter.java. Not compile-tested (no Android SDK/Gradle) — brace balance checked only; run a normal build.
