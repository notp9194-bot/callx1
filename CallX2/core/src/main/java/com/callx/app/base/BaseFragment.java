package com.callx.app.base;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.callx.app.utils.FirebaseUtils;

/**
 * BaseFragment — common foundation for all CallX fragments.
 *
 * WhatsApp-level patterns:
 *   1. Auth guard — check on onViewCreated (UI-safe)
 *   2. ViewModel factory helper — standardized ViewModelProvider.Factory pattern
 *   3. Safe UI run — guard runOnUiThread-style post-detach crashes
 *
 * Feature fragments should extend this.
 */
public abstract class BaseFragment extends Fragment {

    protected boolean requiresAuth() { return true; }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (requiresAuth() && !isAuthenticated()) {
            // Fragment can't navigate to login directly; host activity handles it.
            // Just show empty state and stop further init.
            onNotAuthenticated();
        }
    }

    /** Override to handle the case where the user is not authenticated. */
    protected void onNotAuthenticated() {}

    // ── Helpers ────────────────────────────────────────────────────────────

    protected boolean isAuthenticated() {
        String uid = FirebaseUtils.getCurrentUid();
        return uid != null && !uid.isEmpty();
    }

    protected String myUid() {
        return FirebaseUtils.getCurrentUid();
    }

    protected String myName() {
        return FirebaseUtils.getCurrentName();
    }

    /**
     * Run code on the main thread only if the fragment is still attached.
     * Prevents "Fragment not attached to an activity" crashes from async callbacks.
     */
    protected void runSafely(Runnable action) {
        if (!isAdded() || getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (isAdded() && getActivity() != null) action.run();
        });
    }

    /**
     * Convenience helper to get a ViewModel scoped to this fragment's lifecycle.
     * Usage: MyViewModel vm = viewModel(MyViewModel.class);
     */
    protected <T extends androidx.lifecycle.ViewModel> T viewModel(Class<T> clazz) {
        return new ViewModelProvider(this).get(clazz);
    }

    /**
     * Convenience helper to get a ViewModel scoped to the parent activity.
     * Useful for sharing a ViewModel between sibling fragments.
     */
    protected <T extends androidx.lifecycle.ViewModel> T activityViewModel(Class<T> clazz) {
        if (getActivity() == null) throw new IllegalStateException("Fragment not attached");
        return new ViewModelProvider(getActivity()).get(clazz);
    }
}
