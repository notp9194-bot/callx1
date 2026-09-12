package com.callx.app.group;

import android.view.*;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.chat.R;
import de.hdodenhof.circleimageview.CircleImageView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * GroupReadByAdapter — backs one tab (Read / Delivered / Pending) inside
 * GroupReadByActivity.  Each row shows: avatar · member name · timestamp.
 */
public class GroupReadByAdapter extends ListAdapter<GroupReadByAdapter.MemberItem, GroupReadByAdapter.VH> {

    public static class MemberItem {
        public final String uid, name, photoUrl;
        public final Long   timestamp;
        public MemberItem(String uid, String name, String photoUrl, Long timestamp) {
            this.uid = uid; this.name = name; this.photoUrl = photoUrl; this.timestamp = timestamp;
        }
    }

    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault());

    public GroupReadByAdapter() {
        super(new DiffUtil.ItemCallback<MemberItem>() {
            @Override public boolean areItemsTheSame(@NonNull MemberItem a, @NonNull MemberItem b) {
                return a.uid.equals(b.uid);
            }
            @Override public boolean areContentsTheSame(@NonNull MemberItem a, @NonNull MemberItem b) {
                return java.util.Objects.equals(a.timestamp, b.timestamp);
            }
        });
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
        View v = LayoutInflater.from(p.getContext())
                .inflate(R.layout.item_group_read_by_member, p, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) { h.bind(getItem(pos)); }

    @Override public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        com.callx.app.cache.ChatAvatarBinder.cancel(h.ivAvatar.getContext(), h.ivAvatar);
    }

    static class VH extends RecyclerView.ViewHolder {
        final CircleImageView ivAvatar;
        final TextView tvName, tvTime;
        final ImageView ivTick;

        VH(View v) {
            super(v);
            ivAvatar = v.findViewById(R.id.iv_avatar);
            tvName   = v.findViewById(R.id.tv_name);
            tvTime   = v.findViewById(R.id.tv_time);
            ivTick   = v.findViewById(R.id.iv_tick);
        }

        void bind(MemberItem item) {
            tvName.setText(item.name);
            tvTime.setText(item.timestamp != null && item.timestamp > 0
                    ? FMT.format(new Date(item.timestamp)) : "Pending");
            if (ivTick != null)
                ivTick.setVisibility(item.timestamp != null ? View.VISIBLE : View.GONE);

            if (item.photoUrl != null && !item.photoUrl.isEmpty()) {
                // FIX (avatar optimization — reuse core pipeline): was a
                // flat, un-tiered Glide load — shares L2/L3 with
                // GroupMemberAdapter's row for this exact member now.
                com.callx.app.cache.ChatAvatarBinder.bind(ivAvatar.getContext(), ivAvatar,
                        item.photoUrl, 0L, R.drawable.ic_person);
            } else {
                ivAvatar.setImageResource(R.drawable.ic_person);
            }
        }
    }
}
