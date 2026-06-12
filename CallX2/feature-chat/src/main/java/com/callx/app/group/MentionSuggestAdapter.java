package com.callx.app.group;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.chat.R;

import java.util.ArrayList;
import java.util.List;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * MentionSuggestAdapter — Group chat mein @mention suggestion list.
 *
 * Keyboard pe @ likhte hi group members ki filtered list dikhao.
 * Member pe tap karo → EditText mein @name paste ho jata hai.
 *
 * Usage:
 *   adapter = new MentionSuggestAdapter(listener);
 *   adapter.filter("Ra");   // "Ra" se shuru hone wale members dikhao
 *   adapter.clearFilter();  // sab dikhao / hide karo
 */
public class MentionSuggestAdapter
        extends RecyclerView.Adapter<MentionSuggestAdapter.VH> {

    public interface OnMentionSelectedListener {
        /**
         * @param uid  Selected member ka Firebase UID
         * @param name Display name (without @)
         */
        void onMentionSelected(String uid, String name);
    }

    /** Lightweight model — sirf naam aur photo chahiye suggestion ke liye */
    public static class MemberItem {
        public final String uid;
        public final String name;
        public final String photoUrl;

        public MemberItem(String uid, String name, String photoUrl) {
            this.uid      = uid;
            this.name     = name != null ? name : "Member";
            this.photoUrl = photoUrl != null ? photoUrl : "";
        }
    }

    private final List<MemberItem>          allMembers      = new ArrayList<>();
    private final List<MemberItem>          filteredMembers = new ArrayList<>();
    private final OnMentionSelectedListener listener;

    public MentionSuggestAdapter(@NonNull OnMentionSelectedListener listener) {
        this.listener = listener;
    }

    // ── Data ─────────────────────────────────────────────────────────────

    /** Full member list set/update karo */
    public void setMembers(List<MemberItem> members) {
        allMembers.clear();
        if (members != null) allMembers.addAll(members);
        filteredMembers.clear();
        filteredMembers.addAll(allMembers);
        notifyDataSetChanged();
    }

    /**
     * @ ke baad typed query se filter karo.
     * e.g. query="ra" → "Rahul", "Ramesh" etc.
     */
    public void filter(String query) {
        filteredMembers.clear();
        if (query == null || query.isEmpty()) {
            filteredMembers.addAll(allMembers);
        } else {
            String lq = query.toLowerCase();
            for (MemberItem item : allMembers) {
                if (item.name.toLowerCase().startsWith(lq)) {
                    filteredMembers.add(item);
                }
            }
        }
        notifyDataSetChanged();
    }

    /** Saari suggestions saaf karo (hide hone ke liye) */
    public void clearFilter() {
        filteredMembers.clear();
        notifyDataSetChanged();
    }

    // ── Adapter ───────────────────────────────────────────────────────────

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_mention_suggest, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        MemberItem item = filteredMembers.get(pos);
        h.tvName.setText(item.name);

        if (!item.photoUrl.isEmpty()) {
            Glide.with(h.itemView.getContext())
                 .load(item.photoUrl)
                 .apply(RequestOptions.circleCropTransform())
                 .placeholder(R.drawable.ic_person)
                 .into(h.ivAvatar);
        } else {
            h.ivAvatar.setImageResource(R.drawable.ic_person);
        }

        h.itemView.setOnClickListener(v ->
                listener.onMentionSelected(item.uid, item.name));
    }

    @Override
    public int getItemCount() { return filteredMembers.size(); }

    // ── ViewHolder ────────────────────────────────────────────────────────

    static class VH extends RecyclerView.ViewHolder {
        final CircleImageView ivAvatar;
        final TextView        tvName;

        VH(@NonNull View v) {
            super(v);
            ivAvatar = v.findViewById(R.id.iv_mention_avatar);
            tvName   = v.findViewById(R.id.tv_mention_name);
        }
    }
}
