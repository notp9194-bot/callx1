# v704 — Seen-by sheet polish (v703 ke upar): 1, 2, 3, 6

## 1. Story switch par stale rows / flash nahi
- `switchStory()`: tap karte hi purani story ki list turant clear (`submitList(empty)`), empty-state hide, list top par scroll, queued profile reads clear, chip counts reset, "N viewers" text turant naye seed count se.
- Phir `compute()` ka result aate hi naye rows aate hain (gen guard pehle se hai, purana result drop hota hai).

## 2. Carousel ke saare cards ke counts live
- Naya `attachCardCounts()`: har story par ek **count-only** listener (`status/{owner}/{id}/seenBy` -> `getChildrenCount()`), `env.cardCounts` me. Sirf `PAYLOAD_COUNT` se wahi card repaint hota hai (image reload nahi).
- Selected card abhi bhi block-aware `liveViews` dikhata hai; baaki cards raw seenBy size (blocked viewer unme gine jaate hain).
- Sirf tab lagta hai jab 1 se zyada story ho. Sheet dismiss par `detachCardCounts()`. Firebase same ref ki duplicate listen dedupe karta hai, extra server load nahi.

## 3. Dark sheet par dark dialogs / snackbar
- Hide / Block `AlertDialog`: ab `darkDialogCtx()` (night-mode override context, `Theme.DeviceDefault.Dialog.Alert`) + app ka shared `AlertDialogStyler.showRounded()` — light-mode phone par bhi dark rounded dialog, baaki app jaisa button style ("Block" red, "Hide" green, "Cancel" purple).
- Purana manual `setTextColor(RED)` hata diya (styler ke red background par red text dikhta nahi).
- Undo Snackbar: dark surface, white text, green action.

## 6. Row avatar: HARDWARE bitmap (no RGB_565 banding)
- `StatusSheetAvatar.options(ctx, tier)`: `ROW_TIER` (MEDIUM, 54dp avatar) ab API 26+ par app default format (ARGB_8888 -> HARDWARE), same rule/low-RAM check jo Status cards me hai. API < 26 / low-RAM par RGB_565.
- TINY (reply sheet) bilkul pehle jaisa RGB_565 — us path me koi change nahi.
- Kill switch: `StatusSheetAvatar.ROW_HW_BITMAPS = false`.
- Note: Glide cache key me decode format hota hai, to MEDIUM-tier avatar jo kisi aur screen par 565 me cached ho, yahan alag entry banegi (ek baar decode).

## Files
`StatusSeenByBottomSheet.java`, `StatusSheetAvatar.java` (resources / activity me change nahi).

## Verify
- Build: `./gradlew :feature-status:assembleDebug` (yahan gradle nahi chala — Java syntax parse check hi hua).
- Check: card tap par list instantly clear hoke naya aaye; dusre cards ke counts live badlein (dusre phone se story dekho); light-mode phone par Hide/Block dialog dark aaye; avatars par banding nahi; low-end device par bhi avatar sahi.
