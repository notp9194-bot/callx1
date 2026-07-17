package com.callx.app.feed;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.comments.ReelCommentActivity;
import com.callx.app.models.FeedPost;
import com.callx.app.models.FeedStory;
import com.callx.app.models.ReelModel;
import com.callx.app.player.SingleReelPlayerActivity;
import com.callx.app.profile.UserReelsActivity;
import com.callx.app.reels.R;

import java.util.ArrayList;
import java.util.List;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * HomeFeedAdapter — Instagram-like multi-type RecyclerView adapter.
 *
 * ─────────────── ViewTypes ───────────────────────────────────────────────────
 *  TYPE_STORIES_BAR      (0) — always position 0; horizontal story circles
 *  TYPE_POST_PHOTO       (1) — photo/image post (single image, full width)
 *  TYPE_POST_VIDEO       (2) — video reel post (thumbnail + play icon)
 *  TYPE_POST_CAROUSEL    (3) — swipeable multi-image carousel post
 *  TYPE_SUGGESTED        (4) — "Suggested for you" horizontal accounts card
 *  TYPE_REELS_STRIP      (5) — "Reels for you" horizontal mini-reel strip
 *  TYPE_LOADING          (6) — skeleton/spinner at end of list
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * All Firebase interactions are delegated to HomeFeedRepository.
 * State changes (like, save, follow) are applied optimistically (UI first).
 */
public class HomeFeedAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    // ── View type constants ────────────────────────────────────────────────
    public static final int TYPE_STORIES_BAR   = 0;
    public static final int TYPE_POST_PHOTO    = 1;
    public static final int TYPE_POST_VIDEO    = 2;
    public static final int TYPE_POST_CAROUSEL = 3;
    public static final int TYPE_SUGGESTED     = 4;
    public static final int TYPE_REELS_STRIP   = 5;
    public static final int TYPE_LOADING       = 6;

    // ── Injection intervals ────────────────────────────────────────────────
    private static final int SUGGESTED_INTERVAL  = 6;  // inject "Suggested" every 6 posts
    private static final int REELS_STRIP_INTERVAL = 10; // inject "Reels strip" every 10 posts

    // ── Data ──────────────────────────────────────────────────────────────
    private List<FeedStory>  stories     = new ArrayList<>();
    private List<ReelModel>  stripReels  = new ArrayList<>();
    private List<String[]>   suggested   = new ArrayList<>();
    private final List<Object> items     = new ArrayList<>(); // FeedPost | SUGGESTED_TAG | REELS_STRIP_TAG | LOADING_TAG

    private static final String TAG_SUGGESTED  = "__SUGGESTED__";
    private static final String TAG_REELS_STRIP = "__REELS_STRIP__";
    private static final String TAG_LOADING    = "__LOADING__";

    private boolean isLoading = false;
    private final String myUid;
    private final HomeFeedRepository repo = new HomeFeedRepository();

    // ── Callbacks ──────────────────────────────────────────────────────────
    public interface OnPostActionListener {
        void onAvatarClicked(String uid);
        void onFollowClicked(FeedPost post, boolean currentlyFollowing);
        void onLikeClicked(FeedPost post, boolean currentlyLiked, ImageButton btnLike, TextView tvCount);
        void onCommentClicked(FeedPost post);
        void onShareClicked(FeedPost post);
        void onSaveClicked(FeedPost post, boolean currentlySaved, ImageButton btnSave);
        void onPostThumbClicked(FeedPost post);
        void onMoreClicked(FeedPost post, View anchor);
    }

    private OnPostActionListener actionListener;

    public HomeFeedAdapter(String myUid) {
        this.myUid = myUid;
    }

    public void setActionListener(OnPostActionListener l) { this.actionListener = l; }

    // ── Data setters ───────────────────────────────────────────────────────

    public void setStories(List<FeedStory> list) {
        this.stories = new ArrayList<>(list);
        if (!items.isEmpty()) notifyItemChanged(0); // stories bar is always pos 0
    }

    public void setStripReels(List<ReelModel> list) {
        this.stripReels = new ArrayList<>(list);
    }

    public void setSuggestedUsers(List<String[]> list) {
        this.suggested = new ArrayList<>(list);
    }

    /**
     * Set the initial feed posts — rebuilds the items list with injected cards.
     */
    public void setPosts(List<FeedPost> posts) {
        items.clear();
        buildItemList(posts, true);
        notifyDataSetChanged();
    }

    /**
     * Append more posts from the next page (infinite scroll).
     */
    public void appendPosts(List<FeedPost> more) {
        removeLoading();
        int insertAt = items.size();
        buildItemList(more, false);
        notifyItemRangeInserted(insertAt, items.size() - insertAt);
    }

    /**
     * Show/hide the loading skeleton at the bottom.
     */
    public void setLoading(boolean loading) {
        if (isLoading == loading) return;
        isLoading = loading;
        if (loading) {
            items.add(TAG_LOADING);
            notifyItemInserted(items.size() - 1);
        } else {
            removeLoading();
        }
    }

    private void removeLoading() {
        int idx = items.lastIndexOf(TAG_LOADING);
        if (idx >= 0) {
            items.remove(idx);
            notifyItemRemoved(idx);
        }
    }

    /** Insert injected cards (Suggested, Reels Strip) at the right intervals. */
    private void buildItemList(List<FeedPost> posts, boolean isFirstPage) {
        int postCount = (int) items.stream().filter(o -> o instanceof FeedPost).count();
        int sugIdx    = 0; // how many times we've injected suggested
        int stripIdx  = 0;

        for (int i = 0; i < posts.size(); i++) {
            FeedPost p = posts.get(i);
            items.add(p);
            postCount++;

            // Inject "Suggested for you" every SUGGESTED_INTERVAL posts
            if (postCount % SUGGESTED_INTERVAL == 0 && !suggested.isEmpty()
                    && !items.contains(TAG_SUGGESTED)) {
                items.add(TAG_SUGGESTED);
            }

            // Inject "Reels for you" strip every REELS_STRIP_INTERVAL posts
            if (postCount % REELS_STRIP_INTERVAL == 0 && !stripReels.isEmpty()
                    && !items.contains(TAG_REELS_STRIP)) {
                items.add(TAG_REELS_STRIP);
            }
        }
    }

    // ── RecyclerView.Adapter ───────────────────────────────────────────────

    @Override
    public int getItemViewType(int position) {
        if (position == 0) return TYPE_STORIES_BAR; // always
        Object item = items.get(position - 1); // -1 because stories bar = virtual pos 0
        if (item instanceof FeedPost) {
            FeedPost p = (FeedPost) item;
            if (FeedPost.TYPE_CAROUSEL.equals(p.postType)) return TYPE_POST_CAROUSEL;
            if (FeedPost.TYPE_VIDEO.equals(p.postType))    return TYPE_POST_VIDEO;
            return TYPE_POST_PHOTO;
        }
        if (TAG_SUGGESTED.equals(item))   return TYPE_SUGGESTED;
        if (TAG_REELS_STRIP.equals(item)) return TYPE_REELS_STRIP;
        if (TAG_LOADING.equals(item))     return TYPE_LOADING;
        return TYPE_POST_PHOTO;
    }

    @Override
    public int getItemCount() {
        return 1 + items.size(); // +1 for the stories bar slot at position 0
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case TYPE_STORIES_BAR:
                return new StoriesBarVH(inf.inflate(R.layout.item_feed_stories_bar, parent, false));
            case TYPE_POST_CAROUSEL:
                return new CarouselPostVH(inf.inflate(R.layout.item_feed_carousel_post, parent, false));
            case TYPE_POST_VIDEO:
                return new VideoPostVH(inf.inflate(R.layout.item_feed_photo_post, parent, false));
            case TYPE_SUGGESTED:
                return new SuggestedVH(inf.inflate(R.layout.item_feed_suggested_card, parent, false));
            case TYPE_REELS_STRIP:
                return new ReelsStripVH(inf.inflate(R.layout.item_feed_reels_strip, parent, false));
            case TYPE_LOADING:
                return new LoadingVH(inf.inflate(R.layout.item_feed_skeleton, parent, false));
            default: // TYPE_POST_PHOTO
                return new PhotoPostVH(inf.inflate(R.layout.item_feed_photo_post, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (position == 0) {
            ((StoriesBarVH) holder).bind(stories, myUid);
            return;
        }
        Object item = items.get(position - 1);
        if (item instanceof FeedPost) {
            FeedPost post = (FeedPost) item;
            if (holder instanceof CarouselPostVH) ((CarouselPostVH) holder).bind(post);
            else if (holder instanceof VideoPostVH) ((VideoPostVH) holder).bindPost(post);
            else if (holder instanceof PhotoPostVH) ((PhotoPostVH) holder).bindPost(post);
        } else if (TAG_SUGGESTED.equals(item) && holder instanceof SuggestedVH) {
            ((SuggestedVH) holder).bind(suggested, myUid);
        } else if (TAG_REELS_STRIP.equals(item) && holder instanceof ReelsStripVH) {
            ((ReelsStripVH) holder).bind(stripReels);
        }
        // Loading VH needs no binding
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ── ViewHolders ──────────────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════════

    // ── Stories Bar ────────────────────────────────────────────────────────
    class StoriesBarVH extends RecyclerView.ViewHolder {
        RecyclerView rvStories;
        StoriesBarAdapter storiesAdapter;

        StoriesBarVH(@NonNull View v) {
            super(v);
            rvStories = v.findViewById(R.id.rv_stories_bar);
            storiesAdapter = new StoriesBarAdapter(myUid);
            rvStories.setLayoutManager(
                    new LinearLayoutManager(v.getContext(), LinearLayoutManager.HORIZONTAL, false));
            rvStories.setAdapter(storiesAdapter);
            rvStories.setHasFixedSize(false);
        }

        void bind(List<FeedStory> stories, String myUid) {
            storiesAdapter.setStories(stories);
        }
    }

    // ── Base post bind helpers ─────────────────────────────────────────────
    private void bindPostHeader(View root, FeedPost post) {
        CircleImageView ivAvatar = root.findViewById(R.id.iv_post_avatar);
        TextView tvOwner   = root.findViewById(R.id.tv_post_owner);
        TextView tvTime    = root.findViewById(R.id.tv_post_time);
        TextView tvLoc     = root.findViewById(R.id.tv_post_location);
        View     vVerified = root.findViewById(R.id.ic_verified_badge);
        Button   btnFollow = root.findViewById(R.id.btn_post_follow);
        ImageButton btnMore = root.findViewById(R.id.btn_post_more);

        // Avatar
        if (post.ownerPhotoUrl != null && !post.ownerPhotoUrl.isEmpty()) {
            Glide.with(root.getContext())
                 .load(post.ownerPhotoUrl)
                 .apply(RequestOptions.circleCropTransform())
                 .placeholder(R.drawable.ic_person)
                 .into(ivAvatar);
        } else {
            ivAvatar.setImageResource(R.drawable.ic_person);
        }

        tvOwner.setText(post.ownerName != null ? post.ownerName : "User");
        tvTime.setText(post.formatAgo());

        if (tvLoc != null) {
            if (post.location != null && !post.location.isEmpty()) {
                tvLoc.setText(post.location);
                tvLoc.setVisibility(View.VISIBLE);
            } else {
                tvLoc.setVisibility(View.GONE);
            }
        }

        if (vVerified != null) {
            vVerified.setVisibility(post.ownerVerified ? View.VISIBLE : View.GONE);
        }

        // Follow button
        if (btnFollow != null) {
            if (post.uid != null && post.uid.equals(myUid)) {
                btnFollow.setVisibility(View.GONE);
            } else {
                btnFollow.setVisibility(View.VISIBLE);
                applyFollowButton(btnFollow, post.isFollowing);
                btnFollow.setOnClickListener(v -> {
                    boolean wasFollowing = post.isFollowing;
                    post.isFollowing = !wasFollowing;
                    applyFollowButton(btnFollow, post.isFollowing);
                    if (actionListener != null) actionListener.onFollowClicked(post, wasFollowing);
                });
            }
        }

        // Avatar click → profile
        ivAvatar.setOnClickListener(v -> {
            if (actionListener != null) actionListener.onAvatarClicked(post.uid);
        });
        tvOwner.setOnClickListener(v -> {
            if (actionListener != null) actionListener.onAvatarClicked(post.uid);
        });

        // More button
        if (btnMore != null) {
            btnMore.setOnClickListener(v -> {
                if (actionListener != null) actionListener.onMoreClicked(post, v);
            });
        }
    }

    private void applyFollowButton(Button btn, boolean isFollowing) {
        if (isFollowing) {
            btn.setText("Following");
            btn.setBackgroundResource(R.drawable.bg_filter_chip_unselected);
            btn.setTextColor(0xFFAAAAAA);
        } else {
            btn.setText("Follow");
            btn.setBackgroundResource(R.drawable.bg_filter_chip_selected);
            btn.setTextColor(0xFFFFFFFF);
        }
    }

    private void bindPostActions(View root, FeedPost post) {
        ImageButton btnLike    = root.findViewById(R.id.btn_post_like);
        ImageButton btnComment = root.findViewById(R.id.btn_post_comment);
        ImageButton btnShare   = root.findViewById(R.id.btn_post_share);
        ImageButton btnSave    = root.findViewById(R.id.btn_post_save);
        TextView    tvLikes    = root.findViewById(R.id.tv_post_likes);
        TextView    tvComments = root.findViewById(R.id.tv_post_comments);
        TextView    tvCaption  = root.findViewById(R.id.tv_post_caption);

        // Like state
        applyLikeState(btnLike, post.isLiked);
        if (tvLikes != null) tvLikes.setText(post.formatCount(post.likesCount));
        if (tvComments != null) tvComments.setText(post.formatCount(post.commentsCount));

        // Save state
        applySaveState(btnSave, post.isSaved);

        // Caption with hashtag coloring
        if (tvCaption != null && post.caption != null && !post.caption.isEmpty()) {
            tvCaption.setVisibility(View.VISIBLE);
            tvCaption.setText(buildCaption(post));
        } else if (tvCaption != null) {
            tvCaption.setVisibility(View.GONE);
        }

        // Like click — optimistic + animation
        if (btnLike != null) {
            btnLike.setOnClickListener(v -> {
                boolean wasLiked = post.isLiked;
                post.isLiked = !wasLiked;
                post.likesCount += wasLiked ? -1 : 1;
                applyLikeState(btnLike, post.isLiked);
                if (tvLikes != null) tvLikes.setText(post.formatCount(post.likesCount));
                if (!wasLiked) animateLike(btnLike); // only animate on like, not unlike
                if (actionListener != null) actionListener.onLikeClicked(post, wasLiked, btnLike, tvLikes);
            });
        }

        // Comment
        if (btnComment != null) {
            btnComment.setOnClickListener(v -> {
                if (actionListener != null) actionListener.onCommentClicked(post);
            });
        }

        // Share
        if (btnShare != null) {
            btnShare.setOnClickListener(v -> {
                if (actionListener != null) actionListener.onShareClicked(post);
            });
        }

        // Save — optimistic
        if (btnSave != null) {
            btnSave.setOnClickListener(v -> {
                boolean wasSaved = post.isSaved;
                post.isSaved = !wasSaved;
                applySaveState(btnSave, post.isSaved);
                if (actionListener != null) actionListener.onSaveClicked(post, wasSaved, btnSave);
            });
        }
    }

    private void applyLikeState(ImageButton btn, boolean liked) {
        if (btn == null) return;
        btn.setImageResource(liked ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
        btn.setColorFilter(liked ? 0xFFEF4444 : 0xFFFFFFFF);
    }

    private void applySaveState(ImageButton btn, boolean saved) {
        if (btn == null) return;
        btn.setImageResource(saved ? R.drawable.ic_bookmark : R.drawable.ic_bookmark_outline);
        btn.setColorFilter(saved ? 0xFF4CAF50 : 0xFFFFFFFF);
    }

    private void animateLike(View view) {
        AnimatorSet anim = new AnimatorSet();
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.4f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.4f, 1f);
        scaleX.setDuration(300);
        scaleY.setDuration(300);
        scaleX.setInterpolator(new OvershootInterpolator());
        scaleY.setInterpolator(new OvershootInterpolator());
        anim.playTogether(scaleX, scaleY);
        anim.start();
    }

    private CharSequence buildCaption(FeedPost post) {
        if (post.caption == null) return "";
        SpannableString ss = new SpannableString(post.caption);
        // Color hashtags brand_primary (#4CAF50)
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("#\\w+").matcher(post.caption);
        while (m.find()) {
            ss.setSpan(new ForegroundColorSpan(0xFF4CAF50),
                       m.start(), m.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        // Color @mentions
        m = java.util.regex.Pattern.compile("@\\w+").matcher(post.caption);
        while (m.find()) {
            ss.setSpan(new ForegroundColorSpan(0xFF22D3A6),
                       m.start(), m.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return ss;
    }

    // ── Photo Post VH ──────────────────────────────────────────────────────
    class PhotoPostVH extends RecyclerView.ViewHolder {
        PhotoPostVH(@NonNull View v) { super(v); }

        void bindPost(FeedPost post) {
            bindPostHeader(itemView, post);
            bindPostActions(itemView, post);

            // Thumbnail
            ImageView ivThumb = itemView.findViewById(R.id.iv_post_thumb);
            View      vVideoBadge = itemView.findViewById(R.id.iv_video_badge);
            if (ivThumb != null) {
                if (post.thumbUrl != null && !post.thumbUrl.isEmpty()) {
                    Glide.with(itemView.getContext())
                         .load(post.thumbUrl)
                         .centerCrop()
                         .placeholder(R.drawable.gradient_reel_bottom)
                         .into(ivThumb);
                } else {
                    ivThumb.setImageResource(R.drawable.gradient_reel_bottom);
                }
                if (vVideoBadge != null) vVideoBadge.setVisibility(View.GONE);

                // Double-tap heart
                ivThumb.setOnClickListener(v -> {
                    if (actionListener != null) actionListener.onPostThumbClicked(post);
                });
            }

            // Repost badge
            View layoutRepost = itemView.findViewById(R.id.layout_repost_badge);
            if (layoutRepost != null) {
                if (post.isRepost && post.repostByName != null) {
                    layoutRepost.setVisibility(View.VISIBLE);
                    TextView tvBy = layoutRepost.findViewById(R.id.tv_repost_by);
                    if (tvBy != null) tvBy.setText(post.repostByName + " reposted");
                } else {
                    layoutRepost.setVisibility(View.GONE);
                }
            }

            // Music bar
            View musicBar = itemView.findViewById(R.id.layout_music_bar);
            if (musicBar != null) {
                if (post.musicName != null && !post.musicName.isEmpty()) {
                    musicBar.setVisibility(View.VISIBLE);
                    TextView tvMusic = musicBar.findViewById(R.id.tv_music_name);
                    if (tvMusic != null) tvMusic.setText("♪  " + post.musicName
                            + (post.musicArtist != null ? " · " + post.musicArtist : ""));
                } else {
                    musicBar.setVisibility(View.GONE);
                }
            }
        }
    }

    // ── Video Post VH (extends PhotoPostVH behavior, adds play icon) ───────
    class VideoPostVH extends RecyclerView.ViewHolder {
        VideoPostVH(@NonNull View v) { super(v); }

        void bindPost(FeedPost post) {
            bindPostHeader(itemView, post);
            bindPostActions(itemView, post);

            ImageView ivThumb   = itemView.findViewById(R.id.iv_post_thumb);
            View      vVideoBadge = itemView.findViewById(R.id.iv_video_badge);

            if (ivThumb != null) {
                if (post.thumbUrl != null && !post.thumbUrl.isEmpty()) {
                    Glide.with(itemView.getContext())
                         .load(post.thumbUrl)
                         .centerCrop()
                         .placeholder(R.drawable.gradient_reel_bottom)
                         .into(ivThumb);
                }
                if (vVideoBadge != null) vVideoBadge.setVisibility(View.VISIBLE);

                ivThumb.setOnClickListener(v -> {
                    if (actionListener != null) actionListener.onPostThumbClicked(post);
                });
            }

            // Music bar
            View musicBar = itemView.findViewById(R.id.layout_music_bar);
            if (musicBar != null) {
                if (post.musicName != null && !post.musicName.isEmpty()) {
                    musicBar.setVisibility(View.VISIBLE);
                    TextView tvMusic = musicBar.findViewById(R.id.tv_music_name);
                    if (tvMusic != null) tvMusic.setText("♪  " + post.musicName
                            + (post.musicArtist != null ? " · " + post.musicArtist : ""));
                } else {
                    musicBar.setVisibility(View.GONE);
                }
            }
        }
    }

    // ── Carousel Post VH ───────────────────────────────────────────────────
    class CarouselPostVH extends RecyclerView.ViewHolder {
        ViewPager2 vpCarousel;
        TextView   tvDotIndicator;

        CarouselPostVH(@NonNull View v) {
            super(v);
            vpCarousel    = v.findViewById(R.id.vp_carousel);
            tvDotIndicator = v.findViewById(R.id.tv_carousel_indicator);
        }

        void bind(FeedPost post) {
            bindPostHeader(itemView, post);
            bindPostActions(itemView, post);

            List<String> photos = post.photoUrls != null ? post.photoUrls : new ArrayList<>();

            // Simple photo adapter for carousel
            vpCarousel.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                @NonNull @Override
                public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
                    ImageView iv = new ImageView(p.getContext());
                    iv.setLayoutParams(new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(350, p.getContext())));
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    return new RecyclerView.ViewHolder(iv) {};
                }
                @Override
                public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
                    Glide.with(h.itemView.getContext())
                         .load(photos.get(pos))
                         .centerCrop()
                         .into((ImageView) h.itemView);
                }
                @Override public int getItemCount() { return photos.size(); }
            });

            // Dot indicator
            if (tvDotIndicator != null && photos.size() > 1) {
                tvDotIndicator.setVisibility(View.VISIBLE);
                updateDots(tvDotIndicator, 0, photos.size());
                vpCarousel.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                    @Override
                    public void onPageSelected(int position) {
                        updateDots(tvDotIndicator, position, photos.size());
                    }
                });
            } else if (tvDotIndicator != null) {
                tvDotIndicator.setVisibility(View.GONE);
            }
        }

        void updateDots(TextView tv, int current, int total) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < total; i++) sb.append(i == current ? "●" : "○");
            tv.setText(sb.toString());
        }

        int dpToPx(int dp, Context ctx) {
            return (int) (dp * ctx.getResources().getDisplayMetrics().density);
        }
    }

    // ── Suggested Accounts VH ──────────────────────────────────────────────
    class SuggestedVH extends RecyclerView.ViewHolder {
        RecyclerView rvSuggested;
        SuggestedAccountsAdapter sugAdapter;
        View btnDismiss;

        SuggestedVH(@NonNull View v) {
            super(v);
            rvSuggested = v.findViewById(R.id.rv_suggested_accounts);
            btnDismiss  = v.findViewById(R.id.btn_suggested_dismiss);
            sugAdapter  = new SuggestedAccountsAdapter();
            rvSuggested.setLayoutManager(
                    new LinearLayoutManager(v.getContext(), LinearLayoutManager.HORIZONTAL, false));
            rvSuggested.setAdapter(sugAdapter);
        }

        void bind(List<String[]> users, String myUid) {
            sugAdapter.setUsers(users);
            sugAdapter.setFollowListener((uid, wasFollowing) -> {
                repo.toggleFollow(uid, myUid, wasFollowing, followed -> {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        sugAdapter.markFollowed(uid, followed);
                    });
                });
            });

            if (btnDismiss != null) {
                btnDismiss.setOnClickListener(v -> {
                    int pos = getBindingAdapterPosition();
                    if (pos >= 0) {
                        items.remove(TAG_SUGGESTED);
                        notifyItemRemoved(pos);
                    }
                });
            }
        }
    }

    // ── Reels Strip VH ─────────────────────────────────────────────────────
    class ReelsStripVH extends RecyclerView.ViewHolder {
        RecyclerView rvStrip;
        HomeReelsStripAdapter stripAdapter;
        View btnSeeAll;

        ReelsStripVH(@NonNull View v) {
            super(v);
            rvStrip     = v.findViewById(R.id.rv_reels_strip);
            btnSeeAll   = v.findViewById(R.id.btn_reels_strip_see_all);
            stripAdapter = new HomeReelsStripAdapter();
            rvStrip.setLayoutManager(
                    new LinearLayoutManager(v.getContext(), LinearLayoutManager.HORIZONTAL, false));
            rvStrip.setAdapter(stripAdapter);
        }

        void bind(List<ReelModel> reels) {
            stripAdapter.setReels(reels);
            if (btnSeeAll != null) {
                btnSeeAll.setOnClickListener(v -> {
                    try {
                        Intent i = new Intent(v.getContext(),
                                Class.forName("com.callx.app.explore.ReelExploreActivity"));
                        v.getContext().startActivity(i);
                    } catch (ClassNotFoundException ignored) {}
                });
            }
        }
    }

    // ── Loading VH ─────────────────────────────────────────────────────────
    static class LoadingVH extends RecyclerView.ViewHolder {
        LoadingVH(@NonNull View v) { super(v); }
    }
}
