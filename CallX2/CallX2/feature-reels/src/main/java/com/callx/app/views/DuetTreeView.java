package com.callx.app.views;

  import android.content.Context;
  import android.graphics.*;
  import android.util.AttributeSet;
  import android.view.*;
  import androidx.annotation.NonNull;
  import java.util.*;

  /**
   * DuetTreeView — Custom Canvas view that draws an interactive duet chain tree.
   *
   * Features:
   *  ✅ Radial layout: root at centre, children spread outward per depth level
   *  ✅ Pinch-to-zoom (ScaleGestureDetector) + fling/pan (GestureDetector)
   *  ✅ Tap on node → callback with reelId
   *  ✅ Thumbnail circle (loaded async via Glide into a Bitmap cache)
   *  ✅ Lines from parent to each child
   *  ✅ Node label (owner name) below circle
   *  ✅ Depth 0 = gold ring, depth 1 = white ring, depth 2+ = grey ring
   */
  public class DuetTreeView extends View {

      public interface OnNodeClick { void onNodeClick(String reelId); }

      // ── Tree node ─────────────────────────────────────────────────────────────
      public static class TreeNode {
          public final String  reelId, ownerName, thumbUrl;
          public final int     depth;
          public final TreeNode parent;
          public final List<TreeNode> children = new ArrayList<>();

          // Layout coords (set during measure pass)
          public float x, y;

          public TreeNode(String reelId, String ownerName, String thumbUrl,
                          int depth, TreeNode parent) {
              this.reelId    = reelId;
              this.ownerName = ownerName;
              this.thumbUrl  = thumbUrl;
              this.depth     = depth;
              this.parent    = parent;
          }
      }

      // ── Painting ──────────────────────────────────────────────────────────────
      private final Paint linePaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
      private final Paint circlePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
      private final Paint ringPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
      private final Paint textPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
      private final Paint placeholderP = new Paint(Paint.ANTI_ALIAS_FLAG);

      // ── State ─────────────────────────────────────────────────────────────────
      private TreeNode rootNode;
      private final List<TreeNode> allNodes = new ArrayList<>();
      private OnNodeClick clickListener;

      // Bitmap cache: reelId → circular thumb bitmap
      private final Map<String, Bitmap> thumbCache = new HashMap<>();
      private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

      // Transform
      private float translateX = 0, translateY = 0;
      private float scaleFactor = 1f;
      private static final float MIN_SCALE = 0.3f, MAX_SCALE = 3f;

      // Gesture detectors
      private ScaleGestureDetector scaleDetector;
      private GestureDetector      gestureDetector;

      // Last known tap in view coords
      private float lastTapX, lastTapY;

      public DuetTreeView(Context ctx) { super(ctx); init(ctx); }
      public DuetTreeView(Context ctx, AttributeSet a) { super(ctx, a); init(ctx); }

      private void init(Context ctx) {
          linePaint.setColor(0x66FFFFFF);
          linePaint.setStrokeWidth(2f);
          linePaint.setStyle(Paint.Style.STROKE);

          circlePaint.setStyle(Paint.Style.FILL);
          ringPaint.setStyle(Paint.Style.STROKE);
          ringPaint.setStrokeWidth(3f);

          textPaint.setColor(0xFFFFFFFF);
          textPaint.setTextSize(26f);
          textPaint.setTextAlign(Paint.Align.CENTER);

          placeholderP.setColor(0xFF333333);

          scaleDetector  = new ScaleGestureDetector(ctx, new ScaleListener());
          gestureDetector = new GestureDetector(ctx, new GestureListener());
      }

      public void setRootNode(TreeNode root) {
          this.rootNode = root;
          allNodes.clear();
          collectNodes(root, allNodes);
          requestLayout();
          invalidate();
          preloadThumbs();
      }

      public void setOnNodeClickListener(OnNodeClick l) { this.clickListener = l; }

      // ── Layout: radial placement ───────────────────────────────────────────────
      private void layoutTree() {
          if (rootNode == null) return;
          int w = getWidth(), h = getHeight();
          rootNode.x = w / 2f;
          rootNode.y = h / 2f;
          layoutChildren(rootNode, 0, (float)(2 * Math.PI), 120f);
      }

      private void layoutChildren(TreeNode node, float startAngle, float sweepAngle, float radius) {
          if (node.children.isEmpty()) return;
          float step = sweepAngle / node.children.size();
          float angle = startAngle + step / 2f;
          for (TreeNode child : node.children) {
              child.x = node.x + (float)(radius * Math.cos(angle));
              child.y = node.y + (float)(radius * Math.sin(angle));
              layoutChildren(child, angle - step / 2f, step, radius * 0.8f);
              angle += step;
          }
      }

      @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
          super.onSizeChanged(w, h, ow, oh);
          layoutTree();
      }

      // ── Drawing ───────────────────────────────────────────────────────────────
      @Override protected void onDraw(@NonNull Canvas canvas) {
          super.onDraw(canvas);
          if (rootNode == null) return;

          canvas.save();
          canvas.translate(translateX, translateY);
          canvas.scale(scaleFactor, scaleFactor, getWidth() / 2f, getHeight() / 2f);

          // Draw edges first
          for (TreeNode node : allNodes) {
              if (node.parent != null) {
                  canvas.drawLine(node.parent.x, node.parent.y, node.x, node.y, linePaint);
              }
          }

          // Draw nodes
          for (TreeNode node : allNodes) {
              drawNode(canvas, node);
          }

          canvas.restore();
      }

      private void drawNode(Canvas canvas, TreeNode node) {
          float r = nodeRadius(node.depth);
          Bitmap thumb = thumbCache.get(node.reelId);

          if (thumb != null) {
              // Circular clip for thumbnail
              Path clip = new Path();
              clip.addCircle(node.x, node.y, r, Path.Direction.CW);
              canvas.save();
              canvas.clipPath(clip);
              RectF dst = new RectF(node.x - r, node.y - r, node.x + r, node.y + r);
              canvas.drawBitmap(thumb, null, dst, null);
              canvas.restore();
          } else {
              placeholderP.setColor(depthColor(node.depth));
              canvas.drawCircle(node.x, node.y, r, placeholderP);
          }

          // Ring
          ringPaint.setColor(node.depth == 0 ? 0xFFFFD700 : node.depth == 1 ? 0xFFFFFFFF : 0xFF888888);
          canvas.drawCircle(node.x, node.y, r, ringPaint);

          // Name label
          String label = node.ownerName.length() > 10
              ? node.ownerName.substring(0, 10) + "…" : node.ownerName;
          textPaint.setTextSize(Math.max(18f, 28f - node.depth * 4f));
          canvas.drawText(label, node.x, node.y + r + 32f, textPaint);
      }

      private float nodeRadius(int depth) {
          return Math.max(24f, 56f - depth * 10f);
      }

      private int depthColor(int depth) {
          int[] colors = { 0xFF1A1A2E, 0xFF16213E, 0xFF0F3460, 0xFF533483 };
          return colors[Math.min(depth, colors.length - 1)];
      }

      // ── Thumbnail preload ─────────────────────────────────────────────────────
      private void preloadThumbs() {
          for (TreeNode node : allNodes) {
              if (node.thumbUrl == null || node.thumbUrl.isEmpty()) continue;
              if (thumbCache.containsKey(node.reelId)) continue;
              String reelId = node.reelId;
              String url    = node.thumbUrl;
              new Thread(() -> {
                  try {
                      Bitmap bmp = com.bumptech.glide.Glide.with(getContext())
                          .asBitmap().load(url).submit(120, 120).get();
                      if (bmp != null) {
                          Bitmap round = toCircle(bmp);
                          thumbCache.put(reelId, round);
                          mainHandler.post(this::invalidate);
                      }
                  } catch (Exception ignored) {}
              }).start();
          }
      }

      private Bitmap toCircle(Bitmap src) {
          int size = Math.min(src.getWidth(), src.getHeight());
          Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
          Canvas c = new Canvas(out);
          Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
          c.drawCircle(size / 2f, size / 2f, size / 2f, p);
          p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
          c.drawBitmap(src, 0, 0, p);
          return out;
      }

      // ── Touch handling ────────────────────────────────────────────────────────
      @Override public boolean onTouchEvent(MotionEvent e) {
          scaleDetector.onTouchEvent(e);
          gestureDetector.onTouchEvent(e);
          return true;
      }

      private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
          @Override public boolean onScale(ScaleGestureDetector d) {
              scaleFactor = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scaleFactor * d.getScaleFactor()));
              invalidate();
              return true;
          }
      }

      private class GestureListener extends GestureDetector.SimpleOnGestureListener {
          @Override public boolean onScroll(MotionEvent e1, @NonNull MotionEvent e2, float dx, float dy) {
              translateX -= dx; translateY -= dy; invalidate(); return true;
          }
          @Override public boolean onSingleTapUp(@NonNull MotionEvent e) {
              float tx = (e.getX() - translateX) / scaleFactor;
              float ty = (e.getY() - translateY) / scaleFactor;
              // Adjust for scale pivot
              float pivotX = getWidth() / 2f, pivotY = getHeight() / 2f;
              float wx = (e.getX() - pivotX) / scaleFactor + pivotX - translateX / scaleFactor;
              float wy = (e.getY() - pivotY) / scaleFactor + pivotY - translateY / scaleFactor;
              for (TreeNode node : allNodes) {
                  float dist = (float)Math.sqrt(Math.pow(wx - node.x, 2) + Math.pow(wy - node.y, 2));
                  if (dist <= nodeRadius(node.depth) + 10f) {
                      if (clickListener != null) clickListener.onNodeClick(node.reelId);
                      return true;
                  }
              }
              return false;
          }
      }

      private void collectNodes(TreeNode node, List<TreeNode> out) {
          out.add(node);
          for (TreeNode child : node.children) collectNodes(child, out);
      }
  }
  