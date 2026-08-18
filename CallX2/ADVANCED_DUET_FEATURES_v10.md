# CallX2 — Advanced Duet Features v10

  ## 7 New Features Added

  ### 🔥 Feature 1: Duet Invite / Request
  **Files:** `DuetInviteActivity.java`, `activity_duet_invite.xml`, `item_duet_invite_user.xml`

  Kisi specific follower ko duet karne ka invite bhej sako.
  - Searchable follower list
  - Firebase: `duet_invites/{targetUid}/{inviteId}`
  - Target user ko notification milti hai
  - Invite pe tap karte hi DuetReelActivity directly open hoti hai

  **Integration:** ReelPlayerFragment ke "Duet" button ke paas "Invite" button add karo

  ---

  ### 🔥 Feature 2: Duet Approval Queue
  **Files:** `DuetApprovalQueueActivity.java`, `activity_duet_approval_queue.xml`, `item_duet_approval.xml`

  Creator har duet ko publish hone se pehle approve/reject kar sakta hai.
  - Firebase: `duet_pending/{ownerUid}/{duetReelId}`
  - Setting: `reels/{reelId}/duetApprovalRequired = true` (ReelRemixSettingsActivity mein toggle add karo)
  - Approve: reel public hoti hai + duetCount badhta hai
  - Reject: reel delete hoti hai + duetor ko notification

  **Integration:** ReelRemixSettingsActivity mein "Require approval" toggle add karo

  ---

  ### 🔥 Feature 3: Duet Battle (Voting)
  **Files:** `DuetBattleActivity.java`, `DuetBattleCreateActivity.java`, `activity_duet_battle.xml`, `activity_duet_battle_create.xml`

  Do duet videos ek doosre se compete karein — viewers vote dein.
  - Firebase: `duet_battles/{battleId}`, `duet_battle_votes/{battleId}/{voterUid}`
  - Real-time vote bars animate hote hain
  - Battle duration: 24h / 48h / 72h
  - Winner announcement on battle end
  - Ek user ek hi vote de sakta hai

  **Integration:** DuetsByReelActivity mein duet grid ke item pe long-press = "Challenge to Battle"

  ---

  ### 🔥 Feature 4: Beat Sync Hints
  **Files:** `BeatSyncAnalyzer.java`, `BeatSyncOverlayView.java`

  Original reel ke audio se beats detect karo, recording screen pe beat markers dikhao.
  - Pure MediaCodec — koi FFmpeg nahi
  - RMS onset detection, 300ms minimum beat gap
  - Gold timeline bar with pulse ring animation on each beat
  - Haptic feedback on beat (caller sets OnBeatListener)

  **Integration in DuetReelActivity:**
  ```java
  // After ExoPlayer is prepared:
  BeatSyncAnalyzer.analyze(this, cachedVideoPath, durationMs, new BeatSyncAnalyzer.Callback() {
      public void onBeatsReady(long[] beats) {
          runOnUiThread(() -> beatOverlay.setBeats(beats, durationMs));
      }
      public void onError(Exception e) {}
  });

  // In Handler (every 100ms):
  beatOverlay.setPosition(exoPlayer.getCurrentPosition());
  ```

  ---

  ### ⚡ Feature 5: Multi-Person Duet (up to 4 people)
  **Files:** `MultiDuetActivity.java`, `activity_multi_duet.xml`, `item_multi_duet_slot.xml`

  3-4 log ek saath duet karein — final video 2×2 grid mein composite hoti hai.
  - Firebase: `multi_duet_sessions/{sessionId}`
  - Host up to 3 followers invite kar sakta hai
  - Har participant apna recording karla DuetReelActivity mein
  - DuetVideoCompositor (4-way grid) sab clips stitch karta hai
  - Real-time WebRTC co-recording future enhancement hai

  **Integration:** DuetReelActivity ke layout selector mein "Multi 👥" button add karo

  ---

  ### ⚡ Feature 6: Duet Challenge + Leaderboard
  **Files:** `DuetChallengeCreateActivity.java`, `DuetChallengeLeaderboardActivity.java`, `activity_duet_challenge_create.xml`, `activity_duet_challenge_leaderboard.xml`, `item_challenge_leaderboard.xml`

  Creator ek named challenge launch kare jisme sab log participate karein.
  - Firebase: `duet_challenges/{challengeId}`
  - Hashtag-based entry tracking
  - Leaderboard: top 50 entries likeCount se rank hoti hain
  - 🥇🥈🥉 podium badges
  - Duration: 3 / 7 / 14 / 30 days

  **Integration:**
  - ReelPlayerFragment ke "More" menu mein "Create Challenge" add karo
  - HashtagReelsActivity mein challenge leaderboard tab add karo

  ---

  ### ⚡ Feature 7: Duet Tree Visualization
  **Files:** `DuetTreeActivity.java`, `DuetTreeView.java`, `activity_duet_tree.xml`

  Duet chain ka visual tree dekho — kaun kisne duet kiya.
  - Radial layout: root centre mein, children outward
  - 4 levels deep load (duet of duet of duet of duet)
  - Pinch-to-zoom + pan (ScaleGestureDetector + GestureDetector)
  - Thumbnail circles (async Glide load)
  - Tap karo kisi circle pe → reel khul jaati hai

  **Integration:** DuetsByReelActivity ke title bar mein "🌳 Tree" button add karo

  ---

  ## Firebase Indexes Required

  ```json
  {
    "rules": {
      "reels": {
        ".indexOn": ["duetOf", "duetRootId", "stitchOf", "challengeId"]
      },
      "duet_battles": {
        ".indexOn": ["status", "originalReelId"]
      },
      "duet_challenges": {
        ".indexOn": ["status", "hostUid"]
      },
      "duet_pending": {
        ".indexOn": ["originalReelId"]
      }
    }
  }
  ```

  ## Manifest Registration

  Add these to `feature-reels/src/main/AndroidManifest.xml`:
  ```xml
  <activity android:name=".social.DuetInviteActivity" android:screenOrientation="portrait"/>
  <activity android:name=".social.DuetApprovalQueueActivity" android:screenOrientation="portrait"/>
  <activity android:name=".social.DuetBattleActivity" android:screenOrientation="portrait"/>
  <activity android:name=".social.DuetBattleCreateActivity" android:screenOrientation="portrait"/>
  <activity android:name=".social.MultiDuetActivity" android:screenOrientation="portrait"/>
  <activity android:name=".social.DuetChallengeCreateActivity" android:screenOrientation="portrait"/>
  <activity android:name=".social.DuetChallengeLeaderboardActivity" android:screenOrientation="portrait"/>
  <activity android:name=".social.DuetTreeActivity" android:screenOrientation="portrait"/>
  ```
  