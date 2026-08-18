package com.callx.app.channel.canvas;

/**
 * Click/interaction callbacks fired by ChannelPostCanvasView touch hit-tests.
 * The adapter or Activity implements this and wires it via
 * ChannelPostCanvasView.setOnPostClickListener().
 */
public interface OnPostClickListener {
    void onPostClick();
    void onPostLongClick();
    void onAuthorClick();
    void onMediaClick();
    void onMediaGroupCellClick(int cellIndex);
    void onMediaGroupOverflowClick();
    void onPollOptionClick(int optionIndex);
    void onMentionClick(String mention);
    void onReactionsClick();
    void onReactClick();
    void onForwardClick();
    void onReplyClick();
    void onLinkClick(String url);
    void onOptionsClick();
    void onRsvpClick(String status);   // "going" | "maybe" | "not_going"
}
