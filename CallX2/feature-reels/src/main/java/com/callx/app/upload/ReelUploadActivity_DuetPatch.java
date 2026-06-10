// ═══════════════════════════════════════════════════════════════════════════
// ReelUploadActivity — FIX 7 PATCH NOTES
// ═══════════════════════════════════════════════════════════════════════════
//
// STATUS: ✅ ALREADY CORRECTLY IMPLEMENTED in the original file.
//
// Verified that saveReelToFirebase() (line 555) already:
//   ✅ Sets reel.duetOf           = duetOriginalId
//   ✅ Sets reel.duetOfOwnerUid   = duetOwnerUid
//   ✅ Sets reel.duetOriginalUrl  = duetOriginalUrl
//   ✅ Increments duetCount on original reel after publish
//   ✅ Fires DuetNotificationWorker ONLY after confirmed Firebase save
//
// FIX 7 ADDITION — add these two new duet fields to the save block
// In saveReelToFirebase(), inside the "if (a.isDuet)" block, add:
//
//   reel.chainDuetRootId = intent.getStringExtra("chain_duet_root_id");   // Fix 10
//   reel.chainDuetDepth  = intent.getIntExtra("chain_duet_depth", 0);     // Fix 10
//   reel.duetOriginalSoundUrl = intent.getStringExtra("duet_original_sound_url"); // Fix 4
//
// These are passed from DuetReelActivity v28 → ReelEditorActivity → ReelUploadActivity.
// ReelEditorActivity already forwards all "extra" intent extras via pass-through.
//
// ReelUploadActivity.handleEditorExtras() must read and store them:
//   private String chainDuetRootId = null;
//   private int    chainDuetDepth  = 0;
//   private String duetOriginalSoundUrl = null;
//
// Then in saveReelToFirebase() inside the isDuet block:
//   if (chainDuetRootId != null && !chainDuetRootId.isEmpty()) {
//       reel.chainDuetRootId = chainDuetRootId;
//       reel.chainDuetDepth  = chainDuetDepth;
//   }
//   if (duetOriginalSoundUrl != null && !duetOriginalSoundUrl.isEmpty()) {
//       reel.duetOriginalSoundUrl = duetOriginalSoundUrl;
//   }
//
// See full implementation in ReelUploadActivity_Fix7_Full.java (companion file)
// ═══════════════════════════════════════════════════════════════════════════
