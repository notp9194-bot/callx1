package com.callx.app.models;

/**
 * @deprecated Use {@link XProfile} instead.
 *
 * XUser has been replaced by the new XProfile system.
 * This class is kept temporarily for any legacy references
 * that haven't been migrated yet. Do NOT add new code here.
 *
 * Migration:
 *   - XUser → XProfile
 *   - Direct Firebase reads → XProfileManager.load() / XProfileManager.observe()
 *   - Direct Firebase writes → XProfileManager.save() / updateAvatar() / updateBanner()
 */
@Deprecated
public class XUser extends XProfile {
    // All fields now live in XProfile.
    // XUser extends XProfile for backward compatibility only.
    // Remove this class entirely once all references are migrated.
}
