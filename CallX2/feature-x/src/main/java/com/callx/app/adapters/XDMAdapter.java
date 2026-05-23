package com.callx.app.adapters;

  import android.content.Context;
  import android.view.Gravity;
  import android.view.LayoutInflater;
  import android.view.View;
  import android.view.ViewGroup;
  import android.widget.LinearLayout;
  import android.widget.TextView;
  import androidx.annotation.NonNull;
  import androidx.recyclerview.widget.RecyclerView;
  import com.callx.app.models.XMessage;
  import com.callx.app.x.R;
  import java.text.SimpleDateFormat;
  import java.util.ArrayList;
  import java.util.Date;
  import java.util.List;
  import java.util.Locale;

  public class XDMAdapter extends RecyclerView.Adapter<XDMAdapter.VH> {

      private final Context ctx;
      private final String myUid;
      private final List<XMessage> msgs = new ArrayList<>();

      public XDMAdapter(Context ctx, String myUid) { this.ctx = ctx; this.myUid = myUid; }

      public void setMessages(List<XMessage> list) {
          msgs.clear(); msgs.addAll(list); notifyDataSetChanged();
      }

      @NonNull @Override
      public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
          return new VH(LayoutInflater.from(ctx).inflate(R.layout.item_x_dm_message, parent, false));
      }

      @Override public void onBindViewHolder(@NonNull VH h, int pos) { h.bind(msgs.get(pos)); }
      @Override public int getItemCount() { return msgs.size(); }

      class VH extends RecyclerView.ViewHolder {
          TextView tvText, tvTime;
          LinearLayout llBubble;

          VH(View v) {
              super(v);
              tvText   = v.findViewById(R.id.tv_xdm_text);
              tvTime   = v.findViewById(R.id.tv_xdm_msg_time);
              llBubble = v.findViewById(R.id.ll_xdm_bubble);
          }

          void bind(XMessage m) {
              boolean mine = myUid.equals(m.senderId);
              tvText.setText(m.text);
              tvTime.setText(new SimpleDateFormat("HH:mm", Locale.US).format(new Date(m.timestamp)));
              // Align: mine=right, other=left
              LinearLayout root = (LinearLayout) itemView;
              root.setGravity(mine ? Gravity.END : Gravity.START);
              llBubble.setBackgroundResource(mine ? R.drawable.bg_x_bubble_sent : R.drawable.bg_x_bubble_recv);
          }
      }
  }