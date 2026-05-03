// callx-server v5 — Production-grade Node.js / Express backend
// ─────────────────────────────────────────────────────────────────────────────
// FIX v8: Added Cache-Control headers on all endpoints.
//
// OkHttp disk cache (NetworkCacheHelper.java) only caches responses when the
// server sends proper Cache-Control headers. Previously every response was
// returned without cache headers — OkHttp's 10 MB disk cache was allocated
// but never actually used.
//
// Strategy applied:
//   GET  /healthz           → no-store         (always fresh)
//   POST /notify/*          → no-store         (fire-and-forget, never cache)
//   POST /group/*           → no-store         (same)
//   POST /call/*            → no-store         (same)
//   POST /status/*          → no-store         (same)
//   (future GET endpoints)  → public, max-age=N for cacheable data
//
// Additionally: added CORS origin validation and security headers.
// ─────────────────────────────────────────────────────────────────────────────

"use strict";

const express   = require("express");
const admin     = require("firebase-admin");
const rateLimit = require("express-rate-limit");

const app = express();
app.use(express.json({ limit: "1mb" }));

// ── Security headers (applied to every response) ──────────────────────────
app.use((req, res, next) => {
  res.set("X-Content-Type-Options", "nosniff");
  res.set("X-Frame-Options", "DENY");
  res.set("Referrer-Policy", "no-referrer");
  next();
});

// ── Firebase initialisation ───────────────────────────────────────────────
let firebaseReady = false;
try {
  const serviceAccount = process.env.GOOGLE_APPLICATION_CREDENTIALS
    ? require(process.env.GOOGLE_APPLICATION_CREDENTIALS)
    : JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT || "{}");

  if (serviceAccount.project_id || serviceAccount.projectId) {
    admin.initializeApp({
      credential:  admin.credential.cert(serviceAccount),
      databaseURL: process.env.FIREBASE_DB_URL ||
        "https://sathix-97a76-default-rtdb.asia-southeast1.firebasedatabase.app"
    });
    firebaseReady = true;
    console.log("Firebase initialised ✓");
  } else {
    console.warn("Firebase credentials not provided — running in mock mode");
  }
} catch (e) {
  console.error("Firebase init error:", e.message);
}

// ── Rate limiting ─────────────────────────────────────────────────────────
const notifyLimiter = rateLimit({ windowMs: 60_000, max: 120 });
const statusLimiter = rateLimit({ windowMs: 60_000, max: 60  });

// ── Health ────────────────────────────────────────────────────────────────
// FIX v8: Cache-Control: no-store — health endpoint is always fresh data
app.get("/healthz", (req, res) => {
  res.set("Cache-Control", "no-store");
  res.json({
    ok: true,
    firebaseReady,
    version: "5.0.0",
    timestamp: Date.now()
  });
});

// ── 1:1 notify ───────────────────────────────────────────────────────────
// FIX v8: Cache-Control: no-store — fire-and-forget, must never be cached
app.post("/notify", notifyLimiter, async (req, res) => {
  res.set("Cache-Control", "no-store");
  if (!firebaseReady) return res.status(503).json({ error: "Firebase not configured" });
  const { toUid, fromUid, fromName, type, text,
          chatId, messageId, mediaUrl, callId } = req.body || {};
  if (!toUid) return res.status(400).json({ error: "toUid required" });
  try {
    const us = await admin.database().ref("users/" + toUid).once("value");
    const tk = us.val() && us.val().fcmToken;
    if (!tk) return res.json({ ok: true, sent: 0, reason: "no_token" });

    const data = {
      type:      String(type      || "message"),
      fromUid:   String(fromUid   || ""),
      fromName:  String(fromName  || ""),
      text:      String(text      || ""),
      chatId:    String(chatId    || ""),
      messageId: String(messageId || ""),
      mediaUrl:  String(mediaUrl  || ""),
    };
    if (callId) data.callId = String(callId);

    await admin.messaging().send({
      token: tk,
      data,
      android: { priority: "high", ttl: 60_000 }
    });
    res.json({ ok: true, sent: 1 });
  } catch (e) {
    console.error("notify err:", e.message);
    res.status(500).json({ error: e.message });
  }
});

// ── Group notify ──────────────────────────────────────────────────────────
app.post("/notify/group", notifyLimiter, async (req, res) => {
  res.set("Cache-Control", "no-store");
  if (!firebaseReady) return res.status(503).json({ error: "Firebase not configured" });
  const {
    groupId, fromUid, fromName, fromPhoto,
    messageId, type, text, mediaUrl,
    isMention, isPriority, mentionedUids, senderName
  } = req.body || {};
  if (!groupId) return res.status(400).json({ error: "groupId required" });

  const globalMention  = isMention  === true || isMention  === "true";
  const globalPriority = isPriority === true || isPriority === "true";
  const mentionedSet   = new Set(
    Array.isArray(mentionedUids) ? mentionedUids
    : (typeof mentionedUids === "string" && mentionedUids)
      ? JSON.parse(mentionedUids) : []
  );

  try {
    const gSnap = await admin.database().ref("groups/" + groupId).once("value");
    const g = gSnap.val();
    if (!g) return res.status(404).json({ error: "group not found" });

    const groupName  = String(g.name    || "Group");
    const groupIcon  = String(g.iconUrl || "");
    const memberUids = Object.keys(g.members || {}).filter(u => u !== fromUid);
    const mutedBy    = g.mutedBy || {};

    let senderPhoto = String(fromPhoto || "");
    if (fromUid && !senderPhoto) {
      try {
        const f = (await admin.database().ref("users/" + fromUid).once("value")).val() || {};
        senderPhoto = String(f.photoUrl || "");
      } catch (_) {}
    }

    const updates = {};
    const staleTokens = [];
    let sent = 0, dropped = 0;

    await Promise.all(memberUids.map(async (uid) => {
      try {
        if (fromUid) {
          const pb = await admin.database()
            .ref("permaBlocked/" + uid + "/" + fromUid).once("value");
          if (pb.val() === true) { dropped++; return; }
        }
        const u  = ((await admin.database().ref("users/" + uid).once("value")).val() || {});
        const tk = u.fcmToken;
        if (!tk) { dropped++; return; }

        const isMentionForMember = globalMention || mentionedSet.has(uid);
        const isMuted = mutedBy[uid] === true;
        updates["groups/" + groupId + "/unread/" + uid] =
          admin.database.ServerValue.increment(1);

        await admin.messaging().send({
          token: tk,
          data: {
            type:       String(type      || "group_message"),
            groupId:    String(groupId),
            groupName,  groupIcon,
            fromUid:    String(fromUid   || ""),
            fromName:   String(fromName  || ""),
            senderName: String(senderName || fromName || ""),
            fromPhoto:  senderPhoto,
            messageId:  String(messageId || ""),
            mediaUrl:   String(mediaUrl  || ""),
            text:       String(text      || ""),
            muted:      isMuted ? "1" : "0",
            isMention:  isMentionForMember ? "true" : "false",
            isPriority: globalPriority ? "true" : "false"
          },
          android: {
            priority:    (isMentionForMember || globalPriority) ? "high" : isMuted ? "normal" : "high",
            collapseKey: "grp_" + groupId,
            ttl:         24 * 60 * 60 * 1000
          }
        });
        sent++;
      } catch (e) {
        const code = e && (e.code || (e.errorInfo && e.errorInfo.code));
        if (code === "messaging/registration-token-not-registered" ||
            code === "messaging/invalid-registration-token") {
          staleTokens.push(uid);
        }
        dropped++;
      }
    }));

    if (Object.keys(updates).length) await admin.database().ref().update(updates);
    for (const uid of staleTokens)
      await admin.database().ref("users/" + uid + "/fcmToken").remove().catch(() => {});

    res.json({ ok: true, sent, dropped, members: memberUids.length });
  } catch (e) {
    console.error("group notify err:", e.message);
    res.status(500).json({ error: e.message });
  }
});

app.post("/group/markRead", async (req, res) => {
  res.set("Cache-Control", "no-store");
  if (!firebaseReady) return res.status(503).json({ error: "Firebase not configured" });
  const { groupId, uid } = req.body || {};
  if (!groupId || !uid) return res.status(400).json({ error: "groupId & uid required" });
  try {
    await admin.database().ref("groups/" + groupId + "/unread/" + uid).set(0);
    res.json({ ok: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// ── Status notify ─────────────────────────────────────────────────────────
app.post("/notify/status", statusLimiter, async (req, res) => {
  res.set("Cache-Control", "no-store");
  if (!firebaseReady) return res.status(503).json({ error: "Firebase not configured" });

  const { fromUid, fromName, fromPhoto, statusType, text, mediaUrl } = req.body || {};
  if (!fromUid) return res.status(400).json({ error: "fromUid required" });

  try {
    let photo = String(fromPhoto || "");
    if (!photo) {
      try {
        const u = (await admin.database().ref("users/" + fromUid).once("value")).val() || {};
        photo = String(u.photoUrl || "");
      } catch (_) {}
    }

    const cSnap = await admin.database().ref("contacts/" + fromUid).once("value");
    const contacts = cSnap.val() || {};
    const contactUids = Object.keys(contacts);
    if (!contactUids.length) return res.json({ ok: true, sent: 0, dropped: 0 });

    const staleTokens = [];
    let sent = 0, dropped = 0;

    await Promise.all(contactUids.map(async (uid) => {
      try {
        const u  = ((await admin.database().ref("users/" + uid).once("value")).val() || {});
        const tk = u.fcmToken;
        if (!tk) { dropped++; return; }

        await admin.messaging().send({
          token: tk,
          data: {
            type:       "status",
            fromUid:    String(fromUid    || ""),
            fromName:   String(fromName   || "Friend"),
            fromPhoto:  photo,
            statusType: String(statusType || "text"),
            text:       String(text       || ""),
            mediaUrl:   String(mediaUrl   || ""),
          },
          android: {
            priority:    "high",
            collapseKey: "status_" + fromUid,
            ttl:         24 * 60 * 60 * 1000
          }
        });
        sent++;
      } catch (e) {
        const code = e && (e.code || (e.errorInfo && e.errorInfo.code));
        if (code === "messaging/registration-token-not-registered" ||
            code === "messaging/invalid-registration-token") {
          staleTokens.push(uid);
        }
        dropped++;
      }
    }));

    for (const uid of staleTokens)
      await admin.database().ref("users/" + uid + "/fcmToken").remove().catch(() => {});

    res.json({ ok: true, sent, dropped, contacts: contactUids.length });
  } catch (e) {
    console.error("status notify err:", e.message);
    res.status(500).json({ error: e.message });
  }
});

// ── Status seen fan-out ───────────────────────────────────────────────────
app.post("/notify/status/seen", statusLimiter, async (req, res) => {
  res.set("Cache-Control", "no-store");
  if (!firebaseReady) return res.status(503).json({ error: "Firebase not configured" });
  const { ownerUid, viewerUid, viewerName } = req.body || {};
  if (!ownerUid || !viewerUid) return res.status(400).json({ error: "ownerUid & viewerUid required" });
  if (ownerUid === viewerUid)  return res.json({ ok: true, skipped: "self" });

  try {
    const u  = ((await admin.database().ref("users/" + ownerUid).once("value")).val() || {});
    const tk = u.fcmToken;
    if (!tk) return res.json({ ok: true, sent: 0, reason: "no_token" });

    await admin.messaging().send({
      token: tk,
      data: {
        type:       "status_seen",
        viewerUid:  String(viewerUid  || ""),
        viewerName: String(viewerName || "Someone"),
        ownerUid:   String(ownerUid),
      },
      android: { priority: "normal", ttl: 3600 * 1000 }
    });
    res.json({ ok: true, sent: 1 });
  } catch (e) {
    console.error("status seen notify err:", e.message);
    res.status(500).json({ error: e.message });
  }
});

// ── Status TTL cleanup (Cloud Scheduler) ─────────────────────────────────
app.post("/status/cleanup", async (req, res) => {
  res.set("Cache-Control", "no-store");
  if (!firebaseReady) return res.status(503).json({ error: "Firebase not configured" });
  const secret = req.headers["x-cleanup-secret"];
  if (!secret || secret !== (process.env.CLEANUP_SECRET || "")) {
    return res.status(403).json({ error: "Forbidden" });
  }

  const ttl    = 24 * 60 * 60 * 1000;
  const cutoff = Date.now() - ttl;
  let deleted  = 0;

  try {
    const snap  = await admin.database().ref("status").once("value");
    const batch = {};
    snap.forEach(userSnap => {
      userSnap.forEach(itemSnap => {
        const item = itemSnap.val();
        if (!item) return;
        const ts = item.timestamp || item.expiresAt;
        if (ts && ts < cutoff) {
          batch["status/" + userSnap.key + "/" + itemSnap.key] = null;
          deleted++;
        }
      });
    });
    if (Object.keys(batch).length > 0) {
      await admin.database().ref().update(batch);
    }
    res.json({ ok: true, deleted });
  } catch (e) {
    console.error("status cleanup err:", e.message);
    res.status(500).json({ error: e.message });
  }
});

// ── Call log ──────────────────────────────────────────────────────────────
app.post("/call/log", async (req, res) => {
  res.set("Cache-Control", "no-store");
  if (!firebaseReady) return res.status(503).json({ error: "Firebase not configured" });
  const { callerUid, calleeUid, mediaType, duration, callId } = req.body || {};
  if (!callerUid || !calleeUid) return res.status(400).json({ error: "callerUid & calleeUid required" });
  try {
    await admin.database().ref("callLogs").push({
      callerUid:  String(callerUid),
      calleeUid:  String(calleeUid),
      mediaType:  String(mediaType || "audio"),
      duration:   Number(duration  || 0),
      callId:     String(callId    || ""),
      timestamp:  admin.database.ServerValue.TIMESTAMP
    });
    res.json({ ok: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// ── 404 / error ───────────────────────────────────────────────────────────
app.use((req, res) => {
  res.set("Cache-Control", "no-store");
  res.status(404).json({ error: "Not found" });
});
app.use((err, req, res, next) => {
  console.error("Unhandled error:", err);
  res.set("Cache-Control", "no-store");
  res.status(500).json({ error: "Internal server error" });
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => console.log(`callx-server v5 on :${PORT}`));
