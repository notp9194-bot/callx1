package com.callx.app.group;

import android.content.Context;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.cache.ChatAvatarBinder;
import com.callx.app.cache.AvatarVersionSyncManager;
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
 *
 * v4 — AsyncListDiffer + payload-based bind (perf, mirrors
 * FollowConnectionsActivity's UserListAdapter / ReelCommentsAdapter): this
 * list was the one member/user list left doing raw {@code
 * notifyDataSetChanged()} on every Firebase snapshot — full rebind of every
 * visible row (avatar re-decode included) even when only one member's
 * online dot flipped. Now:
 *   • {@link #submitList} replaces direct list mutation — AsyncListDiffer
 *     diffs old vs new off the main thread and dispatches minimal
 *     insert/remove/move, same as {@code UserListAdapter.submitList()}.
 *   • Stable IDs (uid hash) so the default item animator matches rows by
 *     identity across diffs, not position.
 *   • {@code PAYLOAD_STATUS} partial bind — when only online/lastSeen
 *     changed (name/role/photo/thumb/avatarVersion identical), only the
 *     online dot + status text repaint; avatar is left untouched (no
 *     Glide re-decode), mirroring {@code PAYLOAD_FOLLOW_STATE}.
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

    /** Payload marker for an online/lastSeen-only partial rebind. */
    private static final String PAYLOAD_STATUS = "status";

    private static final DiffUtil.ItemCallback<MemberItem> DIFF_CALLBACK =
        new DiffUtil.ItemCallback<MemberItem>() {
            @Override
            public boolean areItemsTheSame(@NonNull MemberItem a, @NonNull MemberItem b) {
                return a.uid != null && a.uid.equals(b.uid);
            }

            @Override
            public boolean areContentsTheSame(@NonNull MemberItem a, @NonNull MemberItem b) {
                return Objects.equals(a.name, b.name)
                    && Objects.equals(a.role, b.role)
                    && Objects.equals(a.photoUrl, b.photoUrl)
                    && Objects.equals(a.thumbUrl, b.thumbUrl)
                    && a.online == b.online
                    && Objects.equals(a.lastSeen, b.lastSeen)
                    && a.avatarVersion == b.avatarVersion;
            }

            /** Only online/lastSeen changed → partial bind, skip avatar +
             *  name/badge rebind (mirrors PAYLOAD_FOLLOW_STATE in
             *  FollowConnectionsActivity's UserListAdapter). */
            @Override
            public Object getChangePayload(@NonNull MemberItem a, @NonNull MemberItem b) {
                boolean onlyStatusChanged =
                       Objects.equals(a.name, b.name)
                    && Objects.equals(a.role, b.role)
                    && Objects.equals(a.photoUrl, b.photoUrl)
                    && Objects.equals(a.thumbUrl, b.thumbUrl)
                    && a.avatarVersion == b.avatarVersion
                    && (a.online != b.online || !Objects.equals(a.lastSeen, b.lastSeen));
                return onlyStatusChanged ? PAYLOAD_STATUS : null;
            }
        };

    private final AsyncListDiffer<MemberItem> differ = new AsyncListDiffer<>(this, DIFF_CALLBACK);
    private final String             currentUid;
    private final OnMemberActionListener listener;
    private boolean isAdmin = false;
    private boolean isCreator = false;

    public GroupMemberAdapter(List<MemberItem> items, String currentUid,
                              OnMemberActionListener listener) {
        this.currentUid = currentUid;
        this.listener   = listener;
        setHasStableIds(true);
        if (items != null && !items.isEmpty()) differ.submitList(new ArrayList<>(items));
    }

    /** Diffs against the current list off the main thread and dispatches
     *  minimal insert/remove/move/change calls instead of a full rebind —
     *  replaces the old direct-mutation + notifyDataSetChanged() pattern. */
    public void submitList(List<MemberItem> items) {
        differ.submitList(items != null ? new ArrayList<>(items) : new ArrayList<>());
    }

    public void setIsAdmin(boolean admin) {
        // isAdmin only gates which options showMemberOptionsMenu() offers,
        // read fresh from this field at click-time — it doesn't change any
        // bound row visuals, so (unlike the old notifyDataSetChanged() here)
        // no rebind is needed at all.
        this.isAdmin = admin;
    }

    /** Only the group creator may revoke an admin or remove another admin. */
    public void setIsCreator(boolean creator) {
        this.isCreator = creator;
    }

    @Override
    public long getItemId(int position) {
        MemberItem m = differ.getCurrentList().get(position);
        return (m.uid != null) ? m.uid.hashCode() : RecyclerView.NO_ID;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_group_member, parent, false);
        return new VH(v);
    }

    /** Payload-aware partial bind — PAYLOAD_STATUS skips avatar reload and
     *  name/badge rebind, touching only the online dot + status text. Falls
     *  back to a full bind for a cold bind or any other payload. */
    @Override
    public void onBindViewHolder(@NonNull VH h, int pos, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_STATUS)) {
            MemberItem m = differ.getCurrentList().get(pos);
            bindStatus(h, m);
            return;
        }
        super.onBindViewHolder(h, pos, payloads);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        MemberItem m   = differ.getCurrentList().get(pos);
        Context ctx    = h.itemView.getContext();
        boolean isMe   = currentUid.equals(m.uid);

        // Name — append "(You)" for self
        h.identityView.setName(isMe ? m.name + " (You)" : m.name);

        // Role badge
        boolean showBadge = "admin".equals(m.role) || "creator".equals(m.role);
        h.identityView.setBadge(showBadge ? ("creator".equals(m.role) ? "Creator" : "Admin") : null);

        // Online dot + last seen / status
        bindStatus(h, m);

        // Avatar — thumbUrl preferred (small, fast in a group list), falls back to photoUrl.
        // v3: routed through ChatAvatarBinder — same tiered/versioned URL +
        // L2/L3 pipeline every other avatar list in the app uses now (see
        // this class's v3 doc above). Replaces the old flat
        // Glide.load(avatarUrl).override(96,96) with no tier, no version
        // param, and no L2/L3 reuse.
        String avatarUrl = (m.thumbUrl != null && !m.thumbUrl.isEmpty())
            ? m.thumbUrl
            : (m.photoUrl != null && !m.photoUrl.isEmpty() ? m.photoUrl : null);
        // FIX (avatar delta-sync gap): fall back through AvatarVersionSyncManager's
        // cached version when this member row's own snapshot hasn't resolved one —
        // same reasoning/shape as ChatListAdapter#resolveAvatarVersion, so a member
        // who just changed their photo (observed by their profile screen, a status,
        // a reel...) shows up-to-date here on the next bind instead of only after
        // GroupInfoActivity's whole member list is refetched.
        ChatAvatarBinder.bind(ctx, h.ivAvatar, avatarUrl, resolveAvatarVersion(ctx, m.uid, m.avatarVersion), R.drawable.ic_person);

        // Options menu (3-dot tap + row long-press open the same menu)
        final View.OnClickListener openOptions = v -> {
            if (isMe) {
                // My own row: only profile info
                listener.onAction(m.uid, "view_profile");
                return;
            }
            showMemberOptionsMenu(ctx, m);
        };
        h.btnOptions.setOnClickListener(openOptions);
        h.itemView.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            openOptions.onClick(v);
            return true;
        });

        // Row click = view profile
        h.itemView.setOnClickListener(v -> listener.onAction(m.uid, "view_profile"));
    }

    /** Online dot + last-seen text only — split out so PAYLOAD_STATUS can
     *  repaint just this without touching the avatar or name/badge. */
    private void bindStatus(@NonNull VH h, MemberItem m) {
        h.onlineDot.setVisibility(m.online ? View.VISIBLE : View.GONE);
        if (m.online) {
            h.identityView.setStatus("Online");
        } else if (m.lastSeen != null && m.lastSeen > 0) {
            h.identityView.setStatus("last seen " + formatLastSeen(m.lastSeen));
        } else {
            h.identityView.setStatus("");
        }
    }

    private void showMemberOptionsMenu(Context ctx, MemberItem m) {
        List<String>  labels  = new ArrayList<>();
        List<String>  actions = new ArrayList<>();

        labels.add("Message"); actions.add("message");
        labels.add("View Profile"); actions.add("view_profile");

        if (isAdmin && !"creator".equals(m.role)) {
            boolean targetIsAdmin = "admin".equals(m.role);
            // Admin-vs-admin rule: only the creator can touch another admin.
            if (!targetIsAdmin) {
                labels.add("Make Admin"); actions.add("make_admin");
                labels.add("Remove from Group"); actions.add("remove");
            } else if (isCreator) {
                labels.add("Revoke Admin"); actions.add("revoke_admin");
                labels.add("Remove from Group"); actions.add("remove");
            }
        }

        final float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, (int) (8 * d), 0, (int) (8 * d));

        TypedValueHolder tv = new TypedValueHolder(ctx);
        final AlertDialog[] dialogRef = new AlertDialog[1];

        for (int i = 0; i < labels.size(); i++) {
            final String action = actions.get(i);
            boolean destructive = "remove".equals(action);

            // Divider before the destructive action, separating it from the rest.
            if (destructive) {
                View line = new View(ctx);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) d));
                lp.topMargin = (int) (4 * d);
                lp.bottomMargin = (int) (4 * d);
                line.setLayoutParams(lp);
                line.setBackgroundColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.divider));
                list.addView(line);
            }

            TextView row = new TextView(ctx);
            row.setText(labels.get(i));
            row.setTextSize(16f);
            row.setTextColor(destructive
                    ? androidx.core.content.ContextCompat.getColor(ctx, R.color.member_menu_destructive)
                    : androidx.core.content.ContextCompat.getColor(ctx, R.color.text_primary));
            row.setPadding((int) (24 * d), (int) (14 * d), (int) (24 * d), (int) (14 * d));
            row.setBackgroundResource(tv.selectableItemBackground);
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> {
                if (dialogRef[0] != null) dialogRef[0].dismiss();
                listener.onAction(m.uid, action);
            });
            list.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(m.name)
                .setView(list)
                .create();
        dialogRef[0] = dialog;
        com.callx.app.utils.AlertDialogStyler.showRounded(dialog);
    }

    /** Resolves ?attr/selectableItemBackground once for the programmatic menu rows. */
    private static final class TypedValueHolder {
        final int selectableItemBackground;
        TypedValueHolder(Context ctx) {
            android.util.TypedValue out = new android.util.TypedValue();
            ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true);
            selectableItemBackground = out.resourceId;
        }
    }

    @Override public int getItemCount() { return differ.getCurrentList().size(); }

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
    private ChatAvatarBinder.AvatarSource avatarSource(Context ctx) {
        return new ChatAvatarBinder.AvatarSource() {
            @Override public String photo(int index) {
                MemberItem m = differ.getCurrentList().get(index);
                return (m.thumbUrl != null && !m.thumbUrl.isEmpty()) ? m.thumbUrl : m.photoUrl;
            }
            @Override public long avatarVersion(int index) {
                MemberItem m = differ.getCurrentList().get(index);
                return resolveAvatarVersion(ctx, m.uid, m.avatarVersion);
            }
            @Override public int size() { return differ.getCurrentList().size(); }
        };
    }

    /** See ChatListAdapter#resolveAvatarVersion — same fallback shape, kept local
     *  since AvatarVersionSyncManager.getInstance() only needs a Context, not a
     *  shared static across adapters. */
    private static long resolveAvatarVersion(Context ctx, String uid, long modelVersion) {
        if (modelVersion > 0) return modelVersion;
        if (ctx == null || uid == null || uid.isEmpty()) return 0L;
        return AvatarVersionSyncManager.getInstance(ctx).getCachedVersion(uid);
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
        ChatAvatarBinder.prefetch(ctx, avatarSource(ctx), fromIndex, velocityPxPerMs);
    }

    /** "just now" / "1 min ago" / "1 hour ago" / "yesterday at 3:45 PM" / "12 Sep". */
    private static String formatLastSeen(long ts) {
        long now  = System.currentTimeMillis();
        long diff = now - ts;
        if (diff < 60_000) return "just now"; // also covers small clock skew (negative diff)

        if (diff < 3_600_000) {
            long mins = diff / 60_000;
            return mins + (mins == 1 ? " min ago" : " mins ago");
        }

        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(ts);
        Calendar today = Calendar.getInstance();
        today.setTimeInMillis(now);

        if (isSameDay(then, today)) {
            long hrs = diff / 3_600_000;
            return hrs + (hrs == 1 ? " hour ago" : " hours ago");
        }

        Calendar yesterday = Calendar.getInstance();
        yesterday.setTimeInMillis(now);
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        if (isSameDay(then, yesterday)) {
            return "yesterday at " + new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date(ts));
        }

        String pattern = then.get(Calendar.YEAR) == today.get(Calendar.YEAR) ? "dd MMM" : "dd MMM yyyy";
        return new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date(ts));
    }

    private static boolean isSameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
            && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
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
