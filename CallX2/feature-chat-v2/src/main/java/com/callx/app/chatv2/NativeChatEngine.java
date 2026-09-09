package com.callx.app.chatv2;

/**
 * Thin JNI wrapper around the C++ layout+GL renderer (see
 * src/main/cpp/). Every method here must only be called from the
 * GLSurfaceView's GL thread EXCEPT setMessages(), which is safe to call
 * from any thread — the native side guards the shared layout with a
 * mutex (see jni_bridge.cpp).
 */
public class NativeChatEngine {

    static {
        System.loadLibrary("fastchat_native");
    }

    public native void nativeInit();
    public native void nativeResize(int width, int height);
    public native void nativeDrawFrame();
    public native void nativeSetMessages(String[] ids, boolean[] isMine,
                                          float[] bubbleW, float[] bubbleH,
                                          int[] textureIds, float[] texW, float[] texH);
    public native void nativeSetScrollY(float scrollY);
    public native float nativeGetScrollY();
    public native float nativeGetContentHeight();
    public native int nativeHitTest(float x, float y);
    public native void nativeDestroy();
}
