package com.callx.app.broadcast;

  import android.view.LayoutInflater;
  import android.view.View;
  import android.view.ViewGroup;
  import android.widget.TextView;
  import androidx.annotation.NonNull;
  import androidx.recyclerview.widget.RecyclerView;
  import com.callx.app.chat.R;
  import java.util.List;

  public class BroadcastListAdapter extends RecyclerView.Adapter<BroadcastListAdapter.VH> {
      public interface OnClickListener { void onClick(BroadcastList bl); }
      private final List<BroadcastList> items;
      private final OnClickListener listener;
      public BroadcastListAdapter(List<BroadcastList> items, OnClickListener l) {
          this.items = items; this.listener = l;
      }
      @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
          View v = LayoutInflater.from(p.getContext()).inflate(R.layout.item_broadcast_list, p, false);
          return new VH(v);
      }
      @Override public void onBindViewHolder(@NonNull VH h, int pos) {
          BroadcastList bl = items.get(pos);
          h.tvName.setText(bl.name);
          int count = bl.recipients != null ? bl.recipients.size() : 0;
          h.tvCount.setText(count + " recipients");
          h.itemView.setOnClickListener(v -> { if (listener!=null) listener.onClick(bl); });
      }
      @Override public int getItemCount() { return items.size(); }
      static class VH extends RecyclerView.ViewHolder {
          TextView tvName, tvCount;
          VH(View v) { super(v); tvName=v.findViewById(R.id.tv_name); tvCount=v.findViewById(R.id.tv_count); }
      }
  }