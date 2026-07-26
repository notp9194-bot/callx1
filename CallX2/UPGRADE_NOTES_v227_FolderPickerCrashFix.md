# v227 — Fix: Recents Folder-Picker Popup Crash (colorSurface attr)

## Crash

```
android.view.InflateException: Binary XML file line #24 in
com.callx.app:layout/popup_attach_folder_list: Error inflating class <unknown>
Caused by: java.lang.UnsupportedOperationException: Failed to resolve
attribute at index 2: TypedValue{t=0x2/d=0x7f04012f a=-1}, ...
  at androidx.cardview.widget.CardView.<init>(CardView.java:127)
  at com.google.android.material.card.MaterialCardView.<init>(...)
  at AttachSheetFolderPicker.show(AttachSheetFolderPicker.java:55)
```

Tapping the "Recents ▾" dropdown in the attach sheet crashed while
inflating its `MaterialCardView` container.

## Root cause

`popup_attach_folder_list.xml` set `app:cardBackgroundColor="?attr/colorSurface"`.
Resolving that theme attribute requires the inflating `Context`'s theme to
actually define `colorSurface` — only `Theme.CallX` (this app's Material3
theme, `core/.../values/themes.xml`) does. Several activities in
`feature-chat`'s own manifest — `MediaEditActivity`, `ChatVideoTrimActivity`,
`ChatImageCropActivity`, `ChatStickerPickerActivity` — are declared with a
plain `android:theme="@style/Theme.AppCompat.NoActionBar"`, which has no
`colorSurface` attribute at all. `AttachSheetFolderPicker.show()` inflates
against whatever `Activity` context it's handed, and `MaterialCardView`'s
constructor eagerly resolves `cardBackgroundColor` up front — so against
one of those AppCompat-themed contexts, the attribute lookup has nothing
to resolve and throws `UnsupportedOperationException`.

## Fix

`feature-chat/.../layout/popup_attach_folder_list.xml` — replaced
`app:cardBackgroundColor="?attr/colorSurface"` with the concrete
`app:cardBackgroundColor="@color/surface_card"`. `colorSurface` was only
ever an alias for this same color resource (`core/.../values/colors.xml`
and `values-night/colors.xml` both define `surface_card` directly), so day
and night mode look identical to before — the only change is that this
card's background now resolves straight from the color resource system
instead of through whichever theme the inflating Context happens to have,
so it renders correctly (and doesn't crash) no matter which Activity ends
up showing this popup.

No other files needed changes — `AttachSheetFolderPicker.java`,
`AttachFolderAdapter.java`, and `MaxHeightRecyclerView.java` were already
correct; this was purely a theme-attribute-safety issue in the layout.
