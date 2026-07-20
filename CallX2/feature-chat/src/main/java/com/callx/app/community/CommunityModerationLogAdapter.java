package com.callx.app.community;

import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.community.canvas.CommunityModerationLogCanvasView;
import com.callx.app.db.entity.CommunityModerationLogEntity;

import java.util.Collections;
import java.util.List;

/**
 * v34-canvas: Adapter for moderation log entries.
 * Migrated from item_community_moderation_log.xml to
 * CommunityModerationLogCanvasView — color-coded action dot, admin→action:target,
 * optional reason, timestamp — all painted on canvas.
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
        CommunityModerationLogCanvasView cv = new CommunityModerationLogCanvasView(parent.getContext());
        cv.setLayoutParams(new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
        return new VH(cv);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        h.canvasView.bind(differ.getCurrentList().get(pos));
    }

    @Override
    public int getItemCount() { return differ.getCurrentList().size(); }

    static class VH extends RecyclerView.ViewHolder {
        final CommunityModerationLogCanvasView canvasView;
        VH(@NonNull CommunityModerationLogCanvasView cv) {
            super(cv);
            this.canvasView = cv;
        }
    }
}
