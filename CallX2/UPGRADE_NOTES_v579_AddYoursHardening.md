# v579 — Add Yours hardening (security, privacy, legacy chains, cleanup, count note)

## Deploy (order matters)
1. No Realtime Database rules change (new `addYoursChainMembers` node is server-only; default-deny keeps clients out).
2. `firebase deploy --only functions:onStatusCreatedAddYours,functions:onStatusDeletedAddYours,functions:cleanupExpiredAddYoursEntries`

## What changed
1. **originUid spoof closed** (functions/index.js). A new chain is always owned by whoever's status first carries its chainId.
   A joiner can no longer claim someone else as starter (fake "X added yours" pushes). A joiner of a v578+ chain whose doc
   isn't there yet waits up to ~6s for the real doc, otherwise the join is ignored (logged). Legacy `L_…` chains: doc is
   created by the first joiner for the sticker's origin but flagged `legacy:true` and **never notifies** (origin is unverifiable).
2. **Private stories no longer leak.** Entries with privacy != "everyone" store only `statusId, ts, expiresAt, isOrigin, listed:false`
   (no name/photo/thumb/text/bg). They're still counted. Chain grid skips them (also my own private one — the note below covers it).
   Entries written by v578 are unchanged until they expire.
3. **Legacy chain screen no longer stuck on "Loading…".** Viewer passes origin/template to `AddYoursHelper.openChain(...)`;
   `AddYoursChainActivity` uses them when the chain doc doesn't exist. With neither doc nor extras the button turns into
   "Not available yet — tap to retry" after 6s.
4. **Scheduled cleanup** `cleanupExpiredAddYoursEntries` (every 6h) removes entries whose story expired (pages of 100 chains,
   uses the `ts` index). Count is untouched. To keep "one person = one count" after cleanup, the server now keeps
   `addYoursChainMembers/{chainId}/{uid}=true`; deleting your story removes it (so re-joining after a delete counts again).
5. **Count vs grid note.** Under the counter: "N stories aren't shown (private or expired)" once the grid finished loading and
   count > visible joiners. Empty state says "No public stories to show right now." when count > 0.

## Known limits
- Blocked-user filtering is still client-side (the blocked uid is visible in the entries key, nothing else).
- Privacy changed after posting is not synced to `listed`.
- Not compiled/tested here (no Gradle, no network): only brace/JS syntax checks. Test: join public + private, delete story,
  open a pre-v578 sticker's chain, expire a story and wait for the cleanup run.
