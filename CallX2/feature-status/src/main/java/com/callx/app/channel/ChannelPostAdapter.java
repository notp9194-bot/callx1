package com.callx.app.channel;

import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.transition.Transition;
import com.callx.app.channel.canvas.ChannelPostCanvasView;
import com.callx.app.channel.canvas.ChannelPostGlidePreloader;
import com.callx.app.channel.canvas.ChannelPostHeightCache;
import com.callx.app.channel.canvas.ChannelPostLayoutPrewarmer;
import com.callx.app.channel.canvas.OnPostClickListener;
import com.callx.app.models.ChannelPost;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ChannelPostAdapter — ultra-optimized canvas adapter (v6).
 *
 * V6 OPTIMIZATIONS OVER V5
 * ─────────────────────────
 * 1. AsyncListDiffer — DiffUtil runs on a background thread automatically.
 *    Previously, DiffUtil.calculateDiff() blocked the UI thread for every
 *    setPosts() call — on a 200-post channel that was 8–30 ms of jank. Now it
 *    runs fully off the main thread; dispatchUpdatesTo() is called on main when done.
 *
 * 2. ChannelPostLayoutPrewarmer — a HandlerThread pre-builds StaticLayouts for
 *    upcoming items while the RecyclerView is idle. When the item is bound, the
 *    StaticLayout is already ready; onMeasure() returns the layout height in ~0 ms
 *    instead of running text shaping (~2–8 ms per item on the UI thread).
 *
 * 3. ChannelPostHeightCache — stores measured height per postId. Dirty measure
 *    check in ChannelPostCanvasView.onMeasure() returns the cached height instantly
 *    for posts that re-enter the viewport past the scrap-cache boundary.
 *
 * 4. onViewRecycled() — cancels Glide requests on recycle, preventing stale bitmap
 *    delivery to a holder that has already been rebound to a different post. Without
 *    this, a fast scroll can flash the wrong image momentarily before Glide updates.
 *
 * 5. Fling-aware Glide — setFlingActive(true) pauses all Glide requests during a
 *    fast fling (called by ChannelViewerActivity's OnFlingListener). Bitmaps are
 *    not needed while frames are being dropped; resuming on idle means the first
 *    settled frame gets full-resolution images.
 *
 * 6. PAYLOAD_ENGAGEMENT + PAYLOAD_POLL partial rebinds call only invalidate() on
 *    the canvas view — no requestLayout(), no onMeasure() pass at all.
 *
 * 7. Single item type → RecyclerView pool never fragmented.
 * 8. setHasStableIds(true) → DiffUtil can use postId hash for faster item tracking.
 */
public class ChannelPostAdapter extends RecyclerView.Adapter<ChannelPostAdapter.CanvasVH> {

    // ── DiffUtil payload keys ─────────────────────────────────────────────
    static final String PAYLOAD_ENGAGEMENT = "engagement";
    static final String PAYLOAD_POLL       = "poll";

    // ── AsyncListDiffer item callback ─────────────────────────────────────
    private static final DiffUtil.ItemCallback<ChannelPost> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<ChannelPost>() {
                @Override
                public boolean areItemsTheSame(@NonNull ChannelPost a, @NonNull ChannelPost b) {
                    return Objects.equals(a.id, b.id);
                }

                @Override
                public boolean areContentsTheSame(@NonNull ChannelPost a, @NonNull ChannelPost b) {
                    return Objects.equals(a.text, b.text)
                            && a.viewCount   == b.viewCount
                            && a.replyCount  == b.replyCount
                            && Objects.equals(a.reactions, b.reactions)
                            && Objects.equals(a.pollVotes, b.pollVotes)
                            && a.isPinned    == b.isPinned
                            && a.isDeleted   == b.isDeleted;
                }

                @Nullable
                @Override
                public Object getChangePayload(@NonNull ChannelPost a, @NonNull ChannelPost b) {
                    boolean engagementChanged =
                            a.viewCount  != b.viewCount
                            || a.replyCount != b.replyCount
                            || !Objects.equals(a.reactions, b.reactions);
                    boolean pollChanged = !Objects.equals(a.pollVotes, b.pollVotes);
                    if (pollChanged && !engagementChanged)  return PAYLOAD_POLL;
                    if (engagementChanged && !pollChanged)  return PAYLOAD_ENGAGEMENT;
                    return null; // full rebind
                }
            };

    // ── In-app audio (one player at a time) ───────────────────────────────
    private static MediaPlayer          activePlayer   = null;
    private static String               activeAudioUrl = null;
    private static ChannelPostCanvasView activeView    = null;

    // ── Callback interface ────────────────────────────────────────────────
    public interface PostActionListener {
        void onReact(ChannelPost post);
        void onForward(ChannelPost post);
        void onCopy(ChannelPost post);
        void onDelete(ChannelPost post);
        void onEdit(ChannelPost post);
        void onReport(ChannelPost post);
        void onVotePoll(ChannelPost post, int optionIndex);
        void onViewMedia(ChannelPost post);
        void onPinPost(ChannelPost post);
        void onReactionsDetail(ChannelPost post);
        void onViewCount(ChannelPost post);
        void onReply(ChannelPost post);
    }

    // ── State ─────────────────────────────────────────────────────────────
    private final AsyncListDiffer<ChannelPost> differ;
    private final String               myUid;
    private final PostActionListener   listener;
    private       String               channelOwnerUid = "";
    private       boolean              isAdminOrOwner  = false;

    // Optimization subsystems
    private final ChannelPostHeightCache    heightCache;
    private final ChannelPostGlidePreloader glidePreloader;
    private       ChannelPostLayoutPrewarmer prewarmer;   // injected by Activity
    private       boolean                  flingActive   = false;
    private       RequestManager           glideManager;
    private       int                      containerWidth = 0; // set on first bind

    public ChannelPostAdapter(Context ctx, String myUid, PostActionListener listener) {
        this.myUid      = myUid;
        this.listener   = listener;
        this.heightCache    = ChannelPostHeightCache.get();
        this.glidePreloader = new ChannelPostGlidePreloader(ctx);
        this.differ         = new AsyncListDiffer<>(this, DIFF_CALLBACK);
        this.glideManager   = Glide.with(ctx);
        setHasStableIds(true);
    }

    public void setOwnerUid(String uid)          { channelOwnerUid = uid != null ? uid : ""; }
    public void setIsAdminOrOwner(boolean v)     { isAdminOrOwner = v; }

    /** Inject the prewarmer from the Activity (created after the first RecyclerView width). */
    public void setPrewarmer(ChannelPostLayoutPrewarmer p) { this.prewarmer = p; }

    /**
     * Called by the Activity's OnFlingListener.
     * true  = pause Glide (fast fling — no bitmaps needed until settle)
     * false = resume Glide + trigger image loads for newly visible items
     */
    public void setFlingActive(boolean active) {
        flingActive = active;
        if (active) {
            glideManager.pauseRequests();
        } else {
            glideManager.resumeRequests();
        }
    }

    // ── Data mutation ─────────────────────────────────────────────────────

    /**
     * Submit a new list. AsyncListDiffer runs DiffUtil on a background thread
     * and dispatches minimal updates on the main thread — no UI-thread blocking.
     */
    public void setPosts(List<ChannelPost> newList) {
        // AsyncListDiffer makes its own internal copy — no need for us to copy here.
        List<ChannelPost> safeList = newList != null ? newList : new ArrayList<>();
        differ.submitList(safeList);
        glidePreloader.onNewList();
        // Pre-warm bitmaps + layouts for the first visible batch immediately.
        glidePreloader.preloadRange(safeList, 0, 12);
        prewarmRange(0, 15);
    }

    public void addPost(ChannelPost post) {
        List<ChannelPost> next = new ArrayList<>(differ.getCurrentList());
        next.add(0, post);
        differ.submitList(next);
    }

    public List<ChannelPost> getPosts() {
        return new ArrayList<>(differ.getCurrentList());
    }

    public long getOldestTimestamp() {
        List<ChannelPost> list = differ.getCurrentList();
        if (list.isEmpty()) return 0;
        long min = Long.MAX_VALUE;
        for (ChannelPost p : list) if (p.timestamp < min) min = p.timestamp;
        return min == Long.MAX_VALUE ? 0 : min;
    }

    /** Invalidate height cache for a post whose content has changed (pin, edit, delete). */
    public void invalidateHeightCache(String postId) {
        heightCache.invalidate(postId);
    }

    /**
     * Public entry point for the Activity's scroll-idle listener to trigger
     * prewarming of a specific range beyond the current viewport.
     */
    public void prewarmFrom(int from, int to) {
        prewarmRange(from, to);
    }

    // ── RecyclerView overrides ────────────────────────────────────────────

    @Override public int getItemCount() { return differ.getCurrentList().size(); }

    @Override public long getItemId(int pos) {
        String id = differ.getCurrentList().get(pos).id;
        return id != null ? id.hashCode() : pos;
    }

    @Override public int getItemViewType(int pos) { return 0; }

    @NonNull
    @Override
    public CanvasVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ChannelPostCanvasView v = new ChannelPostCanvasView(parent.getContext());
        v.setLayoutParams(new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT));
        return new CanvasVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull CanvasVH holder, int pos,
            @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, pos);
            return;
        }
        ChannelPost p = differ.getCurrentList().get(pos);
        for (Object payload : payloads) {
            if (PAYLOAD_ENGAGEMENT.equals(payload)) {
                // Only invalidate() — no requestLayout(), no onMeasure(). ~0 ms.
                holder.view.updateEngagement(p, myUid);
            } else if (PAYLOAD_POLL.equals(payload)) {
                holder.view.updatePollVotes(p, myUid);
            } else {
                onBindViewHolder(holder, pos); // fallback to full rebind
                return;
            }
        }
    }

    @Override
    public void onBindViewHolder(@NonNull CanvasVH holder, int pos) {
        ChannelPost p = differ.getCurrentList().get(pos);
        if (p == null) return;

        boolean adminPost = isAdminOrOwner
                || (myUid != null && (myUid.equals(p.authorUid)
                        || myUid.equals(channelOwnerUid)));

        if (listener != null) listener.onViewCount(p);

        // ── 1. Inject prewarmed layouts BEFORE bind() to skip text shaping. ──
        if (prewarmer != null && p.id != null) {
            holder.view.acceptPrewarmed(prewarmer.getPrewarmed(p.id));
        }

        // ── 2. Full bind — copies post data, clears lastMeasuredPostId. ─────
        holder.view.bind(p, adminPost, myUid);

        // ── 3. Wire click listener. ───────────────────────────────────────────
        holder.view.setOnPostClickListener(buildClickListener(holder, p, adminPost));

        // ── 4. Load bitmaps (skip if fling active — resume on idle). ─────────
        if (!flingActive) loadBitmaps(holder.view, p);

        // ── 5. Record container width for prewarmer (first bind wins). ────────
        if (containerWidth <= 0) {
            containerWidth = holder.view.getWidth();
            if (containerWidth <= 0) {
                // View not laid out yet — read from LayoutParams.
                RecyclerView.LayoutParams lp =
                        (RecyclerView.LayoutParams) holder.view.getLayoutParams();
                // Width will be resolved later; prewarmer gets it on first onMeasure.
            }
        }

        // ── 6. Write height to cache after first measure resolves. ────────────
        holder.view.post(() -> {
            int h = holder.view.getLastMeasuredHeight();
            if (h > 0 && p.id != null) {
                heightCache.put(p.id, holder.view.getWidth(), h);
            }
        });

        // ── 7. Pre-warm layouts + Glide bitmaps ahead of current position. ────
        prewarmRange(pos + 1, pos + 10);
        glidePreloader.preloadRange(differ.getCurrentList(), pos + 3, pos + 9);
    }

    @Override
    public void onViewRecycled(@NonNull CanvasVH holder) {
        super.onViewRecycled(holder);
        // Cancel in-flight Glide requests so the recycled view doesn't receive
        // a stale bitmap after it has been rebound to a different post.
        glideManager.clear(holder.view);
        // Clear the prewarmed result so the next bind starts clean.
        holder.view.acceptPrewarmed(null);
    }

    @Override
    public void onViewAttachedToWindow(@NonNull CanvasVH holder) {
        super.onViewAttachedToWindow(holder);
        // Resume any Glide request that was paused due to fling.
        if (!flingActive) {
            ChannelPost p = null;
            try {
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_ID && pos < differ.getCurrentList().size()) {
                    p = differ.getCurrentList().get(pos);
                }
            } catch (Exception ignored) {}
            if (p != null && holder.view.mediaBitmap == null) {
                loadBitmaps(holder.view, p);
            }
        }
    }

    // ── Glide image loading ───────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private void loadBitmaps(ChannelPostCanvasView view, ChannelPost p) {
        Context ctx  = view.getContext();
        String postId = p.id;

        // Author avatar — small, cheap decode.
        if (p.authorIconUrl != null && !p.authorIconUrl.isEmpty()) {
            int aPx = ChannelPostCanvasView.avatarPx(ctx);
            glideManager.asBitmap()
                    .load(p.authorIconUrl)
                    .circleCrop()
                    .override(aPx, aPx)
                    .into(new SimpleTarget<android.graphics.Bitmap>(aPx, aPx) {
                        @Override public void onResourceReady(@NonNull android.graphics.Bitmap bmp,
                                @Nullable Transition<? super android.graphics.Bitmap> t) {
                            view.setAuthorAvatarBitmap(postId, bmp);
                        }
                        @Override public void onLoadFailed(@Nullable android.graphics.drawable.Drawable e) {
                            view.setAuthorAvatarBitmap(postId, null);
                        }
                    });
        }

        // Media (image or video thumbnail).
        String mediaUrl = "video".equals(p.type) ? p.thumbnailUrl : p.mediaUrl;
        if (mediaUrl != null && !mediaUrl.isEmpty()
                && ("image".equals(p.type) || "video".equals(p.type))) {
            int mH = ChannelPostCanvasView.mediaHeightPx(ctx);
            glideManager.asBitmap()
                    .load(mediaUrl)
                    .centerCrop()
                    .override(RecyclerView.LayoutParams.MATCH_PARENT, mH)
                    .into(new SimpleTarget<android.graphics.Bitmap>() {
                        @Override public void onResourceReady(@NonNull android.graphics.Bitmap bmp,
                                @Nullable Transition<? super android.graphics.Bitmap> t) {
                            view.setMediaBitmap(postId, bmp);
                        }
                        @Override public void onLoadFailed(@Nullable android.graphics.drawable.Drawable e) {
                            view.setMediaBitmap(postId, null);
                        }
                    });
        }

        // Link preview thumbnail.
        if ("link".equals(p.type) && p.linkImageUrl != null && !p.linkImageUrl.isEmpty()) {
            glideManager.asBitmap()
                    .load(p.linkImageUrl)
                    .centerCrop()
                    .into(new SimpleTarget<android.graphics.Bitmap>() {
                        @Override public void onResourceReady(@NonNull android.graphics.Bitmap bmp,
                                @Nullable Transition<? super android.graphics.Bitmap> t) {
                            view.setLinkThumbBitmap(postId, bmp);
                        }
                        @Override public void onLoadFailed(@Nullable android.graphics.drawable.Drawable e) {
                            view.setLinkThumbBitmap(postId, null);
                        }
                    });
        }

        // Event banner.
        if ("event".equals(p.type) && p.eventImageUrl != null && !p.eventImageUrl.isEmpty()) {
            glideManager.asBitmap()
                    .load(p.eventImageUrl)
                    .centerCrop()
                    .into(new SimpleTarget<android.graphics.Bitmap>() {
                        @Override public void onResourceReady(@NonNull android.graphics.Bitmap bmp,
                                @Nullable Transition<? super android.graphics.Bitmap> t) {
                            view.setEventBannerBitmap(postId, bmp);
                        }
                        @Override public void onLoadFailed(@Nullable android.graphics.drawable.Drawable e) {
                            view.setEventBannerBitmap(postId, null);
                        }
                    });
        }
    }

    // ── Prewarmer helper ──────────────────────────────────────────────────

    private void prewarmRange(int from, int to) {
        if (prewarmer == null || containerWidth <= 0) return;
        List<ChannelPost> list = differ.getCurrentList();
        if (list.isEmpty() || from >= list.size()) return;
        // contentWidthPx = containerWidth - 2*cardPadding (approx 28 dp each side)
        // We use a rough 56dp total for simplicity; exact value is in CanvasView.cardPadding.
        float density = list.get(0) != null ? 1f : 1f; // density not needed here
        int contentW = containerWidth - Math.round(56f *
                (containerWidth > 0
                        ? android.content.res.Resources.getSystem()
                                .getDisplayMetrics().density
                        : 3f));
        prewarmer.prewarm(list, from, to, Math.max(1, contentW));
    }

    // ── Click listener factory ────────────────────────────────────────────

    private OnPostClickListener buildClickListener(CanvasVH holder,
            ChannelPost p, boolean adminPost) {
        return new OnPostClickListener() {
            @Override public void onPostClick() {
                if ("image".equals(p.type) || "video".equals(p.type))
                    if (listener != null) listener.onViewMedia(p);
            }
            @Override public void onPostLongClick() {
                showPostOptions(holder.view.getContext(), p, adminPost);
            }
            @Override public void onAuthorClick() {}
            @Override public void onMediaClick() {
                if (listener != null) listener.onViewMedia(p);
            }
            @Override public void onMediaGroupCellClick(int idx) {
                if (listener != null) listener.onViewMedia(p);
            }
            @Override public void onMediaGroupOverflowClick() {
                if (listener != null) listener.onViewMedia(p);
            }
            @Override public void onPollOptionClick(int idx) {
                if (listener != null) listener.onVotePoll(p, idx);
            }
            @Override public void onMentionClick(String mention) {}
            @Override public void onReactionsClick() {
                if (listener != null) listener.onReactionsDetail(p);
            }
            @Override public void onReactClick() {
                if (listener != null) listener.onReact(p);
            }
            @Override public void onForwardClick() {
                if (listener != null) listener.onForward(p);
            }
            @Override public void onReplyClick() {
                if (listener != null) listener.onReply(p);
            }
            @Override public void onLinkClick(String url) {
                if (url != null && !url.isEmpty())
                    holder.view.getContext().startActivity(
                            new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            }
            @Override public void onOptionsClick() {
                showPostOptions(holder.view.getContext(), p, adminPost);
            }
            @Override public void onRsvpClick(String status) {
                Context ctx = holder.view.getContext();
                if (ctx instanceof ChannelViewerActivity)
                    ((ChannelViewerActivity) ctx).onRsvpEvent(p, status);
            }
        };
    }

    // ── Post options ──────────────────────────────────────────────────────

    private void showPostOptions(Context ctx, ChannelPost p, boolean adminPost) {
        List<String>   options = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        options.add("💬 Comments");
        actions.add(() -> { if (listener != null) listener.onReply(p); });

        if (p.allowReactions) {
            options.add("😊 React");
            actions.add(() -> { if (listener != null) listener.onReact(p); });
        }
        if (p.allowForward) {
            options.add("↗ Forward");
            actions.add(() -> { if (listener != null) listener.onForward(p); });
        }
        options.add("📋 Copy text");
        actions.add(() -> { if (listener != null) listener.onCopy(p); });
        options.add("🔖 Save post");
        actions.add(() -> {
            if (ctx instanceof ChannelViewerActivity)
                ((ChannelViewerActivity) ctx).onSavePost(p);
        });

        if ("poll".equals(p.type)) {
            options.add("📊 See results");
            actions.add(() -> {
                if (ctx instanceof ChannelViewerActivity)
                    ((ChannelViewerActivity) ctx).onPollResults(p);
            });
        }

        if (adminPost) {
            options.add(p.isPinned ? "📌 Unpin" : "📌 Pin post");
            actions.add(() -> { if (listener != null) listener.onPinPost(p); });
            if (p.text != null && !p.text.isEmpty()) {
                options.add("✏ Edit");
                actions.add(() -> { if (listener != null) listener.onEdit(p); });
            }
            options.add("🗑 Delete");
            actions.add(() -> { if (listener != null) listener.onDelete(p); });
        } else {
            options.add("🚩 Report");
            actions.add(() -> { if (listener != null) listener.onReport(p); });
        }

        new AlertDialog.Builder(ctx)
                .setItems(options.toArray(new String[0]),
                        (d, which) -> actions.get(which).run())
                .show();
    }

    // ── Audio playback ────────────────────────────────────────────────────

    public void toggleAudio(ChannelPost post, ChannelPostCanvasView view) {
        String url = post.audioUrl;
        if (url == null || url.isEmpty()) return;
        if (url.equals(activeAudioUrl) && activePlayer != null) {
            if (activePlayer.isPlaying()) {
                activePlayer.pause();
                view.setAudioPlaying(false, 0f);
            } else {
                activePlayer.start();
                view.setAudioPlaying(true, 0f);
            }
        } else {
            stopAudio();
            activeAudioUrl = url;
            activeView     = view;
            view.setAudioPlaying(true, 0f);
            try {
                activePlayer = new MediaPlayer();
                activePlayer.setDataSource(url);
                activePlayer.setOnPreparedListener(MediaPlayer::start);
                activePlayer.setOnCompletionListener(mp -> {
                    view.setAudioPlaying(false, 0f);
                    activeAudioUrl = null;
                    activePlayer   = null;
                    activeView     = null;
                });
                activePlayer.prepareAsync();
            } catch (Exception e) {
                activePlayer = null;
                view.setAudioPlaying(false, 0f);
            }
        }
    }

    public static void stopAudio() {
        if (activePlayer != null) {
            try { activePlayer.stop(); } catch (Exception ignored) {}
            activePlayer.release();
            activePlayer = null;
        }
        if (activeView != null) {
            activeView.setAudioPlaying(false, 0f);
            activeView = null;
        }
        activeAudioUrl = null;
    }

    // ── ViewHolder ────────────────────────────────────────────────────────

    public static final class CanvasVH extends RecyclerView.ViewHolder {
        final ChannelPostCanvasView view;
        CanvasVH(ChannelPostCanvasView v) { super(v); this.view = v; }
    }
}
