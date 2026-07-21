package com.callx.app.utils;

/**
 * Marker interface for activities that are SUPPOSED to finish() themselves
 * without ever reaching onResume() — splash/redirect/trampoline screens
 * (e.g. AuthActivity redirecting straight to MainActivity when already
 * logged in). SilentFinishWatcher treats this as a normal, expected flow
 * and will not report it as a silent bug.
 *
 * Usage: just implement this on the Activity class, no methods to override.
 *   public class AuthActivity extends AppCompatActivity implements ExpectedAutoFinish { ... }
 */
public interface ExpectedAutoFinish {
}
