package com.callx.app.broadcast;

  import android.view.LayoutInflater;
  import android.view.View;
  import android.view.ViewGroup;
  import android.widget.CheckBox;
  import android.widget.ImageView;
  import android.widget.TextView;
  import androidx.annotation.NonNull;
  import androidx.recyclerview.widget.RecyclerView;
  import com.bumptech.glide.Glide;
  import com.callx.app.chat.R;
  import com.callx.app.models.User;
  import java.util.ArrayList;
  import java.util.HashSet;
  import java.util.List;
  import java.util.Set;

  public class BroadcastMemberSelectAdapter extends RecyclerView.Adapter<BroadcastMemberSelectAdapter.VH> {
      private final List<User> users;
      private final Set<String> selectedUids = new HashSet<>();

      public BroadcastMemberSelectAdapter(List<User> users) { this.users = users; }

      @NonNull @Override
      public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
          View v = LayoutInflater.from(p.getContext()).inflate(R.layout.item_member_select, p, false);
          return new VH(v);
      }

      @Override public void onBindViewHolder(@NonNull VH h, int pos) {
          User u = users.get(pos);
          h.tvName.setText(u.name);
          h.cbSelect.setChecked(selectedUids.contains(u.uid));
          Glide.with(h.ivAvatar.getContext()).load(u.photoUrl).circleCrop().placeholder(R.drawable.ic_person).into(h.ivAvatar);
          h.itemView.setOnClickListener(v -> {
              if (selectedUids.contains(u.uid)) selectedUids.remove(u.uid);
              else selectedUids.add(u.uid);
              notifyItemChanged(pos);
          });
      }

      @Override public int getItemCount() { return users.size(); }

      public List<User> getSelected() {
          List<User> sel = new ArrayList<>();
          for (User u : users) if (selectedUids.contains(u.uid)) sel.add(u);
          return sel;
      }

      static class VH extends RecyclerView.ViewHolder {
          ImageView ivAvatar; TextView tvName; CheckBox cbSelect;
          VH(View v) { super(v); ivAvatar=v.findViewById(R.id.iv_avatar); tvName=v.findViewById(R.id.tv_name); cbSelect=v.findViewById(R.id.cb_select); }
      }
  }