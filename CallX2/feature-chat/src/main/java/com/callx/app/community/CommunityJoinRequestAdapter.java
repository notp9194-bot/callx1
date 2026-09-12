package com.callx.app.community;

import android.graphics.Bitmap;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.request.target.Target;
import com.callx.app.cache.AvatarVersionSyncManager;
import com.callx.app.cache.CommunityAvatarBinder;
import com.callx.app.community.canvas.CommunityJoinRequestCanvasView;
import com.callx.app.db.entity.CommunityJoinRequestEntity;

import java.util.Collections;
import java.util.List;

/**
 * v34-canvas: RecyclerView adapter for pending join requests.
 * Migrated from item_community_join_request.xml + CircleImageView to
 * CommunityJoinRequestCanvasView — no XML inflate, avatar drawn on canvas
 * using Glide asBitmap() + CustomTarget (mirrors CommunityPostAdapter pattern).
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
        CommunityJoinRequestCanvasView cv = new CommunityJoinRequestCanvasView(parent.getContext());
        cv.setLayoutParams(new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
        return new VH(cv);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        CommunityJoinRequestEntity req = differ.getCurrentList().get(pos);
        h.canvasView.bind(req);
        h.canvasView.setListener(new CommunityJoinRequestCanvasView.Listener() {
            @Override public void onApprove(CommunityJoinRequestEntity r) { if (listener != null) listener.onApprove(r); }
            @Override public void onReject(CommunityJoinRequestEntity r)  { if (listener != null) listener.onReject(r);  }
        });

        if (h.avatarTarget != null) CommunityAvatarBinder.cancelBitmap(h.canvasView.getContext(), h.avatarTarget);

        // FIX (avatar-pipeline parity): was a hand-rolled 44dp*density
        // override, RGB_565, Glide's own ALL disk cache only — completely
        // disconnected from CommunityAvatarBinder's tiered/responsive URL
        // and ChatAvatarL2Cache L2/L3 + analytics every other community
        // avatar surface shares. TIER_MEMBER (44dp) matches this row's
        // actual draw size and lets the same requester's photo share
        // cache entries with a CommunityMemberAdapter row for that uid.
        if (req.requesterPhoto != null && !req.requesterPhoto.isEmpty()) {
            final String reqId = req.id;
            long requesterVersion = (req.requesterUid != null && !req.requesterUid.isEmpty())
                    ? AvatarVersionSyncManager.getInstance(h.canvasView.getContext()).getCachedVersion(req.requesterUid) : 0L;
            h.avatarTarget = CommunityAvatarBinder.bindBitmap(
                    h.canvasView.getContext(), req.requesterPhoto, CommunityAvatarBinder.TIER_MEMBER,
                    requesterVersion, bmp -> h.canvasView.setAvatarBitmap(reqId, bmp));
        } else {
            h.avatarTarget = null;
        }
    }

    @Override
    public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        if (h.avatarTarget != null) {
            CommunityAvatarBinder.cancelBitmap(h.canvasView.getContext(), h.avatarTarget);
            h.avatarTarget = null;
        }
    }

    public java.util.List<com.callx.app.db.entity.CommunityJoinRequestEntity> getCurrentList() { return differ.getCurrentList(); }
    public int getItemCount() { return differ.getCurrentList().size(); }

    static class VH extends RecyclerView.ViewHolder {
        final CommunityJoinRequestCanvasView canvasView;
        Target<Bitmap> avatarTarget;
        VH(@NonNull CommunityJoinRequestCanvasView cv) {
            super(cv);
            this.canvasView = cv;
        }
    }
}
