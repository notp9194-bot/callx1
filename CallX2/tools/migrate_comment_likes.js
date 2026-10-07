// One-off, OPTIONAL: moves legacy comment/reply `likedBy` maps into the v471 layout.
//   userCommentLikes/{uid}/{reelId}/{itemId} = true   +   creatorLiked on the item
// Until you run it, old comments keep working (the app reads legacy likedBy once and drops it),
// they just still download their old likedBy map. New likes never write likedBy again.
//
// Usage:  npm i firebase-admin
//         GOOGLE_APPLICATION_CREDENTIALS=key.json DB_URL=https://<db>.firebaseio.com node migrate_comment_likes.js [--apply]
// Default is a DRY RUN. UNTESTED against your data - run on a staging copy / export a backup first.
const admin = require('firebase-admin');
admin.initializeApp({ credential: admin.credential.applicationDefault(), databaseURL: process.env.DB_URL });
const db = admin.database();
const APPLY = process.argv.includes('--apply');

async function run() {
  const reels = (await db.ref('reelComments').once('value'));
  let moved = 0;
  const up = {};
  const flush = async () => {
    if (APPLY && Object.keys(up).length) { await db.ref().update(up); }
    Object.keys(up).forEach(k => delete up[k]);
  };
  const handle = async (basePath, reelId, itemId, item, ownerUid) => {
    const lb = item && item.likedBy;
    if (!lb) return;
    for (const uid of Object.keys(lb)) {
      if (lb[uid] !== true) continue;
      up[`userCommentLikes/${uid}/${reelId}/${itemId}`] = true;
      if (uid === ownerUid) up[`${basePath}/creatorLiked`] = true;
      moved++;
    }
    up[`${basePath}/likedBy`] = null;
    if (Object.keys(up).length > 400) await flush();
  };
  for (const [reelId, comments] of Object.entries(reels.val() || {})) {
    const ownerUid = (await db.ref(`reels/${reelId}/uid`).once('value')).val();
    for (const [cid, c] of Object.entries(comments)) {
      await handle(`reelComments/${reelId}/${cid}`, reelId, cid, c, ownerUid);
      const rs = (await db.ref(`reelCommentReplies/${reelId}/${cid}`).once('value')).val() || {};
      for (const [rid, r] of Object.entries(rs)) {
        await handle(`reelCommentReplies/${reelId}/${cid}/${rid}`, reelId, rid, r, ownerUid);
      }
    }
  }
  await flush();
  console.log(`${APPLY ? 'moved' : 'would move'} ${moved} likes`);
  process.exit(0);
}
run().catch(e => { console.error(e); process.exit(1); });
