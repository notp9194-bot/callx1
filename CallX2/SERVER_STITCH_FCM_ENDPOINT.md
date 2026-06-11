# CallX2 — Server-Side Stitch FCM Endpoint (Fix v9)

  > **File:** `callx2_app/SERVER_STITCH_FCM_ENDPOINT.md`
  > This guide describes the backend endpoint your server must expose to fire
  > an FCM push notification when a user "stitches" (forks + records over) a reel.

  ---

  ## Why this is needed

  When User B records a stitch on User A's reel the app:

  1. Uploads the composite to Firebase Storage.
  2. Writes the new `reel` document to Firestore with `isStitch=true` and
     `stitchOriginalId = A.reelId`.
  3. **Calls this endpoint** so User A gets a push: "Someone stitched your reel 🎬".

  Without the endpoint the notification is never sent.

  ---

  ## Endpoint spec

  ```
  POST /api/reels/stitch/notify
  Content-Type: application/json
  Authorization: Bearer <FIREBASE_ID_TOKEN>   // verified server-side
  ```

  ### Request body

  ```json
  {
    "stitchReelId":       "string",   // new reel ID (User B's output)
    "originalReelId":     "string",   // the reel that was stitched
    "stitcherUid":        "string",   // User B's Firebase UID
    "stitcherUsername":   "string",   // "@handle" of User B
    "thumbnailUrl":       "string"    // CDN URL of composite thumbnail
  }
  ```

  ### Success response — 200

  ```json
  { "sent": true, "messageId": "<FCM message ID>" }
  ```

  ### Error responses

  | Code | Meaning                                          |
  |------|--------------------------------------------------|
  | 400  | Missing required field                           |
  | 401  | Token missing or invalid                         |
  | 403  | `stitcherUid` does not match token subject      |
  | 404  | `originalReelId` not found in Firestore         |
  | 500  | FCM send error (details in body)                 |

  ---

  ## Reference implementation (Node.js / Firebase Admin SDK)

  ```js
  // functions/src/stitchNotify.js  (Express-compatible, deploy as Cloud Function)
  const admin = require("firebase-admin");

  admin.initializeApp(); // uses GOOGLE_APPLICATION_CREDENTIALS or emulator

  const db  = admin.firestore();
  const fcm = admin.messaging();

  async function stitchNotifyHandler(req, res) {
    // 1. Verify caller identity
    const auth   = req.headers.authorization || "";
    const token  = auth.startsWith("Bearer ") ? auth.slice(7) : null;
    if (!token) return res.status(401).json({ error: "Missing token" });

    let decoded;
    try { decoded = await admin.auth().verifyIdToken(token); }
    catch (e) { return res.status(401).json({ error: "Invalid token" }); }

    const { stitchReelId, originalReelId, stitcherUid,
            stitcherUsername, thumbnailUrl } = req.body;

    if (!stitchReelId || !originalReelId || !stitcherUid || !stitcherUsername)
      return res.status(400).json({ error: "Missing required fields" });

    if (decoded.uid !== stitcherUid)
      return res.status(403).json({ error: "UID mismatch" });

    // 2. Look up the original reel to get owner FCM token
    const origDoc = await db.collection("reels").doc(originalReelId).get();
    if (!origDoc.exists)
      return res.status(404).json({ error: "Original reel not found" });

    const { ownerUid } = origDoc.data();
    if (!ownerUid) return res.status(404).json({ error: "ownerUid missing" });

    const userDoc = await db.collection("users").doc(ownerUid).get();
    const fcmToken = userDoc.data()?.fcmToken;
    if (!fcmToken) return res.status(200).json({ sent: false, reason: "no FCM token" });

    // 3. Send the notification
    const message = {
      token: fcmToken,
      notification: {
        title: "Your reel got stitched 🎬",
        body:  `@${stitcherUsername} stitched your reel`,
      },
      data: {
        type:          "stitch_notification",
        stitchReelId,
        originalReelId,
        stitcherUid,
        stitcherUsername,
        thumbnailUrl:  thumbnailUrl || "",
        click_action:  "OPEN_REEL",          // handled in your MainActivity
      },
      android: {
        priority: "high",
        notification: {
          channelId: "social",               // must exist in app (NotificationChannel)
          imageUrl:  thumbnailUrl || undefined,
          clickAction: "OPEN_REEL",
        },
      },
    };

    try {
      const messageId = await fcm.send(message);
      return res.status(200).json({ sent: true, messageId });
    } catch (e) {
      console.error("FCM error:", e);
      return res.status(500).json({ error: e.message });
    }
  }

  module.exports = { stitchNotifyHandler };
  ```

  ---

  ## Android client call  (ReelUploadActivity.java)

  After the reel document is committed to Firestore, fire the stitch notification:

  ```java
  private void notifyStitch(String newReelId) {
      if (!isStitch || stitchOriginalId == null) return;

      FirebaseUser me = FirebaseAuth.getInstance().getCurrentUser();
      if (me == null) return;

      me.getIdToken(false).addOnSuccessListener(res -> {
          OkHttpClient client = new OkHttpClient();
          JSONObject body = new JSONObject();
          try {
              body.put("stitchReelId",     newReelId);
              body.put("originalReelId",   stitchOriginalId);
              body.put("stitcherUid",      me.getUid());
              body.put("stitcherUsername", localUsername);
              body.put("thumbnailUrl",     uploadedThumbnailUrl != null ? uploadedThumbnailUrl : "");
          } catch (JSONException e) { return; }

          Request req = new Request.Builder()
              .url(BuildConfig.API_BASE_URL + "/api/reels/stitch/notify")
              .post(okhttp3.RequestBody.create(body.toString(),
                  okhttp3.MediaType.parse("application/json")))
              .addHeader("Authorization", "Bearer " + res.getToken())
              .build();

          client.newCall(req).enqueue(new okhttp3.Callback() {
              @Override public void onFailure(okhttp3.Call call, IOException e) {
                  Log.w(TAG, "Stitch notify failed: " + e.getMessage());
              }
              @Override public void onResponse(okhttp3.Call call, okhttp3.Response r) {
                  Log.d(TAG, "Stitch notify: " + r.code());
              }
          });
      });
  }
  ```

  Call `notifyStitch(reel.reelId)` at the end of `saveReelToFirebase()` success block.

  ---

  ## Security rules (Firestore)

  No changes needed — the `reels` collection is read by the server using the
  Admin SDK which bypasses client-side rules.

  If you deploy as a **Firebase Callable** instead of an HTTP endpoint, remove
  the manual token verification and replace with the Callable SDK's built-in
  auth context.

  ---

  ## Firestore document fields referenced

  | Collection | Field        | Type   | Notes                               |
  |------------|--------------|--------|-------------------------------------|
  | reels      | ownerUid     | string | set when reel is first uploaded     |
  | reels      | duetRootId   | string | set by DuetReelActivity (v9 fix)    |
  | users      | fcmToken     | string | updated by app on every cold start  |

  ---

  *Last updated: v9 — CallX2 Duet/Stitch FCM guide.*
  