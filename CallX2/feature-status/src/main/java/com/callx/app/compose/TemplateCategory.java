package com.callx.app.compose;

import androidx.annotation.Nullable;

import org.json.JSONObject;

/**
 * v765: category tag of a template ({@code templateJson.cat}). Poster picks it in the "Template options" sheet; the Templates
 * home screen filters its Categories row by it. Old templates have no tag (index -1) and simply never match a category.
 */
public final class TemplateCategory {
    private TemplateCategory() { }

    /** Stored in templateJson as "cat". Order = order of the Categories row. */
    public static final String[] KEYS = {"love", "travel", "birthday", "festival", "aesthetic"};

    /** @return index into {@link #KEYS}, or -1 when the template has no (or an unknown) tag. */
    public static int indexOf(@Nullable String templateJson) {
        if (templateJson == null || templateJson.isEmpty()) return -1;
        try {
            final String k = new JSONObject(templateJson).optString("cat", "");
            for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(k)) return i;
        } catch (Exception ignored) { }
        return -1;
    }
}
