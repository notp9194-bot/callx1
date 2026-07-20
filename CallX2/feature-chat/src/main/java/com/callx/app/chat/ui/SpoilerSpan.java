package com.callx.app.chat.ui;

import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.view.View;

/**
 * SpoilerSpan — hides text until the user taps it (Telegram-style spoiler).
 *
 * Hidden state: text color = background color → text invisible under block.
 * Background is painted with a solid color matching the text color, so the
 * spoiler renders as a solid pill that hides the text underneath.
 *
 * Revealed state: background transparent, text normal color.
 *
 * Usage:
 *   SpannableString s = SpoilerTextHelper.apply(text, revealedSet, () -> tv.invalidate());
 *   tv.setText(s);
 *   tv.setMovementMethod(LinkMovementMethod.getInstance());
 *
 * Tap triggers onClick → sets revealed = true → calls onReveal callback
 * (which should call textView.invalidate() or rebind the ViewHolder).
 */
public class SpoilerSpan extends ClickableSpan {

    private boolean revealed = false;
    private final Runnable onReveal;

    public SpoilerSpan(Runnable onReveal) {
        this.onReveal = onReveal;
    }

    /** Call to programmatically reveal this span (e.g., restore state). */
    public void setRevealed(boolean revealed) {
        this.revealed = revealed;
    }

    public boolean isRevealed() {
        return revealed;
    }

    @Override
    public void onClick(View widget) {
        if (!revealed) {
            revealed = true;
            if (widget != null) widget.invalidate();
            if (onReveal != null) onReveal.run();
        }
    }

    @Override
    public void updateDrawState(TextPaint ds) {
        if (revealed) {
            // Revealed: transparent bg, normal link-style text color
            ds.bgColor = 0x00000000;
            // Keep text color as-is (no underline)
        } else {
            // Hidden: set bg to text color so text is invisible under the block
            int textColor = ds.getColor();
            // Slightly desaturate the block color for a clean look
            ds.bgColor = (textColor & 0x00FFFFFF) | 0xFF000000;
            // Make text same color as bg → invisible
            ds.setColor(ds.bgColor);
        }
        ds.setUnderlineText(false);
    }
}
