package com.callx.app.community;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.db.entity.CommunityEventEntity;
import com.google.android.material.button.MaterialButton;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * v34: RecyclerView adapter for community events — upgraded with:
 *  - Cover/banner image
 *  - Three RSVP buttons: Going / Interested / Not Going (with counts)
 *  - Event type badge (ONLINE / HYBRID / OFFLINE)
 *  - Online link chip (for ONLINE/HYBRID events)
 */
public class CommunityEventAdapter extends RecyclerView.Adapter<CommunityEventAdapter.VH> {

    public interface Listener {
        void onEventClicked(CommunityEventEntity event);
        /** v34: called when user taps one of the 3 RSVP buttons */
        void onRsvp(CommunityEventEntity event, String status); // "going"|"interested"|"not_going"
    }

    private static final DiffUtil.ItemCallback<CommunityEventEntity> DIFF =
            new DiffUtil.ItemCallback<CommunityEventEntity>() {
                @Override public boolean areItemsTheSame(@NonNull CommunityEventEntity a, @NonNull CommunityEventEntity b) { return a.id.equals(b.id); }
                @Override public boolean areContentsTheSame(@NonNull CommunityEventEntity a, @NonNull CommunityEventEntity b) {
                    return a.rsvpCount == b.rsvpCount
                            && a.interestedCount == b.interestedCount
                            && a.notGoingCount == b.notGoingCount
                            && a.startTimeMs == b.startTimeMs;
                }
            };

    private final AsyncListDiffer<CommunityEventEntity> differ = new AsyncListDiffer<>(this, DIFF);
    private final Listener listener;
    private String currentUid = "";

    public CommunityEventAdapter(Listener listener) { this.listener = listener; }

    public void setCurrentUid(String uid) { this.currentUid = uid != null ? uid : ""; }

    public void submitList(List<CommunityEventEntity> list) {
        differ.submitList(list == null ? Collections.emptyList() : list);
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_community_event_v2, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        CommunityEventEntity ev = differ.getCurrentList().get(pos);

        // Cover image
        if (ev.coverImageUrl != null && !ev.coverImageUrl.isEmpty()) {
            h.ivCover.setVisibility(View.VISIBLE);
            Glide.with(h.ivCover.getContext()).load(ev.coverImageUrl)
                    .centerCrop().override(800, 300)
                    .placeholder(R.drawable.ic_gallery).into(h.ivCover);
        } else {
            h.ivCover.setVisibility(View.GONE);
        }

        // Title / description
        h.tvTitle.setText(ev.title != null ? ev.title : "");
        h.tvDescription.setVisibility(
                ev.description != null && !ev.description.isEmpty() ? View.VISIBLE : View.GONE);
        h.tvDescription.setText(ev.description != null ? ev.description : "");

        // Location
        if (ev.location != null && !ev.location.isEmpty()) {
            h.tvLocation.setVisibility(View.VISIBLE);
            h.tvLocation.setText("📍 " + ev.location);
        } else { h.tvLocation.setVisibility(View.GONE); }

        // Event type badge
        if (ev.eventType != null && !"OFFLINE".equals(ev.eventType)) {
            h.tvEventType.setVisibility(View.VISIBLE);
            h.tvEventType.setText("ONLINE".equals(ev.eventType) ? "🌐 Online" : "🔀 Hybrid");
        } else { h.tvEventType.setVisibility(View.GONE); }

        // Online link
        if ((ev.onlineLink != null && !ev.onlineLink.isEmpty())
                && ("ONLINE".equals(ev.eventType) || "HYBRID".equals(ev.eventType))) {
            h.tvOnlineLink.setVisibility(View.VISIBLE);
            h.tvOnlineLink.setText("🔗 " + ev.onlineLink);
        } else { h.tvOnlineLink.setVisibility(View.GONE); }

        // Date / time
        if (ev.startTimeMs > 0) {
            SimpleDateFormat monthSdf = new SimpleDateFormat("MMM", Locale.getDefault());
            SimpleDateFormat daySdf   = new SimpleDateFormat("d",   Locale.getDefault());
            SimpleDateFormat timeSdf  = new SimpleDateFormat("EEE h:mm a", Locale.getDefault());
            Date d = new Date(ev.startTimeMs);
            h.tvMonth.setText(monthSdf.format(d).toUpperCase(Locale.ROOT));
            h.tvDay.setText(daySdf.format(d));
            h.tvTime.setText(timeSdf.format(d));
        }

        // RSVP counts
        h.btnGoing.setText("✅ Going (" + ev.rsvpCount + ")");
        h.btnInterested.setText("⭐ Interested (" + ev.interestedCount + ")");
        h.btnNotGoing.setText("❌ (" + ev.notGoingCount + ")");

        // Determine current user's RSVP from rsvpJson
        String myStatus = getRsvpStatus(ev.rsvpJson, currentUid);
        updateRsvpButtonStates(h, myStatus);

        h.btnGoing.setOnClickListener(v -> {
            if (listener != null) listener.onRsvp(ev, "going");
        });
        h.btnInterested.setOnClickListener(v -> {
            if (listener != null) listener.onRsvp(ev, "interested");
        });
        h.btnNotGoing.setOnClickListener(v -> {
            if (listener != null) listener.onRsvp(ev, "not_going");
        });

        h.itemView.setOnClickListener(v -> { if (listener != null) listener.onEventClicked(ev); });
    }

    private void updateRsvpButtonStates(VH h, String myStatus) {
        int activeColor = 0xFF1976D2; // brand blue
        int defaultColor= 0xFF757575;
        h.btnGoing.setTextColor("going".equals(myStatus) ? 0xFF4CAF50 : defaultColor);
        h.btnInterested.setTextColor("interested".equals(myStatus) ? 0xFFFF9800 : defaultColor);
        h.btnNotGoing.setTextColor("not_going".equals(myStatus) ? 0xFFF44336 : defaultColor);
    }

    private String getRsvpStatus(String rsvpJson, String uid) {
        if (rsvpJson == null || uid == null || uid.isEmpty()) return null;
        try {
            org.json.JSONObject obj = new org.json.JSONObject(rsvpJson);
            return obj.optString(uid, null);
        } catch (Exception e) { return null; }
    }

    @Override public int getItemCount() { return differ.getCurrentList().size(); }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivCover;
        TextView tvMonth, tvDay, tvTitle, tvTime, tvDescription, tvLocation;
        TextView tvEventType, tvOnlineLink;
        MaterialButton btnGoing, btnInterested, btnNotGoing;

        VH(@NonNull View v) {
            super(v);
            ivCover        = v.findViewById(R.id.iv_event_cover);
            tvMonth        = v.findViewById(R.id.tv_event_month);
            tvDay          = v.findViewById(R.id.tv_event_day);
            tvTitle        = v.findViewById(R.id.tv_event_title);
            tvTime         = v.findViewById(R.id.tv_event_time);
            tvDescription  = v.findViewById(R.id.tv_event_description);
            tvLocation     = v.findViewById(R.id.tv_event_location);
            tvEventType    = v.findViewById(R.id.tv_event_type_badge);
            tvOnlineLink   = v.findViewById(R.id.tv_event_online_link);
            btnGoing       = v.findViewById(R.id.btn_rsvp_going);
            btnInterested  = v.findViewById(R.id.btn_rsvp_interested);
            btnNotGoing    = v.findViewById(R.id.btn_rsvp_not_going);
        }
    }
}
