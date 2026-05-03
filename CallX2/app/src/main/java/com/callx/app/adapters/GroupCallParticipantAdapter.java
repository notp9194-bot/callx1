package com.callx.app.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.R;
import com.callx.app.activities.GroupCallActivity;
import org.webrtc.EglBase;
import org.webrtc.SurfaceViewRenderer;
import java.util.List;

/**
 * RecyclerView adapter for group call participants grid.
 * Each item shows a SurfaceViewRenderer (video) or avatar fallback (audio-only).
 */
public class GroupCallParticipantAdapter extends
        RecyclerView.Adapter<GroupCallParticipantAdapter.VH> {

    private final List<GroupCallActivity.ParticipantInfo> items;
    private final EglBase eglBase;

    public GroupCallParticipantAdapter(List<GroupCallActivity.ParticipantInfo> items,
                                        EglBase eglBase) {
        this.items   = items;
        this.eglBase = eglBase;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_group_call_participant, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        GroupCallActivity.ParticipantInfo info = items.get(pos);
        h.tvName.setText(info.name != null ? info.name : "Member");

        // Hand raised indicator
        h.ivHandRaised.setVisibility(info.handRaised ? View.VISIBLE : View.GONE);

        // Mic muted indicator
        h.ivMicOff.setVisibility(info.micOn ? View.GONE : View.VISIBLE);

        if (info.videoTrack != null && info.camOn && eglBase != null) {
            // Show video tile
            h.videoRenderer.setVisibility(View.VISIBLE);
            h.avatarContainer.setVisibility(View.GONE);
            try {
                h.videoRenderer.init(eglBase.getEglBaseContext(), null);
                h.videoRenderer.setMirror(false);
                info.videoTrack.addSink(h.videoRenderer);
            } catch (Exception ignored) {}
        } else {
            // Show avatar/initials tile
            h.videoRenderer.setVisibility(View.GONE);
            h.avatarContainer.setVisibility(View.VISIBLE);
            String initials = "";
            if (info.name != null && !info.name.isEmpty()) {
                String[] parts = info.name.trim().split("\\s+");
                initials += parts[0].charAt(0);
                if (parts.length > 1) initials += parts[parts.length - 1].charAt(0);
            }
            h.tvInitials.setText(initials.toUpperCase());
        }
    }

    @Override
    public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        try { h.videoRenderer.release(); } catch (Exception ignored) {}
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        SurfaceViewRenderer videoRenderer;
        FrameLayout avatarContainer;
        TextView tvInitials, tvName;
        ImageView ivMicOff, ivHandRaised;

        VH(View v) {
            super(v);
            videoRenderer   = v.findViewById(R.id.participantVideo);
            avatarContainer = v.findViewById(R.id.participantAvatarContainer);
            tvInitials      = v.findViewById(R.id.tvParticipantInitials);
            tvName          = v.findViewById(R.id.tvParticipantName);
            ivMicOff        = v.findViewById(R.id.ivParticipantMicOff);
            ivHandRaised    = v.findViewById(R.id.ivParticipantHandRaised);
        }
    }
}
