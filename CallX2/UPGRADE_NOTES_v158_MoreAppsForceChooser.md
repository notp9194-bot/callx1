# v158 — "More apps" ab hamesha real app-chooser (screenshot 1) khulta hai

## Bug
"More apps" pehle `filePicker`(GetContent, "*/*") reuse kar raha tha — usi
launcher ko Document chip bhi use karta hai. Problem: agar phone pe
`ACTION_GET_CONTENT` ke liye pehle se koi default app set ho (is case me
system "Files" app), toh Android chooser dikhaye bina seedha usi default
app ko khol deta hai — isiliye screenshot 1 (Files/Photos/MT Manager/
Drive/Gallery/ZArchiver/Albums list) ki jagah screenshot 2 (Files app ki
apni Recent/Images/Videos/Documents browsing UI) khulta tha.

## Fix
"More apps" ab apna alag `moreAppsChooser` launcher use karta hai jo
`Intent.createChooser(...)` me wrap kiya gaya `ACTION_GET_CONTENT` intent
launch karta hai. `createChooser` HAMESHA disambiguation dialog dikhata
hai — kisi bhi pehle se set default ko ignore karke — isliye ab "More
apps" har baar screenshot 1 jaisa hi asli app-picker dikhayega, jisme wahi
apps honge jo is phone pe GET_CONTENT handle kar sakte hain.

Document chip (`opt_document`) jaanbujh kar `filePicker` (plain GetContent)
pe hi hai — uska default-app-skip behavior wahi rehne diya, sirf "More
apps" row fix ki gayi hai (jo real Android app-chooser dikhana hi
chahiye tha).

## Files touched
- `ChatMediaController.java` — naya `moreAppsChooser` launcher
  (`StartActivityForResult`), `onMoreAppsRequested()` ab isko fire karta hai.
- `GroupChatActivity.java` — same fix, group chat side.
