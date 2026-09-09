package com.callx.app.chatv2;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLSurfaceView;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

import com.callx.app.db.entity.MessageEntity;

/**
 * Bridges GLSurfaceView's callback thread to the native engine. All
 * heavy lifting (layout, culling, drawing) happens in C++ — this class
 * only marshals data across the JNI boundary.
 *
 * Media bubbles (image / video-thumbnail): decoding runs on the MAIN
 * thread via Glide (submitMessages must be called from the main thread —
 * true for the LiveData observer in FastChatActivity). The decoded
 * Bitmap lands in `mediaBitmapCache`; the GL thread (applyMessages,
 * called from onDrawFrame) does the actual texImage2D upload via
 * MediaTextureBuilder and then drops the bitmap. No native-side changes
 * were needed — a media bubble is just a textured quad, same draw path
 * as a text bubble.
 */
public class FastChatGLRenderer implements GLSurfaceView.Renderer {

    private static final float PLACEHOLDER_W = 480f;
    private static final float PLACEHOLDER_H = 320f;

    private final NativeChatEngine engine = new NativeChatEngine();
    private final BubbleTextureBuilder textureBuilder = new BubbleTextureBuilder();
    private final MediaTextureBuilder mediaTextureBuilder = new MediaTextureBuilder();

    // messageId -> decoded bitmap, waiting for GL-thread upload.
    private final Map<String, Bitmap> mediaBitmapCache = new ConcurrentHashMap<>();
    // messageId set we've already kicked off a Glide load for (avoid re-requesting every LiveData tick).
    private final Set<String> requestedMediaIds = new CopyOnWriteArraySet<>();

    private String myUid;
    private Context appContext;

    // Pending message list set from the main thread (LiveData observer),
    // applied on the GL thread on the next frame.
    private volatile List<MessageEntity> pendingMessages;
    private volatile List<MessageEntity> lastAppliedMessages;

    public void setMyUid(String uid) {
        this.myUid = uid;
    }

    public void setContext(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** Must be called from the main thread (kicks off Glide loads for media bubbles). */
    public void submitMessages(List<MessageEntity> messages) {
        this.pendingMessages = messages;
        prefetchMedia(messages);
    }

    public NativeChatEngine getEngine() {
        return engine;
    }

    private void prefetchMedia(List<MessageEntity> messages) {
        if (appContext == null) return;
        for (MessageEntity m : messages) {
            if (!isMediaType(m.type)) continue;
            if (requestedMediaIds.contains(m.id) || mediaBitmapCache.containsKey(m.id)) continue;

            String url = m.thumbnailUrl != null ? m.thumbnailUrl : m.mediaUrl;
            if (url == null || url.isEmpty()) continue;

            requestedMediaIds.add(m.id);
            String messageId = m.id;
            Glide.with(appContext)
                    .asBitmap()
                    .load(url)
                    .into(new CustomTarget<Bitmap>() {
                        @Override
                        public void onResourceReady(@androidx.annotation.NonNull Bitmap resource,
                                                     @androidx.annotation.Nullable Transition<? super Bitmap> transition) {
                            // Copy — Glide may recycle/reuse its own bitmap later.
                            mediaBitmapCache.put(messageId, resource.copy(resource.getConfig(), false));
                        }

                        @Override
                        public void onLoadCleared(@androidx.annotation.Nullable android.graphics.drawable.Drawable placeholder) {
                            // no-op — bubble stays as placeholder quad
                        }
                    });
        }
    }

    private static boolean isMediaType(String type) {
        return "image".equals(type) || "video".equals(type);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        engine.nativeInit();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        engine.nativeResize(width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        List<MessageEntity> messages = pendingMessages;
        if (messages != null) {
            pendingMessages = null;
            applyMessages(messages);
        } else if (!mediaBitmapCache.isEmpty()) {
            // A media bitmap finished decoding since the last full apply —
            // re-run layout so its bubble picks up the real texture
            // instead of staying a placeholder. Cheap at chat-sized
            // message counts; RENDERMODE_CONTINUOUSLY means this is
            // checked every frame regardless.
            List<MessageEntity> last = lastAppliedMessages;
            if (last != null) applyMessages(last);
        }
        engine.nativeDrawFrame();
    }

    private void applyMessages(List<MessageEntity> messages) {
        lastAppliedMessages = messages;
        int n = messages.size();
        String[] ids = new String[n];
        boolean[] isMine = new boolean[n];
        float[] bubbleW = new float[n];
        float[] bubbleH = new float[n];
        int[] textureIds = new int[n];
        float[] texW = new float[n];
        float[] texH = new float[n];

        for (int i = 0; i < n; i++) {
            MessageEntity m = messages.get(i);
            boolean mine = myUid != null && myUid.equals(m.senderId);
            ids[i] = m.id;
            isMine[i] = mine;

            if (isMediaType(m.type)) {
                resolveMediaTexture(m, i, bubbleW, bubbleH, textureIds, texW, texH);
            } else {
                String text = m.text != null ? m.text : "";
                BubbleTextureBuilder.BubbleTexture tex = textureBuilder.buildOrGet(m.id, text, mine);
                bubbleW[i] = tex.width;
                bubbleH[i] = tex.height;
                textureIds[i] = tex.textureId;
                texW[i] = tex.width;
                texH[i] = tex.height;
            }
        }

        engine.nativeSetMessages(ids, isMine, bubbleW, bubbleH, textureIds, texW, texH);
    }

    private void resolveMediaTexture(MessageEntity m, int i,
                                      float[] bubbleW, float[] bubbleH,
                                      int[] textureIds, float[] texW, float[] texH) {
        if (mediaTextureBuilder.has(m.id)) {
            MediaTextureBuilder.MediaTexture tex = mediaTextureBuilder.get(m.id);
            bubbleW[i] = tex.width;
            bubbleH[i] = tex.height;
            textureIds[i] = tex.textureId;
            texW[i] = tex.width;
            texH[i] = tex.height;
            return;
        }

        Bitmap decoded = mediaBitmapCache.remove(m.id);
        if (decoded != null) {
            MediaTextureBuilder.MediaTexture tex = mediaTextureBuilder.upload(m.id, decoded);
            bubbleW[i] = tex.width;
            bubbleH[i] = tex.height;
            textureIds[i] = tex.textureId;
            texW[i] = tex.width;
            texH[i] = tex.height;
            return;
        }

        // Still decoding (or failed) — draw a flat placeholder quad
        // (textureId 0 -> renderer falls back to the plain bubble color)
        // sized like a typical media bubble so layout doesn't jump once
        // the real image lands.
        bubbleW[i] = PLACEHOLDER_W;
        bubbleH[i] = PLACEHOLDER_H;
        textureIds[i] = 0;
        texW[i] = PLACEHOLDER_W;
        texH[i] = PLACEHOLDER_H;
    }

    public void destroy() {
        textureBuilder.clear();
        mediaTextureBuilder.clear();
        engine.nativeDestroy();
    }
}
