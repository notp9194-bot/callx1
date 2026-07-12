package com.callx.app.feed;
  import android.content.Context;
  import android.view.*;
  import android.widget.ImageView;
  import android.widget.TextView;
  import androidx.annotation.NonNull;
  import androidx.recyclerview.widget.DiffUtil;
  import androidx.recyclerview.widget.LinearLayoutManager;
  import androidx.recyclerview.widget.RecyclerView;
  import com.bumptech.glide.Glide;
  import com.callx.app.status.R;
  import com.callx.app.models.StatusItem;
  import com.callx.app.utils.StatusCloseFriendsManager;
  import com.callx.app.utils.StatusMuteManager;
  import de.hdodenhof.circleimageview.CircleImageView;
  import java.text.SimpleDateFormat;
  import java.util.*;
  /**
   * StatusListAdapter v26 — Production-grade, section-aware status list.
   *
   * FIXES v26:
   *   FIX: Highlights strip — was mentioned in comments but NOT implemented; now fully added
   *   FIX: Close Friends star badge — new tv_cf_badge in item_status.xml now wired
   *   FIX: Unseen badge (tv_badge) — was declared gone, now properly shown for unseenCount > 1
   *
   * ORIGINAL (v25):
   *   DiffUtil smooth animated updates (areContentsTheSame MY_STATUS bug fixed)
   *   Muted contacts in "Muted" section
   *   Live search filtering support
   *   Status expiry time label
   *   Reaction emoji preview in row
   *   Media type icon as sub-text prefix
   */
  public class StatusListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
      public static final int TYPE_HIGHLIGHTS    = 5; // FIX: was missing
      public static final int TYPE_MY_STATUS     = 0;
      public static final int TYPE_SECTION_HEADER = 1;
      public static final int TYPE_CONTACT       = 2;
      public static final int TYPE_MUTED_HEADER  = 3;
      public static final int TYPE_MUTED_CONTACT = 4;
      public static final int TYPE_CAROUSEL      = 6; // v27: horizontal status-card carousel

      // ── Highlight album model ─────────────────────────────────────────────
      public static class HighlightAlbum {
          public final String title;
          public final String coverUrl;
          public HighlightAlbum(String title, String coverUrl) {
              this.title = title; this.coverUrl = coverUrl;
          }
      }

      // ── v27: Card model for horizontal status carousel (WhatsApp-style) ────
      public static class CardItem {
          public final boolean isMine;
          public final boolean hasStatus;
          public final String  ownerUid, ownerName, ownerPhoto, thumbUrl, bgColor;
          public final boolean unseen;
          public final boolean isMuted;
          public CardItem(boolean isMine, boolean hasStatus, String ownerUid, String ownerName,
                          String ownerPhoto, String thumbUrl, String bgColor,
                          boolean unseen, boolean isMuted) {
              this.isMine     = isMine;
              this.hasStatus  = hasStatus;
              this.ownerUid   = ownerUid;
              this.ownerName  = ownerName;
              this.ownerPhoto = ownerPhoto;
              this.thumbUrl   = thumbUrl;
              this.bgColor    = bgColor;
              this.unseen     = unseen;
              this.isMuted    = isMuted;
          }
      }

      // ── Entry model ───────────────────────────────────────────────────────
      public static class Entry {
          public final String     ownerUid;
          public final String     ownerName;
          public final String     ownerPhoto;
          public final Long       latestTimestamp;
          public final int        totalCount;
          public final int        unseenCount;
          public final StatusItem latestItem;
          public final boolean    isMuted;
          public final String     latestReaction;
          public final boolean    isCloseFriend; // FIX: new field
          public Entry(String ownerUid, String ownerName, String ownerPhoto,
                       Long latestTimestamp, int totalCount, int unseenCount,
                       StatusItem latestItem, boolean isMuted, String latestReaction,
                       boolean isCloseFriend) {
              this.ownerUid        = ownerUid;
              this.ownerName       = ownerName;
              this.ownerPhoto      = ownerPhoto;
              this.latestTimestamp = latestTimestamp;
              this.totalCount      = totalCount;
              this.unseenCount     = unseenCount;
              this.latestItem      = latestItem;
              this.isMuted         = isMuted;
              this.latestReaction  = latestReaction;
              this.isCloseFriend   = isCloseFriend;
          }
          // Backward compat: no isCloseFriend
          public Entry(String ownerUid, String ownerName, String ownerPhoto,
                       Long latestTimestamp, int totalCount, int unseenCount,
                       StatusItem latestItem, boolean isMuted, String latestReaction) {
              this(ownerUid, ownerName, ownerPhoto, latestTimestamp, totalCount, unseenCount,
                   latestItem, isMuted, latestReaction, false);
          }
      }

      // ── Internal flat list ────────────────────────────────────────────────
      private static final int ITEM_HIGHLIGHTS = 5; // FIX: new
      private static final int ITEM_MY      = 0;
      private static final int ITEM_HDR     = 1;
      private static final int ITEM_ROW     = 2;
      private static final int ITEM_MUT_HDR = 3;
      private static final int ITEM_MUT_ROW = 4;
      private static final int ITEM_CAROUSEL = 6; // v27: horizontal status-card carousel

      private static class FlatItem {
          int    kind;
          String header;
          Entry  entry;
          List<CardItem> carouselItems; // v27: populated only for ITEM_CAROUSEL
          FlatItem(int k, String h, Entry e) { kind = k; header = h; entry = e; }
          FlatItem(int k, List<CardItem> cards) { kind = k; carouselItems = cards; }
      }

      // ── State ─────────────────────────────────────────────────────────────
      private final String           myUid;
      private List<StatusItem>       myStatuses;
      private final Runnable         onMyStatusClick;
      private final Runnable         onAddStatusClick;
      private final ContactClickListener onContactClick;
      private final LongPressListener    onLongPress;
      private List<FlatItem>         items = new ArrayList<>();
      private List<HighlightAlbum>   highlights = new ArrayList<>(); // FIX: new
      private int myStatusCount = 0;
      // v27: cached last section lists, so rebuildFlatList() (highlights-only refresh) can
      // reconstruct the carousel without needing to parse it back out of flat items.
      private List<Entry> lastUnseen = new ArrayList<>();
      private List<Entry> lastSeen   = new ArrayList<>();
      private List<Entry> lastMuted  = new ArrayList<>();
      private final SimpleDateFormat timeFmt =
              new SimpleDateFormat("HH:mm", Locale.getDefault());

      public interface ContactClickListener {
          void onClick(String ownerUid, String ownerName);
      }
      public interface LongPressListener {
          void onLongPress(String ownerUid, String ownerName, boolean isMuted);
      }
      public interface HighlightClickListener {
          void onClick(HighlightAlbum album);
      }
      private HighlightClickListener onHighlightClick;

      public StatusListAdapter(String myUid, List<StatusItem> myStatuses,
                               Runnable onMyStatusClick, Runnable onAddStatusClick,
                               ContactClickListener onContactClick,
                               LongPressListener onLongPress) {
          this.myUid            = myUid;
          this.myStatuses       = myStatuses;
          this.onMyStatusClick  = onMyStatusClick;
          this.onAddStatusClick = onAddStatusClick;
          this.onContactClick   = onContactClick;
          this.onLongPress      = onLongPress;
          setHasStableIds(false);
      }

      public StatusListAdapter(String myUid, List<StatusItem> myStatuses,
                               Runnable onMyStatusClick, Runnable onAddStatusClick,
                               ContactClickListener onContactClick) {
          this(myUid, myStatuses, onMyStatusClick, onAddStatusClick, onContactClick, null);
      }

      public void setHighlightClickListener(HighlightClickListener l) { this.onHighlightClick = l; }

      // ── FIX: Update highlights strip data ────────────────────────────────
      public void updateHighlights(List<HighlightAlbum> albums) {
          this.highlights = albums != null ? albums : new ArrayList<>();
          rebuildFlatList(items);
      }

      // ── Data update ───────────────────────────────────────────────────────
      public void update(List<Entry> unseen, List<Entry> seen) {
          update(unseen, seen, new ArrayList<>());
      }

      public void update(List<Entry> unseen, List<Entry> seen, List<Entry> muted) {
          final int prevMyCount = myStatusCount;
          myStatusCount = myStatuses.size();
          lastUnseen = unseen; lastSeen = seen; lastMuted = muted;
          List<FlatItem> next = buildFlatItems(unseen, seen, muted);
          dispatchDiff(next, prevMyCount);
      }

      // v27: builds the "My status" tile + all contact statuses into one CardItem list
      // that's rendered as a single horizontal scrolling carousel (WhatsApp-style cards),
      // instead of the old vertical "My status" row + "Recent/Viewed updates" list rows.
      private List<CardItem> buildCarouselItems(List<Entry> unseen, List<Entry> seen) {
          List<CardItem> cards = new ArrayList<>();
          if (myStatuses.isEmpty()) {
              cards.add(new CardItem(true, false, myUid, "My Status", null, null, null, false, false));
          } else {
              StatusItem latest = myStatuses.get(myStatuses.size() - 1);
              String thumb = latest.thumbnailUrl != null ? latest.thumbnailUrl : latest.mediaUrl;
              cards.add(new CardItem(true, true, myUid, "My Status", latest.ownerPhoto, thumb, latest.bgColor, false, false));
          }
          for (Entry e : unseen) cards.add(entryToCard(e, true));
          for (Entry e : seen)   cards.add(entryToCard(e, false));
          return cards;
      }

      private CardItem entryToCard(Entry e, boolean unseen) {
          StatusItem latest = e.latestItem;
          String thumb = latest != null ? (latest.thumbnailUrl != null ? latest.thumbnailUrl : latest.mediaUrl) : null;
          String bg = latest != null ? latest.bgColor : null;
          return new CardItem(false, true, e.ownerUid, e.ownerName, e.ownerPhoto, thumb, bg, unseen, e.isMuted);
      }

      private List<FlatItem> buildFlatItems(List<Entry> unseen, List<Entry> seen, List<Entry> muted) {
          List<FlatItem> next = new ArrayList<>();
          // FIX: Highlights strip at very top (if any exist)
          if (!highlights.isEmpty()) {
              next.add(new FlatItem(ITEM_HIGHLIGHTS, null, null));
          }
          // v27: My-status + contacts now render as one horizontal card carousel
          next.add(new FlatItem(ITEM_CAROUSEL, buildCarouselItems(unseen, seen)));
          if (!muted.isEmpty()) {
              next.add(new FlatItem(ITEM_MUT_HDR, "Muted", null));
              for (Entry e : muted) next.add(new FlatItem(ITEM_MUT_ROW, null, e));
          }
          return next;
      }

      private void rebuildFlatList(List<FlatItem> old) {
          // Called when only highlights change — reuse the cached section lists
          // rather than re-parsing the flat list (carousel no longer exposes rows).
          final int prevMyCount = myStatusCount;
          myStatusCount = myStatuses.size();
          List<FlatItem> next = buildFlatItems(lastUnseen, lastSeen, lastMuted);
          dispatchDiff(next, prevMyCount);
      }

      private void dispatchDiff(List<FlatItem> next, int prevMyCount) {
          final List<FlatItem> old = items;
          final int fPrevMyCount = prevMyCount;
          DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
              @Override public int getOldListSize() { return old.size(); }
              @Override public int getNewListSize() { return next.size(); }
              @Override public boolean areItemsTheSame(int op, int np) {
                  FlatItem o = old.get(op), n = next.get(np);
                  if (o.kind != n.kind) return false;
                  if (o.kind == ITEM_HIGHLIGHTS) return true;
                  if (o.kind == ITEM_CAROUSEL) return true; // singleton item
                  if (o.kind == ITEM_HDR || o.kind == ITEM_MUT_HDR)
                      return java.util.Objects.equals(o.header, n.header);
                  if (o.kind == ITEM_ROW || o.kind == ITEM_MUT_ROW)
                      return o.entry != null && n.entry != null
                              && o.entry.ownerUid.equals(n.entry.ownerUid);
                  return true; // MY_STATUS
              }
              @Override public boolean areContentsTheSame(int op, int np) {
                  FlatItem o = old.get(op), n = next.get(np);
                  if (o.kind == ITEM_HIGHLIGHTS) return highlights.size() == highlights.size(); // always false = re-bind
                  if (o.kind == ITEM_CAROUSEL) return false; // always re-bind, nested RV handles its own diffing
                  if (o.kind == ITEM_ROW || o.kind == ITEM_MUT_ROW) {
                      if (o.entry == null || n.entry == null) return false;
                      return o.entry.unseenCount == n.entry.unseenCount
                          && java.util.Objects.equals(o.entry.latestTimestamp, n.entry.latestTimestamp)
                          && o.entry.totalCount == n.entry.totalCount
                          && o.entry.isMuted == n.entry.isMuted
                          && o.entry.isCloseFriend == n.entry.isCloseFriend
                          && java.util.Objects.equals(o.entry.latestReaction, n.entry.latestReaction);
                  }
                  if (o.kind == ITEM_MY) return myStatusCount == fPrevMyCount;
                  return true;
              }
          });
          items = next;
          diff.dispatchUpdatesTo(this);
      }

      // ── Adapter ───────────────────────────────────────────────────────────
      @Override public int getItemViewType(int pos) {
          switch (items.get(pos).kind) {
              case ITEM_HIGHLIGHTS: return TYPE_HIGHLIGHTS;
              case ITEM_CAROUSEL:   return TYPE_CAROUSEL;
              case ITEM_MY:         return TYPE_MY_STATUS;
              case ITEM_HDR:        return TYPE_SECTION_HEADER;
              case ITEM_MUT_HDR:    return TYPE_MUTED_HEADER;
              case ITEM_MUT_ROW:    return TYPE_MUTED_CONTACT;
              default:              return TYPE_CONTACT;
          }
      }
      @Override public int getItemCount() { return items.size(); }

      @NonNull
      @Override
      public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
          LayoutInflater li = LayoutInflater.from(parent.getContext());
          switch (vt) {
              case TYPE_HIGHLIGHTS:
                  return new HighlightsVH(li.inflate(R.layout.item_status_highlights, parent, false));
              case TYPE_CAROUSEL:
                  return new CarouselVH(li.inflate(R.layout.item_status_carousel, parent, false));
              case TYPE_MY_STATUS:
                  return new MyStatusVH(li.inflate(R.layout.item_my_status, parent, false));
              case TYPE_SECTION_HEADER:
              case TYPE_MUTED_HEADER:
                  return new HeaderVH(li.inflate(R.layout.item_status_header, parent, false));
              default:
                  return new ContactVH(li.inflate(R.layout.item_status, parent, false));
          }
      }

      @Override
      public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
          FlatItem fi = items.get(pos);
          Context ctx = holder.itemView.getContext();
          if (holder instanceof HighlightsVH) bindHighlights((HighlightsVH) holder, ctx);
          else if (holder instanceof CarouselVH) bindCarousel((CarouselVH) holder, fi.carouselItems);
          else if (holder instanceof MyStatusVH) bindMyStatus((MyStatusVH) holder, ctx);
          else if (holder instanceof HeaderVH) {
              String label = fi.kind == ITEM_MUT_HDR ? "\uD83D\uDD07 Muted" : fi.header;
              ((HeaderVH) holder).tvHeader.setText(label);
          } else bindContact((ContactVH) holder, fi.entry, ctx, fi.kind == ITEM_MUT_ROW);
      }

      // ── FIX: Highlights ViewHolder ────────────────────────────────────────
      private void bindHighlights(HighlightsVH h, Context ctx) {
          h.rvHighlights.setLayoutManager(
                  new LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false));
          HighlightAlbumAdapter albumAdapter = new HighlightAlbumAdapter(highlights, album -> {
              if (onHighlightClick != null) onHighlightClick.onClick(album);
          });
          h.rvHighlights.setAdapter(albumAdapter);
      }

      // ── v27: Status carousel (horizontal, WhatsApp-style cards) ────────────
      private void bindCarousel(CarouselVH h, List<CardItem> cards) {
          if (cards == null) cards = new ArrayList<>();
          if (h.rvCarousel.getLayoutManager() == null) {
              h.rvCarousel.setLayoutManager(
                      new LinearLayoutManager(h.itemView.getContext(), LinearLayoutManager.HORIZONTAL, false));
          }
          StatusCardAdapter cardAdapter = new StatusCardAdapter(cards, card -> {
              if (card.isMine) {
                  if (card.hasStatus) { if (onMyStatusClick != null) onMyStatusClick.run(); }
                  else                { if (onAddStatusClick != null) onAddStatusClick.run(); }
              } else if (!card.isMuted && onContactClick != null) {
                  onContactClick.onClick(card.ownerUid, card.ownerName);
              } else if (card.isMuted) {
                  android.widget.Toast.makeText(h.itemView.getContext(),
                          card.ownerName + " is muted. Long press to unmute.",
                          android.widget.Toast.LENGTH_SHORT).show();
              }
          }, card -> {
              if (!card.isMine && onLongPress != null) onLongPress.onLongPress(card.ownerUid, card.ownerName, card.isMuted);
          });
          h.rvCarousel.setAdapter(cardAdapter);
      }

      // ── My-Status ─────────────────────────────────────────────────────────
      private void bindMyStatus(MyStatusVH h, Context ctx) {
          if (myStatuses.isEmpty()) {
              h.ring.setVisibility(View.GONE);
              h.ivAdd.setVisibility(View.VISIBLE);
              h.tvName.setText("My Status");
              h.tvSub.setText("Tap to add status update");
              h.ivAvatar.setImageResource(R.drawable.ic_person);
              h.itemView.setOnClickListener(v -> { if (onAddStatusClick != null) onAddStatusClick.run(); });
          } else {
              StatusItem latest = myStatuses.get(myStatuses.size() - 1);
              h.ring.setVisibility(View.VISIBLE);
              h.ivAdd.setVisibility(View.GONE);
              h.tvName.setText("My Status");
              String timeSub = timeFmt.format(new java.util.Date(latest.timestamp != null ? latest.timestamp : 0));
              h.tvSub.setText(timeSub + " \u00B7 " + myStatuses.size() + " update"
                      + (myStatuses.size() > 1 ? "s" : "") + " \u00B7 " + latest.getExpiryLabel());
              if (latest.ownerPhoto != null && !latest.ownerPhoto.isEmpty())
                  Glide.with(ctx).load(latest.ownerPhoto).placeholder(R.drawable.ic_person).into(h.ivAvatar);
              else
                  h.ivAvatar.setImageResource(R.drawable.ic_person);
              if (h.ivThumb != null) {
                  String thumbUrl = latest.thumbnailUrl != null ? latest.thumbnailUrl : latest.mediaUrl;
                  if (thumbUrl != null && !thumbUrl.isEmpty()) {
                      h.ivThumb.setVisibility(View.VISIBLE);
                      Glide.with(ctx).load(thumbUrl).centerCrop().into(h.ivThumb);
                  } else h.ivThumb.setVisibility(View.GONE);
              }
              h.itemView.setOnClickListener(v -> { if (onMyStatusClick != null) onMyStatusClick.run(); });
          }
      }

      // ── Contact row ───────────────────────────────────────────────────────
      private void bindContact(ContactVH h, Entry e, Context ctx, boolean isMuted) {
          if (e == null) return;
          h.tvName.setText(e.ownerName != null ? e.ownerName : "");
          h.tvTime.setText(e.latestTimestamp != null
                  ? timeFmt.format(new java.util.Date(e.latestTimestamp)) : "");
          if (e.ownerPhoto != null && !e.ownerPhoto.isEmpty())
              Glide.with(ctx).load(e.ownerPhoto).placeholder(R.drawable.ic_person).into(h.ivAvatar);
          else
              h.ivAvatar.setImageResource(R.drawable.ic_person);

          h.ring.setBackgroundResource(isMuted ? R.drawable.circle_status_seen
                  : e.unseenCount > 0 ? R.drawable.circle_status_unseen : R.drawable.circle_status_seen);
          h.ring.setAlpha(isMuted ? 0.4f : 1f);

          // FIX: unseen badge properly shown
          if (h.tvBadge != null) {
              if (!isMuted && e.unseenCount > 1) {
                  h.tvBadge.setVisibility(View.VISIBLE);
                  h.tvBadge.setText(String.valueOf(e.unseenCount));
              } else {
                  h.tvBadge.setVisibility(View.GONE);
              }
          }

          // FIX: Close Friends badge
          if (h.tvCfBadge != null) {
              h.tvCfBadge.setVisibility(e.isCloseFriend ? View.VISIBLE : View.GONE);
          }

          if (h.tvSub != null) {
              StatusItem latest = e.latestItem;
              String sub = "";
              if (latest != null) {
                  if ("image".equals(latest.type))   sub = "\uD83D\uDCF7 Photo";
                  else if ("video".equals(latest.type)) sub = "\uD83C\uDFA5 Video";
                  else if ("link".equals(latest.type))  sub = "\uD83D\uDD17 Link";
                  else if ("gif".equals(latest.type))   sub = "GIF";
                  else if (latest.text != null)         sub = latest.text;
                  if (latest.caption != null && !latest.caption.isEmpty()) sub = latest.caption;
                  if (isMuted) sub = "\uD83D\uDD07 " + sub;
              }
              h.tvSub.setText(sub);
          }

          if (h.ivThumb != null) {
              StatusItem latest = e.latestItem;
              String url = latest != null ? (latest.thumbnailUrl != null ? latest.thumbnailUrl : latest.mediaUrl) : null;
              if (url != null && !url.isEmpty()) {
                  h.ivThumb.setVisibility(View.VISIBLE);
                  Glide.with(ctx).load(url).centerCrop().into(h.ivThumb);
              } else h.ivThumb.setVisibility(View.GONE);
          }

          if (h.tvReaction != null) {
              if (e.latestReaction != null) {
                  h.tvReaction.setVisibility(View.VISIBLE);
                  h.tvReaction.setText(e.latestReaction);
              } else h.tvReaction.setVisibility(View.GONE);
          }

          h.itemView.setOnClickListener(v -> {
              if (!isMuted && onContactClick != null)
                  onContactClick.onClick(e.ownerUid, e.ownerName);
              else if (isMuted)
                  android.widget.Toast.makeText(ctx,
                          e.ownerName + " is muted. Long press to unmute.",
                          android.widget.Toast.LENGTH_SHORT).show();
          });
          h.itemView.setOnLongClickListener(v -> {
              if (onLongPress != null) onLongPress.onLongPress(e.ownerUid, e.ownerName, isMuted);
              return true;
          });
      }

      // ── ViewHolders ───────────────────────────────────────────────────────
      // FIX: new HighlightsVH
      static class HighlightsVH extends RecyclerView.ViewHolder {
          RecyclerView rvHighlights;
          HighlightsVH(View v) {
              super(v);
              rvHighlights = v.findViewById(R.id.rv_highlights);
          }
      }

      // v27: holder for the horizontal status-card carousel
      static class CarouselVH extends RecyclerView.ViewHolder {
          RecyclerView rvCarousel;
          CarouselVH(View v) {
              super(v);
              rvCarousel = v.findViewById(R.id.rv_status_carousel);
          }
      }

      static class MyStatusVH extends RecyclerView.ViewHolder {
          CircleImageView ivAvatar;
          ImageView ivAdd, ring, ivThumb;
          TextView tvName, tvSub;
          MyStatusVH(View v) {
              super(v);
              ivAvatar = v.findViewById(R.id.iv_avatar);
              ivAdd    = v.findViewById(R.id.iv_add);
              ring     = v.findViewById(R.id.ring);
              tvName   = v.findViewById(R.id.tv_name);
              tvSub    = v.findViewById(R.id.tv_sub);
              ivThumb  = v.findViewById(R.id.iv_thumb);
          }
      }

      static class HeaderVH extends RecyclerView.ViewHolder {
          TextView tvHeader;
          HeaderVH(View v) { super(v); tvHeader = v.findViewById(R.id.tv_header); }
      }

      static class ContactVH extends RecyclerView.ViewHolder {
          CircleImageView ivAvatar;
          ImageView ring, ivThumb;
          TextView tvName, tvTime, tvSub, tvBadge, tvReaction, tvCfBadge; // FIX: tvCfBadge added
          ContactVH(View v) {
              super(v);
              ivAvatar   = v.findViewById(R.id.iv_avatar);
              ring       = v.findViewById(R.id.ring);
              tvName     = v.findViewById(R.id.tv_name);
              tvTime     = v.findViewById(R.id.tv_time);
              tvSub      = v.findViewById(R.id.tv_sub);
              tvBadge    = v.findViewById(R.id.tv_badge);
              ivThumb    = v.findViewById(R.id.iv_thumb);
              tvReaction = v.findViewById(R.id.tv_reaction);
              tvCfBadge  = v.findViewById(R.id.tv_cf_badge); // FIX: new
          }
      }

      // ── Inner adapter for highlights horizontal strip ──────────────────────
      static class HighlightAlbumAdapter extends RecyclerView.Adapter<HighlightAlbumAdapter.VH> {
          private final List<HighlightAlbum> albums;
          private final java.util.function.Consumer<HighlightAlbum> onClick;
          HighlightAlbumAdapter(List<HighlightAlbum> albums, java.util.function.Consumer<HighlightAlbum> onClick) {
              this.albums = albums; this.onClick = onClick;
          }
          @NonNull @Override
          public VH onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
              return new VH(LayoutInflater.from(parent.getContext())
                      .inflate(R.layout.item_highlight_album, parent, false));
          }
          @Override public void onBindViewHolder(@NonNull VH h, int pos) {
              HighlightAlbum a = albums.get(pos);
              h.tvTitle.setText(a.title);
              if (a.coverUrl != null && !a.coverUrl.isEmpty())
                  Glide.with(h.ivCover).load(a.coverUrl).circleCrop().into(h.ivCover);
              h.itemView.setOnClickListener(v -> onClick.accept(a));
          }
          @Override public int getItemCount() { return albums.size(); }
          static class VH extends RecyclerView.ViewHolder {
              CircleImageView ivCover;
              TextView tvTitle;
              VH(View v) { super(v); ivCover = v.findViewById(R.id.iv_highlight_cover); tvTitle = v.findViewById(R.id.tv_highlight_title); }
          }
      }

      // ── v27: Inner adapter for the horizontal status-card carousel ─────────
      static class StatusCardAdapter extends RecyclerView.Adapter<StatusCardAdapter.VH> {
          private final List<CardItem> cards;
          private final java.util.function.Consumer<CardItem> onClick;
          private final java.util.function.Consumer<CardItem> onLongClick;

          StatusCardAdapter(List<CardItem> cards,
                             java.util.function.Consumer<CardItem> onClick,
                             java.util.function.Consumer<CardItem> onLongClick) {
              this.cards = cards;
              this.onClick = onClick;
              this.onLongClick = onLongClick;
          }

          @NonNull @Override
          public VH onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
              return new VH(LayoutInflater.from(parent.getContext())
                      .inflate(R.layout.item_status_card, parent, false));
          }

          @Override public void onBindViewHolder(@NonNull VH h, int pos) {
              CardItem c = cards.get(pos);
              Context ctx = h.itemView.getContext();

              h.tvName.setText(c.isMine ? "My Status" : (c.ownerName != null ? c.ownerName : ""));

              // Card background image: latest status media, or the owner's profile photo,
              // or a plain color fallback (already set as the ImageView's default background).
              String bg = c.thumbUrl != null && !c.thumbUrl.isEmpty() ? c.thumbUrl : c.ownerPhoto;
              if (bg != null && !bg.isEmpty()) {
                  Glide.with(ctx).load(bg).centerCrop()
                          .placeholder(R.drawable.ic_person)
                          .into(h.ivBg);
              } else {
                  h.ivBg.setImageResource(R.drawable.ic_person);
              }

              if (c.ownerPhoto != null && !c.ownerPhoto.isEmpty())
                  Glide.with(ctx).load(c.ownerPhoto).placeholder(R.drawable.ic_person).into(h.ivAvatar);
              else
                  h.ivAvatar.setImageResource(R.drawable.ic_person);

              if (c.isMine && !c.hasStatus) {
                  // "Add status" tile — grey ring, plus badge, no status yet
                  h.ring.setVisibility(View.GONE);
                  h.ivAddBadge.setVisibility(View.VISIBLE);
              } else {
                  h.ring.setVisibility(View.VISIBLE);
                  h.ring.setBackgroundResource(c.isMuted ? R.drawable.circle_status_seen
                          : c.unseen || c.isMine ? R.drawable.circle_status_unseen : R.drawable.circle_status_seen);
                  h.ring.setAlpha(c.isMuted ? 0.4f : 1f);
                  h.ivAddBadge.setVisibility(c.isMine ? View.VISIBLE : View.GONE);
              }

              h.itemView.setOnClickListener(v -> { if (onClick != null) onClick.accept(c); });
              h.itemView.setOnLongClickListener(v -> { if (onLongClick != null) onLongClick.accept(c); return true; });
          }

          @Override public int getItemCount() { return cards.size(); }

          static class VH extends RecyclerView.ViewHolder {
              ImageView ivBg, ring, ivAddBadge;
              CircleImageView ivAvatar;
              TextView tvName;
              VH(View v) {
                  super(v);
                  ivBg       = v.findViewById(R.id.iv_card_bg);
                  ring       = v.findViewById(R.id.ring);
                  ivAvatar   = v.findViewById(R.id.iv_card_avatar);
                  ivAddBadge = v.findViewById(R.id.iv_card_add);
                  tvName     = v.findViewById(R.id.tv_card_name);
              }
          }
      }
  }