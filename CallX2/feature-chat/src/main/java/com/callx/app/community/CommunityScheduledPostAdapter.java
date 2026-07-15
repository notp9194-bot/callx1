package com.callx.app.community;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.callx.app.db.entity.CommunityScheduledPostEntity;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * v31: Adapter for scheduled posts list.
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
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_community_scheduled_post, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        CommunityScheduledPostEntity p = differ.getCurrentList().get(pos);

        SimpleDateFormat sdf = new SimpleDateFormat("EEE, MMM d 'at' h:mm a", Locale.getDefault());
        h.tvScheduledTime.setText("⏰ Scheduled: " + sdf.format(new Date(p.scheduledAt)));

        String preview = p.text != null && !p.text.isEmpty()
                ? p.text : (p.mediaUrl != null ? "📷 Media post" : "(no text)");
        h.tvPostTextPreview.setText(preview);

        h.tvMediaBadge.setVisibility(p.mediaUrl != null && !p.mediaUrl.isEmpty() ? View.VISIBLE : View.GONE);
        h.tvAnnouncementBadge.setVisibility(p.isAnnouncement ? View.VISIBLE : View.GONE);

        h.btnCancel.setOnClickListener(v -> { if (listener != null) listener.onCancelClicked(p); });
    }

    @Override
    public int getItemCount() { return differ.getCurrentList().size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvScheduledTime, tvPostTextPreview, tvMediaBadge, tvAnnouncementBadge;
        Button btnCancel;

        VH(@NonNull View itemView) {
            super(itemView);
            tvScheduledTime      = itemView.findViewById(R.id.tv_scheduled_time);
            tvPostTextPreview    = itemView.findViewById(R.id.tv_post_text_preview);
            tvMediaBadge         = itemView.findViewById(R.id.tv_media_badge);
            tvAnnouncementBadge  = itemView.findViewById(R.id.tv_announcement_badge);
            btnCancel            = itemView.findViewById(R.id.btn_cancel_scheduled);
        }
    }
}
