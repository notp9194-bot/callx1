package com.callx.app.group;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.calls.R;
import com.callx.app.group.GroupCallActivity;
import org.webrtc.EglBase;
import org.webrtc.SurfaceViewRenderer;
import java.util.List;

/**
 * RecyclerView adapter for group call participants grid.
 * Each item shows a SurfaceViewRenderer (video) or avatar fallback (audio-only).
 *
 * FIX-3: eglBase was final — passed as null from onCreate before WebRTC init.
 * Now mutable via setEglBase() called after EglBase.create() in initWebRTC().
 * Also fixed onViewRecycled crash: release() called on renderer that may never
 * have been init()'d — now guarded with isInitialized flag per ViewHolder.
 */
public class GroupCallParticipantAdapter extends
        RecyclerView.Adapter<GroupCallParticipantAdapter.VH> {

    private final List<GroupCallActivity.ParticipantInfo> items;
    // FIX-3: non-final so setEglBase() can update after WebRTC init
    private EglBase eglBase;

    public GroupCallParticipantAdapter(List<GroupCallActivity.ParticipantInfo> items,
                                        EglBase eglBase) {
        this.items   = items;
        this.eglBase = eglBase;
    }

    /** FIX-3: Called from GroupCallActivity.initWebRTC() after EglBase.create() */
    public void setEglBase(EglBase base) {
        this.eglBase = base;
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
                // FIX-3: release before re-init to prevent "already initialized" crash on recycle
                if (h.rendererInitialized) {
                    h.videoRenderer.release();
                    h.rendererInitialized = false;
                }
                h.videoRenderer.init(eglBase.getEglBaseContext(), null);
                h.rendererInitialized = true;
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
        // FIX-3: Only release if actually initialized — prevents crash on never-init'd renderers
        if (h.rendererInitialized) {
            try { h.videoRenderer.release(); } catch (Exception ignored) {}
            h.rendererInitialized = false;
        }
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        SurfaceViewRenderer videoRenderer;
        FrameLayout avatarContainer;
        TextView tvInitials, tvName;
        ImageView ivMicOff, ivHandRaised;
        // FIX-3: track whether renderer has been init()'d
        boolean rendererInitialized = false;

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
