package com.callx.app.community;

import androidx.lifecycle.LiveData;

import com.callx.app.db.entity.CommunityPostEntity;

import java.util.List;

/**
 * CommunityAnnouncementsFragment — same UI/behavior as CommunityFeedFragment,
 * scoped to isAnnouncement=true posts only (observeAnnouncements() instead
 * of observeFeed()). Announcement-only creation is gate-kept server-side by
 * Firebase rules (admin/owner) and client-side in CommunityPostComposerActivity.
 */
public class CommunityAnnouncementsFragment extends CommunityFeedFragment {

    public static CommunityAnnouncementsFragment newInstance(String communityId) {
        CommunityAnnouncementsFragment f = new CommunityAnnouncementsFragment();
        android.os.Bundle args = new android.os.Bundle();
        args.putString("communityId", communityId);
        f.setArguments(args);
        return f;
    }

    @Override
    protected LiveData<List<CommunityPostEntity>> observeFeedSource() {
        // PERF: windowed, not unbounded — see CommunityFeedFragment.WINDOW_SIZE
        // and CommunityDao.observeAnnouncementsWindowed's javadoc.
        return repo.observeAnnouncementsWindowed(communityId, WINDOW_SIZE);
    }

    @Override
    protected boolean isAnnouncementsTab() {
        return true;
    }
}
