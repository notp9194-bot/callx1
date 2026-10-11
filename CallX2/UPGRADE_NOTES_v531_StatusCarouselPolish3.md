# v531 — Status carousel polish (3)

- **Close friends:** card pe green ring (flat `status_card_cf_ring`, ab bhi per-status arcs) + avatar ke corner me star badge (`iv_card_cf`). `StatusSegmentedRingDrawable` me optional `unseenColor`.
- **Press feedback:** card touch pe 96% scale (touch consume nahi hota, click/long-press same). Recycle pe scale reset.
- **Time label:** naam ke neeche "Just now / 5m ago / 2h ago" (`tv_card_time`). `CardItem.timeKey` diff me hai; Fragment sweep ~60s me rebuild karta hai taaki label fresh rahe (sirf jab data ho).
- **Loading skeleton:** `StatusListAdapter.beginInitialLoad()` — first data aane tak 4 shimmer cards (`ll_status_skeleton`), phir fade-in. 4s safety timeout (offline/empty). Fragment sirf tab call karta hai jab statusMap/myStatuses khaali ho.
- **Strings:** "My Status", "Hidden", muted toast, `describe()` ke saare strings, TalkBack actions, time labels ab `status_card_strings.xml` me. `describe()` me close friend + time bhi.
