package com.callx.app.feed;
  import android.content.Context;
  import android.view.*;
  import android.widget.ImageView;
  import android.widget.TextView;
  import androidx.annotation.NonNull;
  import androidx.recyclerview.widget.DiffUtil;
  import androidx.recyclerview.widget.RecyclerView;
  import com.bumptech.glide.Glide;
  import com.callx.app.status.R;
  import com.callx.app.models.StatusItem;
  import com.callx.app.utils.StatusMuteManager;
  import de.hdodenhof.circleimageview.CircleImageView;
  import java.text.SimpleDateFormat;
  import java.util.*;
  /**
   * StatusListAdapter v26 — Privacy badge + Status Notes (Instagram Notes style).
   *
   * MODERN FEATURES v26:
   *   NEW: tv_privacy_badge — shows privacy icon per contact row
   *   NEW: tv_cf_badge — star icon for close-friends contact
   *   NEW: tv_note — short note text shown under name (like IG Notes)
   *   NEW: Entry fields: privacyLabel, noteText, isCloseFriends
   */
  public class StatusListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
      public static final int TYPE_MY_STATUS      = 0;
      public static final int TYPE_SECTION_HEADER = 1;
      public static final int TYPE_CONTACT        = 2;
      public static final int TYPE_MUTED_HEADER   = 3;
      public static final int TYPE_MUTED_CONTACT  = 4;

      // ── Entry model (v26: + privacyLabel, noteText, isCloseFriends) ───────
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
          // NEW v26
          public final String     privacyLabel;   // null = don't show; "🌍","👥","⭐","🔒","🚫"
          public final String     noteText;        // short note (null = none)
          public final boolean    isCloseFriends;  // show ⭐ on name

          public Entry(String ownerUid, String ownerName, String ownerPhoto,
                       Long latestTimestamp, int totalCount, int unseenCount,
                       StatusItem latestItem, boolean isMuted, String latestReaction) {
              this(ownerUid, ownerName, ownerPhoto, latestTimestamp, totalCount, unseenCount,
                   latestItem, isMuted, latestReaction, null, null, false);
          }

          public Entry(String ownerUid, String ownerName, String ownerPhoto,
                       Long latestTimestamp, int totalCount, int unseenCount,
                       StatusItem latestItem, boolean isMuted, String latestReaction,
                       String privacyLabel, String noteText, boolean isCloseFriends) {
              this.ownerUid        = ownerUid;
              this.ownerName       = ownerName;
              this.ownerPhoto      = ownerPhoto;
              this.latestTimestamp = latestTimestamp;
              this.totalCount      = totalCount;
              this.unseenCount     = unseenCount;
              this.latestItem      = latestItem;
              this.isMuted         = isMuted;
              this.latestReaction  = latestReaction;
              this.privacyLabel    = privacyLabel;
              this.noteText        = noteText;
              this.isCloseFriends  = isCloseFriends;
          }
      }

      /** Build a privacyLabel from a StatusItem.privacy string */
      public static String privacyLabel(String privacy, boolean isCloseFriends) {
          if (isCloseFriends || "close_friends".equals(privacy)) return "\u2B50 CF";
          if (privacy == null) return null;
          switch (privacy) {
              case "everyone":  return "\uD83C\uDF0D";
              case "contacts":  return "\uD83D\uDC65";
              case "except":    return "\uD83D\uDEAB";
              case "only":      return "\uD83D\uDD12";
              default:          return null;
          }
      }

      // ── Internal flat list ────────────────────────────────────────────────
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
      private List<FlatItem> items = new ArrayList<>();
      private int myStatusCount = 0;
      private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());

      public interface ContactClickListener {
          void onClick(String ownerUid, String ownerName);
      }
      public interface LongPressListener {
          void onLongPress(String ownerUid, String ownerName, boolean isMuted);
      }

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

      // ── Data update ───────────────────────────────────────────────────────
      public void update(List<Entry> unseen, List<Entry> seen) {
          update(unseen, seen, new ArrayList<>());
      }
      public void update(List<Entry> unseen, List<Entry> seen, List<Entry> muted) {
          final int prevMyCount = myStatusCount;
          myStatusCount = myStatuses.size();
          List<FlatItem> next = new ArrayList<>();
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
          final List<FlatItem> old = items;
          final int finalPrevMyCount = prevMyCount;
          DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
              @Override public int getOldListSize() { return old.size(); }
              @Override public int getNewListSize() { return next.size(); }
              @Override public boolean areItemsTheSame(int op, int np) {
                  FlatItem o = old.get(op), n = next.get(np);
                  if (o.kind != n.kind) return false;
                  if (o.kind == ITEM_HDR || o.kind == ITEM_MUT_HDR)
                      return java.util.Objects.equals(o.header, n.header);
                  if (o.kind == ITEM_ROW || o.kind == ITEM_MUT_ROW)
                      return o.entry != null && n.entry != null && o.entry.ownerUid.equals(n.entry.ownerUid);
                  return true;
              }
              @Override public boolean areContentsTheSame(int op, int np) {
                  FlatItem o = old.get(op), n = next.get(np);
                  if (o.kind == ITEM_ROW || o.kind == ITEM_MUT_ROW) {
                      if (o.entry == null || n.entry == null) return false;
                      return o.entry.unseenCount == n.entry.unseenCount
                          && java.util.Objects.equals(o.entry.latestTimestamp, n.entry.latestTimestamp)
                          && o.entry.totalCount == n.entry.totalCount
                          && o.entry.isMuted == n.entry.isMuted
                          && java.util.Objects.equals(o.entry.latestReaction, n.entry.latestReaction)
                          && java.util.Objects.equals(o.entry.noteText, n.entry.noteText);
                  }
                  if (o.kind == ITEM_MY) return myStatusCount == finalPrevMyCount;
                  return true;
              }
          });
          items = next;
          diff.dispatchUpdatesTo(this);
      }

      // ── Adapter ───────────────────────────────────────────────────────────
      @Override public int getItemViewType(int pos) {
          switch (items.get(pos).kind) {
              case ITEM_MY:      return TYPE_MY_STATUS;
              case ITEM_HDR:     return TYPE_SECTION_HEADER;
              case ITEM_MUT_HDR: return TYPE_MUTED_HEADER;
              case ITEM_MUT_ROW: return TYPE_MUTED_CONTACT;
              default:           return TYPE_CONTACT;
          }
      }
      @Override public int getItemCount() { return items.size(); }
      @NonNull @Override
      public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
          LayoutInflater li = LayoutInflater.from(parent.getContext());
          switch (vt) {
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
          if (holder instanceof MyStatusVH) bindMyStatus((MyStatusVH) holder, ctx);
          else if (holder instanceof HeaderVH) {
              String label = fi.kind == ITEM_MUT_HDR ? "\uD83D\uDD07 Muted" : fi.header;
              ((HeaderVH) holder).tvHeader.setText(label);
          } else bindContact((ContactVH) holder, fi.entry, ctx, fi.kind == ITEM_MUT_ROW);
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
              String timeSub = timeFmt.format(new Date(latest.timestamp != null ? latest.timestamp : 0));
              h.tvSub.setText(timeSub + " \u00B7 " + myStatuses.size() + " update"
                      + (myStatuses.size() > 1 ? "s" : "") + " \u00B7 " + latest.getExpiryLabel());
              if (latest.ownerPhoto != null && !latest.ownerPhoto.isEmpty())
                  Glide.with(ctx).load(latest.ownerPhoto).placeholder(R.drawable.ic_person).into(h.ivAvatar);
              else h.ivAvatar.setImageResource(R.drawable.ic_person);
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

      // ── Contact row (v26: + privacy badge, CF badge, note) ───────────────
      private void bindContact(ContactVH h, Entry e, Context ctx, boolean isMuted) {
          if (e == null) return;
          h.tvName.setText(e.ownerName != null ? e.ownerName : "");
          h.tvTime.setText(e.latestTimestamp != null ? timeFmt.format(new Date(e.latestTimestamp)) : "");
          if (e.ownerPhoto != null && !e.ownerPhoto.isEmpty())
              Glide.with(ctx).load(e.ownerPhoto).placeholder(R.drawable.ic_person).into(h.ivAvatar);
          else h.ivAvatar.setImageResource(R.drawable.ic_person);

          // Ring color
          h.ring.setBackgroundResource(isMuted ? R.drawable.circle_status_seen
                  : e.unseenCount > 0 ? R.drawable.circle_status_unseen : R.drawable.circle_status_seen);
          h.ring.setAlpha(isMuted ? 0.4f : 1f);

          // Unseen badge count
          if (h.tvBadge != null) {
              if (!isMuted && e.unseenCount > 1) {
                  h.tvBadge.setVisibility(View.VISIBLE);
                  h.tvBadge.setText(String.valueOf(e.unseenCount));
              } else h.tvBadge.setVisibility(View.GONE);
          }

          // NEW v26: Close Friends star
          if (h.tvCfBadge != null)
              h.tvCfBadge.setVisibility(e.isCloseFriends ? View.VISIBLE : View.GONE);

          // NEW v26: Privacy label (only show for own status in viewer; for other contacts, only CF)
          if (h.tvPrivacyBadge != null) {
              if (e.privacyLabel != null && !e.privacyLabel.isEmpty()) {
                  h.tvPrivacyBadge.setVisibility(View.VISIBLE);
                  h.tvPrivacyBadge.setText(e.privacyLabel);
              } else h.tvPrivacyBadge.setVisibility(View.GONE);
          }

          // NEW v26: Note text
          if (h.tvNote != null) {
              if (e.noteText != null && !e.noteText.isEmpty()) {
                  h.tvNote.setVisibility(View.VISIBLE);
                  h.tvNote.setText("\uD83D\uDCAC " + e.noteText);
              } else h.tvNote.setVisibility(View.GONE);
          }

          // Sub-text (media type hint)
          if (h.tvSub != null) {
              StatusItem latest = e.latestItem;
              String sub = "";
              if (latest != null) {
                  String t = latest.type != null ? latest.type : "";
                  switch (t) {
                      case "image":         sub = "\uD83D\uDCF7 Photo"; break;
                      case "video": case "reel_story": case "reel_clip": sub = "\uD83C\uDFA5 Video"; break;
                      case "link":          sub = "\uD83D\uDD17 Link"; break;
                      case "gif":           sub = "GIF"; break;
                      case "poll":          sub = "\uD83D\uDCCA Poll: " + (latest.pollQuestion != null ? latest.pollQuestion : ""); break;
                      case "question_box":  sub = "\u2753 " + (latest.questionBoxText != null ? latest.questionBoxText : "Ask me anything"); break;
                      default:
                          sub = (latest.caption != null && !latest.caption.isEmpty()) ? latest.caption
                              : (latest.text != null ? latest.text : "");
                  }
              }
              if (isMuted) sub = "\uD83D\uDD07 " + sub;
              h.tvSub.setText(sub);
          }

          // Thumbnail
          if (h.ivThumb != null) {
              StatusItem latest = e.latestItem;
              String url = latest != null ? (latest.thumbnailUrl != null ? latest.thumbnailUrl : latest.mediaUrl) : null;
              if (url != null && !url.isEmpty() && ("image".equals(latest.type)||"video".equals(latest.type)
                      ||"reel_story".equals(latest.type)||"reel_clip".equals(latest.type))) {
                  h.ivThumb.setVisibility(View.VISIBLE);
                  Glide.with(ctx).load(url).centerCrop().into(h.ivThumb);
              } else h.ivThumb.setVisibility(View.GONE);
          }

          // Reaction emoji
          if (h.tvReaction != null) {
              if (e.latestReaction != null) {
                  h.tvReaction.setVisibility(View.VISIBLE);
                  h.tvReaction.setText(e.latestReaction);
              } else h.tvReaction.setVisibility(View.GONE);
          }

          h.itemView.setOnClickListener(v -> {
              if (!isMuted && onContactClick != null) onContactClick.onClick(e.ownerUid, e.ownerName);
              else if (isMuted) android.widget.Toast.makeText(ctx,
                      e.ownerName + " is muted. Long press to unmute.", android.widget.Toast.LENGTH_SHORT).show();
          });
          h.itemView.setOnLongClickListener(v -> {
              if (onLongPress != null) onLongPress.onLongPress(e.ownerUid, e.ownerName, isMuted);
              return true;
          });
      }

      // ── ViewHolders ───────────────────────────────────────────────────────
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
          TextView tvName, tvTime, tvSub, tvBadge, tvReaction;
          // NEW v26
          TextView tvCfBadge, tvPrivacyBadge, tvNote;
          ContactVH(View v) {
              super(v);
              ivAvatar      = v.findViewById(R.id.iv_avatar);
              ring          = v.findViewById(R.id.ring);
              tvName        = v.findViewById(R.id.tv_name);
              tvTime        = v.findViewById(R.id.tv_time);
              tvSub         = v.findViewById(R.id.tv_sub);
              tvBadge       = v.findViewById(R.id.tv_badge);
              ivThumb       = v.findViewById(R.id.iv_thumb);
              tvReaction    = v.findViewById(R.id.tv_reaction);
              // v26 additions
              tvCfBadge     = v.findViewById(R.id.tv_cf_badge);
              tvPrivacyBadge = v.findViewById(R.id.tv_privacy_badge);
              tvNote        = v.findViewById(R.id.tv_note);
          }
      }
  }