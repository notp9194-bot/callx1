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
  import com.callx.app.activities.XProfileActivity;
  import com.callx.app.activities.XTweetDetailActivity;
  import com.callx.app.models.XNotification;
  import com.callx.app.x.R;
  import java.util.ArrayList;
  import java.util.List;

  public class XNotificationAdapter extends RecyclerView.Adapter<XNotificationAdapter.VH> {

      private final Context ctx;
      private final List<XNotification> items = new ArrayList<>();

      public XNotificationAdapter(Context ctx) { this.ctx = ctx; }

      public void setItems(List<XNotification> list) {
          items.clear(); items.addAll(list); notifyDataSetChanged();
      }

      public void addItem(XNotification n) { items.add(0, n); notifyItemInserted(0); }

      @NonNull @Override
      public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
          return new VH(LayoutInflater.from(ctx).inflate(R.layout.item_x_notification, parent, false));
      }

      @Override
      public void onBindViewHolder(@NonNull VH h, int position) {
          h.bind(items.get(position));
      }

      @Override public int getItemCount() { return items.size(); }

      class VH extends RecyclerView.ViewHolder {
          ImageView ivAvatar, ivType;
          TextView tvTitle, tvSnippet, tvTime;

          VH(View v) {
              super(v);
              ivAvatar  = v.findViewById(R.id.iv_xn_avatar);
              ivType    = v.findViewById(R.id.iv_xn_type);
              tvTitle   = v.findViewById(R.id.tv_xn_title);
              tvSnippet = v.findViewById(R.id.tv_xn_snippet);
              tvTime    = v.findViewById(R.id.tv_xn_time);
          }

          void bind(XNotification n) {
              Glide.with(ctx).load(n.fromPhotoUrl).circleCrop()
                  .placeholder(R.drawable.ic_person).into(ivAvatar);
              tvTitle.setText(buildTitle(n));
              tvSnippet.setText(n.tweetSnippet != null ? n.tweetSnippet : "");
              tvSnippet.setVisibility(n.tweetSnippet != null && !n.tweetSnippet.isEmpty()
                  ? View.VISIBLE : View.GONE);
              tvTime.setText(formatTime(n.timestamp));

              // Type icon
              int iconRes = iconForType(n.type);
              ivType.setImageResource(iconRes);

              // Click
              itemView.setOnClickListener(v -> {
                  if (n.tweetId != null && !n.tweetId.isEmpty()) {
                      ctx.startActivity(new Intent(ctx, XTweetDetailActivity.class)
                          .putExtra("tweet_id", n.tweetId));
                  } else if (n.fromUid != null && !n.fromUid.isEmpty()) {
                      ctx.startActivity(new Intent(ctx, XProfileActivity.class)
                          .putExtra("uid", n.fromUid));
                  }
              });
          }

          private String buildTitle(XNotification n) {
              String name = n.fromName != null ? n.fromName : "Someone";
              switch (n.type != null ? n.type : "") {
                  case "like":    return name + " liked your post";
                  case "retweet": return name + " reposted your post";
                  case "reply":   return name + " replied to your post";
                  case "follow":  return name + " followed you";
                  case "mention": return name + " mentioned you";
                  case "quote":   return name + " quoted your post";
                  case "dm":      return name + " sent you a message";
                  default:        return name + " interacted with your post";
              }
          }

          private int iconForType(String type) {
              if (type == null) return R.drawable.ic_x_logo;
              switch (type) {
                  case "like":    return R.drawable.ic_x_heart_filled;
                  case "retweet": return R.drawable.ic_x_retweet;
                  case "follow":  return R.drawable.ic_person;
                  case "reply":   return R.drawable.ic_x_reply;
                  case "mention": return R.drawable.ic_x_reply;
                  case "dm":      return R.drawable.ic_x_dm;
                  default:        return R.drawable.ic_x_logo;
              }
          }

          private String formatTime(long ts) {
              long diff = System.currentTimeMillis() - ts;
              if (diff < 60_000) return diff / 1000 + "s";
              if (diff < 3_600_000) return diff / 60_000 + "m";
              if (diff < 86_400_000) return diff / 3_600_000 + "h";
              return diff / 86_400_000 + "d";
          }
      }
  }