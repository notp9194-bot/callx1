package com.callx.app.social;

  import android.content.Intent;
  import android.os.Bundle;
  import android.view.*;
  import android.widget.*;
  import androidx.annotation.NonNull;
  import androidx.appcompat.app.AppCompatActivity;
  import com.callx.app.reels.R;
  import com.callx.app.utils.FirebaseUtils;
  import com.callx.app.views.DuetTreeView;
  import com.google.firebase.database.*;
  import java.util.*;

  /**
   * DuetTreeActivity — Visualises the full duet chain tree.
   *
   * Root node = originalReelId
   * Children = reels where duetOf = parentId (direct duets)
   * Grand-children = reels where duetOf = each child's reelId
   *
   * Loads up to 4 levels deep (max 1+N+N²+N³ nodes).
   * DuetTreeView (custom Canvas drawing) renders the tree with:
   *  • Thumbnail circles connected by lines
   *  • Tap circle → opens SingleReelPlayerActivity
   *  • Pinch-to-zoom + pan
   *  • Depth indicated by circle size (root = largest)
   */
  public class DuetTreeActivity extends AppCompatActivity {

      public static final String EXTRA_ROOT_REEL_ID  = "tree_root_reel_id";
      public static final String EXTRA_OWNER_NAME    = "tree_owner_name";
      private static final int   MAX_DEPTH           = 4;
      private static final int   MAX_CHILDREN        = 8;

      private DuetTreeView treeView;
      private ProgressBar  progress;
      private TextView     tvTitle;
      private ImageButton  btnBack;

      private String rootReelId;

      @Override
      protected void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);
          setContentView(R.layout.activity_duet_tree);

          rootReelId      = getIntent().getStringExtra(EXTRA_ROOT_REEL_ID);
          String ownerName= getIntent().getStringExtra(EXTRA_OWNER_NAME);

          btnBack  = findViewById(R.id.btn_tree_back);
          tvTitle  = findViewById(R.id.tv_tree_title);
          treeView = findViewById(R.id.duet_tree_view);
          progress = findViewById(R.id.progress_tree);

          tvTitle.setText(ownerName != null ? "@" + ownerName + "'s Duet Tree" : "Duet Tree");
          btnBack.setOnClickListener(v -> finish());

          treeView.setOnNodeClickListener(reelId -> {
              Intent i = new Intent(this, com.callx.app.player.SingleReelPlayerActivity.class);
              i.putExtra(com.callx.app.player.SingleReelPlayerActivity.EXTRA_REEL_ID, reelId);
              startActivity(i);
          });

          loadTree();
      }

      private void loadTree() {
          if (rootReelId == null) return;
          progress.setVisibility(View.VISIBLE);

          // First load root reel info
          FirebaseUtils.db().getReference("reels").child(rootReelId)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      String name  = snap.child("ownerName").getValue(String.class);
                      String thumb = snap.child("thumbUrl").getValue(String.class);
                      DuetTreeView.TreeNode root = new DuetTreeView.TreeNode(
                          rootReelId,
                          name  != null ? name  : "Root",
                          thumb != null ? thumb : "",
                          0, null
                      );
                      loadChildren(root, 1, new Runnable() {
                          @Override public void run() {
                              progress.setVisibility(View.GONE);
                              treeView.setRootNode(root);
                          }
                      });
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {
                      progress.setVisibility(View.GONE);
                  }
              });
      }

      private void loadChildren(DuetTreeView.TreeNode parent, int depth, Runnable onDone) {
          if (depth > MAX_DEPTH) { onDone.run(); return; }

          FirebaseUtils.db().getReference("reels")
              .orderByChild("duetOf").equalTo(parent.reelId).limitToFirst(MAX_CHILDREN)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      List<DuetTreeView.TreeNode> children = new ArrayList<>();
                      for (DataSnapshot ds : snap.getChildren()) {
                          String id    = ds.getKey();
                          String name  = ds.child("ownerName").getValue(String.class);
                          String thumb = ds.child("thumbUrl").getValue(String.class);
                          if (id != null) {
                              children.add(new DuetTreeView.TreeNode(
                                  id, name != null ? name : "User",
                                  thumb != null ? thumb : "", depth, parent));
                          }
                      }
                      parent.children.addAll(children);

                      if (children.isEmpty()) { onDone.run(); return; }

                      // Recursively load children of children
                      final int[] remaining = { children.size() };
                      for (DuetTreeView.TreeNode child : children) {
                          loadChildren(child, depth + 1, () -> {
                              remaining[0]--;
                              if (remaining[0] == 0) onDone.run();
                          });
                      }
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) { onDone.run(); }
              });
      }
  }
  