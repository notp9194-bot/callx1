package com.callx.app.duet;

/**
 * DuetLayoutMode — Supported side-by-side layout options.
 *
 * TOP_BOTTOM  — original on top, camera on bottom (default)
 * LEFT_RIGHT  — original on left, camera on right
 * PIP         — camera as small overlay (picture-in-picture), original fullscreen
 * REACT       — camera fullscreen, original plays as small corner PiP
 */
public enum DuetLayoutMode {
    TOP_BOTTOM,
    LEFT_RIGHT,
    PIP,
    REACT;

    public String label() {
        switch (this) {
            case TOP_BOTTOM: return "Top & Bottom";
            case LEFT_RIGHT: return "Side by Side";
            case PIP:        return "Original Focus";
            case REACT:      return "React";
            default:         return "Split";
        }
    }

    public static DuetLayoutMode[] all() {
        return new DuetLayoutMode[]{TOP_BOTTOM, LEFT_RIGHT, PIP, REACT};
    }
}
