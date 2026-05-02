package com.callx.app.adapters;

import android.content.Context;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.R;
import com.callx.app.utils.Constants;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * GroupMemberAdapter — Ultra-advanced member list for GroupInfoActivity.
 *
 * Shows: circular avatar, online dot, name, admin/creator badge,
 *        last-seen text, 3-dot options menu (view profile, message,
 *        make admin, remove — admin-gated).
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
        public final boolean online;
        public final Long   lastSeen;

        public MemberItem(String uid, String name, String role,
                          String photoUrl, boolean online, Long lastSeen) {
            this.uid      = uid;
            this.name     = name;
            this.role     = role;
            this.photoUrl = photoUrl;
            this.online   = online;
            this.lastSeen = lastSeen;
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
        h.tvName.setText(isMe ? m.name + " (You)" : m.name);

        // Role badge
        boolean showBadge = "admin".equals(m.role) || "creator".equals(m.role);
        h.tvRoleBadge.setVisibility(showBadge ? View.VISIBLE : View.GONE);
        if (showBadge) h.tvRoleBadge.setText("creator".equals(m.role) ? "Creator" : "Admin");

        // Online dot
        h.onlineDot.setVisibility(m.online ? View.VISIBLE : View.GONE);

        // Avatar
        if (m.photoUrl != null && !m.photoUrl.isEmpty()) {
            Glide.with(ctx).load(m.photoUrl)
                    .placeholder(R.drawable.ic_person)
                    .circleCrop()
                    .into(h.ivAvatar);
        } else {
            h.ivAvatar.setImageResource(R.drawable.ic_person);
        }

        // Last seen / status
        if (m.online) {
            h.tvStatus.setText("Online");
        } else if (m.lastSeen != null && m.lastSeen > 0) {
            h.tvStatus.setText("last seen " + formatLastSeen(m.lastSeen));
        } else {
            h.tvStatus.setText("");
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

        new AlertDialog.Builder(ctx)
                .setTitle(m.name)
                .setItems(labels.toArray(new String[0]), (d, which) ->
                        listener.onAction(m.uid, actions.get(which)))
                .show();
    }

    @Override public int getItemCount() { return items.size(); }

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
        TextView tvName, tvRoleBadge, tvStatus;
        View   btnOptions;

        VH(@NonNull View itemView) {
            super(itemView);
            ivAvatar    = itemView.findViewById(R.id.iv_avatar);
            onlineDot   = itemView.findViewById(R.id.online_dot);
            tvName      = itemView.findViewById(R.id.tv_member_name);
            tvRoleBadge = itemView.findViewById(R.id.tv_role_badge);
            tvStatus    = itemView.findViewById(R.id.tv_member_status);
            btnOptions  = itemView.findViewById(R.id.btn_member_options);
        }
    }
}
