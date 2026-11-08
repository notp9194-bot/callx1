package com.callx.app.utils;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Objects;

/**
 * Replaces {@code notifyDataSetChanged()} on simple list screens: only rows that actually changed
 * rebind, inserts/removes animate, scroll position survives. The adapter's backing list must
 * already hold {@code next} when {@link #dispatch} is called ({@code old} is a snapshot taken before).
 */
public final class AdapterDiff {
    private AdapterDiff() {}

    public interface Fn<T> { Object apply(T t); }

    public static <T> void dispatch(@NonNull RecyclerView.Adapter<?> adapter, @NonNull List<T> old,
                                    @NonNull List<T> next, @NonNull Fn<T> key, @NonNull Fn<T> sig) {
        final DiffUtil.DiffResult r = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return old.size(); }
            @Override public int getNewListSize() { return next.size(); }
            @Override public boolean areItemsTheSame(int o, int n) {
                final Object a = key.apply(old.get(o));
                return a != null && Objects.equals(a, key.apply(next.get(n)));
            }
            @Override public boolean areContentsTheSame(int o, int n) {
                return Objects.equals(sig.apply(old.get(o)), sig.apply(next.get(n)));
            }
        });
        r.dispatchUpdatesTo(adapter);
    }
}
