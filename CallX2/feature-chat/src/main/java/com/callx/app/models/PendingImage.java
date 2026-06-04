package com.callx.app.models;

/**
 * PendingImage — holds a URI string + caption for multi-image send.
 * Used in MultiImagePickerActivity before images are uploaded.
 */
public class PendingImage {
    public String uriString;  // content:// or file:// URI as string
    public String caption;    // per-image caption (can be empty)

    public PendingImage(String uriString, String caption) {
        this.uriString = uriString;
        this.caption   = caption != null ? caption : "";
    }
}
