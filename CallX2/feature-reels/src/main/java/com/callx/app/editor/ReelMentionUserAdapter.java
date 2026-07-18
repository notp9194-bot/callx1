package com.callx.app.editor;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.reels.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
// java.util.function.Consumer removed — requires API 24, minSdk=23

/**
 * ReelMentionUserAdapter — shows a list of users that can be @mentioned /
 * tagged on the reel. Used by both:
 *
 *  ① ReelMentionTagSheet (video tag overlay: single-select toggle)
 *  ② Caption mention popup (inline @username autocomplete: single-select)
 */
public class ReelMentionUserAdapter
        extends RecyclerView.Adapter<ReelMentionUserAdapter.VH> {

    public static final class MentionUser {
        public final String uid;
        public final String displayName;
        public final String username;   // without "@"
        public final String photoUrl;

        public MentionUser(String uid, String displayName, String username, String photoUrl) {
            this.uid         = uid;
            this.displayName = displayName != null ? displayName : "";
            this.username    = username    != null ? username    : "";
            this.photoUrl    = photoUrl    != null ? photoUrl    : "";
        }
    }

    private final List<MentionUser> items    = new ArrayList<>();
    private final Set<String>       selected = new HashSet<>();  // UIDs
    /** API-23-safe callback replacing java.util.function.Consumer (requires API 24). */
    public interface OnMentionClick {
        void onClick(MentionUser user);
    }

    private final OnMentionClick onToggle;
    private final boolean        showCheckbox;

    public ReelMentionUserAdapter(OnMentionClick onToggle, boolean showCheckbox) {
        this.onToggle    = onToggle;
        this.showCheckbox = showCheckbox;
    }

    public void setItems(List<MentionUser> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    public void setSelected(Set<String> uids) {
        selected.clear();
        if (uids != null) selected.addAll(uids);
        notifyDataSetChanged();
    }

    public boolean isSelected(String uid) { return selected.contains(uid); }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_mention_user_row, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        MentionUser u = items.get(pos);
        h.tvName.setText(u.displayName.isEmpty() ? u.username : u.displayName);
        h.tvUsername.setText(u.username.isEmpty() ? "" : "@" + u.username);
        h.tvUsername.setVisibility(u.username.isEmpty() ? View.GONE : View.VISIBLE);

        if (!u.photoUrl.isEmpty()) {
            Glide.with(h.ivAvatar.getContext())
                .load(u.photoUrl)
                .circleCrop()
                .placeholder(R.drawable.ic_person)
                .into(h.ivAvatar);
        } else {
            h.ivAvatar.setImageResource(R.drawable.ic_person);
        }

        if (showCheckbox) {
            h.cbCheck.setVisibility(View.VISIBLE);
            h.cbCheck.setChecked(selected.contains(u.uid));
        } else {
            h.cbCheck.setVisibility(View.GONE);
        }

        h.itemView.setOnClickListener(v -> {
            if (onToggle != null) onToggle.onClick(u);
        });
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivAvatar;
        TextView  tvName, tvUsername;
        CheckBox  cbCheck;

        VH(@NonNull View v) {
            super(v);
            ivAvatar  = v.findViewById(R.id.iv_mention_avatar);
            tvName    = v.findViewById(R.id.tv_mention_display_name);
            tvUsername = v.findViewById(R.id.tv_mention_username);
            cbCheck   = v.findViewById(R.id.cb_mention_check);
        }
    }
}
