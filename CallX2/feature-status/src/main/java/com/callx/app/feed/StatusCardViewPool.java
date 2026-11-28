package com.callx.app.feed;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import androidx.asynclayoutinflater.view.AsyncLayoutInflater;

import com.callx.app.status.R;

import java.util.ArrayDeque;

/**
 * v952 — process-lifetime stock of pre-inflated Status carousel cards ({@code item_status_card}).
 *
 * WHY: the carousel's RecyclerView (and its RecycledViewPool) is rebuilt every time ViewPager2 re-creates the Status fragment,
 * so the first screen of cards was always inflated synchronously inside the first layout pass. The old per-adapter
 * pre-inflate was started from that same pass, so its results only arrived after the cards were already needed.
 * This pool is filled early (MainActivity idle, and again as soon as a Status fragment view is destroyed), off the main thread.
 *
 * Context rule: a pooled view is only handed out to a RecyclerView whose context is the very same instance it was inflated
 * with (theme-safe); anything else is dropped and the caller inflates synchronously as before. {@link #release()} (MainActivity.onDestroy)
 * drops every reference so no Activity is leaked. Main thread only.
 */
public final class StatusCardViewPool {
    private StatusCardViewPool() {}

    private static final int CAP = 8;
    private static final ArrayDeque<View> POOL = new ArrayDeque<>();
    private static Context sOwner;     // context the pooled views (and in-flight inflations) belong to
    private static int sPending;       // async inflations still running for sOwner

    /** Tops the pool up to {@code want} (max {@link #CAP}) cards for {@code ctx}. Cheap no-op when already stocked. */
    public static void warm(Context ctx, int want) {
        if (ctx == null) return;
        if (sOwner != ctx) { POOL.clear(); sPending = 0; sOwner = ctx; }
        final int target = Math.min(want, CAP);
        final int need = target - POOL.size() - sPending;
        if (need <= 0) return;
        try {
            final AsyncLayoutInflater ali = new AsyncLayoutInflater(ctx);
            final FrameLayout holder = new FrameLayout(ctx);   // only supplies LayoutParams (card width/height from the XML)
            for (int i = 0; i < need; i++) {
                sPending++;
                final Context owner = ctx;
                ali.inflate(R.layout.item_status_card, holder, (view, resid, parent) -> {
                    if (sOwner != owner) return;               // released / switched activity while inflating
                    sPending = Math.max(0, sPending - 1);
                    if (POOL.size() < CAP) POOL.add(view);
                });
            }
        } catch (Throwable ignored) { sPending = 0; /* falls back to sync inflate */ }
    }

    /** A ready card for a RecyclerView living in {@code ctx}, or null. */
    static View take(Context ctx) {
        if (sOwner != ctx) return null;
        View v = POOL.poll();
        return (v != null && v.getParent() == null) ? v : null;
    }

    /** Drops everything (Activity gone). */
    public static void release() {
        POOL.clear();
        sPending = 0;
        sOwner = null;
    }
}
