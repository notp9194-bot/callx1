package com.callx.app.adapters;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.R;
import com.callx.app.activities.MediaViewerActivity;
import com.callx.app.models.Message;
import com.callx.app.utils.FileUtils;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.VH> {
    private final List<Message> messages;
    private final String currentUid;
    private final boolean isGroup;
    private static final int SENT = 1, RECEIVED = 2;
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private MediaPlayer player;
    private int playingPos = -1;
    public MessageAdapter(List<Message> messages, String currentUid, boolean isGroup) {
        this.messages = messages;
        this.currentUid = currentUid;
        this.isGroup = isGroup;
    }
    @Override public int getItemViewType(int pos) {
        Message m = messages.get(pos);
        return (m.senderId != null && m.senderId.equals(currentUid)) ? SENT : RECEIVED;
    }
    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == SENT
            ? R.layout.item_message_sent : R.layout.item_message_received;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new VH(v);
    }
    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        Message m = messages.get(pos);
        Context ctx = h.itemView.getContext();
        // hide everything
        h.tvMessage.setVisibility(View.GONE);
        h.ivImage.setVisibility(View.GONE);
        if (h.flVideo != null) h.flVideo.setVisibility(View.GONE);
        if (h.llAudio != null) h.llAudio.setVisibility(View.GONE);
        if (h.llFile  != null) h.llFile.setVisibility(View.GONE);
        // sender name in groups
        if (h.tvSenderName != null) {
            if (isGroup && getItemViewType(pos) == RECEIVED) {
                h.tvSenderName.setVisibility(View.VISIBLE);
                h.tvSenderName.setText(m.senderName == null ? "Unknown" : m.senderName);
            } else {
                h.tvSenderName.setVisibility(View.GONE);
            }
        }
        String type = m.type == null ? "text" : m.type;
        if (m.imageUrl != null && !m.imageUrl.isEmpty()
            && (m.mediaUrl == null || m.mediaUrl.isEmpty())) type = "image";
        switch (type) {
            case "image":
                h.ivImage.setVisibility(View.VISIBLE);
                String iu = m.mediaUrl != null ? m.mediaUrl : m.imageUrl;
                Glide.with(ctx).load(iu).into(h.ivImage);
                h.ivImage.setOnClickListener(v -> openMedia(ctx, iu, "image"));
                break;
            case "video":
                if (h.flVideo != null) {
                    h.flVideo.setVisibility(View.VISIBLE);
                    if (m.thumbnailUrl != null && !m.thumbnailUrl.isEmpty()) {
                        Glide.with(ctx).load(m.thumbnailUrl).into(h.ivVideoThumb);
                    } else if (m.mediaUrl != null) {
                        Glide.with(ctx).load(m.mediaUrl).into(h.ivVideoThumb);
                    }
                    h.flVideo.setOnClickListener(v -> openMedia(ctx, m.mediaUrl, "video"));
                }
                break;
            case "audio":
                if (h.llAudio != null) {
                    h.llAudio.setVisibility(View.VISIBLE);
                    h.tvAudioDur.setText(m.duration != null ?
                        FileUtils.formatDuration(m.duration) : "0:00");
                    h.btnPlayAudio.setOnClickListener(v -> togglePlay(h, m, pos));
                }
                break;
            case "file":
                if (h.llFile != null) {
                    h.llFile.setVisibility(View.VISIBLE);
                    h.tvFileName.setText(m.fileName == null ? "File" : m.fileName);
                    h.tvFileMeta.setText(m.fileSize != null ?
                        FileUtils.humanSize(m.fileSize) : "Document");
                    h.llFile.setOnClickListener(v -> {
                        if (m.mediaUrl == null) return;
                        try {
                            ctx.startActivity(new Intent(Intent.ACTION_VIEW,
                                android.net.Uri.parse(m.mediaUrl)));
                        } catch (Exception ignored) {}
                    });
                }
                break;
            default:
                h.tvMessage.setVisibility(View.VISIBLE);
                h.tvMessage.setText(m.text == null ? "" : m.text);
        }
        if (h.tvTime != null && m.timestamp != null) {
            h.tvTime.setText(fmt.format(new Date(m.timestamp)));
        }
    }
    private void openMedia(Context ctx, String url, String type) {
        if (url == null || url.isEmpty()) return;
        Intent i = new Intent(ctx, MediaViewerActivity.class);
        i.putExtra("url", url); i.putExtra("type", type);
        ctx.startActivity(i);
    }
    private void togglePlay(VH h, Message m, int pos) {
        if (m.mediaUrl == null) return;
        try {
            if (player != null && playingPos == pos && player.isPlaying()) {
                player.pause();
                h.btnPlayAudio.setImageResource(R.drawable.ic_play);
                return;
            }
            if (player != null) { player.release(); player = null; }
            player = new MediaPlayer();
            player.setDataSource(m.mediaUrl);
            player.prepareAsync();
            playingPos = pos;
            h.btnPlayAudio.setImageResource(R.drawable.ic_pause);
            player.setOnPreparedListener(MediaPlayer::start);
            player.setOnCompletionListener(mp -> {
                h.btnPlayAudio.setImageResource(R.drawable.ic_play);
                mp.release(); player = null; playingPos = -1;
            });
        } catch (Exception ignored) {}
    }
    @Override public int getItemCount() { return messages.size(); }
    static class VH extends RecyclerView.ViewHolder {
        TextView tvMessage, tvTime, tvSenderName, tvAudioDur, tvFileName, tvFileMeta;
        ImageView ivImage, ivVideoThumb;
        FrameLayout flVideo;
        LinearLayout llAudio, llFile;
        ImageButton btnPlayAudio;
        SeekBar seekAudio;
        VH(View v) {
            super(v);
            tvMessage    = v.findViewById(R.id.tv_message);
            tvTime       = v.findViewById(R.id.tv_time);
            tvSenderName = v.findViewById(R.id.tv_sender_name);
            ivImage      = v.findViewById(R.id.iv_image);
            flVideo      = v.findViewById(R.id.fl_video);
            ivVideoThumb = v.findViewById(R.id.iv_video_thumb);
            llAudio      = v.findViewById(R.id.ll_audio);
            btnPlayAudio = v.findViewById(R.id.btn_play_audio);
            seekAudio    = v.findViewById(R.id.seek_audio);
            tvAudioDur   = v.findViewById(R.id.tv_audio_dur);
            llFile       = v.findViewById(R.id.ll_file);
            tvFileName   = v.findViewById(R.id.tv_file_name);
            tvFileMeta   = v.findViewById(R.id.tv_file_meta);
        }
    }
}
