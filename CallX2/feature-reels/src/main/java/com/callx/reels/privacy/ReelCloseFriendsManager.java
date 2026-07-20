package com.callx.reels.privacy;

import android.content.Context;
import com.callx.app.models.ReelModel;
import com.callx.app.utils.StatusCloseFriendsManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ReelCloseFriendsManager {
    private static ReelCloseFriendsManager instance;
    private ReelCloseFriendsManager() {}

    public static synchronized ReelCloseFriendsManager getInstance() {
        if (instance == null) instance = new ReelCloseFriendsManager();
        return instance;
    }

    public interface Callback { void onResult(boolean visible); }
    public interface FilterCallback { void onFiltered(List<ReelModel> filtered); }

    public void isCloseFriendReel(Context ctx, String viewerUid, String uploaderUid, String viewAudience, Callback cb) {
        if (!"close_friends".equals(viewAudience)) {
            cb.onResult(true);
            return;
        }
        if (viewerUid != null && viewerUid.equals(uploaderUid)) {
            cb.onResult(true);
            return;
        }
        cb.onResult(StatusCloseFriendsManager.isCloseFriend(ctx, uploaderUid));
    }

    public List<ReelModel> filterReelsList(Context ctx, List<ReelModel> reels, String myUid) {
        List<ReelModel> filtered = new ArrayList<>();
        Set<String> cfList = StatusCloseFriendsManager.getLocalList(ctx);
        for (ReelModel reel : reels) {
            String va = reel.audienceType;
            if (!"close_friends".equals(va)) {
                filtered.add(reel);
            } else if (myUid != null && (myUid.equals(reel.uid) || cfList.contains(reel.uid))) {
                filtered.add(reel);
            }
        }
        return filtered;
    }
}
