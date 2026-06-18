package com.callx.app.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.models.CollabModel;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CollabRequestAdapter extends RecyclerView.Adapter<CollabRequestAdapter.VH> {

    public interface ActionListener {
        void onAction(CollabModel collab, String action); // "accept" | "reject" | "cancel"
    }

    private final List<CollabModel> items;
    private final Context ctx;
    private final ActionListener listener;
    private boolean isPendingForMe; // true = show accept/reject; false = show cancel

    public CollabRequestAdapter(List<CollabModel> items, Context ctx, ActionListener listener) {
        this.items    = items;
        this.ctx      = ctx;
        this.listener = listener;
    }

    public void setIsPendingForMe(boolean v) { isPendingForMe = v; }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_collab_request, p, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        CollabModel m = items.get(pos);

        // Whose invite is this? Show the other side's info
        boolean iAmInvitee = isPendingForMe;
        String displayName  = iAmInvitee ? m.ownerName   : m.inviteeName;
        String displayPhoto = iAmInvitee ? m.ownerPhoto  : m.inviteePhoto;
        String roleLabel    = iAmInvitee ? "invited you to collab" : "invite pending";

        h.tvName.setText(displayName != null ? displayName : "Unknown");
        h.tvRole.setText(roleLabel);
        h.tvReelId.setText("Reel: " + (m.reelId != null ? m.reelId.substring(0, 8) + "..." : "—"));
        h.tvStatus.setText("Status: " + (m.status != null ? m.status.toUpperCase() : "PENDING"));

        String time = new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
            .format(new Date(m.invitedAt));
        h.tvTime.setText(time);

        if (displayPhoto != null && !displayPhoto.isEmpty()) {
            Glide.with(ctx).load(displayPhoto).circleCrop().into(h.ivAvatar);
        } else {
            h.ivAvatar.setImageResource(R.drawable.ic_default_avatar);
        }

        if (iAmInvitee) {
            h.btnAccept.setVisibility(View.VISIBLE);
            h.btnReject.setVisibility(View.VISIBLE);
            h.btnCancel.setVisibility(View.GONE);
            h.btnAccept.setOnClickListener(v -> listener.onAction(m, "accept"));
            h.btnReject.setOnClickListener(v -> listener.onAction(m, "reject"));
        } else {
            h.btnAccept.setVisibility(View.GONE);
            h.btnReject.setVisibility(View.GONE);
            h.btnCancel.setVisibility(View.VISIBLE);
            h.btnCancel.setOnClickListener(v -> listener.onAction(m, "cancel"));
        }
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivAvatar;
        TextView tvName, tvRole, tvReelId, tvStatus, tvTime;
        Button btnAccept, btnReject, btnCancel;

        VH(View v) {
            super(v);
            ivAvatar  = v.findViewById(R.id.iv_collab_avatar);
            tvName    = v.findViewById(R.id.tv_collab_name);
            tvRole    = v.findViewById(R.id.tv_collab_role);
            tvReelId  = v.findViewById(R.id.tv_collab_reel_id);
            tvStatus  = v.findViewById(R.id.tv_collab_status);
            tvTime    = v.findViewById(R.id.tv_collab_time);
            btnAccept = v.findViewById(R.id.btn_accept_collab);
            btnReject = v.findViewById(R.id.btn_reject_collab);
            btnCancel = v.findViewById(R.id.btn_cancel_collab);
        }
    }
}
