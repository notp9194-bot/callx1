package com.callx.app.community;

import android.graphics.Bitmap;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;
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
    private RequestOptions avatarOptions;

    public CommunityJoinRequestAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submitList(List<CommunityJoinRequestEntity> list) {
        differ.submitList(list == null ? Collections.emptyList() : list);
    }

    private RequestOptions avatarOptions(android.content.Context ctx) {
        if (avatarOptions == null) {
            int px = Math.round(44 * ctx.getResources().getDisplayMetrics().density);
            avatarOptions = RequestOptions.circleCropTransform()
                    .override(px, px)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .diskCacheStrategy(DiskCacheStrategy.ALL);
        }
        return avatarOptions;
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

        if (h.avatarTarget != null) Glide.with(h.canvasView.getContext()).clear(h.avatarTarget);

        if (req.requesterPhoto != null && !req.requesterPhoto.isEmpty()) {
            final String reqId = req.id;
            h.avatarTarget = new CustomTarget<Bitmap>() {
                @Override public void onResourceReady(@NonNull Bitmap bmp, Transition<? super Bitmap> t) {
                    h.canvasView.setAvatarBitmap(reqId, bmp);
                }
                @Override public void onLoadCleared(android.graphics.drawable.Drawable p) {
                    h.canvasView.setAvatarBitmap(reqId, null);
                }
            };
            Glide.with(h.canvasView.getContext()).asBitmap()
                    .load(req.requesterPhoto)
                    .apply(avatarOptions(h.canvasView.getContext()))
                    .into(h.avatarTarget);
        } else {
            h.avatarTarget = null;
        }
    }

    @Override
    public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        if (h.avatarTarget != null) {
            Glide.with(h.canvasView.getContext()).clear(h.avatarTarget);
            h.avatarTarget = null;
        }
    }

    @Override
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
