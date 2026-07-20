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
import com.callx.app.community.canvas.CommunityEventCanvasView;
import com.callx.app.db.entity.CommunityEventEntity;

import java.util.Collections;
import java.util.List;

/**
 * v34-canvas: RecyclerView adapter for community events.
 * Migrated from item_community_event_v2.xml + MaterialButton inflate to
 * CommunityEventCanvasView — cover image, date box, RSVP buttons (Going /
 * Interested / Not Going), event-type badge, all drawn on canvas.
 *
 * Cover image loaded via Glide asBitmap() + CustomTarget (same pattern
 * as CommunityPostAdapter's mediaTarget for single-media posts).
 */
public class CommunityEventAdapter extends RecyclerView.Adapter<CommunityEventAdapter.VH> {

    public interface Listener {
        void onEventClicked(CommunityEventEntity event);
        void onRsvp(CommunityEventEntity event, String status); // "going"|"interested"|"not_going"
    }

    private static final DiffUtil.ItemCallback<CommunityEventEntity> DIFF =
            new DiffUtil.ItemCallback<CommunityEventEntity>() {
                @Override public boolean areItemsTheSame(@NonNull CommunityEventEntity a, @NonNull CommunityEventEntity b) { return a.id.equals(b.id); }
                @Override public boolean areContentsTheSame(@NonNull CommunityEventEntity a, @NonNull CommunityEventEntity b) {
                    return a.rsvpCount == b.rsvpCount
                            && a.interestedCount == b.interestedCount
                            && a.notGoingCount == b.notGoingCount
                            && a.startTimeMs == b.startTimeMs;
                }
            };

    private final AsyncListDiffer<CommunityEventEntity> differ = new AsyncListDiffer<>(this, DIFF);
    private final Listener listener;
    private String currentUid = "";
    private RequestOptions coverOptions;

    public CommunityEventAdapter(Listener listener) { this.listener = listener; }

    public void setCurrentUid(String uid) { this.currentUid = uid != null ? uid : ""; }

    public void submitList(List<CommunityEventEntity> list) {
        differ.submitList(list == null ? Collections.emptyList() : list);
    }

    private RequestOptions coverOptions(android.content.Context ctx) {
        if (coverOptions == null) {
            int w = Math.min(ctx.getResources().getDisplayMetrics().widthPixels, 1080);
            int h = Math.round(160 * ctx.getResources().getDisplayMetrics().density);
            coverOptions = RequestOptions.centerCropTransform()
                    .override(w, h)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .diskCacheStrategy(DiskCacheStrategy.ALL);
        }
        return coverOptions;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        CommunityEventCanvasView cv = new CommunityEventCanvasView(parent.getContext());
        cv.setLayoutParams(new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
        return new VH(cv);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        CommunityEventEntity ev = differ.getCurrentList().get(pos);
        h.canvasView.bind(ev, currentUid);
        h.canvasView.setListener(new CommunityEventCanvasView.Listener() {
            @Override public void onEventClicked(CommunityEventEntity e) { if (listener != null) listener.onEventClicked(e); }
            @Override public void onRsvp(CommunityEventEntity e, String status) { if (listener != null) listener.onRsvp(e, status); }
        });

        if (h.coverTarget != null) Glide.with(h.canvasView.getContext()).clear(h.coverTarget);

        if (ev.coverImageUrl != null && !ev.coverImageUrl.isEmpty()) {
            final String evId = ev.id;
            h.coverTarget = new CustomTarget<Bitmap>() {
                @Override public void onResourceReady(@NonNull Bitmap bmp, Transition<? super Bitmap> t) {
                    h.canvasView.setCoverBitmap(evId, bmp);
                }
                @Override public void onLoadCleared(android.graphics.drawable.Drawable p) {
                    h.canvasView.setCoverBitmap(evId, null);
                }
            };
            Glide.with(h.canvasView.getContext()).asBitmap()
                    .load(ev.coverImageUrl)
                    .apply(coverOptions(h.canvasView.getContext()))
                    .into(h.coverTarget);
        } else {
            h.coverTarget = null;
        }
    }

    @Override
    public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        if (h.coverTarget != null) {
            Glide.with(h.canvasView.getContext()).clear(h.coverTarget);
            h.coverTarget = null;
        }
    }

    @Override public int getItemCount() { return differ.getCurrentList().size(); }

    static class VH extends RecyclerView.ViewHolder {
        final CommunityEventCanvasView canvasView;
        Target<Bitmap> coverTarget;
        VH(@NonNull CommunityEventCanvasView cv) {
            super(cv);
            this.canvasView = cv;
        }
    }
}
