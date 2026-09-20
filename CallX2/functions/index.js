/**
 * CallX2 — Linked Devices backend
 * ================================
 * This is the one piece of the Linked Devices feature that CANNOT run
 * purely client-side: minting a Firebase Auth custom token for the
 * *account's* uid, so the web companion can sign in as that account without
 * ever touching the user's real credentials.
 *
 * Flow recap (see core/linkeddevice/LinkedDeviceManager.java and
 * callx2-web/callx2-web.html for the other two legs):
 *   1. Web writes pairingSessions/{code} = { status: "pending", deviceInfo }
 *   2. Phone (already authenticated) scans the QR, shows an approval sheet,
 *      and on "Link Device" writes status -> "approved" + uid + deviceId.
 *   3. THIS FUNCTION fires on that update, mints a custom token for `uid`,
 *      and writes it back to the same node as `customToken`.
 *   4. Web (listening the whole time) reads `customToken`, calls
 *      signInWithCustomToken(), and immediately deletes the pairing node —
 *      it's single-use and would otherwise leak a valid credential.
 *
 * Deploy with:
 *   cd functions && npm install
 *   firebase deploy --only functions
 */

const { initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getDatabase, ServerValue } = require("firebase-admin/database");
const { getMessaging } = require("firebase-admin/messaging");
const functions = require("firebase-functions/v1");

initializeApp();

const PAIRING_PATH = "/pairingSessions/{pairingCode}";

/**
 * Mint the custom token the instant a pairing flips to "approved".
 * Runs with Admin privileges — this is the only place in the whole feature
 * with elevated access, and it does exactly one narrowly-scoped thing.
 */
exports.onDevicePairingApproved = functions.database
  .ref(PAIRING_PATH)
  .onUpdate(async (change, context) => {
    const before = change.before.val();
    const after = change.after.val();
    const { pairingCode } = context.params;

    if (!after || after.status !== "approved" || (before && before.status === "approved")) {
      return null; // only act on the pending -> approved transition, once
    }
    if (!after.uid || !after.deviceId) {
      console.error(`Pairing ${pairingCode} approved without uid/deviceId — refusing to mint a token.`);
      return null;
    }

    try {
      // Custom claim ties the token to this specific linked-device entry so
      // security rules (or future audit tooling) can distinguish "signed in
      // from the phone" vs "signed in as a web companion" if ever needed.
      const token = await getAuth().createCustomToken(after.uid, {
        linkedDevice: true,
        deviceId: after.deviceId,
      });

      await change.after.ref.child("customToken").set(token);
      await change.after.ref.child("tokenIssuedAt").set(Date.now());
      console.log(`Minted companion token for uid=${after.uid} device=${after.deviceId}`);
    } catch (err) {
      console.error("Failed to mint custom token:", err);
      await change.after.ref.child("status").set("denied");
    }

    return null;
  });

/**
 * GAP FIX (#1 — "true server cursor nahi hai"):
 * =============================================
 * The Android delta-sync cursor (see ChatRepository#syncMessagesDelta and
 * CacheManager#getLastSyncCursor) has always been a client-derived
 * (timestamp, messageId) compound key. That's a solid *keyset* cursor for
 * pagination — no two rows ever collide — but it's NOT a real ack/sequence
 * token, because `timestamp` is written by whichever device sent the
 * message: offline send-queue flushes, multi-device clock skew, or just
 * two people typing at once can all produce a client-side ordering that
 * doesn't match "the order the server actually durably received them in."
 *
 * This function is the minimal piece that fixes that: the moment a new
 * message node is created, atomically hand it the next integer in a
 * per-chat counter using an RTDB transaction. `chatSeqCounters/{chatId}`
 * is a single integer per chat — `transaction()` guarantees the increment
 * is race-free even when two messages are written to the same chat by two
 * different clients in the same instant (the exact case a client-only
 * counter can't get right). `seq` is therefore a genuine, monotonic,
 * gap-free (per chat) server-issued cursor — the Android client stores it
 * in `message_sync_state.cursorSeq` (see MIGRATION_54_55 in AppDatabase)
 * and, once a chat has one, queries `orderByChild("seq").startAt(seq)`
 * instead of the timestamp-based query.
 *
 * BACKWARD COMPAT: existing messages written before this function was
 * deployed have no `seq` and are NOT backfilled by this function (a
 * one-time backfill script walking the whole `messages` tree is a
 * separate, explicit operational step — see the deploy note below, not
 * something to run automatically on every deploy). The Android client
 * already handles this: any chat whose stored cursor predates this
 * feature has no `cursorSeq` yet, so it keeps using the timestamp cursor
 * until a fresh full resync (or this function catching up on that chat's
 * next new message) gives it a seq to switch to.
 *
 * DEPLOY NOTE: to backfill *existing* chats instead of only new messages
 * going forward, run a one-off script that, per chatId, walks
 * `messages/{chatId}` ordered by `timestamp` ascending and writes
 * `seq: 1, 2, 3, ...` in that order, then seeds
 * `chatSeqCounters/{chatId}` to the final count — intentionally not
 * included here since it's a single-run migration, not steady-state
 * function code, and shouldn't risk re-running on every `firebase deploy`.
 */
const MESSAGE_PATH = "/messages/{chatId}/{messageId}";

exports.assignMessageSeq = functions.database
  .ref(MESSAGE_PATH)
  .onCreate(async (snapshot, context) => {
    const { chatId, messageId } = context.params;
    const message = snapshot.val();
    if (!message) return null;
    if (message.seq !== undefined && message.seq !== null) {
      // Already has one — a retried trigger invocation, or a client that
      // (incorrectly) set its own seq. Never let a client-supplied value
      // stand in for the authoritative one; still, don't reassign it.
      return null;
    }

    const counterRef = getDatabase().ref(`chatSeqCounters/${chatId}`);
    try {
      const result = await counterRef.transaction((current) => (current || 0) + 1);
      if (!result.committed) {
        console.error(`seq transaction did not commit for chat=${chatId} message=${messageId}`);
        return null;
      }
      const seq = result.snapshot.val();
      await snapshot.ref.update({ seq, seqAssignedAt: ServerValue.TIMESTAMP });
    } catch (err) {
      // Don't let a seq-assignment failure ever touch the message itself —
      // the message the user sent is already durably written; this is a
      // best-effort enrichment on top of it. A message that never gets a
      // seq just keeps falling back to timestamp-based sync for that one
      // message, same as pre-migration history.
      console.error(`Failed to assign seq for chat=${chatId} message=${messageId}:`, err);
    }
    return null;
  });


/**
 * PERF FIX — group presence fan-out reduction (N listeners → 1 listener).
 *
 * Previously every client viewing a group attached its OWN
 * ValueEventListener on users/{memberUid} for EVERY member of that group,
 * the instant the group screen opened — for a 50-member group with 10
 * simultaneous viewers, that's 500 live Firebase listeners doing the exact
 * same job. This trigger moves that fan-out server-side, ONCE, regardless
 * of how many clients are watching: whenever a user's online/lastSeen/
 * photoUrl changes, mirror it into groups/{groupId}/memberPresence/{uid}
 * for every group that user belongs to (from users/{uid}/groups). Clients
 * then attach exactly ONE listener — on groups/{groupId}/memberPresence —
 * to get every member's presence in a single snapshot/single callback.
 */
async function mirrorUserPresence(uid) {
  const db = getDatabase();
  const [onlineSnap, lastSeenSnap, photoSnap, groupsSnap] = await Promise.all([
    db.ref(`users/${uid}/online`).once("value"),
    db.ref(`users/${uid}/lastSeen`).once("value"),
    db.ref(`users/${uid}/photoUrl`).once("value"),
    db.ref(`users/${uid}/groups`).once("value"),
  ]);
  if (!groupsSnap.exists()) return null;

  const updates = {};
  groupsSnap.forEach((child) => {
    const groupId = child.key;
    updates[`groups/${groupId}/memberPresence/${uid}/online`] = onlineSnap.val() === true;
    updates[`groups/${groupId}/memberPresence/${uid}/lastSeen`] = lastSeenSnap.val() || 0;
    updates[`groups/${groupId}/memberPresence/${uid}/photoUrl`] = photoSnap.val() || null;
  });
  return db.ref().update(updates);
}

exports.mirrorPresenceOnOnlineChange = functions.database
  .ref("/users/{uid}/online")
  .onWrite((change, context) => mirrorUserPresence(context.params.uid));

exports.mirrorPresenceOnLastSeenChange = functions.database
  .ref("/users/{uid}/lastSeen")
  .onWrite((change, context) => mirrorUserPresence(context.params.uid));

/**
 * Also mirror the instant someone JOINS a group (users/{uid}/groups/{gid}
 * write) — otherwise a newly-added member wouldn't appear in
 * memberPresence until their online/lastSeen next happened to change.
 */
exports.mirrorPresenceOnGroupJoin = functions.database
  .ref("/users/{uid}/groups/{groupId}")
  .onCreate((snapshot, context) => mirrorUserPresence(context.params.uid));

/**
 * Remove pairing sessions that the web client left behind after expiry.
 *
 * code that sat around without ever being approved (the web client
 * regenerates its QR locally long before this runs — this just keeps the
 * database tidy).
 */
exports.cleanupExpiredPairingSessions = functions.pubsub
  .schedule("every 5 minutes")
  .onRun(async () => {
    const db = getDatabase();
    const snap = await db.ref("pairingSessions").once("value");
    const now = Date.now();
    const updates = {};

    snap.forEach((child) => {
      const session = child.val();
      if (session.status === "pending" && session.expiresAt && session.expiresAt < now) {
        updates[child.key] = null; // delete
      }
      // Approved sessions should be deleted client-side right after the web
      // client consumes the token; if one lingers >2 minutes, something went
      // wrong on the web side, so clean it up too.
      if (session.status === "approved" && session.tokenIssuedAt && now - session.tokenIssuedAt > 120000) {
        updates[child.key] = null;
      }
    });

    if (Object.keys(updates).length > 0) {
      await db.ref("pairingSessions").update(updates);
      console.log(`Cleaned up ${Object.keys(updates).length} stale pairing session(s).`);
    }
    return null;
  });

/**
 * ⏳ Countdown sticker — auto-notify-on-expiry (Status).
 * ======================================================
 * This is the one piece of the Countdown sticker's "🔔 Remind me" flow that
 * CANNOT run client-side (see UPGRADE_NOTES_v209_CountdownStickerFullFlow.md
 * and UPGRADE_NOTES_v260_CountdownStickerExpiryPush.md): actually pushing a
 * notification to every subscribed viewer the moment a countdown hits zero
 * needs something watching the clock even when nobody has the status open —
 * there's no cron/scheduler available on-device for that. Everything else
 * (subscribe/unsubscribe toggle, persistence, notifying the poster that
 * someone subscribed) is already handled client-side in
 * StatusStickerOverlayView / StatusViewerActivity / StatusReplyBottomSheet.
 *
 * Data model (see FirebaseUtils.getStatusCountdownSubscriberRef and
 * StatusStickerOverlayView#buildCountdown):
 *   status/{ownerUid}/{statusId}/stickersJson
 *     → JSON array; a countdown entry looks like
 *       { "type": "countdown", "label": "...", "targetDate": "yyyy-MM-dd" }
 *   status/{ownerUid}/{statusId}/stickerSubscribers/{stickerIndex}/{viewerUid}
 *     → { subscribed: true, timestamp, notified?: true, notifiedAt? }
 *   users/{uid}/fcmToken
 *     → the same per-user token every other push path in this app reads
 *       (see PushNotify.java / ChatRepository#fcmToken).
 *
 * Every 5 minutes: scan statuses for a countdown sticker whose targetDate
 * has passed, and for each of its subscribers who hasn't been notified yet,
 * send one FCM push and mark `notified: true` so a later run (or the
 * countdown having several subscribers) never double-sends.
 *
 * SCOPE NOTE: this sends the push notification only — it does not also send
 * a quoted chat DM the way subscribe-time does, because building that
 * message requires the same on-device E2E encryption the chat pipeline
 * uses for every other message (see MessageEntity#mediaKeyEnc's doc), which
 * an Admin-privileged backend function has no legitimate reason to hold.
 *
 * SCALE NOTE: like cleanupExpiredPairingSessions above, this walks the
 * whole `status` node once per run. Fine at this app's current data volume
 * (statuses already self-expire after 24h); if that ever becomes a real
 * cost, the fix is a lightweight write-time index — e.g.
 * countdownExpiryQueue/{targetDateEpochDay}/{ownerUid}_{statusId}_{stickerIndex}
 * populated by the composer when a countdown sticker is attached — so this
 * function can query just "today's" bucket instead of every status.
 *
 * TIMEZONE NOTE: targetDate is a bare "yyyy-MM-dd" with no timezone — the
 * composer never captured one, and the client itself parses it with the
 * device's default timezone (see StatusStickerOverlayView#startCountdown).
 * This function treats it as UTC midnight, which matches the client to
 * within a few hours depending on the poster's timezone; tightening that
 * would need the composer to start storing an explicit UTC epoch instead of
 * a date string, which is a separate, larger change to the sticker JSON
 * shape shared by the composer, the overlay view, and every existing
 * countdown already posted.
 */
exports.notifyExpiredCountdownStickers = functions.pubsub
  .schedule("every 5 minutes")
  .onRun(async () => {
    const db = getDatabase();
    const statusRootSnap = await db.ref("status").once("value");
    if (!statusRootSnap.exists()) return null;

    const now = Date.now();
    const messaging = getMessaging();

    // A viewer can be subscribed to countdowns on several different
    // statuses in the same run — cache each uid's token lookup once.
    const tokenCache = new Map();
    async function tokenFor(uid) {
      if (tokenCache.has(uid)) return tokenCache.get(uid);
      const snap = await db.ref("users").child(uid).child("fcmToken").once("value");
      const token = snap.exists() ? snap.val() : null;
      tokenCache.set(uid, token);
      return token;
    }

    let notifiedCount = 0;
    let staleTokenCount = 0;
    const pendingWork = [];

    statusRootSnap.forEach((ownerSnap) => {
      const ownerUid = ownerSnap.key;

      ownerSnap.forEach((statusSnap) => {
        const statusId = statusSnap.key;
        const stickersJsonRaw = statusSnap.child("stickersJson").val();
        if (!stickersJsonRaw) return;

        let stickers;
        try {
          stickers = JSON.parse(stickersJsonRaw);
        } catch (e) {
          return; // malformed/legacy stickersJson — same tolerance the client's own parser uses
        }
        if (!Array.isArray(stickers)) return;

        stickers.forEach((sticker, stickerIndex) => {
          if (!sticker || sticker.type !== "countdown" || !sticker.targetDate) return;

          const targetMs = Date.parse(`${sticker.targetDate}T00:00:00Z`);
          if (!Number.isFinite(targetMs) || targetMs > now) return; // not expired yet

          const subscribersSnap = statusSnap.child("stickerSubscribers").child(String(stickerIndex));
          if (!subscribersSnap.exists()) return;

          subscribersSnap.forEach((subSnap) => {
            const viewerUid = subSnap.key;
            const sub = subSnap.val() || {};
            if (!sub.subscribed || sub.notified) return;

            pendingWork.push((async () => {
              const token = await tokenFor(viewerUid);
              if (!token) {
                staleTokenCount++;
                // No token on file — still mark notified so this doesn't
                // get retried forever; the viewer will see the "ended"
                // state next time they open the status either way.
                await subSnap.ref.update({ notified: true, notifiedAt: ServerValue.TIMESTAMP });
                return;
              }

              try {
                await messaging.send({
                  token,
                  notification: {
                    title: "⏳ Countdown ended",
                    body: `"${sticker.label || "Countdown"}" just hit zero — tap to see what's next.`,
                  },
                  data: {
                    type: "countdown_expired",
                    ownerUid,
                    statusId,
                    stickerIndex: String(stickerIndex),
                  },
                });
                notifiedCount++;
              } catch (err) {
                // Expired/unregistered token, etc. — don't let one bad
                // token throw the whole run; skip and mark notified so it
                // isn't retried every 5 minutes forever.
                console.error(`Countdown push failed for viewer=${viewerUid}:`, err.message || err);
              }
              await subSnap.ref.update({ notified: true, notifiedAt: ServerValue.TIMESTAMP });
            })());
          });
        });
      });
    });

    await Promise.all(pendingWork);
    if (notifiedCount > 0 || staleTokenCount > 0) {
      console.log(`Countdown expiry sweep: ${notifiedCount} push(es) sent, ${staleTokenCount} subscriber(s) had no token on file.`);
    }
    return null;
  });

/**
 * 💬 Story comments cleanup on expiry (Instagram-style).
 * ========================================================
 * Statuses only "self-expire" client-side today — StatusFragment/
 * StatusViewerActivity just filter out anything with expiresAt < now, the
 * underlying status/{ownerUid}/{statusId} node (and its replies) is never
 * actually removed. That's fine for the story media itself, but it means
 * public comments (status/{ownerUid}/{statusId}/replies, see
 * FirebaseUtils#getStatusRepliesRef) would otherwise sit around forever
 * for a story nobody can even watch anymore.
 *
 * Rule (matches Instagram):
 *   - Story expired AND never added to a Highlight → delete its comments.
 *   - Story expired but IS in a Highlight (isHighlighted === true, set by
 *     StatusHighlightManager#addToHighlight) → comments are left alone.
 *     StatusViewerActivity reads the live comment overlay from this same
 *     status/{ownerUid}/{statusId}/replies node whether you're watching the
 *     story live or replaying it from a Highlight album (both use the same
 *     statusId), so leaving replies in place here is what makes highlighted
 *     comments keep showing up — no separate copy step needed.
 *
 * Only the `replies` child is removed, never the status doc itself — the
 * client's own expiresAt filtering already hides expired stories from the
 * feed, so this only needs to clean up the one thing that doesn't
 * self-hide (the public comment overlay data).
 */
exports.cleanupExpiredStatusReplies = functions.pubsub
  .schedule("every 30 minutes")
  .onRun(async () => {
    const db = getDatabase();
    const statusRootSnap = await db.ref("status").once("value");
    if (!statusRootSnap.exists()) return null;

    const now = Date.now();
    const pendingDeletes = [];
    let deletedCount = 0;

    statusRootSnap.forEach((ownerSnap) => {
      ownerSnap.forEach((statusSnap) => {
        const expiresAt = statusSnap.child("expiresAt").val();
        if (!expiresAt || expiresAt >= now) return; // not expired yet / no expiry set

        const isHighlighted = statusSnap.child("isHighlighted").val() === true;
        if (isHighlighted) return; // Instagram-style: keep comments for highlighted stories

        const repliesRef = statusSnap.child("replies").ref;
        if (!statusSnap.child("replies").exists()) return;

        pendingDeletes.push(repliesRef.remove().then(() => { deletedCount++; }));
      });
    });

    await Promise.all(pendingDeletes);
    if (deletedCount > 0) {
      console.log(`Expired-story comment sweep: cleared replies on ${deletedCount} expired, non-highlighted status(es).`);
    }
    return null;
  });

/**
 * CallX Admin Control Center
 * ==========================
 * All privileged admin operations live behind this callable boundary. The
 * Android admin app never receives a service-account credential and never
 * reads the write-only moderation trees directly.
 *
 * Backwards compatible policy:
 *   admins/{uid}: true
 *     -> super_admin
 *   admins/{uid}: { role: "moderator", permissions: {...} }
 *
 * Deploy together with the admin APK:
 *   firebase deploy --only functions:adminAction
 */
const ADMIN_READ_ROOTS = [
  "reelReports", "reelCommentReports", "reelReplyReports", "reports",
  "community_reports", "channelReports", "groupReports", "sound_reports",
];

function adminRole(node) {
  if (node === true) return "super_admin";
  if (node && typeof node === "object") return node.role || "moderator";
  return null;
}

async function requireAdmin(context, operation) {
  if (!context.auth || !context.auth.uid) {
    throw new functions.https.HttpsError("unauthenticated", "Admin sign-in required.");
  }
  const snap = await getDatabase().ref(`admins/${context.auth.uid}`).once("value");
  const node = snap.val();
  const role = adminRole(node);
  if (!role) throw new functions.https.HttpsError("permission-denied", "Admin access revoked.");
  const permissions = node && typeof node === "object" ? (node.permissions || {}) : {};
  const superOnly = ["deleteUser", "organizationAction", "setConfig", "setAdmin",
    "removeAdmin", "updateMilestoneConfig"];
  if (superOnly.includes(operation) && role !== "super_admin" && !permissions[operation]) {
    throw new functions.https.HttpsError("permission-denied", "This role cannot perform that action.");
  }
  if ((operation === "paymentReview" || operation === "reviewCreatorPayout"
      || operation === "reviewMilestonePayout")
      && role !== "super_admin" && role !== "finance"
      && !permissions.paymentReview && !permissions.reviewCreatorPayout
      && !permissions.reviewMilestonePayout) {
    throw new functions.https.HttpsError("permission-denied", "Finance permission required.");
  }
  return { uid: context.auth.uid, role, permissions };
}

async function audit(adminUid, action, targetType, targetId, details) {
  await getDatabase().ref("admin_audit").push({
    adminUid, action, targetType: targetType || "", targetId: targetId || "",
    details: typeof details === "string" ? details : JSON.stringify(details || {}),
    createdAt: ServerValue.TIMESTAMP,
  });
}

function asObject(value) {
  return value && typeof value === "object" ? value : {};
}

const CREATOR_MONETIZATION_LEVELS = [
  { key: "starter", name: "Starter", followers: 1000, posts30d: 20, totalViews: 10000, singleReelViews: 1000 },
  { key: "pro", name: "Pro", followers: 5000, posts30d: 30, totalViews: 100000, singleReelViews: 1000 },
  { key: "pro_plus", name: "Pro Plus", followers: 25000, posts30d: 30, totalViews: 500000, singleReelViews: 1000 },
];

const DEFAULT_MILESTONE_EARNINGS = {
  enabled: true,
  levels: [
    { level: 1, likes: 100, following: 200, shares: 50, rewardPaise: 500 },
    { level: 2, likes: 250, following: 500, shares: 100, rewardPaise: 1000 },
    { level: 3, likes: 500, following: 800, shares: 200, rewardPaise: 2000 },
    { level: 4, likes: 1000, following: 1200, shares: 300, rewardPaise: 4000 },
    { level: 5, likes: 1500, following: 1600, shares: 400, rewardPaise: 7500 },
    { level: 6, likes: 2500, following: 2000, shares: 500, rewardPaise: 15000 },
    { level: 7, likes: 5000, following: 2500, shares: 600, rewardPaise: 30000 },
    { level: 8, likes: 10000, following: 3000, shares: 700, rewardPaise: 50000 },
  ],
};

// One source of truth for the merged verification + talent product. Prices are
// deliberately stored in rupees and are returned to both Android clients and
// the admin app. The admin can override them under
// appConfig/verificationBadgeCatalog without an app release.
const DEFAULT_VERIFICATION_BADGE_PLANS = [
  { key: "star_monthly", tierKey: "star", tierName: "Star", periodKey: "monthly",
    name: "Star Verification", priceRupees: 195, period: "Monthly",
    benefits: ["Star badge on your profile", "Verified checkmark", "Priority creator support"] },
  { key: "star_quarterly", tierKey: "star", tierName: "Star", periodKey: "quarterly",
    name: "Star Verification", priceRupees: 395, period: "3 Months",
    benefits: ["Star badge on your profile", "Verified checkmark", "Priority creator support"] },
  { key: "star_half_year", tierKey: "star", tierName: "Star", periodKey: "half_year",
    name: "Star Verification", priceRupees: 795, period: "6 Months",
    benefits: ["Star badge on your profile", "Verified checkmark", "Priority creator support"] },
  { key: "star_yearly", tierKey: "star", tierName: "Star", periodKey: "yearly",
    name: "Star Verification", priceRupees: 1395, period: "Yearly",
    benefits: ["Star badge on your profile", "Verified checkmark", "Priority creator support"] },
  { key: "gold_monthly", tierKey: "gold", tierName: "Gold", periodKey: "monthly",
    name: "Gold Verification", priceRupees: 299, period: "Monthly",
    benefits: ["Gold badge on your profile", "Verified checkmark", "Enhanced creator discovery"] },
  { key: "gold_quarterly", tierKey: "gold", tierName: "Gold", periodKey: "quarterly",
    name: "Gold Verification", priceRupees: 599, period: "3 Months",
    benefits: ["Gold badge on your profile", "Verified checkmark", "Enhanced creator discovery"] },
  { key: "gold_half_year", tierKey: "gold", tierName: "Gold", periodKey: "half_year",
    name: "Gold Verification", priceRupees: 1199, period: "6 Months",
    benefits: ["Gold badge on your profile", "Verified checkmark", "Enhanced creator discovery"] },
  { key: "gold_yearly", tierKey: "gold", tierName: "Gold", periodKey: "yearly",
    name: "Gold Verification", priceRupees: 2099, period: "Yearly",
    benefits: ["Gold badge on your profile", "Verified checkmark", "Enhanced creator discovery"] },
  { key: "platinum_monthly", tierKey: "platinum", tierName: "Platinum", periodKey: "monthly",
    name: "Platinum Verification", priceRupees: 499, period: "Monthly",
    benefits: ["Platinum badge on your profile", "Verified checkmark", "Featured creator placement"] },
  { key: "platinum_quarterly", tierKey: "platinum", tierName: "Platinum", periodKey: "quarterly",
    name: "Platinum Verification", priceRupees: 999, period: "3 Months",
    benefits: ["Platinum badge on your profile", "Verified checkmark", "Featured creator placement"] },
  { key: "platinum_half_year", tierKey: "platinum", tierName: "Platinum", periodKey: "half_year",
    name: "Platinum Verification", priceRupees: 1999, period: "6 Months",
    benefits: ["Platinum badge on your profile", "Verified checkmark", "Featured creator placement"] },
  { key: "platinum_yearly", tierKey: "platinum", tierName: "Platinum", periodKey: "yearly",
    name: "Platinum Verification", priceRupees: 3499, period: "Yearly",
    benefits: ["Platinum badge on your profile", "Verified checkmark", "Featured creator placement"] },
];

const VERIFICATION_TIERS = [
  { key: "star", name: "Star", icon: "★" },
  { key: "gold", name: "Gold", icon: "★" },
  { key: "platinum", name: "Platinum", icon: "♛" },
];

const VERIFICATION_PERIODS = [
  { key: "monthly", name: "Monthly", months: 1 },
  { key: "quarterly", name: "3 Months", months: 3 },
  { key: "half_year", name: "6 Months", months: 6 },
  { key: "yearly", name: "Yearly", months: 12 },
];

// Kept as a compatibility alias for old admin builds and the old route.
const STAR_TALENT_TIERS = DEFAULT_VERIFICATION_BADGE_PLANS
  .filter((plan) => plan.periodKey === "monthly")
  .map((plan) => ({ key: plan.tierKey, name: `Become a ${plan.tierName} Talent`,
    priceRupees: plan.priceRupees, benefits: plan.benefits }));

async function verificationCatalog(db) {
  const snap = await db.ref("appConfig/verificationBadgeCatalog").once("value");
  const configured = asObject(snap.val());
  const prices = asObject(configured.prices || configured);
  return DEFAULT_VERIFICATION_BADGE_PLANS.map((plan) => {
    const configuredPrice = Number(prices[plan.key]);
    return { ...plan, priceRupees: Number.isFinite(configuredPrice) && configuredPrice > 0
      ? Math.round(configuredPrice) : plan.priceRupees };
  });
}

function findVerificationPlan(catalog, tierKey, periodKey, planKey) {
  if (planKey) {
    const exact = catalog.find((plan) => plan.key === String(planKey));
    if (exact) return exact;
  }
  const tier = String(tierKey || "").toLowerCase();
  const period = String(periodKey || "monthly").toLowerCase();
  return catalog.find((plan) => plan.tierKey === tier && plan.periodKey === period) || null;
}

function legacyTier(value) {
  const tier = String(value || "").toLowerCase();
  return ["star", "gold", "platinum"].includes(tier) ? tier : "";
}

function catalogItem(catalog, key) {
  return catalog.find((item) => item.key === String(key || "")) || null;
}

async function verificationBadgeView(uid, db) {
  const [userSnap, requestSnap, catalog] = await Promise.all([
    db.ref(`users/${uid}`).once("value"),
    db.ref(`verification_requests/${uid}`).once("value"),
    verificationCatalog(db),
  ]);
  const user = asObject(userSnap.val());
  const request = asObject(requestSnap.val());
  const badgeTier = legacyTier(user.badgeTier || user.talentPlan || user.talentTier || user.talent);
  return {
    plans: catalog,
    tiers: VERIFICATION_TIERS,
    periods: VERIFICATION_PERIODS,
    isVerified: user.isVerified === true || user.verified === true || user.blueBadge === true,
    badgeTier,
    badgePeriod: user.badgePeriod || "",
    currentPlan: user.verificationPlan || "",
    status: request.status || "",
    request: requestSnap.exists() ? request : null,
  };
}

async function handleVerificationBadgeAction(data, context) {
  if (!context.auth || !context.auth.uid) {
    throw new functions.https.HttpsError("unauthenticated", "Sign-in required.");
  }
  const uid = context.auth.uid;
  const db = getDatabase();
  const action = String(data && data.action || "get");
  const payload = asObject(data && data.payload);
  if (action === "get") return verificationBadgeView(uid, db);
  if (!["purchase", "request", "apply"].includes(action)) {
    throw new functions.https.HttpsError("invalid-argument", "Unknown verification action.");
  }
  const catalog = await verificationCatalog(db);
  const plan = findVerificationPlan(catalog, payload.tierKey || payload.tier,
    payload.periodKey || payload.period, payload.planKey);
  const reason = String(payload.reason || "").trim().slice(0, 1000);
  const isManualRequest = !plan && !payload.tierKey && !payload.tier && !payload.planKey;
  if (!plan && !isManualRequest) {
    throw new functions.https.HttpsError("invalid-argument", "Choose a valid badge tier and period.");
  }
  const view = await verificationBadgeView(uid, db);
  if (view.isVerified) {
    throw new functions.https.HttpsError("failed-precondition", "This profile is already verified.");
  }
  if (view.status === "pending") {
    throw new functions.https.HttpsError("already-exists", "A verification request is already under review.");
  }
  const userSnap = await db.ref(`users/${uid}`).once("value");
  const user = asObject(userSnap.val());
  const request = {
    uid, name: user.name || user.displayName || "CallX creator",
    photoUrl: user.photoUrl || user.photo || "", reason: reason || (plan
      ? "Paid verified badge plan" : "Manual verification request"),
    source: plan ? "verified_badge_plan" : "manual_verification",
    planKey: plan ? plan.key : "manual",
    planName: plan ? plan.name : "Manual Verification",
    tierKey: plan ? plan.tierKey : "",
    tierName: plan ? plan.tierName : "",
    periodKey: plan ? plan.periodKey : "",
    period: plan ? plan.period : "",
    priceRupees: plan ? plan.priceRupees : 0,
    amountPaise: plan ? plan.priceRupees * 100 : 0,
    status: "pending", submittedAt: ServerValue.TIMESTAMP,
  };
  await Promise.all([
    db.ref(`verification_requests/${uid}`).set(request),
    db.ref(`verificationBadgeRequests/${uid}`).set(request),
  ]);
  return { ...(await verificationBadgeView(uid, db)), submitted: true };
}

exports.verificationBadgeAction = functions.https.onCall(handleVerificationBadgeAction);

exports.starTalentAction = functions.https.onCall(async (data, context) => {
  const action = String(data && data.action || "get");
  const payload = asObject(data && data.payload);
  if (action === "get") {
    const result = await handleVerificationBadgeAction({ action: "get", payload: {} }, context);
    return { ...result, tiers: STAR_TALENT_TIERS };
  }
  return handleVerificationBadgeAction({
    action: "purchase",
    payload: { tierKey: payload.tierKey, periodKey: payload.periodKey || "monthly",
      reason: payload.reason || payload.category || "" },
  }, context);
});

function normalizeMilestoneConfig(value) {
  const source = asObject(value);
  const rawLevels = Array.isArray(source.levels) ? source.levels : [];
  const levels = rawLevels.map((raw, index) => {
    const row = asObject(raw);
    return {
      level: Number(row.level || index + 1),
      likes: Math.max(0, Number(row.likes || 0)),
      following: Math.max(0, Number(row.following || 0)),
      shares: Math.max(0, Number(row.shares || 0)),
      rewardPaise: Math.max(0, Number(row.rewardPaise || 0)),
    };
  }).filter((row) => row.level > 0 && row.likes > 0 && row.following > 0 && row.shares > 0)
    .sort((a, b) => a.level - b.level).slice(0, 20);
  return {
    enabled: source.enabled !== false,
    levels: levels.length ? levels : DEFAULT_MILESTONE_EARNINGS.levels,
  };
}

async function milestoneConfig(db) {
  const snap = await db.ref("appConfig/milestoneEarnings").once("value");
  return normalizeMilestoneConfig(snap.val());
}

async function milestoneStats(uid, db) {
  const [likes, following, shares] = await Promise.all([
    db.ref(`reelLikedByUser/${uid}`).once("value"),
    db.ref(`reelFollows/${uid}`).once("value"),
    db.ref(`milestoneShareEvents/${uid}`).once("value"),
  ]);
  return {
    uniqueLikes: likes.numChildren(),
    following: following.numChildren(),
    whatsappShares: shares.numChildren(),
  };
}

function milestoneProgress(stats, config) {
  const levels = config.levels;
  const completedLevels = levels.filter((level) =>
    stats.uniqueLikes >= level.likes
      && stats.following >= level.following
      && stats.whatsappShares >= level.shares);
  const highestCompletedLevel = completedLevels.length
    ? completedLevels[completedLevels.length - 1].level : 0;
  const active = levels.find((level) => level.level > highestCompletedLevel) || levels[levels.length - 1];
  const pct = (value, target) => target <= 0
    ? 100 : Math.min(100, Math.round((value / target) * 100));
  const likesPercent = pct(stats.uniqueLikes, active.likes);
  const followingPercent = pct(stats.following, active.following);
  const sharesPercent = pct(stats.whatsappShares, active.shares);
  return {
    completedLevels, highestCompletedLevel, active,
    overallPercent: Math.round((likesPercent + followingPercent + sharesPercent) / 3),
    likesPercent, followingPercent, sharesPercent,
  };
}

async function syncMilestoneRewards(uid, db, progress) {
  const ref = db.ref(`milestoneEarnings/${uid}`);
  await ref.transaction((current) => {
    const state = asObject(current);
    const rewards = asObject(state.rewards);
    const history = asObject(state.history);
    let balancePaise = Number(state.balancePaise || 0);
    let lifetimePaise = Number(state.lifetimePaise || 0);
    const completedKeys = new Set(progress.completedLevels.map((level) => `level_${level.level}`));
    Object.entries(rewards).forEach(([key, reward]) => {
      if (!completedKeys.has(key) && reward && reward.status === "credited") {
        const amount = Number(reward.amountPaise || 0);
        balancePaise = Math.max(0, balancePaise - amount);
        rewards[key] = {
          ...reward, status: "reversed", reversedAt: Date.now(),
        };
        history[key] = rewards[key];
      }
    });
    progress.completedLevels.forEach((level) => {
      const key = `level_${level.level}`;
      if (rewards[key]) return;
      rewards[key] = {
        type: "milestone_reward", level: level.level,
        amountPaise: level.rewardPaise, status: "credited",
        creditedAt: Date.now(),
      };
      history[key] = rewards[key];
      balancePaise += level.rewardPaise;
      lifetimePaise += level.rewardPaise;
    });
    return {
      ...state, rewards, history, balancePaise, lifetimePaise,
      highestCompletedLevel: Math.max(Number(state.highestCompletedLevel || 0),
        progress.highestCompletedLevel),
      updatedAt: Date.now(),
    };
  });
  return ref.once("value");
}

async function milestoneView(uid, db) {
  const [config, stats] = await Promise.all([milestoneConfig(db), milestoneStats(uid, db)]);
  const progress = milestoneProgress(stats, config);
  const stateSnap = await syncMilestoneRewards(uid, db, progress);
  const state = asObject(stateSnap.val());
  const history = Object.entries(asObject(state.history)).map(([id, value]) => ({
    id, ...asObject(value),
  })).sort((a, b) => Number(b.creditedAt || b.requestedAt || 0)
    - Number(a.creditedAt || a.requestedAt || 0));
  const active = progress.active || {};
  return {
    enabled: config.enabled,
    config,
    stats: {
      uniqueLikes: stats.uniqueLikes,
      following: stats.following,
      whatsappShares: stats.whatsappShares,
    },
    currentLevel: active.level || 1,
    highestCompletedLevel: progress.highestCompletedLevel,
    overallPercent: progress.overallPercent,
    metricPercents: {
      likes: progress.likesPercent,
      following: progress.followingPercent,
      shares: progress.sharesPercent,
    },
    activeLevel: active,
    availablePaise: Number(state.balancePaise || 0),
    pendingPaise: Number(state.pendingPayoutPaise || 0),
    lifetimePaise: Number(state.lifetimePaise || 0),
    withdrawalsUnlocked: progress.highestCompletedLevel >= 3,
    payoutSummary: state.pendingPayoutPaise
      ? "Withdrawal request is under review."
      : progress.highestCompletedLevel >= 3
        ? "Withdrawals are unlocked at Level 3."
        : "Withdrawals unlock at Level 3.",
    history: history.slice(0, 50),
    levels: config.levels,
  };
}

function childCount(node) {
  let count = 0;
  if (node && typeof node.forEach === "function") node.forEach(() => { count++; });
  return count;
}

function numericChild(value, keys) {
  const object = asObject(value);
  for (const key of keys) {
    if (typeof object[key] === "number") return Number(object[key]);
  }
  return 0;
}

async function creatorStats(uid, db) {
  const [userSnap, reelsSnap] = await Promise.all([
    db.ref(`users/${uid}`).once("value"),
    db.ref("reels").once("value"),
  ]);
  const user = asObject(userSnap.val());
  const thirtyDaysAgo = Date.now() - 30 * 24 * 60 * 60 * 1000;
  let totalViews = 0;
  let maxReelViews = 0;
  let posts30d = 0;
  reelsSnap.forEach((reelSnap) => {
    const reel = asObject(reelSnap.val());
    const owner = reel.ownerUid || reel.uid || reel.creatorUid || reel.userId;
    if (owner !== uid) return;
    const views = numericChild(reel, ["viewsCount", "viewCount", "views"]);
    totalViews += views;
    maxReelViews = Math.max(maxReelViews, views);
    const createdAt = Number(reel.createdAt || reel.created_at || reel.timestamp || 0);
    if (createdAt >= thirtyDaysAgo) posts30d++;
  });
  const followers = numericChild(user, ["followersCount", "followerCount"])
    || childCount(userSnap.child("followers"));
  const following = numericChild(user, ["followingCount"])
    || childCount(userSnap.child("following"));
  const talentPlan = String(user.talentPlan || user.talentTier || user.talent || "").toLowerCase();
  const verified = user.isVerified === true || user.verified === true || user.blueBadge === true;
  return {
    followers, following, totalViews, maxReelViews, posts30d, verified,
    hasTalentPlan: ["star", "gold", "platinum"].includes(talentPlan),
    talentPlan: talentPlan || "none",
  };
}

function creatorLevelFor(followers) {
  if (followers >= 25000) return { label: "Level 3 Creator", goal: 0 };
  if (followers >= 5000) return { label: "Level 2 Creator", goal: 25000 };
  return { label: "Level 1 Creator", goal: followers < 1000 ? 1000 : 5000 };
}

function eligibleLevel(stats) {
  let matched = null;
  CREATOR_MONETIZATION_LEVELS.forEach((level) => {
    const ok = stats.followers >= level.followers
      && stats.posts30d >= level.posts30d
      && stats.totalViews >= level.totalViews
      && stats.maxReelViews >= level.singleReelViews
      && stats.verified && stats.hasTalentPlan;
    if (ok) matched = level;
  });
  return matched;
}

function centsToUsd(cents) {
  return Number((Number(cents || 0) / 100).toFixed(2));
}

function reelRateCents(plan, views) {
  const rates = plan === "pro_plus"
    ? [[10000, 500], [25000, 1200], [50000, 2500], [100000, 5000], [250000, 10000]]
    : plan === "pro"
      ? [[5000, 100], [10000, 300], [25000, 700], [50000, 1500], [100000, 3000]]
      : [[1000, 10], [2000, 50], [3000, 100], [5000, 200], [10000, 500]];
  let earned = 0;
  rates.forEach(([minimumViews, cents]) => {
    if (views >= minimumViews) earned = cents;
  });
  return earned;
}

async function readCreatorState(uid, db) {
  const [stateSnap, legacySnap] = await Promise.all([
    db.ref(`creatorMonetization/${uid}`).once("value"),
    db.ref(`reelCreatorFund/${uid}`).once("value"),
  ]);
  const state = asObject(stateSnap.val());
  if (Object.keys(state).length > 0) return state;
  const legacy = asObject(legacySnap.val());
  return {
    enrolled: legacy.enrolled === true,
    balanceCents: Math.round(Number(legacy.balance || 0) / 10),
    lifetimeCents: Math.round(Number(legacy.lifetimeEarnings || 0) / 10),
    payouts: legacy.payouts || {},
  };
}

/**
 * View-count settlement is server-side and idempotent. Each reel stores the
 * last rate already credited, so a repeated Firebase delivery cannot pay the
 * same view milestone twice.
 */
exports.settleCreatorReelViews = functions.database
  .ref("/reels/{reelId}/viewsCount")
  .onWrite(async (change, context) => {
    const afterViews = Number(change.after.val() || 0);
    const beforeViews = Number(change.before.val() || 0);
    if (!change.after.exists() || afterViews <= beforeViews) return null;
    const reelSnap = await getDatabase().ref(`reels/${context.params.reelId}`).once("value");
    const reel = asObject(reelSnap.val());
    const uid = reel.ownerUid || reel.uid || reel.creatorUid || reel.userId;
    if (!uid) return null;
    const db = getDatabase();
    const [stats, stateSnap] = await Promise.all([
      creatorStats(uid, db),
      db.ref(`creatorMonetization/${uid}`).once("value"),
    ]);
    const state = asObject(stateSnap.val());
    if (state.enrolled !== true) return null;
    const level = eligibleLevel(stats);
    if (!level) return null;
    const rateNow = reelRateCents(level.key, afterViews);
    const creditedBefore = Number(state.reelEarnings
      && state.reelEarnings[context.params.reelId] || 0);
    const delta = rateNow - creditedBefore;
    if (delta <= 0) return null;
    const stateRef = db.ref(`creatorMonetization/${uid}`);
    await stateRef.transaction((current) => {
      const next = asObject(current);
      next.balanceCents = Number(next.balanceCents || 0) + delta;
      next.lifetimeCents = Number(next.lifetimeCents || 0) + delta;
      next.reelEarnings = asObject(next.reelEarnings);
      next.reelEarnings[context.params.reelId] = rateNow;
      next.lastSettledAt = Date.now();
      return next;
    });
    return null;
  });

async function creatorMonetizationView(uid, db) {
  const [stats, state] = await Promise.all([creatorStats(uid, db), readCreatorState(uid, db)]);
  const level = creatorLevelFor(stats.followers);
  const eligible = eligibleLevel(stats);
  const balanceCents = Number(state.balanceCents || 0);
  const pendingCents = Number(state.pendingPayoutCents || 0);
  const lifetimeCents = Number(state.lifetimeCents || 0);
  const payouts = [];
  Object.entries(asObject(state.payouts)).forEach(([id, payout]) => {
    payouts.push({ id, ...asObject(payout) });
  });
  payouts.sort((a, b) => Number(b.requestedAt || 0) - Number(a.requestedAt || 0));
  const missing = [];
  const starter = CREATOR_MONETIZATION_LEVELS[0];
  if (stats.followers < starter.followers) missing.push(`${starter.followers - stats.followers} more followers`);
  if (stats.posts30d < starter.posts30d) missing.push(`${starter.posts30d - stats.posts30d} more posts in 30 days`);
  if (stats.totalViews < starter.totalViews) missing.push(`${starter.totalViews - stats.totalViews} more total views`);
  if (stats.maxReelViews < starter.singleReelViews) missing.push("1 reel with 1,000 views");
  if (!stats.verified) missing.push("verified blue badge");
  if (!stats.hasTalentPlan) missing.push("Star, Gold or Platinum talent plan");
  const pending = payouts.find((payout) => ["pending", "approved", "processing"].includes(payout.status));
  return {
    stats,
    creatorLevel: level.label,
    nextFollowerGoal: level.goal,
    eligible: !!eligible,
    eligiblePlan: eligible ? eligible.name : "",
    eligibilitySummary: eligible
      ? `Eligible for ${eligible.name}. Keep creating to unlock the next level.`
      : `Complete: ${missing.slice(0, 3).join(" • ")}${missing.length > 3 ? " • and more" : ""}`,
    enrolled: state.enrolled === true,
    earnings: {
      availableCents: balanceCents, availableUsd: centsToUsd(balanceCents),
      pendingCents, pendingUsd: centsToUsd(pendingCents),
      lifetimeCents, lifetimeUsd: centsToUsd(lifetimeCents),
    },
    payoutSummary: pending
      ? `Payout ${String(pending.status).toLowerCase()} • $${centsToUsd(pending.amountCents)}`
      : "Payouts are reviewed and released after approval.",
    payouts: payouts.slice(0, 20),
  };
}

exports.creatorMonetizationAction = functions.https.onCall(async (data, context) => {
  if (!context.auth || !context.auth.uid) {
    throw new functions.https.HttpsError("unauthenticated", "Sign-in required.");
  }
  const action = data && data.action;
  const uid = context.auth.uid;
  const db = getDatabase();
  const stateRef = db.ref(`creatorMonetization/${uid}`);
  if (action === "get") return creatorMonetizationView(uid, db);

  if (action === "setEnrollment") {
    const enabled = data && data.payload && data.payload.enabled === true;
    const view = await creatorMonetizationView(uid, db);
    if (enabled && !view.eligible) {
      throw new functions.https.HttpsError("failed-precondition",
        "Your account has not met the Starter eligibility requirements yet.");
    }
    await stateRef.update({ enrolled: enabled, enrollmentUpdatedAt: ServerValue.TIMESTAMP });
    return creatorMonetizationView(uid, db);
  }

  if (action === "requestPayout") {
    const view = await creatorMonetizationView(uid, db);
    if (!view.enrolled) throw new functions.https.HttpsError("failed-precondition", "Start monetization first.");
    if (!view.eligible) throw new functions.https.HttpsError("failed-precondition", "Meet the creator requirements first.");
    if (view.earnings.availableCents <= 0) throw new functions.https.HttpsError("failed-precondition", "There is no available balance.");
    if (view.payouts.some((payout) => ["pending", "approved", "processing"].includes(payout.status))) {
      throw new functions.https.HttpsError("already-exists", "A payout is already under review.");
    }
    const payoutRef = stateRef.child("payouts").push();
    const amountCents = view.earnings.availableCents;
    await stateRef.update({
      balanceCents: 0, pendingPayoutCents: amountCents,
      lastPayoutRequestedAt: ServerValue.TIMESTAMP,
    });
    await payoutRef.set({
      amountCents, amountUsd: centsToUsd(amountCents), status: "pending",
      requestedAt: ServerValue.TIMESTAMP, uid,
    });
    return { ok: true, payoutId: payoutRef.key, amountCents };
  }

  throw new functions.https.HttpsError("invalid-argument", `Unknown monetization action: ${action}`);
});

exports.milestoneEarningsAction = functions.https.onCall(async (data, context) => {
  if (!context.auth || !context.auth.uid) {
    throw new functions.https.HttpsError("unauthenticated", "Sign-in required.");
  }
  const action = String(data && data.action || "get");
  const payload = asObject(data && data.payload);
  const uid = context.auth.uid;
  const db = getDatabase();
  const stateRef = db.ref(`milestoneEarnings/${uid}`);

  if (action === "get") return milestoneView(uid, db);

  if (action === "recordWhatsappShare") {
    const reelId = String(payload.reelId || "");
    if (!reelId || /[.#$\[\]/]/.test(reelId)) {
      throw new functions.https.HttpsError("invalid-argument", "A valid reel is required.");
    }
    const event = db.ref(`milestoneShareEvents/${uid}`).push();
    await event.set({
      reelId, channel: "whatsapp", createdAt: ServerValue.TIMESTAMP,
    });
    return milestoneView(uid, db);
  }

  if (action === "requestPayout") {
    const view = await milestoneView(uid, db);
    if (!view.withdrawalsUnlocked) {
      throw new functions.https.HttpsError("failed-precondition",
        "Complete Level 3 to unlock withdrawals.");
    }
    if (view.availablePaise <= 0) {
      throw new functions.https.HttpsError("failed-precondition",
        "There is no available milestone reward balance.");
    }
    if (view.pendingPaise > 0) {
      throw new functions.https.HttpsError("already-exists",
        "A milestone withdrawal is already under review.");
    }
    const payoutRef = stateRef.child("payouts").push();
    const historyKey = `payout_${payoutRef.key}`;
    const amountPaise = view.availablePaise;
    await stateRef.update({
      balancePaise: 0,
      pendingPayoutPaise: amountPaise,
      lastPayoutRequestedAt: ServerValue.TIMESTAMP,
      [`history/${historyKey}`]: {
        type: "milestone_payout", amountPaise, status: "pending",
        requestedAt: ServerValue.TIMESTAMP,
      },
    });
    await payoutRef.set({
      amountPaise, status: "pending", requestedAt: ServerValue.TIMESTAMP, uid,
    });
    return { ok: true, payoutId: payoutRef.key, amountPaise };
  }

  throw new functions.https.HttpsError("invalid-argument",
    `Unknown milestone action: ${action}`);
});

function safeTargetPath(type, value) {
  const v = asObject(value);
  const id = v.reelId || v.tweetId || v.soundId || v.groupId || v.channelId
    || v.communityId || v.targetId;
  if (!id || typeof id !== "string" || /[.#$\[\]/]/.test(id)) return null;
  if (type === "reel") return `reels/${id}`;
  if (type === "x") return `x/tweets/${id}`;
  if (type === "sound") return `sounds/${id}`;
  if (type === "group") return `groups/${id}`;
  if (type === "channel") return `channels/${id}`;
  if (type === "community") return `communities/${id}`;
  if (type === "reel_comment" && v.commentId && !/[.#$\[\]/]/.test(v.commentId)) {
    return `reelComments/${id}/${v.commentId}`;
  }
  return null;
}

function normaliseReport(type, reportPath, reportId, targetKey, value) {
  const v = asObject(value);
  const targetId = v.reelId || v.tweetId || v.soundId || v.groupId || v.channelId
    || v.communityId || v.reportedUid || targetKey || "";
  return {
    type, reportId: reportId || "", reportPath, targetId,
    reportedUid: v.reportedUid || (type === "user" ? targetKey : ""),
    reporterUid: v.reporterUid || v.uid || "",
    reason: v.reason || "No reason supplied",
    status: v.status || "open",
    timestamp: v.timestamp || v.ts || 0,
    targetPath: safeTargetPath(type, v),
  };
}

function collectNested(rootName, snapshot, type, output, limit) {
  snapshot.forEach((targetSnap) => {
    targetSnap.forEach((reportSnap) => {
      if (output.length >= limit) return;
      const value = reportSnap.val();
      if (!value || typeof value !== "object") return;
      output.push(normaliseReport(
        type,
        `${rootName}/${targetSnap.key}/${reportSnap.key}`,
        reportSnap.key,
        targetSnap.key,
        value,
      ));
    });
  });
}

async function listAllReports(limit = 500) {
  const db = getDatabase();
  const roots = await Promise.all(ADMIN_READ_ROOTS.map((root) => db.ref(root).once("value")));
  const items = [];
  const nested = [
    ["reports", "user"], ["reelReports", "reel"], ["community_reports", "community"],
    ["channelReports", "channel"], ["groupReports", "group"], ["sound_reports", "sound"],
  ];
  nested.forEach(([root, type], index) => collectNested(root, roots[index], type, items, limit));
  const flatCommentIndex = ADMIN_READ_ROOTS.indexOf("reelCommentReports");
  const flatReplyIndex = ADMIN_READ_ROOTS.indexOf("reelReplyReports");
  [ ["reel_comment", roots[flatCommentIndex], "reelCommentReports"],
    ["reel_reply", roots[flatReplyIndex], "reelReplyReports"] ].forEach(([type, snap, root]) => {
    snap.forEach((child) => {
      if (items.length >= limit) return;
      items.push(normaliseReport(type, `${root}/${child.key}`, child.key, "", child.val()));
    });
  });
  const xSnap = await db.ref("x/reports").once("value");
  collectNested("x/reports", xSnap, "x", items, limit);
  return items.filter((item) => item.status === "open" || item.status === "pending")
    .sort((a, b) => Number(b.timestamp || 0) - Number(a.timestamp || 0));
}

async function authProfile(uid) {
  let authUser = {};
  try {
    const u = await getAuth().getUser(uid);
    authUser = { email: u.email || "", phoneNumber: u.phoneNumber || "",
      disabled: !!u.disabled, createdAt: u.metadata && u.metadata.creationTime || "" };
  } catch (e) {
    authUser = { authError: e.code || "not-found" };
  }
  const profile = (await getDatabase().ref(`users/${uid}`).once("value")).val();
  return { uid, ...asObject(profile), ...authUser };
}

/**
 * Resolve a moderator's free-text search into an actual Firebase Auth uid.
 * The admin search box accepts a UID, email, phone number, CallX ID, or
 * display name — a moderator almost never has the raw UID on hand, so
 * treating the input as "UID or nothing" (the old behaviour) meant every
 * realistic search came back "not found" even for real accounts.
 * Tries, in order, the cheapest/most-exact match first and stops at the
 * first hit. Returns null only if none of the strategies find anyone.
 */
async function resolveSearchToUid(rawQuery) {
  const query = String(rawQuery || "").trim();
  if (!query) return null;
  const db = getDatabase();

  // 1) Treat it as a Firebase Auth uid / RTDB key directly.
  try {
    const u = await getAuth().getUser(query);
    return u.uid;
  } catch (e) { /* not a uid, keep trying */ }

  // 2) Email address.
  if (query.includes("@")) {
    try {
      const u = await getAuth().getUserByEmail(query);
      return u.uid;
    } catch (e) { /* no match */ }
  }

  // 3) Phone number (accept with or without leading '+').
  if (/^\+?[0-9]{6,15}$/.test(query)) {
    const phone = query.startsWith("+") ? query : `+${query}`;
    try {
      const u = await getAuth().getUserByPhoneNumber(phone);
      return u.uid;
    } catch (e) { /* no match */ }
  }

  // 4) @username — direct O(1) lookup on the usernames/{username} reservation
  //    node (same node ProfileSetupActivity writes to). Accepts a leading
  //    '@' since that's how moderators will naturally type a handle.
  const asUsername = (query.startsWith("@") ? query.slice(1) : query).toLowerCase();
  if (/^[a-z0-9_]{3,30}$/.test(asUsername)) {
    const byUsername = await db.ref("usernames").child(asUsername).once("value");
    if (byUsername.exists()) return byUsername.val();
  }

  // 5) CallX ID — exact match, same field the in-app SearchActivity uses.
  const byCallxId = await db.ref("users").orderByChild("callxId")
    .equalTo(query).limitToFirst(1).once("value");
  if (byCallxId.exists()) {
    let found = null;
    byCallxId.forEach((c) => { found = c.key; });
    if (found) return found;
  }

  // 5) Display name — case-insensitive prefix match via nameLower, same
  //    approach as the in-app search.
  const lower = query.toLowerCase();
  const rangeEnd = lower.slice(0, -1)
    + String.fromCharCode(lower.charCodeAt(lower.length - 1) + 1);
  const byName = await db.ref("users").orderByChild("nameLower")
    .startAt(lower).endAt(rangeEnd + "\uf8ff").limitToFirst(1).once("value");
  if (byName.exists()) {
    let found = null;
    byName.forEach((c) => { found = c.key; });
    if (found) return found;
  }

  return null;
}

exports.adminAction = functions.https.onCall(async (data, context) => {
  const action = data && data.action;
  const payload = asObject(data && data.payload);
  const policy = await requireAdmin(context, action);
  const db = getDatabase();

  if (action === "dashboard") {
    const [users, calls, reports, verification, talent, config] = await Promise.all([
      db.ref("users").once("value"), db.ref("activeCalls").once("value"),
      listAllReports(500), db.ref("verification_requests").orderByChild("status").equalTo("pending").once("value"),
      db.ref("starTalentApplications").orderByChild("status").equalTo("pending").once("value"),
      db.ref("appConfig").once("value"),
    ]);
    const now = Date.now();
    let dau = 0; let mau = 0; let online = 0;
    users.forEach((child) => {
      const lastSeen = Number(asObject(child.val()).lastSeen || 0);
      if (lastSeen >= now - 24 * 60 * 60 * 1000) dau++;
      if (lastSeen >= now - 30 * 24 * 60 * 60 * 1000) mau++;
      if (asObject(child.val()).online === true) online++;
    });
    const storage = (await db.ref("storageMetrics").once("value")).val() || {};
    return { role: policy.role, metrics: {
      users: users.numChildren(), dau, mau, online, activeCalls: calls.numChildren(),
      storageBytes: Number(storage.totalBytes || 0),
      pendingReports: reports.length, pendingVerification: verification.numChildren(),
      pendingTalent: talent.numChildren(),
      config: config.val() || {},
    } };
  }

  if (action === "getVerificationCatalog") {
    const [catalog, configSnap] = await Promise.all([
      verificationCatalog(db),
      db.ref("appConfig/verificationBadgeCatalog").once("value"),
    ]);
    return { tiers: VERIFICATION_TIERS, periods: VERIFICATION_PERIODS,
      plans: catalog, config: configSnap.val() || {} };
  }

  if (action === "updateVerificationCatalog") {
    const incoming = asObject(payload.prices);
    const validKeys = new Set(DEFAULT_VERIFICATION_BADGE_PLANS.map((plan) => plan.key));
    const prices = {};
    for (const [key, value] of Object.entries(incoming)) {
      if (!validKeys.has(key)) continue;
      const price = Math.round(Number(value));
      if (!Number.isFinite(price) || price < 1 || price > 100000) {
        throw new functions.https.HttpsError("invalid-argument",
          `Invalid price for ${key}. Use a whole rupee amount between 1 and 100000.`);
      }
      prices[key] = price;
    }
    if (Object.keys(prices).length !== DEFAULT_VERIFICATION_BADGE_PLANS.length) {
      throw new functions.https.HttpsError("invalid-argument",
        "All 12 tier-period prices are required.");
    }
    await db.ref("appConfig/verificationBadgeCatalog").set({
      prices, updatedBy: context.auth.uid, updatedAt: ServerValue.TIMESTAMP,
    });
    await audit(context.auth.uid, "update_verification_catalog", "app_config",
      "verificationBadgeCatalog", { prices });
    return { ok: true, plans: await verificationCatalog(db) };
  }

  if (action === "lookupUser") {
    if (!payload.uid || typeof payload.uid !== "string") {
      throw new functions.https.HttpsError("invalid-argument", "uid is required.");
    }
    const resolvedUid = await resolveSearchToUid(payload.uid);
    if (!resolvedUid) {
      return { user: null };
    }
    return { user: await authProfile(resolvedUid) };
  }

  if (action === "getUsernameHistory") {
    const uid = payload.uid;
    if (!uid || typeof uid !== "string") {
      throw new functions.https.HttpsError("invalid-argument", "uid is required.");
    }
    const snap = await db.ref(`reelUsernameHistory/${uid}`).once("value");
    const items = [];
    snap.forEach((child) => {
      const value = asObject(child.val());
      items.push({ username: value.username || child.key, changedAt: value.changedAt || null });
    });
    items.sort((a, b) => Number(b.changedAt || 0) - Number(a.changedAt || 0));
    return { items };
  }

  if (action === "setUserStatus") {
    const uid = payload.uid;
    const status = payload.status;
    if (!uid || !["active", "suspended", "banned"].includes(status)) {
      throw new functions.https.HttpsError("invalid-argument", "Invalid user status.");
    }
    await db.ref(`users/${uid}`).update({
      accountStatus: status, moderationUpdatedAt: ServerValue.TIMESTAMP,
      moderationUpdatedBy: context.auth.uid,
    });
    try { await getAuth().updateUser(uid, { disabled: status !== "active" }); } catch (e) {
      console.warn("Auth disable update failed", uid, e.message || e);
    }
    await audit(context.auth.uid, "set_user_status", "user", uid, { status });
    return { ok: true, status };
  }

  if (action === "forceLogout") {
    if (!payload.uid) throw new functions.https.HttpsError("invalid-argument", "uid is required.");
    await getAuth().revokeRefreshTokens(payload.uid);
    await db.ref(`admin_session_events/${payload.uid}`).push({
      type: "force_logout", by: context.auth.uid, createdAt: ServerValue.TIMESTAMP,
    });
    await audit(context.auth.uid, "force_logout", "user", payload.uid, {});
    return { ok: true };
  }

  if (action === "deleteUser") {
    if (!payload.uid || payload.uid === context.auth.uid) {
      throw new functions.https.HttpsError("invalid-argument", "A different uid is required.");
    }
    // Read the handle before removing the profile so it can be released —
    // otherwise usernames/{username} stays pointed at a uid that no longer
    // exists and nobody can ever claim that handle again (client-side
    // self-delete in AccountMenuActivity already does this; admin-initiated
    // delete did not).
    const profileSnap = await db.ref(`users/${payload.uid}`).once("value");
    const username = asObject(profileSnap.val()).username;
    await db.ref(`users/${payload.uid}`).remove();
    if (username) {
      const reservation = await db.ref(`usernames/${username}`).once("value");
      if (reservation.val() === payload.uid) {
        await db.ref(`usernames/${username}`).remove();
      }
    }
    try { await getAuth().deleteUser(payload.uid); } catch (e) {
      if (e.code !== "auth/user-not-found") throw e;
    }
    await audit(context.auth.uid, "delete_user", "user", payload.uid, {});
    return { ok: true };
  }

  if (action === "listBlockedUsers") {
    const [blocks, permanent] = await Promise.all([
      db.ref("blocked").once("value"), db.ref("permaBlocked").once("value"),
    ]);
    const items = [];
    [["blocks", blocks], ["permaBlocked", permanent]].forEach(([source, snap]) => {
      snap.forEach((owner) => owner.forEach((blocked) => {
        if (blocked.val()) items.push({ ownerUid: owner.key, blockedUid: blocked.key, source });
      }));
    });
    return { items: items.slice(0, 2000) };
  }

  if (action === "listReports") {
    const items = await listAllReports(500);
    return { count: items.length, items };
  }

  if (action === "moderateReport") {
    const type = String(payload.type || "");
    const targetId = String(payload.targetId || "");
    const reportId = String(payload.reportId || "");
    const reportRoots = {
      user: "reports", reel: "reelReports", community: "community_reports",
      channel: "channelReports", group: "groupReports", sound: "sound_reports",
    };
    let reportPath = payload.reportPath;
    if (["reel_comment", "reel_reply"].includes(type)) {
      reportPath = `${type === "reel_comment" ? "reelCommentReports" : "reelReplyReports"}/${reportId}`;
    } else if (type === "x") {
      reportPath = `x/reports/${targetId}/${reportId}`;
    } else if (reportRoots[type]) {
      reportPath = `${reportRoots[type]}/${targetId}/${reportId}`;
    }
    if (!reportPath || /(^|\/)\.\.?($|\/)/.test(reportPath)) {
      throw new functions.https.HttpsError("invalid-argument", "Invalid report reference.");
    }
    const updates = { status: payload.removeTarget ? "removed" : (payload.status || "resolved"),
      reviewedBy: context.auth.uid, reviewedAt: ServerValue.TIMESTAMP };
    await db.ref(reportPath).update(updates);
    if (payload.removeTarget) {
      const targetPath = safeTargetPath(type, { targetId, reelId: type === "reel" ? targetId : undefined,
        tweetId: type === "x" ? targetId : undefined, communityId: type === "community" ? targetId : undefined,
        groupId: type === "group" ? targetId : undefined, channelId: type === "channel" ? targetId : undefined,
        soundId: type === "sound" ? targetId : undefined });
      if (targetPath) await db.ref(targetPath).remove();
    }
    await audit(context.auth.uid, "moderate_report", type, targetId,
      { reportId, removeTarget: !!payload.removeTarget });
    return { ok: true };
  }

  if (action === "directTakedown") {
    const type = String(payload.type || "").toLowerCase();
    const contentId = String(payload.contentId || "");
    const containerId = String(payload.containerId || "");
    const valid = (value) => value && value.length <= 180 && !/[.#$\[\]/]/.test(value);
    if (!valid(contentId) || (containerId && !valid(containerId))) {
      throw new functions.https.HttpsError("invalid-argument", "Invalid content identifier.");
    }
    let targetPath = null;
    if (type === "reel") targetPath = `reels/${contentId}`;
    if (type === "x") targetPath = `x/tweets/${contentId}`;
    if (type === "group") targetPath = `groups/${contentId}`;
    if (type === "channel") targetPath = `channels/${contentId}`;
    if (type === "community") targetPath = `communities/${contentId}`;
    if (type === "message" || type === "media") {
      if (!containerId) throw new functions.https.HttpsError("invalid-argument", "Container ID is required.");
      targetPath = `messages/${containerId}/${contentId}`;
    }
    if (!targetPath) throw new functions.https.HttpsError("invalid-argument", "Unsupported takedown type.");
    await db.ref(targetPath).remove();
    await audit(context.auth.uid, "direct_takedown", type, contentId,
      { targetPath, containerId });
    return { ok: true, targetPath };
  }

  if (action === "listOrganizations") {
    const roots = await Promise.all(["groups", "channels", "communities"].map((root) => db.ref(root).once("value")));
    const items = [];
    ["groups", "channels", "communities"].forEach((root, index) => roots[index].forEach((child) => {
      const value = asObject(child.val());
      const members = asObject(value.members);
      items.push({ type: root.slice(0, -1), id: child.key, name: value.name || value.title || "",
        ownerUid: value.ownerUid || value.ownerId || value.createdBy || "",
        status: value.status || "active", memberCount: Object.keys(members).length });
    }));
    return { items: items.slice(0, 1000) };
  }

  if (action === "organizationAction") {
    const root = { group: "groups", channel: "channels", community: "communities" }[payload.type];
    if (!root || !payload.id) throw new functions.https.HttpsError("invalid-argument", "Invalid organisation.");
    const ref = db.ref(`${root}/${payload.id}`);
    if (payload.operation === "delete") await ref.remove();
    else {
      const current = (await ref.child("status").once("value")).val();
      const next = current === "suspended" ? "active" : "suspended";
      await ref.update({ status: next, suspensionUpdatedAt: ServerValue.TIMESTAMP,
        suspensionUpdatedBy: context.auth.uid });
    }
    await audit(context.auth.uid, payload.operation, payload.type, payload.id, {});
    return { ok: true };
  }

  if (action === "listPayments") {
    const paths = ["payment_transactions", "payments/transactions", "paymentEvents",
      "paymentFailures", "payment_failures", "paymentDisputes", "payment_admin_cases",
      "fraudFlags", "kyc_reviews"];
    const snaps = await Promise.all(paths.map((path) => db.ref(path).once("value")));
    const items = [];
    snaps.forEach((snap, index) => snap.forEach((child) => {
      const value = asObject(child.val());
      items.push({ id: child.key, recordType: paths[index], ...value });
    }));
    return { items: items.sort((a, b) => Number(b.createdAt || 0) - Number(a.createdAt || 0)).slice(0, 500) };
  }

  if (action === "paymentReview") {
    if (!payload.transactionId || !payload.operation) {
      throw new functions.https.HttpsError("invalid-argument", "Transaction and operation are required.");
    }
    const caseRef = db.ref(`payment_admin_cases/${payload.transactionId}`).push();
    await caseRef.set({ transactionId: payload.transactionId, operation: payload.operation,
      status: payload.operation === "refund" ? "refund_requested" : "open",
      createdBy: context.auth.uid, createdAt: ServerValue.TIMESTAMP });
    const statusByOperation = { dispute: "DISPUTED", fraud: "FRAUD_REVIEW", kyc_approved: "KYC_APPROVED", refund: "REFUND_REQUESTED" };
    if (statusByOperation[payload.operation]) {
      await db.ref(`payment_transactions/${payload.transactionId}`).update({
        adminStatus: statusByOperation[payload.operation], adminUpdatedAt: ServerValue.TIMESTAMP,
      });
    }
    await audit(context.auth.uid, `payment_${payload.operation}`, "payment", payload.transactionId, {});
    return { ok: true };
  }

  if (action === "reviewVerificationRequest") {
    const uid = String(payload.uid || "");
    const decision = String(payload.decision || "");
    if (!uid || !["approve", "reject"].includes(decision)) {
      throw new functions.https.HttpsError("invalid-argument",
        "Verification request and decision are required.");
    }
    const requestRef = db.ref(`verification_requests/${uid}`);
    const requestSnap = await requestRef.once("value");
    if (!requestSnap.exists()) {
      throw new functions.https.HttpsError("not-found", "Verification request not found.");
    }
    const request = asObject(requestSnap.val());
    if (["approved", "rejected"].includes(String(request.status || ""))) {
      throw new functions.https.HttpsError("failed-precondition", "Request is already closed.");
    }
    const nextStatus = decision === "approve" ? "approved" : "rejected";
    const requestUpdate = {
      status: nextStatus, reviewedBy: context.auth.uid,
      reviewedAt: ServerValue.TIMESTAMP,
    };
    const updates = { [`verification_requests/${uid}/status`]: nextStatus,
      [`verification_requests/${uid}/reviewedBy`]: context.auth.uid,
      [`verification_requests/${uid}/reviewedAt`]: ServerValue.TIMESTAMP,
      [`verificationBadgeRequests/${uid}/status`]: nextStatus,
      [`verificationBadgeRequests/${uid}/reviewedBy`]: context.auth.uid,
      [`verificationBadgeRequests/${uid}/reviewedAt`]: ServerValue.TIMESTAMP };
    if (decision === "approve") {
      updates[`users/${uid}/isVerified`] = true;
      updates[`users/${uid}/verificationPlan`] = request.planKey || "manual";
      updates[`users/${uid}/badgeTier`] = legacyTier(request.tierKey)
        || legacyTier(request.badgeTier) || null;
      updates[`users/${uid}/badgePeriod`] = request.periodKey || request.badgePeriod || null;
      updates[`users/${uid}/verificationApprovedAt`] = ServerValue.TIMESTAMP;
    }
    updates[`verificationBadgeRequests/${uid}/tierKey`] = request.tierKey || "";
    updates[`verificationBadgeRequests/${uid}/periodKey`] = request.periodKey || "";
    await db.ref().update(updates);
    await audit(context.auth.uid, `verification_${decision}`, "verification", uid,
      { planKey: request.planKey || "manual", ...requestUpdate });
    return { ok: true, status: nextStatus };
  }

  if (action === "listStarTalentApplications") {
    const snap = await db.ref("starTalentApplications")
      .orderByChild("status").equalTo("pending").once("value");
    const items = [];
    snap.forEach((child) => items.push({ id: child.key, ...asObject(child.val()) }));
    items.sort((a, b) => Number(b.submittedAt || 0) - Number(a.submittedAt || 0));
    return { tiers: STAR_TALENT_TIERS, items: items.slice(0, 500) };
  }

  if (action === "listVerificationRequests") {
    const snap = await db.ref("verification_requests")
      .orderByChild("status").equalTo("pending").once("value");
    const items = [];
    snap.forEach((child) => items.push({ id: child.key, ...asObject(child.val()) }));
    items.sort((a, b) => Number(b.submittedAt || 0) - Number(a.submittedAt || 0));
    return { plans: await verificationCatalog(db), items: items.slice(0, 500) };
  }

  if (action === "backfillVerificationBadges") {
    const snap = await db.ref("users").once("value");
    const updates = {};
    let usersUpdated = 0;
    snap.forEach((child) => {
      const user = asObject(child.val());
      const tier = legacyTier(user.badgeTier || user.talentPlan || user.talentTier || user.talent);
      if (tier && !user.badgeTier) {
        usersUpdated += 1;
        updates[`users/${child.key}/badgeTier`] = tier;
        if (!user.badgePeriod) updates[`users/${child.key}/badgePeriod`] = "monthly";
      }
    });
    if (Object.keys(updates).length) await db.ref().update(updates);
    await audit(context.auth.uid, "backfill_verification_badges", "users", "legacy",
      { count: usersUpdated });
    return { ok: true, updatedFields: Object.keys(updates).length };
  }

  if (action === "reviewStarTalentApplication") {
    const uid = String(payload.uid || "");
    const decision = String(payload.decision || "");
    if (!uid || !["approve", "reject"].includes(decision)) {
      throw new functions.https.HttpsError("invalid-argument",
        "Talent application and decision are required.");
    }
    const applicationRef = db.ref(`starTalentApplications/${uid}`);
    const applicationSnap = await applicationRef.once("value");
    if (!applicationSnap.exists()) {
      throw new functions.https.HttpsError("not-found", "Talent application not found.");
    }
    const application = asObject(applicationSnap.val());
    if (["approved", "rejected"].includes(String(application.status || ""))) {
      throw new functions.https.HttpsError("failed-precondition", "Application is already closed.");
    }
    const nextStatus = decision === "approve" ? "approved" : "rejected";
    const updates = {
      [`starTalentApplications/${uid}/status`]: nextStatus,
      [`starTalentApplications/${uid}/reviewedBy`]: context.auth.uid,
      [`starTalentApplications/${uid}/reviewedAt`]: ServerValue.TIMESTAMP,
    };
    if (decision === "approve") {
      updates[`users/${uid}/talentPlan`] = application.tierKey || "star";
      updates[`users/${uid}/talentTier`] = application.tierKey || "star";
      updates[`users/${uid}/talentStatus`] = "active";
      updates[`users/${uid}/talentApprovedAt`] = ServerValue.TIMESTAMP;
      updates[`users/${uid}/isVerified`] = true;
      updates[`users/${uid}/badgeTier`] = legacyTier(application.tierKey) || "star";
      updates[`users/${uid}/badgePeriod`] = application.periodKey || "monthly";
      updates[`users/${uid}/verificationPlan`] = "legacy_star_talent";
      updates[`users/${uid}/verificationApprovedAt`] = ServerValue.TIMESTAMP;
    }
    await db.ref().update(updates);
    await audit(context.auth.uid, `star_talent_${decision}`, "star_talent", uid,
      { tierKey: application.tierKey || "star" });
    return { ok: true, status: nextStatus };
  }

  if (action === "listCreatorPayouts") {
    const snap = await db.ref("creatorMonetization").once("value");
    const creators = [];
    const payouts = [];
    snap.forEach((creatorSnap) => {
      const state = asObject(creatorSnap.val());
      creators.push({
        uid: creatorSnap.key,
        enrolled: state.enrolled === true,
        balanceCents: Number(state.balanceCents || 0),
        pendingPayoutCents: Number(state.pendingPayoutCents || 0),
        lifetimeCents: Number(state.lifetimeCents || 0),
      });
      creatorSnap.child("payouts").forEach((payoutSnap) => {
        const payout = asObject(payoutSnap.val());
        payouts.push({
          ...payout,
          id: payoutSnap.key,
          uid: creatorSnap.key,
          amountCents: Number(payout.amountCents || 0),
        });
      });
    });
    payouts.sort((a, b) => Number(b.requestedAt || 0) - Number(a.requestedAt || 0));
    return { creators, payouts: payouts.slice(0, 500) };
  }

  if (action === "reviewCreatorPayout") {
    const uid = String(payload.uid || "");
    const payoutId = String(payload.payoutId || "");
    const operation = String(payload.operation || "");
    if (!uid || !payoutId || !["approve", "reject", "paid"].includes(operation)) {
      throw new functions.https.HttpsError("invalid-argument", "Creator payout details are required.");
    }
    const payoutRef = db.ref(`creatorMonetization/${uid}/payouts/${payoutId}`);
    const payoutSnap = await payoutRef.once("value");
    const payout = asObject(payoutSnap.val());
    if (!payoutSnap.exists()) {
      throw new functions.https.HttpsError("not-found", "Payout request not found.");
    }
    const currentStatus = String(payout.status || "pending");
    if (["paid", "rejected"].includes(currentStatus)) {
      throw new functions.https.HttpsError("failed-precondition", "Payout is already closed.");
    }
    const amountCents = Number(payout.amountCents || 0);
    const nextStatus = operation === "approve" ? "approved"
      : operation === "paid" ? "paid" : "rejected";
    const updates = { status: nextStatus, reviewedBy: context.auth.uid,
      reviewedAt: ServerValue.TIMESTAMP };
    if (operation === "reject") {
      updates.rejectionReason = String(payload.reason || "Rejected by admin").slice(0, 240);
      updates.restoreBalanceCents = amountCents;
      await db.ref(`creatorMonetization/${uid}`).update({
        balanceCents: amountCents,
        pendingPayoutCents: 0,
      });
    } else if (operation === "paid") {
      await db.ref(`creatorMonetization/${uid}`).update({ pendingPayoutCents: 0 });
    }
    await payoutRef.update(updates);
    await audit(context.auth.uid, `creator_payout_${operation}`, "creator_payout",
      `${uid}/${payoutId}`, { amountCents });
    return { ok: true, status: nextStatus };
  }

  if (action === "listMilestoneEarnings") {
    const [config, usersSnap, eventsSnap] = await Promise.all([
      milestoneConfig(db),
      db.ref("milestoneEarnings").once("value"),
      db.ref("milestoneShareEvents").once("value"),
    ]);
    const users = [];
    const payouts = [];
    usersSnap.forEach((userSnap) => {
      const state = asObject(userSnap.val());
      users.push({
        uid: userSnap.key,
        balancePaise: Number(state.balancePaise || 0),
        pendingPayoutPaise: Number(state.pendingPayoutPaise || 0),
        lifetimePaise: Number(state.lifetimePaise || 0),
        highestCompletedLevel: Number(state.highestCompletedLevel || 0),
      });
      userSnap.child("payouts").forEach((payoutSnap) => {
        payouts.push({
          ...asObject(payoutSnap.val()),
          id: payoutSnap.key, uid: userSnap.key,
          amountPaise: Number(asObject(payoutSnap.val()).amountPaise || 0),
        });
      });
    });
    const shareCounts = {};
    eventsSnap.forEach((userSnap) => { shareCounts[userSnap.key] = userSnap.numChildren(); });
    users.forEach((row) => { row.whatsappShares = shareCounts[row.uid] || 0; });
    payouts.sort((a, b) => Number(b.requestedAt || 0) - Number(a.requestedAt || 0));
    return { config, users, payouts: payouts.slice(0, 500) };
  }

  if (action === "updateMilestoneConfig") {
    const next = normalizeMilestoneConfig(payload.config);
    if (!Array.isArray(payload.config && payload.config.levels)
        || payload.config.levels.length < 1) {
      throw new functions.https.HttpsError("invalid-argument", "At least one level is required.");
    }
    await db.ref("appConfig/milestoneEarnings").set({
      ...next, updatedBy: context.auth.uid, updatedAt: ServerValue.TIMESTAMP,
    });
    await audit(context.auth.uid, "update_milestone_config", "app_config",
      "milestoneEarnings", next);
    return { ok: true, config: next };
  }

  if (action === "reviewMilestonePayout") {
    const uid = String(payload.uid || "");
    const payoutId = String(payload.payoutId || "");
    const operation = String(payload.operation || "");
    if (!uid || !payoutId || !["approve", "reject", "paid"].includes(operation)) {
      throw new functions.https.HttpsError("invalid-argument",
        "Milestone payout details are required.");
    }
    const payoutRef = db.ref(`milestoneEarnings/${uid}/payouts/${payoutId}`);
    const payoutSnap = await payoutRef.once("value");
    const payout = asObject(payoutSnap.val());
    if (!payoutSnap.exists()) {
      throw new functions.https.HttpsError("not-found", "Milestone payout not found.");
    }
    const currentStatus = String(payout.status || "pending");
    if (["paid", "rejected"].includes(currentStatus)) {
      throw new functions.https.HttpsError("failed-precondition", "Payout is already closed.");
    }
    const amountPaise = Number(payout.amountPaise || 0);
    const nextStatus = operation === "approve" ? "approved"
      : operation === "paid" ? "paid" : "rejected";
    const updates = {
      status: nextStatus, reviewedBy: context.auth.uid,
      reviewedAt: ServerValue.TIMESTAMP,
    };
    if (operation === "reject") {
      updates.rejectionReason = String(payload.reason || "Rejected by admin").slice(0, 240);
      await db.ref(`milestoneEarnings/${uid}`).update({
        balancePaise: amountPaise, pendingPayoutPaise: 0,
        [`history/payout_${payoutId}/status`]: "rejected",
      });
    } else if (operation === "paid") {
      await db.ref(`milestoneEarnings/${uid}`).update({
        pendingPayoutPaise: 0, [`history/payout_${payoutId}/status`]: "paid",
      });
    } else {
      await db.ref(`milestoneEarnings/${uid}`)
        .update({ [`history/payout_${payoutId}/status`]: "approved" });
    }
    await payoutRef.update(updates);
    await audit(context.auth.uid, `milestone_payout_${operation}`, "milestone_payout",
      `${uid}/${payoutId}`, { amountPaise });
    return { ok: true, status: nextStatus };
  }

  if (action === "sendAnnouncement") {
    const title = String(payload.title || "").trim();
    const body = String(payload.body || "").trim();
    if (!title || !body || title.length > 120 || body.length > 1000) {
      throw new functions.https.HttpsError("invalid-argument", "Announcement title/body is invalid.");
    }
    const announcement = db.ref("admin_announcements").push();
    await announcement.set({ title, body, audience: payload.audience || "all",
      createdBy: context.auth.uid, createdAt: ServerValue.TIMESTAMP });
    const users = await db.ref("users").once("value");
    const tokens = [];
    users.forEach((child) => {
      const v = asObject(child.val());
      if (v.fcmToken && typeof v.fcmToken === "string") tokens.push(v.fcmToken);
    });
    let sent = 0;
    for (let i = 0; i < tokens.length; i += 500) {
      const response = await getMessaging().sendEachForMulticast({
        tokens: tokens.slice(i, i + 500), notification: { title, body },
        data: { type: "admin_announcement", announcementId: announcement.key },
      });
      sent += response.successCount;
    }
    await audit(context.auth.uid, "send_announcement", "announcement", announcement.key, { sent });
    return { ok: true, sent, announcementId: announcement.key };
  }

  if (action === "listConfig") {
    return { config: (await db.ref("appConfig").once("value")).val() || {} };
  }

  if (action === "setConfig") {
    const key = String(payload.key || "");
    if (!key || key.startsWith("/") || key.includes("..") || /[#$\[\]]/.test(key)) {
      throw new functions.https.HttpsError("invalid-argument", "Invalid config key.");
    }
    await db.ref(`appConfig/${key}`).set(payload.value);
    await audit(context.auth.uid, "set_config", "config", key, { value: payload.value });
    return { ok: true };
  }

  if (action === "listAdmins") {
    const snap = await db.ref("admins").once("value");
    const items = [];
    snap.forEach((child) => {
      const node = child.val();
      items.push({ uid: child.key, role: adminRole(node) || "unknown",
        permissions: node && typeof node === "object" ? node.permissions || {} : {} });
    });
    return { items };
  }

  if (action === "setAdmin") {
    const role = String(payload.role || "moderator");
    if (!payload.uid || !["super_admin", "moderator", "support", "finance"].includes(role)) {
      throw new functions.https.HttpsError("invalid-argument", "Invalid admin uid or role.");
    }
    const permissions = {};
    if (payload.permissions && typeof payload.permissions === "object") {
      Object.keys(payload.permissions).slice(0, 30).forEach((key) => {
        if (/^[a-zA-Z0-9_]+$/.test(key) && payload.permissions[key] === true) permissions[key] = true;
      });
    }
    await db.ref(`admins/${payload.uid}`).set({ role, updatedBy: context.auth.uid,
      updatedAt: ServerValue.TIMESTAMP, permissions });
    await audit(context.auth.uid, "set_admin", "admin", payload.uid, { role });
    return { ok: true };
  }

  if (action === "removeAdmin") {
    if (!payload.uid || payload.uid === context.auth.uid) {
      throw new functions.https.HttpsError("invalid-argument", "Cannot remove the current admin.");
    }
    await db.ref(`admins/${payload.uid}`).remove();
    await audit(context.auth.uid, "remove_admin", "admin", payload.uid, {});
    return { ok: true };
  }

  if (action === "listAudit") {
    const [auditSnap, crashSnap] = await Promise.all([
      db.ref("admin_audit").limitToLast(300).once("value"),
      db.ref("crash_reports").limitToLast(300).once("value"),
    ]);
    const items = [];
    auditSnap.forEach((child) => items.push({ ...asObject(child.val()), id: child.key }));
    crashSnap.forEach((child) => items.push({ ...asObject(child.val()), id: child.key,
      action: "crash_report", targetType: "crash" }));
    const communities = await db.ref("communities").once("value");
    communities.forEach((community) => {
      community.child("moderation_log").forEach((entry) => {
        items.push({ ...asObject(entry.val()), id: entry.key,
          action: "community_moderation", targetType: "community",
          targetId: community.key });
      });
    });
    return { items: items.sort((a, b) => Number(b.createdAt || b.timestamp || 0)
      - Number(a.createdAt || a.timestamp || 0)).slice(0, 500) };
  }

  throw new functions.https.HttpsError("invalid-argument", `Unknown admin action: ${action}`);
});


// ═══════════════════════════════════════════════════════════════════════════
// SOUND USAGE COUNTERS — reel_count / user_count / is_trending
// ═══════════════════════════════════════════════════════════════════════════
// WHY THIS LIVES ON THE SERVER
//   Before, reel_count was bumped by a CLIENT transaction at upload time
//   (ReelUploadActivity#registerOrLinkSound, CollabRepostAcceptActivity) and
//   never decremented when a reel was deleted, so it drifted; and there was
//   no count of DISTINCT users at all, so the Sound Detail "Used by A, B and
//   N others" row could only estimate N from the reels loaded so far.
//   Any signed-in user could also write any sound's counters
//   (sounds/$key had ".write": "auth != null").
//
//   Now a single trigger — the only writer — keeps:
//     sounds/{soundId}/reel_count   — number of reels using the sound
//     sounds/{soundId}/user_count   — number of DISTINCT owners of those reels
//     sounds/{soundId}/is_trending  — set true once reel_count >= 5
//   plus a server-only index:
//     soundUsers/{soundId}/{ownerUid}/{reelId} = true
//   (kept OUT of sounds/{id} on purpose: SoundDetailCache / search / trending
//    read whole sounds/{id} nodes, and this index can be large.)
//
// HOW IT STAYS CORRECT
//   * Triggered on the .../reels/{reelId}/ownerUid LEAF, not the reel entry:
//     the entry is also created/updated by view-count transactions
//     (viewsCount) from viewers, which must NOT count as "a user used this
//     sound". ownerUid is written once, by the uploader, when the entry is
//     really created — and removing the entry (or the whole sound) removes
//     the leaf, which fires this trigger with the old owner.
//   * IDEMPOTENT against at-least-once delivery: membership is decided by a
//     transaction on soundUsers/{soundId}/{ownerUid} (a set of reelIds). A
//     duplicate event finds the reelId already there / already gone,
//     `committed` is false, and the counters are NOT touched again.
//   * user_count only moves on a 0<->1 transition of that owner's set, so
//     two reels by the same person count as ONE user.
//   * Decrements never create a missing counter (a deleted sound doesn't
//     get resurrected as an empty {reel_count:0,user_count:0} stub) and
//     never go below 0.
//   If a run dies between the membership write and the counter write the
//   counters can be off by one; POST /admin/backfill-sound-counts on the
//   Render server rebuilds soundUsers + user_count (+ reel_count with
//   fix_reel_count=1) from the real reels entries, so it doubles as repair.

const SOUND_TRENDING_REEL_THRESHOLD = 5;

/** ownerUid comes from DATA (not the path) and is used as a DB key — validate it. */
function isSafeSoundOwnerKey(v) {
  return typeof v === "string" && v.length > 0 && v.length <= 128 && !/[.#$\[\]\/]/.test(v);
}

/**
 * Atomic +/-1 on sounds/{soundId}/{field}. Returns the resulting value.
 * Increment creates the field; decrement never creates it and floors at 0.
 */
async function bumpSoundCounter(db, soundId, field, delta) {
  const res = await db.ref(`sounds/${soundId}/${field}`).transaction((cur) => {
    if (delta > 0) return (typeof cur === "number" ? cur : 0) + delta;
    if (typeof cur !== "number") return cur; // null stays null (no stub), odd types untouched
    return Math.max(0, cur + delta);
  });
  return res.snapshot.val();
}

async function addSoundReelUsage(db, soundId, reelId, ownerUid) {
  let wasEmpty = false;
  const res = await db.ref(`soundUsers/${soundId}/${ownerUid}`).transaction((cur) => {
    wasEmpty = false;                                // the LAST run is the one that commits
    const map = cur && typeof cur === "object" ? { ...cur } : {};
    if (map[reelId]) return undefined;               // already counted → abort (duplicate delivery)
    wasEmpty = Object.keys(map).length === 0;
    map[reelId] = true;
    return map;
  });
  if (!res.committed) return;                        // duplicate — counters already reflect this reel

  const reelCount = await bumpSoundCounter(db, soundId, "reel_count", +1);
  if (wasEmpty) await bumpSoundCounter(db, soundId, "user_count", +1);
  if (typeof reelCount === "number" && reelCount >= SOUND_TRENDING_REEL_THRESHOLD) {
    await db.ref(`sounds/${soundId}/is_trending`).set(true);
  }
}

async function removeSoundReelUsage(db, soundId, reelId, ownerUid) {
  let removed = false;
  let nowEmpty = false;
  const res = await db.ref(`soundUsers/${soundId}/${ownerUid}`).transaction((cur) => {
    removed = false; nowEmpty = false;               // the LAST run is the one that commits
    // First local run has no cached data (null): returning null (not
    // undefined) forces the server hash check so we retry with real data
    // instead of aborting on a guess.
    if (cur === null || cur === undefined) return null;
    if (typeof cur !== "object" || !cur[reelId]) return undefined; // not in the set → abort
    const map = { ...cur };
    delete map[reelId];
    removed = true;
    nowEmpty = Object.keys(map).length === 0;
    return nowEmpty ? null : map;                    // null deletes the owner node
  });
  if (!res.committed || !removed) return;            // duplicate / nothing to remove — counters untouched

  await bumpSoundCounter(db, soundId, "reel_count", -1);
  if (nowEmpty) await bumpSoundCounter(db, soundId, "user_count", -1);
}

exports.onSoundReelOwnerWrite = functions.database
  .ref("/sounds/{soundId}/reels/{reelId}/ownerUid")
  .onWrite(async (change, context) => {
    const { soundId, reelId } = context.params;
    const before = change.before.exists() ? change.before.val() : null;
    const after = change.after.exists() ? change.after.val() : null;
    const oldOwner = isSafeSoundOwnerKey(before) ? before : null;
    const newOwner = isSafeSoundOwnerKey(after) ? after : null;
    if (oldOwner === newOwner) return null;

    const db = getDatabase();
    try {
      if (oldOwner) await removeSoundReelUsage(db, soundId, reelId, oldOwner);
      if (newOwner) await addSoundReelUsage(db, soundId, reelId, newOwner);
    } catch (err) {
      console.error(`[soundCounters] ${soundId}/${reelId} failed:`, err);
      throw err;
    }
    return null;
  });
