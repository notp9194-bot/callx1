const express = require("express");
const cors    = require("cors");
const morgan  = require("morgan");
const crypto  = require("crypto");
const admin   = require("firebase-admin");

// ── FFmpeg binary path (Render / any server pe) ───────────────────────────────
try {
  const ffmpegInstaller = require("@ffmpeg-installer/ffmpeg");
  const ffmpegLib       = require("fluent-ffmpeg");
  ffmpegLib.setFfmpegPath(ffmpegInstaller.path);
  console.log("[OK] FFmpeg binary path set:", ffmpegInstaller.path);
} catch (e) {
  console.warn("[WARN] @ffmpeg-installer/ffmpeg not found:", e.message);
}

const app = express();

// ✅ YE LINE ADD KI (game chalane ke liye)
app.use(express.static(__dirname));

app.use(cors());
app.use(express.json({ limit: "2mb" }));
app.use(morgan("tiny"));

// ══════════════════════════════════════════════════════════════════════════════
// Firebase Admin init
// ══════════════════════════════════════════════════════════════════════════════
let firebaseReady = false;
try {
  const sa = process.env.FIREBASE_SERVICE_ACCOUNT;
  if (sa) {
    admin.initializeApp({
      credential: admin.credential.cert(JSON.parse(sa)),
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

// ══════════════════════════════════════════════════════════════════════════════
// Cloudinary config
// ══════════════════════════════════════════════════════════════════════════════
const CLOUD_NAME = process.env.CLOUDINARY_CLOUD_NAME || "dvqqgqdls";
const CLOUD_KEY  = process.env.CLOUDINARY_API_KEY;
const CLOUD_SEC  = process.env.CLOUDINARY_API_SECRET;
const cloudReady = !!(CLOUD_KEY && CLOUD_SEC);
if (!cloudReady) console.warn("[WARN] CLOUDINARY_API_KEY/SECRET missing");

// ══════════════════════════════════════════════════════════════════════════════
// Health / root
// ══════════════════════════════════════════════════════════════════════════════
app.get("/", (req, res) => res.json({
  ok: true,
  service: "callx-server v4",
  firebaseReady,
  cloudReady,
  cloudName: CLOUD_NAME
}));
app.get("/healthz", (req, res) =>
  res.json({ ok: true, firebaseReady, cloudReady }));
app.get("/ping", (req, res) =>
  res.json({ ok: true, time: Date.now() }));

// ══════════════════════════════════════════════════════════════════════════════
// Cloudinary signed upload
// ══════════════════════════════════════════════════════════════════════════════
app.post("/cloudinary/sign", (req, res) => {
  if (!cloudReady) {
    return res.status(503).json({
      error: "Cloudinary not configured",
      hint: "Render dashboard pe CLOUDINARY_API_KEY / CLOUDINARY_API_SECRET set karo"
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
    api_key:       CLOUD_KEY,
    cloud_name:    CLOUD_NAME,
    folder,
    resource_type: resourceType
  });
});

// ══════════════════════════════════════════════════════════════════════════════
// Cloudinary VIDEO signed upload — eager transform support
// POST /cloudinary/sign/video
// Body: { folder, eager }
// Response: { signature, timestamp, api_key, cloud_name, folder, eager }
// ══════════════════════════════════════════════════════════════════════════════
app.post("/cloudinary/sign/video", (req, res) => {
  if (!cloudReady) {
    return res.status(503).json({
      error: "Cloudinary not configured",
      hint: "Render dashboard pe CLOUDINARY_API_KEY / CLOUDINARY_API_SECRET set karo"
    });
  }
  const folder    = (req.body && req.body.folder) || "callx/videos/file";
  const eager     = (req.body && req.body.eager)  || "";
  const timestamp = Math.floor(Date.now() / 1000).toString();

  // Signature string — eager include karo agar present hai
  let toSign = `folder=${folder}&timestamp=${timestamp}`;
  if (eager) toSign = `eager=${eager}&` + toSign;

  const signature = crypto.createHash("sha1")
    .update(toSign + CLOUD_SEC).digest("hex");

  res.json({
    signature, timestamp,
    api_key:    CLOUD_KEY,
    cloud_name: CLOUD_NAME,
    folder,
    eager
  });
});

// ══════════════════════════════════════════════════════════════════════════════
// VIDEO COMPRESS — Android v25 server-side compression endpoint
// POST /compress/video  (multipart/form-data)
//
// Mobile ne raw video bheja → server FFmpeg se compress karta hai →
// Cloudinary pe upload karta hai → URL return karta hai
//
// Fields:
//   file            — raw MP4 video
//   api_key         — Cloudinary API key (sign se aata hai)
//   timestamp       — Cloudinary timestamp
//   signature       — Cloudinary signature
//   cloud_name      — Cloudinary cloud name
//   quality_preset  — "360p" / "480p" / "720p" / "1080p" / "original"
//   original_width  — original video width (int)
//   original_height — original video height (int)
//   duration_ms     — video duration in ms (int)
//
// Response: { video_url, thumb_url, public_id, compressed_bytes }
// ══════════════════════════════════════════════════════════════════════════════
(function setupVideoCompress() {
  let multer, cloudinary, ffmpeg, fs, os, path, execFile;

  try {
    multer     = require("multer");
    cloudinary = require("cloudinary").v2;
    ffmpeg     = require("fluent-ffmpeg");
    fs         = require("fs");
    os         = require("os");
    path       = require("path");
    execFile   = require("child_process").execFile;
  } catch (e) {
    console.warn("[WARN] /compress/video deps missing:", e.message,
      "→ npm install multer cloudinary fluent-ffmpeg");
    // Stub endpoint — returns 503 with helpful message
    app.post("/compress/video", (req, res) => {
      res.status(503).json({
        error: "Server video compress ready nahi hai",
        hint: "npm install multer cloudinary fluent-ffmpeg"
      });
    });
    return;
  }

  const upload = multer({
    dest: os.tmpdir(),
    limits: { fileSize: 500 * 1024 * 1024 } // 500 MB max
  });

  // Quality preset → FFmpeg params mapping
  const QUALITY_MAP = {
    "360p":    { scale: "scale=-2:360",   vb: "500k"  },
    "480p":    { scale: "scale=-2:480",   vb: "1000k" },
    "720p":    { scale: "scale=-2:720",   vb: "2000k" },
    "1080p":   { scale: "scale=-2:1080",  vb: "4000k" },
    "original": null  // skip FFmpeg, direct upload
  };

  app.post("/compress/video", upload.single("file"), async (req, res) => {
    if (!cloudReady) {
      return res.status(503).json({ error: "Cloudinary not configured" });
    }
    if (!req.file) {
      return res.status(400).json({ error: "file field required" });
    }

    const inputPath  = req.file.path;
    const outputPath = inputPath + "_compressed.mp4";

    const {
      api_key, timestamp, signature, cloud_name,
      quality_preset = "480p",
      original_width, original_height, duration_ms
    } = req.body;

    // Cloudinary credentials — request fields prefer karo, env fallback
    const cldKey    = api_key    || CLOUD_KEY;
    const cldCloud  = cloud_name || CLOUD_NAME;
    const cldSig    = signature;
    const cldTs     = timestamp;

    cloudinary.config({
      cloud_name:  cldCloud,
      api_key:     cldKey,
      api_secret:  CLOUD_SEC
    });

    const preset = QUALITY_MAP[quality_preset] || QUALITY_MAP["480p"];

    try {
      // ── Step 1: FFmpeg compress (skip if "original") ──────────────────────
      let uploadFile = inputPath;

      if (preset) {
        await new Promise((resolve, reject) => {
          let cmd = ffmpeg(inputPath)
            .videoCodec("libx264")
            .audioCodec("aac")
            .outputOptions([
              "-vf",      preset.scale,
              "-b:v",     preset.vb,
              "-preset",  "fast",
              "-movflags", "+faststart",
              "-y"
            ])
            .on("end", resolve)
            .on("error", reject)
            .save(outputPath);
        });
        uploadFile = outputPath;
      }

      const compressedBytes = fs.statSync(uploadFile).size;

      // ── Step 2: Upload compressed video to Cloudinary ─────────────────────
      const videoUploadOpts = {
        resource_type: "video",
        folder:        "callx/videos/file",
        ...(cldSig && cldTs ? {
          signature:  cldSig,
          timestamp:  cldTs,
          api_key:    cldKey
        } : {})
      };

      const videoResult = await cloudinary.uploader.upload(uploadFile, videoUploadOpts);

      // ── Step 3: Generate thumbnail via Cloudinary eager ───────────────────
      let thumbUrl = "";
      try {
        const thumbResult = await cloudinary.uploader.upload(uploadFile, {
          resource_type: "video",
          folder:        "callx/videos/thumb",
          eager: [{ width: 400, height: 400, crop: "fill", format: "jpg" }]
        });
        thumbUrl = thumbResult.eager?.[0]?.secure_url || "";
      } catch (thumbErr) {
        console.warn("[compress/video] thumb upload failed:", thumbErr.message);
        // Thumb fail hone pe video still return karo
      }

      res.json({
        video_url:        videoResult.secure_url,
        thumb_url:        thumbUrl,
        public_id:        videoResult.public_id,
        compressed_bytes: compressedBytes
      });

    } catch (err) {
      console.error("[compress/video] failed:", err.message);
      res.status(500).json({ error: err.message });
    } finally {
      // Temp files cleanup
      try { if (fs.existsSync(inputPath))  fs.unlinkSync(inputPath);  } catch (_) {}
      try { if (fs.existsSync(outputPath)) fs.unlinkSync(outputPath); } catch (_) {}
    }
  });

  console.log("[OK] /compress/video endpoint ready");
})();

// ══════════════════════════════════════════════════════════════════════════════
// IMAGE COMPRESS — Server-side image compression (Mobile CPU zero load)
// POST /compress/image  (multipart/form-data)
//
// Mobile ne raw image bheji → server sharp se resize + WebP compress kare →
// Cloudinary pe upload kare → { image_url, thumb_url } return kare
//
// Fields:
//   file   — raw image (JPEG/PNG/HEIC etc)
//   folder — optional Cloudinary folder (default: callx/image)
//
// Response: { image_url, thumb_url, compressed_bytes, thumb_bytes }
// ══════════════════════════════════════════════════════════════════════════════
(function setupImageCompress() {
  let multer, sharp, cloudinary, fs, os, path;

  try {
    multer    = require("multer");
    sharp     = require("sharp");
    cloudinary = require("cloudinary").v2;
    fs        = require("fs");
    os        = require("os");
    path      = require("path");
  } catch (e) {
    console.warn("[WARN] /compress/image deps missing:", e.message,
      "→ npm install sharp multer cloudinary");
    app.post("/compress/image", (req, res) => {
      res.status(503).json({
        error: "Server image compress ready nahi hai",
        hint: "npm install sharp"
      });
    });
    return;
  }

  const upload = multer({
    dest: os.tmpdir(),
    limits: { fileSize: 50 * 1024 * 1024 } // 50 MB max
  });

  // Full image settings
  const FULL_MAX_PX  = 1280;
  const FULL_QUALITY = 80;
  const FULL_MAX_KB  = 800;

  // Thumbnail settings
  const THUMB_SIZE    = 200;
  const THUMB_QUALITY = 65;

  app.post("/compress/image", upload.single("file"), async (req, res) => {
    if (!cloudReady) {
      return res.status(503).json({ error: "Cloudinary not configured" });
    }
    if (!req.file) {
      return res.status(400).json({ error: "file field required" });
    }

    const inputPath  = req.file.path;
    const outFull    = inputPath + "_full.webp";
    const outThumb   = inputPath + "_thumb.webp";
    const folder     = (req.body && req.body.folder) || "callx/image";

    cloudinary.config({
      cloud_name: CLOUD_NAME,
      api_key:    CLOUD_KEY,
      api_secret: CLOUD_SEC
    });

    try {
      // ── Step 1: Sharp — full image resize + WebP ──────────────────────
      await sharp(inputPath)
        .rotate()                          // EXIF rotation auto-fix
        .resize(FULL_MAX_PX, FULL_MAX_PX, {
          fit: "inside",
          withoutEnlargement: true
        })
        .webp({ quality: FULL_QUALITY })
        .toFile(outFull);

      // ── Step 2: Sharp — thumbnail 200×200 center crop ────────────────
      await sharp(inputPath)
        .rotate()
        .resize(THUMB_SIZE, THUMB_SIZE, {
          fit: "cover",
          position: "centre"
        })
        .webp({ quality: THUMB_QUALITY })
        .toFile(outThumb);

      const compressedBytes = fs.statSync(outFull).size;
      const thumbBytes      = fs.statSync(outThumb).size;

      // ── Step 3: Cloudinary pe full image upload ───────────────────────
      const fullResult = await cloudinary.uploader.upload(outFull, {
        resource_type: "image",
        folder:        folder
      });

      // ── Step 4: Cloudinary pe thumb upload ───────────────────────────
      const thumbResult = await cloudinary.uploader.upload(outThumb, {
        resource_type: "image",
        folder:        "callx/thumb"
      });

      res.json({
        image_url:        fullResult.secure_url,
        thumb_url:        thumbResult.secure_url,
        compressed_bytes: compressedBytes,
        thumb_bytes:      thumbBytes
      });

    } catch (err) {
      console.error("[compress/image] failed:", err.message);
      res.status(500).json({ error: err.message });
    } finally {
      try { if (fs.existsSync(inputPath)) fs.unlinkSync(inputPath); } catch (_) {}
      try { if (fs.existsSync(outFull))   fs.unlinkSync(outFull);   } catch (_) {}
      try { if (fs.existsSync(outThumb))  fs.unlinkSync(outThumb);  } catch (_) {}
    }
  });

  console.log("[OK] /compress/image endpoint ready");
})();

// ══════════════════════════════════════════════════════════════════════════════
// AUDIO MIX — Android v25 server-side audio mixing endpoint
// POST /audio/mix  (multipart/form-data)
//
// Mobile ne video + music URL bheja → server FFmpeg se mix karta hai →
// Cloudinary pe upload karta hai → output URL return karta hai
//
// Fields:
//   video        — video file with mic audio (file)
//   music_url    — background music URL (string, optional)
//   voiceover    — voiceover AAC file (file, optional)
//   mic_vol      — mic audio volume 0.0–1.0
//   music_vol    — music volume 0.0–1.0
//   voiceover_vol — voiceover volume 0.0–1.0
//
// Response: { output_url, public_id }
// ══════════════════════════════════════════════════════════════════════════════
(function setupAudioMix() {
  let multer, cloudinary, ffmpeg, fs, os, path;

  try {
    multer     = require("multer");
    cloudinary = require("cloudinary").v2;
    ffmpeg     = require("fluent-ffmpeg");
    fs         = require("fs");
    os         = require("os");
    path       = require("path");
  } catch (e) {
    console.warn("[WARN] /audio/mix deps missing:", e.message);
    app.post("/audio/mix", (req, res) => {
      res.status(503).json({
        error: "Server audio mix ready nahi hai",
        hint: "npm install multer cloudinary fluent-ffmpeg"
      });
    });
    app.get("/audio/download", (req, res) => {
      res.status(503).json({ error: "Audio mix server ready nahi hai" });
    });
    return;
  }

  const upload = multer({
    dest: os.tmpdir(),
    limits: { fileSize: 500 * 1024 * 1024 }
  });

  app.post("/audio/mix", upload.fields([
    { name: "video",     maxCount: 1 },
    { name: "voiceover", maxCount: 1 }
  ]), async (req, res) => {
    if (!cloudReady) {
      return res.status(503).json({ error: "Cloudinary not configured" });
    }

    const videoFile  = req.files?.["video"]?.[0];
    const voiceFile  = req.files?.["voiceover"]?.[0];

    if (!videoFile) {
      return res.status(400).json({ error: "video field required" });
    }

    const {
      music_url     = "",
      mic_vol       = "1.0",
      music_vol     = "0.5",
      voiceover_vol = "1.0"
    } = req.body;

    const outputPath = path.join(os.tmpdir(), `mixed_${Date.now()}.mp4`);

    cloudinary.config({
      cloud_name: CLOUD_NAME,
      api_key:    CLOUD_KEY,
      api_secret: CLOUD_SEC
    });

    try {
      // ── Build FFmpeg filter_complex ───────────────────────────────────────
      // Input 0: video (with mic audio)
      // Input 1: music URL (optional)
      // Input 2: voiceover file (optional)

      const hasMusicUrl = music_url && music_url.trim().length > 0;
      const hasVoiceover = voiceFile && fs.existsSync(voiceFile.path);

      await new Promise((resolve, reject) => {
        let cmd = ffmpeg(videoFile.path);

        let filterComplex = "";
        let audioOutput   = "";
        let inputCount    = 1;

        // Add music input
        if (hasMusicUrl) {
          cmd = cmd.input(music_url);
          inputCount++;
        }

        // Add voiceover input
        if (hasVoiceover) {
          cmd = cmd.input(voiceFile.path);
          inputCount++;
        }

        // Build filter_complex
        if (!hasMusicUrl && !hasVoiceover) {
          // Sirf mic — volume adjust
          filterComplex = `[0:a]volume=${mic_vol}[out]`;
          audioOutput   = "[out]";

        } else if (hasMusicUrl && !hasVoiceover) {
          // Mic + music
          filterComplex =
            `[0:a]volume=${mic_vol}[mic];` +
            `[1:a]volume=${music_vol}[music];` +
            `[mic][music]amix=inputs=2:duration=first:dropout_transition=2[out]`;
          audioOutput = "[out]";

        } else if (!hasMusicUrl && hasVoiceover) {
          // Mic + voiceover
          filterComplex =
            `[0:a]volume=${mic_vol}[mic];` +
            `[1:a]volume=${voiceover_vol}[vo];` +
            `[mic][vo]amix=inputs=2:duration=first[out]`;
          audioOutput = "[out]";

        } else {
          // Mic + music + voiceover
          filterComplex =
            `[0:a]volume=${mic_vol}[mic];` +
            `[1:a]volume=${music_vol}[music];` +
            `[2:a]volume=${voiceover_vol}[vo];` +
            `[mic][music][vo]amix=inputs=3:duration=first:dropout_transition=2[out]`;
          audioOutput = "[out]";
        }

        cmd
          .complexFilter(filterComplex)
          .outputOptions([
            "-map", "0:v",
            "-map", audioOutput,
            "-c:v", "copy",
            "-c:a", "aac",
            "-shortest",
            "-y"
          ])
          .on("end", resolve)
          .on("error", reject)
          .save(outputPath);
      });

      // ── Upload mixed video to Cloudinary ─────────────────────────────────
      const result = await cloudinary.uploader.upload(outputPath, {
        resource_type: "video",
        folder:        "callx/audio/mixed"
      });

      res.json({
        output_url: result.secure_url,
        public_id:  result.public_id
      });

    } catch (err) {
      console.error("[audio/mix] failed:", err.message);
      res.status(500).json({ error: err.message });
    } finally {
      // Cleanup
      try { if (fs.existsSync(videoFile.path))  fs.unlinkSync(videoFile.path);  } catch (_) {}
      try { if (voiceFile && fs.existsSync(voiceFile.path)) fs.unlinkSync(voiceFile.path); } catch (_) {}
      try { if (fs.existsSync(outputPath)) fs.unlinkSync(outputPath); } catch (_) {}
    }
  });

  // ── /audio/download — agar server ne local path diya ─────────────────────
  // (Cloudinary upload fail hone pe fallback — direct file serve karo)
  app.get("/audio/download", (req, res) => {
    const filePath = req.query.path;
    if (!filePath) return res.status(400).json({ error: "path required" });

    // Security: sirf os.tmpdir() ke andar files allow karo
    const safeBase = os.tmpdir();
    const resolved = require("path").resolve(filePath);
    if (!resolved.startsWith(safeBase)) {
      return res.status(403).json({ error: "Path not allowed" });
    }

    const fs2 = require("fs");
    if (!fs2.existsSync(resolved)) {
      return res.status(404).json({ error: "File not found" });
    }

    res.setHeader("Content-Type", "video/mp4");
    fs2.createReadStream(resolved).pipe(res);
  });

  console.log("[OK] /audio/mix + /audio/download endpoints ready");
})();

// ══════════════════════════════════════════════════════════════════════════════
// Helper: build history JSON from Firebase snapshot
// Returns JSON string: [{"t":"Hi","ts":1234567890,"me":false}, ...]
// "me":true = message sent BY the notification receiver (their own bubble)
// ══════════════════════════════════════════════════════════════════════════════
function getHistoryJson(histSnap, receiverUid) {
  if (!histSnap || !histSnap.exists()) return "";
  const items = [];
  histSnap.forEach(child => {
    const v    = child.val() || {};
    const type = String(v.type || "text");
    let   t    = String(v.text || "");
    const ts   = Number(v.timestamp || 0);
    if (ts === 0) return;
    const sid  = String(v.senderId || v.fromUid || "");
    if (!t) {
      if      (type === "image") t = "\uD83D\uDCF7 Photo";
      else if (type === "video") t = "\uD83C\uDFAC Video";
      else if (type === "audio") t = "\uD83C\uDFA4 Voice message";
      else if (type === "file" ) t = "\uD83D\uDCCE File";
      else if (type === "pdf"  ) t = "\uD83D\uDCC4 PDF document";
      else t = "Message";
    }
    items.push({ t, ts, me: sid === receiverUid });
  });
  items.sort((a, b) => a.ts - b.ts);
  return JSON.stringify(items);
}

// ══════════════════════════════════════════════════════════════════════════════
// Notify single user (v18 — zero Firebase on app side)
// ══════════════════════════════════════════════════════════════════════════════
app.post("/notify", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });

  const {
    toUid, fromUid, fromName, type, text,
    chatId, messageId, mediaUrl, force,
    // FIX-B: missed_call extra fields (sent by PushNotify.notifyMissedCall)
    callerPhoto = "", callerUid = "", callerName = "",
    isVideo = false, callId = "",
    // Feature-3: missed call count (Android locally tracked — server just passes through to FCM)
    missedCount = "1",
    // Broadcast List: true when this message was fanned out via a broadcast
    // list (BroadcastDeliveryWorker) — passed through so the recipient's
    // notification can show a "📢 Broadcast" indicator, same as WhatsApp.
    broadcast = false,
    // Emoji Reaction (1:1 + group, background/killed-safe) — sent by
    // PushNotify.notifyMessageReaction() / notifyGroupMessageReaction().
    // groupId/groupName only present for group_message_reaction; this push
    // still targets a single toUid (the reacted-to message's author), not
    // a group fan-out — see /notify/group for the fan-out pattern.
    reaction = "", groupId = "", groupName = ""
  } = req.body || {};
  if (!toUid) return res.status(400).json({ error: "toUid required" });

  const isCall           = (type === "call" || type === "video_call");
  const isSpecialRequest  = (type === "special_request");
  const isUnblockNotify  = (type === "unblock_notify");
  const isStatusReply = (type === "status_reply");
  const isMissedCall  = (type === "call_missed" || type === "missed_call"); // FIX-A: PushNotify sends "missed_call", legacy was "call_missed"
  const isViewOnceViewed = (type === "view_once_viewed"); // View Once: silent push to sender when receiver opens
  const isMessageReaction = (type === "message_reaction" || type === "group_message_reaction");
  const skipBlockChecks   = isStatusReply || isMissedCall || isSpecialRequest || isUnblockNotify || isViewOnceViewed;

  try {
    const db = admin.database();

    const MAX_SPECIAL_REQUESTS = 3;
    const SEVEN_DAYS_MS = 7 * 24 * 60 * 60 * 1000;

    const reads = [
      db.ref("users/" + toUid).once("value"),
      fromUid ? db.ref("users/" + fromUid).once("value") : Promise.resolve(null),
      (!force && fromUid && !isCall && !skipBlockChecks)
        ? db.ref("permaBlocked/" + toUid + "/" + fromUid).once("value")
        : Promise.resolve(null),
      (!force && fromUid && !isCall && !skipBlockChecks)
        ? db.ref("blocked/" + toUid + "/" + fromUid).once("value")
        : Promise.resolve(null),
      (!force && fromUid && !isCall && !skipBlockChecks)
        ? db.ref("muted/" + toUid + "/" + fromUid).once("value")
        : Promise.resolve(null),
      (chatId && !isCall)
        ? db.ref("messages/" + chatId)
            .orderByChild("timestamp").limitToLast(5).once("value")
        : Promise.resolve(null),
      // Special request: attempt count + ts for limit & expire checks
      (isSpecialRequest && fromUid)
        ? db.ref("specialRequests/" + toUid + "/" + fromUid).once("value")
        : Promise.resolve(null)
    ];

    const [receiverSnap, senderSnap, pbSnap, blockedSnap, mutedSnap, histSnap, sreqSnap]
      = await Promise.all(reads);

    const user = receiverSnap ? (receiverSnap.val() || {}) : {};
    if (!user.fcmToken)
      return res.status(404).json({ error: "no token" });

    const myThumb        = String(user.thumbUrl || user.photoUrl || "");
    const isPermaBlocked = !skipBlockChecks && pbSnap && pbSnap.val() === true;
    const isBlocked      = !skipBlockChecks && blockedSnap && blockedSnap.val() === true;

    if (isPermaBlocked)
      return res.json({ ok: true, dropped: "permaBlocked" });

    // Special request: attempt limit + 7-day expire (server-side safety)
    if (isSpecialRequest && fromUid && sreqSnap) {
      const sreq         = sreqSnap.val() || {};
      const attemptCount = Number(sreq.attemptCount || 0);
      const reqTs        = Number(sreq.ts || 0);

      // 7-day auto-expire — blocker ne respond nahi kiya
      if (reqTs > 0 && (Date.now() - reqTs) > SEVEN_DAYS_MS) {
        await db.ref("permaBlocked/" + toUid + "/" + fromUid).set(true);
        await db.ref("specialRequests/" + toUid + "/" + fromUid).remove();
        await db.ref("seenRequests/" + toUid + "/" + fromUid).remove();
        return res.json({ ok: true, dropped: "expiredRequest" });
      }

      // Attempt limit
      if (attemptCount >= MAX_SPECIAL_REQUESTS) {
        await db.ref("permaBlocked/" + toUid + "/" + fromUid).set(true);
        return res.json({ ok: true, dropped: "maxAttemptsReached" });
      }
    }

    let fromMobile = "", fromPhoto = "", fromThumb = "", fromLastSeen = "0";
    if (senderSnap) {
      const f   = senderSnap.val() || {};
      fromMobile   = String(f.mobile   || f.callxId || "");
      fromPhoto    = String(f.photoUrl || req.body.fromPhoto || "");
      fromThumb    = String(f.thumbUrl || "");
      fromLastSeen = String(f.lastSeen || 0);
    } else if (req.body.fromPhoto) {
      fromPhoto = String(req.body.fromPhoto);
    }

    // ── Feature-4: Missed call — server se caller ka lastSeen + online fetch karo ──
    // Android side async Firebase fetch karta hai, but server se bhi pass karo
    // taaki killed state mein bhi notification mein lastSeen subText aaye.
    let callerLastSeen = "0";
    let callerOnline   = "false";
    if (isMissedCall && (callerUid || fromUid)) {
      try {
        const callerRef  = callerUid || fromUid;
        // senderSnap already fetched — reuse karo
        const callerData = senderSnap ? (senderSnap.val() || {}) : {};
        callerLastSeen   = String(callerData.lastSeen || 0);
        callerOnline     = String(callerData.online   === true ? "true" : "false");
      } catch (_) {}
    }

    const history = getHistoryJson(histSnap, toUid);
    const isMuted = !skipBlockChecks && mutedSnap && mutedSnap.val() === true;

    const message = {
      token: user.fcmToken,
      data: {
        type:         String(type      || "message"),
        fromUid:      String(fromUid   || ""),
        fromName:     String(fromName  || ""),
        fromMobile:   fromMobile,
        fromPhoto:    fromPhoto,
        fromThumb:    fromThumb,
        fromLastSeen: fromLastSeen,
        chatId:       String(chatId    || ""),
        messageId:    String(messageId || ""),
        mediaUrl:     String(mediaUrl  || ""),
        text:         String(text      || ""),
        permaBlocked: "0",
        blocked:      isBlocked ? "1" : "0",
        muted:        isMuted   ? "1" : "0",
        history:      history,
        myThumb:      myThumb,
        broadcast:    (broadcast === true || broadcast === "true") ? "1" : "0",
        // Emoji Reaction passthrough — see PushNotify.notifyMessageReaction()
        // / notifyGroupMessageReaction(). groupId/groupName are only set for
        // group_message_reaction (message_reaction leaves them "").
        ...(isMessageReaction ? {
          reaction:  String(reaction  || "❤️"),
          groupId:   String(groupId   || ""),
          groupName: String(groupName || "")
        } : {}),
        ...(isCall && text ? { callId: String(text) } : {}),
        // FIX-B: missed_call fields — client reads callerPhoto/callerUid/callerName/isVideo
        ...(isMissedCall ? {
          callerPhoto:    String(callerPhoto || fromPhoto || ""),
          callerUid:      String(callerUid   || fromUid   || ""),
          callerName:     String(callerName  || fromName  || ""),
          isVideo:        String(isVideo === true || isVideo === "true"),
          callId:         String(callId || ""),
          // Feature-3: grouping count — Android SharedPrefs se track hota hai,
          // server just passes through for multi-device / reinstall scenarios
          missedCount:    String(missedCount || "1"),
          // Feature-4: lastSeen — Android notification subText mein dikhta hai
          // "Last seen 5 min ago" / "Online now"
          callerLastSeen: callerLastSeen,
          callerOnline:   callerOnline
        } : {})
      },
      android: {
        priority: (isMuted && !isCall && !isStatusReply) ? "normal"
                : isViewOnceViewed ? "normal"   // silent — no wake lock needed
                : "high",
        ...(isCall ? { ttl: 30000 } : {}),
        ...(isMissedCall ? { ttl: 86400000 } : {}),  // FIX-B: missed call 24h TTL
        ...(isViewOnceViewed ? { ttl: 3600000 } : {})  // view_once_viewed: 1h TTL
      }
    };

    const r = await admin.messaging().send(message);
    res.json({ ok: true, id: r });
  } catch (e) {
    console.error("notify err:", e.message);
    res.status(500).json({ error: e.message });
  }
});

// ══════════════════════════════════════════════════════════════════════════════
// Notify reel like / comment / following-posted etc. (v14)
// ══════════════════════════════════════════════════════════════════════════════
const VALID_REEL_TYPES = new Set([
  "like", "comment", "comment_like", "comment_reply",
  "mention_caption", "mention_comment", "new_follower",
  "following_posted", "duet", "stitch", "video_reply",
  "collab_request", "collab_accepted", "gift",
  "live_started", "live_milestone", "close_friend_live",
  "trending", "viral", "view_milestone", "follower_milestone",
  "upload_complete", "upload_failed", "scheduled_post",
  "scheduled_reminder", "product_tag_click", "creator_fund_payout",
  "content_removed", "report_resolved", "sound_trending",
  "pinned_comment", "close_friend_post", "challenge",
  "reel_shared", "reel_saved", "reel_downloaded",
  "weekly_digest", "collab_live",
  // Feature-3 (missed call grouping) se related nahi — ye repost notify fix hai:
  // PushNotify.notifyReelRepost() type="repost" bhejta hai — pehle 400 error aata tha
  "repost",
  // Multi-Duet invite
  "multi_duet_invite",
  // Collab Repost cross-device push
  "collab_repost_invite",
  "collab_repost_accepted",
  "collab_repost_declined"
]);

app.post("/notify/reel", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });

  const {
    toUid, fromUid, fromName, fromPhoto,
    reelId, reelThumb, type, commentText, commentId,
    sessionId,      // multi_duet_invite ke liye extra field
    collabRepostId, // Collab Repost: invite/accepted/declined ke liye (CollabRepostNotificationHelper isi key se padhta h)
    newReelId       // Collab Repost: accepted ke baad ka naya reel id
  } = req.body || {};

  if (!toUid)  return res.status(400).json({ error: "toUid required" });
  if (!type)   return res.status(400).json({ error: "type required" });
  if (!VALID_REEL_TYPES.has(type))
    return res.status(400).json({ error: "invalid reel_notif_type: " + type });

  const noReelIdNeeded = ["new_follower", "weekly_digest", "follower_milestone",
    "creator_fund_payout", "report_resolved", "upload_failed"];
  if (!noReelIdNeeded.includes(type) && !reelId)
    return res.status(400).json({ error: "reelId required for type: " + type });

  if (toUid === fromUid) return res.json({ ok: true, dropped: "self" });

  try {
    const snap = await admin.database().ref("users/" + toUid).once("value");
    const user = snap.val() || {};
    if (!user.fcmToken)
      return res.status(404).json({ error: "no token" });

    let senderPhoto = String(fromPhoto || "");
    if (!senderPhoto && fromUid) {
      try {
        const fSnap = await admin.database().ref("users/" + fromUid).once("value");
        const fVal  = fSnap.val() || {};
        senderPhoto = String(fVal.thumbUrl || fVal.photoUrl || "");
      } catch (_) {}
    }

    const r = await admin.messaging().send({
      token: user.fcmToken,
      data: {
        reel_notif_type: String(type        || "like"),
        sender_uid:      String(fromUid     || ""),
        sender_name:     String(fromName    || ""),
        sender_photo:    senderPhoto,
        reel_id:         String(reelId      || ""),
        reel_thumb:      String(reelThumb   || ""),
        comment_text:    String(commentText || ""),
        comment_id:      String(commentId   || ""),
        session_id:      String(sessionId   || ""),  // multi_duet_invite
        // Collab Repost — keys camelCase rakhi h kyunki ReelFCMNotificationHandler
        // exactly "collabRepostId" / "newReelId" string se hi padhta h (get(data, "collabRepostId")).
        // Pehle ye fields yaha forward nahi ho rahe the, isliye collab repost push
        // aa to jaati thi but collabId/newReelId empty aate the (notif id clash +
        // "highlight_collab_id" deep-link kabhi kaam nahi karta tha).
        collabRepostId:  String(collabRepostId || ""),
        newReelId:       String(newReelId      || "")
      },
      android: { priority: "high", ttl: 86400000 }
    });

    res.json({ ok: true, id: r });
  } catch (e) {
    console.error("reel notify err:", e.message);
    res.status(500).json({ error: e.message });
  }
});

// ══════════════════════════════════════════════════════════════════════════════
// Notify X feature — like, retweet, reply, mention, quote, follow, dm,
//                    poll_ended, list_added, space_started, close_friend_post
// POST /notify/x
//
// Body keys:
//   toUid          — receiver UID (required)
//   fromUid        — sender UID
//   fromName       — sender display name
//   fromHandle     — sender @handle (optional — server x/users se auto-fetch karta hai)
//   fromPhoto      — sender avatar URL (optional — server users/ se auto-fetch karta hai)
//   type           — x_notif_type value (required)
//   tweetId        — target tweet ID (like/retweet/reply/mention/quote)
//   conversationId — DM conversation ID
//   otherUid       — DM other user UID
//   otherHandle    — DM other user handle
//   otherPhoto     — DM other user avatar URL
//   preview        — DM message preview text
//   pollQuestion   — poll_ended ke liye poll question
//   listName       — list_added ke liye list name
//   spaceId        — space_started ke liye space ID
//   spaceTitle     — space_started ke liye space title
//
// Android client: XFCMNotificationHandler.handle() routes by "x_notif_type" key
// ══════════════════════════════════════════════════════════════════════════════
const VALID_X_TYPES = new Set([
  "like", "retweet", "reply", "mention", "quote", "follow", "dm",
  "poll_ended", "list_added", "space_started", "close_friend_post"
]);

app.post("/notify/x", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });

  const {
    toUid, fromUid, fromName, fromPhoto,
    fromHandle     = "",
    type,
    tweetId        = "",
    conversationId = "",
    otherUid       = "",
    otherHandle    = "",
    otherPhoto     = "",
    preview        = "",
    pollQuestion   = "",
    listName       = "",
    spaceId        = "",
    spaceTitle     = ""
  } = req.body || {};

  if (!toUid) return res.status(400).json({ error: "toUid required" });
  if (!type)  return res.status(400).json({ error: "type required" });
  if (!VALID_X_TYPES.has(type))
    return res.status(400).json({ error: "invalid x_notif_type: " + type });

  // Self-notification drop
  if (toUid === fromUid) return res.json({ ok: true, dropped: "self" });

  try {
    const db   = admin.database();
    const snap = await db.ref("users/" + toUid).once("value");
    const user = snap.val() || {};
    if (!user.fcmToken)
      return res.status(404).json({ error: "no token" });

    // Sender photo + handle fallback — Firebase se fetch karo agar nahi mila
    let senderPhoto  = String(fromPhoto  || "");
    let senderHandle = String(fromHandle || "");
    if (fromUid && (!senderPhoto || !senderHandle)) {
      try {
        // x/users me X-specific profile hai — photo aur handle dono yahan se lo
        const xSnap = await db.ref("x/users/" + fromUid).once("value");
        const xVal  = xSnap.val() || {};
        if (!senderPhoto)  senderPhoto  = String(xVal.thumbUrl || xVal.photoUrl || "");
        if (!senderHandle) senderHandle = String(xVal.handle   || xVal.username  || "");
        // x/users me photo nahi mila to main users/ se try karo
        if (!senderPhoto) {
          const fSnap = await db.ref("users/" + fromUid).once("value");
          const fVal  = fSnap.val() || {};
          senderPhoto = String(fVal.thumbUrl || fVal.photoUrl || "");
        }
      } catch (_) {}
    }
    // TTL: 4h for all X notifications (background/killed safe)
    const ttlMs = 4 * 60 * 60 * 1000;

    const r = await admin.messaging().send({
      token: user.fcmToken,
      data: {
        x_notif_type:   String(type),
        fromUid:        String(fromUid        || ""),
        fromName:       String(fromName        || ""),
        fromHandle:     senderHandle,
        fromPhoto:      senderPhoto,
        tweetId:        String(tweetId        || ""),
        conversationId: String(conversationId || ""),
        otherUid:       String(otherUid       || ""),
        otherHandle:    String(otherHandle    || ""),
        otherPhoto:     String(otherPhoto     || ""),
        preview:        String(preview        || ""),
        pollQuestion:   String(pollQuestion   || ""),
        listName:       String(listName       || ""),
        spaceId:        String(spaceId        || ""),
        spaceTitle:     String(spaceTitle     || "")
      },
      android: { priority: "high", ttl: ttlMs }
    });

    // Firebase DB me bhi save karo — XNotificationWorker background polling ke liye
    // x/notifications/{toUid}/{pushKey} — read: false, notified: false
    try {
      await db.ref("x/notifications/" + toUid).push({
        type:           String(type),
        fromUid:        String(fromUid        || ""),
        fromName:       String(fromName        || ""),
        fromHandle:     senderHandle,
        fromPhotoUrl:   senderPhoto,
        tweetId:        String(tweetId        || ""),
        conversationId: String(conversationId || ""),
        otherUid:       String(otherUid       || ""),
        otherHandle:    String(otherHandle    || ""),
        otherPhotoUrl:  String(otherPhoto     || ""),
        preview:        String(preview        || ""),
        pollQuestion:   String(pollQuestion   || ""),
        listName:       String(listName       || ""),
        spaceId:        String(spaceId        || ""),
        spaceTitle:     String(spaceTitle     || ""),
        read:           false,
        notified:       false,
        timestamp:      Date.now()
      });
    } catch (_) {} // DB save fail hone se FCM response affect na ho

    res.json({ ok: true, id: r });
  } catch (e) {
    console.error("x notify err:", e.message);
    res.status(500).json({ error: e.message });
  }
});

// ══════════════════════════════════════════════════════════════════════════════
// POST /notify/youtube
// YouTube notification — background/killed state safe via FCM + Firebase DB save
//
// Body keys:
//   toUid        — receiver UID (required)
//   fromUid      — sender / channel UID
//   fromName     — channel / commenter display name
//   fromPhoto    — avatar URL (optional — server youtube/channels/ se auto-fetch)
//   type         — yt_notif_type value (required)
//   videoId      — target video ID
//   videoTitle   — video title
//   thumbnailUrl — video thumbnail URL
//   commentText  — comment / reply preview text
//   likeCount    — like_milestone ke liye
//
// Android: CallxMessagingService → YouTubeFCMNotificationHandler.handle()
// ══════════════════════════════════════════════════════════════════════════════
const VALID_YT_TYPES = new Set([
  "new_video", "comment", "reply", "subscribe", "live", "like_milestone"
]);

app.post("/notify/youtube", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });

  const {
    toUid, fromUid, fromName, fromPhoto,
    type,
    videoId        = "",
    videoTitle     = "",
    thumbnailUrl   = "",
    commentText    = "",
    likeCount      = ""
  } = req.body || {};

  if (!toUid) return res.status(400).json({ error: "toUid required" });
  if (!type)  return res.status(400).json({ error: "type required" });
  if (!VALID_YT_TYPES.has(type))
    return res.status(400).json({ error: "invalid yt_notif_type: " + type });

  if (toUid === fromUid) return res.json({ ok: true, dropped: "self" });

  try {
    const db   = admin.database();
    const snap = await db.ref("users/" + toUid).once("value");
    const user = snap.val() || {};
    if (!user.fcmToken)
      return res.status(404).json({ error: "no token" });

    // Sender photo fallback — youtube/channels/ se fetch karo
    let senderPhoto = String(fromPhoto || "");
    if (fromUid && !senderPhoto) {
      try {
        const chSnap = await db.ref("youtube/channels/" + fromUid).once("value");
        const chVal  = chSnap.val() || {};
        senderPhoto = String(chVal.thumbUrl || chVal.photoUrl || chVal.avatarUrl || "");
        // Fallback to main users/
        if (!senderPhoto) {
          const uSnap = await db.ref("users/" + fromUid).once("value");
          const uVal  = uSnap.val() || {};
          senderPhoto = String(uVal.thumbUrl || uVal.photoUrl || "");
        }
      } catch (_) {}
    }

    // TTL: all YouTube notifications = 4h (background/killed safe)
    // 60s was too short — Doze mode pe expire ho jaata tha before delivery
    const ttlMs = 4 * 60 * 60 * 1000;

    const r = await admin.messaging().send({
      token: user.fcmToken,
      data: {
        yt_notif_type:  String(type),
        fromUid:        String(fromUid       || ""),
        fromName:       String(fromName      || ""),
        fromPhoto:      senderPhoto,
        videoId:        String(videoId       || ""),
        videoTitle:     String(videoTitle    || ""),
        thumbnailUrl:   String(thumbnailUrl  || ""),
        commentText:    String(commentText   || ""),
        likeCount:      String(likeCount     || "")
      },
      android: { priority: "high", ttl: ttlMs }
    });

    // Firebase DB me bhi save — YouTubeNotificationWorker background polling ke liye
    try {
      await db.ref("youtube/notifications/" + toUid).push({
        type:         String(type),
        fromUid:      String(fromUid       || ""),
        fromName:     String(fromName      || ""),
        fromPhotoUrl: senderPhoto,
        videoId:      String(videoId       || ""),
        videoTitle:   String(videoTitle    || ""),
        thumbnailUrl: String(thumbnailUrl  || ""),
        commentText:  String(commentText   || ""),
        likeCount:    String(likeCount     || ""),
        notified:     false,
        read:         false,
        timestamp:    Date.now()
      });
    } catch (_) {}

    res.json({ ok: true, id: r });
  } catch (e) {
    console.error("youtube notify err:", e.message);
    res.status(500).json({ error: e.message });
  }
});

// ══════════════════════════════════════════════════════════════════════════════
// Notify group (production-grade fanout v2)
// ══════════════════════════════════════════════════════════════════════════════
app.post("/notify/group", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });

  const {
    groupId, fromUid, fromName, fromPhoto,
    messageId, type, text, mediaUrl
  } = req.body || {};
  if (!groupId) return res.status(400).json({ error: "groupId required" });

  try {
    const db    = admin.database();
    const gSnap = await db.ref("groups/" + groupId).once("value");
    const g     = gSnap.val();
    if (!g) return res.status(404).json({ error: "group not found" });

    const groupName  = String(g.name    || "Group");
    const groupIcon  = String(g.iconUrl || "");
    const memberUids = Object.keys(g.members || {}).filter(uid => uid !== fromUid);
    const mutedBy    = g.mutedBy || {};

    const sharedReads = [
      fromUid ? db.ref("users/" + fromUid).once("value") : Promise.resolve(null),
      db.ref("messages/" + groupId)
        .orderByChild("timestamp").limitToLast(5).once("value")
    ];
    const pbReads    = memberUids.map(uid =>
      fromUid ? db.ref("permaBlocked/" + uid + "/" + fromUid).once("value")
              : Promise.resolve(null));
    const tokenReads = memberUids.map(uid => db.ref("users/" + uid).once("value"));

    const allResults = await Promise.all([...sharedReads, ...pbReads, ...tokenReads]);

    const senderSnap   = allResults[0];
    const histSnap     = allResults[1];
    const pbResults    = allResults.slice(2, 2 + memberUids.length);
    const tokenResults = allResults.slice(2 + memberUids.length);

    let senderPhoto    = String(fromPhoto || "");
    let senderMobile   = "";
    let senderLastSeen = "0";
    if (senderSnap) {
      const f = senderSnap.val() || {};
      if (!senderPhoto) senderPhoto = String(f.thumbUrl || f.photoUrl || "");
      senderMobile   = String(f.mobile || f.callxId || "");
      senderLastSeen = String(f.lastSeen || 0);
    }

    const history = getHistoryJson(histSnap, null);

    // @mention detection
    const mentionedUids = new Set();
    if (text) {
      const lower = text.toLowerCase();
      if (lower.includes("@everyone") || lower.includes("@all")) {
        memberUids.forEach(uid => mentionedUids.add(uid));
      } else {
        const mentionTokens = (text.match(/@(\w+)/g) || [])
          .map(t => t.slice(1).toLowerCase());
        if (mentionTokens.length > 0) {
          tokenResults.forEach((snap, idx) => {
            if (!snap) return;
            const u    = snap.val() || {};
            const name = String(u.name || u.displayName || "").toLowerCase().replace(/\s+/g, "");
            const first = name.split(" ")[0];
            if (mentionTokens.some(t => name.startsWith(t) || first.startsWith(t))) {
              mentionedUids.add(memberUids[idx]);
            }
          });
        }
      }
    }

    const updates     = {};
    const staleTokens = [];
    let sent = 0, dropped = 0;

    await Promise.all(memberUids.map(async (uid, idx) => {
      try {
        const pbSnap = pbResults[idx];
        if (pbSnap && pbSnap.val() === true) { dropped++; return; }

        const u  = tokenResults[idx] ? (tokenResults[idx].val() || {}) : {};
        const tk = u.fcmToken;
        if (!tk) { dropped++; return; }

        const isMuted = mutedBy[uid] === true;
        updates["groups/" + groupId + "/unread/" + uid] =
          admin.database.ServerValue.increment(1);

        await admin.messaging().send({
          token: tk,
          data: {
            type:         String(type || "group_message"),
            groupId:      String(groupId),
            groupName:    groupName,
            groupIcon:    groupIcon,
            fromUid:      String(fromUid   || ""),
            fromName:     String(fromName  || ""),
            fromPhoto:    senderPhoto,
            fromThumb:    senderPhoto,
            fromMobile:   senderMobile,
            fromLastSeen: senderLastSeen,
            messageId:    String(messageId || ""),
            msgId:        String(messageId || ""),
            mediaUrl:     String(mediaUrl  || ""),
            text:         String(text      || ""),
            muted:        isMuted ? "1" : "0",
            mention:      mentionedUids.has(uid) ? "true" : "false",
            priority:     "false",
            history:      history
          },
          android: {
            priority:    isMuted ? "normal" : "high",
            collapseKey: "grp_" + groupId + "_" + (messageId || Date.now()),
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

    try { if (Object.keys(updates).length) await db.ref().update(updates); } catch (_) {}
    try { for (const uid of staleTokens) await db.ref("users/" + uid + "/fcmToken").remove(); } catch (_) {}

    res.json({ ok: true, sent, dropped, members: memberUids.length });
  } catch (e) {
    console.error("group notify err:", e.message);
    res.status(500).json({ error: e.message });
  }
});

// ══════════════════════════════════════════════════════════════════════════════
// Reset unread counter for a group
// ══════════════════════════════════════════════════════════════════════════════
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

// ══════════════════════════════════════════════════════════════════════════════
// Notify status (fanout to all contacts of poster) — rich payload v3
// Sends: fromPhoto, statusType, text, mediaUrl so receiver can show
// BigPicture notification even in killed state.
// ══════════════════════════════════════════════════════════════════════════════
app.post("/notify/status", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });

  const {
    fromUid, fromName,
    fromPhoto  = "",
    statusType = "text",
    text       = "",
    mediaUrl   = ""
  } = req.body || {};
  if (!fromUid) return res.status(400).json({ error: "fromUid required" });

  try {
    const db    = admin.database();
    const cSnap = await db.ref("contacts/" + fromUid).once("value");
    const uids  = Object.keys(cSnap.val() || {});
    if (!uids.length) return res.json({ ok: true, sent: 0 });

    // Fetch sender photo fallback
    let senderPhoto = String(fromPhoto || "");
    if (!senderPhoto) {
      try {
        const fSnap = await db.ref("users/" + fromUid).once("value");
        const fVal  = fSnap.val() || {};
        senderPhoto = String(fVal.thumbUrl || fVal.photoUrl || "");
      } catch (_) {}
    }

    // Batch fetch all user tokens
    const userSnaps = await Promise.all(
      uids.map(uid => db.ref("users/" + uid).once("value"))
    );

    let sent = 0;
    await Promise.all(userSnaps.map(async (snap) => {
      const u  = snap.val() || {};
      const tk = u.fcmToken;
      if (!tk) return;
      try {
        await admin.messaging().send({
          token: tk,
          data: {
            type:       "status",
            fromUid:    String(fromUid),
            fromName:   String(fromName  || "Friend"),
            fromPhoto:  senderPhoto,
            statusType: String(statusType),
            text:       String(text),
            mediaUrl:   String(mediaUrl)
          },
          android: { priority: "high" }
        });
        sent++;
      } catch (e) {
        console.warn("status send fail:", e.message);
      }
    }));

    res.json({ ok: true, sent });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// ══════════════════════════════════════════════════════════════════════════════
// Notify status reaction — POST /notify/status_reaction
// Called by PushNotify.notifyStatusReaction()
// Payload: toUid, fromUid, fromName, fromPhoto, reaction, ownerUid
// Android: CallxMessagingService → handleStatusReaction()
// ══════════════════════════════════════════════════════════════════════════════
app.post("/notify/status_reaction", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });

  const {
    toUid, fromUid, fromName,
    fromPhoto = "",
    reaction  = "❤️",
    ownerUid  = ""
  } = req.body || {};
  if (!toUid) return res.status(400).json({ error: "toUid required" });
  if (toUid === fromUid) return res.json({ ok: true, dropped: "self" });

  try {
    const db   = admin.database();
    const snap = await db.ref("users/" + toUid).once("value");
    const user = snap.val() || {};
    if (!user.fcmToken) return res.status(404).json({ error: "no token" });

    let senderPhoto = String(fromPhoto || "");
    if (!senderPhoto && fromUid) {
      try {
        const fSnap = await db.ref("users/" + fromUid).once("value");
        const fVal  = fSnap.val() || {};
        senderPhoto = String(fVal.thumbUrl || fVal.photoUrl || "");
      } catch (_) {}
    }

    const r = await admin.messaging().send({
      token: user.fcmToken,
      data: {
        type:      "status_reaction",
        fromUid:   String(fromUid   || ""),
        fromName:  String(fromName  || ""),
        fromPhoto: senderPhoto,
        reaction:  String(reaction),
        ownerUid:  String(ownerUid)
      },
      android: { priority: "high", ttl: 6 * 60 * 60 * 1000 }
    });
    res.json({ ok: true, id: r });
  } catch (e) {
    console.error("status_reaction notify err:", e.message);
    res.status(500).json({ error: e.message });
  }
});

// ══════════════════════════════════════════════════════════════════════════════
// Notify broadcast delivery — POST /notify/broadcast
// Called by PushNotify.notifyBroadcastComplete() from BroadcastDeliveryWorker
// after a broadcast list fan-out finishes (success OR failure).
//
// Purpose: sender-side, background/killed-safe confirmation push.
//   • BroadcastDeliveryWorker runs via WorkManager and already shows a LOCAL
//     notification directly on the sending device the instant it finishes —
//     that covers the common case with zero network round-trip.
//   • This endpoint additionally pushes an FCM "broadcast_message" data
//     message to the sender's account so that ANY other signed-in device
//     (tablet, secondary phone) also gets the delivery summary even if that
//     device was in the background or fully killed — same high-priority
//     data-only pattern already used for reel/x/youtube/status notifications.
//
// Payload: toUid (sender uid), listId, listName, delivered, total, skipped,
//          status ("sent"|"failed"), msgType, lastMessage
// Android: type="broadcast_message" → CallxMessagingService →
//          BroadcastFCMHandler.handle() → opens BroadcastChatActivity
// ══════════════════════════════════════════════════════════════════════════════
app.post("/notify/broadcast", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });

  const {
    toUid, listId, listName = "Broadcast",
    delivered = 0, total = 0, skipped = 0,
    status = "sent", msgType = "text", lastMessage = ""
  } = req.body || {};
  if (!toUid)  return res.status(400).json({ error: "toUid required" });
  if (!listId) return res.status(400).json({ error: "listId required" });

  try {
    const db   = admin.database();
    const snap = await db.ref("users/" + toUid).once("value");
    const user = snap.val() || {};
    if (!user.fcmToken) return res.status(404).json({ error: "no token" });

    const r = await admin.messaging().send({
      token: user.fcmToken,
      data: {
        type:        "broadcast_message",
        list_id:     String(listId),
        list_name:   String(listName),
        delivered:   String(delivered),
        total:       String(total),
        skipped:     String(skipped),
        status:      String(status),
        msg_type:    String(msgType),
        last_message:String(lastMessage || "")
      },
      // Self-notify, background/killed-safe: high priority so FCM wakes the
      // app to post the notification even if the process was killed, with a
      // generous TTL since it's an informational summary, not time-critical.
      android: { priority: "high", ttl: 24 * 60 * 60 * 1000 }
    });
    res.json({ ok: true, id: r });
  } catch (e) {
    console.error("notify/broadcast err:", e.message);
    res.status(500).json({ error: e.message });
  }
});

// ══════════════════════════════════════════════════════════════════════════════
// Notify contact join — POST /notify/contact_join
// Called by PushNotify.notifyContactsOfNewUser()
// Fanout: notifies all contacts of newUid that they joined CallX
// Payload: newUid, newName, newPhoto
// Android: type="contact_join" → CallxMessagingService
// ══════════════════════════════════════════════════════════════════════════════
app.post("/notify/contact_join", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });

  const { newUid, newName, newPhoto = "" } = req.body || {};
  if (!newUid) return res.status(400).json({ error: "newUid required" });

  try {
    const db    = admin.database();
    const cSnap = await db.ref("contacts/" + newUid).once("value");
    const uids  = Object.keys(cSnap.val() || {});
    if (!uids.length) return res.json({ ok: true, sent: 0 });

    let senderPhoto = String(newPhoto || "");
    if (!senderPhoto) {
      try {
        const fSnap = await db.ref("users/" + newUid).once("value");
        const fVal  = fSnap.val() || {};
        senderPhoto = String(fVal.thumbUrl || fVal.photoUrl || "");
      } catch (_) {}
    }

    const userSnaps = await Promise.all(
      uids.map(uid => db.ref("users/" + uid).once("value"))
    );

    let sent = 0;
    await Promise.all(userSnaps.map(async (snap) => {
      const u  = snap.val() || {};
      const tk = u.fcmToken;
      if (!tk) return;
      try {
        await admin.messaging().send({
          token: tk,
          data: {
            type:     "contact_join",
            fromUid:  String(newUid),
            fromName: String(newName  || ""),
            fromPhoto: senderPhoto
          },
          android: { priority: "normal", ttl: 24 * 60 * 60 * 1000 }
        });
        sent++;
      } catch (e) {
        console.warn("contact_join send fail:", e.message);
      }
    }));

    res.json({ ok: true, sent });
  } catch (e) {
    console.error("contact_join notify err:", e.message);
    res.status(500).json({ error: e.message });
  }
});

// ══════════════════════════════════════════════════════════════════════════════
// Notify group member joined — POST /notify/group_join
// Called by PushNotify.notifyGroupMemberJoined()
// Fanout: notifies all existing group members that someone new joined
// Payload: groupId, groupName, newMemberName
// Android: type="group_member_joined" → CallxMessagingService
// ══════════════════════════════════════════════════════════════════════════════
app.post("/notify/group_join", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });

  const { groupId, groupName = "Group", newMemberName = "" } = req.body || {};
  if (!groupId) return res.status(400).json({ error: "groupId required" });

  try {
    const db    = admin.database();
    const gSnap = await db.ref("groups/" + groupId).once("value");
    const g     = gSnap.val();
    if (!g) return res.status(404).json({ error: "group not found" });

    const memberUids = Object.keys(g.members || {});
    if (!memberUids.length) return res.json({ ok: true, sent: 0 });

    const userSnaps = await Promise.all(
      memberUids.map(uid => db.ref("users/" + uid).once("value"))
    );

    let sent = 0;
    await Promise.all(userSnaps.map(async (snap) => {
      const u  = snap.val() || {};
      const tk = u.fcmToken;
      if (!tk) return;
      try {
        await admin.messaging().send({
          token: tk,
          data: {
            type:          "group_member_joined",
            groupId:       String(groupId),
            groupName:     String(groupName),
            newMemberName: String(newMemberName)
          },
          android: { priority: "normal", ttl: 6 * 60 * 60 * 1000 }
        });
        sent++;
      } catch (e) {
        console.warn("group_join send fail:", e.message);
      }
    }));

    res.json({ ok: true, sent });
  } catch (e) {
    console.error("group_join notify err:", e.message);
    res.status(500).json({ error: e.message });
  }
});

// ══════════════════════════════════════════════════════════════════════════════
// Android App Links + Deep Link Routes
// ══════════════════════════════════════════════════════════════════════════════
const ASSET_LINKS = [
  {
    relation: ["delegate_permission/common.handle_all_urls"],
    target: {
      namespace: "android_app",
      package_name: "com.callx.app",
      sha256_cert_fingerprints: [
        "92:31:CD:9F:90:15:45:54:3B:92:D8:21:FC:6E:1F:DC:D5:40:8B:F0:69:04:96:85:BD:30:99:50:1A:EB:5D:03"
      ]
    }
  }
];

app.get("/.well-known/assetlinks.json", (req, res) => {
  res.setHeader("Content-Type", "application/json");
  res.setHeader("Cache-Control", "public, max-age=3600");
  res.json(ASSET_LINKS);
});
app.get("/assetlinks.json", (req, res) => {
  res.setHeader("Content-Type", "application/json");
  res.json(ASSET_LINKS);
});

// ── Deep Link HTML page helper ──────────────────────────────────────────────
function deepLinkPage(appUrl, webFallbackUrl, title, description) {
  return `<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="width=device-width,initial-scale=1"/>
  <title>${title} – CallX</title>
  <style>
    body{font-family:sans-serif;text-align:center;padding:40px;background:#0f0f0f;color:#fff}
    .logo{font-size:2rem;font-weight:bold;color:#25D366;margin-bottom:8px}
    p{color:#aaa;margin-bottom:24px}
    a.btn{display:inline-block;background:#25D366;color:#fff;padding:14px 32px;
          border-radius:30px;text-decoration:none;font-weight:bold;font-size:1rem}
    .sub{color:#666;font-size:0.85rem;margin-top:16px}
  </style>
</head>
<body>
  <div class="logo">CallX</div>
  <p>${description}</p>
  <a class="btn" id="openBtn" href="${appUrl}">CallX mein kholein</a>
  <p class="sub" id="msg">App khul rahi hai...</p>
  <script>
    var appUrl = "${appUrl}";
    function tryOpen() {
      window.location.href = appUrl;
      setTimeout(function() {
        document.getElementById('msg').innerHTML =
          'App install nahi hai? <a style="color:#25D366" href="https://play.google.com/store/apps/details?id=com.callx.app">Download karo</a>';
      }, 2000);
    }
    window.addEventListener('load', function() { setTimeout(tryOpen, 300); });
    document.getElementById('openBtn').addEventListener('click', function(e) {
      e.preventDefault(); tryOpen();
    });
  </script>
</body>
</html>`;
}

// ── Deep link routes ────────────────────────────────────────────────────────
app.get("/u/:uid",          (req, res) => res.send(deepLinkPage(`callx://u/${req.params.uid}`,            "", "Profile",     "Is user ka profile dekhen CallX app mein")));
app.get("/profile/:uid",    (req, res) => res.send(deepLinkPage(`callx://profile/${req.params.uid}`,      "", "Profile",     "Is user ka profile dekhen CallX app mein")));
app.get("/chat/:uid",       (req, res) => res.send(deepLinkPage(`callx://chat/${req.params.uid}`,         "", "Chat",        "Is user se chat karein CallX par")));
app.get("/join/:groupId",   (req, res) => res.send(deepLinkPage(`callx://join/${req.params.groupId}`,     "", "Group Join",  "CallX group join karein")));
app.get("/g/:groupId",      (req, res) => res.send(deepLinkPage(`callx://g/${req.params.groupId}`,        "", "Group Chat",  "Is group ka chat kholein CallX mein")));
app.get("/reel/:reelId",    (req, res) => res.send(deepLinkPage(`callx://reel/${req.params.reelId}`,      "", "Reel",        "Ye reel CallX mein dekhein")));
app.get("/reels/user/:uid", (req, res) => res.send(deepLinkPage(`callx://reels/user/${req.params.uid}`,  "", "User Reels",  "Is user ke saare reels CallX mein dekhein")));
app.get("/reels/hashtag/:tag",  (req, res) => res.send(deepLinkPage(`callx://reels/hashtag/${req.params.tag}`,  "", `#${req.params.tag} Reels`, `#${req.params.tag} ke saare reels dekhein`)));
app.get("/reels/sound/:soundId",(req, res) => res.send(deepLinkPage(`callx://reels/sound/${req.params.soundId}`, "", "Sound",  "Ye sound CallX mein sune aur use karein")));
app.get("/status/:uid",     (req, res) => res.send(deepLinkPage(`callx://status/${req.params.uid}`,      "", "Status",      "Is user ka status CallX mein dekhein")));
app.get("/search",          (req, res) => {
  const q = req.query.q || "";
  res.send(deepLinkPage(`callx://search?q=${encodeURIComponent(q)}`, "", "Search", `"${q}" ko CallX mein search karein`));
});

// App section tabs
["chats", "calls", "reels", "groups", "notifications"].forEach(tab => {
  app.get(`/${tab}`, (req, res) => res.send(deepLinkPage(
    `callx://${tab}`, "",
    tab.charAt(0).toUpperCase() + tab.slice(1),
    `CallX app ka ${tab} section kholein`
  )));
});

// ══════════════════════════════════════════════════════════════════════════════
// Start server + Render keep-alive
// ══════════════════════════════════════════════════════════════════════════════
const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
  console.log("callx-server v3 on :" + PORT);

  // Render free tier ko jaagta rakho — har 14 min mein self-ping
  const https   = require("https");
  const SELF_URL = "https://callx-server.onrender.com/ping";
  setInterval(() => {
    https.get(SELF_URL, r => {
      console.log("[keep-alive] ping →", r.statusCode);
    }).on("error", e => {
      console.warn("[keep-alive] ping failed:", e.message);
    });
  }, 14 * 60 * 1000);
});
