# v156 — Attach sheet: working "Recents ▾" folder picker

Chat ke input bar capsule → attachment icon → jo bottom sheet khulti hai, uske
expanded header me "Recents" ab sirf text nahi — tap karne pe screenshot jaisa
dropdown khulta hai:

- Recents, Videos, Camera, Screenshots, Downloads, WhatsApp — device pe jo
  actually maujood hai wahi dikhega (empty folders hide rehte hain).
- Har row: cover thumbnail + folder name + item count, aur jo currently
  selected hai uspe green tick.
- Folder tap karte hi grid usi folder ke photos/videos pe switch ho jaata hai
  (pagination bhi us folder ke hisaab se reset hoti hai), header title bhi
  update ho jaata hai.
- Baaki extra on-device folders bhi list me aa jaate hain (largest first) —
  fixed 6 tak limited nahi.

## Naye files
- `RecentMediaLoader.Folder` + `loadFolders()` — MediaStore se real folder
  list banata hai (bucket_display_name grouping, WhatsApp ke split buckets
  ek tile me merge).
- `AttachFolderAdapter.java` — dropdown list ka adapter.
- `AttachSheetFolderPicker.java` — popup show/anchor logic.
- `MaxHeightRecyclerView.java` — popup ki list ko sheet ke ~55% height tak
  cap karta hai (warna lambi list screen se bahar chali jaati).
- `ic_chevron_down.xml`, `ic_check_green.xml`, `item_attach_folder.xml`,
  `popup_attach_folder_list.xml`.

## Modified
- `bottom_sheet_attach.xml` — "Recents" TextView ab `recents_dropdown_row`
  (clickable) + `recents_title` + chevron icon.
- `AttachSheetRecentMediaBinder.java` — dropdown wiring, filter-aware
  pagination (`RecentMediaLoader.loadRecentPage(..., filter)`).

Camera row (opt_camera icon grid) aur peek/expand animation, HD/view-once
toggle — sab pehle jaisa hi kaam karta hai, ismein touch nahi kiya.
