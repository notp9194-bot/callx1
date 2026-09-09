#include "layout_engine.h"

namespace fastchat {

void LayoutEngine::clear() {
    bubbles_.clear();
    contentHeight_ = 0.f;
}

void LayoutEngine::addMessage(const std::string& id, bool isMine,
                               float bubbleW, float bubbleH,
                               unsigned int textureId, float texW, float texH) {
    BubbleLayout b;
    b.id = id;
    b.isMine = isMine;
    b.w = bubbleW;
    b.h = bubbleH;
    b.textureId = textureId;
    b.texW = texW;
    b.texH = texH;

    // Right-align "mine" bubbles, left-align partner's — same convention
    // as MessageBubbleCanvasView's layout on the existing chat screen.
    float sidePadding = 24.f;
    b.x = isMine ? (viewportWidth_ - sidePadding - bubbleW) : sidePadding;
    b.y = contentHeight_;

    contentHeight_ += bubbleH + kBubbleMarginV;
    bubbles_.push_back(std::move(b));
}

void LayoutEngine::visibleBubbles(float scrollY, float viewportH,
                                   std::vector<const BubbleLayout*>& outVisible) const {
    outVisible.clear();
    float top = scrollY;
    float bottom = scrollY + viewportH;
    for (const auto& b : bubbles_) {
        float bTop = b.y;
        float bBottom = b.y + b.h;
        if (bBottom >= top && bTop <= bottom) {
            outVisible.push_back(&b);
        }
    }
}

int LayoutEngine::hitTest(float x, float contentY) const {
    for (size_t i = 0; i < bubbles_.size(); ++i) {
        const auto& b = bubbles_[i];
        if (x >= b.x && x <= b.x + b.w &&
            contentY >= b.y && contentY <= b.y + b.h) {
            return static_cast<int>(i);
        }
    }
    return -1;
}

} // namespace fastchat
