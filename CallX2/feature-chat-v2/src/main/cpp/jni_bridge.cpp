#include <jni.h>
#include <memory>
#include <mutex>
#include "chat_renderer.h"
#include "layout_engine.h"

using fastchat::ChatRenderer;
using fastchat::LayoutEngine;

namespace {
// One engine instance per FastChatSurfaceView. Guarded because
// setMessages() is called from the main thread (LiveData observer)
// while drawFrame()/onTouch() run on the GL thread.
std::unique_ptr<ChatRenderer> gRenderer;
std::unique_ptr<LayoutEngine> gLayout;
std::mutex gLayoutMutex;
float gScrollY = 0.f;
float gContentHeight = 0.f;
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_callx_app_chatv2_NativeChatEngine_nativeInit(JNIEnv*, jobject) {
    gRenderer = std::make_unique<ChatRenderer>();
    gRenderer->init();
    gLayout = std::make_unique<LayoutEngine>();
}

JNIEXPORT void JNICALL
Java_com_callx_app_chatv2_NativeChatEngine_nativeResize(JNIEnv*, jobject, jint w, jint h) {
    if (gRenderer) gRenderer->resize(w, h);
    if (gLayout) gLayout->setViewportWidth(static_cast<float>(w));
}

JNIEXPORT void JNICALL
Java_com_callx_app_chatv2_NativeChatEngine_nativeDrawFrame(JNIEnv*, jobject) {
    if (!gRenderer || !gLayout) return;
    std::lock_guard<std::mutex> lock(gLayoutMutex);
    gRenderer->drawFrame(*gLayout, gScrollY);
}

// ids/texts: parallel arrays built on the Java side from MessageEntity
// rows (text already rasterized into `textureIds` via BubbleTextureBuilder
// — see that class for why text upload stays on Java/Canvas rather than
// a vendored font-shaping stack).
JNIEXPORT void JNICALL
Java_com_callx_app_chatv2_NativeChatEngine_nativeSetMessages(
        JNIEnv* env, jobject,
        jobjectArray ids, jbooleanArray isMineArr,
        jfloatArray bubbleW, jfloatArray bubbleH,
        jintArray textureIds, jfloatArray texW, jfloatArray texH,
        jintArray tickTextureIds, jfloatArray tickW, jfloatArray tickH) {
    if (!gLayout) return;

    jsize count = env->GetArrayLength(ids);
    jboolean* mineArr = env->GetBooleanArrayElements(isMineArr, nullptr);
    jfloat* wArr = env->GetFloatArrayElements(bubbleW, nullptr);
    jfloat* hArr = env->GetFloatArrayElements(bubbleH, nullptr);
    jint* texArr = env->GetIntArrayElements(textureIds, nullptr);
    jfloat* twArr = env->GetFloatArrayElements(texW, nullptr);
    jfloat* thArr = env->GetFloatArrayElements(texH, nullptr);
    jint* tickTexArr = env->GetIntArrayElements(tickTextureIds, nullptr);
    jfloat* tickWArr = env->GetFloatArrayElements(tickW, nullptr);
    jfloat* tickHArr = env->GetFloatArrayElements(tickH, nullptr);

    {
        std::lock_guard<std::mutex> lock(gLayoutMutex);
        gLayout->clear();
        for (jsize i = 0; i < count; ++i) {
            auto jid = (jstring) env->GetObjectArrayElement(ids, i);
            const char* idChars = env->GetStringUTFChars(jid, nullptr);
            gLayout->addMessage(std::string(idChars), mineArr[i] != 0,
                                 wArr[i], hArr[i],
                                 static_cast<unsigned int>(texArr[i]),
                                 twArr[i], thArr[i],
                                 static_cast<unsigned int>(tickTexArr[i]),
                                 tickWArr[i], tickHArr[i]);
            env->ReleaseStringUTFChars(jid, idChars);
            env->DeleteLocalRef(jid);
        }
        gContentHeight = gLayout->contentHeight();
    }

    env->ReleaseBooleanArrayElements(isMineArr, mineArr, JNI_ABORT);
    env->ReleaseFloatArrayElements(bubbleW, wArr, JNI_ABORT);
    env->ReleaseFloatArrayElements(bubbleH, hArr, JNI_ABORT);
    env->ReleaseIntArrayElements(textureIds, texArr, JNI_ABORT);
    env->ReleaseFloatArrayElements(texW, twArr, JNI_ABORT);
    env->ReleaseFloatArrayElements(texH, thArr, JNI_ABORT);
    env->ReleaseIntArrayElements(tickTextureIds, tickTexArr, JNI_ABORT);
    env->ReleaseFloatArrayElements(tickW, tickWArr, JNI_ABORT);
    env->ReleaseFloatArrayElements(tickH, tickHArr, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_callx_app_chatv2_NativeChatEngine_nativeSetScrollY(JNIEnv*, jobject, jfloat scrollY) {
    float maxScroll = gContentHeight;
    if (scrollY < 0) scrollY = 0;
    if (scrollY > maxScroll) scrollY = maxScroll;
    gScrollY = scrollY;
}

JNIEXPORT jfloat JNICALL
Java_com_callx_app_chatv2_NativeChatEngine_nativeGetScrollY(JNIEnv*, jobject) {
    return gScrollY;
}

JNIEXPORT jfloat JNICALL
Java_com_callx_app_chatv2_NativeChatEngine_nativeGetContentHeight(JNIEnv*, jobject) {
    return gContentHeight;
}

JNIEXPORT jint JNICALL
Java_com_callx_app_chatv2_NativeChatEngine_nativeHitTest(JNIEnv*, jobject, jfloat x, jfloat y) {
    if (!gLayout) return -1;
    std::lock_guard<std::mutex> lock(gLayoutMutex);
    return gLayout->hitTest(x, y + gScrollY);
}

// Same hit test as above, but resolves straight to the message id —
// used by long-press (reaction picker) and tap handling on the Java
// side, which only ever need the id, never the raw bubble index.
JNIEXPORT jstring JNICALL
Java_com_callx_app_chatv2_NativeChatEngine_nativeHitTestId(JNIEnv* env, jobject, jfloat x, jfloat y) {
    if (!gLayout) return nullptr;
    std::lock_guard<std::mutex> lock(gLayoutMutex);
    int idx = gLayout->hitTest(x, y + gScrollY);
    const fastchat::BubbleLayout* b = gLayout->bubbleAt(idx);
    if (!b) return nullptr;
    return env->NewStringUTF(b->id.c_str());
}

JNIEXPORT void JNICALL
Java_com_callx_app_chatv2_NativeChatEngine_nativeDestroy(JNIEnv*, jobject) {
    if (gRenderer) gRenderer->destroy();
    gRenderer.reset();
    gLayout.reset();
}

} // extern "C"
