# YouTube Feature — Sub-Module Architecture

## Overview

The monolithic `feature-youtube` module has been split into **11 focused sub-modules**
for scalability, clean build separation, and independent team ownership.

```
feature-youtube/
├── yt-core/          ← Shared foundation (models, Firebase refs, Navigator interface)
├── yt-home/          ← Main shell + Home/Explore/Subscriptions tabs
├── yt-player/        ← ExoPlayer, MiniPlayer, SpeedQuality sheet
├── yt-upload/        ← Upload flow + Cloudinary utils
├── yt-comments/      ← Comments & replies screen + adapter
├── yt-channel/       ← Channel profile, edit, subscribers
├── yt-shorts/        ← Full-screen vertical shorts (like Reels)
├── yt-library/       ← Playlists, history, downloads, liked, watch later
├── yt-search/        ← Search with history & live suggestions
├── yt-notifs/        ← FCM handler, WorkManager worker, notifications screen
└── yt-settings/      ← All settings screens (11 sub-screens)
```

## Dependency Graph

```
:app
 ├── :yt-core       → :core
 ├── :yt-home       → :yt-core, :yt-player, :yt-shorts, :yt-library, :yt-search, :yt-upload
 ├── :yt-player     → :yt-core, :yt-comments, :yt-channel
 ├── :yt-upload     → :yt-core
 ├── :yt-comments   → :yt-core
 ├── :yt-channel    → :yt-core
 ├── :yt-shorts     → :yt-core, :yt-comments, :yt-channel
 ├── :yt-library    → :yt-core
 ├── :yt-search     → :yt-core
 ├── :yt-notifs     → :yt-core
 └── :yt-settings   → :yt-core
```

**Rule:** Sibling modules (e.g. yt-player ↔ yt-channel) never import each other directly —
they communicate through `YTNavigatorProvider` (interface in yt-core, impl in :app).

## Cross-Module Communication

### Pattern: YTNavigator Interface

```java
// Anywhere in any yt-* module:
YTNavigatorProvider.get().openPlayer(context, videoId);
YTNavigatorProvider.get().openChannel(context, channelUid);
YTNavigatorProvider.get().openComments(context, videoId);
```

### Setup (one-time, in CallxApp.onCreate):

```java
// app/src/main/java/com/callx/app/CallxApp.java
import com.callx.app.youtube.YTNavigatorImpl;
import com.callx.app.youtube.core.navigator.YTNavigatorProvider;

public class CallxApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // ... existing init ...
        YTNavigatorProvider.set(new YTNavigatorImpl());
    }
}
```

## Package Structure

| Module       | Package                              |
|--------------|--------------------------------------|
| yt-core      | com.callx.app.youtube.core           |
| yt-home      | com.callx.app.youtube.home           |
| yt-player    | com.callx.app.youtube.player         |
| yt-upload    | com.callx.app.youtube.upload         |
| yt-comments  | com.callx.app.youtube.comments       |
| yt-channel   | com.callx.app.youtube.channel        |
| yt-shorts    | com.callx.app.youtube.shorts         |
| yt-library   | com.callx.app.youtube.library        |
| yt-search    | com.callx.app.youtube.search         |
| yt-notifs    | com.callx.app.youtube.notifications  |
| yt-settings  | com.callx.app.youtube.settings       |

## Migration Checklist

- [ ] Add `YTNavigatorProvider.set(new YTNavigatorImpl())` in `CallxApp.onCreate()`
- [ ] Copy layouts/drawables from old `feature-youtube/src/main/res` into each sub-module
- [ ] Update R references in each sub-module class (already done in generated code)
- [ ] Run `./gradlew :yt-core:assembleDebug` first to verify base module
- [ ] Run `./gradlew assembleDebug` for full build

## Build Performance Benefits

- **Parallel compilation** — Gradle compiles independent modules simultaneously
- **Incremental builds** — changing yt-upload only rebuilds yt-upload + :app
- **Clear ownership** — each team/developer owns their sub-module
- **Isolated testing** — unit tests per module via `./gradlew :yt-comments:test`

## Build Commands

```bash
# Build a single sub-module
./gradlew :yt-player:assembleDebug

# Build all YouTube modules
./gradlew :yt-core:assembleDebug :yt-home:assembleDebug :yt-player:assembleDebug

# Full project build
./gradlew assembleDebug

# Run tests for a module
./gradlew :yt-comments:test
```
