const express = require("express");
const cors    = require("cors");
const morgan  = require("morgan");
const crypto  = require("crypto");
const admin   = require("firebase-admin");

const app = express();
app.use(cors());
app.use(express.json({ limit: "2mb" }));
app.use(morgan("tiny"));

// ---- Firebase Admin init ----
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

const CLOUD_NAME = process.env.CLOUDINARY_CLOUD_NAME || "dvqqgqdls";
const CLOUD_KEY  = process.env.CLOUDINARY_API_KEY;
const CLOUD_SEC  = process.env.CLOUDINARY_API_SECRET;
const cloudReady = !!(CLOUD_KEY && CLOUD_SEC);
if (!cloudReady) console.warn("[WARN] CLOUDINARY_API_KEY/SECRET missing");

app.get("/", (req, res) => res.json({
  ok: true,
  service: "callx-server v2",
  firebaseReady,
  cloudReady,
  cloudName: CLOUD_NAME
}));
app.get("/healthz", (req, res) =>
  res.json({ ok: true, firebaseReady, cloudReady }));

// ---- Cloudinary signed upload ----
app.post("/cloudinary/sign", (req, res) => {
  if (!cloudReady) {
    return res.status(503).json({
      error: "Cloudinary not configured",
      hint: "Render dashboard pe CLOUDINARY_API_KEY / CLOUDINARY_API_SECRET set karo"
    });
  }
  const folder       = (req.body && req.body.folder) || "callx";
  const resourceType = (req.body && req.body.resource_type) || "auto";
  const timestamp    = Math.floor(Date.now() / 1000).toString();
  // Cloudinary signature: alphabetical params (folder + timestamp) + secret
  const toSign = `folder=${folder}&timestamp=${timestamp}`;
  const signature = crypto.createHash("sha1")
    .update(toSign + CLOUD_SEC).digest("hex");
  res.json({
    signature, timestamp,
    api_key: CLOUD_KEY,
    cloud_name: CLOUD_NAME,
    folder, resource_type: resourceType
  });
});

// ---- Notify single user ----
app.post("/notify", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });
  const {
    toUid, fromUid, fromName, type, text,
    chatId, messageId, mediaUrl, force
  } = req.body || {};
  if (!toUid) return res.status(400).json({ error: "toUid required" });
  try {
    // (Feature 4) Server-side perma-block guard — saves bandwidth and
    // ensures NOTHING ever reaches a permanently-blocked receiver.
    // permaBlocked/{receiverUid}/{senderUid} === true means receiver
    // (toUid) blocked sender (fromUid). force=true bypass karta hai
    // (perma-block return + special-request flows ke liye).
    if (!force && fromUid) {
      try {
        const pbSnap = await admin.database()
          .ref("permaBlocked/" + toUid + "/" + fromUid).once("value");
        if (pbSnap.val() === true) {
          return res.json({ ok: true, dropped: "permaBlocked" });
        }
      } catch (e) { /* best-effort */ }
    }
    const snap = await admin.database().ref("users/" + toUid).once("value");
    const user = snap.val() || {};
    if (!user.fcmToken)
      return res.status(404).json({ error: "no token" });
    // Lookup sender profile so the notification can show
    // mobile, photo, online/offline state etc.
    let fromMobile = "", fromPhoto = "", fromLastSeen = "0";
    if (fromUid) {
      try {
        const fSnap = await admin.database()
          .ref("users/" + fromUid).once("value");
        const f = fSnap.val() || {};
        fromMobile   = String(f.mobile || f.callxId || "");
        fromPhoto    = String(f.photoUrl || "");
        fromLastSeen = String(f.lastSeen || 0);
      } catch (e) { /* best-effort */ }
    }
    const message = {
      token: user.fcmToken,
        data: {
          type:         String(type || "message"),
          fromUid:      String(fromUid || ""),
          fromName:     String(fromName || ""),
          fromMobile:   fromMobile,
          fromPhoto:    fromPhoto,
          fromLastSeen: fromLastSeen,
          chatId:       String(chatId || ""),
          messageId:    String(messageId || ""),
          mediaUrl:     String(mediaUrl || ""),
          text:         String(text || ""),
          // For call types: expose callId as dedicated field.
          // Receiver reads "callId" first, falls back to "text".
          ...((type === "call" || type === "video_call") && text
              ? { callId: String(text) } : {})
        },
        android: {
          priority: "high",
          // Call FCM messages expire in 30 s — stale rings are useless
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

// ---- Notify group (production-grade fanout to all members except sender) ----
// Per-receiver handling:
//  * permaBlocked sender drop kar deta hai (group me bhi)
//  * mutedBy/{uid} = true => "muted" flag set hota hai (client low-priority channel)
//  * unread/{uid} server-side increment hota hai (group meta)
//  * sender ka latest photo + lastSeen lookup hota hai (rich notification ke liye)
//  * stale FCM tokens (UNREGISTERED / NOT_FOUND) DB se clean ho jaate hain
app.post("/notify/group", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });
  const {
    groupId, fromUid, fromName, fromPhoto,
    messageId, type, text, mediaUrl
  } = req.body || {};
  if (!groupId) return res.status(400).json({ error: "groupId required" });
  try {
    const gSnap = await admin.database()
      .ref("groups/" + groupId).once("value");
    const g = gSnap.val();
    if (!g) return res.status(404).json({ error: "group not found" });

    const groupName = String(g.name || "Group");
    const groupIcon = String(g.iconUrl || "");
    const memberUids = Object.keys(g.members || {})
      .filter(uid => uid !== fromUid);
    const mutedBy = g.mutedBy || {};

    // Sender ka latest profile (photo / lastSeen) — agar client ne nahi bheja
    // to RTDB se nikal lo. Notification rich banane ke liye chahiye.
    let senderPhoto   = String(fromPhoto || "");
    let senderMobile  = "";
    let senderLastSeen = "0";
    if (fromUid) {
      try {
        const fSnap = await admin.database()
          .ref("users/" + fromUid).once("value");
        const f = fSnap.val() || {};
        if (!senderPhoto) senderPhoto = String(f.photoUrl || "");
        senderMobile   = String(f.mobile || f.callxId || "");
        senderLastSeen = String(f.lastSeen || 0);
      } catch (e) { /* best-effort */ }
    }

    // Per-member fanout
    const updates = {};       // group/{groupId}/unread/{uid} bumps
    const staleTokens = [];   // (uid, token) jinhe DB se hatana hai
    let sent = 0, dropped = 0;

    await Promise.all(memberUids.map(async (uid) => {
      try {
        // Permanent block: agar receiver ne sender ko perma-block kiya hua hai
        // to is member ko notify mat karo.
        if (fromUid) {
          const pbSnap = await admin.database()
            .ref("permaBlocked/" + uid + "/" + fromUid).once("value");
          if (pbSnap.val() === true) { dropped++; return; }
        }
        const us = await admin.database()
          .ref("users/" + uid).once("value");
        const u = us.val() || {};
        const tk = u.fcmToken;
        if (!tk) { dropped++; return; }

        const isMuted = mutedBy[uid] === true;

        // Unread bump (server-side, atomic)
        updates["groups/" + groupId + "/unread/" + uid] =
          admin.database.ServerValue.increment(1);

        await admin.messaging().send({
          token: tk,
          data: {
            type:           String(type || "group_message"),
            groupId:        String(groupId),
            groupName:      groupName,
            groupIcon:      groupIcon,
            fromUid:        String(fromUid || ""),
            fromName:       String(fromName || ""),
            fromPhoto:      senderPhoto,
            fromMobile:     senderMobile,
            fromLastSeen:   senderLastSeen,
            messageId:      String(messageId || ""),
            mediaUrl:       String(mediaUrl  || ""),
            text:           String(text      || ""),
            muted:          isMuted ? "1" : "0"
          },
          android: {
            priority: isMuted ? "normal" : "high",
            collapseKey: "grp_" + groupId,
            ttl: 24 * 60 * 60 * 1000
          }
        });
        sent++;
      } catch (e) {
        // Stale FCM token cleanup
        const code = e && (e.code || e.errorInfo && e.errorInfo.code);
        if (code === "messaging/registration-token-not-registered" ||
            code === "messaging/invalid-registration-token") {
          staleTokens.push(uid);
        } else {
          console.warn("group send fail (" + uid + "):",
            e && e.message ? e.message : e);
        }
        dropped++;
      }
    }));

    // Best-effort writes — agar fail bhi ho to push response affect na ho
    try {
      if (Object.keys(updates).length) {
        await admin.database().ref().update(updates);
      }
    } catch (e) { /* ignore */ }
    try {
      for (const uid of staleTokens) {
        await admin.database()
          .ref("users/" + uid + "/fcmToken").remove();
      }
    } catch (e) { /* ignore */ }

    res.json({ ok: true, sent, dropped, members: memberUids.length });
  } catch (e) {
    console.error("group notify err:", e.message);
    res.status(500).json({ error: e.message });
  }
});

// ---- Reset unread counter for a group (called by client when chat opened) ----
app.post("/group/markRead", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });
  const { groupId, uid } = req.body || {};
  if (!groupId || !uid)
    return res.status(400).json({ error: "groupId & uid required" });
  try {
    await admin.database()
      .ref("groups/" + groupId + "/unread/" + uid).set(0);
    res.json({ ok: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// ---- Notify status (fanout to all contacts of poster) ----
app.post("/notify/status", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });
  const { fromUid, fromName } = req.body || {};
  if (!fromUid) return res.status(400).json({ error: "fromUid required" });
  try {
    const cSnap = await admin.database()
      .ref("contacts/" + fromUid).once("value");
    const contacts = cSnap.val() || {};
    const uids = Object.keys(contacts);
    const tokens = [];
    for (const uid of uids) {
      const us = await admin.database().ref("users/" + uid).once("value");
      const t = us.val() && us.val().fcmToken;
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
            text:     "Naya status post kiya"
          },
          android: { priority: "high" }
        });
        sent++;
      } catch (e) {
        console.warn("status send fail:", e.message);
      }
    }
    res.json({ ok: true, sent });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => console.log("callx-server v2 on :" + PORT));
