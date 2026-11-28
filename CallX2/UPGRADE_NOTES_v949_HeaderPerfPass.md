# v949 — Chats header perf pass

ChatsFragment header (rebuilt on every tab return):
- Removed dead banner_requests (9 views, never shown). Selection bar (7 views + 6 listeners) and the "loading more"
  ProgressBar are ViewStubs now (ensureSelectionBar / showLoadingMoreIndicator inflate on first use).
- Filter chips: ONE TextView each (item_chat_filter_chip.xml) instead of 4-view item_folder_tab, one shared click
  listener. Selection = View.setSelected() with state-list bg (bg_chat_chip) + text colour (chat_chip_text): no
  setBackgroundResource / getColor / setTextColor churn, no-op when unchanged. Same selectors drive folder chips.
- refreshChatFilterChips(): archived scans (2x O(n)) memoised per chatSearchDataVersion; archived label only re-set on
  change (a text change re-layouts the header + RecyclerView).
- setupFolderTabs(): skips the removeAllViews + N+2 inflate rebuild when the folder list (id/emoji/name signature) and
  the view are unchanged (LiveData re-delivers on every new view).

MainActivity header:
- setImmersiveMode / setReelsSystemBarsVisible only re-apply when the target state changes (was: insets dispatch on
  every page change + onResume).
- Return-to-call banner and voice mini player are ViewStubs (inflated only when a call / voice note is active).
  Call banner: one Runnable, static dots array, timer width pinned once (no AppBar re-layout every 500ms).
- Mini-player play/pause icon only swapped on change; updateFab guarded; Reels fragment tag built once;
  tabHistoryStack capped at 24.

Not build-tested (no Gradle/SDK here; javac parse pass only). Verify: selection mode (long-press, all buttons),
archived chip label/unread, folder chips + selection, voice mini player, return-to-call banner, Reels<->Chats status bar.
