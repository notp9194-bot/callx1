# v250 — Reel Upload: Single Screen → Priority-Ordered Step Wizard

## Problem
`ReelUploadActivity` (the Reels post/upload screen) had every option crammed
onto one long scrolling screen: media picker, quality, caption, music,
audience, duet/stitch permissions, series, tag people, location,
collaborators, product tag, privacy settings, schedule, save draft, and post
— all at once.

## Fix
Split the screen into a 4-step wizard, ordered by priority (what you need to
decide first → last), with **Back / Next** navigation and a step indicator
("Step 2 of 4 · Caption & Sound") at the top:

| Step | Screen | Contains |
|---|---|---|
| 1 | **Media** | Video/Photo toggle, pick + preview, compression, photo slideshow settings, video quality |
| 2 | **Caption & Sound** | Caption text, music/audio name |
| 3 | **Audience & Permissions** | Audience (Everyone/Contacts), who can Duet, who can Stitch, Duet Series |
| 4 | **Details & Post** | Tag People, Location, Collaborators, Product Tag, Privacy Settings, Schedule, Save Draft, and the final **Post Reel** button + upload progress |

Buttons that already opened their own dedicated screens (Tag People,
Location, Product Tag, Privacy Settings, Schedule) are unchanged — they still
launch `ReelTagPeopleActivity`, `ReelLocationTagActivity`,
`ReelProductTagActivity`, `ReelPrivacySettingsActivity`,
`ReelSchedulerActivity` exactly as before.

## How it's implemented (low-risk, no logic rewritten)
- `activity_reel_upload.xml`: the single content `LinearLayout` was split into
  a `ViewFlipper` (`@id/step_flipper`) with 4 child `LinearLayout`s
  (`step_media`, `step_caption`, `step_privacy`, `step_details`). **Every
  existing view ID is untouched** — same IDs, same order, same attributes —
  only regrouped under the new step containers. A fixed step-indicator header
  and a fixed bottom Back/Next bar were added around it.
- `ReelUploadActivity.java`: added `setupStepWizard()` + `goToStep()` +
  `updateStepUi()` + `canLeaveMediaStep()` + an `onBackPressed()` override
  that steps backward through the wizard before exiting. **No existing
  field, click-listener, compression/upload/Firebase/Cloudinary logic was
  modified** — the wizard purely controls which step is visible.
- Step 1 → 2 validates a video/photos are selected before advancing.
  Steps 2 and 3 have no required fields (caption/audience have safe
  defaults), matching the original screen's behavior.
- Step 4's own **Post Reel** / **Save as Draft** buttons behave exactly as
  before — the wizard's shared "Next" button is hidden on the last step.

## Testing note
This was edited without an Android SDK / Gradle environment available in
this workspace, so it hasn't been compiled locally. Every referenced
`R.id.*` was diffed against the layout to confirm nothing was dropped, and
the XML/Java were validated for well-formedness and brace/paren balance —
but please do a build + a manual run through Steps 1→4 (including the photo
slideshow path) in Android Studio before shipping.
