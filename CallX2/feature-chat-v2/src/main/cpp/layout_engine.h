#ifndef FASTCHAT_LAYOUT_ENGINE_H
#define FASTCHAT_LAYOUT_ENGINE_H

#include <string>
#include <vector>
#include <cstdint>

namespace fastchat {

// avoid pulling GLES headers into this translation-unit-agnostic header
using GLuintCompat = unsigned int;

// One chat bubble's computed geometry + the GL texture holding its
// rasterized text (built on the Java side via StaticLayout, uploaded
// once per message, cached — see BubbleTextureBuilder.java).
struct BubbleLayout {
    std::string id;
    float x = 0.f, y = 0.f, w = 0.f, h = 0.f;
    bool isMine = false;
    GLuintCompat textureId = 0;
    float texW = 0.f, texH = 0.f;
    // Read-receipt tick icon (mine-only) — 0 means "don't draw a tick"
    // (message still pending, or not mine). Built once per status value
    // on the Java side (TickTextureBuilder) and shared across every
    // bubble in that state, so this is cheap even for long histories.
    GLuintCompat tickTextureId = 0;
    float tickW = 0.f, tickH = 0.f;
};

// Computes bubble positions top-to-bottom (message list is chronological,
// oldest first) and answers "which bubbles are visible" for a given
// scroll offset + viewport height — this is the viewport-culling step
// that keeps draw calls bounded regardless of conversation length,
// mirroring what MessagePagingAdapter's item recycling does on the
// Java/Canvas side, just done here in native for the GL path.
class LayoutEngine {
public:
    void clear();
    void addMessage(const std::string& id, bool isMine,
                     float bubbleW, float bubbleH,
                     unsigned int textureId, float texW, float texH,
                     unsigned int tickTextureId = 0, float tickW = 0.f, float tickH = 0.f);

    // Total scrollable content height once all messages are laid out.
    float contentHeight() const { return contentHeight_; }

    // Fills `outVisible` with bubbles whose vertical extent intersects
    // [scrollY, scrollY + viewportH]. Cheap linear scan — fine up to
    // several thousand messages; a future pass can swap in an interval
    // index if that ever shows up in profiling.
    void visibleBubbles(float scrollY, float viewportH,
                         std::vector<const BubbleLayout*>& outVisible) const;

    // Hit test: returns index of bubble under (x, contentY), or -1.
    int hitTest(float x, float contentY) const;

    // Bubble at a given index (as returned by hitTest), or nullptr if
    // out of range — lets jni_bridge resolve a touch to a message id
    // for long-press / tap handling without exposing the vector itself.
    const BubbleLayout* bubbleAt(int index) const;

    void setViewportWidth(float w) { viewportWidth_ = w; }

private:
    std::vector<BubbleLayout> bubbles_;
    float contentHeight_ = 0.f;
    float viewportWidth_ = 1080.f;
    static constexpr float kBubbleMarginV = 12.f;
};

} // namespace fastchat

#endif // FASTCHAT_LAYOUT_ENGINE_H
