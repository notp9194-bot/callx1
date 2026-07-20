package com.callx.app.community;

import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.community.canvas.CommunityScheduledPostCanvasView;
import com.callx.app.db.entity.CommunityScheduledPostEntity;

import java.util.Collections;
import java.util.List;

/**
 * v34-canvas: Adapter for scheduled posts list.
 * Migrated from item_community_scheduled_post.xml to
 * CommunityScheduledPostCanvasView — no XML inflate, everything painted on canvas.
 */
public class CommunityScheduledPostAdapter
        extends RecyclerView.Adapter<CommunityScheduledPostAdapter.VH> {

    public interface Listener {
        void onCancelClicked(CommunityScheduledPostEntity post);
    }

    private static final DiffUtil.ItemCallback<CommunityScheduledPostEntity> DIFF =
            new DiffUtil.ItemCallback<CommunityScheduledPostEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull CommunityScheduledPostEntity a,
                                               @NonNull CommunityScheduledPostEntity b) {
                    return a.id.equals(b.id);
                }
                @Override
                public boolean areContentsTheSame(@NonNull CommunityScheduledPostEntity a,
                                                  @NonNull CommunityScheduledPostEntity b) {
                    return a.status.equals(b.status) && a.scheduledAt == b.scheduledAt;
                }
            };

    private final AsyncListDiffer<CommunityScheduledPostEntity> differ =
            new AsyncListDiffer<>(this, DIFF);
    private final Listener listener;

    public CommunityScheduledPostAdapter(Listener listener) { this.listener = listener; }

    public void submitList(List<CommunityScheduledPostEntity> list) {
        differ.submitList(list == null ? Collections.emptyList() : list);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        CommunityScheduledPostCanvasView cv = new CommunityScheduledPostCanvasView(parent.getContext());
        cv.setLayoutParams(new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
        return new VH(cv);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        CommunityScheduledPostEntity p = differ.getCurrentList().get(pos);
        h.canvasView.bind(p);
        h.canvasView.setListener(post -> { if (listener != null) listener.onCancelClicked(post); });
    }

    @Override
    public int getItemCount() { return differ.getCurrentList().size(); }

    static class VH extends RecyclerView.ViewHolder {
        final CommunityScheduledPostCanvasView canvasView;
        VH(@NonNull CommunityScheduledPostCanvasView cv) {
            super(cv);
            this.canvasView = cv;
        }
    }
}
