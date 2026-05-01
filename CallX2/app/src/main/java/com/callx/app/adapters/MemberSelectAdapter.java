package com.callx.app.adapters;
import android.view.*;
import android.widget.CheckBox;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.R;
import com.callx.app.models.User;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.List;
import java.util.Set;
public class MemberSelectAdapter
        extends RecyclerView.Adapter<MemberSelectAdapter.VH> {
    private final List<User> users;
    private final Set<String> selected;
    public MemberSelectAdapter(List<User> users, Set<String> selected) {
        this.users = users; this.selected = selected;
    }
    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_member_select, parent, false);
        return new VH(v);
    }
    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        User u = users.get(pos);
        h.tvName.setText(u.name == null ? "User" : u.name);
        if (u.photoUrl != null && !u.photoUrl.isEmpty()) {
            Glide.with(h.itemView.getContext()).load(u.photoUrl).into(h.ivAvatar);
        } else h.ivAvatar.setImageResource(R.drawable.ic_person);
        h.cb.setOnCheckedChangeListener(null);
        h.cb.setChecked(selected.contains(u.uid));
        h.cb.setOnCheckedChangeListener((b, c) -> {
            if (c) selected.add(u.uid); else selected.remove(u.uid);
        });
        h.itemView.setOnClickListener(v -> h.cb.setChecked(!h.cb.isChecked()));
    }
    @Override public int getItemCount() { return users.size(); }
    static class VH extends RecyclerView.ViewHolder {
        TextView tvName;
        CircleImageView ivAvatar;
        CheckBox cb;
        VH(View v) {
            super(v);
            tvName = v.findViewById(R.id.tv_name);
            ivAvatar = v.findViewById(R.id.iv_avatar);
            cb = v.findViewById(R.id.cb_select);
        }
    }
}
