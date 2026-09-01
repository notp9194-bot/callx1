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


 * without ever being approved (the web client regenerates its QR locally
 * long before this runs — this just keeps the database tidy).
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
  const superOnly = ["deleteUser", "organizationAction", "setConfig", "setAdmin", "removeAdmin"];
  if (superOnly.includes(operation) && role !== "super_admin" && !permissions[operation]) {
    throw new functions.https.HttpsError("permission-denied", "This role cannot perform that action.");
  }
  if (operation === "paymentReview" && role !== "super_admin" && role !== "finance"
      && !permissions.paymentReview) {
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

exports.adminAction = functions.https.onCall(async (data, context) => {
  const action = data && data.action;
  const payload = asObject(data && data.payload);
  const policy = await requireAdmin(context, action);
  const db = getDatabase();

  if (action === "dashboard") {
    const [users, calls, reports, verification, config] = await Promise.all([
      db.ref("users").once("value"), db.ref("activeCalls").once("value"),
      listAllReports(500), db.ref("verification_requests").orderByChild("status").equalTo("pending").once("value"),
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
      config: config.val() || {},
    } };
  }

  if (action === "lookupUser") {
    if (!payload.uid || typeof payload.uid !== "string") {
      throw new functions.https.HttpsError("invalid-argument", "uid is required.");
    }
    return { user: await authProfile(payload.uid) };
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
    await db.ref(`users/${payload.uid}`).remove();
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
