/**
 * CallX2 — Collab Repost Server Endpoints
 * Add these to your existing index.js / Express server.
 *
 * Dependencies (already in your package.json if using existing server):
 *   firebase-admin, express, axios
 */

const admin = require('firebase-admin');
const express = require('express');
const router = express.Router();

const db = admin.database();
const messaging = admin.messaging();

// ─────────────────────────────────────────────────────────────────────────────
// Helper: get FCM token for a user
// ─────────────────────────────────────────────────────────────────────────────
async function getFcmToken(uid) {
  const snap = await db.ref(`users/${uid}/fcmToken`).get();
  return snap.exists() ? snap.val() : null;
}

// ─────────────────────────────────────────────────────────────────────────────
// Helper: send FCM push
// ─────────────────────────────────────────────────────────────────────────────
async function sendPush(token, title, body, data = {}) {
  if (!token) return;
  try {
    await messaging.send({
      token,
      notification: { title, body },
      data: Object.fromEntries(Object.entries(data).map(([k, v]) => [k, String(v)])),
      android: { priority: 'high', notification: { channelId: 'reel_social' } },
    });
  } catch (e) {
    console.error('FCM error:', e.message);
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// POST /notify/reel  — Extended to handle repost + collab types
// ─────────────────────────────────────────────────────────────────────────────
router.post('/notify/reel', async (req, res) => {
  const { type, ownerUid, fromUid, fromName, fromPhoto,
          reelId, thumb, caption, collabId } = req.body;

  if (!type || !ownerUid || !fromUid) {
    return res.status(400).json({ error: 'Missing required fields' });
  }

  let title, body, notifType;

  switch (type) {
    // ── Existing types ────────────────────────────────────────────────────
    case 'like':
      title = `${fromName} liked your reel ❤️`;
      body  = 'Your reel got a new like';
      notifType = 'TYPE_LIKE';
      break;
    case 'comment':
      title = `${fromName} commented on your reel 💬`;
      body  = caption || 'New comment on your reel';
      notifType = 'TYPE_COMMENT';
      break;
    case 'duet':
      title = `${fromName} dueted your reel 🔀`;
      body  = 'Someone created a duet with your reel';
      notifType = 'TYPE_DUET';
      break;
    case 'stitch':
      title = `${fromName} stitched your reel ✂️`;
      body  = 'Someone stitched your reel';
      notifType = 'TYPE_STITCH';
      break;

    // ── NEW: Repost types ────────────────────────────────────────────────
    case 'repost':
      title = `${fromName} reposted your reel 🔁`;
      body  = caption ? `"${caption}"` : 'Someone shared your reel with their followers';
      notifType = 'TYPE_REPOST';
      break;
    case 'quote_repost':
      title = `${fromName} quoted your reel 💬`;
      body  = caption ? `"${caption}"` : 'Someone quoted your reel';
      notifType = 'TYPE_QUOTE_REPOST';
      break;
    case 'repost_to_story':
      title = `${fromName} added your reel to their Story 📖`;
      body  = 'Your reel was shared to their story';
      notifType = 'TYPE_REPOST_STORY';
      break;
    case 'repost_milestone':
      const count = req.body.count || 0;
      const badge = count >= 5000 ? '💎 Legend' : count >= 1000 ? '🔥 Viral'
                  : count >= 500  ? '🚀 Trending' : '⭐ Hot';
      title = `${badge} — Your reel hit ${count} reposts! 🎉`;
      body  = 'Your content is spreading fast!';
      notifType = 'TYPE_REPOST_MILESTONE';
      break;

    // ── NEW: Collab types ────────────────────────────────────────────────
    case 'collab_invite':
      title = `${fromName} invited you to collab 🤝`;
      body  = 'Tap to accept or decline the collab invite';
      notifType = 'TYPE_COLLAB_INVITE';
      break;
    case 'collab_accepted':
      title = `${fromName} accepted your collab! 🎉`;
      body  = 'The reel now shows on both profiles';
      notifType = 'TYPE_COLLAB_ACCEPTED';
      break;
    case 'collab_rejected':
      title = `${fromName} declined your collab invite`;
      body  = 'Your collab invite was not accepted';
      notifType = 'TYPE_COLLAB_REJECTED';
      break;
    case 'live_collab_invite':
      title = `${fromName} wants to go LIVE with you! 🔴`;
      body  = 'Tap to join the live collab';
      notifType = 'TYPE_LIVE_COLLAB_INVITE';
      break;

    default:
      return res.status(400).json({ error: `Unknown type: ${type}` });
  }

  try {
    const token = await getFcmToken(ownerUid);
    await sendPush(token, title, body, {
      type:       notifType,
      reel_id:    reelId   || '',
      sender_uid: fromUid,
      sender_name:fromName || '',
      sender_photo:fromPhoto || '',
      thumb:      thumb    || '',
      caption:    caption  || '',
      collab_id:  collabId || '',
    });
    res.json({ ok: true });
  } catch (e) {
    console.error(e);
    res.status(500).json({ error: e.message });
  }
});

// ─────────────────────────────────────────────────────────────────────────────
// POST /repost/schedule — Schedule a repost for future time
// ─────────────────────────────────────────────────────────────────────────────
router.post('/repost/schedule', async (req, res) => {
  const { reelId, ownerUid, fromUid, caption, type, scheduledAt } = req.body;
  if (!reelId || !fromUid || !scheduledAt) {
    return res.status(400).json({ error: 'Missing fields' });
  }

  // Store scheduled repost in Firebase; a Cloud Scheduler or cron will process it
  const key = db.ref('scheduledReposts').push().key;
  await db.ref(`scheduledReposts/${key}`).set({
    reelId, ownerUid, fromUid, caption: caption || '',
    type: type || 'simple', scheduledAt,
    status: 'pending', createdAt: Date.now(),
  });

  res.json({ ok: true, jobId: key });
});

// ─────────────────────────────────────────────────────────────────────────────
// POST /repost/scheduled/process — Called by Cloud Scheduler every 5 min
// Processes due scheduled reposts
// ─────────────────────────────────────────────────────────────────────────────
router.post('/repost/scheduled/process', async (req, res) => {
  const now = Date.now();
  const snap = await db.ref('scheduledReposts')
    .orderByChild('scheduledAt').endAt(now).get();

  if (!snap.exists()) return res.json({ processed: 0 });

  let count = 0;
  const updates = {};

  snap.forEach(child => {
    const job = child.val();
    if (job.status !== 'pending') return;

    // Mark as processing
    updates[`scheduledReposts/${child.key}/status`] = 'processing';
    count++;
  });

  await db.ref().update(updates);

  // Process each due job
  for (const child of Object.keys(updates).map(k => k.replace('scheduledReposts/', '').replace('/status', ''))) {
    const jobSnap = await db.ref(`scheduledReposts/${child}`).get();
    const job = jobSnap.val();

    // Increment repostCount
    await db.ref(`reels/${job.reelId}/repostCount`).set(admin.database.ServerValue.increment(1));

    // Write repost entry
    const repostKey = db.ref(`reelReposts/${job.reelId}`).push().key;
    await db.ref(`reelReposts/${job.reelId}/${repostKey}`).set({
      repostId: repostKey, reelId: job.reelId,
      reposterId: job.fromUid, caption: job.caption,
      repostType: job.type, timestamp: Date.now(),
    });

    await db.ref(`scheduledReposts/${child}/status`).set('done');
  }

  res.json({ processed: count });
});

// ─────────────────────────────────────────────────────────────────────────────
// POST /ai/repost-caption — AI caption suggestion (server-side)
// Calls Gemini/OpenAI — replace API_KEY with your key or use Replit Secrets
// ─────────────────────────────────────────────────────────────────────────────
router.post('/ai/repost-caption', async (req, res) => {
  const { reelId, ownerName } = req.body;

  // Template-based fallback (no API key needed)
  const templates = [
    `This is 🔥 — via @${ownerName || 'creator'}`,
    `Can't stop watching this 👏`,
    `We all needed to see this`,
    `Drop everything 👇`,
    `Tag someone who needs this`,
  ];
  const caption = templates[Math.floor(Math.random() * templates.length)];

  // To use Gemini: uncomment and add GEMINI_API_KEY to Replit Secrets
  // const { GoogleGenerativeAI } = require('@google/generative-ai');
  // const genai = new GoogleGenerativeAI(process.env.GEMINI_API_KEY);
  // const model = genai.getGenerativeModel({ model: 'gemini-pro' });
  // const result = await model.generateContent(`Suggest a short repost caption for a viral reel by ${ownerName}`);
  // const caption = result.response.text();

  res.json({ caption });
});

// ─────────────────────────────────────────────────────────────────────────────
// GET /repost/analytics/:reelId — Repost analytics for a reel
// ─────────────────────────────────────────────────────────────────────────────
router.get('/repost/analytics/:reelId', async (req, res) => {
  const { reelId } = req.params;
  const snap = await db.ref(`reelReposts/${reelId}`).get();

  if (!snap.exists()) return res.json({ total: 0, simple: 0, quote: 0, story: 0 });

  let total = 0, simple = 0, quote = 0, story = 0;
  snap.forEach(child => {
    total++;
    const type = child.val().repostType;
    if (type === 'quote') quote++;
    else if (type === 'story') story++;
    else simple++;
  });

  res.json({ total, simple, quote, story,
    badge: total >= 5000 ? 'legend' : total >= 1000 ? 'viral'
         : total >= 500  ? 'trending' : total >= 100 ? 'hot' : null });
});

module.exports = router;

// ─────────────────────────────────────────────────────────────────────────────
// INTEGRATION: In your main index.js, add:
//   const collabRepostRoutes = require('./collab_repost_endpoints');
//   app.use('/', collabRepostRoutes);
// ─────────────────────────────────────────────────────────────────────────────
