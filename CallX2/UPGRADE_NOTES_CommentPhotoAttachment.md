# Reel Comments — Photo Attachment (Instagram-style)

Adds the ability to attach a single photo to a top-level reel comment,
matching Instagram's comment-photo behaviour.

## What changed

**Model**
- `core/.../models/ReelComment.java` — new `imageUrl` field (Cloudinary
  `secure_url`, null/empty for text-only comments).

**Input bar** (`feature-reels/.../res/layout/activity_reel_comment.xml`)
- New gallery button (`btn_attach_photo`) next to the comment field.
- New preview strip (`layout_comment_image_preview`) shown above the input
  once a photo is picked — thumbnail, remove (✕) button, upload spinner.

**Sending** (`ReelCommentFragment.java`)
- Tapping the gallery button launches `ActivityResultContracts.GetContent()`
  (`image/*`).
- The picked image previews immediately and uploads in the background via
  the existing `CloudinaryUploader.upload(ctx, uri, "callx/reel_comments",
  "image", …)` (same signed-upload pipeline used elsewhere in the app —
  images are auto-compressed before upload).
- Send is blocked with a toast while the upload is still in flight.
- A comment can now be photo-only (no text required) as long as a photo is
  attached — text-only and text+photo both still work as before.
- Photo attachment is intentionally **top-level comments only**: the
  attach button hides automatically while replying to a comment (keeps the
  reply thread lightweight, matches most IG-clone implementations).

**Display**
- `item_reel_comment.xml` — new `iv_comment_image` (150×150dp, rounded,
  hidden unless `imageUrl` is set).
- `ReelCommentsAdapter.java` — binds the photo with Glide; tapping it opens
  the existing full-screen pinch-zoom viewer
  (`DialogFullscreenHelper.showAvatarZoom`, already used for avatar zoom
  elsewhere in the app — no new viewer code needed). `imageUrl` was added
  to the `AsyncListDiffer` content-equality check so a late-arriving
  upload updates the row without a full rebind.

## Not in scope (by design)
- Reply photos (Instagram doesn't support these either).
- Multi-photo comments — one photo per comment, same as Instagram.
- Comment editing does not currently let you change/remove an already-sent
  photo — only the text is editable (existing edit dialog behaviour).

## Firebase
No new Firebase rules are required — `imageUrl` is just an extra string
field on the existing `reelComments/{reelId}/{commentId}` node.
