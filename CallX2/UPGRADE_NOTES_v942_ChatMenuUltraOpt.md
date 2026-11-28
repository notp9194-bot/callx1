# v942 — Chat ⋮ menu + menu screens: ultra optimisation

## 3-dot menu (1:1 + group)
- New `chat/ui/ChatMoreMenu`: fixed-width PopupWindow, built once (first idle moment after open), measured once,
  re-shown with a single showAsDropDown(). Replaces per-tap `PopupMenu` (XML inflate / 12x menu.add + MenuPopup
  measuring every row). Mute title updated in place; group admin/rename/bot rows toggled via setVisible()
  (re-measure only when something flipped). Popup dismissed in onDestroy (no window leak).
- One action table per activity: `handleMenuAction(int)` (ChatActivity) / `handleGroupMenuAction(int)`
  (GroupChatActivity); onOptionsItemSelected delegates to it.
- Debug rows (Paging Debug Log, Bubble Perf) now only exist on debuggable builds.
- `Class.forName` -> `setClassName` for Edit Profile / Media-Links-Docs (no class load on the tap).

## Menu screens
- `ChatSecurityBottomSheet`, `PrivacySecurityActivity`, `TwoStepVerificationActivity`, `LoginActivityLogActivity`:
  `new SecurityManager(ctx)` -> `SecurityManager.get(ctx)`. The constructor builds EncryptedSharedPreferences
  (AES256 key work, ~100-300 ms) on the MAIN thread on every open; singleton pays it once per process.
- `ChatCustomizationBottomSheet` (Wallpaper): colours resolved once, row/icon gradients shared via ConstantState,
  prefs loaded once before rows, duplicate setChecked removed.
- `ChatMoreMenu.warm()` (low-priority thread, once per process + per chat): emoji font, SecurityManager singleton,
  avatar / display-mode prefs, this chat's privacy prefs file.

## Not touched (same SecurityManager pattern, outside menu scope)
IncomingCallActivity:194, AuthActivity:814, CallxApp:608 still use `new SecurityManager(...)`.
