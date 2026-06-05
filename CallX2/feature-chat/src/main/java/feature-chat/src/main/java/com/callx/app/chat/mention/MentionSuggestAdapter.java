package com.callx.app.chat.mention;

  import android.content.Context;
  import android.view.LayoutInflater;
  import android.view.View;
  import android.view.ViewGroup;
  import android.widget.ImageView;
  import android.widget.TextView;
  import androidx.annotation.NonNull;
  import androidx.recyclerview.widget.RecyclerView;
  import com.bumptech.glide.Glide;
  import com.callx.app.chat.R;
  import com.callx.app.models.User;
  import java.util.ArrayList;
  import java.util.List;

  /**
   * MentionSuggestAdapter — Shows @mention suggestions in group chat.
   *
   * Trigger: user types '@' in message input.
   * Filters members whose name starts with text after '@'.
   * On tap: replaces @partial with @username in EditText.
   */
  public class MentionSuggestAdapter extends RecyclerView.Adapter<MentionSuggestAdapter.VH> {

      public interface OnMentionClickListener {
          void onMentionSelected(User user);
      }

      private List<User> allMembers = new ArrayList<>();
      private List<User> filtered  = new ArrayList<>();
      private final OnMentionClickListener listener;

      public MentionSuggestAdapter(OnMentionClickListener listener) {
          this.listener = listener;
      }

      public void setMembers(List<User> members) {
          this.allMembers = members != null ? members : new ArrayList<>();
          filter("");
      }

      public void filter(String query) {
          filtered.clear();
          String q = query.toLowerCase().trim();
          for (User u : allMembers) {
              if (q.isEmpty() || (u.name != null && u.name.toLowerCase().startsWith(q))) {
                  filtered.add(u);
              }
          }
          notifyDataSetChanged();
      }

      @NonNull @Override
      public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
          View v = LayoutInflater.from(parent.getContext())
                  .inflate(R.layout.item_mention_suggest, parent, false);
          return new VH(v);
      }

      @Override public void onBindViewHolder(@NonNull VH h, int pos) {
          User user = filtered.get(pos);
          h.tvName.setText(user.name);
          h.tvUsername.setText("@" + (user.username != null ? user.username : user.name));
          Glide.with(h.ivAvatar.getContext())
               .load(user.photoUrl)
               .placeholder(R.drawable.ic_person)
               .circleCrop()
               .into(h.ivAvatar);
          h.itemView.setOnClickListener(v -> {
              if (listener != null) listener.onMentionSelected(user);
          });
      }

      @Override public int getItemCount() { return filtered.size(); }

      static class VH extends RecyclerView.ViewHolder {
          ImageView ivAvatar;
          TextView tvName, tvUsername;
          VH(View v) {
              super(v);
              ivAvatar   = v.findViewById(R.id.iv_avatar);
              tvName     = v.findViewById(R.id.tv_name);
              tvUsername = v.findViewById(R.id.tv_username);
          }
      }
  }