package com.callx.app.community;

import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.db.entity.CommunityJoinRequestEntity;

import java.util.Collections;
import java.util.List;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * v31: RecyclerView adapter for pending join requests.
 */
public class CommunityJoinRequestAdapter
        extends RecyclerView.Adapter<CommunityJoinRequestAdapter.VH> {

    public interface Listener {
        void onApprove(CommunityJoinRequestEntity request);
        void onReject(CommunityJoinRequestEntity request);
    }

    private static final DiffUtil.ItemCallback<CommunityJoinRequestEntity> DIFF =
            new DiffUtil.ItemCallback<CommunityJoinRequestEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull CommunityJoinRequestEntity a,
                                               @NonNull CommunityJoinRequestEntity b) {
                    return a.id.equals(b.id);
                }
                @Override
                public boolean areContentsTheSame(@NonNull CommunityJoinRequestEntity a,
                                                  @NonNull CommunityJoinRequestEntity b) {
                    return a.status.equals(b.status);
                }
            };

    private final AsyncListDiffer<CommunityJoinRequestEntity> differ =
            new AsyncListDiffer<>(this, DIFF);
    private final Listener listener;

    public CommunityJoinRequestAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submitList(List<CommunityJoinRequestEntity> list) {
        differ.submitList(list == null ? Collections.emptyList() : list);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_community_join_request, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        CommunityJoinRequestEntity req = differ.getCurrentList().get(pos);

        h.tvName.setText(req.requesterName != null ? req.requesterName : "Unknown");
        h.tvTimestamp.setText(req.createdAt > 0
                ? DateUtils.getRelativeTimeSpanString(req.createdAt,
                System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) : "");

        if (req.message != null && !req.message.isEmpty()) {
            h.tvMessage.setVisibility(View.VISIBLE);
            h.tvMessage.setText("\"" + req.message + "\"");
        } else if (req.groupId != null) {
            // v32: Community Access System — ask-to-join a specific ADMIN_ONLY group
            h.tvMessage.setVisibility(View.VISIBLE);
            h.tvMessage.setText("Wants to join a group in this community");
        } else {
            h.tvMessage.setVisibility(View.GONE);
        }

        if (req.requesterPhoto != null && !req.requesterPhoto.isEmpty()) {
            Glide.with(h.ivAvatar.getContext()).load(req.requesterPhoto)
                    .override(96, 96)
                    .circleCrop().placeholder(R.drawable.ic_person).into(h.ivAvatar);
        } else {
            h.ivAvatar.setImageResource(R.drawable.ic_person);
        }

        h.btnApprove.setOnClickListener(v -> { if (listener != null) listener.onApprove(req); });
        h.btnReject.setOnClickListener(v -> { if (listener != null) listener.onReject(req); });
    }

    @Override
    public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        try { Glide.with(h.ivAvatar.getContext()).clear(h.ivAvatar); } catch (Exception ignored) {}
    }

    @Override
    public int getItemCount() { return differ.getCurrentList().size(); }

    static class VH extends RecyclerView.ViewHolder {
        CircleImageView ivAvatar;
        TextView tvName, tvTimestamp, tvMessage;
        Button btnApprove, btnReject;

        VH(@NonNull View itemView) {
            super(itemView);
            ivAvatar    = itemView.findViewById(R.id.iv_avatar);
            tvName      = itemView.findViewById(R.id.tv_name);
            tvTimestamp = itemView.findViewById(R.id.tv_timestamp);
            tvMessage   = itemView.findViewById(R.id.tv_message);
            btnApprove  = itemView.findViewById(R.id.btn_approve);
            btnReject   = itemView.findViewById(R.id.btn_reject);
        }
    }
}
