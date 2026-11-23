# v833 — chat header + input bar not re-themed on light<->dark switch
Cause: ChatActivity declares `uiMode` in configChanges (no recreation). Only toolbar/root background was re-applied
(ChatThemeManager.applyScreenTheme). Everything resolved at inflate time stayed on the old theme: header ImageButton
`android:tint`, name/status text colors, `bg_chat_input_capsule` (solid+stroke), EditText text/hint colors,
ChatIconBarView's cached tinted icon bitmaps (fields set once in initIcons()), btn_view_once bitmap, char counter,
locked banner, record-lock card.
Fix: new `ChatNightModeRefresher.refresh(activity, root)` (by-id, null-safe) called from
`ChatActivity.handlePossibleNightModeChange()`; `ChatIconBarView.refreshTheme()` re-runs initIcons()
(IconTintCache is already keyed on night mode); view-once icon re-set + `setViewOnceMode(isViewOnceModeOn)`.
Not changed: GroupChatActivity (different layout/ids, same stale-view class of problem), reply/search/selection stubs.
Not compiled offline — run CI.
