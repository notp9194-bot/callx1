package com.callx.app.viewmodel;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.callx.app.db.entity.StatusEntity;
import com.callx.app.repository.StatusRepository;
import com.callx.app.utils.FirebaseUtils;
import java.util.List;

/**
 * StatusViewModel — bridges StatusRepository and the Updates-tab UI.
 *
 * WhatsApp-level pattern:
 *   StatusFragment
 *     → observes LiveData<List<StatusEntity>> from this ViewModel
 *     → never touches Firebase or Room directly
 *     → calls postStatus / deleteStatus through this ViewModel
 *
 * Active statuses are Room LiveData — shows cached data instantly,
 * then auto-updates when Firebase syncs arrive.
 */
public class StatusViewModel extends AndroidViewModel {

    private final StatusRepository repo;
    private final String myUid;

    // ── Exposed LiveData ──────────────────────────────────────────────────

    /** All active (non-expired) statuses from Room — drives the status list. */
    public final LiveData<List<StatusEntity>> activeStatuses;

    /** My own statuses — drives the "My Status" row. */
    public final LiveData<List<StatusEntity>> myStatuses;

    private final MutableLiveData<Boolean> _loading = new MutableLiveData<>(false);
    public final LiveData<Boolean> loading = _loading;

    public StatusViewModel(@NonNull Application app) {
        super(app);
        repo  = StatusRepository.getInstance(app);
        myUid = FirebaseUtils.getCurrentUid();

        activeStatuses = repo.getActiveStatuses();
        myStatuses     = repo.getMyStatuses(myUid);
    }

    // ── Lifecycle hooks ───────────────────────────────────────────────────

    /**
     * Call from Fragment.onStart() — triggers a Firebase sync for the
     * user's contacts. Room LiveData auto-notifies the UI when DB updates.
     */
    public void syncContacts(List<String> contactUids) {
        _loading.postValue(true);
        repo.syncStatuses(contactUids);
        // Prune expired after sync
        repo.pruneExpired();
        _loading.postValue(false);
    }

    // ── Write ops ─────────────────────────────────────────────────────────

    public interface Result { void onDone(boolean success); }

    public void deleteMyStatus(String statusId, Result cb) {
        if (myUid == null || myUid.isEmpty()) return;
        repo.deleteStatus(myUid, statusId, ok -> { if (cb != null) cb.onDone(ok); });
    }

    // ── Seen tracking ─────────────────────────────────────────────────────

    public void markSeen(String ownerUid, String statusId) {
        if (myUid == null || myUid.isEmpty()) return;
        repo.markSeen(myUid, ownerUid, statusId);
    }

    public void addReaction(String ownerUid, String statusId, String emoji) {
        if (myUid == null || myUid.isEmpty()) return;
        repo.addReaction(myUid, ownerUid, statusId, emoji);
    }
}
