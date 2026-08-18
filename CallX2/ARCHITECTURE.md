# CallX2 Architecture — WhatsApp-level Shared Core + Feature Layer

## Overview

```
app/                   ← Wiring only: MainActivity, navigation setup, DI entry point
core/                  ← Shared data layer (models, DB, repositories, utils, base classes)
feature-chat/          ← Chat UI only (Activities, Fragments, Adapters, ViewModels)
feature-status/        ← Status + Channels UI only
feature-calls/         ← Calls UI only
feature-reels/         ← Reels UI only
feature-x/             ← X/Twitter-style feed UI only
```

## Layer Architecture (Clean Architecture)

```
┌──────────────────────────────────────────────────────────┐
│                     UI Layer                              │
│  Fragment / Activity → observes LiveData from ViewModel  │
│  NEVER touches Firebase or Room directly                  │
└────────────────────────┬─────────────────────────────────┘
                         │ observes LiveData
┌────────────────────────▼─────────────────────────────────┐
│                  ViewModel Layer (per feature)            │
│  ChannelViewModel, StatusViewModel, ChatListViewModel     │
│  Transforms repo data → UI-ready LiveData                 │
│  Survives rotation; owned by Fragment/Activity            │
└────────────────────────┬─────────────────────────────────┘
                         │ calls methods
┌────────────────────────▼─────────────────────────────────┐
│                 Repository Layer (core/)                   │
│  ChannelRepository, MessageRepository,                    │
│  StatusRepository, UserRepository                         │
│  SINGLE SOURCE OF TRUTH: Room DB + Firebase sync         │
└──────────┬─────────────────────────┬─────────────────────┘
           │ reads/writes            │ syncs
┌──────────▼──────────┐  ┌──────────▼──────────────────────┐
│   Room DB (local)   │  │   Firebase Realtime Database     │
│  ChannelEntity      │  │  channels/{id}                   │
│  ChannelPostEntity  │  │  channelPosts/{channelId}/{id}   │
│  MessageEntity      │  │  messages/{chatId}/{id}          │
│  StatusEntity       │  │  status/{uid}/{id}               │
│  ChatEntity         │  │  users/{uid}                     │
│  UserEntity         │  │                                  │
└─────────────────────┘  └──────────────────────────────────┘
```

## Offline-First Pattern

1. **UI opens** → ViewModel subscribes to Room LiveData → shows cached data instantly (no blank screen)
2. **Repository.sync()** → fires Firebase listener → writes to Room
3. **Room changes** → LiveData notifies ViewModel → ViewModel notifies UI
4. **No polling** — Room LiveData is reactive; UI auto-updates without explicit refresh calls

## Key Files

### Core Layer (`core/`)
| File | Purpose |
|------|---------|
| `models/Channel.java` | Shared channel data model (used by feature-status + any future feature) |
| `models/ChannelPost.java` | Shared channel post model |
| `models/Message.java` | Shared message model (chat + groups) |
| `models/User.java` | Shared user profile model |
| `db/AppDatabase.java` | Room DB v34 — all entities registered here |
| `db/dao/ChannelDao.java` | Channel + ChannelPost queries (LiveData + sync) |
| `db/dao/MessageDao.java` | Message queries (LiveData, Paging3 keyset, delta sync) |
| `repository/ChannelRepository.java` | Offline-first channel CRUD (replaces ChannelManager) |
| `repository/MessageRepository.java` | Offline-first message send/receive |
| `repository/StatusRepository.java` | Offline-first status post/sync |
| `repository/UserRepository.java` | User profile CRUD + live presence |
| `utils/FirebaseUtils.java` | All Firebase node references (centralized) |
| `base/BaseActivity.java` | Auth guard, presence tracking, helper methods |
| `base/BaseFragment.java` | Auth guard, runSafely(), viewModel() helpers |

### Feature-Status (`feature-status/`)
| File | Purpose |
|------|---------|
| `viewmodel/ChannelViewModel.java` | Exposes followedChannels, suggestedChannels LiveData; delegates writes to ChannelRepository |
| `viewmodel/StatusViewModel.java` | Exposes activeStatuses LiveData; delegates to StatusRepository |
| `feed/StatusFragment.java` | Observes ViewModels, never touches Firebase/Room directly for channels |
| `channel/ChannelSectionAdapter.java` | Uses ChannelEntity from core (not local Channel model) |
| `channel/ChannelViewerActivity.java` | Observes ChannelViewModel for posts and follow state |
| `channel/ExploreChannelsActivity.java` | Observes ChannelViewModel.getAllChannels() |
| `channel/CreateChannelActivity.java` | Calls ChannelViewModel.createChannel() |

### Feature-Chat (`feature-chat/`)
| File | Purpose |
|------|---------|
| `chatlist/ChatListViewModel.java` | Exposes chats + archivedChats + starredMessages LiveData |
| `chatlist/ChatsFragment.java` | Observes ChatListViewModel (Room LiveData) |

## Firebase Node Reference

```
channels/{channelId}/              ← Channel metadata
channelFollows/{uid}/{channelId}   ← User's followed channels (true/false)
channelPosts/{channelId}/{postId}  ← Posts inside a channel

messages/{chatId}/{messageId}      ← Chat messages (1:1)
groupMessages/{groupId}/{msgId}    ← Group messages
status/{uid}/{statusId}            ← Status stories
statusSeen/{viewerUid}/{ownerUid}  ← Seen tracking
users/{uid}/                       ← User profiles

blocks/{uid}/{blockedUid}          ← Block system (unified)
```

## ViewModel Scoping Rules

- **Fragment-scoped ViewModel**: use `new ViewModelProvider(this).get(...)` inside the Fragment
  - Good for: data owned by one screen (ChannelViewerActivity, ExploreChannelsActivity)
- **Activity-scoped ViewModel**: use `new ViewModelProvider(requireActivity()).get(...)`
  - Good for: shared data between sibling Fragments (ChatsFragment + GroupsFragment share ChatListViewModel)

## What's NOT in Core

- ❌ Activities, Fragments, Adapters, Layouts, Drawables — these stay in their feature module
- ❌ ViewModel classes — ViewModels live in the feature module that uses them
- ❌ Navigation logic — stays in `app/` or the feature module
- ❌ Business rules specific to one feature (e.g. swipe-reply animation)

## Migration Status (v179)

| Component | Before | After |
|-----------|--------|-------|
| Channel.java | feature-status/channel/ | **core/models/** ✅ |
| ChannelPost.java | feature-status/channel/ | **core/models/** ✅ |
| ChannelManager.java | feature-status/channel/ | **Deleted** (replaced by ChannelRepository) ✅ |
| ChannelRepository | — | **core/repository/** ✅ |
| MessageRepository | — | **core/repository/** ✅ |
| StatusRepository | — | **core/repository/** ✅ |
| UserRepository | — | **core/repository/** ✅ |
| ChannelViewModel | — | **feature-status/viewmodel/** ✅ |
| StatusViewModel | — | **feature-status/viewmodel/** ✅ |
| ChatListViewModel | — | **feature-chat/chatlist/** ✅ |
| BaseActivity | — | **core/base/** ✅ |
| BaseFragment | — | **core/base/** ✅ |
| AppDatabase | v33 | **v34 + Channel entities** ✅ |
| FirebaseUtils | — | **+ Channel node refs** ✅ |
