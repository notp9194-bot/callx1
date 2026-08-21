package com.callx.app.viewer;

import android.os.Bundle;
import com.callx.app.models.StatusItem;

/**
 * StoryViewerActivity — Instagram-style Story viewer for reels shared via
 * "Add to Story" (ReelShareSheetFragment#addToStory()).
 *
 * Reuses StatusViewerActivity end-to-end (same data source: status/{uid} in
 * Firebase, same progress-bar/gesture/reply/reaction/mute/download/forward/
 * repost/highlights/seen-tracking machinery) instead of re-implementing a
 * parallel viewer — the two flows only ever differed in WHICH items they
 * should show and WHAT the fallback header says. Everything else is
 * identical Instagram/WhatsApp-style story-viewing behaviour, so this class
 * intentionally stays tiny: it only overrides the two hooks the base class
 * exposes for exactly this purpose.
 *
 * Launched from feature-reels' HomeFragment (Reels home story ring) via
 * Class.forName — same cross-module pattern that used to point at
 * StatusViewerActivity directly, so no compile-time dependency is added
 * between feature-reels and feature-status.
 *
 * All intent extras (EXTRA_OWNER_UID, EXTRA_OWNER_NAME, EXTRA_TARGET_STATUS_ID,
 * EXTRA_HIGHLIGHT_ALBUM_ID, EXTRA_QUEUE_OWNER_UIDS/NAMES, EXTRA_QUEUE_ALBUM_IDS/NAMES)
 * are inherited unchanged from StatusViewerActivity.
 */
public class StoryViewerActivity extends StatusViewerActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Instagram-style story viewer — label the header differently from
        // the WhatsApp-style Status viewer even though the chrome underneath
        // (progress segments, tap zones, reply bar, etc.) is 100% shared.
        if (binding() != null && binding().tvOwner != null
                && (ownerName == null || ownerName.isEmpty())) {
            binding().tvOwner.setText(viewerLabel());
        }
    }

    /** Only reel-clips explicitly shared via "Add to Story" belong here —
     *  the mirror image of the base viewer's own filter, so the same
     *  status/{uid} feed cleanly splits between the two viewers with zero
     *  overlap regardless of entry point. */
    @Override
    protected boolean shouldIncludeItem(StatusItem s) {
        return "reel_story".equals(s.type);
    }

    @Override
    protected String viewerLabel() {
        return "Story";
    }
}
