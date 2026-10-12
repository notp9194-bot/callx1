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
          /** Preview data of the latest status (text bg / video badge). Set via {@link #withPreview}. */
          String type, previewText, textColor, bgColor2;
          CardItem withPreview(StatusItem latest) {
              if (latest == null) return this;
              type = latest.type; bgColor2 = latest.bgColor2; textColor = latest.textColor;
              if ("text".equals(latest.type) && latest.text != null) {
                  String t = latest.text.trim();
                  previewText = t.length() > 90 ? t.substring(0, 90) + "\u2026" : t;
              }
              return this;
          }
          /** Segmented-ring data: total statuses + how many are unseen (0/0 = unknown, plain ring). */
          int segTotal, segUnseen;
          CardItem withCounts(int total, int unseenCount) { segTotal = total; segUnseen = unseenCount; return this; }
          boolean isCloseFriend;
          CardItem withCloseFriend(boolean cf) { isCloseFriend = cf; return this; }
          /** Minute/hour bucket of the latest status age (-1 = no timestamp) — part of sameContent so labels refresh. */
          int timeKey = -1;
          CardItem withTime(Long t) {
              if (t == null || t <= 0) { timeKey = -1; return this; }
              long mins = Math.max(0L, (System.currentTimeMillis() - t) / 60000L);
              timeKey = mins < 1 ? 0 : (mins < 60 ? (int) mins : 1000 + (int) Math.min(mins / 60L, 999L));
              return this;
          }
          /** Memo for StatusCardAdapter#bgUrlFor (URL building is string-heavy); keyed by resolved avatar version. */
          String bgUrlCache; long bgUrlVer;
          boolean isVideo() { return "video".equals(type) || "reel_clip".equals(type); }
          boolean isTextCard() { return "text".equals(type) && (thumbUrl == null || thumbUrl.isEmpty()); }
          boolean sameIdentity(CardItem o) {
              return o != null && isMine == o.isMine && isHiddenTile == o.isHiddenTile
                      && java.util.Objects.equals(ownerUid, o.ownerUid);
          }
          /** Everything except the seen/unseen state and the time bucket. */
          boolean sameBase(CardItem o) {
              return o != null && hasStatus == o.hasStatus && isMuted == o.isMuted
                      && avatarVersion == o.avatarVersion
                      && java.util.Objects.equals(ownerName, o.ownerName)
                      && java.util.Objects.equals(ownerPhoto, o.ownerPhoto)
                      && java.util.Objects.equals(thumbUrl, o.thumbUrl)
                      && java.util.Objects.equals(bgColor, o.bgColor)
                      && java.util.Objects.equals(ringColor, o.ringColor)
                      && java.util.Objects.equals(ringMode, o.ringMode)
                      && java.util.Objects.equals(type, o.type)
                      && java.util.Objects.equals(previewText, o.previewText)
                      && java.util.Objects.equals(textColor, o.textColor)
                      && java.util.Objects.equals(bgColor2, o.bgColor2)
                      && segTotal == o.segTotal && isCloseFriend == o.isCloseFriend;
          }
          boolean sameContent(CardItem o) {
              return sameBase(o) && unseen == o.unseen && segUnseen == o.segUnseen && timeKey == o.timeKey;
          }
          /** Only the seen/unseen state changed (ring + dim animate instead of snapping). */
          boolean onlySeenStateChanged(CardItem o) {
              return sameBase(o) && (unseen != o.unseen || segUnseen != o.segUnseen);
          }
          /** Only the "5m ago" bucket changed (the once-a-minute refresh) — no image/ring work needed. */
          boolean onlyTimeChanged(CardItem o) {
              return sameBase(o) && unseen == o.unseen && segUnseen == o.segUnseen && timeKey != o.timeKey;
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
          boolean loaded = true;        // statusLoaded at build time (skeleton vs real cards)
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
      /** false only between beginInitialLoad() and the first update() — carousel shows skeleton cards meanwhile. */
      private boolean statusLoaded = true;
      public void beginInitialLoad() { statusLoaded = false; rebuildFlatList(items); }
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
          statusLoaded = true;
          final int prevMyCount = myStatusCount;
          myStatusCount = myStatuses.size();
          lastUnseen = unseen; lastSeen = seen; lastMuted = muted; lastHiddenCount = hiddenCount;
          // Batch-warm the verified-badge cache for this page of statuses
          // before rows bind — avoids one Firebase read per row on scroll.
          List<String> verifyUids = new ArrayList<>();
          if (unseen != null) for (Entry e : unseen) if (e.ownerUid != null) verifyUids.add(e.ownerUid);
          if (seen   != null) for (Entry e : seen)   if (e.ownerUid != null) verifyUids.add(e.ownerUid);
          if (muted  != null) for (Entry e : muted)  if (e.ownerUid != null) verifyUids.add(e.ownerUid);
          com.callx.app.utils.VerifiedBadgeUtils.prefetch(verifyUids);
          List<FlatItem> next = buildFlatItems(unseen, seen, muted, hiddenCount);
          dispatchDiff(next, prevMyCount);
      }

      // v27: builds the "My status" tile + all contact statuses into one CardItem list
      // that's rendered as a single horizontal scrolling carousel (WhatsApp-style cards),
      // instead of the old vertical "My status" row + "Recent/Viewed updates" list rows.
      // v236: optionally appends a trailing "Hidden" tile at the very end.
      private static boolean nz(String x) { return x != null && !x.isEmpty(); }
      /** Thumb for a card: text statuses have none; a video's mediaUrl is NOT an image, so only its thumbnail counts. */
      public static String cardThumb(StatusItem s) {
          if (s == null || "text".equals(s.type)) return null;
          if ("video".equals(s.type) || "reel_clip".equals(s.type)) return nz(s.thumbnailUrl) ? s.thumbnailUrl : null;
          return nz(s.thumbnailUrl) ? s.thumbnailUrl : (nz(s.mediaUrl) ? s.mediaUrl : null);
      }
      /** Own avatar: item's photo, else the already-loaded FirebaseAuth photo (sync, no network call). */
      private static String myPhotoFallback(String photo) {
          if (nz(photo)) return photo;
          try { String p = com.callx.app.utils.FirebaseUtils.getCurrentPhotoUrl(); return nz(p) ? p : null; }
          catch (Exception e) { return null; }
      }
      private List<CardItem> buildCarouselItems(List<Entry> unseen, List<Entry> seen, int hiddenCount) {
          List<CardItem> cards = new ArrayList<>();
          if (myStatuses.isEmpty()) {
              cards.add(new CardItem(true, false, myUid, "My Status", myPhotoFallback(null), null, null, false, false));
          } else {
              StatusItem latest = myStatuses.get(myStatuses.size() - 1);
              String thumb = cardThumb(latest);
              cards.add(new CardItem(true, true, myUid, "My Status", myPhotoFallback(latest.ownerPhoto), thumb, latest.bgColor,
                      false, false, latest.ringColor, latest.ringMode).withPreview(latest)
                      .withCounts(myStatuses.size(), myStatuses.size())
                      .withTime(latest.timestamp));
          }
          for (Entry e : unseen) cards.add(entryToCard(e, true));
          for (Entry e : seen)   cards.add(entryToCard(e, false));
          if (hiddenCount > 0)   cards.add(CardItem.hiddenTile());
          return cards;
      }

      private CardItem entryToCard(Entry e, boolean unseen) {
          StatusItem latest = e.latestItem;
          String thumb = cardThumb(latest);
          String bg = latest != null ? latest.bgColor : null;
          String ringColor = latest != null ? latest.ringColor : null;
          String ringMode  = latest != null ? latest.ringMode  : null;
          return new CardItem(false, true, e.ownerUid, e.ownerName, e.ownerPhoto, thumb, bg,
                  unseen, e.isMuted, ringColor, ringMode, false, e.avatarVersion).withPreview(latest)
                  .withCounts(e.totalCount, e.unseenCount)
                  .withCloseFriend(e.isCloseFriend)
                  .withTime(e.latestTimestamp);
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
          FlatItem carouselItem = new FlatItem(ITEM_CAROUSEL, buildCarouselItems(unseen, seen, hiddenCount));
          carouselItem.loaded = statusLoaded;
          next.add(carouselItem);
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

      /** Perf: skip the carousel re-bind (+ nested diff + Glide prefetch) when nothing visible changed. */
      private static boolean carouselSame(FlatItem o, FlatItem n) {
          if (o.loaded != n.loaded) return false;
          List<CardItem> a = o.carouselItems, b = n.carouselItems;
          if (a == null || b == null || a.size() != b.size()) return false;
          for (int i = 0; i < a.size(); i++) {
              if (!a.get(i).sameIdentity(b.get(i)) || !a.get(i).sameContent(b.get(i))) return false;
          }
          return true;
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
                  if (o.kind == ITEM_CAROUSEL) return carouselSame(o, n); // re-bind only if cards/skeleton state changed
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
          if (h.cardAdapter == null) {
              // One-time setup: persistent adapter (DiffUtil updates keep scroll position + avoid flicker).
              LinearLayoutManager lm = new LinearLayoutManager(
                      h.itemView.getContext(), LinearLayoutManager.HORIZONTAL, false);
              lm.setInitialPrefetchItemCount(4);
              h.rvCarousel.setLayoutManager(lm);
              h.rvCarousel.setHasFixedSize(true);
              h.rvCarousel.setItemViewCacheSize(6);
              if (h.rvCarousel.getItemAnimator() instanceof androidx.recyclerview.widget.SimpleItemAnimator)
                  ((androidx.recyclerview.widget.SimpleItemAnimator) h.rvCarousel.getItemAnimator())
                          .setSupportsChangeAnimations(false);
              h.cardAdapter = new StatusCardAdapter(card -> {
                  if (card.isHiddenTile) {
                      if (onHiddenCardClick != null) onHiddenCardClick.run();
                  } else if (card.isMine) {
                      if (card.hasStatus) { if (onMyStatusClick != null) onMyStatusClick.run(); }
                      else                { if (onAddStatusClick != null) onAddStatusClick.run(); }
                  } else if (!card.isMuted && onContactClick != null) {
                      onContactClick.onClick(card.ownerUid, card.ownerName);
                  } else if (card.isMuted) {
                      android.widget.Toast.makeText(h.itemView.getContext(),
                              h.itemView.getContext().getString(R.string.status_card_muted_toast,
                                      card.ownerName != null ? card.ownerName : ""),
                              android.widget.Toast.LENGTH_SHORT).show();
                  }
              }, card -> {
                  if (!card.isMine && !card.isHiddenTile && onLongPress != null)
                      onLongPress.onLongPress(card.ownerUid, card.ownerName, card.isMuted);
              });
              h.rvCarousel.getRecycledViewPool().setMaxRecycledViews(0, 10);
              h.rvCarousel.setAdapter(h.cardAdapter);
              // v554: inflate the first cards off the main thread (skeleton phase) so the first scroll doesn't inflate 5-7 cards in a row.
              h.cardAdapter.preInflate(h.rvCarousel, cards.isEmpty() ? 5 : Math.min(cards.size(), 7));
              h.rvCarousel.addItemDecoration(new CardGapDecoration(
                      Math.round(8f * h.itemView.getResources().getDisplayMetrics().density)));
              h.rvCarousel.addOnScrollListener(h.cardAdapter.newPreloader());
              h.rvCarousel.addOnScrollListener(new RecyclerView.OnScrollListener() {
                  @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) { h.updateFades(); }
              });
              h.rvCarousel.addOnLayoutChangeListener((rv, l, t, r, b, ol, ot, oR, ob) -> h.updateFades());
              try { new StatusCardStartSnapHelper().attachToRecyclerView(h.rvCarousel); }
              catch (IllegalStateException ignored) { /* a fling listener is already attached */ }
          }
          h.cardAdapter.submit(cards, h.itemView.getContext()); // diff off main thread; prefetch runs after apply
          if (!statusLoaded) {
              // First data not here yet: skeleton cards, with a 4s safety net (offline / empty).
              h.showSkeleton();
              h.itemView.removeCallbacks(h.skeletonTimeout);
              h.skeletonTimeout = () -> { statusLoaded = true; h.hideSkeleton(); };
              h.itemView.postDelayed(h.skeletonTimeout, 4000L);
          } else {
              if (h.skeletonTimeout != null) { h.itemView.removeCallbacks(h.skeletonTimeout); h.skeletonTimeout = null; }
              h.hideSkeleton();
          }
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
              StatusAvatarBinder.bind(ctx, h.ivAvatar, myPhotoFallback(latest.ownerPhoto),
                      resolveAvatarVersion(ctx, myUid, 0L), R.drawable.ic_person);
              if (h.ivThumb != null) {
                  String thumbUrl = cardThumb(latest);
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
          com.callx.app.utils.VerifiedBadgeUtils.bindForUid(h.ivVerified, e.ownerUid);
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
          StatusCardAdapter cardAdapter;
          View llSkeleton;
          android.animation.ObjectAnimator pulse;
          Runnable skeletonTimeout;
          View fadeL, fadeR;
          /** Shows each edge fade only while the list can actually scroll that way (and is visible). */
          void updateFades() {
              if (fadeL == null || fadeR == null) return;
              final boolean vis = rvCarousel.getVisibility() == View.VISIBLE;
              setFade(fadeL, vis && rvCarousel.canScrollHorizontally(-1));
              setFade(fadeR, vis && rvCarousel.canScrollHorizontally(1));
          }
          private static void setFade(View v, boolean show) {
              final int t = show ? View.VISIBLE : View.INVISIBLE; // INVISIBLE: no relayout
              if (v.getVisibility() != t) v.setVisibility(t);
          }
          void showSkeleton() {
              if (llSkeleton == null) return;
              rvCarousel.setVisibility(View.INVISIBLE);
              updateFades();
              llSkeleton.setAlpha(1f);
              llSkeleton.setVisibility(View.VISIBLE);
              startPulse();
          }
          void startPulse() {
              if (llSkeleton == null) return;
              if (pulse == null) {
                  pulse = android.animation.ObjectAnimator.ofFloat(llSkeleton, View.ALPHA, 1f, 0.45f);
                  pulse.setDuration(750);
                  pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                  pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
              }
              if (!pulse.isStarted()) pulse.start();
          }
          void hideSkeleton() {
              if (llSkeleton == null) return;
              final boolean wasShowing = llSkeleton.getVisibility() == View.VISIBLE;
              if (pulse != null) pulse.cancel();
              llSkeleton.setVisibility(View.GONE);
              rvCarousel.setVisibility(View.VISIBLE);
              updateFades();
              if (wasShowing) { rvCarousel.setAlpha(0f); rvCarousel.animate().alpha(1f).setDuration(180).start(); }
          }
          CarouselVH(View v) {
              super(v);
              rvCarousel = v.findViewById(R.id.rv_status_carousel);
              llSkeleton = v.findViewById(R.id.ll_status_skeleton);
              TextView title = v.findViewById(R.id.tv_carousel_title);
              if (title != null) {
                  androidx.core.view.ViewCompat.setAccessibilityHeading(title, true); // TalkBack heading navigation
                  // 21sp title: stop it growing past the 1.3x font-scale size (huge fonts push the cards off-screen).
                  if (v.getResources().getConfiguration().fontScale > 1.3f)
                      title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 21f * 1.3f);
              }
              // Skeleton safety-net timeout must not outlive the view: cancel on detach, re-arm on attach
              // if the skeleton is still showing (offline / never-arriving data would otherwise stick forever).
              v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                  @Override public void onViewAttachedToWindow(View x) {
                      if (skeletonTimeout != null && llSkeleton != null && llSkeleton.getVisibility() == View.VISIBLE) {
                          x.removeCallbacks(skeletonTimeout);
                          x.postDelayed(skeletonTimeout, 4000L);
                      }
                  }
                  @Override public void onViewDetachedFromWindow(View x) {
                      if (skeletonTimeout != null) x.removeCallbacks(skeletonTimeout);
                  }
              });
              fadeL = v.findViewById(R.id.fade_left);
              fadeR = v.findViewById(R.id.fade_right);
              if (fadeL != null && fadeR != null) {
                  final int surface = com.google.android.material.color.MaterialColors.getColor(
                          v, com.google.android.material.R.attr.colorSurface);
                  final int clear = surface & 0x00FFFFFF; // same RGB, alpha 0 → no grey banding
                  fadeL.setBackground(new android.graphics.drawable.GradientDrawable(
                          android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, new int[]{ surface, clear }));
                  fadeR.setBackground(new android.graphics.drawable.GradientDrawable(
                          android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, new int[]{ clear, surface }));
              }
              if (llSkeleton != null) {
                  llSkeleton.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                      @Override public void onViewAttachedToWindow(View x) { if (llSkeleton.getVisibility() == View.VISIBLE) startPulse(); }
                      @Override public void onViewDetachedFromWindow(View x) { if (pulse != null) pulse.cancel(); }
                  });
              }
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
          android.widget.ImageView ivVerified;
          ContactVH(View v) {
              super(v);
              ivAvatar   = v.findViewById(R.id.iv_avatar);
              ring       = v.findViewById(R.id.ring);
              tvName     = v.findViewById(R.id.tv_name);
              ivVerified = v.findViewById(R.id.iv_verified);
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
      /** 8dp gap between carousel cards (Material 4dp grid). Gap sits on each card's trailing edge, so the
       *  snap helper (which aligns a card's leading edge to the start padding) stays exactly flush. */
      static final class CardGapDecoration extends RecyclerView.ItemDecoration {
          private final int gapPx;
          CardGapDecoration(int gapPx) { this.gapPx = gapPx; }
          @Override public void getItemOffsets(@NonNull android.graphics.Rect outRect, @NonNull View view,
                                               @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
              final int pos = parent.getChildAdapterPosition(view);
              if (pos == RecyclerView.NO_POSITION || pos >= state.getItemCount() - 1) return; // no gap after last card
              if (parent.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) outRect.left = gapPx;
              else outRect.right = gapPx;
          }
      }

      static class StatusCardAdapter extends RecyclerView.Adapter<StatusCardAdapter.VH> {
          /** Theme-aware (values / values-night) colour lookup for the card tokens. */
          private static int col(Context ctx, int res) { return androidx.core.content.ContextCompat.getColor(ctx, res); }
          // Card body = 116dp x 160dp (182dp row minus the 22dp avatar overlap) — decode at that size, not 480x853.
          /** Card body size (below the avatar overlap) in px, from @dimen so tablets get bigger cards. */
          private static int bodyW(Context c) { return Math.round(c.getResources().getDimension(R.dimen.status_card_width)); }
          private static int bodyH(Context c) {
              return Math.round(c.getResources().getDimension(R.dimen.status_card_height)
                      - c.getResources().getDimension(R.dimen.status_card_avatar_overlap));
          }

          // Card body size is constant per configuration — resolve @dimen once, not on every bind/scroll event.
          private int dimKey = -1, cachedBw, cachedBh;
          private void ensureDims(Context c) {
              final android.content.res.Configuration cfg = c.getResources().getConfiguration();
              final int key = cfg.densityDpi * 10000 + cfg.smallestScreenWidthDp;
              if (key != dimKey) { cachedBw = bodyW(c); cachedBh = bodyH(c); dimKey = key; }
          }

          private List<CardItem> cards = new ArrayList<>();
          private final java.util.function.Consumer<CardItem> onClick;
          private final java.util.function.Consumer<CardItem> onLongClick;

          StatusCardAdapter(java.util.function.Consumer<CardItem> onClick,
                             java.util.function.Consumer<CardItem> onLongClick) {
              this.onClick = onClick;
              this.onLongClick = onLongClick;
              setHasStableIds(true);
          }

          // ── v554: async pre-inflated card views ─────────────────────────
          private final java.util.ArrayDeque<View> preInflated = new java.util.ArrayDeque<>();
          void preInflate(RecyclerView rv, int count) {
              try {
                  final androidx.asynclayoutinflater.view.AsyncLayoutInflater ali =
                          new androidx.asynclayoutinflater.view.AsyncLayoutInflater(rv.getContext());
                  for (int i = 0; i < count; i++)
                      ali.inflate(R.layout.item_status_card, rv, (view, resid, parent) -> preInflated.add(view));
              } catch (Throwable ignored) { /* falls back to sync inflate in obtainCardView */ }
          }
          private View obtainCardView(ViewGroup parent) {
              View v = preInflated.poll();
              if (v != null && v.getParent() == null) return v;
              return LayoutInflater.from(parent.getContext()).inflate(R.layout.item_status_card, parent, false);
          }

          // ── Stable ids (less rebind/animation churn) ─────────────────────
          private final java.util.HashMap<String, Long> stableIds = new java.util.HashMap<>();
          private long nextStableId = 1;
          @Override public long getItemId(int position) {
              CardItem c = cards.get(position);
              String k = (c.isHiddenTile ? "h:" : c.isMine ? "m:" : "u:") + c.ownerUid;
              Long id = stableIds.get(k);
              if (id == null) { id = nextStableId++; stableIds.put(k, id); }
              return id;
          }

          // ── Async diff ───────────────────────────────────────────────────
          private static final java.util.concurrent.ExecutorService DIFF_EXEC =
                  java.util.concurrent.Executors.newSingleThreadExecutor();
          private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
          private int submitGen = 0;

          // v555: cross-fade only for a real network arrival. Memory/disk hits swap instantly (no extra draw pass while scrolling).
          private static final com.bumptech.glide.request.transition.TransitionFactory<android.graphics.drawable.Drawable> BG_TRANSITION =
                  new com.bumptech.glide.request.transition.TransitionFactory<android.graphics.drawable.Drawable>() {
                      private final com.bumptech.glide.request.transition.TransitionFactory<android.graphics.drawable.Drawable> fade =
                              new com.bumptech.glide.request.transition.DrawableCrossFadeFactory.Builder(150).build();
                      @Override public com.bumptech.glide.request.transition.Transition<android.graphics.drawable.Drawable> build(
                              com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                          return dataSource == com.bumptech.glide.load.DataSource.REMOTE
                                  ? fade.build(dataSource, isFirstResource)
                                  : com.bumptech.glide.request.transition.NoTransition.<android.graphics.drawable.Drawable>get();
                      }
                  };

          /** Glide request shared by bind + preload so both hit the same cache key. */
          private static com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> cardReq(Context ctx, String url, int w, int h) {
              return Glide.with(ctx).load(url).centerCrop().override(w, h)
                      .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565);
          }

          /** Scroll-direction aware preloader: warms the next few cards just outside the viewport. */
          RecyclerView.OnScrollListener newPreloader() {
              return new RecyclerView.OnScrollListener() {
                  int lastFirst = -1, lastLast = -1;
                  @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                      if (dx == 0) return;
                      // v555: fast fling -> skip (user blows past these cards); IDLE below catches up.
                      final float fastPx = 28f * rv.getResources().getDisplayMetrics().density;
                      if (Math.abs(dx) > fastPx) return;
                      preloadWindow(rv);
                  }
                  @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                      if (newState == RecyclerView.SCROLL_STATE_IDLE) preloadWindow(rv);
                  }
                  private void preloadWindow(RecyclerView rv) {
                      RecyclerView.LayoutManager lm = rv.getLayoutManager();
                      if (!(lm instanceof LinearLayoutManager)) return;
                      int first = ((LinearLayoutManager) lm).findFirstVisibleItemPosition();
                      int last  = ((LinearLayoutManager) lm).findLastVisibleItemPosition();
                      if (first < 0 || (first == lastFirst && last == lastLast)) return;
                      lastFirst = first; lastLast = last;
                      Context ctx = rv.getContext();
                      ensureDims(ctx);
                      final int w = cachedBw, h = cachedBh;
                      for (int k = 1; k <= 3; k++) {
                          preloadAt(ctx, last + k, w, h);
                          preloadAt(ctx, first - k, w, h);
                      }
                  }
              };
          }
          private void preloadAt(Context ctx, int i, int w, int h) {
              if (i < 0 || i >= cards.size()) return;
              CardItem c = cards.get(i);
              if (c.isHiddenTile || c.isTextCard()) return;
              String u = bgUrlFor(ctx, c);
              if (u != null) { try { cardReq(ctx, u, w, h).preload(w, h); } catch (Exception ignored) {} }
          }

          private static final Object PAYLOAD_SEEN_STATE = new Object();
          private static final Object PAYLOAD_TIME = new Object();

          /** Seen/unseen flip: normal bind, then the ring and card dim fade to their new state. */
          @Override
          public void onBindViewHolder(@NonNull VH h, int pos, @NonNull List<Object> payloads) {
              if (payloads.contains(PAYLOAD_TIME) && !payloads.contains(PAYLOAD_SEEN_STATE)
                      && pos < cards.size() && !cards.get(pos).isHiddenTile) {
                  bindTimeOnly(h, pos); // minute tick: touch only the label + content description
                  return;
              }
              if (!payloads.contains(PAYLOAD_SEEN_STATE)) { super.onBindViewHolder(h, pos, payloads); return; }
              onBindViewHolder(h, pos);
              if (!h.itemView.isAttachedToWindow()) return; // offscreen/cached: no point animating
              final CardItem c = cards.get(pos);
              final float ringTo = h.ring.getAlpha();
              h.ring.animate().cancel();
              h.ring.setAlpha(0.15f);
              h.ring.animate().alpha(ringTo).setDuration(240).start();
              if (!c.isMuted && !c.isHiddenTile) {
                  final float bgTo = h.ivBg.getAlpha();
                  h.ivBg.animate().cancel();
                  h.ivBg.setAlpha(bgTo < 1f ? 1f : 0.72f);
                  h.ivBg.animate().alpha(bgTo).setDuration(240).start();
              }
          }

          /** Partial bind for {@link #PAYLOAD_TIME}: image, ring and avatar stay exactly as they are. */
          private void bindTimeOnly(@NonNull VH h, int pos) {
              final CardItem c = cards.get(pos);
              h.item = c;
              final Context ctx = h.itemView.getContext();
              final String timeTxt = timeLabel(ctx, c.timeKey);
              final float fontScale = ctx.getResources().getConfiguration().fontScale;
              h.tvTime.setText(timeTxt);
              h.tvTime.setVisibility(timeTxt != null && fontScale < 1.8f ? View.VISIBLE : View.GONE);
              h.itemView.setContentDescription(describe(ctx, c));
          }

          /** DiffUtil update (computed on a background thread) — only changed cards re-bind, scroll position is kept. */
          void submit(List<CardItem> next, Context ctx) {
              final int gen = ++submitGen;
              final List<CardItem> old = cards;
              if (old.isEmpty()) { // nothing to diff: plain insert
                  cards = next;
                  if (!next.isEmpty()) notifyItemRangeInserted(0, next.size());
                  prefetchAhead(ctx);
                  return;
              }
              DIFF_EXEC.execute(() -> {
                  final DiffUtil.DiffResult r = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                      @Override public int getOldListSize() { return old.size(); }
                      @Override public int getNewListSize() { return next.size(); }
                      @Override public boolean areItemsTheSame(int o, int n) { return old.get(o).sameIdentity(next.get(n)); }
                      @Override public boolean areContentsTheSame(int o, int n) { return old.get(o).sameContent(next.get(n)); }
                      @Override public Object getChangePayload(int o, int n) {
                          final CardItem a = old.get(o), b = next.get(n);
                          if (a.onlySeenStateChanged(b)) return PAYLOAD_SEEN_STATE;
                          return a.onlyTimeChanged(b) ? PAYLOAD_TIME : null;
                      }
                  });
                  MAIN.post(() -> {
                      if (gen != submitGen || cards != old) return; // superseded by a newer submit
                      cards = next;
                      r.dispatchUpdatesTo(this);
                      prefetchAhead(ctx);
                  });
              });
          }

          static String bgUrlFor(Context ctx, CardItem c) {
              if (c.thumbUrl != null && !c.thumbUrl.isEmpty()) return c.thumbUrl; // media thumbnail, as-is
              if (c.ownerPhoto == null || c.ownerPhoto.isEmpty()) return null;
              // Memoized per card + avatar version: bind, prefetch and the scroll preloader all ask for the same URL.
              final long ver = resolveAvatarVersion(ctx, c.ownerUid, c.avatarVersion);
              if (c.bgUrlCache != null && c.bgUrlVer == ver) return c.bgUrlCache;
              String u = null;
              try {
                  u = com.callx.app.utils.AvatarUrlBuilder.buildResponsive(ctx, c.ownerPhoto,
                          com.callx.app.utils.AvatarSizeTier.XLARGE, ver);
              } catch (Exception ignored) {}
              final String out = (u == null || u.isEmpty()) ? c.ownerPhoto : u;
              c.bgUrlCache = out; c.bgUrlVer = ver;
              return out;
          }

          /** Warms the card backgrounds just past the first screen so they're already cached on horizontal scroll. */
          void prefetchAhead(Context ctx) {
              ensureDims(ctx);
              final int w = cachedBw, h = cachedBh;
              for (int i = 3; i < Math.min(cards.size(), 7); i++) {
                  CardItem c = cards.get(i);
                  if (c.isHiddenTile || c.isTextCard()) continue;
                  String u = bgUrlFor(ctx, c);
                  if (u != null) { try { cardReq(ctx, u, w, h).preload(w, h); } catch (Exception ignored) {} }
              }
          }

          private static int parseColor(String hex, int def) {
              if (hex == null || hex.isEmpty()) return def;
              try { return android.graphics.Color.parseColor(hex); } catch (IllegalArgumentException e) { return def; }
          }

          /** "5m ago" / "2h ago" from CardItem.timeKey (null = no timestamp). */
          private static String timeLabel(Context ctx, int key) {
              if (key < 0) return null;
              if (key == 0) return ctx.getString(R.string.status_card_time_just_now);
              if (key < 1000) return ctx.getString(R.string.status_card_time_minutes_ago, key);
              return ctx.getString(R.string.status_card_time_hours_ago, key - 1000);
          }

          private static String describe(Context ctx, CardItem c) {
              if (c.isHiddenTile) return ctx.getString(R.string.status_card_desc_hidden);
              String name = c.ownerName != null ? c.ownerName : "";
              if (c.isMine) return ctx.getString(c.hasStatus ? R.string.status_card_desc_mine_view : R.string.status_card_desc_mine_add);
              String kind = ctx.getString(c.isVideo() ? R.string.status_card_kind_video
                      : (c.isTextCard() ? R.string.status_card_kind_text : R.string.status_card_kind_plain));
              String d = c.isMuted ? ctx.getString(R.string.status_card_desc_muted, name, kind)
                      : ctx.getString(c.unseen ? R.string.status_card_desc_new : R.string.status_card_desc_viewed, name, kind);
              if (c.isCloseFriend) d += ctx.getString(R.string.status_card_desc_close_friend);
              String t = timeLabel(ctx, c.timeKey);
              return t != null ? d + ", " + t : d;
          }

          /** Press feedback: card dips to 96% while held (touch is not consumed, click/long-press still work). */
          @android.annotation.SuppressLint("ClickableViewAccessibility")
          private static void attachPressFeedback(View v) {
              v.setOnTouchListener((view, ev) -> {
                  switch (ev.getActionMasked()) {
                      case MotionEvent.ACTION_DOWN:
                          view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start(); break;
                      case MotionEvent.ACTION_UP:
                      case MotionEvent.ACTION_CANCEL:
                          view.animate().scaleX(1f).scaleY(1f).setDuration(120).start(); break;
                      default: break;
                  }
                  return false;
              });
          }

          @NonNull @Override
          public VH onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
              final Context ctx = parent.getContext();
              final VH h = new VH(obtainCardView(parent));
              // Carousel spacing comes from CardGapDecoration (8dp) + RV padding (16dp), not the layout's 8/6dp
              // side margins (that XML is shared with StoryReshareActivity, so override only here).
              final ViewGroup.LayoutParams lp = h.itemView.getLayoutParams();
              if (lp instanceof ViewGroup.MarginLayoutParams) {
                  final ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                  mlp.leftMargin = 0; mlp.rightMargin = 0; mlp.setMarginStart(0); mlp.setMarginEnd(0);
              }
              // Perf: everything that does not depend on the bound card is set ONCE here, not on every bind.
              h.bgPh = new android.graphics.drawable.ColorDrawable(col(ctx, R.color.status_card_placeholder));
              // Load failed (offline / 404 / expired URL): faint person glyph instead of a blank grey card.
              h.bgListener = new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                  @Override public boolean onLoadFailed(com.bumptech.glide.load.engine.GlideException e, Object model,
                          com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target, boolean isFirstResource) {
                      h.bgKey = null;
                      h.ivBg.setScaleType(ImageView.ScaleType.CENTER);
                      h.ivBg.setColorFilter(col(ctx, R.color.status_card_glyph));
                      h.ivBg.setImageResource(R.drawable.ic_person);
                      return true; // handled — Glide won't overwrite it
                  }
                  @Override public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model,
                          com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                          com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                      if (h.bgPendingKey != null) h.bgKey = h.bgPendingKey; // this holder now really shows that image
                      return false;
                  }
              };
              attachPressFeedback(h.itemView);
              h.itemView.setOnClickListener(v -> { if (h.item != null && onClick != null) onClick.accept(h.item); });
              h.itemView.setOnLongClickListener(v -> {
                  final CardItem c = h.item;
                  if (c == null || c.isHiddenTile) return false;
                  v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                  if (onLongClick != null) onLongClick.accept(c);
                  return true;
              });
              // "+ / avatar" always adds a new status for MY card (clickable toggled per bind, see below).
              final View.OnClickListener addClick = v -> {
                  final CardItem c = h.item;
                  if (c != null && c.isMine && onClick != null) {
                      onClick.accept(new CardItem(true, false, c.ownerUid, c.ownerName,
                              c.ownerPhoto, c.thumbUrl, c.bgColor, c.unseen, c.isMuted));
                  }
              };
              h.ivAddBadge.setOnClickListener(addClick);
              if (h.flAvatar != null) h.flAvatar.setOnClickListener(addClick);
              // TalkBack: expose long-press (mute/unmute menu) as a named action — one shared delegate.
              androidx.core.view.ViewCompat.setAccessibilityDelegate(h.itemView,
                      new androidx.core.view.AccessibilityDelegateCompat() {
                          @Override public void onInitializeAccessibilityNodeInfo(@NonNull View host,
                                  @NonNull androidx.core.view.accessibility.AccessibilityNodeInfoCompat info) {
                              super.onInitializeAccessibilityNodeInfo(host, info);
                              final CardItem c = h.item;
                              if (c == null || c.isMine || c.isHiddenTile) return;
                              info.addAction(new androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                                      androidx.core.view.accessibility.AccessibilityNodeInfoCompat.ACTION_LONG_CLICK,
                                      host.getContext().getString(c.isMuted ? R.string.status_card_action_unmute : R.string.status_card_action_mute)));
                          }
                      });
              return h;
          }

          @Override public void onBindViewHolder(@NonNull VH h, int pos) {
              CardItem c = cards.get(pos);
              h.item = c;
              Context ctx = h.itemView.getContext();

              // v236: trailing "Hidden" tile — eye-off icon, no ring/badge/thumb, own click path
              if (c.isHiddenTile) {
                  h.tvName.setText(R.string.status_card_hidden);
                  h.tvTime.setVisibility(View.GONE);
                  h.ivCf.setVisibility(View.GONE);
                  h.bgKey = null; h.bgPendingKey = null;
                  h.ivBg.setImageResource(com.callx.app.core.R.drawable.ic_eye_off);
                  h.ivBg.setScaleType(ImageView.ScaleType.CENTER);
                  h.ivBg.setBackgroundColor(col(ctx, R.color.status_card_hidden_bg));
                  h.ivBg.setColorFilter(col(ctx, R.color.status_card_glyph));
                  h.ivMuted.setVisibility(View.GONE);
                  h.ring.setVisibility(View.GONE);
                  h.ringKey = -1;
                  h.ivAvatar.setVisibility(View.GONE);
                  h.ivAddBadge.setVisibility(View.GONE);
                  if (h.flAvatar != null) h.flAvatar.setVisibility(View.GONE);
                  h.itemView.setContentDescription(describe(ctx, c));
                  h.tvPreview.setVisibility(View.GONE);
                  h.ivType.setVisibility(View.GONE);
                  h.ivBg.setAlpha(1f);
                  return;
              }
              // Reset flags a recycled hidden-tile VH may have left behind
              h.ivBg.setScaleType(ImageView.ScaleType.CENTER_CROP);
              h.ivBg.setColorFilter(null);
              h.ivBg.setBackgroundColor(col(ctx, R.color.status_card_placeholder));
              h.ring.setVisibility(View.VISIBLE);
              h.ivAvatar.setVisibility(View.VISIBLE);
              if (h.flAvatar != null) h.flAvatar.setVisibility(View.VISIBLE);

              h.tvName.setText(c.isMine ? ctx.getString(R.string.status_card_my_status) : (c.ownerName != null ? c.ownerName : ""));
              final String timeTxt = timeLabel(ctx, c.timeKey);
              h.tvTime.setText(timeTxt);
              // Large system font: keep the name to 1 line and drop the time so the card never clips.
              final float fontScale = ctx.getResources().getConfiguration().fontScale;
              h.tvName.setMaxLines(fontScale >= 1.3f ? 1 : 2);
              h.tvPreview.setMaxLines(fontScale >= 1.3f ? 3 : 5);
              h.tvTime.setVisibility(timeTxt != null && fontScale < 1.8f ? View.VISIBLE : View.GONE);
              h.ivCf.setVisibility(c.isCloseFriend && !c.isMine ? View.VISIBLE : View.GONE);

              // Card background image: latest status media, or the owner's profile photo,
              // or a plain color fallback (already set as the ImageView's default background).
              final float dens = ctx.getResources().getDisplayMetrics().density;
              ensureDims(ctx);
              final int bw = cachedBw, bh = cachedBh;
              h.tvPreview.setVisibility(View.GONE);
              h.ivType.setVisibility(c.isVideo() ? View.VISIBLE : View.GONE);
              h.ivMuted.setVisibility(c.isMuted ? View.VISIBLE : View.GONE);
              final String bgUrl = c.isTextCard() ? null : bgUrlFor(ctx, c);
              if (c.isTextCard()) {
                  // Text status: its own colour/gradient + a short preview, like the status itself.
                  h.bgKey = null; h.bgPendingKey = null;
                  Glide.with(ctx).clear(h.ivBg);
                  int c1 = parseColor(c.bgColor, col(ctx, R.color.status_card_text_default));
                  int c2 = parseColor(c.bgColor2, c1);
                  android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable(
                          android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{ c1, c2 });
                  h.ivBg.setScaleType(ImageView.ScaleType.FIT_XY);
                  h.ivBg.setImageDrawable(gd);
                  if (c.previewText != null && !c.previewText.isEmpty()) {
                      h.tvPreview.setText(c.previewText);
                      h.tvPreview.setTextColor(parseColor(c.textColor, android.graphics.Color.WHITE));
                      h.tvPreview.setVisibility(View.VISIBLE);
                  }
              } else if (bgUrl != null) {
                  // v555: same image already showing in this holder (DiffUtil rebind for seen/mute/time) -> no Glide request at all.
                  final String bgKey = bgUrl + "@" + bw + "x" + bh;
                  if (!bgKey.equals(h.bgKey)) {
                  h.bgKey = null;
                  h.bgPendingKey = bgKey;
                  cardReq(ctx, bgUrl, bw, bh)
                          .placeholder(h.bgPh)
                          .listener(h.bgListener)
                          .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.with(BG_TRANSITION))
                          .into(h.ivBg);
                  }
              } else {
                  // No media and no photo: faint centered person glyph on the dark card.
                  h.bgKey = null; h.bgPendingKey = null;
                  Glide.with(ctx).clear(h.ivBg);
                  h.ivBg.setScaleType(ImageView.ScaleType.CENTER);
                  h.ivBg.setColorFilter(col(ctx, R.color.status_card_glyph));
                  h.ivBg.setImageResource(R.drawable.ic_person);
              }
              // Seen / muted cards recede; unseen name is bold.
              h.ivBg.setAlpha(c.isMuted ? 0.55f : (!c.isMine && !c.unseen) ? 0.72f : 1f);
              h.tvName.setTypeface(null, (c.unseen || c.isMine) ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);

              // FIX (deep avatar pipeline + isVisible gate) — same
              // StatusAvatarBinder pipeline as the vertical contact rows;
              // this carousel is its own nested RecyclerView so its rows get
              // the identical offscreen-bind gate + attach-time promotion
              // (see onViewAttachedToWindow/onViewRecycled below).
              // v554: skip the rebind when this holder already has a real (on-screen) bind of the same URL
              // (DiffUtil rebinds for unseen/time changes would otherwise redo L2 lookup + Glide request).
              final String avUrl = StatusAvatarBinder.url(ctx, c.ownerPhoto,
                      resolveAvatarVersion(ctx, c.ownerUid, c.avatarVersion));
              if (avUrl == null || !avUrl.equals(h.avatarKey) || !avUrl.equals(h.ivAvatar.getTag())) {
                  StatusAvatarBinder.bindGated(ctx, h.ivAvatar, c.ownerPhoto,
                          resolveAvatarVersion(ctx, c.ownerUid, c.avatarVersion), R.drawable.ic_person);
                  h.avatarKey = (avUrl != null && h.ivAvatar.isAttachedToWindow()) ? avUrl : null;
              }

              if (c.isMine && !c.hasStatus) {
                  // "Add status" tile — grey ring, plus badge, no status yet
                  h.ring.setVisibility(View.GONE);
                  h.ringKey = -1;
                  h.ivAddBadge.setVisibility(View.VISIBLE);
              } else {
                  // Single ring only — the SAME 3-state story ring StoryRingApplier gives the reel
                  // search-history / followers rows: gradient = unseen, flat gray = seen.
                  // Perf: ring drawable is rebuilt only when its shape/state key changes (ringKey).
                  final boolean showUnseen = !c.isMuted && (c.unseen || c.isMine);
                  h.ring.setVisibility(View.VISIBLE);
                  final int segN = c.segTotal;
                  final int segCap = com.callx.app.utils.StatusSegmentedRingDrawable.MAX_SEGMENTS;
                  final int key;
                  if (c.isCloseFriend && showUnseen && !c.isMine) {
                      // Close friends: green ring (flat colour), still one arc per status.
                      final int n = Math.max(1, Math.min(segN, segCap));
                      final int us = Math.max(1, Math.min(c.segUnseen, n));
                      key = 1_000_000 + n * 1000 + us;
                      if (key != h.ringKey) {
                          h.ring.setBackground(null);
                          h.ring.setImageDrawable(new com.callx.app.utils.StatusSegmentedRingDrawable(
                                  n, us, 1.5f * dens, 2.5f * dens,
                                  col(ctx, com.callx.app.core.R.color.text_muted), col(ctx, R.color.status_card_cf_ring)));
                      }
                  } else if (segN > 1 && segN <= segCap) {
                      // One arc per status; unseen arcs gradient, seen arcs flat grey (WhatsApp-style).
                      final int unseenSegs = showUnseen ? Math.max(1, Math.min(c.segUnseen, segN)) : 0;
                      key = 2_000_000 + segN * 1000 + unseenSegs;
                      if (key != h.ringKey) {
                          h.ring.setBackground(null);
                          h.ring.setImageDrawable(new com.callx.app.utils.StatusSegmentedRingDrawable(
                                  segN, unseenSegs, 1.5f * dens, 2.5f * dens,
                                  col(ctx, com.callx.app.core.R.color.text_muted)));
                      }
                  } else if (showUnseen) {
                      key = 3;
                      if (key != h.ringKey) {
                          h.ring.setImageDrawable(null);
                          h.ring.setBackground(com.callx.app.utils.StoryRingGradientDrawable.withStrokeDp(1.5f, dens));
                      }
                  } else {
                      key = 4;
                      if (key != h.ringKey) {
                          h.ring.setBackground(null);
                          h.ring.setImageResource(com.callx.app.core.R.drawable.circle_status_seen_thin);
                      }
                  }
                  h.ringKey = key;
                  h.ring.setAlpha(c.isMuted ? 0.4f : 1f);
                  h.ivAddBadge.setVisibility(c.isMine ? View.VISIBLE : View.GONE);
              }

              // + badge / avatar tap-target only exists for MY card; for others the tap must fall
              // through to the whole card (listeners are set once in onCreateViewHolder).
              h.ivAddBadge.setClickable(c.isMine);
              if (h.flAvatar != null) {
                  h.flAvatar.setClickable(c.isMine);
                  // Only "mine" has a separate avatar tap target (always adds a status).
                  h.flAvatar.setContentDescription(c.isMine ? ctx.getString(R.string.status_card_add_update) : null);
                  h.flAvatar.setImportantForAccessibility(c.isMine
                          ? View.IMPORTANT_FOR_ACCESSIBILITY_YES : View.IMPORTANT_FOR_ACCESSIBILITY_NO);
              }
              h.itemView.setContentDescription(describe(ctx, c));
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
              holder.itemView.animate().cancel();
              holder.itemView.setScaleX(1f); holder.itemView.setScaleY(1f);
              holder.ring.animate().cancel();
              holder.ivBg.animate().cancel();
              StatusAvatarBinder.cancel(holder.itemView.getContext(), holder.ivAvatar);
              holder.avatarKey = null;
              holder.bgKey = null; holder.bgPendingKey = null;
              // Cancel the card-background load too — a recycled card must not flash the previous person's image.
              try { Glide.with(holder.itemView.getContext()).clear(holder.ivBg); } catch (Exception ignored) {}
          }

          static class VH extends RecyclerView.ViewHolder {
              ImageView ivBg, ring, ivAddBadge, ivType, ivMuted, ivCf;
              TextView tvTime;
              TextView tvPreview;
              CircleImageView ivAvatar;
              View flAvatar;
              TextView tvName;
              CardItem item;                                   // currently bound card (read by listeners set once in onCreateViewHolder)
              android.graphics.drawable.ColorDrawable bgPh;    // reused Glide placeholder
              com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> bgListener; // image-load-failed glyph
              int ringKey = -1;                                // identifies the ring drawable currently set (-1 = unknown)
              String avatarKey;                                // URL of the last real (attached) avatar bind; null = needs a bind
              String bgKey, bgPendingKey;                      // bg image really shown / being loaded (url@WxH); null = needs a load
              VH(View v) {
                  super(v);
                  ivBg       = v.findViewById(R.id.iv_card_bg);
                  ring       = v.findViewById(R.id.ring);
                  ivAvatar   = v.findViewById(R.id.iv_card_avatar);
                  ivAddBadge = v.findViewById(R.id.iv_card_add);
                  flAvatar   = v.findViewById(R.id.fl_avatar);
                  tvName     = v.findViewById(R.id.tv_card_name);
                  ivType     = v.findViewById(R.id.iv_card_type);
                  ivMuted    = v.findViewById(R.id.iv_card_muted);
                  ivCf       = v.findViewById(R.id.iv_card_cf);
                  tvTime     = v.findViewById(R.id.tv_card_time);
                  tvPreview  = v.findViewById(R.id.tv_card_preview);
                  ivType.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                  ivMuted.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                  ivCf.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                  tvTime.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                  tvPreview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                  // The card announces itself as one element (see describe()); children are decorative.
                  ivBg.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                  ring.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                  ivAvatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                  tvName.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
              }
          }
      }
  }