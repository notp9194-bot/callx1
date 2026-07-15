package com.callx.app.community;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.callx.app.db.entity.CommunityEventEntity;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * v31: RecyclerView adapter for community events.
 */
public class CommunityEventAdapter extends RecyclerView.Adapter<CommunityEventAdapter.VH> {

    public interface Listener {
        void onEventClicked(CommunityEventEntity event);
    }

    private static final DiffUtil.ItemCallback<CommunityEventEntity> DIFF =
            new DiffUtil.ItemCallback<CommunityEventEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull CommunityEventEntity a, @NonNull CommunityEventEntity b) {
                    return a.id.equals(b.id);
                }
                @Override
                public boolean areContentsTheSame(@NonNull CommunityEventEntity a, @NonNull CommunityEventEntity b) {
                    return a.rsvpCount == b.rsvpCount
                            && a.startTimeMs == b.startTimeMs;
                }
            };

    private final AsyncListDiffer<CommunityEventEntity> differ = new AsyncListDiffer<>(this, DIFF);
    private final Listener listener;

    public CommunityEventAdapter(Listener listener) { this.listener = listener; }

    public void submitList(List<CommunityEventEntity> list) {
        differ.submitList(list == null ? Collections.emptyList() : list);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_community_event, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        CommunityEventEntity ev = differ.getCurrentList().get(pos);

        h.tvTitle.setText(ev.title != null ? ev.title : "");
        h.tvDescription.setVisibility(
                ev.description != null && !ev.description.isEmpty() ? View.VISIBLE : View.GONE);
        h.tvDescription.setText(ev.description != null ? ev.description : "");
        h.tvRsvp.setText(ev.rsvpCount + " going");

        if (ev.location != null && !ev.location.isEmpty()) {
            h.tvLocation.setVisibility(View.VISIBLE);
            h.tvLocation.setText("📍 " + ev.location);
        } else {
            h.tvLocation.setVisibility(View.GONE);
        }

        if (ev.startTimeMs > 0) {
            SimpleDateFormat monthSdf = new SimpleDateFormat("MMM", Locale.getDefault());
            SimpleDateFormat daySdf   = new SimpleDateFormat("d",   Locale.getDefault());
            SimpleDateFormat timeSdf  = new SimpleDateFormat("EEE h:mm a", Locale.getDefault());
            Date d = new Date(ev.startTimeMs);
            h.tvMonth.setText(monthSdf.format(d).toUpperCase(Locale.ROOT));
            h.tvDay.setText(daySdf.format(d));
            h.tvTime.setText(timeSdf.format(d));
        }

        h.btnRsvp.setOnClickListener(v -> { if (listener != null) listener.onEventClicked(ev); });
        h.itemView.setOnClickListener(v -> { if (listener != null) listener.onEventClicked(ev); });
    }

    @Override
    public int getItemCount() { return differ.getCurrentList().size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvMonth, tvDay, tvTitle, tvTime, tvDescription, tvLocation, tvRsvp;
        Button btnRsvp;

        VH(@NonNull View itemView) {
            super(itemView);
            tvMonth       = itemView.findViewById(R.id.tv_event_month);
            tvDay         = itemView.findViewById(R.id.tv_event_day);
            tvTitle       = itemView.findViewById(R.id.tv_event_title);
            tvTime        = itemView.findViewById(R.id.tv_event_time);
            tvDescription = itemView.findViewById(R.id.tv_event_description);
            tvLocation    = itemView.findViewById(R.id.tv_event_location);
            tvRsvp        = itemView.findViewById(R.id.tv_event_rsvp);
            btnRsvp       = itemView.findViewById(R.id.btn_rsvp);
        }
    }
}
