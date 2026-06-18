# Firebase Realtime Database Rules — Status System
## Merge into your existing firebase.json rules

```json
{
  "rules": {
    "statuses": {
      "$ownerUid": {
        ".read": "auth != null",
        ".write": "auth != null && auth.uid === $ownerUid",
        "$statusId": {
          ".validate": "newData.hasChildren(['ownerUid','type','timestamp','expiresAt'])",
          "deleted":   { ".write": "auth != null && auth.uid === $ownerUid" },
          "archived":  { ".write": "auth != null && auth.uid === $ownerUid" },
          "seenCount": { ".write": "auth != null" }
        }
      }
    },

    "statusSeen": {
      "$ownerUid": {
        "$statusId": {
          ".read": "auth != null && auth.uid === $ownerUid",
          "$viewerUid": {
            ".write": "auth != null && auth.uid === $viewerUid",
            ".validate": "newData.isNumber()"
          }
        }
      }
    },

    "statusReactions": {
      "$ownerUid": {
        "$statusId": {
          ".read": "auth != null && auth.uid === $ownerUid",
          "$viewerUid": {
            ".write": "auth != null && auth.uid === $viewerUid",
            ".validate": "newData.isString() && newData.val().length <= 10"
          }
        }
      }
    },

    "statusHighlights": {
      "$ownerUid": {
        ".read": "auth != null",
        ".write": "auth != null && auth.uid === $ownerUid",
        "$highlightId": {
          ".validate": "newData.hasChildren(['ownerUid','title','createdAt'])"
        }
      }
    },

    "statusArchive": {
      "$ownerUid": {
        ".read": "auth != null && auth.uid === $ownerUid",
        ".write": "auth != null && auth.uid === $ownerUid"
      }
    },

    "statusSeenFlat": {
      "$statusId": {
        ".read": "auth != null",
        "$viewerUid": {
          ".write": "auth != null && auth.uid === $viewerUid",
          ".validate": "newData.isNumber()"
        }
      }
    }
  }
}
```

## Firestore (if you migrate to Firestore later)
```
statuses/{ownerUid}/{statusId}   → allow read: if request.auth != null
                                 → allow write: if request.auth.uid == ownerUid
statusSeen/{ownerUid}/{statusId}/{viewerUid} → allow write: if request.auth.uid == viewerUid
statusReactions/{ownerUid}/{statusId}/{viewerUid} → allow write: if request.auth.uid == viewerUid
statusHighlights/{ownerUid}/{highlightId} → allow read: if request.auth != null; write: if owner
statusArchive/{ownerUid}/{statusId} → allow read/write: if request.auth.uid == ownerUid
```
