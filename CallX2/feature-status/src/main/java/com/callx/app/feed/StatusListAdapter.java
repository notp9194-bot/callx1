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

      // ── Highlight album model ─────────────────────────────────────────────
      public static class HighlightAlbum {
          public final String title;
          public final String coverUrl;
          public HighlightAlbum(String title, String coverUrl) {
              this.title = title; this.coverUrl = coverUrl;
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

      private static class FlatItem {
          int    kind;
          String header;
          Entry  entry;
          FlatItem(int k, String h, Entry e) { kind = k; header = h; entry = e; }
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
          List<FlatItem> next = buildFlatItems(unseen, seen, muted);
          dispatchDiff(next, prevMyCount);
      }

      private List<FlatItem> buildFlatItems(List<Entry> unseen, List<Entry> seen, List<Entry> muted) {
          List<FlatItem> next = new ArrayList<>();
          // FIX: Highlights strip at very top (if any exist)
          if (!highlights.isEmpty()) {
              next.add(new FlatItem(ITEM_HIGHLIGHTS, null, null));
          }
          next.add(new FlatItem(ITEM_MY, null, null));
          if (!unseen.isEmpty()) {
              next.add(new FlatItem(ITEM_HDR, "Recent updates", null));
              for (Entry e : unseen) next.add(new FlatItem(ITEM_ROW, null, e));
          }
          if (!seen.isEmpty()) {
              next.add(new FlatItem(ITEM_HDR, "Viewed updates", null));
              for (Entry e : seen) next.add(new FlatItem(ITEM_ROW, null, e));
          }
          if (!muted.isEmpty()) {
              next.add(new FlatItem(ITEM_MUT_HDR, "Muted", null));
              for (Entry e : muted) next.add(new FlatItem(ITEM_MUT_ROW, null, e));
          }
          return next;
      }

      private void rebuildFlatList(List<FlatItem> old) {
          // Called when only highlights change — extract existing entry lists and rebuild
          List<Entry> unseen = new ArrayList<>(), seen = new ArrayList<>(), muted = new ArrayList<>();
          boolean inUnseen = false, inSeen = false, inMuted = false;
          for (FlatItem fi : old) {
              if (fi.kind == ITEM_HDR && "Recent updates".equals(fi.header)) { inUnseen=true; inSeen=false; inMuted=false; }
              else if (fi.kind == ITEM_HDR && "Viewed updates".equals(fi.header)) { inUnseen=false; inSeen=true; inMuted=false; }
              else if (fi.kind == ITEM_MUT_HDR) { inUnseen=false; inSeen=false; inMuted=true; }
              else if (fi.kind == ITEM_ROW && fi.entry != null) {
                  if (inUnseen) unseen.add(fi.entry);
                  else if (inSeen) seen.add(fi.entry);
              } else if (fi.kind == ITEM_MUT_ROW && fi.entry != null) {
                  if (inMuted) muted.add(fi.entry);
              }
          }
          final int prevMyCount = myStatusCount;
          myStatusCount = myStatuses.size();
          List<FlatItem> next = buildFlatItems(unseen, seen, muted);
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
  }