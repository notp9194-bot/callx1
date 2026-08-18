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

### 2. Reels Profile (Separate)
**Firebase node:** `reels/users/{uid}`
**Activities:** `ReelProfileSetupActivity` (first time) + `ReelEditProfileActivity`
**Data:**
- `displayName`, `handle`, `bio`, `category`, `website`
- `photoUrl`, `thumbUrl`, `bannerUrl`
- `instagramHandle`, `twitterHandle`, `youtubeChannelUrl`
- `followerCount`, `followingCount`, `reelCount`, `totalLikes`
- `verified`, `privateAccount`, `allowDuet`, `allowStitch`, `allowComments`

**Cloudinary folders:**
- Avatar thumb → `callx/reels/avatars/thumbs/`
- Avatar full  → `callx/reels/avatars/`
- Banner       → `callx/reels/banners/`

---

### 3. X (Twitter-like) Profile ← NEW SYSTEM (v18+)
**Firebase node:** `x/users/{uid}`
**Model:** `XProfile.java` (replaces old `XUser.java`)
**Manager:** `XProfileManager.java` (centralized read/write)
**Activities:** `XProfileActivity` + `XEditProfileActivity`

**Data (XProfile):**
- `name`, `handle`, `bio`, `website`, `location`, `birthday`, `gender`
- `photoUrl` — Full avatar → `x/avatars/`
- `thumbUrl` — Feed avatar → `x/avatars/thumbs/`
- `bannerUrl` — Banner     → `x/banners/`
- `verified`, `blueVerified`, `privateAccount`
- `followerCount`, `followingCount`, `tweetCount`, `profileViews`
- `pinnedTweetId`, `joinedTs`, `updatedAt`
- Handle index → `x/x_handles/{handle} = uid`

**New Architecture:**
```
XProfile.java          — Data model (replaces XUser)
XProfileManager.java   — All Firebase read/write
XProfileActivity       — View profile (uses XProfile + XProfileManager)
XEditProfileActivity   — Edit profile (uses XProfile + XProfileManager)
XCloudinaryUtils       — Avatar/banner upload
```

**XProfileManager API:**
```java
// Load once
XProfileManager.load(uid, profile -> { ... });

// Real-time
ValueEventListener l = XProfileManager.observe(uid, profile -> { ... });
XProfileManager.stopObserving(uid, l); // in onDestroy

// Save editable fields
XProfileManager.save(uid, profile, oldHandle, callback);

// Update media
XProfileManager.updateAvatar(uid, photoUrl, thumbUrl);
XProfileManager.updateBanner(uid, bannerUrl);

// Handle check
XProfileManager.checkHandleAvailable(handle, uid, available -> { ... });

// Profile view counter
XProfileManager.incrementProfileViews(uid);
```

**Migration from XUser:**
- `XUser` → `XProfile` (XUser is now a deprecated shim that extends XProfile)
- Direct `xUserRef().setValue()` → `XProfileManager.save()`
- Direct `xUserRef().addValueEventListener()` → `XProfileManager.observe()`

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
- `photoUrl`, `bannerUrl`
- `subscriberCount`, `videoCount`, `totalViews`
- `isVerified`, `createdAt`

**Cloudinary folders:**
- Avatar → `youtube/{uid}/avatar/`
- Banner → `youtube/{uid}/banner/`

---

## Firebase Database Structure

```
Firebase Realtime Database Root
├── users/                          ← CHAT PROFILE
│   └── {uid}/
│       ├── name, about, callxId
│       ├── photoUrl, thumbUrl
│       └── phone, whatsapp, instagram, youtube, otherLink
│
├── reels/                          ← REELS PROFILE
│   ├── users/{uid}/
│   │   ├── displayName, handle, bio, category
│   │   ├── photoUrl, thumbUrl, bannerUrl
│   │   ├── followerCount, followingCount, reelCount
│   │   └── verified, privateAccount, allowDuet, allowStitch
│   └── handles/{handle} → uid
│
├── x/                              ← X PROFILE (new XProfile system)
│   ├── users/{uid}/                ← XProfile node
│   │   ├── name, handle, bio, website, location
│   │   ├── photoUrl, thumbUrl, bannerUrl
│   │   ├── followerCount, followingCount, tweetCount, profileViews
│   │   ├── verified, blueVerified, privateAccount
│   │   ├── pinnedTweetId, joinedTs, updatedAt
│   │   └── birthday, gender
│   ├── x_handles/{handle} → uid   ← Handle uniqueness index
│   ├── tweets/{tweetId}/
│   ├── user_tweets/{uid}/
│   └── notifications/{uid}/
│
└── youtube/                        ← YOUTUBE PROFILE
    ├── channels/{uid}/
    │   ├── channelName, handle, bio, country
    │   ├── photoUrl, bannerUrl
    │   └── subscriberCount, videoCount, totalViews
    ├── videos/{videoId}/
    └── subscriptions/{uid}/
```

---

## New Files (X Profile System)

### feature-x/ (NEW)
- `models/XProfile.java` — New X profile data model
- `utils/XProfileManager.java` — Centralized profile read/write manager
- `activities/XProfileActivity.java` — Updated to use XProfile + XProfileManager
- `activities/XEditProfileActivity.java` — Updated to use XProfile + XProfileManager

### feature-x/ (DEPRECATED)
- `models/XUser.java` — Deprecated shim (extends XProfile for backward compat)

---

## Cloudinary Folder Summary

| System   | Avatar Thumb               | Avatar Full        | Banner              |
|----------|---------------------------|--------------------|---------------------|
| Chat     | callx/avatars/thumbs      | callx/avatars      | —                   |
| Reels    | callx/reels/avatars/thumbs| callx/reels/avatars| callx/reels/banners |
| X        | x/avatars/thumbs          | x/avatars          | x/banners           |
| YouTube  | —                         | youtube/{uid}/avatar| youtube/{uid}/banner|
