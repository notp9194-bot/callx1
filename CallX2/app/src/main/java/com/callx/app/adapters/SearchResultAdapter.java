package com.callx.app.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.R;
import com.callx.app.cache.SearchAvatarBinder;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.ArrayList;
import java.util.List;

/**
 * v18: Search results adapter — shows user avatar, name, callxId list.
 *
 * v19 — Deep avatar pipeline parity (was the one remaining scrollable list
 * still on a flat tier-URL-only Glide load — see SearchAvatarBinder's class
 * doc for the full "density-aware tier + WebP/AVIF + L2/L3 + version cache-
 * bust + per-module onTrimMemory + velocity-based prefetch" picture, all of
 * which this now gets for free by routing through it instead of raw Glide,
 * exactly like ChatListAdapter/GroupMemberAdapter/FollowersAdapter already
 * do:
 *   • onBindViewHolder — SearchAvatarBinder.bind()
 *   • onViewRecycled   — SearchAvatarBinder.cancel(), stops an in-flight
 *                        request for a row that just scrolled off (or got
 *                        re-diffed away by a fresh query)
 *   • prefetchAvatarsFrom() — call from SearchActivity's RecyclerView scroll
 *                        listener; forwards to SearchAvatarBinder.prefetch()'s
 *                        velocity-based window
 */
public class SearchResultAdapter extends RecyclerView.Adapter<SearchResultAdapter.VH> {

    public interface OnUserClickListener {
        void onUserClick(String uid, String name, String photo, String thumb, String callxId);
    }

    public static class UserResult {
        public String uid, name, callxId, photoUrl, thumbUrl;
        // Bumped by 1 every time this user uploads a new avatar (mirrors
        // users/{uid}/avatarVersion — see AvatarUrlBuilder/SearchAvatarBinder).
        // 0 when unknown (e.g. an older Room cache row) — SearchAvatarBinder's
        // URL builder simply omits the cache-busting param in that case.
        public long avatarVersion;

        public UserResult(String uid, String name, String callxId, String photoUrl, String thumbUrl) {
            this(uid, name, callxId, photoUrl, thumbUrl, 0L);
        }

        public UserResult(String uid, String name, String callxId, String photoUrl, String thumbUrl, long avatarVersion) {
            this.uid = uid; this.name = name; this.callxId = callxId;
            this.photoUrl = photoUrl; this.thumbUrl = thumbUrl;
            this.avatarVersion = avatarVersion;
        }
    }

    private final List<UserResult> list = new ArrayList<>();
    private OnUserClickListener listener;

    public void setListener(OnUserClickListener l) { this.listener = l; }

    public void setResults(List<UserResult> results) {
        list.clear();
        if (results != null) list.addAll(results);
        notifyDataSetChanged();
        // Batch-warm the verified-badge cache for this page before rows
        // bind, so scrolling doesn't fire one Firebase read per row.
        if (results != null) {
            List<String> uids = new ArrayList<>(results.size());
            for (UserResult u : results) if (u.uid != null) uids.add(u.uid);
            com.callx.app.utils.VerifiedBadgeUtils.prefetch(uids);
        }
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_search_result, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        UserResult u = list.get(pos);
        h.tvName.setText(u.name != null ? u.name : "User");
        com.callx.app.utils.VerifiedBadgeUtils.bindForUid(h.ivVerified, u.uid);
        h.tvCallxId.setText(u.callxId != null ? u.callxId : "");
        // v19: routed through SearchAvatarBinder — same tiered/versioned
        // responsive URL + L2/L3 memory-and-disk reuse (survives
        // TRIM_MEMORY_MODERATE and process death) every other avatar list
        // in the app already uses. Replaces the old direct
        // Glide.load(avatarUrl).override(...) call, which had a shared-tier
        // URL (from the earlier AvatarUrlBuilder bug fix) but no L2/L3
        // reuse and no lifecycle-aware cancel/prefetch.
        String baseUrl = (u.thumbUrl != null && !u.thumbUrl.isEmpty()) ? u.thumbUrl : u.photoUrl;
        SearchAvatarBinder.bind(h.ivAvatar.getContext(), h.ivAvatar, baseUrl, u.avatarVersion, R.drawable.ic_person);
        h.itemView.setOnClickListener(v -> {
            if (listener != null)
                listener.onUserClick(u.uid, u.name, u.photoUrl, u.thumbUrl, u.callxId);
        });
    }

    @Override public int getItemCount() { return list.size(); }

    /**
     * v19 (lifecycle-aware cancel): stops an in-flight avatar request for a
     * row leaving the pool — without this, a request still resolving after
     * the row was recycled (fast typing re-diffs the whole list via
     * notifyDataSetChanged()) could land its bitmap into a VH now showing a
     * different user. Mirrors ChatListAdapter#onViewRecycled /
     * GroupMemberAdapter#onViewRecycled's identical fix.
     */
    @Override
    public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        if (h.ivAvatar != null) {
            SearchAvatarBinder.cancel(h.ivAvatar.getContext(), h.ivAvatar);
        }
    }

    /** Read-only view over `list` for {@link SearchAvatarBinder#prefetch}. */
    private SearchAvatarBinder.AvatarSource avatarSource() {
        return new SearchAvatarBinder.AvatarSource() {
            @Override public String photo(int index) {
                UserResult u = list.get(index);
                return (u.thumbUrl != null && !u.thumbUrl.isEmpty()) ? u.thumbUrl : u.photoUrl;
            }
            @Override public long avatarVersion(int index) { return list.get(index).avatarVersion; }
            @Override public int size() { return list.size(); }
        };
    }

    /**
     * v19 (velocity-based prefetch): call from SearchActivity's RecyclerView
     * scroll listener. Forwards the newly-visible index + scroll velocity
     * into SearchAvatarBinder.prefetch() — fast fling skips prefetch
     * entirely, slow/deliberate scroll warms several rows ahead via
     * DiskCacheStrategy.DATA (bytes only, decode deferred to a real bind).
     */
    public void prefetchAvatarsFrom(android.content.Context ctx, int fromIndex, float velocityPxPerMs) {
        SearchAvatarBinder.prefetch(ctx, avatarSource(), fromIndex, velocityPxPerMs);
    }

    static class VH extends RecyclerView.ViewHolder {
        CircleImageView ivAvatar;
        TextView tvName, tvCallxId;
        ImageView ivVerified;
        ImageView ivArrow;
        VH(View v) {
            super(v);
            ivAvatar   = v.findViewById(R.id.iv_avatar);
            tvName     = v.findViewById(R.id.tv_name);
            ivVerified = v.findViewById(R.id.iv_verified);
            tvCallxId  = v.findViewById(R.id.tv_callx_id);
            ivArrow    = v.findViewById(R.id.iv_arrow);
        }
    }
}
