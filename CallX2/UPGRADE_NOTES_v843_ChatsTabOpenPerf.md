# v843 — Chats tab open: timing log + cold-start cuts
- NEW core/debug/TabOpenPerfLog + Chats-tab 3-dot menu item "🐞 Chats Open Log" (Copy/Clear). Marks (ms since process start): CallxApp.onCreate, MainActivity.onCreate, main inflate, first frame, ChatsFragment.onCreateView stages, first data, listeners attached, class preload done.
- CallxApp: `chats-class-preload` thread (MainActivity, ChatsFragment, ChatListAdapter, canvas row views, ViewPagerAdapter, ActivityMainBinding...) + warm chat_list_snapshot / chat_list_settings prefs off main.
- Keystore warmup order: AppLock -> ChatLock -> E2E.
- ChatsFragment: Firebase listeners (contacts/special/muted/groups) attached after first frame when a list is already shown (snapshot/in-memory); immediate only on empty first-ever open.
- baseline-prof.txt: ViewPagerAdapter + ChatsFragment/ChatListAdapter$VH/prewarm/canvas rules. Regenerate the profile on a release build.
