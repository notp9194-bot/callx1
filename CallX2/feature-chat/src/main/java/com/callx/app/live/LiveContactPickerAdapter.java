package com.callx.app.live;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.chat.R;
import com.callx.app.models.User;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LiveContactPickerAdapter extends RecyclerView.Adapter<LiveContactPickerAdapter.VH> {

    private final List<User> allContacts;
    private List<User> filtered;
    private final Set<String> selectedUids = new HashSet<>();
    private OnSelectionChangedListener listener;

    public interface OnSelectionChangedListener {
        void onSelectionChanged(int count);
    }

    public LiveContactPickerAdapter(List<User> contacts) {
        this.allContacts = contacts;
        this.filtered    = new ArrayList<>(contacts);
    }

    public void setOnSelectionChangedListener(OnSelectionChangedListener l) {
        this.listener = l;
    }

    public void filter(String query) {
        filtered = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) {
            filtered.addAll(allContacts);
        } else {
            String q = query.toLowerCase().trim();
            for (User u : allContacts) {
                if (u.name != null && u.name.toLowerCase().contains(q)) {
                    filtered.add(u);
                }
            }
        }
        notifyDataSetChanged();
    }

    public List<User> getSelectedContacts() {
        List<User> sel = new ArrayList<>();
        for (User u : allContacts) {
            if (u.uid != null && selectedUids.contains(u.uid)) sel.add(u);
        }
        return sel;
    }

    public int getSelectedCount() { return selectedUids.size(); }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_live_contact_picker, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        User u = filtered.get(pos);
        h.tvName.setText(u.name != null ? u.name : "User");

        String url = (u.thumbUrl != null && !u.thumbUrl.isEmpty()) ? u.thumbUrl : u.photoUrl;
        if (url != null && !url.isEmpty()) {
            Glide.with(h.ivAvatar.getContext())
                .load(url)
                .apply(RequestOptions.circleCropTransform())
                .placeholder(R.drawable.ic_person)
                .into(h.ivAvatar);
        } else {
            h.ivAvatar.setImageResource(R.drawable.ic_person);
        }

        boolean selected = u.uid != null && selectedUids.contains(u.uid);
        h.cbSelect.setChecked(selected);
        h.itemView.setAlpha(selected ? 1f : 0.85f);

        h.itemView.setOnClickListener(v -> {
            if (u.uid == null) return;
            if (selectedUids.contains(u.uid)) {
                selectedUids.remove(u.uid);
            } else {
                selectedUids.add(u.uid);
            }
            notifyItemChanged(h.getAdapterPosition());
            if (listener != null) listener.onSelectionChanged(selectedUids.size());
        });
    }

    @Override public int getItemCount() { return filtered.size(); }

    static class VH extends RecyclerView.ViewHolder {
        CircleImageView ivAvatar;
        TextView tvName;
        CheckBox cbSelect;
        VH(View v) {
            super(v);
            ivAvatar = v.findViewById(R.id.iv_live_contact_avatar);
            tvName   = v.findViewById(R.id.tv_live_contact_name);
            cbSelect = v.findViewById(R.id.cb_live_contact_select);
        }
    }
}
