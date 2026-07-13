package com.callx.app.conversation.controllers;

import android.net.Uri;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ordered multi-select set backing the attach sheet's "Recents" grid
 * (recents_grid, camera tile + recent thumbnails, visible from the
 * collapsed peek state onward) — handed to RecentMediaGridAdapter by
 * AttachSheetRecentMediaBinder, WhatsApp/Telegram numbered-badge style,
 * instead of the old "tap = upload immediately, sheet closes" behavior.
 *
 * Keyed by Uri (content:// ids are stable per MediaStore row) rather than
 * list position, since the grid pages in more items as the user scrolls.
 */
public final class MediaSelectionState {

    public interface Listener {
        void onSelectionChanged();
    }

    /**
     * Fired with the exact uri whose selection state flipped, instead of a
     * blanket "something changed". Adapters use this to notifyItemChanged()
     * a single position instead of notifyDataSetChanged()-ing the whole
     * strip/grid (which was re-triggering a fresh Glide load for every
     * bound cell on every single tap — the main jank source in this sheet).
     */
    public interface ToggleListener {
        void onItemToggled(Uri uri);
    }

    private final Map<Uri, RecentMediaLoader.Item> selected = new LinkedHashMap<>();
    private final List<Listener> listeners = new ArrayList<>();
    private final List<ToggleListener> toggleListeners = new ArrayList<>();

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void addToggleListener(ToggleListener l) {
        toggleListeners.add(l);
    }

    /** Flips selection for this item. @return true if it's now selected. */
    public boolean toggle(RecentMediaLoader.Item item) {
        boolean nowSelected;
        if (selected.containsKey(item.uri)) {
            selected.remove(item.uri);
            nowSelected = false;
        } else {
            selected.put(item.uri, item);
            nowSelected = true;
        }
        notifyChanged();
        notifyToggled(item.uri);
        return nowSelected;
    }

    public boolean isSelected(Uri uri) {
        return selected.containsKey(uri);
    }

    /** 1-based order this uri was selected in (for the numbered badge), or 0 if not selected. */
    public int orderOf(Uri uri) {
        int i = 1;
        for (Uri u : selected.keySet()) {
            if (u.equals(uri)) return i;
            i++;
        }
        return 0;
    }

    public int size() {
        return selected.size();
    }

    public boolean isEmpty() {
        return selected.isEmpty();
    }

    /** Selected items, in the order they were tapped. */
    public List<RecentMediaLoader.Item> items() {
        return new ArrayList<>(selected.values());
    }

    public void clear() {
        if (selected.isEmpty()) return;
        List<Uri> previouslySelected = new ArrayList<>(selected.keySet());
        selected.clear();
        notifyChanged();
        for (Uri u : previouslySelected) notifyToggled(u);
    }

    private void notifyChanged() {
        for (Listener l : listeners) l.onSelectionChanged();
    }

    private void notifyToggled(Uri uri) {
        for (ToggleListener l : toggleListeners) l.onItemToggled(uri);
    }
}
