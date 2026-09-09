#include "chat_renderer.h"
#include <android/log.h>
#include <vector>

#define LOG_TAG "FastChatRenderer"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace fastchat {

namespace {

// Unit quad; per-bubble transform + size go through uMvp/uSize uniforms
// instead of rebuilding vertex data per bubble — keeps this one draw
// call's setup cost near-zero, the GPU does the rounded-corner mask in
// the fragment shader (uSize + uRadius, signed-distance rounded-box)
// rather than us tessellating a rounded polygon on the CPU per frame.
const float kQuadVerts[] = {
    // x, y,   u, v
    0.f, 0.f,  0.f, 0.f,
    1.f, 0.f,  1.f, 0.f,
    0.f, 1.f,  0.f, 1.f,
    1.f, 1.f,  1.f, 1.f,
};

const char* kVertexSrc = R"(
attribute vec2 aPos;
attribute vec2 aUv;
uniform mat4 uMvp;
uniform vec2 uSize;
varying vec2 vUv;
varying vec2 vLocalPx;
void main() {
    vLocalPx = aPos * uSize;
    vUv = aUv;
    gl_Position = uMvp * vec4(aPos * uSize, 0.0, 1.0);
}
)";

// Rounded-rect mask via signed distance field — cheap, resolution
// independent, no per-bubble geometry tessellation needed.
const char* kFragmentSrc = R"(
precision mediump float;
varying vec2 vUv;
varying vec2 vLocalPx;
uniform vec2 uSize;
uniform vec4 uColor;
uniform float uRadius;
uniform sampler2D uTex;
uniform float uUseTex;
float roundedBoxSDF(vec2 p, vec2 halfSize, float r) {
    vec2 q = abs(p - halfSize) - halfSize + r;
    return length(max(q, 0.0)) - r;
}
void main() {
    float d = roundedBoxSDF(vLocalPx, uSize * 0.5, uRadius);
    float alpha = 1.0 - smoothstep(-1.5, 1.5, d);
    vec4 base = uColor;
    if (uUseTex > 0.5) {
        vec4 tex = texture2D(uTex, vUv);
        base = mix(uColor, tex, tex.a);
    }
    gl_FragColor = vec4(base.rgb, base.a * alpha);
}
)";

} // namespace

GLuint ChatRenderer::compileShader(GLenum type, const char* src) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &src, nullptr);
    glCompileShader(shader);
    GLint status = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &status);
    if (!status) {
        char log[512];
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        LOGE("shader compile failed: %s", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

bool ChatRenderer::init() {
    GLuint vs = compileShader(GL_VERTEX_SHADER, kVertexSrc);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, kFragmentSrc);
    if (!vs || !fs) return false;

    program_ = glCreateProgram();
    glAttachShader(program_, vs);
    glAttachShader(program_, fs);
    glLinkProgram(program_);
    GLint linked = 0;
    glGetProgramiv(program_, GL_LINK_STATUS, &linked);
    if (!linked) {
        char log[512];
        glGetProgramInfoLog(program_, sizeof(log), nullptr, log);
        LOGE("program link failed: %s", log);
        return false;
    }
    glDeleteShader(vs);
    glDeleteShader(fs);

    aPosLoc_    = glGetAttribLocation(program_, "aPos");
    aUvLoc_     = glGetAttribLocation(program_, "aUv");
    uMvpLoc_    = glGetUniformLocation(program_, "uMvp");
    uColorLoc_  = glGetUniformLocation(program_, "uColor");
    uRadiusLoc_ = glGetUniformLocation(program_, "uRadius");
    uSizeLoc_   = glGetUniformLocation(program_, "uSize");
    uTexLoc_    = glGetUniformLocation(program_, "uTex");
    uUseTexLoc_ = glGetUniformLocation(program_, "uUseTex");

    glGenBuffers(1, &bubbleVbo_);
    glBindBuffer(GL_ARRAY_BUFFER, bubbleVbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(kQuadVerts), kQuadVerts, GL_STATIC_DRAW);

    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    return true;
}

void ChatRenderer::resize(int widthPx, int heightPx) {
    viewportW_ = widthPx;
    viewportH_ = heightPx;
    glViewport(0, 0, widthPx, heightPx);
}

void ChatRenderer::drawQuad(float x, float y, float w, float h, bool isMine,
                             GLuint textureId, bool hasTexture) {
    // Orthographic projection: content px -> NDC. Column-major mat4.
    float sx = 2.f / static_cast<float>(viewportW_);
    float sy = -2.f / static_cast<float>(viewportH_);
    float mvp[16] = {
        sx, 0, 0, 0,
        0, sy, 0, 0,
        0, 0, 1, 0,
        -1.f + x * sx, 1.f + y * sy, 0, 1,
    };
    glUniformMatrix4fv(uMvpLoc_, 1, GL_FALSE, mvp);
    glUniform2f(uSizeLoc_, w, h);
    glUniform1f(uRadiusLoc_, 18.f);

    if (isMine) {
        glUniform4f(uColorLoc_, 0.14f, 0.55f, 0.98f, 1.0f); // outgoing blue
    } else {
        glUniform4f(uColorLoc_, 0.18f, 0.18f, 0.20f, 1.0f); // incoming gray
    }

    if (hasTexture && textureId != 0) {
        glUniform1f(uUseTexLoc_, 1.0f);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureId);
        glUniform1i(uTexLoc_, 0);
    } else {
        glUniform1f(uUseTexLoc_, 0.0f);
    }

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

void ChatRenderer::drawFrame(const LayoutEngine& layout, float scrollY) {
    glClearColor(0.05f, 0.05f, 0.06f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    if (program_ == 0) return;
    glUseProgram(program_);

    glBindBuffer(GL_ARRAY_BUFFER, bubbleVbo_);
    glEnableVertexAttribArray(aPosLoc_);
    glVertexAttribPointer(aPosLoc_, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)0);
    glEnableVertexAttribArray(aUvLoc_);
    glVertexAttribPointer(aUvLoc_, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)(2 * sizeof(float)));

    // Viewport culling: only the bubbles actually on screen get a draw
    // call, regardless of how long the conversation is — this is the
    // native-side equivalent of RecyclerView item recycling.
    std::vector<const BubbleLayout*> visible;
    layout.visibleBubbles(scrollY, static_cast<float>(viewportH_), visible);

    for (const auto* b : visible) {
        drawQuad(b->x, b->y - scrollY, b->w, b->h, b->isMine,
                 b->textureId, b->textureId != 0);
    }

    glDisableVertexAttribArray(aPosLoc_);
    glDisableVertexAttribArray(aUvLoc_);
}

void ChatRenderer::destroy() {
    if (bubbleVbo_) glDeleteBuffers(1, &bubbleVbo_);
    if (program_) glDeleteProgram(program_);
    bubbleVbo_ = 0;
    program_ = 0;
}

} // namespace fastchat
