package com.callx.app.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.callx.app.R;
import com.callx.app.utils.AvatarUrlBuilder;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.ArrayList;
import java.util.List;

/**
 * v18: Search results adapter — shows user avatar, name, callxId list.
 */
public class SearchResultAdapter extends RecyclerView.Adapter<SearchResultAdapter.VH> {

    public interface OnUserClickListener {
        void onUserClick(String uid, String name, String photo, String thumb, String callxId);
    }

    public static class UserResult {
        public String uid, name, callxId, photoUrl, thumbUrl;
        public UserResult(String uid, String name, String callxId, String photoUrl, String thumbUrl) {
            this.uid = uid; this.name = name; this.callxId = callxId;
            this.photoUrl = photoUrl; this.thumbUrl = thumbUrl;
        }
    }

    // Matches item_search_result.xml iv_avatar (52dp), bucketed to the shared
    // MEDIUM tier (64dp) so this avatar reuses the same cached decode as any
    // other 52-64dp avatar view elsewhere in the app instead of its own
    // 52dp-only cache entry.
    private static final com.callx.app.utils.AvatarSizeTier AVATAR_TIER =
        com.callx.app.utils.AvatarSizeTier.MEDIUM;

    private final List<UserResult> list = new ArrayList<>();
    private OnUserClickListener listener;

    public void setListener(OnUserClickListener l) { this.listener = l; }

    public void setResults(List<UserResult> results) {
        list.clear();
        if (results != null) list.addAll(results);
        notifyDataSetChanged();
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
        h.tvCallxId.setText(u.callxId != null ? u.callxId : "");
        // Bug fix: previously loaded thumbUrl-or-else-raw-photoUrl directly with only
        // .override(96,96) — override just downsamples AFTER the bytes are downloaded,
        // it does not reduce network data. Many users have no thumbUrl yet, so this
        // silently downloaded the full 800x800 photo for every row while typing a
        // search. Now routed through the central AvatarUrlBuilder (same helper used by
        // ChatListAdapter/ReelCommentsAdapter/ProfileActivity) so Cloudinary serves an
        // already-resized, low-data variant — for the thumb AND as a safety net when
        // only the full photo exists.
        String baseUrl = (u.thumbUrl != null && !u.thumbUrl.isEmpty()) ? u.thumbUrl : u.photoUrl;
        String avatarUrl = AvatarUrlBuilder.build(h.ivAvatar.getContext(), baseUrl, AVATAR_TIER);
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            Glide.with(h.ivAvatar.getContext()).load(avatarUrl)
                .placeholder(R.drawable.ic_person).circleCrop()
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .override(AvatarUrlBuilder.tierPx(h.ivAvatar.getContext(), AVATAR_TIER))
                .into(h.ivAvatar);
        } else {
            h.ivAvatar.setImageResource(R.drawable.ic_person);
        }
        h.itemView.setOnClickListener(v -> {
            if (listener != null)
                listener.onUserClick(u.uid, u.name, u.photoUrl, u.thumbUrl, u.callxId);
        });
    }

    @Override public int getItemCount() { return list.size(); }

    static class VH extends RecyclerView.ViewHolder {
        CircleImageView ivAvatar;
        TextView tvName, tvCallxId;
        ImageView ivArrow;
        VH(View v) {
            super(v);
            ivAvatar   = v.findViewById(R.id.iv_avatar);
            tvName     = v.findViewById(R.id.tv_name);
            tvCallxId  = v.findViewById(R.id.tv_callx_id);
            ivArrow    = v.findViewById(R.id.iv_arrow);
        }
    }
}
