#ifndef FASTCHAT_CHAT_RENDERER_H
#define FASTCHAT_CHAT_RENDERER_H

#include <GLES2/gl2.h>
#include "layout_engine.h"

namespace fastchat {

// Owns the GL program + issues the actual draw calls. All work here runs
// on the GLSurfaceView's dedicated GL thread (called from jni_bridge.cpp),
// never on the main/UI thread — that separation is the whole point of the
// native path: no ART/View invalidation cost between "data changed" and
// "pixels on screen".
class ChatRenderer {
public:
    bool init();
    void resize(int widthPx, int heightPx);
    // scrollY: current scroll offset in content px.
    void drawFrame(const LayoutEngine& layout, float scrollY);
    void destroy();

private:
    GLuint program_ = 0;
    GLuint bubbleVbo_ = 0;
    GLint aPosLoc_ = -1;
    GLint aUvLoc_ = -1;
    GLint uMvpLoc_ = -1;
    GLint uColorLoc_ = -1;
    GLint uRadiusLoc_ = -1;
    GLint uSizeLoc_ = -1;
    GLint uTexLoc_ = -1;
    GLint uUseTexLoc_ = -1;

    int viewportW_ = 0, viewportH_ = 0;

    GLuint compileShader(GLenum type, const char* src);
    void drawQuad(float x, float y, float w, float h, bool isMine,
                  GLuint textureId, bool hasTexture);
};

} // namespace fastchat

#endif // FASTCHAT_CHAT_RENDERER_H
