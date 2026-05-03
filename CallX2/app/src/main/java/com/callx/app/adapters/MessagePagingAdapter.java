package com.callx.app.adapters;

import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.paging.PagingDataAdapter;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.callx.app.R;
import com.callx.app.activities.MediaViewerActivity;
import com.callx.app.models.Message;
import com.callx.app.utils.FileUtils;
import com.callx.app.utils.MediaCache;

import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * MessagePagingAdapter — Paging 3 PagingDataAdapter for chat messages.
 *
 * Drop-in replacement for MessageAdapter when loading messages from Room DB
 * via Pager3 + PagingSource. Supports sent/received layout types, text,
 * image, audio, file, and video message rendering identical to MessageAdapter.
 *
 * Usage in ChatActivity:
 *   MessagePagingAdapter pagingAdapter = new MessagePagingAdapter(uid, false);
 *   binding.rvMessages.setAdapter(pagingAdapter);
 *   viewModel.getPagedMessages(chatId).observe(this, pagingAdapter::submitData);
 */
public class MessagePagingAdapter
        extends PagingDataAdapter<Message, MessagePagingAdapter.VH> {

    // ── DiffUtil — required by PagingDataAdapter ──────────────────
    private static final DiffUtil.ItemCallback<Message> DIFF =
        new DiffUtil.ItemCallback<Message>() {
            @Override
            public boolean areItemsTheSame(@NonNull Message a, @NonNull Message b) {
                return a.messageId != null && a.messageId.equals(b.messageId);
            }

            @Override
            public boolean areContentsTheSame(@NonNull Message a, @NonNull Message b) {
                return a.messageId.equals(b.messageId)
                    && safeEquals(a.text, b.text)
                    && safeEquals(a.type, b.type)
                    && safeEquals(a.status, b.status)
                    && a.timestamp == b.timestamp
                    && a.edited == b.edited
                    && a.deleted == b.deleted;
            }

            private boolean safeEquals(String x, String y) {
                if (x == null && y == null) return true;
                if (x == null || y == null) return false;
                return x.equals(y);
            }
        };

    // ── View types ────────────────────────────────────────────────
    private static final int TYPE_SENT     = 1;
    private static final int TYPE_RECEIVED = 2;

    // ── Fields ────────────────────────────────────────────────────
    private final String currentUid;
    private final boolean isGroup;
    private final SimpleDateFormat timeFmt =
            new SimpleDateFormat("hh:mm a", Locale.getDefault());

    private ActionListener actionListener;
    private MediaPlayer player;
    private int playingPos = -1;

    // ── Interface for long-press actions ─────────────────────────
    public interface ActionListener {
        void onReply(Message m);
        void onDelete(Message m);
        void onReact(Message m, String emoji);
        void onStar(Message m);
        void onCopy(Message m);
        void onForward(Message m);
    }

    public MessagePagingAdapter(String currentUid, boolean isGroup) {
        super(DIFF);
        this.currentUid = currentUid;
        this.isGroup    = isGroup;
    }

    public void setActionListener(ActionListener l) {
        this.actionListener = l;
    }

    // ──────────────────────────────────────────────────────────────
    @Override
    public int getItemViewType(int position) {
        Message m = getItem(position);
        if (m == null) return TYPE_RECEIVED;
        return currentUid.equals(m.senderId) ? TYPE_SENT : TYPE_RECEIVED;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == TYPE_SENT
                ? R.layout.item_message_sent
                : R.layout.item_message_received;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Message m = getItem(position);
        if (m == null) {
            // Placeholder — show shimmer or empty
            h.tvMessage.setVisibility(View.GONE);
            return;
        }
        bindMessage(h, m, position);
    }

    // ──────────────────────────────────────────────────────────────
    // Core bind logic (mirrors MessageAdapter)
    // ──────────────────────────────────────────────────────────────
    private void bindMessage(@NonNull VH h, @NonNull Message m, int position) {
        Context ctx = h.itemView.getContext();
        boolean sent = currentUid.equals(m.senderId);

        // Reset visibility
        h.tvMessage.setVisibility(View.GONE);
        if (h.ivImage    != null) h.ivImage.setVisibility(View.GONE);
        if (h.llAudio    != null) h.llAudio.setVisibility(View.GONE);
        if (h.llFile     != null) h.llFile.setVisibility(View.GONE);
        if (h.tvTime     != null) h.tvTime.setVisibility(View.VISIBLE);

        // Timestamp
        if (h.tvTime != null && m.timestamp > 0) {
            h.tvTime.setText(timeFmt.format(new java.util.Date(m.timestamp)));
        }

        // Sender name (group chats)
        if (isGroup && !sent && h.tvSenderName != null) {
            h.tvSenderName.setVisibility(View.VISIBLE);
            String sn = m.senderName != null ? m.senderName : "Member";
            h.tvSenderName.setText(sn);
        } else if (h.tvSenderName != null) {
            h.tvSenderName.setVisibility(View.GONE);
        }

        // Deleted message
        if (Boolean.TRUE.equals(m.deleted)) {
            h.tvMessage.setVisibility(View.VISIBLE);
            h.tvMessage.setText(sent ? "You deleted this message" : "This message was deleted");
            h.tvMessage.setAlpha(0.6f);
            return;
        }

        // ── Render by type ───────────────────────────────────────
        String type = m.type != null ? m.type : "text";
        switch (type) {
            case "image":
            case "gif":
                if (h.ivImage != null) {
                    h.ivImage.setVisibility(View.VISIBLE);
                    String url = m.mediaUrl != null ? m.mediaUrl : m.text;
                    // FIX v14: Check MediaCache first — agar locally stored hai to seedha load,
                    // zero network. Otherwise Glide se load karo aur background mein cache karo.
                    java.io.File cachedImg = MediaCache.getCached(ctx, url);
                    if (cachedImg != null) {
                        android.util.Log.d("PagingAdapter", "Image cache HIT: " + cachedImg.getName());
                        Glide.with(ctx).load(cachedImg)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .placeholder(R.drawable.ic_file)
                            .error(R.drawable.ic_file)
                            .into(h.ivImage);
                    } else {
                        android.util.Log.d("PagingAdapter", "Image cache MISS, downloading: " + url);
                        Glide.with(ctx).load(url)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .placeholder(R.drawable.ic_file)
                            .error(R.drawable.ic_file)
                            .into(h.ivImage);
                        // Background mein cache karo taaki agli baar zero data use ho
                        MediaCache.get(ctx, url, new MediaCache.Callback() {
                            @Override public void onReady(java.io.File file) {
                                android.util.Log.d("PagingAdapter", "Image cached: " + file.getName());
                            }
                            @Override public void onError(String reason) {
                                android.util.Log.w("PagingAdapter", "Image cache failed: " + reason);
                            }
                        });
                    }
                    h.ivImage.setOnClickListener(v -> {
                        Intent i = new Intent(ctx, MediaViewerActivity.class);
                        i.putExtra("url", url);
                        i.putExtra("type", "image");
                        ctx.startActivity(i);
                    });
                }
                break;
            case "video":
                if (h.ivImage != null) {
                    h.ivImage.setVisibility(View.VISIBLE);
                    String vUrl = m.mediaUrl != null ? m.mediaUrl : m.text;
                    // FIX v14: Video thumbnail — MediaCache se check karo
                    java.io.File cachedVid = MediaCache.getCached(ctx, vUrl);
                    if (cachedVid != null) {
                        android.util.Log.d("PagingAdapter", "Video cache HIT: " + cachedVid.getName());
                        Glide.with(ctx).load(cachedVid)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .placeholder(R.drawable.ic_file)
                            .into(h.ivImage);
                    } else {
                        android.util.Log.d("PagingAdapter", "Video cache MISS, downloading: " + vUrl);
                        Glide.with(ctx).load(vUrl)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .placeholder(R.drawable.ic_file)
                            .into(h.ivImage);
                        // FIX v14: Video background mein pre-cache karo (MediaStreamCache se partial)
                        com.callx.app.cache.MediaStreamCache.getInstance(ctx)
                            .preloadPartial(vUrl, new com.callx.app.cache.MediaStreamCache.DownloadCallback() {
                                @Override public void onComplete(java.io.File file) {
                                    android.util.Log.d("PagingAdapter", "Video partial cached: " + file.getName());
                                }
                                @Override public void onError(String error) {
                                    android.util.Log.w("PagingAdapter", "Video preload failed: " + error);
                                }
                                @Override public void onProgress(int percent) {}
                            });
                    }
                    h.ivImage.setOnClickListener(v -> {
                        Intent i = new Intent(ctx, MediaViewerActivity.class);
                        i.putExtra("url", vUrl);
                        i.putExtra("type", "video");
                        ctx.startActivity(i);
                    });
                }
                break;
            case "audio":
                if (h.llAudio != null && h.btnPlayPause != null) {
                    h.llAudio.setVisibility(View.VISIBLE);
                    String aUrl = m.mediaUrl != null ? m.mediaUrl : m.text;
                    final int pos = position;
                    h.btnPlayPause.setOnClickListener(v -> toggleAudio(h, aUrl, pos));
                    // FIX v14: Audio preload — MediaStreamCache se pehle 512KB cache karo
                    // Taaki play button press karne par turant start ho, buffer nahi kare
                    java.io.File cachedAudio = MediaCache.getCached(ctx, aUrl);
                    if (cachedAudio == null && aUrl != null && !aUrl.isEmpty()) {
                        com.callx.app.cache.MediaStreamCache.getInstance(ctx)
                            .preloadPartial(aUrl, new com.callx.app.cache.MediaStreamCache.DownloadCallback() {
                                @Override public void onComplete(java.io.File file) {
                                    android.util.Log.d("PagingAdapter", "Audio preloaded: " + file.getName());
                                }
                                @Override public void onError(String error) {}
                                @Override public void onProgress(int percent) {}
                            });
                    }
                } else {
                    // Fallback if no audio layout
                    h.tvMessage.setVisibility(View.VISIBLE);
                    h.tvMessage.setText("Audio message");
                }
                break;
            case "file":
            case "document":
                if (h.llFile != null && h.tvFileName != null) {
                    h.llFile.setVisibility(View.VISIBLE);
                    String fName = m.fileName != null ? m.fileName : "File";
                    h.tvFileName.setText(fName);
                    if (h.btnDownload != null) {
                        String fUrl = m.mediaUrl != null ? m.mediaUrl : m.text;
                        h.btnDownload.setOnClickListener(v -> {
                            // Pehle local cache check karo
                            java.io.File cached = MediaCache.getCached(ctx, fUrl);
                            if (cached != null) {
                                FileUtils.openOrDownload(ctx, cached.toURI().toString(), fName);
                                return;
                            }
                            android.widget.Toast.makeText(ctx, "Downloading…", android.widget.Toast.LENGTH_SHORT).show();
                            MediaCache.get(ctx, fUrl, new MediaCache.Callback() {
                                @Override public void onReady(java.io.File file) {
                                    FileUtils.openOrDownload(ctx, file.toURI().toString(), fName);
                                }
                                @Override public void onError(String reason) {
                                    FileUtils.openOrDownload(ctx, fUrl, fName);
                                }
                            });
                        });
                    }
                } else {
                    h.tvMessage.setVisibility(View.VISIBLE);
                    h.tvMessage.setText(m.fileName != null ? m.fileName : "File");
                }
                break;
            default: // "text", "emoji", etc.
                h.tvMessage.setVisibility(View.VISIBLE);
                String txt = m.text != null ? m.text : "";
                if (Boolean.TRUE.equals(m.edited)) txt += " (edited)";
                h.tvMessage.setText(txt);
                h.tvMessage.setAlpha(1f);
                break;
        }

        // ── Delivery status (sent messages only) ─────────────────
        if (sent && h.tvStatus != null) {
            h.tvStatus.setVisibility(View.VISIBLE);
            String status = m.status != null ? m.status : "sent";
            switch (status) {
                case "seen":
                    h.tvStatus.setText("✓✓");
                    h.tvStatus.setTextColor(0xFF4FC3F7); // blue
                    break;
                case "delivered":
                    h.tvStatus.setText("✓✓");
                    h.tvStatus.setTextColor(0xAAFFFFFF);
                    break;
                default:
                    h.tvStatus.setText("✓");
                    h.tvStatus.setTextColor(0xAAFFFFFF);
                    break;
            }
        } else if (h.tvStatus != null) {
            h.tvStatus.setVisibility(View.GONE);
        }

        // ── Long press — action listener ─────────────────────────
        h.itemView.setOnLongClickListener(v -> {
            if (actionListener != null) showActionBottomSheet(ctx, m);
            return true;
        });
    }

    // ──────────────────────────────────────────────────────────────
    // Audio playback toggle
    // ──────────────────────────────────────────────────────────────
    private void toggleAudio(@NonNull VH h, String url, int position) {
        if (playingPos == position && player != null && player.isPlaying()) {
            player.pause();
            if (h.btnPlayPause != null) h.btnPlayPause.setImageResource(R.drawable.ic_play);
            return;
        }
        if (player != null) {
            try { player.stop(); player.release(); } catch (Exception ignored) {}
            player = null;
        }
        playingPos = position;
        if (h.btnPlayPause != null) h.btnPlayPause.setImageResource(R.drawable.ic_pause);

        // Pehle local cache check — cached hai to seedha play (zero data use)
        java.io.File cached = MediaCache.getCached(h.itemView.getContext(), url);
        if (cached != null) {
            playAudioFromPath(h, cached.getAbsolutePath(), position);
            return;
        }

        // FIX v14: MediaStreamCache use karo — pehle 512KB stream karo (fast start),
        // baaki background mein download hota rahe. User ko buffer nahi karega.
        com.callx.app.cache.MediaStreamCache.getInstance(h.itemView.getContext())
            .preloadPartial(url, new com.callx.app.cache.MediaStreamCache.DownloadCallback() {
                @Override public void onComplete(java.io.File file) {
                    // Partial/full file ready — play from local file (zero buffering)
                    android.util.Log.d("AudioPlay", "MediaStreamCache ready, playing: " + file.getName());
                    playAudioFromPath(h, file.getAbsolutePath(), position);
                }
                @Override public void onError(String error) {
                    // Fallback: stream directly from URL
                    android.util.Log.w("AudioPlay", "MediaStreamCache failed, streaming URL: " + error);
                    playAudioFromPath(h, url, position);
                }
                @Override public void onProgress(int percent) {
                    android.util.Log.v("AudioPlay", "Audio preload: " + percent + "%");
                }
            });
    }

    private void playAudioFromPath(@NonNull VH h, String path, int position) {
        try {
            if (player != null) { try { player.release(); } catch (Exception ignored) {} }
            player = new MediaPlayer();
            
            // Agar local file hai to FileDescriptor se set karo (cache files ke liye)
            // Agar URL hai to directly
            if (path.startsWith("http")) {
                player.setDataSource(path);
            } else {
                java.io.File f = new java.io.File(path);
                if (f.exists()) {
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                        player.setDataSource(fis.getFD());
                    }
                } else {
                    // File nahi milti to URL ke bahawe try karo
                    player.setDataSource(path);
                }
            }
            
            player.prepareAsync();
            player.setOnPreparedListener(mp -> {
                mp.start();
                if (h.btnPlayPause != null) h.btnPlayPause.setImageResource(R.drawable.ic_pause);
            });
            player.setOnCompletionListener(mp -> {
                playingPos = -1;
                if (h.btnPlayPause != null) h.btnPlayPause.setImageResource(R.drawable.ic_play);
                try { mp.release(); } catch (Exception ignored) {}
                player = null;
            });
            player.setOnErrorListener((mp, what, extra) -> {
                android.util.Log.e("AudioPlay", "Error: " + what + " extra: " + extra + " path: " + path);
                playingPos = -1;
                if (h.btnPlayPause != null) h.btnPlayPause.setImageResource(R.drawable.ic_play);
                return true;
            });
        } catch (Exception e) {
            android.util.Log.e("AudioPlay", "playAudioFromPath error: " + e.getMessage() + " path: " + path);
            if (player != null) { try { player.release(); } catch (Exception ignored) {} player = null; }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Long-press bottom sheet actions
    // ──────────────────────────────────────────────────────────────
    private void showActionBottomSheet(Context ctx, Message m) {
        if (actionListener == null) return;
        String[] options = {"Reply", "Copy", "Star", "Forward", "Delete"};
        new android.app.AlertDialog.Builder(ctx)
            .setItems(options, (d, which) -> {
                switch (which) {
                    case 0: actionListener.onReply(m);   break;
                    case 1: actionListener.onCopy(m);    break;
                    case 2: actionListener.onStar(m);    break;
                    case 3: actionListener.onForward(m); break;
                    case 4: actionListener.onDelete(m);  break;
                }
            }).show();
    }

    @Override
    public void onViewRecycled(@NonNull VH holder) {
        super.onViewRecycled(holder);
        if (holder.ivImage != null) Glide.with(holder.ivImage).clear(holder.ivImage);
    }

    // ──────────────────────────────────────────────────────────────
    // ViewHolder — covers all view IDs used in both item layouts
    // ──────────────────────────────────────────────────────────────
    static class VH extends RecyclerView.ViewHolder {
        TextView     tvMessage, tvTime, tvSenderName, tvFileName;
        ImageView    ivImage;
        TextView     tvStatus;   // tv_status in both item layouts
        LinearLayout llAudio, llFile;
        ImageButton  btnPlayPause;   // ImageButton in both item layouts
        ImageView    btnDownload;

        VH(@NonNull View v) {
            super(v);
            tvMessage    = v.findViewById(R.id.tv_message);
            tvTime       = v.findViewById(R.id.tv_time);
            tvSenderName = v.findViewById(R.id.tv_sender_name);
            ivImage      = v.findViewById(R.id.iv_image);
            tvStatus     = v.findViewById(R.id.tv_status);
            llAudio      = v.findViewById(R.id.ll_audio);
            btnPlayPause = v.findViewById(R.id.btn_play_pause);
            llFile       = v.findViewById(R.id.ll_file);
            tvFileName   = v.findViewById(R.id.tv_file_name);
            btnDownload  = v.findViewById(R.id.btn_download);
        }
    }
}
