# v951 — Calls tab ROW perf pass (CallHistoryAdapter + item_call_history)

Per-row work that happened on every create / bind / draw, and what changed:

**Create / layout**
- Row 13 views / 4 levels -> 7 views / 3 levels. Story ring = background of `avatar_host` (no overlay ImageView, no overdraw);
  direction icon = start compound drawable of `tv_meta`; verified badge = end compound drawable of `tv_name`;
  dead 0dp `btn_video_call` removed.
- RecyclerView: item view cache 4, and ~10 rows are pre-inflated into the RecycledViewPool in main-thread idle time.

**Bind: allocations removed**
- 7 click/long-click lambdas per bind -> created ONCE per ViewHolder (resolve the row by position at click time).
- Date + SimpleDateFormat + String.format + concat per bind -> meta text cached per CallLog (WeakHashMap), built with one StringBuilder.
- parseColor x2-4, toLowerCase, new PorterDuffColorFilter, setImageResource -> static colour/label tables, allocation-free
  direction match, per-holder tinted icon created once per style.
- refreshRing Runnable + registry put per bind -> one Runnable per holder, registered only while attached
  (new `StoryRingRegistry.unregister`; this also fixes the registry entry holding holder/Activity forever).
- Glide: RequestOptions.circleCropTransform().format().diskCacheStrategy() built per bind -> one shared static object.
- Prefetch AvatarSource allocated per onScrolled -> single instance.
- Per-ROW Firebase `users/{uid}` read when the photo wasn't cached -> one read per uid per session, batched (80 ms),
  result rebinds only the avatar (payload).

**Bind: paint / re-record removed**
- Every setter is guarded by the last bound value (name, meta, colours, icon, avatar url, ring state, verified) -> no needless
  relayout / invalidate / display-list re-record.
- `itemView.setBackgroundColor()` (killed the ripple + new ColorDrawable per bind) -> `setActivated()` + state-list bg (ripple kept).
- Selection: `notifyDataSetChanged()` -> payload rebinds (long-press changes 1 row; select-all/clear rebind selection state only).
- Ring: ONE status lookup per bind (was hasUnseen/hasStatus twice + an unused `hasStory`); background only set when state changes;
  StoryRingRegistry.refreshAll no longer re-sets the ring on every row for every status change.
- onViewRecycled: Glide.clear only if something was bound.
- Verified badge via compound drawable, set only on change.

Not changed: avatar URL memoisation (AvatarUrlBuilder depends on network quality, so caching it would pin a stale quality).
Row is still View-based (not a single-canvas custom view).

Not build-tested (no Gradle/SDK here; javac syntax pass only). Verify: row look (ring size/gap, icon size/colour, verified badge),
ring tap -> status, avatar/name tap -> contact sheet, row tap -> chat, long-press + select all + delete + cancel (ripple still shows),
missed/incoming/outgoing/no-answer colours, rows with no photo (avatar appears once resolved), scroll fast, status seen/unseen change.
