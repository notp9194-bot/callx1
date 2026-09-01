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
