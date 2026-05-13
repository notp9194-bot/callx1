package com.callx.app.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import com.callx.app.models.ReelDraft;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ReelDraftAdapter
        extends RecyclerView.Adapter<ReelDraftAdapter.DraftVH> {

    public interface DraftActionListener {
        void onDraftClick(ReelDraft draft);
        void onDraftLongClick(ReelDraft draft, int position);
    }

    private final List<ReelDraft>      drafts;
    private final DraftActionListener  listener;

    public ReelDraftAdapter(List<ReelDraft> drafts, DraftActionListener listener) {
        this.drafts   = drafts;
        this.listener = listener;
    }

    @NonNull
    @Override
    public DraftVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_reel_draft, parent, false);
        return new DraftVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull DraftVH h, int pos) {
        ReelDraft draft = drafts.get(pos);

        if (draft.thumbUrl != null && !draft.thumbUrl.isEmpty()) {
            Glide.with(h.itemView.getContext())
                .load(draft.thumbUrl)
                .centerCrop()
                .placeholder(R.drawable.bg_skeleton_rect)
                .into(h.ivThumb);
        } else {
            h.ivThumb.setImageResource(R.drawable.ic_reels);
        }

        String caption = (draft.caption != null && !draft.caption.isEmpty())
            ? draft.caption : "No caption";
        h.tvCaption.setText(caption);

        if (draft.timestamp > 0) {
            String date = new SimpleDateFormat("MMM d", Locale.US)
                .format(new Date(draft.timestamp));
            h.tvDate.setText(date);
        }

        h.itemView.setOnClickListener(v -> listener.onDraftClick(draft));
        h.itemView.setOnLongClickListener(v -> {
            listener.onDraftLongClick(draft, h.getAdapterPosition());
            return true;
        });
    }

    @Override public int getItemCount() { return drafts.size(); }

    static class DraftVH extends RecyclerView.ViewHolder {
        ImageView ivThumb;
        TextView  tvCaption, tvDate;

        DraftVH(View v) {
            super(v);
            ivThumb   = v.findViewById(R.id.iv_draft_thumb);
            tvCaption = v.findViewById(R.id.tv_draft_caption);
            tvDate    = v.findViewById(R.id.tv_draft_date);
        }
    }
}
