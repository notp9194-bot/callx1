# v84 — .txt "view after send" fix + WhatsApp-level in-app viewer

## Root cause
Tapping a file bubble called `FileProvider.getUriForFile(ctx, packageName + ".provider", file)`,
but the manifest registers the provider as `com.callx.app.fileprovider`. The mismatched
authority made `getUriForFile()` throw, and the call site swallowed it with
`catch (Exception ignored)` — so tapping **any** file bubble silently did nothing, not just `.txt`.

Same authority typo existed in two more places (QR-code share, not chat files):

- `feature-chat/.../MessagePagingAdapter.java` (chat file-open) — **fixed**
- `feature-chat/.../group/GroupInfoActivity.java` (group QR share) — **fixed**
- `feature-status/.../channel/ChannelInviteLinkActivity.java` (channel QR share) — **fixed**

All three now use `.fileprovider`, matching `AndroidManifest.xml`.

## WhatsApp-level .txt viewer
New `TextFileViewerActivity` (`feature-chat` module, package `com.callx.app.conversation`,
registered in `feature-chat/src/main/AndroidManifest.xml`):

- Reads the file off the main thread (`AppBgExecutor`) with a loading spinner
- Selectable, scrollable, monospace text view
- Char/line-count subtitle in the toolbar
- Large files (>300k chars) are safely truncated with a visible banner instead of
  freezing the UI — full content still reachable via Share/Open with
- Error state with Retry if the file can't be read
- Toolbar overflow: **Copy all**, **Share** (shares the real file via FileProvider),
  **Open with** (external app chooser)

`MessagePagingAdapter.onFileOpenClick()` now routes `.txt` / `text/plain` attachments to this
in-app viewer; every other file type still falls back to an external `ACTION_VIEW`, and now
shows a chooser (instead of silently no-op'ing) if no app can handle it.

New files:
- `feature-chat/src/main/java/com/callx/app/conversation/TextFileViewerActivity.java`
- `feature-chat/src/main/res/layout/activity_text_file_viewer.xml`
- `feature-chat/src/main/res/menu/menu_text_file_viewer.xml`
