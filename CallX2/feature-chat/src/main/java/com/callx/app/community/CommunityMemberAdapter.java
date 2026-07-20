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
import com.callx.app.community.canvas.CommunityMemberCanvasView;
import com.callx.app.db.entity.CommunityMemberEntity;

import java.util.Collections;
import java.util.List;

/**
 * v34-canvas: Member list adapter — migrated from item_community_member.xml to
 * CommunityMemberCanvasView. Role badges, member badges, muted state, options ⋮
 * all drawn on canvas. Avatar loaded via Glide + CustomTarget.
 */
public class CommunityMemberAdapter extends RecyclerView.Adapter<CommunityMemberAdapter.VH> {

    public interface OnMemberLongPressListener {
        void onLongPress(CommunityMemberEntity member);
    }

    private static final DiffUtil.ItemCallback<CommunityMemberEntity> DIFF =
            new DiffUtil.ItemCallback<CommunityMemberEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull CommunityMemberEntity a, @NonNull CommunityMemberEntity b) {
                    return a.uid.equals(b.uid);
                }
                @Override
                public boolean areContentsTheSame(@NonNull CommunityMemberEntity a, @NonNull CommunityMemberEntity b) {
                    return safeEq(a.role, b.role) && safeEq(a.badge, b.badge) && a.isMuted == b.isMuted;
                }
                private boolean safeEq(String x, String y) {
                    return x == null ? y == null : x.equals(y);
                }
            };

    private final AsyncListDiffer<CommunityMemberEntity> differ = new AsyncListDiffer<>(this, DIFF);
    private final String currentUid;
    private String myRole = CommunityRole.MEMBER;
    private OnMemberLongPressListener longPressListener;
    private RequestOptions avatarOptions;

    public CommunityMemberAdapter(String currentUid) {
        this.currentUid = currentUid;
    }

    public void setMyRole(String role) {
        this.myRole = role != null ? role : CommunityRole.MEMBER;
    }

    public void setOnMemberLongPressListener(OnMemberLongPressListener l) {
        this.longPressListener = l;
    }

    public void submitList(List<CommunityMemberEntity> list) {
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
        CommunityMemberCanvasView cv = new CommunityMemberCanvasView(parent.getContext());
        cv.setLayoutParams(new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
        return new VH(cv);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        CommunityMemberEntity m = differ.getCurrentList().get(pos);
        h.canvasView.bind(m, myRole, currentUid);
        h.canvasView.setListener(new CommunityMemberCanvasView.Listener() {
            @Override public void onOptionsClick(CommunityMemberEntity member) {
                if (longPressListener != null) longPressListener.onLongPress(member);
            }
            @Override public void onLongPress(CommunityMemberEntity member) {
                if (longPressListener != null) longPressListener.onLongPress(member);
            }
        });

        if (h.avatarTarget != null) Glide.with(h.canvasView.getContext()).clear(h.avatarTarget);

        if (m.photoUrl != null && !m.photoUrl.isEmpty()) {
            h.avatarTarget = new CustomTarget<Bitmap>() {
                @Override public void onResourceReady(@NonNull Bitmap bmp, Transition<? super Bitmap> t) {
                    h.canvasView.setAvatarBitmap(bmp);
                }
                @Override public void onLoadCleared(android.graphics.drawable.Drawable p) {
                    h.canvasView.setAvatarBitmap(null);
                }
            };
            Glide.with(h.canvasView.getContext()).asBitmap()
                    .load(m.photoUrl)
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
    public java.util.List<CommunityMemberEntity> getCurrentList() { return differ.getCurrentList(); }

    public int getItemCount() { return differ.getCurrentList().size(); }

    static class VH extends RecyclerView.ViewHolder {
        final CommunityMemberCanvasView canvasView;
        Target<Bitmap> avatarTarget;
        VH(@NonNull CommunityMemberCanvasView cv) {
            super(cv);
            this.canvasView = cv;
        }
    }
}
