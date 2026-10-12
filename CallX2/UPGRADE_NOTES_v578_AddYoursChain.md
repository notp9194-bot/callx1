# v578 — Add Yours chains (Instagram "Add Yours" template)

Builds on the existing `addyours` sticker (prompt only) and adds: chain id, usage counter, chain screen, template reuse.

## How it works
1. **Create** — Sticker picker → *Add Yours*: prompt + "Share my style as a template" (default on). The sticker gets a `chainId` (Firebase push key).
   On posting a **text status**, `NewStatusActivity.withAddYoursTemplate()` bakes the poster's look into the sticker:
   `template = { bgColor, textColor, fontStyle, textAlign, scale, rotationDeg, posXRatio, posYRatio }` (media statuses: only sticker size/position — the photo itself is never shared).
2. **Share** — normal story post. Cloud Function `onStatusCreatedAddYours` creates `addYoursChains/{chainId}` (prompt, originUid, template, count) and the starter's entry.
3. **Add Yours button** — viewers tap the sticker (existing zoom gate) → `StatusViewerActivity.openAddYoursComposer()`. If they already joined → chain screen; otherwise `NewStatusActivity` opens with the sticker + template pre-applied (background, font, colour, alignment, sticker spot).
4. **Publish** — the joiner posts normally. The function adds `addYoursChainEntries/{chainId}/{uid}` and `count += 1`, and notifies the chain starter (in-app row + push, throttled to 1 push / 5 min / chain; reuses the `status_mention` row so no client change).
5. **Counter** — sticker shows live "N people added" (`AddYoursHelper.attachCount`); tapping it opens the chain screen.
6. **Chain screen** — `AddYoursChainActivity`: prompt, count, *Add Yours* button, paginated grid (24/page, newest first). Tap a tile → that story (`singleStatusOnly`, Back returns to the chain).

## Rules of the chain
- **One join per user per chain** (entry key = uid). You can join again once your previous story has expired.
- The chain starter is **not counted**. Deleting your story withdraws you (entry removed, count − 1). Expiry does **not** reduce the count.
- Grid shows only stories that are live, privacy = *everyone* (or mine), and not by someone I blocked. The counter counts everyone (private/expired included).
- Stickers created before v578 have no `chainId`: they get no counter; tapping them derives a deterministic legacy chain id (`AddYoursHelper.resolveChainId`) so joining still works.
- Reels do **not** take part (chains live in the `status` tree). Their tap handlers are now one shared `AddYoursHelper.onReelTap` instead of two pasted copies.

## Files
| Area | File |
|---|---|
| Server | `functions/index.js` → `onStatusCreatedAddYours`, `onStatusDeletedAddYours` |
| Rules | `firebase_addyours_rules.json` (merge into your deployed rules) |
| Core | `core/.../stickers/AddYoursHelper.java` (new), `StatusStickerOverlayView` (chainId, template, count label), `StatusStickerPickerSheet` (chainId + template checkbox) |
| Composer | `NewStatusActivity` (template snapshot / apply / saved sticker position) |
| Viewer | `StatusViewerActivity` (join check, count label, cleanup), new `AddYoursChainActivity` (+ manifest entry) |
| Reels | `ReelPlayerFragment`, `ReelPhotoSlideshowAdapter` (shared handler) |

## Deploy checklist (REQUIRED — the feature is server-driven)
1. Merge `firebase_addyours_rules.json` into the deployed Realtime Database rules (both nodes are read-only for clients; `.indexOn: ["ts"]` on entries).
2. `firebase deploy --only functions:onStatusCreatedAddYours,functions:onStatusDeletedAddYours`.
3. Until both are live, stickers still post and open the composer, but the counter stays hidden/0 and the chain screen is empty.

## Known limits / follow-ups
- Count updates ~1–2 s after a story is posted (Cloud Function latency).
- Stories with privacy *contacts / except / only* are counted but not listed in the grid.
- Only the viewer's own block list is applied to the grid (not "they blocked me").
- Expired entries are filtered client-side; a scheduled tidy-up of old entries is not included.
