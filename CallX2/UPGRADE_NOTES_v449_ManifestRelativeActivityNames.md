# v449 — Crash fix: ActivityNotFoundException GroupReadByActivity (tap on seen-by strip)

Root cause: feature-chat's manifest declared 4 activities with RELATIVE names (`.group.GroupReadByActivity`, `.group.ChatBackupActivity`,
`.bots.BotSettingsActivity`, `.conversation.ViewOnceViewerActivity`). A leading-dot name resolves against the module's `namespace`
(`com.callx.app.chat`, feature-chat/build.gradle) → `com.callx.app.chat.group.GroupReadByActivity`, which doesn't exist. The real classes live in
`com.callx.app.group/.bots/.conversation`, so they were never actually declared and `startActivity()` threw.
Surfaced now because v437 wired the seen-by strip tap (`onSeenByClick` → GroupChatActivity → GroupReadByActivity); it was never reachable before.
Not related to the v442–v448 perf changes.

Fix: fully-qualified names for all four (the other entries in this manifest already were).
File: feature-chat/src/main/AndroidManifest.xml
