package com.callx.app.community;

import android.graphics.Bitmap;
import android.text.format.DateUtils;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.community.canvas.CommunityNotificationCanvasView;
import com.callx.app.db.entity.CommunityNotificationEntity;

import java.util.Collections;
import java.util.List;

/**
 * v34-canvas: Adapter for in-app community notifications.
 * Migrated from XML inflate (item_community_notification.xml) to
 * CommunityNotificationCanvasView — same approach as CommunityPostAdapter v32
 * migration. No child-view inflate, no measure/layout pass per-row.
 */
public class CommunityNotificationAdapter
        extends RecyclerView.Adapter<CommunityNotificationAdapter.VH> {

    /** Payload tag for read-state-only partial rebind. */
    static final int PAYLOAD_READ_STATE = 1;

    public interface Listener {
        void onNotificationClicked(CommunityNotificationEntity notif);
    }

    private static final DiffUtil.ItemCallback<CommunityNotificationEntity> DIFF =
            new DiffUtil.ItemCallback<CommunityNotificationEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull CommunityNotificationEntity a,
                                               @NonNull CommunityNotificationEntity b) {
                    return a.id.equals(b.id);
                }
                @Override
                public boolean areContentsTheSame(@NonNull CommunityNotificationEntity a,
                                                  @NonNull CommunityNotificationEntity b) {
                    return a.isRead == b.isRead;
                }
                @Override
                public Object getChangePayload(@NonNull CommunityNotificationEntity a,
                                              @NonNull CommunityNotificationEntity b) {
                    // Only read-state changed — deliver a lightweight payload so
                    // onBindViewHolder can repaint just the dot/bg, not the full row.
                    return a.isRead != b.isRead ? PAYLOAD_READ_STATE : null;
                }
            };

    private final AsyncListDiffer<CommunityNotificationEntity> differ =
            new AsyncListDiffer<>(this, DIFF);
    private final Listener listener;

    public CommunityNotificationAdapter(Listener listener) { this.listener = listener; }

    public void submitList(List<CommunityNotificationEntity> list) {
        differ.submitList(list == null ? Collections.emptyList() : list);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        CommunityNotificationCanvasView cv = new CommunityNotificationCanvasView(parent.getContext());
        cv.setLayoutParams(new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
        return new VH(cv);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos, @NonNull java.util.List<Object> payloads) {
        if (!payloads.isEmpty() && Integer.valueOf(PAYLOAD_READ_STATE).equals(payloads.get(0))) {
            // Partial update: only read state changed — update the dot/bg without full rebind
            h.canvasView.setReadState(differ.getCurrentList().get(pos).isRead);
            return;
        }
        onBindViewHolder(h, pos);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        CommunityNotificationEntity n = differ.getCurrentList().get(pos);
        h.canvasView.bind(n);
        h.canvasView.setListener(notif -> {
            if (listener != null) listener.onNotificationClicked(notif);
        });
    }

    @Override
    public int getItemCount() { return differ.getCurrentList().size(); }

    static class VH extends RecyclerView.ViewHolder {
        final CommunityNotificationCanvasView canvasView;
        VH(@NonNull CommunityNotificationCanvasView cv) {
            super(cv);
            this.canvasView = cv;
        }
    }
}
