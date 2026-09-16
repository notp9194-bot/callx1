# Advance #6 — precompute mediaAspectRatio at Room-insert time

## Problem
`MessagePagingAdapter` derived `knownRatio`/`vKnownRatio` via
`(float) m.mediaWidth / m.mediaHeight` inline, on every single bind/rebind
of an image or video bubble — cheap per-call, but redundant: the same
division was being redone every time a row scrolled back into view,
instead of once.

## Fix
- **`messages` table, v70 → v71** (`MIGRATION_70_71`): adds a `REAL`
  `mediaAspectRatio` column, and backfills it for every existing row that
  already has `mediaWidth`/`mediaHeight` — old chat history gets the
  precomputed value immediately after the app updates, not only newly
  sent/received messages.
- **`MessageEntity` / `Message`**: both get a matching
  `Float mediaAspectRatio` field.
- **`MessageEntityMapper`** — the single choke point every send, receive,
  and sync path already routes a `Message` through before it reaches Room
  (per that class's own doc comment):
  - `fromModel()` computes `mediaAspectRatio` from `mediaWidth`/
    `mediaHeight` right there, once, before the Room write. This is what
    makes it "consistently populated everywhere" — no per-call-site
    discipline required, every path that persists a message gets it for
    free.
  - `toModel()` just passes the stored value straight through — never
    recomputes on read.
- **`MessagePagingAdapter`** (both the image and video bind blocks): now
  prefers `m.mediaAspectRatio` when present, falling back to the original
  `mediaWidth/mediaHeight` division only for a `Message` that hasn't
  round-tripped through Room yet (e.g. this session's own optimistic
  local-send object, or a live Firebase update rendered before its async
  persist completes) — zero behavior change for that edge case, pure win
  for every already-persisted row.

## Net effect
Every message that has ever been through a Room write (which is
effectively all of them, `sent` or `received`) now carries its aspect
ratio pre-divided. The adapter's hot bind path does a null/`>0f` check
instead of an integer division + two null checks on every scroll-back —
small per-call, but it's one less thing happening on every single row
bind, and the actual DB is now the single source of truth for this value
instead of every caller re-deriving it independently.
