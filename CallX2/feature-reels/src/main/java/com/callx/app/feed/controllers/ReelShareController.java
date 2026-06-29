package com.callx.app.feed.controllers;

import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.view.View;
import android.widget.Toast;
import com.callx.app.comments.ReelCommentActivity;
import com.callx.app.comments.ReelCommentsBottomSheet;
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
            reel.reelId, reel.videoUrl, reel.effectiveThumbUrl(), reel.caption, reel.uid, reel.allowReposts);
        if (sheet.getArguments() != null) {
            sheet.getArguments().putString(ReelShareSheetFragment.ARG_OWNER_NAME,
                    reel.ownerName != null ? reel.ownerName : "");
            sheet.getArguments().putString(ReelShareSheetFragment.ARG_OWNER_PHOTO,
                    reel.ownerPhoto != null ? reel.ownerPhoto : "");
        }
        delegate.showBottomSheet(sheet, "share_sheet");
    }

    // ── Download ──────────────────────────────────────────────────────────

    public void downloadReel() {
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.videoUrl == null || !delegate.isAdded() || delegate.getContext() == null) return;
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(reel.videoUrl));
            req.setTitle("CallX Reel");
            req.setDescription("Downloading reel…");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES,
                "callx_reel_" + reel.reelId + ".mp4");
            req.allowScanningByMediaScanner();
            DownloadManager dm = (DownloadManager) delegate.requireContext()
                .getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(req);
                Toast.makeText(delegate.requireContext(), "Downloading reel…", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(delegate.requireContext(),
                "Download failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
        ReelCommentsBottomSheet sheet = ReelCommentsBottomSheet.newInstance(
            reel.reelId, reel.uid != null ? reel.uid : "", reel.commentsCount);
        delegate.showBottomSheet(sheet, ReelCommentsBottomSheet.TAG);
    }

    public void openLikesSheet() {
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.reelId == null || !delegate.isAdded() || delegate.getActivity() == null) return;
        ReelLikesBottomSheet sheet = ReelLikesBottomSheet.newInstance(
            reel.reelId, reel.likesCount, reel.viewsCount);
        delegate.showBottomSheet(sheet, ReelLikesBottomSheet.TAG);
    }

    public void openSharesSheet() {
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.reelId == null || !delegate.isAdded() || delegate.getActivity() == null) return;
        ReelSharesBottomSheet sheet = ReelSharesBottomSheet.newInstance(
            reel.reelId, reel.sharesCount, reel.repostCount);
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
            duetLevel, stitchLevel, delegate.isFollowing(), reel.seriesId);
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
        }
    }
}
