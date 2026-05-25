# CallX — Separate Profile System Guide

## Overview

Har system ka apna alag profile data hai Firebase aur Cloudinary mein.
Koi ek system dusre ko overwrite nahi karta.

---

## 4 Alag Profile Systems

### 1. Chat Profile (Chat + Group + Status + Calls)
**Firebase node:** `users/{uid}`
**Activity:** `ProfileActivity` (app module)
**Data:**
- `name`, `about`, `callxId`, `email`, `phone`
- `photoUrl` — Full avatar (800×800 JPEG)
- `thumbUrl` — Chat list avatar (100×100 WebP)
- `whatsapp`, `instagram`, `youtube`, `otherLink`
- `lastSeen`, `fcmToken`, `lastMessage`, `lastMessageAt`

**Cloudinary folders:**
- Avatar thumb → `callx/avatars/thumbs/`
- Avatar full  → `callx/avatars/`

---

### 2. Reels Profile (NEW — Separate)
**Firebase node:** `reels/users/{uid}`
**Activities:** `ReelProfileSetupActivity` (first time) + `ReelEditProfileActivity`
**Accessible from:** `UserReelsActivity` → Settings button
**Data:**
- `displayName`, `handle` (@handle — unique index at `reels/handles/{handle}`)
- `bio`, `category`, `website`
- `photoUrl` — Full avatar (800×800 JPEG)
- `thumbUrl` — Feed avatar (100×100 WebP)
- `bannerUrl` — Profile banner
- `instagramHandle`, `twitterHandle`, `youtubeChannelUrl`
- `followerCount`, `followingCount`, `reelCount`, `totalLikes`
- `verified`, `privateAccount`, `allowDuet`, `allowStitch`, `allowComments`

**Cloudinary folders:**
- Avatar thumb → `callx/reels/avatars/thumbs/`
- Avatar full  → `callx/reels/avatars/`
- Banner       → `callx/reels/banners/`

**First-time flow:**
```
User taps Reels creator icon 
  → ReelsFragment checks reels/users/{uid}
  → Not exists → ReelProfileSetupActivity
  → Exists → UserReelsActivity
```

---

### 3. X (Twitter-like) Profile
**Firebase node:** `x/users/{uid}`
**Activities:** `XProfileActivity` + `XEditProfileActivity`
**Model:** `XUser.java`
**Data:**
- `name`, `handle`, `bio`, `website`, `location`, `birthday`, `gender`
- `photoUrl` — Full avatar
- `thumbUrl` — Feed avatar
- `bannerUrl` — Profile banner
- `verified`, `blueVerified`, `privateAccount`
- `followerCount`, `followingCount`, `tweetCount`
- `pinnedTweetId`
- Handle index → `x/x_handles/{handle} = uid`

**Cloudinary folders:**
- Avatar → `x/avatars/`
- Banner → `x/banners/`

---

### 4. YouTube Channel Profile
**Firebase node:** `youtube/channels/{uid}`
**Activities:** `YouTubeChannelActivity` + `YouTubeEditChannelActivity`
**Model:** `YouTubeChannel.java`
**Data:**
- `channelName`, `handle`, `bio`, `country`
- `photoUrl` — Channel avatar
- `bannerUrl` — Channel banner
- `subscriberCount`, `videoCount`, `totalViews`
- `isVerified`, `createdAt`

**Cloudinary folders (via YouTubeCloudinaryUtils):**
- Avatar → `youtube/{uid}/avatar/`
- Banner → `youtube/{uid}/banner/`

---

## Firebase Database Structure (Simplified)

```
Firebase Realtime Database Root
├── users/                          ← CHAT PROFILE (ProfileActivity)
│   └── {uid}/
│       ├── name, about, callxId
│       ├── photoUrl, thumbUrl
│       └── phone, whatsapp, instagram, youtube, otherLink
│
├── reels/                          ← REELS PROFILE (ReelEditProfileActivity)
│   ├── users/{uid}/
│   │   ├── displayName, handle, bio, category
│   │   ├── photoUrl, thumbUrl, bannerUrl
│   │   ├── website, instagramHandle, twitterHandle, youtubeChannelUrl
│   │   ├── followerCount, followingCount, reelCount
│   │   ├── verified, privateAccount, allowDuet, allowStitch
│   │   └── createdAt, updatedAt
│   ├── handles/{handle} → uid      ← Handle uniqueness index
│   ├── videos/{reelId}/            ← Reel content
│   ├── user_videos/{uid}/
│   ├── user_followers/{uid}/
│   └── user_following/{uid}/
│
├── x/                              ← X PROFILE (XEditProfileActivity)
│   ├── users/{uid}/
│   │   ├── name, handle, bio, website, location
│   │   ├── photoUrl, thumbUrl, bannerUrl
│   │   ├── followerCount, followingCount, tweetCount
│   │   └── verified, blueVerified, privateAccount
│   ├── x_handles/{handle} → uid    ← Handle uniqueness index
│   ├── tweets/{tweetId}/
│   ├── user_tweets/{uid}/
│   └── notifications/{uid}/
│
└── youtube/                        ← YOUTUBE PROFILE (YouTubeEditChannelActivity)
    ├── channels/{uid}/
    │   ├── channelName, handle, bio, country
    │   ├── photoUrl, bannerUrl
    │   ├── subscriberCount, videoCount, totalViews
    │   └── isVerified, createdAt
    ├── videos/{videoId}/
    ├── user_videos/{uid}/
    └── subscriptions/{uid}/
```

---

## New Files Added

### core/
- `models/ReelProfile.java` — Reels profile data model

### feature-reels/
- `activities/ReelProfileSetupActivity.java` — First-time Reel profile create
- `activities/ReelEditProfileActivity.java` — Reel profile edit karo
- `utils/ReelFirebaseUtils.java` — Reels Firebase paths (reels/* root)
- `utils/ReelCloudinaryUtils.java` — Reels Cloudinary upload (avatar + banner)
- `res/layout/activity_reel_profile_setup.xml` — Setup screen layout
- `res/layout/activity_reel_edit_profile.xml` — Edit screen layout

### feature-x/ (Updated)
- `utils/XCloudinaryUtils.java` — Added `uploadXAvatar()` + `uploadXBanner()` methods
  - Avatar → `x/avatars/` (was `x_tweets/`)
  - Banner → `x/banners/` (was `x_tweets/`)
- `activities/XEditProfileActivity.java` — Now calls correct avatar/banner methods

### feature-reels/ (Updated)
- `fragments/ReelsFragment.java` — Creator button checks reel profile, shows setup if missing
- `activities/UserReelsActivity.java` — Settings button opens `ReelEditProfileActivity`
- `AndroidManifest.xml` — `ReelProfileSetupActivity` + `ReelEditProfileActivity` registered

---

## Cloudinary Folder Summary

| System   | Avatar Thumb         | Avatar Full        | Banner              |
|----------|---------------------|--------------------|---------------------|
| Chat     | callx/avatars/thumbs | callx/avatars      | —                   |
| Reels    | callx/reels/avatars/thumbs | callx/reels/avatars | callx/reels/banners |
| X        | —                   | x/avatars          | x/banners           |
| YouTube  | —                   | youtube/{uid}/avatar | youtube/{uid}/banner |

