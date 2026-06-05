package com.callx.app.chat.seenby;

  import android.view.LayoutInflater;
  import android.view.View;
  import android.view.ViewGroup;
  import android.widget.ImageView;
  import android.widget.TextView;
  import androidx.annotation.NonNull;
  import androidx.recyclerview.widget.RecyclerView;
  import com.bumptech.glide.Glide;
  import com.callx.app.chat.R;
  import java.text.SimpleDateFormat;
  import java.util.Date;
  import java.util.List;
  import java.util.Locale;

  public class SeenByAdapter extends RecyclerView.Adapter<SeenByAdapter.VH> {

      private final List<SeenByManager.SeenByEntry> entries;
      private final SimpleDateFormat fmt =
              new SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault());

      public SeenByAdapter(List<SeenByManager.SeenByEntry> entries) {
          this.entries = entries;
      }

      @NonNull @Override
      public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
          View v = LayoutInflater.from(parent.getContext())
                  .inflate(R.layout.item_seen_by_user, parent, false);
          return new VH(v);
      }

      @Override public void onBindViewHolder(@NonNull VH h, int pos) {
          SeenByManager.SeenByEntry e = entries.get(pos);
          h.tvName.setText(e.name != null ? e.name : e.uid);
          h.tvTime.setText(e.seenAt > 0 ? fmt.format(new Date(e.seenAt)) : "");
          Glide.with(h.ivAvatar.getContext())
               .load(e.photoUrl)
               .placeholder(R.drawable.ic_person)
               .circleCrop()
               .into(h.ivAvatar);
      }

      @Override public int getItemCount() { return entries.size(); }

      static class VH extends RecyclerView.ViewHolder {
          ImageView ivAvatar; TextView tvName, tvTime;
          VH(View v) {
              super(v);
              ivAvatar = v.findViewById(R.id.iv_avatar);
              tvName   = v.findViewById(R.id.tv_name);
              tvTime   = v.findViewById(R.id.tv_time);
          }
      }
  }