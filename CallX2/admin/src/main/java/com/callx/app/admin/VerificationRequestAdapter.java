package com.callx.app.admin;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.admin.model.VerificationRequest;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain notifyDataSetChanged()-based adapter — the list is small (pending
 * verification requests only, not a general feed) so a full DiffUtil setup
 * isn't worth the extra code here.
 */
public class VerificationRequestAdapter extends RecyclerView.Adapter<VerificationRequestAdapter.VH> {

    public interface Listener {
        void onApprove(VerificationRequest request);
        void onReject(VerificationRequest request);
    }

    private final List<VerificationRequest> items = new ArrayList<>();
    private final Listener listener;

    public VerificationRequestAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submitList(List<VerificationRequest> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_verification_request, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        VerificationRequest req = items.get(position);
        holder.name.setText(req.name == null || req.name.isEmpty() ? "(no name)" : req.name);
        holder.uid.setText(req.uid);
        holder.reason.setText(req.reason == null || req.reason.isEmpty() ? "(no reason given)" : req.reason);
        // Keep avatar loading while avoiding a third-party image dependency.
        AdminImageLoader.load(holder.avatar, req.photoUrl);
        holder.btnApprove.setOnClickListener(v -> listener.onApprove(req));
        holder.btnReject.setOnClickListener(v -> listener.onReject(req));
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        ImageView avatar;
        TextView name, uid, reason;
        MaterialButton btnApprove, btnReject;

        VH(View v) {
            super(v);
            avatar     = v.findViewById(R.id.iv_request_avatar);
            name       = v.findViewById(R.id.tv_request_name);
            uid        = v.findViewById(R.id.tv_request_uid);
            reason     = v.findViewById(R.id.tv_request_reason);
            btnApprove = v.findViewById(R.id.btn_approve);
            btnReject  = v.findViewById(R.id.btn_reject);
        }
    }
}
