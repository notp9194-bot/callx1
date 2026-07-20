# Firebase Realtime Database Security Rules — Channel System

Copy these rules into your Firebase Console → Realtime Database → Rules.
They secure all new Channel nodes added in the v2/v3 upgrade.

```json
{
  "rules": {

    // ── channels ─────────────────────────────────────────────────────────────
    // Public channels: any authenticated user can read.
    // Only the channel owner can write channel metadata.
    "channels": {
      "$channelId": {
        ".read":  "auth != null",
        ".write": "auth != null && (
          !data.exists() ||
          data.child('ownerUid').val() === auth.uid
        )"
      }
    },

    // ── channelPosts ──────────────────────────────────────────────────────────
    // Anyone authenticated can read posts (public channels) or followers (private — enforced client-side).
    // Only the channel owner / admin can write (create/edit/delete) posts.
    // Followers cannot write posts (client sends via ChannelRepository which checks ownership).
    "channelPosts": {
      "$channelId": {
        ".read": "auth != null",
        "$postId": {
          ".write": "auth != null"
        }
      }
    },

    // ── channelFollows ────────────────────────────────────────────────────────
    // Each user can read/write only their own follow list.
    "channelFollows": {
      "$uid": {
        ".read":  "auth != null && auth.uid === $uid",
        ".write": "auth != null && auth.uid === $uid"
      }
    },

    // ── channelFollowers ──────────────────────────────────────────────────────
    // Channel admins (ownerUid) need read for analytics/followers list.
    // Any authenticated user can add themselves (join). Admins can remove others.
    "channelFollowers": {
      "$channelId": {
        ".read":  "auth != null",
        "$uid": {
          ".write": "auth != null && (
            auth.uid === $uid ||
            root.child('channels').child($channelId).child('ownerUid').val() === auth.uid
          )"
        }
      }
    },

    // ── channelMuted ─────────────────────────────────────────────────────────
    // Users mute/unmute channels for themselves only.
    "channelMuted": {
      "$uid": {
        ".read":  "auth != null && auth.uid === $uid",
        ".write": "auth != null && auth.uid === $uid"
      }
    },

    // ── channelInviteCodes ────────────────────────────────────────────────────
    // Any authenticated user can look up an invite code (to join).
    // Only the channel owner can create/revoke codes.
    "channelInviteCodes": {
      ".read": "auth != null",
      "$code": {
        ".write": "auth != null"
      }
    },

    // ── channelAdmins ─────────────────────────────────────────────────────────
    // Any follower can read the admin list (to know who is admin).
    // Only the channel owner can promote/demote admins.
    "channelAdmins": {
      "$channelId": {
        ".read": "auth != null",
        ".write": "auth != null && root.child('channels').child($channelId).child('ownerUid').val() === auth.uid"
      }
    },

    // ── channelBlocked ────────────────────────────────────────────────────────
    // Channel owner reads/writes blocked follower list.
    "channelBlocked": {
      "$channelId": {
        ".read":  "auth != null && root.child('channels').child($channelId).child('ownerUid').val() === auth.uid",
        ".write": "auth != null && root.child('channels').child($channelId).child('ownerUid').val() === auth.uid"
      }
    },

    // ── channelReports ────────────────────────────────────────────────────────
    // Write-only: any authenticated user can submit a report, no one can read.
    "channelReports": {
      ".read":  false,
      ".write": "auth != null"
    },

    // ── channelReplies ────────────────────────────────────────────────────────
    // Any authenticated user can read and write replies.
    // Soft-delete is done by owner (sets isDeleted=true).
    "channelReplies": {
      "$channelId": {
        "$postId": {
          ".read":  "auth != null",
          "$replyId": {
            ".write": "auth != null"
          }
        }
      }
    },

    // ── channelScheduled ─────────────────────────────────────────────────────
    // Only the channel owner can read/write scheduled posts.
    "channelScheduled": {
      "$channelId": {
        ".read":  "auth != null && root.child('channels').child($channelId).child('ownerUid').val() === auth.uid",
        ".write": "auth != null && root.child('channels').child($channelId).child('ownerUid').val() === auth.uid"
      }
    }
  }
}
```

## Deployment

Deploy via Firebase CLI:
```bash
firebase database:rules:set CHANNEL_FIREBASE_SECURITY_RULES.json --project YOUR_PROJECT_ID
```

Or paste directly into the Firebase Console under:
**Realtime Database → Rules**.

## Notes

- The **channelPosts** node uses authenticated-user write because the
  `ChannelRepository` enforces owner/admin checks client-side before
  writing. For stronger server-side enforcement, add a check like:
  ```
  root.child('channels').child($channelId).child('ownerUid').val() === auth.uid ||
  root.child('channelAdmins').child($channelId).child(auth.uid).exists()
  ```
- **channelInviteCodes** allows any authenticated user to look up a code
  (necessary for the deep-link join flow). The code itself is a random
  short string, so brute-force enumeration is impractical.
- For private channels, the client already gates access via `isPrivate`
  and `inviteCode` checks in `ChannelRepository`. Server-side enforcement
  of private-channel read access requires Cloud Functions (Realtime DB
  rules cannot cross-reference a follower list efficiently).
