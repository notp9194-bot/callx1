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
import com.callx.app.community.canvas.CommunityMemberSearchCanvasView;
import com.callx.app.db.entity.CommunityMemberEntity;

import java.util.Collections;
import java.util.List;

/**
 * v34-canvas: Adapter for member search results.
 * Migrated from item_community_search_result_member.xml to
 * CommunityMemberSearchCanvasView — avatar + name + role, no XML inflate.
 */
public class CommunityMemberSearchAdapter
        extends RecyclerView.Adapter<CommunityMemberSearchAdapter.VH> {

    private static final DiffUtil.ItemCallback<CommunityMemberEntity> DIFF =
            new DiffUtil.ItemCallback<CommunityMemberEntity>() {
                @Override public boolean areItemsTheSame(@NonNull CommunityMemberEntity a, @NonNull CommunityMemberEntity b) {
                    return a.uid.equals(b.uid);
                }
                @Override public boolean areContentsTheSame(@NonNull CommunityMemberEntity a, @NonNull CommunityMemberEntity b) {
                    return a.uid.equals(b.uid) && a.role != null && a.role.equals(b.role);
                }
            };

    private final AsyncListDiffer<CommunityMemberEntity> differ = new AsyncListDiffer<>(this, DIFF);

    public void submitList(List<CommunityMemberEntity> list) {
        differ.submitList(list == null ? Collections.emptyList() : list);
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        CommunityMemberSearchCanvasView cv = new CommunityMemberSearchCanvasView(parent.getContext());
        cv.setLayoutParams(new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
        return new VH(cv);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        CommunityMemberEntity m = differ.getCurrentList().get(pos);
        h.canvasView.bind(m);

        if (h.avatarTarget != null) CommunityAvatarBinder.cancelBitmap(h.canvasView.getContext(), h.avatarTarget);

        // FIX (avatar-pipeline parity): was a hand-rolled 40dp*density
        // override, RGB_565, Glide's own ALL disk cache only — disconnected
        // from CommunityAvatarBinder's tiered/responsive URL and
        // ChatAvatarL2Cache L2/L3 + analytics. TIER_POST_AUTHOR (40dp)
        // matches this row's actual draw size, so the same member's photo
        // shares cache entries with their post-author avatar elsewhere.
        if (m.photoUrl != null && !m.photoUrl.isEmpty()) {
            long memberVersion = (m.uid != null && !m.uid.isEmpty())
                    ? AvatarVersionSyncManager.getInstance(h.canvasView.getContext()).getCachedVersion(m.uid) : 0L;
            h.avatarTarget = CommunityAvatarBinder.bindBitmap(
                    h.canvasView.getContext(), m.photoUrl, CommunityAvatarBinder.TIER_POST_AUTHOR,
                    memberVersion, bmp -> h.canvasView.setAvatarBitmap(bmp));
        } else {
            h.avatarTarget = null;
        }
    }

    @Override public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        if (h.avatarTarget != null) {
            CommunityAvatarBinder.cancelBitmap(h.canvasView.getContext(), h.avatarTarget);
            h.avatarTarget = null;
        }
    }

    @Override public int getItemCount() { return differ.getCurrentList().size(); }

    static class VH extends RecyclerView.ViewHolder {
        final CommunityMemberSearchCanvasView canvasView;
        Target<Bitmap> avatarTarget;
        VH(@NonNull CommunityMemberSearchCanvasView cv) {
            super(cv);
            this.canvasView = cv;
        }
    }
}
