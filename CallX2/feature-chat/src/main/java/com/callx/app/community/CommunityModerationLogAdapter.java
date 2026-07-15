package com.callx.app.community;

import android.graphics.Color;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.callx.app.db.entity.CommunityModerationLogEntity;

import java.util.Collections;
import java.util.List;

/**
 * v31: Adapter for moderation log entries. Color-codes by action severity.
 */
public class CommunityModerationLogAdapter
        extends RecyclerView.Adapter<CommunityModerationLogAdapter.VH> {

    private static final DiffUtil.ItemCallback<CommunityModerationLogEntity> DIFF =
            new DiffUtil.ItemCallback<CommunityModerationLogEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull CommunityModerationLogEntity a,
                                               @NonNull CommunityModerationLogEntity b) {
                    return a.id.equals(b.id);
                }
                @Override
                public boolean areContentsTheSame(@NonNull CommunityModerationLogEntity a,
                                                  @NonNull CommunityModerationLogEntity b) {
                    return a.id.equals(b.id);
                }
            };

    private final AsyncListDiffer<CommunityModerationLogEntity> differ =
            new AsyncListDiffer<>(this, DIFF);

    public void submitList(List<CommunityModerationLogEntity> list) {
        differ.submitList(list == null ? Collections.emptyList() : list);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_community_moderation_log, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        CommunityModerationLogEntity log = differ.getCurrentList().get(pos);

        String actionLabel = getActionLabel(log.action);
        String admin  = log.actionByName != null ? log.actionByName : "Admin";
        String target = log.targetName   != null ? log.targetName   : "Member";

        h.tvActionText.setText(admin + " → " + actionLabel + ": " + target);
        h.tvActionText.setTextColor(getActionColor(log.action));

        if (log.reason != null && !log.reason.isEmpty()) {
            h.tvReason.setVisibility(View.VISIBLE);
            h.tvReason.setText("Reason: " + log.reason);
        } else {
            h.tvReason.setVisibility(View.GONE);
        }

        h.tvActionTime.setText(log.createdAt > 0
                ? DateUtils.getRelativeTimeSpanString(log.createdAt,
                System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) : "");

        h.ivActionIcon.setImageResource(getActionIcon(log.action));
    }

    private String getActionLabel(String action) {
        if (action == null) return "Action";
        switch (action) {
            case "mute":         return "Muted";
            case "unmute":       return "Unmuted";
            case "ban":          return "Banned";
            case "unban":        return "Unbanned";
            case "delete_post":  return "Deleted Post";
            case "make_admin":   return "Made Admin";
            case "remove_admin": return "Removed Admin";
            case "approve_join": return "Approved Join";
            case "reject_join":  return "Rejected Join";
            case "report_post":  return "Reported Post";
            default:             return action;
        }
    }

    private int getActionColor(String action) {
        if (action == null) return Color.DKGRAY;
        switch (action) {
            case "ban":          return Color.parseColor("#F44336");
            case "mute":
            case "delete_post":
            case "report_post":  return Color.parseColor("#FF9800");
            case "make_admin":
            case "approve_join": return Color.parseColor("#2196F3");
            case "unban":
            case "unmute":       return Color.parseColor("#4CAF50");
            default:             return Color.DKGRAY;
        }
    }

    private int getActionIcon(String action) {
        if (action == null) return R.drawable.ic_shield;
        switch (action) {
            case "ban":          return R.drawable.ic_block;
            case "mute":         return R.drawable.ic_volume_off;
            case "delete_post":  return R.drawable.ic_delete;
            case "make_admin":   return R.drawable.ic_admin_panel;
            default:             return R.drawable.ic_shield;
        }
    }

    @Override
    public int getItemCount() { return differ.getCurrentList().size(); }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivActionIcon;
        TextView tvActionText, tvReason, tvActionTime;

        VH(@NonNull View itemView) {
            super(itemView);
            ivActionIcon = itemView.findViewById(R.id.iv_action_icon);
            tvActionText = itemView.findViewById(R.id.tv_action_text);
            tvReason     = itemView.findViewById(R.id.tv_reason);
            tvActionTime = itemView.findViewById(R.id.tv_action_time);
        }
    }
}
