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
  import com.callx.app.utils.HighlightRingDrawable;
  import com.callx.app.cache.StatusAvatarBinder;
  import com.callx.app.cache.AvatarVersionSyncManager;
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
          public final String albumId;
          public final String title;
          public final String coverUrl;
          /** Custom ring color (hex) set via HighlightRingColorPickerBottomSheet,
           *  or null/empty to use the default app-wide StoryRingGradientDrawable. */
          public final String ringColor;
          /** {@link com.callx.app.utils.HighlightRingDrawable#MODE_SOLID} or
           *  {@link com.callx.app.utils.HighlightRingDrawable#MODE_DOMINANT};
           *  only meaningful when ringColor is non-null. */
          public final String ringMode;
          public HighlightAlbum(String albumId, String title, String coverUrl,
                                 String ringColor, String ringMode) {
              this.albumId = albumId; this.title = title; this.coverUrl = coverUrl;
              this.ringColor = ringColor; this.ringMode = ringMode;
          }
      }

      // ── v27: Card model for horizontal status carousel (WhatsApp-style) ────
      public static class CardItem {
          public final boolean isMine;
          public final boolean hasStatus;
          public final String  ownerUid, ownerName, ownerPhoto, thumbUrl, bgColor;
          public final boolean unseen;
          public final boolean isMuted;
          /** Custom ring color (hex) picked in NewStatusActivity via
           *  HighlightRingColorPickerBottomSheet, or null/empty for the default ring. */
          public final String  ringColor;
          /** {@link com.callx.app.utils.HighlightRingDrawable#MODE_SOLID} or
           *  {@link com.callx.app.utils.HighlightRingDrawable#MODE_DOMINANT};
           *  only meaningful when ringColor is non-empty. */
          public final String  ringMode;
          /** v236: true only for the trailing "Hidden" tile appended to the
           *  carousel when the user has 1+ hidden contacts. Every other field
           *  is unused/blank on that tile — StatusCardAdapter special-cases it. */
          public final boolean isHiddenTile;
          /** See {@link Entry#avatarVersion} — same "0 = unversioned, no-op" contract. */
          public final long avatarVersion;
          public CardItem(boolean isMine, boolean hasStatus, String ownerUid, String ownerName,
                          String ownerPhoto, String thumbUrl, String bgColor,
                          boolean unseen, boolean isMuted) {
              this(isMine, hasStatus, ownerUid, ownerName, ownerPhoto, thumbUrl, bgColor,
                      unseen, isMuted, null, null);
          }
          public CardItem(boolean isMine, boolean hasStatus, String ownerUid, String ownerName,
                          String ownerPhoto, String thumbUrl, String bgColor,
                          boolean unseen, boolean isMuted, String ringColor, String ringMode) {
              this(isMine, hasStatus, ownerUid, ownerName, ownerPhoto, thumbUrl, bgColor,
                      unseen, isMuted, ringColor, ringMode, false);
          }
          public CardItem(boolean isMine, boolean hasStatus, String ownerUid, String ownerName,
                          String ownerPhoto, String thumbUrl, String bgColor,
                          boolean unseen, boolean isMuted, String ringColor, String ringMode,
                          boolean isHiddenTile) {
              this(isMine, hasStatus, ownerUid, ownerName, ownerPhoto, thumbUrl, bgColor,
                      unseen, isMuted, ringColor, ringMode, isHiddenTile, 0L);
          }
          public CardItem(boolean isMine, boolean hasStatus, String ownerUid, String ownerName,
                          String ownerPhoto, String thumbUrl, String bgColor,
                          boolean unseen, boolean isMuted, String ringColor, String ringMode,
                          boolean isHiddenTile, long avatarVersion) {
              this.isMine       = isMine;
              this.hasStatus    = hasStatus;
              this.ownerUid     = ownerUid;
              this.ownerName    = ownerName;
              this.ownerPhoto   = ownerPhoto;
              this.thumbUrl     = thumbUrl;
              this.bgColor      = bgColor;
              this.unseen       = unseen;
              this.isMuted      = isMuted;
              this.ringColor    = ringColor;
              this.ringMode     = ringMode;
              this.isHiddenTile = isHiddenTile;
              this.avatarVersion = avatarVersion;
          }
          /** v236: factory for the trailing "Hidden" tile. */
          public static CardItem hiddenTile() {
              return new CardItem(false, true, "__hidden__", "Hidden",
                      null, null, null, false, false, null, null, true);
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
          /**
           * FIX (version param combined with cache tier): 0 when the caller
           * doesn't have it handy — AvatarUrlBuilder omits the &v= param
           * entirely in that case, same unversioned behavior as before this
           * field existed. When the data layer does supply a real avatarVersion
           * (e.g. from Room's UserEntity or a live AvatarVersionSyncManager
           * watch), passing it here means a fresh profile-photo upload
           * invalidates this row's Glide/CDN cache key immediately instead of
           * waiting for ownerPhoto's URL string itself to change.
           */
          public final long avatarVersion;
          public Entry(String ownerUid, String ownerName, String ownerPhoto,
                       Long latestTimestamp, int totalCount, int unseenCount,
                       StatusItem latestItem, boolean isMuted, String latestReaction,
                       boolean isCloseFriend, long avatarVersion) {
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
              this.avatarVersion   = avatarVersion;
          }
          // Backward compat: no avatarVersion
          public Entry(String ownerUid, String ownerName, String ownerPhoto,
                       Long latestTimestamp, int totalCount, int unseenCount,
                       StatusItem latestItem, boolean isMuted, String latestReaction,
                       boolean isCloseFriend) {
              this(ownerUid, ownerName, ownerPhoto, latestTimestamp, totalCount, unseenCount,
                   latestItem, isMuted, latestReaction, isCloseFriend, 0L);
          }
          // Backward compat: no isCloseFriend, no avatarVersion
          public Entry(String ownerUid, String ownerName, String ownerPhoto,
                       Long latestTimestamp, int totalCount, int unseenCount,
                       StatusItem latestItem, boolean isMuted, String latestReaction) {
              this(ownerUid, ownerName, ownerPhoto, latestTimestamp, totalCount, unseenCount,
                   latestItem, isMuted, latestReaction, false, 0L);
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
      private int lastHiddenCount    = 0; // v236
      private Runnable onHiddenCardClick; // v236
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

      /** v236: called when the trailing "Hidden" carousel tile is tapped. */
      public void setOnHiddenCardClickListener(Runnable l) { this.onHiddenCardClick = l; }

      // ── FIX: Update highlights strip data ────────────────────────────────
      public void updateHighlights(List<HighlightAlbum> albums) {
          this.highlights = albums != null ? albums : new ArrayList<>();
          rebuildFlatList(items);
      }

      // ── Data update ───────────────────────────────────────────────────────
      public void update(List<Entry> unseen, List<Entry> seen) {
          update(unseen, seen, new ArrayList<>(), 0);
      }

      public void update(List<Entry> unseen, List<Entry> seen, List<Entry> muted) {
          update(unseen, seen, muted, 0);
      }

      /** v236: hiddenCount appends a trailing "Hidden" tile to the carousel when > 0. */
      public void update(List<Entry> unseen, List<Entry> seen, List<Entry> muted, int hiddenCount) {
          final int prevMyCount = myStatusCount;
          myStatusCount = myStatuses.size();
          lastUnseen = unseen; lastSeen = seen; lastMuted = muted; lastHiddenCount = hiddenCount;
          List<FlatItem> next = buildFlatItems(unseen, seen, muted, hiddenCount);
          dispatchDiff(next, prevMyCount);
      }

      // v27: builds the "My status" tile + all contact statuses into one CardItem list
      // that's rendered as a single horizontal scrolling carousel (WhatsApp-style cards),
      // instead of the old vertical "My status" row + "Recent/Viewed updates" list rows.
      // v236: optionally appends a trailing "Hidden" tile at the very end.
      private List<CardItem> buildCarouselItems(List<Entry> unseen, List<Entry> seen, int hiddenCount) {
          List<CardItem> cards = new ArrayList<>();
          if (myStatuses.isEmpty()) {
              cards.add(new CardItem(true, false, myUid, "My Status", null, null, null, false, false));
          } else {
              StatusItem latest = myStatuses.get(myStatuses.size() - 1);
              String thumb = latest.thumbnailUrl != null ? latest.thumbnailUrl : latest.mediaUrl;
              cards.add(new CardItem(true, true, myUid, "My Status", latest.ownerPhoto, thumb, latest.bgColor,
                      false, false, latest.ringColor, latest.ringMode));
          }
          for (Entry e : unseen) cards.add(entryToCard(e, true));
          for (Entry e : seen)   cards.add(entryToCard(e, false));
          if (hiddenCount > 0)   cards.add(CardItem.hiddenTile());
          return cards;
      }

      private CardItem entryToCard(Entry e, boolean unseen) {
          StatusItem latest = e.latestItem;
          String thumb = latest != null ? (latest.thumbnailUrl != null ? latest.thumbnailUrl : latest.mediaUrl) : null;
          String bg = latest != null ? latest.bgColor : null;
          String ringColor = latest != null ? latest.ringColor : null;
          String ringMode  = latest != null ? latest.ringMode  : null;
          return new CardItem(false, true, e.ownerUid, e.ownerName, e.ownerPhoto, thumb, bg,
                  unseen, e.isMuted, ringColor, ringMode, false, e.avatarVersion);
      }

      /**
       * FIX (version param combined with cache tier): prefers whatever
       * version the Entry/CardItem itself carries (0 if the data layer
       * hasn't been wired to supply one yet — see Entry#avatarVersion),
       * falling back to AvatarVersionSyncManager's cached best-known value
       * for this uid so a live avatar-version bump observed by ANY screen
       * (reel player, follow list...) still invalidates this row's cache
       * key without Status needing its own per-row Firebase listener.
       */
      private static long resolveAvatarVersion(Context ctx, String ownerUid, long modelVersion) {
          if (modelVersion > 0) return modelVersion;
          return AvatarVersionSyncManager.getInstance(ctx).getCachedVersion(ownerUid);
      }

      private List<FlatItem> buildFlatItems(List<Entry> unseen, List<Entry> seen, List<Entry> muted, int hiddenCount) {
          List<FlatItem> next = new ArrayList<>();
          // FIX: Highlights strip at very top (if any exist)
          if (!highlights.isEmpty()) {
              next.add(new FlatItem(ITEM_HIGHLIGHTS, null, null));
          }
          // v27: My-status + contacts now render as one horizontal card carousel
          // v236: trailing "Hidden" tile appended inside the carousel when hiddenCount > 0
          next.add(new FlatItem(ITEM_CAROUSEL, buildCarouselItems(unseen, seen, hiddenCount)));
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
          List<FlatItem> next = buildFlatItems(lastUnseen, lastSeen, lastMuted, lastHiddenCount);
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

      // FIX (isVisible gate — attach-time promotion / detach-time cancel):
      // ContactVH's avatar is bound via StatusAvatarBinder#bindGated, which
      // only issues a disk-cache-only load when the row isn't attached to
      // the window yet (a RecyclerView layout-prefetch bind ahead of
      // scroll). onViewAttachedToWindow promotes that pending gated bind to
      // a real HIGH-priority request the moment the row is confirmed
      // visible; onViewRecycled cancels an in-flight request outright for a
      // row that just scrolled off screen — the Java equivalent of
      // cancelling a coroutine Job on scope-exit (see
      // ReelUiController#onBecameInvisible for the same fix in Reels).
      @Override
      public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder holder) {
          super.onViewAttachedToWindow(holder);
          if (!(holder instanceof ContactVH)) return;
          int pos = holder.getBindingAdapterPosition();
          if (pos < 0 || pos >= items.size()) return;
          FlatItem fi = items.get(pos);
          if (fi.entry == null) return;
          Context ctx = holder.itemView.getContext();
          StatusAvatarBinder.promote(ctx, ((ContactVH) holder).ivAvatar, fi.entry.ownerPhoto,
                  resolveAvatarVersion(ctx, fi.entry.ownerUid, fi.entry.avatarVersion), R.drawable.ic_person);
      }

      @Override
      public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
          super.onViewRecycled(holder);
          if (holder instanceof ContactVH) {
              StatusAvatarBinder.cancel(holder.itemView.getContext(), ((ContactVH) holder).ivAvatar);
          }
      }

      /**
       * AvatarSource view over the currently-flattened contact rows, for
       * StatusAvatarBinder#prefetch() — see StatusFragment's scroll
       * listener. fromPosition is the OUTER (this adapter's own) adapter
       * position; entries at a non-contact-row position (headers, the
       * carousel, muted header) are simply skipped, same as a null/empty
       * photo would be.
       */
      public StatusAvatarBinder.AvatarSource contactAvatarSource() {
          return new StatusAvatarBinder.AvatarSource() {
              @Override public String photo(int index) {
                  Entry e = entryAt(index);
                  return e != null ? e.ownerPhoto : null;
              }
              @Override public long avatarVersion(int index) {
                  Entry e = entryAt(index);
                  return e != null ? e.avatarVersion : 0L;
              }
              @Override public int size() { return items.size(); }
          };
      }

      private Entry entryAt(int position) {
          if (position < 0 || position >= items.size()) return null;
          return items.get(position).entry;
      }

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
              if (card.isHiddenTile) {
                  if (onHiddenCardClick != null) onHiddenCardClick.run();
              } else if (card.isMine) {
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
              if (!card.isMine && !card.isHiddenTile && onLongPress != null)
                  onLongPress.onLongPress(card.ownerUid, card.ownerName, card.isMuted);
          });
          h.rvCarousel.setAdapter(cardAdapter);
      }

      // ── My-Status ─────────────────────────────────────────────────────────
      // WhatsApp-level behaviour: the small green "+" badge on the avatar
      // always adds a new status update — it never disappears just because a
      // status already exists. Tapping the rest of the row (ring/avatar/text)
      // opens the viewer for existing status(es). Previously ivAdd was hidden
      // and left unwired once myStatuses was non-empty, so there was no way
      // to add a second update — only the first one's viewer ever opened.
      private void bindMyStatus(MyStatusVH h, Context ctx) {
          h.ivAdd.setVisibility(View.VISIBLE);
          h.ivAdd.setOnClickListener(v -> { if (onAddStatusClick != null) onAddStatusClick.run(); });

          if (myStatuses.isEmpty()) {
              h.ring.setVisibility(View.GONE);
              h.tvName.setText("My Status");
              h.tvSub.setText("Tap to add status update");
              h.ivAvatar.setImageResource(R.drawable.ic_person);
              if (h.ivThumb != null) h.ivThumb.setVisibility(View.GONE);
              h.itemView.setOnClickListener(v -> { if (onAddStatusClick != null) onAddStatusClick.run(); });
          } else {
              StatusItem latest = myStatuses.get(myStatuses.size() - 1);
              h.ring.setVisibility(View.VISIBLE);
              applyRingStyle(h.ring, latest, ctx, false);
              h.tvName.setText("My Status");
              String timeSub = timeFmt.format(new java.util.Date(latest.timestamp != null ? latest.timestamp : 0));
              h.tvSub.setText(timeSub + " \u00B7 " + myStatuses.size() + " update"
                      + (myStatuses.size() > 1 ? "s" : "") + " \u00B7 " + latest.getExpiryLabel());
              // FIX (deep avatar pipeline): shared AvatarSizeTier + density/
              // WebP-AVIF aware URL, L2/L3 reuse, and blur-up thumbnail —
              // see StatusAvatarBinder class doc. This row is always bound
              // while visible (it's the very first row), so the plain
              // bind() entry point is enough; bindGated() below is for the
              // scrollable contact rows/carousel where offscreen binds happen.
              StatusAvatarBinder.bind(ctx, h.ivAvatar, latest.ownerPhoto,
                      resolveAvatarVersion(ctx, myUid, 0L), R.drawable.ic_person);
              if (h.ivThumb != null) {
                  String thumbUrl = latest.thumbnailUrl != null ? latest.thumbnailUrl : latest.mediaUrl;
                  if (thumbUrl != null && !thumbUrl.isEmpty()) {
                      h.ivThumb.setVisibility(View.VISIBLE);
                      Glide.with(ctx).load(thumbUrl).centerCrop().override(480, 853).into(h.ivThumb);
                  } else h.ivThumb.setVisibility(View.GONE);
              }
              h.itemView.setOnClickListener(v -> { if (onMyStatusClick != null) onMyStatusClick.run(); });
          }
      }

      // ── Custom avatar ring color (NEW) ──────────────────────────────────────
      /**
       * When the given status carries a custom ringColor (set in NewStatusActivity
       * via the same HighlightRingColorPickerBottomSheet used for Highlight albums),
       * paints the avatar ring in that color/mode instead of the default
       * seen/unseen ring drawable. Leaves the default ring untouched when no
       * custom color was picked (ringColor null/empty), or on a bad hex value.
       */
      private void applyRingStyle(ImageView ring, StatusItem item, Context ctx, boolean isMuted) {
          if (ring == null || item == null) return;
          String color = item.ringColor;
          if (color == null || color.isEmpty()) return; // keep default ring drawable/resource
          try {
              int parsedColor = android.graphics.Color.parseColor(color);
              float density = ctx.getResources().getDisplayMetrics().density;
              // BUG FIX: this ImageView's default ring (circle_status_unseen) is set via
              // android:src in the layout, not android:background — src always draws ON
              // TOP of background, so setBackground() here was invisible, silently hidden
              // behind the static default ring image. That's why a custom color never
              // showed up for anyone viewing the status: setImageDrawable() is what
              // actually replaces the visible ring.
              ring.setImageDrawable(HighlightRingDrawable.withStrokeDp(parsedColor, item.ringMode, 2.5f, density));
              ring.setAlpha(isMuted ? 0.4f : 1f);
          } catch (IllegalArgumentException ignored) {
              // Invalid/unparseable hex — silently keep the default ring.
          }
      }

      // ── Contact row ───────────────────────────────────────────────────────
      private void bindContact(ContactVH h, Entry e, Context ctx, boolean isMuted) {
          if (e == null) return;
          h.tvName.setText(e.ownerName != null ? e.ownerName : "");
          h.tvTime.setText(e.latestTimestamp != null
                  ? timeFmt.format(new java.util.Date(e.latestTimestamp)) : "");
          // FIX (deep avatar pipeline + isVisible gate): bindGated() only
          // fires a real network-capable request while this row is actually
          // attached to the window — a RecyclerView layout-prefetch bind
          // ahead of scroll gets a disk-cache-only load instead (see
          // StatusAvatarBinder#bindGated). onViewAttachedToWindow (below)
          // promotes it once the row genuinely becomes visible.
          StatusAvatarBinder.bindGated(ctx, h.ivAvatar, e.ownerPhoto,
                  resolveAvatarVersion(ctx, e.ownerUid, e.avatarVersion), R.drawable.ic_person);

          // BUG FIX: same src-vs-background layering issue as applyRingStyle() —
          // the ring ImageView's default state is an android:src drawable, which
          // always paints over a background, so this seen/unseen swap was
          // silently invisible too. setImageResource() is the one that's
          // actually visible; also clear any leftover custom-color background
          // from a previous bind so it doesn't peek out from behind.
          h.ring.setBackground(null);
          h.ring.setImageResource(isMuted ? R.drawable.circle_status_seen
                  : e.unseenCount > 0 ? R.drawable.circle_status_unseen : R.drawable.circle_status_seen);
          h.ring.setAlpha(isMuted ? 0.4f : 1f);
          applyRingStyle(h.ring, e.latestItem, ctx, isMuted);

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
                  Glide.with(ctx).load(url).centerCrop().override(480, 853).into(h.ivThumb);
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
              Context ctx = h.itemView.getContext();
              h.tvTitle.setText(a.title);
              if (a.coverUrl != null && !a.coverUrl.isEmpty())
                  Glide.with(h.ivCover).load(a.coverUrl).circleCrop().override(96, 96).into(h.ivCover);
              // Ring: the color/gradient the user picked for this highlight, or —
              // when they never chose one — the same default app-wide gradient ring
              // used for live stories, instead of a static "seen" overlay.
              float density = ctx.getResources().getDisplayMetrics().density;
              if (a.ringColor != null && !a.ringColor.isEmpty()) {
                  int customColor;
                  try { customColor = android.graphics.Color.parseColor(a.ringColor); }
                  catch (Exception e) { customColor = android.graphics.Color.parseColor("#DD2A7B"); }
                  h.ivRing.setBackground(com.callx.app.utils.HighlightRingDrawable
                          .withStrokeDp(customColor, a.ringMode, 2.5f, density));
              } else {
                  h.ivRing.setBackground(com.callx.app.utils.StoryRingGradientDrawable
                          .withStrokeDp(2.5f, density));
              }
              h.itemView.setOnClickListener(v -> onClick.accept(a));
          }
          @Override public int getItemCount() { return albums.size(); }
          static class VH extends RecyclerView.ViewHolder {
              CircleImageView ivCover;
              ImageView ivRing;
              TextView tvTitle;
              VH(View v) {
                  super(v);
                  ivCover = v.findViewById(R.id.iv_highlight_cover);
                  ivRing  = v.findViewById(R.id.iv_highlight_ring);
                  tvTitle = v.findViewById(R.id.tv_highlight_title);
              }
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

              // v236: trailing "Hidden" tile — eye-off icon, no ring/badge/thumb, own click path
              if (c.isHiddenTile) {
                  h.tvName.setText("Hidden");
                  h.ivBg.setImageResource(com.callx.app.core.R.drawable.ic_eye_off);
                  h.ivBg.setScaleType(ImageView.ScaleType.CENTER);
                  h.ivBg.setBackgroundColor(android.graphics.Color.parseColor("#2A2A2A"));
                  h.ivBg.setColorFilter(android.graphics.Color.parseColor("#99FFFFFF"));
                  h.ring.setVisibility(View.GONE);
                  h.ivAvatar.setVisibility(View.GONE);
                  h.ivAddBadge.setVisibility(View.GONE);
                  if (h.flAvatar != null) h.flAvatar.setVisibility(View.GONE);
                  h.itemView.setOnClickListener(v -> { if (onClick != null) onClick.accept(c); });
                  h.itemView.setOnLongClickListener(null);
                  return;
              }
              // Reset flags a recycled hidden-tile VH may have left behind
              h.ivBg.setScaleType(ImageView.ScaleType.CENTER_CROP);
              h.ivBg.setColorFilter(null);
              h.ivBg.setBackgroundColor(android.graphics.Color.parseColor("#3A3A3A"));
              h.ring.setVisibility(View.VISIBLE);
              h.ivAvatar.setVisibility(View.VISIBLE);
              if (h.flAvatar != null) h.flAvatar.setVisibility(View.VISIBLE);

              h.tvName.setText(c.isMine ? "My Status" : (c.ownerName != null ? c.ownerName : ""));

              // Card background image: latest status media, or the owner's profile photo,
              // or a plain color fallback (already set as the ImageView's default background).
              String bg = c.thumbUrl != null && !c.thumbUrl.isEmpty() ? c.thumbUrl : c.ownerPhoto;
              if (bg != null && !bg.isEmpty()) {
                  Glide.with(ctx).load(bg).centerCrop()
                          .placeholder(R.drawable.ic_person)
                          .override(480, 853)
                          .into(h.ivBg);
              } else {
                  h.ivBg.setImageResource(R.drawable.ic_person);
              }

              // FIX (deep avatar pipeline + isVisible gate) — same
              // StatusAvatarBinder pipeline as the vertical contact rows;
              // this carousel is its own nested RecyclerView so its rows get
              // the identical offscreen-bind gate + attach-time promotion
              // (see onViewAttachedToWindow/onViewRecycled below).
              StatusAvatarBinder.bindGated(ctx, h.ivAvatar, c.ownerPhoto,
                      resolveAvatarVersion(ctx, c.ownerUid, c.avatarVersion), R.drawable.ic_person);

              if (c.isMine && !c.hasStatus) {
                  // "Add status" tile — grey ring, plus badge, no status yet
                  h.ring.setVisibility(View.GONE);
                  h.ivAddBadge.setVisibility(View.VISIBLE);
              } else {
                  h.ring.setVisibility(View.VISIBLE);
                  // BUG FIX: ring ImageView's default drawable is set via android:src
                  // in item_status_card.xml, which always paints over a background —
                  // setBackgroundResource()/setBackground() here were both invisible,
                  // hidden behind that static src image. setImageResource()/
                  // setImageDrawable() are what actually change what's on screen.
                  h.ring.setBackground(null);
                  h.ring.setImageResource(c.isMuted ? R.drawable.circle_status_seen
                          : c.unseen || c.isMine ? R.drawable.circle_status_unseen : R.drawable.circle_status_seen);
                  h.ring.setAlpha(c.isMuted ? 0.4f : 1f);
                  // Custom ring color/gradient (picked via HighlightRingColorPickerBottomSheet
                  // in NewStatusActivity) overrides the default seen/unseen ring, same as the
                  // vertical list rows via applyRingStyle().
                  if (c.ringColor != null && !c.ringColor.isEmpty()) {
                      try {
                          int customColor = android.graphics.Color.parseColor(c.ringColor);
                          float density = ctx.getResources().getDisplayMetrics().density;
                          h.ring.setImageDrawable(HighlightRingDrawable.withStrokeDp(customColor, c.ringMode, 2.5f, density));
                          h.ring.setAlpha(c.isMuted ? 0.4f : 1f);
                      } catch (IllegalArgumentException ignored) {
                          // Invalid/unparseable hex — keep the default ring resource set above.
                      }
                  }
                  h.ivAddBadge.setVisibility(c.isMine ? View.VISIBLE : View.GONE);
              }

              // WhatsApp-level fix: the + badge must always add a new status update,
              // even after one already exists — previously it was visible but dead,
              // since the whole card's click always resolved to onMyStatusClick once
              // hasStatus was true, so there was no way to add a second update.
              //
              // ✅ FIX: the + badge alone is only 17dp — too small to tap reliably.
              // The avatar/ring container (fl_avatar, 48dp) now shares the same
              // "always add" behaviour, so tapping the avatar OR the plus badge
              // both start a new status. Tapping the rest of the card (background,
              // name, ring edge outside the avatar circle) still opens the viewer
              // when a status already exists.
              if (c.isMine) {
                  View.OnClickListener addClick = v -> {
                      if (onClick != null) {
                          onClick.accept(new CardItem(true, false, c.ownerUid, c.ownerName,
                                  c.ownerPhoto, c.thumbUrl, c.bgColor, c.unseen, c.isMuted));
                      }
                  };
                  h.ivAddBadge.setOnClickListener(addClick);
                  if (h.flAvatar != null) h.flAvatar.setOnClickListener(addClick);
              } else {
                  h.ivAddBadge.setOnClickListener(null);
                  if (h.flAvatar != null) h.flAvatar.setOnClickListener(null);
              }

              h.itemView.setOnClickListener(v -> { if (onClick != null) onClick.accept(c); });
              h.itemView.setOnLongClickListener(v -> { if (onLongClick != null) onLongClick.accept(c); return true; });
          }

          @Override public int getItemCount() { return cards.size(); }

          // FIX (isVisible gate) — same attach-promote/recycle-cancel pair as
          // the outer StatusListAdapter, scoped to this nested carousel RV.
          @Override
          public void onViewAttachedToWindow(@NonNull VH holder) {
              super.onViewAttachedToWindow(holder);
              int pos = holder.getBindingAdapterPosition();
              if (pos < 0 || pos >= cards.size()) return;
              CardItem c = cards.get(pos);
              if (c.isHiddenTile || c.ownerPhoto == null || c.ownerPhoto.isEmpty()) return;
              Context ctx = holder.itemView.getContext();
              StatusAvatarBinder.promote(ctx, holder.ivAvatar, c.ownerPhoto,
                      resolveAvatarVersion(ctx, c.ownerUid, c.avatarVersion), R.drawable.ic_person);
          }

          @Override
          public void onViewRecycled(@NonNull VH holder) {
              super.onViewRecycled(holder);
              StatusAvatarBinder.cancel(holder.itemView.getContext(), holder.ivAvatar);
          }

          static class VH extends RecyclerView.ViewHolder {
              ImageView ivBg, ring, ivAddBadge;
              CircleImageView ivAvatar;
              View flAvatar;
              TextView tvName;
              VH(View v) {
                  super(v);
                  ivBg       = v.findViewById(R.id.iv_card_bg);
                  ring       = v.findViewById(R.id.ring);
                  ivAvatar   = v.findViewById(R.id.iv_card_avatar);
                  ivAddBadge = v.findViewById(R.id.iv_card_add);
                  flAvatar   = v.findViewById(R.id.fl_avatar);
                  tvName     = v.findViewById(R.id.tv_card_name);
              }
          }
      }
  }