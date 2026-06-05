package com.callx.app.chat.search;

  import android.graphics.Typeface;
  import android.text.SpannableString;
  import android.text.Spanned;
  import android.text.style.StyleSpan;
  import android.view.LayoutInflater;
  import android.view.View;
  import android.view.ViewGroup;
  import android.widget.TextView;
  import androidx.annotation.NonNull;
  import androidx.recyclerview.widget.RecyclerView;
  import com.callx.app.chat.R;
  import com.callx.app.db.entity.MessageEntity;
  import java.text.SimpleDateFormat;
  import java.util.Date;
  import java.util.List;
  import java.util.Locale;

  public class ChatSearchAdapter extends RecyclerView.Adapter<ChatSearchAdapter.VH> {

      private final List<MessageEntity> items;
      private String query = "";
      private final SimpleDateFormat fmt =
              new SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault());

      public ChatSearchAdapter(List<MessageEntity> items) { this.items = items; }
      public void setQuery(String q) { this.query = q.toLowerCase(); }

      @NonNull @Override
      public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
          View v = LayoutInflater.from(p.getContext())
                  .inflate(R.layout.item_chat_search_result, p, false);
          return new VH(v);
      }

      @Override public void onBindViewHolder(@NonNull VH h, int pos) {
          MessageEntity msg = items.get(pos);
          h.tvDate.setText(msg.timestamp > 0 ? fmt.format(new Date(msg.timestamp)) : "");
          // Bold-highlight the matched query
          String text = msg.text != null ? msg.text : "";
          SpannableString sp = new SpannableString(text);
          int idx = text.toLowerCase().indexOf(query);
          if (idx >= 0) {
              sp.setSpan(new StyleSpan(Typeface.BOLD), idx, idx + query.length(),
                         Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
          }
          h.tvText.setText(sp);
      }

      @Override public int getItemCount() { return items.size(); }

      static class VH extends RecyclerView.ViewHolder {
          TextView tvText, tvDate;
          VH(View v) {
              super(v);
              tvText = v.findViewById(R.id.tv_text);
              tvDate = v.findViewById(R.id.tv_date);
          }
      }
  }