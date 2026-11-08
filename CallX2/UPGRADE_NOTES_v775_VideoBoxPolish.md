# v775 — Screen 5 polish: video box zoom / move + box length

Viewer ("Use template") me video box par tap -> trim sheet:
- **Box length** chips (2s..10s): viewer ab apne box ki length khud chun sakta hai (poster ki length default rehti hai). Length badalne par sheet dobara khulti hai (trim range length par depend karta hai).
- **Zoom / Move** button: photo boxes wali pinch + drag screen (shape mask ke saath). Frame par jo window chuni, wahi final mp4 me aati hai.
- Buttons: Change | Zoom / Move | Done.

Technical: `applyVideoXform()` viewer ke `slotXform` {zoom, cx, cy} ko compositor ke `Slot.zoom / panX / panY` me badalta hai (cover-crop ka free area dhyan me rakhke). Single-scene aur multi-scene dono post paths me lagta hai. Compositor me koi badlav nahi (zoom / pan pehle se tha, bas UI nahi tha).

Files: MOD `NewStatusActivity.java`, `version.properties` 146.

## Abhi nahi
- Photo boxes ki apni alag length nahi (animation scene / template length follow karti hai).
- Create screen me per-box video length nahi.

## Test
1. Video box me clip lagao > sheet > Box length 8s -> trim slider ki range badle. Post -> box 8s dikhe.
2. Zoom / Move -> pinch + drag, back -> box me zoom dikhe. Post -> mp4 me wahi crop (face / subject wahi jo chuna).
3. Zoom ke baad trim slider hilao -> naya frame, zoom / move wahi rahe.
4. Multi-scene template me video box par bhi wahi.

Build nahi chala (Gradle yahan nahi): bracket-balance, `javac` syntax parse. Green frame / slow ho to logcat `TplVideoComp`.
