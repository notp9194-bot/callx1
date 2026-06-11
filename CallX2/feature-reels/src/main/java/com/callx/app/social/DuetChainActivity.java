package com.callx.app.social;

  import android.content.Intent;
  import android.os.Bundle;
  import android.view.LayoutInflater;
  import android.view.View;
  import android.view.ViewGroup;
  import android.widget.ImageView;
  import android.widget.LinearLayout;
  import android.widget.ProgressBar;
  import android.widget.TextView;
  import android.widget.Toast;

  import androidx.annotation.NonNull;
  import androidx.appcompat.app.AppCompatActivity;
  import androidx.recyclerview.widget.LinearLayoutManager;
  import androidx.recyclerview.widget.RecyclerView;

  import com.bumptech.glide.Glide;
  import com.google.firebase.firestore.FirebaseFirestore;
  import com.google.firebase.firestore.QueryDocumentSnapshot;

  import java.util.ArrayList;
  import java.util.List;

  /**
   * ✅ IMPROVEMENT v10 (DUET CHAIN TREE): Shows a threaded view of:
   *   Root Reel  →  Direct duets of that reel  →  Chain duets (duets of duets)
   *
   * Launch with:
   *   Intent i = new Intent(ctx, DuetChainActivity.class);
   *   i.putExtra("root_reel_id",    reel.reelId);   // or reel.duetRootId
   *   i.putExtra("root_owner_name", reel.ownerName);
   *   ctx.startActivity(i);
   */
  public class DuetChainActivity extends AppCompatActivity {

      public static final String EXTRA_ROOT_REEL_ID    = "root_reel_id";
      public static final String EXTRA_ROOT_OWNER_NAME = "root_owner_name";

      // ── Tree node ─────────────────────────────────────────────────────────────

      public static class DuetNode {
          public final String reelId;
          public final String ownerName;
          public final String thumbnailUrl;
          public final String duetOf;       // null for root
          public final int    depth;        // 0 = root, 1 = direct duet, 2+ = chain

          public DuetNode(String reelId, String ownerName,
                          String thumbnailUrl, String duetOf, int depth) {
              this.reelId       = reelId;
              this.ownerName    = ownerName != null ? ownerName : "Unknown";
              this.thumbnailUrl = thumbnailUrl;
              this.duetOf       = duetOf;
              this.depth        = depth;
          }
      }

      // ── Adapter ───────────────────────────────────────────────────────────────

      private static class ChainAdapter extends RecyclerView.Adapter<ChainAdapter.VH> {

          private final List<DuetNode> nodes;
          private final OnNodeClick    listener;

          interface OnNodeClick { void onClick(DuetNode node); }

          ChainAdapter(List<DuetNode> nodes, OnNodeClick listener) {
              this.nodes    = nodes;
              this.listener = listener;
          }

          @NonNull @Override
          public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
              LinearLayout row = new LinearLayout(parent.getContext());
              row.setOrientation(LinearLayout.HORIZONTAL);
              row.setPadding(0, 8, 0, 8);
              row.setLayoutParams(new RecyclerView.LayoutParams(
                      ViewGroup.LayoutParams.MATCH_PARENT,
                      ViewGroup.LayoutParams.WRAP_CONTENT));
              return new VH(row);
          }

          @Override
          public void onBindViewHolder(@NonNull VH h, int pos) {
              DuetNode n = nodes.get(pos);
              float dp = h.row.getResources().getDisplayMetrics().density;

              // Indent = depth * 32dp
              int indent = (int)(n.depth * 32 * dp);

              // Connector line for non-root nodes
              h.row.removeAllViews();

              if (n.depth > 0) {
                  View connector = new View(h.row.getContext());
                  connector.setBackgroundColor(0x44FFFFFF);
                  LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                          (int)(2 * dp), ViewGroup.LayoutParams.MATCH_PARENT);
                  clp.setMarginStart(indent - (int)(16 * dp));
                  clp.setMarginEnd((int)(14 * dp));
                  connector.setLayoutParams(clp);
                  h.row.addView(connector);
              }

              // Thumbnail
              ImageView thumb = new ImageView(h.row.getContext());
              int thumbSize = (int)(52 * dp);
              LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(thumbSize, thumbSize);
              if (n.depth == 0) tlp.setMarginStart((int)(16 * dp));
              thumb.setLayoutParams(tlp);
              thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
              thumb.setBackgroundColor(0xFF222222);
              // Circular outline for depth > 0 (chain duets)
              if (n.depth > 0) {
                  thumb.setClipToOutline(true);
                  thumb.setOutlineProvider(new android.view.ViewOutlineProvider() {
                      @Override public void getOutline(View v, android.graphics.Outline o) {
                          o.setOval(0, 0, v.getWidth(), v.getHeight());
                      }
                  });
              }
              if (n.thumbnailUrl != null && !n.thumbnailUrl.isEmpty()) {
                  Glide.with(thumb).load(n.thumbnailUrl).centerCrop().into(thumb);
              }
              h.row.addView(thumb);

              // Text block
              LinearLayout textBlock = new LinearLayout(h.row.getContext());
              textBlock.setOrientation(LinearLayout.VERTICAL);
              LinearLayout.LayoutParams tblp = new LinearLayout.LayoutParams(0,
                      ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
              tblp.setMarginStart((int)(12 * dp));
              textBlock.setLayoutParams(tblp);

              TextView tvName = new TextView(h.row.getContext());
              tvName.setText("@" + n.ownerName);
              tvName.setTextColor(0xFFFFFFFF);
              tvName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
              tvName.setTypeface(null, n.depth == 0
                      ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);

              TextView tvLabel = new TextView(h.row.getContext());
              tvLabel.setText(n.depth == 0 ? "Original reel" :
                               n.depth == 1 ? "Duet"          : "Chain duet (×" + n.depth + ")");
              tvLabel.setTextColor(n.depth == 0 ? 0xFF40C4FF :
                                    n.depth == 1 ? 0xFF69F0AE : 0xFFFFAB40);
              tvLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);

              textBlock.addView(tvName);
              textBlock.addView(tvLabel);
              h.row.addView(textBlock);

              // Play arrow
              TextView play = new TextView(h.row.getContext());
              play.setText("▶");
              play.setTextColor(0xFF888888);
              play.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
              LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                      ViewGroup.LayoutParams.WRAP_CONTENT,
                      ViewGroup.LayoutParams.WRAP_CONTENT);
              plp.setMarginEnd((int)(16 * dp));
              plp.gravity = android.view.Gravity.CENTER_VERTICAL;
              play.setLayoutParams(plp);
              h.row.addView(play);

              h.row.setOnClickListener(v -> listener.onClick(n));
          }

          @Override public int getItemCount() { return nodes.size(); }

          static class VH extends RecyclerView.ViewHolder {
              final LinearLayout row;
              VH(LinearLayout row) { super(row); this.row = row; }
          }
      }

      // ── Activity lifecycle ────────────────────────────────────────────────────

      private static final String TAG = "DuetChainActivity";

      private String rootReelId;
      private RecyclerView      rv;
      private ProgressBar       pb;
      private TextView          tvHeader;
      private final List<DuetNode> nodes = new ArrayList<>();
      private ChainAdapter         adapter;

      @Override
      protected void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);

          rootReelId = getIntent().getStringExtra(EXTRA_ROOT_REEL_ID);
          String ownerName = getIntent().getStringExtra(EXTRA_ROOT_OWNER_NAME);

          // ── Build UI programmatically (no XML dependency) ──────────────────
          LinearLayout root = new LinearLayout(this);
          root.setOrientation(LinearLayout.VERTICAL);
          root.setBackgroundColor(0xFF111111);

          // Toolbar row
          LinearLayout toolbar = new LinearLayout(this);
          toolbar.setOrientation(LinearLayout.HORIZONTAL);
          toolbar.setPadding(0, (int)(getResources().getDisplayMetrics().density * 48), 0, 0);
          toolbar.setBackgroundColor(0xFF1A1A1A);

          TextView tvBack = new TextView(this);
          tvBack.setText("  ←  ");
          tvBack.setTextColor(0xFFFFFFFF);
          tvBack.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20);
          tvBack.setPadding(24, 20, 8, 20);
          tvBack.setOnClickListener(v -> finish());

          tvHeader = new TextView(this);
          tvHeader.setText("Duet Chain" + (ownerName != null ? " · @" + ownerName : ""));
          tvHeader.setTextColor(0xFFFFFFFF);
          tvHeader.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
          tvHeader.setTypeface(null, android.graphics.Typeface.BOLD);
          LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(0,
                  ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
          hlp.gravity = android.view.Gravity.CENTER_VERTICAL;
          tvHeader.setLayoutParams(hlp);
          tvHeader.setPadding(8, 20, 0, 20);

          toolbar.addView(tvBack);
          toolbar.addView(tvHeader);

          pb = new ProgressBar(this);
          pb.setIndeterminate(true);

          rv = new RecyclerView(this);
          rv.setLayoutManager(new LinearLayoutManager(this));
          rv.setLayoutParams(new LinearLayout.LayoutParams(
                  ViewGroup.LayoutParams.MATCH_PARENT, 0,1f));

          root.addView(toolbar);
          root.addView(pb);
          root.addView(rv);
          setContentView(root);

          adapter = new ChainAdapter(nodes, node -> openReel(node.reelId));
          rv.setAdapter(adapter);

          if (rootReelId != null) loadChain(rootReelId);
          else { pb.setVisibility(View.GONE); tvHeader.setText("No reel ID supplied"); }
      }

      /**
       * Loads the chain in two passes:
       *  1. Load the root reel document.
       *  2. Query all reels where duetOf == rootReelId OR duetRootId == rootReelId,
       *     then group by depth.
       */
      private void loadChain(String rootId) {
          FirebaseFirestore db = FirebaseFirestore.getInstance();

          // Pass 1 — root reel
          db.collection("reels").document(rootId).get()
            .addOnSuccessListener(doc -> {
                if (!doc.exists()) { pb.setVisibility(View.GONE); return; }
                String owner = doc.getString("ownerName");
                String thumb = doc.getString("thumbnailUrl");
                nodes.add(0, new DuetNode(rootId, owner, thumb, null, 0));
                adapter.notifyDataSetChanged();

                // Pass 2 — all descendants (direct + chain)
                db.collection("reels")
                  .whereEqualTo("duetRootId", rootId)
                  .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                  .limit(50)
                  .get()
                  .addOnSuccessListener(snap -> {
                      for (QueryDocumentSnapshot d : snap) {
                          String id    = d.getId();
                          String uName = d.getString("ownerName");
                          String tUrl  = d.getString("thumbnailUrl");
                          String dOf   = d.getString("duetOf");
                          // depth: direct duet of root = 1, otherwise 2+
                          int depth = rootId.equals(dOf) ? 1 : 2;
                          nodes.add(new DuetNode(id, uName, tUrl, dOf, depth));
                      }
                      pb.setVisibility(View.GONE);
                      adapter.notifyDataSetChanged();
                      if (snap.isEmpty()) {
                          tvHeader.append(" · no duets yet");
                      }
                  })
                  .addOnFailureListener(e -> {
                      pb.setVisibility(View.GONE);
                      Toast.makeText(this, "Failed to load chain: " + e.getMessage(),
                              Toast.LENGTH_SHORT).show();
                  });
            })
            .addOnFailureListener(e -> {
                pb.setVisibility(View.GONE);
                Toast.makeText(this, "Reel not found", Toast.LENGTH_SHORT).show();
            });
      }

      private void openReel(String reelId) {
          // Open in your reel player — adjust class name to your actual player activity
          try {
              Intent i = new Intent(this,
                      Class.forName("com.callx.app.social.ReelPlayerActivity"));
              i.putExtra("reel_id", reelId);
              startActivity(i);
          } catch (ClassNotFoundException e) {
              Toast.makeText(this, "Reel: " + reelId, Toast.LENGTH_SHORT).show();
          }
      }
  }
  