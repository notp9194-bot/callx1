# CallX Modular Architecture

## Module Structure

```
CallX2/
├── app/                  ← :app  (shell — MainActivity, Auth, Profile, etc.)
├── core/                 ← :core (data layer — models, db, cache, utils, firebase)
├── feature-chat/         ← :feature-chat  (Chat, GroupChat, Contacts)
├── feature-reels/        ← :feature-reels (Reels feed, camera, editor, analytics)
├── feature-calls/        ← :feature-calls (Audio/Video/Group calls)
├── feature-status/       ← :feature-status (Status viewer & uploader)
└── patternlockview/      ← :patternlockview (existing custom view library)
```

## Dependency Graph

```
:app
 ├── :core
 ├── :feature-chat    → :core
 ├── :feature-reels   → :core
 ├── :feature-calls   → :core
 ├── :feature-status  → :core
 └── :patternlockview
```

## Module Responsibilities

| Module | Contents |
|---|---|
| `:core` | Models, Room DB, Cache, FirebaseUtils, Constants, Repository, SyncWorker |
| `:feature-chat` | ChatActivity, GroupChat, adapters, ChatsFragment, GroupsFragment |
| `:feature-reels` | All Reel* activities, camera, editor, notifications, VideoPreloader |
| `:feature-calls` | CallActivity, IncomingCall, GroupCall, call services & receivers |
| `:feature-status` | StatusViewer, NewStatus, StatusFragment, StatusBackgroundService |
| `:app` | MainActivity, AuthActivity, ProfileActivity, SearchActivity, etc. |

## Build Features

### AAB (Android App Bundle)
- Build command: `./gradlew bundleRelease`
- Language, density, and ABI splits are enabled in `bundle {}` block
- Play Store delivers only the ABI/language/density the device needs

### ABI Splits (APK)
- Build command: `./gradlew assembleRelease`
- Generates separate APKs for: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`
- Plus a universal APK (`universalApk true`)
- Version codes: arm64=4xxx, armeabi-v7a=3xxx, x86_64=2xxx, x86=1xxx

### Minify (R8 Full Mode)
- `minifyEnabled true` + `shrinkResources true` in release
- `android.enableR8.fullMode=true` in gradle.properties
- All ProGuard rules in `app/proguard-rules.pro`
- Line numbers kept for crash reports via `-keepattributes SourceFile,LineNumberTable`

## Build Commands

```bash
# Debug APK
./gradlew assembleDebug

# Release APK (all ABI splits)
./gradlew assembleRelease

# Release AAB (for Play Store upload)
./gradlew bundleRelease

# Build specific module only
./gradlew :feature-reels:assembleDebug

# Clean build
./gradlew clean bundleRelease
```

## Performance Optimizations (gradle.properties)
- `org.gradle.parallel=true`    — parallel module compilation
- `org.gradle.caching=true`     — build cache (faster incremental builds)
- `org.gradle.configureondemand=true` — only configure needed modules
- `org.gradle.daemon=true`      — reuse Gradle daemon
- `android.nonTransitiveRClass=true` — faster R class compilation
