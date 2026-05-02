"use strict";
const express   = require("express");
const cors      = require("cors");
const morgan    = require("morgan");
const crypto    = require("crypto");
const admin     = require("firebase-admin");
const rateLimit = require("express-rate-limit");
const helmet    = require("helmet");

const app = express();

// ── Security headers ──────────────────────────────────────────────────────
app.use(helmet({ contentSecurityPolicy: false }));

// ── CORS ──────────────────────────────────────────────────────────────────
app.use(cors({
  origin: "*",
  methods: ["GET", "POST", "OPTIONS"],
  allowedHeaders: ["Content-Type", "Authorization"]
}));
app.options("*", cors());

app.use(express.json({ limit: "2mb" }));
app.use(morgan("tiny"));

// ── Rate limiting ─────────────────────────────────────────────────────────
const generalLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 120,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: "Too many requests, please slow down" }
});
const notifyLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 60,
  message: { error: "Notification rate limit exceeded" }
});
const signLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 30,
  message: { error: "Cloudinary sign rate limit exceeded" }
});
const turnLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 40,
  message: { error: "TURN credential rate limit exceeded" }
});

app.use(generalLimiter);

// ── Firebase Admin init ───────────────────────────────────────────────────
let firebaseReady = false;
try {
  const sa = process.env.FIREBASE_SERVICE_ACCOUNT;
  if (sa) {
    const creds = JSON.parse(sa);
    admin.initializeApp({
      credential: admin.credential.cert(creds),
      databaseURL: process.env.DB_URL ||
        "https://sathix-97a76-default-rtdb.asia-southeast1.firebasedatabase.app"
    });
    firebaseReady = true;
    console.log("[OK] Firebase Admin initialized");
  } else {
    console.warn("[WARN] FIREBASE_SERVICE_ACCOUNT missing");
  }
} catch (e) {
  console.error("[ERR] Firebase init failed:", e.message);
}

// ── Cloudinary config ─────────────────────────────────────────────────────
const CLOUD_NAME = process.env.CLOUDINARY_CLOUD_NAME || "dvqqgqdls";
const CLOUD_KEY  = process.env.CLOUDINARY_API_KEY;
const CLOUD_SEC  = process.env.CLOUDINARY_API_SECRET;
const cloudReady = !!(CLOUD_KEY && CLOUD_SEC);
if (!cloudReady) console.warn("[WARN] CLOUDINARY_API_KEY/SECRET missing");

// ── TURN config ───────────────────────────────────────────────────────────
// Set TURN_SECRET env var on Render to enable HMAC-based TURN credentials.
// Compatible with coturn (static-auth-secret mode) and Metered TURN.
const TURN_SECRET    = process.env.TURN_SECRET || "";
const TURN_HOST      = process.env.TURN_HOST   || "turn.callx-server.onrender.com";
const TURN_PORT      = process.env.TURN_PORT   || "3478";
const turnConfigured = !!TURN_SECRET;
if (!turnConfigured) console.warn("[WARN] TURN_SECRET missing — clients fall back to STUN only");

// ── Health ────────────────────────────────────────────────────────────────
app.get("/", (req, res) => res.json({
  ok: true,
  service: "callx-server v3",
  firebaseReady,
  cloudReady,
  turnConfigured,
  cloudName: CLOUD_NAME
}));
app.get("/healthz", (req, res) =>
  res.json({ ok: true, firebaseReady, cloudReady, turnConfigured }));

// ── TURN credentials (RFC 5389 HMAC-based short-lived credentials) ────────
// Client: GET /turn/credentials
// Returns: { url, username, credential, ttl }
app.get("/turn/credentials", turnLimiter, (req, res) => {
  if (!turnConfigured) {
    return res.status(503).json({
      error: "TURN not configured",
      hint: "Set TURN_SECRET, TURN_HOST, TURN_PORT env vars on Render"
    });
  }
  try {
    const ttl      = 86400; // 24 hours
    const unixTime = Math.floor(Date.now() / 1000) + ttl;
    const username  = `${unixTime}:callx`;
    const credential = crypto.createHmac("sha1", TURN_SECRET)
      .update(username).digest("base64");
    res.json({
      url:        `turn:${TURN_HOST}:${TURN_PORT}`,
      username,
      credential,
      ttl,
      expires:    unixTime
    });
  } catch (e) {
    console.error("TURN cred error:", e.message);
    res.status(500).json({ error: "TURN credential generation failed" });
  }
});

// ── Cloudinary signed upload ──────────────────────────────────────────────
app.post("/cloudinary/sign", signLimiter, (req, res) => {
  if (!cloudReady) {
    return res.status(503).json({
      error: "Cloudinary not configured",
      hint: "Set CLOUDINARY_API_KEY / CLOUDINARY_API_SECRET on Render"
    });
  }
  const folder       = (req.body && req.body.folder)        || "callx";
  const resourceType = (req.body && req.body.resource_type) || "auto";
  const timestamp    = Math.floor(Date.now() / 1000).toString();
  const toSign       = `folder=${folder}&timestamp=${timestamp}`;
  const signature    = crypto.createHash("sha1")
    .update(toSign + CLOUD_SEC).digest("hex");
  res.json({
    signature, timestamp,
    api_key: CLOUD_KEY,
    cloud_name: CLOUD_NAME,
    folder, resource_type: resourceType
  });
});

// ── Notify single user ────────────────────────────────────────────────────
app.post("/notify", notifyLimiter, async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });

  const {
    toUid, fromUid, fromName, type, text,
    chatId, messageId, mediaUrl, force
  } = req.body || {};

  if (!toUid) return res.status(400).json({ error: "toUid required" });

  try {
    if (!force && fromUid) {
      try {
        const pbSnap = await admin.database()
          .ref("permaBlocked/" + toUid + "/" + fromUid).once("value");
        if (pbSnap.val() === true)
          return res.json({ ok: true, dropped: "permaBlocked" });
      } catch (e) { /* best-effort */ }
    }

    const snap = await admin.database().ref("users/" + toUid).once("value");
    const user = snap.val() || {};
    if (!user.fcmToken)
      return res.status(404).json({ error: "no token" });

    let fromMobile = "", fromPhoto = "", fromLastSeen = "0";
    if (fromUid) {
      try {
        const fSnap = await admin.database().ref("users/" + fromUid).once("value");
        const f = fSnap.val() || {};
        fromMobile   = String(f.mobile || f.callxId || "");
        fromPhoto    = String(f.photoUrl || "");
        fromLastSeen = String(f.lastSeen || 0);
      } catch (e) { /* best-effort */ }
    }

    const message = {
      token: user.fcmToken,
      data: {
        type:         String(type     || "message"),
        fromUid:      String(fromUid  || ""),
        fromName:     String(fromName || ""),
        fromMobile:   fromMobile,
        fromPhoto:    fromPhoto,
        fromLastSeen: fromLastSeen,
        chatId:       String(chatId   || ""),
        messageId:    String(messageId|| ""),
        mediaUrl:     String(mediaUrl || ""),
        text:         String(text     || ""),
        ...((type === "call" || type === "video_call") && text
            ? { callId: String(text) } : {})
      },
      android: {
        priority: "high",
        ...((type === "call" || type === "video_call") ? { ttl: 30000 } : {})
      }
    };

    const r = await admin.messaging().send(message);
    res.json({ ok: true, id: r });
  } catch (e) {
    console.error("notify err:", e.message);
    res.status(500).json({ error: e.message });
  }
});

// ── Notify group ──────────────────────────────────────────────────────────
app.post("/notify/group", notifyLimiter, async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });

  const {
    groupId, fromUid, fromName, fromPhoto,
    messageId, type, text, mediaUrl,
    isMention,      // "true" if message contains an @mention (auto-detected or explicit)
    isPriority,     // "true" for admin-flagged priority messages
    mentionedUids,  // JSON array of UIDs that were explicitly @mentioned (optional)
    senderName      // display name for mention notification title
  } = req.body || {};
  if (!groupId) return res.status(400).json({ error: "groupId required" });

  // Normalise mention flags
  const globalMention   = isMention   === true || isMention   === "true";
  const globalPriority  = isPriority  === true || isPriority  === "true";
  const mentionedUidSet = new Set(
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
    const memberUids = Object.keys(g.members || {}).filter(uid => uid !== fromUid);
    const mutedBy    = g.mutedBy || {};

    let senderPhoto = String(fromPhoto || "");
    let senderMobile = "", senderLastSeen = "0";
    if (fromUid) {
      try {
        const fSnap = await admin.database().ref("users/" + fromUid).once("value");
        const f = fSnap.val() || {};
        if (!senderPhoto) senderPhoto = String(f.photoUrl || "");
        senderMobile   = String(f.mobile || f.callxId || "");
        senderLastSeen = String(f.lastSeen || 0);
      } catch (e) { /* best-effort */ }
    }

    const updates = {};
    const staleTokens = [];
    let sent = 0, dropped = 0;

    await Promise.all(memberUids.map(async (uid) => {
      try {
        if (fromUid) {
          const pbSnap = await admin.database()
            .ref("permaBlocked/" + uid + "/" + fromUid).once("value");
          if (pbSnap.val() === true) { dropped++; return; }
        }
        const us  = await admin.database().ref("users/" + uid).once("value");
        const u   = us.val() || {};
        const tk  = u.fcmToken;
        if (!tk) { dropped++; return; }

        // Per-member mention flag: global OR this specific UID is in the mentioned set
        const isMentionForMember = globalMention || mentionedUidSet.has(uid);

        const isMuted = mutedBy[uid] === true;
        updates["groups/" + groupId + "/unread/" + uid] =
          admin.database.ServerValue.increment(1);

        // @mention overrides mute on the server side too (client enforces DND).
        // Mentioned users always get high priority even if muted.
        const fcmPriority = (isMentionForMember || globalPriority) ? "high"
                          : isMuted ? "normal" : "high";

        await admin.messaging().send({
          token: tk,
          data: {
            type:           String(type       || "group_message"),
            groupId:        String(groupId),
            groupName:      groupName,
            groupIcon:      groupIcon,
            fromUid:        String(fromUid    || ""),
            fromName:       String(fromName   || ""),
            senderName:     String(senderName || fromName || ""),
            fromPhoto:      senderPhoto,
            fromMobile:     senderMobile,
            fromLastSeen:   senderLastSeen,
            messageId:      String(messageId  || ""),
            mediaUrl:       String(mediaUrl   || ""),
            text:           String(text       || ""),
            muted:          isMuted ? "1" : "0",
            isMention:      isMentionForMember ? "true" : "false",
            isPriority:     globalPriority ? "true" : "false"
          },
          android: {
            priority:    fcmPriority,
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
        } else {
          console.warn("group send fail (" + uid + "):", e && e.message ? e.message : e);
        }
        dropped++;
      }
    }));

    try {
      if (Object.keys(updates).length) await admin.database().ref().update(updates);
    } catch (e) { /* ignore */ }
    try {
      for (const uid of staleTokens)
        await admin.database().ref("users/" + uid + "/fcmToken").remove();
    } catch (e) { /* ignore */ }

    res.json({ ok: true, sent, dropped, members: memberUids.length });
  } catch (e) {
    console.error("group notify err:", e.message);
    res.status(500).json({ error: e.message });
  }
});

// ── Reset group unread ────────────────────────────────────────────────────
app.post("/group/markRead", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });
  const { groupId, uid } = req.body || {};
  if (!groupId || !uid)
    return res.status(400).json({ error: "groupId & uid required" });
  try {
    await admin.database().ref("groups/" + groupId + "/unread/" + uid).set(0);
    res.json({ ok: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// ── Notify status ─────────────────────────────────────────────────────────
app.post("/notify/status", notifyLimiter, async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });
  const { fromUid, fromName } = req.body || {};
  if (!fromUid) return res.status(400).json({ error: "fromUid required" });
  try {
    const cSnap = await admin.database().ref("contacts/" + fromUid).once("value");
    const contacts = cSnap.val() || {};
    const uids = Object.keys(contacts);
    const tokens = [];
    for (const uid of uids) {
      const us = await admin.database().ref("users/" + uid).once("value");
      const t  = us.val() && us.val().fcmToken;
      if (t) tokens.push(t);
    }
    if (!tokens.length) return res.json({ ok: true, sent: 0 });
    let sent = 0;
    for (const tk of tokens) {
      try {
        await admin.messaging().send({
          token: tk,
          data: {
            type:     "status",
            fromUid:  String(fromUid),
            fromName: String(fromName || "Friend"),
            text:     "New status posted"
          },
          android: { priority: "high" }
        });
        sent++;
      } catch (e) { console.warn("status send fail:", e.message); }
    }
    res.json({ ok: true, sent });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// ── Server-side call log (optional — store call records for analytics) ────
app.post("/call/log", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });
  const { callerUid, calleeUid, mediaType, duration, callId } = req.body || {};
  if (!callerUid || !calleeUid)
    return res.status(400).json({ error: "callerUid & calleeUid required" });
  try {
    const entry = {
      callerUid:  String(callerUid),
      calleeUid:  String(calleeUid),
      mediaType:  String(mediaType || "audio"),
      duration:   Number(duration  || 0),
      callId:     String(callId    || ""),
      timestamp:  admin.database.ServerValue.TIMESTAMP
    };
    await admin.database().ref("callLogs").push(entry);
    res.json({ ok: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// ── 404 catch-all ─────────────────────────────────────────────────────────
app.use((req, res) => res.status(404).json({ error: "Not found" }));

// ── Global error handler ──────────────────────────────────────────────────
app.use((err, req, res, next) => {
  console.error("Unhandled error:", err);
  res.status(500).json({ error: "Internal server error" });
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => console.log(`callx-server v3 on :${PORT}`));
