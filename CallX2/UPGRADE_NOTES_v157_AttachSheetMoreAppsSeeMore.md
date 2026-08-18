# v157 — Recents ▾ dropdown: "More apps" + "See more" wired up

v156 me Recents ▾ dropdown bana tha (Recents/Camera/Videos/Screenshots/
Downloads/WhatsApp). Ab wahi dropdown do aur rows ke saath end hota hai —
screenshot 1 jaisa hi order: **More apps** aur **See more**.

## More apps
Tap karte hi wahi system content-chooser khulta hai jo Document chip
(`opt_document`) already khol raha tha (`filePicker.launch("*/*")` —
`ActivityResultContracts.GetContent()`, jo Android ka real "*/*" chooser
hai). Isi wajah se list — Files, Photos, MT Manager, Drive, Gallery,
ZArchiver, Albums — bilkul screenshot 2 jaisi aayegi, kyunki wo tumhare
phone pe actually installed GET_CONTENT-capable apps hain — hardcoded nahi,
asli system chooser hai.

## See more
Tap karte hi wahi Photos/Collections picker khulta hai jo Gallery chip
(`opt_gallery`) already khol raha tha (`multiMediaPicker.launch(...)` —
system Photo Picker, ImageAndVideo). Yehi screenshot 3 hai.

## Files touched
- `RecentMediaLoader.java` — `Folder.isAction` flag + `Folder.action(...)`
  factory + `ACTION_MORE_APPS`/`ACTION_SEE_MORE` keys; `loadFolders()` order
  fix (Camera pehle, Videos baad me — screenshot se match) aur end me dono
  action rows append.
- `item_attach_folder.xml` — thumbnail card ko id di, ek chevron accessory
  add kiya (action rows ke liye).
- `AttachFolderAdapter.java` — action rows ke liye alag bind path (icon
  tile + chevron, koi count/checkmark nahi).
- `ic_chevron_right.xml`, `ic_more_apps_folder.xml`, `ic_more_apps_stack.xml`
  — naye icons.
- `AttachSheetRecentMediaBinder.java` — `Callbacks` me
  `onMoreAppsRequested()` / `onSeeMoreRequested()` add kiye; dropdown ka
  folder-picked handler in dono action keys ko pehchan ke sheet dismiss + 
  callback fire karta hai (folder-filter wale flow ko touch nahi karta).
- `ChatMediaController.java`, `GroupChatActivity.java` — dono jagah naye
  callbacks ko unke already-existing `filePicker`/`multiMediaPicker` se
  wire kiya — koi naya picker/launcher nahi banaya.
