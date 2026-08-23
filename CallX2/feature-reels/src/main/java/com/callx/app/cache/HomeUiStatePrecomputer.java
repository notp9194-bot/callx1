package com.callx.app.cache;

import com.callx.app.models.ReelModel;

import java.util.List;

/**
 * Home feed facade for the shared reel UI-state precompute pipeline.
 *
 * Home and the full-screen Reels tab must not each invent their own count and
 * caption formatting rules. This small lifecycle-owned facade gives Home its
 * own precompute boundary while writing to the same bounded cache used by
 * ReelPlayerFragment, so a reel warmed in either feed is immediately usable
 * by the other one.
 */
public final class HomeUiStatePrecomputer {
    private final ReelUiStatePrecomputer delegate = new ReelUiStatePrecomputer();

    public void precomputeFrom(List<ReelModel> reels, int position) {
        delegate.precomputeFrom(reels, position);
    }

    public void shutdown() {
        delegate.shutdown();
    }
}