# v558 — Crash fix: NPE in StatusFragment.rebuildStatusAdapterNow (String.hashCode on null)

- Trace: `rebuildStatusAdapterNow:789` <- `loadFromRoom` lambda. Preload-signature loop did `en.ownerUid.hashCode()`; ek Room row ka `ownerUid` null tha (orphan/purana row), `roomMap` me null key ban gayi aur entry tak pahunch gayi.
- Fix 1: `loadFromRoom` ab null/empty `ownerUid` wali rows skip karta hai (source par).
- Fix 2: signature me `Objects.hashCode(en.ownerUid)` (defensive).
- Note: ye v557 se related nahi, v555 ke preload-sig code ka purana bug tha jo ab Room-seed par trigger hua.
