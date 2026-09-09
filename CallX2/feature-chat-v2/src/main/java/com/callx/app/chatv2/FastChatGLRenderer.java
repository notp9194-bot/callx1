package com.callx.app.chatv2;

import java.util.ArrayList;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import android.opengl.GLSurfaceView;

import com.callx.app.db.entity.MessageEntity;

/**
 * Bridges GLSurfaceView's callback thread to the native engine. All
 * heavy lifting (layout, culling, drawing) happens in C++ — this class
 * only marshals data across the JNI boundary.
 */
public class FastChatGLRenderer implements GLSurfaceView.Renderer {

    private final NativeChatEngine engine = new NativeChatEngine();
    private final BubbleTextureBuilder textureBuilder = new BubbleTextureBuilder();
    private String myUid;

    // Pending message list set from the main thread (LiveData observer),
    // applied on the GL thread on the next frame.
    private volatile List<MessageEntity> pendingMessages;

    public void setMyUid(String uid) {
        this.myUid = uid;
    }

    /** Safe to call from any thread. */
    public void submitMessages(List<MessageEntity> messages) {
        this.pendingMessages = messages;
    }

    public NativeChatEngine getEngine() {
        return engine;
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
        }
        engine.nativeDrawFrame();
    }

    private void applyMessages(List<MessageEntity> messages) {
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
            String text = m.text != null ? m.text : "";
            boolean mine = myUid != null && myUid.equals(m.senderId);

            BubbleTextureBuilder.BubbleTexture tex = textureBuilder.buildOrGet(m.id, text, mine);

            ids[i] = m.id;
            isMine[i] = mine;
            bubbleW[i] = tex.width;
            bubbleH[i] = tex.height;
            textureIds[i] = tex.textureId;
            texW[i] = tex.width;
            texH[i] = tex.height;
        }

        engine.nativeSetMessages(ids, isMine, bubbleW, bubbleH, textureIds, texW, texH);
    }

    public void destroy() {
        textureBuilder.clear();
        engine.nativeDestroy();
    }
}
