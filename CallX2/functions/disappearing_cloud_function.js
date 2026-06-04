/**
 * Firebase Cloud Function: disappearingMessageCleaner
 *
 * Runs every 5 minutes via Cloud Scheduler.
 * Scans all chats for messages where expiresAt < now, then tombstones them.
 *
 * This guarantees deletion even if:
 *   - The user's phone was offline when the timer expired
 *   - The app crashed before local deletion ran
 *   - The sender deleted the app (receiver still needs message gone)
 *
 * Deploy:
 *   cd functions
 *   npm install
 *   firebase deploy --only functions:disappearingMessageCleaner
 *
 * Required: firebase-functions v4+, firebase-admin v11+
 *
 * NOTE: This uses Realtime Database (not Firestore).
 *       If you're on Firestore, replace admin.database() with admin.firestore()
 *       and adjust query syntax accordingly.
 */

const functions = require('firebase-functions/v2');
const { onSchedule } = require('firebase-functions/v2/scheduler');
const admin = require('firebase-admin');

if (!admin.apps.length) admin.initializeApp();

const db = admin.database();

// ─── Main scheduled cleaner ──────────────────────────────────────────────────

exports.disappearingMessageCleaner = onSchedule(
  { schedule: 'every 5 minutes', timeZone: 'UTC', memory: '256MiB' },
  async (event) => {
    const now = Date.now();
    console.log(`[disappearingCleaner] Running at ${new Date(now).toISOString()}`);

    try {
      // Get all chats that have a disappearingTimer set
      const chatsSnap = await db.ref('chats').once('value');
      if (!chatsSnap.exists()) { console.log('No chats found'); return; }

      const batch = {};  // Firebase multi-path update
      let deletedCount = 0;

      chatsSnap.forEach(chatSnap => {
        const chatId = chatSnap.key;
        const timer  = chatSnap.child('disappearingTimer').val();
        if (!timer) return;  // No timer set for this chat

        chatSnap.child('messages').forEach(msgSnap => {
          const msg = msgSnap.val();
          if (!msg || msg.deleted || msg.type === 'system') return;

          const expiresAt = msg.expiresAt;
          if (!expiresAt) return;  // Message hasn't been delivered yet

          if (expiresAt <= now) {
            // EXPIRED — tombstone it
            const path = `chats/${chatId}/messages/${msgSnap.key}`;
            batch[`${path}/deleted`]      = true;
            batch[`${path}/text`]         = null;
            batch[`${path}/imageUrl`]     = null;
            batch[`${path}/imageUrls`]    = null;
            batch[`${path}/mediaUrl`]     = null;
            batch[`${path}/thumbnailUrl`] = null;
            batch[`${path}/latitude`]     = null;
            batch[`${path}/longitude`]    = null;
            deletedCount++;
          }
        });
      });

      if (deletedCount > 0) {
        await db.ref().update(batch);
        console.log(`[disappearingCleaner] Tombstoned ${deletedCount} expired messages`);
      } else {
        console.log('[disappearingCleaner] No expired messages found');
      }

    } catch (err) {
      console.error('[disappearingCleaner] Error:', err);
    }
  }
);

// ─── onMessageRead trigger ────────────────────────────────────────────────────

/**
 * When a message status changes to "read", set expiresAt = now + disappearingTimer.
 * This starts the countdown from the moment it's read (not sent).
 *
 * Triggered: when message.status is written as "read"
 */
exports.onMessageRead = functions.database.onValueUpdated(
  { ref: 'chats/{chatId}/messages/{msgId}/status' },
  async (event) => {
    const newStatus = event.data.after.val();
    if (newStatus !== 'read') return;  // Only care about "read"

    const { chatId, msgId } = event.params;

    try {
      // Get chat's disappearing timer
      const timerSnap = await db.ref(`chats/${chatId}/disappearingTimer`).once('value');
      const timer = timerSnap.val();
      if (!timer || timer <= 0) return;  // No timer set

      // Check message doesn't already have expiresAt
      const msgSnap = await db.ref(`chats/${chatId}/messages/${msgId}`).once('value');
      const msg = msgSnap.val();
      if (!msg || msg.expiresAt || msg.deleted) return;

      // Set expiresAt
      const expiresAt = Date.now() + timer;
      await db.ref(`chats/${chatId}/messages/${msgId}/expiresAt`).set(expiresAt);

      console.log(`[onMessageRead] Set expiresAt=${new Date(expiresAt).toISOString()} for ${msgId}`);
    } catch (err) {
      console.error('[onMessageRead] Error:', err);
    }
  }
);

// ─── package.json (functions/package.json) ───────────────────────────────────

/*
{
  "name": "callx-functions",
  "version": "1.0.0",
  "engines": { "node": "20" },
  "dependencies": {
    "firebase-admin":     "^11.11.0",
    "firebase-functions": "^4.5.0"
  }
}
*/

// ─── firebase.json (add to existing) ─────────────────────────────────────────

/*
{
  "functions": [{
    "source": "functions",
    "codebase": "default",
    "ignore": ["node_modules", "**/.git"]
  }]
}
*/
