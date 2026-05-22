package com.callx.app.adapters;

  import android.content.Context;
  import android.content.Intent;
  import android.view.LayoutInflater;
  import android.view.View;
  import android.view.ViewGroup;
  import android.widget.ImageView;
  import android.widget.TextView;
  import androidx.annotation.NonNull;
  import androidx.recyclerview.widget.RecyclerView;
  import com.bumptech.glide.Glide;
  import com.bumptech.glide.request.RequestOptions;
  import com.callx.app.reels.R;
  import com.callx.app.activities.ReelLiveActivity;
  import de.hdodenhof.circleimageview.CircleImageView;
  import java.util.List;
  import java.util.Map;

  /** Horizontal adapter for the Live Now strip in HomeFragment. */
  public class HomeLiveAdapter extends RecyclerView.Adapter<HomeLiveAdapter.LiveVH> {

      public static class LiveUser {
          public String uid, name, photo, streamId;
          public long   viewerCount;
      }

      private final Context        ctx;
      private final List<LiveUser> items;

      public HomeLiveAdapter(Context ctx, List<LiveUser> items) {
          this.ctx   = ctx;
          this.items = items;
      }

      @NonNull @Override
      public LiveVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
          View v = LayoutInflater.from(ctx).inflate(R.layout.item_home_live_user, parent, false);
          return new LiveVH(v);
      }

      @Override
      public void onBindViewHolder(@NonNull LiveVH h, int pos) {
          LiveUser u = items.get(pos);
          Glide.with(ctx).load(u.photo)
              .apply(RequestOptions.centerCropTransform().placeholder(R.drawable.ic_person))
              .into(h.ivAvatar);
          Glide.with(ctx).load(u.photo)
              .apply(RequestOptions.centerCropTransform())
              .into(h.ivThumb);
          h.tvName.setText(u.name != null ? u.name : "");
          h.tvViewers.setText(formatCount(u.viewerCount));
          h.itemView.setOnClickListener(v -> {
              Intent i = new Intent(ctx, ReelLiveActivity.class);
              i.putExtra("streamId", u.streamId != null ? u.streamId : u.uid);
              i.putExtra("hostUid",  u.uid);
              i.putExtra("hostName", u.name);
              ctx.startActivity(i);
          });
      }

      @Override public int getItemCount() { return items.size(); }

      private String formatCount(long n) {
          if (n < 1000) return String.valueOf(n);
          return String.format("%.1fK", n / 1000.0);
      }

      static class LiveVH extends RecyclerView.ViewHolder {
          CircleImageView ivAvatar;
          ImageView       ivThumb;
          TextView        tvName, tvViewers;
          LiveVH(@NonNull View v) {
              super(v);
              ivAvatar  = v.findViewById(R.id.iv_live_avatar);
              ivThumb   = v.findViewById(R.id.iv_live_thumb);
              tvName    = v.findViewById(R.id.tv_live_name);
              tvViewers = v.findViewById(R.id.tv_live_viewers);
          }
      }
  }