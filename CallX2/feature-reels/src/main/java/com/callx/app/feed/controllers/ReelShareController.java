package com.callx.app.feed.controllers;

import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.io.File;
import android.os.Environment;
import android.view.View;
import android.widget.Toast;
import com.callx.app.comments.ReelCommentActivity;
import com.callx.app.comments.ReelCommentSheetFragment;
import com.callx.app.comments.ReelLikesBottomSheet;
import com.callx.app.models.ReelModel;
import com.callx.app.social.ReelMoreBottomSheet;
import com.callx.app.social.ReelShareSheetFragment;
import com.callx.app.social.ReelSharesBottomSheet;

/**
 * Manages share, download, copy link, more-sheet display, and dispatches
 * all onMoreItemClick actions to the appropriate peer controller via the delegate.
 */
public class ReelShareController {

    private final ReelPlayerDelegate delegate;

    public ReelShareController(ReelPlayerDelegate delegate) {
        this.delegate = delegate;
    }

    // ── Share ─────────────────────────────────────────────────────────────

    public void shareReel() {
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.reelId == null || !delegate.isAdded() || delegate.getActivity() == null) return;
        ReelShareSheetFragment sheet = ReelShareSheetFragment.newInstance(
            reel.reelId, reel.videoUrl, reel.effectiveThumbUrl(), reel.caption,
            reel.uid, reel.ownerName, reel.ownerPhoto, reel.allowReposts);
        delegate.showBottomSheet(sheet, "share_sheet");
    }

    // ── Download ──────────────────────────────────────────────────────────

    /**
     * ✅ FIX: text overlays now ride as a live in-app layer (see ReelPlayerFragment /
     * ReelTextOverlayRenderer) instead of being burned into reel.videoUrl's pixels —
     * that's what keeps them sharp in-app, but it also means the raw remote file this
     * used to hand straight to DownloadManager has NO text on it at all. A file that
     * leaves the app (saved to Gallery, then shared to WhatsApp etc.) can't render
     * that live layer, so text has to be baked in once here, on the way out — the
     * same "final bake for export" step Instagram does. Downloads a local copy of
     * the video first (DownloadManager can't be post-processed), bakes any text
     * overlays into it with ReelVideoExportEngine, then saves the result to the
     * gallery. Falls back to the plain (text-less) file if either step fails,
     * rather than losing the download entirely.
     */
    public void downloadReel() {
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.videoUrl == null || !delegate.isAdded() || delegate.getContext() == null) return;
        Context appCtx = delegate.requireContext().getApplicationContext();
        String reelId = reel.reelId != null ? reel.reelId : String.valueOf(System.currentTimeMillis());
        String videoUrl = reel.videoUrl;
        String stickerJson = reel.stickerJson;

        Toast.makeText(delegate.requireContext(), "Preparing reel…", Toast.LENGTH_SHORT).show();
        android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

        new Thread(() -> {
            File downloaded;
            try {
                downloaded = downloadToCacheFile(appCtx, videoUrl, reelId);
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(appCtx,
                    "Download failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                return;
            }
            final File plainFile = downloaded;

            java.util.List<com.callx.app.editor.ReelVideoExportEngine.OverlayItem> textOverlays =
                com.callx.app.editor.ReelVideoExportEngine.parseTextOnlyOverlays(stickerJson);

            if (textOverlays.isEmpty()) {
                saveDownloadedFileToGallery(appCtx, plainFile, reelId, mainHandler);
                return;
            }

            // Transformer needs a Looper thread — hop back to main to start the bake.
            mainHandler.post(() -> com.callx.app.editor.ReelVideoExportEngine.export(
                appCtx, plainFile.getAbsolutePath(), null, 0f, 1f, 1f, textOverlays,
                new com.callx.app.editor.ReelVideoExportEngine.ExportCallback() {
                    @Override public void onProgress(int percent) {}
                    @Override public void onSuccess(String outputPath) {
                        new Thread(() -> {
                            saveDownloadedFileToGallery(appCtx, new File(outputPath), reelId, mainHandler);
                            //noinspection ResultOfMethodCallIgnored
                            plainFile.delete();
                        }).start();
                    }
                    @Override public void onError(Exception e) {
                        // Text bake failed — still save the plain download rather than
                        // losing it entirely.
                        saveDownloadedFileToGallery(appCtx, plainFile, reelId, mainHandler);
                    }
                }));
        }).start();
    }

    /** Downloads {@code url} into the app's cache dir. Runs on a background thread. */
    private File downloadToCacheFile(Context appCtx, String url, String reelId) throws Exception {
        File outDir = new File(appCtx.getCacheDir(), "reel_downloads");
        if (!outDir.exists()) //noinspection ResultOfMethodCallIgnored
            outDir.mkdirs();
        File out = new File(outDir, "callx_reel_src_" + reelId + "_" + System.currentTimeMillis() + ".mp4");
        java.net.URL u = new java.net.URL(url);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        try (java.io.InputStream in = conn.getInputStream();
             java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        } finally {
            conn.disconnect();
        }
        return out;
    }

    /** Copies a finished local video file into the public Gallery/Movies. Runs on a
     *  background thread; posts the final toast back via {@code mainHandler}. */
    private void saveDownloadedFileToGallery(Context appCtx, File src, String reelId, android.os.Handler mainHandler) {
        try {
            String displayName = "callx_reel_" + reelId + ".mp4";
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, displayName);
                values.put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/CallX");
                Uri collection = android.provider.MediaStore.Video.Media.getContentUri(
                    android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri itemUri = appCtx.getContentResolver().insert(collection, values);
                if (itemUri == null) throw new java.io.IOException("MediaStore insert failed");
                try (java.io.OutputStream out = appCtx.getContentResolver().openOutputStream(itemUri);
                     java.io.FileInputStream in = new java.io.FileInputStream(src)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            } else {
                File moviesDir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_MOVIES), "CallX");
                if (!moviesDir.exists()) //noinspection ResultOfMethodCallIgnored
                    moviesDir.mkdirs();
                File dest = new File(moviesDir, displayName);
                try (java.io.FileInputStream in = new java.io.FileInputStream(src);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                android.media.MediaScannerConnection.scanFile(
                    appCtx, new String[]{dest.getAbsolutePath()}, null, null);
            }
            //noinspection ResultOfMethodCallIgnored
            src.delete();
            mainHandler.post(() -> Toast.makeText(appCtx, "Reel saved to Gallery", Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            mainHandler.post(() -> Toast.makeText(appCtx,
                "Save failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        }
    }

    // ── Copy link ─────────────────────────────────────────────────────────

    public void copyReelLink() {
        if (!delegate.isAdded() || delegate.getContext() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null) return;
        ClipboardManager cm = (ClipboardManager) delegate.requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Reel Link",
                com.callx.app.utils.Constants.DEEP_LINK_BASE_URL + "/reel/" + reel.reelId));
            Toast.makeText(delegate.requireContext(), "Link copied!", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Mark not interested ───────────────────────────────────────────────

    public void markNotInterested() {
        if (!delegate.isAdded() || delegate.getContext() == null) return;
        Toast.makeText(delegate.requireContext(),
            "Got it! You'll see less like this.", Toast.LENGTH_SHORT).show();
    }

    // ── Bottom sheets: comments / likes / shares ──────────────────────────

    public void openCommentsSheet() {
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.reelId == null || !delegate.isAdded() || delegate.getActivity() == null) return;
        ReelCommentSheetFragment sheet = ReelCommentSheetFragment.newInstance(
            reel.reelId, reel.uid != null ? reel.uid : "", reel.commentsCount);
        delegate.showBottomSheet(sheet, ReelCommentSheetFragment.TAG);
    }

    /**
     * Same comments sheet as openCommentsSheet(), but opened from tapping the
     * reel's caption/owner name text — the sheet renders with the caption +
     * owner row visible above the comment list, and (via the existing
     * ReelCommentSheetFragment video-dock chrome) the reel video docks
     * upward, matching Instagram's caption-tap behavior.
     *
     * PERF: reuses the already-formatted caption string from
     * ReelUiStateCache (the same cache ReelUiController's bind() reads —
     * see precomputedCaption there) when available, instead of
     * re-running ReelModel.safeCaption()'s truncation/sanitization work a
     * second time for a string the fragment already computed once when it
     * bound this reel. Falls back to computing it fresh only on a cache
     * miss (e.g. reel bound before the precompute window reached it).
     */
    public void openCommentsSheetWithCaption() {
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.reelId == null || !delegate.isAdded() || delegate.getActivity() == null) return;
        com.callx.app.cache.ReelUiStateCache.State cached =
            com.callx.app.cache.ReelUiStateCache.get(reel.reelId);
        String caption = (cached != null && cached.captionText != null)
            ? cached.captionText
            : com.callx.app.models.ReelModel.safeCaption(reel.caption != null ? reel.caption : "");
        ReelCommentSheetFragment sheet = ReelCommentSheetFragment.newInstance(
            reel.reelId, reel.uid != null ? reel.uid : "", reel.commentsCount,
            caption, reel.ownerName != null ? reel.ownerName : "",
            reel.ownerPhoto != null ? reel.ownerPhoto : "");
        delegate.showBottomSheet(sheet, ReelCommentSheetFragment.TAG);
    }

    public void openLikesSheet() {
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.reelId == null || !delegate.isAdded() || delegate.getActivity() == null) return;
        // FIX: was passing reel.likesCount/reel.viewsCount — a snapshot frozen
        // at feed-load time. If the count changed since (a like, someone
        // else's like) the sheet's header showed a stale number for a beat
        // while the likers list below it was already live. Pull the current
        // cached values ReelSocialController's Firebase listeners maintain
        // instead, so the header opens already correct — matching the
        // player screen's tvLikesCount, which reads from the same source.
        ReelLikesBottomSheet sheet = ReelLikesBottomSheet.newInstance(
            reel.reelId, delegate.getLastKnownLikeCount(), delegate.getLastKnownViewCount());
        delegate.showBottomSheet(sheet, ReelLikesBottomSheet.TAG);
    }

    public void openSharesSheet() {
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.reelId == null || !delegate.isAdded() || delegate.getActivity() == null) return;
        // FIX (same pattern as openLikesSheet): reel.sharesCount/repostCount
        // are a feed-load-time snapshot. Pull the live cached values instead
        // so the sheet header matches tvSharesCount/tvRepostCount on screen.
        ReelSharesBottomSheet sheet = ReelSharesBottomSheet.newInstance(
            reel.reelId, delegate.getLastKnownSharesCount(), delegate.getLastKnownRepostCount());
        delegate.showBottomSheet(sheet, ReelSharesBottomSheet.TAG);
    }

    public void openComments() {
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.reelId == null || !delegate.isAdded() || delegate.getContext() == null) return;
        try {
            Intent intent = new Intent(delegate.requireContext(), ReelCommentActivity.class);
            intent.putExtra(ReelCommentActivity.EXTRA_REEL_ID,  reel.reelId);
            intent.putExtra(ReelCommentActivity.EXTRA_REEL_UID, reel.uid != null ? reel.uid : "");
            delegate.getFragment().startActivity(intent);
        } catch (Exception ignored) {}
    }

    // ── More options sheet ────────────────────────────────────────────────

    public void showMoreOptions() {
        if (!delegate.isAdded() || delegate.getContext() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null) return;
        String myUid   = delegate.safeMyUid();
        boolean isOwner = myUid != null && myUid.equals(reel.uid);
        String speedLabel = "Speed: " + delegate.getSpeedLabels()[delegate.getSpeedIndex()];
        String duetLevel   = reel.allowDuetLevel   != null ? reel.allowDuetLevel   : "everyone";
        String stitchLevel = reel.allowStitchLevel != null ? reel.allowStitchLevel : "everyone";

        ReelMoreBottomSheet sheet = ReelMoreBottomSheet.newInstance(
            isOwner, delegate.isSaved(), speedLabel,
            duetLevel, stitchLevel, delegate.isFollowing(), reel.seriesId,
            delegate.isCinemaModeOn());
        sheet.show(delegate.getChildFragmentManager(), ReelMoreBottomSheet.TAG);
    }

    // ── onMoreItemClick dispatcher ────────────────────────────────────────

    public void onMoreItemClick(String action) {
        switch (action) {
            case ReelMoreBottomSheet.ACTION_SAVE:               delegate.toggleSave();              break;
            case ReelMoreBottomSheet.ACTION_BOOKMARK_COLLECTIONS: delegate.openBookmarkCollections(); break;
            case ReelMoreBottomSheet.ACTION_SPEED:              delegate.showSpeedPicker();         break;
            case ReelMoreBottomSheet.ACTION_DOWNLOAD:           delegate.downloadReel();            break;
            case ReelMoreBottomSheet.ACTION_DUET:               delegate.openDuet();               break;
            case ReelMoreBottomSheet.ACTION_STITCH:             delegate.openStitch();             break;
            case ReelMoreBottomSheet.ACTION_VIDEO_REPLY:        delegate.openVideoReply();         break;
            case ReelMoreBottomSheet.ACTION_SHARE_TO_STORY:     delegate.openShareToStory();       break;
            case ReelMoreBottomSheet.ACTION_COLLAB_REQUEST:     delegate.openCollabRequest();      break;
            case ReelMoreBottomSheet.ACTION_COLLAB_REPOST:      delegate.openCollabRepost();       break;
            case ReelMoreBottomSheet.ACTION_ADD_COLLABORATORS:  delegate.openAddCollaborators();   break;
            case ReelMoreBottomSheet.ACTION_NOT_INTERESTED:     delegate.markNotInterested();      break;
            case ReelMoreBottomSheet.ACTION_COPY_LINK:          delegate.copyReelLink();           break;
            case ReelMoreBottomSheet.ACTION_REPORT:             delegate.openReelReport();         break;
            case ReelMoreBottomSheet.ACTION_EDIT:               delegate.openReelEdit();           break;
            case ReelMoreBottomSheet.ACTION_ANALYTICS:          delegate.openReelAnalytics();      break;
            case ReelMoreBottomSheet.ACTION_PINNED_COMMENTS:    delegate.openPinnedComments();     break;
            case ReelMoreBottomSheet.ACTION_QR_CODE:            delegate.openReelQRCode();         break;
            case ReelMoreBottomSheet.ACTION_BLOCK:              delegate.blockReelOwner();         break;
            case ReelMoreBottomSheet.ACTION_DELETE:             delegate.confirmDeleteReel();      break;
            // v10 Duet features
            case ReelMoreBottomSheet.ACTION_DUET_INVITE:        delegate.openDuetInvite();        break;
            case ReelMoreBottomSheet.ACTION_MULTI_DUET:         delegate.openMultiDuet();         break;
            case ReelMoreBottomSheet.ACTION_DUET_CHALLENGE:     delegate.openDuetChallenge();     break;
            case ReelMoreBottomSheet.ACTION_DUET_APPROVAL:      delegate.openDuetApproval();      break;
            case ReelMoreBottomSheet.ACTION_DUET_BATTLE:        delegate.openDuetBattle();        break;
            case ReelMoreBottomSheet.ACTION_DUET_TREE:          delegate.openDuetTree();          break;
            // v11 Duet Series
            case ReelMoreBottomSheet.ACTION_VIEW_SERIES:        delegate.openDuetSeries();        break;
            // Remix
            case ReelMoreBottomSheet.ACTION_REMIX:              delegate.openRemix();             break;
            case ReelMoreBottomSheet.ACTION_VIEW_REMIXES:       delegate.openViewRemixes();       break;
            // Watch History
            case ReelMoreBottomSheet.ACTION_WATCH_HISTORY:      delegate.openWatchHistory();      break;
            // Video Quality picker
            case ReelMoreBottomSheet.ACTION_QUALITY:            delegate.showQualityPicker();     break;
            case ReelMoreBottomSheet.ACTION_SAVE_OFFLINE:       delegate.saveReelOffline();        break;
            case ReelMoreBottomSheet.ACTION_QOE_STATS:          delegate.showQoeStats();           break;
            case ReelMoreBottomSheet.ACTION_STREAMING_INFO:     delegate.showStreamingModeInfo();  break;
            case ReelMoreBottomSheet.ACTION_CACHE_STATUS:       delegate.showCacheStatus();        break;
            // Reels Display Mode
            case ReelMoreBottomSheet.ACTION_DISPLAY_MODE:       delegate.showDisplayModePicker();  break;
            case ReelMoreBottomSheet.ACTION_BACKGROUND_PLAY:    delegate.toggleBackgroundPlay();   break;
            case ReelMoreBottomSheet.ACTION_CINEMA_MODE:        delegate.toggleCinemaMode();       break;
        }
    }
}
