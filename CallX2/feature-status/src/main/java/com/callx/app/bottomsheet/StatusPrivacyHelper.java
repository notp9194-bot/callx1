package com.callx.app.bottomsheet;

import android.content.Context;
import java.util.List;
import java.util.Set;

/** Static convenience bridge for StatusPrivacyBottomSheet in non-fragment contexts. */
public final class StatusPrivacyHelper {
    public interface Callback {
        void onSelected(String mode, Set<String> except, Set<String> only, List<String> cfUids);
    }
    private StatusPrivacyHelper() {}
}
