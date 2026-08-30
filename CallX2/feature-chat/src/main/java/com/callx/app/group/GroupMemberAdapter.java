package com.callx.app.group;

import android.content.Context;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.cache.ChatAvatarBinder;
import com.callx.app.chat.R;
import com.callx.app.group.canvas.MemberIdentityCanvasView;
import com.callx.app.utils.Constants;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * GroupMemberAdapter — Ultra-advanced member list for GroupInfoActivity.
 *
 * Shows: circular avatar, online dot, name, admin/creator badge,
 *        last-seen text, 3-dot options menu (view profile, message,
 *        make admin, remove — admin-gated).
 *
 * v2 — Canvas identity block (perf): tv_member_name + tv_role_badge +
 * tv_member_status went from 3 TextViews across 2 nested LinearLayouts to a
 * single MemberIdentityCanvasView — see its class doc. Matters here because
 * this list can run to hundreds of rows and re-binds on every
 * online-status/last-seen update.
 *
 * v3 — Deep avatar pipeline parity (was the one screen still on a flat
 * {@code Glide.load(avatarUrl).override(96,96)} while reels/chat-list/follow
 * lists had all moved to the shared {@link ChatAvatarBinder} pipeline — see
 * that class's doc for the full "density-aware tier + WebP/AVIF + L2/L3 +
 * version/ETag combine + per-module onTrimMemory" picture, all of which
 * this now gets for free by routing through it instead of raw Glide:
 *   • bind()     — server-side responsive AvatarSizeTier URL (SMALL, rounds
 *                  up from this row's 46dp view), L2 memory fast-path,
 *                  L2/L3 write-through on decode.
 *   • cancel()   — onViewRecycled() below stops an in-flight request for a
 *                  row that just scrolled off (or got re-diffed away).
 *   • prefetch() — velocity-based window, called from GroupInfoActivity's
 *                  NestedScrollView scroll listener via
 *                  {@link #prefetchAvatarsFrom} (see that method's doc for
 *                  why this list needs its own scroll-velocity source
 *                  instead of RecyclerView's own OnScrollListener).
 */
public class GroupMemberAdapter extends RecyclerView.Adapter<GroupMemberAdapter.VH> {

    public interface OnMemberActionListener {
        void onAction(String uid, String action);
    }

    public static class MemberItem {
        public final String uid;
        public final String name;
        public final String role;   // "admin" | "member" | "creator"
        public final String photoUrl;
        public final String thumbUrl;
        public final boolean online;
        public final Long   lastSeen;
        public final long   avatarVersion; // mirrors users/{uid}/avatarVersion — see AvatarUrlBuilder/ChatAvatarBinder

        public MemberItem(String uid, String name, String role,
                          String photoUrl, String thumbUrl, boolean online, Long lastSeen) {
            this(uid, name, role, photoUrl, thumbUrl, online, lastSeen, 0L);
        }

        public MemberItem(String uid, String name, String role, String photoUrl, String thumbUrl,
                          boolean online, Long lastSeen, long avatarVersion) {
            this.uid           = uid;
            this.name          = name;
            this.role          = role;
            this.photoUrl      = photoUrl;
            this.thumbUrl      = thumbUrl;
            this.online        = online;
            this.lastSeen      = lastSeen;
            this.avatarVersion = avatarVersion;
        }
    }

    private final List<MemberItem>   items;
    private final String             currentUid;
    private final OnMemberActionListener listener;
    private boolean isAdmin = false;

    public GroupMemberAdapter(List<MemberItem> items, String currentUid,
                              OnMemberActionListener listener) {
        this.items      = items;
        this.currentUid = currentUid;
        this.listener   = listener;
    }

    public void setIsAdmin(boolean admin) {
        this.isAdmin = admin;
        notifyDataSetChanged();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_group_member, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        MemberItem m   = items.get(pos);
        Context ctx    = h.itemView.getContext();
        boolean isMe   = currentUid.equals(m.uid);

        // Name — append "(You)" for self
        h.identityView.setName(isMe ? m.name + " (You)" : m.name);

        // Role badge
        boolean showBadge = "admin".equals(m.role) || "creator".equals(m.role);
        h.identityView.setBadge(showBadge ? ("creator".equals(m.role) ? "Creator" : "Admin") : null);

        // Online dot
        h.onlineDot.setVisibility(m.online ? View.VISIBLE : View.GONE);

        // Avatar — thumbUrl preferred (small, fast in a group list), falls back to photoUrl.
        // v3: routed through ChatAvatarBinder — same tiered/versioned URL +
        // L2/L3 pipeline every other avatar list in the app uses now (see
        // this class's v3 doc above). Replaces the old flat
        // Glide.load(avatarUrl).override(96,96) with no tier, no version
        // param, and no L2/L3 reuse.
        String avatarUrl = (m.thumbUrl != null && !m.thumbUrl.isEmpty())
            ? m.thumbUrl
            : (m.photoUrl != null && !m.photoUrl.isEmpty() ? m.photoUrl : null);
        ChatAvatarBinder.bind(ctx, h.ivAvatar, avatarUrl, m.avatarVersion, R.drawable.ic_person);

        // Last seen / status
        if (m.online) {
            h.identityView.setStatus("Online");
        } else if (m.lastSeen != null && m.lastSeen > 0) {
            h.identityView.setStatus("last seen " + formatLastSeen(m.lastSeen));
        } else {
            h.identityView.setStatus("");
        }

        // Options menu
        h.btnOptions.setOnClickListener(v -> {
            if (isMe) {
                // My own row: only profile info
                listener.onAction(m.uid, "view_profile");
                return;
            }
            showMemberOptionsMenu(ctx, m);
        });

        // Row click = view profile
        h.itemView.setOnClickListener(v -> listener.onAction(m.uid, "view_profile"));
    }

    private void showMemberOptionsMenu(Context ctx, MemberItem m) {
        List<String>  labels  = new ArrayList<>();
        List<String>  actions = new ArrayList<>();

        labels.add("Message"); actions.add("message");
        labels.add("View Profile"); actions.add("view_profile");

        if (isAdmin && !"creator".equals(m.role)) {
            if ("admin".equals(m.role)) {
                labels.add("Revoke Admin"); actions.add("revoke_admin");
            } else {
                labels.add("Make Admin 👑"); actions.add("make_admin");
            }
            labels.add("Remove from Group"); actions.add("remove");
        }

        com.callx.app.utils.AlertDialogStyler.showRounded(
            new AlertDialog.Builder(ctx)
                .setTitle(m.name)
                .setItems(labels.toArray(new String[0]), (d, which) ->
                        listener.onAction(m.uid, actions.get(which)))
                .create());
    }

    @Override public int getItemCount() { return items.size(); }

    /**
     * v3: cancels an in-flight avatar request for a row leaving the pool —
     * without this, a request still resolving after the row was recycled
     * (re-diff, admin-status refresh triggers notifyDataSetChanged) could
     * land its bitmap into a VH now showing a different member. Mirrors
     * ChatListAdapter#onViewRecycled's identical fix for the chat list.
     */
    @Override
    public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        if (h.ivAvatar != null) {
            ChatAvatarBinder.cancel(h.ivAvatar.getContext(), h.ivAvatar);
        }
    }

    /** Read-only view over `items` for {@link ChatAvatarBinder#prefetch}. */
    private ChatAvatarBinder.AvatarSource avatarSource() {
        return new ChatAvatarBinder.AvatarSource() {
            @Override public String photo(int index) {
                MemberItem m = items.get(index);
                return (m.thumbUrl != null && !m.thumbUrl.isEmpty()) ? m.thumbUrl : m.photoUrl;
            }
            @Override public long avatarVersion(int index) { return items.get(index).avatarVersion; }
            @Override public int size() { return items.size(); }
        };
    }

    /**
     * v3 (velocity-based prefetch): call from GroupInfoActivity's scroll
     * listener. Unlike ChatsFragment/FollowersListActivity, this adapter's
     * RecyclerView sits inside a NestedScrollView with nested scrolling
     * disabled (see activity_group_info.xml) — it never receives its own
     * OnScrollListener callbacks, since the outer NestedScrollView does the
     * actual scrolling. GroupInfoActivity is therefore the one computing
     * scroll velocity (from the NestedScrollView's scroll-change deltas)
     * and the newly-visible member index, and this method just forwards
     * both into the SAME ChatAvatarBinder.prefetch() every other avatar
     * list in the app uses — fast fling skips prefetch entirely, slow
     * scroll warms several members ahead via DiskCacheStrategy.DATA.
     */
    public void prefetchAvatarsFrom(Context ctx, int fromIndex, float velocityPxPerMs) {
        ChatAvatarBinder.prefetch(ctx, avatarSource(), fromIndex, velocityPxPerMs);
    }

    private static String formatLastSeen(long ts) {
        long diff = System.currentTimeMillis() - ts;
        if (diff < 60_000) return "just now";
        if (diff < 3_600_000) return (diff / 60_000) + " min ago";
        if (diff < 86_400_000) return (diff / 3_600_000) + " hours ago";
        SimpleDateFormat sdf = new SimpleDateFormat("dd MMM", Locale.getDefault());
        return sdf.format(new Date(ts));
    }

    static class VH extends RecyclerView.ViewHolder {
        de.hdodenhof.circleimageview.CircleImageView ivAvatar;
        View   onlineDot;
        MemberIdentityCanvasView identityView;
        View   btnOptions;

        VH(@NonNull View itemView) {
            super(itemView);
            ivAvatar     = itemView.findViewById(R.id.iv_avatar);
            onlineDot    = itemView.findViewById(R.id.online_dot);
            identityView = itemView.findViewById(R.id.view_member_identity);
            btnOptions   = itemView.findViewById(R.id.btn_member_options);
        }
    }
}
