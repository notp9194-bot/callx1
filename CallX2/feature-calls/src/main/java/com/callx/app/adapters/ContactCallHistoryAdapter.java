package com.callx.app.adapters;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.view.*;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.calls.R;
import com.callx.app.activities.CallActivity;
import com.callx.app.models.CallLog;
import com.callx.app.utils.FileUtils;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Adapter for the "Call History" section inside the contact bottom sheet.
 * Shows only calls with that specific contact — with type, time, duration,
 * and a quick call-back icon per row.
 */
public class ContactCallHistoryAdapter extends RecyclerView.Adapter<ContactCallHistoryAdapter.VH> {

    private final List<CallLog> logs;
    private final SimpleDateFormat fmt =
        new SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault());

    public ContactCallHistoryAdapter(List<CallLog> logs) {
        this.logs = logs;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_call_history_sheet, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        CallLog l = logs.get(pos);
        Context ctx = h.itemView.getContext();

        boolean isVideo  = "video".equals(l.mediaType);
        String  dir      = l.direction == null ? "" : l.direction.toLowerCase();

        // Label + color
        String label;
        int    iconColor;
        int    iconRes;

        if (dir.contains("missed")) {
            label     = isVideo ? "Missed Video" : "Missed Voice";
            iconColor = Color.parseColor("#EF4444");
            iconRes   = isVideo ? R.drawable.ic_video_call : R.drawable.ic_phone;
        } else if (dir.contains("incoming") || dir.contains("in")) {
            label     = isVideo ? "Incoming Video" : "Incoming Voice";
            iconColor = Color.parseColor("#22C55E");
            iconRes   = isVideo ? R.drawable.ic_video_call : R.drawable.ic_phone;
        } else {
            label     = isVideo ? "Outgoing Video" : "Outgoing Voice";
            iconColor = Color.parseColor("#5B5BF6");
            iconRes   = isVideo ? R.drawable.ic_video_call : R.drawable.ic_phone;
        }

        h.tvLabel.setText(label);
        h.tvLabel.setTextColor(iconColor);

        h.ivIcon.setImageResource(iconRes);
        h.ivIcon.setColorFilter(iconColor);

        // Time
        String when = l.timestamp != null ? fmt.format(new Date(l.timestamp)) : "—";
        h.tvTime.setText(when);

        // Duration
        if (l.duration != null && l.duration > 0) {
            h.tvDuration.setText(FileUtils.formatDuration(l.duration));
            h.tvDuration.setVisibility(View.VISIBLE);
        } else {
            h.tvDuration.setVisibility(View.GONE);
        }

        // Quick call back — same media type as this log entry
        h.ivQuickCall.setImageResource(isVideo ? R.drawable.ic_video_call : R.drawable.ic_phone);
        h.ivQuickCall.setColorFilter(Color.parseColor("#5B5BF6"));
        h.ivQuickCall.setOnClickListener(v -> {
            if (l.partnerUid == null) return;
            Intent i = new Intent(ctx, CallActivity.class);
            i.putExtra("partnerUid",  l.partnerUid);
            i.putExtra("partnerName", l.partnerName != null ? l.partnerName : "");
            i.putExtra("isCaller", true);
            i.putExtra("video", isVideo);
            ctx.startActivity(i);
        });
    }

    @Override public int getItemCount() { return logs.size(); }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivIcon, ivQuickCall;
        TextView  tvLabel, tvTime, tvDuration;
        VH(View v) {
            super(v);
            ivIcon      = v.findViewById(R.id.iv_call_type_icon);
            tvLabel     = v.findViewById(R.id.tv_call_type_label);
            tvTime      = v.findViewById(R.id.tv_call_time);
            tvDuration  = v.findViewById(R.id.tv_call_duration);
            ivQuickCall = v.findViewById(R.id.iv_quick_call);
        }
    }
}
