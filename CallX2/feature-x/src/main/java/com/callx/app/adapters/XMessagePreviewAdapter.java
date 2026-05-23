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
  import com.callx.app.activities.XDMConversationActivity;
  import com.callx.app.x.R;
  import java.util.ArrayList;
  import java.util.List;

  public class XMessagePreviewAdapter extends RecyclerView.Adapter<XMessagePreviewAdapter.VH> {

      public static class ConversationPreview {
          public String conversationId, otherUid, otherName, otherHandle, otherPhotoUrl;
          public String lastMessage;
          public long lastTs;
          public boolean unread;
          public boolean otherVerified;
      }

      private final Context ctx;
      private final List<ConversationPreview> items = new ArrayList<>();

      public XMessagePreviewAdapter(Context ctx) { this.ctx = ctx; }

      public void setItems(List<ConversationPreview> list) {
          items.clear(); items.addAll(list); notifyDataSetChanged();
      }

      @NonNull @Override
      public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
          return new VH(LayoutInflater.from(ctx).inflate(R.layout.item_x_dm_preview, parent, false));
      }

      @Override
      public void onBindViewHolder(@NonNull VH h, int position) {
          h.bind(items.get(position));
      }

      @Override public int getItemCount() { return items.size(); }

      class VH extends RecyclerView.ViewHolder {
          ImageView ivAvatar, ivVerified;
          TextView tvName, tvHandle, tvLastMsg, tvTime;
          View tvUnread;

          VH(View v) {
              super(v);
              ivAvatar  = v.findViewById(R.id.iv_xdm_avatar);
              ivVerified= v.findViewById(R.id.iv_xdm_verified);
              tvName    = v.findViewById(R.id.tv_xdm_name);
              tvHandle  = v.findViewById(R.id.tv_xdm_handle);
              tvLastMsg = v.findViewById(R.id.tv_xdm_last_msg);
              tvTime    = v.findViewById(R.id.tv_xdm_time);
              tvUnread  = v.findViewById(R.id.tv_xdm_unread_dot);
          }

          void bind(ConversationPreview p) {
              Glide.with(ctx).load(p.otherPhotoUrl).circleCrop()
                  .placeholder(R.drawable.ic_person).into(ivAvatar);
              tvName.setText(p.otherName);
              tvHandle.setText("@" + p.otherHandle);
              tvLastMsg.setText(p.lastMessage != null ? p.lastMessage : "");
              tvTime.setText(formatTime(p.lastTs));
              ivVerified.setVisibility(p.otherVerified ? View.VISIBLE : View.GONE);
              tvUnread.setVisibility(p.unread ? View.VISIBLE : View.GONE);

              itemView.setOnClickListener(v ->
                  ctx.startActivity(new Intent(ctx, XDMConversationActivity.class)
                      .putExtra("conversation_id", p.conversationId)
                      .putExtra("other_uid", p.otherUid)
                      .putExtra("other_name", p.otherName)
                      .putExtra("other_handle", p.otherHandle)
                      .putExtra("other_photo", p.otherPhotoUrl)));
          }

          private String formatTime(long ts) {
              long diff = System.currentTimeMillis() - ts;
              if (diff < 60_000) return "now";
              if (diff < 3_600_000) return diff / 60_000 + "m";
              if (diff < 86_400_000) return diff / 3_600_000 + "h";
              return diff / 86_400_000 + "d";
          }
      }
  }