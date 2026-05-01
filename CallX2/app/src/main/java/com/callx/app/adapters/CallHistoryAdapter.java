package com.callx.app.adapters;
import android.content.Context;
import android.content.Intent;
import android.view.*;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.R;
import com.callx.app.activities.CallActivity;
import com.callx.app.models.CallLog;
import com.callx.app.utils.FileUtils;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
public class CallHistoryAdapter extends RecyclerView.Adapter<CallHistoryAdapter.VH> {
    private final List<CallLog> logs;
    private final SimpleDateFormat fmt = new SimpleDateFormat("dd MMM HH:mm", Locale.getDefault());
    public CallHistoryAdapter(List<CallLog> logs) { this.logs = logs; }
    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_call_history, parent, false);
        return new VH(v);
    }
    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        CallLog l = logs.get(pos);
        Context ctx = h.itemView.getContext();
        h.tvName.setText(l.partnerName == null ? "Unknown" : l.partnerName);
        String when = l.timestamp != null ? fmt.format(new Date(l.timestamp)) : "";
        String dur  = (l.duration != null && l.duration > 0)
            ? FileUtils.formatDuration(l.duration) : "—";
        h.tvMeta.setText(l.direction + " • " + l.mediaType + " • " + when + " • " + dur);
        h.btnCallBack.setOnClickListener(v -> {
            Intent i = new Intent(ctx, CallActivity.class);
            i.putExtra("partnerUid", l.partnerUid);
            i.putExtra("partnerName", l.partnerName);
            i.putExtra("isCaller", true);
            i.putExtra("video", "video".equals(l.mediaType));
            ctx.startActivity(i);
        });
    }
    @Override public int getItemCount() { return logs.size(); }
    static class VH extends RecyclerView.ViewHolder {
        TextView tvName, tvMeta;
        ImageButton btnCallBack;
        VH(View v) {
            super(v);
            tvName = v.findViewById(R.id.tv_name);
            tvMeta = v.findViewById(R.id.tv_meta);
            btnCallBack = v.findViewById(R.id.btn_call_back);
        }
    }
}
